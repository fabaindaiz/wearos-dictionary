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
import cl.fadiaz.dictionary.presentation.posEnEspanol
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
class PalabraTileService : TileService() {

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): ListenableFuture<TileBuilders.Tile> {
        val (desde, palabras) = PackStore.palabrasDeLaSemana(this)
        val timeline = TimelineBuilders.Timeline.Builder()

        val dias = ventanas(desde, palabras.size)
        if (dias.isEmpty()) {
            timeline.addTimelineEntry(
                TimelineBuilders.TimelineEntry.Builder()
                    .setLayout(envolver(requestParams, null))
                    .build(),
            )
        } else {
            for ((indice, ventana) in dias) {
                timeline.addTimelineEntry(
                    TimelineBuilders.TimelineEntry.Builder()
                        .setValidity(ventana)
                        .setLayout(envolver(requestParams, palabras[indice]))
                        .build(),
                )
            }
        }

        return Futures.immediateFuture(
            TileBuilders.Tile.Builder()
                .setResourcesVersion(RECURSOS)
                // Backstop y no el mecanismo: si el usuario no abre la app, al terminar la semana
                // el tile vuelve a pedirse y muestra su estado vacio en vez de una palabra vieja.
                .setFreshnessIntervalMillis(UNA_SEMANA_MS)
                .setTileTimeline(timeline.build())
                .build(),
        )
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<Resources> =
        Futures.immediateFuture(Resources.Builder().setVersion(RECURSOS).build())

    private fun envolver(
        requestParams: RequestBuilders.TileRequest,
        visita: cl.fadiaz.dictionary.data.Visita?,
    ): androidx.wear.protolayout.LayoutElementBuilders.Layout {
        val elemento: LayoutElement = materialScope(this, requestParams.deviceConfiguration) {
            if (visita == null) {
                tileVacio(this@PalabraTileService, getString(R.string.tile_palabra_vacia))
            } else {
                tarjetaDePalabra(
                    this@PalabraTileService,
                    visita,
                    visita.partOfSpeech?.let(::posEnEspanol),
                )
            }
        }
        return androidx.wear.protolayout.LayoutElementBuilders.Layout.fromLayoutElement(elemento)
    }

    /**
     * Una ventana de reloj de pared por dia cacheado, de medianoche local a medianoche local.
     *
     * La zona horaria se consulta aca y no en [ContenidoDeTiles] a proposito: es estado del
     * sistema, igual que la fecha, y lo que se testea en la JVM tiene que recibirlo hecho.
     */
    private fun ventanas(
        desde: String?,
        cuantas: Int,
    ): List<Pair<Int, TimelineBuilders.TimeInterval>> {
        if (desde == null || cuantas <= 0) return emptyList()
        val inicio = try {
            LocalDate.parse(desde)
        } catch (e: DateTimeParseException) {
            return emptyList()
        }
        val zona = ZoneId.systemDefault()
        return (0 until cuantas).map { dia ->
            val arranca = inicio.plusDays(dia.toLong()).atStartOfDay(zona).toInstant().toEpochMilli()
            val termina = inicio.plusDays(dia + 1L).atStartOfDay(zona).toInstant().toEpochMilli()
            dia to TimelineBuilders.TimeInterval.Builder()
                .setStartMillis(arranca)
                .setEndMillis(termina)
                .build()
        }
    }
}

private const val UNA_SEMANA_MS = 7L * 24 * 60 * 60 * 1000
