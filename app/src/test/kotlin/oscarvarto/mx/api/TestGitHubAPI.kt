package oscarvarto.mx.api

import com.microsoft.playwright.APIRequest
import com.microsoft.playwright.APIRequestContext
import com.microsoft.playwright.APIResponse
import com.microsoft.playwright.Page
import com.microsoft.playwright.junit.Options
import com.microsoft.playwright.junit.OptionsFactory
import com.microsoft.playwright.junit.UsePlaywright
import com.microsoft.playwright.options.RequestOptions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import oscarvarto.mx.aws.SecretsProvider
import java.util.UUID

/**
 * Tests for the GitHub Issues API using Playwright's API testing support.
 *
 * Demonstrates:
 * - Creating and deleting GitHub repositories via API as test fixtures
 * - Creating issues via API and verifying them through the same API
 * - Mixing API calls with browser-based assertions using [Page]
 *
 * Extends [PlaywrightApiTest] to inherit the authentication state reuse
 * utility for tests that need both API and browser-based verification.
 *
 * ## Prerequisites
 *
 * This test class is **opt-in** and only runs when
 * `-Dplaywright.github.integration.enabled=true` is set.
 *
 * When enabled, provide credentials through either environment variables or the
 * configured secrets provider mappings:
 *
 * - `GITHUB_USER` -- your GitHub username
 * - `GITHUB_API_TOKEN` -- a personal access token with `repo` scope
 *
 * @see [Playwright: API testing](https://playwright.dev/java/docs/api-testing)
 */
@UsePlaywright(TestGitHubAPI.GitHubApiOptions::class)
@EnabledIfSystemProperty(named = "playwright.github.integration.enabled", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestGitHubAPI : PlaywrightApiTest() {
    private val repo = "test-repo-${UUID.randomUUID().toString().substring(0, 8)}"

    /**
     * [OptionsFactory] that configures the [APIRequestContext] for the GitHub REST API.
     *
     * Inherits browser settings (engine, headless, channel) from
     * [PlaywrightApiTest.browserOptions] and layers GitHub-specific config on top:
     * - Base URL: `https://api.github.com`
     * - `Accept: application/vnd.github.v3+json` per
     *   [GitHub API guidelines](https://docs.github.com/en/rest)
     * - `Authorization: token <PAT>` using the `GITHUB_API_TOKEN` secret resolved by [SecretsProvider]
     */
    class GitHubApiOptions : OptionsFactory {
        override fun getOptions(): Options {
            val headers =
                mapOf(
                    "Accept" to "application/vnd.github.v3+json",
                    "Authorization" to "token ${apiToken()}",
                )
            return browserOptions()
                .setApiRequestOptions(
                    APIRequest
                        .NewContextOptions()
                        .setBaseURL("https://api.github.com")
                        .setExtraHTTPHeaders(headers),
                )
        }
    }

    /**
     * Creates the test repository on GitHub before all tests run.
     */
    @BeforeAll
    fun beforeAll(request: APIRequestContext) {
        // Clean up stale repo from a previous crashed run (ignore 404/failure)
        request.delete("/repos/${githubUser()}/$repo")

        val newRepo: APIResponse =
            request.post("/user/repos", RequestOptions.create().setData(mapOf("name" to repo)))
        assertTrue(newRepo.ok(), newRepo.text())
    }

    /**
     * Deletes the test repository on GitHub after all tests complete.
     */
    @AfterAll
    fun afterAll(request: APIRequestContext) {
        val deletedRepo: APIResponse = request.delete("/repos/${githubUser()}/$repo")
        assertTrue(deletedRepo.ok(), deletedRepo.text())
    }

    /**
     * Creates a bug report issue via the GitHub API and verifies it appears in the
     * repository's issue list with the correct title and body.
     */
    @Test
    fun shouldCreateBugReport(request: APIRequestContext) {
        val data = mapOf("title" to "[Bug] report 1", "body" to "Bug description")
        val newIssue: APIResponse =
            request.post(
                "/repos/${githubUser()}/$repo/issues",
                RequestOptions.create().setData(data),
            )
        assertTrue(newIssue.ok(), newIssue.text())

        val issues: APIResponse = request.get("/repos/${githubUser()}/$repo/issues")
        assertTrue(issues.ok(), issues.text())
        val json = Json.parseToJsonElement(issues.text()).jsonArray
        val issue = findIssueByTitle(json, "[Bug] report 1")
        assertNotNull(issue)
        assertEquals("Bug description", issue!!.jsonObject["body"]?.jsonPrimitive?.content, issue.toString())
    }

    /**
     * Creates a feature request issue via the GitHub API and verifies it appears in the
     * repository's issue list with the correct title and body.
     */
    @Test
    fun shouldCreateFeatureRequest(request: APIRequestContext) {
        val data = mapOf("title" to "[Feature] request 1", "body" to "Feature description")
        val newIssue: APIResponse =
            request.post(
                "/repos/${githubUser()}/$repo/issues",
                RequestOptions.create().setData(data),
            )
        assertTrue(newIssue.ok(), newIssue.text())

        val issues: APIResponse = request.get("/repos/${githubUser()}/$repo/issues")
        assertTrue(issues.ok(), issues.text())
        val json = Json.parseToJsonElement(issues.text()).jsonArray
        val issue = findIssueByTitle(json, "[Feature] request 1")
        assertNotNull(issue)
        assertEquals("Feature description", issue!!.jsonObject["body"]?.jsonPrimitive?.content, issue.toString())
    }

    /**
     * Creates a feature request issue via API, then navigates to the repository's issue
     * page in a browser and asserts the newly created issue appears first in the list.
     */
    @Test
    fun lastCreatedIssueShouldBeFirstInTheList(
        request: APIRequestContext,
        page: Page,
    ) {
        val data = mapOf("title" to "[Feature] request 2", "body" to "Feature description")
        val newIssue: APIResponse =
            request.post(
                "/repos/${githubUser()}/$repo/issues",
                RequestOptions.create().setData(data),
            )
        assertTrue(newIssue.ok(), newIssue.text())

        page.navigate("https://github.com/${githubUser()}/$repo/issues")
        val firstIssue = page.locator("a[data-hovercard-type='issue']").first()
        com.microsoft.playwright.assertions.PlaywrightAssertions
            .assertThat(firstIssue)
            .hasText("[Feature] request 2")
    }

    companion object {
        private fun findIssueByTitle(
            issues: JsonArray,
            title: String,
        ): JsonObject? {
            for (item in issues) {
                val obj = item.jsonObject
                val itemTitle = obj["title"]?.jsonPrimitive?.content
                if (itemTitle == title) {
                    return obj
                }
            }
            return null
        }

        private fun githubUser(): String = SecretsProvider.require("GITHUB_USER")

        private fun apiToken(): String = SecretsProvider.require("GITHUB_API_TOKEN")
    }
}
