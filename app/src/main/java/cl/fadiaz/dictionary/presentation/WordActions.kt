package cl.fadiaz.dictionary.presentation

import cl.fadiaz.dictionary.R
import cl.fadiaz.dictionary.core.PackKind
import cl.fadiaz.dictionary.data.PackHandle

/**
 * ⚠️ **`View translation` was removed, and both reasons the user gave were true.**
 *
 * *"It does nothing"*: it resolved the lemma in the OTHER pack and navigated only if it existed
 * there. `casa` is not a lemma of the English dictionary, so the destination was null and the
 * button did nothing -- no error, no message, in silence. Offering an action that sometimes does
 * not happen is worse than not offering it (the same family as D-084: do not paint what does not
 * navigate).
 *
 * *"It is redundant"*: since D-179 translations are drawn **inside** the card, and since D-196 the
 * other language's word is an entry of this same pack, reached by tapping it. The action sent you
 * to another screen for what was already in plain sight.
 *
 * It is deleted rather than fixed because the case that was left --going to the same lemma in
 * another dictionary-- is already covered by searching for it with the other language active,
 * which is one tap on the chip.
 */

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
 * Translating for real needs the pack to **carry** the translations. The action is offered only
 * if something open declares `translationsTo`, which since D-183 is a **capability** and not a
 * direction: the Spanish monolingual pack declares it too, because it carries per-sense
 * translations even though its headwords are Spanish.
 *
 * ⚠️ **And since D-196 the bilingual pack rarely needs this action at all**: the other language's
 * words are entries in that same file, so tapping a translation opens it there. What is left for
 * this action is the case it was written for — the same word in ANOTHER dictionary.
 */
internal fun wordActions(
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onCopy: () -> Unit,
): List<EntryAction> = buildList {
    add(
        EntryAction(
            label = if (isFavorite) R.string.action_unsave else R.string.action_save,
            onClick = onToggleFavorite,
        ),
    )
    add(EntryAction(label = R.string.action_copy, onClick = onCopy))
}
