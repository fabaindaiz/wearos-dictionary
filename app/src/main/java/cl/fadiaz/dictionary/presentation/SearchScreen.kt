package cl.fadiaz.dictionary.presentation

import android.app.Activity
import android.content.Intent
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.input.RemoteInputIntentHelper
import androidx.wear.input.wearableExtender
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import cl.fadiaz.dictionary.core.PackKind
import cl.fadiaz.dictionary.R
import cl.fadiaz.dictionary.core.EntrySummary
import cl.fadiaz.dictionary.core.Suggestion
import cl.fadiaz.dictionary.data.PackHandle
import cl.fadiaz.dictionary.data.givesWordOfTheDay
import cl.fadiaz.dictionary.data.Visit
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.wear.compose.material3.Icon
import androidx.compose.ui.res.painterResource

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
    // An open keyboard suspends the search (D-128). Empty by default: a test screen that does not
    // wire it still behaves as before.
    onTypingChanged: (Boolean) -> Unit = {},
    onLanguageChange: (String) -> Unit = {},
    /** Warns that the system input is about to cover the app. See `SearchViewModel.onLeftApp`. */
    onSystemInputOpening: () -> Unit = {},
    /**
     * Open the system input as soon as the screen exists, because a tile asked for it.
     *
     * ⚠️ **Once per composition and not on every recomposition**: the `LaunchedEffect` carries
     * `Unit` as its key on purpose. Without that, coming back from a card would reopen dictation
     * and there would be no way out.
     */
    abrirInputAlEntrar: Boolean = false,
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
    /** To the full history. Empty by default: a test screen that does not wire it still works. */
    onOpenHistory: () -> Unit = {},
) {
    // ⚠️ **One single tag for the whole list, and that follows from two decisions.** The search is
    // strict by language (D-189) and now also filters by `entry.lang` inside a bidirectional pack,
    // so **every visible row is in the active language**. It used to be a `packId -> tag` map,
    // which with a two-language pack no longer sufficed: the same pack would have had to return
    // `ES` for some rows and `EN` for others.
    val etiqueta = resultTag(state.activeLang)
    // The home's history carries the OTHER mechanism: its rows can come from a pack that is no
    // longer there. See `historyTags`.
    val etiquetasHistorial = remember(state.available) { historyTags(state.available) }
    // The words of the day that will be shown: **one per language, not one per pack** (D-151), the
    // active one first. It is computed here and not inside the list's lambda because there is no
    // `remember` there --it is not a composition scope-- and it was redone on every recomposition.
    // ⚠️ **The representative is chosen among the DEFINITION DICTIONARIES**, and skipping that
    // filter made the word of the day disappear entirely. `representativePacks` picks the largest
    // pack of each language, and the bilingual one became the largest of BOTH --209,484 against
    // Spanish's 152,281 and the English core's 16,652-- so the screen was asking for the word of a
    // pack that generates none. Filtering before and not after is what fixes it.
    val ofTheDay = remember(state.available, state.active?.packId, state.wordsOfTheDay) {
        val conDefiniciones = state.available.filter {
            it !is PackHandle.Open || givesWordOfTheDay(it.metadata)
        }
        representativePacks(conDefiniciones, state.active?.packId)
            .mapNotNull { handle -> state.wordsOfTheDay[handle.packId]?.let { handle to it } }
            .sortedByDescending { it.first.packId == state.active?.packId }
    }
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

    // The native input's label names the dictionary, because the system input dictates in the
    // WATCH's language and not the pack's: it is the only thing telling the user what they are
    // searching in (D-127). It is assembled here and not inside the list: `stringResource` is
    // @Composable and a lazy item's scope is not.
    val voiceLabel = stringResource(
        R.string.home_search_in,
        state.active?.name ?: stringResource(R.string.home_dictionary),
    )

    // The tile asked to search: the input opens as soon as there is a screen. See
    // `abrirInputAlEntrar`.
    LaunchedEffect(Unit) {
        if (abrirInputAlEntrar) {
            onSystemInputOpening()
            voice.launch(nativeInputIntent(voiceLabel))
        }
    }

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
            // Below the clock. An item and not padding: as padding, the bar stayed inside the
            // edge transform and came out with its shape clipped. See clockGap().
            item(key = "bajo-el-reloj") { Spacer(Modifier.height(clockGap())) }

            when (val status = state.status) {
                SearchState.Status.Loading, SearchState.Status.Installing -> item {
                    LoadingMessage(
                        message = stringResource(
                            if (status == SearchState.Status.Installing) R.string.pack_installing
                            else R.string.pack_opening,
                        ),
                    )
                }

                // With no dictionary: the ViewModel emits the state and the text is put here
                // (D-127).
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
                            // It warns before launching: the system input covers the app and its
                            // `ON_STOP` must not be confused with leaving. See `onLeftApp`.
                            onSystemInputOpening()
                            voice.launch(nativeInputIntent(voiceLabel))
                        }
                    }

                    // ⚠️ **Below the bar and ALWAYS, not only on the home** (D-156). It used to
                    // live inside the "no search" block, so it disappeared exactly when it is most
                    // needed: looking at results that are not the expected ones because the active
                    // language was not the one you thought. It costs one of the ~3 rows that fit,
                    // and it is worth paying: the case it prevents is typing an English word with
                    // Spanish active and not understanding why it does not show up.
                    // ⚠️ **LANGUAGES are counted and not files, and the difference was seen on the
                    // emulator.** With only the bidirectional pack installed --one file speaking
                    // two languages-- the selector was not drawn, so there was no way to reach its
                    // English half: the pack offered two and the app, zero.
                    if (idiomasDisponibles(state.available).size > 1) {
                        item(key = "selector") { LanguageSelector(state, onLanguageChange) }
                    }

                    if (state.submitted.isEmpty()) {

                        // One per loaded dictionary, the active one's first. The header shows
                        // ONLY if there is at least one: a heading with nothing under it is
                        // worse than no heading.
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
                            // ⚠️ **The same detail as every other row: `sust. · ES`.** It used to
                            // name the dictionary --`Español · definiciones`-- and that was the one
                            // row in the app that said something different from the other three.
                            // Asked for: *"under the word of the day, put only what kind of word
                            // it is and the language code"*. The header already says these are
                            // the words of the day, so naming the pack spent the line on what
                            // the section had said.
                            //
                            // ⚠️ **`singleOrNull` and not `first`**, which is `historyTags`'s same
                            // rule: a pack that declares two languages has no single true tag,
                            // and no tag beats the wrong one. It cannot happen today --a
                            // bilingual pack gives no word of the day (D-200)-- and that is
                            // precisely why the guard is cheap.
                            WordOfTheDayRow(
                                word = word,
                                subtitle = wordDetail(
                                    partOfSpeech = word.partOfSpeech?.let { posLabel(it) },
                                    tag = resultTag(handle.metadata.langs.singleOrNull()),
                                ),
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
                    // Untrimmed: the home SCROLLS, so limiting here would hide entries and gain
                    // nothing. The cap lives where scrolling is impossible -- the tile (D-131).
                    // ⚠️ **Three, and the rest behind a button** (D-148). The home is the most
                    // contested screen on the watch: with eight recents, settings and attribution
                    // were several scrolls away. Three is what fits after the field and the voice
                    // button without pushing anything out of reach.
                    val recent = state.history.take(HOME_RECENT)
                    val hayMas = state.history.size > recent.size
                    if (state.submitted.isEmpty() && recent.isNotEmpty()) {
                        item(key = "titulo-recientes") {
                            ListHeader(
                                modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                                transformation = SurfaceTransformation(spec),
                            ) { Text(stringResource(R.string.home_recent)) }
                        }
                        items(
                            count = recent.size,
                            key = { index ->
                                val v = recent[index]
                                "h:${v.packId}:${v.entryId}"
                            },
                        ) { index ->
                            val visit = recent[index]
                            ListRow(
                                headword = visit.headword,
                                detail = wordDetail(
                                    visit.partOfSpeech?.let { posLabel(it) },
                                    etiquetasHistorial[visit.packId],
                                ),
                            ) { onOpenVisita(visit) }
                        }
                        // Only if there are more: a button leading to the same list you are
                        // already looking at is chrome, and on a watch chrome is paid in rows.
                        if (hayMas) {
                            item(key = "ver-mas-recientes") {
                                ListRow(
                                    headword = stringResource(R.string.home_recent_more),
                                    detail = state.history.size.toString(),
                                    onClick = onOpenHistory,
                                )
                            }
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
                        // ⚠️ **The LANGUAGE decides, not the pack**, and that governs BOTH the
                        // condition and the label. Two defects lived here until 2026-09-23:
                        //
                        // 1. The pill was gated on a SECOND PACK existing, so a lone
                        //    bidirectional pack --the case D-195 created-- hid the hatch
                        //    entirely, even though that one file speaks both languages and the
                        //    user had no way out of the one being searched.
                        // 2. It was labelled with that second pack's NAME while the tap switched
                        //    a language, computed independently. With three packs installed it
                        //    could read "Search in English (full)" and activate a language whose
                        //    representative is a different file -- a label asserting a provenance
                        //    nobody checked, which is D-080's family.
                        //
                        // The tag and not a pack name is also what the rest of the app shows:
                        // *"it should just be EN, ES, because all I care about is knowing the
                        // language it comes from"*.
                        val otroIdioma = idiomasDisponibles(state.available)
                            .firstOrNull { it != state.activeLang }
                        if (otroIdioma != null) {
                            item(key = "escotilla-idioma") {
                                Pill(
                                    text = stringResource(
                                        R.string.home_search_in,
                                        resultTag(otroIdioma).orEmpty(),
                                    ),
                                    onClick = { onLanguageChange(otroIdioma) },
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
                        ResultRow(state.results[index], etiqueta) {
                            onOpenEntry(state.results[index])
                        }
                    }

                    // Settings only with an empty search: with results on screen a row of
                    // chrome is one result less (D-073).
                    if (state.submitted.isEmpty()) {
                        item(key = "titulo-opciones") {
                            ListHeader(
                                modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                                transformation = SurfaceTransformation(spec),
                            ) { Text(stringResource(R.string.home_options)) }
                        }
                        // The language selector lives here and no longer replaces the home
                        // heading. That changes what D-078 said --that it cost ZERO rows-- and
                        // the new cost is a row of its own; in exchange it stops competing with
                        // the bar for the top spot, which is where the search has to be.
                        // Always, even when empty: someone who never saved a word had no way
                        // to discover they could. The screen already carries an empty state
                        // that explains the gesture, so arriving with zero is not a dead end.
                        item(key = "favoritos") {
                            ListRow(
                                headword = stringResource(R.string.home_saved),
                                detail = state.favorites.size.takeIf { it > 0 }?.toString(),
                                onClick = onOpenFavoritos,
                            )
                        }
                        item(key = "ajustes") {
                            ListRow(
                                headword = stringResource(R.string.home_settings),
                                detail = null,
                                onClick = onOpenSettings,
                            )
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
private fun ResultRow(
    suggestion: Suggestion,
    /**
     * `packId` -> the tag the row carries: the language, or the source when the language is not
     * enough. [resultTag] assembles it, from the active language.
     *
     * ⚠️ **A `packId` not in the map gets NO tag**, rather than inheriting the active pack's. With
     * several dictionaries coexisting (D-136) that inheritance would assert the word comes from
     * somewhere nobody checked -- the same failure family as D-080.
     */
    etiqueta: String?,
    onClick: () -> Unit,
) {
    // A single slot on the right and not two: in a 234 dp row the lemma already competes for the
    // width. The part of speech first because it answers "what kind of word is this", which is
    // what gets looked at first; the language after, which only disambiguates when there is more
    // than one dictionary.
    ListRow(
        headword = suggestion.headword,
        detail = wordDetail(
            partOfSpeech = suggestion.partOfSpeech?.let { posLabel(it) },
            tag = etiqueta,
            override = matchLabel(suggestion.matchKind),
        ),
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
                // Tapping Search closes the keyboard and releases focus. Releasing it is what
                // FIRES the query (D-128): there is no need to call it here.
                keyboardActions = KeyboardActions(
                    onSearch = {
                        keyboard?.hide()
                        // Measured: releasing focus does NOT eat the text the IME
                        // was composing --checked by removing it and the field
                        // stayed the same-- and it gives the crown back to the list.
                        focus.clearFocus()
                    },
                ),
                // Focus IS the keyboard: while the field holds it, you type without searching
                // (D-128). Releasing it --through Search, the system gesture or tapping outside--
                // is what fires the query, once.
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { onTypingChanged(it.isFocused) },
            )
        }
        // ⚠️ **Always, not only with something typed** (D-157). The home used to have a big button
        // of its own and the results this small one, so the header changed shape as you typed and
        // had to be relearned. One header for both states.
        run {
            Box(
                modifier = Modifier
                    .clip(PILL_SHAPE)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable(onClick = onVoice)
                    .heightIn(min = TOUCH_TARGET)
                    .heightIn(min = TOUCH_TARGET)
                    .width(TOUCH_TARGET),
                contentAlignment = Alignment.Center,
            ) {
                // ⚠️ **An icon and not the word "voz"** (D-157). The text was hardcoded in Spanish
                // and was drawn just the same on an English watch; `check_no_hardcoded
                // _translations` did not see it because it requires 4 characters and "voz" has 3.
                // A microphone does not get translated, and at 48 dp it reads better than any word.
                Icon(
                    painter = painterResource(R.drawable.ic_mic),
                    contentDescription = stringResource(R.string.home_say_a_word),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
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
    subtitle: String?,
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
        // Nullable since the subtitle became the word's own detail: an entry with no `pos` in
        // a pack that declares two languages has nothing to say, and an empty line under the
        // headword reads as a label that failed to load.
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * One chip per **language**, not per file (D-147).
 *
 * 48 dp chips --the Wear OS touch minimum-- spread across the width. Built with
 * `Row`/`Box`/`clickable` and deliberately not with a Wear Compose component: with
 * `allWarningsAsErrors`, an API deprecated in the next bump breaks the build.
 *
 * The grouping is [languageChips] and lives outside the composable on purpose: which languages
 * exist, in what order, and which pack represents each one are **decisions**, so the gate covers
 * them on the JVM instead of under Robolectric.
 */
@Composable
private fun LanguageSelector(state: SearchState, onLanguageChange: (String) -> Unit) {
    // `remember` and not the bare call, same as `historyTags` on the home. Since D-156 this is
    // drawn ALWAYS --with results on screen too-- so it regrouped the packs on every
    // recomposition, and `available` does not change between keystrokes.
    //
    // ⚠️ **It is not a battery fix and must not be sold as one**: `docs/bateria.md` measured that
    // work of this kind is microseconds against minutes of screen. It is a correction of practice,
    // and the reason to make it is that it costs one line.
    val chips = remember(state.available, state.activeLang) {
        languageChips(state.available, state.activeLang)
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        chips.forEach { chip ->
            val active = chip.active
            Text(
                text = chip.label,
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
                    .clickable { onLanguageChange(chip.lang) }
                    .heightIn(min = TOUCH_TARGET)
                    .padding(vertical = 14.dp),
            )
        }
    }
}

/**
 * How many recents go on the home (D-148).
 *
 * It is not the cap on what gets stored --that is `MAX_HISTORY`, and it is larger--: it is how
 * many fit without pushing settings and attribution out of reach. The rest lives behind "see
 * more".
 */
private const val HOME_RECENT = 3

/** The key the system input returns what was dictated or typed under. */
private const val KEY_SPOKEN = "spoken"

/**
 * The **watch's native** input, not Google's recognizer.
 *
 * `ACTION_REMOTE_INPUT` opens the system's input picker, which on Wear OS offers voice, keyboard
 * and handwriting on a single surface -- the same one any quick reply on the watch uses.
 * `RecognizerIntent` opened **only** Google's recognizer, which is a separate app and is not
 * always there.
 *
 * ⚠️ **What is lost, and it is not cosmetic**: `RecognizerIntent` accepted `EXTRA_LANGUAGE` with
 * the pack's language, so you dictated in THAT language. The system input uses the **watch's**
 * language. With the watch in Spanish and the English pack open, dictating will transcribe in
 * Spanish. That is why the label names the dictionary: it is all that is left to tell the user
 * what they are searching in.
 */
private fun nativeInputIntent(label: String): Intent {
    val input = RemoteInput.Builder(KEY_SPOKEN)
        .setLabel(label)
        // A dictionary does not search for emoji, and removing them takes a tab out of the picker.
        //
        // ⚠️ The action type (Search instead of Send) **cannot be set from Kotlin**:
        // `setInputActionType` is public but `WearableRemoteInputExtender`'s `INPUT_ACTION_TYPE_*`
        // constants are `internal` in wear-input 1.2.0 --verified by disassembling the .aar, they
        // appear as `$wear_input_release`--. From Java they look public; from Kotlin they do not.
        // It stays on the default.
        .wearableExtender { setEmojisAllowed(false) }
        .build()
    return RemoteInputIntentHelper.putRemoteInputsExtra(
        RemoteInputIntentHelper.createActionRemoteInputIntent(),
        listOf(input),
    )
}

