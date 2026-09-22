package cl.fadiaz.dictionary.data

import android.util.Log

/**
 * El unico sitio desde el que esta app escribe en `logcat`.
 *
 * ## Como se enciende
 *
 * ```sh
 * adb shell setprop log.tag.Dict DEBUG   # el detalle, sin recompilar ni reinstalar
 * adb logcat -s Dict:V
 * adb shell setprop log.tag.Dict INFO    # y se apaga
 * ```
 *
 * ⚠️ **Esto NO es `BuildConfig.DEBUG`, y la diferencia es el motivo por el que existe el
 * archivo.** Con `BuildConfig.DEBUG` R8 borra las llamadas en `release`, y `benchmark` hereda de
 * `release` (ver `build.gradle.kts`) -- o sea que **la build con la que se mide arranque y
 * bateria se quedaria sin logs**, que es justo donde hacen falta. `Log.isLoggable` funciona igual
 * en las tres builds y se controla desde afuera.
 *
 * ## El contrato de coste
 *
 * Los mensajes se pasan como **lambda, no como String**. `Log.d(TAG, "x=" + x)` construye el
 * String aunque el log este apagado; `d { "x=$x" }` no ejecuta nada si nadie escucha. Eso lo fija
 * un test: [cl.fadiaz.dictionary.data.DictLogTest].
 *
 * `INFO` y arriba se ven siempre --son los eventos que se cuentan con los dedos: packs que abren,
 * packs que se rechazan, tiles que se piden--. `DEBUG` es el detalle por consulta y esta apagado
 * salvo que se pida.
 */
object DictLog {

    /** Un solo tag para toda la app: `adb logcat -s Dict:V` y ya esta todo. */
    const val TAG = "Dict"

    /** Eventos que pasan pocas veces y explican el estado: packs, tiles, descargas. */
    inline fun i(msg: () -> String) {
        if (Log.isLoggable(TAG, Log.INFO)) Log.i(TAG, msg())
    }

    /** Algo salio mal pero la app sigue: un pack rechazado, una descarga que no cuadro. */
    inline fun w(msg: () -> String) {
        if (Log.isLoggable(TAG, Log.WARN)) Log.w(TAG, msg())
    }

    /** Detalle por consulta. **Apagado** salvo `setprop log.tag.Dict DEBUG`. */
    inline fun d(msg: () -> String) {
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, msg())
    }

    /** Un fallo con su excepcion. El unico que acepta un `Throwable`. */
    inline fun e(error: Throwable?, msg: () -> String) {
        if (Log.isLoggable(TAG, Log.ERROR)) Log.e(TAG, msg(), error)
    }

    /** ¿Esta encendido el detalle? Para no armar un reporte que nadie va a leer. */
    val verbose: Boolean get() = Log.isLoggable(TAG, Log.DEBUG)
}
