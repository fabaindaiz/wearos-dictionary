package cl.fadiaz.dictionary.data

import android.util.Log

/**
 * The only place from which this app writes to `logcat`.
 *
 * ## How it is switched on
 *
 * ```sh
 * adb shell setprop log.tag.Dict DEBUG   # the detail, with no recompile and no reinstall
 * adb logcat -s Dict:V
 * adb shell setprop log.tag.Dict INFO    # and off again
 * ```
 *
 * ⚠️ **This is NOT `BuildConfig.DEBUG`, and the difference is why the file exists.** With
 * `BuildConfig.DEBUG` R8 strips the calls in `release`, and `benchmark` inherits from `release`
 * (see `build.gradle.kts`) -- meaning **the build that startup and battery are measured with would
 * be left with no logs**, which is exactly where they are needed. `Log.isLoggable` works the same
 * in all three builds and is controlled from outside.
 *
 * ## The cost contract
 *
 * Messages are passed as a **lambda, not a String**. `Log.d(TAG, "x=" + x)` builds the String even
 * when the log is off; `d { "x=$x" }` executes nothing if nobody is listening. A test pins that:
 * [cl.fadiaz.dictionary.data.DictLogTest].
 *
 * `INFO` and above are always visible --they are the events you can count on your fingers: packs
 * opening, packs being rejected, tiles being requested--. `DEBUG` is the per-query detail and is
 * off unless asked for.
 */
object DictLog {

    /** A single tag for the whole app: `adb logcat -s Dict:V` and that is everything. */
    const val TAG = "Dict"

    /** Events that happen rarely and explain the state: packs, tiles, downloads. */
    inline fun i(msg: () -> String) {
        if (Log.isLoggable(TAG, Log.INFO)) Log.i(TAG, msg())
    }

    /** Something went wrong but the app carries on: a rejected pack, a download that did not add up. */
    inline fun w(msg: () -> String) {
        if (Log.isLoggable(TAG, Log.WARN)) Log.w(TAG, msg())
    }

    /** Per-query detail. **Off** unless `setprop log.tag.Dict DEBUG`. */
    inline fun d(msg: () -> String) {
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, msg())
    }

    /** A failure with its exception. The only one that takes a `Throwable`. */
    inline fun e(error: Throwable?, msg: () -> String) {
        if (Log.isLoggable(TAG, Log.ERROR)) Log.e(TAG, msg(), error)
    }

    /** Is the detail on? So as not to assemble a report nobody will read. */
    val verbose: Boolean get() = Log.isLoggable(TAG, Log.DEBUG)
}
