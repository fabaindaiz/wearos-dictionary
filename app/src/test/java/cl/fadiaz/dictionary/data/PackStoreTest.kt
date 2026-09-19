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

    private fun content(n: Int) = ByteArray(n) { (it % 251).toByte() }

    @Test
    fun unaCopiaCompletaDejaElPackYNoDejaBasura() {
        val bytes = content(300_000)
        val target = PackStore.installAtomically(bytes.inputStream(), dir, "es.db")

        assertEquals("es.db", target.name)
        assertTrue(bytes.contentEquals(target.readBytes()), "el contenido tiene que ser identico")
        assertEquals(listOf("es.db"), dir.list()!!.sorted(), "no tiene que quedar ningun .part")
    }

    @Test
    fun unaCopiaQueSeCortaNoDejaUnPackAMedioEscribir() {
        // El caso real: se acaba el disco, o el usuario mata la app durante los 69 MB. Si eso
        // dejara un `.db` incompleto, el proximo arranque lo abriria SIN ERROR y buscaria en un
        // diccionario al que le faltan palabras.
        assertFailsWith<IOException> {
            PackStore.installAtomically(StreamQueSeCorta(120_000), dir, "es.db")
        }

        assertFalse(File(dir, "es.db").exists(), "no puede quedar un pack a medio escribir")
        assertEquals(emptyList(), dir.list()!!.sorted(), "ni el temporal: ocupa disco para nada")
    }

    @Test
    fun unPartHuerfanoDeUnIntentoAnteriorNoBloqueaElSiguiente() {
        // Si la app murio durante una instalacion, el `.part` sobrevive. El intento siguiente
        // tiene que pisarlo, no fallar ni concatenarse encima.
        File(dir, "es.db.part").writeBytes(content(50_000))

        val bytes = content(10_000)
        val target = PackStore.installAtomically(bytes.inputStream(), dir, "es.db")

        assertTrue(bytes.contentEquals(target.readBytes()))
        assertEquals(listOf("es.db"), dir.list()!!.sorted())
    }

    @Test
    fun instalarDosVecesDejaElPackNuevo() {
        PackStore.installAtomically(content(1_000).inputStream(), dir, "es.db")
        val nuevo = content(2_000)
        PackStore.installAtomically(nuevo.inputStream(), dir, "es.db")

        assertTrue(nuevo.contentEquals(File(dir, "es.db").readBytes()))
    }

    @Test
    fun sinPacksInstaladosNoDevuelveNada() {
        assertEquals(emptyList(), PackStore.installedPacks(dir))
    }

    @Test
    fun elPartAMedioInstalarNoSeConfundeConUnPack() {
        // `es.db.part` no termina en `.db`, y eso no es un accidente del nombre: si se eligiera
        // como pack, la app abriria justo el archivo incompleto que el rename existe para evitar.
        File(dir, "es.db.part").writeBytes(content(1_000))
        assertEquals(emptyList(), PackStore.installedPacks(dir))
    }

    @Test
    fun conVariosPacksLosDevuelveTodosEnOrdenEstable() {
        // REEMPLAZA a `conVariosPacksElegidoEsDeterminista`, que congelaba justo lo que habia
        // que matar: devolver SOLO el primero alfabetico. Con dos packs instalados eso escondia
        // el español en silencio, porque "en-..." ordena antes que "es-...".
        //
        // El orden sigue importando --dos arranques tienen que ver la misma lista-- pero ya no
        // decide cual se abre: eso lo decide el usuario con el selector.
        listOf("zz.db", "aa.db", "mm.db").forEach { File(dir, it).writeBytes(content(10)) }
        repeat(3) {
            assertEquals(listOf("aa.db", "mm.db", "zz.db"), PackStore.installedPacks(dir).map { it.name })
        }
    }

    // --- Que se extrae del APK, y sobre todo que NO ------------------------------------------
    //
    // El APK trae UN pack de demostracion, chico, para que la app tenga algo que mostrar recien
    // instalada. Los diccionarios de verdad --69 y 295 MB-- no viajan adentro: entran por
    // `adb push` o, cuando exista, por el instalador (D-071).

    @Test
    fun elPackDeDemoSeExtraeLaPrimeraVez() {
        assertEquals(listOf("demo-es-en.db"), PackStore.missingFromDisk(listOf("demo-es-en.db"), emptyList()))
    }

    @Test
    fun unPackYaInstaladoNoSeVuelveAExtraer() {
        // Sin esto se copia en cada arranque.
        assertEquals(
            emptyList(),
            PackStore.missingFromDisk(listOf("demo-es-en.db"), listOf("demo-es-en.db")),
        )
    }

    @Test
    fun unDiccionarioPuestoAManoNoSeToca() {
        // Es el camino de hoy para los packs de verdad: `adb push` a filesDir/packs. Si la
        // extraccion los pisara o los borrara, ese camino no existiria.
        assertEquals(
            emptyList(),
            PackStore.missingFromDisk(listOf("demo-es-en.db"), listOf("demo-es-en.db", "es-def-wikc.db")),
        )
    }

    @Test
    fun unaDemoNuevaEnElApkSeExtraeAunqueYaHayaDiccionariosInstalados() {
        // Actualizar el APK con otra demo no puede quedar invisible detras de los packs reales.
        assertEquals(
            listOf("demo-es-en.db"),
            PackStore.missingFromDisk(listOf("demo-es-en.db"), listOf("es-def-wikc.db")),
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
