package cl.fadiaz.dictionary.presentation

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
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
import cl.fadiaz.dictionary.data.PackHandle

/**
 * La pantalla de busqueda. Es la app: D-026 dice que la busqueda vive aca adentro porque ni los
 * tiles ni los widgets aceptan text input.
 *
 * EL DISEÑO ESTA GOBERNADO POR UN PRESUPUESTO DE 192 dp
 *
 * La pantalla son 384x384 px a 320 dpi, o sea **192x192 dp**, y la guia de Wear OS pide 48 dp
 * minimos de area tocable. Eso da cuatro filas y nada mas: cada dp que gasta el encabezado es un
 * resultado que el usuario no ve. De ahi las dos decisiones que se ven raras sueltas:
 *
 * - **La entrada de texto cambia de tamaño.** Con la busqueda vacia, el boton de voz ocupa lo
 *   que tiene que ocupar: es el camino principal en una muñeca (`app/CLAUDE.md`) y no hay nada
 *   que compita con el. En cuanto hay algo escrito se colapsa a una fila, porque a partir de
 *   ahi lo que importa son los resultados.
 * - **Las filas son de una linea y truncan.** Los refranes del Wikcionario son entradas y
 *   llegan a 96 caracteres; una fila que creciera para mostrarlos enteros se comeria media
 *   pantalla por un caso raro. El lema completo esta a un toque.
 */
@Composable
fun SearchScreen(
    state: SearchState,
    onQueryChange: (String) -> Unit,
    onPackChange: (String) -> Unit = {},
    onOpenEntry: (Suggestion) -> Unit,
    onOpenAttribution: () -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    val focusRequester = remember { FocusRequester() }
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
        TransformingLazyColumn(
            contentPadding = contentPadding,
            state = listState,
            // La corona es el scroll principal de un reloj: el dedo tapa justamente lo que se
            // esta leyendo. No viene cableada por defecto.
            modifier = Modifier.rotaryScrollable(
                RotaryScrollableDefaults.behavior(listState),
                focusRequester,
            ).focusRequester(focusRequester).requestFocusOnHierarchyActive(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            when (val status = state.status) {
                SearchState.Status.Loading, SearchState.Status.Installing -> item {
                    Cargando(
                        mensaje = if (status == SearchState.Status.Installing) {
                            "Instalando el diccionario.\nSolo pasa la primera vez."
                        } else {
                            "Abriendo el diccionario…"
                        },
                    )
                }

                is SearchState.Status.Failed -> item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = state.activo?.name ?: "Diccionario",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = status.message,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }

                SearchState.Status.Ready -> {
                    // Con la busqueda vacia el encabezado puede permitirse existir; en cuanto
                    // hay resultados, cada fila de chrome es un resultado menos.
                    if (state.query.isEmpty()) {
                        item {
                            // Con un solo pack esto es el titulo de siempre; con dos es el
                            // selector. Reusar el header es lo que hace que el selector cueste
                            // CERO filas de resultado -- y con 192 dp sólo entran tres.
                            if (state.disponibles.size > 1) {
                                SelectorDeIdioma(state, onPackChange)
                            } else {
                                ListHeader(
                                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                                    transformation = SurfaceTransformation(spec),
                                ) { Text(state.activo?.name ?: "Diccionario") }
                            }
                        }
                        item {
                            Button(
                                onClick = { voz.launch(intentDeVoz(state.activo?.langSource ?: "es")) },
                                modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                                transformation = SurfaceTransformation(spec),
                            ) { Text("Decir una palabra") }
                        }
                    }

                    item {
                        BarraDeBusqueda(state.query, onQueryChange) {
                            voz.launch(intentDeVoz(state.activo?.langSource ?: "es"))
                        }
                    }

                    if (state.query.isNotBlank() && state.results.isEmpty()) {
                        item {
                            Text(
                                text = "Sin resultados para “${state.query}”",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            )
                        }
                        // La escotilla de escape, y aparece SOLO aca: el usuario escribio algo
                        // que este idioma no tiene. Con resultados en pantalla el selector
                        // costaria una fila, o sea un tercio de la lista (D-073).
                        val otro = state.disponibles
                            .filterIsInstance<PackHandle.Abierto>()
                            .firstOrNull { it.packId != state.activo?.packId }
                        if (otro != null) {
                            item {
                                Text(
                                    text = "Buscar en ${otro.metadata.name}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp)
                                        .clip(RoundedCornerShape(percent = 50))
                                        .background(MaterialTheme.colorScheme.primaryContainer)
                                        .clickable { onPackChange(otro.packId) }
                                        .heightIn(min = TOUCH_TARGET)
                                        .padding(vertical = 14.dp),
                                )
                            }
                        }
                    }

                    items(count = state.results.size) { indice ->
                        FilaDeResultado(state.results[indice]) { onOpenEntry(state.results[indice]) }
                    }

                    item {
                        Text(
                            text = "Sobre estos datos",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onOpenAttribution)
                                .heightIn(min = TOUCH_TARGET)
                                .padding(top = 14.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Una fila de resultado: 48 dp, una linea, el lema manda y la categoria acompaña.
 *
 * No usa `Button` de Wear Compose a proposito: su alto minimo es 52 dp y con el encabezado no
 * entraban cuatro filas. 48 dp es el minimo que pide la guia de Wear OS para un area tocable, y
 * bajar de ahi seria ganar densidad rompiendo algo peor.
 */
@Composable
private fun FilaDeResultado(sugerencia: Suggestion, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(percent = 50))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .heightIn(min = TOUCH_TARGET)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = sugerencia.headword,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        val detalle = etiquetaDeNivel(sugerencia.matchKind)
            ?: sugerencia.partOfSpeech?.let(::posEnEspanol)
        if (detalle != null) {
            Text(
                text = detalle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/**
 * Teclado y voz en una sola fila.
 *
 * Es `BasicTextField` y no un componente de Wear Compose porque **Wear Compose no trae campo de
 * texto**: la libreria asume que el input entra por voz o por el activity del sistema. Y es el
 * teclado el que ejercita la busqueda incremental: la voz entrega la frase entera de una vez.
 */
@Composable
private fun BarraDeBusqueda(query: String, onQueryChange: (String) -> Unit, onVoz: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(percent = 50))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .heightIn(min = TOUCH_TARGET)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (query.isEmpty()) {
                Text(
                    text = "escribir…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // Con algo escrito el boton grande de voz desaparece, pero la voz no puede desaparecer
        // con el: sigue siendo el camino principal en una muñeca.
        if (query.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable(onClick = onVoz)
                    .heightIn(min = TOUCH_TARGET)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Text(
                    text = "voz",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

/**
 * Los idiomas disponibles, en la banda donde antes estaba el titulo.
 *
 * Chips de 48 dp --el minimo tocable de Wear OS-- repartidos a lo ancho. Se construye con
 * `Row`/`Box`/`clickable` y no con un componente de Wear Compose a proposito: con
 * `allWarningsAsErrors`, una API que se deprecie en el proximo bump rompe el build.
 */
@Composable
private fun SelectorDeIdioma(state: SearchState, onPackChange: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.disponibles.forEach { handle ->
            val activo = handle.packId == state.activo?.packId
            val etiqueta = when (handle) {
                is PackHandle.Abierto -> handle.metadata.langSource.uppercase()
                is PackHandle.Disponible -> handle.etiqueta
            }
            Text(
                text = etiqueta,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                color = if (activo) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(
                        if (activo) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer
                        },
                    )
                    .clickable { onPackChange(handle.packId) }
                    .heightIn(min = TOUCH_TARGET)
                    .padding(vertical = 14.dp),
            )
        }
    }
}

@Composable
private fun Cargando(mensaje: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CircularProgressIndicator()
        Text(
            text = mensaje,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}

private fun intentDeVoz(lang: String): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
        )
        // El reconocedor tiene que buscar en el idioma del pack ACTIVO, no en el del sistema:
        // un reloj en ingles dictando "perro" devolveria cualquier cosa, y al reves igual.
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
    }

/** Minimo que pide la guia de Wear OS para algo que se toca. */
private val TOUCH_TARGET = 48.dp

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

/**
 * Solo se etiquetan los niveles que sorprenden.
 *
 * Que un resultado salga por prefijo es lo esperado y no merece una palabra en una pantalla de
 * 192 dp. Que salga por una forma flexionada o por parecido si: explica por que aparece algo
 * que el usuario no escribio.
 */
private fun etiquetaDeNivel(kind: MatchKind): String? = when (kind) {
    MatchKind.PREFIX -> null
    MatchKind.INFLECTED_FORM -> "forma"
    MatchKind.TRANSLATION -> "traducción"
    MatchKind.FUZZY -> "quizás"
    MatchKind.DEFINITION -> "definición"
}
