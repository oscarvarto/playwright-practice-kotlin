package oscarvarto.mx.aws

import com.typesafe.config.Config
import org.testcontainers.containers.localstack.LocalStackContainer

/**
 * Strategy interface for provisioning a specific AWS service in LocalStack.
 *
 * Implementations set up test fixtures (secrets, buckets, queues, etc.)
 * after LocalStack is available. [LocalStackExtension] discovers provisioners
 * via [forService] and invokes [provision] for each service
 * listed in `aws-testing.json`.
 */
interface ServiceProvisioner {
    val service: LocalStackContainer.Service

    fun provision(
        endpoint: String,
        region: String,
        serviceConfig: Config,
    )

    companion object {
        fun forService(serviceName: String): ServiceProvisioner? =
            when (serviceName) {
                "secretsmanager" -> SecretsManagerProvisioner()
                else -> null
            }
    }
}
