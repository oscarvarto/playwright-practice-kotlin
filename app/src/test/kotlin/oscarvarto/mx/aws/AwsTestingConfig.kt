package oscarvarto.mx.aws

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Configuration for AWS testing environments.
 *
 * Reads from `aws-testing.json` on the test classpath and provides settings
 * for both LocalStack modes:
 * - **local**: Connects to a running LocalStack instance (fast, for development)
 * - **testcontainers**: Starts a fresh LocalStack container (isolated, for CI/CD)
 */
class AwsTestingConfig private constructor() {
    private val config: Config = ConfigFactory.load(CONFIG_NAME).getConfig(ROOT_PATH)
    val mode: Mode = Mode.valueOf(config.getString("mode").uppercase())

    enum class Mode {
        LOCAL,
        TESTCONTAINERS,
    }

    val isLocalMode: Boolean get() = mode == Mode.LOCAL
    val isTestcontainersMode: Boolean get() = mode == Mode.TESTCONTAINERS

    val localEndpoint: String
        get() {
            check(isLocalMode) { "Not in local mode. Current mode: $mode" }
            return config.getString("local.endpoint")
        }

    val localRegion: String
        get() {
            check(isLocalMode) { "Not in local mode. Current mode: $mode" }
            return config.getString("local.region")
        }

    val testcontainersImage: String
        get() {
            check(isTestcontainersMode) { "Not in testcontainers mode. Current mode: $mode" }
            return config.getString("testcontainers.image")
        }

    val testcontainersRegion: String
        get() {
            check(isTestcontainersMode) { "Not in testcontainers mode. Current mode: $mode" }
            return config.getString("testcontainers.region")
        }

    val services: List<String> get() = config.getStringList("services")

    fun getServiceConfig(serviceName: String): Config =
        if (config.hasPath(serviceName)) config.getConfig(serviceName) else ConfigFactory.empty()

    companion object {
        private val logger = KotlinLogging.logger {}
        private const val CONFIG_NAME = "aws-testing"
        private const val ROOT_PATH = "aws-testing"

        @Volatile
        private var instance: AwsTestingConfig? = null

        @JvmStatic
        fun getInstance(): AwsTestingConfig =
            instance ?: synchronized(this) {
                instance ?: AwsTestingConfig().also {
                    instance = it
                    logger.info { "AWS Testing configured with mode: ${it.mode}" }
                }
            }

        @JvmStatic
        fun reset() {
            synchronized(this) {
                instance = null
                logger.info { "AwsTestingConfig has been reset" }
            }
        }
    }
}
