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
import androidx.wear.compose.material3.SwitchButton
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
    /** The commit this APK came out of, with `+dirty` if the tree was not clean. */
    buildCommit: String,
    /** When it was built, to the minute and in UTC. */
    buildTime: String,
    /**
     * Whether the other languages answer when the active one found nothing close (D-266).
     *
     * Default `false` so a test screen that does not wire it shows today's behaviour, which is
     * also the shipped default.
     */
    crossLanguageFallback: Boolean = false,
    onManagePacks: () -> Unit,
    onScaleChange: (TextScale) -> Unit,
    /** Deliberately no default: a switch that moves and changes nothing is worse than no switch. */
    onCrossLanguageFallbackChange: (Boolean) -> Unit,
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

            // ⚠️ **Above the text size and below the dictionaries, and the order is the point.**
            // This is the only row here that changes what a SEARCH returns; everything below it
            // changes how the app looks. Somebody arriving because "it does not find my English
            // words" has to meet it before three rows about type.
            item(key = "cabecera-busqueda") { ListHeader { Text(stringResource(R.string.settings_search)) } }
            item(key = "respaldo-idioma") {
                SwitchButton(
                    checked = crossLanguageFallback,
                    onCheckedChange = onCrossLanguageFallbackChange,
                    label = { Text(stringResource(R.string.settings_cross_language)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item(key = "nota-respaldo") {
                Text(
                    // It says WHEN it acts, not just what it does. Read as "search both languages
                    // always" it would look like a way to make every list twice as long, which is
                    // the opposite of what D-189 measured and of what this does.
                    text = stringResource(R.string.settings_cross_language_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
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
            // ⚠️ **The build's identity, and it is the row that decides whether the rest is worth
            // reading.** `versionName` is the same across ten builds on the same day; this says
            // which one it is. Without it, *"the fix did not work"* and *"the watch is running
            // yesterday's APK"* are the same report. The `+dirty` is the honest half: almost
            // everything installed on the watch comes out of an uncommitted tree, and there the
            // hash does NOT identify what is running.
            item(key = "build") {
                Diagnostic(stringResource(R.string.settings_build, buildCommit, buildTime))
            }
            // ⚠️ **The per-dictionary entry count was removed on request.** It was a diagnostic
            // figure nobody uses to decide anything: how many lemmas a pack carries does not say
            // whether it works, and it took one row per dictionary on the longest screen in the
            // app. What IS useful for deciding --the size on disk-- lives in dictionary
            // management, which is where you delete them.
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
 * The name of each [TextScale] step.
 *
 * ⚠️ **An exhaustive `when` and not an `if/else`**, which is what was there: with two values the
 * `else` worked, and adding the third would have labelled `SMALL` as "Large" **without a single
 * compilation error**. This way, adding a step does not compile until it is named.
 */
@StringRes
private fun scaleLabel(scale: TextScale): Int = when (scale) {
    TextScale.SMALL -> R.string.settings_scale_small
    TextScale.NORMAL -> R.string.settings_scale_normal
    TextScale.LARGE -> R.string.settings_scale_large
}
