import java.io.File

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
        return truncateCleanly(cleaned, MAX_RESPONSE_CHARS)
    }

    private fun extractModelAnswer(rawOutput: String): String {
        val assistantMarker = "Assistant:\n"
        val assistantIndex = rawOutput.lastIndexOf(assistantMarker)

        val candidate = if (assistantIndex != -1) {
            rawOutput.substring(assistantIndex + assistantMarker.length)
        } else {
            rawOutput
        }

        return candidate
            .lines()
            .takeWhile { line ->
                !line.startsWith("BenchmarkInfo:") &&
                    !line.startsWith("input_prompt:")
            }
            .filterNot { it.startsWith("VERBOSE:") }
            .filterNot { it.startsWith("INFO:") }
            .filterNot { it.startsWith("WARNING:") }
            .dropWhile { it.isBlank() }
            .joinToString("\n")
            .trim()
    }

    private fun truncateCleanly(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text

        val cut = text.take(maxChars)
        val lastParagraph = cut.lastIndexOf("\n\n")
        if (lastParagraph > maxChars / 2) {
            return cut.substring(0, lastParagraph).trim()
        }

        val lastLine = cut.lastIndexOf('\n')
        if (lastLine > maxChars / 2) {
            return cut.substring(0, lastLine).trim()
        }

        val lastSentence = maxOf(
            cut.lastIndexOf(". "),
            cut.lastIndexOf("! "),
            cut.lastIndexOf("? ")
        )
        if (lastSentence > maxChars / 2) {
            return cut.substring(0, lastSentence + 1).trim()
        }

        return cut.trim()
    }
}
