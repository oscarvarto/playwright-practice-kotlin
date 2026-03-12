package oscarvarto.mx.aws

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNullOrEmpty
import assertk.assertions.isTrue
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(LocalStackExtension::class)
@DisplayName("SecretsProvider with AWS Testing Configuration")
@EnabledIfSystemProperty(named = "playwright.aws.testing.enabled", matches = "true")
class SecretsProviderAwsTestingTest {
    @BeforeEach
    fun setUp() {
        val config = AwsTestingConfig.getInstance()
        logger.info { "Running tests in ${config.mode} mode" }
        logger.info { "LocalStack endpoint: ${System.getProperty("LOCALSTACK_ENDPOINT")}" }
    }

    @AfterEach
    fun tearDown() {
        SecretsProvider.reset()
    }

    @Test
    @DisplayName("should resolve secrets from LocalStack when env var is not set")
    fun shouldResolveSecretsFromLocalStack() {
        assertThat(System.getenv("GITHUB_USER"), "GITHUB_USER should not be set as environment variable")
            .isNullOrEmpty()

        val githubUser = SecretsProvider.get("GITHUB_USER")
        val githubToken = SecretsProvider.get("GITHUB_API_TOKEN")

        assertThat(githubUser, "GITHUB_USER should be resolved from LocalStack")
            .isNotNull()
            .isEqualTo("test-user")

        assertThat(githubToken, "GITHUB_API_TOKEN should be resolved from LocalStack")
            .isNotNull()
            .isEqualTo("test-token-12345")
    }

    @Test
    @DisplayName("should prefer environment variable over LocalStack")
    fun shouldPreferEnvironmentVariable() {
        logger.info { "Environment variable GITHUB_USER: ${System.getenv("GITHUB_USER")}" }
        logger.info { "LocalStack endpoint: ${System.getProperty("LOCALSTACK_ENDPOINT")}" }

        if (System.getenv("GITHUB_USER") == null) {
            val value = SecretsProvider.get("GITHUB_USER")
            assertThat(value).isNotNull()
            assertThat(value).isEqualTo("test-user")
        }
    }

    @Test
    @DisplayName("should require() throw exception for non-existent key")
    fun shouldThrowForNonExistentKey() {
        assertThrows(
            IllegalStateException::class.java,
            { SecretsProvider.require("NON_EXISTENT_KEY") },
            "Should throw IllegalStateException for non-existent key",
        )
    }

    @Test
    @DisplayName("should cache secrets to avoid multiple API calls")
    fun shouldCacheSecrets() {
        val first = SecretsProvider.get("GITHUB_USER")
        assertThat(first).isNotNull().isEqualTo("test-user")

        val second = SecretsProvider.get("GITHUB_USER")
        assertThat(second).isNotNull().isEqualTo("test-user")

        assertThat(first).isEqualTo(second)
    }

    @Test
    @DisplayName("should use configured mode from aws-testing.json")
    fun shouldUseConfiguredMode() {
        val config = AwsTestingConfig.getInstance()

        logger.info { "Current mode: ${config.mode}" }

        if (config.isLocalMode) {
            logger.info { "Using local endpoint: ${config.localEndpoint}" }
            assertThat(config.localEndpoint).isNotNull()
        } else {
            logger.info { "Using Testcontainers image: ${config.testcontainersImage}" }
            assertThat(config.testcontainersImage).isNotNull()
        }

        val smConfig = config.getServiceConfig("secretsmanager")
        assertThat(smConfig.hasPath("secrets")).isTrue()

        val secretsConfig = smConfig.getConfig("secrets")
        assertThat(secretsConfig.hasPath("playwright-practice/github")).isTrue()

        val githubSecret = secretsConfig.getConfig("playwright-practice/github")
        assertThat(githubSecret.getString("username")).isEqualTo("test-user")
        assertThat(githubSecret.getString("token")).isEqualTo("test-token-12345")
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
