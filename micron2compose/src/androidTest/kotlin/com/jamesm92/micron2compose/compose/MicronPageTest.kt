package com.jamesm92.micron2compose.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.jamesm92.micron2compose.parser.LinkTarget
import com.jamesm92.micron2compose.parser.MicronConverter
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

    /**
     * `src/test/resources` (used by the plain-JUnit parser tests) isn't
     * packaged into the instrumented test APK at all — on-device tests
     * read fixtures from `src/androidTest/assets` via the instrumentation
     * context's `AssetManager` instead.
     */
    private fun readAsset(name: String): String {
        val context = InstrumentationRegistry.getInstrumentation().context
        return context.assets.open(name).bufferedReader().use { it.readText() }
    }

    @Test
    fun showcaseFixtureRendersWithoutThrowing() {
        val fixture = readAsset("showcase.mu")
        val result = MicronConverter().convert(fixture)

        composeTestRule.setContent {
            MicronPage(result = result)
        }

        composeTestRule.waitForIdle()
    }

    @Test
    fun malformedInputCorpusRendersWithoutThrowing() {
        // One setContent call for the whole corpus — the Compose test rule
        // only allows setContent once per test. Composing every adversarial
        // input's blocks in a single Column still proves the "never throws"
        // guarantee across all of them: any exception during composition
        // fails the test.
        val adversarial = listOf(
            "`", "``", "`[", "`[a`b`c`d`e]", "`<", "`<|", "`{", "`:", "`F", "`Fa",
            "\\", "`t\n`t\n`t", "-".repeat(500), "`!".repeat(500),
        )
        val converter = MicronConverter()
        val results = adversarial.map { converter.convert(it) }

        composeTestRule.setContent {
            Column {
                for (result in results) {
                    for (block in result.blocks) {
                        MicronBlock(block = block)
                    }
                }
            }
        }

        composeTestRule.waitForIdle()
    }

    @Test
    fun tappingALinkInvokesOnLinkClickWithResolvedTarget() {
        val converter = MicronConverter()
        val result = converter.convert("`[Click here`https://example.com]")
        var clicked: LinkTarget? = null

        composeTestRule.setContent {
            MicronPage(result = result, onLinkClick = { clicked = it })
        }

        // Not onNodeWithText().performClick(): the outer Text node spans the
        // full fillMaxWidth() row, but the actual link only occupies the
        // glyph width of its own label as a distinct child semantics node
        // (Compose exposes each LinkAnnotation as its own accessibility
        // node, with its own OnClick action and its own, smaller, bounds -
        // confirmed by dumping the tree via onRoot().printToLog() against a
        // real device this library was verified on). Clicking the outer
        // node's center can land past the link's actual glyphs entirely
        // when the surrounding text is short; querying by click action
        // finds the link's own node directly, with its own correct bounds.
        composeTestRule.onAllNodes(hasClickAction(), useUnmergedTree = true).onFirst().performClick()
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
