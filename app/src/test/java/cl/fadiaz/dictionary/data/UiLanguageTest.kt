package cl.fadiaz.dictionary.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UiLanguageTest {

    @Test
    fun theEndonymsAreDistinctAndWrittenInTheirOwnLanguage() {
        // If two rows read the same, the picker stops being a picker. And the names are not
        // resources on purpose: somebody whose watch came up in a language they cannot read
        // has to be able to find their own line.
        val endonimos = UiLanguage.entries.map { it.endonym }
        assertEquals(endonimos.size, endonimos.toSet().size)
        assertEquals("Español", UiLanguage.ES.endonym)
        assertEquals("English", UiLanguage.EN.endonym)
    }

    @Test
    fun anEmptyTagIsAutomatic() {
        // `LocaleManager.applicationLocales` comes back EMPTY when nobody chose, which is the
        // automatic case, and the platform is free to hand it over as "" instead of null.
        assertNull(UiLanguage.of(null))
        assertNull(UiLanguage.of(""))
    }

    @Test
    fun theRegionSubtagDoesNotLoseTheSelection() {
        // The watch may report "es-CL" or "es-419" for what the picker wrote as "es". Comparing
        // the whole tag would leave the list with NOTHING marked, and the user would read that
        // as the choice having been forgotten.
        assertEquals(UiLanguage.ES, UiLanguage.of("es-CL"))
        assertEquals(UiLanguage.ES, UiLanguage.of("es-419"))
        assertEquals(UiLanguage.EN, UiLanguage.of("en-US"))
    }

    @Test
    fun aLanguageThisAppDoesNotHaveIsNotInvented() {
        // Falling back to the first entry would show "Español" selected on a watch in Portuguese
        // that is actually being served the English base.
        assertNull(UiLanguage.of("pt-BR"))
    }
}
