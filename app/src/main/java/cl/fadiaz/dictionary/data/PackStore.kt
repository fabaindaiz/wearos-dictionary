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

    /** Donde viven los packs instalados. Excluido del backup en `data_extraction_rules.xml`. */
    fun packsDir(context: Context): File = File(context.filesDir, "packs")

    /**
     * Abre el pack, extrayendolo del asset si hace falta.
     *
     * `onExtracting` se llama antes de copiar, no mientras: la copia de 69 MB tarda y la
     * pantalla tiene que poder decir por que esta esperando.
     */
    suspend fun open(
        context: Context,
        preferido: String?,
        onExtracting: () -> Unit = {},
    ): PackSet = withContext(Dispatchers.IO) {
        val dir = packsDir(context)
        dir.mkdirs()

        val instalados = packsInstalados(dir)
        val assets = assetsDePack(context)
        if (instalados.isEmpty() && assets.isEmpty()) return@withContext PackSet.NoPack

        // Solo se abre lo que ya esta en disco: extraer los 69 MB de un idioma que quizas no se
        // use es el gasto que la pereza evita.
        val abiertos = mutableListOf<PackHandle.Abierto>()
        val problemas = mutableListOf<String>()
        for (file in instalados) {
            when (val cargado = abrir(file)) {
                is PackLoad.Ready -> abiertos += PackHandle.Abierto(cargado.source)
                is PackLoad.Unusable -> problemas += "${file.name}: ${cargado.reason}"
                PackLoad.NoPack -> Unit
            }
        }

        val instaladosPorAsset = instalados.map { it.name }.toSet()
        val disponibles = queFaltaExtraer(assets, instaladosPorAsset.toList())
            .map { PackHandle.Disponible(packIdDeAsset(it), it, etiquetaDeAsset(it)) }

        val elegido = abiertos.firstOrNull { it.packId == preferido }
            ?: abiertos.firstOrNull()

        if (elegido == null) {
            // No hay nada abierto todavia. Si hay assets, se extrae el preferido (o el primero)
            // y se abre: es el primer arranque.
            val aExtraer = disponibles.firstOrNull { it.packId == preferido }
                ?: disponibles.firstOrNull()
                ?: return@withContext PackSet.Unusable(
                    problemas.firstOrNull() ?: "Ningún diccionario se pudo abrir.",
                )
            onExtracting()
            val file = try {
                instalarAtomico(context.assets.open(aExtraer.asset), dir, aExtraer.asset)
            } catch (e: IOException) {
                return@withContext PackSet.Unusable(
                    "No se pudo instalar el diccionario: ${e.message}",
                )
            }
            return@withContext when (val cargado = abrir(file)) {
                is PackLoad.Ready -> {
                    val activo = PackHandle.Abierto(cargado.source)
                    PackSet.Ready(activo, listOf(activo) + disponibles.filter { it != aExtraer }, problemas)
                }
                is PackLoad.Unusable -> PackSet.Unusable(cargado.reason)
                PackLoad.NoPack -> PackSet.NoPack
            }
        }

        PackSet.Ready(elegido, abiertos + disponibles, problemas)
    }

    /**
     * Extrae y abre un pack que estaba solo en el APK. Es la segunda mitad de la pereza.
     */
    suspend fun extraer(context: Context, handle: PackHandle.Disponible): PackLoad =
        withContext(Dispatchers.IO) {
            val dir = packsDir(context)
            dir.mkdirs()
            val file = try {
                instalarAtomico(context.assets.open(handle.asset), dir, handle.asset)
            } catch (e: IOException) {
                return@withContext PackLoad.Unusable(
                    "No se pudo instalar el diccionario: ${e.message}",
                )
            }
            abrir(file)
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

    /** `es-def-wikc.db` -> `es-def-wikc`. El builder escribe el pack_id como nombre de archivo. */
    private fun packIdDeAsset(asset: String): String = asset.removeSuffix(".db")

    /** Lo unico que se puede decir de un pack sin extraerlo: su idioma, que sale del nombre. */
    private fun etiquetaDeAsset(asset: String): String =
        packIdDeAsset(asset).substringBefore("-").uppercase()

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
     * Que assets hay que copiar a disco, y sobre todo **cuales no**.
     *
     * Pura y sin `Context` para que se pueda testear en la JVM, igual que [instalarAtomico].
     * Los tres casos que un `if (dir.isEmpty())` se come:
     *
     *  - un pack ya instalado no se vuelve a copiar (serian 72 MB por arranque);
     *  - actualizar el APK con un idioma nuevo extrae **solo** el nuevo;
     *  - un `.db` puesto a mano con `adb push` se conserva, que es el camino de iteracion que
     *    D-071 promete.
     */
    internal fun queFaltaExtraer(assets: List<String>, instalados: List<String>): List<String> {
        val yaEstan = instalados.toSet()
        return assets.filterNot { it in yaEstan }.sorted()
    }

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
