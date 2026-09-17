package cl.fadiaz.dictionary.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import cl.fadiaz.dictionary.core.Entry

/**
 * El cuerpo de una entrada. Es la unica pantalla que descomprime un payload: la lista se sirve
 * entera desde el covering index, sin tocar la tabla (D-012).
 */
@Composable
fun EntryScreen(entryId: Long, cargar: suspend (Long) -> Entry?) {
    var entry by remember(entryId) { mutableStateOf<Entry?>(null) }
    var fallo by remember(entryId) { mutableStateOf(false) }

    LaunchedEffect(entryId) {
        entry = cargar(entryId)
        fallo = entry == null
    }

    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(contentPadding = contentPadding, state = listState) {
            val actual = entry
            item {
                ListHeader(
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                ) { Text(actual?.headword ?: "…") }
            }

            if (fallo) {
                item {
                    Text(
                        text = "Esa entrada ya no está en el diccionario.",
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    )
                }
            }

            actual?.partOfSpeech?.let { pos ->
                item {
                    Text(
                        text = posEnEspanol(pos),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    )
                }
            }

            actual?.senses?.forEachIndexed { indice, sense ->
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text("${indice + 1}. ${sense.gloss}")
                        sense.examples.forEach { ejemplo ->
                            Text(
                                text = ejemplo,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
