package cl.fadiaz.dictionary.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import cl.fadiaz.dictionary.data.TextScale
import cl.fadiaz.dictionary.data.PackHandle

/**
 * Ajustes.
 *
 * Es el **segundo nivel** de la app y el unico que hay: la guia de Wear OS pide jerarquias de
 * como mucho dos niveles y que la accion primaria --buscar-- este arriba de todo, por eso el
 * inicio siguio siendo la busqueda y esto cuelga de ahi.
 *
 * Sin componente de preferencias: `androidx.preference` no existe para Wear. Es la lista de
 * siempre --`ScreenScaffold` + `TransformingLazyColumn`-- con `RadioButton` para lo que es una
 * eleccion entre pocas opciones.
 */
@Composable
fun SettingsScreen(
    packs: List<PackHandle>,
    scale: TextScale,
    onManagePacks: () -> Unit,
    onScaleChange: (TextScale) -> Unit,
    onClearHistory: () -> Unit,
    hasHistory: Boolean,
) {
    val listState = rememberTransformingLazyColumnState()
    val focusRequester = remember { FocusRequester() }
    var emptyHistory by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }

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
            val opened = packs.filterIsInstance<PackHandle.Open>()
            item(key = "cabecera-idioma") { ListHeader { Text("Diccionarios") } }
            item(key = "gestionar-packs") {
                // Ya no es el selector: elegir idioma se hace en el inicio, que es donde se
                // necesita rapido. Aca se entra a ver cuanto ocupan y a sacar los que sobran.
                ListRow(
                    headword = "Gestionar",
                    detail = opened.size.toString(),
                    onClick = onManagePacks,
                )
            }

            item(key = "cabecera-texto") { ListHeader { Text("Tamaño del texto") } }
            items(count = TextScale.entries.size, key = { "escala:$it" }) { index ->
                val option = TextScale.entries[index]
                RadioButton(
                    selected = option == scale,
                    onSelect = { onScaleChange(option) },
                    label = { Text(if (option == TextScale.NORMAL) "Normal" else "Grande") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item(key = "nota-texto") {
                Text(
                    // WO-V1: la app tiene que respetar el tamano del sistema. Esto multiplica
                    // sobre esa escala, no la reemplaza, y decirlo evita que alguien lo lea como
                    // "la app ignora lo que configure en el reloj".
                    text = "Se suma al tamaño que tengas puesto en el reloj.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }

            item(key = "cabecera-historial") { ListHeader { Text("Historial") } }
            item(key = "limpiar-historial") {
                // Dos toques en el MISMO boton, sin dialogo: el historial se rehace solo
                // usando la app, asi que no justifica una pantalla encima. Lo que si hace falta
                // es que un toque suelto --y en una muñeca los hay-- no lo borre.
                Pill(
                    text = when {
                        emptyHistory -> "Historial borrado"
                        !hasHistory -> "No hay historial"
                        confirming -> "Confirmar"
                        else -> "Borrar el historial"
                    },
                    background = if (confirming) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    },
                    ink = if (confirming) {
                        MaterialTheme.colorScheme.onError
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    // Sin onClick cuando no hay nada que borrar o ya se borro: una accion que no
                    // hace nada y no lo dice enseña a desconfiar del resto de los botones.
                    onClick = if (!hasHistory || emptyHistory) {
                        null
                    } else if (confirming) {
                        {
                            onClearHistory()
                            emptyHistory = true
                            confirming = false
                        }
                    } else {
                        { confirming = true }
                    },
                )
            }
        }
    }
}
