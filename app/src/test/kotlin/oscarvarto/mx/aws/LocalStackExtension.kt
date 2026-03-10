package oscarvarto.mx.aws

import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.utility.DockerImageName

/**
 * JUnit 5 extension that manages AWS testing infrastructure.
 *
 * Supports two modes:
 * 1. **LOCAL mode**: Connects to an already running LocalStack instance.
 * 2. **TESTCONTAINERS mode**: Starts a fresh LocalStack Docker container.
 */
class LocalStackExtension :
    BeforeAllCallback,
    AfterAllCallback {
    override fun beforeAll(context: ExtensionContext) {
        val store = getStore(context)

        if (store.get(ENDPOINT_KEY) != null) return

        val config = AwsTestingConfig.getInstance()
        store.put(MODE_KEY, config.mode.name)

        if (config.isLocalMode) {
            logger.info { "Using LOCAL mode - connecting to running LocalStack instance" }
            setupLocalMode(config, store)
        } else {
            logger.info { "Using TESTCONTAINERS mode - starting LocalStack container" }
            setupTestcontainersMode(config, store)
        }
    }

    private fun setupLocalMode(
        config: AwsTestingConfig,
        store: ExtensionContext.Store,
    ) {
        val endpoint = config.localEndpoint
        val region = config.localRegion

        logger.info { "Configuring for LocalStack at: $endpoint (region: $region)" }
        System.setProperty("LOCALSTACK_ENDPOINT", endpoint)
        store.put(ENDPOINT_KEY, endpoint)
        provisionServices(config, endpoint, region)
    }

    private fun setupTestcontainersMode(
        config: AwsTestingConfig,
        store: ExtensionContext.Store,
    ) {
        val image = config.testcontainersImage
        val region = config.testcontainersRegion

        logger.info { "Starting LocalStack container with image: $image" }

        val services =
            config.services
                .mapNotNull { ServiceProvisioner.forService(it) }
                .map { it.service }
                .toTypedArray()

        localstack =
            LocalStackContainer(DockerImageName.parse(image))
                .withServices(*services)

        localstack!!.start()

        val endpoint = localstack!!.endpoint.toString()
        logger.info { "LocalStack container started at: $endpoint (region: $region)" }

        System.setProperty("LOCALSTACK_ENDPOINT", endpoint)
        store.put(ENDPOINT_KEY, endpoint)
        provisionServices(config, endpoint, region)
    }

    private fun provisionServices(
        config: AwsTestingConfig,
        endpoint: String,
        region: String,
    ) {
        for (name in config.services) {
            val provisioner = ServiceProvisioner.forService(name)
            if (provisioner != null) {
                val svcConfig = config.getServiceConfig(name)
                provisioner.provision(endpoint, region, svcConfig)
            } else {
                logger.warn { "No provisioner found for service: $name" }
            }
        }
    }

    override fun afterAll(context: ExtensionContext) {
        val store = getStore(context)

        if (context.parent.isPresent &&
            context.parent
                .get()
                .testClass.isEmpty
        ) {
            val mode = store.get(MODE_KEY, String::class.java)

            if ("TESTCONTAINERS" == mode && localstack != null) {
                logger.info { "Stopping LocalStack container..." }
                localstack!!.stop()
                localstack = null
            }

            System.clearProperty("LOCALSTACK_ENDPOINT")
            logger.info { "Cleaned up LocalStack configuration" }
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
        private const val ENDPOINT_KEY = "localstack.endpoint"
        private const val MODE_KEY = "localstack.mode"

        private var localstack: LocalStackContainer? = null

        fun getEndpoint(context: ExtensionContext): String? = getStore(context).get(ENDPOINT_KEY, String::class.java)

        fun getContainer(): LocalStackContainer? = localstack

        private fun getStore(context: ExtensionContext): ExtensionContext.Store =
            context.root.getStore(ExtensionContext.Namespace.create(LocalStackExtension::class.java.name))
    }
}
