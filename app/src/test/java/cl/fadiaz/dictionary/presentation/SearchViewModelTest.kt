package cl.fadiaz.dictionary.presentation

import cl.fadiaz.dictionary.data.PackLoad
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * La concurrencia de la busqueda, que es donde un bug NO da error.
 *
 * Los tres modos de falla que cubren estos tests se ven todos iguales desde afuera --una lista
 * de resultados-- y ninguno lanza nada: resultados de una query anterior pisando a la actual,
 * una consulta por pulsacion drenando la bateria, y la busqueda muerta hasta que el usuario
 * borra y vuelve a escribir.
 *
 * TDD: el de la carrera al abrir el pack se escribio **antes** del arreglo y fallo. Los demas
 * son de CARACTERIZACION -- el comportamiento ya existia y esto lo fija.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun antes() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun despues() = Dispatchers.resetMain()

    private fun conPack(source: FakeDictionary) = SearchViewModel { PackLoad.Ready(source) }

    // --- La carrera al abrir el pack (TDD: este fallaba) -------------------------------------

    @Test
    fun loQueSeEscribeMientrasElPackCargaSeBuscaCuandoTermina() = runTest {
        // El pack de español pesa 69 MB y tarda en abrir; la pantalla ya acepta texto. Si lo
        // escrito durante ese rato no se vuelve a consultar, la busqueda queda MUERTA: el
        // usuario ve "Sin resultados" para siempre, hasta que borra una letra y la reescribe.
        val diferido = PackDiferido()
        val fake = FakeDictionary()
        val vm = SearchViewModel(diferido::abrir)

        vm.onQueryChange("per")
        advanceUntilIdle()
        assertTrue(fake.consultas.isEmpty(), "no hay pack todavia: no deberia haber consultado")

        diferido.completarCon(PackLoad.Ready(fake))
        advanceUntilIdle()

        assertEquals(listOf("per"), fake.consultas, "al abrir el pack tiene que buscar lo escrito")
        assertEquals(listOf("per"), vm.state.value.results.map { it.headword })
    }

    @Test
    fun siElPackNoSePuedeAbrirLaPantallaLoDice() = runTest {
        val vm = SearchViewModel { PackLoad.Unusable("el diccionario esta dañado") }
        advanceUntilIdle()
        val status = assertIs<SearchState.Status.Failed>(vm.state.value.status)
        assertEquals("el diccionario esta dañado", status.message)
    }

    @Test
    fun mientrasSeExtraeElPackLaPantallaDiceQueEstaInstalando() = runTest {
        val diferido = PackDiferido()
        val vm = SearchViewModel(diferido::abrir)
        advanceUntilIdle()
        assertEquals(SearchState.Status.Installing, vm.state.value.status)

        diferido.completarCon(PackLoad.Ready(FakeDictionary()))
        advanceUntilIdle()
        assertEquals(SearchState.Status.Ready, vm.state.value.status)
    }

    // --- Caracterizacion: el debounce y la cancelacion ---------------------------------------

    @Test
    fun caracterizacion_escribirUnaPalabraDisparaUnaSolaConsulta() = runTest {
        // CARACTERIZACION. Cuatro pulsaciones seguidas mas rapido que el debounce tienen que
        // costar UNA consulta, no cuatro. En un reloj esto es bateria, no milisegundos.
        val fake = FakeDictionary()
        val vm = conPack(fake)
        advanceUntilIdle()

        listOf("p", "pe", "per", "perr").forEach { texto ->
            vm.onQueryChange(texto)
            advanceTimeBy(SearchViewModel.DEBOUNCE_MS / 2)
        }
        advanceUntilIdle()

        assertEquals(listOf("perr"), fake.consultas)
    }

    @Test
    fun caracterizacion_escribirDespacioDisparaUnaConsultaPorPalabra() = runTest {
        val fake = FakeDictionary()
        val vm = conPack(fake)
        advanceUntilIdle()

        vm.onQueryChange("per")
        advanceTimeBy(SearchViewModel.DEBOUNCE_MS * 2)
        vm.onQueryChange("casa")
        advanceUntilIdle()

        assertEquals(listOf("per", "casa"), fake.consultas)
    }

    @Test
    fun caracterizacion_unaConsultaVieja_seCancela_yNoPisaALaNueva() = runTest {
        // El modo de falla: "per" tarda, el usuario escribe "casa", y "per" termina despues y
        // deja SUS resultados en pantalla. La lista mostraria otra palabra que la escrita.
        val fake = FakeDictionary(demora = 1_000)
        val vm = conPack(fake)
        advanceUntilIdle()

        vm.onQueryChange("per")
        advanceTimeBy(SearchViewModel.DEBOUNCE_MS + 10)
        vm.onQueryChange("casa")
        advanceUntilIdle()

        assertEquals(listOf("per", "casa"), fake.consultas, "las dos tienen que haber empezado")
        assertEquals(listOf("per"), fake.canceladas, "la vieja tiene que haberse cancelado")
        assertEquals(listOf("casa"), vm.state.value.results.map { it.headword })
        assertEquals("casa", vm.state.value.query)
    }

    @Test
    fun caracterizacion_laConsultaVaciaNoConsultaYLimpiaLaLista() = runTest {
        val fake = FakeDictionary()
        val vm = conPack(fake)
        advanceUntilIdle()

        vm.onQueryChange("per")
        advanceUntilIdle()
        assertTrue(vm.state.value.results.isNotEmpty())

        vm.onQueryChange("")
        advanceUntilIdle()
        assertEquals(listOf("per"), fake.consultas, "una query vacia no tiene que ir al pack")
        assertTrue(vm.state.value.results.isEmpty(), "y tiene que limpiar la lista")
    }

    @Test
    fun caracterizacion_elTextoEscritoSeVeAntesDelDebounce() = runTest {
        // La query es del usuario y se muestra ya; los resultados son del pack y llegan despues.
        // Si el campo esperara al debounce, escribir se sentiria trabado.
        val fake = FakeDictionary()
        val vm = conPack(fake)
        advanceUntilIdle()

        vm.onQueryChange("per")
        assertEquals("per", vm.state.value.query)
        assertTrue(fake.consultas.isEmpty(), "todavia no paso el debounce")
    }

    @Test
    fun caracterizacion_cerrarElViewModelCierraElPack() = runTest {
        // Un pack sin cerrar deja la conexion de SQLite viva y el archivo mapeado en memoria.
        val fake = FakeDictionary()
        val vm = conPack(fake)
        advanceUntilIdle()

        vm.cerrar()
        assertTrue(fake.cerrado)
    }

    @Test
    fun laAtribucionYLaLicenciaSalenDelPackYNoDelCodigo() = runTest {
        // D-031: mostrarlas es la condicion de uso de los datos. Si vinieran de una constante,
        // un pack de otra fuente mostraria la licencia equivocada.
        val fake = FakeDictionary()
        val vm = conPack(fake)
        advanceUntilIdle()

        assertEquals(fake.metadata.attribution, vm.state.value.attribution)
        assertEquals(fake.metadata.license, vm.state.value.license)
        assertEquals(fake.metadata.name, vm.state.value.packName)
    }
}
