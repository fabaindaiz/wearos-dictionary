package cl.fadiaz.dictionary.data

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Como termino una descarga. */
sealed interface DownloadResult {
    data class Installed(val file: File, val bytes: Long) : DownloadResult
    data class Failed(val reason: String) : DownloadResult
}

/**
 * Baja un pack del catalogo y lo deja instalado. **Dos etapas y dos hashes.**
 *
 * ```
 * red ──► <nombre>.gz.part ──(sha256 del .gz)──► inflar ──(sha256 del .db)──► <nombre>  atomico
 * ```
 *
 * ⚠️ **Los dos hashes comprueban cosas distintas y hacen falta los dos.** El del `.gz` dice que
 * *llegaron los bytes publicados* --y es el unico que puede decirlo, porque una reanudacion mal
 * calculada produce un archivo de la longitud correcta con un agujero dentro--. El del `.db` dice
 * que *inflar produjo el pack publicado*, y es el que compara `installAtomically` **antes de
 * renombrar** (D-165): un `.db` truncado sigue siendo SQLite valido y se abre sin una queja.
 *
 * ⚠️ **La reanudacion existe porque el ingles son 192 MB comprimidos.** Una caida de Wi-Fi al 90 %
 * sin `Range` significa empezar de cero, y D-040 eligio `HttpURLConnection` justamente por eso.
 * El `.gz.part` se conserva entre intentos a proposito; es lo unico que hace la reanudacion real.
 *
 * ⚠️ **El sha256 del `.gz` se calcula al final, sobre el archivo completo, y no mientras baja.**
 * Un digest incremental no sobrevive a reanudar --habria que rehidratarlo leyendo lo ya bajado, que
 * es lo mismo que esto-- y ademas dejaria pasar sin ruido el caso en que el `.part` que estaba en
 * disco era de otra version del pack.
 */
object PackDownloader {

    private const val CONNECT_TIMEOUT_MS = 15_000

    /** Por lectura, no por descarga: 192 MB no caben en ningun timeout total razonable. */
    private const val READ_TIMEOUT_MS = 30_000

    private const val BUFFER = 256 * 1024

    /** Cada cuantos bytes se avisa del progreso. Mas fino seria despertar la UI para nada. */
    private const val PROGRESS_EVERY = 1L shl 20

    suspend fun download(
        baseUrl: String,
        pack: CatalogPack,
        dir: File,
        onProgress: (descargado: Long, total: Long) -> Unit = { _, _ -> },
    ): DownloadResult = withContext(Dispatchers.IO) {
        val nombre = pack.url.substringAfterLast('/').removeSuffix(".gz")
        if (nombre.isEmpty() || !nombre.endsWith(".db")) {
            return@withContext DownloadResult.Failed("el catalogo publica una url rara: ${pack.url}")
        }
        dir.mkdirs()
        val comprimido = File(dir, "$nombre.gz.part")
        try {
            bajar(baseUrl, pack, comprimido, onProgress)

            val llego = sha256(comprimido)
            if (!llego.equals(pack.sha256.trim(), ignoreCase = true)) {
                // ⚠️ Se borra: si queda, el proximo intento REANUDA sobre bytes malos y vuelve a
                // fallar para siempre, que es peor que volver a bajarlo.
                comprimido.delete()
                DictLog.w { "descarga ${pack.packId}: el .gz no coincide, se descarta y se reintenta entero" }
                return@withContext DownloadResult.Failed("los bytes descargados no son los publicados")
            }

            val instalado = GZIPInputStream(comprimido.inputStream().buffered(BUFFER)).use { gz ->
                PackStore.installAtomically(gz, dir, nombre, pack.dbSha256)
            }
            comprimido.delete()
            DictLog.i { "descarga ${pack.packId}: instalado ${instalado.length() / 1_048_576} MB en ${instalado.name}" }
            DownloadResult.Installed(instalado, instalado.length())
        } catch (e: IOException) {
            DictLog.e(e) { "descarga ${pack.packId}: fallo" }
            DownloadResult.Failed(e.message ?: "fallo la descarga")
        }
    }

    /** Trae el `.gz`, reanudando si ya hay parte de el en disco. */
    private suspend fun bajar(
        baseUrl: String,
        pack: CatalogPack,
        destino: File,
        onProgress: (Long, Long) -> Unit,
    ) {
        val ya = if (destino.exists()) destino.length() else 0L
        if (ya >= pack.bytes && pack.bytes > 0) {
            DictLog.i { "descarga ${pack.packId}: el .gz ya estaba completo, se verifica" }
            return
        }
        val url = baseUrl.trimEnd('/') + "/" + pack.url.trimStart('/')
        DictLog.i {
            "descarga ${pack.packId}: $url" + if (ya > 0) " reanudando desde $ya B" else ""
        }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            if (ya > 0) setRequestProperty("Range", "bytes=$ya-")
        }
        try {
            val code = conn.responseCode
            // 206 = el servidor acepto el Range. 200 = lo ignoro y manda todo, asi que lo ya
            // bajado no sirve y se empieza de cero; anadir sobre el daria un archivo doble.
            val reanuda = code == HttpURLConnection.HTTP_PARTIAL
            if (code != HttpURLConnection.HTTP_OK && !reanuda) {
                throw IOException("el servidor contesto $code")
            }
            if (ya > 0 && !reanuda) {
                DictLog.w { "descarga ${pack.packId}: el servidor ignoro el Range, se baja entera" }
            }
            var escrito = if (reanuda) ya else 0L
            conn.inputStream.use { entrada ->
                java.io.FileOutputStream(destino, reanuda).buffered(BUFFER).use { salida ->
                    val buf = ByteArray(BUFFER)
                    var ultimoAviso = 0L
                    while (true) {
                        // Cancelar la corrutina tiene que cortar la descarga de verdad, y no
                        // seguir bajando 192 MB que nadie va a mirar.
                        coroutineContext.ensureActive()
                        val n = entrada.read(buf)
                        if (n <= 0) break
                        salida.write(buf, 0, n)
                        escrito += n
                        if (escrito - ultimoAviso >= PROGRESS_EVERY) {
                            ultimoAviso = escrito
                            onProgress(escrito, pack.bytes)
                        }
                    }
                }
            }
            onProgress(escrito, pack.bytes)
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { entrada ->
            val buf = ByteArray(BUFFER)
            while (true) {
                val n = entrada.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
