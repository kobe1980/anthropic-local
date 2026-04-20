import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

fun main() {
    val model: LocalModel = CliLocalModel(
        modelPath = MODEL_PATH,
        binPath = LITERT_BIN,
        libDir = LITERT_LIB_DIR,
        backend = LITERT_BACKEND
    )

    val server = HttpServer.create(InetSocketAddress("127.0.0.1", SERVER_PORT), 0)

    server.createContext("/health", HttpHandler { exchange ->
        handleHealth(exchange, model)
    })

    server.createContext("/v1/messages", HttpHandler { exchange ->
        handleMessages(exchange, model)
    })

    server.executor = null
    server.start()

    println("Server started on http://127.0.0.1:$SERVER_PORT")
    println("Using model path: $MODEL_PATH")
    println("Model implementation: ${model::class.simpleName}")
}

private fun handleHealth(exchange: HttpExchange, model: LocalModel) {
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

    sendJson(exchange, 200, body)
}

private fun handleMessages(exchange: HttpExchange, model: LocalModel) {
    if (exchange.requestMethod != "POST") {
        sendPlain(exchange, 405, "method_not_allowed")
        return
    }

    val body = exchange.requestBody.bufferedReader().use { it.readText() }
    val answer = model.generate(
        prompt = body,
        maxTokens = 512,
        temperature = 0.2
    )

    val response = """
        {
          "type": "message",
          "role": "assistant",
          "content": [
            {
              "type": "text",
              "text": ${jsonString(answer)}
            }
          ]
        }
    """.trimIndent()
    try {
        val result = model.generate(...)
        sendJson(exchange, 200, resultJson) 
    } catch (e: Exception) {
        sendJson(exchange, 500, """{"error": ${jsonString(e.message ?: "unknown")}}""")
    }
}

private fun sendPlain(exchange: HttpExchange, statusCode: Int, body: String) {
    val bytes = body.toByteArray(Charsets.UTF_8)
    exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
    exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
}
