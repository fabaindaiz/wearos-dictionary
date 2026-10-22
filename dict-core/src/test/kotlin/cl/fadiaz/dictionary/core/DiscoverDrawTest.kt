package cl.fadiaz.dictionary.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The draw behind *see more words*: which `(bucket, seq)` pairs a reload reads.
 *
 * The pack holds the pools (`discover`, d-a2f271-a3033f); this decides which rows of them, and
 * every rule below is something the owner asked for on 2026-10-21 or a case a pool that small
 * makes real: `en-core`'s curious adjectives are 44 words before the gloss filters.
 */
class DiscoverDrawTest {

    private val noun = DiscoverBucket(DiscoverTier.COMMON, DiscoverPos.NOUN)
    private val verb = DiscoverBucket(DiscoverTier.COMMON, DiscoverPos.VERB)
    private val rareNoun = DiscoverBucket(DiscoverTier.RARE, DiscoverPos.NOUN)

    @Test
    fun aDrawIsTwentyDistinctWordsOfTheAskedBucket() {
        val draw = DiscoverDraw.draw(
            mapOf(noun to 500, verb to 500, rareNoun to 500),
            DiscoverTier.COMMON, DiscoverPos.NOUN, seen = emptySet(), random = Random(1),
        )
        assertEquals(DiscoverDraw.SIZE, draw.picks.size)
        assertEquals(DiscoverDraw.SIZE, draw.picks.toSet().size)
        assertTrue(draw.picks.all { it.bucket == noun })
        assertTrue(draw.picks.all { it.seq in 0 until 500 })
        assertFalse(draw.restarted)
    }

    @Test
    fun allPartsOfSpeechDrawsFromEveryBucketOfTheTierAndNoOtherTier() {
        // ⚠️ The tier is a clause, and a lost clause returns MORE, quietly: rare words under
        // *common* would pass every test that only counts the result.
        val draws = (1..50).flatMap {
            DiscoverDraw.draw(
                mapOf(noun to 900, verb to 100, rareNoun to 1000),
                DiscoverTier.COMMON, pos = null, seen = emptySet(), random = Random(it),
            ).picks
        }
        assertTrue(draws.none { it.bucket.tier != DiscoverTier.COMMON })
        val verbs = draws.count { it.bucket == verb }
        // Weighted by pool size: about 10 % verbs. A uniform choice of BUCKET would give 50 %.
        assertTrue(verbs in 50..200, "verbos: $verbs de ${draws.size}")
    }

    @Test
    fun aWordAlreadySeenThisSessionDoesNotComeBackWhileOthersRemain() {
        val pools = mapOf(noun to 60)
        val first = DiscoverDraw.draw(pools, DiscoverTier.COMMON, null, emptySet(), Random(2))
        val second = DiscoverDraw.draw(
            pools, DiscoverTier.COMMON, null, first.picks.toSet(), Random(3),
        )
        assertTrue(second.picks.none { it in first.picks })
        assertFalse(second.restarted)
    }

    @Test
    fun anExhaustedPoolStartsOverSilentlyAndSaysSo() {
        // 25 words, 20 seen: the five left come first, then the pool starts over for the rest --
        // never a draw of five, and never a repeat inside one draw.
        val pools = mapOf(noun to 25)
        val seen = (0 until 20).map { DiscoverPick(noun, it) }.toSet()
        val draw = DiscoverDraw.draw(pools, DiscoverTier.COMMON, null, seen, Random(4))
        assertEquals(DiscoverDraw.SIZE, draw.picks.size)
        assertEquals(DiscoverDraw.SIZE, draw.picks.toSet().size)
        assertTrue((20 until 25).all { DiscoverPick(noun, it) in draw.picks })
        assertTrue(draw.restarted)
    }

    @Test
    fun aPoolSmallerThanADrawReturnsItAll() {
        val draw = DiscoverDraw.draw(
            mapOf(noun to 7), DiscoverTier.COMMON, null, emptySet(), Random(5),
        )
        assertEquals((0 until 7).map { DiscoverPick(noun, it) }.toSet(), draw.picks.toSet())
        assertEquals(7, draw.picks.size)
    }

    @Test
    fun aBucketThePackDoesNotHoldIsAnEmptyDrawNotAnotherBucket() {
        val draw = DiscoverDraw.draw(
            mapOf(noun to 100), DiscoverTier.COMMON, DiscoverPos.ADJECTIVE, emptySet(), Random(6),
        )
        assertTrue(draw.picks.isEmpty())
    }

    @Test
    fun theOrderOfADrawIsNotTheOrderOfThePool() {
        // `seq` follows entry id, which follows the dump's alphabetical order: drawn in order,
        // every list would read *a…, b…, c…*.
        val draw = DiscoverDraw.draw(
            mapOf(noun to 20), DiscoverTier.COMMON, null, emptySet(), Random(7),
        )
        assertTrue(draw.picks.map { it.seq } != (0 until 20).toList())
    }

    @Test
    fun theCodesAreThePacksContract() {
        // `sources/discover.py`: COMMON, RARE, CURIOUS = 0, 1, 2 and noun, verb, adj = 0, 1, 2.
        assertEquals(listOf(0, 1, 2), DiscoverTier.entries.map { it.code })
        assertEquals(listOf(0, 1, 2), DiscoverPos.entries.map { it.code })
    }
}
