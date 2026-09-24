package cl.fadiaz.dictionary.presentation

import cl.fadiaz.dictionary.core.PackRejection
import cl.fadiaz.dictionary.core.PackTier
import cl.fadiaz.dictionary.core.PackKind
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import cl.fadiaz.dictionary.data.DownloadPhase
import cl.fadiaz.dictionary.data.PackDownload
import cl.fadiaz.dictionary.data.CatalogFetch
import cl.fadiaz.dictionary.data.CatalogPack
import cl.fadiaz.dictionary.data.CatalogState
import cl.fadiaz.dictionary.data.CatalogStatus
import cl.fadiaz.dictionary.data.PackHandle
import cl.fadiaz.dictionary.data.Visit
import cl.fadiaz.dictionary.tile.TileContents

/**
 * The search's concurrency, which is where a bug gives NO error.
 *
 * The three failure modes these tests cover all look the same from outside --a list of results--
 * and none of them throws anything: results from an earlier query overwriting the current one, a
 * query per keystroke draining the battery, and the search left dead until the user deletes and
 * types again.
 *
 * TDD: the one about the race when opening the pack was written **before** the fix and failed.
 * The rest are CHARACTERIZATION tests -- the behaviour already existed and this pins it down.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private fun handle(d: FakeDictionary, isBundled: Boolean = false) =
        PackHandle.Open(d, isBundled)

    @BeforeTest
    fun before() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun after() = Dispatchers.resetMain()

    private fun conPack(source: FakeDictionary) = SearchViewModel({ listos(source) })

    // --- El catalogo de descarga (D-213) ---------------------------------------------------

    private fun ofrecido(id: String, version: Long, schema: Int = 4) = CatalogPack(
        packId = id, name = id, description = null, langs = listOf("es"), entryCount = 1,
        dataVersion = version, schemaVersion = schema, normVersion = 2, license = null,
        url = "packs/$id.db.gz", bytes = 1, sha256 = "a", dbBytes = 2, dbSha256 = "b",
    )

    @Test
    fun elCatalogoEmpiezaEnIdleYNadieLoConsultaSolo() = runTest {
        var consultas = 0
        val vm = SearchViewModel(
            { listos(FakeDictionary("es-def")) },
            fetchCatalog = { consultas++; CatalogFetch.NotModified },
        )
        advanceUntilIdle()
        assertEquals(CatalogState.Idle, vm.state.value.catalog)
        assertEquals(0, consultas, "arrancar la app no puede consultar el catalogo")
    }

    @Test
    fun una_descarga_VIEJA_de_WorkManager_no_publica_un_catalogo_vacio() = runTest {
        // ⚠️ Seen on the emulator on 2026-09-22. WorkManager **keeps** a finished job's `WorkInfo`,
        // so on starting the app a DONE from the previous session arrives. The collector read it
        // as "a download has just finished" and republished the catalog -- which at that moment is
        // EMPTY, because nobody has queried it yet. Result: the screen said "Nothing new. What is
        // installed is up to date" without anybody ever having asked.
        val vm = SearchViewModel(
            { listos(FakeDictionary("es-def")) },
            downloadStates = flowOf(
                listOf(PackDownload("lo-de-ayer", DownloadPhase.DONE)),
            ),
        )
        advanceUntilIdle()
        assertEquals(
            CatalogState.Idle,
            vm.state.value.catalog,
            "sin haber consultado el catalogo, el estado tiene que seguir en Idle",
        )
    }

    @Test
    fun una_descarga_TERMINADA_de_otra_sesion_no_se_muestra_como_en_curso() = runTest {
        // ⚠️ Seen on the emulator on 2026-09-22, and it is worse than the republishing.
        // WorkManager keeps the SUCCEEDED job, so on starting, that pack's row showed "Installed"
        // **and stopped being tappable** -- even if the pack had been deleted and the catalog was
        // offering it for download. There was no way to get it again.
        //
        // An already finished job is history, not a download in flight.
        val vm = SearchViewModel(
            { listos(FakeDictionary("es-def")) },
            downloadStates = flowOf(listOf(PackDownload("lo-de-ayer", DownloadPhase.DONE))),
        )
        advanceUntilIdle()
        assertTrue(
            vm.state.value.downloads.isEmpty(),
            "un trabajo terminado en otra sesion no es una descarga en curso: ${vm.state.value.downloads}",
        )
    }

    @Test
    fun volver_a_bajar_un_pack_que_YA_se_habia_bajado_antes_si_cuenta() = runTest {
        // ⚠️ The fix's own defect, seen on the emulator on 2026-09-22. On hiding WorkManager's
        // history, a pack already downloaded in another session stayed marked forever: its NEW
        // download was hidden too on finishing, so the packs were not reloaded and nothing was
        // reclassified. The pack sat on disk with the app not seeing it.
        //
        // Leaving the history has to happen the moment the pack moves again.
        val descargas = MutableStateFlow(listOf(PackDownload("repetido", DownloadPhase.DONE)))
        // ⚠️ What really matters is that the packs get RELOADED: without that the pack sits on
        // disk and the app does not see it until the next launch. The phase on screen is the
        // symptom.
        var escaneos = 0
        val vm = SearchViewModel(
            { escaneos++; listos(FakeDictionary("es-def", dataVersion = 200L)) },
            fetchCatalog = { CatalogFetch.Fresh(listOf(ofrecido("repetido", 100L)), null) },
            downloadStates = descargas,
        )
        advanceUntilIdle()
        assertTrue(vm.state.value.downloads.isEmpty(), "la historia empieza escondida")
        vm.onCheckCatalog(); advanceUntilIdle()

        // Se vuelve a pedir: WorkManager lo pone en marcha y despues lo termina.
        descargas.value = listOf(PackDownload("repetido", DownloadPhase.RUNNING))
        advanceUntilIdle()
        assertEquals(
            DownloadPhase.RUNNING,
            vm.state.value.downloads["repetido"]?.phase,
            "en marcha tiene que verse",
        )
        val antes = escaneos
        descargas.value = listOf(PackDownload("repetido", DownloadPhase.DONE))
        advanceUntilIdle()
        assertEquals(
            DownloadPhase.DONE,
            vm.state.value.downloads["repetido"]?.phase,
            "la SEGUNDA descarga del mismo pack tiene que contar como terminada",
        )
        assertEquals(
            antes + 1,
            escaneos,
            "terminar la segunda descarga tiene que RECARGAR los packs",
        )
    }

    @Test
    fun una_descarga_que_termina_SIN_haber_consultado_tampoco_publica_nada() = runTest {
        // ⚠️ The second guard, and it is needed separately: the first only covers what WorkManager
        // drags in from startup. This is the live case -- a download queued in an earlier session
        // that finishes NOW, with the user looking at the screen without having queried. Without
        // the guard, finishing publishes an empty list and the screen says "nothing new".
        val descargas = MutableStateFlow(emptyList<PackDownload>())
        val vm = SearchViewModel({ listos(FakeDictionary("es-def")) }, downloadStates = descargas)
        advanceUntilIdle()
        assertEquals(CatalogState.Idle, vm.state.value.catalog)

        descargas.value = listOf(PackDownload("recien", DownloadPhase.DONE))
        advanceUntilIdle()
        assertEquals(
            CatalogState.Idle,
            vm.state.value.catalog,
            "terminar una descarga no puede inventar un catalogo que nadie pidio",
        )
    }

    @Test
    fun una_descarga_que_termina_AHORA_si_reclasifica() = runTest {
        // The other half: what DOES have to happen when a download really finishes.
        val descargas = MutableStateFlow(emptyList<PackDownload>())
        val vm = SearchViewModel(
            { listos(FakeDictionary("es-def", dataVersion = 200L)) },
            fetchCatalog = { CatalogFetch.Fresh(listOf(ofrecido("otro", 100L)), null) },
            downloadStates = descargas,
        )
        advanceUntilIdle()
        vm.onCheckCatalog(); advanceUntilIdle()
        assertEquals(
            CatalogStatus.DOWNLOAD,
            (vm.state.value.catalog as CatalogState.Ready).offers.single().status,
        )
        descargas.value = listOf(PackDownload("otro", DownloadPhase.DONE))
        advanceUntilIdle()
        // Sigue habiendo catalogo: se reclasifico, no se borro.
        assertEquals(1, (vm.state.value.catalog as CatalogState.Ready).offers.size)
    }

    @Test
    fun consultarClasificaContraLoInstalado() = runTest {
        val vm = SearchViewModel(
            { listos(FakeDictionary("es-def", dataVersion = 200L)) },
            fetchCatalog = {
                CatalogFetch.Fresh(listOf(ofrecido("es-def", 300L), ofrecido("otro", 100L)), "\"e1\"")
            },
        )
        advanceUntilIdle()
        vm.onCheckCatalog()
        advanceUntilIdle()
        val listo = vm.state.value.catalog as CatalogState.Ready
        assertEquals(
            mapOf("es-def" to CatalogStatus.UPDATE, "otro" to CatalogStatus.DOWNLOAD),
            listo.offers.associate { it.pack.packId to it.status },
        )
    }

    @Test
    fun unaSegundaConsultaMANDAelEtagDeLaPrimera() = runTest {
        val etags = mutableListOf<String?>()
        val vm = SearchViewModel(
            { listos(FakeDictionary("es-def", dataVersion = 200L)) },
            fetchCatalog = { etag ->
                etags += etag
                if (etag == null) {
                    CatalogFetch.Fresh(listOf(ofrecido("es-def", 300L)), "\"e1\"")
                } else {
                    CatalogFetch.NotModified
                }
            },
        )
        advanceUntilIdle()
        vm.onCheckCatalog(); advanceUntilIdle()
        vm.onCheckCatalog(); advanceUntilIdle()
        assertEquals(listOf(null, "\"e1\""), etags, "la segunda consulta tiene que llevar el ETag")
    }

    @Test
    fun un304VUELVEaClasificar_porque_lo_instalado_pudo_cambiar() = runTest {
        // ⚠️ The assertion that pays for this section. The catalog did not change --304-- but the
        // user deleted the dictionary between the two queries, so what was UPDATE is now DOWNLOAD.
        // Reusing the previous result as it stands would show "update" for a pack that is no
        // longer on the watch.
        val pack = FakeDictionary("es-def", dataVersion = 200L)
        val otro = FakeDictionary("se-queda", dataVersion = 5L)
        var borrado = false
        val vm = SearchViewModel(
            // The double has to SHRINK on deletion: always returning the same pack would make it
            // reappear, and the test would prove nothing.
            { if (borrado) listos(otro) else listos(otro, pack) },
            fetchCatalog = { etag ->
                if (etag == null) {
                    CatalogFetch.Fresh(listOf(ofrecido("es-def", 300L)), "\"e1\"")
                } else {
                    CatalogFetch.NotModified
                }
            },
            deleteFromDisk = { borrado = true; true },
        )
        advanceUntilIdle()
        vm.onCheckCatalog(); advanceUntilIdle()
        assertEquals(
            CatalogStatus.UPDATE,
            (vm.state.value.catalog as CatalogState.Ready).offers.single().status,
        )

        // ⚠️ `deletePack` recibe un packId, no un nombre de archivo.
        vm.deletePack("es-def")
        advanceUntilIdle()
        vm.onCheckCatalog(); advanceUntilIdle()
        assertEquals(
            CatalogStatus.DOWNLOAD,
            (vm.state.value.catalog as CatalogState.Ready).offers.single().status,
            "tras borrarlo, el mismo catalogo tiene que ofrecerlo para DESCARGAR",
        )
    }

    @Test
    fun unFalloDeRedNoTumbaElEstado() = runTest {
        val vm = SearchViewModel(
            { listos(FakeDictionary("es-def")) },
            fetchCatalog = { CatalogFetch.Failed("Connection refused") },
        )
        advanceUntilIdle()
        vm.onCheckCatalog(); advanceUntilIdle()
        assertEquals(CatalogState.Failed("Connection refused"), vm.state.value.catalog)
    }

    // --- Several packs of the same language, coexisting (D-136) ----------------------------

    @Test
    fun dosPacksDelMISMOIdiomaSeConsultanLosDos() = runTest {
        // What coexistence asks for: two sources of the same language installed at once, and the
        // gain is the UNION of their lemmas. If only the active one were queried, the second pack
        // would be dead weight until somebody chose it by hand.
        val wikc = FakeDictionary(packId = "es-def-wikc", lang = "es")
        val otra = FakeDictionary(packId = "es-def-otra", lang = "es")
        val vm = SearchViewModel({ listos(wikc, otra) })
        advanceUntilIdle()

        vm.onQueryChange("casa")
        advanceUntilIdle()

        assertTrue(wikc.queries.isNotEmpty(), "no se consulto el pack activo")
        assertTrue(otra.queries.isNotEmpty(), "no se consulto el segundo pack del mismo idioma")
    }

    @Test
    fun unPackDeOTROIdiomaNoSeConsulta() = runTest {
        // ⚠️ The coexistence is WITHIN a language. With Spanish and English installed, typing
        // "casa" cannot return English entries: the selector still chooses which language is
        // searched (D-078), and what changed is that it now chooses a LANGUAGE and not a file.
        val es = FakeDictionary(packId = "es-def", lang = "es")
        val en = FakeDictionary(packId = "en-def", lang = "en")
        val vm = SearchViewModel({ listos(es, en) })
        advanceUntilIdle()

        vm.onQueryChange("casa")
        advanceUntilIdle()

        assertTrue(es.queries.isNotEmpty(), "no se consulto el pack del idioma activo")
        assertTrue(en.queries.isEmpty(), "se consulto un pack de otro idioma: ${en.queries}")
    }

    @Test
    fun cambiarDeIdiomaCambiaElConjuntoQueSeConsulta() = runTest {
        val es = FakeDictionary(packId = "es-def", lang = "es")
        val en1 = FakeDictionary(packId = "en-def-wikt", lang = "en")
        val en2 = FakeDictionary(packId = "en-def-otra", lang = "en")
        val vm = SearchViewModel({ listos(es, en1, en2) })
        advanceUntilIdle()

        vm.onLanguageChange("en")
        vm.onQueryChange("house")
        advanceUntilIdle()

        assertTrue(en1.queries.isNotEmpty(), "no se consulto el pack elegido")
        assertTrue(en2.queries.isNotEmpty(), "no se consulto el otro pack del mismo idioma")
    }

    // --- Volver a la pantalla de inicio (D-143) ---------------------------------------------

    @Test
    fun clearQueryDejaLaBarraVaciaYElInicioComoEstaba() = runTest {
        // What the magnifier button asks for: searching again **with the word erased**, not with
        // whatever was typed. And it is the same thing the back gesture with text has to do.
        val vm = conPack(FakeDictionary())
        advanceUntilIdle()
        vm.onQueryChange("casa")
        advanceUntilIdle()
        assertEquals("casa", vm.state.value.query)
        assertTrue(vm.state.value.results.isNotEmpty())

        vm.clearQuery()
        advanceUntilIdle()

        assertEquals("", vm.state.value.query)
        assertEquals("", vm.state.value.submitted)
        assertTrue(vm.state.value.results.isEmpty(), "el inicio no puede quedar con resultados")
    }

    @Test
    fun clearQuerySaleDelModoDefiniciones() = runTest {
        // Otherwise, returning to the magnifier from a definition search would leave the screen in
        // a mode that no longer corresponds to what the bar shows.
        val vm = conPack(FakeDictionary())
        advanceUntilIdle()
        vm.onQueryChange("mover")
        advanceUntilIdle()
        vm.onSearchDefinitions()
        advanceUntilIdle()
        assertEquals(SearchState.Mode.DEFINICIONES, vm.state.value.mode)

        vm.clearQuery()
        advanceUntilIdle()

        assertEquals(SearchState.Mode.NORMAL, vm.state.value.mode)
    }

    // --- Deleting a dictionary ----------------------------------------------------------------

    @Test
    fun theConnectionClosesBEFORETheFileIsDeleted() = runTest {
        // This is THE rule of this function, and it is not theoretical: on Unix a deleted file
        // with an open descriptor keeps occupying the disk until it is closed, and the app would
        // go on reading it as if nothing happened. The user would see "deleted" and zero space
        // freed.
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
        // Otherwise the app is left searching a pack that no longer exists.
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
    fun `se pueden borrar TODOS los descargados y quedarse solo con los nucleos`() = runTest {
        // ⚠️ **An explicit request, and the test exists because the premise has to be sustained,
        // not supposed**: *"that all downloaded packs can be deleted and none be left, because in
        // theory the core packs are always available"*.
        //
        // What gets checked is that **there is no floor**: no "you cannot delete the last one",
        // and no pack refusing because it is the active one. Both full packs are deleted, one
        // after the other, and what is left is exactly the APK's core -- which goes on answering,
        // so the app does not fall into `NoPack`.
        val esCore = FakeDictionary(packId = "es-core")
        val esFull = FakeDictionary(packId = "es-def")
        val enFull = FakeDictionary(packId = "en-def")
        var quedan = listOf(esCore, esFull, enFull)
        val vm = SearchViewModel(
            { listos(*quedan.toTypedArray(), demos = setOf("es-core")) },
            deleteFromDisk = { fileName ->
                quedan = quedan.filterNot { it.metadata.packId + ".db" == fileName }
                true
            },
        )
        advanceUntilIdle()
        // `es-core` is not offered: full Spanish already speaks that language. Both full ones are.
        assertEquals(listOf("es-def", "en-def"), vm.state.value.available.map { it.packId })

        vm.deletePack("es-def")
        advanceUntilIdle()
        vm.deletePack("en-def")
        advanceUntilIdle()

        assertEquals(
            listOf("es-core"), vm.state.value.available.map { it.packId },
            "borrar todos los descargados tiene que dejar el nucleo, no un error",
        )
        // And there is still something to search with: the core stays ACTIVE. Without this the
        // test would pass just the same with an app that deleted everything and was left with no
        // active dictionary.
        assertEquals("es-core", vm.state.value.active?.packId)
    }

    @Test
    fun `el nucleo de otro idioma SOBREVIVE a tener un diccionario completo instalado`() = runTest {
        // ⚠️ **The defect this test found, and it is the exact shape of the bug the repo cannot
        // see.** D-088's rule was *"if any pack is installed, hide all of the APK's"*, written
        // when what shipped was a 28-entry toy whose tag collided with the real pack's. D-175 put
        // the **real cores** there and nobody looked at the rule again.
        //
        // With full Spanish downloaded, the filter took **both** cores away: `en-core` stayed
        // installed, open and queryable, and **with no language chip**. English disappeared
        // entirely from the interface with no error, no log and nothing failing.
        val esCore = FakeDictionary(packId = "es-core", lang = "es")
        val enCore = FakeDictionary(packId = "en-core", lang = "en")
        val esFull = FakeDictionary(packId = "es-def", lang = "es")
        val vm = SearchViewModel(
            { listos(esFull, esCore, enCore, demos = setOf("es-core", "en-core")) },
        )
        advanceUntilIdle()

        val ofrecidos = vm.state.value.available.map { it.packId }
        assertTrue(
            "en-core" in ofrecidos,
            "el nucleo de ingles desaparecio y no hay otro pack que hable ingles: $ofrecidos",
        )
        // And the half D-088 wanted still holds: the Spanish core DOES step aside, because the
        // full one already speaks that language. Without this the fix would be "show everything".
        assertTrue("es-core" !in ofrecidos, "el nucleo de espanol sobraba: $ofrecidos")
        // What the user sees: two choosable languages, not one.
        assertEquals(listOf("en", "es"), idiomasDisponibles(vm.state.value.available))
    }

    @Test
    fun theDemoPackCannotBeDeleted() = runTest {
        // It comes inside the APK and `PackStore.open` re-extracts it on reopening, so deleting
        // it would be an action that does nothing: the pack comes back on its own. Offering it
        // would be a lie.
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

    // --- The saved words ----------------------------------------------------------------------

    private fun visit(headword: String, id: Long = 1, pack: String = "es-def") =
        Visit(packId = pack, entryId = id, headword = headword, partOfSpeech = "noun")

    @Test
    fun savingAWordLeavesItInTheList() = runTest {
        val savedWords = mutableListOf<List<Visit>>()
        val vm = SearchViewModel({ listos(FakeDictionary()) }, saveFavorites = { savedWords += it })
        advanceUntilIdle()

        vm.toggleFavorite(visit("perro"))

        assertEquals(listOf("perro"), vm.state.value.favorites.map { it.headword })
        assertTrue(vm.isFavorite("es-def", 1))
        assertEquals(1, savedWords.size, "tiene que persistirse, no solo quedar en memoria")
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
        // entryIds are rowids: 1 exists in EVERY pack. Without looking at the packId, saving
        // "perro" would also mark entry 1 of the English dictionary as saved.
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
        // It ends up in a SharedPreferences String: with no cap it grows without bound.
        val vm = SearchViewModel({ listos(FakeDictionary()) })
        advanceUntilIdle()
        repeat(SearchViewModel.MAX_FAVORITES + 10) { i -> vm.toggleFavorite(visit("p$i", i.toLong())) }
        assertEquals(SearchViewModel.MAX_FAVORITES, vm.state.value.favorites.size)
        // The most recently saved goes first: it is the one you are most likely to revisit.
        assertEquals("p${SearchViewModel.MAX_FAVORITES + 9}", vm.state.value.favorites.first().headword)
    }

    // --- The word of the day ------------------------------------------------------------------

    @Test
    fun openingThePackPublishesTheWordOfTheDay() = runTest {
        // A correct policy is not enough: it has to reach the state. This was written because
        // the first version compiled, passed its tests and **showed nothing** on the watch, and
        // a screenshot does not say why.
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
        // With two languages installed both words are of interest, and switching language
        // cannot require recomputing anything.
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
        // Same date, same data, different packId: if the seed ignored the pack, both
        // dictionaries would show the entry with the same id, which in each is another word.
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

    // --- The cache that feeds the tiles --------------------------------------------------

    @Test
    fun openingThePacksCachesTheWeekOfWordsForTheTile() = runTest {
        // The tile does NOT open the pack --onTileRequest runs on the main thread with a 10 s
        // cap-- so if the app does not leave the week written, the tile has nothing to show.
        val fake = FakeDictionary(packId = "es-def", entryCount = 500).apply {
            summaries = (1L..500L).associateWith { EntrySummary(it, "p$it", "noun", 900) }
        }
        var savedWords: Pair<String, List<Visit>>? = null
        val vm = SearchViewModel(
            { listos(fake) },
            todayDate = { "2026-09-19" },
            saveWeekWords = { since, words -> savedWords = since to words },
        )
        advanceUntilIdle()

        val cached = savedWords
        assertTrue(cached != null, "no se cacheo ninguna palabra para el tile")
        assertEquals("2026-09-19", cached.first)
        assertEquals(TileContents.CACHED_DAYS, cached.second.size)
        assertTrue(cached.second.all { it.packId == "es-def" }, "la cache mezclo packs")
    }

    @Test
    fun theCachedWeekHasADifferentWordPerDay() = runTest {
        // If the hash ignored the date, the tile's Timeline would have seven windows with the
        // same word and "word of the day" would just be a word.
        val fake = FakeDictionary(packId = "es-def", entryCount = 5000).apply {
            summaries = (1L..5000L).associateWith { EntrySummary(it, "p$it", "noun", 900) }
        }
        var savedWords: List<Visit> = emptyList()
        val vm = SearchViewModel(
            { listos(fake) },
            todayDate = { "2026-09-19" },
            saveWeekWords = { _, words -> savedWords = words },
        )
        advanceUntilIdle()

        assertTrue(
            savedWords.map { it.entryId }.toSet().size > 1,
            "los siete dias eligieron la misma entrada",
        )
    }

    @Test
    fun aCacheFromTodayAndTheSamePackIsNotRecomputed() = runTest {
        // It is 32 reads per day: redoing them on every launch is work that changes nothing.
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
        // Switching language has to switch the tile's word: otherwise the tile is left showing
        // Spanish with the app in English, and that goes unreported because nobody opens a tile
        // on purpose.
        val fake = FakeDictionary(packId = "en-def", entryCount = 500).apply {
            summaries = (1L..500L).associateWith { EntrySummary(it, "p$it", "noun", 900) }
        }
        var savedWords: List<Visit> = emptyList()
        val vm = SearchViewModel(
            { listos(fake) },
            todayDate = { "2026-09-19" },
            savedWeekWords = {
                "2026-09-19" to listOf(Visit("es-def", 1, "de-otro-pack", "noun"))
            },
            saveWeekWords = { _, words -> savedWords = words },
        )
        advanceUntilIdle()

        assertTrue(savedWords.isNotEmpty(), "no rehizo la cache al cambiar de diccionario")
        assertTrue(savedWords.all { it.packId == "en-def" })
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

    // --- Notifying the tiles ------------------------------------------------------------------

    @Test
    fun openingAnEntryNotifiesTheHistoryTile() = runTest {
        // The history tile has no scheduled refresh: `freshnessIntervalMillis = 0` and the
        // system never calls it again. If the app does not push it, it keeps what it had at
        // install time.
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
        // The default: if nobody wires the clock, the absence shows on screen instead of a word
        // that never changes.
        val fake = FakeDictionary()
        fake.summaries = mapOf(1L to EntrySummary(1, "unica", "noun", 900))
        val vm = SearchViewModel({ listos(fake) })
        advanceUntilIdle()
        assertTrue(vm.state.value.wordsOfTheDay.isEmpty())
    }

    // --- The race when opening the pack (TDD: this one failed) -------------------------------

    @Test
    fun whatIsTypedWhileThePackLoadsIsSearchedWhenItFinishes() = runTest {
        // The Spanish pack weighs 69 MB and takes a while to open; the screen already accepts
        // text. If what is typed during that window is never queried again, the search is left
        // DEAD: the user sees "No results" forever, until they delete a letter and retype it.
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

    // --- The history records every way of opening a word --------------------------------------

    @Test
    fun openingByIdAlsoLandsInTheHistory() = runTest {
        // Reported: "el historial a veces no se actualiza". The "sometimes" is the whole clue --
        // `recordVisit` was only called from a SEARCH RESULT. Opening the word of the day, a
        // recent entry, or a word tapped inside a gloss navigated without recording anything.
        val fake = FakeDictionary()
        fake.summaries = mapOf(7L to EntrySummary(7L, "permanecer", "verb", 100))
        val vm = conPack(fake)
        advanceUntilIdle()

        vm.recordVisit("fake", 7L)
        advanceUntilIdle()

        assertEquals(listOf("permanecer"), vm.state.value.history.map { it.headword })
    }

    @Test
    fun openingByIdMovesItToTheFront() = runTest {
        // Same move-to-front as a search result: opening it again raises it, not duplicates it.
        val fake = FakeDictionary()
        fake.summaries = mapOf(
            7L to EntrySummary(7L, "permanecer", "verb", 100),
            9L to EntrySummary(9L, "correr", "verb", 200),
        )
        val vm = conPack(fake)
        advanceUntilIdle()

        vm.recordVisit("fake", 7L)
        vm.recordVisit("fake", 9L)
        vm.recordVisit("fake", 7L)
        advanceUntilIdle()

        assertEquals(listOf("permanecer", "correr"), vm.state.value.history.map { it.headword })
    }

    @Test
    fun openingByIdFromAPackThatIsNotOpenRecordsNothing() = runTest {
        // Same rule as `entry`: it does not fall back to the active pack, because falling back
        // is the D-080 bug -- it would record the wrong word, with the right headword.
        val fake = FakeDictionary()
        fake.summaries = mapOf(7L to EntrySummary(7L, "permanecer", "verb", 100))
        val vm = conPack(fake)
        advanceUntilIdle()

        vm.recordVisit("otro-pack", 7L)
        advanceUntilIdle()

        assertEquals(emptyList(), vm.state.value.history)
    }

    // --- The keyboard: while it is open, nothing is searched ---------------------------------

    @Test
    fun withTheKeyboardOpenTypingSearchesNothing() = runTest {
        // Reported from the watch: typing closed the keyboard. The cause is not the field, it is
        // the LIST: with an empty query the home shows heading, voice, word of the day and
        // history; with one letter all of that disappears and results take over. That
        // restructuring destroys and recomposes the field, and the focus --and the keyboard--
        // go with it. Deleting the last letter does the same in reverse.
        //
        // While the keyboard covers the screen the list is not visible anyway, so searching
        // there is work nobody sees that costs the only thing that matters.
        val fake = FakeDictionary()
        val vm = conPack(fake)
        advanceUntilIdle()

        vm.onTypingChanged(true)
        listOf("p", "pe", "per", "perro").forEach { text ->
            vm.onQueryChange(text)
            advanceTimeBy(SearchViewModel.DEBOUNCE_MS * 2)
        }
        advanceUntilIdle()

        assertEquals(emptyList<String>(), fake.queries, "no se busco nada con el teclado abierto")
        assertEquals("perro", vm.state.value.query, "pero el campo SI muestra lo escrito")
    }

    @Test
    fun theListDoesNotRestructureWhileTyping() = runTest {
        // `submitted` is what the list reflects. If it moved with every keystroke, the home
        // would collapse on the first letter -- which is the bug.
        val fake = FakeDictionary()
        val vm = conPack(fake)
        advanceUntilIdle()

        vm.onTypingChanged(true)
        vm.onQueryChange("perro")
        advanceTimeBy(SearchViewModel.DEBOUNCE_MS * 2)
        advanceUntilIdle()

        assertEquals("", vm.state.value.submitted, "la lista sigue mostrando el inicio")
    }

    @Test
    fun closingTheKeyboardSearchesWhatWasLeft() = runTest {
        val fake = FakeDictionary()
        val vm = conPack(fake)
        advanceUntilIdle()

        vm.onTypingChanged(true)
        listOf("p", "pe", "per", "perro").forEach { vm.onQueryChange(it) }
        vm.onTypingChanged(false)
        advanceUntilIdle()

        assertEquals(listOf("perro"), fake.queries, "una sola consulta, con el texto final")
        assertEquals("perro", vm.state.value.submitted)
    }

    @Test
    fun deletingTheLastLetterWithTheKeyboardOpenDoesNotBringTheHomeBack() = runTest {
        // The other half of the report: "se sale cada vez que uno borra una letra". Going from
        // one character to zero restructures the list back to the home, with the same effect.
        val fake = FakeDictionary()
        val vm = conPack(fake)
        advanceUntilIdle()

        vm.onTypingChanged(true)
        vm.onQueryChange("p")
        vm.onTypingChanged(false)
        advanceUntilIdle()
        assertEquals("p", vm.state.value.submitted)

        vm.onTypingChanged(true)
        vm.onQueryChange("")
        advanceTimeBy(SearchViewModel.DEBOUNCE_MS * 2)
        advanceUntilIdle()

        assertEquals("p", vm.state.value.submitted, "la lista no volvio al inicio mientras se escribe")
        assertEquals("", vm.state.value.query)
    }

    @Test
    fun withoutTheKeyboardTheSearchIsStillIncremental() = runTest {
        // Voice delivers the whole phrase at once and never opens the keyboard: that path has to
        // keep firing on its own, with no commit step.
        val fake = FakeDictionary()
        val vm = conPack(fake)
        advanceUntilIdle()

        vm.onQueryChange("perro")
        advanceUntilIdle()

        assertEquals(listOf("perro"), fake.queries)
        assertEquals("perro", vm.state.value.submitted)
    }

    // --- Characterization: the debounce and the cancellation ---------------------------------

    @Test
    fun characterization_typingOneWordFiresASingleQuery() = runTest {
        // CHARACTERIZATION. Four keystrokes in a row faster than the debounce have to cost ONE
        // query, not four. On a watch this is battery, not milliseconds.
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
        // The failure mode: "per" takes a while, the user types "casa", and "per" finishes
        // afterwards and leaves ITS results on screen. The list would show a word other than the
        // one typed.
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
        // The query belongs to the user and shows immediately; the results belong to the pack
        // and arrive later. If the field waited for the debounce, typing would feel stuck.
        val fake = FakeDictionary()
        val vm = conPack(fake)
        advanceUntilIdle()

        vm.onQueryChange("per")
        assertEquals("per", vm.state.value.query)
        assertTrue(fake.queries.isEmpty(), "todavia no paso el debounce")
    }

    @Test
    fun characterization_closingTheViewModelClosesThePack() = runTest {
        // An unclosed pack leaves the SQLite connection alive and the file mapped in memory.
        val fake = FakeDictionary()
        val vm = conPack(fake)
        advanceUntilIdle()

        vm.close()
        assertTrue(fake.cerrado)
    }

    // --- The language selector ----------------------------------------------------------------

    @Test
    fun withTwoPacksItStartsOnTheSavedOne() = runTest {
        // Without this, alphabetical order would decide the active pack -- and "en-..." sorts
        // before "es-...", so the watch of someone who only uses Spanish would start in English.
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
    fun elIDIOMA_ELEGIDO_SOBREVIVE_al_reinicio_en_un_pack_BIDIRECCIONAL() = runTest {
        // ⚠️ **The bidirectional pack introduced the bug this closes.** What gets persisted is the
        // user's choice, and until here it was a `packId`. With a pack that speaks TWO languages
        // that stopped being enough: on restart, `activeLang` fell back to the **first** of
        // `meta.langs`, so somebody who chose English reopened the app in Spanish -- with no
        // error, and looking as though the chip does nothing.
        //
        // What is stored now is the **language**, which is also what the fallback already returned
        // when nothing was stored: the watch's regional configuration.
        val bilingue = FakeDictionary("es-tr-enwikt", "es", langs = listOf("es", "en"))
        val vm = SearchViewModel(
            { PackSet.Ready(handle(bilingue), listOf(handle(bilingue))) },
            preferred = { "en" },
        )
        advanceUntilIdle()
        assertEquals("en", vm.state.value.activeLang, "el idioma guardado manda sobre el primero")
        assertEquals("es-tr-enwikt", vm.state.value.active?.packId, "y el pack es el mismo")
    }

    @Test
    fun alCAMBIAR_DE_IDIOMA_se_guarda_el_IDIOMA_y_no_el_pack() = runTest {
        var guardado: String? = null
        val bilingue = FakeDictionary("es-tr-enwikt", "es", langs = listOf("es", "en"))
        val vm = SearchViewModel(
            { PackSet.Ready(handle(bilingue), listOf(handle(bilingue))) },
            saveActiveLanguage = { guardado = it },
        )
        advanceUntilIdle()
        vm.onLanguageChange("en")
        advanceUntilIdle()
        assertEquals("en", guardado)
    }

    @Test
    fun theDemoPackNeverWinsIfThereIsARealDictionary() = runTest {
        // Found by using it: with the demo pack (28 entries) and the real Spanish one (146,194)
        // installed, the app opened the DEMO. Neither the preference nor the watch locale broke
        // the tie --both packs are "es"-- so it fell to the last rung, which was alphabetical
        // order: "demo-" beats "es-".
        //
        // It is the same class D-079 killed, surviving in the last resort. And with a demo pack
        // inside the APK that resort fires always, not almost never.
        val demo = FakeDictionary("toy-es-en", "es")
        val real = FakeDictionary("es-def-wikc", "es")
        val vm = SearchViewModel({
            PackSet.Ready(handle(demo), listOf(handle(demo, isBundled = true), handle(real)))
        }, preferred = { "en" })
        advanceUntilIdle()
        assertEquals("es-def-wikc", vm.state.value.active?.packId)
    }

    @Test
    fun theDemoPackIsNotEvenOfferedIfThereIsARealOne() = runTest {
        // Seen on screen: the selector showed "ES" and "ES" --the demo and the real Spanish
        // one-- and there was no way to tell which was which. A placeholder is not an option: if
        // a dictionary exists, the toy one is not offered, and with a single pack the selector
        // disappears.
        val demo = FakeDictionary("toy-es-en", "es")
        val real = FakeDictionary("es-def-wikc", "es")
        val vm = SearchViewModel({
            PackSet.Ready(handle(real), listOf(handle(demo, isBundled = true), handle(real)))
        })
        advanceUntilIdle()
        assertEquals(listOf("es-def-wikc"), vm.state.value.available.map { it.packId })
    }

    @Test
    fun aShadowedBundledPackIsStillReportedAsLoaded() = runTest {
        // Measured on the emulator 2026-09-23 and the reason `loaded` exists: startup logged
        // `listo: 3 abiertos` and the debug dump, seconds later, said `abiertos=2`. Three packs
        // were open; `es-core` was missing because the dump read `available`, which hides a
        // bundled core whose language a full pack already covers.
        //
        // The dump's whole job is answering what is actually loaded on a device nobody can
        // attach a debugger to, so the two lists have to stay distinguishable: `available` is
        // for drawing, `loaded` is for reporting.
        val core = FakeDictionary("es-core", "es")
        val full = FakeDictionary("es-full", "es")
        val vm = SearchViewModel({
            PackSet.Ready(handle(full), listOf(handle(core, isBundled = true), handle(full)))
        })
        advanceUntilIdle()
        assertEquals(listOf("es-full"), vm.state.value.available.map { it.packId })
        assertEquals(listOf("es-core", "es-full"), vm.state.value.loaded.map { it.packId })
    }

    @Test
    fun withOnlyTheDemoPackThatOneIsUsed() = runTest {
        // That is what it exists for: so a freshly installed app has something to show.
        val demo = FakeDictionary("toy-es-en", "es")
        val vm = SearchViewModel({
            PackSet.Ready(handle(demo, isBundled = true), listOf(handle(demo, isBundled = true)))
        })
        advanceUntilIdle()
        assertEquals("toy-es-en", vm.state.value.active?.packId)
    }

    @Test
    fun switchingLanguageRepeatsTheCurrentSearchInTheNewPack() = runTest {
        // That is the point of the selector: if switching meant retyping the word, nobody would
        // use it on a wrist.
        val es = FakeDictionary("es-def", "es")
        val en = FakeDictionary("en-def", "en")
        val vm = SearchViewModel({ PackSet.Ready(handle(es), listOf(handle(es), handle(en))) })
        advanceUntilIdle()

        vm.onQueryChange("per")
        advanceUntilIdle()
        assertEquals(listOf("per"), es.queries)

        vm.onLanguageChange("en")
        advanceUntilIdle()
        assertEquals(listOf("per"), en.queries, "la query tiene que repetirse en el pack nuevo")
        assertEquals("per", vm.state.value.query)
        assertEquals("en-def", vm.state.value.active?.packId)
    }

    @Test
    fun loQueSE_PERSISTE_PARA_EL_TILE_no_incluye_packs_desinstalados() = runTest {
        // ⚠️ **A second instance of the same class as the tile bug**, found by sweeping on purpose
        // rather than waiting to trip over it. The app filters the history to the installed packs
        // (`visibleOnes`) and the tile read `PackStore.history()` **raw**: it showed a word from a
        // deleted dictionary, which opens nothing when tapped.
        //
        // The class is *"a rule that holds on one surface and not on its parallel"*, and both
        // times the symptom was the same: the error lives where nobody reports it.
        var paraElTile: List<Visit> = emptyList()
        val es = FakeDictionary("es-def", "es")
        val vm = SearchViewModel(
            { PackSet.Ready(handle(es), listOf(handle(es))) },
            savedHistory = {
                listOf(
                    Visit("es-def", 1, "casa", "noun"),
                    Visit("fantasma", 2, "perro", "noun"),
                )
            },
            saveTileHistory = { paraElTile = it },
        )
        advanceUntilIdle()
        assertEquals(
            listOf("casa"),
            paraElTile.map { it.headword },
            "el tile no puede mostrar una palabra de un pack que ya no está: $paraElTile",
        )
    }

    @Test
    fun unPackNUCLEO_SI_genera_palabra_del_dia() = runTest {
        // ⚠️ **This inverts the earlier rule, and the reason is that the defect was not the
        // core's.** They were excluded because a core is the 8,000 most frequent words and picking
        // the best ranked gave *the most common of the most common*: on the emulator out came `my`
        // and `un`. But the FULL packs have the same bias, only diluted -- measured over 112 days,
        // 41 % (es) and 16 % (en) of the days landed in the function-word zone just the same. The
        // selection rule was the problem; excluding the cores covered it up in one half.
        //
        // `WordOfTheDay.RANK_FLOOR` fixes it: with it all four real packs drop to **0 %** and a
        // core returns `acción`, `anillo`, `Christmas`, `afternoon`.
        //
        // ⚠️ **What this closes**: a fresh install carries only the APK's cores, so until here the
        // home showed no word of the day **on every user's first launch**. Now it does.
        val nucleo = FakeDictionary("es-core", "es", entryCount = 300, tier = PackTier.CORE)
        // Above the floor: it is a real core, not a sample of function words.
        nucleo.summaries = (1L..300L).associateWith { EntrySummary(it, "nucleo$it", "noun", 400) }
        val vm = SearchViewModel(
            { PackSet.Ready(handle(nucleo), listOf(handle(nucleo))) },
            todayDate = { "2026-10-01" },
        )
        advanceUntilIdle()
        val palabras = vm.state.value.wordsOfTheDay
        assertEquals(
            setOf("es-core"), palabras.keys,
            "un núcleo tiene que generar palabra del día: $palabras",
        )
    }

    @Test
    fun elTILE_tampoco_cachea_palabras_de_un_pack_de_TRADUCCION() = runTest {
        // ⚠️ **D-200's rule held on the screen and NOT on the tile**, and the tile is the worst
        // place for it to fail: nobody opens it on purpose, so a wrong word there gets reported by
        // nobody -- `onLanguageChange`'s own comment says so.
        //
        // `cacheWeekForTile` received the ACTIVE pack, and the active one can be the bilingual:
        // it is the largest (209,484 against 152,281), so `chooseActive` prefers it.
        var cacheado: List<Visit> = emptyList()
        val bi = FakeDictionary("es-tr-enwikt", "es", langs = listOf("es", "en"),
                                kind = PackKind.BILINGUAL, entryCount = 300)
        val defs = FakeDictionary("es-def-wikc", "es", entryCount = 200)
        // ⚠️ **The `entryCount` values are small and the `summaries` cover them entirely.** With
        // the real values --209,484 and 152,281-- `pick` draws ids the fake does not have, returns
        // null and the tile caches NOTHING: the test passed without testing anything. An
        // `isNotEmpty` probe put there on purpose before believing the green found it.
        bi.summaries = (1L..300L).associateWith {
            EntrySummary(it, "bilingue$it", "noun", 100)
        }
        defs.summaries = (1L..300L).associateWith {
            EntrySummary(it, "definido$it", "noun", 100)
        }
        SearchViewModel(
            { PackSet.Ready(handle(bi), listOf(handle(bi), handle(defs))) },
            todayDate = { "2026-10-01" },
            saveWeekWords = { _, week -> cacheado = week },
        )
        advanceUntilIdle()
        assertTrue(cacheado.isNotEmpty(), "el tile tiene que cachear algo, del pack correcto")
        assertEquals(
            0,
            cacheado.count { it.packId == "es-tr-enwikt" },
            "el tile no puede cachear del pack de traducción: $cacheado",
        )
    }

    @Test
    fun switchingLanguageIsRemembered() = runTest {
        var recordado: String? = null
        val es = FakeDictionary("es-def", "es")
        val en = FakeDictionary("en-def", "en")
        val vm = SearchViewModel({ PackSet.Ready(handle(es), listOf(handle(es), handle(en))) },
                                 saveActiveLanguage = { recordado = it })
        advanceUntilIdle()
        vm.onLanguageChange("en")
        advanceUntilIdle()
        // What is remembered is the LANGUAGE and no longer the `packId`: a bidirectional pack
        // speaks two, so its id does not say which one was being searched.
        assertEquals("en", recordado)
    }

    @Test
    fun openingAnEntryLooksItUpInItsOwnPack() = runTest {
        // The bug this fixes: navigation passed only the entryId, so with two packs open an
        // English entry was resolved against the active pack -- and showed ANOTHER word, with no
        // error.
        val es = FakeDictionary("es-def", "es")
        val en = FakeDictionary("en-def", "en")
        val vm = SearchViewModel({ PackSet.Ready(handle(es), listOf(handle(es), handle(en))) })
        advanceUntilIdle()
        assertEquals("en-def", vm.entry("en-def", 7)?.packId)
    }

    @Test
    fun openingAnEntryFromAnUnknownPackReturnsNull() = runTest {
        // Falling back to the active pack would be the same bug, but silent. Null makes the
        // screen say the entry is not there, which is honest.
        val vm = conPack(FakeDictionary("es-def", "es"))
        advanceUntilIdle()
        assertEquals(null, vm.entry("no-existe", 7))
    }

    @Test
    fun aBrokenPackDoesNotTakeTheOtherDown() = runTest {
        val es = FakeDictionary("es-def", "es")
        val roto = PackHandle.Incompatible("en-def.db", 1_000L, PackRejection.FTS_MISALIGNED)
        val vm = SearchViewModel({ PackSet.Ready(handle(es), listOf(handle(es), roto)) })
        advanceUntilIdle()
        assertEquals(SearchState.Status.Ready, vm.state.value.status)
        assertEquals(listOf(roto), vm.state.value.rejected)
    }

    @Test
    fun aRejectedPackIsNotOfferedInTheSelector() = runTest {
        // ⚠️ **The two channels have to separate in BOTH directions.** A rejected one that slips
        // into `available` is a language chip that searches nothing; one missing from `rejected`
        // is a file that takes space and appears nowhere.
        val es = FakeDictionary("es-def", "es")
        val roto = PackHandle.Incompatible("en-def.db", 1_000L, PackRejection.KEYS)
        val vm = SearchViewModel({ PackSet.Ready(handle(es), listOf(handle(es), roto)) })
        advanceUntilIdle()
        assertTrue(vm.state.value.available.none { it is PackHandle.Incompatible })
        assertEquals(listOf(roto), vm.state.value.rejected)
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

    // --- The history of opened entries -----------------------------------------------------------

    private fun suggestion(pack: String, id: Long, headword: String) = Suggestion(
        packId = pack, entryId = id, headword = headword, partOfSpeech = "noun",
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
    fun aVisitFromABidirectionalPackRecordsTheLanguageItWasSearchedIn() = runTest {
        // D-265, and the bidirectional pack is the case that has no other answer: `atizar` and
        // `stoke` both come out of `es-tr-enwikt-freq`, which declares es+en, so deriving the tag
        // from the pack gave NOTHING for either and the two rows were indistinguishable.
        //
        // The active language is a fact and not an inference here: the search is strict by
        // language (D-189) and filters by `entry.lang` inside the pack, so every result on screen
        // is in it. That is also why the results list carries a single tag.
        val bilingue = FakeDictionary("es-tr", "es", langs = listOf("es", "en"))
        val vm = conPack(bilingue)
        advanceUntilIdle()
        vm.onLanguageChange("en")
        advanceUntilIdle()
        vm.recordVisit(suggestion("es-tr", 7, "stoke"))
        assertEquals(listOf("en"), vm.state.value.history.map { it.lang })
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
        // El tope guardado es el TECHO de lo que cualquier pantalla podria mostrar, no lo que
        // muestra un reloj concreto: recortar a lo que entra es cosa del inicio (D-131). Antes
        // eran tres, y eso ataba el almacenamiento a una pantalla de 192 dp.
        // Se alimentan MAS de las que caben, calculado del propio tope: escribir un numero a
        // mano ataba el test al valor del momento y se rompio cuando subio de 8 a 25 (D-148).
        val cuantas = SearchViewModel.MAX_HISTORY + 4
        val vm = conPack(FakeDictionary("es-def", "es"))
        (1..cuantas).forEach { vm.recordVisit(suggestion("es-def", it.toLong(), "lema$it")) }
        assertEquals(SearchViewModel.MAX_HISTORY, vm.state.value.history.size)
        assertEquals("lema$cuantas", vm.state.value.history.first().headword)
    }

    @Test
    fun anEntryFromAPackThatIsGoneIsNotShown() = runTest {
        // Filtered when displayed, not pruned when saved: uninstalling and reinstalling a pack
        // is a real flow, and this way the history comes back on its own. A row that opens
        // nothing when tapped is worse than no row at all.
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

    // --- Searching the definitions ------------------------------------------------------------

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
        // It is the interface contract turned into a test: it walks a far larger index than the
        // headword one and does not meet the incremental search's latency budget.
        val fake = FakeDictionary("es-def", "es")
        val vm = conPack(fake)
        advanceUntilIdle()

        vm.onQueryChange("animal")
        advanceUntilIdle()

        assertTrue(fake.definitionMode.isEmpty(), "escribir no puede tocar el indice de texto libre")
    }

    @Test
    fun typingAfterADefinitionSearchReturnsToTheNormalSearch() = runTest {
        // Going back cannot cost a button: on 192 dp every control is paid for in results.
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
        // The failure mode: the definition search takes a while, the user keeps typing, and the
        // old result lands on top. It throws no exception at all.
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

        vm.onLanguageChange("en")
        advanceUntilIdle()

        assertEquals(SearchState.Mode.NORMAL, vm.state.value.mode)
        assertEquals(listOf("animal"), en.queries, "el pack nuevo recibe la query por prefijo")
    }

    @Test
    fun attributionAndLicenseComeFromThePackAndNotFromTheCode() = runTest {
        // D-031: showing them is the condition for using the data. If they came from a
        // constant, a pack from another source would show the wrong license.
        val fake = FakeDictionary()
        val vm = conPack(fake)
        advanceUntilIdle()

        assertEquals(fake.metadata.attribution, vm.state.value.active?.attribution)
        assertEquals(fake.metadata.license, vm.state.value.active?.license)
        assertEquals(fake.metadata.name, vm.state.value.active?.name)
    }
}
