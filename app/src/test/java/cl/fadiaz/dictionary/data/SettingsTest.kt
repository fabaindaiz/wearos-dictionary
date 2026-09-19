package cl.fadiaz.dictionary.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The settings codec, which is where this can corrupt itself in silence.
 *
 * Same contract as `VisitTest`: it is stored as text in SharedPreferences, so the format is an
 * agreement with the disk. An old version after an update cannot bring the app down.
 */
class SettingsTest {

    @Test
    fun whatIsStoredComesBack() {
        for (scale in TextScale.entries) {
            val original = Settings(textScale = scale)
            assertEquals(original, parseSettings(serializeSettings(original)))
        }
    }

    @Test
    fun emptyTextGivesTheFactorySettings() {
        assertEquals(Settings(), parseSettings(""))
    }

    @Test
    fun aValueThatNoLongerExistsFallsBackToFactory() {
        // The case of a scale removed in a newer version. It cannot bring down startup.
        assertEquals(Settings(), parseSettings("escala=ENORME"))
    }

    @Test
    fun formatlessGarbageBringsNothingDown() {
        assertEquals(Settings(), parseSettings("=\nsin igual\n\n===="))
    }

    @Test
    fun theNormalScaleChangesNothing() {
        // If NORMAL were not exactly 1, respecting the system scale (WO-V1) would stop being
        // true for anyone who changed nothing.
        assertEquals(1.0f, TextScale.NORMAL.factor)
    }
}
