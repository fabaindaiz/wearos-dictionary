package cl.fadiaz.dictionary.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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

/**
 * The saved words.
 *
 * It is the same list as the history and the results one --[ListRow], 48 dp, a single line with
 * ellipsis-- because they are the same thing: a headword you tap to open. The only difference is
 * where it comes from.
 *
 * Unlike the history it has no cap of three: the history is three because it competes for the
 * screen with the results, and this competes with nothing.
 */
@Composable
fun FavoritesScreen(favorites: List<Visit>, onOpen: (Visit) -> Unit) {
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
            item(key = "cabecera") { ListHeader { Text(stringResource(R.string.saved_title)) } }

            if (favorites.isEmpty()) {
                item(key = "vacio") {
                    Text(
                        // It says HOW to save, not just that there is nothing: an empty state
                        // that does not explain the way out of it is a dead end.
                        text = stringResource(R.string.saved_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                }
            }

            items(
                count = favorites.size,
                key = { index ->
                    val visit = favorites[index]
                    "f:${visit.packId}:${visit.entryId}"
                },
            ) { index ->
                val visit = favorites[index]
                ListRow(
                    headword = visit.headword,
                    detail = visit.partOfSpeech?.let { posLabel(it) },
                ) { onOpen(visit) }
            }
        }
    }
}
