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
import cl.fadiaz.dictionary.presentation.rowsThatFit
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
        // ⚠️ It is capped at `MAX_ROWS`, which is the measured number, and not allowed to grow: a
        // tile's chrome --the title, the renderer's margins-- **is not measured**, and
        // `rowsThatFit` is anchored to the SCREEN. Letting it grow would assert a number nobody
        // measured. What it does do is go DOWN on a small watch, which is what protects the
        // generic one.
        val rows = minOf(
            rowsThatFit(requestParams.deviceConfiguration.screenWidthDp),
            TileContents.MAX_ROWS,
        )
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
