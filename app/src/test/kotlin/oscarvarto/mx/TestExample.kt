package oscarvarto.mx

import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import io.kotest.matchers.shouldBe
import oscarvarto.mx.playwright.PlaywrightFunSpec

class TestExample :
    PlaywrightFunSpec({
        test("should click button") {
            page.navigate("""data:text/html,<script>var result;</script><button onclick='result="Clicked"'>Go</button>""")
            page.locator("button").click()
            page.evaluate("result") shouldBe "Clicked"
        }

        test("should check the box") {
            page.setContent("<input id='checkbox' type='checkbox'></input>")
            page.locator("input").check()
            page.evaluate("window['checkbox'].checked") shouldBe true
        }

        test("should search wiki") {
            page.navigate("https://www.wikipedia.org/")
            page.locator("input[name=\"search\"]").click()
            page.locator("input[name=\"search\"]").fill("playwright")
            page.locator("input[name=\"search\"]").press("Enter")
            assertThat(page).hasURL("https://en.wikipedia.org/wiki/Playwright")
        }
    })
