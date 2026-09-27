package cl.fadiaz.dictionary.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.graphics.Shape
import cl.fadiaz.dictionary.core.PackKind
import cl.fadiaz.dictionary.R
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import kotlin.math.ceil

/**
 * The pieces the screens share.
 *
 * It exists because the same mould --clip to a pill, paint the background, make it tappable and
 * never go below 48 dp-- was copied into six places across two files. Six copies of a rule are
 * six places where somebody lowers the touch height in one of them and nobody finds out.
 *
 * The six are NOT unified. Three are full-width text pills and come from [Pill]; the other three
 * are genuinely different --two chips sized to their content and a row with an icon-- and forcing
 * them into the same composable would ask for half a dozen optional parameters, which is worse
 * than the duplication it came to fix. What the six do share is [PILL_SHAPE] and [TOUCH_TARGET],
 * which is where the real risk was.
 */

/**
 * The minimum touch target the Wear OS guidance asks for.
 *
 * It lives in a named constant and not as a loose `48.dp` for a concrete reason: if somebody
 * lowers it to squeeze in one more row, the density test **still passes** and what breaks is the
 * touch area, which no test can see (D-073).
 */
internal val TOUCH_TARGET: Dp = 48.dp

/**
 * The pill. The clip and the border have to be the SAME shape: if they drift apart, the border is
 * drawn straight over the rounded corners.
 */
internal val PILL_SHAPE = RoundedCornerShape(percent = 50)

/**
 * For anything taller than one line.
 *
 * The 50 % pill clips the corners with a radius of half the height: with two lines that eats the
 * beginning and the end of the text. A fixed radius does not grow with the height.
 */
internal val CARD_SHAPE = RoundedCornerShape(24.dp)

/**
 * How far you can scroll **past** the last item.
 *
 * Without it the last line sits flush against the edge, and on a ROUND screen the bottom edge
 * curves inward: the text looks cut off and there is no way to scroll further. It happened on all
 * six screens because all six passed the `ScreenScaffold`'s `contentPadding` straight through.
 */
private val BOTTOM_MARGIN: Dp = 32.dp

/**
 * How far the home pushes its first item down, to clear the clock.
 *
 * `ScreenScaffold` draws `TimeText` as an overlay and its `contentPadding` does not reserve all
 * of it. On the five screens that open on a title that does not matter; on the home the first
 * item is the **search bar** (D-111), and a text field with the clock on top reads as broken.
 *
 * ⚠️ **It is a spacer ITEM and not `contentPadding`, and the difference is visible.** As padding
 * the bar still started inside the `TransformingLazyColumn`'s edge transform, which scales and
 * clips whatever is closest to the rim: the field moved down but **its rounded shape came out
 * cut**. An item of its own is laid out like any other and keeps its shape.
 *
 * **It scales with the screen, and that is not a guess** (D-133): Wear Compose Material3 declares
 * the vertical content padding as 10 % of the screen —read out of `compose-material3-1.6.2.aar`,
 * `PaddingDefaults.verticalContentPaddingPercentage = 10.0f`— and the 20 dp that used to be
 * hardcoded here **is exactly 10 % of 192 dp**, the width this repo historically assumed. The
 * number always was a fraction; it was frozen against the wrong screen. `ClockGapTest` pins that.
 *
 * Floored and capped: a mis-reported screen must not put the field back under the clock, and 10 %
 * of a big screen must not eat the only scarce resource a watch has.
 */
internal fun clockGap(screenHeightDp: Int): Dp =
    ceil(screenHeightDp * CLOCK_GAP_FRACTION).toInt().coerceIn(16, 32).dp

/**
 * The platform's own vertical fraction. A `private const` and not a call to `PaddingDefaults`
 * because that one is `@Composable` —it reads the configuration itself— and this function has to
 * stay callable from a plain JVM test, which is what makes the 192 dp anchor verifiable at all.
 */
private const val CLOCK_GAP_FRACTION = 0.10f

/** [clockGap] against the real screen, so the caller does not have to know how to measure it. */
@Composable
internal fun clockGap(): Dp = clockGap(LocalConfiguration.current.screenHeightDp)

/**
 * The scaffold's `contentPadding`, with room to breathe at the end.
 *
 * A function and not 32.dp repeated across six files: the day the number changes --or somebody
 * measures how much the curve really eats-- there is a single place to touch.
 */
@Composable
internal fun withScreenMargins(base: PaddingValues): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = base.calculateStartPadding(direction),
        top = base.calculateTopPadding(),
        end = base.calculateEndPadding(direction),
        bottom = base.calculateBottomPadding() + BOTTOM_MARGIN,
    )
}

/**
 * A full-width text pill, tappable or not.
 *
 * A null `onClick` means **present but not tappable**, which is not the same as absent: it is the
 * "searching the definitions…" state, where the pill stays on screen so the list does not jump,
 * but tapping it again cannot fire a second query.
 */
@Composable
internal fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    background: Color = MaterialTheme.colorScheme.primaryContainer,
    ink: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    style: TextStyle = MaterialTheme.typography.labelMedium,
    margin: Dp = 16.dp,
    onClick: (() -> Unit)? = null,
) {
    Text(
        text = text,
        style = style,
        color = ink,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = margin)
            .clip(PILL_SHAPE)
            .background(background)
            .let { if (onClick == null) it else it.clickable(onClick = onClick) }
            .heightIn(min = TOUCH_TARGET)
            .padding(vertical = 14.dp),
    )
}

/**
 * The mould of a tappable row: 48 dp, one line, the headword leads and the detail follows.
 *
 * It deliberately does not use Wear Compose's `Button`: its minimum height is 52 dp and with the
 * header four rows did not fit. 48 dp is the minimum the Wear OS guidance asks for a touch area,
 * and going below that would buy density by breaking something worse.
 */
@Composable
internal fun ListRow(
    headword: String,
    detail: String?,
    /** Long press, for the lists that arm a deletion. Null everywhere else. */
    onLongClick: (() -> Unit)? = null,
    /**
     * The word is in a dictionary that is no longer installed.
     *
     * ⚠️ **It keeps every datum and changes only the colour.** A saved word whose pack is gone
     * still knows its headword and its part of speech, and dropping it would lose what somebody
     * chose to keep; drawing it identical to a working one promises an entry that will not open.
     * The saved list is where it shows -- the history is filtered by installed pack.
     */
    orphaned: Boolean = false,
    // Last, so the trailing lambda keeps working: every call site writes
    // `ListRow(headword, detail) { abrir() }`.
    onClick: () -> Unit,
) {
    val colors = if (orphaned) {
        WordBubbleDefaults.orphanedColors()
    } else {
        WordBubbleDefaults.colors()
    }
    WordBubble(colors = colors, onClick = onClick, onLongClick = onLongClick) {
        WordBubbleContent(headword, detail, colors)
    }
}

/**
 * The detail that accompanies a word in ANY row: `sust. · ES`.
 *
 * ⚠️ **It exists so the three lists say the same thing** (D-152). A search result used to say
 * `sust. · ES` and the same lemma in the history said only `sust.`: two rows representing the same
 * thing with different information teach the user that the tag means something different
 * depending on where it is.
 *
 * ⚠️ **There is no longer an `override` for the match rung.** It used to replace the part of
 * speech in the results --`forma`, `parecida`-- because all three do not fit in a 234 dp row.
 * Removed on 2026-09-26 by request: a row says what the word IS, and the three lists now say it
 * identically, which is what this function was written for.
 *
 * The separator comes from the **same resource** the entry uses. It was written by hand here and
 * as a resource there, which is two definitions of the same thing waiting to diverge.
 */
@Composable
internal fun wordDetail(
    partOfSpeech: String?,
    tag: String?,
): String? = wordDetail(LocalContext.current, partOfSpeech, tag)

/**
 * [wordDetail] for whoever has a `Context` and no composition: **the tiles**.
 *
 * ⚠️ **It exists so a row says the same thing on both surfaces.** The recents tile showed only
 * `perro` while the app showed `perro · sust.`, and not out of a density decision: it simply did
 * not share the function. It is the same failure family that has already appeared twice today --a
 * rule that holds on one surface and not on its parallel-- and the same remedy `posLabel` was
 * already using.
 *
 * ⚠️ **The separator comes from the resource, not from a constant**: it is the same `·` the app
 * uses, and if it ever changes, it changes on both sides at once.
 */
internal fun wordDetail(
    context: Context,
    partOfSpeech: String?,
    tag: String?,
): String? {
    val partes = listOfNotNull(partOfSpeech, tag)
    return partes.takeIf { it.isNotEmpty() }
        ?.joinToString(context.getString(R.string.entry_list_separator))
}

/** Opening the pack, or extracting it for the first time. */
@Composable
internal fun LoadingMessage(message: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CircularProgressIndicator()
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Which class of dictionary it is, as a **resource id**.
 *
 * Split in two on purpose. The mapping is pure, so a JVM test can demand that **there be one per
 * `PackKind` and that they be distinct** without starting Android; resolving the text needs a
 * `Context` and lives in [packTypeLabel].
 *
 * It exists because the pack's name stopped saying it: it was "Español - definiciones" --22
 * characters, clipped in all four places it is shown-- and became "Español" (D-125). What the long
 * name communicated now comes from `kind`, which is **a datum of the pack** and not a string
 * somebody has to remember to write correctly in every new pack.
 *
 * ⚠️ It used to be in `data/PackSet.kt`, which the audit watches so it does not import `android.*`
 * (D-072). Translating it would have broken that: hence the move to the layer that may (D-127).
 */
@StringRes
internal fun packTypeLabelRes(kind: PackKind): Int = when (kind) {
    PackKind.MONOLINGUAL -> R.string.pack_kind_monolingual
    PackKind.BILINGUAL -> R.string.pack_kind_bilingual
}

/** [packTypeLabelRes]'s text, in the watch's language. */
@Composable
internal fun packTypeLabel(kind: PackKind): String = stringResource(packTypeLabelRes(kind))

/**
 * What the chrome eats before the first row: the clock at the top, the trailing margin, and what a
 * round screen curves inward.
 *
 * It comes from solving the line through the TWO measured points --192 dp composes 2 rows and 234
 * composes 3-- against a step of [TOUCH_TARGET] per row. It is not a design constant: it is the
 * residue of a measurement, which is why it lives next to the function that uses it and not in a
 * token table.
 */
private const val CHROME_DP = 60

/** Row cap. Wear OS does not go past ~250 dp today; this is a net, not a real case. */
private const val MAX_ROWS_EVER = 8

/**
 * How many [TOUCH_TARGET] rows fit on a screen `screenWidthDp` wide.
 *
 * **It exists so the code is generic and not to pick one watch.** The repo priced five decisions
 * against 192 dp (D-073, D-075, D-078, D-084, D-085) and the project's watch delivers 234: putting
 * 234 in its place would be swapping one wrong number for another. What adapts is **how many rows
 * are shown**, not the size of any of them -- going below 48 dp breaks the touch area the Wear OS
 * guidance requires, and no test would see it.
 *
 * It never returns fewer than two: with a single row the list stops being a list.
 */
internal fun rowsThatFit(screenWidthDp: Int): Int =
    ((screenWidthDp - CHROME_DP) / TOUCH_TARGET.value.toInt())
        .coerceIn(2, MAX_ROWS_EVER)

/** [rowsThatFit] against the real screen, without the caller having to know how to measure it. */
@Composable
internal fun rowsThatFit(): Int = rowsThatFit(LocalConfiguration.current.screenWidthDp)


/**
 * Coming back to the app returns THIS screen to its top, without leaving it.
 *
 * ⚠️ **It replaces a `popBackStack`, and the difference is the whole point.** Leaving the app used
 * to navigate back to the home, on the reasoning that a watch is not closed --the wrist is
 * lowered-- so an old card is not worth resuming. In use it reads the other way round: lowering
 * your wrist mid-word and finding the home again is losing your place. What was right about the
 * old behaviour is the *feeling of starting over*, and that is a scroll, not a navigation.
 *
 * ⚠️ **`ON_START` and not `ON_RESUME`**: the system's voice input is a full-screen Activity that
 * pauses ours, and scrolling on resume would yank the list under somebody who never left. It is
 * the mirror of why the observer this replaces listened on `ON_STOP`.
 *
 * ⚠️ **It skips the FIRST start**, which is the one that happens as the screen appears. Scrolling
 * there is at best a no-op and at worst fights the position a freshly opened card was given --
 * `EntryScreen` deliberately opens anchored at item 1, not 0.
 */
@Composable
internal fun ScrollToTopOnReturn(state: TransformingLazyColumnState, firstIndex: Int = 0) {
    var vueltas by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) vueltas++
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(vueltas) {
        if (vueltas > 1) state.scrollToItem(firstIndex)
    }
}


/**
 * How a word is broken when it does not fit: **hyphenated, never cut**.
 *
 * ⚠️ **It is one value used by every place a word is drawn**, which is what stops the three from
 * drifting. A headword, a row and a principal part are the same object seen at three sizes, and
 * before this each decided its own overflow: the card wrapped mid-word, the row ellipsised, and a
 * long form pushed its type out of the row.
 *
 * `Hyphens.Auto` asks the platform for a break the language allows --`elec-tro-en-ce-fa-lo-grá-fi-co`--
 * instead of wherever the pixel ran out. It needs the text's locale, which comes from the
 * composition, so a Spanish word breaks by Spanish rules and an English one by English ones.
 *
 * ⚠️ **`LineBreak.Paragraph` and not `Heading`, and it took looking at it to find out.** `Heading`
 * is what Compose documents for short titles and it was the obvious pick; on the 234 dp emulator
 * it split `electroencefalográfico` as `electroence|falográfico`, with no hyphen and in the middle
 * of a syllable. `Paragraph` is the high-quality strategy, the one that actually consults the
 * hyphenation dictionary. The balanced look `Heading` promises is worth nothing if the break it
 * balances is in the wrong place.
 */
internal val WORD_BREAK = LineBreak.Paragraph

/**
 * A word as the head of its own screen: the word, how it sounds, and what it is.
 *
 * ⚠️ **One component and not three blocks**, because the entry card and anything else that titles
 * a word have to agree. It is the same reason `wordDetail` exists for rows (D-152): two places
 * describing the same object differently teach the reader that the difference means something.
 *
 * ⚠️ **The word and the pronunciation are a size above what they were.** Asked for on 2026-09-26:
 * they are the part of an entry that gets looked at first, and they were drawn at the same weight
 * as the line below them. The word goes `titleMedium` -> `titleLarge` and the pronunciation
 * `labelSmall` -> `bodySmall`; the `sust. · ES` line stays where it was, which is what makes the
 * step visible.
 *
 * ⚠️ **It wraps instead of truncating, and the entry is where that matters most**: this screen
 * exists to read the whole word, sayings included.
 */
@Composable
internal fun WordTitle(headword: String, pronunciation: String?, detail: String?) {
    Text(
        text = headword,
        style = MaterialTheme.typography.titleLarge.copy(
            hyphens = Hyphens.Auto,
            lineBreak = WORD_BREAK,
        ),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    if (!pronunciation.isNullOrBlank()) {
        Text(
            text = pronunciation,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        )
    }
    if (detail != null) {
        Text(
            text = detail,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        )
    }
}


/**
 * What a [WordBubble] is painted with. One value so a caller sets a STATE, not three colours.
 *
 * ⚠️ **Immutable, and that is not decoration**: Compose skips a recomposition only when it can
 * prove the parameters did not change, and an unmarked data class of `Color`s cannot be proven
 * stable. On a watch a needless recomposition of every row of a list is CPU the battery pays for.
 */
@Immutable
internal data class WordBubbleColors(
    val container: Color,
    val headword: Color,
    val detail: Color,
)

/**
 * The defaults of [WordBubble], in the shape Compose itself uses for this.
 *
 * ⚠️ **A `Defaults` object and a content slot, rather than a growing list of optional flags.**
 * The row started as `ListRow(headword, detail, onClick)` and grew `onLongClick`, then `orphaned`;
 * the next state would have been a fourth boolean, and a component whose API is a pile of booleans
 * cannot say which combinations are meaningful. Here a caller passes the STATE it is in --
 * [orphanedColors], [armedColors] -- and whatever it wants inside.
 *
 * It is the convention `androidx.wear.compose` follows for its own components (`ButtonDefaults`,
 * and the `RotaryScrollableDefaults` this app already calls), so it is also what a reader coming
 * from the library expects. ⚠️ **KMP itself stays out** (D-018): Wear Compose is Android only and
 * there is no second target to share with. What is borrowed is the API shape, not the build.
 */
internal object WordBubbleDefaults {

    /** See [CARD_SHAPE]: identical to the pill at one line, and correct at two. */
    val Shape: Shape get() = CARD_SHAPE

    val MinHeight: Dp get() = TOUCH_TARGET

    val Padding get() = PaddingValues(horizontal = 16.dp, vertical = 8.dp)

    /** The word. Hyphenated and language-aware, which is the rule every word on screen follows. */
    val headwordStyle: TextStyle
        @Composable get() = MaterialTheme.typography.bodyLarge.copy(
            hyphens = Hyphens.Auto,
            lineBreak = WORD_BREAK,
        )

    /** What the word IS: `sust. · ES`. Never why it matched (D-152, and 2026-09-26). */
    val detailStyle: TextStyle
        @Composable get() = MaterialTheme.typography.labelSmall

    @Composable
    fun colors(
        container: Color = MaterialTheme.colorScheme.surfaceContainer,
        headword: Color = MaterialTheme.colorScheme.onSurface,
        detail: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    ): WordBubbleColors = WordBubbleColors(container, headword, detail)

    /** The word's dictionary is no longer installed: it keeps every datum and changes colour. */
    @Composable
    fun orphanedColors(): WordBubbleColors = colors(
        container = MaterialTheme.colorScheme.errorContainer,
        headword = MaterialTheme.colorScheme.onErrorContainer,
        detail = MaterialTheme.colorScheme.onErrorContainer,
    )

    /** A deletion is armed and the next tap confirms it. */
    @Composable
    fun armedColors(): WordBubbleColors = colors(
        container = MaterialTheme.colorScheme.error,
        headword = MaterialTheme.colorScheme.onError,
        detail = MaterialTheme.colorScheme.onError,
    )
}

/**
 * The bubble every word in this app sits in. **The content is the caller's.**
 *
 * ⚠️ **What it owns is the box, not what goes in it**: the shape, the touch target, the padding
 * and the background. Three screens used to own their own copy of that box --and one of them was
 * `ListRow` character for character-- so a change to the box reached one of the three and nothing
 * said the others had drifted.
 *
 * `onClick` is nullable because a bubble is not always tappable, and `combinedClickable` is only
 * attached when it is: an empty click handler still consumes the gesture and still ripples, which
 * on a row that leads nowhere reads as the app ignoring you.
 */
@Composable
internal fun WordBubble(
    modifier: Modifier = Modifier,
    colors: WordBubbleColors = WordBubbleDefaults.colors(),
    shape: Shape = WordBubbleDefaults.Shape,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.container)
            .let {
                if (onClick == null) {
                    it
                } else {
                    it.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                }
            }
            .heightIn(min = WordBubbleDefaults.MinHeight)
            .padding(WordBubbleDefaults.Padding),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * The usual inside of a [WordBubble]: the word, and what it is, right-aligned.
 *
 * Separate from the bubble so a caller with something else to put in --the armed row puts an icon
 * and a label-- uses the same box without inheriting this layout.
 */
@Composable
internal fun RowScope.WordBubbleContent(
    headword: String,
    detail: String?,
    colors: WordBubbleColors = WordBubbleDefaults.colors(),
) {
    Text(
        text = headword,
        style = WordBubbleDefaults.headwordStyle,
        color = colors.headword,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
    )
    if (detail != null) {
        Text(
            text = detail,
            style = WordBubbleDefaults.detailStyle,
            color = colors.detail,
            maxLines = 1,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
