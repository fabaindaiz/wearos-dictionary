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
    /**
     * Quitar una palabra de la lista, o `null` si esta lista no se cura a mano (D-155).
     *
     * ⚠️ **Las guardadas si, el historial no.** Una guardada la pusiste vos con un toque y
     * deshacerlo tenia que costar lo mismo; el historial se llena solo al abrir palabras y ya
     * tiene tope, asi que un boton por fila invitaria a un trabajo sin recompensa -- para
     * vaciarlo entero ya esta Ajustes.
     */
    onDelete: ((Visit) -> Unit)? = null,
    onOpen: (Visit) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<Visit?>(null) }
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
                WordRowWithDelete(
                    headword = visit.headword,
                    detail = wordDetail(
                        visit.partOfSpeech?.let { posLabel(it) },
                        tags[visit.packId],
                    ),
                    onDelete = onDelete?.let { { pendingDelete = visit } },
                    onOpen = { onOpen(visit) },
                )
            }
        }
    }

    // La confirmacion, con el mismo lenguaje que borrar un diccionario: el boton destructivo en
    // rojo y el de cancelar neutro, para que el toque por inercia no sea el que borra.
    val candidata = pendingDelete
    AlertDialog(
        visible = candidata != null,
        onDismissRequest = { pendingDelete = null },
        title = {
            Text(stringResource(R.string.saved_remove_question, candidata?.headword.orEmpty()))
        },
    ) {
        item {
            Pill(
                text = stringResource(R.string.saved_remove),
                background = MaterialTheme.colorScheme.error,
                ink = MaterialTheme.colorScheme.onError,
                margin = 8.dp,
                onClick = {
                    candidata?.let { onDelete?.invoke(it) }
                    pendingDelete = null
                },
            )
        }
        item {
            Pill(
                text = stringResource(R.string.packs_cancel),
                background = MaterialTheme.colorScheme.surfaceContainer,
                ink = MaterialTheme.colorScheme.onSurfaceVariant,
                margin = 8.dp,
                onClick = { pendingDelete = null },
            )
        }
    }

}

/**
 * Una fila de palabra con un boton de quitar opcional a la derecha (D-155).
 *
 * Mismo reparto que la fila de un diccionario en `PacksScreen`: la palabra ocupa el ancho y el
 * boton es un blanco de 48 dp aparte, para que no haya forma de borrar queriendo abrir.
 */
@Composable
private fun WordRowWithDelete(
    headword: String,
    detail: String?,
    onDelete: (() -> Unit)?,
    onOpen: () -> Unit,
) {
    if (onDelete == null) {
        ListRow(headword = headword, detail = detail, onClick = onOpen)
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(modifier = Modifier.weight(1f)) {
            ListRow(headword = headword, detail = detail, onClick = onOpen)
        }
        Box(
            modifier = Modifier
                .clip(PILL_SHAPE)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable(onClick = onDelete)
                .heightIn(min = TOUCH_TARGET)
                .width(TOUCH_TARGET),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = stringResource(R.string.saved_remove_named, headword),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
