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
        return if (cleaned.length > MAX_RESPONSE_CHARS) {
            cleaned.take(MAX_RESPONSE_CHARS)
        } else {
            cleaned
        }
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
            .takeWhile { !it.startsWith("BenchmarkInfo:") }
            .filterNot { it.startsWith("VERBOSE:") }
            .filterNot { it.startsWith("INFO:") }
            .filterNot { it.startsWith("WARNING:") }
            .joinToString("\n")
            .trim()
    }
}
