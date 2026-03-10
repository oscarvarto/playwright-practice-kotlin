package oscarvarto.mx.playwright

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import io.kotest.core.spec.style.FunSpec

abstract class PlaywrightFunSpec(
    body: PlaywrightFunSpec.() -> Unit = {},
) : FunSpec() {
    lateinit var playwright: Playwright
    lateinit var browser: Browser
    lateinit var context: BrowserContext
    lateinit var page: Page

    init {
        beforeSpec {
            playwright = Playwright.create()
            browser = playwright.chromium().launch()
        }
        afterSpec {
            browser.close()
            playwright.close()
        }
        beforeTest {
            context = browser.newContext()
            page = context.newPage()
        }
        afterTest { (_, _) ->
            context.close()
        }
        body()
    }
}
