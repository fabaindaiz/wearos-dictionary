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
        // Cuantas filas, contra la pantalla REAL. Un tile no scrollea, asi que aca el tamaño si
        // manda -- al reves que el inicio, donde recortar solo esconderia (D-131).
        //
        // ⚠️ Se topea en `MAX_ROWS`, que es el numero medido, y no se deja crecer: el chrome de
        // un tile --el titulo, los margenes del renderer-- **no esta medido**, y `rowsThatFit`
        // esta anclado en la PANTALLA. Dejarlo crecer seria afirmar un numero que nadie midio.
        // Lo que si hace es BAJAR en un reloj chico, que es lo que protege al generico.
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
        // ⚠️ El unico rastro de que un tile corrio. `onTileRequest` es `@MainThread` con diez
        // segundos (D-106) y *"nobody opens a tile on purpose"*: si tarda o se rinde, no hay
        // pantalla donde verlo. Ademas deja escrito el ancho real, que es el numero que falta
        // para cerrar el breakpoint de 225 dp.
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
