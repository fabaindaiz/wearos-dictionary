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
import cl.fadiaz.dictionary.core.Entry
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

    private fun estadoListo(vararg lemas: String) = SearchState(
        query = "per",
        results = lemas.map { sugerencia(it) },
        status = SearchState.Status.Ready,
        packName = "Español — definiciones",
        attribution = "Definiciones del Wikcionario, CC BY-SA 4.0",
        license = "CC-BY-SA-4.0",
    )

    private fun mostrarBusqueda(
        state: SearchState,
        onOpenEntry: (Suggestion) -> Unit = {},
        onOpenAttribution: () -> Unit = {},
    ) = compose.setContent {
        SearchScreen(state, onQueryChange = {}, onOpenEntry = onOpenEntry, onOpenAttribution)
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
            AttributionScreen(
                packName = "Español — definiciones",
                attribution = "Definiciones del Wikcionario, CC BY-SA 4.0",
                license = "CC-BY-SA-4.0",
            )
        }
        compose.onNodeWithText("Wikcionario", substring = true).assertExists()
        compose.onNodeWithText("CC-BY-SA-4.0", substring = true).assertExists()
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
