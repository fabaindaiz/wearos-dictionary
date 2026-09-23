package cl.fadiaz.dictionary.core

/**
 * A word inside a text: where it sits in the original, and which key it is searched by.
 *
 * [start] and [end] are indices into the **original** string, not the normalized one: they are
 * what the interface needs to paint and make that exact span tappable. [norm] is the key
 * `entry.norm` is queried with, which is why it comes from [TextNormalizer.norm] and not from a
 * slice of its own.
 */
data class WordSpan(val start: Int, val end: Int, val norm: String)

/**
 * Splits a text --a gloss-- into the words that can be looked up in the dictionary.
 *
 * WHAT A WORD IS, AND WHY THIS FILE DOES NOT DECIDE IT
 *
 * There is no punctuation table here, no regex, and no query to [UnicodeRepertoire]. A code point
 * opens a word if and only if `norm()` keeps it. That is deliberate: if this file had its own idea
 * of what a letter is, it would be **a second source of truth** about the same question the
 * central invariant already answers, and the two would drift apart in silence -- exactly the
 * failure D-003 and D-005 exist to prevent.
 *
 * The practical effect: "self-made" gives two words because `norm` turns the hyphen into a
 * separator, and "Ärztin" gives one with the key "arztin", without this file knowing anything
 * about hyphens or about
 * diacriticos.
 */
object GlossTokenizer {

    /**
     * The words in [text], in order and without overlapping.
     *
     * It returns only the ones with a non-empty key: a span `norm` reduces to nothing cannot be
     * searched, so there is no sense painting it as tappable either.
     */
    fun tokenize(text: String): List<WordSpan> {
        val spans = mutableListOf<WordSpan>()
        // Memo per code point: a gloss repeats the same letters dozens of times and each
        // `norm` hace NFD y recorre el repertorio entero.
        val abrePalabra = HashMap<Int, Boolean>()

        var inicio = -1
        var indice = 0
        while (indice < text.length) {
            // Hand-written code point walk, same as in TextNormalizer: `Character.` and
            // `codePoints()` are JVM APIs and this module does not use them (D-017).
            val primero = text[indice]
            val esPar = primero.isHighSurrogate() &&
                indice + 1 < text.length &&
                text[indice + 1].isLowSurrogate()
            val codePoint = if (esPar) {
                CodePoint.fromSurrogatePair(primero, text[indice + 1])
            } else {
                primero.code
            }
            val ancho = if (esPar) 2 else 1

            val cuenta = abrePalabra.getOrPut(codePoint) {
                val solo = StringBuilder(2).also { it.appendUtf16(codePoint) }.toString()
                TextNormalizer.norm(solo).isNotEmpty()
            }
            if (cuenta) {
                if (inicio < 0) inicio = indice
            } else if (inicio >= 0) {
                agregar(spans, text, inicio, indice)
                inicio = -1
            }
            indice += ancho
        }
        if (inicio >= 0) agregar(spans, text, inicio, text.length)
        return spans
    }

    private fun agregar(spans: MutableList<WordSpan>, text: String, start: Int, end: Int) {
        val clave = TextNormalizer.norm(text.substring(start, end))
        if (clave.isNotEmpty()) spans.add(WordSpan(start, end, clave))
    }
}
