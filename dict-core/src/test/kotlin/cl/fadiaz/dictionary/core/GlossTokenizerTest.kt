package cl.fadiaz.dictionary.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Partir una glosa en palabras buscables.
 *
 * Los rangos esperados estan **contados a mano** sobre el string del test, no copiados de lo que
 * devuelve el codigo: un rango corrido por uno pinta la palabra de al lado y abre la entrada
 * equivocada, y es la clase de error que un test escrito desde el output ratifica en vez de
 * atrapar.
 *
 * Lo que este test defiende de verdad es que la respuesta a "esto es una letra" salga de
 * `norm()` y no de una tabla propia: si alguien mete aca una regex de puntuacion, aparece una
 * segunda fuente de verdad sobre el invariante central (D-003, D-005).
 */
class GlossTokenizerTest {

    private fun spans(texto: String) = GlossTokenizer.tokenize(texto)

    @Test
    fun unaGlosaConAcentosDaSusPalabrasSinAcentos() {
        //                   0123456789...
        val texto = "Mamífero cánido doméstico."
        assertEquals(
            listOf(
                WordSpan(0, 8, "mamifero"),
                WordSpan(9, 15, "canido"),
                WordSpan(16, 25, "domestico"),
            ),
            spans(texto),
        )
    }

    @Test
    fun elGuionSeparaPorqueNormLoConvierteEnEspacio() {
        // No hay una regla de guiones en el tokenizer: `norm("self-made")` ya da "self made".
        assertEquals(listOf(WordSpan(0, 4, "self"), WordSpan(5, 9, "made")), spans("self-made"))
    }

    @Test
    fun laPuntuacionDeAperturaYCierreNoEntraEnElRango() {
        // Los refranes del Wikcionario vienen con comillas y signos: el rango tocable tiene que
        // ser la palabra, no la palabra mas el signo pegado.
        assertEquals(listOf(WordSpan(3, 6, "que")), spans("  ¿qué?  "))
    }

    @Test
    fun unTextoSinLetrasNoDaPalabras() {
        assertTrue(spans("… -- ¡!").isEmpty())
        assertTrue(spans("").isEmpty())
    }

    @Test
    fun unCodePointDelPlanoSuplementarioNoPartePalabras() {
        // Un emoji ocupa DOS Char. Si el recorrido fuera por Char en vez de por code point, el
        // rango de "b" saldria corrido y el par surrogate se partiria al medio.
        assertEquals(listOf(WordSpan(0, 1, "a"), WordSpan(3, 4, "b")), spans("a😀b"))
    }

    @Test
    fun cadaRangoRecortaExactamenteSuPalabraEnElOriginal() {
        // El contrato que usa la interfaz: pintar text.substring(start, end) tiene que pintar la
        // palabra, y normalizar ese mismo recorte tiene que dar la clave con la que se consulto.
        val texto = "El «perro» corrió tras el Ñandú, ¡rápido!"
        val encontradas = spans(texto)
        assertTrue(encontradas.isNotEmpty())
        for (span in encontradas) {
            assertEquals(span.norm, TextNormalizer.norm(texto.substring(span.start, span.end)))
        }
        assertEquals(
            listOf("el", "perro", "corrio", "tras", "el", "nandu", "rapido"),
            encontradas.map { it.norm },
        )
    }

    @Test
    fun losRangosVanEnOrdenYNoSeSolapan() {
        val encontradas = spans("Mamífero cánido doméstico, de la familia Canidae.")
        var anterior = 0
        for (span in encontradas) {
            assertTrue(span.start >= anterior, "el rango ${span.norm} se solapa con el anterior")
            assertTrue(span.end > span.start, "rango vacio en ${span.norm}")
            anterior = span.end
        }
    }
}
