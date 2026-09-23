package cl.fadiaz.dictionary.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import cl.fadiaz.dictionary.BuildConfig

/**
 * Preguntarle cosas a la app **desde `adb`**, sin un dedo.
 *
 * ## Por qué existe
 *
 * ⚠️ **El campo de búsqueda no toma foco con un tap sintético.** Es la misma forma del problema
 * que obligó a fijar espresso 3.7.0 (D-093), y no es teórica: dejó **D-168** (el respaldo entre
 * idiomas) y **D-169** (los sinónimos tocables) sin verificar *con el reloj en la mano*, y volvió
 * a costar en el emulador cuando hubo que comprobar que un pack núcleo devuelve resultados — hubo
 * que contestarlo leyendo el `.db` con `sqlite3`, que comprueba el dato y no la app.
 *
 * Lo que esto habilita es todo lo que **termina en la pantalla** y por eso el gate no lo ve: el
 * orden de los resultados sobre el pack real, el respaldo entre idiomas, los sinónimos, y cualquier
 * cosa que dependa de escribir.
 *
 * ## Lo que se puede preguntar
 *
 * ```sh
 * # Sembrar una consulta, como si se hubiera escrito. `-p` ANTES del extra: ver ACTION_CLEAR.
 * adb shell am broadcast -p cl.fadiaz.dictionary \
 *     -a cl.fadiaz.dictionary.DEBUG_SEARCH -e q "hous"
 *
 * # Vaciar el campo y volver al inicio:
 * adb shell am broadcast -p cl.fadiaz.dictionary -a cl.fadiaz.dictionary.DEBUG_CLEAR
 *
 * # Volcar el estado a logcat: qué packs abrieron, cuál está activo, y el memo de verificación:
 * adb shell am broadcast -p cl.fadiaz.dictionary -a cl.fadiaz.dictionary.DEBUG_DUMP
 * adb logcat -s Dict:V
 * ```
 *
 * El volcado es el ítem 3 de la tabla de herramientas del roadmap, y lo que cierra es que
 * [PackVerification] **se pueda comprobar en el dispositivo**: hasta acá el memo sólo se podía
 * leer con `run-as`, que no existe sobre un APK de `benchmark`.
 *
 * ## Por qué no está en release, y cómo se garantiza
 *
 * ⚠️ **Un receiver exportado en producción es superficie de ataque y batería.** La garantía no es
 * que nadie lo registre: es que `BuildConfig.DEBUG_INTENTS` sea una **constante** `false` en
 * `release`, así que R8 pliega el `if`, la clase se queda sin referencias y **desaparece del
 * dex**. No es que no se use — es que no existe. Es el mismo trato que D-213 le dio al HTTP en
 * claro, y el motivo por el que la puerta es un `buildConfigField` y no un chequeo en tiempo de
 * ejecución.
 *
 * ⚠️ **Y está encendida en `benchmark`** aunque herede de `release`: es la build con la que se
 * mide (D-166) y la única parecida a release que se puede instalar. Si fuera la única que no
 * puede sembrar una consulta, medir una búsqueda volvería a necesitar un dedo.
 */
object DebugIntents {

    /** Sembrar una consulta. El texto viaja en el extra `q`. */
    const val ACTION_SEARCH = "cl.fadiaz.dictionary.DEBUG_SEARCH"

    /** Volcar el estado a `logcat`, bajo el tag `Dict`. */
    const val ACTION_DUMP = "cl.fadiaz.dictionary.DEBUG_DUMP"

    /**
     * Vaciar el campo, que es la otra mitad de cualquier prueba de busqueda.
     *
     * ⚠️ **Accion propia y no `-e q ""`, y el motivo salio de usarlo.** Un extra vacio **no
     * sobrevive a `adb shell`**: el shell del dispositivo se come la cadena vacia y el argumento
     * siguiente ocupa su lugar. Medido el 2026-09-23 -- `am broadcast -a ... -e q "" -p
     * cl.fadiaz.dictionary` dejo la app buscando literalmente **`-p`**, y de paso perdio el
     * filtro de paquete. Con una accion sin extras no hay nada que se pueda comer.
     */
    const val ACTION_CLEAR = "cl.fadiaz.dictionary.DEBUG_CLEAR"

    /** El extra con el texto a buscar. */
    const val EXTRA_QUERY = "q"

    /**
     * Registra el receiver si esta build lo permite, y devuelve cómo darlo de baja.
     *
     * Devuelve `null` cuando la puerta está cerrada, y eso es lo que permite que quien llama
     * escriba `DebugIntents.install(...)?.let { ... }` sin preguntar por la build.
     *
     * ⚠️ **`RECEIVER_EXPORTED` es obligatorio para que `am broadcast` llegue**, y es exactamente
     * la razón por la que esto no puede existir en release. Desde API 34 el flag es obligatorio
     * declararlo; acá se declara el valor que hace falta y se paga apagando el build entero.
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
                        DictLog.i { "debug: sembrar consulta '$texto'" }
                        onSearch(texto)
                    }
                    ACTION_CLEAR -> {
                        DictLog.i { "debug: vaciar el campo" }
                        onSearch("")
                    }
                    ACTION_DUMP -> onDump()
                }
            }
        }
        val filtro = IntentFilter().apply {
            addAction(ACTION_SEARCH)
            addAction(ACTION_CLEAR)
            addAction(ACTION_DUMP)
        }
        // Sin rama de compatibilidad: `minSdk` es 33 y el flag existe desde 33, asi que
        // preguntar por `SDK_INT` seria codigo muerto -- lo agarro lint, que en este repo rompe
        // el build (ObsoleteSdkInt).
        context.registerReceiver(receiver, filtro, Context.RECEIVER_EXPORTED)
        DictLog.i { "debug: intents de depuracion ACTIVOS ($ACTION_SEARCH, $ACTION_DUMP)" }
        return { context.unregisterReceiver(receiver) }
    }

    /**
     * El volcado, como texto. Puro y sin Android **para que el gate lo cubra** (D-072).
     *
     * ⚠️ **Va en líneas y no en una sola**, porque `logcat` corta los mensajes largos y el memo de
     * verificación puede tener una línea por pack: un volcado truncado es peor que ninguno,
     * porque parece completo.
     */
    fun dump(
        opened: List<String>,
        active: String?,
        rejected: List<String>,
        memo: String?,
        appVersion: Int,
        /**
         * La identidad del build: commit, `+dirty`, y cuándo.
         *
         * ⚠️ **Va PRIMERO y no al final**, porque decide si el resto del volcado sirve: un
         * readout que describe un APK que no es el que se cree estar mirando es peor que ninguno.
         */
        buildId: String = "",
    ): List<String> = buildList {
        add("--- volcado ---")
        add("app versionCode=$appVersion${buildId.takeIf { it.isNotBlank() }?.let { " build=$it" }.orEmpty()}")
        add("activo=${active ?: "(ninguno)"}")
        add("abiertos=${opened.size}${if (opened.isEmpty()) "" else ": " + opened.joinToString()}")
        add(
            "rechazados=${rejected.size}" +
                if (rejected.isEmpty()) "" else ": " + rejected.joinToString(),
        )
        val lineas = memo.orEmpty().lineSequence().filter { it.isNotBlank() }.toList()
        add("memo de verificacion: ${lineas.size} anotacion(es)")
        // ⚠️ Una línea por anotación, y la huella entera: es el dato con el que se comprueba que
        // un `versionCode` nuevo caducó el memo (D-225), y abreviarla lo volvería incomprobable.
        lineas.forEach { add("  $it") }
        add("--- fin ---")
    }
}
