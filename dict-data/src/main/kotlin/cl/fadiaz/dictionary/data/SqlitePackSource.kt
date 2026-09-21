package cl.fadiaz.dictionary.data

import androidx.sqlite.SQLiteStatement
import cl.fadiaz.dictionary.core.DictionarySource
import cl.fadiaz.dictionary.core.EditDistance
import cl.fadiaz.dictionary.core.Entry
import cl.fadiaz.dictionary.core.EntrySummary
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

            // La deduplicacion va DESPUES de ordenar y al final de la cascada, no dentro de un
            // nivel: `accumulated` esta indexado por entryId, asi que dos entradas distintas con
            // el mismo lema y el mismo pos sobreviven las dos. Fundirlas antes de ordenar
            // elegiria una al azar; hacerlo despues conserva la de mejor nivel y mejor score.
            //
            // Que no alcanza con deduplicar en byPrefix lo encontro un test: el nivel tolerante
            // volvia a meter la entrada que el prefijo ya habia fundido.
            accumulated.values
                .sortedWith(compareBy({ it.matchKind.ordinal }, { it.score }))
                .distinctBy { it.headword to it.partOfSpeech }
                .take(limit)
        }
    }

    /**
     * Prefijo del lema: el primer peldano, y el unico que corre cuando alcanza.
     *
     * ⚠️ **Decia "el 95% del uso" y la medicion lo desmintio.** Es cierto MIENTRAS SE ESCRIBE
     * --37 de 70 prefijos llenan el limite-- y falso para la busqueda que de verdad corre: D-128
     * no busca con el teclado abierto, busca una vez al cerrarlo y sobre la palabra **completa**,
     * que es justo el caso donde el prefijo devuelve pocas filas y la cascada sigue. Medido sobre
     * el pack español, el promedio de una busqueda real es **3,0 peldanos**, no 1. No cambia nada
     * de este metodo; cambia lo que alguien concluye leyendolo. Ver `docs/bateria.md`.
     *
     * Sale integra del covering index, sin tocar la tabla y sin leer un solo payload. Se usa un
     * rango explicito y no `LIKE 'x%'` porque LIKE solo se optimiza a range scan si
     * `case_sensitive_like` esta en el valor correcto, y ante la duda SQLite hace full scan
     * (D-012).
     */
    private suspend fun byPrefix(normalized: String, limit: Int): List<Suggestion> {
        val upper = PrefixRange.upperBound(normalized)
        // El rango sale del covering index; el orden NO, y es deliberado (D-068). `norm` es
        // alfabetico y ordenar por el entierra la palabra comun debajo de las raras que
        // comparten prefijo. El CASE sube la coincidencia exacta, que es lo que el usuario
        // acaba de escribir entero y nunca puede faltar.
        val order = " ORDER BY CASE WHEN norm = ? THEN 0 ELSE 1 END, rank, norm LIMIT ?"
        val sql = if (upper != null) {
            "SELECT id, headword, pos FROM entry WHERE norm >= ? AND norm < ?" + order
        } else {
            "SELECT id, headword, pos FROM entry WHERE norm >= ?" + order
        }

        val rows = pack.connection().prepare(sql).use { statement ->
            var i = 1
            statement.bindText(i++, normalized)
            if (upper != null) statement.bindText(i++, upper)
            statement.bindText(i++, normalized)
            statement.bindInt(i, limit * PREFIX_OVERFETCH)
            statement.collectSuggestions(MatchKind.PREFIX)
        }

        // Se pide de mas y se deduplica aca, no con GROUP BY: medido sobre el pack real, el
        // GROUP BY cuesta 7,9 ms p95 con un prefijo de una letra contra 1,8 ms de esta forma,
        // y ademas no saca los duplicados que se ven, que difieren en pos.
        return rows.distinctBy { it.headword to it.partOfSpeech }.take(limit)
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

            // El orden que FTS5 acaba de calcular se guarda ANTES de perderlo.
            //
            // La consulta de abajo es `WHERE id IN (...)`, que SQLite resuelve por el indice del
            // PK y devuelve en orden de **rowid**, no de relevancia. Sin esto, `collectSuggestions`
            // asignaria `score` sobre ese orden y el ranking de bm25 quedaria calculado y tirado:
            // la definicion que mejor coincide no encabeza. Es la misma clase de bug que hacia que
            // el prefijo "per" no devolviera "perro".
            //
            // Se reordena en memoria y no con un JOIN porque el JOIN cambia el plan de consulta, y
            // en este repo un plan no se cambia sin medirlo (D-012). Son 30 filas como maximo.
            val posicionEnFts = ids.withIndex().associate { (posicion, id) -> id to posicion }

            // fts_def es contentless: solo devuelve rowids, que SON entry.id (D-011).
            val placeholders = ids.joinToString(",") { "?" }
            pack.connection().prepare(
                "SELECT id, headword, pos FROM entry WHERE id IN ($placeholders)",
            ).use { statement ->
                ids.forEachIndexed { index, id -> statement.bindLong(index + 1, id) }
                statement.collectSuggestions(MatchKind.DEFINITION)
                    .map { it.copy(score = posicionEnFts.getValue(it.entryId)) }
                    .sortedBy { it.score }
            }
        }
    }

    /** El cuerpo de una entrada. Aca si se lee y descomprime el payload. */
    override suspend fun entry(entryId: Long): Entry? = withContext(dispatcher) {
        pack.connection().prepare(
            "SELECT headword, pos, payload, uid FROM entry WHERE id = ?",
        ).use { statement ->
            statement.bindLong(1, entryId)
            if (!statement.step()) return@withContext null

            val body = PayloadCodec.decode(statement.getBlob(2), pack.payloadDictionary)
            Entry(
                packId = pack.metadata.packId,
                entryId = entryId,
                // La fila ya se leyo entera para traer el payload, asi que el uid sale gratis:
                // es justo el momento en que la composicion entre packs lo necesita.
                uid = statement.getLong(3),
                headword = statement.getText(0),
                // El pos de la columna manda sobre el del payload: es el que ordena la lista.
                partOfSpeech = statement.getTextOrNull(1) ?: body.partOfSpeech,
                senses = body.senses,
                wordTranslations = body.wordTranslations,
            )
        }
    }

    override suspend fun resolveHeadwords(norms: Set<String>): Map<String, Long> {
        if (norms.isEmpty()) return emptyMap()
        val claves = norms.take(MAX_PALABRAS_POR_CONSULTA)
        return withContext(dispatcher) {
            val huecos = claves.joinToString(",") { "?" }
            // SIN `ORDER BY rank`, y es deliberado: ordenar globalmente sobre un `IN` obliga a
            // SQLite a un TEMP B-TREE y la consulta deja de servirse del covering index (D-012,
            // y es el mismo efecto que midio D-063). El mejor rank se elige aca abajo, sobre las
            // pocas filas que devuelve una glosa: con dos entradas por clave no hay nada que
            // ordenar que valga una tabla temporal.
            pack.connection().prepare(
                "SELECT norm, id, rank FROM entry WHERE norm IN ($huecos)",
            ).use { statement ->
                claves.forEachIndexed { indice, clave -> statement.bindText(indice + 1, clave) }
                val context: CoroutineContext = currentCoroutineContext()
                val mejorPorClave = HashMap<String, Pair<Long, Int>>()
                while (statement.step()) {
                    context.ensureActive()
                    val clave = statement.getText(0)
                    val id = statement.getLong(1)
                    val rank = statement.getInt(2)
                    val actual = mejorPorClave[clave]
                    // Menor rank es mas comun: "arbol" con tilde (45) le gana a la variante sin
                    // tilde (900), que es la misma regla con la que se ordena la lista (D-068).
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

        /**
         * Cuantas filas se piden por cada resultado que se va a mostrar, para que la
         * deduplicacion no deje la lista corta.
         *
         * 3 sale de medir el pack real: con el prefijo mas productivo de una letra (22.358
         * filas), pedir 90 y deduplicar en memoria deja 30 lemas distintos y cuesta 1,8 ms
         * p95 en escritorio. El numero del reloj falta: es lo que O-1 existe para dar.
         */
        const val PREFIX_OVERFETCH: Int = 3

        /**
         * Tope de claves por consulta al resolver las palabras de una glosa.
         *
         * No es una optimizacion sino un limite duro: cada clave es un parametro enlazado y
         * SQLite tiene un maximo. La glosa mas larga medida en el pack real tiene bastante menos
         * que esto, asi que el tope no recorta nada real; existe para que un texto anomalo
         * --un ejemplo de 917 caracteres colado donde va una glosa-- falle recortando y no
         * tirando.
         */
        const val MAX_PALABRAS_POR_CONSULTA: Int = 64

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
