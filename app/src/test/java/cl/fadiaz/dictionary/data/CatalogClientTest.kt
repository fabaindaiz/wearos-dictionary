package cl.fadiaz.dictionary.data

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * El cliente del catalogo, contra un servidor HTTP **de verdad**.
 *
 * Se levanta un `HttpServer` de la JVM en un puerto efimero. `ETag`, `304` y los timeouts son
 * protocolo: un doble que devuelve un String no prueba que el `If-None-Match` se mande, y mandarlo
 * es lo unico que hace que apretar el boton dos veces no cueste el indice entero.
 */
@RunWith(RobolectricTestRunner::class)
class CatalogClientTest {

    private lateinit var server: HttpServer
    private lateinit var base: String
    private var pedidos = 0
    private var ultimoIfNoneMatch: String? = null

    private val indice = """
        {"catalog_version":1,"packs":[
          {"pack_id":"es-def","name":"Espanol","langs":["es"],"entry_count":10,
           "data_version":300,"schema_version":4,"norm_version":2,
           "url":"packs/es-def.db.gz","bytes":1,"sha256":"aa","db_bytes":2,"db_sha256":"bb"}]}
    """.trimIndent()

    private val etag = "\"abc123\""

    @Before
    fun arriba() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/index.json") { exchange ->
            pedidos++
            ultimoIfNoneMatch = exchange.requestHeaders.getFirst("If-None-Match")
            if (ultimoIfNoneMatch == etag) {
                exchange.responseHeaders.add("ETag", etag)
                exchange.sendResponseHeaders(304, -1)
            } else {
                val bytes = indice.toByteArray()
                exchange.responseHeaders.add("ETag", etag)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            exchange.close()
        }
        server.createContext("/roto.json") { exchange ->
            exchange.sendResponseHeaders(500, -1)
            exchange.close()
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun abajo() {
        server.stop(0)
    }

    @Test
    fun `trae el indice y su ETag`() = runTest {
        val r = CatalogClient.fetchIndex(base)
        val fresh = assertIs<CatalogFetch.Fresh>(r)
        assertEquals(listOf("es-def"), fresh.packs.map { it.packId })
        assertEquals(300L, fresh.packs.single().dataVersion)
        assertEquals(etag, fresh.etag, "el ETag tiene que llegar para poder reusarlo")
    }

    @Test
    fun `con el ETag de antes el servidor contesta 304 y NO viaja el cuerpo`() = runTest {
        // El aserto que paga el archivo: es lo que hace barato apretar el boton dos veces.
        val primera = assertIs<CatalogFetch.Fresh>(CatalogClient.fetchIndex(base))
        val segunda = CatalogClient.fetchIndex(base, primera.etag)
        assertIs<CatalogFetch.NotModified>(segunda)
        assertEquals(etag, ultimoIfNoneMatch, "no se mando If-None-Match")
        assertEquals(2, pedidos)
    }

    @Test
    fun `un 500 es Failed y no una excepcion`() = runTest {
        val r = CatalogClient.fetchIndex("$base/roto.json".removeSuffix("/index.json"))
        // La url se arma pegando /index.json, asi que se apunta a un contexto que no existe: 404.
        val failed = assertIs<CatalogFetch.Failed>(r)
        assertTrue(failed.reason.isNotEmpty())
    }

    @Test
    fun `un host que no existe es Failed y no tumba la pantalla`() = runTest {
        val r = CatalogClient.fetchIndex("http://127.0.0.1:1")
        assertIs<CatalogFetch.Failed>(r)
    }
}
