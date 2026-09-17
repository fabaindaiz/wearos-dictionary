package cl.fadiaz.dictionary.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Casos que no se pueden expresar en el TSV compartido sin que un editor que recorta
 * whitespace los arruine. Estos mismos casos estan en test_normalize.py.
 */
class TextNormalizerTest {

    @Test
    fun `string vacio`() {
        assertEquals("", TextNormalizer.norm(""))
        assertEquals("", TextNormalizer.fuzzy("", FuzzyProfile.SPANISH))
    }

    @Test
    fun `se descartan espacios al inicio y al final`() {
        assertEquals("hola mundo", TextNormalizer.norm("  hola   mundo  "))
    }

    @Test
    fun `tabs y saltos de linea son separadores`() {
        assertEquals("a b", TextNormalizer.norm("a\t\nb"))
    }

    @Test
    fun `norm es idempotente`() {
        // Importante porque fuzzy() llama a norm() y el builder normaliza en varios pasos.
        for (text in listOf("Straße", "İstanbul", "self-made", "Łódź", "COVID-19")) {
            val once = TextNormalizer.norm(text)
            assertEquals(once, TextNormalizer.norm(once), "norm no es idempotente en '$text'")
        }
    }

    @Test
    fun `fuzzy es idempotente para los perfiles declarados`() {
        // El builder podria recalcular la clave sobre un valor ya plegado; no debe cambiar.
        for (profile in FuzzyProfile.entries) {
            for (text in listOf("correr", "llave", "knight", "Schule")) {
                val once = TextNormalizer.fuzzy(text, profile)
                assertEquals(
                    once,
                    TextNormalizer.fuzzy(once, profile),
                    "fuzzy(${profile.id}) no es idempotente en '$text'",
                )
            }
        }
    }

    @Test
    fun `un perfil desconocido cae en generic en vez de fallar`() {
        // Un pack construido con un perfil mas nuevo sigue siendo utilizable: solo pierde
        // tolerancia a errores. Fallar dejaria el pack inservible.
        assertEquals(FuzzyProfile.GENERIC, FuzzyProfile.fromId("klingon"))
        assertEquals(FuzzyProfile.GENERIC, FuzzyProfile.fromId(null))
        assertEquals(FuzzyProfile.SPANISH, FuzzyProfile.fromId("es"))
    }

    @Test
    fun `la salida de norm solo contiene letras digitos y espacios simples`() {
        val messy = "¡Hola, señor «Pérez»! -- 3.14 (nº 7) ﬁn"
        val result = TextNormalizer.norm(messy)
        assertTrue(
            result.all { it == ' ' || it.isLetterOrDigit() },
            "norm dejo caracteres inesperados: '$result'",
        )
        assertTrue(!result.contains("  "), "norm dejo espacios dobles: '$result'")
        assertEquals(result.trim(), result, "norm dejo espacios en los bordes: '$result'")
    }
}
