package cl.fadiaz.dictionary.data

import cl.fadiaz.dictionary.core.PackRejection

/**
 * Si un pack ya se probó, **con qué resultado**, y si esa prueba sigue valiendo.
 *
 * ## Los tres momentos, y por qué cada uno usa un método distinto
 *
 * | Momento | Qué se pregunta | Con qué |
 * |---|---|---|
 * | **Instalar o descargar** | ¿Llegaron los bytes que se publicaron? | **sha256 en streaming**, en `PackStore.installAtomically` |
 * | **Descubrir un pack** que la app no instaló | ¿Es un pack válido y están bien sus claves? | la verificación entera de `PackFile.open` |
 * | **Cada arranque** | ¿Es el mismo archivo que ya probé, y qué salió? | la huella de acá |
 *
 * ⚠️ **Un hash NO sirve para el tercero, y ahí está el matiz que importa.** Comprobarlo obligaría
 * a releer el archivo entero --301 MB en el pack de inglés-- que es mucho peor que las 64 filas
 * que se querían evitar. En cambio **sí es lo correcto para el primero**, y ahí sale casi gratis:
 * los bytes ya están pasando para copiarse.
 *
 * ⚠️ **«Descubrir» no necesita mecanismo**: un pack que la app no instaló --uno puesto por
 * `devpack.py`, o por el instalador-- simplemente no tiene entrada en el memo, así que se valida
 * entero la primera vez que se abre. La ausencia de una anotación *es* el descubrimiento.
 *
 * ## El memo guarda también los RECHAZOS, y eso cambia el riesgo de signo
 *
 * Pedido: que un pack rechazado no se vuelva a escanear y que la pantalla de diccionarios pueda
 * decir **por qué** sin volver a abrirlo. Eso convierte el memo en algo más peligroso de lo que
 * era:
 *
 * - Un **sí** cacheado de más cuesta que un pack malo se use — y para eso hace falta que el
 *   archivo haya cambiado sin cambiar tamaño ni fecha.
 * - Un **no** cacheado de más cuesta que un pack **perfectamente bueno desaparezca para
 *   siempre**. El archivo no cambia, así que nada lo vuelve a mirar nunca.
 *
 * El segundo es el modo de falla que este repo no puede observar, y el único mecanismo que lo
 * impide es que **las reglas enteras entren en la huella**. Si mañana la app entiende
 * `schema_version` 5, todo rechazo por esquema tiene que caducar solo.
 *
 * ## Qué problema resuelve, y qué NO deja de hacer
 *
 * Abrir un pack recalcula `norm()` y `fuzzy()` sobre 64 entradas repartidas (D-142) y comprueba
 * que su contenido sea el que `meta` promete. Eso convierte `norm_version` --un número que el
 * pack se pone a sí mismo-- en una prueba, y cubre el modo de falla central del repo: un pack
 * construido con otras reglas **devuelve menos palabras, sin excepción y sin log**.
 *
 * Medido: son **36 de los 42 ms** que cuesta cada arranque del proceso con los dos packs reales
 * instalados, y las comprobaciones de contenido que se le sumaron son **5,7 ms** más sobre el
 * pack inglés de 306,8 MB. Y el pack es **inmutable** (D-001): volver a probar el mismo archivo
 * byte por byte no prueba nada que no se supiera.
 *
 * ## Por qué la huella lleva lo que lleva
 *
 * - `bytes` y `modifiedAt`: identifican el archivo. `devpack.py` y el instalador escriben el
 *   `.db` **en su lugar** (D-082), así que el nombre no alcanza para decir que es el mismo.
 * - [rules]: **todo lo que puede cambiar el veredicto sin que cambie el archivo.** Ver ahí.
 *
 * ## Por qué no lleva un hash del archivo
 *
 * Porque costaría más que lo que ahorra: hashear 301 MB en cada arranque es peor que releer 64
 * filas. `(tamaño, mtime)` no distingue dos archivos distintos del mismo tamaño escritos en el
 * mismo milisegundo, y eso es aceptable **acá**: no es una defensa contra un atacante --quien
 * escribe en `filesDir` ya es la app-- sino contra que el usuario reinstale un pack distinto y
 * nadie lo note.
 *
 * Es puro y sin Android a propósito: así el gate lo cubre en la JVM y no en un dispositivo
 * (D-072). Quién lo guarda es `PackStore`.
 */
internal object PackVerification {

    /**
     * Sube cuando cambia **qué se comprueba**, aunque no cambie ninguna constante del formato.
     *
     * ⚠️ **Es la vía que más fácil se olvida.** `NORM_VERSION`, `schema_version` y el códec
     * cambian con ceremonia y hay decisiones que obligan a tocarlos (D-005, D-006). Agregar una
     * invariante nueva a `PackFile.open` no toca ninguno de los tres — y sin bumpear esto, todo
     * pack ya anotado como verificado se saltaría la comprobación nueva para siempre, que es
     * exactamente el agujero que la comprobación venía a tapar.
     *
     * 1 → la verificación original: esquema, norm, códec, sha256 del diccionario, muestra de 64.
     * 2 → suma claves de meta obligatorias, licencia declarada, los dos índices, staging,
     *     `entry_count` contra las filas reales, `fts_def` 1:1, `norm` vacío y huérfanos.
     */
    const val CHECKS_VERSION: Int = 2

    /** Qué salió cuando este archivo se probó. `null` en [verdict] es *"nunca se probó"*. */
    sealed interface Verdict {
        /** Pasó todo. Se puede abrir saltándose lo que ya se comprobó. */
        data object Passed : Verdict

        /** No se pudo usar, y por qué. No hace falta volver a abrirlo para saberlo. */
        data class Rejected(val rejection: PackRejection) : Verdict
    }

    /**
     * Todo lo que puede cambiar el veredicto **sin que cambie el archivo**.
     *
     * Los tres primeros son las constantes contra las que se compara el pack; el cuarto es qué
     * comprobaciones se corren. Cualquiera que se mueva caduca el memo entero, que es lo que
     * evita que un rechazo sobreviva a la versión de la app que ya sabría leer ese pack.
     */
    fun rules(normVersion: Int, schemaVersion: Int, codecId: String): String =
        "n$normVersion.s$schemaVersion.$codecId.c$CHECKS_VERSION"

    /** Qué hace único a un archivo probado, bajo las reglas de hoy. */
    fun fingerprint(bytes: Long, modifiedAt: Long, rules: String): String =
        "$bytes:$modifiedAt:$rules"

    /** Qué salió la última vez con **este** archivo, o `null` si no consta. */
    fun verdict(stored: String?, name: String, fingerprint: String): Verdict? {
        val anotado = entries(stored)[name] ?: return null
        if (anotado.fingerprint != fingerprint) return null
        return if (anotado.reason == PASSED) {
            Verdict.Passed
        } else {
            // `fromId` degrada a DAMAGED en vez de lanzar: un motivo escrito por otra versión
            // de la app significa "hubo un motivo y no sé cuál", y lo seguro es que el pack se
            // vuelva a mirar, nunca que quede escondido por un código ilegible.
            Verdict.Rejected(PackRejection.fromId(anotado.reason))
        }
    }

    /** El memo con este pack anotado. Reemplaza la entrada anterior del mismo nombre. */
    fun remember(
        stored: String?,
        name: String,
        fingerprint: String,
        rejection: PackRejection?,
    ): String =
        serialize(entries(stored) + (name to Anotacion(fingerprint, rejection?.id ?: PASSED)))

    /** El memo sin los packs que ya no están en el disco, para que no crezca sin techo. */
    fun prune(stored: String?, present: Set<String>): String =
        serialize(entries(stored).filterKeys { it in present })

    /** `"ok"` no es un [PackRejection]: ningún id de motivo puede colisionar con él. */
    private const val PASSED = "ok"

    private data class Anotacion(val fingerprint: String, val reason: String)

    private fun entries(stored: String?): Map<String, Anotacion> =
        stored.orEmpty().lineSequence()
            .mapNotNull { linea ->
                // Una línea que no tiene las tres partes es basura --otra versión de la app, una
                // escritura a medias-- y se ignora en vez de romper. El costo de ignorarla es
                // volver a probar el pack, que es el comportamiento anterior.
                val campos = linea.split('\t')
                if (campos.size != 3 || campos.any { it.isEmpty() }) {
                    null
                } else {
                    campos[0] to Anotacion(campos[1], campos[2])
                }
            }
            .toMap()

    private fun serialize(entries: Map<String, Anotacion>): String =
        entries.entries
            // El nombre lo elige quien instala el pack: un tab ahí adentro partiría la línea en
            // otro lado y haría pasar por verificado a un pack que no lo está.
            .filterNot { (name, _) -> '\t' in name || '\n' in name }
            .joinToString("\n") { (name, a) -> "$name\t${a.fingerprint}\t${a.reason}" }
}
