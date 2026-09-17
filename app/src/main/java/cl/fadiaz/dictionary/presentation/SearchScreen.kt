package cl.fadiaz.dictionary.presentation

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import cl.fadiaz.dictionary.core.MatchKind
import cl.fadiaz.dictionary.core.Suggestion

/**
 * La pantalla de busqueda. Es la app: D-026 dice que la busqueda vive aca adentro porque ni los
 * tiles ni los widgets aceptan text input.
 *
 * Dos entradas de texto, y el orden no es estetico. La voz va primero porque es la forma
 * natural de escribir en una muñeca, y es la razon de que la busqueda tenga un nivel tolerante
 * a errores: el dictado produce texto que no coincide exactamente con ningun lema. El teclado
 * queda de fallback, y es el que ejercita la busqueda incremental con debounce.
 */
@Composable
fun SearchScreen(
    state: SearchState,
    onQueryChange: (String) -> Unit,
    onOpenEntry: (Suggestion) -> Unit,
    onOpenAttribution: () -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()

    val voz = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.let(onQueryChange)
        }
    }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(contentPadding = contentPadding, state = listState) {
            item {
                ListHeader(
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                ) { Text(state.packName.ifEmpty { "Diccionario" }) }
            }

            when (val status = state.status) {
                SearchState.Status.Loading, SearchState.Status.Installing -> item {
                    Cargando(
                        mensaje = if (status == SearchState.Status.Installing) {
                            "Instalando el diccionario. Solo pasa la primera vez."
                        } else {
                            "Abriendo el diccionario…"
                        },
                    )
                }

                is SearchState.Status.Failed -> item {
                    Text(
                        text = status.message,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    )
                }

                SearchState.Status.Ready -> {
                    item {
                        Button(
                            onClick = { voz.launch(intentDeVoz()) },
                            modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                            transformation = SurfaceTransformation(spec),
                        ) { Text("Decir una palabra") }
                    }
                    item { CampoDeTexto(state.query, onQueryChange) }

                    if (state.query.isNotBlank() && state.results.isEmpty()) {
                        item {
                            Text(
                                text = "Sin resultados para “${state.query}”",
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            )
                        }
                    }

                    items(count = state.results.size) { indice ->
                        val sugerencia = state.results[indice]
                        Button(
                            onClick = { onOpenEntry(sugerencia) },
                            modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                            transformation = SurfaceTransformation(spec),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(sugerencia.headword)
                                val detalle = listOfNotNull(
                                    sugerencia.partOfSpeech?.let(::posEnEspanol),
                                    etiquetaDeNivel(sugerencia.matchKind),
                                ).joinToString(" · ")
                                if (detalle.isNotEmpty()) {
                                    Text(
                                        text = detalle,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    item {
                        Button(
                            onClick = onOpenAttribution,
                            modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                            transformation = SurfaceTransformation(spec),
                        ) { Text("Sobre estos datos") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Cargando(mensaje: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator()
        Text(text = mensaje, textAlign = TextAlign.Center)
    }
}

/**
 * Teclado. Es `BasicTextField` y no un componente de Wear Compose porque Wear Compose no trae
 * campo de texto: la libreria asume que el input entra por voz o por el activity del sistema.
 */
@Composable
private fun CampoDeTexto(query: String, onQueryChange: (String) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        if (query.isEmpty()) {
            Text(
                text = "o escribir…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun intentDeVoz(): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
        )
        // El pack es de español y el reconocedor tiene que buscar en el mismo idioma, no en el
        // del sistema: un reloj en ingles dictando "perro" devolveria cualquier cosa.
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es")
    }

/**
 * El pack guarda el `pos` con el codigo de kaikki (`noun`, `verb`). Traducirlo es cosa de la
 * UI: meterlo en el pack lo ataria a un idioma de interfaz y costaria bytes por entrada.
 */
internal fun posEnEspanol(pos: String): String = when (pos) {
    "noun" -> "sust."
    "verb" -> "verbo"
    "adj" -> "adj."
    "adv" -> "adv."
    "name" -> "n. propio"
    "phrase" -> "locución"
    "intj" -> "interj."
    "pron" -> "pron."
    "prep" -> "prep."
    "conj" -> "conj."
    "num" -> "num."
    "suffix" -> "sufijo"
    "prefix" -> "prefijo"
    "proverb" -> "refrán"
    "abbrev" -> "abrev."
    else -> pos
}

/** Solo se etiquetan los niveles que sorprenden: que salga por prefijo es lo esperado. */
private fun etiquetaDeNivel(kind: MatchKind): String? = when (kind) {
    MatchKind.PREFIX -> null
    MatchKind.INFLECTED_FORM -> "forma"
    MatchKind.TRANSLATION -> "traducción"
    MatchKind.FUZZY -> "quizás"
    MatchKind.DEFINITION -> "definición"
}
