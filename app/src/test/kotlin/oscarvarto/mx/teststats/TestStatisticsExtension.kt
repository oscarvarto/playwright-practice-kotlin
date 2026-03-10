package oscarvarto.mx.teststats

import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.AfterTestExecutionCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.TestWatcher
import java.nio.file.Path
import java.time.Instant
import java.util.Optional
import java.util.UUID

class TestStatisticsExtension :
    BeforeAllCallback,
    AfterAllCallback,
    BeforeTestExecutionCallback,
    AfterTestExecutionCallback,
    TestWatcher {
    override fun beforeAll(context: ExtensionContext) {
        session(context)
    }

    override fun afterAll(context: ExtensionContext) {
        session(context)
    }

    override fun beforeTestExecution(context: ExtensionContext) {
        val session = session(context)
        if (!session.enabled) return

        val startedAt = Instant.now()
        val testCaseDetails = session.collector.collectTestCaseDetails(context)
        val executionId = UUID.randomUUID().toString()
        session.repository.startExecution(
            session.runContext,
            testCaseDetails,
            executionId,
            startedAt,
            Thread.currentThread().name,
        )
        CurrentTestExecution.begin(executionId, session.runContext.runId, testCaseDetails.testCaseId, startedAt)
    }

    override fun afterTestExecution(context: ExtensionContext) {
        CurrentTestExecution.current()?.markFinished(Instant.now())
    }

    override fun testSuccessful(context: ExtensionContext) {
        complete(context, CanonicalStatus.PASSED, "SUCCESSFUL", null)
    }

    override fun testAborted(
        context: ExtensionContext,
        cause: Throwable,
    ) {
        complete(context, CanonicalStatus.ABORTED, "ABORTED", cause)
    }

    override fun testFailed(
        context: ExtensionContext,
        cause: Throwable,
    ) {
        complete(context, CanonicalStatus.FAILED, "FAILED", cause)
    }

    override fun testDisabled(
        context: ExtensionContext,
        reason: Optional<String>,
    ) {
        val session = session(context)
        if (!session.enabled) return
        val testCaseDetails = session.collector.collectTestCaseDetails(context)
        session.repository.recordDisabledExecution(
            session.runContext,
            testCaseDetails,
            reason.orElse(null),
            Thread.currentThread().name,
        )
    }

    private fun complete(
        context: ExtensionContext,
        status: CanonicalStatus,
        frameworkStatus: String,
        cause: Throwable?,
    ) {
        val session = session(context)
        if (!session.enabled) {
            CurrentTestExecution.clear()
            return
        }

        val execution = CurrentTestExecution.current() ?: return

        execution.ensureFinished()
        session.repository.finishExecution(
            execution,
            status,
            frameworkStatus,
            cause,
            Thread.currentThread().name,
        )
        CurrentTestExecution.clear()
    }

    private fun session(context: ExtensionContext): SessionResource =
        context.root
            .getStore(NAMESPACE)
            .getOrComputeIfAbsent(
                SessionResource::class.java,
                { SessionResource.create() },
                SessionResource::class.java,
            )

    private class SessionResource private constructor(
        val enabled: Boolean,
        private val _repository: TestStatisticsRepository?,
        private val _collector: TestRunContextCollector?,
        private val _runContext: TestRunContextCollector.TestRunContext?,
    ) : ExtensionContext.Store.CloseableResource {
        val repository: TestStatisticsRepository get() = _repository!!
        val collector: TestRunContextCollector get() = _collector!!
        val runContext: TestRunContextCollector.TestRunContext get() = _runContext!!

        companion object {
            fun create(): SessionResource {
                val factory = TestStatisticsDataSourceFactory()
                if (!factory.isEnabled()) {
                    return SessionResource(false, null, null, null)
                }

                val repository = TestStatisticsRepository(factory, TestStatisticsSchemaMigrator())
                repository.initialize()

                val collector = TestRunContextCollector()
                val runContext =
                    collector.collectRunContext(
                        Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize(),
                    )
                repository.createRun(runContext)
                return SessionResource(true, repository, collector, runContext)
            }
        }

        override fun close() {
            if (enabled) {
                repository.finishRun(runContext.runId, Instant.now())
            }
        }
    }

    companion object {
        private val NAMESPACE = ExtensionContext.Namespace.create(TestStatisticsExtension::class.java)
    }
}
