package cl.fadiaz.dictionary.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cl.fadiaz.dictionary.core.DictionarySource
import cl.fadiaz.dictionary.core.Entry
import cl.fadiaz.dictionary.core.Suggestion
import cl.fadiaz.dictionary.core.PackMetadata
import cl.fadiaz.dictionary.core.TextNormalizer
import cl.fadiaz.dictionary.data.PackHandle
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Lo que la pantalla de busqueda necesita saber, y nada mas. */
data class SearchState(
    val query: String = "",
    val results: List<Suggestion> = emptyList(),
    val status: Status = Status.Loading,
    /** El pack en el que se esta buscando. Su `attribution` y `license` son las que se muestran. */
    val active: PackMetadata? = null,
    /** Todos los packs que la app conoce, extraidos o no. Es lo que dibuja el selector. */
    val available: List<PackHandle> = emptyList(),
    /** Packs que estaban y no abrieron. Se muestran en la atribucion, no en la busqueda. */
    val problems: List<String> = emptyList(),
    val mode: Mode = Mode.NORMAL,
    /** Las ultimas entradas abiertas, ya filtradas: solo las de packs que estan instalados. */
    val history: List<Visit> = emptyList(),
    /**
     * La entrada de hoy, **una por diccionario cargado**, por `packId`.
     *
     * Un mapa y no una sola: con dos idiomas instalados las dos palabras del dia interesan, y
     * ademas cambiar de idioma no tiene que recalcular nada. Vacio mientras se calculan, si los
     * packs estan vacios, o si nadie cableo `fechaDeHoy` --que es una ausencia visible--.
     */
    val wordsOfTheDay: Map<String, EntrySummary> = emptyMap(),
    val settings: Settings = Settings(),
    val favorites: List<Visit> = emptyList(),
) {
    /**
     * Por que camino salieron los resultados que se estan mostrando.
     *
     * No hay una segunda lista: `results` es la misma, y `MatchKind.DEFINITION` ya hace que cada
     * fila se etiquete sola. Un modo y no un `Boolean` porque hay tres estados y el intermedio
     * --buscando-- tiene que verse: el indice de texto libre es mucho mas grande que el de lemas.
     */
    enum class Mode { NORMAL, BUSCANDO_DEFINICIONES, DEFINICIONES }

    sealed interface Status {
        data object Loading : Status

        /** Extrayendo el pack del APK. Tarda, y la pantalla tiene que decir por que. */
        data object Installing : Status

        data object Ready : Status

        data class Failed(val message: String) : Status
    }
}

/**
 * La busqueda incremental.
 *
 * Recibe `abrirPack` en vez de construirlo: es lo unico que este ViewModel necesitaba de
 * Android, y sacarlo lo deja testeable en la JVM dentro del gate. Ver `SearchViewModelTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SearchViewModel(
    private val openPacks: suspend (onExtracting: () -> Unit) -> PackSet,
    /** El pack de la ultima vez, o el idioma del reloj. Nunca el orden alfabetico. */
    private val preferred: () -> String? = { null },
    private val saveActivePack: (packId: String) -> Unit = {},
    /** El historial persistido. Entra por parametro porque vive en Android (D-072). */
    private val savedHistory: () -> List<Visit> = { emptyList() },
    private val saveHistory: (List<Visit>) -> Unit = {},
    /**
     * Hoy, como "AAAA-MM-DD". Entra por parametro y no sale de un reloj de sistema acá adentro:
     * es lo que deja que la politica de [PalabraDelDia] corra entera en la JVM (D-072).
     *
     * El default devuelve null --sin fecha no hay palabra del dia-- para que olvidarse de
     * cablearlo se vea en pantalla como una ausencia, y no como una palabra que nunca cambia.
     */
    private val todayDate: () -> String? = { null },
    private val savedSettings: () -> Settings = { Settings() },
    private val saveSettings: (Settings) -> Unit = {},
    /**
     * Borra el archivo de un pack. Devuelve si habia algo que borrar.
     *
     * Entra por parametro como todo lo que toca Android (D-072), y recibe el **nombre de
     * archivo** y no el packId: son cosas distintas.
     */
    private val deleteFromDisk: (fileName: String) -> Boolean = { false },
    private val savedFavorites: () -> List<Visit> = { emptyList() },
    private val saveFavorites: (List<Visit>) -> Unit = {},
    /**
     * La semana de palabras que ya estaba cacheada para el tile: desde que dia, y cuales.
     *
     * Se consulta para **no recalcularla en cada arranque**: son [ContenidoDeTiles.DIAS_CACHEADOS]
     * x [PalabraDelDia.CANDIDATOS] lecturas y solo cambian una vez por dia.
     */
    private val savedWeekWords: () -> Pair<String?, List<Visit>> =
        { null to emptyList() },
    private val saveWeekWords: (since: String, words: List<Visit>) -> Unit =
        { _, _ -> },
    /**
     * Avisa a los tiles que lo que muestran cambio.
     *
     * Entra por parametro porque `TileService.getUpdater` es Android (D-072). No es opcional: el
     * tile de historial se pide con `freshnessIntervalMillis = 0`, o sea que **el sistema no lo
     * vuelve a llamar solo**; sin este empujon se queda con lo que tenia al instalarse.
     */
    private val notifyTiles: () -> Unit = {},
) : ViewModel() {

    /**
     * El pack es un flow y no un `var`, y esa es la diferencia entre buscar y no buscar.
     *
     * El pack de español pesa 69 MB y tarda en abrir, mientras la pantalla ya acepta texto. Con
     * un `var`, lo escrito durante ese rato se consultaba contra `null`, devolvia vacio y
     * **nada lo volvia a intentar**: la busqueda quedaba muerta hasta que el usuario borraba una
     * letra. Siendo un flow, abrir el pack es un evento que vuelve a disparar la consulta.
     */
    private val source = MutableStateFlow<DictionarySource?>(null)

    /** Todos los abiertos, para resolver entradas de cualquier pack y para cerrarlos. */
    private var opened: List<DictionarySource> = emptyList()

    /**
     * La busqueda por definicion en vuelo.
     *
     * Se guarda para poder cancelarla: recorre un indice mucho mas grande que el de lemas, asi
     * que puede seguir viva cuando el usuario ya escribio otra cosa. Sin esto, su resultado
     * aterriza encima del nuevo y muestra otra palabra, sin ninguna excepcion.
     */
    private var definitionMode: Job? = null

    /**
     * El historial completo, sin filtrar.
     *
     * Se filtra al MOSTRAR y no se poda al guardar: desinstalar un pack y volver a instalarlo es
     * un flujo real de desarrollo, y asi el historial reaparece solo. Lo que no puede pasar es
     * mostrar una fila que al tocarla no abre nada.
     */
    private var visits: List<Visit> = emptyList()
    private var favoriteVisits: List<Visit> = emptyList()

    private val queries = MutableStateFlow("")
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    init {
        favoriteVisits = savedFavorites()
        _state.update { it.copy(settings = savedSettings(), favorites = favoriteVisits) }
        viewModelScope.launch {
            visits = savedHistory()
            loadPacks()
        }

        viewModelScope.launch {
            combine(
                // El debounce es de bateria antes que de rendimiento: en un reloj, disparar una
                // consulta por pulsacion mantiene la CPU despierta durante toda la frase.
                queries.debounce(DEBOUNCE_MS),
                source,
            ) { text, pack -> text to pack }
                // mapLatest cancela la busqueda anterior en cuanto llega una tecla nueva. La
                // cascada chequea cancelacion fila por fila, asi que la vieja se corta de verdad
                // en vez de terminar y descartarse.
                .mapLatest { (text, pack) ->
                    if (text.isBlank() || pack == null) text to emptyList()
                    else text to pack.suggest(text)
                }
                .onEach { (text, results) ->
                    // Se compara contra la query vigente: si el usuario siguio escribiendo
                    // mientras esta consulta corria, su resultado ya no es el que se muestra.
                    // Y no se publica en modo definiciones: ahi manda la otra consulta.
                    if (text == queries.value && _state.value.mode == SearchState.Mode.NORMAL) {
                        _state.update { it.copy(query = text, results = results) }
                    }
                }
                .collect {}
        }
    }

    /**
     * Cambia el diccionario activo sin perder lo escrito.
     *
     * Le asigna otro valor al mismo flow, asi que el `combine` repite la query vigente y
     * `mapLatest` cancela la consulta anterior. No hay maquina de estados nueva.
     *
     * **Los resultados viejos no se limpian**: la consulta nueva tarda milisegundos y un
     * parpadeo en blanco se ve peor que una lista vieja por un instante. Ningun test puede ver
     * esa diferencia, por eso queda dicha aca.
     */
    /**
     * Abre los packs y publica el estado. Se llama al arrancar y **cada vez que la lista de
     * diccionarios cambia** --hoy, al borrar uno--.
     *
     * Es `suspend` y no lanza su propia corrutina para que quien la llama controle el orden:
     * borrar exige cerrar las conexiones ANTES de tocar el disco, y eso no se puede hacer si
     * esta funcion se dispara sola.
     */
    private suspend fun loadPacks() {
        when (val result = openPacks { _state.update { it.copy(status = SearchState.Status.Installing) } }) {
            is PackSet.Ready -> {
                opened = result.all.filterIsInstance<PackHandle.Open>().map { it.source }
                val chosen = chooseActive(result, preferred())
                source.value = chosen.source
                _state.update {
                    it.copy(
                        status = SearchState.Status.Ready,
                        // D-031: la atribucion sale del pack, no de una constante. Un pack
                        // de otra fuente trae su propia licencia y tiene que mostrarse.
                        active = chosen.metadata,
                        // El de demostracion no se ofrece si hay un diccionario de verdad:
                        // es un placeholder, no una opcion. Ademas su etiqueta chocaria --
                        // con el toy y el español real el selector decia "ES" y "ES".
                        available = offerable(result.all),
                        problems = result.problems,
                        history = visibleOnes(visits),
                    )
                }
                // Para TODOS los ofrecidos, no solo el activo: el de demostracion queda
                // fuera porque `ofrecibles` ya lo saco cuando hay un diccionario de verdad.
                refreshWordsOfTheDay(
                    offerable(result.all).filterIsInstance<PackHandle.Open>()
                        .map { it.source },
                )
                cacheWeekForTile(chosen.source)
            }

            PackSet.NoPack -> _state.update {
                it.copy(status = SearchState.Status.Failed("No hay ningún diccionario instalado."))
            }

            is PackSet.Unusable -> _state.update {
                it.copy(status = SearchState.Status.Failed(result.reason))
            }
        }
    }

    fun onPackChange(packId: String) {
        val pack = opened.firstOrNull { it.metadata.packId == packId } ?: return
        // El combine va a repetir la query por prefijo en el pack nuevo y pisaria los resultados
        // de definicion igual: mejor salir del modo explicitamente que dejar la carrera abierta.
        leaveDefinitionMode()
        source.value = pack
        _state.update { it.copy(active = pack.metadata) }
        // No se recalcula nada para la PANTALLA: las palabras del dia de todos los packs ya
        // estan. El tile si, porque muestra una sola y es la del activo -- dejarlo en el idioma
        // anterior seria una palabra equivocada que nadie reporta, porque nadie abre un tile a
        // proposito.
        cacheWeekForTile(pack)
        saveActivePack(packId)
    }

    fun onQueryChange(text: String) {
        // Editar la query es la forma de volver de las definiciones a la busqueda normal. No hay
        // boton de "volver" a proposito: en 192 dp un control se paga en resultados, y el usuario
        // ya tiene el gesto.
        leaveDefinitionMode()
        // La query se muestra YA y los resultados llegan despues: si el campo esperara al
        // debounce, escribir se sentiria trabado.
        _state.update { it.copy(query = text) }
        queries.value = text
    }

    /**
     * Busca la query vigente DENTRO de las definiciones, por FTS5.
     *
     * Es una accion explicita y nunca se cuelga del pipeline incremental: recorre un indice mucho
     * mas grande que el de lemas y no cumple el presupuesto de latencia de escribir.
     */
    fun onSearchDefinitions() {
        val pack = source.value ?: return
        val text = queries.value
        if (text.isBlank()) return

        definitionMode?.cancel()
        _state.update { it.copy(mode = SearchState.Mode.BUSCANDO_DEFINICIONES) }
        definitionMode = viewModelScope.launch {
            val found = pack.searchDefinitions(text)
            // Si mientras tanto se escribio otra cosa, este resultado ya no es el que se muestra.
            if (text == queries.value) {
                _state.update {
                    it.copy(results = found, mode = SearchState.Mode.DEFINICIONES)
                }
            }
        }
    }

    /**
     * Anota que se abrio una entrada. Se llama al navegar, no al volver.
     *
     * La `Suggestion` ya trae los cuatro campos, asi que registrar no cuesta abrir la entrada ni
     * descomprimir un payload.
     */
    fun recordVisit(suggestion: Suggestion) {
        val visit = Visit(
            packId = suggestion.packId,
            entryId = suggestion.entryId,
            headword = suggestion.headword,
            partOfSpeech = suggestion.partOfSpeech,
        )
        // Move-to-front: abrir dos veces la misma palabra la sube, no la duplica.
        visits = (listOf(visit) + visits.filterNot {
            it.packId == visit.packId && it.entryId == visit.entryId
        }).take(MAX_HISTORY)
        saveHistory(visits)
        _state.update { it.copy(history = visibleOnes(visits)) }
        notifyTiles()
    }

    private fun offerable(all: List<PackHandle>): List<PackHandle> {
        val opened = all.filterIsInstance<PackHandle.Open>()
        return if (opened.any { !it.isDemo }) opened.filterNot { it.isDemo } else all
    }

    /** Solo las de packs abiertos: una fila que no abre nada es peor que no tener la fila. */
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
     * Abre una entrada **en su propio pack**.
     *
     * Devuelve null si ese pack no esta abierto, en vez de caer al activo: caer seria el bug que
     * esto arregla --mostrar otra palabra-- pero silencioso.
     */
    /**
     * Calcula la palabra del dia de cada diccionario cargado.
     *
     * En su propia corrutina y sin bloquear la pantalla, que ya esta lista para buscar: son
     * [PalabraDelDia.CANDIDATOS] lecturas de una fila por pack. Cada una se publica en cuanto
     * esta, asi que con dos idiomas la primera no espera a la segunda.
     *
     * Si un pack falla se queda sin palabra del dia y los demas siguen: una pantalla de inicio
     * incompleta es mejor que una que no carga.
     */
    /**
     * Deja escrita la semana de palabras del pack activo, para que el tile no tenga que abrirlo.
     *
     * **El tile no puede calcular esto.** `onTileRequest` corre en el hilo principal con 10 s de
     * tope, y abrir un pack de 69 o 295 MB ahi esta fuera de discusion por contrato de la API,
     * no por sospecha de rendimiento. Pero la palabra es determinista por (fecha, pack), asi que
     * la app --que ya tiene el pack abierto-- puede adelantar los proximos dias y guardarlos.
     *
     * Siete dias y no uno: son las siete ventanas del `Timeline` que dejan que el renderer cambie
     * de palabra a medianoche **sin un solo despertar del proceso**.
     *
     * No se rehace si la cache ya es de hoy y del mismo pack: eso la convierte en trabajo de una
     * vez por dia en vez de una vez por arranque.
     */
    private fun cacheWeekForTile(active: DictionarySource) {
        val today = todayDate() ?: return
        val packId = active.metadata.packId
        val (since, cacheadas) = savedWeekWords()
        if (since == today && cacheadas.isNotEmpty() && cacheadas.all { it.packId == packId }) return

        viewModelScope.launch {
            val week = mutableListOf<Visit>()
            for (dia in 0 until TileContents.CACHED_DAYS) {
                val picked = runCatching {
                    WordOfTheDay.pick(
                        date = TileContents.plusDays(today, dia) ?: return@launch,
                        packId = packId,
                        entryCount = active.metadata.entryCount,
                        read = { id -> active.summary(id) },
                    )
                }.getOrNull() ?: return@launch
                week += Visit(
                    packId = packId,
                    entryId = picked.entryId,
                    headword = picked.headword,
                    partOfSpeech = picked.partOfSpeech,
                )
            }
            saveWeekWords(today, week)
            notifyTiles()
        }
    }

    private fun refreshWordsOfTheDay(packs: List<DictionarySource>) {
        val date = todayDate() ?: return
        for (pack in packs) {
            viewModelScope.launch {
                val picked = runCatching {
                    WordOfTheDay.pick(
                        date = date,
                        packId = pack.metadata.packId,
                        entryCount = pack.metadata.entryCount,
                        read = { id -> pack.summary(id) },
                    )
                }.getOrNull() ?: return@launch
                _state.update {
                    it.copy(wordsOfTheDay = it.wordsOfTheDay + (pack.metadata.packId to picked))
                }
            }
        }
    }

    /**
     * Borra un diccionario del reloj. **Irreversible**: reponerlo cuesta ~90 s por adb.
     *
     * EL ORDEN ES EL CONTRATO, y no es teorico. En Unix un archivo borrado con un descriptor
     * abierto sigue ocupando el disco hasta que se cierre, y la app lo seguiria leyendo como si
     * nada: el usuario veria "borrado" y **cero espacio liberado**, que es peor que no poder
     * borrar. Asi que primero se sueltan las conexiones, despues se toca el disco, y recien
     * despues se reabre lo que quedo.
     *
     * Se cierran TODAS y no solo la del pack que se va: `cargarPacks` reabre el set entero, y
     * dejar las viejas abiertas seria filtrar una conexion por borrado.
     *
     * El pack de demostracion **no se puede borrar**: viene dentro del APK y `PackStore.open` lo
     * re-extrae al reabrir, asi que la accion no haria nada y el pack volveria solo.
     */
    fun deletePack(packId: String) {
        val handle = state.value.available
            .filterIsInstance<PackHandle.Open>()
            .firstOrNull { it.packId == packId } ?: return
        if (handle.isDemo) return

        viewModelScope.launch {
            // Sin pack activo y en "cargando" mientras dura: una consulta que llegue en el medio
            // no puede caer sobre una conexion ya cerrada.
            _state.update {
                it.copy(
                    status = SearchState.Status.Loading,
                    wordsOfTheDay = it.wordsOfTheDay - packId,
                )
            }
            source.value = null
            close()
            deleteFromDisk(handle.fileName)
            loadPacks()
        }
    }

    /** Si esa entrada esta guardada. Por `packId` ademas del id: dos packs comparten ids. */
    fun isFavorite(packId: String, entryId: Long): Boolean =
        favoriteVisits.any { it.packId == packId && it.entryId == entryId }

    /**
     * Guarda o saca una palabra de favoritas.
     *
     * Al frente y sin duplicar, igual que el historial, pero **con un tope mucho mas alto**: el
     * historial son tres porque compite por las filas de la pantalla (D-073), y los favoritos
     * viven en su propia lista. El tope existe igual porque esto termina en un String de
     * SharedPreferences.
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

    /** Cambia la escala del texto y la deja guardada. */
    fun onTextScaleChange(scale: TextScale) {
        val fresh = state.value.settings.copy(textScale = scale)
        saveSettings(fresh)
        _state.update { it.copy(settings = fresh) }
    }

    /**
     * Vacia el historial, en memoria y en disco.
     *
     * Hacia falta: con tope de tres y move-to-front se recicla solo, pero una palabra que no
     * queres volver a ver se queda hasta que abras tres mas.
     */
    fun clearHistory() {
        visits = emptyList()
        saveHistory(visits)
        _state.update { it.copy(history = emptyList()) }
    }

    /**
     * A que entrada lleva una visita guardada, **corrigiendola si el pack se reconstruyo**.
     *
     * `entry.id` es el rowid y no sobrevive a un rebuild (D-055): el historial y las guardadas
     * se respaldan con el, asi que tras reconstruir un pack apuntan a otra palabra --con el lema
     * correcto escrito en la fila, o sea sin nada que lo delate--.
     *
     * **No cuesta una consulta extra en el caso normal**: si el lema que hay en ese id es el
     * guardado, [destinoDeVisita] devuelve `Directo` y `resolveHeadwords` no se llama. El
     * `summary` que si se paga es una lectura de la fila por rowid, que es la misma que la
     * pantalla de entrada hace igual un instante despues.
     *
     * Cuando corrige, **reescribe el respaldo**: si no, cada apertura volveria a pagar la
     * re-resolucion y el tile seguiria publicando el id viejo.
     */
    suspend fun targetOf(visit: Visit): Long? {
        val source = opened.firstOrNull { it.metadata.packId == visit.packId } ?: return null
        val atId = source.summary(visit.entryId)?.headword
        val relocated = if (atId == visit.headword) {
            null
        } else {
            val key = TextNormalizer.norm(visit.headword)
            source.resolveHeadwords(setOf(key))[key]
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

    /** Reescribe el `entryId` de esta visita en el historial y en las guardadas. */
    private fun fixEntryId(visit: Visit, entryId: Long) {
        fun fix(lista: List<Visit>) = lista.map {
            if (it.packId == visit.packId && it.entryId == visit.entryId) {
                it.copy(entryId = entryId)
            } else {
                it
            }
        }
        visits = fix(visits)
        favoriteVisits = fix(favoriteVisits)
        saveHistory(visits)
        saveFavorites(favoriteVisits)
        _state.update { it.copy(history = visibleOnes(visits), favorites = favoriteVisits) }
    }

    suspend fun entry(packId: String, entryId: Long): Entry? =
        opened.firstOrNull { it.metadata.packId == packId }?.entry(entryId)

    /**
     * Que palabras de una glosa son lema, **en el pack de esa entrada** y no en el activo.
     *
     * Misma regla que [entry]: si el pack no esta abierto devuelve vacio en vez de caer al
     * activo. Caer seria pintar tocable una palabra que abre otra distinta, que es exactamente
     * el bug que arreglo D-080, pero mudo.
     */
    suspend fun resolveIn(packId: String, norms: Set<String>): Map<String, Long> =
        opened.firstOrNull { it.metadata.packId == packId }
            ?.resolveHeadwords(norms)
            .orEmpty()

    /** Cierra el pack. Publico para que un test pueda ejercitarlo sin simular el ciclo de vida. */
    fun close() {
        opened.forEach { it.close() }
        opened = emptyList()
        source.value = null
    }

    override fun onCleared() = close()

    companion object {
        /** Lo que tarda un dedo en encadenar dos letras en una pantalla de reloj. */
        const val DEBOUNCE_MS: Long = 120

        /**
         * Cuantas entradas recientes se recuerdan.
         *
         * Tres, y sale de la misma aritmetica que D-073: la pantalla da tres filas de 48 dp.
         * Guardar diez es gratis en bytes y caro en lo unico escaso -- serian siete filas que
         * nadie ve sin scrollear el estado vacio.
         */
        const val MAX_HISTORY: Int = 3

        /**
         * Tope de favoritas. Alto a proposito --no compiten por la pantalla como el historial--
         * pero acotado porque todo esto termina en un String de SharedPreferences.
         */
        const val MAX_FAVORITES: Int = 100

        /**
         * Que pack se abre al arrancar. **Nunca el orden alfabetico**: con dos packs eso hacia
         * que un reloj en español arrancara en ingles, porque "en-" ordena antes que "es-".
         *
         * Tres escalones: el pack exacto de la ultima vez, cualquiera de ese idioma --que cubre
         * el caso de no tener preferencia guardada y usar el idioma del reloj-- y por ultimo el
         * que venga.
         */
        internal fun chooseActive(set: PackSet.Ready, preferred: String?): PackHandle.Open {
            val opened = set.all.filterIsInstance<PackHandle.Open>()
            // El pack de demostracion solo gana si no hay ningun otro: existe para que la app
            // recien instalada tenga algo que mostrar, no para tapar un diccionario de verdad.
            val candidates = opened.filterNot { it.isDemo }.ifEmpty { opened }
            return candidates.firstOrNull { it.packId == preferred }
                ?: candidates.firstOrNull { it.metadata.langSource == preferred }
                ?: candidates.firstOrNull()
                ?: set.active
        }
    }
}

private inline fun MutableStateFlow<SearchState>.update(bloque: (SearchState) -> SearchState) {
    value = bloque(value)
}
