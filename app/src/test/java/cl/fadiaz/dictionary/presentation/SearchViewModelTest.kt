package cl.fadiaz.dictionary.presentation

import cl.fadiaz.dictionary.data.PackSet
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
import cl.fadiaz.dictionary.core.EntrySummary
import cl.fadiaz.dictionary.core.MatchKind
import cl.fadiaz.dictionary.core.Suggestion
import cl.fadiaz.dictionary.data.PackHandle
import cl.fadiaz.dictionary.data.Visita

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

    private fun handle(d: FakeDictionary, esDemo: Boolean = false) =
        PackHandle.Abierto(d, esDemo)

    @BeforeTest
    fun antes() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun despues() = Dispatchers.resetMain()

    private fun conPack(source: FakeDictionary) = SearchViewModel({ listos(source) })

    // --- Las palabras guardadas ---------------------------------------------------------------

    private fun visita(lema: String, id: Long = 1, pack: String = "es-def") =
        Visita(packId = pack, entryId = id, headword = lema, partOfSpeech = "noun")

    @Test
    fun guardarUnaPalabraLaDejaEnLaLista() = runTest {
        val guardadas = mutableListOf<List<Visita>>()
        val vm = SearchViewModel({ listos(FakeDictionary()) }, guardarFavoritos = { guardadas += it })
        advanceUntilIdle()

        vm.alternarFavorita(visita("perro"))

        assertEquals(listOf("perro"), vm.state.value.favoritos.map { it.headword })
        assertTrue(vm.esFavorita("es-def", 1))
        assertEquals(1, guardadas.size, "tiene que persistirse, no solo quedar en memoria")
    }

    @Test
    fun guardarDosVecesLaMismaLaSaca() = runTest {
        val vm = SearchViewModel({ listos(FakeDictionary()) })
        advanceUntilIdle()

        vm.alternarFavorita(visita("perro"))
        vm.alternarFavorita(visita("perro"))

        assertTrue(vm.state.value.favoritos.isEmpty())
        assertTrue(!vm.esFavorita("es-def", 1))
    }

    @Test
    fun lasFavoritasDeDosPacksNoSeConfunden() = runTest {
        // Los entryId son rowids: el 1 existe en TODOS los packs. Sin mirar el packId, guardar
        // "perro" marcaria tambien como guardada la entrada 1 del diccionario de ingles.
        val vm = SearchViewModel({ listos(FakeDictionary()) })
        advanceUntilIdle()

        vm.alternarFavorita(visita("perro", id = 1, pack = "es-def"))

        assertTrue(vm.esFavorita("es-def", 1))
        assertTrue(!vm.esFavorita("en-def", 1), "el mismo id en otro pack es otra palabra")
    }

    @Test
    fun lasGuardadasSeLeenAlArrancar() = runTest {
        val previas = listOf(visita("casa", 7), visita("perro", 9))
        val vm = SearchViewModel({ listos(FakeDictionary()) }, favoritosGuardados = { previas })
        advanceUntilIdle()
        assertEquals(previas, vm.state.value.favoritos)
    }

    @Test
    fun elTopeDeGuardadasSeRespeta() = runTest {
        // Termina en un String de SharedPreferences: sin tope crece sin limite.
        val vm = SearchViewModel({ listos(FakeDictionary()) })
        advanceUntilIdle()
        repeat(SearchViewModel.FAVORITOS_MAX + 10) { i -> vm.alternarFavorita(visita("p$i", i.toLong())) }
        assertEquals(SearchViewModel.FAVORITOS_MAX, vm.state.value.favoritos.size)
        // La ultima guardada va primero: es la que mas probablemente quieras volver a ver.
        assertEquals("p${SearchViewModel.FAVORITOS_MAX + 9}", vm.state.value.favoritos.first().headword)
    }

    // --- La palabra del dia ------------------------------------------------------------------

    @Test
    fun alAbrirElPackSePublicaLaPalabraDelDia() = runTest {
        // Que la politica sea correcta no alcanza: tiene que llegar al estado. Esto se escribio
        // porque la primera version compilaba, pasaba sus tests y **no mostraba nada** en el
        // reloj, y una captura de pantalla no dice por que.
        val fake = FakeDictionary()
        fake.resumenes = (1L..50L).associateWith {
            EntrySummary(it, "palabra$it", "noun", (1000 - it).toInt())
        }
        val vm = SearchViewModel({ listos(fake) }, fechaDeHoy = { "2026-09-18" })
        advanceUntilIdle()

        val hoy = vm.state.value.palabraDelDia
        assertTrue(hoy != null, "no se publico ninguna palabra del dia")
        assertTrue(hoy.headword.startsWith("palabra"), "salio algo raro: ${hoy.headword}")
    }

    @Test
    fun sinFechaNoHayPalabraDelDia() = runTest {
        // El default: si nadie cablea el reloj, la ausencia se ve en pantalla en vez de mostrar
        // una palabra que nunca cambia.
        val fake = FakeDictionary()
        fake.resumenes = mapOf(1L to EntrySummary(1, "unica", "noun", 900))
        val vm = SearchViewModel({ listos(fake) })
        advanceUntilIdle()
        assertEquals(null, vm.state.value.palabraDelDia)
    }

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

        diferido.completarCon(listos(fake))
        advanceUntilIdle()

        assertEquals(listOf("per"), fake.consultas, "al abrir el pack tiene que buscar lo escrito")
        assertEquals(listOf("per"), vm.state.value.results.map { it.headword })
    }

    @Test
    fun siElPackNoSePuedeAbrirLaPantallaLoDice() = runTest {
        val vm = SearchViewModel({ PackSet.Unusable("el diccionario esta dañado") })
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

        diferido.completarCon(listos(FakeDictionary()))
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

    // --- El selector de idioma ---------------------------------------------------------------

    @Test
    fun conDosPacksArrancaEnElGuardado() = runTest {
        // Sin esto, el pack activo lo decidiria el orden alfabetico -- y "en-..." ordena antes
        // que "es-...", asi que el reloj de alguien que solo usa español arrancaria en ingles.
        val es = FakeDictionary("es-def", "es")
        val en = FakeDictionary("en-def", "en")
        val vm = SearchViewModel({ PackSet.Ready(handle(en), listOf(handle(en), handle(es))) },
                                 preferido = { "es-def" })
        advanceUntilIdle()
        assertEquals("es-def", vm.state.value.activo?.packId)
    }

    @Test
    fun siNoHayNadaGuardadoManaElIdiomaDelReloj() = runTest {
        val es = FakeDictionary("es-def", "es")
        val en = FakeDictionary("en-def", "en")
        val vm = SearchViewModel({ PackSet.Ready(handle(en), listOf(handle(en), handle(es))) },
                                 preferido = { "es" })
        advanceUntilIdle()
        assertEquals("es-def", vm.state.value.activo?.packId, "deberia caer al pack de ese idioma")
    }

    @Test
    fun elPackDeDemostracionNuncaGanaSiHayUnDiccionarioDeVerdad() = runTest {
        // Encontrado usandolo: con el pack de demo (28 entradas) y el español real (146.194)
        // instalados, la app abria el de DEMO. Ni la preferencia ni el idioma del reloj
        // desempataban --los dos packs son "es"-- asi que caia al ultimo escalon, que era el
        // orden alfabetico: "demo-" gana a "es-".
        //
        // Es la misma clase que mato D-079, sobrevivida en el ultimo recurso. Y con un pack de
        // demostracion dentro del APK ese recurso se dispara siempre, no casi nunca.
        val demo = FakeDictionary("toy-es-en", "es")
        val real = FakeDictionary("es-def-wikc", "es")
        val vm = SearchViewModel({
            PackSet.Ready(handle(demo), listOf(handle(demo, esDemo = true), handle(real)))
        }, preferido = { "en" })
        advanceUntilIdle()
        assertEquals("es-def-wikc", vm.state.value.activo?.packId)
    }

    @Test
    fun elPackDeDemostracionNiSiquieraSeOfreceSiHayUnoDeVerdad() = runTest {
        // Visto en pantalla: el selector mostraba "ES" y "ES" --el demo y el español real-- y no
        // habia forma de saber cual era cual. Un placeholder no es una opcion: si hay un
        // diccionario, el de juguete no se ofrece, y con un solo pack el selector desaparece.
        val demo = FakeDictionary("toy-es-en", "es")
        val real = FakeDictionary("es-def-wikc", "es")
        val vm = SearchViewModel({
            PackSet.Ready(handle(real), listOf(handle(demo, esDemo = true), handle(real)))
        })
        advanceUntilIdle()
        assertEquals(listOf("es-def-wikc"), vm.state.value.disponibles.map { it.packId })
    }

    @Test
    fun conSoloElPackDeDemostracionSeUsaEse() = runTest {
        // Para eso existe: que la app recien instalada tenga algo que mostrar.
        val demo = FakeDictionary("toy-es-en", "es")
        val vm = SearchViewModel({
            PackSet.Ready(handle(demo, esDemo = true), listOf(handle(demo, esDemo = true)))
        })
        advanceUntilIdle()
        assertEquals("toy-es-en", vm.state.value.activo?.packId)
    }

    @Test
    fun cambiarDeIdiomaRepiteLaBusquedaVigenteEnElPackNuevo() = runTest {
        // Es el punto del selector: si al cambiar hubiera que reescribir la palabra, en una
        // muñeca nadie lo usaria.
        val es = FakeDictionary("es-def", "es")
        val en = FakeDictionary("en-def", "en")
        val vm = SearchViewModel({ PackSet.Ready(handle(es), listOf(handle(es), handle(en))) })
        advanceUntilIdle()

        vm.onQueryChange("per")
        advanceUntilIdle()
        assertEquals(listOf("per"), es.consultas)

        vm.onPackChange("en-def")
        advanceUntilIdle()
        assertEquals(listOf("per"), en.consultas, "la query tiene que repetirse en el pack nuevo")
        assertEquals("per", vm.state.value.query)
        assertEquals("en-def", vm.state.value.activo?.packId)
    }

    @Test
    fun cambiarDeIdiomaLoRecuerda() = runTest {
        var recordado: String? = null
        val es = FakeDictionary("es-def", "es")
        val en = FakeDictionary("en-def", "en")
        val vm = SearchViewModel({ PackSet.Ready(handle(es), listOf(handle(es), handle(en))) },
                                 recordar = { recordado = it })
        advanceUntilIdle()
        vm.onPackChange("en-def")
        advanceUntilIdle()
        assertEquals("en-def", recordado)
    }

    @Test
    fun abrirUnaEntradaLaBuscaEnSuPropioPack() = runTest {
        // El bug que esto arregla: la navegacion pasaba solo entryId, asi que con dos packs
        // abiertos una entrada de ingles se resolvia contra el pack activo -- y mostraba OTRA
        // palabra, sin error.
        val es = FakeDictionary("es-def", "es")
        val en = FakeDictionary("en-def", "en")
        val vm = SearchViewModel({ PackSet.Ready(handle(es), listOf(handle(es), handle(en))) })
        advanceUntilIdle()
        assertEquals("en-def", vm.entry("en-def", 7)?.packId)
    }

    @Test
    fun abrirUnaEntradaDeUnPackDesconocidoDevuelveNull() = runTest {
        // Caer al pack activo seria el mismo bug, pero silencioso. Null hace que la pantalla
        // diga que la entrada no esta, que es honesto.
        val vm = conPack(FakeDictionary("es-def", "es"))
        advanceUntilIdle()
        assertEquals(null, vm.entry("no-existe", 7))
    }

    @Test
    fun unPackRotoNoSeLlevaAlOtro() = runTest {
        val es = FakeDictionary("es-def", "es")
        val vm = SearchViewModel({
            PackSet.Ready(handle(es), listOf(handle(es)), problemas = listOf("en-def: dañado"))
        })
        advanceUntilIdle()
        assertEquals(SearchState.Status.Ready, vm.state.value.status)
        assertEquals(listOf("en-def: dañado"), vm.state.value.problemas)
    }

    @Test
    fun cerrarCierraTodosLosPacks() = runTest {
        val es = FakeDictionary("es-def", "es")
        val en = FakeDictionary("en-def", "en")
        val vm = SearchViewModel({ PackSet.Ready(handle(es), listOf(handle(es), handle(en))) })
        advanceUntilIdle()
        vm.cerrar()
        assertTrue(es.cerrado && en.cerrado, "un pack sin cerrar deja viva su conexion de SQLite")
    }

    // --- El historial de entradas abiertas -------------------------------------------------------

    private fun sugerencia(pack: String, id: Long, lema: String) = Suggestion(
        packId = pack, entryId = id, headword = lema, partOfSpeech = "noun",
        matchKind = MatchKind.PREFIX, score = 0,
    )

    @Test
    fun abrirUnaEntradaLaDejaEnElHistorial() {
        val fake = FakeDictionary("es-def", "es")
        val vm = conPack(fake)
        vm.registrarVisita(sugerencia("es-def", 7, "perro"))
        assertEquals(listOf("perro"), vm.state.value.historial.map { it.headword })
    }

    @Test
    fun abrirLaMismaEntradaDosVecesNoLaDuplicaYLaSubeAlTope() {
        val vm = conPack(FakeDictionary("es-def", "es"))
        vm.registrarVisita(sugerencia("es-def", 1, "perro"))
        vm.registrarVisita(sugerencia("es-def", 2, "casa"))
        vm.registrarVisita(sugerencia("es-def", 1, "perro"))
        assertEquals(listOf("perro", "casa"), vm.state.value.historial.map { it.headword })
    }

    @Test
    fun elHistorialSeRecortaAlMaximo() {
        // El tope no es arbitrario: la pantalla da tres filas de 48 dp (D-073). Guardar mas seria
        // guardar lo que no se ve.
        val vm = conPack(FakeDictionary("es-def", "es"))
        (1..6).forEach { vm.registrarVisita(sugerencia("es-def", it.toLong(), "lema$it")) }
        assertEquals(SearchViewModel.HISTORIAL_MAX, vm.state.value.historial.size)
        assertEquals("lema6", vm.state.value.historial.first().headword)
    }

    @Test
    fun unaEntradaDeUnPackQueYaNoEstaNoSeMuestra() = runTest {
        // Se filtra al mostrar, no se poda al guardar: desinstalar y reinstalar un pack es un
        // flujo real, y asi el historial vuelve solo. Una fila que al tocarla no abre nada es
        // peor que no tener la fila.
        val es = FakeDictionary("es-def", "es")
        val vm = SearchViewModel(
            { PackSet.Ready(handle(es), listOf(handle(es))) },
            historialGuardado = {
                listOf(
                    Visita("es-def", 1, "perro", "noun"),
                    Visita("de-def", 2, "Hund", "noun"),
                )
            },
        )
        advanceUntilIdle()
        assertEquals(listOf("perro"), vm.state.value.historial.map { it.headword })
    }

    @Test
    fun elHistorialSePersiste() {
        var guardado: List<Visita> = emptyList()
        val vm = SearchViewModel({ listos(FakeDictionary("es-def", "es")) },
                                 guardarHistorial = { guardado = it })
        vm.registrarVisita(sugerencia("es-def", 7, "perro"))
        assertEquals(listOf("perro"), guardado.map { it.headword })
    }

    // --- Buscar en las definiciones -----------------------------------------------------------

    @Test
    fun buscarEnDefinicionesConsultaElPackActivoConLaQueryVigente() = runTest {
        val fake = FakeDictionary("es-def", "es")
        val vm = conPack(fake)
        advanceUntilIdle()
        vm.onQueryChange("animal que ladra")
        advanceUntilIdle()

        vm.onSearchDefinitions()
        advanceUntilIdle()

        assertEquals(listOf("animal que ladra"), fake.definiciones)
        assertEquals(SearchState.Modo.DEFINICIONES, vm.state.value.modo)
        assertEquals(listOf("def:animal que ladra"), vm.state.value.results.map { it.headword })
    }

    @Test
    fun laBusquedaPorDefinicionNoSeDisparaEscribiendo() = runTest {
        // Es el contrato de la interfaz vuelto test: recorre un indice mucho mayor que el de
        // lemas y no cumple el presupuesto de latencia de la busqueda incremental.
        val fake = FakeDictionary("es-def", "es")
        val vm = conPack(fake)
        advanceUntilIdle()

        vm.onQueryChange("animal")
        advanceUntilIdle()

        assertTrue(fake.definiciones.isEmpty(), "escribir no puede tocar el indice de texto libre")
    }

    @Test
    fun escribirDespuesDeBuscarEnDefinicionesVuelveALaBusquedaNormal() = runTest {
        // Volver no puede costar un boton: en 192 dp cada control se paga en resultados.
        val fake = FakeDictionary("es-def", "es")
        val vm = conPack(fake)
        advanceUntilIdle()
        vm.onQueryChange("animal")
        advanceUntilIdle()
        vm.onSearchDefinitions()
        advanceUntilIdle()
        assertEquals(SearchState.Modo.DEFINICIONES, vm.state.value.modo)

        vm.onQueryChange("animales")
        advanceUntilIdle()

        assertEquals(SearchState.Modo.NORMAL, vm.state.value.modo)
        assertEquals(listOf("animales"), vm.state.value.results.map { it.headword })
    }

    @Test
    fun unaBusquedaPorDefinicionViejaNoPisaLoQueSeEscribioDespues() = runTest {
        // El modo de falla: la de definiciones tarda, el usuario sigue escribiendo, y el
        // resultado viejo aterriza encima. No tira ninguna excepcion.
        val fake = FakeDictionary("es-def", "es", demora = 1_000)
        val vm = conPack(fake)
        advanceUntilIdle()
        vm.onQueryChange("animal")
        advanceTimeBy(SearchViewModel.DEBOUNCE_MS + 10)

        vm.onSearchDefinitions()
        advanceTimeBy(10)
        vm.onQueryChange("casa")
        advanceUntilIdle()

        assertEquals(SearchState.Modo.NORMAL, vm.state.value.modo)
        assertEquals(listOf("casa"), vm.state.value.results.map { it.headword })
    }

    @Test
    fun mientrasBuscaEnLasDefinicionesElEstadoLoDice() = runTest {
        val fake = FakeDictionary("es-def", "es", demora = 1_000)
        val vm = conPack(fake)
        advanceUntilIdle()
        vm.onQueryChange("animal")
        advanceUntilIdle()

        vm.onSearchDefinitions()
        advanceTimeBy(10)

        assertEquals(SearchState.Modo.BUSCANDO_DEFINICIONES, vm.state.value.modo)
    }

    @Test
    fun cambiarDeIdiomaSaleDelModoDefiniciones() = runTest {
        val es = FakeDictionary("es-def", "es")
        val en = FakeDictionary("en-def", "en")
        val vm = SearchViewModel({ PackSet.Ready(handle(es), listOf(handle(es), handle(en))) })
        advanceUntilIdle()
        vm.onQueryChange("animal")
        advanceUntilIdle()
        vm.onSearchDefinitions()
        advanceUntilIdle()

        vm.onPackChange("en-def")
        advanceUntilIdle()

        assertEquals(SearchState.Modo.NORMAL, vm.state.value.modo)
        assertEquals(listOf("animal"), en.consultas, "el pack nuevo recibe la query por prefijo")
    }

    @Test
    fun laAtribucionYLaLicenciaSalenDelPackYNoDelCodigo() = runTest {
        // D-031: mostrarlas es la condicion de uso de los datos. Si vinieran de una constante,
        // un pack de otra fuente mostraria la licencia equivocada.
        val fake = FakeDictionary()
        val vm = conPack(fake)
        advanceUntilIdle()

        assertEquals(fake.metadata.attribution, vm.state.value.activo?.attribution)
        assertEquals(fake.metadata.license, vm.state.value.activo?.license)
        assertEquals(fake.metadata.name, vm.state.value.activo?.name)
    }
}
