package cl.fadiaz.dictionary.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performClick
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

    @Test
    fun laEntradaMuestraLemaCategoriaYAcepcionesNumeradas() {
        compose.setContent { EntryScreen(1) { entrada("Mamífero cánido doméstico.") } }
        compose.onNodeWithText("perro").assertIsDisplayed()
        compose.onNodeWithText("sust.", substring = true).assertExists()
        compose.onNodeWithText("1.", substring = true).assertExists()
    }

    @Test
    fun conMasDeTresAcepcionesSoloSeVenTresYUnVerMas() {
        // "justicia" tiene 10 acepciones y el maximo medido es 47. Sin tope, la pantalla se
        // vuelve un rollo y la acepcion util queda debajo de nueve que no se buscaban.
        compose.setContent {
            EntryScreen(1) { entrada("uno", "dos", "tres", "cuatro", "cinco") }
        }
        compose.onNodeWithText("tres", substring = true).assertExists()
        assertEquals(0, compose.onAllNodesWithText("cuatro", substring = true).fetchSemanticsNodes().size)
        compose.onNodeWithText("Ver más", substring = true).assertExists()
    }

    @Test
    fun verMasDespliegaElResto() {
        compose.setContent {
            EntryScreen(1) { entrada("uno", "dos", "tres", "cuatro", "cinco") }
        }
        compose.onNodeWithText("Ver más", substring = true).performClick()
        compose.onNodeWithText("cuatro", substring = true).assertExists()
        compose.onNodeWithText("cinco", substring = true).assertExists()
    }

    @Test
    fun conTresAcepcionesOMenosNoHayVerMas() {
        // La mediana es 3: en la mitad de las entradas el boton no tiene que aparecer siquiera.
        compose.setContent { EntryScreen(1) { entrada("uno", "dos", "tres") } }
        compose.onNodeWithText("tres", substring = true).assertExists()
        assertEquals(0, compose.onAllNodesWithText("Ver más", substring = true).fetchSemanticsNodes().size)
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
    override fun close() = Unit
}
