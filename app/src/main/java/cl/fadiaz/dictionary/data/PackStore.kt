package cl.fadiaz.dictionary.data

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * De donde salen los packs que la app abre: **`filesDir/packs/`, y nada mas**.
 *
 * El APK no lleva ningun diccionario. Los packs entran por `adb push` hoy y por el instalador
 * cuando exista; los dos escriben en el mismo directorio, asi que la app no distingue.
 *
 * ESTO CIERRA D-071, y antes de tiempo
 *
 * El pack viajaba como asset del APK y se extraia al primer arranque. Eso lo duplicaba en disco
 * --comprimido adentro del APK y extraido afuera-- que es el mismo costo por el que se descarto
 * Room (D-039). Se habia aceptado a cambio de que instalar la app dejara un diccionario
 * andando, y la propia decision decia que se revertia al existir el instalador.
 *
 * Lo que la adelanto fue medir el ingles: **295 MiB en disco, 185 MiB comprimido**. Con los dos
 * idiomas el APK se iba a ~270 MB, que por Bluetooth a un reloj no es un detalle.
 *
 * Lo que sobrevive de aquello es [instalarAtomico], y no por inercia: el instalador necesita
 * exactamente esa propiedad, y la razon esta medida.
 */
object PackStore {

    /** Donde viven los packs instalados. Excluido del backup en `data_extraction_rules.xml`. */
    fun packsDir(context: Context): File = File(context.filesDir, "packs")

    /**
     * Abre el pack, extrayendolo del asset si hace falta.
     *
     * `onExtracting` se llama antes de copiar, no mientras: la copia de 69 MB tarda y la
     * pantalla tiene que poder decir por que esta esperando.
     */
    /**
     * Abre todos los packs instalados.
     *
     * `onExtracting` sobrevive en la firma porque el instalador va a necesitar avisar lo mismo;
     * hoy no se llama, porque no hay nada que extraer.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun open(
        context: Context,
        preferido: String?,
        onExtracting: () -> Unit = {},
    ): PackSet = withContext(Dispatchers.IO) {
        val dir = packsDir(context)
        dir.mkdirs()

        val instalados = packsInstalados(dir)
        if (instalados.isEmpty()) return@withContext PackSet.NoPack

        val abiertos = mutableListOf<PackHandle.Abierto>()
        val problemas = mutableListOf<String>()
        for (file in instalados) {
            when (val cargado = abrir(file)) {
                is PackLoad.Ready -> abiertos += PackHandle.Abierto(cargado.source)
                is PackLoad.Unusable -> problemas += "${file.name}: ${cargado.reason}"
                PackLoad.NoPack -> Unit
            }
        }

        val elegido = abiertos.firstOrNull { it.packId == preferido }
            ?: abiertos.firstOrNull()
            ?: return@withContext PackSet.Unusable(
                problemas.firstOrNull() ?: "Ningún diccionario se pudo abrir.",
            )

        PackSet.Ready(elegido, abiertos, problemas)
    }

    /** El idioma elegido, para que el reloj abra el mismo diccionario que la ultima vez. */
    fun packPreferido(context: Context): String? =
        prefs(context).getString(CLAVE_PACK, null)

    fun recordarPack(context: Context, packId: String) {
        prefs(context).edit().putString(CLAVE_PACK, packId).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences("dictionary", Context.MODE_PRIVATE)

    private const val CLAVE_PACK = "pack_activo"

    private fun assetsDePack(context: Context): List<String> =
        runCatching { context.assets.list("")?.filter { it.endsWith(".db") }.orEmpty() }
            .getOrDefault(emptyList())
            .sorted()


    /**
     * Todos los packs instalados, por nombre.
     *
     * Antes esto devolvia **solo el primero**, y con dos packs eso escondia el español en
     * silencio porque "en-..." ordena antes que "es-...". El orden sigue importando --dos
     * arranques tienen que ver la misma lista-- pero ya no decide cual se abre.
     */
    internal fun packsInstalados(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".db") }
            ?.sortedBy { it.name }
            ?.toList()
            .orEmpty()

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
