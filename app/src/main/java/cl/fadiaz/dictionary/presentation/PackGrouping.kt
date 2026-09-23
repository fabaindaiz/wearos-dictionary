package cl.fadiaz.dictionary.presentation

import cl.fadiaz.dictionary.data.PackHandle

/**
 * From packs to languages: the model that governs the whole coexistence of dictionaries.
 *
 * > A pack is not a dictionary the user chooses. It is a **source** for a language. What the user
 * > chooses is the language; all the packs of that language get queried (D-136).
 *
 * The three functions here are that sentence translated into the screen, and they sit outside
 * `SearchScreen.kt` because **they are decisions, not drawing**: the gate covers them on the JVM
 * with `LanguageChipsTest` instead of with Robolectric, which is slower and measures something
 * else.
 */
/**
 * A language offered by the selector, and the pack that represents it.
 *
 * `packId` is who gets activated on tapping it: it decides **the attribution shown, the tile's
 * word of the day and which pack goes first on a tie-break**, not which packs are searched --
 * since D-136 **all** of the active language's are searched.
 */
internal data class LanguageChip(
    val label: String,
    /**
     * The language, not the pack.
     *
     * ⚠️ **It used to carry a `packId` and with bidirectional packs that stopped being enough**: a
     * single one speaks two languages, so choosing it does not say which one is being searched. It
     * is what D-147 was already pushing --*"one chip per language, not per file"*-- taken all the
     * way.
     */
    val lang: String,
    val active: Boolean,
)

/**
 * Groups the open packs by language: **one chip per language, not per file** (D-147).
 *
 * ⚠️ **It closes an inconsistency D-136 introduced and did not finish.** That decision put in
 * writing that the selector moves to choosing a language because `SearchRepository` queries every
 * pack of the active language -- and the screen went on listing packs. With two Spanish
 * dictionaries the home showed **two "ES" chips**, both activable, and tapping them changed
 * nothing about what was searched.
 *
 * **A language's representative** is the active pack if it already is one --tapping another
 * language and coming back cannot change the dictionary you chose from under you-- and otherwise
 * **the one with the most entries**: it is the one that will have the word most often.
 *
 * ⚠️ **The order is by language code and not `available`'s**, which comes from listing a directory
 * and promises no order: if the chips followed it, they would move between launches, and a control
 * that moves on its own gets tapped by mistake. Same criterion as D-136's tie-break.
 */
internal fun languageChips(packs: List<PackHandle>, activoLang: String?): List<LanguageChip> =
    idiomasDisponibles(packs).map { lang ->
        LanguageChip(label = lang.uppercase(), lang = lang, active = lang == activoLang)
    }

/**
 * The languages that can be searched, ordered by code.
 *
 * ⚠️ **A pack contributes ONE PER LANGUAGE it declares**, and that is what makes a bilingual one
 * give two chips: it has entries in both --`casa` and `house` in the same file-- so choosing `EN`
 * filters to its English lemmas without opening any other pack. Asked for: *"that every task and
 * query can be done using only that pack"*.
 *
 * ⚠️ **The order is by code and not `packs`'**, which comes from listing a directory and promises
 * no order: if the chips followed it they would move between launches, and a control that moves on
 * its own gets tapped by mistake.
 */
internal fun idiomasDisponibles(packs: List<PackHandle>): List<String> =
    packs.filterIsInstance<PackHandle.Open>()
        .flatMap { it.metadata.langs }
        .distinct()
        .sorted()

/**
 * **One pack per language**: the one that represents it. Ordered by language code.
 *
 * Three screens need the same thing, which is why it lives on its own (D-151): the selector draws
 * one per language, the **word of the day** is computed one per language, and the tile caches the
 * active one's. The word of the day used to be computed **per pack**, so with two Spanish
 * dictionaries the home showed two words of the day in the same language -- the bug D-145 covered
 * by merging the packs instead of fixing it, and which comes back the moment somebody installs a
 * pack of their own.
 *
 * **The representative** is the active pack if it already is one --choosing another language and
 * coming back cannot change the dictionary from under you-- and otherwise **the one with the most
 * entries**: it is the one that will have the word most often.
 *
 * ⚠️ **The order is by language code and not `packs`'**, which comes from listing a directory and
 * promises no order. Same criterion as D-136's tie-break.
 */
internal fun representativePacks(packs: List<PackHandle>, activo: String?): List<PackHandle.Open> {
    val abiertos = packs.filterIsInstance<PackHandle.Open>()
    // ⚠️ **It groups by EVERY language the pack declares, not by one.** A bilingual one enters
    // both groups, so it can represent English even though it also speaks Spanish -- which is what
    // is needed when it is the only installed pack.
    return idiomasDisponibles(packs).mapNotNull { lang ->
        val delIdioma = abiertos.filter { lang in it.metadata.langs }
        delIdioma.firstOrNull { it.packId == activo }
            ?: delIdioma.maxByOrNull { it.metadata.entryCount }
            ?: delIdioma.firstOrNull()
    }.distinctBy { it.packId }
}

/**
 * Which tag each result carries: **the language, and nothing else**.
 *
 * ⚠️ **It used to show the SOURCE when there were two packs of the same language** --`WIKC`,
 * `ENWIKT`-- reasoning that `ES · ES` does not disambiguate. It was reverted on request, and the
 * reasoning that reverts it is better than the one that put it there: *"it should just be EN, ES.
 * I do not like having an ENWIK... because all I care about is knowing the language it comes
 * from"*.
 *
 * Disambiguating **two packs** is a catalog question and has its own screen --dictionary
 * management--; the results row answers something else, which is what language the word I am about
 * to open is in. The abbreviation is also unintelligible without knowing the `pack_id`, so it took
 * the same width to say less.
 *
 * Since the search became strict by language
 * ([LanguageScope][cl.fadiaz.dictionary.core.LanguageScope]), every row of a query shares a tag;
 * it is left as it is because the card shows it too, and because the future auto mode mixes
 * languages again without touching this.
 */
internal fun resultTag(lang: String?): String? = lang?.uppercase()

/**
 * The tag of a HISTORY or saved row, by `packId`.
 *
 * ⚠️ **It is a different mechanism from the results one, and the difference is real.** A results
 * row cannot be in another language: the search filters by the active one, across packs (D-189)
 * and inside a bidirectional pack. A history row **can**: it was stored when another dictionary
 * was installed, or with another language chosen. Tagging it with the active language would assert
 * a provenance nobody checked -- the same failure family as D-080.
 *
 * ⚠️ **And a bidirectional pack gets no tag**, because there is no single true one: `casa` and
 * `house` live in the same file and `Visit` does not store the language. **No tag** is preferred
 * over the wrong one, which is the same rule a gloss's links are painted by. Storing the language
 * in `Visit` would fix it; that was not done because it changes what is written in the preferences
 * and that deserves a decision of its own.
 */
internal fun historyTags(packs: List<PackHandle>): Map<String, String> =
    packs.filterIsInstance<PackHandle.Open>()
        .mapNotNull { handle ->
            handle.metadata.langs.singleOrNull()?.let { handle.packId to it.uppercase() }
        }
        .toMap()