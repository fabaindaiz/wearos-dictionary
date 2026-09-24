package cl.fadiaz.dictionary.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import cl.fadiaz.dictionary.R
import cl.fadiaz.dictionary.data.Visit
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.Icon
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.text.style.TextOverflow

/**
 * A list of visited words: the saved ones, or the full history (D-148).
 *
 * It is the same list as the history and the results one --[ListRow], 48 dp, a single line with
 * ellipsis-- because they are the same thing: a headword you tap to open. The only difference is
 * where it comes from.
 *
 * Unlike the history it has no cap of three: the history is three because it competes for the
 * screen with the results, and this competes with nothing.
 */
@Composable
fun WordListScreen(
    words: List<Visit>,
    /** The header. A parameter and not a constant: this screen serves two lists. */
    @StringRes title: Int,
    /** What to say when there is nothing. An empty list with no explanation looks broken. */
    @StringRes empty: Int,
    /**
     * `packId` -> the language tag. `historyTags` assembles it.
     *
     * Empty by default so a test screen that does not wire it still works; a `packId` that is not
     * there simply gets no tag, just as in the results.
     */
    /**
     * `packId` -> language tag, the **fallback** when the visit does not carry its own (D-265).
     *
     * ⚠️ **A map and not a single one**: this list can bring words from a pack that is no longer
     * installed, and inheriting the active language for them would assert something nobody
     * checked. See `visitTag`.
     */
    tags: Map<String, String> = emptyMap(),
    /**
     * Removing a word from the list, or `null` if this list is not curated by hand (D-155).
     *
     * ⚠️ **The saved ones yes, the history no.** A saved word you put there yourself with one tap
     * and undoing it had to cost the same; the history fills itself as you open words and already
     * has a cap, so a button per row would invite work with no reward -- to empty it whole there
     * is Settings.
     */
    onDelete: ((Visit) -> Unit)? = null,
    onOpen: (Visit) -> Unit,
) {
    // Which row is armed for deletion. One at a time: arming another disarms the previous one,
    // which is what keeps the state always obvious.
    var armada by remember { mutableStateOf<Visit?>(null) }
    val listState = rememberTransformingLazyColumnState()
    val focusRequester = remember { FocusRequester() }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            contentPadding = withScreenMargins(contentPadding),
            state = listState,
            modifier = Modifier.rotaryScrollable(
                RotaryScrollableDefaults.behavior(listState),
                focusRequester,
            ).focusRequester(focusRequester).requestFocusOnHierarchyActive(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "cabecera") { ListHeader { Text(stringResource(title)) } }

            if (words.isEmpty()) {
                item(key = "vacio") {
                    Text(
                        // It says HOW to save, not just that there is nothing: an empty state
                        // that does not explain the way out of it is a dead end.
                        text = stringResource(empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                }
            }

            items(
                count = words.size,
                key = { index ->
                    val visit = words[index]
                    "f:${visit.packId}:${visit.entryId}"
                },
            ) { index ->
                val visit = words[index]
                WordRow(
                    headword = visit.headword,
                    detail = wordDetail(
                        visit.partOfSpeech?.let { posLabel(it) },
                        visitTag(visit, tags),
                    ),
                    armada = armada == visit,
                    onArm = onDelete?.let { { armada = visit } },
                    onConfirm = {
                        onDelete?.invoke(visit)
                        armada = null
                    },
                    // ⚠️ With something armed, a tap on ANOTHER row disarms and does not navigate:
                    // if it opened the word, you would leave the screen with a red row waiting for
                    // you on your return and no obvious way to cancel.
                    onOpen = { if (armada != null) armada = null else onOpen(visit) },
                )
            }
        }
    }

}

/**
 * A word row that is **armed** by long-pressing it, and confirmed with a tap (D-155).
 *
 * ⚠️ **A gesture and not a button, and on a watch that is not aesthetics.** A 48 dp button beside
 * every row eats the lemma's width exactly where the lemma is all that matters; and it sits right
 * against the target that opens the word, so an imprecise tap deletes what you wanted to read.
 * Long-pressing is the gesture the whole system uses to reveal the destructive, and it **has no
 * way of firing by accident**.
 *
 * Armed, the row is painted in the error colour and says what is about to happen. The second tap
 * deletes.
 */
@Composable
private fun WordRow(
    headword: String,
    detail: String?,
    armada: Boolean,
    onArm: (() -> Unit)?,
    onConfirm: () -> Unit,
    onOpen: () -> Unit,
) {
    if (armada) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(PILL_SHAPE)
                .background(MaterialTheme.colorScheme.error)
                .clickable(onClick = onConfirm)
                .heightIn(min = TOUCH_TARGET)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onError,
            )
            Text(
                text = stringResource(R.string.saved_remove),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onError,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = headword,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onError,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PILL_SHAPE)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .combinedClickable(onClick = onOpen, onLongClick = onArm)
            .heightIn(min = TOUCH_TARGET)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = headword,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
