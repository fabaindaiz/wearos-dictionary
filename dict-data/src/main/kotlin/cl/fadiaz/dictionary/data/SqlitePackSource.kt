package cl.fadiaz.dictionary.data

import androidx.sqlite.SQLiteStatement
import cl.fadiaz.dictionary.core.DictionarySource
import cl.fadiaz.dictionary.core.EditDistance
import cl.fadiaz.dictionary.core.Entry
import cl.fadiaz.dictionary.core.MatchKind
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
 * CONFINAMIENTO A UN SOLO HILO
 *
 * El SQLite empacado reporta `THREADSAFE=2`, que es multi-thread y **no** serialized: una
 * conexion no se puede usar desde dos hilos a la vez. Por eso cada pack trae su propio
 * dispatcher de un solo hilo, y toda consulta pasa por ahi. No es una precaucion: es un
 * requisito de la libreria, verificado en `PlatformAssumptionsTest`.
 *
 * El efecto secundario es util: las consultas de un mismo pack se serializan solas, asi que una
 * busqueda vieja que todavia no termino no compite con la nueva.
 *
 * CANCELACION
 *
 * La busqueda se dispara en cada pulsacion y la anterior se descarta. Cada bucle de filas
 * chequea cancelacion, porque una consulta que sigue leyendo despues de que el usuario siguio
 * escribiendo es trabajo de CPU que el reloj paga en bateria para nada.
 */
class SqlitePackSource(
    private val pack: PackFile,
    private val dispatcher: CoroutineDispatcher = defaultDispatcher(),
) : DictionarySource {

    override val metadata: PackMetadata get() = pack.metadata

    /**
     * Resultados para lo que el usuario lleva escrito.
     *
     * La cascada esta ordenada por confianza y por costo: prefijo, forma flexionada,
     * traduccion, y recien si eso devolvio casi nada, el nivel tolerante a errores. Ese ultimo
     * se salta en el caso normal porque es el mas caro y el menos confiable.
     */
    override suspend fun suggest(query: String, limit: Int): List<Suggestion> {
        val normalized = TextNormalizer.norm(query)
        if (normalized.isEmpty()) return emptyList()

        return withContext(dispatcher) {
            val accumulated = LinkedHashMap<Long, Suggestion>()

            byPrefix(normalized, limit).forEach { accumulated.putIfBetter(it) }
            if (accumulated.size < limit) {
                byInflectedForm(normalized, limit).forEach { accumulated.putIfBetter(it) }
            }
            if (accumulated.size < limit) {
                byTranslation(normalized, limit).forEach { accumulated.putIfBetter(it) }
            }
            // El nivel tolerante solo entra cuando lo anterior fue casi vacio. Si ya hay
            // resultados buenos, agregar candidatos por distancia de edicion solo ensucia.
            if (accumulated.size < FUZZY_TRIGGER) {
                byFuzzy(normalized, query).forEach { accumulated.putIfBetter(it) }
            }

            accumulated.values
                .sortedWith(compareBy({ it.matchKind.ordinal }, { it.score }))
                .take(limit)
        }
    }

    /**
     * Prefijo del lema: el 95% del uso.
     *
     * Sale integra del covering index, sin tocar la tabla y sin leer un solo payload. Se usa un
     * rango explicito y no `LIKE 'x%'` porque LIKE solo se optimiza a range scan si
     * `case_sensitive_like` esta en el valor correcto, y ante la duda SQLite hace full scan
     * (D-012).
     */
    private suspend fun byPrefix(normalized: String, limit: Int): List<Suggestion> {
        val upper = PrefixRange.upperBound(normalized)
        val sql = if (upper != null) {
            "SELECT id, headword, pos FROM entry WHERE norm >= ? AND norm < ?" +
                " ORDER BY norm, rank DESC LIMIT ?"
        } else {
            "SELECT id, headword, pos FROM entry WHERE norm >= ? ORDER BY norm, rank DESC LIMIT ?"
        }

        return pack.connection().prepare(sql).use { statement ->
            statement.bindText(1, normalized)
            if (upper != null) {
                statement.bindText(2, upper)
                statement.bindInt(3, limit)
            } else {
                statement.bindInt(2, limit)
            }
            statement.collectSuggestions(MatchKind.PREFIX)
        }
    }

    /** Se escribio "corriendo" y el lema es "correr". */
    private suspend fun byInflectedForm(normalized: String, limit: Int): List<Suggestion> =
        pack.connection().prepare(
            "SELECT e.id, e.headword, e.pos FROM form f JOIN entry e ON e.id = f.entry_id" +
                " WHERE f.norm = ? ORDER BY e.rank LIMIT ?",
        ).use { statement ->
            statement.bindText(1, normalized)
            statement.bindInt(2, limit)
            statement.collectSuggestions(MatchKind.INFLECTED_FORM)
        }

    /**
     * Lado de la traduccion.
     *
     * **Deduplica por entrada a proposito.** El rango de prefijo matchea varias claves de la
     * misma entrada ("to", "to run", "to pass"), y sin el `IN (SELECT ...)` la entrada saldria
     * una vez por clave.
     */
    private suspend fun byTranslation(normalized: String, limit: Int): List<Suggestion> {
        val upper = PrefixRange.upperBound(normalized) ?: return emptyList()
        return pack.connection().prepare(
            "SELECT e.id, e.headword, e.pos FROM entry e WHERE e.id IN" +
                " (SELECT entry_id FROM trans WHERE norm >= ? AND norm < ?)" +
                " ORDER BY e.rank LIMIT ?",
        ).use { statement ->
            statement.bindText(1, normalized)
            statement.bindText(2, upper)
            statement.bindInt(3, limit)
            statement.collectSuggestions(MatchKind.TRANSLATION)
        }
    }

    /**
     * Nivel tolerante a errores.
     *
     * Consulta por un **prefijo** de la clave fuzzy, no por la clave entera: eso trae un
     * vecindario en vez de solo las colisiones exactas, que es lo que hace falta cuando el
     * dictado por voz produjo algo que no coincide con nada.
     *
     * Los candidatos se reordenan en Kotlin por distancia de Damerau-Levenshtein contra la
     * consulta normalizada. El indice incluye `norm` justamente para poder hacer eso sin leer
     * la tabla (D-013); recien los sobrevivientes se buscan por id.
     */
    private suspend fun byFuzzy(normalized: String, rawQuery: String): List<Suggestion> {
        val fuzzyKey = TextNormalizer.fuzzy(rawQuery, pack.metadata.fuzzyProfile)
        if (fuzzyKey.isEmpty()) return emptyList()

        val prefix = fuzzyKey.take(FUZZY_PREFIX_LENGTH)
        val upper = PrefixRange.upperBound(prefix) ?: return emptyList()

        val candidates = mutableListOf<Pair<Long, Int>>()
        pack.connection().prepare(
            "SELECT id, norm FROM entry WHERE fuzzy >= ? AND fuzzy < ? LIMIT ?",
        ).use { statement ->
            statement.bindText(1, prefix)
            statement.bindText(2, upper)
            statement.bindInt(3, FUZZY_CANDIDATES)
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
     * Texto libre dentro de las definiciones.
     *
     * Es una accion explicita del usuario, **nunca** se dispara mientras escribe: recorre un
     * indice mucho mas grande que el de lemas.
     */
    override suspend fun searchDefinitions(query: String, limit: Int): List<Suggestion> {
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

            // fts_def es contentless: solo devuelve rowids, que SON entry.id (D-011).
            val placeholders = ids.joinToString(",") { "?" }
            pack.connection().prepare(
                "SELECT id, headword, pos FROM entry WHERE id IN ($placeholders)",
            ).use { statement ->
                ids.forEachIndexed { index, id -> statement.bindLong(index + 1, id) }
                statement.collectSuggestions(MatchKind.DEFINITION)
            }
        }
    }

    /** El cuerpo de una entrada. Aca si se lee y descomprime el payload. */
    override suspend fun entry(entryId: Long): Entry? = withContext(dispatcher) {
        pack.connection().prepare(
            "SELECT headword, pos, payload FROM entry WHERE id = ?",
        ).use { statement ->
            statement.bindLong(1, entryId)
            if (!statement.step()) return@withContext null

            val body = PayloadCodec.decode(statement.getBlob(2), pack.payloadDictionary)
            Entry(
                packId = pack.metadata.packId,
                entryId = entryId,
                headword = statement.getText(0),
                // El pos de la columna manda sobre el del payload: es el que ordena la lista.
                partOfSpeech = statement.getTextOrNull(1) ?: body.partOfSpeech,
                senses = body.senses,
            )
        }
    }

    override fun close() {
        pack.close()
    }

    // ----------------------------------------------------------------- helpers

    private suspend fun SQLiteStatement.collectSuggestions(kind: MatchKind): List<Suggestion> {
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
                // Menor es mejor: la posicion dentro de su nivel, que ya viene ordenado por rank.
                score = position++,
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
        /** Si la cascada confiable devolvio menos que esto, se intenta el nivel tolerante. */
        const val FUZZY_TRIGGER: Int = 5

        /** Cuantos caracteres de la clave fuzzy definen el vecindario. */
        const val FUZZY_PREFIX_LENGTH: Int = 4

        /** Tope de candidatos a reordenar por distancia de edicion. */
        const val FUZZY_CANDIDATES: Int = 200

        /** Cuantos sobreviven al reordenamiento. */
        const val FUZZY_RESULTS: Int = 10

        /** Mas alla de esto ya no es un error de tipeo, es otra palabra. */
        const val MAX_EDIT_DISTANCE: Int = 2

        /**
         * Un hilo por pack, por `THREADSAFE=2`. `limitedParallelism(1)` sobre IO y no un
         * executor propio: reusa el pool del proceso en vez de sumarle un hilo.
         */
        fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

        /**
         * Convierte texto libre en una expresion MATCH de FTS5.
         *
         * Cada token va entre comillas para que lo que escriba el usuario **nunca** se
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
