package cl.fadiaz.dictionary.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import cl.fadiaz.dictionary.core.FuzzyProfile
import cl.fadiaz.dictionary.core.PackKind
import cl.fadiaz.dictionary.core.PackTier
import cl.fadiaz.dictionary.core.fuzzyProfileFor
import cl.fadiaz.dictionary.core.PackIntegrity
import cl.fadiaz.dictionary.core.PackMetadata
import cl.fadiaz.dictionary.core.PackRejection
import cl.fadiaz.dictionary.core.RankBasis
import cl.fadiaz.dictionary.core.PackSource
import cl.fadiaz.dictionary.core.PayloadCodec
import cl.fadiaz.dictionary.core.TextNormalizer

/**
 * A pack opened read-only.
 *
 * Deliberately thin: it opens, validates and exposes the connection. The queries live in the
 * layer above.
 *
 * Room is NOT used for this (D-039): `createFromFile()` copies the file into Room's directory,
 * duplicating tens of MB on a watch. Nor is the system SQLite (D-002): FTS5 is not guaranteed on
 * Android.
 */
class PackFile private constructor(
    val metadata: PackMetadata,
    val payloadDictionary: ByteArray,
    private val connection: SQLiteConnection,
) : AutoCloseable {

    /** For the layer above's queries, and for the tests. */
    fun connection(): SQLiteConnection = connection

    override fun close() {
        connection.close()
    }

    /**
     * A pack that cannot be used, and why.
     *
     * ⚠️ **It carries TWO things rather than one, and that is the difference from the earlier
     * version.** [rejection] is the reason as data: the dictionaries screen reads it to write a
     * localized line, and the memo reads it so the file is not tried again. `message` is still
     * the prose with the concrete values --what was declared, what was expected-- and it is
     * still **for `logcat`, not for the user**: it is what debugging needs and what nobody wants
     * to read on a watch.
     */
    class IncompatibleException(
        val rejection: PackRejection,
        message: String,
    ) : Exception(message)

    companion object {
        /** The schema version this app understands. Any other is rejected. */
        const val SUPPORTED_SCHEMA_VERSION: Int = 4

        /**
         * Opens a pack, or throws [IncompatibleException] saying why not.
         *
         * ⚠️ **Every check covers a failure that would otherwise be SILENT**, and that is the bar
         * for adding another: not "this could be wrong" but "if this is wrong, the user sees
         * incorrect or incomplete results and nothing tells them".
         *
         * In three rounds, cheapest first:
         *
         * 1. **`meta` alone** (0 ms): schema, required keys, `norm_version`, codec, and that the
         *    pack can be credited. It lives in [PackIntegrity], which the gate does cover.
         * 2. **The file's schema** (0 ms, [checkStructure]): the two indexes, and that no
         *    quedado la tabla de staging.
         * 3. **The content** ([checkContentAgainstMetadata] and [checkKeysAgainstASample]): that
         *    the rows are the ones `meta` promises and that the keys were computed right. It is
         *    the only part skipped when the memo says this same file already passed, because the
         *    pack es inmutable (D-001). Medido sobre el pack ingles: 5,7 ms + 14,7 ms.
         *
         * ⚠️ **All of them reject**, including the ones that merely degrade --a missing index
         * makes the search scan-- and that was a product decision taken against the
         * recommendation.
         * Ver [PackRejection] y D-217.
         */
        fun open(
            path: String,
            driver: BundledSQLiteDriver = BundledSQLiteDriver(),
            /**
             * Whether the key sample has to be recomputed (D-142).
             *
             * ⚠️ **The default is `true` and has to stay that way**: whoever does not know about
             * this gets the whole validation. It is switched off only when the caller can prove
             * that **this same file already passed under these same rules** -- the fingerprint
             * `PackVerification` incluye `NORM_VERSION` justo para eso.
             *
             * It switches off the sample and nothing else: `schema_version`, `norm_version`,
             * `payload_codec` and the dictionary's sha256 are always checked, because they are a
             * handful of
             * milisegundos y cubren fallas distintas.
             */
            verifyKeys: Boolean = true,
        ): PackFile {
            val connection = driver.open(path, SQLITE_OPEN_READONLY)
            try {
                // query_only is belt and braces: the file was already opened read-only, but it
                // puts the error on the statement rather than on the open, which is easier to
                // attribute.
                connection.execSQL("PRAGMA query_only = 1")
                // mmap helps random reads over flash; the cache is small because on a watch the
                // memory belongs to the system before it belongs to us.
                connection.execSQL("PRAGMA mmap_size = 8388608")
                connection.execSQL("PRAGMA cache_size = -2000")
                connection.execSQL("PRAGMA temp_store = MEMORY")

                val meta = readMeta(connection)
                // ⚠️ **Everything decided by looking at `meta` alone lives in `PackIntegrity`,
                // and the ORDER is part of what lives there.** It used to be here, and it was
                // wrong: it required schema 4's mandatory keys **before** looking at
                // `schema_version`, so a schema 3 pack --which carries no `langs` and no
                // `fuzzy_profiles` because
                // nacieron despues-- se rechazaba como "metadatos incompletos" en vez de "hecho
                // for another version of the app". Both reject; the second is the useful one.
                //
                // Mudarlo ademas lo puso bajo el gate: `:dict-data` se prueba en dispositivo y
                // `:dict-core` en la JVM (D-072).
                PackIntegrity.checkMeta(
                    meta,
                    supportedSchema = SUPPORTED_SCHEMA_VERSION,
                    normVersion = TextNormalizer.NORM_VERSION,
                    codecId = PayloadCodec.CODEC_ID,
                )?.let { throw IncompatibleException(it.rejection, it.detail) }

                val metadata = try {
                    parseMetadata(meta)
                } catch (error: Exception) {
                    // Safety net: `checkMeta` already checked what `parseMetadata` requires, so
                    // reaching here means the two drifted apart. It is reported as invalid
                    // metadata --which is what it is-- and not as a damaged file.
                    throw IncompatibleException(
                        PackRejection.METADATA,
                        "meta no se pudo interpretar: ${error.message}",
                    )
                }

                checkStructure(connection)

                if (verifyKeys) {
                    checkContentAgainstMetadata(connection, metadata)
                    checkKeysAgainstASample(connection, metadata)
                }

                val dictionary = hexToBytes(meta.getValue("payload_dict"))
                val declared = meta.getValue("payload_dict_sha256")
                if (PayloadCodec.dictionaryDigest(dictionary) != declared) {
                    throw IncompatibleException(
                        PackRejection.PAYLOAD_DICTIONARY,
                        "payload_dict_sha256 no corresponde al diccionario guardado: deflate no " +
                            "avisaria y las entradas saldrian corruptas",
                    )
                }

                return PackFile(metadata, dictionary, connection)
            } catch (error: Throwable) {
                connection.close()
                throw error
            }
        }

        /**
         * What the schema can be asked without touching a single row. Measured: **0.0 ms** over
         * the 306.8 MB English pack, so it runs **always**, including when the memo says this
         * file already passed.
         *
         * - **The two indexes.** Without `idx_entry_norm` the prefix search scans 956,150 rows
         *   instead of walking a range of the covering index (D-012). It does not give a wrong
         *   answer: it gives the same one late, and on a watch that is battery.
         * - **The staging table.** `PackBuilder` writes there during the first pass and drops it
         *   at the end; its still being present means the build was cut in half, and a half-built
         *   pack **opens without error and returns fewer words than it holds**.
         */
        private fun checkStructure(connection: SQLiteConnection) {
            val objetos = mutableSetOf<String>()
            connection.prepare(
                "SELECT name FROM sqlite_master WHERE name IN " +
                    "('idx_entry_norm', 'idx_entry_fuzzy', 'staging')",
            ).use { statement ->
                while (statement.step()) objetos += statement.getText(0)
            }
            if ("staging" in objetos) {
                throw IncompatibleException(
                    PackRejection.HALF_BUILT,
                    "quedo la tabla de staging: el pack se construyo a medias",
                )
            }
            val faltan = listOf("idx_entry_norm", "idx_entry_fuzzy").filterNot { it in objetos }
            if (faltan.isNotEmpty()) {
                throw IncompatibleException(
                    PackRejection.MISSING_INDEX,
                    "faltan indices: ${faltan.joinToString()}; la busqueda escanearia la tabla",
                )
            }
        }

        /**
         * That the content is the one `meta` promises. It runs **once per file**, alongside the
         * key sample, because the pack is immutable (D-001).
         *
         * Medido sobre el pack ingles real (956.150 entradas, 306,8 MB): **5,7 ms en total**,
         * against the 14.7 ms the sample's 64 reads already cost. The expensive checks stayed in
         * `verify_pack.py`, where they run at build time and not on the watch: unique `uid` is
         * **377 ms** and the full orphan sweep **672 ms**.
         *
         * - **`entry_count` against the real rows** (4.2 ms). This is what catches a truncated
         *   file: it opens without error and returns fewer words than it claims to hold.
         * - **`fts_def`, one row per entry** (1.4 ms). Its `rowid` **is** `entry.id` (D-011).
         *   Misaligned, searching by definition does not return fewer results: it returns
         *   **different** ones, which is worse, because they read as correct.
         * - **Empty `norm`** (0.0 ms, the index answers it). An entry with an empty key is
         *   reachable neither by prefix nor by the tolerant rung: it is in the file and does not
         *   exist.
         * - **Orphans in `form` and `trans`** (0.1 ms over 64 rows). An inflection pointing at an
         *   entry that is not there is a search that finds nothing. A sample is looked at rather
         *   than the whole table because the full sweep costs 672 ms; it bounds the damage rather
         *   than removing it, which is the same treatment D-142 gave the keys.
         */
        private fun checkContentAgainstMetadata(
            connection: SQLiteConnection,
            metadata: PackMetadata,
        ) {
            val filas = countOf(connection, "SELECT COUNT(*) FROM entry")
            if (filas != metadata.entryCount.toLong()) {
                throw IncompatibleException(
                    PackRejection.ENTRY_COUNT,
                    "meta.entry_count dice ${metadata.entryCount} y hay $filas filas: el archivo " +
                        "esta truncado o se construyo a medias",
                )
            }
            val indexadas = countOf(connection, "SELECT COUNT(*) FROM fts_def_docsize")
            if (indexadas != filas) {
                throw IncompatibleException(
                    PackRejection.FTS_MISALIGNED,
                    "fts_def tiene $indexadas filas para $filas entradas: la busqueda por " +
                        "definicion apuntaria a otras entradas (D-011)",
                )
            }
            val sinClave = countOf(connection, "SELECT COUNT(*) FROM entry WHERE norm = ''")
            if (sinClave > 0) {
                throw IncompatibleException(
                    PackRejection.EMPTY_KEY,
                    "$sinClave entradas con norm vacio: estan en el archivo y no se alcanzan",
                )
            }
            for (tabla in listOf("form", "trans")) {
                val huerfanas = countOf(
                    connection,
                    "SELECT COUNT(*) FROM (SELECT entry_id FROM $tabla LIMIT $ORPHAN_SAMPLE_SIZE) t" +
                        " WHERE NOT EXISTS (SELECT 1 FROM entry e WHERE e.id = t.entry_id)",
                )
                if (huerfanas > 0) {
                    throw IncompatibleException(
                        PackRejection.ORPHAN_ROW,
                        "$huerfanas filas de $tabla apuntan a entradas que no existen",
                    )
                }
            }
        }

        /** Cuantas filas de `$tabla` se miran para buscar huerfanas. Ver [checkContentAgainstMetadata]. */
        private const val ORPHAN_SAMPLE_SIZE = 64

        private fun countOf(connection: SQLiteConnection, sql: String): Long =
            connection.prepare(sql).use { statement ->
                if (statement.step()) statement.getLong(0) else 0L
            }

        /**
         * How many entries are recomputed on open. 64 reads by rowid, not a scan.
         *
         * With 64 samples spread out, a pack whose `norm()` differs in anything systematic
         * --another Unicode version, lowercasing done differently, NFC instead of NFD-- fails
         * with probability essentially 1. One that differs in a single rare character may pass,
         * and that is acceptable: this bounds the damage, it does not remove it. What removes it
         * is `verify_pack.py`, which recomputes **every** row and runs at build time.
         */
        private const val KEY_SAMPLE_SIZE = 64

        /**
         * Recomputes `norm()` and `fuzzy()` over a sample and compares them with what the pack
         * carries.
         *
         * ⚠️ **It turns a declaration into a proof, and that is why it exists.** `norm_version` is
         * a number the pack gives itself: a community-built pack can declare the right version
         * and have built its keys under other rules --another ICU version, locale-dependent
         * lowercasing, NFC where NFD belongs-- and then **words are missing**, with no exception,
         * no log and nothing in the stack trace. It is this repository's central failure mode
         * (see `CLAUDE.md`), and until now only the builder covered it.
         *
         * It costs 64 reads by rowid on open, once per pack. The rowids are spread along the
         * table on purpose: a pack that is correct only in its first rows --what happens if
         * somebody built half of it with one version and half with another-- is caught all the
         * same.
         */
        private fun checkKeysAgainstASample(connection: SQLiteConnection, metadata: PackMetadata) {
            val total = metadata.entryCount
            if (total <= 0) return
            val step = maxOf(1, total / KEY_SAMPLE_SIZE)
            // ⚠️ **The profile comes from the ROW and not from the pack**, because in a
            // bidirectional pack the English entries fold with the English profile and the
            // Spanish ones with the Spanish. Checking both with a single profile would give
            // false positives over half the pack -- and this check exists to reject incompatible
            // packs, so a false positive leaves the user with no dictionary.
            connection.prepare(
                "SELECT headword, norm, fuzzy, lang FROM entry WHERE id = ?",
            ).use { statement ->
                var id = 1L
                while (id <= total) {
                    statement.reset()
                    statement.bindLong(1, id)
                    if (statement.step()) {
                        val headword = statement.getText(0)
                        val storedNorm = statement.getText(1)
                        val expectedNorm = TextNormalizer.norm(headword)
                        if (storedNorm != expectedNorm) {
                            throw IncompatibleException(
                                PackRejection.KEYS,
                                "entry.norm no coincide con norm() en '$headword': el pack dice " +
                                    "'$storedNorm' y esta app calcula '$expectedNorm'. " +
                                    "Faltarian palabras en los resultados sin ningun error",
                            )
                        }
                        // `fuzzy` may be NULL: those are the entries deliberately left out of
                        // the tolerant rung, and `verify_pack.py` counts them without alarm.
                        if (!statement.isNull(2)) {
                            val storedFuzzy = statement.getText(2)
                            val expectedFuzzy = TextNormalizer.fuzzy(
                                headword, metadata.fuzzyProfileFor(statement.getText(3)))
                            if (storedFuzzy != expectedFuzzy) {
                                throw IncompatibleException(
                                    PackRejection.KEYS,
                                    "entry.fuzzy no coincide con fuzzy() en '$headword': el pack " +
                                        "dice '$storedFuzzy' y esta app calcula '$expectedFuzzy'",
                                )
                            }
                        }
                    }
                    id += step
                }
            }
        }

        private fun readMeta(connection: SQLiteConnection): Map<String, String> {
            val out = mutableMapOf<String, String>()
            connection.prepare("SELECT key, value FROM meta").use { statement ->
                while (statement.step()) {
                    out[statement.getText(0)] = statement.getText(1)
                }
            }
            return out
        }

        private fun parseMetadata(meta: Map<String, String>): PackMetadata = PackMetadata(
            packId = meta.getValue("pack_id"),
            schemaVersion = meta.getValue("schema_version").toInt(),
            normVersion = meta.getValue("norm_version").toInt(),
            kind = PackKind.fromId(meta.getValue("kind")),
            name = meta.getValue("name"),
            // `meta[...]` and not `getValue`: a pack built before D-125 does not carry it and
            // has to keep opening.
            description = meta["description"],
            // ⚠️ **`langs` and not `lang_src`: languages are PEERS.** A bidirectional pack has
            // entries from both and neither is the primary one.
            langs = parseList(meta.getValue("langs")),
            fuzzyProfiles = parseList(meta.getValue("fuzzy_profiles"))
                .map { FuzzyProfile.fromId(it) },
            tier = PackTier.fromId(meta["tier"]),
            // `toIntOrNull` and not `toInt`: a pack declaring anything at all degrades to "no
            // band", which is how every pack behaved before D-185.
            rankSignalBoundary = meta["rank_signal_boundary"]?.toIntOrNull(),
            // `meta[...]`: only a pack that declares translations carries it.
            // ⚠️ **Falling back to `lang_dst` for a BILINGUAL pack older than the key.** A
            // bilingual pack translates by definition --its glosses are already in the target
            // language-- so inferring it is safe, and without this a pack built before D-183
            // would stop being offered for translation despite being exactly that.
            // ⚠️ **Inferred from the SECOND declared language when the pack does not say.** A
            // bilingual pack translates by definition --its glosses are already in the other
            // language-- so inferring it is safe, and without this a pack that does not declare
            // the capability would stop being offered for translation despite being exactly
            // that.
            translationsTo = meta["translations_to"]
                ?: parseList(meta["langs"]).getOrNull(1)
                    ?.takeIf { meta["kind"] == PackKind.BILINGUAL.id },
            // `fromId` does not throw on an unknown id: a newer pack may carry a basis this
            // version cannot read, and that degrades well.
            rankBasis = RankBasis.fromId(meta["rank_basis"]),
            // `meta[...]` again: no pack today carries it, and the format has no migrations
            // (D-001) -- but that applies to `schema_version`; a new, additive key is exactly
            // what the tolerance exists to support.
            subsetOf = meta["subset_of"],
            entryCount = meta.getValue("entry_count").toInt(),
            dataVersion = meta.getValue("data_version").toLong(),
            license = meta.getValue("license"),
            attribution = meta.getValue("attribution"),
            // `meta[...]` and not `getValue`: a pack older than D-138 does not carry it and has
            // to keep opening. `parse` never throws.
            sources = PackSource.parse(meta["sources"]),
        )

        /** `"es,en"` -> `["es", "en"]`. Vacios y espacios fuera; el orden se conserva. */
        private fun parseList(crudo: String?): List<String> =
            crudo.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

        private fun hexToBytes(hex: String): ByteArray =
            ByteArray(hex.length / 2) { index ->
                hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
    }
}
