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
import cl.fadiaz.dictionary.presentation.posLabel
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * The word of the day, the same one the app's home shows (D-097).
 *
 * WHY A TIMELINE AND NOT A FRESHNESS INTERVAL
 *
 * `setFreshnessIntervalMillis` is, verbatim from the javadoc, *"how many milliseconds of
 * **elapsed time (not wall clock time)**"*, as well as *"inexact"* and throttled. Asking it for
 * 24 h does not mean "at midnight": the word would drift by a few minutes every day, and a "word
 * of the day" that changes at 15:47 has stopped being of the day.
 *
 * `TimeInterval`, on the other hand, is *"in milliseconds since the Unix epoch"* -- wall clock.
 * So **a single** response emits the windows for the whole week, one per day, and the renderer
 * changes the word on its own as local midnight goes by. Zero process wakeups.
 *
 * The week is left written by the app, because this service **cannot open the pack**: picking a
 * word is 32 reads and `onTileRequest` runs on the main thread.
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
                // A backstop and not the mechanism: if the user does not open the app, once the
                // week ends the tile is requested again and shows its empty state instead of a
                // stale word.
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
                    visit.partOfSpeech?.let { posLabel(this@WordOfTheDayTileService, it) },
                )
            }
        }
        return androidx.wear.protolayout.LayoutElementBuilders.Layout.fromLayoutElement(item)
    }

    /**
     * One wall-clock window per cached day, from local midnight to local midnight.
     *
     * The time zone is read here and not in [TileContents] on purpose: it is system state, just
     * like the date, and what gets tested on the JVM has to receive it already resolved.
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
