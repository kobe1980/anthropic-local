import com.sun.net.httpserver.Headers
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Instant
import kotlin.math.ceil

fun main(args: Array<String>) {
    val debug = args.contains("--debug")

    val baseModel: LocalModel = CliLocalModel(
        modelPath = MODEL_PATH,
        binPath = LITERT_BIN,
        libDir = LITERT_LIB_DIR,
        backend = LITERT_BACKEND
    )

    val model: LocalModel = if (debug) {
        DebugLocalModel(baseModel)
    } else {
        baseModel
    }

    val server = HttpServer.create(InetSocketAddress("127.0.0.1", SERVER_PORT), 0)

    server.createContext("/health", HttpHandler { exchange ->
        handleHealth(exchange, model, debug)
    })

    server.createContext("/v1/messages", HttpHandler { exchange ->
        handleMessages(exchange, model, debug)
    })

    server.executor = null
    server.start()

    println("Server started on http://127.0.0.1:$SERVER_PORT")
    println("Using model path: $MODEL_PATH")
    println("Model implementation: ${model::class.simpleName}")
    println("Debug mode: $debug")
}

private fun handleHealth(exchange: HttpExchange, model: LocalModel, debug: Boolean) {
    if (debug) {
        logRequest(exchange, body = null)
    }

    if (exchange.requestMethod != "GET") {
        sendPlain(exchange, 405, "method_not_allowed")
        return
    }

    val body = """
        {
          "status": "ok",
          "engine_ready": ${model.isReady()}
        }
    """.trimIndent()

    if (debug) {
        println("[${timestamp()}] [http] response /health:")
        println(body)
    }

    sendJson(exchange, 200, body)
}

private fun handleMessages(exchange: HttpExchange, model: LocalModel, debug: Boolean) {
    if (exchange.requestMethod != "POST") {
        sendPlain(exchange, 405, "method_not_allowed")
        return
    }

    val requestBody = exchange.requestBody.bufferedReader().use { it.readText() }

    if (debug) {
        logRequest(exchange, requestBody)
    }

    try {
        val requestedModel = extractTopLevelString(requestBody, "model") ?: "local-lite"
        val rawSystem = extractSystemText(requestBody)
        val outputFormatInstruction = extractOutputFormatInstruction(requestBody)
        val effectiveSystem = listOfNotNull(
            rawSystem?.takeIf { it.isNotBlank() },
            outputFormatInstruction?.takeIf { it.isNotBlank() }
        ).joinToString("\n\n").takeIf { it.isNotBlank() }

        val messages = extractMessages(requestBody)
        if (messages.isEmpty()) {
            sendAnthropicError(
                exchange = exchange,
                statusCode = 400,
                errorType = "invalid_request_error",
                message = "No usable message content found in request.",
                debug = debug
            )
            return
        }

        val maxTokens = extractTopLevelInt(requestBody, "max_tokens") ?: 512
        val temperature = extractTopLevelDouble(requestBody, "temperature") ?: 0.2
        val prompt = buildPrompt(effectiveSystem, messages)

        if (debug) {
            println("[${timestamp()}] [http] parsed request:")
            println("  model=$requestedModel")
            println("  raw system length=${rawSystem?.length ?: 0}")
            println("  output format instruction length=${outputFormatInstruction?.length ?: 0}")
            println("  effective system length=${effectiveSystem?.length ?: 0}")
            println("  messages count=${messages.size}")
            println("  max_tokens=$maxTokens")
            println("  temperature=$temperature")
            println("  prompt chars=${prompt.length}")
            println("[${timestamp()}] [litert] prompt:")
            println(prompt)
        }

        val answer = model.generate(
            prompt = prompt,
            maxTokens = maxTokens,
            temperature = temperature
        )

        val inputTokens = estimateTokens(prompt)
        val outputTokens = estimateTokens(answer)
        val responseId = "msg_${System.currentTimeMillis()}"

        val response = """
            {
              "id": ${jsonString(responseId)},
              "type": "message",
              "role": "assistant",
              "model": ${jsonString(requestedModel)},
              "content": [
                {
                  "type": "text",
                  "text": ${jsonString(answer)}
                }
              ],
              "stop_reason": "end_turn",
              "stop_sequence": null,
              "usage": {
                "input_tokens": $inputTokens,
                "output_tokens": $outputTokens
              }
            }
        """.trimIndent()

        if (debug) {
            println("[${timestamp()}] [http] response /v1/messages:")
            println(response)
        }

        sendJson(exchange, 200, response)
    } catch (e: IllegalArgumentException) {
        sendAnthropicError(
            exchange = exchange,
            statusCode = 400,
            errorType = "invalid_request_error",
            message = e.message ?: "invalid request",
            debug = debug
        )
    } catch (e: Exception) {
        e.printStackTrace()
        sendAnthropicError(
            exchange = exchange,
            statusCode = 500,
            errorType = "api_error",
            message = e.message ?: "unknown",
            debug = debug
        )
    }
}

private fun sendAnthropicError(
    exchange: HttpExchange,
    statusCode: Int,
    errorType: String,
    message: String,
    debug: Boolean
) {
    val body = """
        {
          "type": "error",
          "error": {
            "type": ${jsonString(errorType)},
            "message": ${jsonString(message)}
          }
        }
    """.trimIndent()

    if (debug) {
        println("[${timestamp()}] [http] error response /v1/messages:")
        println(body)
    }

    sendJson(exchange, statusCode, body)
}

private class DebugLocalModel(
    private val delegate: LocalModel
) : LocalModel {

    override fun isReady(): Boolean {
        val ready = delegate.isReady()
        println("[${timestamp()}] [litert] isReady=$ready")
        return ready
    }

    override fun generate(prompt: String, maxTokens: Int, temperature: Double): String {
        val boundedMaxTokens = maxTokens.coerceIn(1, 1024)
        val boundedTemperature = temperature.coerceIn(0.0, 2.0)

        val command = listOf(
            LITERT_BIN,
            "--backend=$LITERT_BACKEND",
            "--model_path=$MODEL_PATH",
            "--input_prompt=$prompt"
        )

        println("[${timestamp()}] [litert] command:")
        command.forEachIndexed { index, arg ->
            if (index == command.lastIndex) {
                println("  $arg")
            } else {
                println("  $arg \\")
            }
        }

        println("[${timestamp()}] [litert] generation params:")
        println("  requested maxTokens=$maxTokens")
        println("  requested temperature=$temperature")
        println("  bounded maxTokens=$boundedMaxTokens")
        println("  bounded temperature=$boundedTemperature")

        val answer = delegate.generate(prompt, maxTokens, temperature)

        println("[${timestamp()}] [litert] raw answer:")
        println(answer)

        return answer
    }
}

private fun estimateTokens(text: String): Int {
    if (text.isBlank()) return 1
    return ceil(text.length / 4.0).toInt().coerceAtLeast(1)
}

private fun logRequest(exchange: HttpExchange, body: String?) {
    println("[${timestamp()}] [http] incoming request:")
    println("  method=${exchange.requestMethod}")
    println("  path=${exchange.requestURI.path}")
    println("  query=${exchange.requestURI.rawQuery ?: ""}")
    println("  remote=${exchange.remoteAddress}")
    println("  headers:")
    printHeaders(exchange.requestHeaders)
    if (body != null) {
        println("  body:")
        println(body)
    }
}

private fun printHeaders(headers: Headers) {
    for ((key, values) in headers) {
        val renderedValue = if (
            key.equals("x-api-key", ignoreCase = true) ||
            key.equals("authorization", ignoreCase = true)
        ) {
            "***redacted***"
        } else {
            values.joinToString(", ")
        }
        println("    $key: $renderedValue")
    }
}

private fun timestamp(): String = Instant.now().toString()

private fun sendPlain(exchange: HttpExchange, statusCode: Int, body: String) {
    val bytes = body.toByteArray(Charsets.UTF_8)
    exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
    exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
}
