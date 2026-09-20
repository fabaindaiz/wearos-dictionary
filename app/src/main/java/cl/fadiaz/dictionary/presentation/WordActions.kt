package cl.fadiaz.dictionary.presentation

import cl.fadiaz.dictionary.R
import cl.fadiaz.dictionary.core.PackKind
import cl.fadiaz.dictionary.data.PackHandle

/**
 * What a word's menu offers.
 *
 * It lives apart from `MainActivity` and without a single reference to Android so it can be
 * tested on the JVM, inside the gate: **which action is offered** is a decision, and the decision
 * is what went wrong (D-072).
 *
 * WHY "VIEW TRANSLATION" ALMOST NEVER SHOWS UP
 *
 * The earlier version offered it whenever another pack was open, and what it did was look up
 * **the same headword** in the other dictionary. With two monolingual packs that almost never
 * finds anything: "house" is not a Spanish word. It found loanwords and proper nouns
 * --"chocolate", "Madrid"-- and in everything else it was a button that did nothing, which is
 * worse than not having it.
 *
 * Translating for real needs the pack to **carry** the translations, and today both real ones are
 * monolingual (D-034): `trans` is empty and `MatchKind.TRANSLATION` returns not a single row. So
 * the action is offered **only if a bilingual pack is open**, which is the only thing that
 * declares `lang_dst` and therefore the only thing that can translate. Today: never.
 */
internal fun wordActions(
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    translationPack: PackHandle.Open?,
    onViewTranslation: (PackHandle.Open) -> Unit,
    onCopy: () -> Unit,
): List<EntryAction> = buildList {
    add(
        EntryAction(
            label = if (isFavorite) R.string.action_unsave else R.string.action_save,
            onClick = onToggleFavorite,
        ),
    )
    if (translationPack != null) {
        add(
            EntryAction(
                label = R.string.action_translate,
                onClick = { onViewTranslation(translationPack) },
            ),
        )
    }
    add(EntryAction(label = R.string.action_copy, onClick = onCopy))
}

/**
 * The pack that can translate this entry, or null.
 *
 * Bilingual and different from the one being read. `PackKind.BILINGUAL` is the right signal and
 * not the name or the language: it is the only thing that forces `lang_dst` to be declared (see
 * `PackMetadata.init`).
 */
internal fun translationPack(
    opened: List<PackHandle>,
    packOfTheEntry: String,
): PackHandle.Open? =
    opened.filterIsInstance<PackHandle.Open>()
        .firstOrNull { it.packId != packOfTheEntry && it.metadata.kind == PackKind.BILINGUAL }
