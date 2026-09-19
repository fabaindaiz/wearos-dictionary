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
    fun theDemoPackIsExtractedTheFirstTime() {
        assertEquals(listOf("demo-es-en.db"), PackStore.missingFromDisk(listOf("demo-es-en.db"), emptyList()))
    }

    @Test
    fun anAlreadyInstalledPackIsNotExtractedAgain() {
        // Without this it gets copied on every launch.
        assertEquals(
            emptyList(),
            PackStore.missingFromDisk(listOf("demo-es-en.db"), listOf("demo-es-en.db")),
        )
    }

    @Test
    fun aDictionaryInstalledByHandIsLeftAlone() {
        // This is today's path for the real packs: `adb push` into filesDir/packs. If the
        // extraction overwrote or deleted them, that path would not exist.
        assertEquals(
            emptyList(),
            PackStore.missingFromDisk(listOf("demo-es-en.db"), listOf("demo-es-en.db", "es-def-wikc.db")),
        )
    }

    @Test
    fun aNewDemoInTheApkIsExtractedEvenWithDictionariesInstalled() {
        // Updating the APK with a different demo cannot stay invisible behind the real packs.
        assertEquals(
            listOf("demo-es-en.db"),
            PackStore.missingFromDisk(listOf("demo-es-en.db"), listOf("es-def-wikc.db")),
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
}
