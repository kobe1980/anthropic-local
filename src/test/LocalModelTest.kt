import kotlin.test.Test
import kotlin.test.assertEquals

class LocalModelTest {

    @Test
    fun extractModelAnswer_keeps_only_last_assistant_answer() {
        val raw = """
            INFO: init
            User:
            bonjour

            Assistant:
            ancienne reponse

            User:
            nouvelle question

            Assistant:
            vraie reponse ici
            sur deux lignes
            BenchmarkInfo:
            stats
        """.trimIndent()

        val model = CliLocalModelForTest()
        val result = model.extract(raw)

        assertEquals("vraie reponse ici\nsur deux lignes", result)
    }
}

private class CliLocalModelForTest {
    fun extract(rawOutput: String): String {
        val assistantMarker = "Assistant:\n"
        val assistantIndex = rawOutput.lastIndexOf(assistantMarker)

        val candidate = if (assistantIndex != -1) {
            rawOutput.substring(assistantIndex + assistantMarker.length)
        } else {
            rawOutput
        }

        return candidate
            .lines()
            .takeWhile { !it.startsWith("BenchmarkInfo:") }
            .filterNot { it.startsWith("VERBOSE:") }
            .filterNot { it.startsWith("INFO:") }
            .filterNot { it.startsWith("WARNING:") }
            .joinToString("\n")
            .trim()
    }
}
