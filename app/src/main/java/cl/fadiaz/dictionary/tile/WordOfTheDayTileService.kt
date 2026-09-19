package cl.fadiaz.dictionary.tile

import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.ResourceBuilders.Resources
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material3.materialScope
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import cl.fadiaz.dictionary.R
import cl.fadiaz.dictionary.data.PackStore
import cl.fadiaz.dictionary.presentation.posInSpanish
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * La palabra del dia, la misma que muestra el inicio de la app (D-097).
 *
 * POR QUE UN TIMELINE Y NO UN FRESHNESS INTERVAL
 *
 * `setFreshnessIntervalMillis` es, verbatim del javadoc, *"how many milliseconds of **elapsed
 * time (not wall clock time)**"*, ademas de *"inexact"* y con throttling. Pedirle 24 h no
 * significa "a medianoche": la palabra iria corriendose unos minutos cada dia, y una "palabra del
 * dia" que cambia a las 15:47 dejo de ser del dia.
 *
 * `TimeInterval`, en cambio, es *"in milliseconds since the Unix epoch"* -- reloj de pared. Asi
 * que en **una sola** respuesta se emiten las ventanas de toda la semana, una por dia, y el
 * renderer cambia de palabra solo al cruzar la medianoche local. Cero despertares del proceso.
 *
 * La semana la deja escrita la app, porque este servicio **no puede abrir el pack**: elegir una
 * palabra son 32 lecturas y `onTileRequest` corre en el hilo principal.
 */
class WordOfTheDayTileService : TileService() {

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): ListenableFuture<TileBuilders.Tile> {
        val (since, words) = PackStore.weekWords(this)
        val timeline = TimelineBuilders.Timeline.Builder()

        val days = windows(since, words.size)
        if (days.isEmpty()) {
            timeline.addTimelineEntry(
                TimelineBuilders.TimelineEntry.Builder()
                    .setLayout(wrap(requestParams, null))
                    .build(),
            )
        } else {
            for ((index, window) in days) {
                timeline.addTimelineEntry(
                    TimelineBuilders.TimelineEntry.Builder()
                        .setValidity(window)
                        .setLayout(wrap(requestParams, words[index]))
                        .build(),
                )
            }
        }

        return Futures.immediateFuture(
            TileBuilders.Tile.Builder()
                .setResourcesVersion(RESOURCES)
                // Backstop y no el mecanismo: si el usuario no abre la app, al terminar la semana
                // el tile vuelve a pedirse y muestra su estado vacio en vez de una palabra vieja.
                .setFreshnessIntervalMillis(ONE_WEEK_MS)
                .setTileTimeline(timeline.build())
                .build(),
        )
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<Resources> =
        Futures.immediateFuture(Resources.Builder().setVersion(RESOURCES).build())

    private fun wrap(
        requestParams: RequestBuilders.TileRequest,
        visit: cl.fadiaz.dictionary.data.Visit?,
    ): androidx.wear.protolayout.LayoutElementBuilders.Layout {
        val item: LayoutElement = materialScope(this, requestParams.deviceConfiguration) {
            if (visit == null) {
                emptyTile(this@WordOfTheDayTileService, getString(R.string.tile_word_empty))
            } else {
                wordCard(
                    this@WordOfTheDayTileService,
                    visit,
                    visit.partOfSpeech?.let(::posInSpanish),
                )
            }
        }
        return androidx.wear.protolayout.LayoutElementBuilders.Layout.fromLayoutElement(item)
    }

    /**
     * Una ventana de reloj de pared por dia cacheado, de medianoche local a medianoche local.
     *
     * La zona horaria se consulta aca y no en [ContenidoDeTiles] a proposito: es estado del
     * sistema, igual que la fecha, y lo que se testea en la JVM tiene que recibirlo hecho.
     */
    private fun windows(
        since: String?,
        howMany: Int,
    ): List<Pair<Int, TimelineBuilders.TimeInterval>> {
        if (since == null || howMany <= 0) return emptyList()
        val start = try {
            LocalDate.parse(since)
        } catch (e: DateTimeParseException) {
            return emptyList()
        }
        val zone = ZoneId.systemDefault()
        return (0 until howMany).map { day ->
            val startsAt = start.plusDays(day.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
            val endsAt = start.plusDays(day + 1L).atStartOfDay(zone).toInstant().toEpochMilli()
            day to TimelineBuilders.TimeInterval.Builder()
                .setStartMillis(startsAt)
                .setEndMillis(endsAt)
                .build()
        }
    }
}

private const val ONE_WEEK_MS = 7L * 24 * 60 * 60 * 1000
