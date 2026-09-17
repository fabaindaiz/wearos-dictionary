package cl.fadiaz.dictionary.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Hace verificable que :dict-core siga siendo portable a Kotlin Multiplatform.
 *
 * "Es Kotlin puro" es una afirmacion que se degrada sola: alcanza con que alguien use
 * `String.format`, `java.util.Locale` o `codePoints()` sin pensarlo para que el modulo deje de
 * compilar para cualquier target que no sea JVM, y nada lo notaria hasta el dia que se intente
 * la conversion. Este test lo convierte en un fallo de build inmediato.
 *
 * El acuerdo es: TODA la API de plataforma vive en PlatformJvm.kt, que al pasar a KMP se mueve
 * a src/jvmMain/ mientras el resto se va a src/commonMain/.
 */
class ArchitectureTest {

    /** El unico archivo autorizado a tocar APIs de la JVM. */
    private val platformFile = "PlatformJvm.kt"

    /**
     * Lineas de codigo real, sin comentarios.
     *
     * Hace falta distinguirlos porque la documentacion de este modulo NOMBRA las APIs de la JVM
     * que dejo de usar y por que. Un escaner ingenuo marca justamente los comentarios que
     * explican el arreglo, que fue lo que paso la primera vez que corrio este test.
     */
    private fun codeLines(file: File): List<IndexedValue<String>> {
        val out = mutableListOf<IndexedValue<String>>()
        var inBlockComment = false
        file.readLines().forEachIndexed { index, raw ->
            var line = raw
            if (inBlockComment) {
                val close = line.indexOf("*/")
                if (close < 0) return@forEachIndexed
                line = line.substring(close + 2)
                inBlockComment = false
            }
            while (true) {
                val open = line.indexOf("/*")
                if (open < 0) break
                val close = line.indexOf("*/", open + 2)
                if (close < 0) {
                    line = line.substring(0, open)
                    inBlockComment = true
                    break
                }
                line = line.substring(0, open) + line.substring(close + 2)
            }
            val code = line.substringBefore("//").trim()
            if (code.isNotEmpty()) out.add(IndexedValue(index + 1, code))
        }
        return out
    }

    private fun sourceFiles(): List<File> {
        // El test corre desde el directorio del modulo.
        val root = File("src/main/kotlin")
        assertTrue(root.isDirectory, "no se encontro src/main/kotlin en ${root.absolutePath}")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            .also { assertTrue(it.size > 5, "se encontraron sospechosamente pocos archivos") }
    }

    @Test
    fun `solo PlatformJvm importa APIs de la JVM`() {
        val offenders = mutableListOf<String>()
        for (file in sourceFiles()) {
            if (file.name == platformFile) continue
            for ((number, code) in codeLines(file)) {
                if (code.startsWith("import java.") || code.startsWith("import javax.")) {
                    offenders.add("${file.name}:$number  $code")
                }
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "estos archivos deberian ser Kotlin puro. Mover lo que necesiten a $platformFile " +
                "detras de una funcion con tipos portables:\n" + offenders.joinToString("\n"),
        )
    }

    @Test
    fun `no se usan APIs de la JVM sin import`() {
        // Las que mas se cuelan, porque no necesitan import y parecen de Kotlin. Cada una tiene
        // equivalente portable ya escrito en el modulo.
        val forbidden = mapOf(
            "Character." to "usar UnicodeRepertoire.classify o CodePoint",
            ".codePoints()" to "usar la iteracion manual de TextNormalizer/PrefixRange",
            "System." to "no hay equivalente portable; repensar el diseño",
            "Thread(" to "la concurrencia es responsabilidad del llamador",
        )

        val offenders = mutableListOf<String>()
        for (file in sourceFiles()) {
            if (file.name == platformFile) continue
            for ((number, code) in codeLines(file)) {
                for ((needle, hint) in forbidden) {
                    if (code.contains(needle)) {
                        offenders.add("${file.name}:$number  '$needle' -> $hint")
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(), "APIs de la JVM fuera de $platformFile:\n" +
            offenders.joinToString("\n"))
    }

    @Test
    fun `String format no se usa porque no existe fuera de la JVM`() {
        // "%02x".format(...) compila en Kotlin/JVM y no existe en Kotlin/Native. Es el caso
        // mas traicionero porque parece stdlib de Kotlin.
        val offenders = sourceFiles()
            .filter { it.name != platformFile }
            .flatMap { file ->
                codeLines(file).mapNotNull { (number, code) ->
                    if (code.contains(".format(")) "${file.name}:$number" else null
                }
            }
        assertTrue(offenders.isEmpty(), ".format() no es portable, usado en: $offenders")
    }

    @Test
    fun `la tabla Unicode generada coincide con su fuente`() {
        // UnicodeRepertoire.kt y tools/unicode/repertoire.txt se generan juntos del mismo
        // origen. Si alguien regenera uno solo, o edita el .kt a mano, quedan dos repertorios
        // distintos y el builder y la app vuelven a clasificar distinto -- que es exactamente
        // el bug que este mecanismo existe para evitar.
        val vectorsDir = System.getProperty("vectors.dir")
            ?: fail("falta la propiedad de sistema vectors.dir")
        val source = File(File(vectorsDir).parentFile.parentFile, "unicode/repertoire.txt")
        assertTrue(source.isFile, "no se encontro repertoire.txt en ${source.absolutePath}")

        val header = source.readLines()
            .filterNot { it.startsWith("#") || it.isBlank() }
            .associate { it.substringBefore(' ') to it.substringAfter(' ') }

        assertEquals(
            header.getValue("sha256"),
            UnicodeRepertoire.DIGEST,
            "UnicodeRepertoire.kt no corresponde a repertoire.txt; " +
                "regenerar con python3 tools/unicode/gen_repertoire.py",
        )
        assertEquals(header.getValue("unicode_version"), UnicodeRepertoire.UNICODE_VERSION)
        assertEquals(header.getValue("ranges").toInt(), UnicodeRepertoire.RANGE_COUNT)
    }
}
