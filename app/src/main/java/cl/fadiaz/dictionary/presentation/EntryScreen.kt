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
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
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

@Composable
fun EntryScreen(
    entryId: Long,
    // No default: a word painted as tappable that navigates nowhere is worse than not painting
    // it, and it is indistinguishable from one that works (same rule as D-084).
    onOpenWord: (Long) -> Unit,
    onBackToSearch: () -> Unit = {},
    /**
     * The menu actions, built from the already loaded entry.
     *
     * A function and not a list: "save" or "remove from saved" depends on the concrete word, and
     * the headword is needed to copy it. Returning empty = the menu is not offered, which is not
     * the same as offering an empty menu.
     */
    actions: (Entry) -> List<EntryAction> = { emptyList() },
    resolveIn: suspend (Set<String>) -> Map<String, Long> = { emptyMap() },
    // It goes last so it stays the trailing lambda: that is how the screens and tests call it.
    cargar: suspend (Long) -> Entry?,
) {
    var entry by remember(entryId) { mutableStateOf<Entry?>(null) }
    var failure by remember(entryId) { mutableStateOf(false) }
    var expanded by remember(entryId) { mutableStateOf(false) }
    var links by remember(entryId) { mutableStateOf(emptyMap<String, Long>()) }
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

    // Which words in the glosses are headwords of the pack, in ONE query for the whole screen
    // and not one per word. Only the ones that exist get painted, so the colour promises upfront
    // that it leads somewhere. It lives in its own effect because it depends on the loaded entry.
    LaunchedEffect(entry) {
        val loaded = entry ?: return@LaunchedEffect
        val keys = loaded.senses.flatMap { GlossTokenizer.tokenize(it.gloss) }
            .map { it.norm }
            .toSet()
        links = runCatching { resolveIn(keys) }
            .getOrDefault(emptyMap())
            // A link to the entry we are already reading leads nowhere.
            .filterValues { it != entryId }
    }

    // Anchored at 1 and not 0: item 0 is the shortcut to the search, and the screen has to open
    // showing THE WORD. This way the shortcut is one scroll up and costs none of the rows that
    // are visible -- which is the condition it came in under (D-084). Measured: without this,
    // "Show more" with three short senses no longer fit on screen.
    val listState = rememberTransformingLazyColumnState(initialAnchorItemIndex = 1)
    val focusRequester = remember { FocusRequester() }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            contentPadding = withBottomMargin(contentPadding),
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
                    current?.partOfSpeech?.let { pos ->
                        Text(
                            text = posInSpanish(pos),
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
                        text = "Esa entrada ya no está en el diccionario.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
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
                        text = "Ver más ($hidden)",
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
        title = { Text("Opciones") },
    ) {
        items(actionsFor.size) { index ->
            val action = actionsFor[index]
            Pill(
                text = action.label,
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
data class EntryAction(val label: String, val onClick: () -> Unit)

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
            description = "Buscar",
            onClick = onBackToSearch,
            modifier = Modifier.weight(1f),
        )
        if (onOpenMenu != null) {
            IconPill(
                icono = Icons.Filled.MoreVert,
                description = "Opciones",
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
 */
@Composable
private fun SenseBlock(
    number: Int,
    sense: Sense,
    links: Map<String, Long>,
    onOpenWord: (Long) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            text = annotatedGloss("$number. ", sense.gloss, links, onOpenWord),
            style = MaterialTheme.typography.bodyMedium,
        )
        sense.examples.forEach { ejemplo ->
            Text(
                text = ejemplo,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, start = 10.dp),
            )
        }
        // The synonyms go on a single line and after the example: they are a help, not the
        // definition. The cap of four already comes from the payload (MAX_SYNONYMS_PER_SENSE),
        // so nothing needs to be trimmed here.
        if (sense.synonyms.isNotEmpty()) {
            Text(
                text = "sin. " + sense.synonyms.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, start = 10.dp),
            )
        }
        // The antonyms, below and at the same visual weight (D-126). The prefix is NOT optional
        // and cannot look like "sin.": the two lists look identical and the only difference
        // between "another way to say it" and "the opposite" is those four letters.
        if (sense.antonyms.isNotEmpty()) {
            Text(
                text = "ant. " + sense.antonyms.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, start = 10.dp),
            )
        }
    }
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
    links: Map<String, Long>,
    onOpenWord: (Long) -> Unit,
): AnnotatedString {
    val style = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary))
    return remember(prefijo, gloss, links, style) {
        buildAnnotatedString {
            append(prefijo)
            var cursor = 0
            for (word in GlossTokenizer.tokenize(gloss)) {
                val target = links[word.norm] ?: continue
                if (word.start > cursor) append(gloss.substring(cursor, word.start))
                withLink(
                    LinkAnnotation.Clickable("palabra:$target", style) { onOpenWord(target) },
                ) {
                    append(gloss.substring(word.start, word.end))
                }
                cursor = word.end
            }
            if (cursor < gloss.length) append(gloss.substring(cursor))
        }
    }
}
