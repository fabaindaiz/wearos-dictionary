package cl.fadiaz.dictionary.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Corre los vectores compartidos con tools/packbuilder contra la implementacion de Kotlin.
 *
 * El mismo archivo lo corre tools/packbuilder/tests/test_normalize.py. Es el unico mecanismo
 * que detecta que las dos implementaciones se separaron, y una divergencia no produce ningun
 * error en runtime: solo hace que falten palabras en los resultados.
 */
class NormalizationVectorsTest {

    private data class Vector(
        val line: Int,
        val function: String,
        val profile: String,
        val input: String,
        val expected: String,
    )

    private fun loadVectors(): List<Vector> {
        val dir = System.getProperty("vectors.dir")
            ?: fail("falta la propiedad de sistema vectors.dir (ver dict-core/build.gradle.kts)")
        val file = File(dir, "normalization-vectors.tsv")
        assertTrue(file.isFile, "no se encontro el archivo de vectores en ${file.absolutePath}")

        return file.readLines().mapIndexedNotNull { index, raw ->
            val line = raw.trimEnd('\r')
            if (line.isEmpty() || line.startsWith("#")) return@mapIndexedNotNull null
            // No se hace trim de los campos, solo del salto de linea: los espacios dentro de
            // un campo son parte del caso.
            val fields = line.split('\t')
            assertEquals(
                4,
                fields.size,
                "linea ${index + 1}: se esperaban 4 campos separados por tab, hay " +
                    "${fields.size}: $line",
            )
            Vector(index + 1, fields[0], fields[1], fields[2], fields[3])
        }
    }

    @Test
    fun `el archivo de vectores se encuentra y tiene contenido`() {
        // Un archivo vacio o no encontrado haria pasar todo lo demas en silencio.
        assertTrue(loadVectors().size > 40, "el archivo de vectores tiene sospechosamente pocos casos")
    }

    @Test
    fun `todos los vectores compartidos coinciden`() {
        for (vector in loadVectors()) {
            val actual = when (vector.function) {
                "norm" -> {
                    assertEquals("-", vector.profile, "norm no usa perfil")
                    TextNormalizer.norm(vector.input)
                }
                "fuzzy" -> {
                    val profile = FuzzyProfile.entries.firstOrNull { it.id == vector.profile }
                        ?: fail("linea ${vector.line}: perfil desconocido '${vector.profile}'")
                    TextNormalizer.fuzzy(vector.input, profile)
                }
                else -> fail("linea ${vector.line}: funcion desconocida '${vector.function}'")
            }
            assertEquals(
                vector.expected,
                actual,
                "linea ${vector.line}: ${vector.function}('${vector.input}') dio '$actual'",
            )
        }
    }

    @Test
    fun `los perfiles del TSV cubren todos los perfiles declarados`() {
        // Si se agrega un perfil sin vectores, su plegado nunca se compara contra Python.
        val covered = loadVectors().filter { it.function == "fuzzy" }.map { it.profile }.toSet()
        val declared = FuzzyProfile.entries.map { it.id }.toSet()
        assertEquals(declared, covered, "hay perfiles sin vectores compartidos")
    }
}
