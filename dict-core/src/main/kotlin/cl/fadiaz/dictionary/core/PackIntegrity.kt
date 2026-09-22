package cl.fadiaz.dictionary.core

/**
 * Qué se le pregunta a la `meta` de un pack, **y en qué orden**.
 *
 * ## Por qué vive acá y no junto a `PackFile`
 *
 * `PackFile` está en `:dict-data`, cuyos tests son instrumentados y **el gate no los corre**.
 * Esto es la mitad que no necesita SQLite: mapas de cadenas entrando, un motivo saliendo. Puesto
 * acá lo cubre el gate en milisegundos, que es la misma razón por la que `PackLoad` y `PackSet`
 * no tocan Android (D-072). Lo que se queda del otro lado es lo que de verdad necesita abrir el
 * archivo: contar filas, mirar los índices y recalcular la muestra de claves.
 *
 * ## El orden es la decisión, y lo encontró un pack real
 *
 * Los tres primeros chequeos rechazan igual, así que el orden **no cambia si un pack entra o
 * no**: cambia la única línea que el usuario lee. Correr estas reglas contra los siete `.db` de
 * `schema_version` 3 que quedaron en el directorio de datos los rechazaba a todos por
 * `METADATA` —*"metadatos incompletos"*— porque no traen `langs` ni `fuzzy_profiles`: esas claves
 * nacieron con el esquema 4. Es cierto y es inútil; suena a archivo corrupto cuando lo que pasa
 * es que el pack es más viejo que la app.
 *
 * ⚠️ **La causa es de dependencia, no de prolijidad: `schema_version` es la clave que dice qué
 * otras claves tienen que existir.** Exigir el juego de la versión 4 antes de mirar la versión
 * juzga a un pack de la 3 con reglas que no eran las suyas. Por eso el esquema se mira primero,
 * y por eso es la única clave que se lee antes que el resto.
 */
object PackIntegrity {

    /**
     * Las claves sin las cuales no se puede construir un [PackMetadata].
     *
     * No están las opcionales --`description`, `tier`, `subset_of`, `translations_to`,
     * `rank_basis`-- que se leen con `meta[...]` justamente para que un pack más viejo del mismo
     * esquema siga abriendo: son aditivas, y la tolerancia a lo aditivo es lo que evita tener
     * que subir `schema_version` por cada campo nuevo.
     */
    val REQUIRED_META: List<String> = listOf(
        "pack_id", "schema_version", "norm_version", "kind", "name", "langs",
        "fuzzy_profiles", "entry_count", "data_version", "license", "attribution",
        "payload_dict", "payload_dict_sha256", "payload_codec",
    )

    /** El motivo por el que un pack no se carga, con la prosa para el log. */
    data class MetaProblem(val rejection: PackRejection, val detail: String)

    /**
     * Lo que se puede decidir mirando sólo `meta`, o `null` si no hay nada que objetar.
     *
     * Las comprobaciones que necesitan abrir las tablas --`entry_count` contra las filas reales,
     * `fts_def`, los índices, las claves-- viven en `PackFile`, porque necesitan la conexión.
     */
    fun checkMeta(
        meta: Map<String, String>,
        supportedSchema: Int,
        normVersion: Int,
        codecId: String,
    ): MetaProblem? {
        // 1. El esquema, antes que nada. Ver el ⚠️ del encabezado.
        val schema = meta["schema_version"]?.trim()?.toIntOrNull()
            ?: return MetaProblem(
                PackRejection.METADATA,
                "schema_version ausente o ilegible: ${meta["schema_version"]}",
            )
        if (schema != supportedSchema) {
            return MetaProblem(
                PackRejection.SCHEMA_VERSION,
                "schema_version $schema, esta app entiende $supportedSchema",
            )
        }

        // 2. Recién ahora tiene sentido exigir el juego de claves de ESTE esquema.
        val faltan = REQUIRED_META.filterNot { it in meta }
        if (faltan.isNotEmpty()) {
            return MetaProblem(
                PackRejection.METADATA,
                "a meta le faltan claves obligatorias: ${faltan.joinToString()}",
            )
        }
        for (clave in listOf("norm_version", "entry_count")) {
            meta.getValue(clave).trim().toIntOrNull()
                ?: return MetaProblem(
                    PackRejection.METADATA,
                    "$clave no es un numero: ${meta[clave]}",
                )
        }
        // ⚠️ `Long` y no `Int`: `202609211432` pasa el tope de un Int de 32 bits, y con `Int` el
        // pack reventaba al ABRIR con un NumberFormatException que no nombraba la clave (D-070).
        meta.getValue("data_version").trim().toLongOrNull()
            ?: return MetaProblem(
                PackRejection.METADATA,
                "data_version no es un numero: ${meta["data_version"]}",
            )
        val langs = meta.getValue("langs").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (langs.isEmpty()) {
            return MetaProblem(PackRejection.METADATA, "meta.langs no declara ningun idioma")
        }
        val perfiles = meta.getValue("fuzzy_profiles")
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (perfiles.size != langs.size) {
            return MetaProblem(
                PackRejection.METADATA,
                "meta.fuzzy_profiles trae ${perfiles.size} perfiles para ${langs.size} idiomas",
            )
        }

        // 3. Las dos que dicen que el contenido está construido con otras reglas.
        val norm = meta.getValue("norm_version").trim().toInt()
        if (norm != normVersion) {
            return MetaProblem(
                PackRejection.NORM_VERSION,
                "norm_version $norm != $normVersion: el pack esta indexado con otras reglas y " +
                    "devolveria menos resultados",
            )
        }
        if (meta["payload_codec"] != codecId) {
            return MetaProblem(
                PackRejection.PAYLOAD_CODEC,
                "payload_codec '${meta["payload_codec"]}', esta app lee '$codecId'",
            )
        }

        // 4. Y que se pueda acreditar, que es condición de uso del dato y no una cortesía.
        val sources = PackSource.parse(meta["sources"])
        if (sources.isEmpty()) {
            return MetaProblem(
                PackRejection.LICENSE,
                "meta.sources vacio: el pack no declara de donde sale su contenido (D-138)",
            )
        }
        val sinLicencia = sources.filter { it.license.isBlank() }
        if (sinLicencia.isNotEmpty()) {
            return MetaProblem(
                PackRejection.LICENSE,
                "fuentes sin licencia declarada: " +
                    sinLicencia.joinToString { it.name.ifBlank { "(sin nombre)" } },
            )
        }
        return null
    }
}
