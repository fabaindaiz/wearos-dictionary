package cl.fadiaz.dictionary.presentation

import androidx.annotation.StringRes
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import cl.fadiaz.dictionary.R
import cl.fadiaz.dictionary.core.MatchKind
import cl.fadiaz.dictionary.core.PackRejection

/**
 * How what the pack stores as a code is NAMED on screen.
 *
 * It used to live inside `SearchScreen.kt` and came out because **it is not a screen**: `posLabel`
 * is used by a tile too, and a Tile importing a symbol from a screen file is a dependency the
 * wrong way round. Here the import says what actually happens -- two different surfaces resolving
 * the same label.
 *
 * The translation lives in the UI and not in the pack on purpose: putting it in the `.db` would
 * tie it to an interface language and cost bytes per entry, with 152,281 of them.
 */
/**
 * The `pos` the pack stores is kaikki's code (`noun`, `verb`). Translating it is the UI's business:
 * putting it in the pack would tie it to an interface language and cost bytes per entry.
 *
 * It returns the **resource id** and not the text because two different surfaces use this: the
 * screens, which resolve with `stringResource`, and the tiles, which have a `Context` and resolve
 * with `getString`. A code we do not know returns null and is shown raw, which is better than
 * hiding it.
 */
@StringRes
internal fun posLabelRes(pos: String): Int? = when (pos) {
    "noun" -> R.string.pos_noun
    "verb" -> R.string.pos_verb
    "adj" -> R.string.pos_adj
    "adv" -> R.string.pos_adv
    "name" -> R.string.pos_name
    "phrase" -> R.string.pos_phrase
    "intj" -> R.string.pos_intj
    "pron" -> R.string.pos_pron
    "prep" -> R.string.pos_prep
    "conj" -> R.string.pos_conj
    "num" -> R.string.pos_num
    "suffix" -> R.string.pos_suffix
    "prefix" -> R.string.pos_prefix
    "proverb" -> R.string.pos_proverb
    "abbrev" -> R.string.pos_abbrev
    else -> null
}

/** [posLabelRes] resolved in the watch's language; the raw code if it is not known. */
@Composable
internal fun posLabel(pos: String): String = posLabelRes(pos)?.let { stringResource(it) } ?: pos

/**
 * The part of speech WRITTEN OUT IN FULL, for a word's card.
 *
 * It exists alongside [posLabel] and not instead of it: they are two places with different
 * budgets. In a 234 dp row the lemma is all that matters and "sustantivo" eats its width; on the
 * card it competes with nothing and "sust." is an abbreviation somebody has to decipher.
 *
 * It falls back to the abbreviation --and not to the raw code-- when the long key is missing: a
 * pack can carry a `pos` we do not know, and half a label beats `intj`.
 */
@Composable
internal fun posLabelFull(pos: String): String = when (pos) {
    "noun" -> stringResource(R.string.pos_full_noun)
    "verb" -> stringResource(R.string.pos_full_verb)
    "adj" -> stringResource(R.string.pos_full_adj)
    "adv" -> stringResource(R.string.pos_full_adv)
    "name" -> stringResource(R.string.pos_full_name)
    "phrase" -> stringResource(R.string.pos_full_phrase)
    "intj" -> stringResource(R.string.pos_full_intj)
    "pron" -> stringResource(R.string.pos_full_pron)
    "prep" -> stringResource(R.string.pos_full_prep)
    "conj" -> stringResource(R.string.pos_full_conj)
    "num" -> stringResource(R.string.pos_full_num)
    "suffix" -> stringResource(R.string.pos_full_suffix)
    "prefix" -> stringResource(R.string.pos_full_prefix)
    "proverb" -> stringResource(R.string.pos_full_proverb)
    "abbrev" -> stringResource(R.string.pos_full_abbrev)
    else -> posLabel(pos)
}

/** [posLabelRes] for whoever has a `Context` and no composition: the tiles. */
internal fun posLabel(context: Context, pos: String): String =
    posLabelRes(pos)?.let(context::getString) ?: pos

/**
 * Only the rungs that surprise get labelled.
 *
 * That a result came out by prefix is what is expected and does not deserve a word on a watch
 * screen. That it came out through an inflected form or a near match does: it explains why
 * something the user did not type is showing up.
 */
@Composable
internal fun matchLabel(kind: MatchKind): String? = when (kind) {
    MatchKind.PREFIX -> null
    MatchKind.INFLECTED_FORM -> stringResource(R.string.match_inflected)
    MatchKind.TRANSLATION -> stringResource(R.string.match_translation)
    MatchKind.FUZZY -> stringResource(R.string.match_fuzzy)
    MatchKind.DEFINITION -> stringResource(R.string.match_definition)
}

/**
 * Why a dictionary does not load, **in one line**.
 *
 * ⚠️ **An exhaustive `when` and not a map, and that is D-125's rule**: a new reason in
 * [PackRejection] **does not compile** until somebody writes it a text. The opposite --a map with
 * a `?: "unknown"`-- would let mute reasons through, and on screen those read as a dictionary that
 * disappeared with no explanation.
 *
 * ⚠️ **One line, and a short one, is the requirement and not a preference.** It is what the user
 * asked for and it is what fits in a watch row under the file name. What a reason needs in order
 * to be debugged --what the pack declared, what was expected-- goes to the log and not here: that
 * prose is for `logcat`, and on a wrist it only gets in the way.
 */
@StringRes
internal fun packRejectionLabelRes(rejection: PackRejection): Int = when (rejection) {
    PackRejection.METADATA -> R.string.pack_reason_metadata
    PackRejection.SCHEMA_VERSION -> R.string.pack_reason_schema
    PackRejection.NORM_VERSION -> R.string.pack_reason_norm
    PackRejection.PAYLOAD_CODEC -> R.string.pack_reason_codec
    PackRejection.LICENSE -> R.string.pack_reason_license
    PackRejection.MISSING_INDEX -> R.string.pack_reason_index
    PackRejection.HALF_BUILT -> R.string.pack_reason_half_built
    PackRejection.ENTRY_COUNT -> R.string.pack_reason_count
    PackRejection.FTS_MISALIGNED -> R.string.pack_reason_fts
    PackRejection.EMPTY_KEY -> R.string.pack_reason_empty_key
    PackRejection.ORPHAN_ROW -> R.string.pack_reason_orphan
    PackRejection.KEYS -> R.string.pack_reason_keys
    PackRejection.PAYLOAD_DICTIONARY -> R.string.pack_reason_dictionary
    PackRejection.DAMAGED -> R.string.pack_reason_damaged
}
