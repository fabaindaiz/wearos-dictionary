package cl.fadiaz.dictionary.core

/**
 * A queryable language pack. It is the only surface through which the app reaches the data:
 * neither the UI nor the ViewModels see SQL.
 *
 * The interface living in a pure Kotlin module and never mentioning SQLite is what makes it
 * possible to change the storage implementation (today, packed SQLite) without touching anything
 * above it, and to test the search layer with in-memory implementations.
 *
 * Every query function is cancellable: the search fires on each keystroke and the previous query
 * is discarded, so an implementation must check for cancellation while iterating rows and release
 * its resources on the way out.
 */
interface DictionarySource {

    val metadata: PackMetadata

    /**
     * Results for what the user has typed so far, in the order they are shown.
     *
     * It combines, in this priority order: lemma prefix, inflected form and translation side. It
     * falls back to the typo-tolerant key only when the above returns very little, because that is
     * the expensive path and its results are the least trustworthy.
     */
    suspend fun suggest(query: String, limit: Int = 30, lang: String? = null): List<Suggestion>

    // ⚠️ **`lang` exists because a pack can hold entries in TWO languages.** In a bidirectional
    // one `casa` and `house` live together, and a list the user filtered to Spanish cannot bring
    // English lemmas. `null` = every language in the pack, which is what a monolingual pack does
    // and what "auto mode" will do once it exists.
    //
    // ⚠️ **The filtering happens in SQL and not in Kotlin**, and that is not micro-optimization:
    // `idx_entry_norm` includes `lang` at the end, so the filter resolves INSIDE the covering
    // index without touching the table. Filtering afterwards would mean fetching twice the rows
    // to fill the same limit, and the prefix rung is 95 % of the work.

    /** An entry's body. Here the payload is read and decompressed. */
    suspend fun entry(entryId: Long): Entry?

    /**
     * Free-text search inside the definitions (FTS5).
     *
     * It is an explicit action by the user, never fired while typing: it walks an index far larger
     * than the lemma one and does not meet the incremental search's latency budget.
     */
    suspend fun searchDefinitions(
        query: String,
        limit: Int = 30,
        lang: String? = null,
    ): List<Suggestion>

    /**
     * Which of these normalized keys are a lemma of the pack, and with which entry.
     *
     * It is what lets a gloss's words be made tappable: ALL of them are asked for at once --one
     * query per screen, not one per word-- and only what exists gets painted, so the colour says
     * up front that it leads somewhere.
     *
     * When several entries share a `norm` --"árbol" and accentless "arbol" are two different
     * entries-- it returns the one with the **best rank**, which is the same rule the results list
     * uses to choose what to show first (D-068).
     *
     * ⚠️ **`lang` is not optional in practice, and forgetting it produced a real bug.** Choosing
     * by best rank was right while a pack had a single language; in a bidirectional one `pie` is
     * **both things** --Spanish, the body part; English, the pastry-- so tapping `foot`'s
     * translation `pie` opened the ENGLISH `pie`: a translation that sends you back to the
     * language you came from. Measured over the real pack: **8.30 %** of the translations of
     * English entries resolved to the wrong language.
     *
     * `null` = any language, which is what a monolingual pack does and what everybody did before.
     *
     * No default, on purpose: an implementation that forgets it would leave the gloss with no
     * links, and that is indistinguishable from a gloss with no known words.
     */
    suspend fun resolveHeadwords(norms: Set<String>, lang: String? = null): Map<String, Long>

    /**
     * An entry's header by its id, without decompressing the payload.
     *
     * One row, served by primary key. It is used to discard candidates cheaply --word of the day
     * tries several before settling on one-- without paying an inflate for each.
     */
    suspend fun summary(entryId: Long): EntrySummary?

    /** Closes the underlying connection. The pack becomes unusable. */
    fun close()
}
