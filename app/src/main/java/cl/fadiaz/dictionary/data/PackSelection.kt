package cl.fadiaz.dictionary.data

import cl.fadiaz.dictionary.core.DictionarySource
import cl.fadiaz.dictionary.core.PackKind
import cl.fadiaz.dictionary.core.PackMetadata
import cl.fadiaz.dictionary.core.PackTier
import cl.fadiaz.dictionary.core.speaks

/**
 * Of all the installed packs, which ones get asked.
 *
 * ## Why this exists as a separate list
 *
 * ⚠️ **Installed and queried are not the same thing, and until here they were.** Settings has to
 * keep showing **everything** that takes disk --otherwise a pack that is not queried becomes
 * invisible and there is no way to delete it-- while the search has to ask only what can
 * contribute something. `PackStore` still returns everything; this one chooses.
 *
 * ## The two rules, and why they are a single function
 *
 * They answer different questions and are applied in order:
 *
 * 1. **Of each `pack_id`, the highest `data_version`.** Two files with the same `pack_id` are the
 *    same dictionary (D-138); the older build is strictly worse (D-170). It closes a defect that
 *    exists today: `PackStore` opens every `.db` in the directory, so reinstalling a pack without
 *    deleting the previous one leaves **two builds answering**. Nothing comes out wrong --the
 *    `(lemma, pos)` deduplication covers it-- but twice the queries and twice the disk are paid,
 *    in silence.
 * 2. **A pack is not queried if the one containing it is there too.** It is the rule the core pack
 *    needs: each of its answers either already came from the full one and is dropped on
 *    deduplication, or is a lemma the full one does not have, **which cannot happen if it really
 *    is a subset**. Today **nobody uses it**: no pack declares `subset_of`. It is written because
 *    it is the same shape as the first one and separating them would be two passes with the same
 *    bug.
 *
 * The order matters: the build of each dictionary is chosen first and containment is looked at
 * **afterwards**, so a core does not survive merely because the installed full one is an old
 * build.
 *
 * ## Only the unabsorbed absorbs, and that is not a subtlety
 *
 * ⚠️ **A community pack can declare anything.** If two declare each other a subset, the obvious
 * version of this rule --*"drop everyone who names somebody present"*-- **drops both and leaves
 * the search with no dictionary**. It is the worst possible outcome for a badly made declaration,
 * and the test written for it found it: my first version did exactly that.
 *
 * The rule that works: **a pack only steps aside for another that has not stepped aside itself**.
 * In a cycle nobody qualifies as absorbing and both survive.
 *
 * The cost is that containment **is not transitive**: with A ⊂ B ⊂ C, A and C are queried, and A
 * is redundant. That is accepted on purpose -- `subset_of` asserts a direct containment and
 * nothing more, and the worst case of not chaining is extra work; the worst case of chaining is
 * being left with nothing.
 *
 * It is pure and free of Android so the gate covers it on the JVM (D-072).
 */
internal fun packsToQuery(opened: List<DictionarySource>): List<DictionarySource> {
    val masNuevos = opened
        .groupBy { it.metadata.packId }
        .map { (_, versiones) -> versiones.maxBy { it.metadata.dataVersion } }
    val presentes = masNuevos.map { it.metadata.packId }.toSet()
    // The ones that can absorb another: the ones not absorbed themselves. In a cycle none
    // qualifies, and they all survive.
    val absorbentes = masNuevos
        .filter { it.metadata.subsetOf == null || it.metadata.subsetOf !in presentes }
        .map { it.metadata.packId }
        .toSet()
    return masNuevos.filterNot { it.metadata.subsetOf in absorbentes }
}

/**
 * Which pack ends up **active**, which is the other half of the same rules.
 *
 * ⚠️ **Choosing the active one apart from [packsToQuery] was a hole, and one that cancelled
 * itself out.** The rule took the old build out of the list to query, but the active one was
 * chosen from the directory listing --which promises no order-- and was then added to the query
 * **always**. If the old one came up, the old one was queried for being active and the new one for
 * being in the list: **both builds answering**, which is exactly the defect the rule came to
 * close.
 *
 * The order of preference, and each one has its reason:
 *
 * 1. **Only among the ones that get queried.** Hand-picking a pack another one contains cannot
 *    return it: nothing will be asked of it.
 * 2. **A real dictionary beats a demo one** (D-081). The demo exists so a freshly installed app
 *    shows something; beating what the user installed would be backwards.
 * 3. **What the user chose last time**, if it is still there.
 * 4. Anything, as long as it is stable -- the list already comes ordered by the rules.
 */
internal fun activePack(opened: List<PackHandle.Open>, preferred: String?): PackHandle.Open? {
    val consultables = packsToQuery(opened.map { it.source }).toSet()
    val vivos = opened.filter { it.source in consultables }
    val candidatos = vivos.filterNot { it.isBundled }.ifEmpty { vivos }
    return candidatos.firstOrNull { it.packId == preferred } ?: candidatos.firstOrNull()
}

/**
 * Whether this pack has anything to say when the active language is [idioma].
 *
 * ⚠️ **A pack declares its languages as PEERS, and a bidirectional one answers for both.** It used
 * to be chosen with `langSource == idioma` and the bilingual one declared `es`: with **English
 * active** it fell into "other languages", which since D-189 never answer, so the only pack with
 * translations was invisible precisely in the `en → es` direction and `dog` stopped returning
 * `perro`.
 *
 * ⚠️ **Now the answer comes from the artifact and not from an inference.** Since `schema_version`
 * 4 every entry carries its `entry.lang` and the pack declares `meta.langs`: that the bilingual
 * one answers for English is not an app rule but a fact of the file --it has 164,249 English
 * lemmas-- and `verify_pack.py` checks that what is declared and what is there agree.
 *
 * Pure and free of Android, so the gate covers it on the JVM (D-072).
 */
internal fun answersFor(pack: DictionarySource, idioma: String?): Boolean =
    pack.metadata.speaks(idioma)

/**
 * Whether a **word of the day** can come out of this pack.
 *
 * ⚠️ **It lives here and not in the ViewModel because the rule already diverged once**: it held on
 * the screen and not on the tile (D-203), and having it written twice is exactly how that happens
 * again. All three paths --the screen, the tile's cache and the computation-- consult this one.
 *
 * **One** class stays out: translation packs (D-200). A reverse entry has no senses (D-196), so
 * the card would say *"you say `perro`"* and nothing else.
 *
 * ⚠️ **Core packs DO give a word of the day, and until now they did not.** They were excluded
 * because a core is the 8,000 most frequent words, so picking the best rank gave *the most common
 * of the most common* — `my`, `un`, `a`, `de`. Measured, the defect was not the core's but the
 * selection rule's: the full packs carry the same bias, only diluted (41 % of days in Spanish,
 * 16 % in English landed in the function-word zone just the same). With `WordOfTheDay.RANK_FLOOR`
 * all four real packs drop to **0 %**, and a core returns `acción`, `anillo`, `Christmas`,
 * `afternoon`. Excluding cores treated the symptom in one half.
 *
 * ⚠️ **It asks what the pack DECLARES** —`kind` (D-198)— and never its name or its entry count:
 * somebody else's pack can call itself anything, and all the app may believe is what the artefact
 * declares and `verify_pack.py` checks.
 *
 * ⚠️ **The consequence, written so nobody rediscovers it**: a fresh install —which carries only
 * the APK's cores— **now shows a word of the day**. It did not before, and that was an empty home
 * screen on every user's first launch.
 */
internal fun givesWordOfTheDay(meta: PackMetadata): Boolean =
    meta.kind != PackKind.BILINGUAL
