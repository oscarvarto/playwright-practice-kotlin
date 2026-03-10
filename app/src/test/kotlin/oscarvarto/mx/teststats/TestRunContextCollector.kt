package oscarvarto.mx.teststats

import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigFactory
import org.junit.jupiter.api.extension.ExtensionContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.TreeSet
import java.util.UUID

internal class TestRunContextCollector {
    fun collectRunContext(workingDirectory: Path): TestRunContext {
        val environment = System.getenv()
        val browserSettings = collectBrowserSettings()
        val ciProvider = detectCiProvider(environment)
        return TestRunContext(
            runId = UUID.randomUUID().toString(),
            framework = "junit5",
            runner = "gradle-test",
            startedAt = Instant.now(),
            triggerSource = ciProvider ?: "local",
            runLabel = blankToNull(System.getProperty(TestStatisticsDataSourceFactory.RUN_LABEL_PROPERTY)),
            workingDirectory = workingDirectory.toString(),
            gitCommit =
                firstNonBlank(
                    environment["GITHUB_SHA"],
                    environment["CI_COMMIT_SHA"],
                    environment["BUILD_SOURCEVERSION"],
                    gitValue(workingDirectory, "rev-parse", "HEAD"),
                ),
            gitBranch =
                firstNonBlank(
                    environment["GITHUB_REF_NAME"],
                    environment["CI_COMMIT_REF_NAME"],
                    environment["BUILD_SOURCEBRANCHNAME"],
                    environment["BRANCH_NAME"],
                    gitValue(workingDirectory, "rev-parse", "--abbrev-ref", "HEAD"),
                ),
            ciProvider = ciProvider,
            ciBuildId =
                firstNonBlank(
                    environment["GITHUB_RUN_ID"],
                    environment["CI_PIPELINE_ID"],
                    environment["BUILD_BUILDID"],
                    environment["BUILD_ID"],
                    environment["CIRCLE_WORKFLOW_ID"],
                ),
            ciJobName =
                firstNonBlank(
                    environment["GITHUB_JOB"],
                    environment["CI_JOB_NAME"],
                    environment["JOB_NAME"],
                    environment["AGENT_JOBNAME"],
                ),
            hostName = hostName(),
            osName = System.getProperty("os.name"),
            osArch = System.getProperty("os.arch"),
            jvmVendor = System.getProperty("java.vendor"),
            jvmVersion = System.getProperty("java.version"),
            browserName = browserSettings.browserName,
            browserChannel = browserSettings.browserChannel,
            headless = browserSettings.headless,
        )
    }

    fun collectTestCaseDetails(context: ExtensionContext): TestCaseDetails {
        val uniqueId = context.uniqueId
        val testClass = context.testClass.orElse(null)
        return TestCaseDetails(
            testCaseId = DigestUtils.sha256Hex("junit5|$uniqueId"),
            framework = "junit5",
            engineId = engineId(uniqueId),
            uniqueId = uniqueId,
            packageName = testClass?.let { blankToNull(it.packageName) },
            className = testClass?.name,
            methodName = context.testMethod.map { it.name }.orElse(null),
            displayName = context.displayName,
            tags = TreeSet(context.tags),
        )
    }

    private fun collectBrowserSettings(): BrowserSettings {
        var browserName = blankToNull(System.getProperty("playwright.browser"))
        var channel = blankToNull(System.getProperty("playwright.channel"))
        var headless = parseHeadless(System.getProperty("playwright.headless"))

        if (browserName != null && channel != null && headless != null) {
            return BrowserSettings(browserName, channel, headless)
        }

        try {
            val playwrightConfig = ConfigFactory.load("playwright").getConfig("playwright")
            if (browserName == null && playwrightConfig.hasPath("browser")) {
                browserName = blankToNull(playwrightConfig.getString("browser"))
            }
            if (channel == null && playwrightConfig.hasPath("channel")) {
                channel = blankToNull(playwrightConfig.getString("channel"))
            }
            if (headless == null && playwrightConfig.hasPath("headless")) {
                headless = if (playwrightConfig.getBoolean("headless")) 1 else 0
            }
        } catch (_: ConfigException) {
            // Playwright config is optional for non-browser tests.
        }

        return BrowserSettings(browserName, channel, headless)
    }

    private fun parseHeadless(value: String?): Int? {
        if (value.isNullOrBlank()) return null
        return if (value.toBoolean()) 1 else 0
    }

    private fun detectCiProvider(environment: Map<String, String>): String? =
        when {
            environment.containsKey("GITHUB_ACTIONS") -> "github-actions"
            environment.containsKey("GITLAB_CI") -> "gitlab-ci"
            environment.containsKey("JENKINS_URL") -> "jenkins"
            environment.containsKey("BUILD_BUILDID") -> "azure-pipelines"
            environment.containsKey("CIRCLECI") -> "circleci"
            environment.containsKey("CI") -> "generic-ci"
            else -> null
        }

    private fun gitValue(
        workingDirectory: Path,
        vararg args: String,
    ): String? {
        if (!Files.exists(workingDirectory.resolve(".git"))) return null
        val command = arrayOf("git") + args
        val builder = ProcessBuilder(*command)
        builder.directory(workingDirectory.toFile())
        builder.redirectErrorStream(true)
        return try {
            val process = builder.start()
            BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                val value = reader.readLine()
                val exitCode = process.waitFor()
                if (exitCode == 0 && !value.isNullOrBlank()) value.trim() else null
            }
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            null
        }
    }

    private fun hostName(): String? =
        try {
            InetAddress.getLocalHost().hostName
        } catch (_: Exception) {
            null
        }

    private fun engineId(uniqueId: String): String {
        val matcher = ENGINE_ID_PATTERN.matcher(uniqueId)
        return if (matcher.find()) matcher.group(1) else "junit-jupiter"
    }

    private fun firstNonBlank(vararg values: String?): String? = values.firstOrNull { !it.isNullOrBlank() }

    private fun blankToNull(value: String?): String? = if (value.isNullOrBlank()) null else value

    data class BrowserSettings(
        val browserName: String?,
        val browserChannel: String?,
        val headless: Int?,
    )

    data class TestRunContext(
        val runId: String,
        val framework: String,
        val runner: String,
        val startedAt: Instant,
        val triggerSource: String,
        val runLabel: String?,
        val workingDirectory: String,
        val gitCommit: String?,
        val gitBranch: String?,
        val ciProvider: String?,
        val ciBuildId: String?,
        val ciJobName: String?,
        val hostName: String?,
        val osName: String?,
        val osArch: String?,
        val jvmVendor: String?,
        val jvmVersion: String?,
        val browserName: String?,
        val browserChannel: String?,
        val headless: Int?,
    )

    data class TestCaseDetails(
        val testCaseId: String,
        val framework: String,
        val engineId: String,
        val uniqueId: String,
        val packageName: String?,
        val className: String?,
        val methodName: String?,
        val displayName: String,
        val tags: Set<String>,
    )

    companion object {
        private val ENGINE_ID_PATTERN = Regex("\\[engine:([^\\]]+)]").toPattern()
    }
}
