package cl.fadiaz.dictionary.data

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * De donde sale el pack que la app abre.
 *
 * Dos origenes, y el orden importa:
 *
 * 1. `filesDir/packs/` (cualquier `.db`) -- lo que ya esta instalado. Tambien es por donde entra
 *    un pack puesto a mano con `adb push`, que es como se itera sin reconstruir el APK.
 * 2. El asset del APK, extraido a `filesDir/packs/` la primera vez.
 *
 * SOBRE EL COSTO DE (2), que es una desviacion consciente (D-071)
 *
 * `BundledSQLiteDriver.open()` recibe un PATH, no un descriptor, y un asset vive dentro del
 * zip del APK. No hay forma de abrirlo en sitio: hay que copiarlo. El resultado es que el pack
 * ocupa dos veces -- comprimido dentro del APK (~36 MB) y extraido en disco (~69 MB) -- que es
 * exactamente el costo por el que se descarto Room en D-039.
 *
 * Se acepta a cambio de que instalar la app deje un diccionario funcionando, sin `adb`. Cuando
 * exista el instalador (roadmap), el asset desaparece y queda solo el origen (1).
 */
object PackStore {

    const val ASSET_NAME: String = "es-def-wikc.db"

    /** Donde viven los packs instalados. Excluido del backup en `data_extraction_rules.xml`. */
    fun packsDir(context: Context): File = File(context.filesDir, "packs")

    /**
     * Abre el pack, extrayendolo del asset si hace falta.
     *
     * `onExtracting` se llama antes de copiar, no mientras: la copia de 69 MB tarda y la
     * pantalla tiene que poder decir por que esta esperando.
     */
    suspend fun open(context: Context, onExtracting: () -> Unit = {}): PackLoad =
        withContext(Dispatchers.IO) {
            val dir = packsDir(context)
            dir.mkdirs()

            var file = packInstalado(dir)

            if (file == null) {
                if (!hasAsset(context)) return@withContext PackLoad.NoPack
                onExtracting()
                file = try {
                    instalarAtomico(context.assets.open(ASSET_NAME), dir, ASSET_NAME)
                } catch (e: IOException) {
                    return@withContext PackLoad.Unusable(
                        "No se pudo instalar el diccionario: ${e.message}",
                    )
                }
            }

            abrir(file)
        }

    /** El primer `.db` que haya, por nombre. Determinista: dos arranques abren el mismo. */
    internal fun packInstalado(dir: File): File? =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".db") }
            ?.sortedBy { it.name }
            ?.firstOrNull()

    /**
     * Copia a un archivo temporal y recien al final lo renombra.
     *
     * El rename es lo que importa, y es lo unico que hace esto correcto: si la copia se corta a
     * la mitad --se acaba el disco, el usuario mata la app-- lo que queda es un `.part`, no un
     * `.db` a medio escribir. **Un pack truncado se abre sin error** y devuelve menos resultados
     * de los que tiene, que es justamente el sintoma que este proyecto no puede observar.
     *
     * Es `internal` y sin `Context` para que se pueda testear en la JVM: la atomicidad es una
     * propiedad del sistema de archivos, no de Android.
     */
    internal fun instalarAtomico(input: InputStream, dir: File, nombre: String): File {
        val partial = File(dir, "$nombre.part")
        val target = File(dir, nombre)
        partial.delete()
        try {
            input.use { origen ->
                partial.outputStream().use { destino -> origen.copyTo(destino, BUFFER) }
            }
        } catch (e: IOException) {
            // Si no se borra, el proximo intento arranca con basura y encima ocupa disco.
            partial.delete()
            throw e
        }
        if (!partial.renameTo(target)) {
            partial.delete()
            throw IOException("no se pudo renombrar ${partial.name}")
        }
        return target
    }

    private fun hasAsset(context: Context): Boolean =
        runCatching { context.assets.list("")?.contains(ASSET_NAME) == true }.getOrDefault(false)

    private fun abrir(file: File): PackLoad =
        try {
            PackLoad.Ready(SqlitePackSource(PackFile.open(file.path)))
        } catch (e: PackFile.IncompatibleException) {
            // El pack es de otra version del formato o de otras reglas de normalizacion.
            // Devolveria MENOS resultados de los que tiene, en silencio: por eso se rechaza
            // entero en vez de abrirse igual (D-001, D-006).
            PackLoad.Unusable("El diccionario no es compatible con esta versión. ${e.message}")
        } catch (e: Exception) {
            PackLoad.Unusable("El diccionario está dañado o incompleto. ${e.message}")
        }

    private const val BUFFER = 256 * 1024
}
