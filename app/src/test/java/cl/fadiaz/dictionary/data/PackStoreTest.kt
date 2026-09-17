package cl.fadiaz.dictionary.data

import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * La instalacion del pack, que es la otra falla silenciosa de esta capa.
 *
 * **Un pack truncado se abre sin error** y devuelve menos resultados de los que tiene: ni SQLite
 * ni `PackFile` se quejan, porque el archivo es un SQLite valido, solo que incompleto. No hay
 * ninguna capa mas abajo que lo detecte. Por eso la copia termina con un rename y no escribiendo
 * sobre el destino.
 *
 * Corre en la JVM porque la atomicidad es del sistema de archivos, no de Android.
 */
class PackStoreTest {

    private lateinit var dir: File

    @BeforeTest
    fun antes() {
        dir = File.createTempFile("packs", "").let { it.delete(); it.mkdirs(); it }
    }

    @AfterTest
    fun despues() {
        dir.deleteRecursively()
    }

    private fun contenido(n: Int) = ByteArray(n) { (it % 251).toByte() }

    @Test
    fun unaCopiaCompletaDejaElPackYNoDejaBasura() {
        val bytes = contenido(300_000)
        val destino = PackStore.instalarAtomico(bytes.inputStream(), dir, "es.db")

        assertEquals("es.db", destino.name)
        assertTrue(bytes.contentEquals(destino.readBytes()), "el contenido tiene que ser identico")
        assertEquals(listOf("es.db"), dir.list()!!.sorted(), "no tiene que quedar ningun .part")
    }

    @Test
    fun unaCopiaQueSeCortaNoDejaUnPackAMedioEscribir() {
        // El caso real: se acaba el disco, o el usuario mata la app durante los 69 MB. Si eso
        // dejara un `.db` incompleto, el proximo arranque lo abriria SIN ERROR y buscaria en un
        // diccionario al que le faltan palabras.
        assertFailsWith<IOException> {
            PackStore.instalarAtomico(StreamQueSeCorta(120_000), dir, "es.db")
        }

        assertFalse(File(dir, "es.db").exists(), "no puede quedar un pack a medio escribir")
        assertEquals(emptyList(), dir.list()!!.sorted(), "ni el temporal: ocupa disco para nada")
    }

    @Test
    fun unPartHuerfanoDeUnIntentoAnteriorNoBloqueaElSiguiente() {
        // Si la app murio durante una instalacion, el `.part` sobrevive. El intento siguiente
        // tiene que pisarlo, no fallar ni concatenarse encima.
        File(dir, "es.db.part").writeBytes(contenido(50_000))

        val bytes = contenido(10_000)
        val destino = PackStore.instalarAtomico(bytes.inputStream(), dir, "es.db")

        assertTrue(bytes.contentEquals(destino.readBytes()))
        assertEquals(listOf("es.db"), dir.list()!!.sorted())
    }

    @Test
    fun instalarDosVecesDejaElPackNuevo() {
        PackStore.instalarAtomico(contenido(1_000).inputStream(), dir, "es.db")
        val nuevo = contenido(2_000)
        PackStore.instalarAtomico(nuevo.inputStream(), dir, "es.db")

        assertTrue(nuevo.contentEquals(File(dir, "es.db").readBytes()))
    }

    @Test
    fun sinPacksInstaladosNoDevuelveNada() {
        assertEquals(emptyList(), PackStore.packsInstalados(dir))
    }

    @Test
    fun elPartAMedioInstalarNoSeConfundeConUnPack() {
        // `es.db.part` no termina en `.db`, y eso no es un accidente del nombre: si se eligiera
        // como pack, la app abriria justo el archivo incompleto que el rename existe para evitar.
        File(dir, "es.db.part").writeBytes(contenido(1_000))
        assertEquals(emptyList(), PackStore.packsInstalados(dir))
    }

    @Test
    fun conVariosPacksLosDevuelveTodosEnOrdenEstable() {
        // REEMPLAZA a `conVariosPacksElegidoEsDeterminista`, que congelaba justo lo que habia
        // que matar: devolver SOLO el primero alfabetico. Con dos packs instalados eso escondia
        // el español en silencio, porque "en-..." ordena antes que "es-...".
        //
        // El orden sigue importando --dos arranques tienen que ver la misma lista-- pero ya no
        // decide cual se abre: eso lo decide el usuario con el selector.
        listOf("zz.db", "aa.db", "mm.db").forEach { File(dir, it).writeBytes(contenido(10)) }
        repeat(3) {
            assertEquals(listOf("aa.db", "mm.db", "zz.db"), PackStore.packsInstalados(dir).map { it.name })
        }
    }

    // --- Que extraer del APK, y sobre todo que NO --------------------------------------------

    @Test
    fun unPackYaInstaladoNoSeVuelveAExtraer() {
        // Sin esto se copian 72 MB en cada arranque.
        assertEquals(emptyList(), PackStore.queFaltaExtraer(listOf("es.db"), listOf("es.db")))
    }

    @Test
    fun unAssetNuevoSeExtraeAunqueYaHayaOtroInstalado() {
        // Es el camino de actualizar el APK: se agrega ingles y el español ya esta en disco.
        assertEquals(
            listOf("en.db"),
            PackStore.queFaltaExtraer(listOf("en.db", "es.db"), listOf("es.db")),
        )
    }

    @Test
    fun unPackPuestoAManoNoSeToca() {
        // D-071: `adb push` a filesDir/packs es el camino para iterar sin rearmar el APK.
        // Si la extraccion lo borrara o lo pisara, ese camino no existiria.
        assertEquals(emptyList(), PackStore.queFaltaExtraer(listOf("es.db"), listOf("es.db", "mio.db")))
    }

    @Test
    fun sinNadaInstaladoSeExtraeTodoLoQueHaya() {
        assertEquals(
            listOf("en.db", "es.db"),
            PackStore.queFaltaExtraer(listOf("es.db", "en.db"), emptyList()),
        )
    }

    /** Se corta a los `hasta` bytes, como un disco lleno. */
    private class StreamQueSeCorta(private val hasta: Int) : InputStream() {
        private var leidos = 0

        override fun read(): Int = read(ByteArray(1), 0, 1).let { if (it < 0) -1 else 0 }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (leidos >= hasta) throw IOException("no space left on device")
            val n = minOf(len, hasta - leidos)
            leidos += n
            return n
        }
    }
}
