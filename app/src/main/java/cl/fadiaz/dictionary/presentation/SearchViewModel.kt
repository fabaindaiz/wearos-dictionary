package cl.fadiaz.dictionary.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cl.fadiaz.dictionary.core.DictionarySource
import cl.fadiaz.dictionary.core.Entry
import cl.fadiaz.dictionary.core.Suggestion
import cl.fadiaz.dictionary.core.PackMetadata
import cl.fadiaz.dictionary.data.PackHandle
import cl.fadiaz.dictionary.data.PackSet
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
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
) {
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

    private val queries = MutableStateFlow("")
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
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
                            disponibles = result.todos,
                            problemas = result.problemas,
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
                    if (text == queries.value) {
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
        source.value = pack
        _state.update { it.copy(activo = pack.metadata) }
        recordar(packId)
    }

    fun onQueryChange(text: String) {
        // La query se muestra YA y los resultados llegan despues: si el campo esperara al
        // debounce, escribir se sentiria trabado.
        _state.update { it.copy(query = text) }
        queries.value = text
    }

    /**
     * Abre una entrada **en su propio pack**.
     *
     * Devuelve null si ese pack no esta abierto, en vez de caer al activo: caer seria el bug que
     * esto arregla --mostrar otra palabra-- pero silencioso.
     */
    suspend fun entry(packId: String, entryId: Long): Entry? =
        abiertos.firstOrNull { it.metadata.packId == packId }?.entry(entryId)

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
         * Que pack se abre al arrancar. **Nunca el orden alfabetico**: con dos packs eso hacia
         * que un reloj en español arrancara en ingles, porque "en-" ordena antes que "es-".
         *
         * Tres escalones: el pack exacto de la ultima vez, cualquiera de ese idioma --que cubre
         * el caso de no tener preferencia guardada y usar el idioma del reloj-- y por ultimo el
         * que venga.
         */
        internal fun elegirActivo(set: PackSet.Ready, preferido: String?): PackHandle.Abierto {
            val abiertos = set.todos.filterIsInstance<PackHandle.Abierto>()
            return abiertos.firstOrNull { it.packId == preferido }
                ?: abiertos.firstOrNull { it.metadata.langSource == preferido }
                ?: set.activo
        }
    }
}

private inline fun MutableStateFlow<SearchState>.update(bloque: (SearchState) -> SearchState) {
    value = bloque(value)
}
