import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliLocalModelCommandTest {

    @Test
    fun buildCommand_includes_generation_parameters() {
        val model = CliLocalModel(
            modelPath = "/tmp/model.litertlm",
            binPath = "/tmp/litert_lm_main",
            libDir = "/tmp",
            backend = "cpu"
        )

        val command = model.buildCommand(
            prompt = "hello",
            maxTokens = 128,
            temperature = 0.2
        )

        assertTrue(command.contains("--max_output_tokens=128"))
        assertTrue(command.contains("--temperature=0.2"))
        assertTrue(command.contains("--input_prompt=hello"))
    }

    @Test
    fun buildCommand_bounds_generation_parameters() {
        val model = CliLocalModel(
            modelPath = "/tmp/model.litertlm",
            binPath = "/tmp/litert_lm_main",
            libDir = "/tmp"
        )

        val command = model.buildCommand(
            prompt = "hi",
            maxTokens = 0,
            temperature = -2.0
        )

        assertEquals("--max_output_tokens=1", command[3])
        assertEquals("--temperature=0.0", command[4])
    }
}
