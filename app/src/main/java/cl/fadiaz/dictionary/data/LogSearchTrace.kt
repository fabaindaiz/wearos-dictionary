package cl.fadiaz.dictionary.data

import cl.fadiaz.dictionary.core.MatchKind
import cl.fadiaz.dictionary.core.SearchTrace

/**
 * What the cascade reports, written into `logcat`.
 *
 * It is [SearchTrace]'s `:app` side: that module cannot touch `android.util.Log` (D-017), so it
 * reports in pure Kotlin and this translates it. See [DictLog] for how it is switched on.
 *
 * ⚠️ **The two events go to different levels on purpose.** A pack that fails when queried is
 * written **always** (WARN): it is a defect, not telemetry, and it is the one that was invisible.
 * The per-query summary goes to DEBUG, because there is one per debounced keystroke and in
 * production nobody reads it.
 */
object LogSearchTrace : SearchTrace {

    /**
     * It only governs whether the per-query summary is worth **assembling**.
     *
     * It is read on every search on purpose, and not cached: that way `setprop log.tag.Dict DEBUG`
     * starts showing without restarting the app.
     */
    override val enabled: Boolean get() = DictLog.verbose

    override fun packFailed(packId: String, error: String) {
        // No [enabled] guard: this is written even when the detail is switched off.
        DictLog.w { "pack $packId FALLO al consultarlo, la busqueda sigue sin el: $error" }
    }

    override fun searched(
        query: String,
        byKind: Map<MatchKind, Int>,
        fallback: Boolean,
        returned: Int,
    ) {
        DictLog.d {
            val peldanos = if (byKind.isEmpty()) {
                "sin resultados"
            } else {
                // In the enum's order, which is the cascade's order: that way you read at a
                // glance whether a rung contributed rows it should not have.
                MatchKind.entries
                    .filter { byKind.containsKey(it) }
                    .joinToString(" ") { "${it.name.lowercase()}=${byKind[it]}" }
            }
            "buscar '$query' -> $returned ($peldanos)" + if (fallback) " RESPALDO" else ""
        }
    }
}
