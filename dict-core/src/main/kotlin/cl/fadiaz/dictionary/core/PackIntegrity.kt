package cl.fadiaz.dictionary.core

/**
 * What a pack's `meta` gets asked, **and in which order**.
 *
 * ## Why it lives here and not next to `PackFile`
 *
 * `PackFile` is in `:dict-data`, whose tests are instrumented and **the gate does not run them**.
 * This is the half that needs no SQLite: string maps in, a reason out. Placed here the gate covers
 * it in milliseconds, which is the same reason `PackLoad` and `PackSet` do not touch Android
 * (D-072). What stays on the other side is what genuinely needs the file open: counting rows,
 * looking at the indexes and recomputing the key sample.
 *
 * ## The order is the decision, and a real pack found it
 *
 * The first three checks all reject, so the order **does not change whether a pack gets in**: it
 * changes the single line the user reads. Running these rules against the seven `schema_version`
 * 3 `.db` files left in the data directory rejected every one of them as `METADATA`
 * --*"incomplete metadata"*-- because they carry neither `langs` nor `fuzzy_profiles`: those keys
 * were born with schema 4. It is true and it is useless; it sounds like a corrupt file when what
 * is happening is that the pack is older than the app.
 *
 * ⚠️ **The cause is dependency, not tidiness: `schema_version` is the key that says which other
 * keys have to exist.** Demanding version 4's set before looking at the version judges a version
 * 3 pack by rules that were not its own. That is why the schema is looked at first, and why it is
 * the only key read ahead of the rest.
 */
object PackIntegrity {

    /**
     * The keys without which a [PackMetadata] cannot be built.
     *
     * The optional ones are not here --`description`, `tier`, `subset_of`, `translations_to`,
     * `rank_basis`-- which are read with `meta[...]` precisely so an older pack of the same schema
     * still opens: they are additive, and tolerating the additive is what avoids having to bump
     * `schema_version` for every new field.
     */
    val REQUIRED_META: List<String> = listOf(
        "pack_id", "schema_version", "norm_version", "kind", "name", "langs",
        "fuzzy_profiles", "entry_count", "data_version", "license", "attribution",
        "payload_dict", "payload_dict_sha256", "payload_codec",
    )

    /** The reason a pack does not load, with the prose for the log. */
    data class MetaProblem(val rejection: PackRejection, val detail: String)

    /**
     * What can be decided by looking at `meta` alone, or `null` if there is nothing to object to.
     *
     * The checks that need the tables open --`entry_count` against the real rows, `fts_def`, the
     * indexes, the keys-- live in `PackFile`, because they need the connection.
     */
    fun checkMeta(
        meta: Map<String, String>,
        supportedSchema: Int,
        normVersion: Int,
        codecId: String,
    ): MetaProblem? {
        // 1. The schema, before anything else. See the ⚠️ in the header.
        val schema = meta["schema_version"]?.trim()?.toIntOrNull()
            ?: return MetaProblem(
                PackRejection.METADATA,
                "schema_version missing or unreadable: ${meta["schema_version"]}",
            )
        if (schema != supportedSchema) {
            return MetaProblem(
                PackRejection.SCHEMA_VERSION,
                "schema_version $schema, this app understands $supportedSchema",
            )
        }

        // 2. Only now does it make sense to demand THIS schema's set of keys.
        val faltan = REQUIRED_META.filterNot { it in meta }
        if (faltan.isNotEmpty()) {
            return MetaProblem(
                PackRejection.METADATA,
                "meta is missing required keys: ${faltan.joinToString()}",
            )
        }
        for (clave in listOf("norm_version", "entry_count")) {
            meta.getValue(clave).trim().toIntOrNull()
                ?: return MetaProblem(
                    PackRejection.METADATA,
                    "$clave is not a number: ${meta[clave]}",
                )
        }
        // ⚠️ `Long` and not `Int`: `202609211432` passes the top of a 32-bit Int, and with `Int`
        // the pack blew up on OPENING with a NumberFormatException that did not name the key
        // (D-070).
        meta.getValue("data_version").trim().toLongOrNull()
            ?: return MetaProblem(
                PackRejection.METADATA,
                "data_version is not a number: ${meta["data_version"]}",
            )
        val langs = meta.getValue("langs").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (langs.isEmpty()) {
            return MetaProblem(PackRejection.METADATA, "meta.langs declares no language")
        }
        val perfiles = meta.getValue("fuzzy_profiles")
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (perfiles.size != langs.size) {
            return MetaProblem(
                PackRejection.METADATA,
                "meta.fuzzy_profiles carries ${perfiles.size} profiles for ${langs.size} languages",
            )
        }

        // 3. The two that say the content was built under different rules.
        val norm = meta.getValue("norm_version").trim().toInt()
        if (norm != normVersion) {
            return MetaProblem(
                PackRejection.NORM_VERSION,
                "norm_version $norm != $normVersion: the pack is indexed under other rules and " +
                    "would return fewer results",
            )
        }
        if (meta["payload_codec"] != codecId) {
            return MetaProblem(
                PackRejection.PAYLOAD_CODEC,
                "payload_codec '${meta["payload_codec"]}', this app reads '$codecId'",
            )
        }

        // 4. And that it can be credited, which is a condition of using the data and not a
        //    courtesy.
        val sources = PackSource.parse(meta["sources"])
        if (sources.isEmpty()) {
            return MetaProblem(
                PackRejection.LICENSE,
                "meta.sources empty: the pack does not declare where its content comes from (D-138)",
            )
        }
        val sinLicencia = sources.filter { it.license.isBlank() }
        if (sinLicencia.isNotEmpty()) {
            return MetaProblem(
                PackRejection.LICENSE,
                "sources with no declared licence: " +
                    sinLicencia.joinToString { it.name.ifBlank { "(unnamed)" } },
            )
        }
        return null
    }
}
