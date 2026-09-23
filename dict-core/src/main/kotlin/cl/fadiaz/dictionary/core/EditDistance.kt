package cl.fadiaz.dictionary.core

import kotlin.math.abs
import kotlin.math.min

/**
 * Edit distance, used to reorder candidates from the search's fuzzy rung.
 */
object EditDistance {

    /**
     * Damerau-Levenshtein restringida (optimal string alignment): inserciones, borrados,
     * sustituciones y transposiciones de caracteres adyacentes. La transposicion importa
     * porque en un teclado de reloj "hte" por "the" es de los errores mas comunes.
     *
     * It cuts as soon as the distance passes [maxDistance] and returns [maxDistance] + 1. Since
     * this runs over a couple of hundred candidates per keystroke, cutting early is what keeps
     * the fuzzy rung inside the latency budget.
     */
    fun damerauLevenshtein(a: String, b: String, maxDistance: Int): Int {
        val overflow = maxDistance + 1
        if (abs(a.length - b.length) > maxDistance) return overflow
        if (a == b) return 0
        if (a.isEmpty()) return if (b.length <= maxDistance) b.length else overflow
        if (b.isEmpty()) return if (a.length <= maxDistance) a.length else overflow

        var twoRowsBack = IntArray(b.length + 1)
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)

        for (i in 1..a.length) {
            current[0] = i
            var rowBest = current[0]
            for (j in 1..b.length) {
                val substitution = if (a[i - 1] == b[j - 1]) 0 else 1
                var cost = min(
                    min(current[j - 1] + 1, previous[j] + 1),
                    previous[j - 1] + substitution,
                )
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    cost = min(cost, twoRowsBack[j - 2] + 1)
                }
                current[j] = cost
                if (cost < rowBest) rowBest = cost
            }
            // Every later row is >= this one's minimum, so once a whole row has passed the
            // threshold there is no point going on.
            if (rowBest > maxDistance) return overflow

            val recycled = twoRowsBack
            twoRowsBack = previous
            previous = current
            current = recycled
        }

        val distance = previous[b.length]
        return if (distance > maxDistance) overflow else distance
    }
}
