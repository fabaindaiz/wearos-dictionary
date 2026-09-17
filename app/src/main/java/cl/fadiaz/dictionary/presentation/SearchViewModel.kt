package cl.fadiaz.dictionary.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cl.fadiaz.dictionary.core.DictionarySource
import cl.fadiaz.dictionary.core.Entry
import cl.fadiaz.dictionary.core.Suggestion
import cl.fadiaz.dictionary.data.PackLoad
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** Lo que la pantalla de busqueda necesita saber, y nada mas. */
data class SearchState(
    val query: String = "",
    val results: List<Suggestion> = emptyList(),
    val status: Status = Status.Loading,
    val packName: String = "",
    val attribution: String = "",
    val license: String = "",
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
    private val abrirPack: suspend (onExtracting: () -> Unit) -> PackLoad,
) : ViewModel() {

    private var source: DictionarySource? = null

    private val queries = MutableStateFlow("")
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            when (val result = abrirPack { _state.update { it.copy(status = SearchState.Status.Installing) } }) {
                is PackLoad.Ready -> {
                    val meta = result.source.metadata
                    source = result.source
                    _state.update {
                        it.copy(
                            status = SearchState.Status.Ready,
                            packName = meta.name,
                            // D-031: la atribucion sale del pack, no de una constante. Un pack
                            // de otra fuente trae su propia licencia y tiene que mostrarse.
                            attribution = meta.attribution,
                            license = meta.license,
                        )
                    }
                }

                PackLoad.NoPack -> _state.update {
                    it.copy(status = SearchState.Status.Failed("No hay ningún diccionario instalado."))
                }

                is PackLoad.Unusable -> _state.update {
                    it.copy(status = SearchState.Status.Failed(result.reason))
                }
            }
        }

        viewModelScope.launch {
            // El debounce es de bateria antes que de rendimiento: en un reloj, disparar una
            // consulta por pulsacion mantiene la CPU despierta durante toda la frase.
            queries.debounce(DEBOUNCE_MS).map { text -> text to source }
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

    fun onQueryChange(text: String) {
        // La query se muestra YA y los resultados llegan despues: si el campo esperara al
        // debounce, escribir se sentiria trabado.
        _state.update { it.copy(query = text) }
        queries.value = text
    }

    suspend fun entry(entryId: Long): Entry? = source?.entry(entryId)

    /** Cierra el pack. Publico para que un test pueda ejercitarlo sin simular el ciclo de vida. */
    fun cerrar() {
        source?.close()
        source = null
    }

    override fun onCleared() = cerrar()

    companion object {
        /** Lo que tarda un dedo en encadenar dos letras en una pantalla de reloj. */
        const val DEBOUNCE_MS: Long = 120
    }
}

private inline fun MutableStateFlow<SearchState>.update(bloque: (SearchState) -> SearchState) {
    value = bloque(value)
}
