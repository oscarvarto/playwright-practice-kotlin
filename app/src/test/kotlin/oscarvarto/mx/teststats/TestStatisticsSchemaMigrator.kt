package oscarvarto.mx.teststats

import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

internal class TestStatisticsSchemaMigrator {
    fun migrate(dataSourceFactory: TestStatisticsDataSourceFactory) {
        val configuration =
            Flyway
                .configure()
                .dataSource(dataSourceFactory.sqliteJdbcUrl(), null, null)
                .locations("classpath:test-statistics/schema")
                .validateMigrationNaming(true)

        if (shouldBaselineLegacyDatabase(dataSourceFactory)) {
            configuration.baselineOnMigrate(true).baselineVersion("2").baselineDescription("legacy custom migrator")
        }

        configuration.load().migrate()
    }

    private fun shouldBaselineLegacyDatabase(dataSourceFactory: TestStatisticsDataSourceFactory): Boolean {
        DriverManager.getConnection(dataSourceFactory.sqliteJdbcUrl()).use { connection ->
            return tableExists(connection, "schema_migrations") && !tableExists(connection, "flyway_schema_history")
        }
    }

    private fun tableExists(
        connection: Connection,
        tableName: String,
    ): Boolean {
        connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?").use { stmt ->
            stmt.setString(1, tableName)
            stmt.executeQuery().use { rs ->
                return rs.next()
            }
        }
    }
}
