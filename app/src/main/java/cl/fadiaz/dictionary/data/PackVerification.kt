package cl.fadiaz.dictionary.data

/**
 * Si un pack ya se probó, y si esa prueba sigue valiendo.
 *
 * ## Los tres momentos, y por qué cada uno usa un método distinto
 *
 * | Momento | Qué se pregunta | Con qué |
 * |---|---|---|
 * | **Instalar o descargar** | ¿Llegaron los bytes que se publicaron? | **sha256 en streaming**, en `PackStore.installAtomically` |
 * | **Descubrir un pack** que la app no instaló | ¿Están bien construidas sus claves? | la muestra de 64 de D-142, entera |
 * | **Cada arranque** | ¿Es el mismo archivo que ya probé? | la huella de acá |
 *
 * ⚠️ **Un hash NO sirve para el tercero, y ahí está el matiz que importa.** Comprobarlo obligaría
 * a releer el archivo entero --301 MB en el pack de inglés-- que es mucho peor que las 64 filas
 * que se querían evitar. En cambio **sí es lo correcto para el primero**, y ahí sale casi gratis:
 * los bytes ya están pasando para copiarse.
 *
 * ⚠️ **«Descubrir» no necesita mecanismo**: un pack que la app no instaló --uno puesto por
 * `devpack.py`, o por el instalador cuando exista-- simplemente no tiene entrada en el memo, así
 * que se valida entero la primera vez que se abre. La ausencia de una anotación *es* el
 * descubrimiento.
 *
 * ## Qué problema resuelve, y qué NO deja de hacer
 *
 * Abrir un pack recalcula `norm()` y `fuzzy()` sobre 64 entradas repartidas y las compara con lo
 * que el archivo trae (D-142). Eso convierte `norm_version` --un número que el pack se pone a sí
 * mismo-- en una prueba, y cubre el modo de falla central del repo: un pack construido con otras
 * reglas **devuelve menos palabras, sin excepción y sin log**.
 *
 * Medido: son **36 de los 42 ms** que cuesta cada arranque del proceso con los dos packs reales
 * instalados. Y el pack es **inmutable** (D-001): volver a probar el mismo archivo byte por byte
 * no prueba nada que no se supiera.
 *
 * Así que la muestra se saltea **sólo** cuando el archivo es el mismo que ya pasó, con las mismas
 * reglas. Cualquier duda se resuelve volviendo a probar, que es exactamente lo que se hacía antes.
 *
 * ## Por qué la huella lleva lo que lleva
 *
 * - `bytes` y `modifiedAt`: identifican el archivo. `devpack.py` y el instalador escriben el
 *   `.db` **en su lugar** (D-082), así que el nombre no alcanza para decir que es el mismo.
 * - `normVersion`: ⚠️ **es la mitad que importa**. Si `norm()` o `fuzzy()` cambian, todo pack ya
 *   verificado tiene que volver a probarse — sus claves se calcularon con otras reglas. Un caché
 *   que sobreviviera a un bump de `NORM_VERSION` haría justo el daño que D-142 evita. Alcanza con
 *   ese número porque D-005 y D-006 obligan a subirlo en el mismo commit que cambia cualquiera de
 *   las dos funciones.
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

    /** Qué hace único a un archivo verificado, bajo las reglas de normalización de hoy. */
    fun fingerprint(bytes: Long, modifiedAt: Long, normVersion: Int): String =
        "$bytes:$modifiedAt:$normVersion"

    /** ¿Este archivo, con esta huella, ya pasó la muestra de claves? */
    fun isVerified(stored: String?, name: String, fingerprint: String): Boolean =
        entries(stored)[name] == fingerprint

    /** El memo con este pack anotado. Reemplaza la entrada anterior del mismo nombre. */
    fun remember(stored: String?, name: String, fingerprint: String): String =
        serialize(entries(stored) + (name to fingerprint))

    /** El memo sin los packs que ya no están en el disco, para que no crezca sin techo. */
    fun prune(stored: String?, present: Set<String>): String =
        serialize(entries(stored).filterKeys { it in present })

    private fun entries(stored: String?): Map<String, String> =
        stored.orEmpty().lineSequence()
            .mapNotNull { linea ->
                // Una línea sin separador es basura --otra versión de la app, una escritura a
                // medias-- y se ignora en vez de romper. El costo de ignorarla es volver a
                // probar el pack, que es el comportamiento anterior.
                val corte = linea.indexOf('\t')
                if (corte <= 0 || corte == linea.length - 1) {
                    null
                } else {
                    linea.substring(0, corte) to linea.substring(corte + 1)
                }
            }
            .toMap()

    private fun serialize(entries: Map<String, String>): String =
        entries.entries.joinToString("\n") { (name, fingerprint) -> "$name\t$fingerprint" }
}
