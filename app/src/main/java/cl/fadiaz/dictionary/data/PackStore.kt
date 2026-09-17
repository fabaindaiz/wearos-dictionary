package cl.fadiaz.dictionary.data

import android.content.Context
import cl.fadiaz.dictionary.core.DictionarySource
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * De donde sale el pack que la app abre.
 *
 * Dos origenes, y el orden importa:
 *
 * 1. `filesDir/packs/` (cualquier `.db`) -- lo que ya esta instalado. Tambien es por donde entra un pack
 *    puesto a mano con `adb push`, que es como se itera sin reconstruir el APK.
 * 2. El asset del APK, extraido a `filesDir/packs/` la primera vez.
 *
 * SOBRE EL COSTO DE (2), que es una desviacion consciente
 *
 * `BundledSQLiteDriver.open()` recibe un PATH, no un descriptor, y un asset vive dentro del
 * zip del APK. No hay forma de abrirlo en sitio: hay que copiarlo. El resultado es que el pack
 * ocupa dos veces -- comprimido dentro del APK (~34 MB) y extraido en disco (~69 MB) -- que es
 * exactamente el costo por el que se descarto Room en D-039.
 *
 * Se acepta a cambio de que instalar la app deje un diccionario funcionando, sin `adb`. Cuando
 * exista el instalador (roadmap), el asset desaparece y queda solo el origen (1).
 */
object PackStore {

    const val ASSET_NAME: String = "es-def-wikc.db"

    /** Donde viven los packs instalados. Excluido del backup en `data_extraction_rules.xml`. */
    fun packsDir(context: Context): File = File(context.filesDir, "packs")

    sealed interface Result {
        data class Ready(val source: DictionarySource) : Result

        /** No hay pack y tampoco asset: el APK se armo sin el. */
        data object NoPack : Result

        /** Hay un archivo pero no se puede usar. El mensaje es para la pantalla, en español. */
        data class Unusable(val reason: String) : Result
    }

    /**
     * Abre el pack, extrayendolo del asset si hace falta.
     *
     * `onExtracting` se llama antes de copiar, no mientras: la copia de 69 MB tarda y la
     * pantalla tiene que poder decir por que esta esperando.
     */
    suspend fun open(context: Context, onExtracting: () -> Unit = {}): Result =
        withContext(Dispatchers.IO) {
            val dir = packsDir(context)
            dir.mkdirs()

            var file = dir.listFiles { f -> f.isFile && f.name.endsWith(".db") }
                ?.sortedBy { it.name }
                ?.firstOrNull()

            if (file == null) {
                if (!hasAsset(context)) return@withContext Result.NoPack
                onExtracting()
                file = try {
                    extract(context, dir)
                } catch (e: Exception) {
                    return@withContext Result.Unusable(
                        "No se pudo instalar el diccionario: ${e.message}",
                    )
                }
            }

            try {
                Result.Ready(SqlitePackSource(PackFile.open(file.path)))
            } catch (e: PackFile.IncompatibleException) {
                // El pack es de otra version del formato o de otras reglas de normalizacion.
                // Devolveria MENOS resultados de los que tiene, en silencio: por eso se rechaza
                // entero en vez de abrirse igual (D-001, D-006).
                Result.Unusable("El diccionario no es compatible con esta version. ${e.message}")
            } catch (e: Exception) {
                Result.Unusable("El diccionario esta dañado o incompleto. ${e.message}")
            }
        }

    private fun hasAsset(context: Context): Boolean =
        runCatching { context.assets.list("")?.contains(ASSET_NAME) == true }.getOrDefault(false)

    /**
     * Copia el asset a un archivo temporal y recien al final lo renombra.
     *
     * El rename es lo que importa: si la copia se corta a la mitad --se acaba el disco, el
     * usuario mata la app-- lo que queda es un `.part`, no un `.db` a medio escribir. Un pack
     * truncado se ABRE SIN ERROR y devuelve menos resultados de los que tiene, que es
     * justamente el sintoma que este proyecto no puede observar.
     */
    private fun extract(context: Context, dir: File): File {
        val partial = File(dir, "$ASSET_NAME.part")
        val target = File(dir, ASSET_NAME)
        partial.delete()
        context.assets.open(ASSET_NAME).use { input ->
            partial.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
        }
        if (!partial.renameTo(target)) {
            partial.delete()
            error("no se pudo renombrar ${partial.name}")
        }
        return target
    }

    private const val DEFAULT_BUFFER_SIZE = 256 * 1024
}
