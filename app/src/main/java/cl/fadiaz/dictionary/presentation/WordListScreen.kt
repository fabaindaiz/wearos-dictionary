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
     * `packId` -> la etiqueta de idioma. La arma `historyTags`.
     *
     * Vacio por defecto para que una pantalla de test que no la cablea siga andando; un `packId`
     * que no este simplemente no recibe etiqueta, igual que en los resultados.
     */
    /**
     * `packId` -> etiqueta de idioma. **Un mapa y no una sola**: esta lista puede traer palabras
     * de un pack que ya no esta instalado, y heredarles el idioma activo afirmaria algo que
     * nadie comprobo. Ver `historyTags`.
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
    // Cuál fila está armada para borrar. Una sola a la vez: armar otra desarma la
    // anterior, que es lo que hace que el estado sea siempre evidente.
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
                        tags[visit.packId],
                    ),
                    armada = armada == visit,
                    onArm = onDelete?.let { { armada = visit } },
                    onConfirm = {
                        onDelete?.invoke(visit)
                        armada = null
                    },
                    // ⚠️ Con algo armado, un toque en OTRA fila desarma y no navega: si abriera
                    // la palabra, te irías de la pantalla con una fila roja esperándote al
                    // volver y sin forma evidente de cancelar.
                    onOpen = { if (armada != null) armada = null else onOpen(visit) },
                )
            }
        }
    }

}

/**
 * Una fila de palabra que se **arma** manteniéndola apretada, y se confirma con un toque (D-155).
 *
 * ⚠️ **Un gesto y no un botón, y en un reloj eso no es estética.** Un botón de 48 dp al lado de
 * cada fila le come el ancho al lema justo donde el lema es lo único que importa; y queda pegado
 * al blanco que abre la palabra, así que un toque impreciso borra lo que se quería leer. Mantener
 * apretado es el gesto que todo el sistema usa para revelar lo destructivo, y **no tiene forma de
 * dispararse por accidente**.
 *
 * Armada, la fila se pinta con el color de error y dice qué va a pasar. El segundo toque borra.
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
