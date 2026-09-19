package cl.fadiaz.dictionary.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * El tamano que se muestra al lado de cada diccionario.
 *
 * Los casos son los packs reales del proyecto, no numeros redondos inventados: es la unica forma
 * de que el test diga algo sobre lo que el usuario va a ver.
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
        // Con String.format seria "." o "," segun el Locale, y el mismo pack se veria distinto en
        // dos relojes. Aca es siempre coma.
        assertEquals("1,5 MB", asHumanSize(1_500_000))
    }

    @Test
    fun roundsInsteadOfTruncating() {
        // Truncar mostraria "1,9 MB" para algo que esta a un pelo de 2: al lado de un boton de
        // borrar, esa cifra es la que decide.
        assertEquals("2,0 MB", asHumanSize(1_960_000))
    }

    @Test
    fun anEmptyOrImpossiblePackShowsNoOddNumber() {
        assertEquals("0 kB", asHumanSize(0))
        assertEquals("—", asHumanSize(-1))
    }
}
