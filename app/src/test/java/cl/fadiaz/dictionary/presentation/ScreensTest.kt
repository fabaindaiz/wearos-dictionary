package cl.fadiaz.dictionary.presentation

import androidx.compose.ui.test.assertIsDisplayed
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
import androidx.test.ext.junit.runners.AndroidJUnit4
import cl.fadiaz.dictionary.core.DictionarySource
import cl.fadiaz.dictionary.core.Entry
import cl.fadiaz.dictionary.R
import cl.fadiaz.dictionary.core.EntrySummary
import cl.fadiaz.dictionary.core.FuzzyProfile
import cl.fadiaz.dictionary.core.PackKind
import cl.fadiaz.dictionary.core.PackMetadata
import cl.fadiaz.dictionary.data.PackHandle
import cl.fadiaz.dictionary.data.Visit
import cl.fadiaz.dictionary.core.MatchKind
import cl.fadiaz.dictionary.core.Sense
import cl.fadiaz.dictionary.core.Suggestion
import org.junit.Assert.assertEquals
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

    private fun suggestion(headword: String, pos: String? = "noun") = Suggestion(
        packId = "test",
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
    ) = PackMetadata(
        packId = packId,
        schemaVersion = 3,
        normVersion = 1,
        kind = PackKind.MONOLINGUAL,
        name = name,
        // null: exercises the path of a pack older than D-125, which does not carry the key.
        description = null,
        langSource = lang,
        langTarget = null,
        fuzzyProfile = FuzzyProfile.SPANISH,
        entryCount = 1,
        dataVersion = 1,
        license = "CC-BY-SA-4.0",
        attribution = "Definiciones del Wikcionario, CC BY-SA 4.0",
    )

    private fun handle(m: PackMetadata) = PackHandle.Open(FakeSource(m))

    private fun readyState(vararg headwords: String) = SearchState(
        // Los dos: este estado representa una busqueda YA HECHA. `submitted` es lo que la lista
        // refleja y `query` lo que el campo muestra; con el teclado abierto se separan (D-128).
        query = "per",
        submitted = "per",
        results = headwords.map { suggestion(it) },
        status = SearchState.Status.Ready,
        active = meta(),
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

    private fun showSearch(
        state: SearchState,
        onOpenEntry: (Suggestion) -> Unit = {},
        onOpenAttribution: () -> Unit = {},
        onPackChange: (String) -> Unit = {},
        onSearchDefinitions: () -> Unit = {},
        onOpenVisita: (Visit) -> Unit = {},
        onOpenSettings: () -> Unit = {},
        onOpenFavoritos: () -> Unit = {},
        onOpenWordOfTheDay: (String, EntrySummary) -> Unit = { _, _ -> },
    ) = compose.setContent {
        SearchScreen(state, onQueryChange = {}, onPackChange = onPackChange,
            onSearchDefinitions = onSearchDefinitions, onOpenVisita = onOpenVisita,
            onOpenEntry = onOpenEntry, onOpenAttribution = onOpenAttribution,
            onOpenSettings = onOpenSettings, onOpenFavoritos = onOpenFavoritos,
            onOpenWordOfTheDay = onOpenWordOfTheDay)
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
        // EL RELOJ GENERICO. 192 dp es la aritmetica con la que se justificaron D-073, D-075,
        // D-078, D-084 y D-085, y tiene que seguir funcionando: la app no se optimiza para un
        // reloj rompiendo el otro.
        //
        // ⚠️ Son DOS, no tres. El test que esto reemplaza --`entranTresResultadosSinScrollear`--
        // afirmaba tres y pasaba, pero corria con el dispositivo POR DEFECTO de Robolectric, que
        // no es un reloj: con las cuatro sugerencias componia las cuatro. Pasaba por el motivo
        // equivocado.
        //
        // El numero simulado no es el mismo que el medido en el emulador de 384x384 (que da
        // tres): el qualifier `h192dp` es alto DISPONIBLE y descuenta decoracion. Lo que este
        // test fija no es el absoluto sino que el generico sigue mostrando resultados utiles.
        showSearch(readyState("perder", "perro", "permitir", "persona"))
        assertEquals(2, visibles(listOf("perder", "perro", "permitir", "persona")).size)
    }

    @Test
    @Config(qualifiers = "+w234dp-h234dp")
    fun theProjectsWatchFitsOneMoreResultThanTheGenericOne() {
        // EL RELOJ DEL PROYECTO. El SM-L715F entrega `sw234dp w234dp h234dp 340dpi`, confirmado
        // preguntandole al sistema qué configuracion recibe la app.
        //
        // **Esta es la propiedad que importa y la unica que se puede sostener**: 22 % mas
        // pantalla entra UNA FILA MAS. El absoluto depende de cuanto descuente la decoracion; la
        // relacion, no. Y es lo que deja revisar las cinco decisiones cotizadas contra 192 dp sin
        // tener el reloj delante.
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
    fun theEntryShowsHeadwordPartOfSpeechAndNumberedSenses() {
        compose.setContent { EntryScreen(1, onOpenWord = {}) { entry("Mamífero cánido doméstico.") } }
        compose.onNodeWithText("perro").assertIsDisplayed()
        compose.onNodeWithText("sust.", substring = true).assertExists()
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
        compose.onNodeWithText("sin. ardiente", substring = true).assertExists()
        compose.onNodeWithText("ant. gélido", substring = true).assertExists()
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
                examples = if (number == 1) listOf(longExample) else emptyList(),
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
            EntryScreen(entryId = 1, onOpenWord = {}, resolveIn = { emptyMap() }) {
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
            EntryScreen(entryId = 1, onOpenWord = {}, resolveIn = { mapOf("cera" to 1L) }) {
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
        // With a single pack the subtitle says "palabra del día"; with two, the dictionary's
        // name, which is the only thing telling them apart.
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
        // Short name AND kind (D-125): the name stopped saying what kind of dictionary it is,
        // so the row has to say it separately or the fact is lost.
        compose.onNodeWithText("English · definiciones").assertExists()
        assertEquals(
            "con dos diccionarios el subtitulo es el idioma, no la etiqueta generica",
            0,
            compose.onAllNodesWithText("palabra del día").fetchSemanticsNodes().size,
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
            isDemo = demo,
            fileName = "$id.db",
            bytes = bytes,
        )

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
                active = "es-def",
                onActivate = {},
                onDelete = {},
            )
        }
        // Size and language together: the pack's name comes from inside the .db and does not
        // always say which language it is.
        compose.onNodeWithText("72,2 MB", substring = true).assertIsDisplayed()
        compose.onNodeWithText("· ES", substring = true).assertExists()
        compose.onNodeWithText("· EN", substring = true).assertExists()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("309,5 MB", substring = true))
        compose.onNodeWithContentDescription("En uso").assertExists()
        assertEquals(
            "solo el activo lleva check",
            1,
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
                active = "demo",
                onActivate = {},
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
                active = "en-def",
                onActivate = {},
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
                active = "en-def",
                onActivate = {},
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
    fun theDownloadSectionSaysNotYetAndHowToInstallToday() {
        // A bare "coming soon" leaves the user with no idea how to install a dictionary.
        compose.setContent {
            PacksScreen(
                packs = listOf(openPack("es-def", "Español", 72_212_480)),
                active = "es-def",
                onActivate = {},
                onDelete = {},
            )
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Para descargar"))
        compose.onNodeWithText("cable", substring = true).assertExists()
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
        compose.setContent {
            SettingsScreen(
                packs = emptyList(),
                scale = cl.fadiaz.dictionary.data.TextScale.NORMAL,
                onManagePacks = {},
                onScaleChange = {},
                onClearHistory = { deleted++ },
                hasHistory = true,
            )
        }
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
    fun theLanguageSelectorLivesUnderOptions() {
        showSearch(twoPackState().copy(query = "", submitted = ""))
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Opciones"))
        val options = compose.onNodeWithText("Opciones").getBoundsInRoot()
        val selector = compose.onNodeWithText("ES").getBoundsInRoot()
        assertTrue("el selector quedo fuera de Opciones", selector.top >= options.top)
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

    // --- The language selector ----------------------------------------------------------------

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
        var chosen: String? = null
        showSearch(twoPackState().copy(query = "", submitted = ""), onPackChange = { chosen = it })
        compose.onNodeWithText("EN").performClick()
        assertEquals("en-def", chosen)
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
        showSearch(twoPackState().copy(query = "dog", submitted = "dog"))
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Buscar en", substring = true))
        compose.onNodeWithText("Buscar en English", substring = true).assertIsDisplayed()
    }

    @Test
    fun theAttributionShowsBothPacks() {
        // D-031 with two sources: showing one license alone breaches the other one's terms.
        compose.setContent {
            AttributionScreen(
                packs = listOf(handle(meta()), handle(meta("en-def", "en", "English"))),
                problems = listOf("de-def: dañado"),
            )
        }
        compose.onNodeWithText("Español", substring = true).assertExists()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("English", substring = true))
        compose.onNodeWithText("English", substring = true).assertExists()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("dañado", substring = true))
        compose.onNodeWithText("dañado", substring = true).assertExists()
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
        // El reverso del anterior, y es el arreglo de D-128. Antes la primera letra hacia
        // desaparecer encabezado, voz, palabra del dia e historial de un golpe; esa
        // reestructuracion destruia el campo de texto y se llevaba el foco y el teclado. Con el
        // teclado abierto `submitted` no se mueve, asi que la lista se queda como estaba.
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
}


/** The minimum to wrap a `PackMetadata` in a `PackHandle`. The screens never query. */
private class FakeSource(override val metadata: PackMetadata) : DictionarySource {
    override suspend fun suggest(query: String, limit: Int) = emptyList<Suggestion>()
    override suspend fun entry(entryId: Long): Entry? = null
    override suspend fun searchDefinitions(query: String, limit: Int) = emptyList<Suggestion>()
    override suspend fun resolveHeadwords(norms: Set<String>) = emptyMap<String, Long>()
    override suspend fun summary(entryId: Long): EntrySummary? = null
    override fun close() = Unit
}
