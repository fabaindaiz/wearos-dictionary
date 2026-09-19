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
 * Settings.
 *
 * It is the app's **second level** and the only one there is: the Wear OS guidance asks for
 * hierarchies of at most two levels and for the primary action --searching-- to sit at the very
 * top, which is why the home stayed the search and this hangs off it.
 *
 * No preferences component: `androidx.preference` does not exist for Wear. It is the usual list
 * --`ScreenScaffold` + `TransformingLazyColumn`-- with `RadioButton` for what is a choice among
 * a few options.
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
                // This is no longer the selector: picking a language happens on the home, which
                // is where it is needed quickly. You come here to see how much they take and to
                // get rid of the ones you do not need.
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
                    // WO-V1: the app has to respect the system size. This multiplies on top of
                    // that scale, it does not replace it, and saying so keeps somebody from
                    // reading it as "the app ignores what I set on the watch".
                    text = "Se suma al tamaño que tengas puesto en el reloj.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }

            item(key = "cabecera-historial") { ListHeader { Text("Historial") } }
            item(key = "limpiar-historial") {
                // Two taps on the SAME button, no dialog: the history rebuilds itself just by
                // using the app, so it does not justify a screen on top. What is needed is that
                // a stray tap --and on a wrist there are some-- does not wipe it.
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
                    // No onClick when there is nothing to clear or it was already cleared: an
                    // action that does nothing and does not say so teaches you to distrust every
                    // other button.
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
