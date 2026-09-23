package cl.fadiaz.dictionary.core

/**
 * Turns a prefix search into a half-open range [prefix, bound) over a column with
 * collation BINARY.
 *
 * This is used instead of `LIKE 'x%'` on purpose: LIKE is only optimized into a range scan when
 * `case_sensitive_like` holds the right value, which depends on the connection, and in doubt
 * SQLite full-scans the table. With an explicit range the query plan is always a range scan over
 * the index, with no pragma to depend on.
 *
 * Pure Kotlin: see PlatformJvm.kt for why that matters.
 */
object PrefixRange {

    private const val MAX_CODE_POINT = 0x10FFFF
    private const val SURROGATE_FIRST = 0xD800
    private const val SURROGATE_LAST = 0xDFFF

    /**
     * The prefix's exclusive upper bound: the smallest lexicographic successor not starting with
     * [prefix].
     *
     * It returns null when no bound exists (an empty prefix, or one made only of the maximum
     * code point). In that case the query must omit the bound condition rather than pass null,
     * which would make the range match nothing.
     */
    fun upperBound(prefix: String): String? {
        if (prefix.isEmpty()) return null

        val codePoints = toCodePoints(prefix)
        var end = codePoints.size
        while (end > 0) {
            var next = codePoints[end - 1] + 1
            // The surrogate range is not made of valid code points on its own.
            if (next in SURROGATE_FIRST..SURROGATE_LAST) next = SURROGATE_LAST + 1
            if (next <= MAX_CODE_POINT) {
                val out = StringBuilder()
                for (index in 0 until end - 1) CodePoint.appendTo(out, codePoints[index])
                CodePoint.appendTo(out, next)
                return out.toString()
            }
            // El ultimo code point ya era el maximo: se descarta y se incrementa el anterior.
            end--
        }
        return null
    }

    /** Equivalent of String.codePoints().toArray(), which is a JVM API. */
    private fun toCodePoints(text: String): IntArray {
        val out = IntArray(text.length)
        var count = 0
        var index = 0
        while (index < text.length) {
            val first = text[index]
            if (first.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) {
                out[count++] = CodePoint.fromSurrogatePair(first, text[index + 1])
                index += 2
            } else {
                out[count++] = first.code
                index += 1
            }
        }
        return out.copyOf(count)
    }
}
