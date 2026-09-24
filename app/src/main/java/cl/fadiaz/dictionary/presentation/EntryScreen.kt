package cl.fadiaz.dictionary.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import cl.fadiaz.dictionary.R
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import cl.fadiaz.dictionary.data.TextScale
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import cl.fadiaz.dictionary.core.Entry
import cl.fadiaz.dictionary.core.GlossTokenizer
import cl.fadiaz.dictionary.core.Sense
import cl.fadiaz.dictionary.core.PayloadCodec
import cl.fadiaz.dictionary.core.TextNormalizer

/**
 * The body of an entry. It is the only screen that decompresses a payload: the list is served
 * entirely from the covering index, without touching the table (D-012).
 *
 * WHY IT CUTS OFF AT THREE SENSES
 *
 * Measured over the 3,000 best-ranked entries, which are the ones most likely to be opened: the
 * median is **3 senses**, p90 is 7 and the maximum is **47**. Cutting at three leaves half the
 * entries untouched --no extra button, no extra gesture-- and keeps "justicia", with its ten,
 * from turning into a scroll where the useful sense sits below nine nobody was looking for.
 */
private const val VISIBLE_SENSES = 3

/**
 * Where a link leads: **which pack and which entry**.
 *
 * ⚠️ **The `packId` is not decorative and translation forced it.** A link used to be a bare `Long`
 * and `onOpenWord` opened it in the SAME pack, deliberately: sending an `entryId` to another pack
 * opens **another word, with no error** (D-080). While every link was a word from the same gloss
 * that sufficed. A translation necessarily goes to another dictionary, so the destination has to
 * say which one -- and the usual case becomes the same type with its own pack, not an exception.
 */
data class WordLink(val packId: String, val entryId: Long)

/**
 * Where a term to be resolved came from, which is what decides **which language** to look it up in.
 *
 * ⚠️ **The distinction did not exist and produced a bug the user reported.** Until a pack held
 * both languages in one file, "resolve in the same pack" implied "in the same language"; making it
 * bidirectional broke that equivalence in silence. `pie` is Spanish --the body part-- **and**
 * English --the pastry-- so tapping `foot`'s translation `pie` opened the English `pie`: a
 * translation that sends you back to the language you came from.
 *
 * Measured over the real pack: **8.30 %** of the translations of English entries (7,757 of 93,473)
 * resolved to the wrong language.
 */
enum class TermSource {
    /** A word from the gloss: it is in **the entry's** language. */
    GLOSS,

    /** A term from the translations list: it is in **the other** language. */
    TRANSLATION,
}

@Composable
fun EntryScreen(
    entryId: Long,
    // No default: a word painted as tappable that navigates nowhere is worse than not painting
    // it, and it is indistinguishable from one that works (same rule as D-084).
    onOpenWord: (WordLink) -> Unit,
    onBackToSearch: () -> Unit = {},
    /**
     * The menu actions, built from the already loaded entry.
     *
     * A function and not a list: "save" or "remove from saved" depends on the concrete word, and
     * the headword is needed to copy it. Returning empty = the menu is not offered, which is not
     * the same as offering an empty menu.
     */
    actions: (Entry) -> List<EntryAction> = { emptyList() },
    /**
     * The current text size, or `null` to not offer the selector.
     *
     * ⚠️ **It lives in the card's menu and not only in Settings, and the reason is when it gets
     * noticed.** That the type is small is discovered **while reading a definition**, not while
     * navigating settings: asking the reader to leave the word, cross two screens and come back is
     * exactly the kind of trip the Wear OS guidance asks to avoid. Here it costs one tap and the
     * effect is visible on the text underneath the dialog.
     */
    textScale: TextScale? = null,
    onTextScaleChange: (TextScale) -> Unit = {},
    /**
     * Which terms on this screen are lemmas, and which entry they lead to.
     *
     * The second argument is **the open entry's** language and the third says whether the term is
     * in that language or in the other one. See [TermSource]: without it, a translation can
     * resolve to a word in the language you came from.
     */
    resolveIn: suspend (Set<String>, String?, TermSource) -> Map<String, WordLink> =
        { _, _, _ -> emptyMap() },
    // It goes last so it stays the trailing lambda: that is how the screens and tests call it.
    cargar: suspend (Long) -> Entry?,
) {
    var entry by remember(entryId) { mutableStateOf<Entry?>(null) }
    var failure by remember(entryId) { mutableStateOf(false) }
    var expanded by remember(entryId) { mutableStateOf(false) }
    var links by remember(entryId) { mutableStateOf(emptyMap<String, WordLink>()) }
    var menuOpen by remember(entryId) { mutableStateOf(false) }
    val actionsFor = entry?.let(actions).orEmpty()

    LaunchedEffect(entryId) {
        // Without the try, anything `load` throws --a SQLiteException, an inflate over a
        // truncated pack-- rises through the composition coroutine and kills the process. An
        // unreadable pack has to degrade to the message that already exists below, just like an
        // entry that is not there.
        entry = runCatching { cargar(entryId) }.getOrNull()
        failure = entry == null
    }

    // Which words on the screen are lemmas of the pack, **in two queries for the whole card** and
    // not one per word. Only the ones that exist get painted, so the colour promises up front that
    // it leads somewhere. It lives in an effect of its own because it depends on the loaded entry.
    //
    // ⚠️ **Two queries and not one, and the reason is a hard cap**: `MAX_PALABRAS_POR_CONSULTA` is
    // 64 because each key is a bound SQLite parameter. Glosses already average 39.9 keys in
    // Spanish and 58.8 in English, so adding each sense's synonyms, antonyms and related words
    // would overflow the cap and **trim in silence** -- and what got trimmed would be precisely
    // the terms, which are the most valuable as links. Kept apart, each has its own cap. Measured:
    // 0.18 ms each in Spanish.
    LaunchedEffect(entry) {
        val loaded = entry ?: return@LaunchedEffect
        val deLaGlosa = loaded.senses.flatMap { GlossTokenizer.tokenize(it.gloss) }
            .map { it.norm }
            .toSet()
        // ⚠️ **Synonyms, antonyms and related words go with the GLOSS, not with the
        // translations.** They are in the entry's language --a synonym of `casa` is Spanish-- and
        // putting them in the translations bag would have resolved them in the wrong language,
        // which is the same bug in reverse.
        val propios = loaded.senses
            .flatMap { it.synonyms + it.antonyms + it.related }
            .map { TextNormalizer.norm(it) }
            .filterNot { it.isEmpty() }
            .toSet()
        val deLasTraducciones = (loaded.senses.flatMap { it.translations } +
            loaded.wordTranslations)
            .map { TextNormalizer.norm(it) }
            .filterNot { it.isEmpty() }
            .toSet()
        // ⚠️ **Two calls with DIFFERENT languages, and that is half the fix.** The gloss's words
        // are in the entry's language; the translation lists' terms are in the other one.
        // Resolving them all alike is what sent `pie` to the English `pie`.
        // ⚠️ **Three queries and not two**, and the reason is the same hard cap as before:
        // `MAX_PALABRAS_POR_CONSULTA` is 64 and glosses already average 39.9 keys in Spanish and
        // 58.8 in English, so merging them would overflow and **trim in silence**. Each cost a
        // measured 0.18 ms.
        links = runCatching {
            resolveIn(deLaGlosa, loaded.lang, TermSource.GLOSS) +
                resolveIn(propios, loaded.lang, TermSource.GLOSS) +
                resolveIn(deLasTraducciones, loaded.lang, TermSource.TRANSLATION)
        }
            .getOrDefault(emptyMap())
            // A link to the entry you are already reading leads nowhere.
            .filterValues { it.entryId != entryId }
    }

    // Anchored at 1 and not 0: item 0 is the shortcut to the search, and the screen has to open
    // showing THE WORD. This way the shortcut is one scroll up and costs none of the rows that
    // are visible -- which is the condition it came in under (D-084). Measured: without this,
    // "Show more" with three short senses no longer fit on screen.
    val listState = rememberTransformingLazyColumnState(initialAnchorItemIndex = 1)
    val focusRequester = remember { FocusRequester() }

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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val current = entry
            // The quick way out to the search, living as the FIRST ROW OF THE SCROLL and not as
            // fixed chrome: the very top is one crown turn away, and it costs zero dp of
            // permanent screen. A fixed button would cost 48 dp, which is exactly what D-084
            // rejected. It is needed because tapping words stacks entries: without it, getting
            // back to the start from three words deep is three gestures.
            item(key = "acciones") {
                EntryActionsMenu(
                    onBackToSearch = onBackToSearch,
                    // Null while the entry has not loaded: a menu button that opens nothing is
                    // worse than a button that is not there yet.
                    onOpenMenu = if (actionsFor.isEmpty()) null else { { menuOpen = true } },
                )
            }
            item(key = "encabezado") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    Text(
                        // The headword is NOT truncated here, unlike in the list: this screen
                        // exists precisely to read the whole word, sayings included.
                        text = current?.headword ?: "…",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // ⚠️ **Bare, with no slashes or brackets, and that is a decision.** The pack
                    // stores the transcription stripped of the source's delimiters
                    // (d-a2f271-13da99), so the card no longer knows whether it was phonemic
                    // `/…/` or phonetic `[…]`. Adding either back would assert a notation nothing
                    // recorded -- the D-080 family -- and on 234 dp it would cost two characters
                    // to say something possibly false. A printed dictionary puts it here, right
                    // under the headword and before the part of speech.
                    val ipa = current?.pronunciation
                    if (!ipa.isNullOrBlank()) {
                        Text(
                            text = ipa,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        )
                    }
                    val pos = current?.partOfSpeech
                    // ⚠️ **The language comes from the LOADED ENTRY, not from a parameter.** This
                    // screen is reached by tapping a translation, and then the open entry is in
                    // the OTHER language: taking it from the pack --which in a bidirectional one
                    // speaks two-- would assert the wrong language, which is worse than putting
                    // nothing.
                    //
                    // It is the LANGUAGE and never the source (D-190): *"it should just be EN, ES.
                    // I do not like having an ENWIK... because all I care about is knowing the
                    // language it comes from"*.
                    val languageTag = current?.lang?.uppercase()
                    if (pos != null || languageTag != null) {
                        Text(
                            // In full, not abbreviated: this screen competes for width with
                            // nothing, and it is where the part of speech actually gets read. The
                            // language goes after it, with the same separator the results row
                            // uses.
                            text = listOfNotNull(pos?.let { posLabelFull(it) }, languageTag)
                                .joinToString(stringResource(R.string.entry_list_separator)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        )
                    }
                }
            }

            if (failure) {
                item(key = "fallo") {
                    Text(
                        text = stringResource(R.string.entry_gone),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                }
            }

            // ⚠️ **The WORD's translations go here, outside `SenseBlock`, and that placement is
            // half the design.** A list drawn under a sense **asserts** that it belongs to it, and
            // what lands here is precisely what the source could not attribute: merging them would
            // undo on screen what the format separated (D-117), and the error would read
            // perfectly plausible.
            //
            // ⚠️ **They go BEFORE the senses, and that reverses what this comment used to say.**
            // They were after, on the reasoning that "the definitions are what the reader came
            // for". The measurement that was already right here disproves it: **48.6 %** of the
            // entries with a translation have **only** these, so for half the cases the section
            // that went last was the whole answer, and it sat below a `See more (12)` you have to
            // tap to reach. Asked for: *"that the per-word translation, when available with no
            // senses, appear at the start"*.
            //
            // Only 3.0 % shows both sections at once, so the cost of pushing the senses down is
            // paid by one entry in thirty.
            //
            // With `prominent`: it hangs off no sense, so it is not drawn subordinate to one.
            // ⚠️ **Forms come BEFORE the translations and carry a sense's weight**, which was
            // the request. The order is not cosmetic: *how this word is spelled* is a question
            // about the word itself, and it is answered before *how it is said in another
            // language*. With `prominent = true` it draws at the margin in `bodyMedium`, the
            // same body a gloss gets, rather than hanging off a sense in `labelSmall`.
            //
            // ⚠️ **These are the principal parts, not the conjugation.** `correr` has 202 rows
            // in `form`; what comes out here is `corriendo` and `corrido`. See
            // `PayloadCodec.TAG_FORM`.
            //
            // ⚠️ **And they are NOT tappable**, unlike synonyms and translations: an inflected
            // form is not a headword, so it has no card of its own to go to. Painting something
            // that leads nowhere teaches the reader not to trust the colour (D-094).
            val forms = current?.forms.orEmpty()
            if (forms.isNotEmpty()) {
                item(key = "formas") {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        TermList(
                            R.string.entry_forms_title,
                            forms.map { labelledForm(it) },
                            links = emptyMap(),
                            onOpenWord = {},
                            prominent = true,
                        )
                    }
                }
            }

            val wordTranslations = current?.wordTranslations.orEmpty()
            if (wordTranslations.isNotEmpty()) {
                item(key = "traducciones-palabra") {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        TermList(
                            R.string.entry_word_translations_title,
                            wordTranslations,
                            links,
                            onOpenWord,
                            prominent = true,
                        )
                    }
                }
            }

            val allSenses = current?.senses.orEmpty()
            val visibleOnes = if (expanded) allSenses else allSenses.take(VISIBLE_SENSES)
            val hidden = allSenses.size - visibleOnes.size

            // With `key` the item keeps its identity when expanding; without it, the lazy
            // layout identifies them by position. It is the same cause that stole the text
            // field's focus on the search, and here it also leaves `visibleOnes` captured by
            // closure.
            items(count = visibleOnes.size, key = { index -> "s:$index" }) { index ->
                // getOrNull and not [index]: the item lambda and the count are consumed by the
                // layout on different frames. Skipping a sense for one frame is acceptable;
                // throwing is not.
                visibleOnes.getOrNull(index)?.let { sense ->
                    SenseBlock(
                        number = index + 1,
                        sense = sense,
                        links = links,
                        onOpenWord = onOpenWord,
                    )
                }
            }

            if (hidden > 0) {
                item(key = "ver-mas") {
                    Pill(
                        text = stringResource(R.string.entry_show_more, hidden),
                        background = MaterialTheme.colorScheme.surfaceContainer,
                        ink = MaterialTheme.colorScheme.onSurfaceVariant,
                        margin = 24.dp,
                        onClick = { expanded = true },
                    )
                }
            }
        }

    // The options menu. Wear's `AlertDialog` and not a hand-rolled one: it is the one that keeps
    // the system's swipe-to-dismiss, which the quality list requires on nearly every screen
    // (WO-V3), and it also avoids adding a navigation level --the guidance asks for at most two--.
    //
    // Wear Material3 ships NO dropdown menu and no overflow, verified against the API reference:
    // the two supported shapes are this dialog or pushing a list screen.
    AlertDialog(
        visible = menuOpen && actionsFor.isNotEmpty(),
        onDismissRequest = { menuOpen = false },
        title = { Text(stringResource(R.string.entry_options)) },
    ) {
        if (textScale != null) {
            item {
                // ⚠️ **The buttons SHOW the size they apply instead of naming it**, which is what
                // the user asked for: *"a selector of 3 buttons with different type sizes to
                // represent this setting"*. A small `A`, a medium one and a large one are
                // understood without reading, which on a watch is worth more than a label -- and
                // they also need no translation.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextScale.entries.forEach { opcion ->
                        val elegida = opcion == textScale
                        Text(
                            text = stringResource(R.string.settings_scale_sample),
                            // The button's size IS the scale it represents, applied over the
                            // card's body: what you see is what you will get.
                            fontSize = MaterialTheme.typography.bodyMedium.fontSize * opcion.factor,
                            fontWeight = if (elegida) FontWeight.Bold else FontWeight.Normal,
                            textAlign = TextAlign.Center,
                            color = if (elegida) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier
                                .weight(1f)
                                .clip(PILL_SHAPE)
                                .background(
                                    if (elegida) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainer
                                    },
                                )
                                .clickable { onTextScaleChange(opcion) }
                                .heightIn(min = TOUCH_TARGET)
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            }
        }
        items(actionsFor.size) { index ->
            val action = actionsFor[index]
            Pill(
                text = stringResource(action.label),
                background = MaterialTheme.colorScheme.surfaceContainer,
                ink = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = {
                    // Close first: the action may navigate, and a dialog left open on top of the
                    // new screen is orphaned.
                    menuOpen = false
                    action.onClick()
                },
            )
        }
    }
    }
}


/** An action in a word's menu. The state --e.g. whether it is already saved-- is decided above. */
data class EntryAction(@get:StringRes val label: Int, val onClick: () -> Unit)

/**
 * The two buttons up top: back to the search, and the menu.
 *
 * In ONE row and not stacked, and the arithmetic is the reason: side by side they cost 48 dp
 * --the touch minimum-- and stacked they would cost 96, which on a 234 dp screen is one sense
 * less. Built with `Row` and not Wear Material3's `ButtonGroup` for the same reason as the
 * language selector: with `allWarningsAsErrors`, an API deprecated in the next bump breaks the
 * build, and here there is nothing gained that justifies that risk.
 */
@Composable
private fun EntryActionsMenu(onBackToSearch: () -> Unit, onOpenMenu: (() -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconPill(
            icono = Icons.Filled.Search,
            description = stringResource(R.string.entry_back_to_search),
            onClick = onBackToSearch,
            modifier = Modifier.weight(1f),
        )
        if (onOpenMenu != null) {
            IconPill(
                icono = Icons.Filled.MoreVert,
                description = stringResource(R.string.entry_options),
                onClick = onOpenMenu,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun IconPill(
    icono: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(PILL_SHAPE)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .heightIn(min = TOUCH_TARGET),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icono,
            // Not null as in a decorative icon: here the icon IS the label, so without this the
            // button has no name for anyone using a screen reader.
            contentDescription = description,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One sense: the number carries the order, the gloss is the content and the example follows.
 *
 * The example is secondary and smaller for a measured reason: the median is 64 characters but the
 * maximum is **917** --sixteenth-century chronicles the Wiktionary cites as usage-- and at the
 * same visual weight as the gloss, a single one buries the next sense.
 *
 * **The citation is tertiary, and it is what makes those chronicles legible.** Measured on the
 * English dump, **86,5 %** of the examples are quotations lifted from a published text, so
 * without it the reader gets a sentence out of an 1897 novel with nothing saying so -- which is
 * exactly what sent a user to Wiktionary by hand to find out. It is trimmed to year and author
 * in the builder (average 31 bytes), and capped at two lines here because the p99 is 147 bytes
 * and the maximum 352: the pack keeps the data, the screen decides how much of it fits.
 */
@Composable
private fun SenseBlock(
    number: Int,
    sense: Sense,
    links: Map<String, WordLink>,
    onOpenWord: (WordLink) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        val glosa = annotatedGloss("$number. ", sense.gloss, links)
        LinkedText(
            text = glosa.text,
            targets = glosa.targets,
            onOpenWord = onOpenWord,
            style = MaterialTheme.typography.bodyMedium,
        )
        sense.examples.forEach { ejemplo ->
            Text(
                text = ejemplo.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, start = 10.dp),
            )
            ejemplo.citation?.let { cita ->
                Text(
                    text = stringResource(R.string.entry_example_citation, cita),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
        }
        // The three lists, with their category above and the words below. The category used to go
        // as a prefix --`sin.`, `ant.`, `rel.`-- and now it is written out in full, which is
        // D-159's same rule: in a row it is abbreviated because the lemma needs the width; here it
        // competes with nothing.
        // The translation goes FIRST of the four, and it is the only one of the lists that is not
        // a complement to the gloss but **another answer to the same question**: somebody opening
        // an entry to find out how it is said in the other language wants that, not the fourth
        // line.
        //
        // ⚠️ **It goes inside `SenseBlock`, and that ASSERTS it belongs to this sense.** Only what
        // the source attributed with `sense_index` gets in here; what cannot be attributed is
        // discarded in the builder instead of hanging off the first sense, which is D-117's rule.
        // Its honest place is an entry-level channel that does not yet exist (roadmap §Naming a
        // sense from another pack).
        //
        // ⚠️ **They ARE resolved, and that is why a link carries a `packId`.** They used to go
        // with an empty map because `links` only knew about THIS pack and a term in the other
        // language never resolved. Now the destination says which dictionary it goes to, so
        // `house` can lead to the English pack's entry -- and if that pack is not installed, it
        // does not resolve and is shown unpainted, which is the same promise as always (D-084).
        TermList(R.string.entry_translations_title, sense.translations, links, onOpenWord)
        TermList(R.string.entry_synonyms_title, sense.synonyms, links, onOpenWord)
        // The antonyms, below and with the same visual weight (D-126). ⚠️ **The category is not
        // optional**: the three lists look identical, and the only thing separating "another way
        // of saying it" from "the opposite" is that word.
        TermList(R.string.entry_antonyms_title, sense.antonyms, links, onOpenWord)
        // The related ones, last, because they are the weakest claim of the three: neither another
        // way of saying it nor the opposite, just a neighbour. They exist above all for the THIN
        // entries --one sense, no example-- which are 70.4 % of the Spanish pack, so in practice
        // this list is the only thing under the gloss (D-132).
        TermList(R.string.entry_related_title, sense.related, links, onOpenWord)
    }
}

/**
 * One of a sense's lists: the category above, the words below and **tappable**.
 *
 * Asked for: *"improve the synonyms and antonyms view, showing the category first and the words
 * below, with the ability to click them to go to them"*.
 *
 * ⚠️ **It costs one more line per list, and at 234 dp that is paid for.** It is accepted because
 * the line it adds is the one saying which list you are reading, which is the information D-126
 * and D-132 say cannot be missing; and it is made cheaper with no vertical padding between the
 * title and its words, so the two lines together take less than one list row.
 *
 * ⚠️ **The title does NOT use the links' colour, and that corrects a design mistake.** It used to
 * be `primary`, which on this screen is exactly the colour a navigating word is painted in: the
 * heading promised a tap that never existed. Asked for: *"the prefixes that indicate things like
 * translation, synonyms and so on should be more prominent and visible and not clickable like
 * hyperlinks"*. It now stands out by **weight** --bold over `onSurface`-- which is what
 * distinguishes a heading from a link without competing with it.
 *
 * ⚠️ **Only what exists in the pack is painted as a link**, same as in the gloss: the colour is
 * the promise that it leads somewhere, and a painted word that does not navigate is worse than an
 * unpainted one.
 */
@Composable
private fun TermList(
    @StringRes title: Int,
    terms: List<String>,
    links: Map<String, WordLink>,
    onOpenWord: (WordLink) -> Unit,
    /**
     * Whether the section carries the same weight as a sense rather than hanging off one.
     *
     * Asked for: *"that these individual sections have the same prominence as a sense"*. What
     * changes is the text body and the indent: an entry-level list is subordinate to nothing, so
     * it starts at the margin and not indented under a gloss.
     */
    prominent: Boolean = false,
) {
    if (terms.isEmpty()) return
    val indent = if (prominent) 0.dp else 10.dp
    Text(
        text = stringResource(title),
        style = if (prominent) {
            MaterialTheme.typography.bodyMedium
        } else {
            MaterialTheme.typography.labelMedium
        },
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 6.dp, start = indent),
    )
    val contenido = linkedTerms(terms, links)
    LinkedText(
        text = contenido.text,
        targets = contenido.targets,
        onOpenWord = onOpenWord,
        style = if (prominent) {
            MaterialTheme.typography.bodyMedium
        } else {
            MaterialTheme.typography.labelSmall
        },
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = indent),
    )
}

/** The terms separated by the usual separator, with the known ones as links. */
@Composable
private fun linkedTerms(
    terms: List<String>,
    links: Map<String, WordLink>,
): LinkedContent {
    val separator = stringResource(R.string.entry_list_separator)
    val color = MaterialTheme.colorScheme.primary
    return remember(terms, links, separator, color) {
        val targets = mutableListOf<Pair<IntRange, WordLink>>()
        val text = buildAnnotatedString {
            terms.forEachIndexed { index, term ->
                if (index > 0) append(separator)
                val target = links[TextNormalizer.norm(term)]
                if (target == null) {
                    append(term)
                } else {
                    val desde = length
                    withStyle(SpanStyle(color = color)) { append(term) }
                    targets += (desde until length) to target
                }
            }
        }
        LinkedContent(text, targets)
    }
}

/**
 * One principal part as the card shows it: `corriendo (gerundio)`.
 *
 * ⚠️ **The label comes from the app and not from the pack**, and that is the point of the
 * channel: the pack stores a neutral key (`ger`, `part`) because the same file is shared by a
 * user running the interface in Spanish and one running it in English. Translating belongs to the
 * localization, never to the artefact.
 *
 * ⚠️ **An unknown key shows the form WITHOUT a label rather than hiding it.** Somebody else's
 * pack may declare parts this version cannot name, and `corriendo` with no note is still
 * information; `corriendo` missing is not.
 */
@Composable
private fun labelledForm(form: PayloadCodec.InflectedForm): String {
    val label = when (form.key) {
        "ger" -> R.string.entry_form_gerund
        "part" -> R.string.entry_form_participle
        "pl" -> R.string.entry_form_plural
        "fem" -> R.string.entry_form_feminine
        else -> null
    } ?: return form.form
    return stringResource(R.string.entry_form_item, form.form, stringResource(label))
}

/**
 * A text whose linked words are resolved **by proximity**, not by hitting the glyph.
 *
 * ⚠️ **This replaces `LinkAnnotation.Clickable`, and the reason is geometry.** That API makes the
 * touch area exactly the painted glyph: measured on these packs, a word of average length is
 * about 40 x 14 dp at `labelSmall` on 234 dp, against Android's 48 x 48 minimum. The height is
 * 3.4x under, and a mis-tap was the normal case rather than the exception.
 *
 * The decision of *which* word a tap meant is [GlossTap], which is pure and covered by the gate.
 * What lives here is only the measuring: turning each linked range into the rectangles it
 * occupies, which needs a `TextLayoutResult` and therefore a device.
 *
 * ⚠️ **A word that wraps produces one box per line**, not one box spanning both. A single
 * rectangle around a wrapped word would cover the whole width of the paragraph between its two
 * halves, and every tap in that band would snap to it.
 */
@Composable
private fun LinkedText(
    text: AnnotatedString,
    targets: List<Pair<IntRange, WordLink>>,
    onOpenWord: (WordLink) -> Unit,
    style: TextStyle,
    // `modifier` first among the optionals: lint requires it, and lint breaks the build here.
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = text,
        style = style,
        color = color,
        onTextLayout = { layout = it },
        // ⚠️ **Keyed on `targets` ALONE, never on `layout`.** The first version added `layout`
        // and that is a recomposition loop: the layout arrives after the first pass, re-keys
        // `pointerInput`, which recomposes, which lays out again. Robolectric reported it as
        // *"Compose did not get idle after 60 SECONDS"* on a screen that has nothing to do with
        // gestures. The lambda reads the current `layout` through the state it captured, so it
        // does not need to be a key to be up to date.
        modifier = modifier.pointerInput(targets) {
            detectTapGestures { position ->
                val resultado = layout ?: return@detectTapGestures
                if (targets.isEmpty()) return@detectTapGestures
                val cajas = buildList {
                    targets.forEachIndexed { index, (rango, _) ->
                        val primera = resultado.getLineForOffset(rango.first)
                        val ultima = resultado.getLineForOffset(rango.last)
                        for (linea in primera..ultima) {
                            val izquierda = if (linea == primera) {
                                resultado.getHorizontalPosition(rango.first, usePrimaryDirection = true)
                            } else {
                                resultado.getLineLeft(linea)
                            }
                            val derecha = if (linea == ultima) {
                                resultado.getHorizontalPosition(rango.last + 1, usePrimaryDirection = true)
                            } else {
                                resultado.getLineRight(linea)
                            }
                            add(
                                GlossTap.LinkBox(
                                    index = index,
                                    line = linea,
                                    left = minOf(izquierda, derecha),
                                    top = resultado.getLineTop(linea),
                                    right = maxOf(izquierda, derecha),
                                    bottom = resultado.getLineBottom(linea),
                                ),
                            )
                        }
                    }
                }
                // The radius comes from the REAL line height, not a dp constant: it is the
                // only form that survives the text-scale setting. See GlossTap.radiusFor.
                val alto = if (resultado.lineCount > 0) {
                    resultado.getLineBottom(0) - resultado.getLineTop(0)
                } else {
                    0f
                }
                val elegido = GlossTap.linkAt(
                    cajas,
                    position.x,
                    position.y,
                    GlossTap.radiusFor(alto),
                ) ?: return@detectTapGestures
                onOpenWord(targets[elegido].second)
            }
        },
    )
}

/**
 * The gloss with its known words turned into links.
 *
 * The ranges come from [GlossTokenizer], which decides what a word is by delegating to `norm()`
 * --the same function the index was built with-- so the span that gets painted is exactly the one
 * that was queried. Words that are not headwords stay plain text: the colour is the promise that
 * tapping leads somewhere.
 */
@Composable
private fun annotatedGloss(
    prefijo: String,
    gloss: String,
    links: Map<String, WordLink>,
): LinkedContent {
    val color = MaterialTheme.colorScheme.primary
    return remember(prefijo, gloss, links, color) {
        val targets = mutableListOf<Pair<IntRange, WordLink>>()
        val text = buildAnnotatedString {
            append(prefijo)
            var cursor = 0
            for (word in GlossTokenizer.tokenize(gloss)) {
                val target = links[word.norm] ?: continue
                if (word.start > cursor) append(gloss.substring(cursor, word.start))
                val desde = length
                withStyle(SpanStyle(color = color)) {
                    append(gloss.substring(word.start, word.end))
                }
                targets += (desde until length) to target
                cursor = word.end
            }
            if (cursor < gloss.length) append(gloss.substring(cursor))
        }
        LinkedContent(text, targets)
    }
}

/** A text and where its linked words are, so the tap can be resolved by proximity. */
private data class LinkedContent(
    val text: AnnotatedString,
    val targets: List<Pair<IntRange, WordLink>>,
)
