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

    private fun handle(d: FakeDictionary, isDemo: Boolean = false) =
        PackHandle.Open(d, isDemo)

    @BeforeTest
    fun before() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun after() = Dispatchers.resetMain()

    private fun conPack(source: FakeDictionary) = SearchViewModel({ listos(source) })

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
            PackSet.Ready(handle(demo), listOf(handle(demo, isDemo = true), handle(real)))
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
            PackSet.Ready(handle(real), listOf(handle(demo, isDemo = true), handle(real)))
        })
        advanceUntilIdle()
        assertEquals(listOf("es-def-wikc"), vm.state.value.available.map { it.packId })
    }

    @Test
    fun withOnlyTheDemoPackThatOneIsUsed() = runTest {
        // That is what it exists for: so a freshly installed app has something to show.
        val demo = FakeDictionary("toy-es-en", "es")
        val vm = SearchViewModel({
            PackSet.Ready(handle(demo, isDemo = true), listOf(handle(demo, isDemo = true)))
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
        val vm = conPack(FakeDictionary("es-def", "es"))
        (1..12).forEach { vm.recordVisit(suggestion("es-def", it.toLong(), "lema$it")) }
        assertEquals(SearchViewModel.MAX_HISTORY, vm.state.value.history.size)
        assertEquals("lema12", vm.state.value.history.first().headword)
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

        vm.onPackChange("en-def")
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
