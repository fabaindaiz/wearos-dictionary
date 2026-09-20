package cl.fadiaz.dictionary.presentation

import android.app.Activity
import android.content.Intent
import android.content.Context
import androidx.annotation.StringRes
import android.app.RemoteInput
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.wear.input.RemoteInputIntentHelper
import androidx.wear.input.wearableExtender
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import cl.fadiaz.dictionary.R
import cl.fadiaz.dictionary.core.EntrySummary
import cl.fadiaz.dictionary.core.MatchKind
import cl.fadiaz.dictionary.core.Suggestion
import cl.fadiaz.dictionary.data.PackHandle
import cl.fadiaz.dictionary.data.Visit

/**
 * The search screen. It is the app: D-026 says the search lives in here because neither tiles nor
 * widgets accept text input.
 *
 * THE DESIGN IS GOVERNED BY A 192 dp BUDGET
 *
 * The screen is 384x384 px at 320 dpi, that is **192x192 dp**, and the Wear OS guidance asks for
 * a 48 dp minimum touch area. That gives four rows and nothing more: every dp the header spends
 * is a result the user does not see. Hence the two decisions that look odd on their own:
 *
 * - **The text input changes size.** With an empty search, the voice button takes as much room as
 *   it should: it is the primary path on a wrist (`app/CLAUDE.md`) and nothing competes with it.
 *   As soon as something is typed it collapses to one row, because from then on what matters is
 *   the results.
 * - **The rows are one line and truncate.** Wiktionary's sayings are entries and reach 96
 *   characters; a row that grew to show them whole would eat half the screen for a rare case.
 *   The full headword is one tap away.
 */
@Composable
fun SearchScreen(
    state: SearchState,
    onQueryChange: (String) -> Unit,
    // El teclado abierto suspende la busqueda (D-128). Default vacio: una pantalla de test que
    // no lo cablea sigue comportandose como antes.
    onTypingChanged: (Boolean) -> Unit = {},
    onPackChange: (String) -> Unit = {},
    // Deliberately no default: a callback forgotten in MainActivity would be a dead escape
    // hatch, indistinguishable from one that works.
    onSearchDefinitions: () -> Unit,
    onOpenEntry: (Suggestion) -> Unit,
    onOpenVisita: (Visit) -> Unit = {},
    onOpenAttribution: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenFavoritos: () -> Unit = {},
    // It carries the packId as well as the entry: with two languages loaded there are two words
    // of the day and each lives in ITS dictionary. Resolving it against the active one would be
    // D-080 again. No default: a word of the day that shows and opens nothing is worse than none.
    onOpenWordOfTheDay: (String, EntrySummary) -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    val focusRequester = remember { FocusRequester() }
    val spec = rememberTransformationSpec()

    val voice = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            RemoteInput.getResultsFromIntent(result.data)
                ?.getCharSequence(KEY_SPOKEN)
                ?.toString()
                ?.takeIf { it.isNotBlank() }
                ?.let(onQueryChange)
        }
    }

    // La etiqueta del input nativo nombra el diccionario, porque el input del sistema dicta en
    // el idioma DEL RELOJ y no en el del pack: es lo unico que le dice al usuario en que esta
    // buscando (D-127). Se arma aca y no dentro de la lista: `stringResource` es @Composable y
    // el scope de un lazy item no lo es.
    val voiceLabel = stringResource(
        R.string.home_search_in,
        state.active?.name ?: stringResource(R.string.home_dictionary),
    )

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            contentPadding = withScreenMargins(contentPadding),
            state = listState,
            // The crown is a watch's primary scroll: the finger covers exactly what is being
            // read. It is not wired up by default.
            modifier = Modifier.rotaryScrollable(
                RotaryScrollableDefaults.behavior(listState),
                focusRequester,
            ).focusRequester(focusRequester).requestFocusOnHierarchyActive(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Bajo el reloj. Un item y no padding: como padding, la barra quedaba dentro del
            // transform del borde y salia con la forma cortada. Ver CLOCK_GAP.
            item(key = "bajo-el-reloj") { Spacer(Modifier.height(CLOCK_GAP)) }

            when (val status = state.status) {
                SearchState.Status.Loading, SearchState.Status.Installing -> item {
                    LoadingMessage(
                        message = stringResource(
                            if (status == SearchState.Status.Installing) R.string.pack_installing
                            else R.string.pack_opening,
                        ),
                    )
                }

                // Sin diccionario: el ViewModel emite el estado y el texto lo pone aca (D-127).
                SearchState.Status.NoDictionary -> item {
                    Text(
                        text = stringResource(R.string.pack_none_installed),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                }

                is SearchState.Status.Failed -> item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = state.active?.name ?: stringResource(R.string.home_dictionary),
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
                    // THE BAR GOES FIRST. It used to sit below the header, the word of the
                    // day and the voice button, and searching is the primary action: the
                    // Wear OS guidance asks to elevate it so you can act without navigating.
                    item(key = "barra") {
                        SearchBar(state.query, onQueryChange, onTypingChanged) {
                            voice.launch(nativeInputIntent(voiceLabel))
                        }
                    }

                    if (state.submitted.isEmpty()) {
                        // Voice sits next to the bar: both answer the same question --how do I
                        // enter what I am looking for-- and separating them forced a scroll.
                        item(key = "voz") {
                            Button(
                                onClick = { voice.launch(nativeInputIntent(voiceLabel)) },
                                modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                                transformation = SurfaceTransformation(spec),
                            ) { Text(stringResource(R.string.home_say_a_word)) }
                        }

                        // One per loaded dictionary, the active one's first. The header shows
                        // ONLY if there is at least one: a heading with nothing under it is
                        // worse than no heading.
                        val ofTheDay = state.available
                            .filterIsInstance<PackHandle.Open>()
                            .mapNotNull { handle ->
                                state.wordsOfTheDay[handle.packId]?.let { handle to it }
                            }
                            .sortedByDescending { it.first.packId == state.active?.packId }
                        if (ofTheDay.isNotEmpty()) {
                            item(key = "titulo-del-dia") {
                                ListHeader(
                                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                                    transformation = SurfaceTransformation(spec),
                                ) { Text(stringResource(R.string.home_word_of_the_day)) }
                            }
                        }
                        items(
                            count = ofTheDay.size,
                            key = { index -> "pdd:${ofTheDay[index].first.packId}" },
                        ) { index ->
                            val (handle, word) = ofTheDay[index]
                            // Always the dictionary's name: the header already says this is
                            // the word of the day, so repeating it here wasted a line.
                            WordOfTheDayRow(
                                word = word,
                                // Short name AND kind: since D-125 the name is just "Español",
                                // so without the label you cannot tell what kind it is.
                                subtitle = "${handle.metadata.name} · " +
                                    packTypeLabel(handle.metadata.kind),
                            ) { onOpenWordOfTheDay(handle.packId, word) }
                        }
                    }

                    // The most recently opened words, only with an empty search: they disappear
                    // when typing by construction, without an extra `if`, so they never compete
                    // with the results.
                    //
                    // They DO carry a header now. They did not before because "a ListHeader costs
                    // two thirds of a row and there is nothing here to confuse them with" -- but
                    // with the word of the day above and the options below, the only list without
                    // a heading became the odd one out.
                    if (state.submitted.isEmpty() && state.history.isNotEmpty()) {
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
                                headword = visit.headword,
                                detail = visit.partOfSpeech?.let { posLabel(it) },
                            ) { onOpenVisita(visit) }
                        }
                    }

                    if (state.submitted.isNotBlank() && state.results.isEmpty()) {
                        item(key = "sin-resultados") {
                            Text(
                                text = if (state.mode == SearchState.Mode.DEFINICIONES) {
                                    stringResource(R.string.home_no_results_definitions)
                                } else {
                                    stringResource(R.string.home_no_results_for, state.submitted)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            )
                        }
                        // Search the word INSIDE the definitions. Not offered if we are
                        // already looking at definitions: that would be a loop.
                        if (state.mode != SearchState.Mode.DEFINICIONES) {
                            item(key = "escotilla-definiciones") {
                                val searching = state.mode == SearchState.Mode.BUSCANDO_DEFINICIONES
                                Pill(
                                    text = if (searching) {
                                        stringResource(R.string.home_searching_definitions)
                                    } else {
                                        stringResource(R.string.home_search_definitions)
                                    },
                                    // No onClick while searching: it stays on screen so the
                                    // list does not jump, but it fires no second query.
                                    onClick = if (searching) null else onSearchDefinitions,
                                )
                            }
                        }
                        // The escape hatch, and it shows up ONLY here: the user typed something
                        // this language does not have. With results on screen the selector would
                        // cost a row, that is a third of the list (D-073).
                        val other = state.available
                            .filterIsInstance<PackHandle.Open>()
                            .firstOrNull { it.packId != state.active?.packId }
                        if (other != null) {
                            item(key = "escotilla-idioma") {
                                Pill(
                                    text = stringResource(R.string.home_search_in, other.metadata.name),
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

                    // Settings only with an empty search: with results on screen a row of
                    // chrome is one result less (D-073).
                    if (state.submitted.isEmpty()) {
                        item(key = "titulo-opciones") {
                            ListHeader(
                                modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                                transformation = SurfaceTransformation(spec),
                            ) { Text("Opciones") }
                        }
                        // The language selector lives here and no longer replaces the home
                        // heading. That changes what D-078 said --that it cost ZERO rows-- and
                        // the new cost is a row of its own; in exchange it stops competing with
                        // the bar for the top spot, which is where the search has to be.
                        if (state.available.size > 1) {
                            item(key = "selector") { LanguageSelector(state, onPackChange) }
                        }
                        // Always, even when empty: someone who never saved a word had no way
                        // to discover they could. The screen already carries an empty state
                        // that explains the gesture, so arriving with zero is not a dead end.
                        item(key = "favoritos") {
                            ListRow(
                                headword = "Guardadas",
                                detail = state.favorites.size.takeIf { it > 0 }?.toString(),
                                onClick = onOpenFavoritos,
                            )
                        }
                        item(key = "ajustes") {
                            ListRow(headword = "Ajustes", detail = null, onClick = onOpenSettings)
                        }
                    }

                    item(key = "atribucion") {
                        Text(
                            text = stringResource(R.string.home_about),
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
 * A result row: 48 dp, one line, the headword leads and the part of speech follows.
 *
 * It deliberately does not use Wear Compose's `Button`: its minimum height is 52 dp and with the
 * header four rows did not fit. 48 dp is the minimum the Wear OS guidance asks for a touch area,
 * and going below that would buy density by breaking something worse.
 */
@Composable
private fun ResultRow(suggestion: Suggestion, onClick: () -> Unit) {
    ListRow(
        headword = suggestion.headword,
        detail = matchLabel(suggestion.matchKind)
            ?: suggestion.partOfSpeech?.let { posLabel(it) },
        onClick = onClick,
    )
}


/**
 * Keyboard and voice on a single row.
 *
 * It is a `BasicTextField` and not a Wear Compose component because **Wear Compose ships no text
 * field**: the library assumes input arrives by voice or through the system activity. And it is
 * the keyboard that exercises the incremental search: voice delivers the whole phrase at once.
 */
@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onTypingChanged: (Boolean) -> Unit,
    onVoice: () -> Unit,
) {
    // "Accept" did nothing: there was an ImeAction declared and no handler, and
    // KeyboardActions.Default defines no behaviour for Search --unlike Next/Previous,
    // which move focus--. The only way out was the system's back gesture.
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
                // The border is what sets it apart, and border rather than fill is deliberate:
                // the bar used `surfaceContainer`, the SAME token as a result row and as the
                // "Show more" button, so the field was indistinguishable from a list item. A
                // full fill would light the whole band on an OLED; the outline lights the
                // perimeter and reads just as well.
                .border(2.dp, MaterialTheme.colorScheme.primary, PILL_SHAPE)
                .heightIn(min = TOUCH_TARGET)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (query.isEmpty()) {
                Text(
                    text = stringResource(R.string.home_type_hint),
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
                // Tocar Buscar cierra el teclado y suelta el foco. Soltarlo es lo que
                // DISPARA la consulta (D-128): no hace falta llamarla aca.
                keyboardActions = KeyboardActions(
                    onSearch = {
                        keyboard?.hide()
                        // Measured: releasing focus does NOT eat the text the IME
                        // was composing --checked by removing it and the field
                        // stayed the same-- and it gives the crown back to the list.
                        focus.clearFocus()
                    },
                ),
                // El foco ES el teclado: mientras el campo lo tiene, se escribe sin buscar
                // (D-128). Soltarlo --por Buscar, por el gesto del sistema o por tocar fuera--
                // es lo que dispara la consulta, una sola vez.
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { onTypingChanged(it.isFocused) },
            )
        }
        // With something typed the large voice button goes away, but voice cannot go away
        // with it: it is still the primary path on a wrist.
        if (query.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .clip(PILL_SHAPE)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable(onClick = onVoice)
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
 * Today's word: the headword large and a small label saying what it is.
 *
 * It carries the label even though it costs height because without it it is indistinguishable
 * from a history entry, and then it communicates nothing. It does not use [ListRow] for the same
 * reason: a one-line row would read "perro · sust." and that already exists three times below.
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
 * The available languages, in the band where the heading used to be.
 *
 * 48 dp chips --the Wear OS touch minimum-- spread across the width. Built with
 * `Row`/`Box`/`clickable` and deliberately not with a Wear Compose component: with
 * `allWarningsAsErrors`, an API deprecated in the next bump breaks the build.
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

/** La clave con la que el input del sistema devuelve lo dictado o escrito. */
private const val KEY_SPOKEN = "spoken"

/**
 * La entrada **nativa del reloj**, no el reconocedor de Google.
 *
 * `ACTION_REMOTE_INPUT` abre el selector de entrada del sistema, que en un Wear OS ofrece voz,
 * teclado y escritura a mano en una sola superficie — la misma que usa cualquier respuesta rapida
 * del reloj. `RecognizerIntent` abria **solo** el reconocedor de Google, que es una app aparte y
 * no siempre esta.
 *
 * ⚠️ **Lo que se pierde, y no es cosmetico**: `RecognizerIntent` aceptaba
 * `EXTRA_LANGUAGE = langSource`, asi que se dictaba en el idioma DEL PACK. El input del sistema
 * usa el idioma **del reloj**. Con el reloj en español y el pack ingles abierto, dictar va a
 * transcribir en español. Por eso la etiqueta nombra el diccionario: es lo unico que queda para
 * decirle al usuario en que esta buscando.
 */
private fun nativeInputIntent(label: String): Intent {
    val input = RemoteInput.Builder(KEY_SPOKEN)
        .setLabel(label)
        // Un diccionario no busca emojis, y quitarlos saca una pestaña del selector.
        //
        // ⚠️ El tipo de accion (Buscar en vez de Enviar) **no se puede fijar desde Kotlin**:
        // `setInputActionType` es publica pero las constantes `INPUT_ACTION_TYPE_*` de
        // `WearableRemoteInputExtender` son `internal` en wear-input 1.2.0 --verificado
        // desarmando el .aar, aparecen como `$wear_input_release`--. Desde Java se ven
        // publicas; desde Kotlin no. Queda con el default.
        .wearableExtender { setEmojisAllowed(false) }
        .build()
    return RemoteInputIntentHelper.putRemoteInputsExtra(
        RemoteInputIntentHelper.createActionRemoteInputIntent(),
        listOf(input),
    )
}

/**
 * El `pos` que guarda el pack es el codigo de kaikki (`noun`, `verb`). Traducirlo es cosa de la
 * UI: meterlo en el pack lo ataria a un idioma de interfaz y costaria bytes por entrada.
 *
 * Devuelve el **id de recurso** y no el texto porque esto lo usan dos superficies distintas: las
 * pantallas, que resuelven con `stringResource`, y los tiles, que tienen `Context` y resuelven
 * con `getString`. Un codigo que no conocemos devuelve null y se muestra crudo, que es mejor que
 * esconderlo.
 */
@StringRes
internal fun posLabelRes(pos: String): Int? = when (pos) {
    "noun" -> R.string.pos_noun
    "verb" -> R.string.pos_verb
    "adj" -> R.string.pos_adj
    "adv" -> R.string.pos_adv
    "name" -> R.string.pos_name
    "phrase" -> R.string.pos_phrase
    "intj" -> R.string.pos_intj
    "pron" -> R.string.pos_pron
    "prep" -> R.string.pos_prep
    "conj" -> R.string.pos_conj
    "num" -> R.string.pos_num
    "suffix" -> R.string.pos_suffix
    "prefix" -> R.string.pos_prefix
    "proverb" -> R.string.pos_proverb
    "abbrev" -> R.string.pos_abbrev
    else -> null
}

/** [posLabelRes] resuelto en el idioma del reloj; el codigo crudo si no se conoce. */
@Composable
internal fun posLabel(pos: String): String = posLabelRes(pos)?.let { stringResource(it) } ?: pos

/** [posLabelRes] para quien tiene `Context` y no composicion: los tiles. */
internal fun posLabel(context: Context, pos: String): String =
    posLabelRes(pos)?.let(context::getString) ?: pos

/**
 * Solo se etiquetan los niveles que sorprenden.
 *
 * Que un resultado salga por prefijo es lo esperado y no merece una palabra en una pantalla de
 * reloj. Que salga por una forma flexionada o por parecido si: explica por que aparece algo que
 * el usuario no escribio.
 */
@Composable
private fun matchLabel(kind: MatchKind): String? = when (kind) {
    MatchKind.PREFIX -> null
    MatchKind.INFLECTED_FORM -> stringResource(R.string.match_inflected)
    MatchKind.TRANSLATION -> stringResource(R.string.match_translation)
    MatchKind.FUZZY -> stringResource(R.string.match_fuzzy)
    MatchKind.DEFINITION -> stringResource(R.string.match_definition)
}

