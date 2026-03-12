package oscarvarto.mx.teststats

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.Isolated
import org.junit.platform.engine.DiscoverySelector
import org.junit.platform.engine.discovery.DiscoverySelectors
import org.junit.platform.launcher.LauncherDiscoveryRequest
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.launcher.listeners.SummaryGeneratingListener
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

@Isolated("Uses global test statistics system properties and embedded JUnit launchers.")
@Execution(ExecutionMode.SAME_THREAD)
class TestStatisticsExtensionIntegrationTest {
    @Test
    fun createsDatabaseAndAppliesMigrations() {
        val databasePath = temporaryDatabasePath()

        val summary = executeFixtures(databasePath, PassingFixture::class.java)

        assertThat(summary.summary.testsSucceededCount).isEqualTo(1)
        assertThat(Files.exists(databasePath)).isTrue()
        assertThat(queryForLong(databasePath, "SELECT COUNT(*) FROM flyway_schema_history"))
            .isEqualTo(3)
        assertThat(
            queryForLong(
                databasePath,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'view' AND name LIKE 'v_%'",
            ),
        ).isGreaterThanOrEqualTo(6)
    }

    @Test
    fun capturesStatusesPayloadsArtifactsAndFailureDetails() {
        val databasePath = temporaryDatabasePath()

        val summary =
            executeFixtures(
                databasePath,
                PassingFixture::class.java,
                FailingFixture::class.java,
                DisabledFixture::class.java,
                PayloadArtifactFixture::class.java,
            )

        assertThat(summary.summary.testsFoundCount).isEqualTo(4)
        assertThat(summary.summary.testsStartedCount).isEqualTo(3)
        assertThat(summary.summary.testsSkippedCount).isEqualTo(1)
        assertThat(queryForLong(databasePath, "SELECT COUNT(*) FROM test_execution WHERE canonical_status = 'PASSED'"))
            .isEqualTo(2)
        assertThat(queryForLong(databasePath, "SELECT COUNT(*) FROM test_execution WHERE canonical_status = 'FAILED'"))
            .isEqualTo(1)
        assertThat(queryForLong(databasePath, "SELECT COUNT(*) FROM test_execution WHERE canonical_status = 'DISABLED'"))
            .isEqualTo(1)
        assertThat(queryForLong(databasePath, "SELECT COUNT(*) FROM test_execution_payload"))
            .isEqualTo(1)
        assertThat(queryForLong(databasePath, "SELECT COUNT(*) FROM test_execution_artifact"))
            .isEqualTo(1)
        assertThat(queryForString(databasePath, "SELECT disabled_reason FROM test_execution WHERE canonical_status = 'DISABLED'"))
            .isEqualTo("fixture disabled")
        assertThat(queryForString(databasePath, "SELECT exception_class FROM test_execution WHERE canonical_status = 'FAILED'"))
            .isEqualTo(org.opentest4j.AssertionFailedError::class.java.name)
        assertThat(queryForString(databasePath, "SELECT payload_text FROM test_execution_payload"))
            .contains("\"tenant\":\"acme\"")
        assertThat(queryForString(databasePath, "SELECT artifact_kind FROM test_execution_artifact"))
            .isEqualTo("trace")
    }

    @Test
    fun updatesHistoryAndComputesDerivedViews() {
        val databasePath = temporaryDatabasePath()

        FlakyFixture.reset()
        executeFixtures(databasePath, PassingFixture::class.java)
        Thread.sleep(Duration.ofMillis(25))
        executeFixtures(databasePath, PassingFixture::class.java)
        val firstFlaky = executeFixtures(databasePath, FlakyFixture::class.java)
        val secondFlaky = executeFixtures(databasePath, FlakyFixture::class.java)
        val firstFingerprint = executeFixtures(databasePath, AlwaysFailingFingerprintFixture::class.java)
        val secondFingerprint = executeFixtures(databasePath, AlwaysFailingFingerprintFixture::class.java)

        assertThat(firstFlaky.summary.testsFailedCount).isEqualTo(1)
        assertThat(secondFlaky.summary.testsSucceededCount).isEqualTo(1)
        assertThat(firstFingerprint.summary.testsFailedCount).isEqualTo(1)
        assertThat(secondFingerprint.summary.testsFailedCount).isEqualTo(1)

        val timestamps =
            queryForStrings(
                databasePath,
                "SELECT first_seen_at_utc, last_seen_at_utc FROM test_case WHERE class_name LIKE '%PassingFixture'",
            )
        assertThat(timestamps).hasSize(2)
        assertThat(timestamps[1]).isGreaterThan(timestamps[0])
        assertThat(
            queryForLong(
                databasePath,
                "SELECT total_executions FROM v_test_case_quality WHERE class_name LIKE '%PassingFixture'",
            ),
        ).isEqualTo(2)
        assertThat(queryForLong(databasePath, "SELECT COUNT(*) FROM v_flaky_candidates"))
            .isEqualTo(1)
        assertThat(
            queryForString(
                databasePath,
                "SELECT latest_status FROM v_flaky_candidates WHERE class_name LIKE '%FlakyFixture'",
            ),
        ).isEqualTo("PASSED")
        assertThat(queryForLong(databasePath, "SELECT SUM(occurrence_count) FROM v_failure_fingerprints"))
            .isEqualTo(3)
        assertThat(
            queryForLong(
                databasePath,
                "SELECT fail_count FROM v_daily_status_trend ORDER BY execution_day_utc DESC LIMIT 1",
            ),
        ).isGreaterThanOrEqualTo(3)
    }

    @Test
    fun usesSingleRunRowWhenClassesExecuteConcurrently() {
        val databasePath = temporaryDatabasePath()

        val summary =
            executeFixtures(
                databasePath,
                ConcurrentFixtureA::class.java,
                ConcurrentFixtureB::class.java,
                ConcurrentFixtureC::class.java,
                ConcurrentFixtureD::class.java,
            )

        assertThat(summary.summary.testsSucceededCount).isEqualTo(4)
        assertThat(queryForLong(databasePath, "SELECT COUNT(*) FROM test_run")).isEqualTo(1)
        assertThat(queryForLong(databasePath, "SELECT COUNT(*) FROM test_execution"))
            .isEqualTo(4)
        assertThat(queryForLong(databasePath, "SELECT total_tests FROM v_run_summary"))
            .isEqualTo(4)
    }

    private fun executeFixtures(
        databasePath: Path,
        vararg fixtureClasses: Class<*>,
    ): SummaryGeneratingListener {
        val previousPath = System.getProperty(TestStatisticsDataSourceFactory.DB_PATH_PROPERTY)
        val previousEnabled = System.getProperty(TestStatisticsDataSourceFactory.ENABLED_PROPERTY)
        val previousFixtureEnabled = System.getProperty(FIXTURE_PROPERTY)
        try {
            System.setProperty(TestStatisticsDataSourceFactory.DB_PATH_PROPERTY, databasePath.toString())
            System.setProperty(TestStatisticsDataSourceFactory.ENABLED_PROPERTY, "true")
            System.setProperty(FIXTURE_PROPERTY, "true")

            val request: LauncherDiscoveryRequest =
                LauncherDiscoveryRequestBuilder
                    .request()
                    .selectors(
                        fixtureClasses.map { DiscoverySelectors.selectClass(it) as DiscoverySelector },
                    ).build()
            val launcher = LauncherFactory.create()
            val listener = SummaryGeneratingListener()
            launcher.registerTestExecutionListeners(listener)
            launcher.execute(request)
            return listener
        } finally {
            restoreProperty(TestStatisticsDataSourceFactory.DB_PATH_PROPERTY, previousPath)
            restoreProperty(TestStatisticsDataSourceFactory.ENABLED_PROPERTY, previousEnabled)
            restoreProperty(FIXTURE_PROPERTY, previousFixtureEnabled)
        }
    }

    private fun restoreProperty(
        key: String,
        value: String?,
    ) {
        if (value == null) {
            System.clearProperty(key)
        } else {
            System.setProperty(key, value)
        }
    }

    private fun temporaryDatabasePath(): Path {
        val directory = Files.createTempDirectory("test-statistics-")
        return directory.resolve("test-statistics.db")
    }

    private fun queryForLong(
        databasePath: Path,
        sql: String,
    ): Long {
        DriverManager.getConnection("jdbc:turso:$databasePath").use { connection ->
            connection.prepareStatement(sql).use { stmt ->
                stmt.executeQuery().use { rs ->
                    assertThat(rs.next()).isTrue()
                    return rs.getLong(1)
                }
            }
        }
    }

    private fun queryForString(
        databasePath: Path,
        sql: String,
    ): String {
        DriverManager.getConnection("jdbc:turso:$databasePath").use { connection ->
            connection.prepareStatement(sql).use { stmt ->
                stmt.executeQuery().use { rs ->
                    assertThat(rs.next()).isTrue()
                    return rs.getString(1)
                }
            }
        }
    }

    private fun queryForStrings(
        databasePath: Path,
        sql: String,
    ): List<String> {
        DriverManager.getConnection("jdbc:turso:$databasePath").use { connection ->
            connection.prepareStatement(sql).use { stmt ->
                stmt.executeQuery().use { rs ->
                    val results = mutableListOf<String>()
                    val columnCount = rs.metaData.columnCount
                    while (rs.next()) {
                        for (i in 1..columnCount) {
                            results.add(rs.getString(i))
                        }
                    }
                    return results
                }
            }
        }
    }

    companion object {
        private const val FIXTURE_PROPERTY = "teststats.launcher-fixture.enabled"
    }
}

// Fixture classes for embedded launcher tests

@EnabledIfSystemProperty(named = "teststats.launcher-fixture.enabled", matches = "true")
class PassingFixture {
    @Test
    fun passes() {
    }
}

@EnabledIfSystemProperty(named = "teststats.launcher-fixture.enabled", matches = "true")
class FailingFixture {
    @Test
    fun fails() {
        fail<Nothing>("intentional failure")
    }
}

@EnabledIfSystemProperty(named = "teststats.launcher-fixture.enabled", matches = "true")
class DisabledFixture {
    @Disabled("fixture disabled")
    @Test
    fun disabled() {
    }
}

@EnabledIfSystemProperty(named = "teststats.launcher-fixture.enabled", matches = "true")
class PayloadArtifactFixture {
    @Test
    fun recordsPayloadAndArtifact() {
        TestStats.recordInput(
            buildJsonObject {
                put("tenant", "acme")
                put("attempt", 1)
            },
        )
        TestStats.recordArtifactPath("trace", Path.of("artifacts", "trace.zip"), "Playwright trace")
    }
}

@EnabledIfSystemProperty(named = "teststats.launcher-fixture.enabled", matches = "true")
class FlakyFixture {
    @Test
    fun flipsFromFailToPass() {
        if (ATTEMPTS.getAndIncrement() == 0) {
            fail<Nothing>("transient issue")
        }
    }

    companion object {
        private val ATTEMPTS = AtomicInteger()

        fun reset() {
            ATTEMPTS.set(0)
        }
    }
}

@EnabledIfSystemProperty(named = "teststats.launcher-fixture.enabled", matches = "true")
class AlwaysFailingFingerprintFixture {
    @Test
    fun failsWithSameFingerprint(): Unit = throw IllegalStateException("fingerprint failure")
}

@EnabledIfSystemProperty(named = "teststats.launcher-fixture.enabled", matches = "true")
class ConcurrentFixtureA {
    @Test
    fun executes() {
        Thread.sleep(100)
    }
}

@EnabledIfSystemProperty(named = "teststats.launcher-fixture.enabled", matches = "true")
class ConcurrentFixtureB {
    @Test
    fun executes() {
        Thread.sleep(100)
    }
}

@EnabledIfSystemProperty(named = "teststats.launcher-fixture.enabled", matches = "true")
class ConcurrentFixtureC {
    @Test
    fun executes() {
        Thread.sleep(100)
    }
}

@EnabledIfSystemProperty(named = "teststats.launcher-fixture.enabled", matches = "true")
class ConcurrentFixtureD {
    @Test
    fun executes() {
        Thread.sleep(100)
    }
}
