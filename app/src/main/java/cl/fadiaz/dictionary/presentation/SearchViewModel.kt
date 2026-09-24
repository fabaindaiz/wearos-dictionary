package cl.fadiaz.dictionary.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cl.fadiaz.dictionary.core.LanguageScope
import cl.fadiaz.dictionary.core.DictionarySource
import cl.fadiaz.dictionary.core.Entry
import cl.fadiaz.dictionary.core.Suggestion
import cl.fadiaz.dictionary.core.PackMetadata
import cl.fadiaz.dictionary.core.SearchRepository
import cl.fadiaz.dictionary.data.Catalog
import cl.fadiaz.dictionary.data.CatalogFetch
import cl.fadiaz.dictionary.data.CatalogState
import cl.fadiaz.dictionary.data.CatalogPack
import cl.fadiaz.dictionary.data.DownloadPhase
import cl.fadiaz.dictionary.data.PackDownload
import cl.fadiaz.dictionary.data.DictLog
import cl.fadiaz.dictionary.data.LogSearchTrace
import cl.fadiaz.dictionary.core.TextNormalizer
import cl.fadiaz.dictionary.data.PackHandle
import cl.fadiaz.dictionary.core.PackKind
import cl.fadiaz.dictionary.core.PackTier
import cl.fadiaz.dictionary.core.speaks
import cl.fadiaz.dictionary.data.answersFor
import cl.fadiaz.dictionary.data.givesWordOfTheDay
import cl.fadiaz.dictionary.data.packsToQuery
import cl.fadiaz.dictionary.core.EntrySummary
import cl.fadiaz.dictionary.data.Settings
import cl.fadiaz.dictionary.data.TextScale
import cl.fadiaz.dictionary.data.PackSet
import cl.fadiaz.dictionary.data.WordOfTheDay
import cl.fadiaz.dictionary.tile.TileContents
import cl.fadiaz.dictionary.data.VisitTarget
import cl.fadiaz.dictionary.data.Visit
import cl.fadiaz.dictionary.data.visitTarget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** What the search screen needs to know, and nothing else. */
data class SearchState(
    val query: String = "",
    /**
     * What the LIST reflects, which is not always what the field shows.
     *
     * With the keyboard open you type without searching (D-128): `query` advances with every key
     * and `submitted` stays put. Without that separation, the first letter makes the header, the
     * voice button, the word of the day and the history disappear at once, the list restructures
     * whole, and the text field is destroyed and recomposed **taking the focus and the keyboard
     * with it**. Deleting the last letter does the same in reverse.
     */
    val submitted: String = "",
    val results: List<Suggestion> = emptyList(),
    val status: Status = Status.Loading,
    /** The pack being searched. Its `attribution` and `license` are the ones shown. */
    val active: PackMetadata? = null,
    /**
     * The language being searched in.
     *
     * ⚠️ **It exists because [active] stopped answering that.** A bidirectional pack speaks two
     * languages, so knowing which one is open no longer says which one is being searched:
     * `es-tr-enwikt` has `casa` and `house`. The chip chooses this; the pack is derived.
     */
    val activeLang: String? = null,
    /**
     * The download catalog. **[CatalogState.Idle] until the user presses the button.**
     *
     * It lives in the state and not in the screen so it survives navigating away and back:
     * querying costs network, and losing the result by opening a card would make the user pay
     * twice for the same answer.
     */
    val catalog: CatalogState = CatalogState.Idle,
    /** Downloads in flight by `packId`. Empty when there are none. */
    val downloads: Map<String, PackDownload> = emptyMap(),
    /**
     * The packs the selector **offers**, which is not every pack that is open.
     *
     * ⚠️ **It is [offerable]'s output, so two kinds of pack are missing from it**: the rejected
     * ones, which travel in [rejected], and **an open, bundled pack whose languages another
     * installed pack already covers** -- offering `Español (core)` next to `Español (full)` is
     * noise, so the core is hidden while the full one is there.
     *
     * ⚠️ **Anything asking "what is loaded" wants [loaded] and not this.** This KDoc used to
     * claim the field was every pack the app knows about and that it carried the rejected ones;
     * both were false, and the debug dump believed them -- it reported `abiertos=2` on a launch
     * whose own startup line said 3. A field whose documentation outranks its assignment is the
     * same failure as two sources of truth, with the copy nobody diffs being a comment.
     */
    val available: List<PackHandle> = emptyList(),
    /**
     * Every pack that is **open**, shadowed ones included. Nothing draws it; it is what the debug
     * dump reports.
     *
     * It exists because the dump's whole job is answering *what is actually loaded* on a device
     * nobody can attach a debugger to, and the only list it had was the one filtered for display.
     */
    val loaded: List<PackHandle.Open> = emptyList(),
    /**
     * The `.db` files that are on disk and **do not load**, with their reason.
     *
     * ⚠️ **They travel apart from [available] on purpose.** The two screens that read packs want
     * different lists and confusing them is a bug in either direction: the home's selector cannot
     * offer a dictionary that searches nothing, and the dictionaries screen **has** to show what
     * takes space on disk. A single field filtered at each use ends up either offering what is
     * useless or hiding what is there.
     */
    val rejected: List<PackHandle.Incompatible> = emptyList(),
    val mode: Mode = Mode.NORMAL,
    /** The most recently opened entries, already filtered: only those from installed packs. */
    val history: List<Visit> = emptyList(),
    /**
     * Today's entry, **one per loaded dictionary**, keyed by `packId`.
     *
     * A map and not a single one: with two languages installed both words of the day are of
     * interest, and switching language then has nothing to recompute. Empty while they are being
     * computed, if the packs are empty, or if nobody wired `todayDate` --a visible absence--.
     */
    val wordsOfTheDay: Map<String, EntrySummary> = emptyMap(),
    val settings: Settings = Settings(),
    val favorites: List<Visit> = emptyList(),
) {
    /**
     * Which path produced the results currently on screen.
     *
     * There is no second list: `results` is the same one, and `MatchKind.DEFINITION` already makes
     * each row label itself. A mode and not a `Boolean` because there are three states and the
     * middle one --searching-- has to be visible: the free-text index is far larger than the
     * headword one.
     */
    enum class Mode { NORMAL, BUSCANDO_DEFINICIONES, DEFINICIONES }

    sealed interface Status {
        data object Loading : Status

        /** No hay ningun diccionario instalado. El texto lo pone la pantalla (D-127). */
        data object NoDictionary : Status

        /** Extracting the pack from the APK. It takes a while, and the screen has to say why. */
        data object Installing : Status

        data object Ready : Status

        data class Failed(val message: String) : Status
    }
}

/**
 * The incremental search.
 *
 * It receives `openPacks` instead of building it: that was the only thing this ViewModel needed
 * from Android, and taking it out leaves it testable on the JVM inside the gate. See
 * `SearchViewModelTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SearchViewModel(
    private val openPacks: suspend (onExtracting: () -> Unit) -> PackSet,
    /**
     * Last time's **language**, or the watch's. Never alphabetical order.
     *
     * ⚠️ **It was a `packId` and with a bidirectional pack it stopped being enough**: a single one
     * speaks two languages, so remembering which was open does not say which was being searched.
     * On restart, somebody who had chosen English came back to Spanish -- with no error, and
     * looking as though the chip does nothing. An old stored `packId` still works: `chooseActive`
     * tries it first and only then as a language.
     */
    private val preferred: () -> String? = { null },
    private val saveActiveLanguage: (lang: String) -> Unit = {},
    /** The persisted history. It arrives as a parameter because it lives in Android (D-072). */
    private val savedHistory: () -> List<Visit> = { emptyList() },
    private val saveHistory: (List<Visit>) -> Unit = {},
    /**
     * What the recents **tile** can show: the history already filtered to installed packs.
     *
     * ⚠️ **It goes under a key of its own and does not replace the full history.** The app hides
     * the visits of an uninstalled pack (`visibleOnes`) but **keeps** them: reinstalling the
     * dictionary brings them back. The tile, by contrast, cannot filter on its own --it does not
     * know which packs are there without opening them, and opening a pack in a tile is forbidden
     * (D-106)-- so it reads an already resolved list.
     *
     * It is the same pattern as the week of words of the day, and for the same reason: what a tile
     * needs is left written by the app, which does have the context.
     */
    private val saveTileHistory: (List<Visit>) -> Unit = {},
    /**
     * Today, as "YYYY-MM-DD". It arrives as a parameter and does not come from a system clock in
     * here: that is what lets the [WordOfTheDay] policy run entirely on the JVM (D-072).
     *
     * The default returns null --no date means no word of the day-- so that forgetting to wire it
     * shows on screen as an absence, and not as a word that never changes.
     */
    private val todayDate: () -> String? = { null },
    private val savedSettings: () -> Settings = { Settings() },
    private val saveSettings: (Settings) -> Unit = {},
    /**
     * Deletes a pack's file. Returns whether there was anything to delete.
     *
     * It arrives as a parameter like everything that touches Android (D-072), and it takes the
     * **file name** and not the packId: they are different things.
     */
    private val deleteFromDisk: (fileName: String) -> Boolean = { false },
    private val savedFavorites: () -> List<Visit> = { emptyList() },
    private val saveFavorites: (List<Visit>) -> Unit = {},
    /**
     * The week of words already cached for the tile: from which day, and which ones.
     *
     * It is consulted so it is **not recomputed on every launch**: it is [TileContents.CACHED_DAYS]
     * x [WordOfTheDay.CANDIDATES] reads and they only change once a day.
     */
    private val savedWeekWords: () -> Pair<String?, List<Visit>> =
        { null to emptyList() },
    private val saveWeekWords: (since: String, words: List<Visit>) -> Unit =
        { _, _ -> },
    /**
     * Tells the tiles that what they show has changed.
     *
     * It arrives as a parameter because `TileService.getUpdater` is Android (D-072). It is not
     * optional: the history tile is published with `freshnessIntervalMillis = 0`, meaning **the
     * system never calls it again on its own**; without this push it keeps whatever it had when it
     * was installed.
     */
    private val notifyTiles: () -> Unit = {},
    /**
     * Asks the catalog for the index, passing whatever `ETag` is held.
     *
     * It arrives as a parameter and [CatalogClient] is not called directly so the tests can
     * exercise the screen **with no network**: the double returns a [CatalogFetch] and that is
     * all. Same pattern as `openPacks`.
     */
    private val fetchCatalog: suspend (etag: String?) -> CatalogFetch = { CatalogFetch.NotModified },
    /** Queues a pack's download. WorkManager does it with D-029's constraints. */
    private val startDownload: (CatalogPack) -> Unit = {},
    /** Stopping a download in flight and freeing its partial. See `DownloadPackWorker.cancel`. */
    private val cancelDownload: (CatalogPack) -> Unit = {},
    /** What WorkManager keeps saying about the downloads in flight. */
    private val downloadStates: Flow<List<PackDownload>> = flowOf(emptyList()),
) : ViewModel() {

    /**
     * The pack is a flow and not a `var`, and that is the difference between searching and not.
     *
     * The Spanish pack weighs 69 MB and takes a while to open, while the screen already accepts
     * text. With a `var`, whatever was typed during that window was queried against `null`, returned
     * empty and **nothing ever retried it**: the search stayed dead until the user deleted a letter.
     * As a flow, opening the pack is an event that fires the query again.
     */
    private val source = MutableStateFlow<DictionarySource?>(null)

    /**
     * What the search actually queries: **every open pack of the active language** (D-136).
     *
     * A flow for the same reason [source] is one -- opening a pack is an event that has to fire
     * the pending query again.
     *
     * ⚠️ **The selector now picks a language, not a file.** Two Spanish packs from different
     * sources installed together are both searched, and the gain is the union of their headwords;
     * that is what "packs that coexist" means here. It stays *within* a language on purpose: with
     * Spanish and English installed, typing "casa" must not return English entries. [source] is
     * still the active pack and still what the word of the day, the tile and the attribution use,
     * because those are about **one** dictionary.
     */
    private val searcher = MutableStateFlow<SearchRepository?>(null)

    /** All the open ones, to resolve entries from any pack and to close them. */
    private var opened: List<DictionarySource> = emptyList()

    /**
     * The definition search in flight.
     *
     * It is kept so it can be cancelled: it walks a far larger index than the headword one, so it
     * can still be alive when the user has already typed something else. Without this, its result
     * lands on top of the new one and shows another word, with no exception at all.
     */
    private var definitionMode: Job? = null

    /**
     * The full history, unfiltered.
     *
     * It is filtered when DISPLAYED and not pruned when saved: uninstalling a pack and installing it
     * again is a real development flow, and this way the history comes back on its own. What cannot
     * happen is showing a row that opens nothing when tapped.
     */
    private var visits: List<Visit> = emptyList()
    private var favoriteVisits: List<Visit> = emptyList()

    /** The keyboard is open. See [onTypingChanged]. */
    private var typing = false

    private val queries = MutableStateFlow("")
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    init {
        favoriteVisits = savedFavorites()
        _state.update { it.copy(settings = savedSettings(), favorites = favoriteVisits) }
        viewModelScope.launch {
            // The startup phase `am start -W` cannot see: that number ends at the first frame, and
            // the packs open AFTERWARDS, on IO. Without this there was no way to know whether the
            // wait until you can search is put there by the packs or by Compose.
            val desde = System.nanoTime()
            visits = savedHistory()
            loadPacks()
            DictLog.i {
                "arranque: listo para buscar en ${(System.nanoTime() - desde) / 1_000_000} ms " +
                    "(historial=${visits.size})"
            }
        }
        seguirDescargas()

        viewModelScope.launch {
            combine(
                // The debounce is about battery before performance: on a watch, firing one query
                // per keystroke keeps the CPU awake for the whole phrase.
                queries.debounce(DEBOUNCE_MS),
                searcher,
            ) { text, repo -> text to repo }
                // mapLatest cancels the previous search as soon as a new keystroke arrives. The
                // cascade checks for cancellation row by row, so the old one really stops instead
                // of finishing and being thrown away.
                .mapLatest { (text, repo) ->
                    if (text.isBlank() || repo == null) text to emptyList()
                    else text to repo.suggest(text)
                }
                .onEach { (text, results) ->
                    // Compared against the current query: if the user kept typing while this
                    // one ran, its result is no longer the one on screen. And nothing is
                    // published in definition mode: there the other query is in charge.
                    if (text == queries.value && _state.value.mode == SearchState.Mode.NORMAL) {
                        // `submitted` and not `query`: publishing `query` would overwrite what the
                        // user is typing at that instant.
                        _state.update { it.copy(submitted = text, results = results) }
                    }
                }
                .collect {}
        }
    }

    /**
     * Switches the active dictionary without losing what was typed.
     *
     * It assigns another value to the same flow, so the `combine` repeats the current query and
     * `mapLatest` cancels the previous one. There is no new state machine.
     *
     * **The old results are not cleared**: the new query takes milliseconds and a blank flash looks
     * worse than a stale list for an instant. No test can see that difference, which is why it is
     * written down here.
     */
    /**
     * Opens the packs and publishes the state. Called at startup and **every time the list of
     * dictionaries changes** --today, when one is deleted--.
     *
     * It is `suspend` and does not launch its own coroutine so the caller controls the ordering:
     * deleting requires closing the connections BEFORE touching the disk, and that cannot be done if
     * this function fires on its own.
     */
    private suspend fun loadPacks() {
        when (val result = openPacks { _state.update { it.copy(status = SearchState.Status.Installing) } }) {
            is PackSet.Ready -> {
                opened = result.all.filterIsInstance<PackHandle.Open>().map { it.source }
                val preferido = preferred()
                val chosen = chooseActive(result, preferido)
                source.value = chosen.source
                searcher.value = repositoryFor(
                    chosen.source,
                    preferido.takeIf { chosen.metadata.speaks(it) }
                        ?: chosen.metadata.langs.firstOrNull(),
                )
                _state.update {
                    it.copy(
                        status = SearchState.Status.Ready,
                        // D-031: the attribution comes from the pack, not from a constant. A
                        // pack from another source brings its own license and must show it.
                        active = chosen.metadata,
                        // ⚠️ **What was stored wins over the pack's first language**, and that is
                        // half the fix: `langs.first()` would always give `es` in a bidirectional
                        // pack, erasing the user's choice on every launch.
                        activeLang = it.activeLang
                            ?: preferido.takeIf { l -> chosen.metadata.speaks(l) }
                            ?: chosen.metadata.langs.firstOrNull(),
                        // The demo one is not offered if a real dictionary exists: it is a
                        // placeholder, not an option. Its label would also clash -- with the
                        // toy and the real Spanish one the selector read "ES" and "ES".
                        available = offerable(result.all),
                        loaded = result.all.filterIsInstance<PackHandle.Open>(),
                        rejected = result.all.filterIsInstance<PackHandle.Incompatible>(),
                        history = visibleOnes(visits),
                    )
                }
                // ⚠️ **Here and not only on visiting**, because it is the only moment when which
                // packs are there is known: if a dictionary was uninstalled between two launches,
                // the tile goes on showing its words until somebody opens a new one. Rewriting it
                // when the packs open fixes that without waiting for anything.
                saveTileHistory(visibleOnes(visits))
                // For ALL the offered ones, not just the active: the demo is left out
                // because `offerable` already removed it when a real dictionary exists.
                refreshWordsOfTheDay(
                    offerable(result.all).filterIsInstance<PackHandle.Open>()
                        .map { it.source },
                )
                cacheWeekForTile(chosen.source)
            }

            // No text: `:app`'s logic cannot translate (D-072), so it emits a STATE and the screen
            // resolves it (D-127).
            PackSet.NoPack -> _state.update { it.copy(status = SearchState.Status.NoDictionary) }

            is PackSet.Unusable -> _state.update {
                it.copy(status = SearchState.Status.Failed(result.reason))
            }
        }
    }

    /**
     * The packs that get searched together with [active]: the ones that **speak its language**.
     *
     * The active one goes first so that, on an exact tie, it wins -- the rest of the ordering is
     * `SearchRepository`'s business, and it does not promise that `score` is comparable across
     * packs built from different dumps.
     *
     * ⚠️ **A pack in another language is no longer left out entirely: it becomes the fallback.**
     * It used to be discarded on the argument that *"it is not a worse answer, it is the answer to
     * another question"*, and that is true **while the active language answers something**. When
     * it answers nothing resembling what was typed, the question the user really asked was the
     * other one: they typed an English word with Spanish active. `SearchRepository` decides when,
     * and its threshold is measured -- 0 of 400 common Spanish lemmas trigger it.
     */
    private fun repositoryFor(active: DictionarySource, idioma: String?): SearchRepository {
        // ⚠️ **Not everything installed gets queried, and that is the difference.**
        // `packsToQuery` drops the old builds of a same dictionary and the packs another one
        // contains; Settings still sees the whole list, because what is not queried still takes
        // disk and has to be
        val consultables = packsToQuery(opened)
        // The active one already comes from `activePack`, that is, from these same rules, so it is
        // in the list. It goes first because on an exact tie the one the user chose wins.
        // ⚠️ **`answersFor` and not `langSource ==`, because a BILINGUAL pack answers for both its
        // languages.** `es-tr-enwikt` declares `langSource = es`, so with English active it fell
        // into `otrosIdiomas` -- which since D-189 never answer. The only pack with translations
        // was invisible precisely in the `en → es` direction, which is half its reason for being.
        val mismoIdioma = consultables.filter { it !== active && answersFor(it, idioma) }
        val otrosIdiomas = consultables.filter { !answersFor(it, idioma) }
        return SearchRepository(
            listOf(active) + mismoIdioma,
            otherLanguages = otrosIdiomas,
            // ⚠️ **The first caller that ever picks `FALLBACK`.** The value existed since D-189
            // and nothing could construct it: the capability was built, tested and unreachable.
            scope = if (_state.value.settings.crossLanguageFallback) {
                LanguageScope.FALLBACK
            } else {
                LanguageScope.STRICT
            },
            lang = idioma,
            trace = LogSearchTrace,
        )
    }

    /**
     * Queries the catalog. **Only from the management screen's button.**
     *
     * ⚠️ **It is not called on entering the screen**, and that is the decision, not an omission:
     * the official Wear OS guidance classifies network access as *very high impact*, above turning
     * the screen on (D-029, `docs/bateria.md`). Automatic polling would be the most expensive
     * thing the app does, and the user did not ask for it.
     *
     * A `304` reuses the packs already fetched but **classifies again**: between the two queries
     * the user may have deleted a dictionary, and then what was "installed" becomes "download"
     * without the catalog having changed a comma.
     */
    fun onCheckCatalog() {
        if (_state.value.catalog is CatalogState.Checking) return
        _state.update { it.copy(catalog = CatalogState.Checking) }
        viewModelScope.launch {
            val instalados = opened.map { it.metadata }
            when (val r = fetchCatalog(catalogEtag)) {
                is CatalogFetch.Fresh -> {
                    catalogPacks = r.packs
                    catalogEtag = r.etag
                    publicar(instalados)
                }
                CatalogFetch.NotModified -> publicar(instalados)
                is CatalogFetch.Failed ->
                    _state.update { it.copy(catalog = CatalogState.Failed(r.reason)) }
            }
        }
    }

    /** Queues the download of a catalog pack. */
    fun onDownload(pack: CatalogPack) {
        DictLog.i { "descarga pedida: ${pack.packId} (${pack.bytes / 1_048_576} MB)" }
        startDownload(pack)
    }

    /**
     * Stops a download in flight and **takes its row off the screen at once**.
     *
     * ⚠️ **The local state is updated AS WELL AS cancelling, and that is not redundant.**
     * WorkManager reports the cancellation through its `Flow`, but not in the same frame: between
     * the tap and the report the row would go on saying *"downloading 3 MB of 24"* about something
     * already cancelled. On a watch that gap reads as the button having done nothing, and the
     * second tap is the reflex.
     */
    fun onCancelDownload(pack: CatalogPack) {
        DictLog.i { "descarga cancelada: ${pack.packId}" }
        cancelDownload(pack)
        _state.update { it.copy(downloads = it.downloads - pack.packId) }
    }

    /**
     * Follows what WorkManager says, and **reloads the packs when one finishes**.
     *
     * ⚠️ Without that reload the pack would be on disk and the app would not see it until the next
     * launch: the scan happens once, in this same `init`. And afterwards it classifies again, so
     * the row moves from "download" to sitting above, among the installed ones.
     */
    private fun seguirDescargas() {
        viewModelScope.launch {
            // ⚠️ **WorkManager KEEPS finished jobs**, so every launch's first emission carries the
            // DONE and FAILED ones from earlier sessions. That caused two distinct defects, seen
            // on the emulator on 2026-09-22:
            //
            //  1. They read as "a download has just finished" and republished the catalog while it
            //     was empty: the screen said "Nothing new" without anybody having asked.
            //  2. They were shown as the current state, so that pack's row said "Installed" and
            //     **stopped being tappable** -- even if the pack had been deleted and the catalog
            //     was offering it. There was no way to download it again.
            //
            // Hence the first emission is kept apart: what already arrived finished is **history**.
            var historia: Set<String>? = null
            var terminadas = emptySet<String>()
            downloadStates.collect { lista ->
                if (historia == null) {
                    historia = lista.filter { it.phase in FINALES }.map { it.packId }.toSet()
                    terminadas = lista.filter { it.phase == DownloadPhase.DONE }.map { it.packId }.toSet()
                }
                // ⚠️ **A pack leaves BOTH registers the moment it moves again.** Without this, one
                // already downloaded in another session stayed marked forever and its NEW download
                // did not count on finishing: the pack sat on disk with the app not seeing it
                // until the next launch.
                //
                // ⚠️ And there are TWO registers, not one: the first attempt at a fix cleared only
                // `historia` --what gets HIDDEN-- and left `terminadas` --what was already
                // COUNTED-- so the phase looked right on screen and still nothing reloaded. The
                // emulator gave it away, not a test: the symptom was the absence of a log line.
                val moviendose = lista.filterNot { it.phase in FINALES }.map { it.packId }.toSet()
                historia = historia.orEmpty() - moviendose
                terminadas = terminadas - moviendose
                val viejas = historia.orEmpty()
                val enCurso = lista.filterNot { it.packId in viejas && it.phase in FINALES }
                _state.update { it.copy(downloads = enCurso.associateBy { d -> d.packId }) }

                val nuevas = enCurso.filter { it.phase == DownloadPhase.DONE }.map { it.packId }.toSet()
                val recien = nuevas - terminadas
                if (recien.isEmpty()) return@collect
                DictLog.i { "descarga terminada: ${recien.joinToString()}" }
                terminadas = nuevas
                loadPacks()
                // ⚠️ Only if the user has ALREADY queried. Reclassifying over a catalog that was
                // never fetched publishes an empty list, which reads as "there is nothing new".
                if (_state.value.catalog is CatalogState.Ready) publicar(opened.map { it.metadata })
            }
        }
    }

    /**
     * The phases in which a job no longer advances. WorkManager keeps them between sessions.
     *
     * ⚠️ **[DownloadPhase.CANCELLED] has to be here.** Were it missing, a cancelled download would
     * count as "moving" forever: it would be taken out of the already-seen register on every
     * emission and its row would come back to the screen on its own after the user cancelled it.
     */
    private val FINALES =
        setOf(DownloadPhase.DONE, DownloadPhase.FAILED, DownloadPhase.CANCELLED)

    /** Classifies the last thing fetched against what is installed NOW. */
    private fun publicar(instalados: List<PackMetadata>) {
        val listado = Catalog.classify(catalogPacks, instalados)
        DictLog.i {
            val porEstado = listado.offers.groupingBy { it.status }.eachCount()
            "catalogo: " + porEstado.entries.joinToString(" ") { "${it.key}=${it.value}" }
                .ifEmpty { "nada que ofrecer" } +
                // The discarded ones are not listed, but they ARE counted in the log: without
                // this, a whole catalog rejected by version looks the same as an empty one.
                if (listado.needsAppUpdate) " (hay packs para una version mas nueva de la app)" else ""
        }
        _state.update {
            it.copy(catalog = CatalogState.Ready(listado.offers, listado.needsAppUpdate))
        }
    }

    /** The last thing the catalog said, so it can be reclassified after a 304. */
    private var catalogPacks: List<cl.fadiaz.dictionary.data.CatalogPack> = emptyList()

    /** The last response's `ETag`. It is what makes pressing the button twice cheap. */
    private var catalogEtag: String? = null

    /**
     * Changes the LANGUAGE being searched in, and derives the pack that represents it.
     *
     * ⚠️ **It used to take a `packId` and that stopped being enough**: a bidirectional pack speaks
     * two languages, so choosing it does not say which to search in. The chip sends a language;
     * the pack comes out of the same rules that choose a representative in the selector.
     */
    fun onLanguageChange(lang: String) {
        // Among the ones that get queried, not among the open ones: tapping a language's chip
        // cannot activate an old build the selection already discarded.
        val candidatos = packsToQuery(opened).filter { lang in it.metadata.langs }
        val pack = candidatos.maxByOrNull { it.metadata.entryCount } ?: return
        // The combine is going to repeat the prefix query on the new pack and would overwrite the
        // definition results anyway: better to leave the mode explicitly than leave the race open.
        leaveDefinitionMode()
        source.value = pack
        searcher.value = repositoryFor(pack, lang)
        _state.update { it.copy(active = pack.metadata, activeLang = lang) }
        // Nothing is recomputed for the SCREEN: the words of the day for every pack are already
        // there. The tile is, because it shows only one and it is the active pack's -- leaving it
        // in the previous language would be a wrong word that nobody reports, because nobody opens
        // a tile on purpose.
        cacheWeekForTile(pack)
        saveActiveLanguage(lang)
    }

    fun onQueryChange(text: String) {
        // Editing the query is how you get back from the definitions to the normal search. There is
        // deliberately no "back" button: on 192 dp a control is paid for in results, and the user
        // already has the gesture.
        leaveDefinitionMode()
        // The query shows IMMEDIATELY and the results arrive later: if the field waited for the
        // debounce, typing would feel stuck.
        _state.update { it.copy(query = text) }
        // With the keyboard open there is NO searching (D-128): the list is covered by the
        // keyboard, so searching there is work nobody sees that costs the only thing that matters
        // -- restructuring the list takes the field's focus, and the keyboard with it.
        if (!typing) queries.value = text
    }

    /**
     * The keyboard opened or closed.
     *
     * While it is open you type without searching; on closing --or on tapping Search, which closes
     * the keyboard-- whatever was typed is searched **once**.
     *
     * It is a method and not a boolean in the state because closing has an effect: it fires the
     * search. A flag somebody sets and clears would not have that.
     */
    fun onTypingChanged(isTyping: Boolean) {
        typing = isTyping
        if (!isTyping) queries.value = _state.value.query
    }

    /**
     * Searches the current query INSIDE the definitions, through FTS5.
     *
     * It is an explicit action and never hangs off the incremental pipeline: it walks a far larger
     * index than the headword one and does not meet the latency budget of typing.
     */
    /**
     * Leaves the search **empty**, as if the app had just opened (D-143).
     *
     * Two entry points want exactly this and neither had it:
     *
     *  - the magnifier on an entry, which used to come back to the search **with the word still
     *    typed**: you had to delete it by hand to look for something else;
     *  - the back gesture with text in the field, which used to **leave the app**. On a watch
     *    that is a harsh exit for what the user meant as "undo what I typed".
     *
     * It is [onQueryChange] with an empty string and not a new state machine: that one already
     * leaves definition mode and already makes the pipeline republish, so this is the same path
     * with the same guarantees rather than a second way of arriving at the home screen.
     */
    fun clearQuery() = onQueryChange("")

    /**
     * The user left the app: save what is pending and go back to a clean home.
     *
     * ⚠️ **It exists because a watch is not "closed", the wrist is lowered**, and coming back
     * three hours later to `esdrújula`'s card with `esdrú` typed is not resuming anything: it is
     * running into some earlier moment's screen. Asked for: *"that it not stay in the background
     * but close and save everything, so next time it takes you to a clean home screen"*.
     *
     * ⚠️ **The process is not killed, and that is deliberate.** Finishing the Activity would force
     * reopening the packs --measured: 500 ms cold against 278 warm with 372.6 MB open-- to save
     * memory the system already knows how to reclaim on its own. What gets thrown away is the
     * **screen state**, which is what annoys; what is kept are the descriptors, which is what
     * costs.
     *
     * The history and the saved words are already persisted on every change, so there is nothing
     * more to write here: it is named all the same so that the day something deferred appears it
     * has somewhere to go.
     */
    /**
     * Marks that the system input is about to cover the app, so it is not confused with leaving.
     *
     * `ACTION_REMOTE_INPUT` opens a full-screen SysUI Activity, so ours receives `ON_STOP` exactly
     * as when the user leaves. Without this mark, dictating a word would erase the screen you come
     * back to with the result.
     */
    fun onSystemInputOpening() {
        systemInputPending = true
    }

    /** Was this `ON_STOP` caused by the system input? It is consumed: only the first one counts. */
    fun consumeSystemInputPause(): Boolean {
        val era = systemInputPending
        systemInputPending = false
        return era
    }

    private var systemInputPending = false

    fun onLeftApp() {
        definitionMode?.cancel()
        _state.update {
            it.copy(query = "", submitted = "", results = emptyList(), mode = SearchState.Mode.NORMAL)
        }
        queries.value = ""
    }

    fun onSearchDefinitions() {
        val pack = source.value ?: return
        val text = _state.value.query
        if (text.isBlank()) return
        queries.value = text

        definitionMode?.cancel()
        _state.update { it.copy(mode = SearchState.Mode.BUSCANDO_DEFINICIONES) }
        definitionMode = viewModelScope.launch {
            val found = pack.searchDefinitions(text)
            // If something else was typed meanwhile, this result is no longer the one on screen.
            if (text == queries.value) {
                _state.update {
                    it.copy(results = found, mode = SearchState.Mode.DEFINICIONES)
                }
            }
        }
    }

    /**
     * Records that an entry was opened. Called when navigating, not when going back.
     *
     * The `Suggestion` already carries the four fields, so recording costs neither opening the entry
     * nor decompressing a payload.
     */
    fun recordVisit(suggestion: Suggestion) = recordVisit(
        Visit(
            packId = suggestion.packId,
            entryId = suggestion.entryId,
            headword = suggestion.headword,
            partOfSpeech = suggestion.partOfSpeech,
            // ⚠️ **The ACTIVE language, and that is a fact rather than an inference.** The search
            // is strict by language (D-189) and filters by `entry.lang` inside a bidirectional
            // pack, so every result on screen is in it -- which is also why the results list
            // carries a single tag. Reading it off the pack instead would give **nothing** for the
            // bilingual one, which is the untagged row being fixed.
            lang = _state.value.activeLang,
        ),
    )

    /**
     * The language a visit in [packId] is in, when the pack can answer it on its own.
     *
     * ⚠️ **Null for a bidirectional pack, and deliberately.** `es-tr-enwikt-freq` declares es+en,
     * so there is no single true answer and **no tag beats the wrong one** -- the rule a gloss's
     * links are painted by. Whoever knows better --the search, which filtered by language; the
     * card, which holds `entry.lang`-- passes it instead of asking here.
     */
    internal fun langOf(packId: String): String? =
        opened.firstOrNull { it.metadata.packId == packId }?.metadata?.langs?.singleOrNull()

    /**
     * Records a visit when only the id is held: the word of the day, a history entry, or a word
     * tapped inside a gloss.
     *
     * **None of the three paths recorded anything** (D-129), which is where the report's
     * "sometimes" came from: the history was updated when a SEARCH RESULT was opened and not when
     * it was opened any other way.
     *
     * It costs one row read by rowid --the same one the entry screen makes an instant later-- and
     * it does not fall back to the active pack when the request is not for an open pack: falling
     * back would record another word under the right headword, which is D-080's bug.
     */
    fun recordVisit(packId: String, entryId: Long) {
        val pack = opened.firstOrNull { it.metadata.packId == packId } ?: return
        viewModelScope.launch {
            val header = pack.summary(entryId) ?: return@launch
            // ⚠️ **`langOf` and not the active language.** This path is reached by tapping a
            // word inside a gloss or a translation, and a translation lands in the OTHER
            // language: asserting the active one here is exactly D-080's family. A bidirectional
            // pack therefore leaves it null, and the row stays untagged -- what would fix that is
            // `entry.lang` reaching `EntrySummary`, which is a `:dict-core` change and is written
            // up in the roadmap rather than smuggled in here.
            recordVisit(
                Visit(
                    packId, header.entryId, header.headword, header.partOfSpeech,
                    lang = langOf(packId),
                ),
            )
        }
    }

    fun recordVisit(visit: Visit) {
        // Move-to-front: opening the same word twice raises it, it does not duplicate it.
        visits = (listOf(visit) + visits.filterNot {
            it.packId == visit.packId && it.entryId == visit.entryId
        }).take(MAX_HISTORY)
        saveHistory(visits)
        saveTileHistory(visibleOnes(visits))
        _state.update { it.copy(history = visibleOnes(visits)) }
        notifyTiles()
    }

    /**
     * Of the open packs, which ones get **offered**: to the selector, to the word of the day, to
     * the dictionaries screen and to the credits.
     *
     * ⚠️ **The rule was *"if any pack is installed, hide ALL of the APK's"*, and with the real
     * cores inside that hid a whole dictionary.** It was written for D-088, when what shipped was
     * a 28-entry toy whose tag collided with the real pack's: the selector read *ES* and *ES* with
     * no way to tell them apart. D-175 replaced that toy with the **Spanish and English cores**,
     * and the rule was never looked at again.
     *
     * **The defect, measured with `elNucleoDeOtroIdiomaSOBREVIVE...`**: with full Spanish
     * downloaded, `opened.any { !isBundled }` is true and the filter takes **both** cores away.
     * `en-core.db` stays installed, open and queryable, and **with no chip**: the user loses all
     * of English from the interface without anything failing or being logged. It is the exact
     * shape of the bug this repo cannot see.
     *
     * **The rule that closes it: a pack from the APK steps aside only if another pack already
     * speaks ALL of its languages.** It is the same shape as `packsToQuery`'s containment
     * --stepping aside for whoever contains you-- and it keeps what D-088 wanted: with full
     * Spanish installed, `es-core` hides; `en-core` does not, because nobody else speaks English.
     *
     * ⚠️ **The fallback is `opened` and not `all`, and those stopped being the same thing.** While
     * `all` only carried open packs the two were identical; now it carries the rejected ones too,
     * and returning them here would put them in the home's selector -- a language chip that
     * searches nothing. The rejected ones go through their own channel, to the dictionaries
     * screen.
     */
    private fun offerable(all: List<PackHandle>): List<PackHandle> {
        val opened = all.filterIsInstance<PackHandle.Open>()
        val cubiertos = opened.filterNot { it.isBundled }
            .flatMap { it.metadata.langs }
            .toSet()
        return opened
            .filterNot { pack -> pack.isBundled && pack.metadata.langs.all { it in cubiertos } }
            // A pack declaring no language would satisfy `all {}` vacuously and slip through the
            // filter. It cannot happen --`meta.langs` is required since schema 4-- and for that
            // very reason it is not paid for by being left with nothing to offer.
            .ifEmpty { opened }
    }

    /** Only those from open packs: a row that opens nothing is worse than no row at all. */
    private fun visibleOnes(allSenses: List<Visit>): List<Visit> {
        val installed = opened.map { it.metadata.packId }.toSet()
        return if (installed.isEmpty()) allSenses else allSenses.filter { it.packId in installed }
    }

    private fun leaveDefinitionMode() {
        definitionMode?.cancel()
        definitionMode = null
        if (_state.value.mode != SearchState.Mode.NORMAL) {
            _state.update { it.copy(mode = SearchState.Mode.NORMAL, results = emptyList()) }
        }
    }

    /**
     * Opens an entry **in its own pack**.
     *
     * It returns null if that pack is not open, instead of falling back to the active one: falling
     * back would be the very bug this fixes --showing another word-- but silent.
     */
    /**
     * Computes the word of the day for each loaded dictionary.
     *
     * In its own coroutine and without blocking the screen, which is already ready to search: it is
     * [WordOfTheDay.CANDIDATES] single-row reads per pack. Each one is published as soon as it is
     * ready, so with two languages the first does not wait for the second.
     *
     * If a pack fails it is left without a word of the day and the others carry on: an incomplete
     * home screen is better than one that does not load.
     */
    /**
     * Leaves the active pack's week of words written down, so the tile does not have to open it.
     *
     * **The tile cannot compute this.** `onTileRequest` runs on the main thread with a 10 s cap, and
     * opening a 69 or 295 MB pack there is out of the question by API contract, not by suspicion
     * about performance. But the word is deterministic by (date, pack), so the app --which already
     * has the pack open-- can work the next days out ahead of time and store them.
     *
     * Seven days and not one: they are the seven `Timeline` windows that let the renderer change the
     * word at midnight **without a single process wakeup**.
     *
     * It is not redone if the cache is already from today and from the same pack: that turns it into
     * once-a-day work instead of once-per-launch work.
     */
    private fun cacheWeekForTile(activo: DictionarySource) {
        val today = todayDate() ?: return
        // ⚠️ **D-200's rule holds here too, and the tile is the worst place for it to fail.** A
        // translation pack generates no word of the day, but the ACTIVE pack may be one --it is
        // the largest, so `chooseActive` prefers it-- and this method received the active one flat
        // out. The result would have been a word of the day from a dictionary that defines
        // nothing, **on the surface nobody opens on purpose**: an error that does not get
        // reported.
        //
        // If there is no definitions dictionary for the active language, nothing is cached and the
        // tile shows what it already had. That is the right degradation: better with no word than
        // with one that explains nothing when tapped.
        val active = elegirParaPalabraDelDia(activo) ?: return
        val packId = active.metadata.packId
        val (since, cacheadas) = savedWeekWords()
        if (since == today && cacheadas.isNotEmpty() && cacheadas.all { it.packId == packId }) return

        viewModelScope.launch {
            val week = mutableListOf<Visit>()
            for (day in 0 until TileContents.CACHED_DAYS) {
                val picked = runCatching {
                    WordOfTheDay.pick(
                        date = TileContents.plusDays(today, day) ?: return@launch,
                        packId = packId,
                        entryCount = active.metadata.entryCount,
                        read = { id -> active.summary(id) },
                        rankBasis = active.metadata.rankBasis,
                    )
                }.getOrNull() ?: return@launch
                // ⚠️ **The gloss is read HERE and not in the tile**, and that is half the design:
                // opening an entry decompresses its payload, and `onTileRequest` is `@MainThread`
                // with ten seconds (D-106). The app, which already has the pack open, leaves it
                // written. It is seven reads once a day.
                val primera = runCatching { active.entry(picked.entryId) }
                    .getOrNull()?.senses?.firstOrNull()?.gloss
                week += Visit(
                    packId = packId,
                    entryId = picked.entryId,
                    headword = picked.headword,
                    partOfSpeech = picked.partOfSpeech,
                    gloss = primera,
                )
            }
            saveWeekWords(today, week)
            notifyTiles()
        }
    }

    /**
     * The DEFINITIONS dictionary representing the given pack's language, or null.
     *
     * It prefers the pack itself if it already defines, and otherwise the largest of its language
     * that does -- the same representative rule the selector uses.
     */
    private fun elegirParaPalabraDelDia(activo: DictionarySource): DictionarySource? {
        if (givesWordOfTheDay(activo.metadata)) return activo
        return packsToQuery(opened)
            .filter { givesWordOfTheDay(it.metadata) && answersFor(it, state.value.activeLang) }
            .maxByOrNull { it.metadata.entryCount }
    }

    private fun refreshWordsOfTheDay(packs: List<DictionarySource>) {
        val date = todayDate() ?: return
        for (pack in packs.filter { givesWordOfTheDay(it.metadata) }) {
            viewModelScope.launch {
                val picked = runCatching {
                    WordOfTheDay.pick(
                        date = date,
                        packId = pack.metadata.packId,
                        entryCount = pack.metadata.entryCount,
                        read = { id -> pack.summary(id) },
                        rankBasis = pack.metadata.rankBasis,
                    )
                }.getOrNull() ?: return@launch
                _state.update {
                    it.copy(wordsOfTheDay = it.wordsOfTheDay + (pack.metadata.packId to picked))
                }
            }
        }
    }

    /**
     * Deletes a dictionary from the watch. **Irreversible**: putting it back costs ~90 s over adb.
     *
     * THE ORDER IS THE CONTRACT, and it is not theoretical. On Unix a deleted file with an open
     * descriptor keeps occupying the disk until it is closed, and the app would go on reading it as
     * if nothing happened: the user would see "deleted" and **zero space freed**, which is worse
     * than not being able to delete. So the connections are released first, then the disk is
     * touched, and only then is what is left reopened.
     *
     * ALL of them are closed and not just the one for the pack being removed: `loadPacks` reopens
     * the whole set, and leaving the old ones open would leak a connection per deletion.
     *
     * The demo pack **cannot be deleted**: it comes inside the APK and `PackStore.open` re-extracts
     * it on reopening, so the action would do nothing and the pack would come back on its own.
     */
    /**
     * Deletes a dictionary from disk, loaded or not.
     *
     * ⚠️ **It resolves against BOTH lists.** A rejected pack is not in `available` --it is not
     * offered for searching-- but it takes space on disk and deleting it is the only action left
     * on it. Looking for it only among the open ones made its row's button do nothing, which is
     * worse than having no button.
     */
    fun deletePack(packId: String) {
        val abierto = state.value.available
            .filterIsInstance<PackHandle.Open>()
            .firstOrNull { it.packId == packId }
        val archivo = abierto?.fileName
            ?: state.value.rejected.firstOrNull { it.fileName == packId }?.fileName
            ?: return
        if (abierto?.isBundled == true) return

        viewModelScope.launch {
            // No active pack and in "loading" while it lasts: a query arriving in the middle
            // cannot land on an already closed connection.
            _state.update {
                it.copy(
                    status = SearchState.Status.Loading,
                    wordsOfTheDay = it.wordsOfTheDay - packId,
                )
            }
            source.value = null
            close()
            deleteFromDisk(archivo)
            loadPacks()
        }
    }

    /** Whether that entry is saved. By `packId` as well as the id: two packs share ids. */
    fun isFavorite(packId: String, entryId: Long): Boolean =
        favoriteVisits.any { it.packId == packId && it.entryId == entryId }

    /**
     * Saves a word or takes it out of the saved ones.
     *
     * To the front and without duplicating, same as the history, but **with a far higher cap**: the
     * history is three because it competes for the screen's rows (D-073), and the saved words live
     * in their own list. The cap still exists because all of this ends up in a SharedPreferences
     * String.
     */
    fun toggleFavorite(visit: Visit) {
        val wasFavorite = isFavorite(visit.packId, visit.entryId)
        favoriteVisits = if (wasFavorite) {
            favoriteVisits.filterNot { it.packId == visit.packId && it.entryId == visit.entryId }
        } else {
            (listOf(visit) + favoriteVisits).take(MAX_FAVORITES)
        }
        saveFavorites(favoriteVisits)
        _state.update { it.copy(favorites = favoriteVisits) }
        notifyTiles()
    }

    /** Changes the text scale and stores it. */
    fun onTextScaleChange(scale: TextScale) {
        val fresh = state.value.settings.copy(textScale = scale)
        saveSettings(fresh)
        _state.update { it.copy(settings = fresh) }
    }

    /**
     * Turns the cross-language fallback on or off, stores it, **and rebuilds the searcher**.
     *
     * ⚠️ **The rebuild is the whole thing, and leaving it out is a silent no-op.** The repository
     * is built when the packs load and when the language changes --NOT per query-- and `scope` is
     * a constructor argument, so without this the switch would move, persist, read back correctly
     * on the next launch, and change nothing at all in between. A test caught it; nothing else
     * would have, because every visible symptom is "the setting does not seem to do anything",
     * which is also what it looks like when the fallback simply does not trigger.
     *
     * What is deliberately NOT done is re-running the query on screen: results do not change
     * under your finger, which is the rule [onLanguageChange] follows too.
     */
    fun onCrossLanguageFallbackChange(enabled: Boolean) {
        val fresh = state.value.settings.copy(crossLanguageFallback = enabled)
        saveSettings(fresh)
        _state.update { it.copy(settings = fresh) }
        val pack = source.value ?: return
        searcher.value = repositoryFor(pack, state.value.activeLang)
    }

    /**
     * Empties the history, in memory and on disk.
     *
     * It was needed: with a cap of three and move-to-front it recycles itself, but a word you do not
     * want to see again stays until you open three more.
     */
    fun clearHistory() {
        visits = emptyList()
        saveHistory(visits)
        saveTileHistory(visibleOnes(visits))
        _state.update { it.copy(history = emptyList()) }
    }

    /**
     * Which entry a stored visit leads to, **fixing it if the pack was rebuilt**.
     *
     * `entry.id` is the rowid and does not survive a rebuild (D-055): the history and the saved
     * words are backed up by it, so after rebuilding a pack they point at another word --with the
     * right headword still written in the row, that is, with nothing to give it away--.
     *
     * **It costs no extra query in the normal case**: if the headword at that id is the stored one,
     * [visitTarget] returns `Direct` and `resolveHeadwords` is never called. The `summary` that is
     * paid for is a single-row read by rowid, the same one the entry screen does an instant later
     * anyway.
     *
     * When it does fix something, it **rewrites the backup**: otherwise every opening would pay for
     * the re-resolution again and the tile would go on publishing the old id.
     */
    suspend fun targetOf(visit: Visit): Long? {
        val source = opened.firstOrNull { it.metadata.packId == visit.packId } ?: return null
        val atId = source.summary(visit.entryId)?.headword
        val relocated = if (atId == visit.headword) {
            null
        } else {
            val key = TextNormalizer.norm(visit.headword)
            source.resolveHeadwords(setOf(key), null)[key]
        }
        return when (val target = visitTarget(visit, atId, relocated)) {
            is VisitTarget.Direct -> target.entryId
            is VisitTarget.Relocated -> {
                fixEntryId(visit, target.entryId)
                target.entryId
            }
            VisitTarget.Missing -> null
        }
    }

    /** Rewrites this visit's `entryId` in the history and in the saved words. */
    private fun fixEntryId(visit: Visit, entryId: Long) {
        fun fix(list: List<Visit>) = list.map {
            if (it.packId == visit.packId && it.entryId == visit.entryId) {
                it.copy(entryId = entryId)
            } else {
                it
            }
        }
        visits = fix(visits)
        favoriteVisits = fix(favoriteVisits)
        saveHistory(visits)
        saveTileHistory(visibleOnes(visits))
        saveFavorites(favoriteVisits)
        _state.update { it.copy(history = visibleOnes(visits), favorites = favoriteVisits) }
    }

    suspend fun entry(packId: String, entryId: Long): Entry? =
        opened.firstOrNull { it.metadata.packId == packId }?.entry(entryId)

    /**
     * Which words in a gloss are headwords, **in that entry's pack** and not in the active one.
     *
     * Same rule as [entry]: if the pack is not open it returns empty instead of falling back to the
     * active one. Falling back would paint as tappable a word that opens a different one, which is
     * exactly the bug D-080 fixed, but mute.
     */
    suspend fun resolveIn(
        packId: String,
        norms: Set<String>,
        lang: String? = null,
    ): Map<String, Long> =
        opened.firstOrNull { it.metadata.packId == packId }
            ?.resolveHeadwords(norms, lang)
            .orEmpty()

    /**
     * Resolves the same `norms` in the first installed pack of `lang`, which is **not** the
     * entry's.
     *
     * ⚠️ **It looks up by LANGUAGE and not by `pack_id`, and that is the decision that keeps the
     * link from dying.** Declaring the target pack by name would make a user with the English
     * **core** installed rather than the full one lose every link, even though they hold an
     * English dictionary perfectly able to resolve them.
     *
     * It returns empty if there is none, and then the term is shown **unpainted** -- which is
     * right: a painted word that does not navigate is worse than an unpainted one (D-084).
     */
    suspend fun resolveInLanguage(lang: String, norms: Set<String>): Map<String, Pair<String, Long>> {
        val destino = opened.firstOrNull { it.metadata.speaks(lang) } ?: return emptyMap()
        return destino.resolveHeadwords(norms, lang)
            .mapValues { (_, id) -> destino.metadata.packId to id }
    }

    /** Closes the pack. Public so a test can exercise it without simulating the lifecycle. */
    fun close() {
        opened.forEach { it.close() }
        opened = emptyList()
        source.value = null
    }

    override fun onCleared() = close()

    companion object {
        /** How long a finger takes to chain two letters on a watch screen. */
        const val DEBOUNCE_MS: Long = 120

        /**
         * How many recent entries are REMEMBERED. How many are SHOWN is the screen's decision.
         *
         * It was three, tied to D-073's arithmetic --"the screen gives three 48 dp rows"-- and
         * that made storage depend on one concrete watch: on a bigger one there was room to spare
         * and the history still held three.
         *
         * It is now the **ceiling of what any screen could show** (`rowsThatFit` caps at 8) and
         * the home trims to what actually fits. Storing eight is still free in bytes; what was
         * being guarded was not showing what nobody sees, and the screen takes care of that.
         */
        const val MAX_HISTORY: Int = 25

        /**
         * Cap on saved words. Deliberately high --they do not compete for the screen like the
         * history-- but bounded because all of this ends up in a SharedPreferences String.
         */
        const val MAX_FAVORITES: Int = 100

        /**
         * Which pack opens at startup. **Never alphabetical order**: with two packs that made a watch
         * set to Spanish start in English, because "en-" sorts before "es-".
         *
         * Three rungs: the exact pack from last time, any pack of that language --which covers having no
         * stored preference and using the watch locale-- and finally whichever comes first.
         */
        internal fun chooseActive(set: PackSet.Ready, preferred: String?): PackHandle.Open {
            val opened = set.all.filterIsInstance<PackHandle.Open>()
            // The demo pack only wins if there is no other: it exists so a freshly installed app
            // has something to show, not to cover up a real dictionary.
            val candidates = opened.filterNot { it.isBundled }.ifEmpty { opened }
            return candidates.firstOrNull { it.packId == preferred }
                ?: candidates.firstOrNull { it.metadata.speaks(preferred) }
                ?: candidates.firstOrNull()
                ?: set.active
        }
    }
}

private inline fun MutableStateFlow<SearchState>.update(bloque: (SearchState) -> SearchState) {
    value = bloque(value)
}
