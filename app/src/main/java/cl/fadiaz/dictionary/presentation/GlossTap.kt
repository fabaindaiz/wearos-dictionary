package cl.fadiaz.dictionary.presentation

import kotlin.math.abs

/**
 * Which linked word a tap meant, when the finger is wider than the word.
 *
 * ## Why this exists
 *
 * A tappable word inside a gloss is painted by its glyphs, so **its touch area is the glyph**:
 * measured on this repo's packs, a word of the average length is about **40 x 14 dp** at
 * `labelSmall` on a 234 dp screen, against Android's **48 x 48** minimum. The height is the
 * problem -- 3.4x under the minimum -- and in a paragraph the word above and the word below sit
 * about 4 dp away. Mis-taps are geometry, not a defect.
 *
 * ⚠️ **This was evaluated as one of five options and recommended AGAINST**, on the grounds that
 * snapping trades *failing visibly* for *failing confidently*: with no visual feedback, a hit and
 * a near-miss feel identical, and two adjacent links make it choose wrong with conviction. It was
 * chosen anyway, so the design answers that objection where it can:
 *
 *  - **An exact hit always wins.** The snap never overrides a word the finger actually landed on,
 *    so nothing that works today changes.
 *  - **The line is checked before the distance.** A tap inside a line's vertical band can only
 *    snap to a word *on that line*, so the failure mode the objection names -- grabbing the word
 *    above or below because it happens to be closer in a straight line -- cannot happen while the
 *    tap is inside any line at all.
 *  - **The radius is bounded.** Beyond it the tap does nothing, exactly as today. Snapping
 *    across half a screen would be the confident-wrong behaviour the objection describes.
 *
 * What it cannot answer: with two links side by side on the same line and a tap in the gap
 * between them, it picks the nearer one and the user gets no signal that it guessed. That cost
 * is real and is the reason this was not the recommendation.
 *
 * Pure and free of Android so the gate covers it (D-072). The Composable measures the boxes and
 * hands them over; every decision lives here.
 */
internal object GlossTap {

    /**
     * Where one linked word sits, in the text's own coordinates.
     *
     * [index] is the position in the caller's list of links, not an entry id: this file knows
     * nothing about packs and does not need to.
     */
    internal data class LinkBox(
        val index: Int,
        val line: Int,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom

        /** Horizontal gap to [x]; zero when [x] is within the box. */
        fun horizontalGap(x: Float): Float = when {
            x < left -> left - x
            x > right -> x - right
            else -> 0f
        }

        /** Vertical gap to [y]; zero when [y] is within the band of its line. */
        fun verticalGap(y: Float): Float = when {
            y < top -> top - y
            y > bottom -> y - bottom
            else -> 0f
        }
    }

    /**
     * The link a tap at ([x], [y]) meant, or `null` when it meant none.
     *
     * The order is the whole design, and each step exists to stop the one after it from doing
     * something wrong:
     *
     * 1. **A box that contains the point wins outright.** No heuristic runs while the finger is
     *    on the word, so the behaviour that already worked is untouched.
     * 2. **Otherwise, only words on the tapped line are considered**, chosen by horizontal gap.
     *    This is what keeps a tap in the middle of a line from jumping to the line above.
     * 3. **Only if the tap is between lines** -- in the leading, where no box's band contains it
     *    -- is the search widened to every box, by straight-line gap.
     * 4. **Anything beyond [radius] is not a tap on a word.** Returning `null` there is the
     *    point: a snap with no bound is how a heuristic becomes confidently wrong.
     *
     * Ties go to the lowest [LinkBox.index], so the same tap always resolves the same way. A
     * word that resolves differently on two taps would be worse than one that resolves wrongly:
     * the user could not learn it.
     */
    fun linkAt(boxes: List<LinkBox>, x: Float, y: Float, radius: Float): Int? {
        if (boxes.isEmpty()) return null

        // ⚠️ **Containment is resolved BEFORE the radius is consulted, and the order is a fix.**
        // The first version asked `radius < 0f` at the top and returned null: an EXACT tap on the
        // word was lost because the radius was invalid, when containment does not depend on the
        // radius at all. Its own test caught it.
        boxes.firstOrNull { it.contains(x, y) }?.let { return it.index }
        if (radius < 0f) return null

        val onTappedLine = boxes.filter { it.verticalGap(y) == 0f }
        if (onTappedLine.isNotEmpty()) {
            val nearest = onTappedLine.minWith(
                compareBy({ it.horizontalGap(x) }, { it.index }),
            )
            return if (nearest.horizontalGap(x) <= radius) nearest.index else null
        }

        val nearest = boxes.minWith(compareBy({ gap(it, x, y) }, { it.index }))
        return if (gap(nearest, x, y) <= radius) nearest.index else null
    }

    /**
     * Straight-line gap from the point to the box, zero inside.
     *
     * Not the euclidean distance between centres: a long word and a short one at the same
     * distance from the finger would rank by length instead of by nearness.
     */
    private fun gap(box: LinkBox, x: Float, y: Float): Float {
        val dx = box.horizontalGap(x)
        val dy = box.verticalGap(y)
        return if (dx == 0f) dy else if (dy == 0f) dx else kotlin.math.hypot(dx, dy)
    }

    /**
     * How far a tap may be from a word and still mean it, **as a fraction of the line height**.
     *
     * ⚠️ **Relative to the text and not a fixed dp value**, which is the only way it survives the
     * text-scale setting: with `TextScale.LARGE` a fixed radius would stop covering the gap
     * between words, and with `SMALL` it would swallow two of them.
     *
     * 0.75 of a line: measured on this repo's typography, a line of `labelSmall` at 234 dp is
     * about 14 dp tall, so the radius is ~10 dp -- roughly one character. Wider than that and a
     * tap in the gap between two words stops having an obvious answer, which is precisely the
     * case this cannot get right.
     */
    const val RADIUS_IN_LINES: Float = 0.75f

    /**
     * The radius in pixels for a given line height.
     *
     * ⚠️ **[openedUp] divides the measured height back, and that is not bookkeeping.** A text
     * that has links lays out with [LINE_HEIGHT_OVER_NATURAL] times the leading it needs, so its
     * lines really are that much taller -- and feeding that straight in would widen the
     * HORIZONTAL snap by the same factor, from about one character to two. That is precisely the
     * case [linkAt] documents as the one it cannot get right: with two links side by side, a
     * wider radius reaches the wrong one with more confidence. The extra leading is meant to
     * widen the vertical band a tap lands in and nothing else.
     */
    fun radiusFor(lineHeightPx: Float, openedUp: Boolean = false): Float {
        val natural = if (openedUp) abs(lineHeightPx) / LINE_HEIGHT_OVER_NATURAL else abs(lineHeightPx)
        return natural * RADIUS_IN_LINES
    }
}

/**
 * How tall a line becomes in a text that HAS links, as a multiple of its own font size.
 *
 * It is the cheap half of the geometry [GlossTap] exists to work around: the glyph is ~14 dp tall
 * against a 48 dp minimum, and opening up the leading widens the band a tap resolves inside
 * without changing a single glyph. It is applied **only where there are links**, so a gloss with
 * none keeps all 3--4 of its visible lines; one with links drops to about 2, which on 192 dp is
 * the real price and the reason this is not simply the screen's line height.
 *
 * ⚠️ **A multiple of the FONT SIZE and not of the style's declared line height.** Multiplying the
 * line height was the first version and it is measurably wrong: a style's `lineHeight` can sit
 * under what the font actually lays out, and then doubling it changes nothing at all -- under
 * Robolectric a line of `bodyMedium` comes out 36 px and twice its declared line height is less
 * than that. The font size is the one number that is always present and always the glyph's.
 *
 * 2.8 is about twice a typical typography's own ratio of ~1.4. ⚠️ **The resulting dp are NOT
 * verified by the gate**: Robolectric lays text out with stub font metrics, so the unit tests can
 * only see that a gloss with links got taller than one without. How tall it looks is an emulator
 * question.
 */
internal const val LINE_HEIGHT_IN_FONTS: Float = 2.8f

/**
 * How much taller than natural an opened-up line is, for the purpose of undoing it.
 *
 * ⚠️ **It exists so that [GlossTap.radiusFor] can divide the measured height back**, and it is
 * the approximation in this design: the true ratio is [LINE_HEIGHT_IN_FONTS] over whatever the
 * font lays out naturally, which is only known after layout and differs per typography. Two is
 * the intended doubling. Getting it wrong does not break anything visible -- it moves the
 * horizontal snap a few dp -- which is exactly why it is written down instead of inferred.
 */
internal const val LINE_HEIGHT_OVER_NATURAL: Float = 2f
