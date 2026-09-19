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
 * From [TileContent] to pixels. The only thing these files decide is how it looks.
 *
 * WHAT DOES NOT HAPPEN HERE, AND THAT IS THE POINT
 *
 * No tile opens a pack. It is not a performance precaution --which without a measurement would
 * be forbidden (D-042)-- but the API contract: `onTileRequest` is annotated `@MainThread` and
 * "must complete after at most 10 seconds". The official guide also says it in prose: *"Don't
 * fetch content frequently or start long-running asynchronous work in your tile service"*, and
 * recommends *"cache or store the results in local storage"*, which is exactly what the app
 * leaves written in `SharedPreferences`.
 */

/** The activity both tiles open. */
private fun mainActivity(context: Context) =
    ComponentName(context.packageName, "cl.fadiaz.dictionary.presentation.MainActivity")

/** Key of the extra carrying the pack of the entry the tile wants to open. */
const val EXTRA_PACK_ID: String = "cl.fadiaz.dictionary.PACK_ID"

/** Key of the extra carrying the entry. It travels as a `long`, not as text to re-parse. */
const val EXTRA_ENTRY_ID: String = "cl.fadiaz.dictionary.ENTRY_ID"

/**
 * Key of the extra carrying the headword.
 *
 * It travels **so the `entryId` can be fixed**, not to be displayed: the tile publishes an id
 * that came out of `SharedPreferences` and that a pack rebuild leaves pointing at another word
 * (D-055). The headword is all that survives, and it is right there already --the tile draws it--.
 */
const val EXTRA_HEADWORD: String = "cl.fadiaz.dictionary.HEADWORD"

/**
 * Open the app on the search.
 *
 * An explicit component and not a deep link with `<data>`: a scheme would turn an entry's route
 * into public API of the watch in exchange for nothing, because both ends live in this APK.
 */
private fun openTheApp(context: Context): Clickable =
    Clickable.Builder()
        .setId("abrir")
        .setOnClick(ActionBuilders.launchAction(mainActivity(context)))
        .build()

/**
 * Open one specific entry, in **its** pack.
 *
 * The `packId` travels alongside the `entryId` because without it, with two dictionaries open,
 * the entry would be resolved against the active one and would show **another word**, with no
 * error (D-080).
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
 * What gets drawn when there is nothing.
 *
 * **Never a blank tile**: in the carousel it does not read as "empty" but as "broken". And it
 * also has to be tappable, because opening the app is the only way for it to stop being empty.
 */
internal fun MaterialScope.emptyTile(context: Context, message: String): LayoutElement =
    primaryLayout(
        onClick = openTheApp(context),
        mainSlot = { text(message.layoutString, typography = Typography.BODY_LARGE) },
    )

/** The most recently opened entries, one per row, each opening its own entry. */
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

/** Today's word: the headword large and its part of speech underneath. */
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
