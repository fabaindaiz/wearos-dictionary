package cl.fadiaz.dictionary.data

import cl.fadiaz.dictionary.core.DictionarySource
import cl.fadiaz.dictionary.core.PackRejection

/**
 * The result of trying to open a pack.
 *
 * It lives in its own file and **without a single reference to Android** on purpose: it is the
 * boundary that lets `SearchViewModel` be tested on the JVM, in milliseconds and inside the gate.
 * The day a `Context` shows up here, those tests move to a device along with it.
 *
 * The three cases are not over-defensive: each one looks different on screen and the user can do
 * something different about each one.
 */
sealed interface PackLoad {

    /** There is a dictionary. */
    data class Ready(val source: DictionarySource) : PackLoad

    /** No pack installed and no asset to extract one from: the APK was built without one. */
    data object NoPack : PackLoad

    /**
     * There is a file and it is no good.
     *
     * Keeping it separate from [NoPack] matters: a pack from another `schema_version` or another
     * `norm_version` would return FEWER results than it holds, with no error at all (D-001,
     * D-006). Opening it anyway would be worse than not opening it.
     *
     * ⚠️ **[rejection] is the reason as DATA, and it used to be a Spanish sentence.** That is
     * what lets the dictionaries screen write one translated line (D-127) and lets the memo
     * remember the verdict without carrying prose. [detail] keeps the concrete values --what it
     * declared, what was expected-- and stays **for `logcat`, not for the user**.
     */
    data class Unusable(
        val rejection: PackRejection,
        val detail: String = "",
    ) : PackLoad
}
