package oscarvarto.mx.teststats

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Public helper API used by test code to enrich the current test execution.
 *
 * ## Typical usage
 *
 * ```kotlin
 * TestStats.recordInput(buildJsonObject { put("tenant", "acme"); put("attempt", 1) })
 * TestStats.recordArtifactPath("trace", Path.of("artifacts", "trace.zip"))
 * ```
 *
 * ## Contract
 *
 * Calls are only valid while a JUnit test method is actively running under
 * [TestStatisticsExtension]. If no execution is bound to the current
 * thread, the helper fails fast with `IllegalStateException`.
 */
object TestStats {
    private val json = Json { prettyPrint = false }

    @JvmStatic
    fun recordInputJson(jsonString: String) {
        // Re-parse to validate JSON
        val element = json.parseToJsonElement(jsonString)
        CurrentTestExecution
            .requireCurrent()
            .addPayload("INPUT", "default", "application/json", element.toString())
    }

    @JvmStatic
    fun recordInput(value: JsonElement) {
        CurrentTestExecution
            .requireCurrent()
            .addPayload("INPUT", "default", "application/json", value.toString())
    }

    @JvmStatic
    fun recordArtifactPath(
        kind: String,
        path: Path,
    ) {
        recordArtifactPath(kind, path, null)
    }

    @JvmStatic
    fun recordArtifactPath(
        kind: String,
        path: Path,
        description: String?,
    ) {
        val normalized = normalizeArtifactPath(path)
        CurrentTestExecution.requireCurrent().addArtifact(kind, normalized, description)
    }

    private fun normalizeArtifactPath(path: Path): Path {
        val root = System.getProperty("test.statistics.artifacts.root")
        if (!root.isNullOrBlank() && !path.isAbsolute) {
            return Paths.get(root).resolve(path).normalize()
        }
        return path.toAbsolutePath().normalize()
    }
}
