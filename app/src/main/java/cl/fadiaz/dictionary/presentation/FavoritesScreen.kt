package cl.fadiaz.dictionary.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
import cl.fadiaz.dictionary.data.Visit

/**
 * Las palabras guardadas.
 *
 * Es la misma lista que el historial y la de resultados --[Fila], 48 dp, una linea con elipsis--
 * porque son la misma cosa: un lema que se toca para abrirlo. Lo unico distinto es de donde sale.
 *
 * A diferencia del historial no tiene tope de tres: el historial son tres porque compite por la
 * pantalla con los resultados, y esto no compite con nada.
 */
@Composable
fun FavoritesScreen(favorites: List<Visit>, onOpen: (Visit) -> Unit) {
    val listState = rememberTransformingLazyColumnState()
    val focusRequester = remember { FocusRequester() }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            contentPadding = withBottomMargin(contentPadding),
            state = listState,
            modifier = Modifier.rotaryScrollable(
                RotaryScrollableDefaults.behavior(listState),
                focusRequester,
            ).focusRequester(focusRequester).requestFocusOnHierarchyActive(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "cabecera") { ListHeader { Text("Guardadas") } }

            if (favorites.isEmpty()) {
                item(key = "vacio") {
                    Text(
                        // Dice COMO se guarda, no solo que no hay: un estado vacio que no explica
                        // como salir de el es un callejon.
                        text = "Abrí una palabra y usá Opciones para guardarla.",
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
                    lema = visit.headword,
                    detail = visit.partOfSpeech?.let(::posInSpanish),
                ) { onOpen(visit) }
            }
        }
    }
}
