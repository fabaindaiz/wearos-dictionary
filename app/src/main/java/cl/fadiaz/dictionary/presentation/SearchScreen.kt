package cl.fadiaz.dictionary.presentation

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import cl.fadiaz.dictionary.core.EntrySummary
import cl.fadiaz.dictionary.core.MatchKind
import cl.fadiaz.dictionary.core.Suggestion
import cl.fadiaz.dictionary.data.PackHandle
import cl.fadiaz.dictionary.data.packTypeLabel
import cl.fadiaz.dictionary.data.Visit

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
    // Sin default a proposito: un callback olvidado en MainActivity seria una escotilla muerta
    // que no se distingue de una que funciona.
    onSearchDefinitions: () -> Unit,
    onOpenEntry: (Suggestion) -> Unit,
    onOpenVisita: (Visit) -> Unit = {},
    onOpenAttribution: () -> Unit,
    onOpenAjustes: () -> Unit = {},
    onOpenFavoritos: () -> Unit = {},
    // Lleva el packId ademas de la entrada: con dos idiomas cargados hay dos palabras del dia y
    // cada una vive en SU diccionario. Resolverla contra el activo seria D-080 otra vez.
    // Sin default: una palabra del dia que se ve y no abre nada es peor que no tenerla.
    onOpenPalabraDelDia: (String, EntrySummary) -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    val focusRequester = remember { FocusRequester() }
    val spec = rememberTransformationSpec()

    val voice = rememberLauncherForActivityResult(
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
            contentPadding = withBottomMargin(contentPadding),
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
                    LoadingMessage(
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
                            text = state.active?.name ?: "Diccionario",
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
                    // LA BARRA VA PRIMERA. Antes quedaba debajo del encabezado, de la palabra
                    // del dia y del boton de voz, y buscar es la accion primaria: la guia de
                    // Wear OS pide elevarla para que se actue sin navegar.
                    item(key = "barra") {
                        SearchBar(state.query, onQueryChange) {
                            voice.launch(voiceIntent(state.active?.langSource ?: "es"))
                        }
                    }

                    if (state.query.isEmpty()) {
                        // La voz va pegada a la barra: las dos son la misma pregunta --como
                        // escribo lo que busco-- y separarlas obligaba a scrollear entre ellas.
                        item(key = "voz") {
                            Button(
                                onClick = { voice.launch(voiceIntent(state.active?.langSource ?: "es")) },
                                modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                                transformation = SurfaceTransformation(spec),
                            ) { Text("Decir una palabra") }
                        }

                        // Una por diccionario cargado, la del activo primero. El encabezado
                        // aparece SOLO si hay alguna: un titulo sin nada debajo es peor que no
                        // tener titulo.
                        val delDia = state.available
                            .filterIsInstance<PackHandle.Open>()
                            .mapNotNull { handle ->
                                state.wordsOfTheDay[handle.packId]?.let { handle to it }
                            }
                            .sortedByDescending { it.first.packId == state.active?.packId }
                        if (delDia.isNotEmpty()) {
                            item(key = "titulo-del-dia") {
                                ListHeader(
                                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                                    transformation = SurfaceTransformation(spec),
                                ) { Text("Palabra del día") }
                            }
                        }
                        items(
                            count = delDia.size,
                            key = { index -> "pdd:${delDia[index].first.packId}" },
                        ) { index ->
                            val (handle, word) = delDia[index]
                            // Siempre el nombre del diccionario: el encabezado ya dice que es
                            // la palabra del dia, asi que repetirlo aca gastaba un renglon.
                            WordOfTheDayRow(
                                word = word,
                                // Nombre corto Y tipo: desde D-125 el nombre es solo "Español",
                                // asi que sin la etiqueta no se sabe que clase de diccionario es.
                                subtitle = "${handle.metadata.name} · " +
                                    packTypeLabel(handle.metadata.kind),
                            ) { onOpenPalabraDelDia(handle.packId, word) }
                        }
                    }

                    // Las ultimas palabras abiertas, solo con la busqueda vacia: desaparecen al
                    // escribir por construccion, sin un `if` extra, asi que no compiten nunca con
                    // los resultados.
                    //
                    // AHORA SI llevan encabezado. Antes no lo tenian porque "un ListHeader cuesta
                    // dos tercios de una fila y aca no hay nada con que confundirlas" -- pero con
                    // la palabra del dia arriba y las opciones abajo, la unica lista sin titulo
                    // pasaba a ser la rara.
                    if (state.query.isEmpty() && state.history.isNotEmpty()) {
                        item(key = "titulo-recientes") {
                            ListHeader(
                                modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                                transformation = SurfaceTransformation(spec),
                            ) { Text("Recientes") }
                        }
                        items(
                            count = state.history.size,
                            key = { index ->
                                val v = state.history[index]
                                "h:${v.packId}:${v.entryId}"
                            },
                        ) { index ->
                            val visit = state.history[index]
                            ListRow(
                                lema = visit.headword,
                                detail = visit.partOfSpeech?.let(::posInSpanish),
                            ) { onOpenVisita(visit) }
                        }
                    }

                    if (state.query.isNotBlank() && state.results.isEmpty()) {
                        item(key = "sin-resultados") {
                            Text(
                                text = if (state.mode == SearchState.Mode.DEFINICIONES) {
                                    "Sin resultados en las definiciones"
                                } else {
                                    "Sin resultados para “${state.query}”"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            )
                        }
                        // Buscar la palabra DENTRO de las definiciones. No se ofrece si ya
                        // estamos viendo definiciones: seria un bucle.
                        if (state.mode != SearchState.Mode.DEFINICIONES) {
                            item(key = "escotilla-definiciones") {
                                val searching = state.mode == SearchState.Mode.BUSCANDO_DEFINICIONES
                                Pill(
                                    text = if (searching) {
                                        "Buscando en las definiciones…"
                                    } else {
                                        "Buscar en las definiciones"
                                    },
                                    // Sin onClick mientras busca: sigue en pantalla para que la
                                    // lista no salte, pero no dispara una segunda consulta.
                                    onClick = if (searching) null else onSearchDefinitions,
                                )
                            }
                        }
                        // La escotilla de escape, y aparece SOLO aca: el usuario escribio algo
                        // que este idioma no tiene. Con resultados en pantalla el selector
                        // costaria una fila, o sea un tercio de la lista (D-073).
                        val other = state.available
                            .filterIsInstance<PackHandle.Open>()
                            .firstOrNull { it.packId != state.active?.packId }
                        if (other != null) {
                            item(key = "escotilla-idioma") {
                                Pill(
                                    text = "Buscar en ${other.metadata.name}",
                                    onClick = { onPackChange(other.packId) },
                                )
                            }
                        }
                    }

                    items(
                        count = state.results.size,
                        key = { index ->
                            val s = state.results[index]
                            "r:${s.packId}:${s.entryId}"
                        },
                    ) { index ->
                        ResultRow(state.results[index]) { onOpenEntry(state.results[index]) }
                    }

                    // Ajustes solo con la busqueda vacia: con resultados en pantalla una fila
                    // de chrome es un resultado menos (D-073).
                    if (state.query.isEmpty()) {
                        item(key = "titulo-opciones") {
                            ListHeader(
                                modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                                transformation = SurfaceTransformation(spec),
                            ) { Text("Opciones") }
                        }
                        // El selector de idioma vive aca y ya no reemplaza al titulo del inicio.
                        // Eso cambia lo que decia D-078 --que costaba CERO filas-- y el costo
                        // nuevo es una fila propia; a cambio deja de competir con la barra por
                        // el lugar de arriba, que es donde tiene que estar la busqueda.
                        if (state.available.size > 1) {
                            item(key = "selector") { LanguageSelector(state, onPackChange) }
                        }
                        // Siempre, aunque este vacia: quien nunca guardo una palabra no tenia
                        // como descubrir que se puede. La pantalla ya trae un estado vacio que
                        // explica el gesto, asi que llegar ahi con cero no es un callejon.
                        item(key = "favoritos") {
                            ListRow(
                                lema = "Guardadas",
                                detail = state.favorites.size.takeIf { it > 0 }?.toString(),
                                onClick = onOpenFavoritos,
                            )
                        }
                        item(key = "ajustes") {
                            ListRow(lema = "Ajustes", detail = null, onClick = onOpenAjustes)
                        }
                    }

                    item(key = "atribucion") {
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
private fun ResultRow(suggestion: Suggestion, onClick: () -> Unit) {
    ListRow(
        lema = suggestion.headword,
        detail = matchLabel(suggestion.matchKind)
            ?: suggestion.partOfSpeech?.let(::posInSpanish),
        onClick = onClick,
    )
}


/**
 * Teclado y voz en una sola fila.
 *
 * Es `BasicTextField` y no un componente de Wear Compose porque **Wear Compose no trae campo de
 * texto**: la libreria asume que el input entra por voz o por el activity del sistema. Y es el
 * teclado el que ejercita la busqueda incremental: la voz entrega la frase entera de una vez.
 */
@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit, onVoz: () -> Unit) {
    // "Aceptar" no hacia nada: habia un ImeAction declarado y ningun handler, y
    // KeyboardActions.Default no define comportamiento para Search --a diferencia de
    // Next/Previous, que mueven foco--. La unica salida era el gesto de volver del sistema.
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(PILL_SHAPE)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                // El borde es lo que la distingue, y es deliberado que sea borde y no relleno:
                // la barra usaba `surfaceContainer`, el MISMO token que una fila de resultado y
                // que el boton "Ver mas", asi que el campo era indistinguible de un item de
                // lista. Un relleno entero encenderia toda la banda en un OLED; el contorno
                // enciende el perimetro y se nota igual.
                .border(2.dp, MaterialTheme.colorScheme.primary, PILL_SHAPE)
                .heightIn(min = TOUCH_TARGET)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (query.isEmpty()) {
                Text(
                    text = "escribir…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
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
                // No dispara una consulta: la busqueda ya corrio por el debounce. Cierra el
                // teclado y suelta el foco, que es lo que devuelve la corona a la lista.
                keyboardActions = KeyboardActions(
                    onSearch = {
                        keyboard?.hide()
                        // Medido: soltar el foco NO se come el texto que venia
                        // componiendo el IME --se comprobo quitandolo y el campo
                        // quedaba igual--, y es lo que devuelve la corona a la lista.
                        focus.clearFocus()
                    },
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // Con algo escrito el boton grande de voz desaparece, pero la voz no puede desaparecer
        // con el: sigue siendo el camino principal en una muñeca.
        if (query.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .clip(PILL_SHAPE)
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
 * La palabra de hoy: el lema grande y una etiqueta chica que dice que es.
 *
 * Lleva la etiqueta aunque cueste altura porque sin ella es indistinguible de una entrada del
 * historial, y entonces no comunica nada. No usa [Fila] por lo mismo: una fila de una linea
 * diria "perro · sust." y eso ya existe tres veces mas abajo.
 */
@Composable
private fun WordOfTheDayRow(
    word: EntrySummary,
    subtitle: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PILL_SHAPE)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .heightIn(min = TOUCH_TARGET)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = word.headword,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
private fun LanguageSelector(state: SearchState, onPackChange: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.available.forEach { handle ->
            val active = handle.packId == state.active?.packId
            val label = (handle as PackHandle.Open).metadata.langSource.uppercase()
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                color = if (active) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .weight(1f)
                    .clip(PILL_SHAPE)
                    .background(
                        if (active) {
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

private fun voiceIntent(lang: String): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
        )
        // El reconocedor tiene que buscar en el idioma del pack ACTIVO, no en el del sistema:
        // un reloj en ingles dictando "perro" devolveria cualquier cosa, y al reves igual.
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
    }

/**
 * El pack guarda el `pos` con el codigo de kaikki (`noun`, `verb`). Traducirlo es cosa de la
 * UI: meterlo en el pack lo ataria a un idioma de interfaz y costaria bytes por entrada.
 */
internal fun posInSpanish(pos: String): String = when (pos) {
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
private fun matchLabel(kind: MatchKind): String? = when (kind) {
    MatchKind.PREFIX -> null
    MatchKind.INFLECTED_FORM -> "forma"
    MatchKind.TRANSLATION -> "traducción"
    MatchKind.FUZZY -> "quizás"
    MatchKind.DEFINITION -> "definición"
}
