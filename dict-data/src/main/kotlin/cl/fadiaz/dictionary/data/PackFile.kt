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

    /**
     * Un pack que no se puede usar, y por que.
     *
     * ⚠️ **Lleva DOS cosas y no una, y esa es la diferencia con la version anterior.**
     * [rejection] es el motivo como dato: lo lee la pantalla de diccionarios para escribir una
     * linea traducida, y el memo para no volver a probar el archivo. `message` sigue siendo la
     * prosa con los valores concretos --que declaraba, que se esperaba-- y sigue siendo **para
     * `logcat`, no para el usuario**: es lo que se necesita para depurar y lo que nadie quiere
     * leer en un reloj.
     */
    class IncompatibleException(
        val rejection: PackRejection,
        message: String,
    ) : Exception(message)

    companion object {
        /** Version de esquema que esta app entiende. Otra distinta se rechaza. */
        const val SUPPORTED_SCHEMA_VERSION: Int = 4

        /**
         * Abre un pack, o lanza [IncompatibleException] diciendo por que no.
         *
         * ⚠️ **Cada comprobacion cubre una falla que de otro modo seria SILENCIOSA**, y esa es
         * la vara para agregar una nueva: no "esto podria estar mal" sino "si esto esta mal, el
         * usuario ve resultados incorrectos o incompletos y nada se lo dice".
         *
         * En tres tandas, de mas barata a mas cara:
         *
         * 1. **Solo `meta`** (0 ms): esquema, claves obligatorias, `norm_version`, codec y que
         *    el pack se pueda acreditar. Vive en [PackIntegrity], que el gate si cubre.
         * 2. **El esquema del archivo** (0 ms, [checkStructure]): los dos indices y que no haya
         *    quedado la tabla de staging.
         * 3. **El contenido** ([checkContentAgainstMetadata] y [checkKeysAgainstASample]): que
         *    las filas sean las que `meta` promete y que las claves esten bien calculadas. Es lo
         *    unico que se saltea cuando el memo dice que este mismo archivo ya paso, porque el
         *    pack es inmutable (D-001). Medido sobre el pack ingles: 5,7 ms + 14,7 ms.
         *
         * ⚠️ **Todas rechazan**, tambien las que solo degradan --un indice faltante hace que la
         * busqueda escanee-- y eso fue una decision de producto tomada contra la recomendacion.
         * Ver [PackRejection] y D-217.
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
                // ⚠️ **Todo lo que se decide mirando solo `meta` vive en `PackIntegrity`, y el
                // ORDEN es parte de lo que vive ahi.** Aca estaba antes, y estaba mal: exigia
                // las claves obligatorias del esquema 4 **antes** de mirar `schema_version`, asi
                // que un pack del esquema 3 --que no trae `langs` ni `fuzzy_profiles` porque
                // nacieron despues-- se rechazaba como "metadatos incompletos" en vez de "hecho
                // para otra version de la app". Los dos rechazan; el segundo es el que sirve.
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
                    // Red de seguridad: `checkMeta` ya comprobo lo que `parseMetadata` exige, asi
                    // que llegar aca significa que los dos se desincronizaron. Se reporta como
                    // metadata invalida --que es lo que es-- y no como archivo dañado.
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
         * Lo que se puede preguntarle al esquema sin tocar una sola fila. Medido: **0,0 ms**
         * sobre el pack ingles de 306,8 MB, asi que corre **siempre**, tambien cuando el memo
         * dice que este archivo ya paso.
         *
         * - **Los dos indices.** Sin `idx_entry_norm` la busqueda por prefijo escanea 956.150
         *   filas en vez de recorrer un rango del indice de cobertura (D-012). No da un
         *   resultado equivocado: da el mismo tarde, y en un reloj eso es bateria.
         * - **La tabla de staging.** `PackBuilder` escribe ahi durante la primera pasada y la
         *   borra al terminar; que siga existiendo significa que el build se corto a la mitad, y
         *   un pack a medias **abre sin error y devuelve menos palabras de las que tiene**.
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
         * Que el contenido sea el que `meta` promete. Corre **una vez por archivo**, junto a la
         * muestra de claves, porque el pack es inmutable (D-001).
         *
         * Medido sobre el pack ingles real (956.150 entradas, 306,8 MB): **5,7 ms en total**,
         * contra los 14,7 ms que ya costaban las 64 lecturas de la muestra. Lo caro se quedo en
         * `verify_pack.py`, donde corre al construir y no en el reloj: `uid` unico son **377 ms**
         * y los huerfanos completos **672 ms**.
         *
         * - **`entry_count` contra las filas reales** (4,2 ms). Es lo que agarra un archivo
         *   truncado: se abre sin error y devuelve menos palabras de las que dice tener.
         * - **`fts_def` una fila por entrada** (1,4 ms). Su `rowid` **es** `entry.id` (D-011).
         *   Desalineados, buscar por definicion no devuelve menos resultados: devuelve **otros**,
         *   que es peor, porque se leen como correctos.
         * - **`norm` vacio** (0,0 ms, lo contesta el indice). Una entrada con la clave vacia no
         *   se alcanza por prefijo ni por el nivel tolerante: esta en el archivo y no existe.
         * - **Huerfanos de `form` y `trans`** (0,1 ms sobre 64 filas). Una flexion que apunta a
         *   una entrada que no esta es una busqueda que no encuentra nada. Se mira una muestra y
         *   no la tabla entera porque completa cuesta 672 ms; acota el daño, no lo elimina, que
         *   es el mismo trato que D-142 hizo con las claves.
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
            // ⚠️ **El perfil sale de la FILA y no del pack**, porque en un pack bidireccional
            // las entradas inglesas se pliegan con el perfil ingles y las españolas con el
            // español. Comprobar las dos con un solo perfil daria falsos positivos justo en la
            // mitad del pack -- y esta comprobacion existe para rechazar packs incompatibles,
            // asi que un falso positivo deja al usuario sin diccionario.
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
                        // `fuzzy` puede ser NULL: son las entradas que quedan fuera del nivel
                        // tolerante a proposito, y `verify_pack.py` las cuenta sin alarmarse.
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
            // `meta[...]` y no `getValue`: un pack construido antes de D-125 no la trae y
            // tiene que seguir abriendo.
            description = meta["description"],
            // ⚠️ **`langs` y no `lang_src`: los idiomas son PARES.** Un pack bidireccional
            // tiene entradas de los dos y ninguno es el principal.
            langs = parseList(meta.getValue("langs")),
            fuzzyProfiles = parseList(meta.getValue("fuzzy_profiles"))
                .map { FuzzyProfile.fromId(it) },
            tier = PackTier.fromId(meta["tier"]),
            // `toIntOrNull` y no `toInt`: un pack que declare cualquier cosa degrada a "sin
            // banda", que es como se comportaban todos antes de D-185.
            rankSignalBoundary = meta["rank_signal_boundary"]?.toIntOrNull(),
            // `meta[...]`: la trae sólo un pack que declare traducciones.
            // ⚠️ **Con respaldo a `lang_dst` para un pack BILINGUE anterior a la clave.** Un
            // bilingue traduce por definicion --sus glosas ya estan en el idioma destino-- asi
            // que inferirlo es seguro, y sin esto un pack construido antes de D-183 dejaria de
            // ofrecerse para traducir aunque sea exactamente lo que hace.
            // ⚠️ **Se infiere del SEGUNDO idioma declarado cuando el pack no lo dice.** Un
            // bilingue traduce por definicion --sus glosas ya estan en el otro idioma-- asi que
            // inferirlo es seguro, y sin esto un pack que no declare la capacidad dejaria de
            // ofrecerse para traducir aunque sea exactamente lo que hace.
            translationsTo = meta["translations_to"]
                ?: parseList(meta["langs"]).getOrNull(1)
                    ?.takeIf { meta["kind"] == PackKind.BILINGUAL.id },
            // `fromId` no lanza ante un id desconocido: un pack mas nuevo puede
            // traer una base que esta version no sabe leer, y eso degrada bien.
            rankBasis = RankBasis.fromId(meta["rank_basis"]),
            // `meta[...]` otra vez: ningun pack de hoy la trae, y el formato no tiene
            // migraciones (D-001) pero eso aplica a `schema_version`; una clave nueva y aditiva
            // es justo lo que la tolerancia existe para soportar.
            subsetOf = meta["subset_of"],
            entryCount = meta.getValue("entry_count").toInt(),
            dataVersion = meta.getValue("data_version").toLong(),
            license = meta.getValue("license"),
            attribution = meta.getValue("attribution"),
            // `meta[...]` y no `getValue`: un pack anterior a D-138 no la trae y tiene que
            // seguir abriendo. `parse` nunca lanza.
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
