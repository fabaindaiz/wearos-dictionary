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

    /** The radius in pixels for a given line height. */
    fun radiusFor(lineHeightPx: Float): Float = abs(lineHeightPx) * RADIUS_IN_LINES
}
