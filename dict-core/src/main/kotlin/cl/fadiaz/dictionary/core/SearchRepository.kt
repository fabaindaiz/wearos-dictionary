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
        merge(limit, query) { it.suggest(query, limit) }

    /** The free-text search over definitions (D-084). Same merge, same order. */
    suspend fun searchDefinitions(query: String, limit: Int = DEFAULT_LIMIT): List<Suggestion> =
        // Sin `query` para la banda: acá lo escrito no es un prefijo del lema sino una palabra
        // de la definición, así que la cobertura no significa nada. Ver [coverageBand].
        merge(limit, query = null) { it.searchDefinitions(query, limit) }

    private suspend fun merge(
        limit: Int,
        query: String?,
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
        return todas
            .sortedWith(orderFor(query))
            .distinctBy { it.headword to it.partOfSpeech }
            .take(limit)
    }

    private companion object {
        const val DEFAULT_LIMIT = 30

        /**
         * How much of the headword the user actually typed, in **coarse bands**.
         *
         * ⚠️ **The one signal in the whole ordering that trusts no pack at all** (D-142). It is a
         * function of the typed text and of the headword string: no `rank`, no `score`, nothing a
         * pack computed. That is exactly what makes it survive a badly calibrated —or hostile—
         * community pack, because `score` is *the position inside that pack's own list*, so a
         * pack with a broken internal order hands its garbage over at position 0 and the merge
         * treats it as the equal of the good pack's best row.
         *
         * **Bands and not the raw ratio, deliberately.** Coarse buckets put `casa` above
         * `castigar` without overriding a *good* pack inside a bucket: two words of similar
         * length keep the order the pack chose, which beats any heuristic when the pack is sane.
         *
         * Measured over the two real Spanish packs: typing "cas" returned `castigar, castreño,
         * cascar` and **did not return `casa` at all**; with this it returns `casa, casar,
         * cascar`. "per" goes from `percibir, perder` to `perro, persa`. "arb" from `árbitro` to
         * `árbol`. It fixes a visible ordering bug that was there with **one** pack too (D-067).
         *
         * ⚠️ Cross-pack agreement —ranking a headword higher because several packs returned it—
         * was measured next to this and **rejected**: it improved "cas" and made "tomat" worse,
         * and it makes the order depend on which *other* dictionaries happen to be installed.
         */
        internal fun coverageBand(query: String, headword: String): Int {
            if (headword.isEmpty()) return LAST_BAND
            val coverage = query.length.toDouble() / headword.length
            return when {
                coverage >= 0.85 -> 0   // practically typed the whole word
                coverage >= 0.60 -> 1
                coverage >= 0.40 -> 2
                else -> LAST_BAND
            }
        }

        private const val LAST_BAND = 3

        /**
         * [ORDEN], with the calibration-free band in front when there is a query to measure
         * against.
         *
         * The band applies **only to prefix matches**. For `FUZZY`, `score` is the edit distance
         * —which we compute, so it is already pack-independent— and the coverage of a word that
         * is *not* a prefix of the headword means nothing.
         */
        private fun orderFor(query: String?): Comparator<Suggestion> {
            if (query.isNullOrEmpty()) return ORDEN
            return compareBy<Suggestion> { it.matchKind.ordinal }
                .thenBy { if (it.matchKind == MatchKind.PREFIX) coverageBand(query, it.headword) else 0 }
                .thenComparator { a, b -> ORDEN.compare(a, b) }
        }

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
