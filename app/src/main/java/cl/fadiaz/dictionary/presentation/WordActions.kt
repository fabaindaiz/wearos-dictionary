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
 * ⚠️ **Se pregunta por la CAPACIDAD y no por `kind`, y el cambio no es cosmético.** Antes el
 * filtro era `kind == PackKind.BILINGUAL`, y desde que el pack español lee la tabla de
 * traducciones del Wikcionario eso dejó de ser cierto: es `MONOLINGUAL` --sus definiciones son en
 * español-- y **traduce al inglés**. Con el filtro viejo la acción **no aparecía nunca** sobre un
 * pack que sí traduce. `kind` contesta en qué idioma están las definiciones; `translationsTo`
 * contesta si traduce.
 *
 * ⚠️ **Y no se ofrece si la entrada ya muestra las suyas.** La acción existía para ir a buscar la
 * palabra a OTRO diccionario; ahora las traducciones se dibujan dentro de la ficha, así que
 * ofrecerla además mandaría al lector a otra pantalla por lo que ya está viendo.
 */
internal fun translationPack(
    opened: List<PackHandle>,
    packOfTheEntry: String,
    entryHasTranslations: Boolean,
): PackHandle.Open? {
    if (entryHasTranslations) return null
    return opened.filterIsInstance<PackHandle.Open>()
        .firstOrNull { it.packId != packOfTheEntry && it.metadata.translationsTo != null }
}
