private const val MAX_SYSTEM_CHARS = 3500
private const val MAX_MESSAGE_CHARS = 1200
private const val MAX_MESSAGES_TOTAL_CHARS = 2800
private const val MAX_HISTORY_MESSAGES = 4
private const val MAX_PROMPT_CHARS = 9000

fun buildPrompt(system: String?, messages: List<ChatMessage>): String {
    val sb = StringBuilder()

    sb.append(
        """
        System:
        You are a coding assistant used by Claude Code.
        Follow all system instructions exactly.
        Be concise, practical, and execution-focused.
        Prefer direct answers, commands, code, or concrete file edits.
        When JSON-only or structured-output requirements are provided, obey them exactly.
        """.trimIndent()
    )
    sb.append("\n\n")

    val compactSystem = compactSystemText(system)
    if (!compactSystem.isNullOrBlank()) {
        sb.append("System:\n")
        sb.append(compactSystem)
        sb.append("\n\n")
    }

    val compactMessages = compactMessages(messages)
    for (message in compactMessages) {
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
    return trimMiddle(sb.toString().trimEnd() + "\n", MAX_PROMPT_CHARS)
}

private fun compactSystemText(system: String?): String? {
    if (system.isNullOrBlank()) return null

    val cleaned = system
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    if (cleaned.isBlank()) return null
    return trimMiddle(cleaned, MAX_SYSTEM_CHARS)
}

private fun compactMessages(messages: List<ChatMessage>): List<ChatMessage> {
    val recent = messages
        .takeLast(MAX_HISTORY_MESSAGES)
        .map {
            ChatMessage(
                role = it.role,
                content = normalizePromptText(it.content)
            )
        }
        .filter { it.content.isNotBlank() }

    val selected = ArrayDeque<ChatMessage>()
    var totalChars = 0

    for (message in recent.asReversed()) {
        val trimmedContent = trimMiddle(message.content, MAX_MESSAGE_CHARS)
        if (trimmedContent.isBlank()) continue

        val normalized = ChatMessage(
            role = message.role,
            content = trimmedContent
        )

        val cost = normalized.role.length + normalized.content.length + 16
        if (selected.isNotEmpty() && totalChars + cost > MAX_MESSAGES_TOTAL_CHARS) {
            break
        }

        selected.addFirst(normalized)
        totalChars += cost
    }

    return selected.toList()
}

private fun normalizePromptText(text: String): String {
    return text
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

private fun trimMiddle(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    if (maxChars <= 32) return text.take(maxChars)

    val separator = "\n\n[... trimmed ...]\n\n"
    val remaining = maxChars - separator.length
    val head = (remaining * 2) / 3
    val tail = remaining - head

    return text.take(head).trimEnd() + separator + text.takeLast(tail).trimStart()
}
