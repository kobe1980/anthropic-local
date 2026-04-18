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
