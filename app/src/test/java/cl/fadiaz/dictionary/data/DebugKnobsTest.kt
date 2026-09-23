package cl.fadiaz.dictionary.data

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The app's internal knobs, and the two rules that keep them from becoming settings.
 *
 * Pure and Android-free so it enters the gate (D-072). The receiver itself needs a device and is
 * not tested here; what IS tested is every decision the receiver delegates.
 */
class DebugKnobsTest {

    @Test
    fun anUnknownKeyIsAFailureAndNotSilence() {
        // ⚠️ **The one that matters most.** These arrive from somebody typing into a shell, and a
        // typo that changes nothing while saying nothing is indistinguishable from a knob that
        // does not work -- and the second is what gets reported, hours later, as a bug.
        val w = DebugKnobs.write("catalogue", "http://localhost:8765")
        assertTrue(w is DebugKnobs.Written.UnknownKey)
        val dicho = DebugKnobs.describe(w)
        assertTrue(dicho.contains("catalogue"), dicho)
        // And it lists what DOES exist, because the next thing anybody does is guess again.
        assertTrue(dicho.contains(DebugKnobs.CATALOG), dicho)
    }

    @Test
    fun aValueTheKnobDoesNotAcceptChangesNothingAndSaysWhatWasExpected() {
        val w = DebugKnobs.write(DebugKnobs.CATALOG, "localhost:8765")
        assertTrue(w is DebugKnobs.Written.BadValue, "a url with no scheme is not a url")
        assertTrue(DebugKnobs.describe(w).contains("http"), DebugKnobs.describe(w))
    }

    @Test
    fun aGoodValueIsAccepted() {
        assertEquals(
            DebugKnobs.Written.Ok(DebugKnobs.CATALOG, "http://localhost:8765"),
            DebugKnobs.write(DebugKnobs.CATALOG, "http://localhost:8765"),
        )
        assertEquals(
            DebugKnobs.Written.Ok(DebugKnobs.SCALE, "LARGE"),
            DebugKnobs.write(DebugKnobs.SCALE, "LARGE"),
        )
    }

    @Test
    fun anEmptyValueClearsInsteadOfFailing() {
        // The reset spelling, and it is the one `adb shell` forces: an empty extra does not
        // survive the device's shell, so clearing arrives as a key with nothing behind it.
        assertEquals(
            DebugKnobs.Written.Cleared(DebugKnobs.CATALOG),
            DebugKnobs.write(DebugKnobs.CATALOG, ""),
        )
    }

    @Test
    fun theCatalogueIsNeverOfferedInSettings() {
        // ⚠️ **This is a security assertion wearing the costume of a preference test.** The index
        // comes from that server and every pack's sha256 comes from that index, so whoever
        // answers the url decides what the app installs. Verifying the hash proves the download
        // matches what the server said -- never that the server is the right one.
        //
        // If somebody ever flips this flag to show it on screen, this test is the conversation.
        assertTrue(DebugKnobs.knob(DebugKnobs.CATALOG)!!.sensitive)
        assertFalse(DebugKnobs.knob(DebugKnobs.SCALE)!!.sensitive)
    }

    @Test
    fun aSensitiveKnobIsStillWritableFromAdb() {
        // ⚠️ **The flag governs the SCREEN, not the door**, and this test exists because the
        // opposite reading is the obvious-looking hardening: someone skims `sensitive = true`,
        // makes `write` refuse it, and removes the only reason the knob exists -- aiming a build
        // at a development catalogue without rebuilding 112 MB.
        //
        // It would also buy nothing: `adb` reaches only a build that ships the receiver, and
        // release folds it out of the dex entirely.
        val knob = DebugKnobs.knob(DebugKnobs.CATALOG)!!
        assertTrue(knob.sensitive, "the premise of this test")
        assertEquals(
            DebugKnobs.Written.Ok(DebugKnobs.CATALOG, "http://localhost:8799"),
            DebugKnobs.write(DebugKnobs.CATALOG, "http://localhost:8799"),
        )
    }

    @Test
    fun everyKnobSaysWhatItIsAndWhatItTakes() {
        // Cheap, and it is what stops the fifth knob from arriving with an empty description:
        // the help text is the only documentation anybody reads at a prompt.
        DebugKnobs.KNOBS.forEach { k ->
            assertTrue(k.key.isNotBlank(), "a knob with no name")
            assertTrue(k.what.isNotBlank(), "${k.key} does not say what it is")
            assertTrue(k.expected.isNotBlank(), "${k.key} does not say what it takes")
        }
        assertEquals(
            DebugKnobs.KNOBS.size,
            DebugKnobs.KNOBS.map { it.key }.toSet().size,
            "two knobs share a name and one of them can never be written",
        )
    }

    @Test
    fun anOverrideDoesNotSurviveAVersionChange() {
        // ⚠️ **An override is scaffolding tied to ONE build.** A catalogue url left pointing at a
        // laptop that stopped serving turns the next session's "downloads are broken" into an
        // hour spent on the wrong thing.
        assertTrue(DebugKnobs.survives(writtenBy = 9, current = 9))
        assertFalse(DebugKnobs.survives(writtenBy = 8, current = 9))
    }

    @Test
    fun goingDOWNAVersionAlsoExpiresThem() {
        // ⚠️ **Equality and not `>=`**, because sideloading moves the number both ways. Which
        // direction it moved says nothing about whether the override still makes sense; that it
        // moved at all says everything. The pack memo's fingerprint makes the same choice.
        assertFalse(DebugKnobs.survives(writtenBy = 9, current = 8))
    }

    @Test
    fun aFreshInstallHasNothingToExpire() {
        // 0 is what the preference returns when nobody ever wrote it. It must not match any real
        // versionCode, or the first launch of a build would inherit overrides nobody set.
        assertFalse(DebugKnobs.survives(writtenBy = 0, current = 9))
    }
}
