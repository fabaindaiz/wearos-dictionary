package cl.fadiaz.dictionary.presentation

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import cl.fadiaz.dictionary.core.Entry
import cl.fadiaz.dictionary.core.Sense
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one thing in `ScreensTest` that CANNOT run on the JVM.
 *
 * The other 46 screen tests run under Robolectric inside the gate, in seconds. This one does not:
 * tapping a word inside a gloss requires the tap to land on the **rectangle of a word inside a
 * paragraph**, and that depends on the real text layout. Under Robolectric the link node exists
 * and is found, the click is dispatched, and the callback **does not fire** --the test fails with
 * `expected:<77> but was:<null>`--. It is not a product bug: it is the exact measure of how far
 * the simulated runtime goes.
 *
 * If Robolectric ever resolves text hit-testing, this file merges back.
 */
@RunWith(AndroidJUnit4::class)
class GlossLinksOnDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    private fun entry(gloss: String) = Entry(
        packId = "test",
        entryId = 1,
        uid = 1,
        headword = "perro",
        partOfSpeech = "noun",
        senses = listOf(Sense(gloss)),
    )

    @Test
    fun tappingAKnownWordInTheGlossOpensItsEntry() {
        var abierta: Long? = null
        compose.setContent {
            EntryScreen(
                entryId = 1,
                onOpenWord = { abierta = it },
                resolveIn = { mapOf("cera" to 77L) },
            ) { entry("cilindro de cera con mecha") }
        }
        compose.waitForIdle()

        // The link node has NO text semantics of its own --Compose only puts OnClick on the
        // word's rectangle-- so it is identified through the text that contains it.
        compose.onNode(
            hasClickAction() and hasAnyAncestor(hasText("cilindro de cera", substring = true)),
            useUnmergedTree = true,
        ).performClick()

        assertEquals(77L, abierta)
    }
}
