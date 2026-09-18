package cl.fadiaz.dictionary.core

/**
 * Una palabra dentro de un texto: donde esta en el original y con que clave se busca.
 *
 * [start] y [end] son indices sobre el string **original**, no sobre el normalizado: son lo que
 * necesita la interfaz para pintar y hacer tocable ese tramo exacto. [norm] es la clave con la
 * que se consulta `entry.norm`, y por eso sale de [TextNormalizer.norm] y no de un recorte propio.
 */
data class WordSpan(val start: Int, val end: Int, val norm: String)

/**
 * Parte un texto --una glosa-- en las palabras que se pueden buscar en el diccionario.
 *
 * QUE ES UNA PALABRA, Y POR QUE NO LO DECIDE ESTE ARCHIVO
 *
 * No hay aca una tabla de puntuacion, ni una regex, ni una consulta a [UnicodeRepertoire]. Un
 * code point abre palabra si y solo si `norm()` lo conserva. Eso es deliberado: si este archivo
 * tuviera su propia idea de que es una letra, seria **una segunda fuente de verdad** sobre la
 * misma pregunta que ya responde el invariante central, y las dos se separarian en silencio --
 * exactamente el fallo que D-003 y D-005 existen para impedir--.
 *
 * El efecto practico: "self-made" da dos palabras porque `norm` convierte el guion en separador,
 * y "Ärztin" da una sola con clave "arztin", sin que este archivo sepa nada de guiones ni de
 * diacriticos.
 */
object GlossTokenizer {

    /**
     * Las palabras de [text], en orden y sin solaparse.
     *
     * Devuelve solo las de clave no vacia: un tramo que `norm` deja en nada no se puede buscar,
     * asi que tampoco tiene sentido pintarlo como tocable.
     */
    fun tokenize(text: String): List<WordSpan> {
        val spans = mutableListOf<WordSpan>()
        // Memo por code point: una glosa repite las mismas letras decenas de veces y cada
        // `norm` hace NFD y recorre el repertorio entero.
        val abrePalabra = HashMap<Int, Boolean>()

        var inicio = -1
        var indice = 0
        while (indice < text.length) {
            // Recorrido por code point escrito a mano, igual que en TextNormalizer: `Character.`
            // y `codePoints()` son de la JVM y este modulo no los usa (D-017).
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
