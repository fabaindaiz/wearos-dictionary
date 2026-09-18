package cl.fadiaz.dictionary.data

/**
 * El tamano de un pack, para que alguien decida si lo borra.
 *
 * En MB y GB decimales --10^6, no 2^20-- porque es la unidad en la que el reloj informa su
 * espacio libre, y dos cifras distintas para la misma cosa en la misma pantalla confunden mas de
 * lo que cualquier precision aporta.
 *
 * Sin `String.format`: no existe fuera de la JVM y este proyecto ya tuvo que reemplazarlo una
 * vez (D-019). Ademas la separacion decimal de `format` depende del Locale, y aca el separador
 * tiene que ser el mismo en todos los relojes.
 */
internal fun enTamanoLegible(bytes: Long): String = when {
    bytes < 0 -> "—"
    // +500 y no +999: al mas cercano, igual que MB y GB. Dos criterios de redondeo en la
    // misma funcion hacen que 53.248 bytes se vean como 54 kB y 53,2 MB como 53.
    bytes < 1_000_000L -> "${(bytes + 500) / 1000} kB"
    bytes < 1_000_000_000L -> "${unDecimal(bytes, 1_000_000L)} MB"
    else -> "${unDecimal(bytes, 1_000_000_000L)} GB"
}

/** `bytes / unidad` con un decimal, sin coma flotante y sin depender del Locale. */
private fun unDecimal(bytes: Long, unidad: Long): String {
    val decimas = (bytes * 10 + unidad / 2) / unidad
    return "${decimas / 10},${decimas % 10}"
}
