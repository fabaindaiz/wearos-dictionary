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
import cl.fadiaz.dictionary.data.Visit
import cl.fadiaz.dictionary.tile.TileContents

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

    private fun handle(d: FakeDictionary, isDemo: Boolean = false) =
        PackHandle.Open(d, isDemo)

    @BeforeTest
    fun before() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun after() = Dispatchers.resetMain()

    private fun conPack(source: FakeDictionary) = SearchViewModel({ listos(source) })

    // --- Borrar un diccionario -----------------------------------------------------------------

    @Test
    fun theConnectionClosesBEFORETheFileIsDeleted() = runTest {
        // Es LA regla de esta funcion, y no es teorica: en Unix un archivo borrado con un
        // descriptor abierto sigue ocupando el disco hasta que se cierre, y la app lo seguiria
        // leyendo como si nada. El usuario veria "borrado" y cero espacio liberado.
        val es = FakeDictionary(packId = "es-def")
        val en = FakeDictionary(packId = "en-def")
        var closedOnDelete: Boolean? = null
        val vm = SearchViewModel(
            { listos(es, en) },
            deleteFromDisk = { closedOnDelete = en.cerrado; true },
        )
        advanceUntilIdle()

        vm.deletePack("en-def")
        advanceUntilIdle()

        assertEquals(true, closedOnDelete, "se borro el archivo con la conexion todavia abierta")
    }

    @Test
    fun deletingAPackRemovesItFromTheList() = runTest {
        val es = FakeDictionary(packId = "es-def")
        val en = FakeDictionary(packId = "en-def")
        var quedan = listOf(es, en)
        val vm = SearchViewModel(
            { listos(*quedan.toTypedArray()) },
            deleteFromDisk = { fileName ->
                quedan = quedan.filterNot { it.metadata.packId + ".db" == fileName }
                true
            },
        )
        advanceUntilIdle()
        assertEquals(2, vm.state.value.available.size)

        vm.deletePack("en-def")
        advanceUntilIdle()

        assertEquals(listOf("es-def"), vm.state.value.available.map { it.packId })
    }

    @Test
    fun deletingTheACTIVEDictionaryLeavesAnotherActive() = runTest {
        // Si no, la app queda buscando en un pack que ya no existe.
        val es = FakeDictionary(packId = "es-def")
        val en = FakeDictionary(packId = "en-def")
        var quedan = listOf(es, en)
        val vm = SearchViewModel(
            { listos(*quedan.toTypedArray()) },
            deleteFromDisk = { fileName ->
                quedan = quedan.filterNot { it.metadata.packId + ".db" == fileName }
                true
            },
        )
        advanceUntilIdle()
        val activoAntes = vm.state.value.active?.packId
        assertEquals("es-def", activoAntes)

        vm.deletePack("es-def")
        advanceUntilIdle()

        assertEquals("en-def", vm.state.value.active?.packId)
    }

    @Test
    fun theDemoPackCannotBeDeleted() = runTest {
        // Viene dentro del APK y `PackStore.open` lo re-extrae al reabrir, asi que borrarlo seria
        // una accion que no hace nada: el pack vuelve solo. Ofrecerla seria mentir.
        val demo = FakeDictionary(packId = "demo")
        var deleteWasAttempted = false
        val vm = SearchViewModel(
            { listos(demo, demos = setOf("demo")) },
            deleteFromDisk = { deleteWasAttempted = true; true },
        )
        advanceUntilIdle()

        vm.deletePack("demo")
        advanceUntilIdle()

        assertTrue(!deleteWasAttempted, "intento borrar el pack de demostracion")
    }

    // --- Las palabras guardadas ---------------------------------------------------------------

    private fun visit(lema: String, id: Long = 1, pack: String = "es-def") =
        Visit(packId = pack, entryId = id, headword = lema, partOfSpeech = "noun")

    @Test
    fun savingAWordLeavesItInTheList() = runTest {
        val guardadas = mutableListOf<List<Visit>>()
        val vm = SearchViewModel({ listos(FakeDictionary()) }, saveFavorites = { guardadas += it })
        advanceUntilIdle()

        vm.toggleFavorite(visit("perro"))

        assertEquals(listOf("perro"), vm.state.value.favorites.map { it.headword })
        assertTrue(vm.isFavorite("es-def", 1))
        assertEquals(1, guardadas.size, "tiene que persistirse, no solo quedar en memoria")
    }

    @Test
    fun savingTheSameWordTwiceRemovesIt() = runTest {
        val vm = SearchViewModel({ listos(FakeDictionary()) })
        advanceUntilIdle()

        vm.toggleFavorite(visit("perro"))
        vm.toggleFavorite(visit("perro"))

        assertTrue(vm.state.value.favorites.isEmpty())
        assertTrue(!vm.isFavorite("es-def", 1))
    }

    @Test
    fun favoritesFromTwoPacksAreNotConfused() = runTest {
        // Los entryId son rowids: el 1 existe en TODOS los packs. Sin mirar el packId, guardar
        // "perro" marcaria tambien como guardada la entrada 1 del diccionario de ingles.
        val vm = SearchViewModel({ listos(FakeDictionary()) })
        advanceUntilIdle()

        vm.toggleFavorite(visit("perro", id = 1, pack = "es-def"))

        assertTrue(vm.isFavorite("es-def", 1))
        assertTrue(!vm.isFavorite("en-def", 1), "el mismo id en otro pack es otra palabra")
    }

    @Test
    fun savedWordsAreReadAtStartup() = runTest {
        val previas = listOf(visit("casa", 7), visit("perro", 9))
        val vm = SearchViewModel({ listos(FakeDictionary()) }, savedFavorites = { previas })
        advanceUntilIdle()
        assertEquals(previas, vm.state.value.favorites)
    }

    @Test
    fun theSavedWordsCapIsRespected() = runTest {
        // Termina en un String de SharedPreferences: sin tope crece sin limite.
        val vm = SearchViewModel({ listos(FakeDictionary()) })
        advanceUntilIdle()
        repeat(SearchViewModel.MAX_FAVORITES + 10) { i -> vm.toggleFavorite(visit("p$i", i.toLong())) }
        assertEquals(SearchViewModel.MAX_FAVORITES, vm.state.value.favorites.size)
        // La ultima guardada va primero: es la que mas probablemente quieras volver a ver.
        assertEquals("p${SearchViewModel.MAX_FAVORITES + 9}", vm.state.value.favorites.first().headword)
    }

    // --- La palabra del dia ------------------------------------------------------------------

    @Test
    fun openingThePackPublishesTheWordOfTheDay() = runTest {
        // Que la politica sea correcta no alcanza: tiene que llegar al estado. Esto se escribio
        // porque la primera version compilaba, pasaba sus tests y **no mostraba nada** en el
        // reloj, y una captura de pantalla no dice por que.
        val fake = FakeDictionary(entryCount = 50)
        fake.summaries = (1L..50L).associateWith {
            EntrySummary(it, "palabra$it", "noun", (1000 - it).toInt())
        }
        val vm = SearchViewModel({ listos(fake) }, todayDate = { "2026-09-18" })
        advanceUntilIdle()

        val today = vm.state.value.wordsOfTheDay[fake.metadata.packId]
        assertTrue(today != null, "no se publico ninguna palabra del dia")
        assertTrue(today.headword.startsWith("palabra"), "salio algo raro: ${today.headword}")
    }

    @Test
    fun thereIsOneWordOfTheDayPerLoadedDictionary() = runTest {
        // Con dos idiomas instalados las dos palabras interesan, y cambiar de idioma no puede
        // tener que recalcular nada.
        val es = FakeDictionary(packId = "es-def", entryCount = 50).apply {
            summaries = (1L..50L).associateWith { EntrySummary(it, "es$it", "noun", 900) }
        }
        val en = FakeDictionary(packId = "en-def", entryCount = 50).apply {
            summaries = (1L..50L).associateWith { EntrySummary(it, "en$it", "noun", 900) }
        }
        val vm = SearchViewModel({ listos(es, en) }, todayDate = { "2026-09-18" })
        advanceUntilIdle()

        val words = vm.state.value.wordsOfTheDay
        assertEquals(setOf("es-def", "en-def"), words.keys)
        assertTrue(words.getValue("es-def").headword.startsWith("es"))
        assertTrue(words.getValue("en-def").headword.startsWith("en"))
    }

    @Test
    fun eachDictionaryHasItsOwnWord() = runTest {
        // Misma fecha, mismos datos, distinto packId: si la semilla ignorara el pack, los dos
        // diccionarios mostrarian la entrada del mismo id, que en cada uno es otra palabra.
        val a = FakeDictionary(packId = "aaa", entryCount = 500).apply {
            summaries = (1L..500L).associateWith { EntrySummary(it, "p$it", "noun", 900) }
        }
        val b = FakeDictionary(packId = "bbb", entryCount = 500).apply {
            summaries = (1L..500L).associateWith { EntrySummary(it, "p$it", "noun", 900) }
        }
        val vm = SearchViewModel({ listos(a, b) }, todayDate = { "2026-09-18" })
        advanceUntilIdle()

        val words = vm.state.value.wordsOfTheDay
        assertTrue(
            words.getValue("aaa").entryId != words.getValue("bbb").entryId,
            "los dos packs eligieron la misma entrada: la semilla no mira el packId",
        )
    }

    // --- La cache que alimenta a los tiles ------------------------------------------------

    @Test
    fun openingThePacksCachesTheWeekOfWordsForTheTile() = runTest {
        // El tile NO abre el pack --onTileRequest corre en el hilo principal con 10 s de tope--
        // asi que si la app no deja la semana escrita, el tile no tiene nada que mostrar.
        val fake = FakeDictionary(packId = "es-def", entryCount = 500).apply {
            summaries = (1L..500L).associateWith { EntrySummary(it, "p$it", "noun", 900) }
        }
        var guardadas: Pair<String, List<Visit>>? = null
        val vm = SearchViewModel(
            { listos(fake) },
            todayDate = { "2026-09-19" },
            saveWeekWords = { since, words -> guardadas = since to words },
        )
        advanceUntilIdle()

        val cached = guardadas
        assertTrue(cached != null, "no se cacheo ninguna palabra para el tile")
        assertEquals("2026-09-19", cached.first)
        assertEquals(TileContents.CACHED_DAYS, cached.second.size)
        assertTrue(cached.second.all { it.packId == "es-def" }, "la cache mezclo packs")
    }

    @Test
    fun theCachedWeekHasADifferentWordPerDay() = runTest {
        // Si el hash ignorara la fecha, el Timeline del tile tendria siete ventanas con la misma
        // palabra y "palabra del dia" seria una palabra a secas.
        val fake = FakeDictionary(packId = "es-def", entryCount = 5000).apply {
            summaries = (1L..5000L).associateWith { EntrySummary(it, "p$it", "noun", 900) }
        }
        var guardadas: List<Visit> = emptyList()
        val vm = SearchViewModel(
            { listos(fake) },
            todayDate = { "2026-09-19" },
            saveWeekWords = { _, words -> guardadas = words },
        )
        advanceUntilIdle()

        assertTrue(
            guardadas.map { it.entryId }.toSet().size > 1,
            "los siete dias eligieron la misma entrada",
        )
    }

    @Test
    fun aCacheFromTodayAndTheSamePackIsNotRecomputed() = runTest {
        // Son 32 lecturas por dia: rehacerlas en cada arranque es trabajo que no cambia nada.
        val fake = FakeDictionary(packId = "es-def", entryCount = 500).apply {
            summaries = (1L..500L).associateWith { EntrySummary(it, "p$it", "noun", 900) }
        }
        var vecesGuardadas = 0
        val vm = SearchViewModel(
            { listos(fake) },
            todayDate = { "2026-09-19" },
            savedWeekWords = {
                "2026-09-19" to listOf(Visit("es-def", 1, "ya-estaba", "noun"))
            },
            saveWeekWords = { _, _ -> vecesGuardadas++ },
        )
        advanceUntilIdle()

        assertEquals(0, vecesGuardadas, "recalculo una cache que ya era de hoy")
    }

    @Test
    fun aCacheFromYesterdayIsRebuilt() = runTest {
        val fake = FakeDictionary(packId = "es-def", entryCount = 500).apply {
            summaries = (1L..500L).associateWith { EntrySummary(it, "p$it", "noun", 900) }
        }
        var vecesGuardadas = 0
        val vm = SearchViewModel(
            { listos(fake) },
            todayDate = { "2026-09-19" },
            savedWeekWords = {
                "2026-09-18" to listOf(Visit("es-def", 1, "de-ayer", "noun"))
            },
            saveWeekWords = { _, _ -> vecesGuardadas++ },
        )
        advanceUntilIdle()

        assertEquals(1, vecesGuardadas, "no rehizo una cache vencida")
    }

    @Test
    fun aCacheFromAnotherDictionaryIsRebuilt() = runTest {
        // Cambiar de idioma tiene que cambiar la palabra del tile: si no, el tile queda mostrando
        // espanol con la app en ingles, y eso no se reporta porque nadie abre un tile a proposito.
        val fake = FakeDictionary(packId = "en-def", entryCount = 500).apply {
            summaries = (1L..500L).associateWith { EntrySummary(it, "p$it", "noun", 900) }
        }
        var guardadas: List<Visit> = emptyList()
        val vm = SearchViewModel(
            { listos(fake) },
            todayDate = { "2026-09-19" },
            savedWeekWords = {
                "2026-09-19" to listOf(Visit("es-def", 1, "de-otro-pack", "noun"))
            },
            saveWeekWords = { _, words -> guardadas = words },
        )
        advanceUntilIdle()

        assertTrue(guardadas.isNotEmpty(), "no rehizo la cache al cambiar de diccionario")
        assertTrue(guardadas.all { it.packId == "en-def" })
    }

    @Test
    fun withNoDateNothingIsCached() = runTest {
        val fake = FakeDictionary(entryCount = 50).apply {
            summaries = (1L..50L).associateWith { EntrySummary(it, "p$it", "noun", 900) }
        }
        var vecesGuardadas = 0
        val vm = SearchViewModel(
            { listos(fake) },
            saveWeekWords = { _, _ -> vecesGuardadas++ },
        )
        advanceUntilIdle()
        assertEquals(0, vecesGuardadas)
    }

    // --- El aviso a los tiles -----------------------------------------------------------------

    @Test
    fun openingAnEntryNotifiesTheHistoryTile() = runTest {
        // El tile de historial no tiene refresco programado: `freshnessIntervalMillis = 0` y el
        // sistema no vuelve a llamarlo. Si la app no lo empuja, se queda con lo de la instalacion.
        val fake = FakeDictionary()
        var avisos = 0
        val vm = SearchViewModel({ listos(fake) }, notifyTiles = { avisos++ })
        advanceUntilIdle()

        vm.recordVisit(Suggestion("es-def", 1, "perro", "noun", MatchKind.PREFIX, 0))
        advanceUntilIdle()

        assertEquals(1, avisos, "abrir una entrada no avisa al tile")
    }

    @Test
    fun savingAWordAlsoNotifiesTheTile() = runTest {
        val fake = FakeDictionary()
        var avisos = 0
        val vm = SearchViewModel({ listos(fake) }, notifyTiles = { avisos++ })
        advanceUntilIdle()

        vm.toggleFavorite(Visit("es-def", 1, "perro", "noun"))
        advanceUntilIdle()

        assertTrue(avisos >= 1, "guardar una palabra no avisa al tile")
    }

    @Test
    fun withNoDateThereIsNoWordOfTheDay() = runTest {
        // El default: si nadie cablea el reloj, la ausencia se ve en pantalla en vez de mostrar
        // una palabra que nunca cambia.
        val fake = FakeDictionary()
        fake.summaries = mapOf(1L to EntrySummary(1, "unica", "noun", 900))
        val vm = SearchViewModel({ listos(fake) })
        advanceUntilIdle()
        assertTrue(vm.state.value.wordsOfTheDay.isEmpty())
    }

    // --- La carrera al abrir el pack (TDD: este fallaba) -------------------------------------

    @Test
    fun whatIsTypedWhileThePackLoadsIsSearchedWhenItFinishes() = runTest {
        // El pack de español pesa 69 MB y tarda en abrir; la pantalla ya acepta texto. Si lo
        // escrito durante ese rato no se vuelve a consultar, la busqueda queda MUERTA: el
        // usuario ve "Sin resultados" para siempre, hasta que borra una letra y la reescribe.
        val diferido = DeferredPack()
        val fake = FakeDictionary()
        val vm = SearchViewModel(diferido::openFile)

        vm.onQueryChange("per")
        advanceUntilIdle()
        assertTrue(fake.queries.isEmpty(), "no hay pack todavia: no deberia haber consultado")

        diferido.padWith(listos(fake))
        advanceUntilIdle()

        assertEquals(listOf("per"), fake.queries, "al abrir el pack tiene que buscar lo escrito")
        assertEquals(listOf("per"), vm.state.value.results.map { it.headword })
    }

    @Test
    fun ifThePackCannotBeOpenedTheScreenSaysSo() = runTest {
        val vm = SearchViewModel({ PackSet.Unusable("el diccionario esta dañado") })
        advanceUntilIdle()
        val status = assertIs<SearchState.Status.Failed>(vm.state.value.status)
        assertEquals("el diccionario esta dañado", status.message)
    }

    @Test
    fun whileThePackIsExtractedTheScreenSaysItIsInstalling() = runTest {
        val diferido = DeferredPack()
        val vm = SearchViewModel(diferido::openFile)
        advanceUntilIdle()
        assertEquals(SearchState.Status.Installing, vm.state.value.status)

        diferido.padWith(listos(FakeDictionary()))
        advanceUntilIdle()
        assertEquals(SearchState.Status.Ready, vm.state.value.status)
    }

    // --- Caracterizacion: el debounce y la cancelacion ---------------------------------------

    @Test
    fun characterization_typingOneWordFiresASingleQuery() = runTest {
        // CARACTERIZACION. Cuatro pulsaciones seguidas mas rapido que el debounce tienen que
        // costar UNA consulta, no cuatro. En un reloj esto es bateria, no milisegundos.
        val fake = FakeDictionary()
        val vm = conPack(fake)
        advanceUntilIdle()

        listOf("p", "pe", "per", "perr").forEach { text ->
            vm.onQueryChange(text)
            advanceTimeBy(SearchViewModel.DEBOUNCE_MS / 2)
        }
        advanceUntilIdle()

        assertEquals(listOf("perr"), fake.queries)
    }

    @Test
    fun characterization_typingSlowlyFiresOneQueryPerWord() = runTest {
        val fake = FakeDictionary()
        val vm = conPack(fake)
        advanceUntilIdle()

        vm.onQueryChange("per")
        advanceTimeBy(SearchViewModel.DEBOUNCE_MS * 2)
        vm.onQueryChange("casa")
        advanceUntilIdle()

        assertEquals(listOf("per", "casa"), fake.queries)
    }

    @Test
    fun characterization_anOldQuery_isCancelled_andDoesNotOverwriteTheNewOne() = runTest {
        // El modo de falla: "per" tarda, el usuario escribe "casa", y "per" termina despues y
        // deja SUS resultados en pantalla. La lista mostraria otra palabra que la escrita.
        val fake = FakeDictionary(demora = 1_000)
        val vm = conPack(fake)
        advanceUntilIdle()

        vm.onQueryChange("per")
        advanceTimeBy(SearchViewModel.DEBOUNCE_MS + 10)
        vm.onQueryChange("casa")
        advanceUntilIdle()

        assertEquals(listOf("per", "casa"), fake.queries, "las dos tienen que haber empezado")
        assertEquals(listOf("per"), fake.canceladas, "la vieja tiene que haberse cancelado")
        assertEquals(listOf("casa"), vm.state.value.results.map { it.headword })
        assertEquals("casa", vm.state.value.query)
    }

    @Test
    fun characterization_theEmptyQueryDoesNotSearchAndClearsTheList() = runTest {
        val fake = FakeDictionary()
        val vm = conPack(fake)
        advanceUntilIdle()

        vm.onQueryChange("per")
        advanceUntilIdle()
        assertTrue(vm.state.value.results.isNotEmpty())

        vm.onQueryChange("")
        advanceUntilIdle()
        assertEquals(listOf("per"), fake.queries, "una query vacia no tiene que ir al pack")
        assertTrue(vm.state.value.results.isEmpty(), "y tiene que limpiar la lista")
    }

    @Test
    fun characterization_theTypedTextShowsBeforeTheDebounce() = runTest {
        // La query es del usuario y se muestra ya; los resultados son del pack y llegan despues.
        // Si el campo esperara al debounce, escribir se sentiria trabado.
        val fake = FakeDictionary()
        val vm = conPack(fake)
        advanceUntilIdle()

        vm.onQueryChange("per")
        assertEquals("per", vm.state.value.query)
        assertTrue(fake.queries.isEmpty(), "todavia no paso el debounce")
    }

    @Test
    fun characterization_closingTheViewModelClosesThePack() = runTest {
        // Un pack sin cerrar deja la conexion de SQLite viva y el archivo mapeado en memoria.
        val fake = FakeDictionary()
        val vm = conPack(fake)
        advanceUntilIdle()

        vm.close()
        assertTrue(fake.cerrado)
    }

    // --- El selector de idioma ---------------------------------------------------------------

    @Test
    fun withTwoPacksItStartsOnTheSavedOne() = runTest {
        // Sin esto, el pack activo lo decidiria el orden alfabetico -- y "en-..." ordena antes
        // que "es-...", asi que el reloj de alguien que solo usa español arrancaria en ingles.
        val es = FakeDictionary("es-def", "es")
        val en = FakeDictionary("en-def", "en")
        val vm = SearchViewModel({ PackSet.Ready(handle(en), listOf(handle(en), handle(es))) },
                                 preferred = { "es-def" })
        advanceUntilIdle()
        assertEquals("es-def", vm.state.value.active?.packId)
    }

    @Test
    fun withNothingSavedTheWatchLocaleDecides() = runTest {
        val es = FakeDictionary("es-def", "es")
        val en = FakeDictionary("en-def", "en")
        val vm = SearchViewModel({ PackSet.Ready(handle(en), listOf(handle(en), handle(es))) },
                                 preferred = { "es" })
        advanceUntilIdle()
        assertEquals("es-def", vm.state.value.active?.packId, "deberia caer al pack de ese idioma")
    }

    @Test
    fun theDemoPackNeverWinsIfThereIsARealDictionary() = runTest {
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
            PackSet.Ready(handle(demo), listOf(handle(demo, isDemo = true), handle(real)))
        }, preferred = { "en" })
        advanceUntilIdle()
        assertEquals("es-def-wikc", vm.state.value.active?.packId)
    }

    @Test
    fun theDemoPackIsNotEvenOfferedIfThereIsARealOne() = runTest {
        // Visto en pantalla: el selector mostraba "ES" y "ES" --el demo y el español real-- y no
        // habia forma de saber cual era cual. Un placeholder no es una opcion: si hay un
        // diccionario, el de juguete no se ofrece, y con un solo pack el selector desaparece.
        val demo = FakeDictionary("toy-es-en", "es")
        val real = FakeDictionary("es-def-wikc", "es")
        val vm = SearchViewModel({
            PackSet.Ready(handle(real), listOf(handle(demo, isDemo = true), handle(real)))
        })
        advanceUntilIdle()
        assertEquals(listOf("es-def-wikc"), vm.state.value.available.map { it.packId })
    }

    @Test
    fun withOnlyTheDemoPackThatOneIsUsed() = runTest {
        // Para eso existe: que la app recien instalada tenga algo que mostrar.
        val demo = FakeDictionary("toy-es-en", "es")
        val vm = SearchViewModel({
            PackSet.Ready(handle(demo, isDemo = true), listOf(handle(demo, isDemo = true)))
        })
        advanceUntilIdle()
        assertEquals("toy-es-en", vm.state.value.active?.packId)
    }

    @Test
    fun switchingLanguageRepeatsTheCurrentSearchInTheNewPack() = runTest {
        // Es el punto del selector: si al cambiar hubiera que reescribir la palabra, en una
        // muñeca nadie lo usaria.
        val es = FakeDictionary("es-def", "es")
        val en = FakeDictionary("en-def", "en")
        val vm = SearchViewModel({ PackSet.Ready(handle(es), listOf(handle(es), handle(en))) })
        advanceUntilIdle()

        vm.onQueryChange("per")
        advanceUntilIdle()
        assertEquals(listOf("per"), es.queries)

        vm.onPackChange("en-def")
        advanceUntilIdle()
        assertEquals(listOf("per"), en.queries, "la query tiene que repetirse en el pack nuevo")
        assertEquals("per", vm.state.value.query)
        assertEquals("en-def", vm.state.value.active?.packId)
    }

    @Test
    fun switchingLanguageIsRemembered() = runTest {
        var recordado: String? = null
        val es = FakeDictionary("es-def", "es")
        val en = FakeDictionary("en-def", "en")
        val vm = SearchViewModel({ PackSet.Ready(handle(es), listOf(handle(es), handle(en))) },
                                 saveActivePack = { recordado = it })
        advanceUntilIdle()
        vm.onPackChange("en-def")
        advanceUntilIdle()
        assertEquals("en-def", recordado)
    }

    @Test
    fun openingAnEntryLooksItUpInItsOwnPack() = runTest {
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
    fun openingAnEntryFromAnUnknownPackReturnsNull() = runTest {
        // Caer al pack activo seria el mismo bug, pero silencioso. Null hace que la pantalla
        // diga que la entrada no esta, que es honesto.
        val vm = conPack(FakeDictionary("es-def", "es"))
        advanceUntilIdle()
        assertEquals(null, vm.entry("no-existe", 7))
    }

    @Test
    fun aBrokenPackDoesNotTakeTheOtherDown() = runTest {
        val es = FakeDictionary("es-def", "es")
        val vm = SearchViewModel({
            PackSet.Ready(handle(es), listOf(handle(es)), problems = listOf("en-def: dañado"))
        })
        advanceUntilIdle()
        assertEquals(SearchState.Status.Ready, vm.state.value.status)
        assertEquals(listOf("en-def: dañado"), vm.state.value.problems)
    }

    @Test
    fun closeClosesEveryPack() = runTest {
        val es = FakeDictionary("es-def", "es")
        val en = FakeDictionary("en-def", "en")
        val vm = SearchViewModel({ PackSet.Ready(handle(es), listOf(handle(es), handle(en))) })
        advanceUntilIdle()
        vm.close()
        assertTrue(es.cerrado && en.cerrado, "un pack sin cerrar deja viva su conexion de SQLite")
    }

    // --- El historial de entradas abiertas -------------------------------------------------------

    private fun suggestion(pack: String, id: Long, lema: String) = Suggestion(
        packId = pack, entryId = id, headword = lema, partOfSpeech = "noun",
        matchKind = MatchKind.PREFIX, score = 0,
    )

    @Test
    fun openingAnEntryLeavesItInTheHistory() {
        val fake = FakeDictionary("es-def", "es")
        val vm = conPack(fake)
        vm.recordVisit(suggestion("es-def", 7, "perro"))
        assertEquals(listOf("perro"), vm.state.value.history.map { it.headword })
    }

    @Test
    fun openingTheSameEntryTwiceDoesNotDuplicateItAndMovesItToTheTop() {
        val vm = conPack(FakeDictionary("es-def", "es"))
        vm.recordVisit(suggestion("es-def", 1, "perro"))
        vm.recordVisit(suggestion("es-def", 2, "casa"))
        vm.recordVisit(suggestion("es-def", 1, "perro"))
        assertEquals(listOf("perro", "casa"), vm.state.value.history.map { it.headword })
    }

    @Test
    fun theHistoryIsTrimmedToItsCap() {
        // El tope no es arbitrario: la pantalla da tres filas de 48 dp (D-073). Guardar mas seria
        // guardar lo que no se ve.
        val vm = conPack(FakeDictionary("es-def", "es"))
        (1..6).forEach { vm.recordVisit(suggestion("es-def", it.toLong(), "lema$it")) }
        assertEquals(SearchViewModel.MAX_HISTORY, vm.state.value.history.size)
        assertEquals("lema6", vm.state.value.history.first().headword)
    }

    @Test
    fun anEntryFromAPackThatIsGoneIsNotShown() = runTest {
        // Se filtra al mostrar, no se poda al guardar: desinstalar y reinstalar un pack es un
        // flujo real, y asi el historial vuelve solo. Una fila que al tocarla no abre nada es
        // peor que no tener la fila.
        val es = FakeDictionary("es-def", "es")
        val vm = SearchViewModel(
            { PackSet.Ready(handle(es), listOf(handle(es))) },
            savedHistory = {
                listOf(
                    Visit("es-def", 1, "perro", "noun"),
                    Visit("de-def", 2, "Hund", "noun"),
                )
            },
        )
        advanceUntilIdle()
        assertEquals(listOf("perro"), vm.state.value.history.map { it.headword })
    }

    @Test
    fun theHistoryIsPersisted() {
        var guardado: List<Visit> = emptyList()
        val vm = SearchViewModel({ listos(FakeDictionary("es-def", "es")) },
                                 saveHistory = { guardado = it })
        vm.recordVisit(suggestion("es-def", 7, "perro"))
        assertEquals(listOf("perro"), guardado.map { it.headword })
    }

    // --- Buscar en las definiciones -----------------------------------------------------------

    @Test
    fun searchingDefinitionsQueriesTheActivePackWithTheCurrentQuery() = runTest {
        val fake = FakeDictionary("es-def", "es")
        val vm = conPack(fake)
        advanceUntilIdle()
        vm.onQueryChange("animal que ladra")
        advanceUntilIdle()

        vm.onSearchDefinitions()
        advanceUntilIdle()

        assertEquals(listOf("animal que ladra"), fake.definitionMode)
        assertEquals(SearchState.Mode.DEFINICIONES, vm.state.value.mode)
        assertEquals(listOf("def:animal que ladra"), vm.state.value.results.map { it.headword })
    }

    @Test
    fun theDefinitionSearchDoesNotFireWhileTyping() = runTest {
        // Es el contrato de la interfaz vuelto test: recorre un indice mucho mayor que el de
        // lemas y no cumple el presupuesto de latencia de la busqueda incremental.
        val fake = FakeDictionary("es-def", "es")
        val vm = conPack(fake)
        advanceUntilIdle()

        vm.onQueryChange("animal")
        advanceUntilIdle()

        assertTrue(fake.definitionMode.isEmpty(), "escribir no puede tocar el indice de texto libre")
    }

    @Test
    fun typingAfterADefinitionSearchReturnsToTheNormalSearch() = runTest {
        // Volver no puede costar un boton: en 192 dp cada control se paga en resultados.
        val fake = FakeDictionary("es-def", "es")
        val vm = conPack(fake)
        advanceUntilIdle()
        vm.onQueryChange("animal")
        advanceUntilIdle()
        vm.onSearchDefinitions()
        advanceUntilIdle()
        assertEquals(SearchState.Mode.DEFINICIONES, vm.state.value.mode)

        vm.onQueryChange("animales")
        advanceUntilIdle()

        assertEquals(SearchState.Mode.NORMAL, vm.state.value.mode)
        assertEquals(listOf("animales"), vm.state.value.results.map { it.headword })
    }

    @Test
    fun anOldDefinitionSearchDoesNotOverwriteWhatWasTypedAfterIt() = runTest {
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

        assertEquals(SearchState.Mode.NORMAL, vm.state.value.mode)
        assertEquals(listOf("casa"), vm.state.value.results.map { it.headword })
    }

    @Test
    fun whileSearchingDefinitionsTheStateSaysSo() = runTest {
        val fake = FakeDictionary("es-def", "es", demora = 1_000)
        val vm = conPack(fake)
        advanceUntilIdle()
        vm.onQueryChange("animal")
        advanceUntilIdle()

        vm.onSearchDefinitions()
        advanceTimeBy(10)

        assertEquals(SearchState.Mode.BUSCANDO_DEFINICIONES, vm.state.value.mode)
    }

    @Test
    fun switchingLanguageLeavesDefinitionMode() = runTest {
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

        assertEquals(SearchState.Mode.NORMAL, vm.state.value.mode)
        assertEquals(listOf("animal"), en.queries, "el pack nuevo recibe la query por prefijo")
    }

    @Test
    fun attributionAndLicenseComeFromThePackAndNotFromTheCode() = runTest {
        // D-031: mostrarlas es la condicion de uso de los datos. Si vinieran de una constante,
        // un pack de otra fuente mostraria la licencia equivocada.
        val fake = FakeDictionary()
        val vm = conPack(fake)
        advanceUntilIdle()

        assertEquals(fake.metadata.attribution, vm.state.value.active?.attribution)
        assertEquals(fake.metadata.license, vm.state.value.active?.license)
        assertEquals(fake.metadata.name, vm.state.value.active?.name)
    }
}
