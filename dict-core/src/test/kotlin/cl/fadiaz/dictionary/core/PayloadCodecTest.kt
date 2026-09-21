package cl.fadiaz.dictionary.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Comprueba que java.util.zip descomprime exactamente lo que comprimio el zlib de Python, con
 * el mismo diccionario precargado.
 *
 * El fixture lo genera tools/packbuilder/gen_payload_fixture.py. Sin este test, que los dos
 * lados hagan "deflate crudo con diccionario" seria un supuesto sin verificar, y el sintoma de
 * que no lo sea es que TODA entrada del pack falle al abrirse en el reloj.
 */
class PayloadCodecTest {

    private data class Fixture(
        val dictionary: ByteArray,
        val dictionaryDigest: String,
        val cases: List<Case>,
    )

    private data class Case(val description: String, val compressed: ByteArray, val expected: String)

    private fun loadFixture(): Fixture {
        val dir = System.getProperty("vectors.dir")
            ?: fail("falta la propiedad de sistema vectors.dir (ver dict-core/build.gradle.kts)")
        val file = File(dir, "payload-fixture.tsv")
        assertTrue(
            file.isFile,
            "no se encontro el fixture en ${file.absolutePath}; generarlo con " +
                "python3 tools/packbuilder/gen_payload_fixture.py",
        )

        var dictionary: ByteArray? = null
        var dictionaryDigest: String? = null
        val cases = mutableListOf<Case>()
        for (raw in file.readLines()) {
            val line = raw.trimEnd('\r')
            if (line.isEmpty() || line.startsWith("#")) continue
            val fields = line.split('\t')
            assertEquals(3, fields.size, "linea mal formada en el fixture: $line")
            if (fields[0] == "DICTIONARY") {
                dictionary = fields[1].hexToBytes()
            } else if (fields[0] == "DICTIONARY_SHA256") {
                dictionaryDigest = fields[1]
            } else {
                cases.add(Case(fields[0], fields[1].hexToBytes(), fields[2].unescape()))
            }
        }
        return Fixture(
            dictionary ?: fail("el fixture no trae la linea DICTIONARY"),
            dictionaryDigest ?: fail("el fixture no trae la linea DICTIONARY_SHA256"),
            cases.also { assertTrue(it.isNotEmpty(), "el fixture no trae casos") },
        )
    }

    private fun String.hexToBytes(): ByteArray =
        ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Deshace el escapado del generador: \\ \n \t . */
    private fun String.unescape(): String {
        val out = StringBuilder(length)
        var i = 0
        while (i < length) {
            val ch = this[i]
            if (ch == '\\' && i + 1 < length) {
                when (this[i + 1]) {
                    'n' -> { out.append('\n'); i += 2 }
                    't' -> { out.append('\t'); i += 2 }
                    '\\' -> { out.append('\\'); i += 2 }
                    else -> { out.append(ch); i++ }
                }
            } else {
                out.append(ch)
                i++
            }
        }
        return out.toString()
    }

    @Test
    fun `descomprime lo que comprimio Python, byte por byte`() {
        val fixture = loadFixture()
        for (case in fixture.cases) {
            val body = PayloadCodec.decode(case.compressed, fixture.dictionary)
            assertEquals(
                case.expected,
                PayloadCodec.render(body),
                "el caso '${case.description}' no se reconstruye igual",
            )
        }
    }

    @Test
    fun `una acepcion se queda con sus sinonimos y no con los de la siguiente`() {
        // Un sinonimo atribuido a la acepcion equivocada no falla ni loguea: sale como
        // contenido correcto. Es el modo de falla que este tag introduce.
        val body = PayloadCodec.parse("S\tuna\nY\tbobo\nS\totra\nY\tlisto\n")
        assertEquals(listOf("bobo"), body.senses[0].synonyms)
        assertEquals(listOf("listo"), body.senses[1].synonyms)
    }

    @Test
    fun `un sinonimo antes de la primera acepcion no tiene donde colgar`() {
        val body = PayloadCodec.parse("Y\thuerfano\nS\tla acepcion\n")
        assertEquals(1, body.senses.size)
        assertEquals(emptyList<String>(), body.senses[0].synonyms)
    }

    @Test
    fun `una acepcion se queda con sus antonimos y no con los de la siguiente`() {
        // Mismo modo de falla que los sinonimos, con peor consecuencia: un antonimo mal
        // atribuido no se lee como raro, se lee como lo contrario de otra cosa.
        val body = PayloadCodec.parse("S\tuna\nA\tfrio\nS\totra\nA\tlento\n")
        assertEquals(listOf("frio"), body.senses[0].antonyms)
        assertEquals(listOf("lento"), body.senses[1].antonyms)
    }

    @Test
    fun `un antonimo antes de la primera acepcion no tiene donde colgar`() {
        val body = PayloadCodec.parse("A\thuerfano\nS\tla acepcion\n")
        assertEquals(1, body.senses.size)
        assertEquals(emptyList<String>(), body.senses[0].antonyms)
    }

    @Test
    fun `sinonimos y antonimos no se mezclan`() {
        // El tag es lo unico que los separa, y confundirlos invierte el significado.
        val body = PayloadCodec.parse("S\tcaliente\nY\tardiente\nA\tfrio\n")
        assertEquals(listOf("ardiente"), body.senses[0].synonyms)
        assertEquals(listOf("frio"), body.senses[0].antonyms)
    }

    @Test
    fun `una acepcion se queda con sus relacionadas y no con las de la siguiente`() {
        val body = PayloadCodec.parse("S\tuna\nR\tprimera\nS\totra\nR\tsegunda\n")
        assertEquals(listOf("primera"), body.senses[0].related)
        assertEquals(listOf("segunda"), body.senses[1].related)
    }

    @Test
    fun `relacionadas, sinonimos y antonimos no se mezclan`() {
        // Los tres son listas de palabras y el tag es lo unico que las separa. Una relacionada
        // leida como sinonimo afirma una equivalencia que la fuente no da.
        val body = PayloadCodec.parse("S\tcaliente\nY\tardiente\nA\tfrio\nR\tcalor\n")
        assertEquals(listOf("ardiente"), body.senses[0].synonyms)
        assertEquals(listOf("frio"), body.senses[0].antonyms)
        assertEquals(listOf("calor"), body.senses[0].related)
    }

    @Test
    fun `el fixture trae un caso con relacionadas`() {
        // Sin esto el espejo Python-Kotlin del tag R no queda verificado contra bytes reales:
        // los dos tests de arriba solo prueban el parser de este lado.
        val fixture = loadFixture()
        val bodies = fixture.cases.map { PayloadCodec.decode(it.compressed, fixture.dictionary) }
        assertTrue(
            bodies.flatMap { it.senses }.any { it.related.isNotEmpty() },
            "el fixture no trae ninguna acepcion con relacionadas",
        )
    }

    @Test
    fun `el fixture trae un caso con antonimos, y no los confunde con sinonimos`() {
        // El fixture es lo unico que comprueba que java.util.zip descomprima exactamente lo que
        // zlib comprimio. Un caso con los dos tags CRUZADOS es lo que detecta un parser que
        // confunde 'Y' con 'A' -- y confundirlos no da un resultado raro, da el inverso.
        val fixture = loadFixture()
        val bodies = fixture.cases.map { PayloadCodec.decode(it.compressed, fixture.dictionary) }
        val conAmbos = bodies.flatMap { it.senses }
            .filter { it.synonyms.isNotEmpty() && it.antonyms.isNotEmpty() }
        assertTrue(conAmbos.isNotEmpty(), "el fixture no trae ninguna acepcion con los dos tags")
        assertTrue(
            conAmbos.none { sense -> sense.synonyms.any { it in sense.antonyms } },
            "hay un termino que figura como sinonimo Y antonimo de la misma acepcion",
        )
    }

    @Test
    fun `el fixture trae un caso con sinonimos`() {
        // Sin esto, el espejo Python-Kotlin del tag Y no estaria verificado contra bytes
        // reales: los dos tests de arriba solo prueban el parser de este lado.
        val fixture = loadFixture()
        val bodies = fixture.cases.map { PayloadCodec.decode(it.compressed, fixture.dictionary) }
        assertTrue(
            bodies.any { body -> body.senses.any { it.synonyms.isNotEmpty() } },
            "el fixture no ejercita el tag de sinonimos; regenerar con gen_payload_fixture.py",
        )
    }

    @Test
    fun `el fixture incluye acentos y no ASCII`() {
        // Si el fixture perdiera los casos no ASCII, un error de charset pasaria desapercibido.
        val fixture = loadFixture()
        val bodies = fixture.cases.map { PayloadCodec.decode(it.compressed, fixture.dictionary) }
        assertTrue(
            bodies.any { body -> body.senses.any { it.gloss.contains("niño") } },
            "el fixture perdio los casos con caracteres no ASCII",
        )
        assertTrue(
            bodies.any { body -> body.senses.any { it.gloss.contains("日本語") } },
            "el fixture perdio los casos fuera de Latin-1",
        )
    }

    @Test
    fun `el saneado del builder deja el texto sin tabs ni saltos`() {
        // El formato es delimitado por tabs y saltos de linea, asi que un tab que llegue desde
        // la fuente corromperia la entrada. Se sanea al construir, no al leer.
        val fixture = loadFixture()
        for (case in fixture.cases) {
            val body = PayloadCodec.decode(case.compressed, fixture.dictionary)
            for (sense in body.senses) {
                assertTrue(
                    !sense.gloss.contains('\t') && !sense.gloss.contains('\n'),
                    "la glosa de '${case.description}' trae delimitadores: '${sense.gloss}'",
                )
            }
        }
    }

    @Test
    fun `el caso tipico parsea a la estructura esperada`() {
        val fixture = loadFixture()
        val case = fixture.cases.first { it.description.startsWith("entrada tipica") }
        val body = PayloadCodec.decode(case.compressed, fixture.dictionary)

        assertEquals("verb", body.partOfSpeech)
        assertEquals(2, body.senses.size)
        assertEquals("moverse rapidamente de un lugar a otro", body.senses[0].gloss)
        assertEquals(listOf("corrio hasta la esquina"), body.senses[0].examples)
        assertEquals(listOf("to run"), body.senses[0].translations)
        assertEquals(emptyList(), body.senses[1].examples)
        assertEquals(listOf("to pass", "to elapse"), body.senses[1].translations)
    }

    @Test
    fun `un diccionario equivocado no reproduce el contenido`() {
        // deflate NO valida el diccionario precargado. Segun el largo del equivocado, esto
        // lanza (referencias fuera de la ventana) o devuelve texto corrupto sin decir nada.
        // Las dos salidas son aceptables aca; lo que no seria aceptable es que diera el texto
        // correcto, porque entonces el chequeo por hash no tendria sentido.
        val fixture = loadFixture()
        val case = fixture.cases.first { it.description.startsWith("entrada tipica") }
        val wrong = "un diccionario que no corresponde en nada".repeat(3).toByteArray()

        val result = runCatching { PayloadCodec.render(PayloadCodec.decode(case.compressed, wrong)) }
        assertTrue(
            result.isFailure || result.getOrNull() != case.expected,
            "con otro diccionario no deberia salir el texto correcto",
        )
    }

    @Test
    fun `el hash del diccionario coincide con el que calculo Python`() {
        // Es la unica defensa contra el caso de arriba: la app compara este hash contra
        // meta.payload_dict_sha256 al abrir el pack. Si los dos lados lo calcularan distinto,
        // TODO pack valido seria rechazado.
        val fixture = loadFixture()
        assertEquals(fixture.dictionaryDigest, PayloadCodec.dictionaryDigest(fixture.dictionary))
    }

    @Test
    fun `el hash del diccionario detecta cualquier cambio`() {
        val fixture = loadFixture()
        val digest = PayloadCodec.dictionaryDigest(fixture.dictionary)
        assertNotEquals(digest, PayloadCodec.dictionaryDigest(fixture.dictionary.dropLast(1).toByteArray()))
        assertNotEquals(digest, PayloadCodec.dictionaryDigest(fixture.dictionary + "x".toByteArray()))
        assertNotEquals(digest, PayloadCodec.dictionaryDigest(ByteArray(0)))
    }

    @Test
    fun `round-trip propio de Kotlin`() {
        val fixture = loadFixture()
        val body = PayloadCodec.Body(
            partOfSpeech = "verb",
            senses = listOf(
                Sense("primera acepcion", listOf("un ejemplo"), listOf("first")),
                Sense("segunda", emptyList(), listOf("second", "other")),
            ),
        )
        val encoded = PayloadCodec.encode(body, fixture.dictionary)
        assertEquals(body, PayloadCodec.decode(encoded, fixture.dictionary))
    }

    @Test
    fun `los tags desconocidos se ignoran`() {
        // Compatibilidad hacia adelante: un builder mas nuevo puede agregar campos y una app
        // vieja debe seguir mostrando la entrada, no descartarla.
        val parsed = PayloadCodec.parse(
            "P\tverb\nS\tuna acepcion\nZ\tcampo del futuro\nT\ta translation\n"
        )
        assertEquals("verb", parsed.partOfSpeech)
        assertEquals(1, parsed.senses.size)
        assertEquals(listOf("a translation"), parsed.senses[0].translations)
    }

    @Test
    fun `lineas corruptas no tiran la entrada completa`() {
        val parsed = PayloadCodec.parse("S\tbuena\nlinea sin tab\nS\t\nT\tvale\n")
        assertEquals(1, parsed.senses.size, "se perdio o duplico una acepcion")
        assertEquals("buena", parsed.senses[0].gloss)
        assertEquals(listOf("vale"), parsed.senses[0].translations)
    }

    @Test
    fun `el codigo de acepcion coincide con el vector de Python`() {
        // ⚠️ **Este es el guardrail del espejo, y es el unico mecanismo que detecta que
        // `payload.sense_code` y `PayloadCodec.senseCode` se separaron.** Si calculan distinto,
        // los enlaces entre packs apuntan a la nada **sin excepcion y sin log**, que es el modo
        // de falla central de este repo -- la misma forma que los vectores de normalizacion.
        //
        // El mismo numero esta fijado en `test_payload.py`. Cambiarlo es un acto deliberado:
        // invalida los enlaces de todos los packs ya construidos.
        assertEquals("8ec316909e48", PayloadCodec.senseCode(1L, "casa"))
        // Que "Casa." de el MISMO numero es el plegado funcionando.
        assertEquals("8ec316909e48", PayloadCodec.senseCode(1L, "Casa."))
    }

    @Test
    fun `el codigo NO pasa por norm, asi que NORM_VERSION no lo puede romper`() {
        // El precedente de D-055. Si pasara por `norm()`, un bump de `NORM_VERSION` --que D-005
        // permite en cualquier momento-- cambiaria todos los codigos.
        // ⚠️ El caso tiene que ser uno donde el PLEGADO y `norm()` difieran, y eso son los
        // acentos: `foldGloss` los conserva y `norm()` los saca.
        val gloss = "El público"
        assertNotEquals(
            PayloadCodec.senseCode(7L, gloss),
            PayloadCodec.senseCode(7L, TextNormalizer.norm(gloss)),
            "si coinciden es que el codigo esta pasando por norm()",
        )
    }

    @Test
    fun `la misma glosa compuesta o descompuesta da el mismo codigo`() {
        // Dos fuentes pueden entregar "a" con tilde precompuesta o descompuesta.
        assertEquals(
            PayloadCodec.senseCode(7L, "Secci\u00f3n"),
            PayloadCodec.senseCode(7L, "Seccio\u0301n"),
        )
    }

    @Test
    fun `el espacio duro NO se colapsa, igual que en Python`() {
        // ⚠️ El guardrail de la trampa entre lenguajes: en Python `\s` sobre `str` es Unicode y
        // en Java es ASCII. Si algun dia alguien "arregla" uno de los dos usando `\s`, los dos
        // codigos de la misma acepcion se separan **sin error y sin log**. Se fija que NINGUNO
        // lo colapse.
        assertNotEquals(
            PayloadCodec.senseCode(1L, "una\u00a0casa"),
            PayloadCodec.senseCode(1L, "una casa"),
        )
    }

    @Test
    fun `el plegado no saca acentos`() {
        assertNotEquals(PayloadCodec.senseCode(7L, "el publico"), PayloadCodec.senseCode(7L, "el público"))
    }

    @Test
    fun `el plegado coincide con Python caso por caso`() {
        // ⚠️ **El guardrail del tercer contrato cruzado.** `payload.fold_gloss` y `foldGloss`
        // leen la MISMA tabla fijada, pero eso solo vale si tambien la aplican igual. Estos
        // pares se imprimieron desde Python y se pegaron aca: si los dos lados se separan, los
        // `sense_code` divergen y los enlaces entre packs apuntan a la nada, sin error ni log.
        val esperado = listOf(
            "Casa." to "casa.",
            "ß-ENDORFINA" to "ss-endorfina",
            "ΜΆΪΟΣ" to "μάϊοσ",
            "Μάϊος" to "μάϊοσ",
            "µm" to "μm",
            "ſaber" to "saber",
            "PÚBLICO" to "público",
        )
        for ((entrada, salida) in esperado) {
            assertEquals(salida, CaseFolding.fold(entrada), "plegado distinto para $entrada")
        }
    }
}
