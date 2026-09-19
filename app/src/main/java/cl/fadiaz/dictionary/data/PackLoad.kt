package cl.fadiaz.dictionary.data

import cl.fadiaz.dictionary.core.DictionarySource

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
     * There is a file and it is no good. The message goes to the screen, in Spanish.
     *
     * Keeping it separate from [NoPack] matters: a pack from another `schema_version` or another
     * `norm_version` would return FEWER results than it holds, with no error at all (D-001,
     * D-006). Opening it anyway would be worse than not opening it.
     */
    data class Unusable(val reason: String) : PackLoad
}
