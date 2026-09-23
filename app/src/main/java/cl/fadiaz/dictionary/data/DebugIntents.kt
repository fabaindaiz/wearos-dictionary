package cl.fadiaz.dictionary.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.edit
import cl.fadiaz.dictionary.BuildConfig

/**
 * Asking the app things **from `adb`**, with no finger.
 *
 * ## Why it exists
 *
 * ⚠️ **The search field does not take focus from a synthetic tap.** Same shape as the problem
 * that pinned espresso 3.7.0 (D-093), and not a theoretical one: it left **D-168** (the fallback
 * between languages) and **D-169** (tappable synonyms) unverified *with the watch in hand*, and
 * it cost again on the emulator when the question was whether a core pack returns results — that
 * had to be answered by reading the `.db` with `sqlite3`, which checks the data and not the app.
 *
 * What this enables is everything that **ends up on screen**, which is exactly what the gate
 * cannot see: result order over the real pack, the language fallback, the synonyms, and anything
 * that depends on typing.
 *
 * ## What can be asked
 *
 * ```sh
 * # Seed a query, as if it had been typed. `-p` BEFORE the extra: see ACTION_CLEAR.
 * adb shell am broadcast -p cl.fadiaz.dictionary \
 *     -a cl.fadiaz.dictionary.DEBUG_SEARCH -e q "hous"
 *
 * # Clear the field and go back to the home screen:
 * adb shell am broadcast -p cl.fadiaz.dictionary -a cl.fadiaz.dictionary.DEBUG_CLEAR
 *
 * # Dump the state to logcat: which packs opened, which is active, and the verification memo:
 * adb shell am broadcast -p cl.fadiaz.dictionary -a cl.fadiaz.dictionary.DEBUG_DUMP
 * adb logcat -s Dict:V
 * ```
 *
 * The dump is item 3 of the roadmap's tooling table, and what it closes is that
 * [PackVerification] **can be checked on the device**: until now the memo could only be read with
 * `run-as`, which does not exist over a `benchmark` APK.
 *
 * ## Why it is not in release, and how that is guaranteed
 *
 * ⚠️ **An exported receiver in production is attack surface and battery.** The guarantee is not
 * that nobody registers it: it is that `BuildConfig.DEBUG_INTENTS` is a **constant** `false` in
 * `release`, so R8 folds the `if`, the class loses its references and **leaves the dex**. It is
 * not that it goes unused — it does not exist. Same treatment D-213 gave cleartext HTTP, and the
 * reason the gate is a `buildConfigField` and not a runtime check.
 *
 * ⚠️ **And it is on in `benchmark`** despite inheriting from `release`: that is the build used to
 * measure (D-166) and the only release-like one that installs. If it were the only one unable to
 * seed a query, measuring a search would need a finger again.
 */
object DebugIntents {

    /** Seed a query. The text travels in the `q` extra. */
    const val ACTION_SEARCH = "cl.fadiaz.dictionary.DEBUG_SEARCH"

    /** Dump the state to `logcat`, under the `Dict` tag. */
    const val ACTION_DUMP = "cl.fadiaz.dictionary.DEBUG_DUMP"

    /**
     * Clear the field, which is the other half of any search probe.
     *
     * ⚠️ **Its own action rather than `-e q ""`, and the reason came from using it.** An empty
     * extra **does not survive `adb shell`**: the device's shell eats the empty string and the
     * next argument takes its place. Measured 2026-09-23 -- `am broadcast -a ... -e q "" -p
     * cl.fadiaz.dictionary` left the app searching for the literal **`-p`**, and lost the package
     * filter on the way. An action with no extras has nothing that can be eaten.
     */
    const val ACTION_CLEAR = "cl.fadiaz.dictionary.DEBUG_CLEAR"

    /**
     * Change one of the app's internal knobs **without rebuilding it**, from `adb`.
     *
     * The knobs, what each accepts and which ones must never reach the Settings screen are in
     * [DebugKnobs]; this is only the door. Extras are read by NAME, so several travel in one
     * broadcast and a typo names itself instead of doing nothing.
     *
     * ```sh
     * adb shell am broadcast -p cl.fadiaz.dictionary \
     *     -a cl.fadiaz.dictionary.DEBUG_SET -e catalog http://localhost:8765
     * adb shell am broadcast -p cl.fadiaz.dictionary \
     *     -a cl.fadiaz.dictionary.DEBUG_SET -e scale LARGE -e catalog https://example.invalid
     * adb shell am broadcast -p cl.fadiaz.dictionary \
     *     -a cl.fadiaz.dictionary.DEBUG_SET        # clears every override
     * adb logcat -s Dict:V
     * ```
     *
     * ⚠️ **Clearing is the ABSENCE of extras**, which is forced rather than chosen: an empty
     * extra does not survive `adb shell`, the same trap that gave [ACTION_CLEAR] its own action.
     * Here it lands well -- no knobs named, nothing overridden.
     */
    const val ACTION_SET = "cl.fadiaz.dictionary.DEBUG_SET"

    /** The extra carrying the text to search for. */
    const val EXTRA_QUERY = "q"

    private const val PREFS = "debug"
    private const val KEY_BY = "escrito_por"

    /**
     * The overrides in force, **after discarding those a previous build wrote**.
     *
     * ⚠️ **The expiry happens on READ and not on install**, and that is deliberate: there is no
     * hook that runs when a new APK replaces an old one, only the next launch. Doing it here means
     * there is no window in which a stale override is live, and no second place that has to
     * remember to call a reset.
     */
    private fun overrides(context: Context): Map<String, String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val escritoPor = prefs.getInt(KEY_BY, 0)
        if (!DebugKnobs.survives(escritoPor, BuildConfig.VERSION_CODE)) {
            if (prefs.all.keys.any { it != KEY_BY }) {
                DictLog.i {
                    "debug: los overrides eran de la version $escritoPor y esta es la " +
                        "${BuildConfig.VERSION_CODE}: se descartan"
                }
            }
            prefs.edit { clear() }
            return emptyMap()
        }
        return DebugKnobs.KNOBS.mapNotNull { k ->
            prefs.getString(k.key, null)?.takeIf { it.isNotBlank() }?.let { k.key to it }
        }.toMap()
    }

    /** Every override in force, for the dump. Empty when the build is running as built. */
    fun activeOverrides(context: Context): Map<String, String> = overrides(context)

    /**
     * The catalogue url in force: the `adb` override if one applies, otherwise the built-in one.
     *
     * ⚠️ **Call it behind `if (BuildConfig.DEBUG_INTENTS)`, never bare.** That is what lets R8
     * fold the branch in release and drop this whole class from the dex, which is the guarantee
     * the class header describes.
     */
    fun catalogUrl(context: Context): String =
        overrides(context)[DebugKnobs.CATALOG] ?: BuildConfig.CATALOG_URL

    /**
     * Registers the receiver if this build allows it, and returns how to take it down.
     *
     * Returns `null` when the gate is shut, which is what lets a caller write
     * `DebugIntents.install(...)?.let { ... }` without asking which build it is.
     *
     * ⚠️ **`RECEIVER_EXPORTED` is required for `am broadcast` to arrive**, and that is exactly
     * why this cannot exist in release. The flag is mandatory to declare from API 34; here the
     * needed value is declared and paid for by switching the whole thing off per build type.
     */
    fun install(
        context: Context,
        onSearch: (String) -> Unit,
        onDump: () -> Unit,
    ): (() -> Unit)? {
        if (!BuildConfig.DEBUG_INTENTS) return null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_SEARCH -> {
                        val texto = intent.getStringExtra(EXTRA_QUERY).orEmpty()
                        DictLog.i { "debug: seed query '$texto'" }
                        onSearch(texto)
                    }
                    ACTION_CLEAR -> {
                        DictLog.i { "debug: clear the field" }
                        onSearch("")
                    }
                    ACTION_SET -> onSet(context, intent)
                    ACTION_DUMP -> onDump()
                }
            }
        }
        val filtro = IntentFilter().apply {
            addAction(ACTION_SEARCH)
            addAction(ACTION_CLEAR)
            addAction(ACTION_DUMP)
            addAction(ACTION_SET)
        }
        // No compatibility branch: `minSdk` is 33 and the flag exists from 33, so asking about
        // `SDK_INT` would be dead code -- lint caught it, and lint breaks the build here
        // (ObsoleteSdkInt).
        context.registerReceiver(receiver, filtro, Context.RECEIVER_EXPORTED)
        DictLog.i {
            "debug: debug intents ACTIVE ($ACTION_SEARCH, $ACTION_DUMP, $ACTION_SET)"
        }
        // The url in force goes out at startup, not only on a dump: a download that 404s is read
        // as "the server is wrong" far more often than as "the app is pointing somewhere else".
        DictLog.i { "debug: catalogo en uso: ${catalogUrl(context)}" }
        overrides(context).forEach { (k, v) -> DictLog.i { "debug: override $k=$v" } }
        return { context.unregisterReceiver(receiver) }
    }

    /**
     * Applies every knob named in the broadcast, reporting each one by name.
     *
     * ⚠️ **The versionCode is stamped on every write**, which is what makes the expiry in
     * [overrides] possible. Writing a knob without it would leave an override nothing can date.
     */
    private fun onSet(context: Context, intent: Intent) {
        val extras = intent.extras
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val claves = extras?.keySet().orEmpty().filter { it != KEY_BY }
        if (claves.isEmpty()) {
            prefs.edit { clear() }
            DictLog.i { "debug: sin overrides, la app corre como fue construida" }
            DebugKnobs.help().forEach { linea -> DictLog.i { linea } }
            return
        }
        prefs.edit {
            claves.forEach { clave ->
                val valor = extras?.getString(clave)?.trim().orEmpty()
                val resultado = DebugKnobs.write(clave, valor)
                DictLog.i { DebugKnobs.describe(resultado) }
                when (resultado) {
                    is DebugKnobs.Written.Ok -> putString(clave, valor)
                    is DebugKnobs.Written.Cleared -> remove(clave)
                    // A bad value and an unknown key both change NOTHING: a half-applied
                    // broadcast is worse than a rejected one, because the next probe runs
                    // against a state nobody described.
                    else -> Unit
                }
            }
            putInt(KEY_BY, BuildConfig.VERSION_CODE)
        }
    }

    /**
     * The dump, as text. Pure and free of Android **so the gate covers it** (D-072).
     *
     * ⚠️ **In lines rather than one string**, because `logcat` truncates long messages and the
     * verification memo can hold one line per pack: a truncated dump is worse than none, because
     * it looks complete.
     */
    fun dump(
        opened: List<String>,
        active: String?,
        rejected: List<String>,
        memo: String?,
        appVersion: Int,
        /**
         * The build identity: commit, `+dirty`, and when.
         *
         * ⚠️ **It goes FIRST and not last**, because it decides whether the rest of the dump is
         * worth anything: a readout describing an APK other than the one you think you are
         * looking at is worse than no readout.
         */
        buildId: String = "",
        /**
         * The catalogue url in force.
         *
         * ⚠️ **It belongs in the dump because it can now be changed at runtime.** While it was
         * a compile-time constant, the APK identity above answered it implicitly; with an `adb`
         * override, two builds with the same commit can be pointing at different servers, and a
         * download that 404s reads as a broken server rather than a misaimed app.
         */
        catalog: String = "",
        /**
         * The knobs an `adb` broadcast has overridden, if any.
         *
         * ⚠️ **Empty is worth printing as "none"**, because the question it answers is *"is
         * this build behaving as built?"* and silence cannot distinguish a clean build from a
         * dump that forgot to ask.
         */
        overrides: Map<String, String> = emptyMap(),
    ): List<String> = buildList {
        add("--- volcado ---")
        add("app versionCode=$appVersion${buildId.takeIf { it.isNotBlank() }?.let { " build=$it" }.orEmpty()}")
        if (catalog.isNotBlank()) add("catalogo=$catalog")
        add(
            if (overrides.isEmpty()) "overrides: ninguno (corre como fue construida)"
            else "overrides: " + overrides.entries.joinToString { "${it.key}=${it.value}" },
        )
        add("activo=${active ?: "(ninguno)"}")
        add("abiertos=${opened.size}${if (opened.isEmpty()) "" else ": " + opened.joinToString()}")
        add(
            "rechazados=${rejected.size}" +
                if (rejected.isEmpty()) "" else ": " + rejected.joinToString(),
        )
        val lineas = memo.orEmpty().lineSequence().filter { it.isNotBlank() }.toList()
        add("memo de verificacion: ${lineas.size} anotacion(es)")
        // ⚠️ One line per annotation, and the whole fingerprint: it is the datum that proves a
        // new `versionCode` expired the memo (D-225), and abbreviating it would make that
        // uncheckable.
        lineas.forEach { add("  $it") }
        add("--- fin ---")
    }
}
