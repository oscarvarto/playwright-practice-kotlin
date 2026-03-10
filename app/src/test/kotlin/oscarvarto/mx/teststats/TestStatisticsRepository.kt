package oscarvarto.mx.teststats

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.PrintWriter
import java.io.StringWriter
import java.sql.Connection
import java.time.Instant
import java.util.UUID

internal class TestStatisticsRepository(
    private val dataSourceFactory: TestStatisticsDataSourceFactory,
    private val schemaMigrator: TestStatisticsSchemaMigrator,
) {
    @Synchronized
    fun initialize() {
        schemaMigrator.migrate(dataSourceFactory)
    }

    @Synchronized
    fun createRun(runContext: TestRunContextCollector.TestRunContext) {
        dataSourceFactory.createDataSource().connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO test_run(
                      run_id, framework, runner, started_at_utc, trigger_source, run_label,
                      working_directory, git_commit, git_branch, ci_provider, ci_build_id,
                      ci_job_name, host_name, os_name, os_arch, jvm_vendor, jvm_version,
                      browser_name, browser_channel, headless
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { stmt ->
                    var i = 1
                    stmt.setString(i++, runContext.runId)
                    stmt.setString(i++, runContext.framework)
                    stmt.setString(i++, runContext.runner)
                    stmt.setString(i++, runContext.startedAt.toString())
                    stmt.setString(i++, runContext.triggerSource)
                    stmt.setString(i++, runContext.runLabel)
                    stmt.setString(i++, runContext.workingDirectory)
                    stmt.setString(i++, runContext.gitCommit)
                    stmt.setString(i++, runContext.gitBranch)
                    stmt.setString(i++, runContext.ciProvider)
                    stmt.setString(i++, runContext.ciBuildId)
                    stmt.setString(i++, runContext.ciJobName)
                    stmt.setString(i++, runContext.hostName)
                    stmt.setString(i++, runContext.osName)
                    stmt.setString(i++, runContext.osArch)
                    stmt.setString(i++, runContext.jvmVendor)
                    stmt.setString(i++, runContext.jvmVersion)
                    stmt.setString(i++, runContext.browserName)
                    stmt.setString(i++, runContext.browserChannel)
                    if (runContext.headless != null) stmt.setInt(i, runContext.headless) else stmt.setNull(i, java.sql.Types.INTEGER)
                    stmt.executeUpdate()
                }
        }
    }

    @Synchronized
    fun finishRun(
        runId: String,
        finishedAt: Instant,
    ) {
        dataSourceFactory.createDataSource().connection.use { connection ->
            connection.prepareStatement("UPDATE test_run SET finished_at_utc = ? WHERE run_id = ?").use { stmt ->
                stmt.setString(1, finishedAt.toString())
                stmt.setString(2, runId)
                stmt.executeUpdate()
            }
        }
    }

    @Synchronized
    fun startExecution(
        runContext: TestRunContextCollector.TestRunContext,
        testCaseDetails: TestRunContextCollector.TestCaseDetails,
        executionId: String,
        startedAt: Instant,
        threadName: String,
    ) {
        val startedAtUtc = startedAt.toString()
        dataSourceFactory.createDataSource().connection.use { connection ->
            connection.autoCommit = false
            upsertTestCase(connection, testCaseDetails, startedAtUtc)
            val attemptIndex = nextAttemptIndex(connection, runContext.runId, testCaseDetails.testCaseId)
            connection
                .prepareStatement(
                    """
                    INSERT INTO test_execution(
                      execution_id, run_id, test_case_id, attempt_index,
                      started_at_utc, finished_at_utc, duration_ms,
                      canonical_status, framework_status, disabled_reason,
                      exception_class, exception_message, exception_stacktrace,
                      failure_fingerprint, thread_name, created_at_utc
                    ) VALUES (?, ?, ?, ?, ?, NULL, NULL, ?, ?, NULL, NULL, NULL, NULL, NULL, ?, ?)
                    """.trimIndent(),
                ).use { stmt ->
                    stmt.setString(1, executionId)
                    stmt.setString(2, runContext.runId)
                    stmt.setString(3, testCaseDetails.testCaseId)
                    stmt.setInt(4, attemptIndex)
                    stmt.setString(5, startedAtUtc)
                    stmt.setString(6, CanonicalStatus.UNKNOWN.name)
                    stmt.setString(7, "STARTED")
                    stmt.setString(8, threadName)
                    stmt.setString(9, startedAtUtc)
                    stmt.executeUpdate()
                }
            connection.commit()
        }
    }

    @Synchronized
    fun finishExecution(
        execution: CurrentTestExecution,
        status: CanonicalStatus,
        frameworkStatus: String,
        throwable: Throwable?,
        threadName: String,
    ) {
        val failureDetails = FailureDetails.fromThrowable(throwable)
        dataSourceFactory.createDataSource().connection.use { connection ->
            connection.autoCommit = false
            connection
                .prepareStatement(
                    """
                    UPDATE test_execution
                    SET finished_at_utc = ?,
                        duration_ms = ?,
                        canonical_status = ?,
                        framework_status = ?,
                        disabled_reason = NULL,
                        exception_class = ?,
                        exception_message = ?,
                        exception_stacktrace = ?,
                        failure_fingerprint = ?,
                        thread_name = ?
                    WHERE execution_id = ?
                    """.trimIndent(),
                ).use { stmt ->
                    stmt.setString(1, execution.finishedAt.toString())
                    if (execution.durationMs != null) stmt.setLong(2, execution.durationMs!!) else stmt.setNull(2, java.sql.Types.BIGINT)
                    stmt.setString(3, status.name)
                    stmt.setString(4, frameworkStatus)
                    stmt.setString(5, failureDetails.exceptionClass)
                    stmt.setString(6, failureDetails.exceptionMessage)
                    stmt.setString(7, failureDetails.exceptionStacktrace)
                    stmt.setString(8, failureDetails.failureFingerprint)
                    stmt.setString(9, threadName)
                    stmt.setString(10, execution.executionId)
                    stmt.executeUpdate()
                }
            insertPayloads(connection, execution)
            insertArtifacts(connection, execution)
            connection.commit()
        }
    }

    @Synchronized
    fun recordDisabledExecution(
        runContext: TestRunContextCollector.TestRunContext,
        testCaseDetails: TestRunContextCollector.TestCaseDetails,
        disabledReason: String?,
        threadName: String,
    ) {
        val now = Instant.now()
        val nowUtc = now.toString()
        dataSourceFactory.createDataSource().connection.use { connection ->
            connection.autoCommit = false
            upsertTestCase(connection, testCaseDetails, nowUtc)
            val attemptIndex = nextAttemptIndex(connection, runContext.runId, testCaseDetails.testCaseId)
            connection
                .prepareStatement(
                    """
                    INSERT INTO test_execution(
                      execution_id, run_id, test_case_id, attempt_index,
                      started_at_utc, finished_at_utc, duration_ms,
                      canonical_status, framework_status, disabled_reason,
                      exception_class, exception_message, exception_stacktrace,
                      failure_fingerprint, thread_name, created_at_utc
                    ) VALUES (?, ?, ?, ?, NULL, NULL, NULL, ?, ?, ?, NULL, NULL, NULL, NULL, ?, ?)
                    """.trimIndent(),
                ).use { stmt ->
                    stmt.setString(1, UUID.randomUUID().toString())
                    stmt.setString(2, runContext.runId)
                    stmt.setString(3, testCaseDetails.testCaseId)
                    stmt.setInt(4, attemptIndex)
                    stmt.setString(5, CanonicalStatus.DISABLED.name)
                    stmt.setString(6, "DISABLED")
                    stmt.setString(7, disabledReason)
                    stmt.setString(8, threadName)
                    stmt.setString(9, nowUtc)
                    stmt.executeUpdate()
                }
            connection.commit()
        }
    }

    private fun upsertTestCase(
        connection: Connection,
        testCaseDetails: TestRunContextCollector.TestCaseDetails,
        seenAtUtc: String,
    ) {
        connection
            .prepareStatement(
                """
                INSERT INTO test_case(
                  test_case_id, framework, engine_id, unique_id,
                  package_name, class_name, method_name, display_name,
                  first_seen_at_utc, last_seen_at_utc
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(test_case_id) DO UPDATE SET
                  package_name = excluded.package_name,
                  class_name = excluded.class_name,
                  method_name = excluded.method_name,
                  display_name = excluded.display_name,
                  last_seen_at_utc = excluded.last_seen_at_utc
                """.trimIndent(),
            ).use { stmt ->
                var i = 1
                stmt.setString(i++, testCaseDetails.testCaseId)
                stmt.setString(i++, testCaseDetails.framework)
                stmt.setString(i++, testCaseDetails.engineId)
                stmt.setString(i++, testCaseDetails.uniqueId)
                stmt.setString(i++, testCaseDetails.packageName)
                stmt.setString(i++, testCaseDetails.className)
                stmt.setString(i++, testCaseDetails.methodName)
                stmt.setString(i++, testCaseDetails.displayName)
                stmt.setString(i++, seenAtUtc)
                stmt.setString(i, seenAtUtc)
                stmt.executeUpdate()
            }

        connection.prepareStatement("INSERT OR IGNORE INTO test_case_tag(test_case_id, tag) VALUES (?, ?)").use { stmt ->
            for (tag in testCaseDetails.tags) {
                stmt.setString(1, testCaseDetails.testCaseId)
                stmt.setString(2, tag)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
    }

    private fun nextAttemptIndex(
        connection: Connection,
        runId: String,
        testCaseId: String,
    ): Int {
        connection
            .prepareStatement(
                "SELECT COALESCE(MAX(attempt_index), 0) + 1 FROM test_execution WHERE run_id = ? AND test_case_id = ?",
            ).use { stmt ->
                stmt.setString(1, runId)
                stmt.setString(2, testCaseId)
                stmt.executeQuery().use { rs ->
                    return if (rs.next()) rs.getInt(1) else 1
                }
            }
    }

    private fun insertPayloads(
        connection: Connection,
        execution: CurrentTestExecution,
    ) {
        if (execution.payloads.isEmpty()) return
        connection
            .prepareStatement(
                """
                INSERT INTO test_execution_payload(
                  payload_id, execution_id, payload_role, payload_name,
                  content_type, payload_text, created_at_utc
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { stmt ->
                for (payload in execution.payloads) {
                    stmt.setString(1, payload.payloadId)
                    stmt.setString(2, payload.executionId)
                    stmt.setString(3, payload.payloadRole)
                    stmt.setString(4, payload.payloadName)
                    stmt.setString(5, payload.contentType)
                    stmt.setString(6, payload.payloadText)
                    stmt.setString(7, payload.createdAt.toString())
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
    }

    private fun insertArtifacts(
        connection: Connection,
        execution: CurrentTestExecution,
    ) {
        if (execution.artifacts.isEmpty()) return
        connection
            .prepareStatement(
                """
                INSERT INTO test_execution_artifact(
                  artifact_id, execution_id, artifact_kind, artifact_path,
                  description, created_at_utc
                ) VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { stmt ->
                for (artifact in execution.artifacts) {
                    stmt.setString(1, artifact.artifactId)
                    stmt.setString(2, artifact.executionId)
                    stmt.setString(3, artifact.artifactKind)
                    stmt.setString(4, artifact.artifactPath)
                    stmt.setString(5, artifact.description)
                    stmt.setString(6, artifact.createdAt.toString())
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
    }

    private data class FailureDetails(
        val exceptionClass: String?,
        val exceptionMessage: String?,
        val exceptionStacktrace: String?,
        val failureFingerprint: String?,
    ) {
        companion object {
            fun fromThrowable(throwable: Throwable?): FailureDetails {
                if (throwable == null) return FailureDetails(null, null, null, null)
                val exceptionClass = throwable.javaClass.name
                val exceptionMessage = normalizeMessage(throwable.message)
                val exceptionStacktrace = stacktraceOf(throwable)
                val preferredFrame = preferredFrame(throwable)
                val fingerprintSource = "$exceptionClass|$preferredFrame|$exceptionMessage"
                return FailureDetails(
                    exceptionClass,
                    exceptionMessage,
                    exceptionStacktrace,
                    DigestUtils.sha256Hex(fingerprintSource),
                )
            }

            private fun normalizeMessage(message: String?): String? {
                if (message.isNullOrBlank()) return null
                val firstLine = message.lines().firstOrNull()?.trim()
                return if (firstLine.isNullOrEmpty()) null else firstLine
            }

            private fun preferredFrame(throwable: Throwable): String? {
                var fallback: StackTraceElement? = null
                for (element in throwable.stackTrace) {
                    val className = element.className
                    if (className.startsWith("oscarvarto.mx")) {
                        return formatFrame(element)
                    }
                    if (fallback == null &&
                        !className.startsWith("org.junit.") &&
                        !className.startsWith("java.") &&
                        !className.startsWith("jdk.") &&
                        !className.startsWith("sun.")
                    ) {
                        fallback = element
                    }
                }
                return fallback?.let { formatFrame(it) }
            }

            private fun formatFrame(element: StackTraceElement): String = "${element.className}#${element.methodName}"

            private fun stacktraceOf(throwable: Throwable): String {
                val writer = StringWriter()
                throwable.printStackTrace(PrintWriter(writer))
                return writer.toString()
            }
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
