import kotlin.test.Test
import kotlin.test.assertTrue

class PromptBuilderTest {

    @Test
    fun buildPrompt_ends_with_assistant_marker() {
        val prompt = buildPrompt(
            system = null,
            messages = listOf(ChatMessage("user", "hello"))
        )

        assertTrue(prompt.endsWith("Assistant:\n"))
    }

    @Test
    fun buildPrompt_includes_user_message() {
        val prompt = buildPrompt(
            system = null,
            messages = listOf(ChatMessage("user", "hello"))
        )

        assertTrue(prompt.contains("User:\nhello"))
    }
}
