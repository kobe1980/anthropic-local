import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

interface LocalModel {
    fun isReady(): Boolean
    fun generate(prompt: String, maxTokens: Int, temperature: Double): String
}

class CliLocalModel(
    private val modelPath: String,
    private val binPath: String,
    private val libDir: String,
    private val backend: String = "cpu",
    private val generationTimeoutSeconds: Long = 120
) : LocalModel {

    override fun isReady(): Boolean {
        return File(binPath).canExecute() && File(modelPath).exists()
    }

    override fun generate(prompt: String, maxTokens: Int, temperature: Double): String {
        check(isReady()) { "CLI model is not ready" }

        val command = buildCommand(prompt, maxTokens, temperature)

        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .apply {
                val currentLd = environment()["LD_LIBRARY_PATH"].orEmpty()
                environment()["LD_LIBRARY_PATH"] = if (currentLd.isBlank()) libDir else "$libDir:$currentLd"
            }
            .start()

        val outputCollector = StringBuilder()
        val readerThread = thread(start = true, isDaemon = true, name = "litert-output-reader") {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    outputCollector.appendLine(line)
                }
            }
        }

        val finished = process.waitFor(generationTimeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor()
            }
            readerThread.join(2_000)
            val output = outputCollector.toString().trim()
            throw IllegalStateException(
                "litert_lm_main timed out after ${generationTimeoutSeconds}s. " +
                    "Try a smaller max_tokens or shorter prompt.\n" +
                    output.takeLast(4_000)
            )
        }

        readerThread.join(2_000)
        val output = outputCollector.toString()
        val exitCode = process.exitValue()
        if (exitCode != 0) {
            throw IllegalStateException("litert_lm_main failed with exit code $exitCode:\n$output")
        }

        val cleaned = extractModelAnswer(output)
        return truncateCleanly(cleaned, MAX_RESPONSE_CHARS)
    }

    internal fun buildCommand(prompt: String, maxTokens: Int, temperature: Double): List<String> {
        val boundedMaxTokens = maxTokens.coerceIn(1, 1024)
        val boundedTemperature = temperature.coerceIn(0.0, 2.0)

        return listOf(
            binPath,
            "--backend=$backend",
            "--model_path=$modelPath",
            "--max_output_tokens=$boundedMaxTokens",
            "--temperature=$boundedTemperature",
            "--input_prompt=$prompt"
        )
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
