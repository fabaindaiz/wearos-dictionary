package cl.fadiaz.dictionary.data

import cl.fadiaz.dictionary.core.PackMetadata
import java.time.LocalDate
import cl.fadiaz.dictionary.core.TextNormalizer
import org.json.JSONObject

/**
 * A pack as the catalog publishes it, **before** downloading it.
 *
 * It is deliberately different from [PackMetadata]: that is what a pack that is already open says,
 * and this is a promise about a file that does not yet exist on the watch. Merging them would make
 * an offered pack and an installed one look the same in the code, which is how you end up showing
 * "installed" for something that was never downloaded.
 *
 * ⚠️ **`schemaVersion` and `normVersion` are here for efficiency and not for completeness**: they
 * are the two that make `PackFile.open` reject a whole pack, so having them BEFORE the url allows
 * discarding an incompatible pack **without spending 192 MB to throw them away**.
 *
 * ⚠️ **Two hashes, and both are needed.** [sha256] is of the `.gz` that travels and [dbSha256] of
 * the `.db` that stays on disk after decompressing. It is verified at both moments, and what
 * `PackStore.installAtomically` compares is the second (D-165).
 */
data class CatalogPack(
    val packId: String,
    val name: String,
    val description: String?,
    val langs: List<String>,
    val entryCount: Int,
    /** The monotonic integer the builder writes. It is with this that novelty is decided. */
    val dataVersion: Long,
    val schemaVersion: Int,
    val normVersion: Int,
    val license: String?,
    val url: String,
    val bytes: Long,
    val sha256: String,
    val dbBytes: Long,
    val dbSha256: String,
)

/** Which bucket a catalog pack falls into when compared with what is installed. */
enum class CatalogStatus {
    /** It is not installed and it can be used. */
    DOWNLOAD,

    /** It is installed and the catalog has a higher `data_version`. */
    UPDATE,

    /** It is installed and up to date. It is shown, but not offered. */
    INSTALLED,
}

/**
 * The catalog, already compared with what is installed.
 *
 * ⚠️ **Packs this app does not open are NOT in [offers]**, and that reverses an earlier decision.
 * They used to be shown in a section of their own, on the reasoning that *"a pack that exists and
 * cannot be used is a question the user is going to ask"*. Explicit request: *"I do not want
 * unavailable packs listed; instead it should just make clear that the app has to be updated"*.
 * And it is better: the list should only hold things that can be had, and the useful answer is not
 * *"this pack is no good"* but **"update the app"**, which is actionable.
 */
data class CatalogListing(
    val offers: List<CatalogOffer>,
    /** Some packs were discarded by version. The screen says so once, not pack by pack. */
    val needsAppUpdate: Boolean,
)

/** Where ONE pack's download stands. */
enum class DownloadPhase {
    /**
     * Queued, waiting for D-029 to be satisfied: charging and on unmetered Wi-Fi.
     *
     * ⚠️ **This state has to be visible on screen.** If the watch is not charging, tapping
     * download downloads nothing yet, and progress that does not move with no explanation reads as
     * a broken app.
     */
    WAITING,

    RUNNING,
    DONE,

    /** It failed; WorkManager will retry. The `.part` is kept, so it will resume. */
    FAILED,

    /**
     * The user stopped it. **It is not [FAILED]**, and the difference is what gets said to them.
     *
     * `FAILED` promises a retry; this one has none. The screen treats it as *"there is no
     * download"*: the row goes back to being an offer, which is what cancelling means. And the
     * `.part` is gone --see `DownloadPackWorker.cancel`-- so asking again downloads from zero.
     */
    CANCELLED,
}

/** A pack download's progress, as WorkManager reports it. */
data class PackDownload(
    val packId: String,
    val phase: DownloadPhase,
    val done: Long = 0,
    val total: Long = 0,
)

/**
 * Where the catalog query stands, for the management screen.
 *
 * ⚠️ **It starts at [Idle] and stays there until the user presses the button.** Entering the
 * screen queries nothing: that was the explicit request and it matches D-029, because the official
 * Wear OS guidance puts network access above turning the screen on.
 */
sealed interface CatalogState {
    /** Nobody has asked yet. */
    data object Idle : CatalogState

    /** The question is in flight. The screen shows that something is happening. */
    data object Checking : CatalogState

    /** An answer arrived. [offers] can be empty: a catalog with nothing to offer. */
    data class Ready(
        val offers: List<CatalogOffer>,
        /** Some packs this version does not open were found. Said once, not pack by pack. */
        val needsAppUpdate: Boolean = false,
    ) : CatalogState

    /** It could not be done. [reason] is shown verbatim: in development it is the only guide. */
    data class Failed(val reason: String) : CatalogState
}

/** A catalog pack with its verdict and, if there was one, the version already on disk. */
data class CatalogOffer(
    val pack: CatalogPack,
    val status: CatalogStatus,
    val installedVersion: Long?,
)

/**
 * The catalog: reading it and comparing it with what is installed.
 *
 * It downloads nothing and does not touch the network: this is the pure part. See [CatalogClient]
 * for the HTTP.
 */
object Catalog {

    /**
     * Sorts the catalog's packs into [CatalogStatus]'s buckets.
     *
     * ⚠️ **Compatibility is compared by EQUALITY and not by `>=`**, because that is exactly what
     * `PackFile.open` does: a different `schema_version` is rejected, higher or lower. Putting
     * `>=` here would offer packs the app then does not open, and the user would have paid for the
     * download.
     *
     * The versions arrive as parameters --with the real value as the default-- so a test can move
     * the threshold without touching the constants, which have a mirror of their own in the audit.
     */
    fun classify(
        catalog: List<CatalogPack>,
        installed: List<PackMetadata>,
        schemaVersion: Int = PackFile.SUPPORTED_SCHEMA_VERSION,
        normVersion: Int = TextNormalizer.NORM_VERSION,
    ): CatalogListing {
        // By `packId` and never by file name: the Spanish core pack lives in `es-core.db` and is
        // called `es-def-wikc-tat-freq-wn-wd-core`. Identity is what the artifact DECLARES
        // (D-138); confusing it with location would make renaming a file look like a new pack.
        val localPorId = installed.associate { it.packId to it.dataVersion }
        // ⚠️ **They are compared by EQUALITY and not by `>=`**, because that is what
        // `PackFile.open` does: a different `schema_version` is rejected, higher or lower. With
        // `>=` a pack the app then does not open would be offered, and the user would already have
        // paid for the download.
        val (abribles, rechazados) = catalog.partition {
            it.schemaVersion == schemaVersion && it.normVersion == normVersion
        }
        val offers = abribles.map { pack ->
            val local = localPorId[pack.packId]
            val status = when {
                local == null -> CatalogStatus.DOWNLOAD
                // Strictly greater. In development it happens daily to hold a local pack newer
                // than the one the server serves, and offering "update" there would be a
                // downgrade.
                pack.dataVersion > local -> CatalogStatus.UPDATE
                else -> CatalogStatus.INSTALLED
            }
            CatalogOffer(pack, status, local)
        }
        return CatalogListing(offers, needsAppUpdate = rechazados.isNotEmpty())
    }

    /**
     * The date a `data_version` carries inside it, or `null` if that number is not a date.
     *
     * ⚠️ **This came out of seeing it on the emulator**, which is where UI decisions get seen in
     * this repo. An update's row read literally `3,0 MB · v202609211912, you have v202609211911`:
     * two twelve-digit numbers differing in the last one, 390 px of the ~459 usable at that height
     * on a round screen, and nothing gets decided with that. The date does inform.
     *
     * The builder writes `data_version` as `YYYYMMDDHHMM`, but **somebody else's pack can put
     * whatever it likes there** --it is a monotonic integer and nothing more-- so this is
     * defensive: if it does not look like a valid date it returns `null` and the row keeps just
     * the size.
     */
    fun dataVersionDate(dataVersion: Long): LocalDate? {
        // ⚠️ **There are TWO widths in circulation**, seen in the real index: the new packs carry
        // `YYYYMMDDHHMM` (202609211912) and `es-def-wd` carries `YYYYMMDD` (20260920). Accepting
        // only one left half the rows with no date, and with no error to give it away.
        val ymd = when (dataVersion) {
            in 10_000_101L..99_991_231L -> dataVersion
            in 100_001_010_000L..999_912_312_359L -> dataVersion / 10_000L
            else -> return null
        }.toInt()
        // `runCatching` and not more hand checks: `LocalDate.of` already rejects month 13 and the
        // 30th of February, and duplicating that calendar here would be a second source of truth.
        return runCatching {
            LocalDate.of(ymd / 10_000, (ymd / 100) % 100, ymd % 100)
        }.getOrNull()
    }

    /**
     * Parses the server's `index.json`.
     *
     * ⚠️ **A pack missing a required field is SKIPPED rather than bringing the catalog down.** A
     * catalog with six packs and one badly written has to offer the five good ones; if one broken
     * entry leaves the user with no options at all, the failure is the client's and not the
     * server's.
     */
    fun parse(json: String): List<CatalogPack> {
        val root = JSONObject(json)
        val array = root.optJSONArray("packs") ?: return emptyList()
        val out = mutableListOf<CatalogPack>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val packId = o.optString("pack_id").takeIf { it.isNotEmpty() } ?: continue
            val url = o.optString("url").takeIf { it.isNotEmpty() } ?: continue
            val sha = o.optString("sha256").takeIf { it.isNotEmpty() } ?: continue
            val dbSha = o.optString("db_sha256").takeIf { it.isNotEmpty() } ?: continue
            val langs = o.optJSONArray("langs")
            out += CatalogPack(
                packId = packId,
                name = o.optString("name").takeIf { it.isNotEmpty() } ?: packId,
                description = o.optString("description").takeIf { it.isNotEmpty() },
                langs = (0 until (langs?.length() ?: 0)).mapNotNull {
                    langs?.optString(it)?.takeIf { s -> s.isNotEmpty() }
                },
                entryCount = o.optInt("entry_count"),
                dataVersion = o.optLong("data_version"),
                schemaVersion = o.optInt("schema_version"),
                normVersion = o.optInt("norm_version"),
                license = o.optString("license").takeIf { it.isNotEmpty() },
                url = url,
                bytes = o.optLong("bytes"),
                sha256 = sha,
                dbBytes = o.optLong("db_bytes"),
                dbSha256 = dbSha,
            )
        }
        return out
    }
}
