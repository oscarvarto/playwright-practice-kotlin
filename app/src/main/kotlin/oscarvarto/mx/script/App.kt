package oscarvarto.mx.script

import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole

fun main() {
    Playwright.create().use { playwright ->
        val browser = playwright.chromium().launch()
        val page = browser.newPage()
        page.navigate("https://playwright.dev")

        // Expect a title "to contain" a substring.
        assertThat(page).hasTitle(
            java.util.regex.Pattern
                .compile("Playwright"),
        )

        // create a locator
        val getStarted = page.getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName("Get Started"))

        // Expect an attribute "to be strictly equal" to the value.
        assertThat(getStarted).hasAttribute("href", "/docs/intro")

        // Click the get started link.
        getStarted.click()

        // Expects page to have a heading with the name of Installation.
        assertThat(
            page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Installation")),
        ).isVisible()
    }
}
