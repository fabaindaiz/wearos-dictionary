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

/**
 * Una lista de palabras visitadas: las guardadas, o el historial completo (D-148).
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
    /** El encabezado. Un parametro y no una constante: esta pantalla sirve a dos listas. */
    @StringRes title: Int,
    /** Que decir cuando no hay nada. Una lista vacia sin explicacion parece rota. */
    @StringRes empty: Int,
    /**
     * `packId` -> la etiqueta de idioma o fuente. La arma `resultTags`.
     *
     * Vacio por defecto para que una pantalla de test que no la cablea siga andando; un `packId`
     * que no este simplemente no recibe etiqueta, igual que en los resultados.
     */
    tags: Map<String, String> = emptyMap(),
    onOpen: (Visit) -> Unit,
) {
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
                ListRow(
                    headword = visit.headword,
                    detail = wordDetail(
                        visit.partOfSpeech?.let { posLabel(it) },
                        tags[visit.packId],
                    ),
                ) { onOpen(visit) }
            }
        }
    }
}
