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

/**
 * Las tres pantallas, contra Android de verdad.
 *
 * Son INSTRUMENTADOS y el gate NO los corre: necesitan dispositivo, como los 25 de
 * `:dict-data`. Lo que cubren es lo que ningun test de la JVM puede ver -- que lo que el estado
 * dice llegue a los pixeles-- y sobre todo **que la atribucion se muestre**, que es la condicion
 * de uso de los datos (D-031) y hasta hoy no tenia mas enforcer que la buena voluntad.
 *
 * No usan un DictionarySource: las pantallas son funciones del estado, asi que el estado se
 * construye a mano. Por eso tampoco hace falta duplicar aca el fake de `src/test`.
 */
@RunWith(AndroidJUnit4::class)
class ScreensTest {

    @get:Rule
    val compose = createComposeRule()

    private fun suggestion(lema: String, pos: String? = "noun") = Suggestion(
        packId = "test",
        entryId = lema.hashCode().toLong(),
        headword = lema,
        partOfSpeech = pos,
        matchKind = MatchKind.PREFIX,
        score = 0,
    )

    /** Un pack de mentira: sólo importa su metadata, porque las pantallas son del estado. */
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
        // null: ejercita el camino de un pack anterior a D-125, que no trae la clave.
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

    private fun readyState(vararg lemas: String) = SearchState(
        query = "per",
        results = lemas.map { suggestion(it) },
        status = SearchState.Status.Ready,
        active = meta(),
        available = listOf(handle(meta())),
    )

    /** Dos packs: es el estado que ejercita el selector. */
    private fun twoPackState(vararg lemas: String): SearchState {
        val es = meta()
        val en = meta("en-def", "en", "English")
        return readyState(*lemas).copy(
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
        onOpenAjustes: () -> Unit = {},
        onOpenFavoritos: () -> Unit = {},
        onOpenPalabraDelDia: (String, EntrySummary) -> Unit = { _, _ -> },
    ) = compose.setContent {
        SearchScreen(state, onQueryChange = {}, onPackChange = onPackChange,
            onSearchDefinitions = onSearchDefinitions, onOpenVisita = onOpenVisita,
            onOpenEntry = onOpenEntry, onOpenAttribution = onOpenAttribution,
            onOpenAjustes = onOpenAjustes, onOpenFavoritos = onOpenFavoritos,
            onOpenPalabraDelDia = onOpenPalabraDelDia)
    }

    // --- La lista de resultados --------------------------------------------------------------

    @Test
    fun theListShowsTheHeadwordAndItsPartOfSpeech() {
        showSearch(readyState("perder"))
        compose.onNodeWithText("perder").assertIsDisplayed()
        compose.onNodeWithText("sust.", substring = true).assertExists()
    }

    @Test
    fun threeResultsFitWithoutScrolling() {
        // Es la decision de densidad, y este test es la unica forma de fijarla.
        //
        // **La meta eran cinco y no entran, ni cuatro tampoco.** Medido en pantalla: son
        // 192x192 dp (384 px a 320 dpi), la guia de Wear OS pide 48 dp minimos de area tocable,
        // y el ScreenScaffold reserva margen arriba y abajo por la pantalla redonda. Con paso de
        // 52 dp por fila entran tres. El diseño anterior, de dos lineas, usaba ~74 dp: la
        // ganancia real es de ~40 % mas filas por pantalla, no del doble.
        //
        // Si alguien baja de 48 dp para meter una cuarta, este test sigue pasando y el area
        // tocable se rompe en silencio. Por eso el minimo esta en una constante con nombre.
        showSearch(readyState("perder", "perro", "permitir", "persona"))
        compose.onNodeWithText("permitir").assertIsDisplayed()
    }

    @Test
    fun aLongHeadwordDoesNotEatTheScreen() {
        // Los refranes son entradas del Wikcionario y llegan a 96 caracteres. Si una fila
        // creciera para mostrarlo entero, un solo resultado ocuparia la pantalla.
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

    // --- Los estados que no son "hay resultados" ---------------------------------------------

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
        showSearch(readyState().copy(query = "xyzzy"))
        compose.onNodeWithText("Sin resultados", substring = true).assertIsDisplayed()
    }

    // --- La entrada -------------------------------------------------------------------------

    private fun entry(vararg glosas: String) = Entry(
        packId = "test",
        entryId = 1,
        uid = 1,
        headword = "perro",
        partOfSpeech = "noun",
        senses = glosas.map { Sense(it) },
    )

    /** Un enlace dentro de una glosa: lo unico que lo identifica es que es clickeable y de quien
     *  cuelga. Compose no le da texto propio al rectangulo del link. */
    private fun linkAt(textoDeLaGlosa: String) =
        hasClickAction() and hasAnyAncestor(hasText(textoDeLaGlosa, substring = true))

    @Test
    fun theEntryShowsHeadwordPartOfSpeechAndNumberedSenses() {
        compose.setContent { EntryScreen(1, onOpenPalabra = {}) { entry("Mamífero cánido doméstico.") } }
        compose.onNodeWithText("perro").assertIsDisplayed()
        compose.onNodeWithText("sust.", substring = true).assertExists()
        compose.onNodeWithText("1.", substring = true).assertExists()
    }

    @Test
    fun theSenseShowsItsSynonyms() {
        // 26.845 entradas del pack español traen sinonimos y el builder los tiraba. Importan
        // sobre todo donde la glosa es de una palabra ("Tonto."), que es el 25,6 % del pack.
        compose.setContent {
            EntryScreen(1, onOpenPalabra = {}) {
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
        // El riesgo no es que no se vean: es que se vean IGUAL. Las dos listas comparten estilo,
        // posicion y separador, asi que lo unico que distingue "otra forma de decirlo" de "lo
        // contrario" es el prefijo. Este test fija los dos prefijos, no la presencia.
        compose.setContent {
            EntryScreen(1, onOpenPalabra = {}) {
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
        // "justicia" tiene 10 acepciones y el maximo medido es 47. Sin tope, la pantalla se
        // vuelve un rollo y la acepcion util queda debajo de nueve que no se buscaban.
        compose.setContent {
            EntryScreen(1, onOpenPalabra = {}) { entry("uno", "dos", "tres", "cuatro", "cinco") }
        }
        compose.onNodeWithText("tres", substring = true).assertExists()
        assertEquals(0, compose.onAllNodesWithText("cuatro", substring = true).fetchSemanticsNodes().size)
        // El atajo a la busqueda ocupa la primera fila, asi que "Ver mas" bajo un renglon
        // y con tres acepciones ya no entra en el primer pantallazo. Es el costo medido
        // de esa fila: el boton sigue ahi y a un scroll, pero deja de estar a la vista.
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Ver más", substring = true))
        compose.onNodeWithText("Ver más", substring = true).assertExists()
    }

    @Test
    fun showMoreExpandsTheRest() {
        compose.setContent {
            EntryScreen(1, onOpenPalabra = {}) { entry("uno", "dos", "tres", "cuatro", "cinco") }
        }
        // El atajo a la busqueda ocupa la primera fila, asi que "Ver mas" bajo un renglon
        // y con tres acepciones ya no entra en el primer pantallazo. Es el costo medido
        // de esa fila: el boton sigue ahi y a un scroll, pero deja de estar a la vista.
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Ver más", substring = true))
        compose.onNodeWithText("Ver más", substring = true).performClick()
        compose.onNodeWithText("cuatro", substring = true).assertExists()
        compose.onNodeWithText("cinco", substring = true).assertExists()
    }

    @Test
    fun withThreeSensesOrFewerThereIsNoShowMore() {
        // La mediana es 3: en la mitad de las entradas el boton no tiene que aparecer siquiera.
        compose.setContent { EntryScreen(1, onOpenPalabra = {}) { entry("uno", "dos", "tres") } }
        compose.onNodeWithText("tres", substring = true).assertExists()
        assertEquals(0, compose.onAllNodesWithText("Ver más", substring = true).fetchSemanticsNodes().size)
    }

    // --- El campo de texto: por donde entra la busqueda con teclado ---------------------------

    /**
     * Un harness CON estado, y esa es exactamente la razon por la que el bug sobrevivio a 22 tests.
     *
     * Todos los demas pasan `onQueryChange = {}`: el estado nunca cambia, la lista nunca se
     * reordena y el campo nunca se destruye. Aca escribir cambia el estado de verdad, que es lo
     * que hace desaparecer el encabezado, el boton de voz y el historial -- y con ellos, la
     * posicion del campo dentro de la lista.
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
            onOpenPalabraDelDia = { _, _ -> },
        )
    }

    @Test
    fun typingTheFirstLetterDoesNotCloseTheField() {
        // El bug que aparecio en el reloj: a la primera letra desaparecen encabezado, boton de
        // voz e historial, el campo salta del indice 2 al 0 y --sin `key`-- el lazy layout lo da
        // por otro nodo, lo destruye y lo recompone. El foco se va con el, y el teclado detras.
        showTypableSearch(readyState("perder").copy(query = "", history = recientes))

        compose.onNode(hasSetTextAction()).performClick()
        compose.onNode(hasSetTextAction()).assertIsFocused()

        compose.onNode(hasSetTextAction()).performTextInput("p")
        compose.waitForIdle()

        compose.onNode(hasSetTextAction()).assertIsFocused()
    }

    @Test
    fun acceptingOnTheKeyboardReleasesTheField() {
        // "Aceptar" no hacia nada: hay `ImeAction.Search` declarado y cero `keyboardActions`, y
        // `KeyboardActions.Default` no define comportamiento para Search. La unica salida era el
        // gesto de volver del sistema. Soltar el foco es lo que cierra el teclado y deja la
        // corona operativa sobre los resultados.
        showTypableSearch(readyState("perder").copy(query = ""))

        compose.onNode(hasSetTextAction()).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("per")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitForIdle()

        compose.onNode(hasSetTextAction()).assertIsNotFocused()
    }

    @Test
    fun theMaximumSensesWithTheLongestExampleExpandWithoutFalling() {
        // Los numeros son los medidos sobre el pack real: 47 acepciones es el maximo y 917
        // caracteres el ejemplo mas largo. `verMasDespliegaElResto` usa CINCO acepciones sin
        // ejemplos, y por eso nunca reprodujo el crash que aparecio al tocar "Ver mas".
        val longExample =
            "cronica del siglo XVI que el Wikcionario cita como uso. ".repeat(17).take(917)
        val muchas = (1..47).map { number ->
            Sense(
                gloss = "acepcion numero $number",
                examples = if (number == 1) listOf(longExample) else emptyList(),
            )
        }
        compose.setContent { EntryScreen(1, onOpenPalabra = {}) { entry().copy(senses = muchas) } }

        // Hay que scrollear para llegar al boton: el ejemplo de 917 caracteres lo empuja
        // fuera de pantalla. Ese es, literalmente, el muro que D-074 documenta.
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Ver más", substring = true))
        compose.onNodeWithText("Ver más", substring = true).performClick()
        compose.waitForIdle()

        // Por indice y no por texto: el ultimo item de un TransformingLazyColumn no queda
        // compuesto scrolleando al maximo, asi que buscarlo por texto da un falso rojo.
        compose.onNode(hasScrollAction()).performScrollToIndex(47)
        compose.onNodeWithText("acepcion numero 47", substring = true).assertExists()
    }


    // --- Las dos acciones de arriba y el menu ---------------------------------------------------

    @Test
    fun theTwoTopButtonsShareASingleRow() {
        // La razon es aritmetica, no estetica: lado a lado cuestan 48 dp --el minimo tocable--
        // y apilados costarian 96, que en esta pantalla es una acepcion menos a la vista.
        compose.setContent {
            EntryScreen(
                entryId = 1,
                onOpenPalabra = {},
                actions = { listOf(EntryAction("Guardar") {}) },
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
        // Un boton que abre un menu vacio es peor que no tener boton.
        compose.setContent {
            EntryScreen(entryId = 1, onOpenPalabra = {}) { entry("una glosa") }
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
                onOpenPalabra = {},
                actions = {
                    listOf(
                        EntryAction("Guardar") { ejecutada = "Guardar" },
                        EntryAction("Copiar") { ejecutada = "Copiar" },
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

    // --- Palabras tocables dentro de una glosa ------------------------------------------------

    // `tocarUnaPalabraConocidaDeLaGlosaAbreSuEntrada` vive en `androidTest`, en
    // `EnlacesEnDispositivoTest`: tocar una palabra dentro de un parrafo depende del layout de
    // texto real y bajo Robolectric el callback no se dispara. Es el unico de los 47.

    @Test
    fun aWordThatIsNotAHeadwordCannotBeTapped() {
        // El color es una promesa: si se pinta tocable algo que no lleva a ningun lado, el
        // usuario aprende a no confiar en el color y la funcion deja de servir.
        compose.setContent {
            EntryScreen(entryId = 1, onOpenPalabra = {}, resolveIn = { emptyMap() }) {
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
        // Resolver devuelve la entrada abierta: un enlace a donde ya estamos no lleva a nada.
        compose.setContent {
            EntryScreen(entryId = 1, onOpenPalabra = {}, resolveIn = { mapOf("cera" to 1L) }) {
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
        // Tocar palabras apila entradas: sin este atajo, volver desde tres de profundidad son
        // tres gestos. Se busca por contentDescription y no por texto porque es un icono, y esa
        // descripcion es ademas lo unico que lo nombra para un lector de pantalla.
        var volvio = false
        compose.setContent {
            EntryScreen(entryId = 1, onOpenPalabra = {}, onVolverABuscar = { volvio = true }) {
                entry("una glosa")
            }
        }
        compose.onNodeWithContentDescription("Buscar").performClick()
        assertEquals(true, volvio)
    }

    // --- El inicio: lo que se ve con la busqueda vacia ----------------------------------------

    private val todaysWord =
        EntrySummary(entryId = 42, headword = "permanecer", partOfSpeech = "verb", rank = 883)

    @Test
    fun theWordOfTheDayShowsUnderItsHeadingAndOpens() {
        // Ya NO se ve sin scrollear, y es el costo aceptado de poner la barra primero: arriba
        // quedan la busqueda y la voz, que es lo que mas se repite. Lo que si tiene que pasar es
        // que se llegue, que lleve su titulo de seccion y que abra en SU diccionario.
        var abierta: EntrySummary? = null
        var packOfTheWord: String? = null
        showSearch(
            readyState().copy(query = "", wordsOfTheDay = mapOf("es-def" to todaysWord)),
            onOpenPalabraDelDia = { pack, word -> packOfTheWord = pack; abierta = word },
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
        // Con un solo pack el subtitulo dice "palabra del día"; con dos, el nombre del
        // diccionario, que es lo unico que las distingue.
        showSearch(
            twoPackState().copy(
                query = "",
                wordsOfTheDay = mapOf(
                    "es-def" to todaysWord,
                    "en-def" to EntrySummary(7, "remain", "verb", 880),
                ),
            ),
        )
        compose.onNodeWithText("permanecer").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("remain"))
        compose.onNodeWithText("remain").assertIsDisplayed()
        // Nombre corto Y tipo (D-125): el nombre dejo de decir que clase de diccionario es,
        // asi que la fila tiene que decirlo aparte o se pierde el dato.
        compose.onNodeWithText("English · definiciones").assertExists()
        assertEquals(
            "con dos diccionarios el subtitulo es el idioma, no la etiqueta generica",
            0,
            compose.onAllNodesWithText("palabra del día").fetchSemanticsNodes().size,
        )
    }

    @Test
    fun withNoWordOfTheDayTheGapDoesNotShow() {
        // Un pack vacio, o el primer arranque antes de que termine de elegirse: la fila no
        // aparece en vez de aparecer vacia.
        showSearch(readyState().copy(query = "", wordsOfTheDay = emptyMap()))
        assertEquals(
            0,
            compose.onAllNodesWithText("palabra del día").fetchSemanticsNodes().size,
        )
    }

    @Test
    fun whenTypingTheWordOfTheDayAndSettingsDisappear() {
        // Misma regla que el historial: con resultados en pantalla, cada fila de chrome es un
        // resultado menos, y con 48 dp de area tocable eso se nota (D-073).
        showSearch(
            readyState("perder").copy(query = "per", wordsOfTheDay = mapOf("es-def" to todaysWord)),
        )
        assertEquals(0, compose.onAllNodesWithText("palabra del día").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("Ajustes").fetchSemanticsNodes().size)
    }

    @Test
    fun theHomeLeadsToSettings() {
        var abrio = false
        showSearch(readyState().copy(query = ""), onOpenAjustes = { abrio = true })
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Ajustes"))
        compose.onNodeWithText("Ajustes").performClick()
        assertEquals(true, abrio)
    }

    // --- Gestion de diccionarios ----------------------------------------------------------------

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
        // El tamaño es la unica cifra que importa cuando hay que hacer lugar, y el selector del
        // inicio no la dice.
        compose.setContent {
            PacksScreen(
                packs = listOf(
                    openPack("es-def", "Español", 72_212_480),
                    openPack("en-def", "English", 309_452_800, lang = "en"),
                ),
                active = "es-def",
                onActivar = {},
                onBorrar = {},
            )
        }
        // Tamaño e idioma juntos: el nombre del pack sale de adentro del .db y no siempre dice
        // de que idioma es.
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
        // Viene dentro del APK y se re-extrae al reabrir: el boton no haria nada y el pack
        // volveria solo. Ofrecerlo seria mentir.
        compose.setContent {
            PacksScreen(
                packs = listOf(openPack("demo", "Juguete", 53_248, demo = true)),
                active = "demo",
                onActivar = {},
                onBorrar = {},
            )
        }
        assertEquals(
            0,
            compose.onAllNodesWithContentDescription("Borrar Juguete").fetchSemanticsNodes().size,
        )
    }

    @Test
    fun deleteAsksForConfirmationAndDoesNotDeleteOnTheFirstTap() {
        // Es la unica accion de la app que no se puede deshacer desde la app: reponer un pack
        // son ~90 s por cable.
        var deleted: String? = null
        compose.setContent {
            PacksScreen(
                packs = listOf(openPack("en-def", "English", 309_452_800)),
                active = "en-def",
                onActivar = {},
                onBorrar = { deleted = it },
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
                onActivar = {},
                onBorrar = { deleted = it },
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
        // Un "proximamente" a secas deja al usuario sin saber como poner un diccionario.
        compose.setContent {
            PacksScreen(
                packs = listOf(openPack("es-def", "Español", 72_212_480)),
                active = "es-def",
                onActivar = {},
                onBorrar = {},
            )
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Para descargar"))
        compose.onNodeWithText("cable", substring = true).assertExists()
    }

    @Test
    fun theHomeOffersSavedWordsEvenWithNoneSaved() {
        // Antes la fila solo aparecia con favoritas: quien nunca guardo una no tenia como
        // descubrir que se puede. La pantalla ya trae un estado vacio que lo explica.
        var abrio = false
        showSearch(readyState().copy(query = "", favorites = emptyList()),
            onOpenFavoritos = { abrio = true })
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Guardadas"))
        compose.onNodeWithText("Guardadas").performClick()
        assertEquals(true, abrio)
    }

    @Test
    fun clearingTheHistoryAsksForConfirmationOnTheSameButton() {
        // Sin dialogo: el historial se rehace solo usando la app, asi que un segundo toque
        // alcanza. Lo que no puede pasar es que un toque suelto lo borre.
        var deleted = 0
        compose.setContent {
            SettingsScreen(
                packs = emptyList(),
                scale = cl.fadiaz.dictionary.data.TextScale.NORMAL,
                onGestionarPacks = {},
                onEscalaChange = {},
                onLimpiarHistorial = { deleted++ },
                hayHistorial = true,
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
        // Es la accion primaria: la guia de Wear pide elevarla, y antes quedaba debajo del
        // encabezado, la palabra del dia y el boton de voz.
        showSearch(
            readyState().copy(query = "", wordsOfTheDay = mapOf("es-def" to todaysWord)),
        )
        val barra = compose.onNode(hasSetTextAction()).getBoundsInRoot()
        val word = compose.onNodeWithText("permanecer").getBoundsInRoot()
        assertTrue("la palabra del dia quedo arriba de la barra", barra.top < word.top)
    }

    @Test
    fun everyHomeSectionHasItsHeading() {
        // Sin titulos, la palabra del dia se confundia con una entrada del historial y el
        // selector de idioma con un resultado.
        showSearch(
            twoPackState().copy(
                query = "",
                wordsOfTheDay = mapOf("es-def" to todaysWord),
                history = recientes,
            ),
        )
        for (title in listOf("Palabra del día", "Recientes", "Opciones")) {
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(title))
            compose.onNodeWithText(title).assertExists()
        }
    }

    @Test
    fun theLanguageSelectorLivesUnderOptions() {
        showSearch(twoPackState().copy(query = ""))
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Opciones"))
        val options = compose.onNodeWithText("Opciones").getBoundsInRoot()
        val selector = compose.onNodeWithText("ES").getBoundsInRoot()
        assertTrue("el selector quedo fuera de Opciones", selector.top >= options.top)
    }

    @Test
    fun withASingleDictionaryThereIsNoEmptyWordOfTheDaySection() {
        // Un titulo sin nada debajo es peor que no tener titulo.
        showSearch(readyState().copy(query = "", wordsOfTheDay = emptyMap()))
        assertEquals(
            0,
            compose.onAllNodesWithText("Palabra del día").fetchSemanticsNodes().size,
        )
    }

    // --- La atribucion, que es D-031 ---------------------------------------------------------

    @Test
    fun theAttributionShowsTheLicenseAndTheSource() {
        // No es decorativa: es la condicion de uso de los datos. Si alguien borra esta pantalla,
        // este test es lo unico que lo dice.
        compose.setContent {
            AttributionScreen(packs = listOf(handle(meta())))
        }
        compose.onNodeWithText("Wikcionario", substring = true).assertExists()
        compose.onNodeWithText("CC-BY-SA-4.0", substring = true).assertExists()
    }

    // --- El selector de idioma ----------------------------------------------------------------

    @Test
    fun withTwoPacksTheSelectorShowsBothLanguages() {
        showSearch(twoPackState().copy(query = ""))
        compose.onNodeWithText("ES").assertIsDisplayed()
        compose.onNodeWithText("EN").assertIsDisplayed()
    }

    @Test
    fun withASinglePackThereIsNoSelector() {
        // Un selector de una opcion es chrome puro, y en 192 dp el chrome cuesta resultados.
        showSearch(readyState().copy(query = ""))
        assertEquals(0, compose.onAllNodesWithText("ES").fetchSemanticsNodes().size)
    }

    @Test
    fun tappingTheOtherLanguageReportsIt() {
        var chosen: String? = null
        showSearch(twoPackState().copy(query = ""), onPackChange = { chosen = it })
        compose.onNodeWithText("EN").performClick()
        assertEquals("en-def", chosen)
    }

    @Test
    fun withTwoPacksThreeResultsStillFit() {
        // Re-verifica D-073 con el selector presente: el selector no puede costar una fila.
        showSearch(twoPackState("perder", "perro", "permitir", "persona"))
        compose.onNodeWithText("permitir").assertIsDisplayed()
    }

    @Test
    fun withNoResultsItOffersSearchingTheOtherLanguage() {
        // Es la escotilla de escape: escribiste algo que este idioma no tiene.
        showSearch(twoPackState().copy(query = "dog"))
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Buscar en", substring = true))
        compose.onNodeWithText("Buscar en English", substring = true).assertIsDisplayed()
    }

    @Test
    fun theAttributionShowsBothPacks() {
        // D-031 con dos fuentes: mostrar una sola licencia es incumplir la condicion de la otra.
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

    // --- El historial -----------------------------------------------------------------------

    private val recientes = listOf(
        Visit("es-def", 1, "perro", "noun"),
        Visit("en-def", 2, "house", "noun"),
    )

    @Test
    fun withAnEmptySearchTheRecentEntriesShow() {
        showSearch(readyState().copy(query = "", history = recientes))
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("perro"))
        compose.onNodeWithText("perro").assertIsDisplayed()
    }

    @Test
    fun whenTypingTheHistoryDisappears() {
        // No puede competir con los resultados: con 192 dp entran tres filas.
        showSearch(readyState("perder").copy(query = "per", history = recientes))
        assertEquals(0, compose.onAllNodesWithText("house").fetchSemanticsNodes().size)
    }

    @Test
    fun tappingARecentEntryOpensIt() {
        var abierta: Visit? = null
        showSearch(
            readyState().copy(query = "", history = recientes),
            onOpenVisita = { abierta = it },
        )
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("perro"))
        compose.onNodeWithText("perro").performClick()
        assertEquals("perro", abierta?.headword)
        assertEquals("tiene que abrir en SU pack, no en el activo", "es-def", abierta?.packId)
    }

    // --- Buscar en las definiciones -------------------------------------------------------------

    @Test
    fun withNoResultsItOffersSearchingTheDefinitions() {
        showSearch(readyState().copy(query = "animal que ladra"))
        compose.onNodeWithText("Buscar en las definiciones").assertIsDisplayed()
    }

    @Test
    fun withResultsItDoesNotOfferSearchingTheDefinitions() {
        // Protege D-073: con 192 dp una fila de chrome es un tercio de la lista.
        showSearch(readyState("perder", "perro"))
        assertEquals(
            0,
            compose.onAllNodesWithText("Buscar en las definiciones").fetchSemanticsNodes().size,
        )
    }

    @Test
    fun tappingSearchDefinitionsReportsIt() {
        var pedido = false
        showSearch(readyState().copy(query = "ladra"), onSearchDefinitions = { pedido = true })
        compose.onNodeWithText("Buscar en las definiciones").performClick()
        assertEquals(true, pedido)
    }

    @Test
    fun inDefinitionModeWithNoResultsTheSameOptionIsNotOfferedAgain() {
        // Ofrecerlo otra vez seria un bucle: ya se busco y no hay nada.
        showSearch(
            readyState().copy(query = "xyzzy", mode = SearchState.Mode.DEFINICIONES),
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
            readyState().copy(query = "ladra", mode = SearchState.Mode.BUSCANDO_DEFINICIONES),
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


/** Lo minimo para envolver una `PackMetadata` en un `PackHandle`. Las pantallas no consultan. */
private class FakeSource(override val metadata: PackMetadata) : DictionarySource {
    override suspend fun suggest(query: String, limit: Int) = emptyList<Suggestion>()
    override suspend fun entry(entryId: Long): Entry? = null
    override suspend fun searchDefinitions(query: String, limit: Int) = emptyList<Suggestion>()
    override suspend fun resolveHeadwords(norms: Set<String>) = emptyMap<String, Long>()
    override suspend fun summary(entryId: Long): EntrySummary? = null
    override fun close() = Unit
}
