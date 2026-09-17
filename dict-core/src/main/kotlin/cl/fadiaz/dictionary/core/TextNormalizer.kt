package cl.fadiaz.dictionary.core

/**
 * Normalizacion de texto para indexar y consultar.
 *
 * ESTE ARCHIVO TIENE UN ESPEJO: tools/packbuilder/normalize.py
 *
 * Las claves `norm` y `fuzzy` se calculan en el builder (Python) y se guardan en el pack; en el
 * reloj se calculan otra vez sobre lo que escribe el usuario. Si las dos implementaciones
 * divergen aunque sea en un caracter, la consulta deja de matchear y el sintoma es simplemente
 * "falta esa palabra" -- sin error, sin crash, sin log. Por eso:
 *
 *  - Cualquier cambio aca se replica en normalize.py en el mismo commit.
 *  - Se agregan casos a vectors/normalization-vectors.tsv, que testean AMBOS lados.
 *  - Se sube [NORM_VERSION]; los packs guardan el suyo en meta y se rechazan si no coincide.
 *
 * Se evitan deliberadamente las expresiones regulares: se usa solo reemplazo literal de
 * strings, que tiene semantica identica en Kotlin y en Python (global, izquierda a derecha, sin
 * solapamiento). Una regex "equivalente" en los dos lenguajes es justo el tipo de cosa que
 * diverge en silencio.
 *
 * LA CLASIFICACION DE CARACTERES NO VIENE DE LA PLATAFORMA
 * -------------------------------------------------------
 * Viene de [UnicodeRepertoire], que trae sus propios datos. La version anterior usaba
 * Character.getType y estaba rota: cada plataforma trae su propia version de Unicode
 * (Python 3.9 -> 13.0, Java 26 -> 16, y Android una distinta por cada release del sistema).
 * Medido sobre el repertorio completo, 14.773 code points se clasificaban distinto, y eso hacia
 * que el MISMO pack se comportara distinto en dos relojes con distinta version de Wear OS.
 *
 * Lo que si se sigue delegando en la plataforma es NFD y lowercase, y es seguro: sobre los
 * 133.730 code points del repertorio fijado, Java 26 y Python 3.9 dan CERO diferencias en
 * ambas operaciones.
 */
object TextNormalizer {

    /**
     * Sube cuando cambia el resultado de [norm] o [fuzzy]. Se compara contra meta.norm_version.
     *
     * 1 -> 2: la clasificacion de code points paso de Character.getType a [UnicodeRepertoire].
     */
    const val NORM_VERSION: Int = 2

    /**
     * Letras que NFD no descompone y que igual queremos plegar, para que "Straße" y "strasse"
     * caigan en la misma clave.
     */
    private val EXPANSIONS: Map<Int, String> = mapOf(
        'ß'.code to "ss",
        'æ'.code to "ae",
        'œ'.code to "oe",
        'ø'.code to "o",
        'đ'.code to "d",
        'ð'.code to "d",
        'þ'.code to "th",
        'ł'.code to "l",
        'ı'.code to "i",
        'ŋ'.code to "ng",
        'ſ'.code to "s",
        // Ligaduras latinas: NFD no las descompone (eso es NFKD, que traeria otros efectos
        // menos predecibles como "½" -> "1/2").
        'ﬁ'.code to "fi",
        'ﬂ'.code to "fl",
        'ﬀ'.code to "ff",
    )

    /**
     * Clave de indexado: minusculas, sin diacriticos, solo letras/digitos, espacios colapsados.
     *
     * El resultado se compara con collation BINARY, asi que no hace falta ICU en el reloj y el
     * comportamiento es identico en todos los dispositivos.
     */
    fun norm(input: String): String {
        val lowered = input.lowercase()

        val expanded = StringBuilder(lowered.length)
        forEachCodePoint(lowered) { codePoint ->
            val replacement = EXPANSIONS[codePoint]
            if (replacement != null) expanded.append(replacement) else expanded.appendUtf16(codePoint)
        }

        val decomposed = decomposeToNfd(expanded.toString())

        val kept = StringBuilder(decomposed.length)
        forEachCodePoint(decomposed) { codePoint ->
            when (UnicodeRepertoire.classify(codePoint)) {
                // Las marcas combinantes son los diacriticos que dejo NFD.
                UnicodeRepertoire.CLASS_COMBINING_MARK -> Unit
                UnicodeRepertoire.CLASS_LETTER,
                UnicodeRepertoire.CLASS_DIGIT -> kept.appendUtf16(codePoint)
                // Puntuacion, simbolos y todo lo ajeno al repertorio fijado pasan a ser
                // separadores, no desaparecen: "self-made" debe quedar "self made".
                else -> kept.append(' ')
            }
        }
        return collapseSpaces(kept.toString())
    }

    /**
     * Clave tolerante a errores: [norm] mas plegados foneticos del idioma y colapso de letras
     * repetidas. Es deliberadamente agresiva porque solo se usa como ultimo recurso, cuando la
     * busqueda por prefijo no dio resultados, y despues se reordena por distancia de edicion.
     * Falsos positivos aca son baratos; falsos negativos no.
     */
    fun fuzzy(input: String, profile: FuzzyProfile): String {
        var result = norm(input)
        for ((from, to) in profile.rules) {
            result = result.replace(from, to)
        }
        return collapseSpaces(collapseDoubledLetters(result))
    }

    /**
     * Colapsa letras repetidas adyacentes: "correr" -> "corer". Solo letras; colapsar digitos
     * convertiria "1000" en "10", que es el motivo de que el repertorio separe las dos clases.
     */
    private fun collapseDoubledLetters(text: String): String {
        val out = StringBuilder(text.length)
        var previous = -1
        forEachCodePoint(text) { codePoint ->
            val isRepeatedLetter = codePoint == previous &&
                UnicodeRepertoire.classify(codePoint) == UnicodeRepertoire.CLASS_LETTER
            if (!isRepeatedLetter) {
                out.appendUtf16(codePoint)
                previous = codePoint
            }
        }
        return out.toString()
    }

    private fun collapseSpaces(text: String): String {
        val out = StringBuilder(text.length)
        var pendingSpace = false
        for (character in text) {
            if (character == ' ') {
                if (out.isNotEmpty()) pendingSpace = true
            } else {
                if (pendingSpace) {
                    out.append(' ')
                    pendingSpace = false
                }
                out.append(character)
            }
        }
        return out.toString()
    }

    /**
     * Recorre por code point y no por Char, para no partir los pares surrogate de los planos
     * suplementarios. Escrito a mano porque `String.codePoints()` es de la JVM.
     */
    private inline fun forEachCodePoint(text: String, action: (Int) -> Unit) {
        var index = 0
        while (index < text.length) {
            val first = text[index]
            val codePoint = if (first.isHighSurrogate() && index + 1 < text.length &&
                text[index + 1].isLowSurrogate()
            ) {
                index += 2
                CodePoint.fromSurrogatePair(first, text[index - 1])
            } else {
                index += 1
                first.code
            }
            action(codePoint)
        }
    }
}

/** Utilidades de code points sin dependencias de la plataforma. */
internal object CodePoint {
    private const val SURROGATE_OFFSET = 0x10000
    private const val HIGH_SURROGATE_START = 0xD800
    private const val LOW_SURROGATE_START = 0xDC00

    fun fromSurrogatePair(high: Char, low: Char): Int =
        SURROGATE_OFFSET +
            ((high.code - HIGH_SURROGATE_START) shl 10) +
            (low.code - LOW_SURROGATE_START)

    /** Inverso: agrega un code point a un StringBuilder, con o sin par surrogate. */
    fun appendTo(builder: StringBuilder, codePoint: Int) {
        if (codePoint < SURROGATE_OFFSET) {
            builder.append(codePoint.toChar())
        } else {
            val offset = codePoint - SURROGATE_OFFSET
            builder.append((HIGH_SURROGATE_START + (offset shr 10)).toChar())
            builder.append((LOW_SURROGATE_START + (offset and 0x3FF)).toChar())
        }
    }
}

/** Reemplaza StringBuilder.appendCodePoint, que es de la JVM. */
internal fun StringBuilder.appendUtf16(codePoint: Int) {
    CodePoint.appendTo(this, codePoint)
}
