package cl.fadiaz.dictionary.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The size shown next to each dictionary.
 *
 * The cases are the project's real packs, not made-up round numbers: it is the only way for the
 * test to say anything about what the user is going to see.
 */
class ByteSizeTest {

    @Test
    fun theProjectsRealPacks() {
        assertEquals("72,2 MB", asHumanSize(72_212_480))   // español
        assertEquals("309,5 MB", asHumanSize(309_452_800)) // ingles
        assertEquals("53 kB", asHumanSize(53_248))         // el de demostracion
    }

    @Test
    fun switchesUnitWhereItShould() {
        assertEquals("999 kB", asHumanSize(999_000))
        assertEquals("1,0 MB", asHumanSize(1_000_000))
        assertEquals("999,9 MB", asHumanSize(999_900_000))
        assertEquals("1,0 GB", asHumanSize(1_000_000_000))
    }

    @Test
    fun theDecimalSeparatorDoesNotDependOnTheWatchLocale() {
        // With String.format it would be "." or "," depending on the Locale, and the same pack
        // would look different on two watches. Here it is always a comma.
        assertEquals("1,5 MB", asHumanSize(1_500_000))
    }

    @Test
    fun roundsInsteadOfTruncating() {
        // Truncating would show "1,9 MB" for something a hair away from 2: next to a delete
        // button, that figure is the one that decides.
        assertEquals("2,0 MB", asHumanSize(1_960_000))
    }

    @Test
    fun anEmptyOrImpossiblePackShowsNoOddNumber() {
        assertEquals("0 kB", asHumanSize(0))
        assertEquals("—", asHumanSize(-1))
    }
}
