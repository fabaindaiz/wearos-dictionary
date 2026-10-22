package cl.fadiaz.dictionary.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
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
import cl.fadiaz.dictionary.core.DiscoverPos
import cl.fadiaz.dictionary.core.DiscoverTier
import cl.fadiaz.dictionary.core.EntrySummary

/**
 * *See more words* (i-a2f271-ec624b): two filter rows, then one list per language, then a row
 * that draws other words.
 *
 * ⚠️ **Each filter is ONE row that steps to the next value, not a row of chips.** On a 234 dp
 * screen three chips for the rarity and four for the type would be ~55 dp each, at the edge of
 * the 48 dp touch target, and would cost two rows before the first word. Stepping costs a tap
 * per value and keeps both filters in two 48 dp rows. It is a first proposal, to be looked at on
 * the emulator: the UI here gets decided by seeing it on the watch's geometry.
 *
 * The rows are [ListRow] with the definition's start on a second line, the same row the results
 * use (d-a2f271-0e93af): a word somebody does not know is chosen by what it means.
 */
@Composable
fun DiscoverScreen(
    state: DiscoverState,
    onSelect: (DiscoverTier, DiscoverPos?) -> Unit,
    onReload: () -> Unit,
    /** The pack the word lives in, and the word: each language's list is its own pack's. */
    onOpen: (String, EntrySummary) -> Unit,
) {
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
            item(key = "cabecera") { ListHeader { Text(stringResource(R.string.discover_open)) } }
            item(key = "rareza") {
                ListRow(
                    headword = stringResource(tierLabel(state.tier)),
                    detail = stringResource(R.string.discover_rarity),
                    isLabel = true,
                ) { onSelect(nextTier(state.tier), state.pos) }
            }
            item(key = "tipo") {
                ListRow(
                    headword = stringResource(filterPosLabel(state.pos)),
                    detail = stringResource(R.string.discover_type),
                    isLabel = true,
                ) { onSelect(state.tier, nextPos(state.pos)) }
            }

            for (section in state.sections) {
                item(key = "idioma:${section.lang}") {
                    ListHeader {
                        Text(languageName(section.lang) ?: resultTag(section.lang).orEmpty())
                    }
                }
                if (section.words.isEmpty()) {
                    item(key = "vacio:${section.lang}") {
                        Text(
                            text = stringResource(R.string.discover_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        )
                    }
                }
                items(
                    count = section.words.size,
                    key = { index -> "w:${section.packId}:${section.words[index].entryId}" },
                ) { index ->
                    val word = section.words[index]
                    ListRow(
                        headword = word.headword,
                        detail = wordDetail(word.partOfSpeech?.let { posLabel(it) }, null),
                        lang = section.lang,
                        preview = previewText(section.previews[word.entryId]),
                    ) { onOpen(section.packId, word) }
                }
            }

            if (state.sections.isNotEmpty()) {
                item(key = "otras") {
                    ListRow(
                        headword = stringResource(R.string.discover_reload),
                        detail = null,
                        isLabel = true,
                        onClick = onReload,
                    )
                }
            }
        }
    }
}

/** Common, rare, curious, and back. */
internal fun nextTier(tier: DiscoverTier): DiscoverTier =
    DiscoverTier.entries[(tier.ordinal + 1) % DiscoverTier.entries.size]

/** Every part of speech, then each one, and back to every one. */
internal fun nextPos(pos: DiscoverPos?): DiscoverPos? = when (pos) {
    null -> DiscoverPos.entries.first()
    DiscoverPos.entries.last() -> null
    else -> DiscoverPos.entries[pos.ordinal + 1]
}

private fun tierLabel(tier: DiscoverTier): Int = when (tier) {
    DiscoverTier.COMMON -> R.string.discover_tier_common
    DiscoverTier.RARE -> R.string.discover_tier_rare
    DiscoverTier.CURIOUS -> R.string.discover_tier_curious
}

private fun filterPosLabel(pos: DiscoverPos?): Int = when (pos) {
    null -> R.string.discover_pos_all
    DiscoverPos.NOUN -> R.string.discover_pos_noun
    DiscoverPos.VERB -> R.string.discover_pos_verb
    DiscoverPos.ADJECTIVE -> R.string.discover_pos_adjective
}
