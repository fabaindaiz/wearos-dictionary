package cl.fadiaz.dictionary.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** El resultado de preguntarle al catalogo. */
sealed interface CatalogFetch {
    /** Llego un catalogo nuevo. [etag] se guarda para la proxima pregunta. */
    data class Fresh(val packs: List<CatalogPack>, val etag: String?) : CatalogFetch

    /** El servidor dijo 304: lo que ya se tenia sigue valiendo y no viajo ni un byte de cuerpo. */
    data object NotModified : CatalogFetch

    /** No se pudo. [reason] es para el log y para la pantalla, en ese orden. */
    data class Failed(val reason: String) : CatalogFetch
}

/**
 * Trae el `index.json` del catalogo. **Nada mas**: no descarga packs.
 *
 * ⚠️ **Solo se llama cuando el usuario aprieta el boton**, nunca al entrar a la pantalla ni de
 * fondo. Fue el pedido explicito, y coincide con la guia oficial de Wear OS, que clasifica el
 * acceso a red como *very high impact* --por encima de encender la pantalla-- (D-029,
 * `docs/bateria.md`). Un sondeo automatico del catalogo seria el gasto mas caro de la app.
 *
 * ⚠️ **Esto NO es la descarga de un pack.** Eso son 192 MB y va por WorkManager con las
 * restricciones de D-029 (cargando + Wi-Fi). El indice son unos KB y es una accion directa del
 * usuario: diferirla a que el reloj este cargando haria que el boton no hiciera nada visible.
 */
object CatalogClient {

    /** Cortados a proposito: un reloj con Wi-Fi malo no puede dejar el boton girando para siempre. */
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000

    /**
     * Tope del cuerpo del indice.
     *
     * Con ~10 packs el indice son unos KB. El tope existe porque **leer un cuerpo sin limite en un
     * reloj es como se llena la memoria por un servidor mal configurado**, y de un servidor de
     * desarrollo se puede esperar cualquier cosa.
     */
    private const val MAX_INDEX_BYTES = 512 * 1024

    /**
     * Pregunta por el indice, mandando [etag] si se tiene uno de antes.
     *
     * El `If-None-Match` es lo que hace que apretar el boton dos veces cueste un 304 sin cuerpo en
     * vez del indice entero. `tools/packserver.py` lo implementa.
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
                // Sin gzip a mano: el indice es chico y HttpURLConnection ya negocia la
                // compresion de cuerpos de texto por su cuenta.
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
            // Se atrapa `IOException` y no `Exception`: un problema de red es esperable y tiene
            // que llegar a la pantalla como un mensaje, pero un bug de parseo no se disfraza de
            // "no hay internet".
            DictLog.e(e) { "catalogo: no se pudo consultar $url" }
            CatalogFetch.Failed(e.message ?: "sin conexion")
        } finally {
            connection?.disconnect()
        }
    }
}
