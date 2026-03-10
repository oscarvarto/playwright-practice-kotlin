package oscarvarto.mx.aws

import com.typesafe.config.Config
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.testcontainers.containers.localstack.LocalStackContainer
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient
import software.amazon.awssdk.services.secretsmanager.model.CreateSecretRequest
import software.amazon.awssdk.services.secretsmanager.model.ResourceExistsException
import java.net.URI

/**
 * Provisioner for AWS Secrets Manager test fixtures.
 *
 * Reads the `"secrets"` block from the `"secretsmanager"` service config
 * in `aws-testing.json` and creates each secret in LocalStack.
 */
class SecretsManagerProvisioner : ServiceProvisioner {
    override val service: LocalStackContainer.Service = LocalStackContainer.Service.SECRETSMANAGER

    override fun provision(
        endpoint: String,
        region: String,
        serviceConfig: Config,
    ) {
        if (!serviceConfig.hasPath("secrets")) {
            logger.warn { "No secrets configured in secretsmanager service config" }
            return
        }

        logger.info { "Creating test secrets in LocalStack..." }

        val secretsConfig = serviceConfig.getConfig("secrets")
        val secretNames = secretsConfig.root().keys

        val client =
            SecretsManagerClient
                .builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(
                    StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")),
                ).build()

        client.use {
            for (secretName in secretNames) {
                val secretConfig = secretsConfig.getConfig(secretName)
                val jsonObj =
                    buildJsonObject {
                        for (field in secretConfig.root().keys) {
                            put(field, JsonPrimitive(secretConfig.getString(field)))
                        }
                    }
                val secretValue = jsonObj.toString()

                try {
                    it.createSecret(
                        CreateSecretRequest
                            .builder()
                            .name(secretName)
                            .secretString(secretValue)
                            .build(),
                    )
                    logger.info { "Created test secret: $secretName" }
                } catch (_: ResourceExistsException) {
                    logger.info { "Test secret already exists: $secretName" }
                }
            }
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
