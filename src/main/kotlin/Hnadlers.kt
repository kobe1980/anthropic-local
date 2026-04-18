import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import java.io.IOException
import java.util.UUID

class HealthHandler(private val model: LocalModel) : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") {
            sendJson(exchange, 405, """{"error":"method_not_allowed"}""")
            return
        }

        val body = """
            {
              "status": "ok",
              "engine_ready": ${model.isReady()},
              "model_path": ${jsonString(MODEL_PATH)},
              "model_impl": ${jsonString(model::class.simpleName ?: "unknown")}
            }
        """.trimIndent()

        sendJson(exchange, 200, body)
    }
}

class MessagesHandler(private val model: LocalModel) : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        if (exchange.requestMethod == "OPTIONS") {
            exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
            exchange.responseHeaders.add("Access-Control-Allow-Headers", "Content-Type, x-api-key, anthropic-version")
            exchange.responseHeaders.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            exchange.sendResponseHeaders(204, -1)
            return
        }

        if (exchange.requestMethod != "POST") {
            sendJson(exchange, 405, """{"error":"method_not_allowed"}""")
            return
        }

        try {
            val body = exchange.requestBody.bufferedReader().use { it.readText() }

            val systemPrompt = extractTopLevelString(body, "system")
            val modelName = extractTopLevelString(body, "model") ?: "local-gemma"
            val maxTokens = extractTopLevelInt(body, "max_tokens") ?: 512
            val temperature = extractTopLevelDouble(body, "temperature") ?: 0.2

            val messages = extractMessages(body)
            val prompt = buildPrompt(systemPrompt, messages)

            println(
                "[REQ] model=$modelName max_tokens=$maxTokens temp=$temperature " +
                    "system=${if (systemPrompt.isNullOrBlank()) "<none>" else "<present>"} " +
                    "messages=${messages.size} preview=" +
                    messages.joinToString(" | ") { "${it.role}:${it.content.take(120)}" }
            )

            val answer = model.generate(
                prompt = prompt,
                maxTokens = maxTokens,
                temperature = temperature
            )

            println("[RES] chars=${answer.length} preview=${answer.take(300)}")

            val responseJson = """
                {
                  "id": "msg_local_${UUID.randomUUID()}",
                  "type": "message",
                  "role": "assistant",
                  "model": ${jsonString(modelName)},
                  "content": [
                    {
                      "type": "text",
                      "text": ${jsonString(answer)}
                    }
                  ],
                  "stop_reason": "end_turn"
                }
            """.trimIndent()

            sendJson(exchange, 200, responseJson)
        } catch (t: Throwable) {
            if (t is IOException && (t.message?.contains("Broken pipe") == true)) {
                println("[WARN] client disconnected before response was fully written")
                return
            }

            t.printStackTrace()

            val errorJson = """
                {
                  "error": {
                    "type": "api_error",
                    "message": ${jsonString(t.message ?: "unknown error")}
                  }
                }
            """.trimIndent()

            sendJson(exchange, 500, errorJson)
        }
    }
}
