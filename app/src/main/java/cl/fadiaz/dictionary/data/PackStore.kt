package cl.fadiaz.dictionary.data

import android.content.Context
// `edit { }` and not `.edit()....apply()`: the second form compiles without the trailing
// `apply()` and then saves NOTHING, with no error and no log. The lambda cannot be
// forgotten. It comes from core-ktx, which was already on the classpath.
import androidx.core.content.edit
import cl.fadiaz.dictionary.R
import cl.fadiaz.dictionary.presentation.packRejectionLabelRes
import cl.fadiaz.dictionary.core.PayloadCodec
import cl.fadiaz.dictionary.core.PackRejection
import cl.fadiaz.dictionary.core.PackMetadata
import cl.fadiaz.dictionary.core.TextNormalizer
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where the packs the app opens come from.
 *
 * They all live in `filesDir/packs/`; what differs is how they got there:
 *
 *  - **The demo pack** ships inside the APK and is extracted on first launch. It is small and
 *    exists so a freshly installed app has something to show.
 *  - **The real dictionaries** arrive through `adb push` today, and through the installer once
 *    it exists. Both write into the same directory, so the app cannot tell them apart.
 *
 * WHY A DEMO PACK AND NOT THE WHOLE DICTIONARY (D-071, D-081)
 *
 * A pack inside the APK is duplicated on disk: compressed within and extracted without. With the
 * real dictionary that is unacceptable --English is **295 MiB on disk, 185 MiB compressed**, and
 * with both languages the APK went to ~270 MB, which over Bluetooth to a watch is not a detail.
 * With a demo pack of tens of KB, that same cost is noise.
 *
 * That is the whole difference: it is not the mechanism, it is the size.
 */
object PackStore {

    /** Where installed packs live. Excluded from backup in `data_extraction_rules.xml`. */
    fun packsDir(context: Context): File = File(context.filesDir, "packs")

    /**
     * Opens the pack, extracting it from the asset if needed.
     *
     * `onExtracting` is called before copying, not during: the 69 MB copy takes a while and the
     * screen has to be able to say why it is waiting.
     */
    /**
     * Which assets have to be copied to disk, and above all **which do not**.
     *
     * Pure and free of `Context` so it can be tested on the JVM, same as [installAtomically].
     * The cases an `if (dir.isEmpty())` swallows:
     *
     *  - the already extracted demo pack is not copied again on every launch;
     *  - updating the APK with a different demo extracts it even with dictionaries installed;
     *  - a `.db` pushed by hand with `adb push` is left alone, which is how the real packs get
     *    here today.
     */
    /**
     * Which of the APK's packs have to be copied to disk.
     *
     * ⚠️ **It used to look only at whether the NAME was missing, which is why a bundled pack was
     * extracted once and never updated.** Seen on the watch: the app warned that `demo-es-en.db`
     * was incompatible --it had been extracted with `deflate-v1` and the app moved to `deflate-v2`
     * (D-119)-- while the APK carried a good one that was never copied. It is the cost D-119
     * accepted *"because today it is zero"*, and it stopped being zero.
     *
     * The rule now: **a new version of the app re-extracts its packs**, because they are its own
     * and come with it. The ones the user installed **are never touched** -- they are 372 MB
     * nobody wants to copy over adb again.
     */
    internal fun assetsToExtract(
        assets: List<String>,
        installed: List<String>,
        last: Int,
        current: Int,
        /**
         * The APK's packs the user **replaced from the catalog**.
         *
         * ⚠️ **It is a third case the download created, and without it there is a silent
         * downgrade.** `es-core.db` ships in the APK; updating it from the catalog rewrites that
         * same file, and until here the rule was *"a new version of the app re-extracts its
         * packs"* --which would overwrite the new pack with the old one and raise no error,
         * because the old one opens just as well--.
         *
         * Since [ExtractionPlan] the rule stopped being *"the catalog wins"* flat out: the higher
         * `data_version` wins, wherever it comes from. See there.
         */
        downloaded: Set<String> = emptySet(),
    ): ExtractionPlan {
        val alreadyOnDisk = installed.toSet()
        if (last == current) {
            // The same APK as last time: its content did not change, so there is nothing to
            // compare. Only what is missing from disk gets copied.
            return ExtractionPlan(
                copy = assets.filterNot { it in alreadyOnDisk || it in downloaded }.sorted(),
                compare = emptyList(),
            )
        }
        val (delCatalogo, propios) = assets.partition { it in downloaded }
        // ⚠️ Marked "from the catalog" but **absent from disk** is not a conflict: there is
        // nothing to compare against, so it gets copied. Today `deletePack` clears the mark on
        // deletion, so this should not happen -- and for that very reason, if it does, the safe
        // outcome is having the pack.
        val (presentes, desaparecidos) = delCatalogo.partition { it in alreadyOnDisk }
        return ExtractionPlan(
            copy = (propios + desaparecidos).sorted(),
            compare = presentes.sorted(),
        )
    }

    /**
     * What to do with each of the APK's packs when the app has gone up a version.
     *
     * ⚠️ **Two lists and not one, because they are two questions and merging them already cost a
     * bug.** D-172 put the lesson in writing: *a rule that filters a list is no use if another
     * path builds that list again*. Returning a plan --and not two functions somebody can call
     * separately-- makes forgetting one half not compile.
     */
    internal data class ExtractionPlan(
        /**
         * They get copied and overwrite whatever is there: that file is the APK's and nobody else
         * touched it.
         */
        val copy: List<String>,
        /**
         * They came from the catalog and **are still on disk**: copied only if the APK carries a
         * higher `data_version`.
         *
         * ⚠️ **This replaces *"the catalog beats the APK"*, which was D-214's rule and had the
         * symmetric defect to the one it came to fix.** There the problem was a new app
         * overwriting with its old core the one the user had just downloaded; the rule *"the
         * catalog wins"* closed that, and opened the other: a core downloaded months ago beats
         * **forever** the one today's APK carries, even when today's is newer. Neither fails with
         * an error -- both packs open fine -- so all that is visible is a dictionary that never
         * updates.
         *
         * **The rule that closes both: the higher `data_version` wins, wherever it comes from.**
         * It is the same monotonic integer `Catalog.classify` decides novelty with (D-170), so the
         * app uses one notion of *"which is newer"* and not two.
         *
         * ⚠️ **The APK's version is read from an INDEX, not from the asset**, and that is the part
         * that makes this cheap. SQLite does not open a `.db` inside the APK --verified with
         * `javap` over `sqlite-bundled 2.7.1`: the whole surface is `open(String)`, with no VFS of
         * its own and no `sqlite3_deserialize` (D-173)-- so asking the asset for its version would
         * force **copying the Spanish core's 50.8 MB** to read a 12-digit integer. The first
         * version of this did exactly that.
         *
         * Instead, `bundlePacks` leaves the version written in `assets/core-index.tsv` during the
         * build, reading it from the **same `index.json` `packserver.py` serves** -- the bundled
         * pack is an exceptional case and can afford to declare its version, just as the server
         * declares its own. See [coreIndex]. Cost on the watch: two lines of text.
         *
         * ⚠️ **And the index brings a risk of its own, closed in the build**: a stale one beside a
         * new `.db` would declare a version that is not true. `bundlePacks` compares the declared
         * size against the real file and, if they disagree, **declares nothing** -- and the app
         * then leaves the user's pack as it is, which is the safe state.
         */
        val compare: List<String>,
    )

    /**
     * The verification memo exactly as stored, **so it can be looked at on the device**.
     *
     * It exists for item 3 of the roadmap's debug tooling: until now the memo could only be read
     * with `run-as`, which does not work over a `benchmark` APK. And it is precisely the datum
     * that proves installing a new version expired it (D-225). `DebugIntents.dump` consumes it.
     */
    fun verificationMemo(context: Context): String? =
        prefs(context).getString(KEY_VERIFIED, null)

    /** Which of the APK's packs were replaced from the catalog. See [assetsToExtract]. */
    fun downloadedPacks(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_DOWNLOADED, emptySet()).orEmpty()

    /** Records that [fileName] came from the catalog and must no longer be re-extracted from the APK. */
    fun rememberDownloaded(context: Context, fileName: String) {
        prefs(context).edit {
            putStringSet(KEY_DOWNLOADED, downloadedPacks(context) + fileName)
        }
    }

    /**
     * Forgets the mark when a pack is deleted.
     *
     * Without this, deleting an updated core would leave it **never coming back**: the mark would
     * go on saying "do not re-extract it" about a file that no longer exists.
     */
    private fun forgetDownloaded(context: Context, fileName: String) {
        prefs(context).edit {
            putStringSet(KEY_DOWNLOADED, downloadedPacks(context) - fileName)
        }
    }

    /**
     * Extracts whatever is missing from the APK and opens everything there is.
     *
     * The extraction is eager and not lazy **because the demo pack is small**: deferring it
     * would cost a state machine to save tens of KB.
     */
    suspend fun open(
        context: Context,
        preferred: String?,
        onExtracting: () -> Unit = {},
    ): PackSet = withContext(Dispatchers.IO) {
        val dir = packsDir(context)
        dir.mkdirs()

        val instaladaAntes = prefs(context).getInt(KEY_EXTRACTED_BY, 0)
        val plan = assetsToExtract(
            packAssets(context),
            installedPacks(dir).map { it.name },
            last = instaladaAntes,
            current = versionCode(context),
            downloaded = downloadedPacks(context),
        )
        if (plan.copy.isNotEmpty() || plan.compare.isNotEmpty()) {
            onExtracting()
            DictLog.i {
                "extrayendo del APK: ${plan.copy.joinToString().ifEmpty { "nada" }}" +
                    plan.compare.takeIf { it.isNotEmpty() }
                        ?.let { " · comparando version: ${it.joinToString()}" }.orEmpty()
            }
            for (asset in plan.copy) {
                // ⚠️ The `runCatching` swallows the failure on purpose --the app still starts with
                // whatever packs are there-- but without this log a core that never extracts is
                // invisible: the screen only shows that the language is missing.
                runCatching { installAtomically(context.assets.open(asset), dir, asset) }
                    .onFailure { e -> DictLog.e(e) { "no se pudo extraer $asset del APK" } }
            }
            val declaradas = coreIndex(context)
            for (asset in plan.compare) {
                runCatching { extractIfNewer(context, dir, asset, declaradas[asset]) }
                    .onFailure { e -> DictLog.e(e) { "no se pudo comparar $asset con el APK" } }
            }
        }
        // It is recorded AFTER copying: if the extraction fails half way, the next launch tries
        // again instead of taking it as done.
        prefs(context).edit { putInt(KEY_EXTRACTED_BY, versionCode(context)) }

        val installed = installedPacks(dir)
        if (installed.isEmpty()) return@withContext PackSet.NoPack

        // The ones that came from the APK are demos: they get marked so they cannot beat an
        // installed dictionary.
        val fromAssets = packAssets(context).toSet()
        val opened = mutableListOf<PackHandle.Open>()
        val rejected = mutableListOf<PackHandle.Incompatible>()
        DictLog.i { "packs en disco: ${installed.size} (${installed.joinToString { it.name }})" }
        for (file in installed) {
            val desde = System.nanoTime()
            when (val loaded = openFile(context, file)) {
                is PackLoad.Ready -> {
                    opened += PackHandle.Open(
                        source = loaded.source,
                        isBundled = file.name in fromAssets,
                        fileName = file.name,
                        bytes = file.length(),
                    )
                    logAbierto(loaded.source.metadata, file, desde)
                }
                is PackLoad.Unusable -> {
                    rejected += PackHandle.Incompatible(
                        fileName = file.name,
                        bytes = file.length(),
                        rejection = loaded.rejection,
                    )
                    // WARN and not DEBUG: a rejected pack is the whole explanation of "a language
                    // is missing", and in the watch session it had to be inferred from the absence
                    // of a crash. The `detail` goes here and NOT to the screen: it is prose with
                    // concrete values, useful for debugging and unreadable on a watch.
                    DictLog.w {
                        // The detail comes empty when the verdict came from the memo: the file was
                        // not opened, so there are no concrete values to report.
                        "pack RECHAZADO ${file.name}: ${loaded.rejection.id}" +
                            loaded.detail.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                    }
                }
                PackLoad.NoPack -> Unit
            }
        }

        // The verified memo is pruned against what is left on disk: a deleted pack leaves a line
        // that is no longer any use, and without this it would grow forever in SharedPreferences.
        prefs(context).edit {
            putString(
                KEY_VERIFIED,
                PackVerification.prune(
                    prefs(context).getString(KEY_VERIFIED, null),
                    installed.map { it.name }.toSet(),
                ),
            )
        }

        // The active one comes from the SAME rules that decide who gets queried: choosing it
        // separately let an old build in, which was then queried anyway for being the active one.
        val chosen = activePack(opened, preferred)
            ?: return@withContext PackSet.Unusable(
                rejected.firstOrNull()
                    ?.let { context.getString(packRejectionLabelRes(it.rejection)) }
                    ?: context.getString(R.string.pack_none_opened),
            )

        DictLog.i {
            "listo: ${opened.size} abiertos, ${rejected.size} rechazados, " +
                "activo=${chosen.source.metadata.packId}"
        }
        // The rejected ones go AT THE END of the list: the dictionaries screen shows them below
        // the usable ones, which is where they get in the way least.
        PackSet.Ready(chosen, opened + rejected)
    }

    /**
     * A freshly opened pack's identity, which is the first question when a word is missing.
     *
     * `schema_version` and `norm_version` are here because they are the two that get a pack
     * rejected whole (D-001, D-006), and `data_version` because it is what the catalog decides
     * updates with. It goes to INFO: three lines per launch, not one per query.
     */
    private fun logAbierto(meta: PackMetadata, file: File, desdeNanos: Long) {
        val ms = (System.nanoTime() - desdeNanos) / 1_000_000
        DictLog.i {
            "pack ${meta.packId} schema=${meta.schemaVersion} norm=${meta.normVersion} " +
                "langs=${meta.langs.joinToString("+")} kind=${meta.kind} tier=${meta.tier} " +
                "entries=${meta.entryCount} dataVersion=${meta.dataVersion} " +
                "${file.length() / 1_048_576} MB en ${ms} ms"
        }
    }

    /** The chosen language, so the watch opens the same dictionary as last time. */
    /**
     * What the user chose last time: since D-197 a **language**, before that a `packId`.
     *
     * ⚠️ **The key is kept and the value changed meaning**, on purpose: a `packId` stored by an
     * earlier version still works, because `chooseActive` tries it first as an identity and only
     * then as a language. Migrating the preference would have cost code for a value that gets
     * rewritten the first time somebody taps a chip.
     */
    fun preferredPack(context: Context): String? =
        prefs(context).getString(KEY_PACK, null)

    fun rememberLanguage(context: Context, lang: String) {
        prefs(context).edit { putString(KEY_PACK, lang) }
    }

    private fun packAssets(context: Context): List<String> =
        runCatching { context.assets.list("")?.filter { it.endsWith(".db") }.orEmpty() }
            .getOrDefault(emptyList())
            .sorted()

    /** The history of opened entries. The policy --dedupe, order, cap-- lives in the
     * ViewModel, which the gate can see; here it is only serialised. */
    fun history(context: Context): List<Visit> =
        parseVisits(prefs(context).getString(KEY_HISTORY, null).orEmpty())

    fun rememberHistory(context: Context, visits: List<Visit>) {
        prefs(context).edit { putString(KEY_HISTORY, serializeVisits(visits)) }
    }

    /**
     * The history the **tile** can show: already filtered to the installed packs.
     *
     * ⚠️ **A key of its own and not a filter in the tile**, because a tile **cannot** know which
     * packs are there without opening them, and opening a pack in a tile is forbidden (D-106):
     * `onTileRequest` is `@MainThread` and has 10 seconds. What it needs is left written by the
     * app, which does have the context -- the same pattern as the week of words of the day.
     *
     * If it was never written, it falls back to the full history: a freshly updated app cannot be
     * left with an empty tile until somebody opens a word.
     */
    fun tileHistory(context: Context): List<Visit> =
        prefs(context).getString(KEY_TILE_HISTORY, null)
            ?.let(::parseVisits)
            ?: history(context)

    fun rememberTileHistory(context: Context, visits: List<Visit>) {
        prefs(context).edit { putString(KEY_TILE_HISTORY, serializeVisits(visits)) }
    }

    /**
     * Deletes a pack from disk. **Irreversible**: putting it back costs ~90 s over adb.
     *
     * It takes the file name and not the `packId` on purpose: they are different things, and
     * deriving one from the other would delete the wrong file the day they stop matching.
     *
     * It does NOT close the connection: that belongs to whoever opened it and has to happen
     * **first**. On Unix a deleted file with an open descriptor keeps occupying the disk until
     * it is closed, and the app would go on reading it as if nothing happened -- meaning the
     * user sees a deletion and no space freed, which is worse than not being able to delete.
     */
    fun deletePack(context: Context, fileName: String): Boolean {
        forgetDownloaded(context, fileName)
        val target = File(packsDir(context), fileName)
        return target.isFile && target.delete()
    }

    /** The saved words. Same codec as the history: they are the same shape of data. */
    fun favorites(context: Context): List<Visit> =
        parseVisits(prefs(context).getString(KEY_FAVORITES, null).orEmpty())

    fun rememberFavorites(context: Context, visits: List<Visit>) {
        prefs(context).edit { putString(KEY_FAVORITES, serializeVisits(visits)) }
    }

    /**
     * The week of words of the day the tiles read, and the date it runs from.
     *
     * It exists because **a tile cannot compute it**: `onTileRequest` runs on the main thread
     * with a 10 s cap, and opening a pack of tens or hundreds of MB there is ruled out by the
     * API contract. The app, which already has it open, precomputes it and leaves it written.
     *
     * Same codec as the history and the saved words (D-102): it is the same shape of data and a
     * second format is a second format that can diverge. The date goes in its own key instead of
     * as a fifth field, precisely so that codec is left untouched.
     */
    fun weekWords(context: Context): Pair<String?, List<Visit>> {
        val prefs = prefs(context)
        return prefs.getString(KEY_WEEK_SINCE, null) to
            parseVisits(prefs.getString(KEY_WEEK_WORDS, null).orEmpty())
    }

    fun rememberWeekWords(context: Context, since: String, words: List<Visit>) {
        prefs(context).edit {
            putString(KEY_WEEK_SINCE, since)
            putString(KEY_WEEK_WORDS, serializeVisits(words))
        }
    }

    /** The settings. Same as the history: the policy lives above, here it is only serialised. */
    fun settings(context: Context): Settings =
        parseSettings(prefs(context).getString(KEY_SETTINGS, null).orEmpty())

    fun rememberSettings(context: Context, settings: Settings) {
        prefs(context).edit { putString(KEY_SETTINGS, serializeSettings(settings)) }
    }

    /** The `versionCode` of the APK that is running. */
    private fun versionCode(context: Context): Int =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()

    private fun prefs(context: Context) =
        context.getSharedPreferences("dictionary", Context.MODE_PRIVATE)

    private const val KEY_PACK = "pack_activo"
    private const val KEY_HISTORY = "historial"
    private const val KEY_TILE_HISTORY = "historial_tile"
    private const val KEY_SETTINGS = "ajustes"
    /** The APK's packs the user replaced from the catalog. See [assetsToExtract]. */
    private const val KEY_DOWNLOADED = "packs_del_catalogo"

    /** Which packs have already passed D-142's key sample. See [PackVerification]. */
    private const val KEY_VERIFIED = "packs_verificados"
    /** Which version of the app last extracted the APK's packs. */
    private const val KEY_EXTRACTED_BY = "packs_extraidos_por"
    private const val KEY_FAVORITES = "favoritos"
    private const val KEY_WEEK_WORDS = "palabras_semana"
    private const val KEY_WEEK_SINCE = "palabras_desde"


    /**
     * Every installed pack, by name.
     *
     * This used to return **only the first one**, and with two packs that hid Spanish in silence
     * because "en-..." sorts before "es-...". The order still matters --two launches have to see
     * the same list-- but it no longer decides which one opens.
     */
    internal fun installedPacks(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".db") }
            ?.sortedBy { it.name }
            ?.toList()
            .orEmpty()

    /**
     * Copies to a temporary file and only renames it at the very end.
     *
     * The rename is what matters, and it is the only thing that makes this correct: if the copy
     * is cut in half --the disk fills up, the user kills the app-- what is left is a `.part`, not
     * a half-written `.db`. **A truncated pack opens without error** and returns fewer results
     * than it holds, which is exactly the symptom this project cannot observe.
     *
     * It is `internal` and free of `Context` so it can be tested on the JVM: atomicity is a
     * property of the filesystem, not of Android.
     */
    internal fun installAtomically(
        input: InputStream,
        dir: File,
        name: String,
        /**
         * The sha256 the catalog says this pack has, if it is known.
         *
         * ⚠️ **This is the only moment a hash is any use, and here it comes almost free**: the
         * bytes are already going past to be copied, so digesting them adds no read. Checking it
         * on every launch, by contrast, would force re-reading 301 MB -- which is why the
         * per-launch check is [PackVerification]'s cheap fingerprint and not this. They are two
         * different questions: here *"did the published bytes arrive?"*, there *"is it the same
         * file I already tested?"*.
         *
         * `null` when there is nothing to compare against --the demo pack comes out of the
         * assets-- and then **nothing is digested**: computing a hash nobody looks at, over
         * hundreds of MB and on a watch, is not free.
         */
        expectedSha256: String? = null,
    ): File {
        val partial = File(dir, "$name.part")
        val target = File(dir, name)
        partial.delete()
        val digest = expectedSha256?.let { MessageDigest.getInstance("SHA-256") }
        try {
            input.use { from ->
                val source = if (digest == null) from else DigestInputStream(from, digest)
                partial.outputStream().use { target -> source.copyTo(target, BUFFER) }
            }
        } catch (e: IOException) {
            // If it is not deleted, the next attempt starts on garbage and wastes disk too.
            partial.delete()
            throw e
        }
        if (digest != null) {
            val calculado = digest.digest().joinToString("") { "%02x".format(it) }
            // ⚠️ **It is compared BEFORE renaming, and the order is everything**: renaming first
            // would leave a corrupt dictionary named like the good one, and the next launch opens
            // it without complaint -- a truncated or different `.db` is valid SQLite.
            if (!calculado.equals(expectedSha256.trim(), ignoreCase = true)) {
                partial.delete()
                throw IOException(
                    "el pack descargado no coincide con lo publicado: se esperaba " +
                        "${expectedSha256.trim()} y llegó $calculado",
                )
            }
        }
        if (!partial.renameTo(target)) {
            partial.delete()
            throw IOException("no se pudo renombrar ${partial.name}")
        }
        return target
    }

    /**
     * Copies the APK's core **only if it carries a higher `data_version`** than the one on disk.
     *
     * It is the executable half of [ExtractionPlan.compare]. The order is the same contract as in
     * [installAtomically]: decide first, overwrite after. Comparing after copying would not be
     * comparing.
     *
     * ⚠️ **The asset's version is READ FROM THE INDEX, not from the asset.** The first version of
     * this extracted the pack to a `.candidate` so it could be opened and asked --SQLite does not
     * open a `.db` inside the APK (D-173)-- which cost copying 50.8 MB to read a 12-digit integer.
     * The index solves it at zero cost: `bundlePacks` writes it in the build, from the **same
     * `index.json` `packserver.py` serves**. See [coreIndex].
     *
     * ⚠️ **With no declared version, NOTHING is touched**, and that is the safe state: without
     * knowing which is newer, overwriting the user's pack is exactly the silent downgrade this
     * comes to prevent. The build warns when that happens.
     *
     * ⚠️ **When the APK wins the catalog mark is forgotten**, because the file went back to being
     * the APK's and the mark would say otherwise.
     */
    private fun extractIfNewer(context: Context, dir: File, asset: String, delApk: Long?) {
        val enDisco = File(dir, asset)
        if (delApk == null) {
            DictLog.w { "$asset: el APK no declara version, se deja el de disco" }
            return
        }
        val delUsuario = dataVersionOf(enDisco)
        if (delUsuario == null) {
            // What is there could not be opened. That is NOT the same as "it is old": it may be
            // the disk or a half-copied file, and overwriting it would erase a pack that tomorrow
            // would open fine.
            DictLog.w { "$asset: no se pudo leer la version del de disco, se lo deja" }
            return
        }
        if (delApk <= delUsuario) {
            DictLog.i { "$asset: el de disco es igual o mas nuevo ($delUsuario >= $delApk)" }
            return
        }
        installAtomically(context.assets.open(asset), dir, asset)
        // The file went back to being the APK's: the catalog mark is no longer true.
        forgetDownloaded(context, asset)
        DictLog.i { "$asset: el APK trae uno mas nuevo ($delApk > $delUsuario), reemplazado" }
    }

    /**
     * The `data_version` a pack **on disk** declares, or `null` if it cannot be known.
     *
     * `verifyKeys = false` on purpose: here the question is **which of the two is newer**, not
     * whether the pack works. That is answered afterwards, on really opening it, with D-142's
     * 64-key sample -- the one D-164 measured at 36 ms and that need not be paid twice. The cheap
     * part (`schema_version`, `norm_version`, the codec and the dictionary's sha256) is checked
     * anyway, because `PackFile.open` always checks it.
     */
    private fun dataVersionOf(file: File): Long? =
        runCatching { PackFile.open(file.path, verifyKeys = false).use { it.metadata.dataVersion } }
            .getOrNull()

    /**
     * The versions the APK declares for the packs it carries inside.
     *
     * ⚠️ **It is the index, and it does in the APK what `index.json` does on the server.**
     * `bundlePacks` writes it in the build, reading the same `index.json` that
     * `packserver.py --index-only` produces, and validating that the declared size matches the
     * file -- an old index beside a new pack would declare a version that is not true, and that
     * would be worse than declaring none.
     *
     * Format: `name<TAB>data_version`, one line per pack. TSV and not JSON because it is two lines
     * and the parser runs on a watch: `split('\t')` cannot throw an exception nobody expects.
     *
     * A line that is not understood is **ignored**, and the cost of ignoring it is that the pack
     * gets treated as having no declared version, which is the safe state.
     */
    private fun coreIndex(context: Context): Map<String, Long> =
        runCatching {
            parseCoreIndex(context.assets.open(CORE_INDEX).bufferedReader().use { it.readText() })
        }.getOrDefault(emptyMap())

    /**
     * The index parser, **pure and free of Android so the gate covers it** (D-072).
     *
     * ⚠️ **This is code that fails by returning an empty map**, which is the exact failure shape
     * this repo cannot see: with no declared versions the cores never update, and there is no
     * exception and no error log. That is why it is split from reading the asset, and tested.
     *
     * A line that cannot be parsed is ignored rather than fatal: the cost of ignoring it is that
     * pack having no declared version, which is the safe state. Throwing on one bad line would
     * leave **every** pack without one.
     */
    internal fun parseCoreIndex(texto: String): Map<String, Long> =
        texto.lineSequence()
            .mapNotNull { linea ->
                val campos = linea.split('\t')
                val version = campos.getOrNull(1)?.trim()?.toLongOrNull()
                if (campos.size == 2 && campos[0].isNotBlank() && version != null) {
                    campos[0].trim() to version
                } else {
                    null
                }
            }
            .toMap()

    /**
     * Opens a pack, skipping the key sample **if this same file already passed it**.
     *
     * Measured: the sample is 36 of the 42 ms each launch cost with the two real packs, and the
     * pack is immutable (D-001), so testing the same file again proves nothing new. What makes
     * this safe and not a shortcut is in [PackVerification]: the fingerprint carries
     * `NORM_VERSION`, so a change in `norm()` or `fuzzy()` tests everything again.
     *
     * ⚠️ **It is recorded AFTER opening successfully, never before.** Recording first would turn a
     * pack that fails half way into a pack that next time is not even checked.
     */
    /**
     * Opens a pack, or says why not -- **without testing again what was already tested**.
     *
     * ⚠️ **A recorded rejection cuts in before the file is opened.** That is the difference from
     * the earlier version, which remembered only the successes: an incompatible `.db` was reopened,
     * its `meta` read and discarded on **every launch**. Now, if the memo says this same file was
     * already rejected under these same rules, the disk is not touched.
     *
     * What makes that not dangerous is that the fingerprint carries the whole rules: see
     * [PackVerification.rules]. Without that, a rejection would outlive the version of the app
     * that would already know how to read that pack, and the user would see a dictionary gone
     * forever.
     */
    private fun openFile(context: Context, file: File): PackLoad {
        val memo = prefs(context).getString(KEY_VERIFIED, null)
        val fingerprint = PackVerification.fingerprint(
            file.length(),
            file.lastModified(),
            PackVerification.rules(
                TextNormalizer.NORM_VERSION,
                PackFile.SUPPORTED_SCHEMA_VERSION,
                PayloadCodec.CODEC_ID,
                // ⚠️ **Installing a new app expires the whole memo**, and it is the only one of
                // the five nobody can forget to raise: the installer rejects a downgrade (D-095).
                // See [PackVerification.rules].
                versionCode(context),
            ),
        )
        val anotado = PackVerification.verdict(memo, file.name, fingerprint)
        if (anotado is PackVerification.Verdict.Rejected) {
            DictLog.d { "pack ${file.name} ya rechazado (${anotado.rejection.id}), no se abre" }
            return PackLoad.Unusable(anotado.rejection)
        }
        val yaVerificado = anotado == PackVerification.Verdict.Passed
        return try {
            val pack = PackFile.open(file.path, verifyKeys = !yaVerificado)
            if (!yaVerificado) recordar(context, memo, file.name, fingerprint, null)
            PackLoad.Ready(SqlitePackSource(pack))
        } catch (e: PackFile.IncompatibleException) {
            // The pack is from another format version or from other normalization rules. It
            // would return FEWER results than it holds, in silence: that is why it is rejected
            // whole instead of being opened anyway (D-001, D-006).
            recordar(context, memo, file.name, fingerprint, e.rejection)
            PackLoad.Unusable(e.rejection, e.message.orEmpty())
        } catch (e: Exception) {
            // ⚠️ **This is NOT recorded**, and the asymmetry is deliberate. An
            // `IncompatibleException` is a verdict about the pack's content and will not change on
            // its own; any other exception may be the disk, memory or a half-copied file, and
            // caching that would hide forever a pack that next time would have opened fine.
            DictLog.e(e) { "pack ${file.name}: fallo no atribuible al contenido" }
            PackLoad.Unusable(PackRejection.DAMAGED, e.message.orEmpty())
        }
    }

    private fun recordar(
        context: Context,
        memo: String?,
        name: String,
        fingerprint: String,
        rejection: PackRejection?,
    ) {
        prefs(context).edit {
            putString(KEY_VERIFIED, PackVerification.remember(memo, name, fingerprint, rejection))
        }
    }

    /**
     * The version index `bundlePacks` leaves in `assets/`.
     *
     * ⚠️ **The same literal lives in `app/build.gradle.kts` (`CORE_INDEX`)**: they are the two
     * ends of one file and there is no way to share a constant between the build script and the
     * code. If they stop matching, the index **is not read and nothing fails** -- the cores are
     * left with no declared version and never update. That is why
     * `audit_dictionary.check_core_index_name` watches it.
     */
    internal const val CORE_INDEX = "core-index.tsv"

    private const val BUFFER = 256 * 1024
}
