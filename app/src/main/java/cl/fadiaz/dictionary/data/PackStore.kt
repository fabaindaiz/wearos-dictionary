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
    internal fun missingFromDisk(assets: List<String>, installed: List<String>): List<String> {
        val alreadyOnDisk = installed.toSet()
        return assets.filterNot { it in alreadyOnDisk }.sorted()
    }

    /**
     * Extrae lo que falte del APK y abre todo lo que haya.
     *
     * La extraccion es inmediata y no perezosa **porque el pack de demo es chico**: diferirla
     * costaria una maquina de estados para ahorrar decenas de KB.
     */
    suspend fun open(
        context: Context,
        preferred: String?,
        onExtracting: () -> Unit = {},
    ): PackSet = withContext(Dispatchers.IO) {
        val dir = packsDir(context)
        dir.mkdirs()

        val missing = missingFromDisk(packAssets(context), installedPacks(dir).map { it.name })
        if (missing.isNotEmpty()) {
            onExtracting()
            for (asset in missing) {
                runCatching { installAtomically(context.assets.open(asset), dir, asset) }
            }
        }

        val installed = installedPacks(dir)
        if (installed.isEmpty()) return@withContext PackSet.NoPack

        // Los que vinieron del APK son de demostracion: se marcan para que no le ganen a un
        // diccionario instalado.
        val fromAssets = packAssets(context).toSet()
        val opened = mutableListOf<PackHandle.Open>()
        val problems = mutableListOf<String>()
        for (file in installed) {
            when (val loaded = openFile(file)) {
                is PackLoad.Ready ->
                    opened += PackHandle.Open(
                        source = loaded.source,
                        isDemo = file.name in fromAssets,
                        fileName = file.name,
                        bytes = file.length(),
                    )
                is PackLoad.Unusable -> problems += "${file.name}: ${loaded.reason}"
                PackLoad.NoPack -> Unit
            }
        }

        val candidates = opened.filterNot { it.isDemo }.ifEmpty { opened }
        val chosen = candidates.firstOrNull { it.packId == preferred }
            ?: candidates.firstOrNull()
            ?: return@withContext PackSet.Unusable(
                problems.firstOrNull() ?: "Ningún diccionario se pudo abrir.",
            )

        PackSet.Ready(chosen, opened, problems)
    }

    /** El idioma elegido, para que el reloj abra el mismo diccionario que la ultima vez. */
    fun preferredPack(context: Context): String? =
        prefs(context).getString(KEY_PACK, null)

    fun rememberPack(context: Context, packId: String) {
        prefs(context).edit().putString(KEY_PACK, packId).apply()
    }

    private fun packAssets(context: Context): List<String> =
        runCatching { context.assets.list("")?.filter { it.endsWith(".db") }.orEmpty() }
            .getOrDefault(emptyList())
            .sorted()

    /** El historial de entradas abiertas. La politica --dedupe, orden, tope-- vive en el
     * ViewModel, que el gate ve; aca solo se serializa. */
    fun history(context: Context): List<Visit> =
        parseVisits(prefs(context).getString(KEY_HISTORY, null).orEmpty())

    fun rememberHistory(context: Context, visits: List<Visit>) {
        prefs(context).edit().putString(KEY_HISTORY, serializeVisits(visits)).apply()
    }

    /**
     * Borra un pack del disco. **Irreversible**: reponerlo cuesta ~90 s por adb.
     *
     * Recibe el nombre de archivo y no el `packId` a proposito: son cosas distintas, y deducir
     * uno del otro borraria el archivo equivocado el dia que dejen de coincidir.
     *
     * NO cierra la conexion: eso es de quien la abrio y tiene que pasar **antes**. En Unix un
     * archivo borrado con un descriptor abierto sigue ocupando el disco hasta que se cierre, y
     * la app lo seguiria leyendo como si nada -- o sea, el usuario ve que borro y no se libero
     * nada, que es peor que no poder borrar.
     */
    fun deletePack(context: Context, fileName: String): Boolean {
        val target = File(packsDir(context), fileName)
        return target.isFile && target.delete()
    }

    /** Las palabras guardadas. Mismo codec que el historial: son la misma forma de dato. */
    fun favorites(context: Context): List<Visit> =
        parseVisits(prefs(context).getString(KEY_FAVORITES, null).orEmpty())

    fun rememberFavorites(context: Context, visits: List<Visit>) {
        prefs(context).edit().putString(KEY_FAVORITES, serializeVisits(visits)).apply()
    }

    /**
     * La semana de palabras del dia que los tiles leen, y desde que fecha corre.
     *
     * Existe porque **un tile no puede calcularla**: `onTileRequest` corre en el hilo principal
     * con 10 s de tope, y abrir un pack de decenas o cientos de MB ahi esta descartado por
     * contrato de la API. La app, que ya lo tiene abierto, la adelanta y la deja escrita.
     *
     * Mismo codec que el historial y las guardadas (D-102): es la misma forma de dato y un
     * segundo formato es un segundo formato que puede divergir. La fecha va en su propia clave
     * en vez de como quinto campo, justamente para no tocar ese codec.
     */
    fun weekWords(context: Context): Pair<String?, List<Visit>> {
        val prefs = prefs(context)
        return prefs.getString(KEY_WEEK_SINCE, null) to
            parseVisits(prefs.getString(KEY_WEEK_WORDS, null).orEmpty())
    }

    fun rememberWeekWords(context: Context, since: String, words: List<Visit>) {
        prefs(context).edit()
            .putString(KEY_WEEK_SINCE, since)
            .putString(KEY_WEEK_WORDS, serializeVisits(words))
            .apply()
    }

    /** Los ajustes. Igual que el historial: la politica vive arriba, aca solo se serializa. */
    fun settings(context: Context): Settings =
        parseSettings(prefs(context).getString(KEY_SETTINGS, null).orEmpty())

    fun rememberSettings(context: Context, settings: Settings) {
        prefs(context).edit().putString(KEY_SETTINGS, serializeSettings(settings)).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences("dictionary", Context.MODE_PRIVATE)

    private const val KEY_PACK = "pack_activo"
    private const val KEY_HISTORY = "historial"
    private const val KEY_SETTINGS = "ajustes"
    private const val KEY_FAVORITES = "favoritos"
    private const val KEY_WEEK_WORDS = "palabras_semana"
    private const val KEY_WEEK_SINCE = "palabras_desde"


    /**
     * Todos los packs instalados, por nombre.
     *
     * Antes esto devolvia **solo el primero**, y con dos packs eso escondia el español en
     * silencio porque "en-..." ordena antes que "es-...". El orden sigue importando --dos
     * arranques tienen que ver la misma lista-- pero ya no decide cual se abre.
     */
    internal fun installedPacks(dir: File): List<File> =
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
    internal fun installAtomically(input: InputStream, dir: File, name: String): File {
        val partial = File(dir, "$name.part")
        val target = File(dir, name)
        partial.delete()
        try {
            input.use { from ->
                partial.outputStream().use { target -> from.copyTo(target, BUFFER) }
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

    private fun openFile(file: File): PackLoad =
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
