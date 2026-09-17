package cl.fadiaz.dictionary.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrefixRangeTest {

    @Test
    fun `la cota incrementa el ultimo caracter`() {
        assertEquals("cos", PrefixRange.upperBound("cor"))
        assertEquals("b", PrefixRange.upperBound("a"))
    }

    @Test
    fun `sin prefijo no hay cota`() {
        // La consulta debe omitir la condicion de cota; pasar null la haria no matchear nada.
        assertNull(PrefixRange.upperBound(""))
    }

    @Test
    fun `el rango contiene exactamente las palabras con ese prefijo`() {
        // Esta es la propiedad de la que depende la busqueda por prefijo: comparacion binaria
        // de strings, igual que la collation BINARY de SQLite.
        val words = listOf(
            "cor", "cora", "corazon", "correr", "corto", "cosa", "cz", "c", "d", "bz", "corzz",
        )
        val prefix = "cor"
        val bound = PrefixRange.upperBound(prefix)!!

        val inRange = words.filter { it >= prefix && it < bound }.toSet()
        val expected = words.filter { it.startsWith(prefix) }.toSet()
        assertEquals(expected, inRange)
    }

    @Test
    fun `funciona con acentos y no latinos aunque norm normalmente los quite`() {
        // norm() deja solo letras/digitos/espacios, pero upperBound tambien se usa sobre
        // headwords crudos en herramientas de inspeccion.
        for (prefix in listOf("ñ", "日本", "café", "a b")) {
            val bound = PrefixRange.upperBound(prefix)!!
            assertTrue(prefix < bound, "la cota de '$prefix' no es mayor: '$bound'")
            assertTrue(
                (prefix + "zzz") < bound,
                "la cota de '$prefix' excluye palabras que si tienen el prefijo",
            )
        }
    }

    @Test
    fun `el espacio se incrementa al caracter siguiente`() {
        // "a " agrupa las palabras de dos palabras que empiezan con "a ": la cota es "a!".
        val bound = PrefixRange.upperBound("a ")!!
        assertTrue("a zzz" < bound)
        assertTrue("ab" > bound, "la cota no debe alcanzar 'ab'")
    }

    @Test
    fun `un code point maximo se descarta y se incrementa el anterior`() {
        val maxChar = String(Character.toChars(Character.MAX_CODE_POINT))
        assertEquals("b", PrefixRange.upperBound("a$maxChar"))
        // Compuesto solo por el maximo: no existe cota posible.
        assertNull(PrefixRange.upperBound(maxChar))
    }

    @Test
    fun `la cota nunca cae en el rango de surrogates`() {
        // U+D7FF + 1 seria U+D800, que no es un code point valido por si mismo.
        val bound = PrefixRange.upperBound("퟿")!!
        val codePoint = bound.codePointAt(0)
        assertTrue(
            codePoint !in 0xD800..0xDFFF,
            "la cota cayo en el rango de surrogates: U+${codePoint.toString(16)}",
        )
        assertEquals(0xE000, codePoint)
    }
}
