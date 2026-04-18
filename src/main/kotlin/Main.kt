import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.UUID

// =========================
// CONFIG
// =========================

const val MODEL_PATH = "/data/data/com.termux/files/home/storage/downloads/gemma-4-E2B-it.litertlm"
const val LITERT_BIN = "/data/data/com.termux/files/home/litert/litert_lm_main"
const val LITERT_LIB_DIR = "/data/data/com.termux/files/home/litert"
const val LITERT_BACKEND = "cpu"
const val SERVER_PORT = 8080

fun main() {
    val model: LocalModel = CliLocalModel(
        modelPath = MODEL_PATH,
        binPath = LITERT_BIN,
        libDir = LITERT_LIB_DIR,
        backend = LITERT_BACKEND
    )

    val server = HttpServer.create(InetSocketAddress("127.0.0.1", SERVER_PORT), 0)
    server.createContext("/health", HealthHandler(model))
    server.createContext("/v1/messages", MessagesHandler(model))
    server.executor = null
    server.start()

    println("Server started on http://127.0.0.1:$SERVER_PORT")
    println("Using model path: $MODEL_PATH")
    println("Model implementation: ${model::class.simpleName}")
}

// =========================
// HTTP HANDLERS
// =========================

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

// =========================
// MODEL INTERFACE
// =========================

interface LocalModel {
    fun isReady(): Boolean
    fun generate(prompt: String, maxTokens: Int, temperature: Double): String
}

class CliLocalModel(
    private val modelPath: String,
    private val binPath: String,
    private val libDir: String,
    private val backend: String = "cpu"
) : LocalModel {

    override fun isReady(): Boolean {
        return File(binPath).canExecute() && File(modelPath).exists()
    }

    override fun generate(prompt: String, maxTokens: Int, temperature: Double): String {
        check(isReady()) { "CLI model is not ready" }

        val command = listOf(
            binPath,
            "--backend=$backend",
            "--model_path=$modelPath",
            "--input_prompt=$prompt"
        )

        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .apply {
                val currentLd = environment()["LD_LIBRARY_PATH"].orEmpty()
                environment()["LD_LIBRARY_PATH"] =
                    if (currentLd.isBlank()) libDir else "$libDir:$currentLd"
            }
            .start()

        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            throw IllegalStateException("litert_lm_main failed with exit code $exitCode:\n$output")
        }

        val cleaned = extractModelAnswer(output)
        return if (cleaned.length > 4000) cleaned.take(4000) else cleaned
    }

    private fun extractModelAnswer(rawOutput: String): String {
        val lines = rawOutput.lines()

        val promptIndex = lines.indexOfFirst { it.startsWith("input_prompt:") }
        val candidateLines = if (promptIndex != -1 && promptIndex + 1 < lines.size) {
            lines.drop(promptIndex + 1)
        } else {
            lines
        }

        val startIndex = candidateLines.indexOfFirst { line ->
            val trimmed = line.trimStart()
            trimmed.startsWith("Voici ") ||
                trimmed.startsWith("Bien sûr") ||
                trimmed.startsWith("D'accord") ||
                trimmed.startsWith("fun ") ||
                trimmed.startsWith("```") ||
                trimmed.startsWith("Pour ") ||
                trimmed.startsWith("Étape 1") ||
                trimmed.startsWith("1.")
        }

        val usefulLines = if (startIndex != -1) {
            candidateLines.drop(startIndex)
        } else {
            candidateLines
        }

        return usefulLines
            .takeWhile { !it.startsWith("BenchmarkInfo:") }
            .filterNot { it.startsWith("VERBOSE:") }
            .filterNot { it.startsWith("INFO:") }
            .filterNot { it.startsWith("WARNING:") }
            .joinToString("\n")
            .trim()
    }
}

// =========================
// REQUEST PARSING
// =========================

data class ChatMessage(
    val role: String,
    val content: String
)

fun extractMessages(json: String): List<ChatMessage> {
    val result = mutableListOf<ChatMessage>()

    val messagesIndex = json.indexOf("\"messages\"")
    if (messagesIndex == -1) return result

    val arrayStart = json.indexOf('[', messagesIndex)
    if (arrayStart == -1) return result

    val arrayEnd = findMatchingBracket(json, arrayStart, '[', ']')
    if (arrayEnd == -1) return result

    val arrayBody = json.substring(arrayStart + 1, arrayEnd)

    val objects = splitTopLevelObjects(arrayBody)
    for (obj in objects) {
        val role = extractTopLevelString(obj, "role") ?: "user"
        val content = extractContentField(obj)
        result += ChatMessage(role = role, content = content)
    }

    return result
}

fun extractContentField(messageObject: String): String {
    val contentPos = messageObject.indexOf("\"content\"")
    if (contentPos == -1) return ""

    val colon = messageObject.indexOf(':', contentPos)
    if (colon == -1) return ""

    var i = colon + 1
    while (i < messageObject.length && messageObject[i].isWhitespace()) i++

    if (i >= messageObject.length) return ""

    return when (messageObject[i]) {
        '"' -> {
            val end = findStringEnd(messageObject, i)
            if (end == -1) "" else unescapeJson(messageObject.substring(i + 1, end))
        }
        '[' -> {
            val end = findMatchingBracket(messageObject, i, '[', ']')
            if (end == -1) "" else extractTextBlocksFromContentArray(messageObject.substring(i, end + 1))
        }
        else -> ""
    }
}

fun extractTextBlocksFromContentArray(arrayJson: String): String {
    val objects = splitTopLevelObjects(arrayJson.removePrefix("[").removeSuffix("]"))
    val texts = mutableListOf<String>()

    for (obj in objects) {
        val type = extractTopLevelString(obj, "type")
        if (type == "text") {
            val text = extractTopLevelString(obj, "text")
            if (!text.isNullOrBlank()) texts += text
        }
    }

    return texts.joinToString("\n")
}

fun extractTopLevelString(json: String, key: String): String? {
    val pattern = "\"$key\""
    val keyIndex = json.indexOf(pattern)
    if (keyIndex == -1) return null

    val colonIndex = json.indexOf(':', keyIndex + pattern.length)
    if (colonIndex == -1) return null

    var i = colonIndex + 1
    while (i < json.length && json[i].isWhitespace()) i++
    if (i >= json.length || json[i] != '"') return null

    val end = findStringEnd(json, i)
    if (end == -1) return null

    return unescapeJson(json.substring(i + 1, end))
}

fun extractTopLevelInt(json: String, key: String): Int? {
    return extractTopLevelPrimitive(json, key)?.toIntOrNull()
}

fun extractTopLevelDouble(json: String, key: String): Double? {
    return extractTopLevelPrimitive(json, key)?.toDoubleOrNull()
}

fun extractTopLevelPrimitive(json: String, key: String): String? {
    val pattern = "\"$key\""
    val keyIndex = json.indexOf(pattern)
    if (keyIndex == -1) return null

    val colonIndex = json.indexOf(':', keyIndex + pattern.length)
    if (colonIndex == -1) return null

    var i = colonIndex + 1
    while (i < json.length && json[i].isWhitespace()) i++
    if (i >= json.length) return null

    val start = i
    while (i < json.length && json[i] !in charArrayOf(',', '}', '\n', '\r')) i++

    return json.substring(start, i).trim().removeSuffix(",")
}

fun splitTopLevelObjects(input: String): List<String> {
    val result = mutableListOf<String>()
    var i = 0

    while (i < input.length) {
        while (i < input.length && (input[i].isWhitespace() || input[i] == ',')) i++
        if (i >= input.length) break

        if (input[i] == '{') {
            val end = findMatchingBracket(input, i, '{', '}')
            if (end == -1) break
            result += input.substring(i, end + 1)
            i = end + 1
        } else {
            i++
        }
    }

    return result
}

fun findMatchingBracket(text: String, startIndex: Int, open: Char, close: Char): Int {
    var depth = 0
    var inString = false
    var escaped = false

    for (i in startIndex until text.length) {
        val ch = text[i]

        if (inString) {
            if (escaped) {
                escaped = false
            } else if (ch == '\\') {
                escaped = true
            } else if (ch == '"') {
                inString = false
            }
            continue
        }

        if (ch == '"') {
            inString = true
            continue
        }

        if (ch == open) depth++
        if (ch == close) {
            depth--
            if (depth == 0) return i
        }
    }

    return -1
}

fun findStringEnd(text: String, quoteIndex: Int): Int {
    var escaped = false
    for (i in quoteIndex + 1 until text.length) {
        val ch = text[i]
        if (escaped) {
            escaped = false
            continue
        }
        if (ch == '\\') {
            escaped = true
            continue
        }
        if (ch == '"') return i
    }
    return -1
}

fun unescapeJson(input: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < input.length) {
        val ch = input[i]
        if (ch == '\\' && i + 1 < input.length) {
            val next = input[i + 1]
            when (next) {
                '\\' -> sb.append('\\')
                '"' -> sb.append('"')
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                else -> sb.append(next)
            }
            i += 2
        } else {
            sb.append(ch)
            i++
        }
    }
    return sb.toString()
}

// =========================
// PROMPT BUILDING
// =========================

fun buildPrompt(system: String?, messages: List<ChatMessage>): String {
    val sb = StringBuilder()

    sb.append(
        """
        System:
        You are a coding assistant used by Claude Code.
        Be concise, practical, and action-oriented.
        Prefer short answers.
        When relevant, give direct implementation steps, commands, code, or file edits.
        Do not give long introductions or generic explanations.
        If the user asks for code, provide the code first.
        Prefer concrete next steps over brainstorming.
        """.trimIndent()
    )
    sb.append("\n\n")

    if (!system.isNullOrBlank()) {
        sb.append("System:\n")
        sb.append(system.trim())
        sb.append("\n\n")
    }

    for (message in messages) {
        when (message.role.lowercase()) {
            "user" -> {
                sb.append("User:\n")
                sb.append(message.content.trim())
                sb.append("\n\n")
            }
            "assistant" -> {
                sb.append("Assistant:\n")
                sb.append(message.content.trim())
                sb.append("\n\n")
            }
            "system" -> {
                sb.append("System:\n")
                sb.append(message.content.trim())
                sb.append("\n\n")
            }
            else -> {
                sb.append(message.role)
                sb.append(":\n")
                sb.append(message.content.trim())
                sb.append("\n\n")
            }
        }
    }

    sb.append("Assistant:\n")
    return sb.toString()
}

// =========================
// JSON / HTTP HELPERS
// =========================

fun sendJson(exchange: HttpExchange, statusCode: Int, body: String) {
    val bytes = body.toByteArray(Charsets.UTF_8)
    exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
    exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
    exchange.responseHeaders.add("Access-Control-Allow-Headers", "Content-Type, x-api-key, anthropic-version")
    exchange.responseHeaders.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
    exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
    val os: OutputStream = exchange.responseBody
    os.use { it.write(bytes) }
}

fun jsonString(value: String): String {
    val sb = StringBuilder()
    sb.append('"')
    for (ch in value) {
        when (ch) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> sb.append(ch)
        }
    }
    sb.append('"')
    return sb.toString()
}
