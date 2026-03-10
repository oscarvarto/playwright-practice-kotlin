package oscarvarto.mx

import com.microsoft.playwright.Locator
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import com.microsoft.playwright.options.FilePayload
import com.microsoft.playwright.options.KeyboardModifier
import com.microsoft.playwright.options.MouseButton
import com.microsoft.playwright.options.SelectOption
import io.kotest.matchers.shouldBe
import oscarvarto.mx.playwright.PlaywrightFunSpec
import java.nio.file.Paths

class ActionsGuideTest :
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

        // == https://playwright.dev/java/docs/input ==

        // https://playwright.dev/java/docs/input#text-input
        xtest("textInput - syntax example only") {
            // Text input
            page.getByRole(AriaRole.TEXTBOX).fill("Peter")

            // Date input
            page.getByLabel("Birth date").fill("2020-02-02")

            // Time input
            page.getByLabel("Appointment time").fill("13:15")

            // Local datetime input
            page.getByLabel("Local time").fill("2020-03-02T05:15")
        }

        // https://playwright.dev/java/docs/input#checkboxes-and-radio-buttons
        xtest("checkBoxesAndRadioButtons - syntax example only") {
            // Check the checkbox
            page.getByLabel("I agree to the terms above").check()

            // Assert the checked state
            assertThat(page.getByLabel("Subscribe to newsletter")).isChecked()

            // Select the radio button
            page.getByLabel("XL").check()
        }

        // https://playwright.dev/java/docs/input#select-options
        xtest("selectOptions - syntax example only") {
            // Single selection matching the value or label
            page.getByLabel("Choose a color").selectOption("blue")

            // Single selection matching the label
            page.getByLabel("Choose a color").selectOption(SelectOption().setLabel("Blue"))

            // Multiple selected items
            page.getByLabel("Choose multiple colors").selectOption(arrayOf("red", "green", "blue"))
        }

        // https://playwright.dev/java/docs/input#mouse-click
        xtest("mouseClick - syntax example only") {
            // Generic click
            page.getByRole(AriaRole.BUTTON).click()

            // Double click
            page.getByText("Item").dblclick()

            // Right click
            page.getByText("Item").click(Locator.ClickOptions().setButton(MouseButton.RIGHT))

            // Shift + click
            page.getByText("Item").click(Locator.ClickOptions().setModifiers(listOf(KeyboardModifier.SHIFT)))

            // Ctrl + click on Windows and Linux
            // Meta + click on macOS
            page
                .getByText("Item")
                .click(Locator.ClickOptions().setModifiers(listOf(KeyboardModifier.CONTROLORMETA)))

            // Hover over element
            page.getByText("Item").hover()

            // Click the top left corner
            page.getByText("Item").click(Locator.ClickOptions().setPosition(0.0, 0.0))
        }

        // https://playwright.dev/java/docs/input#type-characters
        xtest("typeCharacters - only if special keyboard handling") {
            // Press keys one by one
            page.locator("#area").pressSequentially("Hello World!")
        }

        // https://playwright.dev/java/docs/input#keys-and-shortcuts
        xtest("keysAndShortcuts - syntax example only") {
            // Hit Enter
            page.getByText("Submit").press("Enter")

            // Dispatch Control+Right
            page.getByRole(AriaRole.TEXTBOX).press("Control+ArrowRight")

            // Press $ sign on keyboard
            page.getByRole(AriaRole.TEXTBOX).press("$")
        }

        // https://playwright.dev/java/docs/input#upload-files
        xtest("uploadFiles - syntax example only") {
            // Select one file
            page.getByLabel("Upload file").setInputFiles(Paths.get("myfile.pdf"))

            // Select multiple files
            page.getByLabel("Upload files").setInputFiles(arrayOf(Paths.get("file1.txt"), Paths.get("file2.txt")))

            // Select a directory
            page.getByLabel("Upload directory").setInputFiles(Paths.get("mydir"))

            // Remove all the selected files
            page.getByLabel("Upload file").setInputFiles(arrayOf<java.nio.file.Path>())

            // Upload buffer from memory
            page
                .getByLabel("Upload file")
                .setInputFiles(FilePayload("file.txt", "text/plain", "this is test".toByteArray(Charsets.UTF_8)))

            // If you don't have input element in hand (it is created dynamically), you can handle the
            // Page.onFileChooser(handler) event or use a corresponding waiting method upon your action:
            val fileChooser = page.waitForFileChooser { page.getByLabel("Upload file").click() }
            fileChooser.setFiles(Paths.get("myfile.pdf"))
        }

        xtest("severalSmallActions - syntax example only") {
            // https://playwright.dev/java/docs/input#focus-element
            page.getByLabel("Password").focus()

            // https://playwright.dev/java/docs/input#drag-and-drop
            page.locator("#item-to-be-dragged").dragTo(page.locator("#item-to-drop-at"))

            // https://playwright.dev/java/docs/input#dragging-manually
            page.locator("#item-to-be-dragged").hover()
            page.mouse().down()
            page.locator("#item-to-drop-at").hover()
            page.mouse().up()
        }

        // https://playwright.dev/java/docs/input#scrolling
        xtest("scrolling - syntax example only") {
            // Scrolls automatically so that button is visible
            page.getByRole(AriaRole.BUTTON).click()

            // **Manually scrolling**: only when necessary

            // Scroll the footer into view, forcing an "infinite list" to load more content
            page.getByText("Footer text").scrollIntoViewIfNeeded()

            // Position the mouse and scroll with the mouse wheel
            page.getByTestId("scrolling-container").hover()

            // Alternatively, programmatically scroll a specific element
            page.getByTestId("scrolling-container").evaluate("e => e.scrollTop += 100")
        }
    })
