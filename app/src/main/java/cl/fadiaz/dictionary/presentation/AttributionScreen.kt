package cl.fadiaz.dictionary.presentation

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
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

/**
 * La atribucion, y **no es opcional** (D-031).
 *
 * El contenido es CC BY-SA: mostrar de donde sale y bajo que licencia es la condicion de uso de
 * los datos, no una cortesia. El texto no se escribe aca: sale de `meta.license` y
 * `meta.attribution` del pack abierto, para que un pack de otra fuente traiga la suya.
 */
@Composable
fun AttributionScreen(packName: String, attribution: String, license: String) {
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(contentPadding = contentPadding, state = listState) {
            item {
                ListHeader(
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                ) { Text("Sobre estos datos") }
            }
            item {
                Text(
                    text = packName,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
            }
            item {
                Text(
                    text = attribution,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            item {
                Text(
                    text = "Licencia: $license",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
            }
        }
    }
}
