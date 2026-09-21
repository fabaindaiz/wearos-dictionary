package cl.fadiaz.dictionary.data

import android.content.Context
// `edit { }` y no `.edit()....apply()`: la segunda forma compila sin el `apply()`
// final y entonces NO guarda nada, sin error y sin log. La lambda no se puede
// olvidar. Viene de core-ktx, que ya estaba en el classpath.
import androidx.core.content.edit
import cl.fadiaz.dictionary.R
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
    internal fun missingFromDisk(assets: List<String>, installed: List<String>): List<String> {
        val alreadyOnDisk = installed.toSet()
        return assets.filterNot { it in alreadyOnDisk }.sorted()
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

        val missing = missingFromDisk(packAssets(context), installedPacks(dir).map { it.name })
        if (missing.isNotEmpty()) {
            onExtracting()
            for (asset in missing) {
                runCatching { installAtomically(context.assets.open(asset), dir, asset) }
            }
        }

        val installed = installedPacks(dir)
        if (installed.isEmpty()) return@withContext PackSet.NoPack

        // The ones that came from the APK are demos: they get marked so they cannot beat an
        // installed dictionary.
        val fromAssets = packAssets(context).toSet()
        val opened = mutableListOf<PackHandle.Open>()
        val problems = mutableListOf<String>()
        for (file in installed) {
            when (val loaded = openFile(context, file)) {
                is PackLoad.Ready ->
                    opened += PackHandle.Open(
                        source = loaded.source,
                        isDemo = file.name in fromAssets,
                        fileName = file.name,
                        bytes = file.length(),
                    )
                is PackLoad.Unusable -> problems += "${file.name}: ${loaded.reason}"
                PackLoad.NoPack -> Unit
            }
        }

        // El memo de verificados se poda con lo que quedó en disco: un pack borrado deja una
        // línea que ya no sirve, y sin esto crecería para siempre en SharedPreferences.
        prefs(context).edit {
            putString(
                KEY_VERIFIED,
                PackVerification.prune(
                    prefs(context).getString(KEY_VERIFIED, null),
                    installed.map { it.name }.toSet(),
                ),
            )
        }

        // El activo sale de las MISMAS reglas que deciden a quién se consulta: elegirlo aparte
        // dejaba entrar un build viejo, que después se consultaba igual por ser el activo.
        val chosen = activePack(opened, preferred)
            ?: return@withContext PackSet.Unusable(
                problems.firstOrNull() ?: context.getString(R.string.pack_none_opened),
            )

        PackSet.Ready(chosen, opened, problems)
    }

    /** The chosen language, so the watch opens the same dictionary as last time. */
    fun preferredPack(context: Context): String? =
        prefs(context).getString(KEY_PACK, null)

    fun rememberPack(context: Context, packId: String) {
        prefs(context).edit { putString(KEY_PACK, packId) }
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

    private fun prefs(context: Context) =
        context.getSharedPreferences("dictionary", Context.MODE_PRIVATE)

    private const val KEY_PACK = "pack_activo"
    private const val KEY_HISTORY = "historial"
    private const val KEY_SETTINGS = "ajustes"
    /** Qué packs ya pasaron la muestra de claves de D-142. Ver [PackVerification]. */
    private const val KEY_VERIFIED = "packs_verificados"
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
         * El sha256 que el catálogo dice que este pack tiene, si se conoce.
         *
         * ⚠️ **Éste es el único momento en que un hash sirve, y acá sale casi gratis**: los bytes
         * ya están pasando para copiarse, así que digerirlos no agrega una lectura. Comprobarlo
         * en cada arranque, en cambio, obligaría a releer 301 MB — por eso el chequeo por
         * arranque es la huella barata de [PackVerification] y no esto. Son dos preguntas
         * distintas: acá *«¿llegaron los bytes que se publicaron?»*, allá *«¿es el mismo archivo
         * que ya probé?»*.
         *
         * `null` cuando no hay contra qué comparar --el pack de demostración sale de los assets--
         * y entonces **no se digiere nada**: calcular un hash que nadie mira, sobre cientos de
         * MB y en un reloj, no es gratis.
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
            // ⚠️ **Se compara ANTES de renombrar, y el orden es todo**: renombrar primero dejaría
            // un diccionario corrupto llamándose como el bueno, y el siguiente arranque lo abre
            // sin quejarse -- un `.db` truncado o distinto es SQLite válido.
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
     * Abre un pack, salteando la muestra de claves **si este mismo archivo ya la pasó**.
     *
     * Medido: la muestra son 36 de los 42 ms que costaba cada arranque con los dos packs reales,
     * y el pack es inmutable (D-001), así que volver a probar el mismo archivo no prueba nada
     * nuevo. Lo que hace que esto sea seguro y no un atajo está en [PackVerification]: la huella
     * lleva `NORM_VERSION`, así que un cambio en `norm()` o `fuzzy()` vuelve a probar todo.
     *
     * ⚠️ **Se anota DESPUÉS de abrir bien, nunca antes.** Anotar primero convertiría un pack que
     * falla a medias en un pack que la próxima vez ni se revisa.
     */
    private fun openFile(context: Context, file: File): PackLoad =
        try {
            val memo = prefs(context).getString(KEY_VERIFIED, null)
            val fingerprint = PackVerification.fingerprint(
                file.length(),
                file.lastModified(),
                TextNormalizer.NORM_VERSION,
            )
            val yaVerificado = PackVerification.isVerified(memo, file.name, fingerprint)
            val pack = PackFile.open(file.path, verifyKeys = !yaVerificado)
            if (!yaVerificado) {
                prefs(context).edit {
                    putString(KEY_VERIFIED, PackVerification.remember(memo, file.name, fingerprint))
                }
            }
            PackLoad.Ready(SqlitePackSource(pack))
        } catch (e: PackFile.IncompatibleException) {
            // The pack is from another format version or from other normalization rules. It
            // would return FEWER results than it holds, in silence: that is why it is rejected
            // whole instead of being opened anyway (D-001, D-006).
            PackLoad.Unusable(context.getString(R.string.pack_incompatible, e.message.orEmpty()))
        } catch (e: Exception) {
            PackLoad.Unusable(context.getString(R.string.pack_damaged, e.message.orEmpty()))
        }

    private const val BUFFER = 256 * 1024
}
