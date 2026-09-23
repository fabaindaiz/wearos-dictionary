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

/** How a download ended. */
sealed interface DownloadResult {
    data class Installed(val file: File, val bytes: Long) : DownloadResult
    data class Failed(val reason: String) : DownloadResult
}

/**
 * Downloads a pack from the catalog and leaves it installed. **Two stages and two hashes.**
 *
 * ```
 * network ──► <name>.gz.part ──(.gz sha256)──► inflate ──(.db sha256)──► <name>  atomically
 * ```
 *
 * ⚠️ **The two hashes check different things and both are needed.** The `.gz` one says that *the
 * published bytes arrived* --and it is the only one that can say so, because a miscalculated
 * resume produces a file of the right length with a hole inside--. The `.db` one says that
 * *inflating produced the published pack*, and it is the one `installAtomically` compares **before
 * renaming** (D-165): a truncated `.db` is still valid SQLite and opens without a complaint.
 *
 * ⚠️ **Resuming exists because English is 192 MB compressed.** A Wi-Fi drop at 90 % with no
 * `Range` means starting from zero, and D-040 chose `HttpURLConnection` precisely for that. The
 * `.gz.part` is kept between attempts on purpose; it is the only thing that makes resuming real.
 *
 * ⚠️ **The `.gz` sha256 is computed at the end, over the whole file, and not while downloading.**
 * An incremental digest does not survive a resume --it would have to be rehydrated by reading what
 * was already downloaded, which is the same as this-- and it would also let through in silence the
 * case where the `.part` on disk belonged to another version of the pack.
 */
object PackDownloader {

    private const val CONNECT_TIMEOUT_MS = 15_000

    /** Per read, not per download: 192 MB does not fit in any reasonable total timeout. */
    private const val READ_TIMEOUT_MS = 30_000

    private const val BUFFER = 256 * 1024

    /** How many bytes between progress reports. Finer would mean waking the UI for nothing. */
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
                // ⚠️ It is deleted: if it stays, the next attempt RESUMES over bad bytes and fails
                // again forever, which is worse than downloading it afresh.
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

    /** Fetches the `.gz`, resuming if part of it is already on disk. */
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
            // 206 = the server accepted the Range. 200 = it ignored it and is sending everything,
            // so what was already downloaded is useless and we start over; appending to it would
            // give a doubled file.
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
                        // Cancelling the coroutine has to cut the download for real, rather than
                        // going on downloading 192 MB nobody will look at.
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
