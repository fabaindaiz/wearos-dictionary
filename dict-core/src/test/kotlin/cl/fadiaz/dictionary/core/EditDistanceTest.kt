package cl.fadiaz.dictionary.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EditDistanceTest {

    private fun distance(a: String, b: String, max: Int = 10) =
        EditDistance.damerauLevenshtein(a, b, max)

    @Test
    fun `strings iguales dan cero`() {
        assertEquals(0, distance("correr", "correr"))
        assertEquals(0, distance("", ""))
    }

    @Test
    fun `operaciones basicas cuestan uno`() {
        assertEquals(1, distance("correr", "corer"))   // borrado
        assertEquals(1, distance("corer", "correr"))   // insercion
        assertEquals(1, distance("correr", "corrar"))  // sustitucion
    }

    @Test
    fun `una transposicion adyacente cuesta uno y no dos`() {
        // El caso que justifica Damerau sobre Levenshtein: en un teclado de reloj invertir
        // dos letras es de los errores mas frecuentes.
        assertEquals(1, distance("the", "hte"))
        assertEquals(1, distance("correr", "ocrrer"))
    }

    @Test
    fun `contra string vacio la distancia es el largo`() {
        assertEquals(6, distance("correr", ""))
        assertEquals(6, distance("", "correr"))
    }

    @Test
    fun `supera el umbral y devuelve max mas uno`() {
        // El contrato no es "la distancia real", es "> maxDistance": eso permite cortar.
        assertEquals(3, distance("gato", "elefante", 2))
        assertEquals(2, distance("abc", "xyz", 1))
    }

    @Test
    fun `una diferencia de largo mayor al umbral corta de inmediato`() {
        assertEquals(1, EditDistance.damerauLevenshtein("a", "abcdefghij", 0))
    }

    @Test
    fun `respeta el umbral en el limite exacto`() {
        // "correr" -> "co" son cuatro borrados. Se eligen largos donde la distancia real (4)
        // y el valor de desborde (maxDistance + 1) no dan el mismo numero, si no el test
        // pasaria igual con el umbral mal implementado.
        assertEquals(4, distance("correr", "co", 4)) // el umbral alcanza justo
        assertEquals(4, distance("correr", "co", 5)) // umbral holgado, mismo resultado
        assertEquals(3, distance("correr", "co", 2)) // se pasa -> maxDistance + 1
    }

    @Test
    fun `es simetrica`() {
        val pairs = listOf(
            "correr" to "corer",
            "the" to "hte",
            "llave" to "yave",
            "knight" to "night",
            "" to "abc",
        )
        for ((a, b) in pairs) {
            assertEquals(distance(a, b), distance(b, a), "no es simetrica para '$a'/'$b'")
        }
    }

    @Test
    fun `cumple la desigualdad triangular en casos concretos`() {
        val a = "correr"
        val b = "corer"
        val c = "coser"
        assertTrue(
            distance(a, c) <= distance(a, b) + distance(b, c),
            "viola la desigualdad triangular",
        )
    }
}
