package cl.fadiaz.dictionary.presentation

import cl.fadiaz.dictionary.R
import cl.fadiaz.dictionary.core.PackKind
import cl.fadiaz.dictionary.data.PackHandle

/**
 * ⚠️ **`Ver traducción` se quitó, y las dos razones que dio el usuario eran ciertas.**
 *
 * *«No hace nada»*: resolvía el lema en el OTRO pack y navegaba sólo si existía ahí. `casa` no es
 * un lema del diccionario inglés, así que el destino era nulo y el botón no hacía nada — sin
 * error, sin mensaje, en silencio. Ofrecer una acción que a veces no ocurre es peor que no
 * ofrecerla (misma familia que D-084: no pintar lo que no navega).
 *
 * *«Es redundante»*: desde D-179 las traducciones se dibujan **dentro** de la ficha, y desde
 * D-196 la palabra del otro idioma es una entrada de este mismo pack a la que se llega tocándola.
 * La acción mandaba a otra pantalla por lo que ya estaba a la vista.
 *
 * Se borra en vez de arreglarse porque el caso que quedaba --ir al mismo lema en otro
 * diccionario-- ya lo cubre buscarlo con el otro idioma activo, que es un toque en el chip.
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
