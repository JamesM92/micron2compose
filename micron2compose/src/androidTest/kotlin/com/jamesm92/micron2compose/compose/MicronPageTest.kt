package com.jamesm92.micron2compose.compose

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.jamesm92.micron2compose.parser.LinkTarget
import com.jamesm92.micron2compose.parser.MicronConverter
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Compose UI smoke tests for the emission layer: renders real content
 * without throwing, and confirms the two pieces of genuine interactivity
 * (link taps, form field input) actually reach the callbacks/[MicronFormState]
 * they're supposed to.
 *
 * A [MicronFormState] test double backed by a plain `mutableStateMapOf`
 * (not the library's [rememberDefaultMicronFormState]) is used throughout
 * so assertions can read back what the widgets wrote without needing a
 * composition-scoped `remember` of their own.
 *
 * Requires a device/emulator (`./gradlew :micron2compose:connectedAndroidTest`)
 * — these don't run under `testDebugUnitTest`.
 */
class MicronPageTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private class TestFormState : MicronFormState {
        val values = mutableStateMapOf<String, String>()
        override fun getValue(name: String): String = values[name] ?: ""
        override fun setValue(name: String, value: String) {
            values[name] = value
        }
    }

    @Test
    fun showcaseFixtureRendersWithoutThrowing() {
        val fixture = File("src/test/resources/fixtures/showcase.mu").readText()
        val result = MicronConverter().convert(fixture)

        composeTestRule.setContent {
            MicronPage(result = result)
        }

        composeTestRule.waitForIdle()
    }

    @Test
    fun malformedInputCorpusRendersWithoutThrowing() {
        val adversarial = listOf(
            "`", "``", "`[", "`[a`b`c`d`e]", "`<", "`<|", "`{", "`:", "`F", "`Fa",
            "\\", "`t\n`t\n`t", "-".repeat(500), "`!".repeat(500),
        )
        val converter = MicronConverter()

        for (input in adversarial) {
            val result = converter.convert(input)
            composeTestRule.setContent {
                MicronPage(result = result)
            }
            composeTestRule.waitForIdle()
        }
    }

    @Test
    fun tappingALinkInvokesOnLinkClickWithResolvedTarget() {
        val converter = MicronConverter()
        val result = converter.convert("`[Click here`https://example.com]")
        var clicked: LinkTarget? = null

        composeTestRule.setContent {
            MicronPage(result = result, onLinkClick = { clicked = it })
        }

        composeTestRule.onNodeWithText("Click here").performClick()
        composeTestRule.waitForIdle()

        assertEquals("https://example.com", clicked?.url)
    }

    @Test
    fun typingIntoATextFieldUpdatesFormState() {
        val converter = MicronConverter()
        val result = converter.convert("`<20|username`>")
        val formState = TestFormState()

        composeTestRule.setContent {
            MicronPage(result = result, formState = formState)
        }

        // The OutlinedTextField is the only editable node on the page.
        composeTestRule.onAllNodes(hasSetTextAction()).onFirst().performTextInput("alice")
        composeTestRule.waitForIdle()

        assertEquals("alice", formState.getValue("username"))
    }

    @Test
    fun tappingACheckboxTogglesFormState() {
        val converter = MicronConverter()
        val result = converter.convert("`<?|agree|yes`I agree>")
        val formState = TestFormState()

        composeTestRule.setContent {
            MicronPage(result = result, formState = formState)
        }

        composeTestRule.onAllNodes(isToggleable()).onFirst().performClick()
        composeTestRule.waitForIdle()

        assertEquals("yes", formState.getValue("agree"))
    }
}
