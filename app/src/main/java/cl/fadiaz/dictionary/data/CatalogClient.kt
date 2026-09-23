package cl.fadiaz.dictionary.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The result of asking the catalog. */
sealed interface CatalogFetch {
    /** A new catalog arrived. [etag] is stored for the next question. */
    data class Fresh(val packs: List<CatalogPack>, val etag: String?) : CatalogFetch

    /** The server said 304: what was already held still stands and not one body byte travelled. */
    data object NotModified : CatalogFetch

    /** It could not be done. [reason] is for the log and for the screen, in that order. */
    data class Failed(val reason: String) : CatalogFetch
}

/**
 * Fetches the catalog's `index.json`. **Nothing else**: it downloads no packs.
 *
 * ⚠️ **It is only called when the user presses the button**, never on entering the screen and
 * never in the background. That was the explicit request, and it matches the official Wear OS
 * guidance, which classifies network access as *very high impact* --above turning the screen on--
 * (D-029, `docs/bateria.md`). Polling the catalog automatically would be the app's most expensive
 * cost.
 *
 * ⚠️ **This is NOT a pack download.** That is 192 MB and goes through WorkManager with D-029's
 * constraints (charging + Wi-Fi). The index is a few KB and is a direct user action: deferring it
 * until the watch is charging would make the button do nothing visible.
 */
object CatalogClient {

    /** Short on purpose: a watch on bad Wi-Fi cannot leave the button spinning forever. */
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000

    /**
     * Cap on the index body.
     *
     * With ~10 packs the index is a few KB. The cap exists because **reading an unbounded body on
     * a watch is how memory gets filled by a misconfigured server**, and anything can be expected
     * of a development server.
     */
    private const val MAX_INDEX_BYTES = 512 * 1024

    /**
     * Asks for the index, sending [etag] if one is held from before.
     *
     * The `If-None-Match` is what makes pressing the button twice cost a bodyless 304 instead of
     * the whole index. `tools/packserver.py` implements it.
     */
    suspend fun fetchIndex(
        baseUrl: String,
        etag: String? = null,
    ): CatalogFetch = withContext(Dispatchers.IO) {
        val url = baseUrl.trimEnd('/') + "/index.json"
        DictLog.i { "catalogo: preguntando a $url" + if (etag != null) " (con ETag)" else "" }
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                // No hand-rolled gzip: the index is small and HttpURLConnection already negotiates
                // compression of text bodies on its own.
                if (etag != null) setRequestProperty("If-None-Match", etag)
            }
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> {
                    DictLog.i { "catalogo: 304, sin cambios" }
                    CatalogFetch.NotModified
                }
                HttpURLConnection.HTTP_OK -> {
                    val body = connection.inputStream.use { it.readNBytes(MAX_INDEX_BYTES) }
                    val packs = Catalog.parse(body.decodeToString())
                    val nuevo = connection.getHeaderField("ETag")
                    DictLog.i { "catalogo: ${packs.size} packs, ${body.size} B" }
                    CatalogFetch.Fresh(packs, nuevo)
                }
                else -> {
                    DictLog.w { "catalogo: el servidor contesto $code" }
                    CatalogFetch.Failed("HTTP $code")
                }
            }
        } catch (e: IOException) {
            // `IOException` is caught and not `Exception`: a network problem is expected and has
            // to reach the screen as a message, but a parsing bug does not get disguised as "there
            // is no internet".
            DictLog.e(e) { "catalogo: no se pudo consultar $url" }
            CatalogFetch.Failed(e.message ?: "sin conexion")
        } finally {
            connection?.disconnect()
        }
    }
}
