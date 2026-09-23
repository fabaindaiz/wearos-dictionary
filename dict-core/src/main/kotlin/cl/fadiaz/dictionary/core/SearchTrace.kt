package cl.fadiaz.dictionary.core

/**
 * Where the cascade reports what it did, so somebody can read it over `adb logcat`.
 *
 * It exists because this module **cannot** log: `ArchitectureTest` forbids `import java.*` and
 * `System.` in `:dict-core` (D-017), so neither `android.util.Log` nor a timestamp can come in
 * here. What can be done is **reporting**, in pure Kotlin, leaving the caller to decide whether
 * escribe, se mide o se tira.
 *
 * ⚠️ **The caller is the one who pays, which is why [enabled] exists.** Assembling a rung's
 * report costs a pass over the result list; with [None] that must not happen. The contract is:
 * whoever
 * reporta pregunta primero.
 */
interface SearchTrace {

    /**
     * Is anybody listening? When `false`, the caller **does not assemble** the report.
     *
     * A property and not a constant, because whoever implements it in `:app` ties it to
     * `Log.isLoggable`, which the user switches on with `setprop` without reinstalling anything.
     */
    val enabled: Boolean

    /**
     * A pack threw when queried and the cascade **carried on without it**.
     *
     * ⚠️ This is the event that justifies the whole file. `SearchRepository.recolectar` swallows
     * the exception on purpose --a corrupt pack cannot leave the user with no search-- and its
     * comment claimed *"a pack that never returns anything is visible"*. It is, on screen;
     * **it was in no log at all**. A pack blowing up on every query looked like an empty one.
     */
    fun packFailed(packId: String, error: String)

    /**
     * A search finished. [byKind] says **how many rows each rung put** into the result.
     *
     * ⚠️ **It counts what a rung produced, not whether it ran.** An absent `FUZZY` means "it
     * contributed no rows", which may be because it was not attempted --it only runs when what
     * came before returned nothing-- or because it was attempted and found nothing.
     * Distinguishing the two would mean instrumenting `DictionarySource`, and what is worth
     * watching --a rung contributing what it should not-- is already visible this way.
     */
    fun searched(query: String, byKind: Map<MatchKind, Int>, fallback: Boolean, returned: Int)

    /** Nobody is listening. The default, and production without `setprop`. */
    object None : SearchTrace {
        override val enabled: Boolean = false
        override fun packFailed(packId: String, error: String) = Unit
        override fun searched(
            query: String,
            byKind: Map<MatchKind, Int>,
            fallback: Boolean,
            returned: Int,
        ) = Unit
    }
}
