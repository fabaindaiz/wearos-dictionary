package cl.fadiaz.dictionary.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import cl.fadiaz.dictionary.core.FuzzyProfile
import cl.fadiaz.dictionary.core.PackKind
import cl.fadiaz.dictionary.core.PackMetadata
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
         */
        fun open(path: String, driver: BundledSQLiteDriver = BundledSQLiteDriver()): PackFile {
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
            fuzzyProfile = FuzzyProfile.fromId(meta["fuzzy_profile"]),
            entryCount = meta.getValue("entry_count").toInt(),
            dataVersion = meta.getValue("data_version").toInt(),
            license = meta.getValue("license"),
            attribution = meta.getValue("attribution"),
        )

        private fun hexToBytes(hex: String): ByteArray =
            ByteArray(hex.length / 2) { index ->
                hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
    }
}
