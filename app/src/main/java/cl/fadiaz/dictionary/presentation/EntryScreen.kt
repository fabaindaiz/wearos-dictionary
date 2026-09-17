package cl.fadiaz.dictionary.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import cl.fadiaz.dictionary.core.Entry
import cl.fadiaz.dictionary.core.Sense

/**
 * El cuerpo de una entrada. Es la unica pantalla que descomprime un payload: la lista se sirve
 * entera desde el covering index, sin tocar la tabla (D-012).
 *
 * POR QUE SE CORTA EN TRES ACEPCIONES
 *
 * Medido sobre las 3.000 entradas de mejor rank, que son las que mas se van a abrir: la mediana
 * es **3 acepciones**, el p90 es 7 y el maximo es **47**. Cortar en tres deja la mitad de las
 * entradas intactas --sin boton ni gesto de mas-- y evita que "justicia", con sus diez, se
 * convierta en un rollo donde la acepcion util queda debajo de nueve que nadie buscaba.
 */
private const val ACEPCIONES_VISIBLES = 3

@Composable
fun EntryScreen(entryId: Long, cargar: suspend (Long) -> Entry?) {
    var entry by remember(entryId) { mutableStateOf<Entry?>(null) }
    var fallo by remember(entryId) { mutableStateOf(false) }
    var desplegada by remember(entryId) { mutableStateOf(false) }

    LaunchedEffect(entryId) {
        entry = cargar(entryId)
        fallo = entry == null
    }

    val listState = rememberTransformingLazyColumnState()
    val focusRequester = remember { FocusRequester() }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            contentPadding = contentPadding,
            state = listState,
            // La corona es el scroll principal de un reloj: el dedo tapa justamente lo que se
            // esta leyendo. No viene cableada por defecto.
            modifier = Modifier.rotaryScrollable(
                RotaryScrollableDefaults.behavior(listState),
                focusRequester,
            ).focusRequester(focusRequester).requestFocusOnHierarchyActive(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val actual = entry
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    Text(
                        // El lema NO se trunca aca, al reves que en la lista: esta pantalla
                        // existe justamente para leer la palabra entera, refranes incluidos.
                        text = actual?.headword ?: "…",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    actual?.partOfSpeech?.let { pos ->
                        Text(
                            text = posEnEspanol(pos),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        )
                    }
                }
            }

            if (fallo) {
                item {
                    Text(
                        text = "Esa entrada ya no está en el diccionario.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                }
            }

            val todas = actual?.senses.orEmpty()
            val visibles = if (desplegada) todas else todas.take(ACEPCIONES_VISIBLES)
            val ocultas = todas.size - visibles.size

            items(count = visibles.size) { indice ->
                Acepcion(numero = indice + 1, sense = visibles[indice])
            }

            if (ocultas > 0) {
                item {
                    Text(
                        text = "Ver más ($ocultas)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .clickable { desplegada = true }
                            .heightIn(min = 48.dp)
                            .padding(vertical = 14.dp),
                    )
                }
            }
        }
    }
}

/**
 * Una acepcion: el numero manda el orden, la glosa es el contenido y el ejemplo acompaña.
 *
 * El ejemplo va en secundario y mas chico por una razon medida: la mediana es de 64 caracteres
 * pero el maximo son **917** --cronicas del siglo XVI que el Wikcionario cita como uso-- y a
 * igual peso visual que la glosa, uno solo entierra la acepcion siguiente.
 */
@Composable
private fun Acepcion(numero: Int, sense: Sense) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            text = "$numero. ${sense.gloss}",
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
    }
}
