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
