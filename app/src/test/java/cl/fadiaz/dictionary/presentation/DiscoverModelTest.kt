package cl.fadiaz.dictionary.presentation

import cl.fadiaz.dictionary.core.DiscoverBucket
import cl.fadiaz.dictionary.core.DiscoverDraw
import cl.fadiaz.dictionary.core.DiscoverPos
import cl.fadiaz.dictionary.core.DiscoverTier
import cl.fadiaz.dictionary.core.PackKind
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * *See more words*: which pack answers for each language, and what a reload shows
 * (`docs/roadmap.md` i-a2f271-ec624b).
 *
 * The draw itself is `DiscoverDrawTest`'s, in `:dict-core`. What is tested here is the wiring --
 * the filter reaching the draw, the session memory surviving a reload, a closed pack never read --
 * because a correct function behind a wrong call site is the failure this repo has met most.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DiscoverModelTest {

    private val nouns = DiscoverBucket(DiscoverTier.COMMON, DiscoverPos.NOUN)
    private val verbs = DiscoverBucket(DiscoverTier.COMMON, DiscoverPos.VERB)
    private val rareNouns = DiscoverBucket(DiscoverTier.RARE, DiscoverPos.NOUN)
    private val pools = mapOf(nouns to 300, verbs to 300, rareNouns to 300)

    private fun TestScope.model() = DiscoverModel(this, Random(1))

    @Test
    fun withNoPackThatHasTheTableThereIsNothingToOffer() = runTest {
        val model = model()
        model.setPacks(listOf(FakeDictionary("es-full", "es", entryCount = 100)), "es")
        advanceUntilIdle()
        assertFalse(model.state.value.available)
    }

    @Test
    fun oneSectionPerLanguageTheActiveOneFirstTwentyWordsEach() = runTest {
        val model = model()
        model.setPacks(
            listOf(
                FakeDictionary("en-full", "en", entryCount = 900, discover = pools),
                FakeDictionary("es-full", "es", entryCount = 100, discover = pools),
            ),
            activeLang = "es",
        )
        model.reload()
        advanceUntilIdle()
        val sections = model.state.value.sections
        assertTrue(model.state.value.available)
        assertEquals(listOf("es", "en"), sections.map { it.lang })
        assertTrue(sections.all { it.words.size == DiscoverDraw.SIZE })
    }

    @Test
    fun eachLanguageIsAnsweredByItsLargestPackThatHasTheTable() = runTest {
        // A core with the table and a full built before it: the core answers, or the language
        // would vanish from the list for having the bigger dictionary installed.
        val core = FakeDictionary("es-core", "es", entryCount = 100, discover = pools)
        val oldFull = FakeDictionary("es-full", "es", entryCount = 9000)
        val newFull = FakeDictionary("es-main", "es", entryCount = 5000, discover = pools)
        val model = model()
        model.setPacks(listOf(core, oldFull), "es")
        model.reload()
        advanceUntilIdle()
        assertEquals(listOf("es-core"), model.state.value.sections.map { it.packId })
        model.setPacks(listOf(core, oldFull, newFull), "es")
        model.reload()
        advanceUntilIdle()
        assertEquals(listOf("es-main"), model.state.value.sections.map { it.packId })
    }

    @Test
    fun aTranslationPackIsNeverAskedEvenWithATable() = runTest {
        val bilingual = FakeDictionary(
            "es-en", "es", langs = listOf("es", "en"), kind = PackKind.BILINGUAL, discover = pools,
        )
        val model = model()
        model.setPacks(listOf(bilingual), "es")
        model.reload()
        advanceUntilIdle()
        assertFalse(model.state.value.available)
        assertTrue(bilingual.discoverReads.isEmpty())
    }

    @Test
    fun theFilterReachesTheDraw() = runTest {
        val model = model()
        model.setPacks(listOf(FakeDictionary("es-full", "es", discover = pools)), "es")
        // Verbs as well as nouns in the tier, so a lost part-of-speech clause would show.
        model.select(DiscoverTier.COMMON, DiscoverPos.VERB)
        advanceUntilIdle()
        val words = model.state.value.sections.single().words
        assertEquals(DiscoverDraw.SIZE, words.size)
        assertTrue(words.all { it.headword.startsWith("common-verb-") }, "${words.map { it.headword }}")
        assertEquals(DiscoverTier.COMMON, model.state.value.tier)
        assertEquals(DiscoverPos.VERB, model.state.value.pos)
    }

    @Test
    fun everyPartOfSpeechMeansBothBucketsOfTheTier() = runTest {
        val model = model()
        model.setPacks(listOf(FakeDictionary("es-full", "es", discover = pools)), "es")
        model.select(DiscoverTier.COMMON, null)
        advanceUntilIdle()
        val words = model.state.value.sections.single().words.map { it.headword }
        assertTrue(words.all { it.startsWith("common-") }, "$words")
        assertTrue(words.any { it.startsWith("common-noun-") } && words.any { it.startsWith("common-verb-") })
    }

    @Test
    fun aReloadShowsNoWordAlreadyShownThisSession() = runTest {
        val model = model()
        model.setPacks(listOf(FakeDictionary("es-full", "es", discover = pools)), "es")
        model.reload()
        advanceUntilIdle()
        val first = model.state.value.sections.single().words.map { it.entryId }.toSet()
        model.reload()
        advanceUntilIdle()
        val second = model.state.value.sections.single().words.map { it.entryId }.toSet()
        assertTrue((first intersect second).isEmpty())
    }

    @Test
    fun anExhaustedPoolStartsOverAndTheListStaysFull() = runTest {
        val small = mapOf(nouns to 25)
        val model = model()
        model.setPacks(listOf(FakeDictionary("es-full", "es", discover = small)), "es")
        model.select(DiscoverTier.COMMON, DiscoverPos.NOUN)
        advanceUntilIdle()
        repeat(3) {
            model.reload()
            advanceUntilIdle()
            assertEquals(DiscoverDraw.SIZE, model.state.value.sections.single().words.size)
        }
    }

    @Test
    fun aBucketThePackDoesNotHoldIsAnEmptySectionAndNotAnotherBucket() = runTest {
        val model = model()
        model.setPacks(listOf(FakeDictionary("es-full", "es", discover = pools)), "es")
        model.select(DiscoverTier.CURIOUS, DiscoverPos.ADJECTIVE)
        advanceUntilIdle()
        assertEquals(emptyList(), model.state.value.sections.single().words)
    }

    @Test
    fun packsThatGoAwayAreNeverReadAgain() = runTest {
        // Deleting a dictionary closes every connection first (D-104): a reload in flight must not
        // land on a closed one.
        val pack = FakeDictionary("es-full", "es", discover = pools)
        val model = model()
        model.setPacks(listOf(pack), "es")
        model.reload()
        advanceUntilIdle()
        val reads = pack.discoverReads.size
        model.setPacks(emptyList(), null)
        model.reload()
        advanceUntilIdle()
        assertEquals(reads, pack.discoverReads.size)
        assertFalse(model.state.value.available)
        assertTrue(model.state.value.sections.isEmpty())
    }
}
