package cl.fadiaz.dictionary.data

import androidx.sqlite.SQLiteStatement
import cl.fadiaz.dictionary.core.DictionarySource
import cl.fadiaz.dictionary.core.EditDistance
import cl.fadiaz.dictionary.core.Entry
import cl.fadiaz.dictionary.core.EntrySummary
import cl.fadiaz.dictionary.core.MatchKind
import cl.fadiaz.dictionary.core.fuzzyProfileFor
import cl.fadiaz.dictionary.core.PackMetadata
import cl.fadiaz.dictionary.core.PayloadCodec
import cl.fadiaz.dictionary.core.PrefixRange
import cl.fadiaz.dictionary.core.Suggestion
import cl.fadiaz.dictionary.core.TextNormalizer
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Consulta un pack. Es la implementacion de [DictionarySource] sobre SQLite.
 *
 * CONFINED TO ONE THREAD
 *
 * The bundled SQLite reports `THREADSAFE=2`, which is multi-thread and **not** serialized: one
 * connection cannot be used from two threads at once. So every pack carries its own
 * single-thread dispatcher and every query goes through it. That is not a precaution: it is a
 * requirement of the library, verified in `PlatformAssumptionsTest`.
 *
 * The side effect is useful: queries against one pack serialize themselves, so an old search
 * that has not finished does not compete with the new one.
 *
 * CANCELLATION
 *
 * The search fires on every keystroke and the previous one is discarded. Every row loop checks
 * for cancellation, because a query still reading after the user has typed on is CPU work the
 * watch pays for in battery and nobody uses.
 */
class SqlitePackSource(
    private val pack: PackFile,
    private val dispatcher: CoroutineDispatcher = defaultDispatcher(),
) : DictionarySource {

    override val metadata: PackMetadata get() = pack.metadata

    /**
     * Results for what the user has typed so far.
     *
     * The cascade is ordered by confidence and by cost: prefix, inflected form, translation, and
     * only if that returned almost nothing, the error-tolerant rung. That last one is skipped in
     * the normal case because it is the most expensive and the least trustworthy.
     */
    override suspend fun suggest(query: String, limit: Int, lang: String?): List<Suggestion> {
        val normalized = TextNormalizer.norm(query)
        if (normalized.isEmpty()) return emptyList()
        // A single-language pack does not filter: the WHERE would be redundant and would change
        // its query plan, which `measure_query_cost.py` replicates.
        val filtro = lang?.takeIf { pack.metadata.langs.size > 1 }

        return withContext(dispatcher) {
            val accumulated = LinkedHashMap<Long, Suggestion>()

            byPrefix(normalized, limit, filtro).forEach { accumulated.putIfBetter(it) }
            if (accumulated.size < limit) {
                byInflectedForm(normalized, limit, filtro).forEach { accumulated.putIfBetter(it) }
            }
            if (accumulated.size < limit) {
                byTranslation(normalized, limit, filtro).forEach { accumulated.putIfBetter(it) }
            }
            // The tolerant rung only enters when what came before was nearly empty. With good
            // results already in hand, adding edit-distance candidates only muddies them.
            if (accumulated.size < FUZZY_TRIGGER) {
                byFuzzy(normalized, query, filtro).forEach { accumulated.putIfBetter(it) }
            }

            // Deduplication runs AFTER sorting and at the end of the cascade, not inside one
            // rung: `accumulated` is keyed by entryId, so two distinct entries with the same
            // headword and pos both survive. Merging them before sorting would pick one at
            // random; doing it after keeps the one from the better rung with the better score.
            //
            // That deduplicating inside byPrefix is not enough was found by a test: the tolerant
            // rung put back the entry the prefix rung had already merged.
            accumulated.values
                .sortedWith(compareBy({ it.matchKind.ordinal }, { it.score }))
                .distinctBy { it.headword to it.partOfSpeech }
                .take(limit)
        }
    }

    /**
     * Headword prefix: the first rung, and the only one that runs when it suffices.
     *
     * ⚠️ **It used to say "95 % of use" and a measurement disproved it.** That is true WHILE
     * TYPING --37 of 70 prefixes fill the limit-- and false for the search that actually runs:
     * D-128 does not search with the keyboard open, it searches once on closing it and over the
     * **whole** word, which is exactly the case where the prefix returns few rows and the cascade
     * continues. Measured over the Spanish pack, a real search averages **3.0 rungs**, not 1. It
     * changes nothing about this method; it changes what somebody concludes from reading it. See
     * `docs/bateria.md`.
     *
     * It comes whole from the covering index, without touching the table and without reading a
     * single payload. An explicit range is used rather than `LIKE 'x%'` because LIKE is only
     * optimized into a range scan when `case_sensitive_like` holds the right value, and in doubt
     * SQLite does a full scan (D-012).
     */
    private suspend fun byPrefix(
        normalized: String,
        limit: Int,
        lang: String?,
    ): List<Suggestion> {
        val upper = PrefixRange.upperBound(normalized)
        // `lang` sits at the end of `idx_entry_norm`, so the filter comes from that same
        // covering index and does not touch the table.
        val porIdioma = if (lang != null) " AND lang = ?" else ""
        // The range comes from the covering index; the ORDER does NOT, and that is deliberate
        // (D-068). `norm` is alphabetical, and ordering by it buries the common word under the
        // rare ones that share its prefix. The CASE lifts the exact match, which is what the user
        // has just typed in full and can never be missing.
        val order = " ORDER BY CASE WHEN norm = ? THEN 0 ELSE 1 END, rank, norm LIMIT ?"
        val sql = if (upper != null) {
            "SELECT id, headword, pos, rank FROM entry WHERE norm >= ? AND norm < ?" +
                porIdioma + order
        } else {
            "SELECT id, headword, pos, rank FROM entry WHERE norm >= ?" + porIdioma + order
        }

        val rows = pack.connection().prepare(sql).use { statement ->
            var i = 1
            statement.bindText(i++, normalized)
            if (upper != null) statement.bindText(i++, upper)
            if (lang != null) statement.bindText(i++, lang)
            statement.bindText(i++, normalized)
            statement.bindInt(i, limit * PREFIX_OVERFETCH)
            statement.collectSuggestions(MatchKind.PREFIX, rankIndex = 3)
        }

        // More rows are asked for and deduplicated here rather than with GROUP BY: measured over
        // the real pack, GROUP BY costs 7.9 ms p95 with a one-letter prefix against 1.8 ms this
        // way, and it does not even remove the duplicates you can see, which differ in pos.
        return rows.distinctBy { it.headword to it.partOfSpeech }.take(limit)
    }

    /** "corriendo" was typed and the headword is "correr". */
    private suspend fun byInflectedForm(
        normalized: String,
        limit: Int,
        lang: String?,
    ): List<Suggestion> =
        pack.connection().prepare(
            "SELECT e.id, e.headword, e.pos FROM form f JOIN entry e ON e.id = f.entry_id" +
                " WHERE f.norm = ?" + (if (lang != null) " AND e.lang = ?" else "") +
                " ORDER BY e.rank LIMIT ?",
        ).use { statement ->
            var i = 1
            statement.bindText(i++, normalized)
            if (lang != null) statement.bindText(i++, lang)
            statement.bindInt(i, limit)
            statement.collectSuggestions(MatchKind.INFLECTED_FORM)
        }

    /**
     * The translation side.
     *
     * **It deduplicates per entry on purpose.** The prefix range matches several keys of the same
     * entry ("to", "to run", "to pass"), and without the `IN (SELECT ...)` the entry would come
     * out once per key.
     */
    private suspend fun byTranslation(
        normalized: String,
        limit: Int,
        lang: String?,
    ): List<Suggestion> {
        val upper = PrefixRange.upperBound(normalized) ?: return emptyList()
        return pack.connection().prepare(
            "SELECT e.id, e.headword, e.pos FROM entry e WHERE e.id IN" +
                " (SELECT entry_id FROM trans WHERE norm >= ? AND norm < ?)" +
                (if (lang != null) " AND e.lang = ?" else "") +
                " ORDER BY e.rank LIMIT ?",
        ).use { statement ->
            var i = 1
            statement.bindText(i++, normalized)
            statement.bindText(i++, upper)
            if (lang != null) statement.bindText(i++, lang)
            statement.bindInt(i, limit)
            statement.collectSuggestions(MatchKind.TRANSLATION)
        }
    }

    /**
     * The error-tolerant rung.
     *
     * It queries a **prefix** of the fuzzy key rather than the whole key: that brings back a
     * neighbourhood instead of only the exact collisions, which is what is needed when voice
     * dictation produced something that matches nothing.
     *
     * The candidates are reordered in Kotlin by Damerau-Levenshtein distance against the
     * normalized query. The index includes `norm` precisely so that can be done without reading
     * the table (D-013); only the survivors are then looked up by id.
     */
    private suspend fun byFuzzy(
        normalized: String,
        rawQuery: String,
        lang: String?,
    ): List<Suggestion> {
        // ⚠️ **The profile is the SEARCHED LANGUAGE's, not the pack's.** Folding an English
        // query with Spanish rules --`ce`→`se`, `v`→`b`-- would give a key that does not exist in
        // the English half of the pack, and the tolerant rung would stop finding anything there.
        val fuzzyKey = TextNormalizer.fuzzy(rawQuery, pack.metadata.fuzzyProfileFor(lang))
        if (fuzzyKey.isEmpty()) return emptyList()

        val prefix = fuzzyKey.take(FUZZY_PREFIX_LENGTH)
        val upper = PrefixRange.upperBound(prefix) ?: return emptyList()

        val candidates = mutableListOf<Pair<Long, Int>>()
        pack.connection().prepare(
            "SELECT id, norm FROM entry WHERE fuzzy >= ? AND fuzzy < ?" +
                (if (lang != null) " AND lang = ?" else "") + " LIMIT ?",
        ).use { statement ->
            var i = 1
            statement.bindText(i++, prefix)
            statement.bindText(i++, upper)
            if (lang != null) statement.bindText(i++, lang)
            statement.bindInt(i, FUZZY_CANDIDATES)
            val context = currentCoroutineContext()
            while (statement.step()) {
                context.ensureActive()
                val distance = EditDistance.damerauLevenshtein(
                    normalized,
                    statement.getText(1),
                    MAX_EDIT_DISTANCE,
                )
                if (distance <= MAX_EDIT_DISTANCE) {
                    candidates += statement.getLong(0) to distance
                }
            }
        }
        if (candidates.isEmpty()) return emptyList()

        val best = candidates.sortedBy { it.second }.take(FUZZY_RESULTS)
        val byId = best.associate { it.first to it.second }
        val placeholders = best.joinToString(",") { "?" }

        return pack.connection().prepare(
            "SELECT id, headword, pos FROM entry WHERE id IN ($placeholders)",
        ).use { statement ->
            best.forEachIndexed { index, (id, _) -> statement.bindLong(index + 1, id) }
            val out = mutableListOf<Suggestion>()
            val context = currentCoroutineContext()
            while (statement.step()) {
                context.ensureActive()
                val id = statement.getLong(0)
                out += Suggestion(
                    packId = pack.metadata.packId,
                    entryId = id,
                    headword = statement.getText(1),
                    partOfSpeech = statement.getTextOrNull(2),
                    matchKind = MatchKind.FUZZY,
                    score = byId.getValue(id),
                )
            }
            out
        }
    }

    /**
     * Free text inside the definitions.
     *
     * It is an explicit user action and **never** fires while typing: it walks an index far
     * larger than the headword one.
     */
    override suspend fun searchDefinitions(
        query: String,
        limit: Int,
        lang: String?,
    ): List<Suggestion> {
        val expression = toMatchExpression(query)
        if (expression.isEmpty()) return emptyList()

        return withContext(dispatcher) {
            val ids = mutableListOf<Long>()
            pack.connection().prepare(
                "SELECT rowid FROM fts_def WHERE fts_def MATCH ? ORDER BY rank LIMIT ?",
            ).use { statement ->
                statement.bindText(1, expression)
                statement.bindInt(2, limit)
                val context = currentCoroutineContext()
                while (statement.step()) {
                    context.ensureActive()
                    ids += statement.getLong(0)
                }
            }
            if (ids.isEmpty()) return@withContext emptyList()

            // The order FTS5 has just computed is saved BEFORE it is lost.
            //
            // The query below is `WHERE id IN (...)`, which SQLite resolves through the PK index
            // and returns in **rowid** order, not relevance order. Without this,
            // `collectSuggestions` would assign `score` over that order and bm25's ranking would
            // be computed and thrown away: the best-matching definition would not lead. It is the
            // same class of bug that made the prefix "per" not return "perro".
            //
            // It is reordered in memory rather than with a JOIN, because a JOIN changes the query
            // plan, and in this repo a plan is not changed without measuring it (D-012). Thirty
            // rows at most.
            val posicionEnFts = ids.withIndex().associate { (posicion, id) -> id to posicion }

            // fts_def is contentless: it returns only rowids, which ARE entry.id (D-011).
            // ⚠️ **The language is filtered HERE and not in the MATCH**, because `fts_def` is
            // contentless and has no columns of its own to filter on: it returns only rowids. The
            // price is that the `LIMIT` above applies before the filter, so a definition search
            // in a bidirectional pack may return fewer than `limit`. That is accepted: it is an
            // explicit user action rather than the incremental search, and the alternative
            // --asking for twice as many and trimming-- would double the pack's most expensive
            // rung for a rare case.
            val porIdioma = if (lang != null) " AND lang = ?" else ""
            val placeholders = ids.joinToString(",") { "?" }
            pack.connection().prepare(
                "SELECT id, headword, pos FROM entry WHERE id IN ($placeholders)$porIdioma",
            ).use { statement ->
                ids.forEachIndexed { index, id -> statement.bindLong(index + 1, id) }
                if (lang != null) statement.bindText(ids.size + 1, lang)
                statement.collectSuggestions(MatchKind.DEFINITION)
                    .map { it.copy(score = posicionEnFts.getValue(it.entryId)) }
                    .sortedBy { it.score }
            }
        }
    }

    /** An entry's body. Here the payload is read and decompressed. */
    override suspend fun entry(entryId: Long): Entry? = withContext(dispatcher) {
        pack.connection().prepare(
            "SELECT headword, pos, payload, uid, lang FROM entry WHERE id = ?",
        ).use { statement ->
            statement.bindLong(1, entryId)
            if (!statement.step()) return@withContext null

            val body = PayloadCodec.decode(statement.getBlob(2), pack.payloadDictionary)
            Entry(
                packId = pack.metadata.packId,
                entryId = entryId,
                // The row was already read whole to fetch the payload, so the uid comes free:
                // and this is exactly when composition between packs needs it.
                uid = statement.getLong(3),
                // From the ROW and not from the pack: in a bidirectional pack the opened entry
                // may belong to the other language, which is exactly the case reached by tapping
                // a translation.
                lang = statement.getTextOrNull(4),
                headword = statement.getText(0),
                // The column's pos wins over the payload's: it is the one the list sorts by.
                partOfSpeech = statement.getTextOrNull(1) ?: body.partOfSpeech,
                senses = body.senses,
                wordTranslations = body.wordTranslations,
                forms = body.forms,
                pronunciation = body.pronunciation,
                etymology = body.etymology,
            )
        }
    }

    override suspend fun resolveHeadwords(
        norms: Set<String>,
        lang: String?,
    ): Map<String, Long> {
        if (norms.isEmpty()) return emptyMap()
        val claves = norms.take(MAX_PALABRAS_POR_CONSULTA)
        return withContext(dispatcher) {
            val huecos = claves.joinToString(",") { "?" }
            // Un pack de un solo idioma no filtra: el WHERE sobraria.
            val filtro = lang?.takeIf { pack.metadata.langs.size > 1 }
            val porIdioma = if (filtro != null) " AND lang = ?" else ""
            // NO `ORDER BY rank`, and that is deliberate: sorting globally over an `IN` forces
            // SQLite into a TEMP B-TREE and the query stops being served by the covering index
            // (D-012, the same effect D-063 measured). The best rank is chosen below, over the
            // handful of rows a gloss returns: with two entries per key there is nothing worth a
            // temporary table to sort.
            pack.connection().prepare(
                "SELECT norm, id, rank FROM entry WHERE norm IN ($huecos)$porIdioma",
            ).use { statement ->
                claves.forEachIndexed { indice, clave -> statement.bindText(indice + 1, clave) }
                if (filtro != null) statement.bindText(claves.size + 1, filtro)
                val context: CoroutineContext = currentCoroutineContext()
                val mejorPorClave = HashMap<String, Pair<Long, Int>>()
                while (statement.step()) {
                    context.ensureActive()
                    val clave = statement.getText(0)
                    val id = statement.getLong(1)
                    val rank = statement.getInt(2)
                    val actual = mejorPorClave[clave]
                    // Lower rank is more common: "árbol" with its accent (45) beats the
                    // accentless variant (900), which is the rule the list sorts by (D-068).
                    if (actual == null || rank < actual.second) {
                        mejorPorClave[clave] = id to rank
                    }
                }
                mejorPorClave.mapValues { (_, par) -> par.first }
            }
        }
    }


    override suspend fun summary(entryId: Long): EntrySummary? = withContext(dispatcher) {
        pack.connection().prepare(
            "SELECT headword, pos, rank FROM entry WHERE id = ?",
        ).use { statement ->
            statement.bindLong(1, entryId)
            if (!statement.step()) return@withContext null
            EntrySummary(
                entryId = entryId,
                headword = statement.getText(0),
                partOfSpeech = statement.getTextOrNull(1),
                rank = statement.getInt(2),
            )
        }
    }

    override fun close() {
        pack.close()
    }

    // ----------------------------------------------------------------- helpers

    /**
     * ⚠️ **`rankIndex` is optional because only the PREFIX rung carries it.** There the query
     * comes from the covering index, which already includes `rank`, so asking for it **costs not
     * one extra row**; on the other rungs the table would have to be touched for a tie-break that
     * does not apply -- `hasFrequencySignal` only orders within `PREFIX`.
     */
    private suspend fun SQLiteStatement.collectSuggestions(
        kind: MatchKind,
        // `Int?` and not a `-1` sentinel: lint rejects it --`getInt` requires >= 0-- and it is
        // right, a magic value in a column position is exactly where an off-by-one hides.
        rankIndex: Int? = null,
    ): List<Suggestion> {
        val frontera = pack.metadata.rankSignalBoundary
        val out = mutableListOf<Suggestion>()
        val context: CoroutineContext = currentCoroutineContext()
        var position = 0
        while (step()) {
            context.ensureActive()
            out += Suggestion(
                packId = pack.metadata.packId,
                entryId = getLong(0),
                headword = getText(1),
                partOfSpeech = getTextOrNull(2),
                matchKind = kind,
                // Lower is better: the position within its rung, already ordered by rank.
                score = position++,
                // The signal is resolved HERE, against the boundary THIS pack declares (D-198).
                // What comes out is the answer, not the `rank`: the scale is not comparable
                // across packs (D-187) and exposing it would invite exactly that comparison.
                hasFrequencySignal = frontera != null &&
                    rankIndex != null &&
                    getInt(rankIndex) < frontera,
            )
        }
        return out
    }

    private fun SQLiteStatement.getTextOrNull(index: Int): String? =
        if (isNull(index)) null else getText(index)

    /** Un nivel mas confiable gana; a igual nivel, gana el mejor score. */
    private fun MutableMap<Long, Suggestion>.putIfBetter(candidate: Suggestion) {
        val existing = this[candidate.entryId]
        if (existing == null ||
            candidate.matchKind.ordinal < existing.matchKind.ordinal ||
            (candidate.matchKind == existing.matchKind && candidate.score < existing.score)
        ) {
            this[candidate.entryId] = candidate
        }
    }

    companion object {
        /** If the trustworthy cascade returned fewer than this, the tolerant rung is tried. */
        const val FUZZY_TRIGGER: Int = 5

        /**
         * How many rows are asked for per result that will be shown, so that the
         * deduplicacion no deje la lista corta.
         *
         * 3 comes from measuring the real pack: with the most productive one-letter prefix
         * (22,358
         * filas), pedir 90 y deduplicar en memoria deja 30 lemas distintos y cuesta 1,8 ms
         * p95 on the desktop. The watch's number is missing: that is what O-1 exists to give.
         */
        const val PREFIX_OVERFETCH: Int = 3

        /**
         * Cap on keys per query when resolving a gloss's words.
         *
         * Not an optimization but a hard limit: every key is a bound parameter and
         * SQLite tiene un maximo. La glosa mas larga medida en el pack real tiene bastante menos
         * than this, so the cap trims nothing real; it exists so that an anomalous text
         * --a 917-character example slipped in where a gloss belongs-- fails by trimming and not
         * tirando.
         */
        const val MAX_PALABRAS_POR_CONSULTA: Int = 64

        /** Cuantos caracteres de la clave fuzzy definen el vecindario. */
        const val FUZZY_PREFIX_LENGTH: Int = 4

        /** Cap on candidates to reorder by edit distance. */
        const val FUZZY_CANDIDATES: Int = 200

        /** Cuantos sobreviven al reordenamiento. */
        const val FUZZY_RESULTS: Int = 10

        /** Beyond this it is no longer a typo, it is a different word. */
        const val MAX_EDIT_DISTANCE: Int = 2

        /**
         * One thread per pack, because of `THREADSAFE=2`. `limitedParallelism(1)` over IO rather
         * than an executor of its own: it reuses the process pool instead of adding a thread.
         */
        fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

        /**
         * Turns free text into an FTS5 MATCH expression.
         *
         * Every token is quoted so that what the user types can **never** be
         * interprete como sintaxis de FTS5: un `"` suelto o un `OR` cambiarian la consulta o la
         * harian fallar.
         */
        internal fun toMatchExpression(query: String): String =
            TextNormalizer.norm(query)
                .split(' ')
                .filter { it.isNotEmpty() }
                .joinToString(" ") { "\"$it\"" }
    }
}
