package cl.fadiaz.dictionary.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
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
import cl.fadiaz.dictionary.core.FuzzyProfile
import cl.fadiaz.dictionary.core.PackKind
import cl.fadiaz.dictionary.core.PackMetadata
import cl.fadiaz.dictionary.data.PackHandle
import cl.fadiaz.dictionary.data.Visita
import cl.fadiaz.dictionary.core.MatchKind
import cl.fadiaz.dictionary.core.Sense
import cl.fadiaz.dictionary.core.Suggestion
import org.junit.Assert.assertEquals
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
class PantallasTest {

    @get:Rule
    val compose = createComposeRule()

    private fun sugerencia(lema: String, pos: String? = "noun") = Suggestion(
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
        langSource = lang,
        langTarget = null,
        fuzzyProfile = FuzzyProfile.SPANISH,
        entryCount = 1,
        dataVersion = 1,
        license = "CC-BY-SA-4.0",
        attribution = "Definiciones del Wikcionario, CC BY-SA 4.0",
    )

    private fun handle(m: PackMetadata) = PackHandle.Abierto(FakeSource(m))

    private fun estadoListo(vararg lemas: String) = SearchState(
        query = "per",
        results = lemas.map { sugerencia(it) },
        status = SearchState.Status.Ready,
        activo = meta(),
        disponibles = listOf(handle(meta())),
    )

    /** Dos packs: es el estado que ejercita el selector. */
    private fun estadoDosPacks(vararg lemas: String): SearchState {
        val es = meta()
        val en = meta("en-def", "en", "English — definitions")
        return estadoListo(*lemas).copy(
            activo = es,
            disponibles = listOf(handle(es), handle(en)),
        )
    }

    private fun mostrarBusqueda(
        state: SearchState,
        onOpenEntry: (Suggestion) -> Unit = {},
        onOpenAttribution: () -> Unit = {},
        onPackChange: (String) -> Unit = {},
        onSearchDefinitions: () -> Unit = {},
        onOpenVisita: (Visita) -> Unit = {},
    ) = compose.setContent {
        SearchScreen(state, onQueryChange = {}, onPackChange = onPackChange,
            onSearchDefinitions = onSearchDefinitions, onOpenVisita = onOpenVisita,
            onOpenEntry = onOpenEntry, onOpenAttribution = onOpenAttribution)
    }

    // --- La lista de resultados --------------------------------------------------------------

    @Test
    fun laListaMuestraElLemaYSuCategoria() {
        mostrarBusqueda(estadoListo("perder"))
        compose.onNodeWithText("perder").assertIsDisplayed()
        compose.onNodeWithText("sust.", substring = true).assertExists()
    }

    @Test
    fun entranTresResultadosSinScrollear() {
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
        mostrarBusqueda(estadoListo("perder", "perro", "permitir", "persona"))
        compose.onNodeWithText("permitir").assertIsDisplayed()
    }

    @Test
    fun unLemaLargoNoSeComeLaPantalla() {
        // Los refranes son entradas del Wikcionario y llegan a 96 caracteres. Si una fila
        // creciera para mostrarlo entero, un solo resultado ocuparia la pantalla.
        val refran = "más corre el galgo que el mastín; pero si el camino es largo, " +
            "más corre el mastín que el galgo"
        mostrarBusqueda(estadoListo(refran, "perder", "perro", "permitir"))
        compose.onNodeWithText("perro").assertIsDisplayed()
    }

    @Test
    fun tocarUnResultadoLoAbre() {
        var abierto: Suggestion? = null
        mostrarBusqueda(estadoListo("perder"), onOpenEntry = { abierto = it })
        compose.onNodeWithText("perder").performClick()
        assertEquals("perder", abierto?.headword)
    }

    // --- Los estados que no son "hay resultados" ---------------------------------------------

    @Test
    fun mientrasSeInstalaElPackLoDice() {
        mostrarBusqueda(SearchState(status = SearchState.Status.Installing))
        compose.onNodeWithText("Instalando", substring = true).assertIsDisplayed()
    }

    @Test
    fun siNoHayDiccionarioLoDiceYNoOfreceBuscar() {
        mostrarBusqueda(SearchState(status = SearchState.Status.Failed("No hay ningún diccionario instalado.")))
        compose.onNodeWithText("No hay ningún diccionario instalado.").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("Decir una palabra").fetchSemanticsNodes().size)
    }

    @Test
    fun sinResultadosLoDiceConLaPalabraBuscada() {
        mostrarBusqueda(estadoListo().copy(query = "xyzzy"))
        compose.onNodeWithText("Sin resultados", substring = true).assertIsDisplayed()
    }

    // --- La entrada -------------------------------------------------------------------------

    private fun entrada(vararg glosas: String) = Entry(
        packId = "test",
        entryId = 1,
        uid = 1,
        headword = "perro",
        partOfSpeech = "noun",
        senses = glosas.map { Sense(it) },
    )

    /** Un enlace dentro de una glosa: lo unico que lo identifica es que es clickeable y de quien
     *  cuelga. Compose no le da texto propio al rectangulo del link. */
    private fun enlaceDentroDe(textoDeLaGlosa: String) =
        hasClickAction() and hasAnyAncestor(hasText(textoDeLaGlosa, substring = true))

    @Test
    fun laEntradaMuestraLemaCategoriaYAcepcionesNumeradas() {
        compose.setContent { EntryScreen(1, onOpenPalabra = {}) { entrada("Mamífero cánido doméstico.") } }
        compose.onNodeWithText("perro").assertIsDisplayed()
        compose.onNodeWithText("sust.", substring = true).assertExists()
        compose.onNodeWithText("1.", substring = true).assertExists()
    }

    @Test
    fun conMasDeTresAcepcionesSoloSeVenTresYUnVerMas() {
        // "justicia" tiene 10 acepciones y el maximo medido es 47. Sin tope, la pantalla se
        // vuelve un rollo y la acepcion util queda debajo de nueve que no se buscaban.
        compose.setContent {
            EntryScreen(1, onOpenPalabra = {}) { entrada("uno", "dos", "tres", "cuatro", "cinco") }
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
    fun verMasDespliegaElResto() {
        compose.setContent {
            EntryScreen(1, onOpenPalabra = {}) { entrada("uno", "dos", "tres", "cuatro", "cinco") }
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
    fun conTresAcepcionesOMenosNoHayVerMas() {
        // La mediana es 3: en la mitad de las entradas el boton no tiene que aparecer siquiera.
        compose.setContent { EntryScreen(1, onOpenPalabra = {}) { entrada("uno", "dos", "tres") } }
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
    private fun mostrarBusquedaEscribible(inicial: SearchState) = compose.setContent {
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
        )
    }

    @Test
    fun escribirLaPrimeraLetraNoCierraElCampo() {
        // El bug que aparecio en el reloj: a la primera letra desaparecen encabezado, boton de
        // voz e historial, el campo salta del indice 2 al 0 y --sin `key`-- el lazy layout lo da
        // por otro nodo, lo destruye y lo recompone. El foco se va con el, y el teclado detras.
        mostrarBusquedaEscribible(estadoListo("perder").copy(query = "", historial = recientes))

        compose.onNode(hasSetTextAction()).performClick()
        compose.onNode(hasSetTextAction()).assertIsFocused()

        compose.onNode(hasSetTextAction()).performTextInput("p")
        compose.waitForIdle()

        compose.onNode(hasSetTextAction()).assertIsFocused()
    }

    @Test
    fun aceptarEnElTecladoSueltaElCampo() {
        // "Aceptar" no hacia nada: hay `ImeAction.Search` declarado y cero `keyboardActions`, y
        // `KeyboardActions.Default` no define comportamiento para Search. La unica salida era el
        // gesto de volver del sistema. Soltar el foco es lo que cierra el teclado y deja la
        // corona operativa sobre los resultados.
        mostrarBusquedaEscribible(estadoListo("perder").copy(query = ""))

        compose.onNode(hasSetTextAction()).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("per")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitForIdle()

        compose.onNode(hasSetTextAction()).assertIsNotFocused()
    }

    @Test
    fun elMaximoDeAcepcionesConElEjemploMasLargoSeDespliegaSinCaerse() {
        // Los numeros son los medidos sobre el pack real: 47 acepciones es el maximo y 917
        // caracteres el ejemplo mas largo. `verMasDespliegaElResto` usa CINCO acepciones sin
        // ejemplos, y por eso nunca reprodujo el crash que aparecio al tocar "Ver mas".
        val ejemploLargo =
            "cronica del siglo XVI que el Wikcionario cita como uso. ".repeat(17).take(917)
        val muchas = (1..47).map { numero ->
            Sense(
                gloss = "acepcion numero $numero",
                examples = if (numero == 1) listOf(ejemploLargo) else emptyList(),
            )
        }
        compose.setContent { EntryScreen(1, onOpenPalabra = {}) { entrada().copy(senses = muchas) } }

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


    // --- Palabras tocables dentro de una glosa ------------------------------------------------

    @Test
    fun tocarUnaPalabraConocidaDeLaGlosaAbreSuEntrada() {
        var abierta: Long? = null
        compose.setContent {
            EntryScreen(
                entryId = 1,
                onOpenPalabra = { abierta = it },
                resolver = { mapOf("cera" to 77L) },
            ) { entrada().copy(senses = listOf(Sense("cilindro de cera con mecha"))) }
        }
        compose.waitForIdle()

        // El nodo del enlace NO tiene semantica de texto propia --Compose le pone solo OnClick
        // sobre el rectangulo de la palabra-- asi que se identifica por el texto que lo contiene.
        compose.onNode(enlaceDentroDe("cilindro de cera"), useUnmergedTree = true).performClick()
        assertEquals(77L, abierta)
    }

    @Test
    fun unaPalabraQueNoEsLemaNoSePuedeTocar() {
        // El color es una promesa: si se pinta tocable algo que no lleva a ningun lado, el
        // usuario aprende a no confiar en el color y la funcion deja de servir.
        compose.setContent {
            EntryScreen(entryId = 1, onOpenPalabra = {}, resolver = { emptyMap() }) {
                entrada().copy(senses = listOf(Sense("cilindro de cera con mecha")))
            }
        }
        compose.waitForIdle()

        assertEquals(
            0,
            compose.onAllNodes(enlaceDentroDe("cilindro de cera"), useUnmergedTree = true)
                .fetchSemanticsNodes().size,
        )
    }

    @Test
    fun laPalabraQueApuntaAEstaMismaEntradaNoSePinta() {
        // Resolver devuelve la entrada abierta: un enlace a donde ya estamos no lleva a nada.
        compose.setContent {
            EntryScreen(entryId = 1, onOpenPalabra = {}, resolver = { mapOf("cera" to 1L) }) {
                entrada().copy(senses = listOf(Sense("cilindro de cera con mecha")))
            }
        }
        compose.waitForIdle()

        assertEquals(
            0,
            compose.onAllNodes(enlaceDentroDe("cilindro de cera"), useUnmergedTree = true)
                .fetchSemanticsNodes().size,
        )
    }

    @Test
    fun laPrimeraFilaVuelveALaBusqueda() {
        // Tocar palabras apila entradas: sin este atajo, volver desde tres de profundidad son
        // tres gestos. Vive en el scroll y no fijo arriba, asi que cuesta cero dp (D-084).
        var volvio = false
        compose.setContent {
            EntryScreen(entryId = 1, onOpenPalabra = {}, onVolverABuscar = { volvio = true }) {
                entrada("una glosa")
            }
        }
        compose.onNodeWithText("Buscar").performClick()
        assertEquals(true, volvio)
    }

    // --- La atribucion, que es D-031 ---------------------------------------------------------

    @Test
    fun laAtribucionMuestraLaLicenciaYLaFuente() {
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
    fun conDosPacksElSelectorMuestraLosDosIdiomas() {
        mostrarBusqueda(estadoDosPacks().copy(query = ""))
        compose.onNodeWithText("ES").assertIsDisplayed()
        compose.onNodeWithText("EN").assertIsDisplayed()
    }

    @Test
    fun conUnSoloPackNoHaySelector() {
        // Un selector de una opcion es chrome puro, y en 192 dp el chrome cuesta resultados.
        mostrarBusqueda(estadoListo().copy(query = ""))
        assertEquals(0, compose.onAllNodesWithText("ES").fetchSemanticsNodes().size)
    }

    @Test
    fun tocarElOtroIdiomaLoAvisa() {
        var elegido: String? = null
        mostrarBusqueda(estadoDosPacks().copy(query = ""), onPackChange = { elegido = it })
        compose.onNodeWithText("EN").performClick()
        assertEquals("en-def", elegido)
    }

    @Test
    fun conDosPacksSiguenEntrandoTresResultados() {
        // Re-verifica D-073 con el selector presente: el selector no puede costar una fila.
        mostrarBusqueda(estadoDosPacks("perder", "perro", "permitir", "persona"))
        compose.onNodeWithText("permitir").assertIsDisplayed()
    }

    @Test
    fun sinResultadosOfreceBuscarEnElOtroIdioma() {
        // Es la escotilla de escape: escribiste algo que este idioma no tiene.
        mostrarBusqueda(estadoDosPacks().copy(query = "dog"))
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Buscar en", substring = true))
        compose.onNodeWithText("Buscar en English", substring = true).assertIsDisplayed()
    }

    @Test
    fun laAtribucionMuestraLosDosPacks() {
        // D-031 con dos fuentes: mostrar una sola licencia es incumplir la condicion de la otra.
        compose.setContent {
            AttributionScreen(
                packs = listOf(handle(meta()), handle(meta("en-def", "en", "English — definitions"))),
                problemas = listOf("de-def: dañado"),
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
        Visita("es-def", 1, "perro", "noun"),
        Visita("en-def", 2, "house", "noun"),
    )

    @Test
    fun conLaBusquedaVaciaSeVenLasEntradasRecientes() {
        mostrarBusqueda(estadoListo().copy(query = "", historial = recientes))
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("perro"))
        compose.onNodeWithText("perro").assertIsDisplayed()
    }

    @Test
    fun alEscribirElHistorialDesaparece() {
        // No puede competir con los resultados: con 192 dp entran tres filas.
        mostrarBusqueda(estadoListo("perder").copy(query = "per", historial = recientes))
        assertEquals(0, compose.onAllNodesWithText("house").fetchSemanticsNodes().size)
    }

    @Test
    fun tocarUnaEntradaRecienteLaAbre() {
        var abierta: Visita? = null
        mostrarBusqueda(
            estadoListo().copy(query = "", historial = recientes),
            onOpenVisita = { abierta = it },
        )
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("perro"))
        compose.onNodeWithText("perro").performClick()
        assertEquals("perro", abierta?.headword)
        assertEquals("tiene que abrir en SU pack, no en el activo", "es-def", abierta?.packId)
    }

    // --- Buscar en las definiciones -------------------------------------------------------------

    @Test
    fun sinResultadosOfreceBuscarEnLasDefiniciones() {
        mostrarBusqueda(estadoListo().copy(query = "animal que ladra"))
        compose.onNodeWithText("Buscar en las definiciones").assertIsDisplayed()
    }

    @Test
    fun conResultadosNoOfreceBuscarEnLasDefiniciones() {
        // Protege D-073: con 192 dp una fila de chrome es un tercio de la lista.
        mostrarBusqueda(estadoListo("perder", "perro"))
        assertEquals(
            0,
            compose.onAllNodesWithText("Buscar en las definiciones").fetchSemanticsNodes().size,
        )
    }

    @Test
    fun tocarBuscarEnLasDefinicionesLoAvisa() {
        var pedido = false
        mostrarBusqueda(estadoListo().copy(query = "ladra"), onSearchDefinitions = { pedido = true })
        compose.onNodeWithText("Buscar en las definiciones").performClick()
        assertEquals(true, pedido)
    }

    @Test
    fun enModoDefinicionesSinResultadosNoSeOfreceLoMismoDeNuevo() {
        // Ofrecerlo otra vez seria un bucle: ya se busco y no hay nada.
        mostrarBusqueda(
            estadoListo().copy(query = "xyzzy", modo = SearchState.Modo.DEFINICIONES),
        )
        compose.onNodeWithText("Sin resultados en las definiciones").assertIsDisplayed()
        assertEquals(
            0,
            compose.onAllNodesWithText("Buscar en las definiciones").fetchSemanticsNodes().size,
        )
    }

    @Test
    fun mientrasBuscaEnLasDefinicionesLoDice() {
        mostrarBusqueda(
            estadoListo().copy(query = "ladra", modo = SearchState.Modo.BUSCANDO_DEFINICIONES),
        )
        compose.onNodeWithText("Buscando", substring = true).assertIsDisplayed()
    }

    @Test
    fun desdeLaBusquedaSeLlegaALaAtribucion() {
        var abierta = false
        mostrarBusqueda(estadoListo("perder"), onOpenAttribution = { abierta = true })
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
    override fun close() = Unit
}
