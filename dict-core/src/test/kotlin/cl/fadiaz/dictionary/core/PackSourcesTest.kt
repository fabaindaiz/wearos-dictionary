package cl.fadiaz.dictionary.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The per-source credits a pack declares about itself (D-138).
 *
 * ⚠️ **One pack can carry content from several sources under DIFFERENT licences**, and that is
 * what made the old single `license` string a lie: the Spanish pack now mixes definitions under
 * CC BY-SA 4.0 with corpus sentences under CC BY 2.0 FR. Collapsing that into one name either
 * over-claims or under-credits, and attribution is the **condition of use** of the data, not a
 * courtesy (D-031).
 *
 * ⚠️ **Delimited text and not JSON, on purpose** -- the same choice the payload made and for the
 * same reason: it parses with no dependency in both languages, it can be read by eye while
 * debugging a pack, and `:dict-core` stays free of a JSON library it has no other use for.
 */
class PackSourcesTest {

    @Test
    fun `una fuente por linea, cinco campos`() {
        val texto = "definitions\tWikcionario\thttps://es.wiktionary.org/\t" +
            "CC BY-SA 4.0\thttps://creativecommons.org/licenses/by-sa/4.0/\n"
        val got = PackSource.parse(texto)
        assertEquals(1, got.size)
        assertEquals(PackSource.Role.DEFINITIONS, got[0].role)
        assertEquals("Wikcionario", got[0].name)
        assertEquals("CC BY-SA 4.0", got[0].license)
    }

    @Test
    fun `varias fuentes conservan su orden y cada una su licencia`() {
        // El caso real del pack español: definiciones CC BY-SA 4.0 y frases CC BY 2.0 FR.
        val texto = buildString {
            append("definitions\tWikcionario\thttps://es.wiktionary.org/\tCC BY-SA 4.0\t\n")
            append("sentences\tTatoeba\thttps://tatoeba.org/\tCC BY 2.0 FR\t\n")
        }
        val got = PackSource.parse(texto)
        assertEquals(listOf("Wikcionario", "Tatoeba"), got.map { it.name })
        assertEquals(listOf("CC BY-SA 4.0", "CC BY 2.0 FR"), got.map { it.license })
    }

    @Test
    fun `un rol desconocido NO tira la fuente`() {
        // Compatibilidad hacia adelante, igual que los tags del payload: un builder mas nuevo
        // puede declarar un rol que este lector no conoce, y perder el credito por no entender
        // la palabra seria exactamente el incumplimiento que esto viene a evitar.
        val got = PackSource.parse("etymology\tAlguien\thttps://x.org/\tCC0 1.0\t\n")
        assertEquals(1, got.size)
        assertEquals(PackSource.Role.OTHER, got[0].role)
        assertEquals("Alguien", got[0].name)
    }

    @Test
    fun `una linea incompleta se ignora en vez de romper el pack`() {
        // Un pack de un tercero puede venir mal formado. Abrirlo igual y mostrar lo que se
        // entiende es mejor que negarse a abrir un diccionario de 68 MB por una linea.
        val got = PackSource.parse("definitions\tWikcionario\nsentences\tTatoeba\thttps://t\tCC0\t\n")
        assertEquals(listOf("Tatoeba"), got.map { it.name })
    }

    @Test
    fun `vacio o ausente da lista vacia y no lanza`() {
        assertTrue(PackSource.parse("").isEmpty())
        assertTrue(PackSource.parse(null).isEmpty())
    }

    @Test
    fun `las licencias distintas se pueden listar sin repetir`() {
        // Lo que la pantalla necesita para decir "este pack se distribuye bajo X e Y".
        val texto = buildString {
            append("definitions\tA\thttps://a\tCC BY-SA 4.0\t\n")
            append("examples\tB\thttps://b\tCC BY-SA 4.0\t\n")
            append("sentences\tC\thttps://c\tCC BY 2.0 FR\t\n")
        }
        assertEquals(listOf("CC BY-SA 4.0", "CC BY 2.0 FR"),
                     PackSource.licenses(PackSource.parse(texto)))
    }
}
