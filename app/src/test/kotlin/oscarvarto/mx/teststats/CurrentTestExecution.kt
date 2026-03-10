package oscarvarto.mx.teststats

import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal class CurrentTestExecution private constructor(
    val executionId: String,
    val runId: String,
    val testCaseId: String,
    val startedAt: Instant,
) {
    private val _payloads = mutableListOf<PayloadRecord>()
    private val _artifacts = mutableListOf<ArtifactRecord>()

    var finishedAt: Instant? = null
        private set
    var durationMs: Long? = null
        private set

    val payloads: List<PayloadRecord> get() = _payloads.toList()
    val artifacts: List<ArtifactRecord> get() = _artifacts.toList()

    fun markFinished(finishedAt: Instant) {
        if (this.finishedAt != null) return
        this.finishedAt = finishedAt
        this.durationMs = maxOf(0L, Duration.between(startedAt, finishedAt).toMillis())
    }

    fun ensureFinished() {
        if (finishedAt == null) markFinished(Instant.now())
    }

    fun addPayload(
        payloadRole: String,
        payloadName: String,
        contentType: String,
        payloadText: String,
    ) {
        _payloads.add(
            PayloadRecord(
                UUID.randomUUID().toString(),
                executionId,
                payloadRole,
                payloadName,
                contentType,
                payloadText,
                Instant.now(),
            ),
        )
    }

    fun addArtifact(
        artifactKind: String,
        artifactPath: Path,
        description: String?,
    ) {
        _artifacts.add(
            ArtifactRecord(
                UUID.randomUUID().toString(),
                executionId,
                artifactKind,
                artifactPath.toString(),
                description,
                Instant.now(),
            ),
        )
    }

    data class PayloadRecord(
        val payloadId: String,
        val executionId: String,
        val payloadRole: String,
        val payloadName: String,
        val contentType: String,
        val payloadText: String,
        val createdAt: Instant,
    )

    data class ArtifactRecord(
        val artifactId: String,
        val executionId: String,
        val artifactKind: String,
        val artifactPath: String,
        val description: String?,
        val createdAt: Instant,
    )

    companion object {
        private val CURRENT = ThreadLocal<CurrentTestExecution>()

        fun begin(
            executionId: String,
            runId: String,
            testCaseId: String,
            startedAt: Instant,
        ): CurrentTestExecution {
            val execution = CurrentTestExecution(executionId, runId, testCaseId, startedAt)
            CURRENT.set(execution)
            return execution
        }

        fun current(): CurrentTestExecution? = CURRENT.get()

        fun requireCurrent(): CurrentTestExecution =
            CURRENT.get()
                ?: throw IllegalStateException(
                    "Test statistics are only available while a JUnit test is actively running.",
                )

        fun clear() {
            CURRENT.remove()
        }
    }
}
