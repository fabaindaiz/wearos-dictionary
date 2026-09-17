package cl.fadiaz.dictionary.core

/**
 * Traduce una busqueda por prefijo a un rango semiabierto [prefijo, cota) sobre una columna con
 * collation BINARY.
 *
 * Se usa esto en vez de `LIKE 'x%'` a proposito: LIKE solo se optimiza a un escaneo de rango si
 * `case_sensitive_like` esta en el valor correcto, cosa que depende de la conexion, y ante la
 * duda SQLite hace un full scan de la tabla. Con el rango explicito el plan de consulta es
 * siempre un range scan sobre el indice, sin depender de pragmas.
 *
 * Kotlin puro: ver PlatformJvm.kt para por que importa.
 */
object PrefixRange {

    private const val MAX_CODE_POINT = 0x10FFFF
    private const val SURROGATE_FIRST = 0xD800
    private const val SURROGATE_LAST = 0xDFFF

    /**
     * Cota superior exclusiva del prefijo: el sucesor lexicografico mas chico que no empieza con
     * [prefix].
     *
     * Devuelve null cuando no existe cota (prefijo vacio, o compuesto solo por el code point
     * maximo). En ese caso la consulta debe omitir la condicion de cota en vez de pasar null,
     * que haria que el rango no matchee nada.
     */
    fun upperBound(prefix: String): String? {
        if (prefix.isEmpty()) return null

        val codePoints = toCodePoints(prefix)
        var end = codePoints.size
        while (end > 0) {
            var next = codePoints[end - 1] + 1
            // El rango de surrogates no son code points validos por si mismos.
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

    /** Equivalente de String.codePoints().toArray(), que es de la JVM. */
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
