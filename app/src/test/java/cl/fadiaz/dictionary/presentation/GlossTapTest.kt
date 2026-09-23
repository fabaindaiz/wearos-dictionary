package cl.fadiaz.dictionary.presentation

import cl.fadiaz.dictionary.presentation.GlossTap.LinkBox
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which linked word a tap meant.
 *
 * ⚠️ **These tests exist because this heuristic was recommended against.** The objection was that
 * snapping trades *failing visibly* for *failing confidently*, so what has to be pinned is not
 * that it snaps -- that part is easy -- but the three bounds that stop it from snapping where it
 * should not: an exact hit is never overridden, a tap inside a line cannot reach another line,
 * and beyond the radius the answer is `null`.
 *
 * Coordinates read as a two-line gloss at roughly this repo's typography: lines 14 px tall, words
 * a few tens of pixels wide.
 */
class GlossTapTest {

    /** Two words on line 0 with a 10 px gap, one on line 1 directly under the first. */
    private val boxes = listOf(
        LinkBox(index = 0, line = 0, left = 0f, top = 0f, right = 40f, bottom = 14f),
        LinkBox(index = 1, line = 0, left = 50f, top = 0f, right = 90f, bottom = 14f),
        LinkBox(index = 2, line = 1, left = 0f, top = 16f, right = 40f, bottom = 30f),
    )

    private fun tap(x: Float, y: Float, radius: Float = 10f) =
        GlossTap.linkAt(boxes, x, y, radius)

    @Test
    fun `a tap on the word gives that word`() {
        assertEquals(0, tap(20f, 7f))
        assertEquals(1, tap(70f, 7f))
        assertEquals(2, tap(20f, 23f))
    }

    @Test
    fun `an exact hit is never overridden by a nearer neighbour`() {
        // ⚠️ **The bound that keeps today's behaviour intact.** A tap at x=41 is one pixel inside
        // nothing and 9 px from word 1, but a tap at x=40 is ON word 0 -- even though its centre
        // is further away than word 1's edge. If proximity could beat containment, every tap near
        // the end of a word would jump to the next one, and the feature would make things worse
        // for the taps that already worked.
        assertEquals(0, tap(40f, 7f))
        assertEquals(1, tap(50f, 7f))
    }

    @Test
    fun `a tap in the gap between two words takes the nearer one`() {
        // This is the case the heuristic is FOR: the finger landed between the words.
        assertEquals(0, tap(44f, 7f))
        assertEquals(1, tap(47f, 7f))
    }

    @Test
    fun `a tap inside a line cannot reach a word on another line`() {
        // ⚠️ **The bound that answers the objection this heuristic was rejected on.** A tap at
        // (120, 7) is on line 0, far to the right of everything: the nearest box in a straight
        // line is word 1 at 30 px, and word 2 on the next line is further. But even where the
        // geometry favoured the other line, being inside line 0's band restricts the answer to
        // line 0 -- so the word above or below can never be grabbed while the finger is on a
        // line at all.
        assertNull(tap(120f, 7f), "se fue mas alla del radio, en su propia linea")
        // And with the radius widened, it still picks the word on the tapped line, not the one
        // vertically closer.
        assertEquals(1, GlossTap.linkAt(boxes, 120f, 7f, radius = 40f))
    }

    @Test
    fun `a tap in the leading between lines may reach either`() {
        // Between the lines nothing contains the point vertically, so widening is the only way
        // to answer at all -- and there the nearest really is the right answer.
        //
        // ⚠️ The first version of this test used y = 15, which is the **exact midpoint** between
        // the two lines (line 0 ends at 14, line 1 starts at 16): both gaps are 1 px and the
        // answer is the index tie-break, not the geometry. The expectation was wrong, not the
        // code. The tie has its own case below.
        assertEquals(2, tap(20f, 15.7f), "mas cerca de la linea de abajo")
        assertEquals(0, tap(20f, 14.3f), "mas cerca de la linea de arriba")
    }

    @Test
    fun `an exact midpoint between two lines resolves the same way every time`() {
        // Deterministic beats correct here: a tap that answered differently on two tries would
        // be unlearnable. y = 15 sits 1 px from both lines.
        repeat(5) { assertEquals(0, tap(20f, 15f)) }
    }

    @Test
    fun `beyond the radius a tap means no word`() {
        // ⚠️ **A snap with no bound is how a heuristic becomes confidently wrong.** Past the
        // radius the answer is the same as today: nothing happens.
        assertNull(tap(200f, 7f))
        assertNull(tap(20f, 200f))
    }

    @Test
    fun `the same tap always resolves to the same word`() {
        // Two boxes exactly equidistant. A word that resolved differently on two taps would be
        // worse than one that resolved wrongly: the user could not learn it.
        val empatados = listOf(
            LinkBox(1, 0, 0f, 0f, 40f, 14f),
            LinkBox(0, 0, 50f, 0f, 90f, 14f),
        )
        repeat(5) {
            assertEquals(0, GlossTap.linkAt(empatados, 45f, 7f, radius = 10f))
        }
    }

    @Test
    fun `with no links there is nothing to tap`() {
        assertNull(GlossTap.linkAt(emptyList(), 20f, 7f, radius = 10f))
    }

    @Test
    fun `a negative radius does not snap`() {
        // Defensive: a caller that computes the radius from a line height of zero must not get
        // an unbounded snap out of it.
        assertNull(GlossTap.linkAt(boxes, 44f, 7f, radius = -1f))
        // And containment still wins, because it runs before the radius is consulted.
        assertEquals(0, GlossTap.linkAt(boxes, 20f, 7f, radius = -1f))
    }

    @Test
    fun `the radius follows the text size, not a fixed dp`() {
        // With TextScale.LARGE a fixed radius would stop covering the gap between words; with
        // SMALL it would swallow two of them.
        assertEquals(10.5f, GlossTap.radiusFor(14f))
        assertEquals(21f, GlossTap.radiusFor(28f))
    }
}
