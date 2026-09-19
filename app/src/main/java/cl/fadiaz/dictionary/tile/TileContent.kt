package cl.fadiaz.dictionary.tile

import cl.fadiaz.dictionary.data.Visit
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/**
 * What a tile has to draw, already decided.
 *
 * It exists so the decision lives without Android and enters the gate (D-072): each `TileService`
 * is left as an adapter that translates this into protolayout, and everything that can go wrong
 * --an expired cache, a drifted clock, corrupt text-- is tested on the JVM in milliseconds.
 *
 * It reuses [Visit] for both surfaces, just as the saved words already do (D-102): they are the
 * same shape of data --`packId`, `entryId`, headword and part of speech-- and a second type
 * would be a second format that can diverge from the one already on disk.
 */
internal sealed interface TileContent {

    /** The most recently opened entries, in the order the ViewModel left them. */
    data class ListRows(val visits: List<Visit>) : TileContent

    /** Today's word. */
    data class Word(val visit: Visit) : TileContent

    /**
     * There is nothing to show, and the tile has to say so.
     *
     * **Never draw a blank tile**: in the carousel it looks broken, not empty. The adapter puts
     * an invitation to open the app, which is also the only way for it to stop being empty.
     */
    data object Empty : TileContent
}

internal object TileContents {

    /**
     * How many rows fit.
     *
     * It matches the history cap (D-085) and the screen budget (D-073), but it is a parameter and
     * not a buried constant: the project's watch measures **234 dp and not 192**, and once that
     * is confirmed from inside the app a fourth row may fit.
     */
    const val MAX_ROWS: Int = 3

    /**
     * How many days of word of the day are precomputed.
     *
     * Seven and not one because the cache is written by the app, and **the tile cannot refill
     * it**: it does not open the pack. With a single day, the tile goes empty the moment midnight
     * passes without anyone opening the app. With seven it holds for a week, and they are also
     * the seven `Timeline` windows that let the renderer change the word on its own, without a
     * single wakeup.
     */
    const val CACHED_DAYS: Int = 7

    /** The most recently opened entries. No reordering: the ViewModel already did move-to-front. */
    fun history(visits: List<Visit>, max: Int = MAX_ROWS): TileContent {
        val visibleOnes = visits.take(max)
        return if (visibleOnes.isEmpty()) TileContent.Empty else TileContent.ListRows(visibleOnes)
    }

    /**
     * The word that belongs to [today], out of the ones the app cached starting at [since].
     *
     * **Out of range it returns [TileContent.Empty] and that is the point**, not a defensive
     * guard: if the app is not opened for more than [CACHED_DAYS] days the cache runs out, and
     * going on showing the last one would be a wrong "word of the day" every single day, on a
     * surface nobody opens on purpose and therefore **where nobody would report it**.
     *
     * The dates are the same ISO strings the app uses (`LocalDate.toString()`), and one that
     * cannot be read is discarded instead of throwing: this is read from SharedPreferences, which
     * is a contract with the disk, and it runs on the main thread.
     */
    fun wordOfTheDay(since: String?, words: List<Visit>, today: String?): TileContent {
        if (words.isEmpty()) return TileContent.Empty
        val start = date(since) ?: return TileContent.Empty
        val current = date(today) ?: return TileContent.Empty

        val index = ChronoUnit.DAYS.between(start, current)
        if (index < 0 || index >= words.size) return TileContent.Empty
        return TileContent.Word(words[index.toInt()])
    }

    /**
     * The date [days] further on, in the same ISO format.
     *
     * It is the inverse of [wordOfTheDay]: the app walks dates forward to fill the cache, the
     * tile computes the difference to read it. Living in the same file is what makes it obvious
     * that both have to use the same calendar arithmetic.
     */
    fun plusDays(since: String?, days: Int): String? =
        date(since)?.plusDays(days.toLong())?.toString()

    private fun date(text: String?): LocalDate? {
        if (text.isNullOrBlank()) return null
        return try {
            LocalDate.parse(text.trim())
        } catch (e: DateTimeParseException) {
            null
        }
    }
}
