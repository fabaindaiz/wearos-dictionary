package cl.fadiaz.dictionary.tile

import cl.fadiaz.dictionary.data.Visit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What each tile draws, decided without Android and therefore inside the gate.
 *
 * A tile fails differently from a screen: nobody opens it on purpose, so **a tile showing
 * something stale never gets reported**. Hence half of these tests are about the expired cache,
 * the drifted clock and corrupt text --the three cases where the right answer is to show
 * nothing-- and not about the happy path.
 */
class TileContentTest {

    private fun visit(headword: String, id: Long = 1, pack: String = "es-def-wikc") =
        Visit(packId = pack, entryId = id, headword = headword, partOfSpeech = "noun")

    // ------------------------------------------------------------------- history

    @Test
    fun withNoHistoryThereIsNothingToShow() {
        assertEquals(TileContent.Empty, TileContents.history(emptyList()))
    }

    @Test
    fun withASingleVisitThatOneIsShown() {
        val una = listOf(visit("perro"))
        assertEquals(TileContent.ListRows(una), TileContents.history(una))
    }

    @Test
    fun noMoreRowsAreShownThanFit() {
        val muchas = (1L..6L).map { visit("lema$it", it) }
        val content = TileContents.history(muchas, max = 3)
        assertEquals(TileContent.ListRows(muchas.take(3)), content)
    }

    @Test
    fun theHistoryOrderIsKept() {
        // The ViewModel already applied move-to-front when saving: the tile reorders nothing,
        // and if it did the top row would stop being the last word opened.
        val order = listOf(visit("tres", 3), visit("dos", 2), visit("uno", 1))
        assertEquals(TileContent.ListRows(order), TileContents.history(order))
    }

    // ------------------------------------------------------------ word of the day

    private val week = listOf(
        visit("lunes", 1), visit("martes", 2), visit("miercoles", 3),
        visit("jueves", 4), visit("viernes", 5), visit("sabado", 6), visit("domingo", 7),
    )

    /** Two languages: the cache is stored day-major, `dia0-es, dia0-en, dia1-es, ...`. */
    private val semanaDeDos = (1L..7L).flatMap { dia ->
        listOf(visit("es$dia", dia, "es-full"), visit("en$dia", dia + 100, "en-core"))
    }

    @Test
    fun twoLanguagesGiveTwoWordsForTheSameDay() {
        // ⚠️ **The home already showed one word of the day per language (D-151) and the tile
        // showed only the active pack's** -- the same rule holding on one surface and not on its
        // parallel, which is the failure this repo has hit four times. Asked for: up to two when
        // more than one definition language is installed.
        val hoy = TileContents.wordOfTheDay("2026-09-19", semanaDeDos, "2026-09-19")
        assertEquals(TileContent.Word(listOf(visit("es1", 1, "es-full"), visit("en1", 101, "en-core"))), hoy)

        val tercerDia = TileContents.wordOfTheDay("2026-09-19", semanaDeDos, "2026-09-21")
        assertEquals(TileContent.Word(listOf(visit("es3", 3, "es-full"), visit("en3", 103, "en-core"))), tercerDia)
    }

    @Test
    fun aCacheWrittenByAnOlderBuildStillReads() {
        // ⚠️ **This is why the count is DERIVED from the length and not stored beside it.** A
        // build that cached one word per day wrote exactly `week`, and after an update the tile
        // has to keep working from it: a format version or a migration for seven rows that
        // rebuild themselves the next time the app opens would be machinery with no purpose. A
        // second field could also disagree with the list next to it.
        val hoy = TileContents.wordOfTheDay("2026-09-19", week, "2026-09-21")
        assertEquals(TileContent.Word(listOf(week[2])), hoy)
    }

    @Test
    fun aCacheThatIsNotAWholeNumberOfDaysIsEmptyAndNotGuessedAt() {
        // ⚠️ **This test was written expecting the opposite and the code was changed instead.**
        // The first version salvaged what it could; with 13 entries the derived count says one
        // per day and day 6 hands back day 3's word. That is a WRONG word of the day, every day,
        // on the surface nobody opens on purpose -- so nobody would report it. Deriving the count
        // only works while the list is rectangular, and outside that the honest answer is that
        // the cache cannot be read.
        //
        // The writer cannot produce this shape: it builds the week in memory and saves once. The
        // guard is for the disk, which is a contract with something outside this process.
        val cortada = semanaDeDos.dropLast(1)
        assertEquals(TileContent.Empty, TileContents.wordOfTheDay("2026-09-19", cortada, "2026-09-25"))
        assertEquals(TileContent.Empty, TileContents.wordOfTheDay("2026-09-19", cortada, "2026-09-19"))
    }

    // --------------------------------------------------------- how many rows fit

    @Test
    fun aTileFitsFewerRowsThanTheHomeDoes() {
        // ⚠️ **Measured, not assumed** (2026-09-28, `sw234dp` at 340 dpi, read off the rendered
        // tile with `uiautomator`): the main slot is 132 dp of 234, because a tile has a title
        // slot AND an edge button that the home does not. The home's `rowsThatFit` discounts 60
        // dp and said three; the third row came out **68 px instead of 102**, clipped, and 32 dp
        // against the 48 dp a touch target needs.
        assertEquals(2, TileContents.rowsThatFitInATile(234))
        // And it goes DOWN on a narrower watch instead of pretending, which is what protects the
        // generic one.
        assertEquals(1, TileContents.rowsThatFitInATile(192))
        // Never zero: an empty tile reads as broken, not as empty.
        assertEquals(1, TileContents.rowsThatFitInATile(100))
    }

    @Test
    fun theFirstCachedDayIsTheFirstWord() {
        val content = TileContents.wordOfTheDay("2026-09-19", week, "2026-09-19")
        assertEquals(TileContent.Word(listOf(week[0])), content)
    }

    @Test
    fun eachDayShiftsByOnePosition() {
        val content = TileContents.wordOfTheDay("2026-09-19", week, "2026-09-21")
        assertEquals(TileContent.Word(listOf(week[2])), content)
    }

    @Test
    fun crossingAMonthBoundaryDoesNotMisalign() {
        // The index is a difference between dates, not a subtraction of days of the month.
        val content = TileContents.wordOfTheDay("2026-09-29", week, "2026-10-02")
        assertEquals(TileContent.Word(listOf(week[3])), content)
    }

    @Test
    fun anExpiredCacheShowsNoStaleWord() {
        // THIS IS THE TEST THAT PAYS FOR THE FILE. If the app was not opened for over a week,
        // the cache runs out; showing the last word it had would be a wrong "word of the day",
        // every single day, with nothing to warn anyone.
        val content = TileContents.wordOfTheDay("2026-09-19", week, "2026-09-30")
        assertEquals(TileContent.Empty, content)
    }

    @Test
    fun ifTheWatchClockGoesBackThereIsNoWord() {
        val content = TileContents.wordOfTheDay("2026-09-19", week, "2026-09-18")
        assertEquals(TileContent.Empty, content)
    }

    @Test
    fun withNoCacheThereIsNoWord() {
        assertEquals(TileContent.Empty, TileContents.wordOfTheDay(null, emptyList(), "2026-09-19"))
        assertEquals(TileContent.Empty, TileContents.wordOfTheDay("2026-09-19", emptyList(), "2026-09-19"))
    }

    @Test
    fun withNoTodaysDateThereIsNoWord() {
        // Same rule as the ViewModel: with no date wired there is no word of the day, and the
        // absence is visible instead of freezing one.
        assertEquals(TileContent.Empty, TileContents.wordOfTheDay("2026-09-19", week, null))
    }

    @Test
    fun whatTheAppPrecomputesIsExactlyWhatTheTileReads() {
        // The property that matters, and the reason both halves live in the same file: the app
        // fills the cache by adding days and the tile reads it by subtracting them. If the two
        // arithmetics drifted apart, the tile would show the word of the wrong day --off by
        // one-- which is exactly the error nobody notices.
        val today = "2026-09-19"
        for (day in week.indices) {
            val eseDia = TileContents.plusDays(today, day)
            assertEquals(
                TileContent.Word(listOf(week[day])),
                TileContents.wordOfTheDay(today, week, eseDia),
                "el dia $day no coincide",
            )
        }
    }

    @Test
    fun plusDaysCrossesMonthsAndYears() {
        assertEquals("2026-10-01", TileContents.plusDays("2026-09-29", 2))
        assertEquals("2027-01-01", TileContents.plusDays("2026-12-31", 1))
        assertEquals("2026-03-01", TileContents.plusDays("2026-02-28", 1))
    }

    @Test
    fun plusDaysOverGarbageGivesNull() {
        assertEquals(null, TileContents.plusDays(null, 1))
        assertEquals(null, TileContents.plusDays("ayer", 1))
    }

    @Test
    fun aCorruptDateDoesNotBringDownTheTile() {
        // It is read from SharedPreferences, which is a contract with the disk: an old version
        // or one changed byte cannot make the tile throw on the main thread.
        for (basura in listOf("", "   ", "ayer", "2026-13-45", "2026-09")) {
            assertEquals(
                TileContent.Empty,
                TileContents.wordOfTheDay(basura, week, "2026-09-19"),
                "la fecha $basura deberia descartarse, no explotar",
            )
            assertEquals(
                TileContent.Empty,
                TileContents.wordOfTheDay("2026-09-19", week, basura),
                "el hoy $basura deberia descartarse, no explotar",
            )
        }
    }
}
