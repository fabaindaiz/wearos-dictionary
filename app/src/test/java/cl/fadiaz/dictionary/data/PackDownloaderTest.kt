package cl.fadiaz.dictionary.data

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Bajar un pack: dos etapas, dos hashes, y una reanudacion que tiene que reconstruir el archivo.
 *
 * El aserto que paga el archivo es el de **reanudar**: el ingles son 192 MB comprimidos, una caida
 * de Wi-Fi al 90 % es lo normal, y una reanudacion mal calculada produce un archivo de la longitud
 * correcta con un agujero dentro -- que al inflarse da un `.db` que SQLite abre sin una queja y que
 * devuelve menos palabras de las que dice tener. Por eso se comprueba el contenido y no el tamano.
 */
@RunWith(RobolectricTestRunner::class)
class PackDownloaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: HttpServer
    private lateinit var base: String

    /** El `.db` "publicado" y su `.gz`. Contenido reconocible para detectar un agujero. */
    private lateinit var db: ByteArray
    private lateinit var gz: ByteArray

    /** Si el servidor debe IGNORAR el `Range` y mandar siempre el archivo entero. */
    private var ignoraRange = false

    /** Bytes que el servidor corrompe al final, para simular un `.gz` que no cuadra. */
    private var corrompe = false

    private fun sha(b: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun pack(bytes: Long = gz.size.toLong(), url: String = "packs/prueba.db.gz") = CatalogPack(
        packId = "prueba", name = "Prueba", description = null, langs = listOf("es"),
        entryCount = 1, dataVersion = 202609220000L, schemaVersion = 4, normVersion = 2,
        license = null, url = url, bytes = bytes, sha256 = sha(gz),
        dbBytes = db.size.toLong(), dbSha256 = sha(db),
    )

    @Before
    fun arriba() {
        // 300 KB de contenido reconocible: cada bloque lleva su numero, asi que un agujero en el
        // medio se ve como un bloque que no es el que toca, no solo como un tamano distinto.
        db = buildString { for (i in 0 until 10_000) append("bloque-%05d;".format(i)) }.toByteArray()
        gz = java.io.ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(db) }
        }.toByteArray()

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/packs/prueba.db.gz") { ex ->
            val cuerpo = if (corrompe) gz.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() } else gz
            val rango = ex.requestHeaders.getFirst("Range")
            if (rango != null && !ignoraRange) {
                val desde = rango.removePrefix("bytes=").substringBefore('-').toInt()
                val trozo = cuerpo.copyOfRange(desde, cuerpo.size)
                ex.responseHeaders.add("Content-Range", "bytes $desde-${cuerpo.size - 1}/${cuerpo.size}")
                ex.sendResponseHeaders(206, trozo.size.toLong())
                ex.responseBody.use { it.write(trozo) }
            } else {
                ex.sendResponseHeaders(200, cuerpo.size.toLong())
                ex.responseBody.use { it.write(cuerpo) }
            }
            ex.close()
        }
        server.createContext("/packs/noexiste.db.gz") { ex ->
            ex.sendResponseHeaders(404, -1); ex.close()
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun abajo() = server.stop(0)

    @Test
    fun `baja, infla y deja el pack instalado`() = runTest {
        val dir = temp.newFolder("packs")
        val r = PackDownloader.download(base, pack(), dir)
        val ok = assertIs<DownloadResult.Installed>(r)
        assertEquals("prueba.db", ok.file.name)
        assertEquals(db.size.toLong(), ok.file.length())
        assertTrue(ok.file.readBytes().contentEquals(db), "el contenido instalado no es el publicado")
    }

    @Test
    fun `no deja basura detras`() = runTest {
        val dir = temp.newFolder("packs")
        PackDownloader.download(base, pack(), dir)
        assertEquals(
            listOf("prueba.db"),
            dir.list()!!.sorted(),
            "tendria que quedar solo el pack: ni .gz ni .part",
        )
    }

    @Test
    fun `REANUDA sobre lo ya bajado y reconstruye el archivo exacto`() = runTest {
        // El aserto que paga el archivo.
        val dir = temp.newFolder("packs")
        val mitad = gz.size / 2
        FileOutputStream(File(dir, "prueba.db.gz.part")).use { it.write(gz, 0, mitad) }

        val r = PackDownloader.download(base, pack(), dir)
        val ok = assertIs<DownloadResult.Installed>(r)
        assertTrue(
            ok.file.readBytes().contentEquals(db),
            "reanudar no reconstruyo el archivo: un agujero aqui da un .db que SQLite abre igual",
        )
    }

    @Test
    fun `si el servidor IGNORA el Range no se duplica el archivo`() = runTest {
        // ⚠️ Un servidor que contesta 200 a un `Range` manda el archivo ENTERO. Anadirlo sobre lo
        // que ya habia daria un `.gz` del doble de largo, que ni siquiera infla.
        ignoraRange = true
        val dir = temp.newFolder("packs")
        FileOutputStream(File(dir, "prueba.db.gz.part")).use { it.write(gz, 0, gz.size / 2) }

        val r = PackDownloader.download(base, pack(), dir)
        val ok = assertIs<DownloadResult.Installed>(r)
        assertTrue(ok.file.readBytes().contentEquals(db))
    }

    @Test
    fun `un gz que no coincide falla Y BORRA el parcial`() = runTest {
        // Si el parcial sobrevive, el proximo intento reanuda sobre bytes malos y falla para
        // siempre. Es peor que volver a bajarlo entero.
        corrompe = true
        val dir = temp.newFolder("packs")
        val r = PackDownloader.download(base, pack(), dir)
        assertIs<DownloadResult.Failed>(r)
        assertFalse(
            File(dir, "prueba.db.gz.part").exists(),
            "el parcial malo tiene que borrarse o la descarga queda envenenada",
        )
        assertFalse(File(dir, "prueba.db").exists(), "no puede quedar un pack a medias")
    }

    @Test
    fun `un db_sha256 equivocado NO deja el pack instalado`() = runTest {
        val dir = temp.newFolder("packs")
        val malo = pack().copy(dbSha256 = "0".repeat(64))
        val r = PackDownloader.download(base, malo, dir)
        assertIs<DownloadResult.Failed>(r)
        assertFalse(File(dir, "prueba.db").exists(), "installAtomically compara ANTES de renombrar")
    }

    @Test
    fun `un 404 es Failed y no lanza`() = runTest {
        val dir = temp.newFolder("packs")
        val r = PackDownloader.download(base, pack(url = "packs/noexiste.db.gz"), dir)
        assertIs<DownloadResult.Failed>(r)
    }

    @Test
    fun `el progreso llega hasta el total`() = runTest {
        val dir = temp.newFolder("packs")
        var ultimo = 0L
        PackDownloader.download(base, pack(), dir) { hecho, _ -> ultimo = hecho }
        assertEquals(gz.size.toLong(), ultimo, "el ultimo aviso tiene que ser el total")
    }
}
