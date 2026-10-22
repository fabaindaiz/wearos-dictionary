package cl.fadiaz.dictionary.core

import kotlin.random.Random

/**
 * How rare a word to discover is. The codes are the pack's (`sources/discover.py`), and renumbering
 * one makes every installed pack draw from the wrong tier with no error.
 *
 * What each one means, and why *rare* is not just *less frequent*: `docs/roadmap.md`
 * i-a2f271-ec624b and d-a2f271-a3033f.
 */
enum class DiscoverTier(val code: Int) {
    COMMON(0),
    RARE(1),
    CURIOUS(2),
    ;

    companion object {
        fun of(code: Int): DiscoverTier? = entries.firstOrNull { it.code == code }
    }
}

/**
 * The parts of speech a tier holds. Adverbs stay out on purpose: most are the adjective plus
 * *-mente* / *-ly*, the same word to learn twice.
 */
enum class DiscoverPos(val code: Int, val partOfSpeech: String) {
    NOUN(0, "noun"),
    VERB(1, "verb"),
    ADJECTIVE(2, "adj"),
    ;

    companion object {
        fun of(code: Int): DiscoverPos? = entries.firstOrNull { it.code == code }
    }
}

/** One `(tier, pos)` bucket of a pack's `discover` table. */
data class DiscoverBucket(val tier: DiscoverTier, val pos: DiscoverPos)

/** One row of the table: the bucket and its `seq`. What a session remembers having shown. */
data class DiscoverPick(val bucket: DiscoverBucket, val seq: Int)

/** A reload: the rows to read, and whether the pool ran out and started over to fill it. */
data class DiscoverDraw(val picks: List<DiscoverPick>, val restarted: Boolean) {

    companion object {

        /** Asked for: *"una cantidad predefinida como 20"*. */
        const val SIZE: Int = 20

        /**
         * Which rows a reload reads.
         *
         * - **Weighted by pool size**, not by bucket: with *all* parts of speech a uniform choice
         *   of bucket would make verbs half of every list in a pool that is 15 % verbs.
         * - **No word seen this session comes back while others remain** -- the owner's choice
         *   on 2026-10-21, kept in memory by the caller and forgotten when the app closes.
         * - **An exhausted pool starts over silently**: what is left comes first, and the rest
         *   is drawn as if nothing had been seen. Never a short list while the pool holds more,
         *   never a repeat inside one draw. [restarted] tells the caller to forget what it had
         *   seen of these buckets.
         *
         * `pools` is the pack's bucket sizes; a bucket it does not hold is an empty draw, never a
         * neighbouring bucket. `pos = null` is *every part of speech*.
         */
        fun draw(
            pools: Map<DiscoverBucket, Int>,
            tier: DiscoverTier,
            pos: DiscoverPos?,
            seen: Set<DiscoverPick>,
            random: Random,
            size: Int = SIZE,
        ): DiscoverDraw {
            val buckets = pools.filter { (bucket, count) ->
                count > 0 && bucket.tier == tier && (pos == null || bucket.pos == pos)
            }.toList()
            val total = buckets.sumOf { it.second }
            if (total == 0) return DiscoverDraw(emptyList(), restarted = false)

            val wanted = minOf(size, total)
            val unseen = total - seen.count { pick ->
                buckets.any { it.first == pick.bucket } && pick.seq < (pools[pick.bucket] ?: 0)
            }
            val picks = LinkedHashSet<DiscoverPick>(wanted * 2)
            pickInto(picks, buckets, total, unseen, minOf(wanted, unseen), random) { it !in seen }
            val restarted = picks.size < wanted
            if (restarted) pickInto(picks, buckets, total, total, wanted, random) { true }
            return DiscoverDraw(picks.toList(), restarted)
        }

        /**
         * Fills `into` up to `target` with random rows that `admits`.
         *
         * ⚠️ **Rejection while the admissible rows are many, enumeration when they are few.**
         * Rejection is what a 16,000-word pool wants -- twenty reads of a random number -- and
         * what a nearly exhausted pool must not do: with one admissible row left it would spin.
         * At [ENUMERATE_BELOW] admissible rows or fewer they are listed and shuffled instead, and
         * what decides is the ADMISSIBLE count, not the pool's: a 16,000-word pool with ten
         * words unseen is the nearly exhausted case.
         */
        private fun pickInto(
            into: LinkedHashSet<DiscoverPick>,
            buckets: List<Pair<DiscoverBucket, Int>>,
            total: Int,
            admissible: Int,
            target: Int,
            random: Random,
            admits: (DiscoverPick) -> Boolean,
        ) {
            if (into.size >= target) return
            if (admissible <= ENUMERATE_BELOW) {
                val all = buckets.flatMap { (bucket, count) ->
                    (0 until count).map { DiscoverPick(bucket, it) }
                }.filter { it !in into && admits(it) }.shuffled(random)
                for (pick in all) {
                    if (into.size >= target) break
                    into += pick
                }
                return
            }
            while (into.size < target) {
                var k = random.nextInt(total)
                var chosen: DiscoverBucket? = null
                for ((bucket, count) in buckets) {
                    if (k < count) {
                        chosen = bucket
                        break
                    }
                    k -= count
                }
                val pick = DiscoverPick(chosen ?: continue, k)
                if (pick !in into && admits(pick)) into += pick
            }
        }

        /** Below this many rows, a draw enumerates instead of rejecting. See [pickInto]. */
        private const val ENUMERATE_BELOW = 400
    }
}
