package cl.fadiaz.dictionary.presentation

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import cl.fadiaz.dictionary.R
import cl.fadiaz.dictionary.core.PackSource
import cl.fadiaz.dictionary.data.PackHandle

/**
 * The attribution, and it is **not optional** (D-031).
 *
 * The content is CC BY-SA: showing where it comes from and under which license is the condition
 * for using the data, not a courtesy. The text is not written here: it comes from `meta.license`
 * and `meta.attribution` of the open pack, so a pack from another source brings its own.
 *
 * ⚠️ **A rejected pack does NOT appear here, and it used to.** This screen credits the sources of
 * the content the app is using; a pack that does not load contributes no content, so naming it
 * here was crediting something nobody is reading. It is now shown on the dictionaries screen,
 * which is where something can be done about it. And no filtering is needed: a
 * `PackHandle.Incompatible` **has no `metadata`**, so this `filterIsInstance` does not see it.
 */
@Composable
fun AttributionScreen(packs: List<PackHandle>) {
    val listState = rememberTransformingLazyColumnState()
    val focusRequester = remember { FocusRequester() }
    val spec = rememberTransformationSpec()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            contentPadding = withScreenMargins(contentPadding),
            state = listState,
        ) {
            item {
                ListHeader(
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                ) { Text(stringResource(R.string.attribution_title)) }
            }
            // Each pack brings ITS own license: with two sources, showing only one would breach
            // the other one's terms of use.
            packs.filterIsInstance<PackHandle.Open>().forEach { handle ->
                val meta = handle.metadata
                item {
                    Text(
                        // The separator comes from the resource, as in every other row: this was
                        // the last place it was written by hand, and with two copies the one
                        // nobody touches is the one that ends up different.
                        text = meta.name + stringResource(R.string.entry_list_separator) +
                            packTypeLabel(meta.kind),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                // **Here** go the details taken out of the name (D-125): this is the screen you
                // open in order to read, not a list row you have to scroll. Null on a pack older
                // than D-125, and then nothing is drawn.
                meta.description?.let { description ->
                    item {
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        )
                    }
                }
                // ⚠️ **One line per declared source, each with ITS licence** (D-138). A pack
                // can mix content under different terms --the Spanish one has definitions under
                // CC BY-SA 4.0 and corpus sentences under CC BY 2.0 FR-- and showing a single
                // name breaches the other. The text is the pack's, never this app's.
                meta.sources.forEach { fuente ->
                    item {
                        Text(
                            text = stringResource(
                                R.string.attribution_source,
                                stringResource(roleLabelRes(fuente.role)),
                                fuente.name,
                                fuente.license,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        )
                    }
                }
                // The one-paragraph credit, which is all a pack older than D-138 has. It is not
                // dropped when `sources` exists either: it is the prose the source itself
                // wrote, and the itemised list above is a summary of it.
                item {
                    Text(
                        text = meta.attribution,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    )
                }
                // The collection's governing licence. Shown only when the pack did NOT itemise:
                // with the list above, repeating one name for the whole pack is the very
                // over-claim D-138 removed.
                if (meta.sources.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.attribution_license, meta.license),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The label for a source's role. A `when` and not a map so a new [PackSource.Role] does not
 * compile until it is given a name, the same rule `packTypeLabelRes` follows (D-125).
 */
@StringRes
internal fun roleLabelRes(role: PackSource.Role): Int = when (role) {
    PackSource.Role.DEFINITIONS -> R.string.attribution_role_definitions
    PackSource.Role.EXAMPLES -> R.string.attribution_role_examples
    PackSource.Role.SENTENCES -> R.string.attribution_role_sentences
    PackSource.Role.RELATIONS -> R.string.attribution_role_relations
    PackSource.Role.TRANSLATIONS -> R.string.attribution_role_translations
    PackSource.Role.OTHER -> R.string.attribution_role_other
}
