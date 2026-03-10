package oscarvarto.mx.api

import com.microsoft.playwright.APIRequestContext
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.junit.Options
import com.typesafe.config.Config
import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigFactory
import org.junit.jupiter.api.TestInstance

/**
 * Base class for Playwright API tests that supports **reuse of authentication state**
 * between [APIRequestContext] and [BrowserContext], and centralizes browser
 * configuration via [TypeSafe Config](https://github.com/lightbend/config).
 *
 * ## When to use
 *
 * Use this base class when your API tests need to transition from API-level authentication
 * to browser-based testing while preserving the authenticated session.
 *
 * ## How it works
 *
 * 1. Authenticate via API calls (e.g., POST to a login endpoint)
 * 2. Call [createAuthenticatedBrowserContext] to capture the storage state and create
 *    a pre-authenticated browser context
 * 3. Use the resulting [BrowserContext] to open pages that are already logged in
 *
 * ## Browser configuration
 *
 * Browser selection is read from `playwright.json` on the test classpath.
 * Settings can be overridden via system properties:
 *
 * ```sh
 * ./gradlew test -Dplaywright.browser=firefox
 * ./gradlew test -Dplaywright.browser=chromium -Dplaywright.channel=chrome
 * ```
 *
 * @see [Playwright: Browsers](https://playwright.dev/java/docs/browsers)
 * @see [Playwright: Reuse authentication state](https://playwright.dev/java/docs/api-testing#reuse-authentication-state)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class PlaywrightApiTest {
    companion object {
        private val PW_CONFIG: Config = loadPlaywrightConfig()

        private fun loadPlaywrightConfig(): Config {
            val root = ConfigFactory.load("playwright")
            return try {
                root.getConfig("playwright")
            } catch (e: ConfigException.Missing) {
                throw IllegalStateException(
                    "playwright.json must contain a top-level \"playwright\" namespace, " +
                        "e.g. { \"playwright\": { \"browser\": \"chromium\" } }",
                    e,
                )
            }
        }

        /**
         * Builds an [Options] instance pre-configured with browser settings from
         * `playwright.json`.
         */
        @JvmStatic
        protected fun browserOptions(): Options {
            val options = Options()
            if (PW_CONFIG.hasPath("browser")) {
                options.setBrowserName(PW_CONFIG.getString("browser"))
            }
            if (PW_CONFIG.hasPath("channel")) {
                options.setChannel(PW_CONFIG.getString("channel"))
            }
            if (PW_CONFIG.hasPath("headless")) {
                options.setHeadless(PW_CONFIG.getBoolean("headless"))
            }
            return options
        }
    }

    /**
     * Creates a [BrowserContext] pre-loaded with the storage state captured from an
     * authenticated [APIRequestContext].
     */
    protected fun createAuthenticatedBrowserContext(
        apiContext: APIRequestContext,
        browser: Browser,
    ): BrowserContext {
        val state = apiContext.storageState()
        return browser.newContext(Browser.NewContextOptions().setStorageState(state))
    }
}
