package oscarvarto.mx.aws

import com.typesafe.config.ConfigFactory
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Static utility for resolving test secrets with an **env-var-first** strategy.
 *
 * Resolution order for each logical key:
 *
 * 1. **Environment variable** — `System.getenv(key)`. If present, the value
 *    is returned immediately and no AWS call is made.
 * 2. **AWS Secrets Manager** — looked up via the mapping defined in
 *    `secrets.json` on the test classpath.
 */
object SecretsProvider {
    private val logger = KotlinLogging.logger {}
    private val SECRETS_CONFIG = ConfigFactory.load("secrets").getConfig("secrets")
    private val CACHE = ConcurrentHashMap<String, String>()
    private val CLIENT_LOCK = Any()

    @Volatile
    private var client: SecretsManagerClient? = null

    @JvmStatic
    fun get(key: String): String? {
        val envValue = System.getenv(key)
        if (envValue != null) {
            logger.debug { "Resolved '$key' from environment variable" }
            return envValue
        }

        val mappings = SECRETS_CONFIG.getConfig("mappings")
        if (!mappings.hasPath(key)) {
            logger.warn { "No mapping found for key '$key' in secrets.json" }
            return null
        }

        val mapping = mappings.getConfig(key)
        val secretName = mapping.getString("secretName")
        val rawSecret = CACHE.computeIfAbsent(secretName) { fetchSecret(it) }

        if (mapping.hasPath("field")) {
            val field = mapping.getString("field")
            val extracted = extractField(rawSecret, field)
            logger.info { "Resolved '$key' from AWS secret '$secretName' field '$field'" }
            return extracted
        }

        logger.info { "Resolved '$key' from AWS secret '$secretName'" }
        return rawSecret
    }

    @JvmStatic
    fun require(key: String): String =
        get(key) ?: throw IllegalStateException(
            "Secret '$key' not found in environment variables or AWS Secrets Manager",
        )

    private fun fetchSecret(secretName: String): String {
        logger.info { "Fetching secret '$secretName' from AWS Secrets Manager" }
        return getClient()
            .getSecretValue(GetSecretValueRequest.builder().secretId(secretName).build())
            .secretString()
    }

    private fun extractField(
        json: String,
        field: String,
    ): String? =
        try {
            val jsonElement = Json.parseToJsonElement(json)
            jsonElement.jsonObject[field]?.jsonPrimitive?.content
        } catch (e: Exception) {
            logger.warn { "Failed to parse secret as JSON for field '$field': ${e.message}" }
            null
        }

    private fun getClient(): SecretsManagerClient {
        var localRef = client
        if (localRef == null) {
            synchronized(CLIENT_LOCK) {
                localRef = client
                if (localRef == null) {
                    val region = SECRETS_CONFIG.getString("region")
                    var localstackEndpoint = System.getenv("LOCALSTACK_ENDPOINT")
                    if (localstackEndpoint.isNullOrEmpty()) {
                        localstackEndpoint = System.getProperty("LOCALSTACK_ENDPOINT")
                    }
                    logger.info { "Creating SecretsManagerClient for region '$region'" }

                    val builder = SecretsManagerClient.builder().region(Region.of(region))

                    if (!localstackEndpoint.isNullOrEmpty()) {
                        logger.info { "Using LocalStack endpoint: $localstackEndpoint" }
                        builder
                            .endpointOverride(URI.create(localstackEndpoint))
                            .credentialsProvider(
                                StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")),
                            )
                    }

                    localRef = builder.build()
                    client = localRef
                }
            }
        }
        return localRef!!
    }

    @JvmStatic
    fun reset() {
        synchronized(CLIENT_LOCK) {
            client?.close()
            client = null
            CACHE.clear()
            logger.info { "SecretsProvider has been reset" }
        }
    }
}
