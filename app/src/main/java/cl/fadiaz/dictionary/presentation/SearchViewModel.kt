package cl.fadiaz.dictionary.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cl.fadiaz.dictionary.core.DictionarySource
import cl.fadiaz.dictionary.core.Entry
import cl.fadiaz.dictionary.core.Suggestion
import cl.fadiaz.dictionary.data.PackStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SearchViewModel(app: Application) : AndroidViewModel(app) {

    private var source: DictionarySource? = null

    private val queries = MutableStateFlow("")
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            when (val result = PackStore.open(getApplication()) {
                _state.value = _state.value.copy(status = SearchState.Status.Installing)
            }) {
                is PackStore.Result.Ready -> {
                    source = result.source
                    val meta = result.source.metadata
                    _state.value = _state.value.copy(
                        status = SearchState.Status.Ready,
                        packName = meta.name,
                        attribution = meta.attribution,
                        license = meta.license,
                    )
                }
                PackStore.Result.NoPack -> _state.value = _state.value.copy(
                    status = SearchState.Status.Failed(
                        "No hay ningún diccionario instalado.",
                    ),
                )
                is PackStore.Result.Unusable -> _state.value = _state.value.copy(
                    status = SearchState.Status.Failed(result.reason),
                )
            }
        }

        viewModelScope.launch {
            queries
                // El debounce es de bateria antes que de rendimiento: en un reloj, disparar una
                // consulta por pulsacion mantiene la CPU despierta durante toda la frase.
                .debounce(DEBOUNCE_MS)
                // mapLatest cancela la busqueda anterior en cuanto llega una tecla nueva. La
                // cascada chequea cancelacion fila por fila, asi que la vieja se corta de verdad
                // en vez de terminar y descartarse.
                .mapLatest { text ->
                    val pack = source
                    if (text.isBlank() || pack == null) text to emptyList()
                    else text to pack.suggest(text)
                }
                .onEach { (text, results) ->
                    // Se compara contra la query vigente: si el usuario siguio escribiendo
                    // mientras esta consulta corria, su resultado ya no es el que se muestra.
                    if (text == queries.value) {
                        _state.value = _state.value.copy(query = text, results = results)
                    }
                }
                .collect {}
        }
    }

    fun onQueryChange(text: String) {
        _state.value = _state.value.copy(query = text)
        queries.value = text
    }

    suspend fun entry(entryId: Long): Entry? = source?.entry(entryId)

    override fun onCleared() {
        source?.close()
        source = null
    }

    companion object {
        /** Lo que tarda un dedo en encadenar dos letras en una pantalla de reloj. */
        const val DEBOUNCE_MS: Long = 120
    }
}
