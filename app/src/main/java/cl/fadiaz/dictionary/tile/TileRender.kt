package cl.fadiaz.dictionary.tile

import android.content.ComponentName
import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.ModifiersBuilders.Clickable
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.Typography
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.material3.textButton
import androidx.wear.protolayout.material3.titleCard
import androidx.wear.protolayout.types.layoutString
import cl.fadiaz.dictionary.R
import cl.fadiaz.dictionary.data.Visit

/**
 * De [TileContenido] a pixeles. Lo unico que estos archivos deciden es como se ve.
 *
 * QUE NO PASA ACA, Y ES EL PUNTO
 *
 * Ningun tile abre un pack. No es una precaucion de rendimiento --que sin medir estaria
 * prohibida (D-042)-- sino el contrato de la API: `onTileRequest` esta anotado `@MainThread` y
 * "must complete after at most 10 seconds". La guia oficial lo dice ademas en prosa: *"Don't
 * fetch content frequently or start long-running asynchronous work in your tile service"*, y
 * recomienda *"cache or store the results in local storage"*, que es exactamente lo que la app
 * deja escrito en `SharedPreferences`.
 */

/** La activity que abren los dos tiles. */
private fun mainActivity(context: Context) =
    ComponentName(context.packageName, "cl.fadiaz.dictionary.presentation.MainActivity")

/** Clave del extra con el pack de la entrada que el tile quiere abrir. */
const val EXTRA_PACK_ID: String = "cl.fadiaz.dictionary.PACK_ID"

/** Clave del extra con la entrada. Viaja como `long`, no como texto a re-parsear. */
const val EXTRA_ENTRY_ID: String = "cl.fadiaz.dictionary.ENTRY_ID"

/**
 * Clave del extra con el lema.
 *
 * Viaja **para poder corregir el `entryId`**, no para mostrarlo: el tile publica un id que salio
 * de `SharedPreferences` y que un rebuild del pack deja apuntando a otra palabra (D-055). El
 * lema es lo unico que sobrevive, y ya esta ahi al lado --el tile lo dibuja--.
 */
const val EXTRA_HEADWORD: String = "cl.fadiaz.dictionary.HEADWORD"

/**
 * Abrir la app en la busqueda.
 *
 * Componente explicito y no un deep link con `<data>`: un scheme convertiria la ruta de una
 * entrada en API publica del reloj a cambio de nada, porque los dos extremos viven en este APK.
 */
private fun openTheApp(context: Context): Clickable =
    Clickable.Builder()
        .setId("abrir")
        .setOnClick(ActionBuilders.launchAction(mainActivity(context)))
        .build()

/**
 * Abrir una entrada concreta, en **su** pack.
 *
 * El `packId` viaja junto al `entryId` porque sin el, con dos diccionarios abiertos, la entrada
 * se resolveria contra el activo y mostraria **otra palabra** sin error (D-080).
 */
private fun openTheEntry(context: Context, visit: Visit): Clickable =
    Clickable.Builder()
        .setId("entrada-${visit.packId}-${visit.entryId}")
        .setOnClick(
            ActionBuilders.launchAction(
                mainActivity(context),
                mapOf(
                    EXTRA_PACK_ID to ActionBuilders.stringExtra(visit.packId),
                    EXTRA_ENTRY_ID to ActionBuilders.longExtra(visit.entryId),
                    EXTRA_HEADWORD to ActionBuilders.stringExtra(visit.headword),
                ),
            ),
        )
        .build()

/**
 * Lo que se dibuja cuando no hay nada.
 *
 * **Nunca un tile en blanco**: en el carrusel no se lee como "vacio" sino como "roto". Y ademas
 * tiene que ser tocable, porque abrir la app es la unica forma de que deje de estar vacio.
 */
internal fun MaterialScope.emptyTile(context: Context, message: String): LayoutElement =
    primaryLayout(
        onClick = openTheApp(context),
        mainSlot = { text(message.layoutString, typography = Typography.BODY_LARGE) },
    )

/** Las ultimas entradas abiertas, una por fila, cada una abriendo su propia entrada. */
internal fun MaterialScope.historyRows(
    context: Context,
    visits: List<Visit>,
): LayoutElement =
    primaryLayout(
        titleSlot = {
            text(
                context.getString(R.string.tile_history_title).layoutString,
                typography = Typography.LABEL_SMALL,
            )
        },
        mainSlot = {
            val column = Column.Builder().setWidth(expand()).setHeight(expand())
            for (visit in visits) {
                column.addContent(
                    textButton(
                        onClick = openTheEntry(context, visit),
                        width = expand(),
                        labelContent = { text(visit.headword.layoutString, maxLines = 1) },
                    ),
                )
            }
            column.build()
        },
    )

/** La palabra de hoy: el lema grande y su categoria debajo. */
internal fun MaterialScope.wordCard(
    context: Context,
    visit: Visit,
    partOfSpeech: String?,
): LayoutElement =
    primaryLayout(
        titleSlot = {
            text(
                context.getString(R.string.tile_word_title).layoutString,
                typography = Typography.LABEL_SMALL,
            )
        },
        mainSlot = {
            titleCard(
                onClick = openTheEntry(context, visit),
                title = { text(visit.headword.layoutString, maxLines = 1) },
                content = partOfSpeech?.let { { text(it.layoutString, maxLines = 1) } },
            )
        },
    )
