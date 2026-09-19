package cl.fadiaz.dictionary.tile

import cl.fadiaz.dictionary.data.Visit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Que dibuja cada tile, decidido sin Android y por lo tanto dentro del gate.
 *
 * Un tile falla distinto que una pantalla: nadie lo abre a proposito, asi que **un tile que
 * muestra algo viejo no se reporta**. De ahi que la mitad de estos tests sean sobre la cache
 * vencida, el reloj corrido y el texto corrupto --los tres casos en los que la respuesta correcta
 * es no mostrar nada-- y no sobre el camino feliz.
 */
class TileContentTest {

    private fun visit(lema: String, id: Long = 1, pack: String = "es-def-wikc") =
        Visit(packId = pack, entryId = id, headword = lema, partOfSpeech = "noun")

    // ----------------------------------------------------------------- historial

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
        // El move-to-front ya lo aplico el ViewModel al guardar: el tile no reordena nada, y si
        // lo hiciera la fila de arriba dejaria de ser la ultima palabra abierta.
        val order = listOf(visit("tres", 3), visit("dos", 2), visit("uno", 1))
        assertEquals(TileContent.ListRows(order), TileContents.history(order))
    }

    // ----------------------------------------------------------- palabra del dia

    private val week = listOf(
        visit("lunes", 1), visit("martes", 2), visit("miercoles", 3),
        visit("jueves", 4), visit("viernes", 5), visit("sabado", 6), visit("domingo", 7),
    )

    @Test
    fun theFirstCachedDayIsTheFirstWord() {
        val content = TileContents.wordOfTheDay("2026-09-19", week, "2026-09-19")
        assertEquals(TileContent.Word(week[0]), content)
    }

    @Test
    fun eachDayShiftsByOnePosition() {
        val content = TileContents.wordOfTheDay("2026-09-19", week, "2026-09-21")
        assertEquals(TileContent.Word(week[2]), content)
    }

    @Test
    fun crossingAMonthBoundaryDoesNotMisalign() {
        // El indice es una diferencia de fechas, no una resta de dias del mes.
        val content = TileContents.wordOfTheDay("2026-09-29", week, "2026-10-02")
        assertEquals(TileContent.Word(week[3]), content)
    }

    @Test
    fun anExpiredCacheShowsNoStaleWord() {
        // ESTE ES EL TEST QUE PAGA EL ARCHIVO. Si la app no se abrio en mas de una semana, la
        // cache se queda corta; mostrar la ultima palabra que tenia seria una "palabra del dia"
        // equivocada, todos los dias, sin que nada avise.
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
        // Mismo criterio que el ViewModel: sin fecha cableada no hay palabra del dia, y la
        // ausencia se ve en vez de congelar una.
        assertEquals(TileContent.Empty, TileContents.wordOfTheDay("2026-09-19", week, null))
    }

    @Test
    fun whatTheAppPrecomputesIsExactlyWhatTheTileReads() {
        // La propiedad que importa, y la razon de que las dos mitades vivan en el mismo archivo:
        // la app llena la cache sumando dias y el tile la lee restandolos. Si las dos aritmeticas
        // se separaran, el tile mostraria la palabra del dia equivocado --corrida un dia-- que es
        // justo el error que nadie nota.
        val today = "2026-09-19"
        for (dia in week.indices) {
            val eseDia = TileContents.plusDays(today, dia)
            assertEquals(
                TileContent.Word(week[dia]),
                TileContents.wordOfTheDay(today, week, eseDia),
                "el dia $dia no coincide",
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
        // Se lee de SharedPreferences, que es un contrato con el disco: una version vieja o un
        // byte cambiado no pueden hacer que el tile tire una excepcion en el hilo principal.
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
