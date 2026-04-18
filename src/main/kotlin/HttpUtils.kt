import com.sun.net.httpserver.HttpExchange
import java.io.OutputStream

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
