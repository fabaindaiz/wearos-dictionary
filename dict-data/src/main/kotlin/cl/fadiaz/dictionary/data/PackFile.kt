package cl.fadiaz.dictionary.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import cl.fadiaz.dictionary.core.FuzzyProfile
import cl.fadiaz.dictionary.core.PackKind
import cl.fadiaz.dictionary.core.PackMetadata
import cl.fadiaz.dictionary.core.RankBasis
import cl.fadiaz.dictionary.core.PackSource
import cl.fadiaz.dictionary.core.PayloadCodec
import cl.fadiaz.dictionary.core.TextNormalizer

/**
 * Un pack abierto en modo solo lectura.
 *
 * Es deliberadamente delgado: abre, valida y expone la conexion. Las consultas viven en la capa
 * de arriba, que todavia no existe -- ver docs/roadmap.md.
 *
 * NO se usa Room para esto (D-039): `createFromFile()` copia el archivo al directorio de Room,
 * duplicando decenas de MB en un reloj. Y no se usa el SQLite del sistema (D-002): FTS5 no esta
 * garantizado en Android.
 */
class PackFile private constructor(
    val metadata: PackMetadata,
    val payloadDictionary: ByteArray,
    private val connection: SQLiteConnection,
) : AutoCloseable {

    /** Para las consultas de la capa de arriba y para los tests. */
    fun connection(): SQLiteConnection = connection

    override fun close() {
        connection.close()
    }

    /** Un pack que no se puede usar, y por que. El mensaje es para un log, no para el usuario. */
    class IncompatibleException(message: String) : Exception(message)

    companion object {
        /** Version de esquema que esta app entiende. Otra distinta se rechaza. */
        const val SUPPORTED_SCHEMA_VERSION: Int = 3

        /**
         * Abre y valida un pack.
         *
         * Las tres validaciones no son defensivas de mas: cada una cubre una falla que de otro
         * modo seria SILENCIOSA.
         *
         * - `schema_version` distinta: las consultas apuntarian a columnas que cambiaron.
         * - `norm_version` distinta: el pack esta indexado con otras reglas de normalizacion, y
         *   devolveria MENOS resultados de los que tiene, sin ningun error (D-006).
         * - `payload_dict_sha256`: deflate NO detecta un diccionario precargado equivocado.
         *   Descomprime sin lanzar nada y devuelve texto corrupto (D-008).
         * - **Y una MUESTRA de las claves recalculada** (D-142): las tres de arriba son
         *   declaraciones, y un pack de la comunidad puede declararlas bien y tener las claves
         *   mal. Ver [checkKeysAgainstASample].
         */
        fun open(
            path: String,
            driver: BundledSQLiteDriver = BundledSQLiteDriver(),
            /**
             * Si hay que recalcular la muestra de claves (D-142).
             *
             * ⚠️ **El default es `true` y tiene que seguir siéndolo**: quien no sepa de esto
             * obtiene la validación completa. Sólo se apaga cuando el llamador puede demostrar
             * que **este mismo archivo ya la pasó con estas mismas reglas** — la huella de
             * `PackVerification` incluye `NORM_VERSION` justo para eso.
             *
             * Apaga la muestra y nada más: `schema_version`, `norm_version`, `payload_codec` y
             * el sha256 del diccionario se comprueban siempre, porque son un puñado de
             * milisegundos y cubren fallas distintas.
             */
            verifyKeys: Boolean = true,
        ): PackFile {
            val connection = driver.open(path, SQLITE_OPEN_READONLY)
            try {
                // query_only es cinturon y tiradores: el archivo ya se abrio read-only, pero
                // deja el error en el statement y no en el open, que es mas facil de atribuir.
                connection.execSQL("PRAGMA query_only = 1")
                // mmap ayuda a las lecturas aleatorias sobre flash; el cache chico porque en un
                // reloj la memoria es del sistema antes que nuestra.
                connection.execSQL("PRAGMA mmap_size = 8388608")
                connection.execSQL("PRAGMA cache_size = -2000")
                connection.execSQL("PRAGMA temp_store = MEMORY")

                val meta = readMeta(connection)
                val metadata = parseMetadata(meta)

                if (metadata.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
                    throw IncompatibleException(
                        "schema_version ${metadata.schemaVersion}, esta app entiende " +
                            "$SUPPORTED_SCHEMA_VERSION",
                    )
                }
                if (metadata.normVersion != TextNormalizer.NORM_VERSION) {
                    throw IncompatibleException(
                        "norm_version ${metadata.normVersion} != ${TextNormalizer.NORM_VERSION}: " +
                            "el pack esta indexado con otras reglas y devolveria menos resultados",
                    )
                }
                if (meta["payload_codec"] != PayloadCodec.CODEC_ID) {
                    throw IncompatibleException(
                        "payload_codec '${meta["payload_codec"]}', esta app lee " +
                            "'${PayloadCodec.CODEC_ID}'",
                    )
                }

                if (verifyKeys) checkKeysAgainstASample(connection, metadata)

                val dictionary = hexToBytes(meta.getValue("payload_dict"))
                val declared = meta.getValue("payload_dict_sha256")
                if (PayloadCodec.dictionaryDigest(dictionary) != declared) {
                    throw IncompatibleException(
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
         * Cuantas entradas se recalculan al abrir. 64 lecturas por rowid, no un scan.
         *
         * Con 64 muestras repartidas, un pack cuyo `norm()` difiera en algo sistematico --otra
         * version de Unicode, minusculas de otra manera, NFC en vez de NFD-- se cae con
         * probabilidad practicamente 1. Uno que difiera en un solo caracter raro puede pasar, y
         * eso es aceptable: esto acota el daño, no lo elimina. El que lo elimina es
         * `verify_pack.py`, que recalcula **todas** las filas, y corre al construir.
         */
        private const val KEY_SAMPLE_SIZE = 64

        /**
         * Recalcula `norm()` y `fuzzy()` sobre una muestra y las compara con lo que el pack trae.
         *
         * ⚠️ **Convierte una declaracion en una prueba, y por eso existe.** `norm_version` es un
         * numero que el pack se pone a si mismo: un pack generado por la comunidad puede
         * declarar la version correcta y haber construido las claves con otras reglas --otra
         * version de ICU, minusculas locale-dependientes, NFC donde va NFD-- y entonces **faltan
         * palabras**, sin excepcion, sin log y sin nada en el stack trace. Es el modo de falla
         * central de este repo (ver `CLAUDE.md`), y hasta ahora solo lo cubria el builder.
         *
         * Cuesta 64 lecturas por rowid al abrir, una sola vez por pack. Los rowids van repartidos
         * a lo largo de la tabla a proposito: un pack correcto solo en las primeras filas --lo
         * que pasa si alguien construyo la mitad con una version y la mitad con otra-- se agarra
         * igual.
         */
        private fun checkKeysAgainstASample(connection: SQLiteConnection, metadata: PackMetadata) {
            val total = metadata.entryCount
            if (total <= 0) return
            val step = maxOf(1, total / KEY_SAMPLE_SIZE)
            val profile = metadata.fuzzyProfile
            connection.prepare(
                "SELECT headword, norm, fuzzy FROM entry WHERE id = ?",
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
                                "entry.norm no coincide con norm() en '$headword': el pack dice " +
                                    "'$storedNorm' y esta app calcula '$expectedNorm'. " +
                                    "Faltarian palabras en los resultados sin ningun error",
                            )
                        }
                        // `fuzzy` puede ser NULL: son las entradas que quedan fuera del nivel
                        // tolerante a proposito, y `verify_pack.py` las cuenta sin alarmarse.
                        if (!statement.isNull(2)) {
                            val storedFuzzy = statement.getText(2)
                            val expectedFuzzy = TextNormalizer.fuzzy(headword, profile)
                            if (storedFuzzy != expectedFuzzy) {
                                throw IncompatibleException(
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
            // `meta[...]` y no `getValue`: un pack construido antes de D-125 no la trae y
            // tiene que seguir abriendo.
            description = meta["description"],
            langSource = meta.getValue("lang_src"),
            langTarget = meta["lang_dst"],
            // `meta[...]`: la trae sólo un pack que declare traducciones.
            // ⚠️ **Con respaldo a `lang_dst` para un pack BILINGUE anterior a la clave.** Un
            // bilingue traduce por definicion --sus glosas ya estan en el idioma destino-- asi
            // que inferirlo es seguro, y sin esto un pack construido antes de D-183 dejaria de
            // ofrecerse para traducir aunque sea exactamente lo que hace.
            translationsTo = meta["translations_to"]
                ?: meta["lang_dst"]?.takeIf { meta["kind"] == PackKind.BILINGUAL.id },
            // `fromId` no lanza ante un id desconocido: un pack mas nuevo puede
            // traer una base que esta version no sabe leer, y eso degrada bien.
            rankBasis = RankBasis.fromId(meta["rank_basis"]),
            // `meta[...]` otra vez: ningun pack de hoy la trae, y el formato no tiene
            // migraciones (D-001) pero eso aplica a `schema_version`; una clave nueva y aditiva
            // es justo lo que la tolerancia existe para soportar.
            subsetOf = meta["subset_of"],
            fuzzyProfile = FuzzyProfile.fromId(meta["fuzzy_profile"]),
            entryCount = meta.getValue("entry_count").toInt(),
            dataVersion = meta.getValue("data_version").toLong(),
            license = meta.getValue("license"),
            attribution = meta.getValue("attribution"),
            // `meta[...]` y no `getValue`: un pack anterior a D-138 no la trae y tiene que
            // seguir abriendo. `parse` nunca lanza.
            sources = PackSource.parse(meta["sources"]),
        )

        private fun hexToBytes(hex: String): ByteArray =
            ByteArray(hex.length / 2) { index ->
                hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
    }
}
