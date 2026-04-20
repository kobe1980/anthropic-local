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

fun extractSystemText(json: String): String? {
    val valueStart = findValueStart(json, "system") ?: return null
    return extractTextLikeValue(json, valueStart)
}

fun extractOutputFormatInstruction(json: String): String? {
    val outputConfigPos = json.indexOf("\"output_config\"")
    if (outputConfigPos == -1) return null

    val formatPos = json.indexOf("\"format\"", outputConfigPos)
    if (formatPos == -1) return null

    val schemaJson = extractObjectForKey(json, "schema", formatPos) ?: return null

    return """
        Output requirement:
        Return valid JSON only.
        Match exactly this JSON schema:
        $schemaJson
        Do not wrap the JSON in markdown fences.
        Do not add explanation before or after the JSON.
    """.trimIndent()
}

fun extractContentField(messageObject: String): String {
    val valueStart = findValueStart(messageObject, "content") ?: return ""
    return extractTextLikeValue(messageObject, valueStart).orEmpty()
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
    val valueStart = findValueStart(json, key) ?: return null
    if (valueStart >= json.length || json[valueStart] != '"') return null

    val end = findStringEnd(json, valueStart)
    if (end == -1) return null

    return unescapeJson(json.substring(valueStart + 1, end))
}

fun extractTopLevelInt(json: String, key: String): Int? {
    return extractTopLevelPrimitive(json, key)?.toIntOrNull()
}

fun extractTopLevelDouble(json: String, key: String): Double? {
    return extractTopLevelPrimitive(json, key)?.toDoubleOrNull()
}

fun extractTopLevelPrimitive(json: String, key: String): String? {
    val valueStart = findValueStart(json, key) ?: return null
    if (valueStart >= json.length) return null

    val start = valueStart
    var i = start
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

private fun extractTextLikeValue(json: String, valueStart: Int): String? {
    if (valueStart >= json.length) return null

    return when (json[valueStart]) {
        '"' -> {
            val end = findStringEnd(json, valueStart)
            if (end == -1) null else unescapeJson(json.substring(valueStart + 1, end))
        }
        '[' -> {
            val end = findMatchingBracket(json, valueStart, '[', ']')
            if (end == -1) null else extractTextBlocksFromContentArray(json.substring(valueStart, end + 1))
        }
        else -> null
    }
}

private fun findValueStart(json: String, key: String, searchFrom: Int = 0): Int? {
    val pattern = "\"$key\""
    val keyIndex = json.indexOf(pattern, searchFrom)
    if (keyIndex == -1) return null

    val colonIndex = json.indexOf(':', keyIndex + pattern.length)
    if (colonIndex == -1) return null

    var i = colonIndex + 1
    while (i < json.length && json[i].isWhitespace()) i++
    if (i >= json.length) return null

    return i
}

private fun extractObjectForKey(json: String, key: String, searchFrom: Int = 0): String? {
    val valueStart = findValueStart(json, key, searchFrom) ?: return null
    if (valueStart >= json.length || json[valueStart] != '{') return null

    val end = findMatchingBracket(json, valueStart, '{', '}')
    if (end == -1) return null

    return json.substring(valueStart, end + 1)
}
