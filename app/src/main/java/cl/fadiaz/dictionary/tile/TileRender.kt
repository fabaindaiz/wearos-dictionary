package cl.fadiaz.dictionary.tile

import android.content.ComponentName
import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
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
import cl.fadiaz.dictionary.presentation.wordDetail

/**
 * The gap between two stacked cards in a tile.
 *
 * ⚠️ **Without it they touch**, and two bubbles with no light between them read as one block --
 * seen on the watch's geometry on 2026-09-28, on both tiles. It is the same 4 dp the home puts
 * between its rows, so the two surfaces space alike.
 *
 * It is affordable: with two rows of 48 dp in a 132 dp main slot there is room to spare, which is
 * exactly why the row count had to come down to two first. A third row left no space for this.
 */
private const val CARD_GAP_DP = 4f

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

/**
 * The bubble a word sits in, **the same one on both tiles**.
 *
 * ⚠️ **The two tiles used to draw a word two different ways** -- the recent list as a one-line
 * `textButton` reading `porta · sust.`, the word of the day as a `titleCard` with the headword
 * large and its gloss under it. Asked for: the recent list uses the word-of-the-day bubble. That
 * is also what D-152 already required of the app's three lists and what the tiles had never been
 * held to.
 *
 * ⚠️ **The type and the language go in the `time` slot**, which is the card's small label beside
 * the title. Naming it `time` is the library's, not ours; what it is, is the only slot that adds
 * a second datum **without taking a line from the content** -- and the content is the gloss, which
 * is the part worth keeping whole.
 *
 * [glossLines] at zero draws no content at all: a history visit carries no gloss, and a card with
 * an empty body reads as a card that failed to load.
 */
private fun MaterialScope.wordBubble(
    context: Context,
    visit: Visit,
    glossLines: Int,
): LayoutElement {
    // ⚠️ **The gloss wins over the part of speech as the BODY.** `futuro · sust.` teaches nothing;
    // the first sense is what makes a word of the day useful at a glance. Since the type now has
    // a slot of its own, it is no longer a fallback -- both are shown.
    val cuerpo = visit.gloss?.takeIf { glossLines > 0 }
    return titleCard(
        onClick = openTheEntry(context, visit),
        title = { text(visit.headword.layoutString, maxLines = 1) },
        time = tileDetail(context, visit)?.let { detalle ->
            { text(detalle.layoutString, maxLines = 1) }
        },
        content = cuerpo?.let { { text(it.layoutString, maxLines = glossLines) } },
    )
}

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
            for ((index, visit) in visits.withIndex()) {
                if (index > 0) {
                    column.addContent(Spacer.Builder().setHeight(dp(CARD_GAP_DP)).build())
                }
                // No gloss: a history visit does not store one. The headword leads and the type
                // sits beside it, which is the same bubble the word of the day draws.
                column.addContent(wordBubble(context, visit, glossLines = 0))
            }
            column.build()
        },
        bottomSlot = { searchButton(context) },
    )

/**
 * Today's word, or **today's two words** when two definition languages are installed.
 *
 * ⚠️ **The gloss shrinks to ONE line when there are two cards, and that is the whole layout
 * decision.** The main slot of a tile measures **132 dp** on the watch's geometry (see
 * `TileContents.TILE_CHROME_DP`, measured). One card with a two-line gloss fills it; two cards
 * with two-line glosses do not fit, and what does not fit in a tile is not scrolled to -- it is
 * clipped, which is how the history tile was drawing a 32 dp third row.
 *
 * So the second word costs the first one a line of its gloss. That is the trade, and it is the
 * right way round: a word with no gloss at all teaches nothing, and `futuro · sust.` even less.
 */
internal fun MaterialScope.wordCard(
    context: Context,
    visits: List<Visit>,
): LayoutElement =
    primaryLayout(
        titleSlot = {
            text(
                context.getString(R.string.tile_word_title).layoutString,
                typography = Typography.LABEL_SMALL,
            )
        },
        mainSlot = {
            val lineas = if (visits.size > 1) 1 else 2
            val column = Column.Builder().setWidth(expand()).setHeight(expand())
            for ((index, visit) in visits.withIndex()) {
                if (index > 0) {
                    column.addContent(Spacer.Builder().setHeight(dp(CARD_GAP_DP)).build())
                }
                column.addContent(wordBubble(context, visit, glossLines = lineas))
            }
            column.build()
        },
        bottomSlot = { searchButton(context) },
    )

/**
 * What a tile's bubble says beside the word: `sust. · ES`. **The same string the app's rows say.**
 *
 * ⚠️ **It used to leave the language tag out, and the reason it gave has expired.** It said a
 * `Visit` does not store the language, so inventing one would assert a provenance nobody checked
 * -- D-080's family, and right at the time. `Visit.lang` has existed since D-265: the row carries
 * what was true when it was written, so the tag is now a fact and not an inference. A stale
 * justification is worse than none, because it looks like a decision.
 *
 * It still refuses to guess: a row written before that field, or one whose language nothing can
 * establish, shows the type alone. No tag beats the wrong tag, which is the rule a gloss's links
 * are painted by.
 *
 * `wordDetail` and not a copy: it is the function the home, the history and the saved words all
 * use (D-152), and the last time this file had its own the tile said `perro` where the home said
 * `perro · sust.`.
 */
private fun tileDetail(context: Context, visit: Visit): String? =
    wordDetail(
        context,
        visit.partOfSpeech?.let { posLabel(context, it) },
        visit.lang?.uppercase(),
    )

/** The bottom edge: search for another word, the only action a tile of this offers. */
private fun MaterialScope.searchButton(context: Context) =
    textEdgeButton(
        onClick = searchAction(context),
        labelContent = { text(context.getString(R.string.tile_search).layoutString) },
    )
