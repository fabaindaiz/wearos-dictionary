package cl.fadiaz.dictionary.core

/**
 * Searches **several packs at once** and merges the answers into one list (D-136).
 *
 * The layer the roadmap kept naming and that did not exist. Two different things were waiting on
 * it, and telling them apart matters because only the first is built here:
 *
 *  - **Coexistence.** Two *base* packs of the same language, from different sources, installed
 *    together. The value is the **union of headwords**: a source has a word the other lacks.
 *    Asked for as *"varios packs para un mismo idioma de distintas fuentes que puedan convivir"*.
 *  - **Composition.** An *auxiliary* pack adding fields to another pack's entry, joined by
 *    [Entry.uid] (D-055). That needs this layer underneath and a granularity decision that is
 *    still open — `uid` is per entry and a synonym is per sense. **Not built here.**
 *
 * ⚠️ **This is deliberately NOT a [DictionarySource], and the reason is a bug class.** Half of
 * that interface is addressed by `entryId`, which is a **rowid local to one pack** (`schema.sql`):
 * answering `entry(7)` over a set of packs means choosing one, and choosing silently is D-080 —
 * the app shows a *different word* with no error anywhere. A composite implementing the whole
 * interface would have to either throw on half its methods or guess. The narrow surface makes it
 * unrepresentable instead: everything here returns [Suggestion], which carries its `packId`, and
 * opening an entry stays the caller's per-pack path.
 *
 * **The fan-out is sequential on purpose.** The installed packs are one or two, and `:dict-data`
 * already serialises each pack's queries onto its own single-threaded dispatcher (D-050), so
 * `async` would buy little and would cost `:dict-core` a dependency it does not have today.
 */
class SearchRepository(private val packs: List<DictionarySource>) {

    /**
     * The normal search, across every pack.
     *
     * `limit` applies to the **merged** list, not to each pack: asking for three and getting
     * three per pack would fill a watch screen with whichever pack answered first.
     */
    suspend fun suggest(query: String, limit: Int = DEFAULT_LIMIT): List<Suggestion> =
        merge(limit) { it.suggest(query, limit) }

    /** The free-text search over definitions (D-084). Same merge, same order. */
    suspend fun searchDefinitions(query: String, limit: Int = DEFAULT_LIMIT): List<Suggestion> =
        merge(limit) { it.searchDefinitions(query, limit) }

    private suspend fun merge(
        limit: Int,
        consultar: suspend (DictionarySource) -> List<Suggestion>,
    ): List<Suggestion> {
        val todas = mutableListOf<Suggestion>()
        for (pack in packs) {
            // ⚠️ A broken pack must not take the search down with it. A corrupt or truncated
            // file opens fine and fails when queried; with two installed, one falling over
            // cannot leave the user with no dictionary at all. Same criterion the word of the
            // day already applies. The failure is not swallowed into a wrong answer -- the
            // other packs still answer, and a pack that never returns anything is visible.
            @Suppress("TooGenericExceptionCaught", "SwallowedException")
            val suyas = try {
                consultar(pack)
            } catch (e: Exception) {
                continue
            }
            todas.addAll(suyas)
        }
        return todas.sortedWith(ORDEN).distinctBy { it.headword to it.partOfSpeech }.take(limit)
    }

    private companion object {
        const val DEFAULT_LIMIT = 30

        /**
         * The order across packs, and the reason it is one comparator and not a concatenation.
         *
         * [MatchKind] first because it is declared in quality order: a **prefix** hit in the
         * second pack has to beat a **fuzzy** hit in the first, or typing a word correctly that
         * only the second pack has would rank it below the first pack's typos.
         *
         * ⚠️ **`packId` and `entryId` at the end are not cosmetic.** Without a total order two
         * identical queries can return the same rows in a different order, and D-128 measured
         * what that does: the list restructures, the text field is recomposed, and **the
         * keyboard closes**. Intermittently, with no visible cause.
         *
         * ⚠️ **What this order does NOT promise**: `score` derives from `rank`, which each pack
         * computes against its own dump with its own profile (D-063). Comparing it across packs
         * from different sources is an approximation nobody has measured. It is good enough to
         * keep exact matches above fuzzy ones; it is not good enough to claim the first row is
         * the best of the two packs. Measuring that needs two real same-language packs.
         */
        val ORDEN: Comparator<Suggestion> = compareBy(
            { it.matchKind.ordinal },
            { it.score },
            { it.headword },
            { it.packId },
            { it.entryId },
        )
    }
}
