package cl.fadiaz.dictionary.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
     * Lo que la LISTA refleja, que no siempre es lo que el campo muestra.
     *
     * Con el teclado abierto se escribe sin buscar (D-128): `query` avanza con cada tecla y
     * `submitted` se queda quieto. Sin esa separacion, la primera letra hace desaparecer
     * encabezado, voz, palabra del dia e historial de un golpe, la lista se reestructura entera,
     * y el campo de texto se destruye y se recompone **llevandose el foco y el teclado**. Borrar
     * la ultima letra hace lo mismo al reves.
     */
    val submitted: String = "",
    val results: List<Suggestion> = emptyList(),
    val status: Status = Status.Loading,
    /** The pack being searched. Its `attribution` and `license` are the ones shown. */
    val active: PackMetadata? = null,
    /**
     * El idioma en el que se busca.
     *
     * ⚠️ **Existe porque [active] dejo de contestarlo.** Un pack bidireccional habla dos idiomas,
     * asi que saber cual esta abierto ya no dice en cual se busca: `es-tr-enwikt` tiene `casa` y
     * `house`. El chip elige esto; el pack se deriva.
     */
    val activeLang: String? = null,
    /**
     * El catalogo de descarga. **[CatalogState.Idle] hasta que el usuario aprieta el boton.**
     *
     * Vive en el estado y no en la pantalla para que sobreviva a navegar y volver: consultar
     * cuesta red, y perder el resultado por entrar a una ficha haria que el usuario pague dos
     * veces por la misma respuesta.
     */
    val catalog: CatalogState = CatalogState.Idle,
    /** Descargas en curso por `packId`. Vacio cuando no hay ninguna. */
    val downloads: Map<String, PackDownload> = emptyMap(),
    /**
     * Every pack the app knows about. It is what the selector draws.
     *
     * ⚠️ **Lleva tambien los rechazados**, como `PackHandle.Incompatible`, y `offerable` los
     * saca del selector. Ya no hay una lista aparte de cadenas: un rechazado es una fila mas de
     * la pantalla de diccionarios, con su motivo, y no una nota al pie en los creditos.
     */
    val available: List<PackHandle> = emptyList(),
    /**
     * Los `.db` que estan en el disco y **no se cargan**, con su motivo.
     *
     * ⚠️ **Viajan aparte de [available] a proposito.** Las dos pantallas que leen packs quieren
     * listas distintas y confundirlas es un bug en cada direccion: el selector del inicio no
     * puede ofrecer un diccionario que no busca, y la pantalla de diccionarios **tiene** que
     * mostrar lo que ocupa lugar en el disco. Un solo campo filtrado en cada uso termina
     * ofreciendo lo que no sirve o escondiendo lo que hay.
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
     * El **idioma** de la última vez, o el del reloj. Nunca el orden alfabético.
     *
     * ⚠️ **Era un `packId` y con un pack bidireccional dejó de alcanzar**: uno solo habla dos
     * idiomas, así que recordar cuál estaba abierto no dice en cuál se estaba buscando. Al
     * reiniciar, quien había elegido inglés volvía al español — sin error, y pareciendo que el
     * chip no hace nada. Un `packId` viejo guardado sigue funcionando: `chooseActive` lo prueba
     * primero y sólo después como idioma.
     */
    private val preferred: () -> String? = { null },
    private val saveActiveLanguage: (lang: String) -> Unit = {},
    /** The persisted history. It arrives as a parameter because it lives in Android (D-072). */
    private val savedHistory: () -> List<Visit> = { emptyList() },
    private val saveHistory: (List<Visit>) -> Unit = {},
    /**
     * Lo que el **tile** de recientes puede mostrar: el historial ya filtrado a packs instalados.
     *
     * ⚠️ **Va por una clave aparte y no reemplaza al historial completo.** La app esconde las
     * visitas de un pack desinstalado (`visibleOnes`) pero las **conserva**: reinstalar el
     * diccionario las devuelve. El tile, en cambio, no puede filtrar por su cuenta --no sabe qué
     * packs hay sin abrirlos, y abrir un pack en un tile está prohibido (D-106)-- así que lee una
     * lista ya resuelta.
     *
     * Es el mismo patrón que la semana de palabras del día, y por el mismo motivo: lo que un tile
     * necesita lo deja escrito la app, que sí tiene el contexto.
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
     * Le pregunta al catalogo por el indice, pasandole el `ETag` que se tenga.
     *
     * Llega como parametro y no se llama a [CatalogClient] directo para que los tests puedan
     * ejercitar la pantalla **sin red**: el doble devuelve un [CatalogFetch] y ya. Mismo patron
     * que `openPacks`.
     */
    private val fetchCatalog: suspend (etag: String?) -> CatalogFetch = { CatalogFetch.NotModified },
    /** Encola la descarga de un pack. La hace WorkManager con las restricciones de D-029. */
    private val startDownload: (CatalogPack) -> Unit = {},
    /** Lo que WorkManager va diciendo de las descargas en curso. */
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

    /** El teclado esta abierto. Ver [onTypingChanged]. */
    private var typing = false

    private val queries = MutableStateFlow("")
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    init {
        favoriteVisits = savedFavorites()
        _state.update { it.copy(settings = savedSettings(), favorites = favoriteVisits) }
        viewModelScope.launch {
            // La fase de arranque que no se ve desde `am start -W`: ese numero termina en el
            // primer frame, y los packs abren DESPUES, en IO. Sin esto no habia forma de saber si
            // la espera hasta poder buscar la ponen los packs o Compose.
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
                        // `submitted` y no `query`: publicar `query` pisaria lo que el usuario
                        // esta escribiendo en ese instante.
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
                        // ⚠️ **Lo guardado manda sobre el primer idioma del pack**, y ésa es la
                        // mitad del arreglo: `langs.first()` daría siempre `es` en un pack
                        // bidireccional, borrando la elección del usuario en cada arranque.
                        activeLang = it.activeLang
                            ?: preferido.takeIf { l -> chosen.metadata.speaks(l) }
                            ?: chosen.metadata.langs.firstOrNull(),
                        // The demo one is not offered if a real dictionary exists: it is a
                        // placeholder, not an option. Its label would also clash -- with the
                        // toy and the real Spanish one the selector read "ES" and "ES".
                        available = offerable(result.all),
                        rejected = result.all.filterIsInstance<PackHandle.Incompatible>(),
                        history = visibleOnes(visits),
                    )
                }
                // ⚠️ **Acá y no sólo al visitar**, porque es el único momento en que se sabe qué
                // packs hay: si se desinstaló un diccionario entre dos arranques, el tile sigue
                // mostrando sus palabras hasta que alguien abra una nueva. Reescribirlo al abrir
                // los packs lo corrige sin esperar a nada.
                saveTileHistory(visibleOnes(visits))
                // For ALL the offered ones, not just the active: the demo is left out
                // because `offerable` already removed it when a real dictionary exists.
                refreshWordsOfTheDay(
                    offerable(result.all).filterIsInstance<PackHandle.Open>()
                        .map { it.source },
                )
                cacheWeekForTile(chosen.source)
            }

            // Sin texto: la logica de :app no puede traducir (D-072), asi que emite un ESTADO
            // y la pantalla lo resuelve (D-127).
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
     * ⚠️ **Un pack de otro idioma ya no se deja afuera del todo: pasa a ser el respaldo.** Antes
     * se descartaba con el argumento de que *«no es una respuesta peor, es la respuesta a otra
     * pregunta»*, y eso es cierto **mientras el idioma activo conteste algo**. Cuando no contesta
     * nada parecido a lo escrito, la pregunta que el usuario hizo de verdad era la otra: escribió
     * una palabra inglesa con español activo. `SearchRepository` decide cuándo, y su umbral está
     * medido -- 0 de 400 lemas españoles comunes lo disparan.
     */
    private fun repositoryFor(active: DictionarySource, idioma: String?): SearchRepository {
        // ⚠️ **No se consulta todo lo instalado, y ésa es la diferencia.** `packsToQuery` saca
        // los builds viejos de un mismo diccionario y los packs que otro contiene; Ajustes sigue
        // viendo la lista entera, porque lo que no se consulta igual ocupa disco y hay que poder
        val consultables = packsToQuery(opened)
        // El activo ya viene de `activePack`, o sea de estas mismas reglas, así que está en la
        // lista. Se pone primero porque en un empate exacto gana el que el usuario eligió.
        // ⚠️ **`answersFor` y no `langSource ==`, porque un pack BILINGÜE contesta por sus dos
        // idiomas.** `es-tr-enwikt` declara `langSource = es`, así que con inglés activo caía en
        // `otrosIdiomas` — que desde D-189 no contestan nunca. El único pack con traducciones
        // quedaba invisible justo en la dirección `en → es`, que es la mitad de su razón de ser.
        val mismoIdioma = consultables.filter { it !== active && answersFor(it, idioma) }
        val otrosIdiomas = consultables.filter { !answersFor(it, idioma) }
        return SearchRepository(
            listOf(active) + mismoIdioma,
            otherLanguages = otrosIdiomas,
            lang = idioma,
            trace = LogSearchTrace,
        )
    }

    /**
     * Consulta el catalogo. **Solo desde el boton de la pantalla de gestion.**
     *
     * ⚠️ **No se llama al entrar a la pantalla**, y eso es la decision, no una omision: la guia
     * oficial de Wear OS clasifica el acceso a red como *very high impact*, por encima de encender
     * la pantalla (D-029, `docs/bateria.md`). Un sondeo automatico seria el gasto mas caro que
     * tiene la app, y el usuario no lo pidio.
     *
     * Un `304` reusa los packs que ya se trajeron pero **vuelve a clasificar**: entre las dos
     * consultas el usuario pudo borrar un diccionario, y entonces lo que era "instalado" pasa a
     * ser "descargar" sin que el catalogo haya cambiado una coma.
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

    /** Encola la descarga de un pack del catalogo. */
    fun onDownload(pack: CatalogPack) {
        DictLog.i { "descarga pedida: ${pack.packId} (${pack.bytes / 1_048_576} MB)" }
        startDownload(pack)
    }

    /**
     * Sigue lo que WorkManager dice, y **recarga los packs cuando uno termina**.
     *
     * ⚠️ Sin esa recarga el pack estaria en disco y la app no lo veria hasta el proximo arranque:
     * el escaneo es una sola vez, en este mismo `init`. Y despues se vuelve a clasificar, para que
     * la fila pase de "descargar" a estar arriba, entre lo instalado.
     */
    private fun seguirDescargas() {
        viewModelScope.launch {
            // ⚠️ **WorkManager CONSERVA los trabajos terminados**, asi que la primera emision de
            // cada arranque trae los DONE y FAILED de sesiones anteriores. Eso causo dos defectos
            // distintos, vistos en el emulador el 2026-09-22:
            //
            //  1. Se leian como "acaba de terminar una descarga" y republicaban el catalogo
            //     estando vacio: la pantalla decia "Nada nuevo" sin que nadie hubiera preguntado.
            //  2. Se mostraban como estado actual, asi que la fila de ese pack decia "Instalado"
            //     y **dejaba de ser pulsable** -- aunque el pack se hubiera borrado y el catalogo
            //     lo estuviera ofreciendo. No habia forma de volver a bajarlo.
            //
            // Por eso la primera emision se separa: lo que ya venia terminado es **historia**.
            var historia: Set<String>? = null
            var terminadas = emptySet<String>()
            downloadStates.collect { lista ->
                if (historia == null) {
                    historia = lista.filter { it.phase in FINALES }.map { it.packId }.toSet()
                    terminadas = lista.filter { it.phase == DownloadPhase.DONE }.map { it.packId }.toSet()
                }
                // ⚠️ **Un pack sale de AMBOS registros en cuanto vuelve a moverse.** Sin esto,
                // uno que ya se habia bajado en otra sesion quedaba marcado para siempre y su
                // descarga NUEVA no contaba al terminar: el pack quedaba en disco y la app sin
                // verlo hasta el proximo arranque.
                //
                // ⚠️ Y son DOS registros, no uno: el primer intento de arreglo solo limpio
                // `historia` --lo que se ESCONDE-- y dejo `terminadas` --lo que ya se CONTO--,
                // asi que la fase se veia bien en pantalla y aun asi no se recargaba nada. Lo
                // delato el emulador, no un test: el sintoma era la ausencia de una linea de log.
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
                // ⚠️ Solo si el usuario YA consulto. Reclasificar sobre un catalogo que nunca se
                // trajo publica una lista vacia, que se lee como "no hay nada nuevo".
                if (_state.value.catalog is CatalogState.Ready) publicar(opened.map { it.metadata })
            }
        }
    }

    /** Las fases en que un trabajo ya no avanza. WorkManager las conserva entre sesiones. */
    private val FINALES = setOf(DownloadPhase.DONE, DownloadPhase.FAILED)

    /** Clasifica lo ultimo que se trajo contra lo que hay instalado AHORA. */
    private fun publicar(instalados: List<PackMetadata>) {
        val listado = Catalog.classify(catalogPacks, instalados)
        DictLog.i {
            val porEstado = listado.offers.groupingBy { it.status }.eachCount()
            "catalogo: " + porEstado.entries.joinToString(" ") { "${it.key}=${it.value}" }
                .ifEmpty { "nada que ofrecer" } +
                // Los descartados no se listan, pero SI se cuentan en el log: sin esto, un
                // catalogo entero rechazado por version se ve igual que uno vacio.
                if (listado.needsAppUpdate) " (hay packs para una version mas nueva de la app)" else ""
        }
        _state.update {
            it.copy(catalog = CatalogState.Ready(listado.offers, listado.needsAppUpdate))
        }
    }

    /** Lo ultimo que dijo el catalogo, para poder reclasificarlo tras un 304. */
    private var catalogPacks: List<cl.fadiaz.dictionary.data.CatalogPack> = emptyList()

    /** El `ETag` de la ultima respuesta. Es lo que hace barato apretar el boton dos veces. */
    private var catalogEtag: String? = null

    /**
     * Cambia el IDIOMA en el que se busca, y deriva el pack que lo representa.
     *
     * ⚠️ **Antes recibia un `packId` y eso dejo de alcanzar**: un pack bidireccional habla dos
     * idiomas, asi que elegirlo no dice en cual buscar. El chip manda un idioma; el pack sale de
     * las mismas reglas que eligen representante en el selector.
     */
    fun onLanguageChange(lang: String) {
        // Entre los que se consultan, no entre los abiertos: tocar el chip de un idioma no puede
        // activar un build viejo que la selección ya descartó.
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
        // Con el teclado abierto NO se busca (D-128): la lista esta tapada por el teclado, asi
        // que buscar ahi es trabajo que nadie ve y que cuesta lo unico que importa -- la
        // reestructuracion de la lista se lleva el foco del campo, y el teclado detras.
        if (!typing) queries.value = text
    }

    /**
     * El teclado se abrio o se cerro.
     *
     * Mientras esta abierto se escribe sin buscar; al cerrarse --o al tocar Buscar, que cierra el
     * teclado-- se busca **una vez** lo que quedo escrito.
     *
     * Es un metodo y no un booleano del estado porque el cierre tiene un efecto: dispara la
     * busqueda. Un flag que alguien pone y saca no lo tendria.
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
     * El usuario dejó la app: guardar lo pendiente y volver al inicio limpio.
     *
     * ⚠️ **Existe porque un reloj no se "cierra", se baja la muñeca**, y volver tres horas
     * después a la ficha de `esdrújula` con `esdrú` escrito no es retomar nada: es encontrarse
     * con la pantalla de otro momento. Pedido: *«que no quede en segundo plano sino que se
     * cierre y guarde todo para que la siguiente vez te lleve a la pantalla de inicio limpia»*.
     *
     * ⚠️ **No se mata el proceso, y eso es deliberado.** Terminar la Activity obligaría a
     * reabrir los packs --medido: 500 ms en frío contra 278 tibio con 372,6 MB abiertos-- para
     * ahorrar una memoria que el sistema ya sabe reclamar solo. Lo que se tira es el **estado de
     * pantalla**, que es lo que molesta; lo que se conserva son los descriptores, que es lo que
     * cuesta.
     *
     * El historial y las guardadas ya se persisten en cada cambio, así que acá no hay nada más
     * que escribir: se nombra igual para que el día que aparezca algo diferido tenga dónde ir.
     */
    /**
     * Marca que el input del sistema está por tapar la app, para no confundirlo con salir.
     *
     * `ACTION_REMOTE_INPUT` abre una Activity de SysUI a pantalla completa, así que la nuestra
     * recibe `ON_STOP` exactamente igual que cuando el usuario se va. Sin esta marca, dictar una
     * palabra borraría la pantalla a la que se vuelve con el resultado.
     */
    fun onSystemInputOpening() {
        systemInputPending = true
    }

    /** ¿Este `ON_STOP` lo causó el input del sistema? Se consume: sólo vale para el primero. */
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
        ),
    )

    /**
     * Anota una visita cuando solo se tiene el id: la palabra del dia, una entrada del historial,
     * o una palabra tocada dentro de una glosa.
     *
     * **Los tres caminos no anotaban nada** (D-129), que es de donde salia el "a veces" del
     * reporte: el historial se actualizaba al abrir un RESULTADO DE BUSQUEDA y no al abrir de
     * ninguna otra forma.
     *
     * Cuesta una lectura de la fila por rowid --la misma que la pantalla de entrada hace un
     * instante despues-- y no cae al pack activo si el pedido no es de un pack abierto: caer
     * seria anotar otra palabra con el lema correcto, que es el bug de D-080.
     */
    fun recordVisit(packId: String, entryId: Long) {
        val pack = opened.firstOrNull { it.metadata.packId == packId } ?: return
        viewModelScope.launch {
            val header = pack.summary(entryId) ?: return@launch
            recordVisit(Visit(packId, header.entryId, header.headword, header.partOfSpeech))
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
     * De los packs abiertos, cuales se **ofrecen**: al selector, a la palabra del dia, a la
     * pantalla de diccionarios y a los creditos.
     *
     * ⚠️ **La regla era *"si hay algun pack instalado, esconder TODOS los del APK"*, y con los
     * nucleos reales adentro eso escondia un diccionario entero.** Escrita para D-088, cuando lo
     * incluido era un juguete de 28 entradas cuya etiqueta chocaba con la del pack real: el
     * selector leia *ES* y *ES* sin forma de distinguirlos. D-175 reemplazo ese juguete por los
     * **nucleos de espanol e ingles**, y la regla no se volvio a mirar.
     *
     * **El defecto, medido con `elNucleoDeOtroIdiomaSOBREVIVE...`**: con el espanol completo
     * descargado, `opened.any { !isBundled }` es verdadero y el filtro se lleva **los dos**
     * nucleos. `en-core.db` queda instalado, abierto y consultable, y **sin chip**: el usuario
     * pierde el ingles entero de la interfaz sin que nada falle ni se loguee. Es la forma exacta
     * del bug que este repo no puede ver.
     *
     * **La regla que lo cierra: un pack del APK se hace a un lado solo si otro pack ya habla
     * TODOS sus idiomas.** Es la misma forma que la contencion de `packsToQuery` --hacerse a un
     * lado por quien te contiene-- y conserva lo que D-088 queria: con el espanol completo
     * instalado, `es-core` se esconde; `en-core` no, porque nadie mas habla ingles.
     *
     * ⚠️ **El respaldo es `opened` y no `all`, y eso dejo de ser lo mismo.** Mientras `all`
     * solo traia packs abiertos los dos eran identicos; ahora trae tambien los rechazados, y
     * devolverlos aca los pondria en el selector del inicio -- un chip de idioma que no
     * busca nada. Los rechazados van por su propio canal, a la pantalla de diccionarios.
     */
    private fun offerable(all: List<PackHandle>): List<PackHandle> {
        val opened = all.filterIsInstance<PackHandle.Open>()
        val cubiertos = opened.filterNot { it.isBundled }
            .flatMap { it.metadata.langs }
            .toSet()
        return opened
            .filterNot { pack -> pack.isBundled && pack.metadata.langs.all { it in cubiertos } }
            // Un pack que no declara ningun idioma cumpliria `all {}` de forma vacia y se
            // escaparia por el filtro. No puede pasar --`meta.langs` es obligatoria desde el
            // esquema 4-- y por eso mismo no se paga con quedarse sin nada que ofrecer.
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
        // ⚠️ **La regla de D-200 también vale acá, y el tile es el peor sitio para que falle.**
        // Un pack de traducción no genera palabra del día, pero el pack ACTIVO puede serlo --es
        // el más grande, así que `chooseActive` lo prefiere-- y este método recibía el activo a
        // secas. El resultado habría sido una palabra del día de un diccionario que no define
        // nada, **en la superficie que nadie abre a propósito**: un error que no se reporta.
        //
        // Si no hay ningún diccionario de definiciones del idioma activo, no se cachea nada y el
        // tile muestra lo que ya tenía. Es la degradación correcta: mejor sin palabra que con
        // una que al tocarla no explica nada.
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
                // ⚠️ **La glosa se lee ACÁ y no en el tile**, y ésa es la mitad del diseño:
                // abrir una entrada descomprime su payload, y `onTileRequest` es `@MainThread`
                // con diez segundos (D-106). La app, que ya tiene el pack abierto, lo deja
                // escrito. Son siete lecturas una vez al día.
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
     * El diccionario de DEFINICIONES que representa al idioma del pack dado, o null.
     *
     * Prefiere el propio pack si ya define, y si no, el más grande de su idioma que sí lo haga —
     * la misma regla de representante que usa el selector.
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
     * Borra un diccionario del disco, esté cargado o no.
     *
     * ⚠️ **Resuelve contra las DOS listas.** Un pack rechazado no está en `available` --no se
     * ofrece para buscar-- pero ocupa lugar en el disco y borrarlo es la única acción que queda
     * sobre él. Buscarlo sólo entre los abiertos hacía que el botón de su fila no hiciera nada,
     * que es peor que no tener botón.
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
     * Resuelve los mismos `norms` en el primer pack instalado de `lang`, que **no** es el de la
     * entrada.
     *
     * ⚠️ **Se busca por IDIOMA y no por `pack_id`, y esa es la decisión que evita que el enlace
     * muera.** Declarar el pack destino por nombre haría que un usuario con el **núcleo** inglés
     * instalado y no el completo perdiera todos los enlaces, aunque tenga un diccionario inglés
     * perfectamente capaz de resolverlos.
     *
     * Devuelve vacío si no hay ninguno, y entonces el término se muestra **sin pintar** — que es
     * lo correcto: una palabra pintada que no navega es peor que una sin pintar (D-084).
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
         * Cuantas entradas recientes se RECUERDAN. Cuantas se MUESTRAN lo decide la pantalla.
         *
         * Eran tres, atadas a la aritmetica de D-073 --"la pantalla da tres filas de 48 dp"--, y
         * eso hacia que el almacenamiento dependiera de un reloj concreto: en uno mas grande
         * sobraba lugar y el historial seguia teniendo tres.
         *
         * Ahora es el **techo de lo que cualquier pantalla podria mostrar** (`rowsThatFit` tope
         * en 8) y el inicio recorta a lo que entra de verdad. Guardar ocho sigue siendo gratis en
         * bytes; lo que se cuidaba era no mostrar lo que nadie ve, y de eso se ocupa la pantalla.
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
