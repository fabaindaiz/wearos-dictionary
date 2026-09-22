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
import cl.fadiaz.dictionary.core.EntrySummary
import cl.fadiaz.dictionary.core.FuzzyProfile
import cl.fadiaz.dictionary.core.PackKind
import cl.fadiaz.dictionary.core.PackMetadata
import cl.fadiaz.dictionary.core.PackSource
import cl.fadiaz.dictionary.data.CatalogOffer
import cl.fadiaz.dictionary.data.CatalogPack
import cl.fadiaz.dictionary.data.CatalogState
import cl.fadiaz.dictionary.data.CatalogStatus
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
        /** Con dos, el pack es bidireccional y aporta un chip por cada uno. */
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
        // Los dos: este estado representa una busqueda YA HECHA. `submitted` es lo que la lista
        // refleja y `query` lo que el campo muestra; con el teclado abierto se separan (D-128).
        query = "per",
        submitted = "per",
        results = headwords.map { suggestion(it) },
        status = SearchState.Status.Ready,
        active = meta(),
        // ⚠️ El idioma es ahora un dato PROPIO del estado y no se deriva del pack activo: un
        // pack bidireccional habla dos, así que `active` dejó de contestarlo.
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
    ) = compose.setContent {
        SearchScreen(state, onQueryChange = {}, onLanguageChange = onLanguageChange,
            onSearchDefinitions = onSearchDefinitions, onOpenVisita = onOpenVisita,
            onOpenEntry = onOpenEntry, onOpenAttribution = onOpenAttribution,
            onOpenSettings = onOpenSettings, onOpenFavoritos = onOpenFavoritos,
            onOpenWordOfTheDay = onOpenWordOfTheDay, onOpenHistory = onOpenHistory)
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
        // Entero y no "sust.": la ficha no pelea por el ancho con nada, y una abreviatura que
        // hay que descifrar sólo se justifica donde el lema necesita el espacio.
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
        // La categoría va escrita entera y arriba, no como prefijo: es la misma regla de D-159
        // --en una fila se abrevia porque el lema necesita el ancho, en la ficha no compite con
        // nada-- y lo que se fija sigue siendo que las dos listas se distingan.
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
        // ⚠️ **El orden es la decisión, no un detalle de maquetado (D-192).** Iban al final
        // razonando que "las definiciones son a lo que el lector entró"; lo desmiente la
        // medición que ya estaba escrita al lado: el **48,6 %** de las entradas con traducción
        // tienen SÓLO éstas, así que para la mitad de los casos la sección del final era la
        // respuesta entera y quedaba debajo de un `Ver más` que hay que tocar.
        //
        // Se fija con coordenadas y no leyendo el árbol porque es exactamente lo que se revierte
        // sin que nada avise: mover un `item` de lugar no rompe ningún test que sólo compruebe
        // que ambas secciones existen.
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
        // ⚠️ **Es lo que obligó a que un enlace lleve `packId` y no sólo `entryId`.** Hasta acá
        // el mapa era `norm -> entryId` y `onOpenWord` navegaba dentro del MISMO pack, a
        // propósito (D-080): mandar el id a otro pack abre otra palabra sin dar error. Una
        // traducción va necesariamente a otro pack, así que el destino tiene que decir a cuál.
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
        // ⚠️ Encontrado MIRANDO la pantalla: salia "marine·freshwater·limnic", pegado, porque
        // **Android recorta los espacios de un `<string>`** salvo que el valor este entre
        // comillas dobles. Con tres o cuatro terminos la linea se vuelve un bloque ilegible y
        // ademas parte mal al ajustar el texto.
        //
        // Este test existe porque el bug es invisible en el recurso --el XML se ve bien-- y
        // ningun test anterior lo veia: todos afirmaban un solo termino.
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
        // Tercera lista con la misma forma (D-132), y el mismo riesgo elevado: "galo" mostrado
        // como sinonimo de "frances" afirma una equivalencia que la fuente no da. Las tres se ven
        // iguales, asi que lo unico que las separa son los prefijos -- y eso es lo que se fija.
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
        // La línea entera y no `substring`: "camélido" también está EN LA GLOSA, así que buscarla
        // como subcadena encuentra dos nodos. Que la palabra aparezca en los dos lugares es
        // correcto --uno es la definición y el otro la lista-- y el test tiene que mirar el que
        // le importa.
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
            isBundled = demo,
            fileName = "$id.db",
            bytes = bytes,
        )

    private fun oferta(
        id: String,
        estado: CatalogStatus,
        bytes: Long = 37_000_000,
        instalada: Long? = null,
    ) = CatalogOffer(
        pack = CatalogPack(
            packId = id, name = id, description = null, langs = listOf("es"), entryCount = 1,
            dataVersion = 300L, schemaVersion = 4, normVersion = 2, license = null,
            url = "packs/$id.db.gz", bytes = bytes, sha256 = "a", dbBytes = bytes * 2,
            dbSha256 = "b",
        ),
        status = estado,
        installedVersion = instalada,
    )

    @Test
    fun entrarALaPantallaNoConsultaElCatalogo() {
        // ⚠️ El aserto que fija la decision: la red se toca cuando el usuario aprieta, y nunca
        // al montar la pantalla. La guia oficial pone el acceso a red por encima de encender la
        // pantalla (D-029), asi que un sondeo al entrar seria el gasto mas caro de la app.
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
    // ⚠️ Pantalla alta a proposito: `TransformingLazyColumn` solo compone lo VISIBLE, y en 234 dp
    // la tercera cabecera queda fuera y el test mide dos. Es el mismo recurso que ya usa el test
    // de la lista larga mas abajo, no un apano nuevo.
    @Config(qualifiers = "+w234dp-h1600dp")
    fun lasCategoriasSalenEnOrdenFIJO_actualizar_descargar_incompatible() {
        // A proposito en el orden INVERSO al esperado: el orden de la pantalla no puede depender
        // del orden del JSON que manda el servidor.
        compose.setContent {
            PacksScreen(
                packs = emptyList(),
                onDelete = {},
                catalog = CatalogState.Ready(
                    listOf(
                        oferta("malo", CatalogStatus.INCOMPATIBLE),
                        oferta("nuevo", CatalogStatus.DOWNLOAD),
                        oferta("viejo", CatalogStatus.UPDATE, instalada = 200L),
                    ),
                ),
            )
        }
        val enPantalla = compose.onAllNodes(hasText("Hay actualización")).fetchSemanticsNodes().size +
            compose.onAllNodes(hasText("Se puede descargar")).fetchSemanticsNodes().size +
            compose.onAllNodes(hasText("Esta versión no lo abre")).fetchSemanticsNodes().size
        assertEquals("las tres cabeceras tienen que estar", 3, enPantalla)
        // El orden vertical: actualizar primero. Es lo que el usuario vino a buscar.
        val y = { texto: String ->
            compose.onNodeWithText(texto).fetchSemanticsNode().positionInRoot.y
        }
        assertTrue(
            "actualizar tiene que ir antes que descargar",
            y("Hay actualización") < y("Se puede descargar"),
        )
        assertTrue(
            "incompatible va al final",
            y("Se puede descargar") < y("Esta versión no lo abre"),
        )
    }

    @Test
    fun unaActualizacionDiceDeQueVersionAQueVersion() {
        // "Hay actualizacion" sin decir de que a que no deja decidir nada.
        compose.setContent {
            PacksScreen(
                packs = emptyList(),
                onDelete = {},
                catalog = CatalogState.Ready(
                    listOf(oferta("es-def", CatalogStatus.UPDATE, instalada = 200L)),
                ),
            )
        }
        compose.onNodeWithText("v300", substring = true).assertIsDisplayed()
        compose.onNodeWithText("200", substring = true).assertIsDisplayed()
    }

    @Test
    fun unCatalogoSinNadaQueOfrecerLoDICE() {
        compose.setContent {
            PacksScreen(
                packs = emptyList(),
                onDelete = {},
                catalog = CatalogState.Ready(
                    // Un pack instalado y al dia NO es una oferta: no se muestra aca.
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
        // ⚠️ **Sin la clave de idioma**: el NOMBRE del pack ya lo dice --«Español»,
        // «Español ↔ English»-- así que la sigla repetía en abreviado la línea de arriba, y era
        // la tercera cosa que competía por un ancho que ya se cortaba.
        assertEquals(
            "la sigla de idioma ya no aparece en la fila",
            0,
            compose.onAllNodesWithText("· ES", substring = true).fetchSemanticsNodes().size,
        )
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("309,5 MB", substring = true))
        // ⚠️ **Y NINGUNA fila marca cuál está en uso.** Esto invierte lo que este mismo test
        // exigía hasta hoy --«sólo el activo lleva check»--. Pedido: *«quitando completamente el
        // ticket de idioma seleccionado y dejando que esto se haga solo desde la pantalla de
        // inicio»*. Elegir idioma vive en el selector del inicio (D-111); tenerlo también acá
        // era una segunda puerta a lo mismo, y su hueco reservado de 20 dp era justo el ancho
        // que le faltaba al tamaño para no cortarse.
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
    fun theDownloadSectionSaysNotYetAndHowToInstallToday() {
        // A bare "coming soon" leaves the user with no idea how to install a dictionary.
        compose.setContent {
            PacksScreen(
                packs = listOf(openPack("es-def", "Español", 72_212_480)),
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
        // ⚠️ **Este test afirmaba lo contrario y el cambio es deliberado** (D-156). El selector
        // vivía al fondo, bajo "Opciones", porque ahí no competía con la barra por el lugar de
        // arriba. El costo apareció al usarlo: desaparecía al buscar, que es justo cuando hace
        // falta —mirando resultados que no son los esperados porque el idioma activo no era el
        // que uno creía—. Ahora va debajo de la barra, y cuesta una fila de las ~3 que entran.
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
        // ⚠️ El caso que obligo a D-138: el pack español mezcla definiciones CC BY-SA 4.0 con
        // frases de corpus CC BY 2.0 FR. Mostrar una sola licencia **incumple la otra**, y
        // ningun otro test lo veria porque el pack abre y funciona igual.
        val fuentes = listOf(
            PackSource(PackSource.Role.DEFINITIONS, "Wikcionario",
                       "https://es.wiktionary.org/", "CC BY-SA 4.0", ""),
            PackSource(PackSource.Role.SENTENCES, "Tatoeba",
                       "https://tatoeba.org/", "CC BY 2.0 FR", ""),
        )
        compose.setContent {
            AttributionScreen(packs = listOf(handle(meta().copy(sources = fuentes))))
        }
        // La linea itemizada, no solo el nombre: "Wikcionario" tambien aparece en la prosa de
        // `attribution`, y encontrarlo ahi no probaria que la fuente se declaro con SU licencia.
        compose.onNodeWithText("definiciones · Wikcionario · CC BY-SA 4.0").assertExists()
        compose.onNodeWithText("frases · Tatoeba · CC BY 2.0 FR").assertExists()
    }

    @Test
    fun unPackSinFuentesDeclaradasSigueMostrandoSuCredito() {
        // Un pack anterior a D-138 no trae `meta.sources`. No puede quedarse SIN atribucion:
        // eso convertiria una mejora de formato en un incumplimiento de licencia.
        compose.setContent {
            AttributionScreen(packs = listOf(handle(meta().copy(sources = emptyList()))))
        }
        compose.onNodeWithText("Wikcionario", substring = true).assertExists()
        compose.onNodeWithText("CC-BY-SA-4.0", substring = true).assertExists()
    }

    // --- El inicio y los resultados comparten la cabecera (D-157) ---------------------------

    @Test
    fun elINICIO_TIENE_LA_MISMA_CABECERA_QUE_LOS_RESULTADOS() {
        // Pedido: barra grande a la izquierda, voz chica a la derecha, selector abajo — igual en
        // los dos estados. Una cabecera que cambia de forma al escribir obliga a reaprenderla.
        showSearch(twoPackState().copy(query = "", submitted = ""))
        compose.onNodeWithContentDescription("Decir una palabra").assertExists()
        // Y ya no el botón grande, que era exclusivo del inicio: ahora el micrófono es lo
        // único que ofrece voz, en los dos estados.
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

    // --- El selector de idioma, visible también con resultados (D-156) ---------------------

    @Test
    fun elSELECTOR_SIGUE_VISIBLE_CON_RESULTADOS_EN_PANTALLA() {
        // ⚠️ Antes vivía dentro del bloque "sin búsqueda", así que desaparecía justo cuando más
        // hace falta: viendo resultados que no son los que esperabas porque el idioma activo no
        // era el que creías.
        showSearch(twoPackState("perder"))
        compose.onNodeWithText("ES").assertExists()
        compose.onNodeWithText("EN").assertExists()
    }

    @Test
    fun elSelectorVaJustoDebajoDeLaBarra() {
        // La posición importa: arriba es donde se mira, y al lado de la barra queda claro que
        // modifica lo que se está buscando. Al fondo de la lista sería un ajuste escondido.
        showSearch(twoPackState("perder"))
        val barra = compose.onNode(hasSetTextAction()).getBoundsInRoot()
        val chip = compose.onNodeWithText("ES").getBoundsInRoot()
        assertTrue(chip.top.value > barra.top.value)
        assertTrue(chip.top.value < barra.bottom.value + 120f)
    }

    // --- Quitar una guardada: mantener apretado y confirmar (D-155) -------------------------

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
        // La fila sigue haciendo lo que hacía: abrir la palabra. El borrado no puede estar a un
        // toque de distancia del gesto que más se usa.
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
        // ⚠️ Con algo armado, un toque en otra fila DESARMA y no navega. Si abriera la palabra,
        // el usuario se iría de la pantalla con una fila roja esperándolo al volver, y la única
        // forma de cancelar sería adivinarla.
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
        // Sin `onDelete` el gesto no hace nada: el historial se llena solo y ya tiene tope.
        compose.setContent {
            WordListScreen(
                words = listOf(visita("perro")),
                title = cl.fadiaz.dictionary.R.string.home_recent,
                empty = cl.fadiaz.dictionary.R.string.history_empty,
                // El mapa y no una etiqueta suelta: esta lista puede traer palabras de un pack
                // que ya no esta instalado. Ver `historyTags`.
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
        // ⚠️ Antes la fila de resultado decía `sust. · ES` y la de reciente sólo `sust.`. Dos
        // filas que representan lo mismo tienen que decir lo mismo: si no, el usuario aprende
        // que la etiqueta significa algo distinto según dónde esté.
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
                // El mapa y no una etiqueta suelta: esta lista puede traer palabras de un
                // pack que ya no esta instalado. Ver `historyTags`.
                tags = mapOf("es-def" to "ES"),
                onOpen = {},
            )
        }
        compose.onNodeWithText("sust. · ES", substring = true).assertExists()
    }

    @Test
    fun unaPalabraDeUnPackDESCONOCIDO_muestra_solo_el_tipo() {
        // Igual que en los resultados: heredar la etiqueta del pack activo afirmaría un idioma
        // que nadie comprobó. Mejor decir menos que decir algo falso.
        showSearch(
            readyState().copy(
                query = "", submitted = "",
                history = listOf(Visit("fantasma", 1, "perro", "noun")),
            ),
        )
        compose.onNodeWithText("sust.", substring = true).assertExists()
        assertEquals(0, compose.onAllNodesWithText("· ES", substring = true).fetchSemanticsNodes().size)
    }

    // --- Recientes: tres en el inicio, el resto detrás de un botón (D-148) ------------------

    @Test
    fun elInicioMuestraTRES_RECIENTES_Y_UN_BOTON() {
        // En un reloj el inicio es la pantalla más disputada: ocho recientes empujaban los
        // ajustes y la atribución fuera de alcance. Tres es lo que se ve sin scrollear después
        // del campo y la voz.
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
        // Un botón que lleva a la misma lista que ya estás viendo es cromo, y el cromo en un
        // reloj se paga en filas.
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
        // Si no navega, el botón es una salida muerta: se ve, se toca y no pasa nada.
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
        // La cuarta es la prueba: el inicio muestra tres, asi que verla aca demuestra que esta
        // pantalla no esta recortando. La octava puede quedar fuera del viewport, y afirmarla
        // haria que el test dependiera del alto de la pantalla de Robolectric y no de la logica.
        compose.onNodeWithText("palabra1").assertExists()
        compose.onNodeWithText("palabra4").assertExists()
    }

    // --- El idioma de cada resultado (D-143) -----------------------------------------------

    @Test
    fun cadaResultadoDiceDeQueIDIOMAViene() {
        // Pedido: junto a la palabra y su tipo, el idioma abreviado. Con dos diccionarios del
        // mismo idioma o de idiomas distintos conviviendo (D-136), una fila sin origen obliga a
        // abrir la entrada para saber de donde salio.
        // ⚠️ **Con dos packs instalados las filas ya NO mezclan idiomas**, y eso cambia lo que
        // este test puede afirmar. La busqueda es estricta por idioma entre packs (D-189) y
        // ahora tambien DENTRO de un pack bidireccional (`WHERE lang = ?`), asi que una lista
        // no contiene filas de dos idiomas: la etiqueta es una sola y vale para todas.
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
        // ⚠️ **Esto invierte D-151, que es lo que este test fijaba.** La regla anterior mostraba
        // la sigla de la fuente --`WIKC`, `WD`-- cuando dos diccionarios compartían idioma,
        // porque "ES · ES" no desambigua. El pedido la revierte: *«solo debe ser EN, ES. No me
        // gusta que haya un ENWIK... porque solo me interesa conocer el idioma de
        // proveniencia»*.
        //
        // Lo que se pierde, dicho para que nadie lo redescubra: con dos packs del mismo idioma
        // la fila **no dice de cuál salió**. Esa pregunta la contesta la pantalla de gestión de
        // diccionarios; la fila contesta en qué idioma está la palabra que voy a abrir, y la
        // sigla no se entiende sin conocer el `pack_id`.
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
        // LAS DOS filas dicen `ES`, que es exactamente el punto: la etiqueta ya no distingue
        // packs, sólo idiomas.
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
        // ⚠️ El bug que D-145 tapó fundiendo packs: el mapa se calcula por pack, así que dos
        // diccionarios de español daban dos palabras del día del mismo idioma.
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
        // ⚠️ **Esto reemplaza a `unResultadoDeUnPackDESCONOCIDONoInventaIdioma`**, y el cambio
        // no es relajar la regla sino que la regla dejo de necesitar un mecanismo.
        //
        // Aquel test protegia contra heredar la etiqueta del pack activo, porque un `packId`
        // fuera del mapa no se podia resolver a un idioma (familia D-080). Hoy **un resultado de
        // otro idioma no puede existir**: `SearchRepository` solo consulta packs que hablan el
        // idioma activo (D-189) y dentro de un pack bidireccional cada peldaño filtra por
        // `entry.lang`. La etiqueta es cierta **por construccion de la consulta**, no por un
        // mapa -- que es mas fuerte, porque un mapa se puede desincronizar.
        //
        // Lo que SI conserva el mecanismo viejo es el historial: ahi una fila puede ser de un
        // pack desinstalado. Ver `unaPalabraDeUnPackDESCONOCIDO_muestra_solo_el_tipo`.
        showSearch(readyState().copy(results = listOf(suggestion("perro"))))
        compose.onNodeWithText("sust. · ES", substring = true).assertExists()
    }

    // --- The language selector ----------------------------------------------------------------

    @Test
    fun laPALABRA_DEL_DIA_sale_de_un_pack_de_DEFINICIONES_aunque_el_bilingue_sea_MAYOR() {
        // ⚠️ **Dos reglas que por separado están bien y juntas borraron la palabra del día.**
        // Un pack de traducción no genera una --pedido: *«esto queda solo para los diccionarios
        // de definiciones»*, y la razón se ve al abrirla: una entrada inversa no tiene
        // acepciones, así que diría «se dice `perro`» y nada más--. Pero el representante de
        // cada idioma es el pack **más grande**, y el bilingüe pasó a serlo de los dos: 209.484
        // contra 152.281 del español y 16.652 del núcleo inglés.
        //
        // Resultado en el emulador: la sección desapareció entera. La pantalla pedía la palabra
        // de un pack que, correctamente, no genera ninguna.
        val bi = meta("es-tr-enwikt", "es", "Español ↔ English", entries = 209_484,
                      langs = listOf("es", "en"))
        val defs = meta("es-def-wikc", "es", "Español", entries = 152_281)
        showSearch(
            readyState().copy(
                query = "", submitted = "",
                // ⚠️ **El ACTIVO es el bilingüe**, que es el caso real: `chooseActive` toma
                // el pack más grande, y el bilingüe lo es. Con el de definiciones activo el
                // fallo no aparece --`representativePacks` respeta al activo-- y por eso la
                // primera versión de este test era vacua: mutando el arreglo seguía pasando.
                active = bi, activeLang = "es",
                available = listOf(handle(bi), handle(defs)),
                wordsOfTheDay = mapOf("es-def-wikc" to resumen("futuro")),
            ),
        )
        compose.onNodeWithText("futuro").assertExists()
    }

    @Test
    fun conUN_SOLO_PACK_BIDIRECCIONAL_el_selector_IGUAL_aparece() {
        // ⚠️ **Encontrado en el emulador, y es el caso que el pack bidireccional existe para
        // servir.** El selector se dibujaba con `state.available.size > 1` --contaba ARCHIVOS--
        // así que con sólo `es-tr-enwikt` instalado no aparecía ninguno, y **no había forma de
        // llegar a su mitad inglesa**: el pack hablaba dos idiomas y la app ofrecía cero.
        //
        // Lo que se cuenta ahora son IDIOMAS, que es lo que el chip elige desde D-197.
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
        // ⚠️ **Reporta el IDIOMA y ya no un `packId`.** Un pack bidireccional habla dos, así que
        // elegirlo no decía en cuál buscar: el chip pasó a ser lo que D-147 ya decía que era.
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

    @Test
    fun theRowKeepsTheAbbreviation() {
        // La otra mitad del pedido --«resumidos donde no hay espacio, expandidos dentro de la
        // ficha»-- y la que se rompe sola si alguien "unifica" las dos: en una fila de resultados
        // "sustantivo" le come el ancho al lema, que es lo único que ahí importa.
        showSearch(readyState("perro"))
        compose.onNodeWithText("sust.", substring = true).assertIsDisplayed()
    }

    @Test
    fun theThreeTermListsEachCarryTheirOwnHeading() {
        // Pedido: «primero mostrando la categoría y abajo las palabras». Las tres listas se ven
        // idénticas --mismo estilo, misma posición, mismo separador-- así que la categoría es lo
        // único que dice cuál estás leyendo. Antes era un prefijo de cuatro letras.
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
        // Una acepción sin antónimos no puede mostrar «Antónimos» y nada debajo: en un reloj eso
        // se lee como que la lista está vacía por un error.
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
        // ⚠️ La misma regla que la glosa: el color es la promesa de que lleva a algún lado. Un
        // sinónimo que el pack no tiene **se sigue mostrando** --la fuente lo dice y esconderlo
        // sería perder información-- pero sin pintar.
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
        // Ya no se podía borrar --volvería sola al reiniciar-- pero la fila no lo decía: un
        // botón que falta sin explicación se lee como un bug, no como una decisión.
        showPacks(listOf(handle(meta(packId = "es-core-wikc", name = "Español")).copy(
            isBundled = true, bytes = 7_500_000,
        )))
        compose.onNodeWithText("Incluido en la app", substring = true).assertIsDisplayed()
    }

    @Test
    fun anInstalledPackDoesNotSayItIsBundled() {
        // El control: la etiqueta tiene que distinguir, no adornar.
        showPacks(listOf(handle(meta()).copy(fileName = "es-def-wikc.db", bytes = 71_000_000)))
        assertEquals(
            0,
            compose.onAllNodesWithText("Incluido en la app", substring = true)
                .fetchSemanticsNodes().size,
        )
    }

    @Test
    fun onlyTheInstalledPackOffersDeleting() {
        // Lo que ya valía y no estaba fijado por ningún test: el pack del APK no ofrece borrar.
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

    /** La pantalla de diccionarios, que es función de la lista de packs y de cuál está activo. */
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
    ) {
        compose.setContent {
            SettingsScreen(
                packs = packs,
                scale = cl.fadiaz.dictionary.data.TextScale.NORMAL,
                appVersion = "9.9.9",
                uiLanguage = uiLanguage,
                onUiLanguageChange = onUiLanguageChange,
                onManagePacks = {},
                onScaleChange = {},
                onClearHistory = onClearHistory,
                hasHistory = hasHistory,
            )
        }
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
    fun elDIAGNOSTICO_es_SOLO_LA_VERSION_y_va_al_fondo() {
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
        // ⚠️ **La cuenta de entradas por diccionario se quitó a pedido.** Era diagnóstico que no
        // sirve para decidir nada --cuántos lemas trae un pack no dice si funciona-- y costaba
        // una fila por diccionario en la pantalla más larga de la app. El dato que sí decide,
        // el tamaño en disco, vive en gestión de diccionarios, que es donde se borra.
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
