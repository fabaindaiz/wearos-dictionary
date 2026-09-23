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
/**
 * How far a search reaches: one language, or every installed one.
 *
 * ⚠️ **[STRICT] is the default and that REVERSES what D-172 measured.** The request was *"that
 * results filtered by language show only results in that language"*, with the reversal stated
 * outright: *"this goes against what I had decided before but I think it is better"*.
 *
 * **The cost is the number that justified the fallback**, and it did not change on reversing it:
 * of 400 common English lemmas, **321 (80 %)** triggered it with Spanish active. Those 321 now do
 * not appear until the language is switched. It is accepted because a list that mixes languages
 * unasked is worse to read than a short list.
 *
 * **Why an enum and not deleting the fallback.** `needsFallback`'s threshold was measured over the
 * real packs and that measurement cannot be reconstructed by reading the code. As a value, the
 * "auto mode" the request names for later is swapping [STRICT] for [FALLBACK] in one place;
 * deleted, it would mean deriving the threshold all over again.
 */
enum class LanguageScope {
    /** Only the active language answers. The default. */
    STRICT,

    /** The other languages answer when the active one had nothing close. The "auto mode". */
    FALLBACK,
}

class SearchRepository(
    private val packs: List<DictionarySource>,
    /**
     * The packs of the **other** languages, which answer only when the active one had nothing.
     *
     * Asked for: *"avoid conflicts when the word I am searching is in English but I picked Spanish
     * as the main language by mistake"*, with the explicit condition *"without overloading the
     * search across several packs unnecessarily"*. Both halves live in [needsFallback].
     */
    private val otherLanguages: List<DictionarySource> = emptyList(),
    /**
     * Whether [otherLanguages]'s packs may answer. By default **no**.
     *
     * See [LanguageScope]: the fallback was not deleted, it became a value.
     */
    private val scope: LanguageScope = LanguageScope.STRICT,
    /**
     * The language being searched in, or `null` for no filter.
     *
     * ⚠️ **It became necessary once a pack could hold entries in TWO languages.** Choosing the
     * pack no longer chooses the language: a bidirectional one speaks both, so without this a list
     * filtered to Spanish would bring its English lemmas. It travels down to each rung's
     * `WHERE lang = ?`, where it comes out of the same covering index and costs not one extra row.
     */
    private val lang: String? = null,
    /** Where the cascade reports what it did. See [SearchTrace]. */
    private val trace: SearchTrace = SearchTrace.None,
) {

    /** The ids of the active language, for [orderFor]'s tie-break. */
    private val activos: Set<String> = packs.map { it.metadata.packId }.toSet()

    /**
     * The packs whose `rank` comes from real usage frequency. See [RankBasis].
     *
     * Computed once and not per query: it is a property of the opened pack, not of the search. It
     * includes the other languages' packs too, because the tie-break holds among them just as well.
     */
    private val porFrecuencia: Set<String> =
        (packs + otherLanguages)
            .filter { it.metadata.rankBasis == RankBasis.FREQUENCY }
            .map { it.metadata.packId }
            .toSet()

    /**
     * The normal search, across every pack.
     *
     * `limit` applies to the **merged** list, not to each pack: asking for three and getting
     * three per pack would fill a watch screen with whichever pack answered first.
     */
    suspend fun suggest(query: String, limit: Int = DEFAULT_LIMIT): List<Suggestion> {
        val propias = recolectar(packs) { it.suggest(query, limit, lang) }
        val ajenas = if (needsFallback(query, propias)) {
            recolectar(otherLanguages) { it.suggest(query, limit) }
        } else {
            emptyList()
        }
        val resultado = ordenar(propias + ajenas, limit, query)
        reportar(query, resultado, respaldo = ajenas.isNotEmpty())
        return resultado
    }

    /**
     * Tells [trace] how a search came out, **only if somebody is listening**.
     *
     * It groups what **ended up in the result**, not what was collected: that is what the user
     * sees, and it is against that that a strange ordering gets debugged. What the limit or the
     * `distinctBy` discarded is not on screen and does not explain what the user reports.
     */
    private fun reportar(query: String, resultado: List<Suggestion>, respaldo: Boolean) {
        if (!trace.enabled) return
        trace.searched(
            query = query,
            byKind = resultado.groupingBy { it.matchKind }.eachCount(),
            fallback = respaldo,
            returned = resultado.size,
        )
    }

    /**
     * Do the other languages have to be asked?
     *
     * **Only if the active language returned nothing resembling what was typed**: no exact match,
     * and not a single row in the maximum coverage band. The condition is computed from the typed
     * text and the lemma, **without looking at a number from any pack**, just like [coverageBand]
     * -- so a badly calibrated pack can neither trigger the fallback nor suppress it.
     *
     * ⚠️ **The threshold was measured before it was chosen, over the two real packs.** Of **400
     * common Spanish lemmas, 0** trigger the fallback: the normal case pays absolutely nothing. Of
     * **400 common English lemmas, 321 (80 %)** trigger it, which is exactly the case it exists
     * for. The 79 that do not --`break`, `man`, `go`, `line`, `bear`-- **really are in the Spanish
     * pack**, so not reaching the fallback is the right answer.
     */
    private fun needsFallback(query: String, propias: List<Suggestion>): Boolean {
        if (scope == LanguageScope.STRICT) return false
        if (otherLanguages.isEmpty() || query.isEmpty()) return false
        return propias.none {
            query.equals(it.headword, ignoreCase = true) || coverageBand(query, it.headword) == 0
        }
    }

    /** The free-text search over definitions (D-084). Same merge, same order. */
    suspend fun searchDefinitions(query: String, limit: Int = DEFAULT_LIMIT): List<Suggestion> =
        // No `query` for the band: here what was typed is not a prefix of the lemma but a word
        // from the definition, so coverage means nothing. See [coverageBand].
        //
        // ⚠️ **And for the same reason there is no cross-language fallback here**: the
        // [needsFallback] threshold is computed from that coverage, and without it there is no way
        // to decide when the active language "had nothing" without inventing a criterion.
        ordenar(recolectar(packs) { it.searchDefinitions(query, limit, lang) }, limit, query = null)
            .also { reportar(query, it, respaldo = false) }

    private fun ordenar(todas: List<Suggestion>, limit: Int, query: String?): List<Suggestion> =
        todas
            .sortedWith(orderFor(query, activos, porFrecuencia))
            .distinctBy { it.headword to it.partOfSpeech }
            .take(limit)

    private suspend fun recolectar(
        fuentes: List<DictionarySource>,
        consultar: suspend (DictionarySource) -> List<Suggestion>,
    ): List<Suggestion> {
        val todas = mutableListOf<Suggestion>()
        for (pack in fuentes) {
            // ⚠️ A broken pack must not take the search down with it. A corrupt or truncated
            // file opens fine and fails when queried; with two installed, one falling over
            // cannot leave the user with no dictionary at all. Same criterion the word of the
            // day already applies. The failure is not swallowed into a wrong answer -- the
            // other packs still answer, and a pack that never returns anything is visible.
            @Suppress("TooGenericExceptionCaught")
            val suyas = try {
                consultar(pack)
            } catch (e: Exception) {
                // The search carries on without it, but **it gets recorded**: without this a pack
                // that blows up on every query is indistinguishable from an empty one. See
                // [SearchTrace.packFailed].
                trace.packFailed(
                    pack.metadata.packId,
                    e.message ?: e::class.simpleName ?: "excepcion sin mensaje",
                )
                continue
            }
            todas.addAll(suyas)
        }
        return todas
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
        private fun orderFor(
            query: String?,
            activos: Set<String>,
            porFrecuencia: Set<String>,
        ): Comparator<Suggestion> {
            if (query.isNullOrEmpty()) return ORDEN
            return compareBy<Suggestion> { it.matchKind.ordinal }
                .thenBy { if (it.matchKind == MatchKind.PREFIX) demoteProperNoun(query, it) else 0 }
                // ⚠️ **Before the coverage band, and only on `PREFIX`.** Afterwards it would be
                // useless: the band would already have put the short fragment on top. Measured
                // over the English pack, the mean position of the obvious word goes from **5.1 to
                // 3.6** -- `hous` left `Hous.` first and `tim` put four `Tim`s before `time`.
                //
                // ⚠️ **It does not fix the root cause, and that is worth knowing**: `wat`, `boo`
                // and `beaut` have signal too --they are real subtitle tokens-- so they stay in
                // front. It is a tie-break, not a cure.
                .thenBy {
                    if (it.matchKind == MatchKind.PREFIX && !it.hasFrequencySignal) 1 else 0
                }
                .thenBy { if (it.matchKind == MatchKind.PREFIX) coverageBand(query, it.headword) else 0 }
                // ⚠️ **The active language breaks ties, and it goes AFTER quality and not
                // before.** If the fallback simply went to the end of the list, an exact answer in
                // the other language would sit below ten phonetic near-misses from the active one
                // -- off screen, which is the same as never having searched for it. And the other
                // way round: all else being equal, the language the user chose wins, because they
                // chose it.
                .thenBy { if (it.packId in activos) 0 else 1 }
                .thenBy { it.matchKind.ordinal }
                .thenBy { it.score }
                // ⚠️ **At equal position the better calibrated pack wins, and it is the weakest
                // tie-break that is worth anything.** The merge is already ordinal --`score` is
                // the position within the pack's own results-- so the `rank` scale cancels out.
                // What that does NOT fix: a badly calibrated pack puts the wrong word at position
                // 0 and on interleaving it carries the same weight as a well calibrated one.
                // Measured over the real packs: `es-def-wikc` ranges 668..1997 and `es-def-wd`
                // 911..997, the same language with different formulas, and nothing told the app.
                //
                // It goes AFTER `score` on purpose: it breaks ties, it does not reorder. Putting
                // it before would sink the other pack whole and with it its exclusive lemmas,
                // which are exactly the gain D-136 measured.
                .thenBy { if (it.packId in porFrecuencia) 0 else 1 }
                .thenBy { it.headword }
                .thenBy { it.packId }
                .thenBy { it.entryId }
        }

        /**
         * The `pos` values a source uses for a proper noun.
         *
         * Two vocabularies because there are two sources: kaikki says `name` and `sources/toy.py`
         * says `proper noun`. Looking at only one lets the other through, and that already
         * happened once (D-116).
         */
        private val NOMBRES_PROPIOS = setOf("name", "proper noun")

        /**
         * A proper noun goes **below** a common word, unless it is exactly what you typed.
         *
         * ⚠️ **The builder's penalty was not enough, and that was measured.** `CASTIGO_NOMBRE_
         * PROPIO` (D-134) already puts them at the bottom of `rank`, but the coverage band goes
         * **ahead** of rank in this merge: a short toponym beats a long common word. Over the real
         * pack, typing "ital" returned `Italia` first and `italiano` second.
         *
         * ⚠️ **The exception is the half that makes it useful.** "Fez", "Car" and "Peru" normalize
         * to exactly what was typed, or nearly: somebody typing the whole word may well be after
         * the city, and penalizing it there would turn the improvement into a loss. So the penalty
         * applies **only** when it is not exact and is also not in the maximum coverage band.
         *
         * It is computed from the `pos` and the typed text, without looking at any number from the
         * pack -- the same property that makes [coverageBand] trustworthy against a badly
         * calibrated pack.
         */
        private fun demoteProperNoun(query: String, suggestion: Suggestion): Int {
            if (suggestion.partOfSpeech !in NOMBRES_PROPIOS) return 0
            val exacto = query.equals(suggestion.headword, ignoreCase = true)
            return if (exacto || coverageBand(query, suggestion.headword) == 0) 0 else 1
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
