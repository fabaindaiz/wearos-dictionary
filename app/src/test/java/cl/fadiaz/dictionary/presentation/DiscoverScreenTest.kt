package cl.fadiaz.dictionary.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import cl.fadiaz.dictionary.core.DiscoverPos
import cl.fadiaz.dictionary.core.DiscoverTier
import cl.fadiaz.dictionary.core.EntrySummary
import cl.fadiaz.dictionary.core.RowPreview
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * *See more words*: the row on the home and its screen (i-a2f271-ec624b).
 *
 * The screen is a function of [DiscoverState], so the state is built by hand (`app/AGENTS.md`);
 * what a reload draws is `DiscoverModelTest`'s.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "+w234dp-h234dp")
class DiscoverScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun word(headword: String, id: Long, lang: String) =
        EntrySummary(entryId = id, headword = headword, partOfSpeech = "noun", rank = 300, lang = lang)

    private val dosIdiomas = DiscoverState(
        available = true,
        tier = DiscoverTier.RARE,
        pos = null,
        languages = listOf("es", "en"),
        lang = "es",
        section = DiscoverSection(
            "es", "es-full", listOf(word("fragor", 1, "es"), word("galimatías", 2, "es")),
            previews = mapOf(1L to RowPreview.Definition("Ruido intenso y prolongado.")),
        ),
    )

    private fun show(
        state: DiscoverState = dosIdiomas,
        onSelect: (DiscoverTier, DiscoverPos?) -> Unit = { _, _ -> },
        onSelectLanguage: (String) -> Unit = {},
        onReload: () -> Unit = {},
        onOpen: (String, EntrySummary) -> Unit = { _, _ -> },
    ) = compose.setContent { DiscoverScreen(state, onSelect, onSelectLanguage, onReload, onOpen) }

    private fun scrollTo(text: String) =
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))

    @Test
    fun theListIsTheChosenLanguagesWords() {
        show()
        scrollTo("fragor")
        scrollTo("galimatías")
    }

    @Test
    fun theLanguageFilterNamesTheLanguageAndStepsToTheNextAvailable() {
        // Asked for on 2026-10-22: the language is a filter, through the packs available.
        var elegido: String? = null
        show(onSelectLanguage = { elegido = it })
        scrollTo("Español")
        compose.onNodeWithText("Español").performClick()
        assertEquals("en", elegido)
        assertEquals("es", nextLanguage(listOf("es", "en"), "en"))
        assertEquals("es", nextLanguage(listOf("es"), "es"))
    }

    @Test
    fun aWordShowsTheStartOfItsDefinition() {
        show()
        scrollTo("Ruido intenso y prolongado.")
    }

    @Test
    fun tappingAWordOpensItInItsOwnPack() {
        var abierta: Pair<String, Long>? = null
        show(onOpen = { pack, w -> abierta = pack to w.entryId })
        scrollTo("galimatías")
        compose.onNodeWithText("galimatías").performClick()
        assertEquals("es-full" to 2L, abierta)
    }

    @Test
    fun theRarityFilterSaysTheTierAndStepsToTheNext() {
        // Rare -> curious -> common -> rare: one row, one tap per step, 48 dp on a 234 dp screen.
        var elegido: Pair<DiscoverTier, DiscoverPos?>? = null
        show(onSelect = { t, p -> elegido = t to p })
        compose.onNodeWithText("Rara").performClick()
        assertEquals(DiscoverTier.CURIOUS to null, elegido)
    }

    @Test
    fun theTypeFilterStepsFromAllToTheFirstPartOfSpeech() {
        var elegido: Pair<DiscoverTier, DiscoverPos?>? = null
        show(onSelect = { t, p -> elegido = t to p })
        compose.onNodeWithText("Todas").performClick()
        assertEquals(DiscoverTier.RARE to DiscoverPos.NOUN, elegido)
    }

    @Test
    fun bothFiltersCycleAndComeBackToWhereTheyStarted() {
        assertEquals(DiscoverPos.VERB, nextPos(DiscoverPos.NOUN))
        assertEquals(DiscoverPos.ADJECTIVE, nextPos(DiscoverPos.VERB))
        assertEquals(null, nextPos(DiscoverPos.ADJECTIVE))
        assertEquals(DiscoverTier.RARE, nextTier(DiscoverTier.COMMON))
        assertEquals(DiscoverTier.COMMON, nextTier(DiscoverTier.CURIOUS))
    }

    @Test
    fun aLanguageWithNothingInThisFilterSaysSo() {
        show(dosIdiomas.copy(section = DiscoverSection("es", "es-full", emptyList())))
        scrollTo("No hay palabras con este filtro.")
    }

    @Test
    fun otherWordsTakesTheViewBackToTheFirstList() {
        // ⚠️ Seen on the emulator on 2026-10-22: the new words arrived and the view stayed on the
        // last row, so the twenty new ones were all above, off screen.
        val muchas = dosIdiomas.copy(
            section = DiscoverSection("es", "es-full", (1..20).map { word("palabra$it", it.toLong(), "es") }),
        )
        show(muchas)
        scrollTo("Otras palabras")
        compose.onNodeWithText("Otras palabras").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("palabra1").assertIsDisplayed()
    }

    @Test
    fun theLastRowDrawsOtherWords() {
        var recargas = 0
        show(onReload = { recargas++ })
        scrollTo("Otras palabras")
        compose.onNodeWithText("Otras palabras").performClick()
        assertEquals(1, recargas)
    }

}
