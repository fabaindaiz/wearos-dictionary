package cl.fadiaz.dictionary.data

/**
 * The size of a pack, so that someone can decide whether to delete it.
 *
 * Decimal MB and GB --10^6, not 2^20-- because that is the unit the watch reports its own free
 * space in, and two different figures for the same thing on the same screen confuse more than
 * any amount of precision helps.
 *
 * No `String.format`: it does not exist outside the JVM and this project already had to replace
 * it once (D-019). On top of that `format` picks its decimal separator from the Locale, and here
 * the separator has to be the same on every watch.
 */
internal fun asHumanSize(bytes: Long): String = when {
    bytes < 0 -> "—"
    // +500 and not +999: round to nearest, same as MB and GB. Two rounding rules in the same
    // function make 53,248 bytes read as 54 kB while 53.2 MB reads as 53.
    bytes < 1_000_000L -> "${(bytes + 500) / 1000} kB"
    bytes < 1_000_000_000L -> "${oneDecimal(bytes, 1_000_000L)} MB"
    else -> "${oneDecimal(bytes, 1_000_000_000L)} GB"
}

/** `bytes / unit` with one decimal, without floating point and without depending on the Locale. */
private fun oneDecimal(bytes: Long, unit: Long): String {
    val tenths = (bytes * 10 + unit / 2) / unit
    return "${tenths / 10},${tenths % 10}"
}
