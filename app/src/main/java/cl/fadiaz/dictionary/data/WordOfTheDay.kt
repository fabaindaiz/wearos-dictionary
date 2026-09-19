package cl.fadiaz.dictionary.data

import cl.fadiaz.dictionary.core.EntrySummary

/**
 * Which word is shown today.
 *
 * DETERMINISTIC BY DATE, AND WHY THAT MATTERS
 *
 * The same date and the same pack always give the same word: it changes on its own at midnight,
 * nothing has to be stored, there is no network, no WorkManager and therefore **no battery to
 * pay**. The date arrives as a parameter --never a system clock in here-- and that is why this
 * whole policy runs on the JVM, inside the gate (D-072).
 *
 * WHY PICKING AT RANDOM IS NOT ENOUGH, MEASURED
 *
 * The `id` values in `entry` are dense (1..entry_count, verified on both real packs), so a
 * random id is a valid entry and costs one primary-key read. But what comes out is useless: ten
 * consecutive days gave *Eyaralar, piscigranja, Ynda, desquiciador* in Spanish and *Voorschoten,
 * Negerhollands, nonparaxiality* in English. Proper nouns and terms nobody knows.
 *
 * The cause is that `rank` is **flattened**: median 992 against a maximum of 997, with every
 * entry below 3000. It only discriminates in the tail --the best are "hacer, venir, salir,
 * correr" and "water, woman, take, break"-- and proper nouns with rich pages sneak in there,
 * which is what `rank` actually measures (D-067): `Ivanivka` has rank 529.
 *
 * Hence one candidate is not picked but **the best of [CANDIDATES]**, skipping proper nouns. It
 * costs [CANDIDATES] single-row primary-key reads --nothing, and the home screen is built once--
 * and above all it **needs no per-language threshold**: the first version used one, and the good
 * value was measured at 912 in Spanish and 978 in English. A third language would have needed
 * another hand-picked number, and that is exactly the kind of constant that rots in silence when
 * nobody measures it again.
 */
internal object WordOfTheDay {

    /**
     * How many candidates are tried before keeping the best one.
     *
     * 32 and not 8 because `rank` is flattened: with few samples nearly all of them land on the
     * median and the word of the day goes back to being obscure. 32 single-row reads cost
     * nothing and the home screen is built only once.
     */
    const val CANDIDATES: Int = 32

    /**
     * The `pos` values that are never the word of the day.
     *
     * **This is NOT the main mechanism any more: it is defence in depth.** Since D-116 the real
     * packs are built without proper nouns, so in `es-def-wikc` and `en-def-wikt` this filter
     * discards nothing. It stays anyway, and removing it would be a mistake, for three reasons:
     *
     *  - **a pack is not updated when the app is.** Packs are separate artifacts, and the watch
     *    may have one installed that was built before D-116. Without the filter, the word of the
     *    day breaks there, in silence;
     *  - **the toy pack keeps proper nouns on purpose** (`sources/toy.py`) and is the only live
     *    fixture for this branch;
     *  - affixes and abbreviations go for a different reason and the builder does **not** strip
     *    them: "-ito" or "EE. UU." are not words anyone wants to learn today. So this set is not
     *    a subset of the pack's pruning.
     *
     * BOTH names for the same concept are here --kaikki packs say `name`, the toy one says
     * `proper noun`-- and that is not redundancy: excluding only one lets proper nouns through
     * in the other vocabulary, and a single-pack fixture does not show it. `verify_pack.py`
     * checks both for the same reason.
     */
    private val EXCLUDED_POS = setOf(
        "name", "proper noun",
        "prefix", "suffix", "abbrev", "num",
    )

    /**
     * The part of speech preferred on each day.
     *
     * It exists because of a MEASURED bias on the real pack: without it, 28 consecutive days
     * gave **28 verbs** in Spanish. It is not that verbs are over-represented --the pack is
     * 29.2 % nouns against 28.5 % verbs, nearly tied-- but that `rank` measures page richness
     * (D-067) and in Spanish the verb pages are the richest because they carry the conjugations.
     * Rotating the category, the same sample gives 12 nouns, 8 verbs, 7 adjectives, 1 adverb.
     *
     * It is a **preference, not a filter**: a pack with no adverbs cannot be left without a word
     * of the day on the day adverbs come up.
     */
    private val CATEGORY_ROTATION = listOf("noun", "verb", "adj", "adv")

    /** A pass index that cannot collide with the candidates' own (0 until `candidates`). */
    private const val CATEGORY_PASS = -1

    /**
     * Today's entry, or null if the pack is empty.
     *
     * Returns the **lowest rank** --most common-- among the candidates that are not proper nouns.
     * If they all were, it still returns the best one: a hole in the screen is worse than an
     * obscure word.
     */
    suspend fun pick(
        date: String,
        packId: String,
        entryCount: Int,
        read: suspend (Long) -> EntrySummary?,
        candidates: Int = CANDIDATES,
    ): EntrySummary? {
        if (entryCount <= 0) return null

        val categoryOfTheDay = CATEGORY_ROTATION[
            positiveModulo(
                seed(date, packId, CATEGORY_PASS),
                CATEGORY_ROTATION.size.toLong(),
            ).toInt(),
        ]

        var bestInCategory: EntrySummary? = null
        var best: EntrySummary? = null
        var bestEvenIfProperNoun: EntrySummary? = null
        for (pass in 0 until candidates) {
            // The ids are dense and start at 1, so the modulus always lands on an entry that
            // exists. If they ever stopped being dense, `read` returns null and we move on.
            val id = 1L + positiveModulo(seed(date, packId, pass), entryCount.toLong())
            val candidate = read(id) ?: continue

            val previousAny = bestEvenIfProperNoun
            if (previousAny == null || candidate.rank < previousAny.rank) {
                bestEvenIfProperNoun = candidate
            }
            if (isExcluded(candidate.partOfSpeech)) continue
            // Strict, not `<=`: on a tie the first one wins, so the result stays deterministic
            // when several candidates share a rank --which with this distribution happens
            // often--.
            val previous = best
            if (previous == null || candidate.rank < previous.rank) best = candidate

            if (candidate.partOfSpeech == categoryOfTheDay) {
                val previousInCategory = bestInCategory
                if (previousInCategory == null || candidate.rank < previousInCategory.rank) {
                    bestInCategory = candidate
                }
            }
        }
        // The order of the three safety nets: the day's category, any valid one, and as a last
        // resort an excluded one. A hole in the screen is worse than all three.
        return bestInCategory ?: best ?: bestEvenIfProperNoun
    }

    private fun isExcluded(pos: String?): Boolean = pos != null && pos in EXCLUDED_POS

    /**
     * FNV-1a over (date, pack, pass) with a splitmix64-style final mixer.
     *
     * Written by hand and not with `String.hashCode()` or `MessageDigest`: the first guarantees
     * no stability across runtime versions --and a word of the day that changes when Android is
     * updated stops being of the day-- and the second is a platform API for something that does
     * not need to be cryptographic.
     */
    private fun seed(date: String, packId: String, pass: Int): Long {
        var h = -0x340d631b7bdddcdbL // FNV-1a offset basis, 64 bits
        for (character in date) h = (h xor character.code.toLong()) * PRIME
        h = (h xor SEPARATOR) * PRIME
        for (character in packId) h = (h xor character.code.toLong()) * PRIME
        h = (h xor pass.toLong()) * PRIME

        // Without this mixing, consecutive passes give neighbouring ids and the rejection would
        // walk a contiguous strip of the dictionary instead of sampling all of it.
        var z = h
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    private fun positiveModulo(value: Long, modulus: Long): Long {
        val remainder = value % modulus
        return if (remainder < 0) remainder + modulus else remainder
    }

    private const val PRIME = 0x100000001b3L
    private const val SEPARATOR = 0x7CL // '|', so ("ab","c") and ("a","bc") cannot collide
}
