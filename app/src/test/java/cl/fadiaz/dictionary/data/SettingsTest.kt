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
    fun theFallbackSwitchSurvivesTheRoundTrip() {
        // D-266. `whatIsStoredComesBack` above only walks the scales, so a second field can be
        // dropped on the way to disk without that loop noticing.
        for (respaldo in listOf(true, false)) {
            val original = Settings(crossLanguageFallback = respaldo)
            assertEquals(original, parseSettings(serializeSettings(original)))
        }
    }

    @Test
    fun aSettingsFileWrittenBeforeTheSwitchKeepsTodaysBehaviour() {
        // A preference stored by any earlier version has only the scale. Reading it must give the
        // shipped default --strict-- and not "whatever a missing key parses to".
        assertEquals(false, parseSettings("escala=LARGE").crossLanguageFallback)
        assertEquals(TextScale.LARGE, parseSettings("escala=LARGE").textScale)
    }

    @Test
    fun anUnreadableSwitchDoesNotTurnItselfOn() {
        // ⚠️ `toBoolean` maps EVERYTHING that is not "true" to false, which happens to be safe
        // here -- but it also maps "TRUE" and " true" to false, silently losing a setting the
        // user made. `toBooleanStrictOrNull` makes the unparseable case explicit instead.
        assertEquals(false, parseSettings("respaldo=quizas").crossLanguageFallback)
        assertEquals(false, parseSettings("respaldo=").crossLanguageFallback)
    }

    @Test
    fun theNormalScaleChangesNothing() {
        // If NORMAL were not exactly 1, respecting the system scale (WO-V1) would stop being
        // true for anyone who changed nothing.
        assertEquals(1.0f, TextScale.NORMAL.factor)
    }
}
