package cl.fadiaz.dictionary.data

import cl.fadiaz.dictionary.core.EntrySummary
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The word-of-the-day policy, entirely on the JVM because the date arrives as a parameter.
 *
 * What it defends is not "that a word comes out" but the two things that make it usable: that it
 * is **the same all day** --if it changes on recomposition it stops being of the day and you
 * cannot show it to anyone-- and that it is **not a proper noun or an obscure term**, which is
 * what the naive version returned: measured on the real packs, ten consecutive days gave
 * *Eyaralar, piscigranja, Ynda* and *Voorschoten, Negerhollands, nonparaxiality*.
 */
class WordOfTheDayTest {

    /** A fake pack where `rank` and `pos` are decided by a function of the id. */
    private fun pack(
        rank: (Long) -> Int = { 900 },
        pos: (Long) -> String? = { "noun" },
    ): suspend (Long) -> EntrySummary? = { id ->
        EntrySummary(entryId = id, headword = "palabra$id", partOfSpeech = pos(id), rank = rank(id))
    }

    private suspend fun pick(
        date: String = "2026-09-18",
        packId: String = "es-def",
        entryCount: Int = 1000,
        read: suspend (Long) -> EntrySummary? = pack(),
        candidates: Int = WordOfTheDay.CANDIDATES,
    ) = WordOfTheDay.pick(date, packId, entryCount, read, candidates)

    @Test
    fun theSameDateAlwaysGivesTheSameWord() = runTest {
        // This is the property that makes it "of the day": if it changed on recomposition, you
        // could not show it to anyone or come back to it.
        val first = pick()
        assertNotNull(first)
        repeat(5) { assertEquals(first, pick()) }
    }

    @Test
    fun twoDifferentDatesGiveDifferentWords() = runTest {
        val days = (1..20).map { pick(date = "2026-09-%02d".format(it))?.entryId }
        // It does not require all 20 to differ --a collision over 1000 entries is expected--
        // but it does require them not to be always the same, which is what a misused hash
        // looks like.
        assertTrue(days.toSet().size > 15, "demasiadas repeticiones entre dias: $days")
    }

    @Test
    fun twoDifferentPacksGiveDifferentWordsOnTheSameDay() = runTest {
        // If the seed ignored the pack, switching language would show the entry with the same
        // id, which in another dictionary is an unrelated word.
        val es = pick(packId = "es-def")?.entryId
        val en = pick(packId = "en-def")?.entryId
        assertTrue(es != en, "la semilla no esta mirando el packId: los dos dieron $es")
    }

    @Test
    fun itNeverPicksAProperNoun() = runTest {
        // "Ynda", "Voorschoten", "Ivanivka": these are what the naive version returned.
        val picked = pick(read = pack(pos = { id -> if (id % 5L == 0L) "noun" else "name" }))
        assertNotNull(picked)
        assertEquals("noun", picked.partOfSpeech)
    }

    @Test
    fun itPicksTheLowestRankAmongTheCandidates() = runTest {
        // Lower rank = richer page = a word people know (D-067). There is no per-language
        // threshold to tune: it samples and the best of the sample wins.
        val picked = pick(read = pack(rank = { id -> if (id % 7L == 0L) 880 else 995 }))
        assertNotNull(picked)
        assertEquals(880, picked.rank)
    }

    @Test
    fun itNeverPicksAProperNoun_inEitherVocabulary() = runTest {
        // The real kaikki packs say "name"; the toy one says "proper noun". Excluding only one
        // lets proper nouns through in the other, and a single-vocabulary fixture cannot see it.
        for (comoSeLlame in listOf("name", "proper noun")) {
            // The proper noun has the BEST rank: without excluding it, it always wins, so the
            // test cannot pass by accident.
            val picked = pick(
                read = pack(
                    pos = { id -> if (id % 5L == 0L) "noun" else comoSeLlame },
                    rank = { id -> if (id % 5L == 0L) 900 else 100 },
                ),
            )
            assertNotNull(picked)
            assertEquals("noun", picked.partOfSpeech, "dejo pasar un '$comoSeLlame'")
        }
    }

    @Test
    fun itNeverPicksAnAffixOrAnAbbreviation() = runTest {
        // "-ito" or "EE. UU." are not words anyone wants to learn today.
        val picked = pick(
            read = pack(
                pos = { id -> if (id % 6L == 0L) "noun" else "suffix" },
                rank = { id -> if (id % 6L == 0L) 900 else 100 },
            ),
        )
        assertNotNull(picked)
        assertEquals("noun", picked.partOfSpeech)
    }

    @Test
    fun theDayDecidesTheCategorySoTheyAreNotAllAlike() = runTest {
        // The bug this fixes was MEASURED on the real pack: 28 consecutive days gave 28 verbs,
        // because in Spanish the verb pages are the richest and `rank` measures richness
        // (D-067). Rotating the target category per day, the same sample gives noun 12, verb 8,
        // adj 7, adv 1.
        // The verbs have the best rank, just like in the real Spanish pack. Without rotation,
        // all 28 days give a verb.
        val partsOfSpeech = (1..28).map { day ->
            pick(
                date = "2026-10-%02d".format(day),
                read = pack(
                    pos = { id -> listOf("noun", "verb", "adj", "adv")[(id % 4L).toInt()] },
                    rank = { id -> if (id % 4L == 1L) 800 else 900 },
                ),
            )?.partOfSpeech
        }.toSet()
        assertTrue(partsOfSpeech.size >= 3, "salieron casi siempre de la misma categoria: $partsOfSpeech")
    }

    @Test
    fun withNobodyFromTheDaysCategoryItFallsBackToTheBest() = runTest {
        // A pack with no adverbs cannot be left without a word of the day on adverb day.
        val picked = pick(read = pack(pos = { "noun" }, rank = { id -> 900 + (id % 5L).toInt() }))
        assertNotNull(picked)
        assertEquals("noun", picked.partOfSpeech)
        assertEquals(900, picked.rank)
    }

    @Test
    fun ifAllAreProperNounsItStillReturnsTheBest() = runTest {
        // A hole in the screen is worse than a proper noun. And it still has to be
        // deterministic down this path too.
        val read = pack(pos = { "name" }, rank = { id -> 990 + (id % 7L).toInt() })
        val picked = pick(read = read)
        assertNotNull(picked)
        assertEquals("name", picked.partOfSpeech)
        assertEquals(990, picked.rank)
        assertEquals(picked, pick(read = read))
    }

    @Test
    fun anEmptyPackHasNoWordOfTheDay() = runTest {
        assertNull(pick(entryCount = 0))
    }

    @Test
    fun theRequestedIdAlwaysLandsInsideThePack() = runTest {
        // The ids in `entry` are dense, 1..entry_count. An out-of-range id would be a silently
        // empty screen, which is the class of bug this repo hunts.
        val pedidos = mutableListOf<Long>()
        repeat(40) { day ->
            WordOfTheDay.pick(
                date = "2026-11-%02d".format(day + 1),
                packId = "es-def",
                entryCount = 17,
                read = { id -> pedidos += id; EntrySummary(id, "p$id", "noun", 900) },
            )
        }
        assertTrue(pedidos.isNotEmpty())
        assertTrue(pedidos.all { it in 1L..17L }, "ids fuera de rango: ${pedidos.filter { it !in 1L..17L }}")
    }

    @Test
    fun itReadsNoMoreThanItIsAllowedTo() = runTest {
        // The cost is exactly `candidates` single-row reads: bounded and predictable, which is
        // what lets it sit on the home screen without a second thought.
        var reads = 0
        pick(
            read = { id -> reads++; EntrySummary(id, "p$id", "noun", 999) },
            candidates = 10,
        )
        assertEquals(10, reads)
    }
}
