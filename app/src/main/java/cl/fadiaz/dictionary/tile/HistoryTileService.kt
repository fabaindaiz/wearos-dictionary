package cl.fadiaz.dictionary.tile

import androidx.wear.protolayout.ResourceBuilders.Resources
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material3.materialScope
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import cl.fadiaz.dictionary.R
import cl.fadiaz.dictionary.data.DictLog
import cl.fadiaz.dictionary.data.PackStore
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * The most recently opened entries, one tap away from the watch face.
 *
 * WHY IT HAS NO SCHEDULED REFRESH
 *
 * `freshnessIntervalMillis = 0`, and the `TileBuilders` javadoc says that value means *"that
 * auto-refreshes should not be used (i.e. you will manually request updates via
 * TileService#getRequester)"*. That is: **the system never calls this tile again**. And that is
 * right, because its content does not change with the wall clock but when the user opens an
 * entry -- and at that moment the app pushes it with `getUpdater().requestUpdate(...)`.
 *
 * It is the only surface in this project that costs **zero** wakeups.
 */
class HistoryTileService : TileService() {

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): ListenableFuture<TileBuilders.Tile> {
        val desde = System.nanoTime()
        // Read from SharedPreferences and nothing else: no pack is opened. See TileRender.kt.
        //
        // How many rows, against the REAL screen. A tile does not scroll, so here the size does
        // decide -- unlike the home, where trimming would only hide (D-131).
        //
        // ⚠️ **The tile's own budget, not the home's, and that was the defect.** This used to
        // call `rowsThatFit`, which discounts 60 dp of chrome -- right for the home, which has
        // neither a title slot nor an edge button. A tile has both: measured on the watch's
        // geometry the main slot is **132 dp of 234**, so the chrome is 102. Asking for three
        // rows produced a third one **68 px tall instead of 102**, clipped, and 32 dp against the
        // 48 dp a touch target needs.
        //
        // Sharing the function looked like the right instinct --one rule, both surfaces-- and
        // shared the wrong half: what is common is the 48 dp row, not what is left for rows.
        val rows = TileContents.rowsThatFitInATile(requestParams.deviceConfiguration.screenWidthDp)
        val content = TileContents.history(PackStore.tileHistory(this), rows)
        val layout = materialScope(this, requestParams.deviceConfiguration) {
            when (content) {
                is TileContent.ListRows -> historyRows(this@HistoryTileService, content.visits)
                else -> emptyTile(
                    this@HistoryTileService,
                    getString(R.string.tile_history_empty),
                )
            }
        }
        // ⚠️ The only trace that a tile ran. `onTileRequest` is `@MainThread` with ten seconds
        // (D-106) and *"nobody opens a tile on purpose"*: if it is slow or gives up, there is no
        // screen to see it on. It also writes down the real width, which is the number missing to
        // close the 225 dp breakpoint.
        DictLog.i {
            val que = when (content) {
                is TileContent.ListRows -> "${content.visits.size} visitas"
                else -> "vacio"
            }
            "tile historial: pantalla=${requestParams.deviceConfiguration.screenWidthDp}dp " +
                "filas=$rows contenido=$que en ${(System.nanoTime() - desde) / 1_000_000} ms"
        }
        return Futures.immediateFuture(
            TileBuilders.Tile.Builder()
                .setResourcesVersion(RESOURCES)
                .setFreshnessIntervalMillis(0)
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
                .build(),
        )
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<Resources> =
        Futures.immediateFuture(Resources.Builder().setVersion(RESOURCES).build())
}

/**
 * The version of the resource bundle.
 *
 * Both tiles are **text only** and that is why this can stay at "0": the library caches the
 * resources by this string, so the day one of them adds an image without changing it, the
 * renderer serves the stale bundle.
 */
internal const val RESOURCES: String = "0"
