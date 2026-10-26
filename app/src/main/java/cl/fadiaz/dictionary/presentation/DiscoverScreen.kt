package cl.fadiaz.dictionary.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
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
import kotlinx.coroutines.launch

/**
 * *See more words* (i-a2f271-ec624b): three filter rows -- rarity, part of speech, language --
 * and the row that draws other words, then a divider, then the chosen language's words. The
 * reload sits with the filters and the bar separates them from the list: asked for on 2026-10-26
 * (d-a2f271-315c50).
 *
 * ⚠️ **Each filter is ONE row that steps to the next value, not a row of chips.** On a 234 dp
 * screen three chips for the rarity and four for the type would be ~55 dp each, at the edge of
 * the 48 dp touch target. Stepping costs a tap per value and keeps each filter in one 48 dp row.
 * Seen on the emulator on 2026-10-22 and kept; the owner then asked for the language to be the
 * third filter instead of one list per language.
 *
 * The rows are [ListRow] with the definition's start on a second line, the same row the results
 * use (d-a2f271-0e93af): a word somebody does not know is chosen by what it means.
 */
@Composable
fun DiscoverScreen(
    state: DiscoverState,
    onSelect: (DiscoverTier, DiscoverPos?) -> Unit,
    onSelectLanguage: (String) -> Unit,
    onReload: () -> Unit,
    /** The pack the word lives in, and the word: each language's list is its own pack's. */
    onOpen: (String, EntrySummary) -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    // The heading, the rarity and type rows, the language row when there is one, the reload and
    // the divider come before the first word.
    val firstWordItem = if (state.lang != null) 6 else 5

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

            val lang = state.lang
            if (lang != null) {
                item(key = "idioma") {
                    ListRow(
                        headword = languageName(lang) ?: resultTag(lang).orEmpty(),
                        detail = stringResource(R.string.discover_language),
                        isLabel = true,
                    ) { onSelectLanguage(nextLanguage(state.languages, lang)) }
                }
            }

            val section = state.section
            if (section != null) {
                item(key = "otras") {
                    ListRow(
                        headword = stringResource(R.string.discover_reload),
                        detail = null,
                        isLabel = true,
                    ) {
                        // ⚠️ **To the first word, or the new words may land off screen.** Seen on
                        // the emulator when the row was last: the view stayed on it and all twenty
                        // were above it. From up here the list can be scrolled down just the same.
                        scope.launch { listState.scrollToItem(firstWordItem) }
                        onReload()
                    }
                }
                item(key = "divisor") {
                    Box(
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)
                            .height(1.dp).background(MaterialTheme.colorScheme.outlineVariant)
                            .testTag(DISCOVER_DIVIDER_TAG),
                    )
                }
            }

            if (section != null && section.words.isEmpty()) {
                item(key = "vacio") {
                    Text(
                        text = stringResource(R.string.discover_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                }
            }
            if (section != null) {
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

        }
    }
}

/** The tag of the bar between the filters and the words, for the tests. */
internal const val DISCOVER_DIVIDER_TAG = "discover-divider"

/** The next language that can answer, and back to the first. One language steps onto itself. */
internal fun nextLanguage(languages: List<String>, current: String): String {
    if (languages.isEmpty()) return current
    return languages[(languages.indexOf(current) + 1) % languages.size]
}

/** Common, rare, curious, colloquial, vulgar, and back. */
internal fun nextTier(tier: DiscoverTier): DiscoverTier =
    DiscoverTier.entries[(tier.ordinal + 1) % DiscoverTier.entries.size]

/** Every part of speech, then each one, and back to every one. */
internal fun nextPos(pos: DiscoverPos?): DiscoverPos? = when (pos) {
    null -> DiscoverPos.entries.first()
    DiscoverPos.entries.last() -> null
    else -> DiscoverPos.entries[pos.ordinal + 1]
}

/** A tier's name. Settings' word-of-the-day row reads it too, so both say the same word. */
internal fun tierLabel(tier: DiscoverTier): Int = when (tier) {
    DiscoverTier.COMMON -> R.string.discover_tier_common
    DiscoverTier.RARE -> R.string.discover_tier_rare
    DiscoverTier.CURIOUS -> R.string.discover_tier_curious
    DiscoverTier.COLLOQUIAL -> R.string.discover_tier_colloquial
    DiscoverTier.VULGAR -> R.string.discover_tier_vulgar
}

private fun filterPosLabel(pos: DiscoverPos?): Int = when (pos) {
    null -> R.string.discover_pos_all
    DiscoverPos.NOUN -> R.string.discover_pos_noun
    DiscoverPos.VERB -> R.string.discover_pos_verb
    DiscoverPos.ADJECTIVE -> R.string.discover_pos_adjective
}
