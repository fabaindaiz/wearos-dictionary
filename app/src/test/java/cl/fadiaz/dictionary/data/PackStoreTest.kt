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
 * Pack installation, which is this layer's other silent failure.
 *
 * **A truncated pack opens without error** and returns fewer results than it holds: neither
 * SQLite nor `PackFile` complains, because the file is a valid SQLite, just incomplete. There is
 * no layer below that catches it. That is why the copy ends with a rename and not by writing
 * over the destination.
 *
 * It runs on the JVM because atomicity belongs to the filesystem, not to Android.
 */
class PackStoreTest {

    private lateinit var dir: File

    @BeforeTest
    fun before() {
        dir = File.createTempFile("packs", "").let { it.delete(); it.mkdirs(); it }
    }

    @AfterTest
    fun after() {
        dir.deleteRecursively()
    }

    private fun content(n: Int) = ByteArray(n) { (it % 251).toByte() }

    @Test
    fun `un pack incluido que se ACTUALIZO desde el catalogo no se re-extrae`() {
        // ⚠️ Visto en el emulador el 2026-09-22, y es un defecto que la descarga CREO. `es-core.db`
        // viene en el APK; actualizarlo desde el catalogo reescribe ese mismo archivo. Sin esto, la
        // siguiente version de la app re-extrae todos sus assets y **pisa en silencio el pack nuevo
        // con el viejo**: un downgrade que nadie reporta, porque el pack sigue abriendo.
        //
        // La regla: el catalogo gana sobre el APK. Lo incluido existe para arrancar de cero.
        assertEquals(
            emptyList(),
            PackStore.assetsToExtract(
                assets = listOf("es-core.db"),
                installed = listOf("es-core.db"),
                last = 4,
                current = 5,
                downloaded = setOf("es-core.db"),
            ),
            "una version nueva de la app no puede pisar lo que se bajo del catalogo",
        )
    }

    @Test
    fun `lo incluido que NO se actualizo si se re-extrae al subir de version`() {
        // La regla anterior sigue valiendo: un pack del APK es suyo y viaja con el.
        assertEquals(
            listOf("en-core.db"),
            PackStore.assetsToExtract(
                assets = listOf("en-core.db", "es-core.db"),
                installed = listOf("en-core.db", "es-core.db"),
                last = 4,
                current = 5,
                downloaded = setOf("es-core.db"),
            ),
        )
    }

    @Test
    fun aCompleteCopyLeavesThePackAndNoLeftovers() {
        val bytes = content(300_000)
        val target = PackStore.installAtomically(bytes.inputStream(), dir, "es.db")

        assertEquals("es.db", target.name)
        assertTrue(bytes.contentEquals(target.readBytes()), "el contenido tiene que ser identico")
        assertEquals(listOf("es.db"), dir.list()!!.sorted(), "no tiene que quedar ningun .part")
    }

    @Test
    fun aCopyThatIsCutOffLeavesNoHalfWrittenPack() {
        // The real case: the disk fills up, or the user kills the app during the 69 MB. If that
        // left an incomplete `.db`, the next launch would open it WITH NO ERROR and search a
        // dictionary that is missing words.
        assertFailsWith<IOException> {
            PackStore.installAtomically(TruncatedStream(120_000), dir, "es.db")
        }

        assertFalse(File(dir, "es.db").exists(), "no puede quedar un pack a medio escribir")
        assertEquals(emptyList(), dir.list()!!.sorted(), "ni el temporal: ocupa disco para nada")
    }

    @Test
    fun anOrphanPartFromAnEarlierTryDoesNotBlockTheNext() {
        // If the app died during an install, the `.part` survives. The next attempt has to
        // overwrite it, not fail and not append to it.
        File(dir, "es.db.part").writeBytes(content(50_000))

        val bytes = content(10_000)
        val target = PackStore.installAtomically(bytes.inputStream(), dir, "es.db")

        assertTrue(bytes.contentEquals(target.readBytes()))
        assertEquals(listOf("es.db"), dir.list()!!.sorted())
    }

    @Test
    fun installingTwiceLeavesTheNewPack() {
        PackStore.installAtomically(content(1_000).inputStream(), dir, "es.db")
        val nuevo = content(2_000)
        PackStore.installAtomically(nuevo.inputStream(), dir, "es.db")

        assertTrue(nuevo.contentEquals(File(dir, "es.db").readBytes()))
    }

    @Test
    fun withNoPacksInstalledItReturnsNothing() {
        assertEquals(emptyList(), PackStore.installedPacks(dir))
    }

    @Test
    fun theHalfInstalledPartIsNotMistakenForAPack() {
        // `es.db.part` does not end in `.db`, and that is no accident of naming: if it were
        // picked as a pack, the app would open precisely the incomplete file the rename exists
        // to avoid.
        File(dir, "es.db.part").writeBytes(content(1_000))
        assertEquals(emptyList(), PackStore.installedPacks(dir))
    }

    @Test
    fun withSeveralPacksItReturnsThemAllInAStableOrder() {
        // REPLACES `withSeveralPacksTheChosenOneIsDeterministic`, which froze exactly what had
        // to be killed: returning ONLY the alphabetically first one. With two packs installed
        // that hid Spanish in silence, because "en-..." sorts before "es-...".
        //
        // The order still matters --two launches have to see the same list-- but it no longer
        // decides which one opens: the user decides that with the selector.
        listOf("zz.db", "aa.db", "mm.db").forEach { File(dir, it).writeBytes(content(10)) }
        repeat(3) {
            assertEquals(listOf("aa.db", "mm.db", "zz.db"), PackStore.installedPacks(dir).map { it.name })
        }
    }

    // --- What gets extracted from the APK, and above all what does NOT -----------------------
    //
    // The APK ships ONE demo pack, small, so the app has something to show when freshly
    // installed. The real dictionaries --69 and 295 MB-- do not travel inside: they arrive
    // through `adb push` or, once it exists, through the installer (D-071).

    @Test
    fun theBundledPackIsExtractedTheFirstTime() {
        assertEquals(
            listOf("es-core.db"),
            PackStore.assetsToExtract(listOf("es-core.db"), emptyList(), last = 0, current = 4),
        )
    }

    @Test
    fun anAlreadyInstalledPackIsNotExtractedAgain() {
        // Sin esto se copia en cada arranque.
        assertEquals(
            emptyList(),
            PackStore.assetsToExtract(
                listOf("es-core.db"), listOf("es-core.db"), last = 4, current = 4),
        )
    }

    @Test
    fun aDictionaryInstalledByHandIsLeftAlone() {
        // El camino de hoy para los packs reales: `adb push` a filesDir/packs. Si la extracción
        // los pisara o los borrara, ese camino no existiría.
        assertEquals(
            emptyList(),
            PackStore.assetsToExtract(
                listOf("es-core.db"), listOf("es-core.db", "es-def-wikc.db"),
                last = 4, current = 4,
            ),
        )
    }

    @Test
    fun aNewVersionOfTheAppReExtractsItsBundledPacks() {
        // ⚠️ **El bug que esto cierra, visto en el reloj.** La app avisaba que `demo-es-en.db` no
        // era compatible: se extrajo el 18/09 con `deflate-v1` y la app pasó a `deflate-v2`
        // (D-119). El APK nuevo traía uno bueno y **nunca se copiaba**, porque la extracción
        // miraba sólo si el NOMBRE faltaba en disco. Un pack incluido se extraía una vez y no se
        // actualizaba jamás.
        //
        // ⚠️ Y deja de ser una molestia cuando el pack incluido es el núcleo: ahí no sería un
        // juguete desactualizado, sería el diccionario.
        assertEquals(
            listOf("en-core.db", "es-core.db"),
            PackStore.assetsToExtract(
                listOf("es-core.db", "en-core.db"), listOf("es-core.db", "en-core.db"),
                last = 3, current = 4,
            ),
        )
    }

    @Test
    fun theDictionariesTheUserInstalledSurviveAnUpdate() {
        // El control de lo de arriba: re-extraer lo del APK no puede tocar lo que el usuario
        // instaló por su cuenta. Son 372 MB que nadie quiere volver a copiar.
        assertEquals(
            listOf("es-core.db"),
            PackStore.assetsToExtract(
                listOf("es-core.db"), listOf("es-core.db", "es-def-wikc.db"),
                last = 3, current = 4,
            ),
        )
    }

    @Test
    fun aNewBundledPackIsExtractedEvenWithDictionariesInstalled() {
        // Actualizar el APK con un pack incluido distinto no puede quedar invisible detrás de
        // los packs reales.
        assertEquals(
            listOf("es-core.db"),
            PackStore.assetsToExtract(
                listOf("es-core.db"), listOf("es-def-wikc.db"), last = 4, current = 4),
        )
    }

    /** Cuts off at `upTo` bytes, like a full disk. */
    private class TruncatedStream(private val upTo: Int) : InputStream() {
        private var read = 0

        override fun read(): Int = read(ByteArray(1), 0, 1).let { if (it < 0) -1 else 0 }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (read >= upTo) throw IOException("no space left on device")
            val n = minOf(len, upTo - read)
            read += n
            return n
        }
    }

    @Test
    fun theDigestIsCheckedBEFORETheRenameAndAMismatchLeavesNoPack() {
        // ⚠️ El orden es todo. Un `.db` que no coincide con lo publicado **no puede llegar a
        // llamarse como el pack**: si se renombra primero y se comprueba después, ya hay un
        // diccionario corrupto en su lugar y el siguiente arranque lo abre sin quejarse.
        val bytes = content(300_000)
        assertFailsWith<IOException> {
            PackStore.installAtomically(
                bytes.inputStream(), dir, "es.db",
                expectedSha256 = "0".repeat(64),
            )
        }
        assertEquals(emptyList(), dir.list()!!.sorted(), "ni el pack ni el .part pueden quedar")
    }

    @Test
    fun aDigestThatMatchesInstallsNormally() {
        val bytes = content(300_000)
        val target = PackStore.installAtomically(
            bytes.inputStream(), dir, "es.db",
            expectedSha256 = sha256(bytes),
        )
        assertTrue(bytes.contentEquals(target.readBytes()))
        assertEquals(listOf("es.db"), dir.list()!!.sorted())
    }

    @Test
    fun theDigestComparisonIgnoresCaseAndSpaces() {
        // El valor va a venir de un catálogo escrito por una persona o por otra herramienta.
        // Rechazar un sha256 correcto por venir en mayúsculas sería un fallo tonto y difícil de
        // diagnosticar desde un reloj.
        val bytes = content(1_000)
        PackStore.installAtomically(
            bytes.inputStream(), dir, "es.db",
            expectedSha256 = "  " + sha256(bytes).uppercase() + "\n",
        )
        assertEquals(listOf("es.db"), dir.list()!!.sorted())
    }

    @Test
    fun withNoExpectedDigestNothingIsHashed() {
        // El pack de demostración sale de los assets y no tiene contra qué compararse. Calcular
        // un digest que nadie mira sería trabajo puro: sobre 300 MB en un reloj no es gratis.
        val bytes = content(1_000)
        val target = PackStore.installAtomically(bytes.inputStream(), dir, "demo.db")
        assertTrue(bytes.contentEquals(target.readBytes()))
    }

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

}
