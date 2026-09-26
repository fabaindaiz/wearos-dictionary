package cl.fadiaz.dictionary.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import cl.fadiaz.dictionary.core.DictionarySource
import cl.fadiaz.dictionary.core.Entry
import cl.fadiaz.dictionary.R
import cl.fadiaz.dictionary.core.Example
import cl.fadiaz.dictionary.core.EntrySummary
import cl.fadiaz.dictionary.core.FuzzyProfile
import cl.fadiaz.dictionary.core.PackKind
import cl.fadiaz.dictionary.core.PackRejection
import cl.fadiaz.dictionary.core.PackMetadata
import cl.fadiaz.dictionary.core.PackSource
import cl.fadiaz.dictionary.data.CatalogOffer
import cl.fadiaz.dictionary.data.CatalogPack
import cl.fadiaz.dictionary.data.CatalogState
import cl.fadiaz.dictionary.data.CatalogStatus
import cl.fadiaz.dictionary.data.DownloadPhase
import cl.fadiaz.dictionary.data.PackDownload
import cl.fadiaz.dictionary.data.PackHandle
import cl.fadiaz.dictionary.data.Visit
import cl.fadiaz.dictionary.core.MatchKind
import cl.fadiaz.dictionary.core.Sense
import cl.fadiaz.dictionary.core.Suggestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The screens, against real Android.
 *
 * They run under Robolectric and **are inside the gate** (D-110). What they cover is what no
 * plain JVM test can see -- that what the state says reaches the pixels -- and above all **that
 * the attribution is shown**, which is the condition for using the data (D-031) and until
 * recently had no enforcer beyond good intentions.
 *
 * They use no DictionarySource: the screens are functions of the state, so the state is built by
 * hand. That is also why the fake from `src/test` does not need duplicating here.
 */
@RunWith(AndroidJUnit4::class)
class ScreensTest {

    @get:Rule
    val compose = createComposeRule()

    private fun suggestion(
        headword: String,
        pos: String? = "noun",
        packId: String = "es-def",
    ) = Suggestion(
        packId = packId,
        entryId = headword.hashCode().toLong(),
        headword = headword,
        partOfSpeech = pos,
        matchKind = MatchKind.PREFIX,
        score = 0,
    )

    /** A fake pack: only its metadata matters, because the screens are functions of state. */
    private fun meta(
        packId: String = "es-def",
        lang: String = "es",
        name: String = "Español — definiciones",
        entries: Int = 1,
        /** With two, the pack is bidirectional and contributes a chip for each. */
        langs: List<String>? = null,
    ) = PackMetadata(
        packId = packId,
        schemaVersion = 4,
        normVersion = 1,
        kind = if ((langs?.size ?: 1) > 1) PackKind.BILINGUAL else PackKind.MONOLINGUAL,
        name = name,
        // null: exercises the path of a pack older than D-125, which does not carry the key.
        description = null,
        langs = langs ?: listOf(lang),
        fuzzyProfiles = List((langs ?: listOf(lang)).size) { FuzzyProfile.SPANISH },
        entryCount = entries,
        dataVersion = 1,
        license = "CC-BY-SA-4.0",
        attribution = "Definiciones del Wikcionario, CC BY-SA 4.0",
    )

    private fun handle(m: PackMetadata) = PackHandle.Open(FakeSource(m))

    private fun readyState(vararg headwords: String) = SearchState(
        // Both: this state represents a search ALREADY MADE. `submitted` is what the list reflects
        // and `query` what the field shows; with the keyboard open they separate (D-128).
        query = "per",
        submitted = "per",
        results = headwords.map { suggestion(it) },
        status = SearchState.Status.Ready,
        active = meta(),
        // ⚠️ The language is now a datum OF THE STATE and is not derived from the active pack: a
        // bidirectional pack speaks two, so `active` stopped answering it.
        activeLang = "es",
        available = listOf(handle(meta())),
    )

    /** Two packs: this is the state that exercises the selector. */
    private fun twoPackState(vararg headwords: String): SearchState {
        val es = meta()
        val en = meta("en-def", "en", "English")
        return readyState(*headwords).copy(
            active = es,
            available = listOf(handle(es), handle(en)),
        )
    }

    private fun resumen(headword: String) =
        EntrySummary(entryId = headword.hashCode().toLong(), headword = headword,
                     partOfSpeech = "noun", rank = 100)

    private fun visita(headword: String) =
        Visit("es-def", headword.hashCode().toLong(), headword, "noun")

    private fun showSearch(
        state: SearchState,
        onOpenEntry: (Suggestion) -> Unit = {},
        onOpenAttribution: () -> Unit = {},
        onLanguageChange: (String) -> Unit = {},
        onSearchDefinitions: () -> Unit = {},
        onOpenVisita: (Visit) -> Unit = {},
        onOpenSettings: () -> Unit = {},
        onOpenFavoritos: () -> Unit = {},
        onOpenWordOfTheDay: (String, EntrySummary) -> Unit = { _, _ -> },
        onOpenHistory: () -> Unit = {},
        onManagePacks: () -> Unit = {},
    ) = compose.setContent {
        SearchScreen(state, onQueryChange = {}, onLanguageChange = onLanguageChange,
            onSearchDefinitions = onSearchDefinitions, onOpenVisita = onOpenVisita,
            onOpenEntry = onOpenEntry, onOpenAttribution = onOpenAttribution,
            onOpenSettings = onOpenSettings, onOpenFavoritos = onOpenFavoritos,
            onOpenWordOfTheDay = onOpenWordOfTheDay, onOpenHistory = onOpenHistory,
            onManagePacks = onManagePacks)
    }

    // --- The results list ---------------------------------------------------------------------

    @Test
    fun theListShowsTheHeadwordAndItsPartOfSpeech() {
        showSearch(readyState("perder"))
        compose.onNodeWithText("perder").assertIsDisplayed()
        compose.onNodeWithText("sust.", substring = true).assertExists()
    }

    @Test
    @Config(qualifiers = "+w192dp-h192dp")
    fun onAGenericWatchTwoResultsFit() {
        // THE GENERIC WATCH. 192 dp is the arithmetic D-073, D-075, D-078, D-084 and D-085 were
        // justified with, and it has to keep working: the app is not optimized for one watch by
        // breaking the other.
        //
        // ⚠️ It is TWO, not three. The test this replaces --`entranTresResultadosSinScrollear`--
        // asserted three and passed, but it ran with Robolectric's DEFAULT device, which is not a
        // watch: with the four suggestions it composed all four. It passed for the wrong reason.
        //
        // The simulated number is not the one measured on the 384x384 emulator (which gives
        // three): the `h192dp` qualifier is AVAILABLE height and discounts decoration. What this
        // test pins is not the absolute but that the generic one still shows useful results.
        showSearch(readyState("perder", "perro", "permitir", "persona"))
        assertEquals(2, visibles(listOf("perder", "perro", "permitir", "persona")).size)
    }

    @Test
    @Config(qualifiers = "+w234dp-h234dp")
    fun theProjectsWatchFitsOneMoreResultThanTheGenericOne() {
        // THE PROJECT'S WATCH. The SM-L715F delivers `sw234dp w234dp h234dp 340dpi`, confirmed by
        // asking the system what configuration the app receives.
        //
        // **This is the property that matters and the only one that can be sustained**: 22 % more
        // screen fits ONE MORE ROW. The absolute depends on how much decoration is discounted; the
        // ratio does not. And it is what allows reviewing the five decisions priced against 192 dp
        // without the watch in front of you.
        showSearch(readyState("perder", "perro", "permitir", "persona"))
        assertEquals(3, visibles(listOf("perder", "perro", "permitir", "persona")).size)
    }

    /** Cuales de estos lemas llegaron a componerse. */
    private fun visibles(headwords: List<String>) = headwords.filter {
        compose.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun aLongHeadwordDoesNotEatTheScreen() {
        // Sayings are Wiktionary entries and reach 96 characters. If a row grew to show one
        // whole, a single result would take the entire screen.
        val refran = "más corre el galgo que el mastín; pero si el camino es largo, " +
            "más corre el mastín que el galgo"
        showSearch(readyState(refran, "perder", "perro", "permitir"))
        compose.onNodeWithText("perro").assertIsDisplayed()
    }

    @Test
    fun tappingAResultOpensIt() {
        var abierto: Suggestion? = null
        showSearch(readyState("perder"), onOpenEntry = { abierto = it })
        compose.onNodeWithText("perder").performClick()
        assertEquals("perder", abierto?.headword)
    }

    // --- The states that are not "there are results" -----------------------------------------

    @Test
    fun whileThePackInstallsItSaysSo() {
        showSearch(SearchState(status = SearchState.Status.Installing))
        compose.onNodeWithText("Instalando", substring = true).assertIsDisplayed()
    }

    @Test
    fun withNoDictionaryItSaysSoAndOffersNoSearch() {
        showSearch(SearchState(status = SearchState.Status.Failed("No hay ningún diccionario instalado.")))
        compose.onNodeWithText("No hay ningún diccionario instalado.").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("Decir una palabra").fetchSemanticsNodes().size)
    }

    @Test
    fun withNoDictionaryThereIsAWayToGetOne() {
        // ⚠️ The state that most needs the downloader was the only one that could not reach it.
        // The dictionary manager hangs off Settings, and the Options section that holds Settings
        // is drawn under `Ready` only -- so an empty `packs/` showed one line of text and no route
        // anywhere. Invisible today because the APK carries the two cores; it is the FIRST-RUN
        // screen the moment they stop shipping inside it.
        //
        // ⚠️ Note the neighbour test above asserts over `Status.Failed`, not this branch: until
        // now nothing exercised `NoDictionary` at all.
        var fueALaGestion = false
        showSearch(
            SearchState(status = SearchState.Status.NoDictionary),
            onManagePacks = { fueALaGestion = true },
        )
        compose.onNodeWithText("No hay ningún diccionario instalado.").assertIsDisplayed()
        compose.onNodeWithText("Conseguir un diccionario").performClick()
        assertEquals(true, fueALaGestion)
    }

    @Test
    fun withNoDictionaryTheDeadRowsStayAway() {
        // One row and not the whole Options section: `Saved` and `Recent` are empty by
        // construction with no packs, so drawing them would offer three dead rows to make one
        // live.
        showSearch(SearchState(status = SearchState.Status.NoDictionary))
        assertEquals(0, compose.onAllNodesWithText("Guardadas").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("Recientes").fetchSemanticsNodes().size)
    }

    @Test
    fun withNoResultsItSaysSoWithTheSearchedWord() {
        showSearch(readyState().copy(query = "xyzzy", submitted = "xyzzy"))
        compose.onNodeWithText("Sin resultados", substring = true).assertIsDisplayed()
    }

    // --- The entry ---------------------------------------------------------------------------

    private fun entry(vararg glosses: String) = Entry(
        packId = "test",
        entryId = 1,
        uid = 1,
        headword = "perro",
        partOfSpeech = "noun",
        senses = glosses.map { Sense(it) },
    )

    /** A link inside a gloss: all that identifies it is that it is clickable and what it hangs
     *  off. Compose gives the link's rectangle no text of its own. */
    private fun linkAt(textoDeLaGlosa: String) =
        hasClickAction() and hasAnyAncestor(hasText(textoDeLaGlosa, substring = true))

    @Test
    fun theCardShowsThePronunciationBareAndUnderTheHeadword() {
        // d-a2f271-13da99. **Bare on purpose**: the pack stores the transcription stripped of the
        // source's delimiters, so the card no longer knows whether it was phonemic `/…/` or
        // phonetic `[…]`, and adding either back would assert a notation nothing recorded.
        compose.setContent {
            EntryScreen(1, onOpenWord = {}) {
                entry("Mamífero cánido doméstico.").copy(pronunciation = "\u02c8pero")
            }
        }
        compose.onNodeWithText("\u02c8pero").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("/\u02c8pero/").fetchSemanticsNodes().size)
    }

    @Test
    fun aPackWithNoPronunciationDrawsNoRowForIt() {
        // The degradation, which is every pack built today: null draws nothing rather than an
        // empty line. It also covers a word whose source carried none -- the card cannot tell
        // those apart, which is why `verify_pack.py` reports the coverage per pack.
        compose.setContent { EntryScreen(1, onOpenWord = {}) { entry("Mamífero.") } }
        compose.onNodeWithText("perro").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("\u02c8", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun theCardShowsTheOriginWholeAndAfterTheSenses() {
        // ⚠️ **After the senses and not before**, unlike the forms: an origin answers *where does
        // it come from*, which is asked once the meaning is known. And **whole**: the pack already
        // decided what to carry, by word and not by length, so the card does not cut it.
        val origen = "Del latín *canis*, y éste del protoindoeuropeo *ḱwṓ*."
        compose.setContent {
            EntryScreen(1, onOpenWord = {}) {
                entry("Mamífero cánido doméstico.").copy(etymology = origen)
            }
        }
        compose.onNodeWithText(origen).assertIsDisplayed()
        compose.onNodeWithText("Origen").assertExists()
    }

    @Test
    fun aPackWithNoOriginDrawsNoSectionForIt() {
        // The degradation, and here it covers one case more than the pronunciation's: a `full`
        // pack carries the datum only up to its `main` tier's vocabulary, so a rare word has none
        // even where the source does. All three draw nothing.
        compose.setContent { EntryScreen(1, onOpenWord = {}) { entry("Mamífero.") } }
        compose.onNodeWithText("perro").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("Origen").fetchSemanticsNodes().size)
    }

    @Test
    fun theEntryShowsHeadwordPartOfSpeechAndNumberedSenses() {
        compose.setContent { EntryScreen(1, onOpenWord = {}) { entry("Mamífero cánido doméstico.") } }
        compose.onNodeWithText("perro").assertIsDisplayed()
        // In full and not "sust.": the card fights nothing for width, and an abbreviation that has
        // to be deciphered is only justified where the lemma needs the space.
        compose.onNodeWithText("sustantivo").assertExists()
        compose.onNodeWithText("1.", substring = true).assertExists()
    }

    @Test
    fun theSenseShowsItsSynonyms() {
        // 26,845 entries of the Spanish pack carry synonyms and the builder threw them away.
        // They matter most where the gloss is one word ("Tonto."), which is 25.6 % of the pack.
        compose.setContent {
            EntryScreen(1, onOpenWord = {}) {
                entry().copy(
                    senses = listOf(Sense("de poco entendimiento", synonyms = listOf("bobo", "zonzo"))),
                )
            }
        }
        compose.onNodeWithText("bobo", substring = true).assertExists()
        compose.onNodeWithText("zonzo", substring = true).assertExists()
    }

    @Test
    fun theExampleShowsWhereItWasQuotedFrom() {
        // 86.5 % of the English dump's examples are quotations lifted from a published text, so
        // without this line the reader gets a sentence out of an 1897 novel with nothing saying
        // so -- which is what sent a user to Wiktionary by hand to find out (D-216).
        compose.setContent {
            EntryScreen(1, onOpenWord = {}) {
                entry().copy(
                    senses = listOf(
                        Sense(
                            "An infidel or doubter.",
                            examples = listOf(
                                Example("prove them Thomases", "1897, Richard Marsh"),
                            ),
                        ),
                    ),
                )
            }
        }
        compose.onNodeWithText("prove them Thomases", substring = true).assertExists()
        compose.onNodeWithText("1897, Richard Marsh", substring = true).assertExists()
    }

    @Test
    fun anExampleWithNoKnownSourceShowsNoCitationLine() {
        // The common case -- 24.9 % of the examples the builder keeps declare no `ref`, and the
        // Tatoeba sentences none at all. What this pins is that the em dash does not appear on
        // its own: a lone "—" under an example reads as a citation that failed to load.
        compose.setContent {
            EntryScreen(1, onOpenWord = {}) {
                entry().copy(
                    senses = listOf(Sense("A mammal.", examples = listOf(Example("the dog barks")))),
                )
            }
        }
        compose.onNodeWithText("the dog barks", substring = true).assertExists()
        // A loose dash under the example would read as a citation that did not load.
        assertEquals(
            0,
            compose.onAllNodesWithText("—", substring = true).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun theSenseShowsItsAntonymsAndDoesNotMixThemWithSynonyms() {
        // The risk is not that they go unseen: it is that they look THE SAME. The two lists
        // share style, position and separator, so the only thing separating "another way to say
        // it" from "the opposite" is the prefix. This test pins both prefixes, not presence.
        compose.setContent {
            EntryScreen(1, onOpenWord = {}) {
                entry().copy(
                    senses = listOf(
                        Sense(
                            "de temperatura alta",
                            synonyms = listOf("ardiente"),
                            antonyms = listOf("gélido"),
                        ),
                    ),
                )
            }
        }
        // The category is written out in full and above, not as a prefix: it is D-159's same rule
        // --in a row it is abbreviated because the lemma needs the width, on the card it competes
        // with nothing-- and what gets pinned is still that the two lists be distinguishable.
        compose.onNodeWithText("Sinónimos").assertExists()
        compose.onNodeWithText("ardiente", substring = true).assertExists()
        compose.onNodeWithText("Antónimos").assertExists()
        compose.onNodeWithText("gélido", substring = true).assertExists()
    }

    @Test
    fun theSenseShowsItsTranslationAndKeepsItApartFromTheSynonyms() {
        // Same risk as the antonyms above and the same fix: the lists are visually identical, so
        // the only thing separating "another way to say it" from "this is the English for it" is
        // the title. Pinning presence alone would pass with all four lists titled the same.
        //
        // ⚠️ It is a FOURTH list on a 234 dp screen and it earns the row: measured over the dump,
        // the median entry carries ONE translation and p90 is two, so it is one line under its
        // title -- and it is the answer somebody opened the entry for, not a complement.
        compose.setContent {
            EntryScreen(1, onOpenWord = {}) {
                entry().copy(
                    senses = listOf(
                        Sense(
                            "cilindro de cera que da luz al arder",
                            translations = listOf("candle"),
                            synonyms = listOf("cirio"),
                        ),
                    ),
                )
            }
        }
        compose.onNodeWithText("Traducción").assertExists()
        compose.onNodeWithText("candle", substring = true).assertExists()
        compose.onNodeWithText("Sinónimos").assertExists()
        compose.onNodeWithText("cirio", substring = true).assertExists()
    }

    @Test
    fun theWordLevelTranslationsGetTheirOwnSectionBelowTheSenses() {
        // ⚠️ They go OUTSIDE `SenseBlock` and that is the whole point: a list drawn under a
        // sense **asserts** it belongs to that sense, and what lands here is precisely what the
        // source could not attribute. Merging them would undo in the screen what the format was
        // built to keep apart (D-117), and the mistake would read as perfectly plausible.
        //
        // Measured: 48.6 % of entries with translations have ONLY these, so for half the words
        // this section is the whole answer; and only 3.0 % show both sections at once.
        compose.setContent {
            EntryScreen(1, onOpenWord = {}) {
                entry().copy(
                    senses = listOf(Sense("asiento para varias personas", translations = listOf("bench"))),
                    wordTranslations = listOf("bank"),
                )
            }
        }
        compose.onNodeWithText("Traducción").assertExists()
        compose.onNodeWithText("bench", substring = true).assertExists()
        compose.onNodeWithText("Traducciones de la palabra").assertExists()
        compose.onNodeWithText("bank", substring = true).assertExists()
    }

    @Test
    fun lasTraduccionesDeLaPALABRA_van_ARRIBA_de_las_acepciones() {
        // ⚠️ **The order is the decision, not a layout detail (D-192).** They went last on the
        // reasoning that "the definitions are what the reader came for"; the measurement already
        // written beside it disproves that: **48.6 %** of the entries with a translation have
        // ONLY these, so for half the cases the section at the end was the whole answer and it sat
        // below a `See more` you have to tap.
        //
        // It is pinned with coordinates and not by reading the tree because it is exactly what
        // gets reverted with nothing to warn: moving an `item` breaks no test that merely checks
        // both sections exist.
        compose.setContent {
            EntryScreen(1, onOpenWord = {}) {
                entry().copy(
                    senses = listOf(Sense("asiento para varias personas")),
                    wordTranslations = listOf("bank"),
                )
            }
        }
        val traduccion = compose.onNodeWithText("Traducciones de la palabra")
            .fetchSemanticsNode().positionInRoot.y
        val acepcion = compose.onNodeWithText("asiento para varias personas", substring = true)
            .fetchSemanticsNode().positionInRoot.y
        assertTrue(
            "la traducción de la palabra va antes que la primera acepción " +
                "(traducción y=$traduccion, acepción y=$acepcion)",
            traduccion < acepcion,
        )
    }

    @Test
    fun aTranslationResolvedInTheOtherPackIsTappable() {
        // ⚠️ **It is what forced a link to carry a `packId` and not only an `entryId`.** Until
        // here the map was `norm -> entryId` and `onOpenWord` navigated within the SAME pack, on
        // purpose (D-080): sending the id to another pack opens another word with no error. A
        // translation necessarily goes to another pack, so the destination has to say which.
        var abierta: WordLink? = null
        compose.setContent {
            EntryScreen(
                1,
                onOpenWord = { abierta = it },
                resolveIn = { norms, _, _ ->
                    norms.filter { it == "house" }
                        .associateWith { WordLink("en-def-wikt", 42L) }
                },
            ) {
                entry().copy(senses = listOf(Sense("edificación", translations = listOf("house"))))
            }
        }
        compose.onNodeWithText("house", substring = true).assertExists()
        compose.waitForIdle()
        assertNull("no se abre sola", abierta)
    }

    @Test
    fun `una acepcion sin traduccion no dibuja el titulo`() {
        // `TermList` returns early on an empty list, and that has to keep holding for the fourth
        // one: a heading with nothing under it costs a row on a screen that has three.
        compose.setContent {
            EntryScreen(1, onOpenWord = {}) {
                entry().copy(senses = listOf(Sense("de poco entendimiento", synonyms = listOf("bobo"))))
            }
        }
        compose.onNodeWithText("Sinónimos").assertExists()
        compose.onNodeWithText("Traducción").assertDoesNotExist()
    }

    @Test
    fun `el separador de listas lleva espacios a los dos lados`() {
        // ⚠️ Found by LOOKING at the screen: it came out "marine·freshwater·limnic", run together,
        // because **Android trims a `<string>`'s spaces** unless the value is inside double
        // quotes. With three or four terms the line becomes an unreadable block and it also wraps
        // badly.
        //
        // This test exists because the bug is invisible in the resource --the XML looks fine-- and
        // no earlier test saw it: they all asserted a single term.
        compose.setContent {
            EntryScreen(1, onOpenWord = {}) {
                entry().copy(
                    senses = listOf(Sense("relativo al agua",
                                          related = listOf("marino", "limnico"))),
                )
            }
        }
        compose.onNodeWithText("marino · limnico").assertExists()
    }

    @Test
    fun `las relacionadas se distinguen de los sinonimos por el prefijo`() {
        // A third list with the same shape (D-132), and the same heightened risk: "galo" shown as
        // a synonym of "frances" asserts an equivalence the source does not give. All three look
        // alike, so the only thing separating them are the prefixes -- and that is what gets
        // pinned.
        compose.setContent {
            EntryScreen(1, onOpenWord = {}) {
                entry().copy(
                    senses = listOf(
                        Sense(
                            "mamífero camélido sudamericano",
                            synonyms = listOf("huanaco"),
                            related = listOf("camélido", "vicuña"),
                        ),
                    ),
                )
            }
        }
        compose.onNodeWithText("Sinónimos").assertExists()
        compose.onNodeWithText("huanaco").assertExists()
        compose.onNodeWithText("Relacionadas").assertExists()
        // The whole line and not a `substring`: "camélido" is also IN THE GLOSS, so looking for it
        // as a substring finds two nodes. That the word appears in both places is correct --one is
        // the definition and the other the list-- and the test has to look at the one it cares
        // about.
        compose.onNodeWithText("camélido · vicuña").assertExists()
        compose.onNodeWithText("vicuña", substring = true).assertExists()
    }

    @Test
    fun withMoreThanThreeSensesOnlyThreeShowPlusAShowMore() {
        // "justicia" has 10 senses and the measured maximum is 47. With no cap, the screen
        // becomes a scroll and the useful sense sits below nine nobody was looking for.
        compose.setContent {
            EntryScreen(1, onOpenWord = {}) { entry("uno", "dos", "tres", "cuatro", "cinco") }
        }
        compose.onNodeWithText("tres", substring = true).assertExists()
        assertEquals(0, compose.onAllNodesWithText("cuatro", substring = true).fetchSemanticsNodes().size)
        // The shortcut to the search takes the first row, so "Show more" moved down a line
        // and with three senses no longer fits in the first screenful. That is the measured
        // cost of that row: the button is still there, one scroll away, but out of sight.
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Ver más", substring = true))
        compose.onNodeWithText("Ver más", substring = true).assertExists()
    }

    @Test
    fun showMoreExpandsTheRest() {
        compose.setContent {
            EntryScreen(1, onOpenWord = {}) { entry("uno", "dos", "tres", "cuatro", "cinco") }
        }
        // The shortcut to the search takes the first row, so "Show more" moved down a line
        // and with three senses no longer fits in the first screenful. That is the measured
        // cost of that row: the button is still there, one scroll away, but out of sight.
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Ver más", substring = true))
        compose.onNodeWithText("Ver más", substring = true).performClick()
        compose.onNodeWithText("cuatro", substring = true).assertExists()
        compose.onNodeWithText("cinco", substring = true).assertExists()
    }

    @Test
    fun withThreeSensesOrFewerThereIsNoShowMore() {
        // The median is 3: in half the entries the button should not even appear.
        compose.setContent { EntryScreen(1, onOpenWord = {}) { entry("uno", "dos", "tres") } }
        compose.onNodeWithText("tres", substring = true).assertExists()
        assertEquals(0, compose.onAllNodesWithText("Ver más", substring = true).fetchSemanticsNodes().size)
    }

    // --- The text field: where keyboard search comes in ---------------------------------------

    /**
     * A harness WITH state, and that is exactly why the bug survived 22 tests.
     *
     * Every other one passes `onQueryChange = {}`: the state never changes, the list never
     * reorders and the field is never destroyed. Here typing really changes the state, which is
     * what makes the header, the voice button and the history disappear -- and with them, the
     * field's position inside the list.
     */
    private fun showTypableSearch(inicial: SearchState) = compose.setContent {
        var query by remember { mutableStateOf(inicial.query) }
        SearchScreen(
            state = inicial.copy(
                query = query,
                results = if (query.isEmpty()) emptyList() else inicial.results,
            ),
            onQueryChange = { query = it },
            onSearchDefinitions = {},
            onOpenEntry = {},
            onOpenAttribution = {},
            onOpenWordOfTheDay = { _, _ -> },
            onManagePacks = {},
        )
    }

    @Test
    fun typingTheFirstLetterDoesNotCloseTheField() {
        // The bug that showed up on the watch: on the first letter the header, voice button and
        // history disappear, the field jumps from index 2 to 0 and --without `key`-- the lazy
        // layout takes it for a different node, destroys it and recomposes it. Focus goes with
        // it, and the keyboard follows.
        showTypableSearch(readyState("perder").copy(query = "", submitted = "", history = recent))

        compose.onNode(hasSetTextAction()).performClick()
        compose.onNode(hasSetTextAction()).assertIsFocused()

        compose.onNode(hasSetTextAction()).performTextInput("p")
        compose.waitForIdle()

        compose.onNode(hasSetTextAction()).assertIsFocused()
    }

    @Test
    fun acceptingOnTheKeyboardReleasesTheField() {
        // "Accept" did nothing: there is an `ImeAction.Search` declared and zero
        // `keyboardActions`, and `KeyboardActions.Default` defines no behaviour for Search. The
        // only way out was the system's back gesture. Releasing focus is what closes the keyboard
        // and leaves the crown working over the results.
        showTypableSearch(readyState("perder").copy(query = "", submitted = ""))

        compose.onNode(hasSetTextAction()).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("per")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitForIdle()

        compose.onNode(hasSetTextAction()).assertIsNotFocused()
    }

    @Test
    fun theMaximumSensesWithTheLongestExampleExpandWithoutFalling() {
        // The numbers are the ones measured on the real pack: 47 senses is the maximum and 917
        // characters the longest example. `showMoreExpandsTheRest` uses FIVE senses with no
        // examples, which is why it never reproduced the crash seen when tapping "Show more".
        val longExample =
            "cronica del siglo XVI que el Wikcionario cita como uso. ".repeat(17).take(917)
        val muchas = (1..47).map { number ->
            Sense(
                gloss = "acepcion numero $number",
                examples = if (number == 1) listOf(Example(longExample)) else emptyList(),
            )
        }
        compose.setContent { EntryScreen(1, onOpenWord = {}) { entry().copy(senses = muchas) } }

        // You have to scroll to reach the button: the 917-character example pushes it off
        // screen. That is, literally, the wall D-074 documents.
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Ver más", substring = true))
        compose.onNodeWithText("Ver más", substring = true).performClick()
        compose.waitForIdle()

        // By index and not by text: the last item of a TransformingLazyColumn is not left
        // composed after scrolling to the end, so looking it up by text gives a false red.
        compose.onNode(hasScrollAction()).performScrollToIndex(47)
        compose.onNodeWithText("acepcion numero 47", substring = true).assertExists()
    }


    // --- The two actions up top and the menu ----------------------------------------------------

    @Test
    fun theTwoTopButtonsShareASingleRow() {
        // The reason is arithmetic, not aesthetic: side by side they cost 48 dp --the touch
        // minimum-- and stacked they would cost 96, which on this screen is one sense less in
        // view.
        compose.setContent {
            EntryScreen(
                entryId = 1,
                onOpenWord = {},
                actions = { listOf(EntryAction(R.string.action_save) {}) },
            ) { entry("una glosa") }
        }
        compose.waitForIdle()

        val buscar = compose.onNodeWithContentDescription("Buscar").getBoundsInRoot()
        val options = compose.onNodeWithContentDescription("Opciones").getBoundsInRoot()
        assertEquals("no estan en la misma fila", buscar.top, options.top)
        assertEquals(
            "no tienen el mismo alto",
            buscar.bottom - buscar.top,
            options.bottom - options.top,
        )
    }

    @Test
    fun withNoActionsTheMenuIsNotOffered() {
        // A button that opens an empty menu is worse than no button.
        compose.setContent {
            EntryScreen(entryId = 1, onOpenWord = {}) { entry("una glosa") }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Buscar").assertExists()
        assertEquals(
            0,
            compose.onAllNodesWithContentDescription("Opciones").fetchSemanticsNodes().size,
        )
    }

    @Test
    fun theMenuShowsTheActionsAndTheTappedOneRuns() {
        var ejecutada: String? = null
        compose.setContent {
            EntryScreen(
                entryId = 1,
                onOpenWord = {},
                actions = {
                    listOf(
                        EntryAction(R.string.action_save) { ejecutada = "Guardar" },
                        EntryAction(R.string.action_copy) { ejecutada = "Copiar" },
                    )
                },
            ) { entry("una glosa") }
        }
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Opciones").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Copiar").assertIsDisplayed()
        compose.onNodeWithText("Copiar").performClick()

        assertEquals("Copiar", ejecutada)
    }

    // --- Tappable words inside a gloss --------------------------------------------------------

    // `tappingAKnownWordInTheGlossOpensItsEntry` lives in `androidTest`, in
    // `GlossLinksOnDeviceTest`: tapping a word inside a paragraph depends on the real text layout
    // and under Robolectric the callback does not fire. It is the only one of the 47.

    @Test
    fun aWordThatIsNotAHeadwordCannotBeTapped() {
        // The colour is a promise: if something that leads nowhere is painted as tappable, the
        // user learns not to trust the colour and the feature stops being useful.
        compose.setContent {
            EntryScreen(entryId = 1, onOpenWord = {}, resolveIn = { _, _, _ -> emptyMap() }) {
                entry().copy(senses = listOf(Sense("cilindro de cera con mecha")))
            }
        }
        compose.waitForIdle()

        assertEquals(
            0,
            compose.onAllNodes(linkAt("cilindro de cera"), useUnmergedTree = true)
                .fetchSemanticsNodes().size,
        )
    }

    @Test
    fun theWordPointingAtThisSameEntryIsNotPainted() {
        // Resolving returns the open entry: a link to where we already are leads nowhere.
        compose.setContent {
            EntryScreen(entryId = 1, onOpenWord = {}, resolveIn = { _, _, _ -> mapOf("cera" to WordLink("es-def", 1L)) }) {
                entry().copy(senses = listOf(Sense("cilindro de cera con mecha")))
            }
        }
        compose.waitForIdle()

        assertEquals(
            0,
            compose.onAllNodes(linkAt("cilindro de cera"), useUnmergedTree = true)
                .fetchSemanticsNodes().size,
        )
    }

    @Test
    fun theTopButtonGoesBackToTheSearch() {
        // Tapping words stacks entries: without this shortcut, coming back from three deep is
        // three gestures. It is looked up by contentDescription and not by text because it is an
        // icon, and that description is also the only thing naming it for a screen reader.
        var volvio = false
        compose.setContent {
            EntryScreen(entryId = 1, onOpenWord = {}, onBackToSearch = { volvio = true }) {
                entry("una glosa")
            }
        }
        compose.onNodeWithContentDescription("Buscar").performClick()
        assertEquals(true, volvio)
    }

    // --- The home: what shows with an empty search --------------------------------------------

    private val todaysWord =
        EntrySummary(entryId = 42, headword = "permanecer", partOfSpeech = "verb", rank = 883)

    @Test
    fun theWordOfTheDayShowsUnderItsHeadingAndOpens() {
        // It is NO longer visible without scrolling, and that is the accepted cost of putting
        // the bar first: the search and voice stay on top, which is what repeats most. What does
        // have to hold is that you reach it, that it carries its section heading and that it
        // opens in ITS dictionary.
        var abierta: EntrySummary? = null
        var packOfTheWord: String? = null
        showSearch(
            readyState().copy(query = "", submitted = "", wordsOfTheDay = mapOf("es-def" to todaysWord)),
            onOpenWordOfTheDay = { pack, word -> packOfTheWord = pack; abierta = word },
        )
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("permanecer"))
        compose.onNodeWithText("Palabra del día").assertExists()
        compose.onNodeWithText("permanecer").assertIsDisplayed()
        compose.onNodeWithText("permanecer").performClick()
        assertEquals(42L, abierta?.entryId)
        assertEquals("tiene que abrir en SU diccionario", "es-def", packOfTheWord)
    }

    @Test
    fun withTwoDictionariesBothWordsAndTheirLanguageShow() {
        // ⚠️ **The subtitle is the word's own detail, not the dictionary's name.** It used to
        // say `English · definiciones`, which made this the one row in the app saying something
        // different from the other three --a result, a recent and a saved word all say
        // `verbo · EN`--. Asked for: *"under the word of the day, put only what kind of word it
        // is and the language code"*.
        //
        // The language still tells the two apart, which is what this test was pinning: it is the
        // `EN`, and it comes from the pack that produced the word.
        showSearch(
            twoPackState().copy(query = "", submitted = "",
                wordsOfTheDay = mapOf(
                    "es-def" to todaysWord,
                    "en-def" to EntrySummary(7, "remain", "verb", 880),
                ),
            ),
        )
        compose.onNodeWithText("permanecer").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("remain"))
        compose.onNodeWithText("remain").assertIsDisplayed()
        compose.onNodeWithText("verbo · EN").assertExists()
        compose.onNodeWithText("verbo · ES").assertExists()
        assertEquals(
            "el nombre del diccionario ya no va: lo dice el encabezado de la seccion",
            0,
            compose.onAllNodesWithText("English · definiciones").fetchSemanticsNodes().size,
        )
    }

    @Test
    fun withNoWordOfTheDayTheGapDoesNotShow() {
        // An empty pack, or the first launch before the choice finishes: the row does not
        // appear instead of appearing empty.
        showSearch(readyState().copy(query = "", submitted = "", wordsOfTheDay = emptyMap()))
        assertEquals(
            0,
            compose.onAllNodesWithText("palabra del día").fetchSemanticsNodes().size,
        )
    }

    @Test
    fun whenTypingTheWordOfTheDayAndSettingsDisappear() {
        // Same rule as the history: with results on screen, every row of chrome is one result
        // less, and with a 48 dp touch area that shows (D-073).
        showSearch(
            readyState("perder").copy(query = "per", submitted = "per", wordsOfTheDay = mapOf("es-def" to todaysWord)),
        )
        assertEquals(0, compose.onAllNodesWithText("palabra del día").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("Ajustes").fetchSemanticsNodes().size)
    }

    @Test
    fun theHomeLeadsToSettings() {
        var abrio = false
        showSearch(readyState().copy(query = "", submitted = ""), onOpenSettings = { abrio = true })
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Ajustes"))
        compose.onNodeWithText("Ajustes").performClick()
        assertEquals(true, abrio)
    }

    // --- Dictionary management ------------------------------------------------------------------

    private fun openPack(
        id: String,
        name: String,
        bytes: Long,
        demo: Boolean = false,
        lang: String = "es",
    ) =
        PackHandle.Open(
            source = FakeSource(meta(packId = id, lang = lang, name = name)),
            isBundled = demo,
            fileName = "$id.db",
            bytes = bytes,
        )

    private fun oferta(
        id: String,
        estado: CatalogStatus,
        bytes: Long = 37_000_000,
        instalada: Long? = null,
        version: Long = 202609211912L,
    ) = CatalogOffer(
        pack = CatalogPack(
            packId = id, name = id, description = null, langs = listOf("es"), entryCount = 1,
            dataVersion = version, schemaVersion = 4, normVersion = 2, license = null,
            url = "packs/$id.db.gz", bytes = bytes, sha256 = "a", dbBytes = bytes * 2,
            dbSha256 = "b",
        ),
        status = estado,
        installedVersion = instalada,
    )

    @Test
    fun unPackIncompatibleSeMuestraConSuMotivoEnUnaLinea() {
        // ⚠️ **It is the whole request on one screen.** A `.db` that does not load used to
        // disappear from the list: it took disk and there was nothing saying why or how to remove
        // it. Now it is a row, with its size, its reason on one line and its delete button --which
        // is the only possible action on a pack that cannot be opened--.
        compose.setContent {
            PacksScreen(
                packs = listOf(openPack("es-def", "Español", 72_212_480)),
                rejected = listOf(
                    PackHandle.Incompatible("viejo-en.db", 12_500_992L, PackRejection.NORM_VERSION),
                ),
                onDelete = {},
            )
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("viejo-en.db", substring = true))
        compose.onNodeWithText("viejo-en.db", substring = true).assertIsDisplayed()
        // The FILE's name and not a pretty one: the pretty one lives inside the pack and reading
        // it would be loading it. And the reason, which is what turns "it disappeared" into "it is
        // no use, and here is why".
        compose.onNodeWithText("Otras reglas de búsqueda", substring = true).assertIsDisplayed()
    }

    @Test
    fun sinPacksIncompatiblesNoHayFilasDeMas() {
        // The normal case is zero: the list cannot gain a header or a gap for a section that is
        // almost always empty, on a screen measured in dp.
        compose.setContent {
            PacksScreen(
                packs = listOf(openPack("es-def", "Español", 72_212_480)),
                onDelete = {},
            )
        }
        assertEquals(0, compose.onAllNodesWithText(".db", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun entrarALaPantallaNoConsultaElCatalogo() {
        // ⚠️ The assertion that pins the decision: the network is touched when the user presses,
        // and never on mounting the screen. The official guidance puts network access above
        // turning the screen on (D-029), so polling on entry would be the app's most expensive
        // cost.
        var consultas = 0
        compose.setContent {
            PacksScreen(
                packs = listOf(openPack("es-def", "Español", 72_212_480)),
                onDelete = {},
                onCheckCatalog = { consultas++ },
            )
        }
        assertEquals("montar la pantalla no puede consultar el catalogo", 0, consultas)
        compose.onNodeWithText("Consultar el catálogo").assertIsDisplayed()
    }

    @Test
    fun elBotonEsLoQueConsulta() {
        var consultas = 0
        compose.setContent {
            PacksScreen(
                packs = emptyList(),
                onDelete = {},
                onCheckCatalog = { consultas++ },
            )
        }
        compose.onNodeWithText("Consultar el catálogo").performClick()
        assertEquals(1, consultas)
    }

    @Test
    fun mientrasConsultaElBotonNoDisparaUnaSegundaVez() {
        var consultas = 0
        compose.setContent {
            PacksScreen(
                packs = emptyList(),
                onDelete = {},
                catalog = CatalogState.Checking,
                onCheckCatalog = { consultas++ },
            )
        }
        compose.onNodeWithText("Consultando…").performClick()
        assertEquals("un segundo toque no puede lanzar otra consulta", 0, consultas)
    }

    @Test
    // ⚠️ A tall screen on purpose: `TransformingLazyColumn` only composes what is VISIBLE, and at
    // 234 dp the last header falls outside. It is the same device the long-list test already uses.
    @Config(qualifiers = "+w234dp-h1600dp")
    fun lasCategoriasSalenEnOrdenFIJO_actualizar_antes_que_descargar() {
        // Deliberately in the REVERSE order to the expected one: the screen's order cannot depend
        // on the order of the JSON the server sends.
        compose.setContent {
            PacksScreen(
                packs = emptyList(),
                onDelete = {},
                catalog = CatalogState.Ready(
                    listOf(
                        oferta("nuevo", CatalogStatus.DOWNLOAD),
                        oferta("viejo", CatalogStatus.UPDATE, instalada = 200L),
                    ),
                ),
            )
        }
        val enPantalla = compose.onAllNodes(hasText("Hay actualización")).fetchSemanticsNodes().size +
            compose.onAllNodes(hasText("Se puede descargar")).fetchSemanticsNodes().size
        assertEquals("las dos cabeceras tienen que estar", 2, enPantalla)
        // The vertical order: update first. It is what the user came for.
        val y = { texto: String ->
            compose.onNodeWithText(texto).fetchSemanticsNode().positionInRoot.y
        }
        assertTrue(
            "actualizar tiene que ir antes que descargar",
            y("Hay actualización") < y("Se puede descargar"),
        )

    }

    @Test
    fun laFilaDiceLaFECHA_yNoElNumeroCrudoDeVersion() {
        // ⚠️ This replaces an earlier assertion that required BOTH version numbers. Seen on the
        // emulator, the row read `3,0 MB · v202609211912, you have v202609211911`: twelve digits
        // differing in the last one, and 390 px of the ~459 usable at that height.
        compose.setContent {
            PacksScreen(
                packs = emptyList(),
                onDelete = {},
                catalog = CatalogState.Ready(
                    listOf(
                        oferta("es-def", CatalogStatus.UPDATE, version = 202609211912L, instalada = 202609211911L),
                    ),
                ),
            )
        }
        assertEquals(
            "no deberia quedar ningun numero de version crudo en pantalla",
            0,
            compose.onAllNodes(hasText("202609211912", substring = true)).fetchSemanticsNodes().size,
        )
        // And it does say when it is from, in the locale's format.
        compose.onNodeWithText("2026", substring = true).assertIsDisplayed()
    }

    @Test
    fun siLaVersionNoEsUnaFechaLaFilaSeQuedaConElTAMANO() {
        compose.setContent {
            PacksScreen(
                packs = emptyList(),
                onDelete = {},
                catalog = CatalogState.Ready(
                    listOf(oferta("raro", CatalogStatus.DOWNLOAD, version = 7L)),
                ),
            )
        }
        compose.onNodeWithText("MB", substring = true).assertIsDisplayed()
        assertEquals(
            "un 7 no es una fecha y no se puede inventar una",
            0,
            compose.onAllNodes(hasText("1970", substring = true)).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun unCatalogoSinNadaQueOfrecerLoDICE() {
        compose.setContent {
            PacksScreen(
                packs = emptyList(),
                onDelete = {},
                catalog = CatalogState.Ready(
                    // An installed and up-to-date pack is NOT an offer: it is not shown here.
                    listOf(oferta("es-def", CatalogStatus.INSTALLED, instalada = 300L)),
                ),
            )
        }
        compose.onNodeWithText("Nada nuevo", substring = true).assertIsDisplayed()
    }

    @Test
    fun unFalloDeRedSeMuestraYNoTumbaLaPantalla() {
        compose.setContent {
            PacksScreen(
                packs = emptyList(),
                onDelete = {},
                catalog = CatalogState.Failed("Connection refused"),
            )
        }
        compose.onNodeWithText("Connection refused", substring = true).assertIsDisplayed()
        // Y se puede reintentar.
        compose.onNodeWithText("Consultar de nuevo").assertIsDisplayed()
    }

    @Test
    fun eachDictionaryShowsItsSizeAndWhichOneIsInUse() {
        // The size is the only figure that matters when room has to be made, and the home
        // selector does not say it.
        compose.setContent {
            PacksScreen(
                packs = listOf(
                    openPack("es-def", "Español", 72_212_480),
                    openPack("en-def", "English", 309_452_800, lang = "en"),
                ),
                onDelete = {},
            )
        }
        // Size and language together: the pack's name comes from inside the .db and does not
        // always say which language it is.
        compose.onNodeWithText("72,2 MB", substring = true).assertIsDisplayed()
        // ⚠️ **Without the language code**: the pack's NAME already says it --"Español",
        // "Español ↔ English"-- so the code repeated the line above in abbreviation, and it was
        // the third thing competing for a width that was already being clipped.
        assertEquals(
            "la sigla de idioma ya no aparece en la fila",
            0,
            compose.onAllNodesWithText("· ES", substring = true).fetchSemanticsNodes().size,
        )
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("309,5 MB", substring = true))
        // ⚠️ **And NO row marks which is in use.** This inverts what this very test required until
        // today --"only the active one carries a check"--. Asked for: *"removing the
        // selected-language check entirely and letting that be done only from the home screen"*.
        // Choosing a language lives in the home's selector (D-111); having it here too was a
        // second door to the same thing, and its reserved 20 dp gap was exactly the width the size
        // needed in order not to be clipped.
        assertEquals(
            "ninguna fila marca el activo: eso se elige en el inicio",
            0,
            compose.onAllNodesWithContentDescription("En uso").fetchSemanticsNodes().size,
        )
    }

    @Test
    fun theDemoPackOffersNoDeleteButton() {
        // It comes inside the APK and is re-extracted on reopening: the button would do nothing
        // and the pack would come back on its own. Offering it would be a lie.
        compose.setContent {
            PacksScreen(
                packs = listOf(openPack("demo", "Juguete", 53_248, demo = true)),
                onDelete = {},
            )
        }
        assertEquals(
            0,
            compose.onAllNodesWithContentDescription("Borrar Juguete").fetchSemanticsNodes().size,
        )
    }

    @Test
    fun deleteAsksForConfirmationAndDoesNotDeleteOnTheFirstTap() {
        // It is the only action in the app that cannot be undone from the app: putting a pack
        // back is ~90 s over a cable.
        var deleted: String? = null
        compose.setContent {
            PacksScreen(
                packs = listOf(openPack("en-def", "English", 309_452_800)),
                onDelete = { deleted = it },
            )
        }

        compose.onNodeWithContentDescription("Borrar English").performClick()
        compose.waitForIdle()
        assertEquals("no puede borrar al primer toque", null, deleted)

        compose.onNodeWithText("Borrar", substring = false).performClick()
        compose.waitForIdle()
        assertEquals("en-def", deleted)
    }

    @Test
    fun cancellingTheConfirmationDeletesNothing() {
        var deleted: String? = null
        compose.setContent {
            PacksScreen(
                packs = listOf(openPack("en-def", "English", 309_452_800)),
                onDelete = { deleted = it },
            )
        }
        compose.onNodeWithContentDescription("Borrar English").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Cancelar").performClick()
        compose.waitForIdle()
        assertEquals(null, deleted)
    }

    @Test
    fun laSeccionDeDescargaYaNoHablaDeUnCABLE() {
        // ⚠️ This test said the opposite until 2026-09-22: it asserted that the section explains
        // that installing happens over a cable today. **Downloading works now**, so that sentence
        // became false, and a screen telling the user something does not exist when it does is
        // worse than one with no text. What it is right to say now is HOW it starts: by tapping
        // the button.
        compose.setContent {
            PacksScreen(
                packs = listOf(openPack("es-def", "Español", 72_212_480)),
                onDelete = {},
            )
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Para descargar"))
        assertEquals(
            "ya no se instala por cable: la seccion no puede seguir diciendolo",
            0,
            compose.onAllNodes(hasText("cable", substring = true)).fetchSemanticsNodes().size,
        )
        compose.onNodeWithText("Toca", substring = true).assertExists()
    }

    @Test
    fun mientrasESPERA_carga_la_fila_lo_DICE() {
        // ⚠️ D-029 defers the download to charging + Wi-Fi, so tapping download with the watch
        // unplugged downloads nothing YET. Progress that does not move with no explanation reads
        // as a broken app; that is why the wait has text of its own.
        compose.setContent {
            PacksScreen(
                packs = emptyList(),
                onDelete = {},
                catalog = CatalogState.Ready(listOf(oferta("nuevo", CatalogStatus.DOWNLOAD))),
                downloads = mapOf("nuevo" to PackDownload("nuevo", DownloadPhase.WAITING)),
            )
        }
        compose.onNodeWithText("En espera", substring = true).assertIsDisplayed()
    }

    @Test
    fun mientras_BAJA_la_fila_muestra_el_progreso_y_NO_se_puede_reencolar() {
        var pedidas = 0
        compose.setContent {
            PacksScreen(
                packs = emptyList(),
                onDelete = {},
                catalog = CatalogState.Ready(listOf(oferta("nuevo", CatalogStatus.DOWNLOAD))),
                downloads = mapOf(
                    "nuevo" to PackDownload("nuevo", DownloadPhase.RUNNING, done = 5_000_000, total = 10_000_000),
                ),
                onDownload = { pedidas++ },
            )
        }
        compose.onNodeWithText("5,0 MB", substring = true).assertIsDisplayed()
        compose.onNodeWithText("5,0 MB", substring = true).performClick()
        assertEquals("tocar mientras baja no puede reencolar la descarga", 0, pedidas)
    }

    @Test
    fun un_pack_de_otra_version_NO_SE_LISTA_y_se_pide_actualizar_la_app() {
        // ⚠️ This reverses an earlier decision, which gave them a section of their own. Asked for:
        // *"I do not want unavailable packs listed; instead it should just make clear that the app
        // has to be updated"*. The list only carries things that can be had, and the useful answer
        // is not "this pack is no use" but "update the app", which is actionable.
        compose.setContent {
            PacksScreen(
                packs = emptyList(),
                onDelete = {},
                catalog = CatalogState.Ready(emptyList(), needsAppUpdate = true),
            )
        }
        compose.onNodeWithText("versión más nueva", substring = true).assertIsDisplayed()
        assertEquals(
            "no puede quedar ninguna seccion de packs incompatibles",
            0,
            compose.onAllNodes(hasText("no lo abre", substring = true)).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun theHomeOffersSavedWordsEvenWithNoneSaved() {
        // The row used to appear only with saved words: someone who never saved one had no way
        // to discover they could. The screen already carries an empty state explaining it.
        var abrio = false
        showSearch(readyState().copy(query = "", submitted = "", favorites = emptyList()),
            onOpenFavoritos = { abrio = true })
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Guardadas"))
        compose.onNodeWithText("Guardadas").performClick()
        assertEquals(true, abrio)
    }

    @Test
    fun clearingTheHistoryAsksForConfirmationOnTheSameButton() {
        // No dialog: the history rebuilds itself just by using the app, so a second tap is
        // enough. What cannot happen is a stray tap wiping it.
        var deleted = 0
        showSettings(onClearHistory = { deleted++ })
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Borrar el historial"))
        compose.onNodeWithText("Borrar el historial").performClick()
        compose.waitForIdle()
        assertEquals("el primer toque no puede borrar", 0, deleted)

        compose.onNodeWithText("Confirmar").performClick()
        compose.waitForIdle()
        assertEquals(1, deleted)
    }

    @Test
    fun theSearchBarSitsAtTheVeryTop() {
        // It is the primary action: the Wear guidance asks to elevate it, and it used to sit
        // below the header, the word of the day and the voice button.
        showSearch(
            readyState().copy(query = "", submitted = "", wordsOfTheDay = mapOf("es-def" to todaysWord)),
        )
        val barra = compose.onNode(hasSetTextAction()).getBoundsInRoot()
        val word = compose.onNodeWithText("permanecer").getBoundsInRoot()
        assertTrue("la palabra del dia quedo arriba de la barra", barra.top < word.top)
    }

    @Test
    fun everyHomeSectionHasItsHeading() {
        // Without headings, the word of the day was confused with a history entry and the
        // language selector with a result.
        showSearch(
            twoPackState().copy(query = "", submitted = "",
                wordsOfTheDay = mapOf("es-def" to todaysWord),
                history = recent,
            ),
        )
        for (title in listOf("Palabra del día", "Recientes", "Opciones")) {
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(title))
            compose.onNodeWithText(title).assertExists()
        }
    }

    @Test
    fun elSelectorYaNoViveBajoOpciones() {
        // ⚠️ **This test asserted the opposite and the change is deliberate** (D-156). The selector
        // lived at the bottom, under "Options", because there it did not compete with the bar for
        // the top spot. The cost appeared on using it: it disappeared when searching, which is
        // exactly when it is needed --looking at results that are not the expected ones because
        // the active language was not the one you thought--. Now it goes below the bar, and it
        // costs one of the ~3 rows that fit.
        showSearch(twoPackState().copy(query = "", submitted = ""))
        val selector = compose.onNodeWithText("ES").getBoundsInRoot()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Opciones"))
        val options = compose.onNodeWithText("Opciones").getBoundsInRoot()
        assertTrue("el selector tiene que estar ARRIBA de Opciones", selector.top < options.top)
    }

    @Test
    fun withASingleDictionaryThereIsNoEmptyWordOfTheDaySection() {
        // A heading with nothing under it is worse than no heading.
        showSearch(readyState().copy(query = "", submitted = "", wordsOfTheDay = emptyMap()))
        assertEquals(
            0,
            compose.onAllNodesWithText("Palabra del día").fetchSemanticsNodes().size,
        )
    }

    // --- The attribution, which is D-031 ------------------------------------------------------

    @Test
    fun theAttributionShowsTheLicenseAndTheSource() {
        // It is not decorative: it is the condition for using the data. If somebody deletes this
        // screen, this test is the only thing that says so.
        compose.setContent {
            AttributionScreen(packs = listOf(handle(meta())))
        }
        compose.onNodeWithText("Wikcionario", substring = true).assertExists()
        compose.onNodeWithText("CC-BY-SA-4.0", substring = true).assertExists()
    }

    @Test
    fun conVariasFuentesSeMuestranTODAS_conSuPropiaLicencia() {
        // ⚠️ The case that forced D-138: the Spanish pack mixes CC BY-SA 4.0 definitions with CC BY
        // 2.0 FR corpus sentences. Showing a single licence **breaches the other**, and no other
        // test would see it because the pack opens and works just the same.
        val fuentes = listOf(
            PackSource(PackSource.Role.DEFINITIONS, "Wikcionario",
                       "https://es.wiktionary.org/", "CC BY-SA 4.0", ""),
            PackSource(PackSource.Role.SENTENCES, "Tatoeba",
                       "https://tatoeba.org/", "CC BY 2.0 FR", ""),
        )
        compose.setContent {
            AttributionScreen(packs = listOf(handle(meta().copy(sources = fuentes))))
        }
        // The itemised line, not just the name: "Wikcionario" also appears in `attribution`'s
        // prose, and finding it there would not prove the source was declared with ITS licence.
        compose.onNodeWithText("definiciones · Wikcionario · CC BY-SA 4.0").assertExists()
        compose.onNodeWithText("frases · Tatoeba · CC BY 2.0 FR").assertExists()
    }

    @Test
    fun unPackSinFuentesDeclaradasSigueMostrandoSuCredito() {
        // A pack older than D-138 carries no `meta.sources`. It cannot be left WITHOUT
        // attribution: that would turn a format improvement into a licence breach.
        compose.setContent {
            AttributionScreen(packs = listOf(handle(meta().copy(sources = emptyList()))))
        }
        compose.onNodeWithText("Wikcionario", substring = true).assertExists()
        compose.onNodeWithText("CC-BY-SA-4.0", substring = true).assertExists()
    }

    // --- The home and the results share the header (D-157) ----------------------------------

    @Test
    fun elINICIO_TIENE_LA_MISMA_CABECERA_QUE_LOS_RESULTADOS() {
        // Asked for: a big bar on the left, a small voice button on the right, the selector below
        // -- the same in both states. A header that changes shape as you type has to be relearned.
        showSearch(twoPackState().copy(query = "", submitted = ""))
        compose.onNodeWithContentDescription("Decir una palabra").assertExists()
        // And no longer the big button, which was exclusive to the home: the microphone is now the
        // only thing that offers voice, in both states.
        assertEquals(1, compose.onAllNodesWithContentDescription("Decir una palabra")
            .fetchSemanticsNodes().size)
    }

    @Test
    fun laVozEstaALaDerechaDeLaBarra() {
        showSearch(twoPackState().copy(query = "", submitted = ""))
        val barra = compose.onNode(hasSetTextAction()).getBoundsInRoot()
        val voz = compose.onNodeWithContentDescription("Decir una palabra").getBoundsInRoot()
        assertTrue("la voz tiene que ir a la derecha", voz.left.value > barra.left.value)
        assertTrue("y en la misma fila", voz.top.value < barra.bottom.value)
    }

    // --- The language selector, visible with results too (D-156) ---------------------------

    @Test
    fun elSELECTOR_SIGUE_VISIBLE_CON_RESULTADOS_EN_PANTALLA() {
        // ⚠️ It used to live inside the "no search" block, so it disappeared exactly when it is
        // most needed: looking at results that are not the ones you expected because the active
        // language was not the one you thought.
        showSearch(twoPackState("perder"))
        compose.onNodeWithText("ES").assertExists()
        compose.onNodeWithText("EN").assertExists()
    }

    @Test
    fun elSelectorVaJustoDebajoDeLaBarra() {
        // The position matters: the top is where you look, and beside the bar it is clear it
        // modifies what is being searched. At the bottom of the list it would be a hidden setting.
        showSearch(twoPackState("perder"))
        val barra = compose.onNode(hasSetTextAction()).getBoundsInRoot()
        val chip = compose.onNodeWithText("ES").getBoundsInRoot()
        assertTrue(chip.top.value > barra.top.value)
        assertTrue(chip.top.value < barra.bottom.value + 120f)
    }

    // --- Removing a saved word: long-press and confirm (D-155) -----------------------------

    private fun guardadas(onDelete: ((Visit) -> Unit)? = {}) = compose.setContent {
        WordListScreen(
            words = listOf(visita("perro"), visita("gato")),
            title = cl.fadiaz.dictionary.R.string.saved_title,
            empty = cl.fadiaz.dictionary.R.string.saved_empty,
            onDelete = onDelete,
            onOpen = {},
        )
    }

    @Test
    fun UN_TOQUE_NORMAL_NO_ARMA_EL_BORRADO() {
        // The row goes on doing what it did: opening the word. Deletion cannot be one tap away
        // from the most used gesture.
        var abierta = false
        compose.setContent {
            WordListScreen(
                words = listOf(visita("perro")),
                title = cl.fadiaz.dictionary.R.string.saved_title,
                empty = cl.fadiaz.dictionary.R.string.saved_empty,
                onDelete = {},
                onOpen = { abierta = true },
            )
        }
        compose.onNodeWithText("perro").performClick()
        assertTrue(abierta)
        assertEquals(0, compose.onAllNodesWithText("Quitar").fetchSemanticsNodes().size)
    }

    @Test
    fun MANTENER_APRETADO_ARMA_EL_BORRADO() {
        guardadas()
        compose.onNodeWithText("perro").performTouchInput { longClick() }
        compose.onNodeWithText("Quitar").assertExists()
    }

    @Test
    fun CON_EL_BORRADO_ARMADO_EL_TOQUE_CONFIRMA() {
        var borrada: Visit? = null
        guardadas(onDelete = { borrada = it })
        compose.onNodeWithText("perro").performTouchInput { longClick() }
        assertEquals(null, borrada)
        compose.onNodeWithText("Quitar").performClick()
        assertEquals("perro", borrada?.headword)
    }

    @Test
    fun TOCAR_OTRA_FILA_CANCELA_EN_VEZ_DE_ABRIRLA() {
        // ⚠️ With something armed, a tap on another row DISARMS and does not navigate. If it
        // opened the word, the user would leave the screen with a red row waiting for them on
        // their return, and the only way to cancel would be to guess it.
        var abierta: Visit? = null
        compose.setContent {
            WordListScreen(
                words = listOf(visita("perro"), visita("gato")),
                title = cl.fadiaz.dictionary.R.string.saved_title,
                empty = cl.fadiaz.dictionary.R.string.saved_empty,
                onDelete = {},
                onOpen = { abierta = it },
            )
        }
        compose.onNodeWithText("perro").performTouchInput { longClick() }
        compose.onNodeWithText("gato").performClick()
        assertEquals(null, abierta)
        assertEquals(0, compose.onAllNodesWithText("Quitar").fetchSemanticsNodes().size)
    }

    @Test
    fun elHISTORIAL_no_se_puede_armar() {
        // With no `onDelete` the gesture does nothing: the history fills itself and already has a
        // cap.
        compose.setContent {
            WordListScreen(
                words = listOf(visita("perro")),
                title = cl.fadiaz.dictionary.R.string.home_recent,
                empty = cl.fadiaz.dictionary.R.string.history_empty,
                // The map and not a loose tag: this list can bring words from a pack that is no
                // longer installed. See `historyTags`.
                tags = mapOf("es-def" to "ES"),
                onOpen = {},
            )
        }
        compose.onNodeWithText("perro").performTouchInput { longClick() }
        assertEquals(0, compose.onAllNodesWithText("Quitar").fetchSemanticsNodes().size)
    }

    // --- Toda fila de palabra dice lo mismo: palabra · tipo · idioma (D-152) ----------------

    @Test
    fun elHISTORIAL_DEL_INICIO_tambien_dice_el_idioma() {
        // ⚠️ The result row used to say `sust. · ES` and the recent one only `sust.`. Two rows
        // representing the same thing have to say the same thing: otherwise the user learns the
        // tag means something different depending on where it is.
        showSearch(
            readyState().copy(query = "", submitted = "", history = listOf(visita("perro"))),
        )
        compose.onNodeWithText("sust. · ES", substring = true).assertExists()
    }

    @Test
    fun laPANTALLA_DE_LISTA_tambien_dice_el_idioma() {
        compose.setContent {
            WordListScreen(
                words = listOf(visita("perro")),
                title = cl.fadiaz.dictionary.R.string.home_recent,
                empty = cl.fadiaz.dictionary.R.string.history_empty,
                // The map and not a loose tag: this list can bring words from a pack that is
                // no longer installed. See `historyTags`.
                tags = mapOf("es-def" to "ES"),
                onOpen = {},
            )
        }
        compose.onNodeWithText("sust. · ES", substring = true).assertExists()
    }

    @Test
    fun unaPalabraDeUnPackDESCONOCIDO_muestra_solo_el_tipo() {
        // Same as in the results: inheriting the active pack's tag would assert a language nobody
        // checked. Better to say less than to say something false.
        showSearch(
            readyState().copy(
                query = "", submitted = "",
                history = listOf(Visit("fantasma", 1, "perro", "noun")),
            ),
        )
        compose.onNodeWithText("sust.", substring = true).assertExists()
        assertEquals(0, compose.onAllNodesWithText("· ES", substring = true).fetchSemanticsNodes().size)
    }

    // --- Recents: three on the home, the rest behind a button (D-148) ----------------------

    @Test
    fun elInicioMuestraTRES_RECIENTES_Y_UN_BOTON() {
        // On a watch the home is the most contested screen: eight recents pushed settings and
        // attribution out of reach. Three is what is visible without scrolling after the field and
        // the voice button.
        showSearch(
            readyState().copy(
                query = "", submitted = "",
                history = (1..8).map { visita("palabra$it") },
            ),
        )
        compose.onNodeWithText("palabra1").assertExists()
        compose.onNodeWithText("palabra3").assertExists()
        // La cuarta ya no va en el inicio.
        assertEquals(0, compose.onAllNodesWithText("palabra4").fetchSemanticsNodes().size)
        compose.onNodeWithText("Ver más").assertExists()
    }

    @Test
    fun conTRES_O_MENOS_no_hay_boton() {
        // A button leading to the same list you are already looking at is chrome, and on a watch
        // chrome is paid in rows.
        showSearch(
            readyState().copy(query = "", submitted = "", history = (1..3).map { visita("p$it") }),
        )
        compose.onNodeWithText("p3").assertExists()
        assertEquals(0, compose.onAllNodesWithText("Ver más").fetchSemanticsNodes().size)
    }

    @Test
    fun elBotonLlevaAlHistorialCompleto() {
        var abierto = false
        showSearch(
            readyState().copy(query = "", submitted = "",
                              history = (1..8).map { visita("palabra$it") }),
            onOpenHistory = { abierto = true },
        )
        compose.onNodeWithText("Ver más").performClick()
        // If it does not navigate, the button is a dead exit: it is seen, it is tapped and nothing
        // happens.
        assertTrue(abierto)
    }

    @Test
    fun laPantallaDeHistorialMuestraTodas() {
        compose.setContent {
            WordListScreen(
                words = (1..8).map { visita("palabra$it") },
                title = cl.fadiaz.dictionary.R.string.home_recent,
                empty = cl.fadiaz.dictionary.R.string.history_empty,
                onOpen = {},
            )
        }
        // The fourth is the proof: the home shows three, so seeing it here demonstrates this
        // screen is not trimming. The eighth may fall outside the viewport, and asserting it would
        // make the test depend on Robolectric's screen height and not on the logic.
        compose.onNodeWithText("palabra1").assertExists()
        compose.onNodeWithText("palabra4").assertExists()
    }

    // --- Each result's language (D-143) ----------------------------------------------------

    @Test
    fun cadaResultadoDiceDeQueIDIOMAViene() {
        // Asked for: beside the word and its part of speech, the abbreviated language. With two
        // dictionaries of the same language or of different languages coexisting (D-136), a row
        // with no origin forces opening the entry to know where it came from.
        // ⚠️ **With two packs installed the rows NO LONGER mix languages**, and that changes what
        // this test can assert. The search is strict by language across packs (D-189) and now also
        // INSIDE a bidirectional pack (`WHERE lang = ?`), so a list does not contain rows of two
        // languages: the tag is a single one and holds for all of them.
        val es = meta("es-def", "es", "Español")
        val en = meta("en-def", "en", "English")
        showSearch(
            readyState().copy(
                results = listOf(
                    suggestion("perro", packId = "es-def"),
                    suggestion("perra", packId = "es-def"),
                ),
                active = es,
                activeLang = "es",
                available = listOf(handle(es), handle(en)),
            ),
        )
        assertEquals(
            2,
            compose.onAllNodesWithText("sust. · ES", substring = true).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun conDOS_DICCIONARIOS_DEL_MISMO_IDIOMA_la_fila_sigue_diciendo_el_IDIOMA() {
        // ⚠️ **This inverts D-151, which is what this test pinned.** The earlier rule showed the
        // source's abbreviation --`WIKC`, `WD`-- when two dictionaries shared a language, because
        // "ES · ES" does not disambiguate. The request reverses it: *"it should just be EN, ES. I
        // do not like having an ENWIK... because all I care about is knowing the language it comes
        // from"*.
        //
        // What is lost, said so nobody rediscovers it: with two packs of the same language the row
        // **does not say which it came from**. That question is answered by the dictionary
        // management screen; the row answers what language the word I am about to open is in, and
        // the abbreviation is unintelligible without knowing the `pack_id`.
        val wikc = meta("es-def-wikc", "es", "Español")
        val wd = meta("es-def-wd", "es", "Español (Wikidata)")
        showSearch(
            readyState().copy(
                results = listOf(suggestion("perro", packId = "es-def-wikc"),
                                 suggestion("perruno", packId = "es-def-wd")),
                active = wikc,
                available = listOf(handle(wikc), handle(wd)),
            ),
        )
        // BOTH rows say `ES`, which is exactly the point: the tag no longer distinguishes packs,
        // only languages.
        assertEquals(
            "las dos filas llevan la etiqueta del idioma",
            2,
            compose.onAllNodesWithText("sust. · ES", substring = true).fetchSemanticsNodes().size,
        )
        assertEquals(
            "la sigla de la fuente ya no aparece en ninguna fila",
            0,
            compose.onAllNodesWithText("WIKC", substring = true).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun conDOS_PACKS_DEL_MISMO_IDIOMA_hay_UNA_palabra_del_dia() {
        // ⚠️ The bug D-145 covered up by merging packs: the map is computed per pack, so two
        // Spanish dictionaries gave two words of the day in the same language.
        val wikc = meta("es-def-wikc", "es", "Español")
        val wd = meta("es-def-wd", "es", "Español (Wikidata)")
        showSearch(
            readyState().copy(
                query = "", submitted = "",
                active = wikc,
                available = listOf(handle(wikc), handle(wd)),
                wordsOfTheDay = mapOf(
                    "es-def-wikc" to resumen("guanaco"),
                    "es-def-wd" to resumen("iquiteño"),
                ),
            ),
        )
        compose.onNodeWithText("guanaco").assertExists()
        assertEquals(0, compose.onAllNodesWithText("iquiteño").fetchSemanticsNodes().size)
    }

    @Test
    fun unResultadoLLEVA_EL_IDIOMA_BUSCADO_porque_la_consulta_lo_garantiza() {
        // ⚠️ **This replaces `unResultadoDeUnPackDESCONOCIDONoInventaIdioma`**, and the change is
        // not relaxing the rule but that the rule stopped needing a mechanism.
        //
        // That test protected against inheriting the active pack's tag, because a `packId` outside
        // the map could not be resolved to a language (D-080's family). Today **a result in
        // another language cannot exist**: `SearchRepository` only queries packs that speak the
        // active language (D-189) and inside a bidirectional pack every rung filters by
        // `entry.lang`. The tag is true **by construction of the query**, not by a map -- which is
        // stronger, because a map can drift.
        //
        // What DOES keep the old mechanism is the history: there a row can come from an
        // uninstalled pack. See `unaPalabraDeUnPackDESCONOCIDO_muestra_solo_el_tipo`.
        showSearch(readyState().copy(results = listOf(suggestion("perro"))))
        compose.onNodeWithText("sust. · ES", substring = true).assertExists()
    }

    // --- The language selector ----------------------------------------------------------------

    @Test
    fun laPALABRA_DEL_DIA_sale_de_un_pack_de_DEFINICIONES_aunque_el_bilingue_sea_MAYOR() {
        // ⚠️ **Two rules that are fine separately and together erased the word of the day.** A
        // translation pack generates none --asked for: *"this stays only for the definitions
        // dictionaries"*, and the reason is visible on opening it: a reverse entry has no senses,
        // so it would say "you say `perro`" and nothing else--. But each language's representative
        // is the **largest** pack, and the bilingual one became that for both: 209,484 against
        // Spanish's 152,281 and the English core's 16,652.
        //
        // Result on the emulator: the section disappeared entirely. The screen was asking for the
        // word of a pack that, correctly, generates none.
        val bi = meta("es-tr-enwikt", "es", "Español ↔ English", entries = 209_484,
                      langs = listOf("es", "en"))
        val defs = meta("es-def-wikc", "es", "Español", entries = 152_281)
        showSearch(
            readyState().copy(
                query = "", submitted = "",
                // ⚠️ **The ACTIVE one is the bilingual**, which is the real case: `chooseActive`
                // takes the largest pack, and the bilingual one is it. With the definitions one
                // active the failure does not appear --`representativePacks` respects the active
                // one-- which is why this test's first version was vacuous: mutating the fix, it
                // went on passing.
                active = bi, activeLang = "es",
                available = listOf(handle(bi), handle(defs)),
                wordsOfTheDay = mapOf("es-def-wikc" to resumen("futuro")),
            ),
        )
        compose.onNodeWithText("futuro").assertExists()
    }

    @Test
    fun conUN_SOLO_PACK_BIDIRECCIONAL_el_selector_IGUAL_aparece() {
        // ⚠️ **Found on the emulator, and it is the case the bidirectional pack exists to serve.**
        // The selector was drawn with `state.available.size > 1` --it counted FILES-- so with only
        // `es-tr-enwikt` installed none appeared, and **there was no way to reach its English
        // half**: the pack spoke two languages and the app offered zero.
        //
        // What gets counted now are LANGUAGES, which is what the chip chooses since D-197.
        val bi = meta("es-tr-enwikt", "es", "Español ↔ English", langs = listOf("es", "en"))
        showSearch(
            readyState().copy(
                query = "", submitted = "",
                active = bi, activeLang = "es", available = listOf(handle(bi)),
            ),
        )
        compose.onNodeWithText("ES").assertExists()
        compose.onNodeWithText("EN").assertExists()
    }

    @Test
    fun withTwoPacksTheSelectorShowsBothLanguages() {
        showSearch(twoPackState().copy(query = "", submitted = ""))
        compose.onNodeWithText("ES").assertIsDisplayed()
        compose.onNodeWithText("EN").assertIsDisplayed()
    }

    @Test
    fun withASinglePackThereIsNoSelector() {
        // A one-option selector is pure chrome, and on 192 dp chrome costs results.
        showSearch(readyState().copy(query = "", submitted = ""))
        assertEquals(0, compose.onAllNodesWithText("ES").fetchSemanticsNodes().size)
    }

    @Test
    fun tappingTheOtherLanguageReportsIt() {
        // ⚠️ **It reports the LANGUAGE and no longer a `packId`.** A bidirectional pack speaks
        // two, so choosing it did not say which to search in: the chip became what D-147 already
        // said it was.
        var chosen: String? = null
        showSearch(twoPackState().copy(query = "", submitted = ""), onLanguageChange = { chosen = it })
        compose.onNodeWithText("EN").performClick()
        assertEquals("en", chosen)
    }

    @Test
    fun withTwoPacksThreeResultsStillFit() {
        // Re-checks D-073 with the selector present: the selector cannot cost a row.
        showSearch(twoPackState("perder", "perro", "permitir", "persona"))
        compose.onNodeWithText("permitir").assertIsDisplayed()
    }

    @Test
    fun withNoResultsItOffersSearchingTheOtherLanguage() {
        // It is the escape hatch: you typed something this language does not have.
        //
        // ⚠️ **It names the LANGUAGE and not the pack**, and it used to read "Buscar en
        // English" -- the second pack's name -- while the tap switched a language computed
        // independently of it. With three packs installed those two disagree: the pill could
        // name one file and activate a language whose representative is another. A label
        // asserting a provenance nobody checked is D-080's family.
        //
        // ⚠️ **The pack is deliberately NOT called "English" here.** With `twoPackState`'s
        // default name the assertion would pass whether the label came from the language or
        // from the pack, which is the very confusion this closes -- a test that cannot tell the
        // fixed behaviour from the broken one is worth nothing.
        val es = meta()
        val en = meta("en-def", "en", "Diccionario inglés de bolsillo")
        val state = readyState().copy(
            query = "dog",
            submitted = "dog",
            results = emptyList(),
            active = es,
            activeLang = "es",
            available = listOf(handle(es), handle(en)),
        )
        showSearch(state)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Buscar en", substring = true))
        // The LANGUAGE's own name: a sentence gets a word, not a code (D-261).
        compose.onNodeWithText("Buscar en English", substring = true).assertIsDisplayed()
        assertEquals(
            "the pill named the pack instead of the language",
            0,
            compose.onAllNodesWithText("bolsillo", substring = true).fetchSemanticsNodes().size,
        )
        assertEquals(
            "the pill used the code where a sentence needs a word",
            0,
            compose.onAllNodesWithText("Buscar en EN", substring = true).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun aLoneBidirectionalPackStillOffersTheOtherLanguage() {
        // ⚠️ **The hatch used to be gated on a SECOND PACK existing**, so the one case D-195
        // created -- a single file speaking two languages -- hid it completely: `es-tr-enwikt`
        // has `casa` and `house`, the user searching `es` got nothing for `dog`, and there was
        // no way out of the language even though the same pack could answer.
        //
        // What decides is the LANGUAGE, and `idiomasDisponibles` gets both from one pack.
        val bidi = meta("es-en", "es", "Español ↔ English", langs = listOf("es", "en"))
        val state = readyState().copy(
            query = "dog",
            submitted = "dog",
            results = emptyList(),
            active = bidi,
            activeLang = "es",
            available = listOf(handle(bidi)),
        )
        showSearch(state)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Buscar en", substring = true))
        compose.onNodeWithText("Buscar en English", substring = true).assertIsDisplayed()
    }

    @Test
    fun theAttributionShowsBothPacks() {
        // D-031 with two sources: showing one license alone breaches the other one's terms.
        compose.setContent {
            AttributionScreen(packs = listOf(handle(meta()), handle(meta("en-def", "en", "English"))))
        }
        compose.onNodeWithText("Español", substring = true).assertExists()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("English", substring = true))
        compose.onNodeWithText("English", substring = true).assertExists()
    }

    @Test
    fun aRejectedPackIsNotCredited() {
        // ⚠️ **The request, literally: that an incompatible pack not be loaded in any way, "for
        // instance that it not show up in the credits".** This screen credits the sources of the
        // content the app is using; a pack that does not load contributes no content, so naming it
        // here credits something nobody is reading.
        compose.setContent {
            AttributionScreen(
                packs = listOf(
                    handle(meta()),
                    PackHandle.Incompatible("de-def.db", 1_000L, PackRejection.NORM_VERSION),
                ),
            )
        }
        compose.onNodeWithText("Español", substring = true).assertExists()
        assertEquals(
            0,
            compose.onAllNodesWithText("de-def", substring = true).fetchSemanticsNodes().size,
        )
    }

    // --- The history -------------------------------------------------------------------------

    private val recent = listOf(
        Visit("es-def", 1, "perro", "noun"),
        Visit("en-def", 2, "house", "noun"),
    )

    @Test
    fun withAnEmptySearchTheRecentEntriesShow() {
        showSearch(readyState().copy(query = "", submitted = "", history = recent))
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("perro"))
        compose.onNodeWithText("perro").assertIsDisplayed()
    }

    @Test
    fun withASearchAlreadyRunTheHistoryDisappears() {
        // It cannot compete with the results: on 192 dp three rows fit.
        showSearch(readyState("perder").copy(query = "per", submitted = "per", history = recent))
        assertEquals(0, compose.onAllNodesWithText("house").fetchSemanticsNodes().size)
    }

    @Test
    fun whileTypingTheHistoryStays() {
        // The reverse of the previous one, and it is D-128's fix. The first letter used to make
        // the header, the voice button, the word of the day and the history disappear at once;
        // that restructuring destroyed the text field and took the focus and the keyboard with it.
        // With the keyboard open `submitted` does not move, so the list stays as it was.
        showSearch(readyState().copy(query = "per", submitted = "", history = recent))
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("perro"))
        compose.onNodeWithText("perro").assertExists()
    }

    @Test
    fun tappingARecentEntryOpensIt() {
        var abierta: Visit? = null
        showSearch(
            readyState().copy(query = "", submitted = "", history = recent),
            onOpenVisita = { abierta = it },
        )
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("perro"))
        compose.onNodeWithText("perro").performClick()
        assertEquals("perro", abierta?.headword)
        assertEquals("tiene que abrir en SU pack, no en el activo", "es-def", abierta?.packId)
    }

    // --- Searching the definitions ---------------------------------------------------------------

    @Test
    fun withNoResultsItOffersSearchingTheDefinitions() {
        showSearch(readyState().copy(query = "animal que ladra", submitted = "animal que ladra"))
        compose.onNodeWithText("Buscar en las definiciones").assertIsDisplayed()
    }

    @Test
    fun withResultsItDoesNotOfferSearchingTheDefinitions() {
        // Protects D-073: on 192 dp a row of chrome is a third of the list.
        showSearch(readyState("perder", "perro"))
        assertEquals(
            0,
            compose.onAllNodesWithText("Buscar en las definiciones").fetchSemanticsNodes().size,
        )
    }

    @Test
    fun tappingSearchDefinitionsReportsIt() {
        var pedido = false
        showSearch(readyState().copy(query = "ladra", submitted = "ladra"), onSearchDefinitions = { pedido = true })
        compose.onNodeWithText("Buscar en las definiciones").performClick()
        assertEquals(true, pedido)
    }

    @Test
    fun inDefinitionModeWithNoResultsTheSameOptionIsNotOfferedAgain() {
        // Offering it again would be a loop: the search already ran and there is nothing.
        showSearch(
            readyState().copy(query = "xyzzy", submitted = "xyzzy", mode = SearchState.Mode.DEFINICIONES),
        )
        compose.onNodeWithText("Sin resultados en las definiciones").assertIsDisplayed()
        assertEquals(
            0,
            compose.onAllNodesWithText("Buscar en las definiciones").fetchSemanticsNodes().size,
        )
    }

    @Test
    fun whileSearchingTheDefinitionsItSaysSo() {
        showSearch(
            readyState().copy(query = "ladra", submitted = "ladra", mode = SearchState.Mode.BUSCANDO_DEFINICIONES),
        )
        compose.onNodeWithText("Buscando", substring = true).assertIsDisplayed()
    }

    @Test
    fun theAttributionIsReachableFromTheSearch() {
        var abierta = false
        showSearch(readyState("perder"), onOpenAttribution = { abierta = true })
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Sobre estos datos"))
        compose.onNodeWithText("Sobre estos datos").performClick()
        assertEquals(true, abierta)
    }

    @Test
    fun theRowKeepsTheAbbreviation() {
        // The other half of the request --"abbreviated where there is no space, expanded inside
        // the card"-- and the one that breaks on its own if somebody "unifies" the two: in a
        // results row "sustantivo" eats the lemma's width, which is the only thing that matters
        // there.
        showSearch(readyState("perro"))
        compose.onNodeWithText("sust.", substring = true).assertIsDisplayed()
    }

    @Test
    fun theThreeTermListsEachCarryTheirOwnHeading() {
        // Asked for: "showing the category first and the words below". The three lists look
        // identical --same style, same position, same separator-- so the category is the only
        // thing that says which you are reading. It used to be a four-letter prefix.
        compose.setContent {
            EntryScreen(1, onOpenWord = {}) {
                entry().copy(
                    senses = listOf(
                        Sense(
                            "de temperatura alta",
                            synonyms = listOf("ardiente"),
                            antonyms = listOf("gélido"),
                            related = listOf("calor"),
                        ),
                    ),
                )
            }
        }
        compose.onNodeWithText("Sinónimos").assertIsDisplayed()
        compose.onNodeWithText("Antónimos").assertIsDisplayed()
        compose.onNodeWithText("Relacionadas").assertIsDisplayed()
    }

    @Test
    fun aHeadingWithNothingUnderItIsNotDrawn() {
        // A sense with no antonyms cannot show "Antónimos" and nothing below: on a watch that
        // reads as the list being empty because of an error.
        compose.setContent {
            EntryScreen(1, onOpenWord = {}) {
                entry().copy(senses = listOf(Sense("de poco entendimiento",
                    synonyms = listOf("bobo"))))
            }
        }
        compose.onNodeWithText("Sinónimos").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("Antónimos").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("Relacionadas").fetchSemanticsNodes().size)
    }

    @Test
    fun aTermThatIsNotInThePackIsStillShownJustNotAsALink() {
        // ⚠️ The gloss's same rule: the colour is the promise that it leads somewhere. A synonym
        // the pack does not have **is still shown** --the source says it and hiding it would lose
        // information-- but unpainted.
        compose.setContent {
            EntryScreen(1, onOpenWord = {}, resolveIn = { _, _, _ -> mapOf("bobo" to WordLink("es-def", 42L)) }) {
                entry().copy(
                    senses = listOf(Sense("de poco entendimiento",
                        synonyms = listOf("bobo", "zonzo"))),
                )
            }
        }
        compose.onNodeWithText("bobo · zonzo").assertIsDisplayed()
    }

    @Test
    fun theBundledPackSaysWhyItHasNoDeleteButton() {
        // It could already not be deleted --it would come back on restart-- but the row did not
        // say so: a button missing with no explanation reads as a bug, not as a decision.
        showPacks(listOf(handle(meta(packId = "es-core-wikc", name = "Español")).copy(
            isBundled = true, bytes = 7_500_000,
        )))
        compose.onNodeWithText("Incluido", substring = true).assertIsDisplayed()
    }

    @Test
    fun anInstalledPackDoesNotSayItIsBundled() {
        // The control: the label has to distinguish, not decorate.
        showPacks(listOf(handle(meta()).copy(fileName = "es-def-wikc.db", bytes = 71_000_000)))
        assertEquals(
            0,
            compose.onAllNodesWithText("Incluido", substring = true)
                .fetchSemanticsNodes().size,
        )
    }

    @Test
    fun onlyTheInstalledPackOffersDeleting() {
        // What already held and no test pinned: the APK's pack offers no delete.
        showPacks(
            listOf(
                handle(meta(packId = "es-core-wikc", name = "Núcleo")).copy(isBundled = true),
                handle(meta(packId = "es-def-wikc", name = "Completo")).copy(
                    fileName = "es-def-wikc.db",
                ),
            ),
        )
        assertEquals(
            "sólo el instalado se puede borrar",
            1,
            compose.onAllNodesWithContentDescription("Borrar", substring = true)
                .fetchSemanticsNodes().size,
        )
    }

    /** The dictionaries screen, which is a function of the pack list and of which one is active. */
    private fun showPacks(packs: List<PackHandle.Open>, active: String? = null) {
        compose.setContent {
            PacksScreen(
                packs = packs,
                onDelete = {},
            )
        }
    }

    /** Settings is a function of its state too: nothing here reads a system service. */
    private fun showSettings(
        packs: List<PackHandle> = emptyList(),
        uiLanguage: String? = null,
        onUiLanguageChange: (String?) -> Unit = {},
        onClearHistory: () -> Unit = {},
        hasHistory: Boolean = true,
        crossLanguageFallback: Boolean = false,
        onCrossLanguageFallbackChange: (Boolean) -> Unit = {},
    ) {
        compose.setContent {
            SettingsScreen(
                packs = packs,
                scale = cl.fadiaz.dictionary.data.TextScale.NORMAL,
                appVersion = "9.9.9",
                buildCommit = "abc1234567+dirty",
                buildTime = "2026-09-23 15:02 UTC",
                uiLanguage = uiLanguage,
                onUiLanguageChange = onUiLanguageChange,
                onManagePacks = {},
                onScaleChange = {},
                crossLanguageFallback = crossLanguageFallback,
                onCrossLanguageFallbackChange = onCrossLanguageFallbackChange,
                onClearHistory = onClearHistory,
                hasHistory = hasHistory,
            )
        }
    }

    @Test
    @Config(qualifiers = "+w234dp-h1600dp")
    fun theSearchSettingIsAboveTheLooks() {
        // D-266. The switch is the only row in Settings that changes what a SEARCH returns;
        // everything under it changes how the app looks. Somebody arriving because "it does not
        // find my English words" has to meet it before three rows about type size.
        var encendido: Boolean? = null
        showSettings(onCrossLanguageFallbackChange = { encendido = it })
        compose.onNodeWithText("Buscar también en el otro idioma").performClick()
        assertEquals(true, encendido)
    }

    @Test
    // The same qualifier as the one below, and for the same reason: the About block is the last
    // thing in the app's longest list, and at 900 dp it does not get composed -- the assertion
    // would measure nothing.
    @Config(qualifiers = "+w234dp-h1600dp")
    fun `el diagnostico dice de QUE build se trata, no solo su version`() {
        // ⚠️ **`versionName` is the same across ten builds on the same day.** Without the commit,
        // *"the fix did not work"* and *"the watch is running yesterday's APK"* are the same
        // report, and on a watch the second is likely: every cache between the machine and the
        // wrist favours it. The `+dirty` is the honest half -- almost everything installed comes
        // out of an uncommitted tree, and there the hash does NOT identify what is running.
        showSettings()
        compose.onNodeWithText("abc1234567+dirty", substring = true).assertExists()
    }

    @Test
    fun withNothingChosenTheLanguagePickerSaysAutomatic() {
        // The default is not English and not Spanish: it is "follow the watch". If the picker
        // opened with a language marked, changing the watch would stop changing the app and
        // nobody would connect the two.
        showSettings(uiLanguage = null)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Automático"))
        compose.onNodeWithText("Automático").assertIsSelected()
    }

    @Test
    fun theRegionOfTheWatchDoesNotUnselectTheLanguage() {
        // The platform hands back "es-CL" for what the picker wrote as "es".
        showSettings(uiLanguage = "es-CL")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Español"))
        compose.onNodeWithText("Español").assertIsSelected()
    }

    @Test
    fun choosingALanguageReportsItsTagAndAutomaticReportsNothing() {
        var chosen: String? = "sin tocar"
        showSettings(uiLanguage = null, onUiLanguageChange = { chosen = it })
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Español"))
        compose.onNodeWithText("Español").performClick()
        compose.waitForIdle()
        assertEquals("es", chosen)

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Automático"))
        compose.onNodeWithText("Automático").performClick()
        compose.waitForIdle()
        // null and not "": automatic is the ABSENCE of a choice, which is what an empty
        // LocaleList means to the platform.
        assertEquals(null, chosen)
    }

    @Test
    @Config(qualifiers = "+w234dp-h1600dp")
    fun elDIAGNOSTICO_va_al_FONDO_y_no_nombra_diccionarios() {
        // The user asked for them at the bottom: they are looked up once, when something is
        // wrong, and they must not push the settings anybody actually changes off the screen.
        // The 1600 dp qualifier is not a claim about any watch: it is the only way both ends of
        // a lazy list compose at the same time so their order can be compared. At 900 dp the
        // About block was still outside the viewport and the assertion measured nothing.
        showSettings(
            packs = listOf(
                handle(meta(packId = "es-def", name = "Español", entries = 114619)),
                handle(meta(packId = "en-def", lang = "en", name = "English", entries = 794355)),
            ),
        )
        compose.onNodeWithText("App 9.9.9").assertIsDisplayed()
        // ⚠️ **The per-dictionary entry count was removed on request.** It was diagnostics that
        // decide nothing --how many lemmas a pack carries does not say whether it works-- and it
        // cost one row per dictionary on the app's longest screen. The datum that does decide, the
        // size on disk, lives in dictionary management, which is where you delete them.
        assertEquals(
            "ya no se nombra ningún diccionario en el diagnóstico",
            0,
            compose.onAllNodesWithText("entradas", substring = true).fetchSemanticsNodes().size,
        )

        val historial = compose.onNodeWithText("Borrar el historial").getBoundsInRoot()
        val version = compose.onNodeWithText("App 9.9.9").getBoundsInRoot()
        assertEquals(
            "la informacion de diagnostico va al fondo, debajo del historial",
            true,
            version.top > historial.top,
        )
    }
}


/** The minimum to wrap a `PackMetadata` in a `PackHandle`. The screens never query. */
private class FakeSource(override val metadata: PackMetadata) : DictionarySource {
    override suspend fun suggest(query: String, limit: Int, lang: String?) = emptyList<Suggestion>()
    override suspend fun entry(entryId: Long): Entry? = null
    override suspend fun searchDefinitions(query: String, limit: Int, lang: String?) = emptyList<Suggestion>()
    override suspend fun resolveHeadwords(norms: Set<String>, lang: String?) = emptyMap<String, Long>()
    override suspend fun summary(entryId: Long): EntrySummary? = null
    override fun close() = Unit
}
