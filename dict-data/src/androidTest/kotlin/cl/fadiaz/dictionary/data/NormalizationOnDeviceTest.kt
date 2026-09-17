package cl.fadiaz.dictionary.data

import androidx.test.platform.app.InstrumentationRegistry
import cl.fadiaz.dictionary.core.FuzzyProfile
import cl.fadiaz.dictionary.core.TextNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LOS VECTORES COMPARTIDOS, CORRIENDO EN UN DISPOSITIVO ANDROID.
 *
 * Este es el test que cierra la clase de bug que el proyecto no podia observar.
 *
 * `norm()` delega NFD y `lowercase()` en la plataforma (D-004). Que eso sea seguro se verifico
 * comparando Java 26 contra Python 3.9 en escritorio -- **nunca sobre Android**, donde cada
 * version del sistema trae su propia version de ICU. Hasta que este test corra, el invariante
 * central del proyecto esta probado en escritorio y ASUMIDO en el reloj.
 *
 * El archivo de vectores es el MISMO que corren los tests de :dict-core y del builder: lo copia
 * al dispositivo la tarea :dict-data:copySharedVectors. Una copia editada a mano seria una
 * segunda fuente de verdad del contrato mas importante del repo.
 *
 * Correr en cada nivel de API soportado, no en uno solo: el punto es justamente que las
 * versiones difieren.
 */
class NormalizationOnDeviceTest {

    private data class Vector(
        val line: Int,
        val function: String,
        val profile: String,
        val input: String,
        val expected: String,
    )

    private fun loadVectors(): List<Vector> {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val text = assets.open("normalization-vectors.tsv").bufferedReader().use { it.readText() }

        return text.lines().mapIndexedNotNull { index, raw ->
            val line = raw.trimEnd('\r')
            if (line.isEmpty() || line.startsWith("#")) return@mapIndexedNotNull null
            val fields = line.split('\t')
            assertEquals("linea ${index + 1} mal formada: $line", 4, fields.size)
            Vector(index + 1, fields[0], fields[1], fields[2], fields[3])
        }
    }

    @Test
    fun losVectoresLleganAlDispositivo() {
        // Si el asset faltara, el resto de los tests pasaria sobre una lista vacia.
        assertTrue("el archivo de vectores no llego al dispositivo", loadVectors().size > 40)
    }

    @Test
    fun normYFuzzyDanLoMismoEnElDispositivoQueEnElBuilder() {
        val fallos = mutableListOf<String>()

        for (vector in loadVectors()) {
            val actual = when (vector.function) {
                "norm" -> TextNormalizer.norm(vector.input)
                "fuzzy" -> TextNormalizer.fuzzy(vector.input, FuzzyProfile.fromId(vector.profile))
                else -> error("linea ${vector.line}: funcion desconocida '${vector.function}'")
            }
            if (actual != vector.expected) {
                fallos += "linea ${vector.line}: ${vector.function}('${vector.input}') dio " +
                    "'$actual', el pack tiene '${vector.expected}'"
            }
        }

        assertTrue(
            "La normalizacion de este dispositivo NO coincide con la del builder. Toda palabra " +
                "afectada va a faltar en los resultados, sin ningun error.\n" +
                fallos.joinToString("\n"),
            fallos.isEmpty(),
        )
    }

    @Test
    fun losCodePointsFueraDelRepertorioSeTratanIgualQueEnElBuilder() {
        // El caso exacto que divergia antes de fijar el repertorio (D-003). U+0870 se asigno en
        // Unicode 14: un Android con ICU nuevo lo veria como letra si preguntaramos a la
        // plataforma. Preguntamos a nuestra tabla, asi que debe salir separador.
        val conCodePointNuevo = "abࡰcd"
        assertEquals(
            "este dispositivo clasifica U+0870 distinto que el builder",
            "ab cd",
            TextNormalizer.norm(conCodePointNuevo),
        )
    }
}
