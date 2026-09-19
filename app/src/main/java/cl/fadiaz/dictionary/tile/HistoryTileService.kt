package cl.fadiaz.dictionary.tile

import androidx.wear.protolayout.ResourceBuilders.Resources
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material3.materialScope
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import cl.fadiaz.dictionary.R
import cl.fadiaz.dictionary.data.PackStore
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Las ultimas entradas abiertas, a un toque desde la esfera.
 *
 * POR QUE NO TIENE REFRESCO PROGRAMADO
 *
 * `freshnessIntervalMillis = 0`, y el javadoc de `TileBuilders` dice que ese valor significa
 * *"that auto-refreshes should not be used (i.e. you will manually request updates via
 * TileService#getRequester)"*. O sea: **el sistema no vuelve a llamar a este tile nunca**. Y esta
 * bien, porque su contenido no cambia con el reloj de pared sino cuando el usuario abre una
 * entrada -- y en ese momento la app lo empuja con `getUpdater().requestUpdate(...)`.
 *
 * Es la unica superficie de este proyecto que cuesta **cero** despertares.
 */
class HistoryTileService : TileService() {

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): ListenableFuture<TileBuilders.Tile> {
        // Se lee de SharedPreferences y nada mas: no se abre ningun pack. Ver TileRender.kt.
        val content = TileContents.history(PackStore.history(this))
        val layout = materialScope(this, requestParams.deviceConfiguration) {
            when (content) {
                is TileContent.ListRows -> historyRows(this@HistoryTileService, content.visits)
                else -> emptyTile(
                    this@HistoryTileService,
                    getString(R.string.tile_history_empty),
                )
            }
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
 * La version del bundle de recursos.
 *
 * Los dos tiles son **solo texto** y por eso puede quedarse en "0": la libreria cachea los
 * recursos por este string, asi que el dia que alguno agregue una imagen sin cambiarlo, el
 * renderer sirve el bundle viejo.
 */
internal const val RESOURCES: String = "0"
