package cl.fadiaz.dictionary.presentation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import cl.fadiaz.dictionary.R
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import cl.fadiaz.dictionary.data.TextScale
import cl.fadiaz.dictionary.data.PackHandle
import cl.fadiaz.dictionary.data.UiLanguage

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
    /**
     * The tag the app is pinned to, or null for automatic.
     *
     * It arrives as a parameter and not from `LocaleManager` because the screen is a function of
     * its state: that is what lets these four tests run in the gate instead of on a device. The
     * system service is read and written in `MainActivity`, which is where Android already lives.
     */
    uiLanguage: String?,
    appVersion: String,
    /** El commit del que salió este APK, con `+dirty` si el árbol no estaba limpio. */
    buildCommit: String,
    /** Cuándo se armó, al minuto y en UTC. */
    buildTime: String,
    onManagePacks: () -> Unit,
    onScaleChange: (TextScale) -> Unit,
    onUiLanguageChange: (String?) -> Unit,
    onClearHistory: () -> Unit,
    hasHistory: Boolean,
) {
    val listState = rememberTransformingLazyColumnState()
    val focusRequester = remember { FocusRequester() }
    var emptyHistory by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }

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
            val opened = packs.filterIsInstance<PackHandle.Open>()
            item(key = "cabecera-idioma") { ListHeader { Text(stringResource(R.string.settings_dictionaries)) } }
            item(key = "gestionar-packs") {
                // This is no longer the selector: picking a language happens on the home, which
                // is where it is needed quickly. You come here to see how much they take and to
                // get rid of the ones you do not need.
                ListRow(
                    headword = stringResource(R.string.settings_manage),
                    detail = opened.size.toString(),
                    onClick = onManagePacks,
                )
            }

            item(key = "cabecera-idioma-app") {
                ListHeader { Text(stringResource(R.string.settings_ui_language)) }
            }
            item(key = "idioma-auto") {
                RadioButton(
                    // Automatic is the ABSENCE of a choice, not a third language: an empty
                    // LocaleList is what the platform reads as "follow the watch".
                    selected = UiLanguage.of(uiLanguage) == null,
                    onSelect = { onUiLanguageChange(null) },
                    label = { Text(stringResource(R.string.settings_ui_language_auto)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            items(count = UiLanguage.entries.size, key = { "idioma-app:$it" }) { index ->
                val option = UiLanguage.entries[index]
                RadioButton(
                    selected = UiLanguage.of(uiLanguage) == option,
                    onSelect = { onUiLanguageChange(option.tag) },
                    // The endonym, and it is not a resource: a language picker that translates
                    // itself fails exactly the person it is for --whoever got a watch in a
                    // language they cannot read and is looking for their own line.
                    label = { Text(option.endonym) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item(key = "nota-idioma-app") {
                Text(
                    text = stringResource(R.string.settings_ui_language_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }

            item(key = "cabecera-texto") { ListHeader { Text(stringResource(R.string.settings_text_size)) } }
            items(count = TextScale.entries.size, key = { "escala:$it" }) { index ->
                val option = TextScale.entries[index]
                RadioButton(
                    selected = option == scale,
                    onSelect = { onScaleChange(option) },
                    label = { Text(stringResource(scaleLabel(option))) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item(key = "nota-texto") {
                Text(
                    // WO-V1: the app has to respect the system size. This multiplies on top of
                    // that scale, it does not replace it, and saying so keeps somebody from
                    // reading it as "the app ignores what I set on the watch".
                    text = stringResource(R.string.settings_text_size_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }

            item(key = "cabecera-historial") { ListHeader { Text(stringResource(R.string.settings_history)) } }
            item(key = "limpiar-historial") {
                // Two taps on the SAME button, no dialog: the history rebuilds itself just by
                // using the app, so it does not justify a screen on top. What is needed is that
                // a stray tap --and on a wrist there are some-- does not wipe it.
                Pill(
                    text = when {
                        emptyHistory -> stringResource(R.string.settings_history_cleared)
                        !hasHistory -> stringResource(R.string.settings_history_empty)
                        confirming -> stringResource(R.string.settings_confirm)
                        else -> stringResource(R.string.settings_history_clear)
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

            // Last on purpose: this is looked up once, when something is wrong, and it must not
            // push the settings somebody actually changes off the screen.
            item(key = "cabecera-acerca") { ListHeader { Text(stringResource(R.string.settings_about)) } }
            item(key = "version") { Diagnostic(stringResource(R.string.settings_version, appVersion)) }
            // ⚠️ **La identidad del build, y es la fila que decide si vale la pena leer el resto.**
            // `versionName` es el mismo en diez builds del mismo día; esto dice de cuál se trata.
            // Sin ella, *«el arreglo no funcionó»* y *«el reloj corre el APK de ayer»* son el
            // mismo reporte. El `+dirty` es la mitad honesta: casi todo lo que se instala en el
            // reloj sale de un árbol sin commitear, y ahí el hash NO identifica lo que corre.
            item(key = "build") {
                Diagnostic(stringResource(R.string.settings_build, buildCommit, buildTime))
            }
            // ⚠️ **La cuenta de entradas por diccionario se quitó a pedido.** Era un dato de
            // diagnóstico que nadie usa para decidir nada: cuántos lemas trae un pack no dice si
            // funciona, y ocupaba una fila por diccionario en la pantalla más larga de la app.
            // Lo que sí sirve para decidir --el tamaño en disco-- vive en gestión de
            // diccionarios, que es donde se borra.
        }
    }
}

/** One line of the diagnostics block: readable, not tappable, and not competing for attention. */
@Composable
private fun Diagnostic(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    )
}

/**
 * El nombre de cada paso de [TextScale].
 *
 * ⚠️ **Un `when` exhaustivo y no un `if/else`**, que es lo que habia: con dos valores el `else`
 * funcionaba, y al agregar el tercero habria etiquetado `SMALL` como "Grande" **sin un solo
 * error de compilacion**. Asi, agregar un paso no compila hasta nombrarlo.
 */
@StringRes
private fun scaleLabel(scale: TextScale): Int = when (scale) {
    TextScale.SMALL -> R.string.settings_scale_small
    TextScale.NORMAL -> R.string.settings_scale_normal
    TextScale.LARGE -> R.string.settings_scale_large
}
