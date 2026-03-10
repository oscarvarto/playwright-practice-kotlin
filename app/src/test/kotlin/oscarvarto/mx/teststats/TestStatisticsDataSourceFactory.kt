package oscarvarto.mx.teststats

import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException
import java.util.logging.Logger
import javax.sql.DataSource

internal class TestStatisticsDataSourceFactory {
    fun isEnabled(): Boolean = System.getProperty(ENABLED_PROPERTY, "true").toBoolean()

    fun resolveDatabasePath(): Path {
        val workingDirectory = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        val configuredPath = System.getProperty(DB_PATH_PROPERTY)
        var databasePath =
            if (configuredPath.isNullOrBlank()) {
                workingDirectory.resolve(DEFAULT_DB_PATH)
            } else {
                Paths.get(configuredPath)
            }
        if (!databasePath.isAbsolute) {
            databasePath = workingDirectory.resolve(databasePath)
        }
        return databasePath.normalize()
    }

    fun createDataSource(): DataSource {
        val databasePath = resolveDatabasePath()
        ensureParentDirectory(databasePath)
        val jdbcUrl = tursoJdbcUrl(databasePath)
        return DriverManagerDataSource(jdbcUrl)
    }

    fun tursoJdbcUrl(): String = tursoJdbcUrl(resolveDatabasePath())

    fun sqliteJdbcUrl(): String {
        val databasePath = resolveDatabasePath()
        ensureParentDirectory(databasePath)
        return "jdbc:sqlite:$databasePath"
    }

    private fun ensureParentDirectory(databasePath: Path) {
        val parent = databasePath.parent
        if (parent != null) {
            Files.createDirectories(parent)
        }
    }

    private fun tursoJdbcUrl(databasePath: Path): String = "jdbc:turso:$databasePath"

    private class DriverManagerDataSource(
        private val jdbcUrl: String,
    ) : DataSource {
        override fun getConnection(): Connection = DriverManager.getConnection(jdbcUrl)

        override fun getConnection(
            username: String?,
            password: String?,
        ): Connection = connection

        override fun getLogWriter(): PrintWriter? = DriverManager.getLogWriter()

        override fun setLogWriter(out: PrintWriter?) {
            DriverManager.setLogWriter(out)
        }

        override fun setLoginTimeout(seconds: Int) {
            DriverManager.setLoginTimeout(seconds)
        }

        override fun getLoginTimeout(): Int = DriverManager.getLoginTimeout()

        override fun getParentLogger(): Logger = throw SQLFeatureNotSupportedException("Parent logger is not supported.")

        override fun <T : Any?> unwrap(iface: Class<T>): T {
            if (iface.isInstance(this)) {
                return iface.cast(this)
            }
            throw SQLException("Unsupported unwrap type: ${iface.name}")
        }

        override fun isWrapperFor(iface: Class<*>?): Boolean = iface?.isInstance(this) == true
    }

    companion object {
        const val ENABLED_PROPERTY = "test.statistics.enabled"
        const val DB_PATH_PROPERTY = "test.statistics.db.path"
        const val RUN_LABEL_PROPERTY = "test.statistics.run.label"
        private const val DEFAULT_DB_PATH = "src/test/resources/test-statistics.db"
    }
}
