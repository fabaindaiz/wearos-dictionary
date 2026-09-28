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

    /**
     * Today's word, **one per language, up to [TileContents.MAX_WORDS]**.
     *
     * ⚠️ **A list and not a single one, and it used to be single.** The home already shows one
     * word of the day per language (D-151) and the tile showed only the active pack's: the same
     * rule holding on one surface and not on its parallel, which is the failure this repo has
     * hit four times. Asked for in those words -- up to two when more than one definition
     * language is installed.
     */
    data class Word(val visits: List<Visit>) : TileContent

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
     * How much of a tile is NOT its main slot, in dp. **Measured, not assumed.**
     *
     * The app's `rowsThatFit` discounts 60 dp of chrome and a tile has more: a title slot AND an
     * edge button, neither of which the home has. Measured on the watch's own geometry
     * (`sw234dp`, 340 dpi) on 2026-09-28, reading the rendered tile with `uiautomator`:
     *
     *     title `Recent`      y=66..99     (33 px)
     *     main slot           y=112..393   (281 px = 132 dp)
     *     edge button         y=393..491   (98 px)
     *
     * So 234 - 132 = **102 dp**, not 60. With the wrong number the tile asked for three rows and
     * **the third came out 68 px instead of 102** -- clipped, and 32 dp tall against the 48 dp
     * minimum a touch target needs. Two rows fit; the third never did.
     */
    const val TILE_CHROME_DP: Int = 102

    /** Up to how many words of the day a tile shows: one per language, and no more than two. */
    const val MAX_WORDS: Int = 2

    /**
     * How many history rows fit in a TILE, which is fewer than fit on the home.
     *
     * ⚠️ **It is not `rowsThatFit`, and importing that one was the bug.** The two surfaces have
     * different chrome: see [TILE_CHROME_DP]. Sharing the function looked like the right instinct
     * --one rule, both surfaces-- and shared the wrong half: what is common is the 48 dp row, not
     * how much room is left over for rows.
     */
    fun rowsThatFitInATile(screenWidthDp: Int, rowDp: Int = 48): Int =
        ((screenWidthDp - TILE_CHROME_DP) / rowDp).coerceIn(1, MAX_ROWS)

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

        // ⚠️ **The cache is stored day-major, and how many words a day holds is DERIVED from its
        // length.** With two languages it is `día0-es, día0-en, día1-es, día1-en, …`, and with
        // one it is exactly what it always was -- so a cache written by an older build reads
        // correctly here without a format version or a migration. That is the whole reason the
        // count is not stored: a second field could disagree with the list beside it.
        // ⚠️ **A length that is not a whole number of days is UNREADABLE, not something to
        // salvage.** Deriving the count only works while the list is rectangular: with 13 entries
        // the division says one per day and day 6 hands back day 3's word -- a wrong word of the
        // day, every day, on the surface nobody opens on purpose and therefore where nobody would
        // report it. That is the failure this whole function is written against, so it fails
        // closed instead of guessing.
        //
        // The writer cannot produce this: it builds the week in memory and saves once, so an
        // interrupted run leaves the previous cache untouched. The guard is for the disk, which
        // is a contract with something outside this process.
        if (words.size % CACHED_DAYS != 0) return TileContent.Empty
        val porDia = words.size / CACHED_DAYS
        val index = ChronoUnit.DAYS.between(start, current)
        if (index < 0 || index >= CACHED_DAYS) return TileContent.Empty
        val desde = (index * porDia).toInt()
        return TileContent.Word(words.subList(desde, desde + porDia))
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
