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
import androidx.wear.protolayout.material3.textEdgeButton
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.material3.textButton
import androidx.wear.protolayout.material3.titleCard
import androidx.wear.protolayout.types.layoutString
import cl.fadiaz.dictionary.R
import cl.fadiaz.dictionary.data.Visit
import cl.fadiaz.dictionary.presentation.posLabel

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
 * Asks the app to open the **system input** directly (voice, keyboard or handwriting).
 *
 * ⚠️ **A tile accepts no text (D-026), but it can fire an intent**, and that is the difference
 * this key exploits. The `primaryLayout`'s `bottomSlot` was empty and it is exactly where the Wear
 * OS guidance puts a tile's action.
 *
 * What it saves: today, searching from the carousel is **three taps** --open the app, tap the
 * field, dictate--. With this it is one.
 */
const val EXTRA_OPEN_INPUT: String = "cl.fadiaz.dictionary.OPEN_INPUT"

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
 * The button that opens the app **with the input already open**. It goes in the `bottomSlot`.
 *
 * It is the only action of a dictionary tile that is not "open this specific word": search for
 * another one.
 */
private fun searchAction(context: Context): Clickable =
    Clickable.Builder()
        .setId("buscar")
        .setOnClick(
            ActionBuilders.launchAction(
                mainActivity(context),
                mapOf(EXTRA_OPEN_INPUT to ActionBuilders.stringExtra("1")),
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
                        // ⚠️ **The SAME function as the app**, not a copy: the row said just
                        // `perro` where the home says `perro · sust.`, and not out of density but
                        // because it shared nothing. The tiles guidance asks not to show LESS
                        // information than fits.
                        labelContent = {
                            text(
                                detalleDeFila(context, visit).layoutString,
                                maxLines = 1,
                            )
                        },
                    ),
                )
            }
            column.build()
        },
        bottomSlot = { searchButton(context) },
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
            // ⚠️ **The gloss wins over the part of speech.** `futuro · sust.` teaches nothing; the
            // first sense is what makes a word of the day useful at a glance. The part of speech
            // stays as a fallback for a pack that does not carry it, and for a cache written
            // before this field existed.
            val cuerpo = visit.gloss ?: partOfSpeech
            titleCard(
                onClick = openTheEntry(context, visit),
                title = { text(visit.headword.layoutString, maxLines = 1) },
                // Two lines: an average gloss is 64 characters and on a single one it is clipped
                // almost every time. The tile does not scroll, so what does not fit does not exist.
                content = cuerpo?.let { { text(it.layoutString, maxLines = 2) } },
            )
        },
        bottomSlot = { searchButton(context) },
    )

/**
 * What a tile row says: the same as what a home row says.
 *
 * ⚠️ **Without the language tag, and on purpose.** In the app it comes from the ACTIVE language,
 * which a tile does not know: asking would mean opening a pack, which in a tile is forbidden
 * (D-106). And a `Visit` does not store the language, so inventing it would assert a provenance
 * nobody checked -- D-080's family. Better to say less than to say something false.
 */
private fun detalleDeFila(context: Context, visit: Visit): String =
    listOfNotNull(
        visit.headword,
        visit.partOfSpeech?.let { posLabel(context, it) },
    ).joinToString(context.getString(R.string.entry_list_separator))

/** The bottom edge: search for another word, the only action a tile of this offers. */
private fun MaterialScope.searchButton(context: Context) =
    textEdgeButton(
        onClick = searchAction(context),
        labelContent = { text(context.getString(R.string.tile_search).layoutString) },
    )
