package cl.fadiaz.dictionary.data

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * De donde salen los packs que la app abre.
 *
 * Todos viven en `filesDir/packs/`; lo que cambia es como llegaron:
 *
 *  - **El pack de demostracion** viaja en el APK y se extrae al primer arranque. Es chico y
 *    existe para que la app recien instalada tenga algo que mostrar.
 *  - **Los diccionarios de verdad** entran por `adb push` hoy, y por el instalador cuando
 *    exista. Los dos escriben en el mismo directorio, asi que la app no los distingue.
 *
 * POR QUE UN PACK DE DEMO Y NO EL DICCIONARIO ENTERO (D-071, D-081)
 *
 * Un pack en el APK se duplica en disco: comprimido adentro y extraido afuera. Con el
 * diccionario real eso es inaceptable --el ingles son **295 MiB en disco, 185 MiB
 * comprimido**, y con los dos idiomas el APK se iba a ~270 MB, que por Bluetooth a un reloj no
 * es un detalle. Con un pack de demostracion de decenas de KB, el mismo costo es ruido.
 *
 * Esa es toda la diferencia: no es el mecanismo, es el tamaño.
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
     * Que assets hay que copiar a disco, y sobre todo **cuales no**.
     *
     * Pura y sin `Context` para que se pueda testear en la JVM, igual que [instalarAtomico].
     * Los casos que un `if (dir.isEmpty())` se come:
     *
     *  - el pack de demo ya extraido no se vuelve a copiar en cada arranque;
     *  - actualizar el APK con otra demo la extrae aunque ya haya diccionarios instalados;
     *  - un `.db` puesto a mano con `adb push` no se toca, que es como entran hoy los packs de
     *    verdad.
     */
    internal fun queFaltaExtraer(assets: List<String>, instalados: List<String>): List<String> {
        val yaEstan = instalados.toSet()
        return assets.filterNot { it in yaEstan }.sorted()
    }

    /**
     * Extrae lo que falte del APK y abre todo lo que haya.
     *
     * La extraccion es inmediata y no perezosa **porque el pack de demo es chico**: diferirla
     * costaria una maquina de estados para ahorrar decenas de KB.
     */
    suspend fun open(
        context: Context,
        preferido: String?,
        onExtracting: () -> Unit = {},
    ): PackSet = withContext(Dispatchers.IO) {
        val dir = packsDir(context)
        dir.mkdirs()

        val faltan = queFaltaExtraer(assetsDePack(context), packsInstalados(dir).map { it.name })
        if (faltan.isNotEmpty()) {
            onExtracting()
            for (asset in faltan) {
                runCatching { instalarAtomico(context.assets.open(asset), dir, asset) }
            }
        }

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

    private fun assetsDePack(context: Context): List<String> =
        runCatching { context.assets.list("")?.filter { it.endsWith(".db") }.orEmpty() }
            .getOrDefault(emptyList())
            .sorted()

    private fun prefs(context: Context) =
        context.getSharedPreferences("dictionary", Context.MODE_PRIVATE)

    private const val CLAVE_PACK = "pack_activo"


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
