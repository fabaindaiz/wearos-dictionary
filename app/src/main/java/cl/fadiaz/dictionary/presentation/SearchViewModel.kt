package cl.fadiaz.dictionary.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cl.fadiaz.dictionary.core.DictionarySource
import cl.fadiaz.dictionary.core.Entry
import cl.fadiaz.dictionary.core.Suggestion
import cl.fadiaz.dictionary.core.PackMetadata
import cl.fadiaz.dictionary.data.PackHandle
import cl.fadiaz.dictionary.data.PackSet
import cl.fadiaz.dictionary.data.Visita
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
    val activo: PackMetadata? = null,
    /** Todos los packs que la app conoce, extraidos o no. Es lo que dibuja el selector. */
    val disponibles: List<PackHandle> = emptyList(),
    /** Packs que estaban y no abrieron. Se muestran en la atribucion, no en la busqueda. */
    val problemas: List<String> = emptyList(),
    val modo: Modo = Modo.NORMAL,
    /** Las ultimas entradas abiertas, ya filtradas: solo las de packs que estan instalados. */
    val historial: List<Visita> = emptyList(),
) {
    /**
     * Por que camino salieron los resultados que se estan mostrando.
     *
     * No hay una segunda lista: `results` es la misma, y `MatchKind.DEFINITION` ya hace que cada
     * fila se etiquete sola. Un modo y no un `Boolean` porque hay tres estados y el intermedio
     * --buscando-- tiene que verse: el indice de texto libre es mucho mas grande que el de lemas.
     */
    enum class Modo { NORMAL, BUSCANDO_DEFINICIONES, DEFINICIONES }

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
    private val abrirPacks: suspend (onExtracting: () -> Unit) -> PackSet,
    /** El pack de la ultima vez, o el idioma del reloj. Nunca el orden alfabetico. */
    private val preferido: () -> String? = { null },
    private val recordar: (packId: String) -> Unit = {},
    /** El historial persistido. Entra por parametro porque vive en Android (D-072). */
    private val historialGuardado: () -> List<Visita> = { emptyList() },
    private val guardarHistorial: (List<Visita>) -> Unit = {},
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
    private var abiertos: List<DictionarySource> = emptyList()

    /**
     * La busqueda por definicion en vuelo.
     *
     * Se guarda para poder cancelarla: recorre un indice mucho mas grande que el de lemas, asi
     * que puede seguir viva cuando el usuario ya escribio otra cosa. Sin esto, su resultado
     * aterriza encima del nuevo y muestra otra palabra, sin ninguna excepcion.
     */
    private var definiciones: Job? = null

    /**
     * El historial completo, sin filtrar.
     *
     * Se filtra al MOSTRAR y no se poda al guardar: desinstalar un pack y volver a instalarlo es
     * un flujo real de desarrollo, y asi el historial reaparece solo. Lo que no puede pasar es
     * mostrar una fila que al tocarla no abre nada.
     */
    private var visitas: List<Visita> = emptyList()

    private val queries = MutableStateFlow("")
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            visitas = historialGuardado()
            when (val result = abrirPacks { _state.update { it.copy(status = SearchState.Status.Installing) } }) {
                is PackSet.Ready -> {
                    abiertos = result.todos.filterIsInstance<PackHandle.Abierto>().map { it.source }
                    val elegido = elegirActivo(result, preferido())
                    source.value = elegido.source
                    _state.update {
                        it.copy(
                            status = SearchState.Status.Ready,
                            // D-031: la atribucion sale del pack, no de una constante. Un pack
                            // de otra fuente trae su propia licencia y tiene que mostrarse.
                            activo = elegido.metadata,
                            // El de demostracion no se ofrece si hay un diccionario de verdad:
                            // es un placeholder, no una opcion. Ademas su etiqueta chocaria --
                            // con el toy y el español real el selector decia "ES" y "ES".
                            disponibles = ofrecibles(result.todos),
                            problemas = result.problemas,
                            historial = visibles(visitas),
                        )
                    }
                }

                PackSet.NoPack -> _state.update {
                    it.copy(status = SearchState.Status.Failed("No hay ningún diccionario instalado."))
                }

                is PackSet.Unusable -> _state.update {
                    it.copy(status = SearchState.Status.Failed(result.reason))
                }
            }
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
                    if (text == queries.value && _state.value.modo == SearchState.Modo.NORMAL) {
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
    fun onPackChange(packId: String) {
        val pack = abiertos.firstOrNull { it.metadata.packId == packId } ?: return
        // El combine va a repetir la query por prefijo en el pack nuevo y pisaria los resultados
        // de definicion igual: mejor salir del modo explicitamente que dejar la carrera abierta.
        volverAModoNormal()
        source.value = pack
        _state.update { it.copy(activo = pack.metadata) }
        recordar(packId)
    }

    fun onQueryChange(text: String) {
        // Editar la query es la forma de volver de las definiciones a la busqueda normal. No hay
        // boton de "volver" a proposito: en 192 dp un control se paga en resultados, y el usuario
        // ya tiene el gesto.
        volverAModoNormal()
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
        val texto = queries.value
        if (texto.isBlank()) return

        definiciones?.cancel()
        _state.update { it.copy(modo = SearchState.Modo.BUSCANDO_DEFINICIONES) }
        definiciones = viewModelScope.launch {
            val encontrados = pack.searchDefinitions(texto)
            // Si mientras tanto se escribio otra cosa, este resultado ya no es el que se muestra.
            if (texto == queries.value) {
                _state.update {
                    it.copy(results = encontrados, modo = SearchState.Modo.DEFINICIONES)
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
    fun registrarVisita(sugerencia: Suggestion) {
        val visita = Visita(
            packId = sugerencia.packId,
            entryId = sugerencia.entryId,
            headword = sugerencia.headword,
            partOfSpeech = sugerencia.partOfSpeech,
        )
        // Move-to-front: abrir dos veces la misma palabra la sube, no la duplica.
        visitas = (listOf(visita) + visitas.filterNot {
            it.packId == visita.packId && it.entryId == visita.entryId
        }).take(HISTORIAL_MAX)
        guardarHistorial(visitas)
        _state.update { it.copy(historial = visibles(visitas)) }
    }

    private fun ofrecibles(todos: List<PackHandle>): List<PackHandle> {
        val abiertos = todos.filterIsInstance<PackHandle.Abierto>()
        return if (abiertos.any { !it.esDemo }) abiertos.filterNot { it.esDemo } else todos
    }

    /** Solo las de packs abiertos: una fila que no abre nada es peor que no tener la fila. */
    private fun visibles(todas: List<Visita>): List<Visita> {
        val instalados = abiertos.map { it.metadata.packId }.toSet()
        return if (instalados.isEmpty()) todas else todas.filter { it.packId in instalados }
    }

    private fun volverAModoNormal() {
        definiciones?.cancel()
        definiciones = null
        if (_state.value.modo != SearchState.Modo.NORMAL) {
            _state.update { it.copy(modo = SearchState.Modo.NORMAL, results = emptyList()) }
        }
    }

    /**
     * Abre una entrada **en su propio pack**.
     *
     * Devuelve null si ese pack no esta abierto, en vez de caer al activo: caer seria el bug que
     * esto arregla --mostrar otra palabra-- pero silencioso.
     */
    suspend fun entry(packId: String, entryId: Long): Entry? =
        abiertos.firstOrNull { it.metadata.packId == packId }?.entry(entryId)

    /**
     * Que palabras de una glosa son lema, **en el pack de esa entrada** y no en el activo.
     *
     * Misma regla que [entry]: si el pack no esta abierto devuelve vacio en vez de caer al
     * activo. Caer seria pintar tocable una palabra que abre otra distinta, que es exactamente
     * el bug que arreglo D-080, pero mudo.
     */
    suspend fun resolver(packId: String, norms: Set<String>): Map<String, Long> =
        abiertos.firstOrNull { it.metadata.packId == packId }
            ?.resolveHeadwords(norms)
            .orEmpty()

    /** Cierra el pack. Publico para que un test pueda ejercitarlo sin simular el ciclo de vida. */
    fun cerrar() {
        abiertos.forEach { it.close() }
        abiertos = emptyList()
        source.value = null
    }

    override fun onCleared() = cerrar()

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
        const val HISTORIAL_MAX: Int = 3

        /**
         * Que pack se abre al arrancar. **Nunca el orden alfabetico**: con dos packs eso hacia
         * que un reloj en español arrancara en ingles, porque "en-" ordena antes que "es-".
         *
         * Tres escalones: el pack exacto de la ultima vez, cualquiera de ese idioma --que cubre
         * el caso de no tener preferencia guardada y usar el idioma del reloj-- y por ultimo el
         * que venga.
         */
        internal fun elegirActivo(set: PackSet.Ready, preferido: String?): PackHandle.Abierto {
            val abiertos = set.todos.filterIsInstance<PackHandle.Abierto>()
            // El pack de demostracion solo gana si no hay ningun otro: existe para que la app
            // recien instalada tenga algo que mostrar, no para tapar un diccionario de verdad.
            val candidatos = abiertos.filterNot { it.esDemo }.ifEmpty { abiertos }
            return candidatos.firstOrNull { it.packId == preferido }
                ?: candidatos.firstOrNull { it.metadata.langSource == preferido }
                ?: candidatos.firstOrNull()
                ?: set.activo
        }
    }
}

private inline fun MutableStateFlow<SearchState>.update(bloque: (SearchState) -> SearchState) {
    value = bloque(value)
}
