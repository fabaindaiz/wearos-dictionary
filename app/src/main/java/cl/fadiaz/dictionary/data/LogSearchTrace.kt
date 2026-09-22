package cl.fadiaz.dictionary.data

import cl.fadiaz.dictionary.core.MatchKind
import cl.fadiaz.dictionary.core.SearchTrace

/**
 * Lo que la cascada reporta, escrito en `logcat`.
 *
 * Es el lado `:app` de [SearchTrace]: ese modulo no puede tocar `android.util.Log` (D-017), asi
 * que reporta en Kotlin puro y esto lo traduce. Ver [DictLog] para como se enciende.
 *
 * ⚠️ **Los dos eventos van a niveles distintos a proposito.** Un pack que falla al consultarlo se
 * escribe **siempre** (WARN): es un defecto, no telemetria, y es el que estaba invisible. El
 * resumen por consulta va a DEBUG, porque hay uno por pulsacion de tecla con debounce y en
 * produccion nadie lo lee.
 */
object LogSearchTrace : SearchTrace {

    /**
     * Sólo gobierna si vale la pena **armar** el resumen por consulta.
     *
     * Se lee en cada busqueda a proposito, y no se cachea: asi `setprop log.tag.Dict DEBUG`
     * empieza a verse sin reiniciar la app.
     */
    override val enabled: Boolean get() = DictLog.verbose

    override fun packFailed(packId: String, error: String) {
        // Sin guarda de [enabled]: esto se escribe aunque el detalle este apagado.
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
                // En el orden del enum, que es el orden de la cascada: asi se lee de un vistazo
                // si un peldaño aporto filas que no deberia.
                MatchKind.entries
                    .filter { byKind.containsKey(it) }
                    .joinToString(" ") { "${it.name.lowercase()}=${byKind[it]}" }
            }
            "buscar '$query' -> $returned ($peldanos)" + if (fallback) " RESPALDO" else ""
        }
    }
}
