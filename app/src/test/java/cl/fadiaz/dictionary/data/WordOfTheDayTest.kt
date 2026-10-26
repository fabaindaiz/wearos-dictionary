package cl.fadiaz.dictionary.data

import cl.fadiaz.dictionary.core.DiscoverBucket
import cl.fadiaz.dictionary.core.DiscoverPick
import cl.fadiaz.dictionary.core.DiscoverPos
import cl.fadiaz.dictionary.core.DiscoverTier
import cl.fadiaz.dictionary.core.EntrySummary
import cl.fadiaz.dictionary.core.RankBasis
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
        rankBasis: RankBasis = RankBasis.PAGE_RICHNESS,
    ) = WordOfTheDay.pick(date, packId, entryCount, read, candidates, rankBasis)

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
        // ⚠️ **Sigue valiendo, pero SOLO para packs de riqueza de pagina** --el basis por
        // defecto de este helper--. Un pack no se actualiza cuando la app se actualiza, asi que
        // el reloj puede tener uno anterior a D-185; ahi el sesgo de verbos es real y la
        // rotacion es la unica defensa. Ver el test de `RankBasis.FREQUENCY` mas abajo.
        //
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
    fun conRANK_DE_FRECUENCIA_la_categoria_del_dia_NO_pisa_a_la_palabra_comun() = runTest {
        // ⚠️ **La rotacion de categorias se apaga cuando el `rank` es frecuencia real, y eso
        // deshace una regla que este archivo defendia.** Existia porque `rank` media riqueza de
        // pagina y en español las paginas de verbo son las mas ricas: 28 dias daban 28 verbos.
        // D-185 cambio el prior, asi que **ese sesgo ya no existe** -- y la rotacion paso de
        // arreglar algo a romperlo: fuerza la mejor palabra DE LA CATEGORIA DEL DIA por encima
        // de una mucho mas comun de otra.
        //
        // Medido sobre los packs reales, 112 dias: con rotacion, **79 % (es) y 49 % (en)** de
        // los dias caen en la banda con señal; sin ella, **99 % y 82 %**. Y el sesgo no vuelve:
        // 58 sustantivos / 20 adjetivos / 18 verbos / 7 adverbios en español.
        //
        // Aca: los sustantivos son comunes (rank 100, banda con señal) y todo lo demas es raro
        // (rank 900, banda sin señal). Con frecuencia real tiene que ganar el comun **siempre**,
        // sea o no el dia de su categoria.
        val categorias = (1..28).map { day ->
            pick(
                date = "2026-10-%02d".format(day),
                read = pack(
                    pos = { id -> listOf("noun", "verb", "adj", "adv")[(id % 4L).toInt()] },
                    // ⚠️ 200 y no 100: `RANK_FLOOR` descarta lo mas comun, y lo que este test
                    // afirma es OTRA cosa --que la categoria del dia no pisa a la palabra comun--
                    // asi que el fixture se mueve por encima del piso en vez de apagarlo. El piso
                    // tiene sus propios tests abajo.
                    rank = { id -> if (id % 4L == 0L) 200 else 900 },
                ),
                rankBasis = RankBasis.FREQUENCY,
            )?.partOfSpeech
        }
        assertEquals(
            List(28) { "noun" },
            categorias,
            "con rank de frecuencia manda la palabra comun, no la categoria del dia",
        )
    }

    // --- El piso de rank -----------------------------------------------------------------

    @Test
    fun conFRECUENCIA_la_palabra_mas_comun_NO_es_la_del_dia() = runTest {
        // ⚠️ **El defecto que esto cierra, medido sobre los cuatro packs reales.** "La mas comun
        // de las candidatas" es una palabra funcional en cualquier idioma, y en un nucleo --que
        // ES las 8.000 mas frecuentes-- lo es siempre: `en-core` daba `'m`, `TOLD`, `a`, `ah`.
        // Sobre 112 dias simulados, los dias que caian por debajo del piso eran el 84 % en
        // es-core, el 94 % en en-core, el 41 % en es-full y el 16 % en en-full. Con el piso, 0 %
        // en los cuatro.
        //
        // Aca: una palabra funcional muy comun (rank 10) contra una ensenable (rank 400).
        val picked = pick(
            read = pack(rank = { id -> if (id % 7L == 0L) 10 else 400 }),
            rankBasis = RankBasis.FREQUENCY,
        )
        assertNotNull(picked)
        // ⚠️ **Contra un LITERAL y no contra `RANK_FLOOR`.** La primera version de este aserto
        // decia `picked.rank >= WordOfTheDay.RANK_FLOOR`, y una sonda lo delato: poner la
        // constante en 0 --o sea, quitar el piso entero-- dejaba el test en verde, porque el
        // aserto mueve el poste junto con lo que deberia vigilar. Un test que se compara contra
        // la constante que prueba no prueba la constante.
        assertEquals(
            400, picked.rank,
            "gano la palabra funcional (rank 10) en vez de la ensenable (rank 400)",
        )
    }

    @Test
    fun elPISO_no_se_aplica_a_un_pack_de_RIQUEZA_DE_PAGINA() = runTest {
        // ⚠️ **El piso es una posicion en la escala Zipf, y en un pack que calibra por riqueza de
        // pagina esa escala no existe**: un rank de 150 no significa "comun", significa nada. Si
        // se aplicara igual, descartaria al azar. Es el mismo guardia que ya usa la rotacion de
        // categorias, y por el mismo motivo.
        val picked = pick(
            read = pack(rank = { id -> if (id % 7L == 0L) 10 else 400 }),
            rankBasis = RankBasis.PAGE_RICHNESS,
        )
        assertNotNull(picked)
        assertEquals(10, picked.rank, "sin frecuencia real manda el rank y punto")
    }

    @Test
    fun siTODO_cae_bajo_el_piso_igual_hay_palabra_del_dia() = runTest {
        // Un hueco en la pantalla es peor que una palabra demasiado comun: el mismo criterio que
        // la red de los nombres propios. Un pack chico --o uno cuyas 96 tiradas caigan todas en
        // la zona comun-- tiene que seguir dando algo.
        val picked = pick(
            read = pack(rank = { 5 }),
            rankBasis = RankBasis.FREQUENCY,
        )
        assertNotNull(picked, "el piso dejo la pantalla sin palabra del dia")
        assertEquals(5, picked.rank)
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

    // --- In a chosen tier (i-a2f271-96caa4) ------------------------------------------------------

    private val commonNoun = DiscoverBucket(DiscoverTier.COMMON, DiscoverPos.NOUN)
    private val commonVerb = DiscoverBucket(DiscoverTier.COMMON, DiscoverPos.VERB)
    private val rareNoun = DiscoverBucket(DiscoverTier.RARE, DiscoverPos.NOUN)
    private val curiousAdj = DiscoverBucket(DiscoverTier.CURIOUS, DiscoverPos.ADJECTIVE)
    private val everyTier =
        mapOf(commonNoun to 900, commonVerb to 100, rareNoun to 500, curiousAdj to 40)

    /** A row of the table reads back as a word naming its bucket and seq. */
    private val readRow: suspend (DiscoverPick) -> EntrySummary? = { pick ->
        EntrySummary(
            entryId = 10_000L * pick.bucket.tier.code + 1_000L * pick.bucket.pos.code + pick.seq,
            headword = "${pick.bucket.tier}-${pick.bucket.pos}-${pick.seq}",
            partOfSpeech = pick.bucket.pos.partOfSpeech,
            rank = 300,
        )
    }

    private val fallbackWord =
        EntrySummary(entryId = 7, headword = "de-siempre", partOfSpeech = "noun", rank = 300)

    private suspend fun inTier(
        tier: DiscoverTier,
        pools: Map<DiscoverBucket, Int>? = everyTier,
        date: String = "2026-10-26",
        packId: String = "es-full",
        read: suspend (DiscoverPick) -> EntrySummary? = readRow,
    ) = WordOfTheDay.pickInTier(date, packId, pools, tier, read) { fallbackWord }

    @Test
    fun aTierWordIsTheSameAllDayForTheSamePack() = runTest {
        val first = inTier(DiscoverTier.RARE)
        assertNotNull(first)
        repeat(5) { assertEquals(first, inTier(DiscoverTier.RARE)) }
        // Another pack on the same day is another draw, as the plain word of the day is.
        val packs = (1..20).map { inTier(DiscoverTier.COMMON, packId = "pack$it")?.entryId }
        assertTrue(packs.toSet().size > 15, "el packId no entra en la semilla: $packs")
    }

    @Test
    fun aTierWordChangesFromOneDayToTheNext() = runTest {
        val days = (1..20).map {
            inTier(DiscoverTier.COMMON, date = "2026-10-%02d".format(it))?.entryId
        }
        assertTrue(days.toSet().size > 15, "demasiadas repeticiones entre dias: $days")
    }

    @Test
    fun aWordFromAnotherTierNeverComesBack() = runTest {
        // ⚠️ The tier is a clause, and a lost clause returns MORE words, quietly: a rare word
        // under *common* passes any test that only asks for a word.
        for (tier in DiscoverTier.entries) {
            val picks = (1..200).map {
                WordOfTheDay.discoverPick("2026-%03d".format(it), "es-full", everyTier, tier)
            }
            assertTrue(picks.all { it != null && it.bucket.tier == tier }, "$tier: $picks")
            assertTrue(picks.filterNotNull().all { it.seq in 0 until everyTier.getValue(it.bucket) })
        }
    }

    @Test
    fun everyPartOfSpeechCountsByThePoolsSize() = runTest {
        // A uniform choice of BUCKET would give verbs half the days in a tier that is 10 % verbs.
        val verbs = (1..400).count {
            WordOfTheDay.discoverPick("d$it", "es-full", everyTier, DiscoverTier.COMMON)
                ?.bucket == commonVerb
        }
        assertTrue(verbs in 15..80, "verbos: $verbs de 400")
    }

    @Test
    fun thePickDoesNotDependOnTheOrderTheBucketsArrive() = runTest {
        // The pools come from a SQL query into a map: an iteration order is not a contract, and
        // a word of the day that changes with it is not of the day.
        val reversed = everyTier.entries.reversed().associate { it.key to it.value }
        for (day in 1..30) {
            val date = "2026-11-%02d".format(day)
            assertEquals(
                WordOfTheDay.discoverPick(date, "es-full", everyTier, DiscoverTier.COMMON),
                WordOfTheDay.discoverPick(date, "es-full", reversed, DiscoverTier.COMMON),
            )
        }
    }

    @Test
    fun aPackWithNoTableGetsTheWordOfTheDayAsBefore() = runTest {
        assertEquals(fallbackWord, inTier(DiscoverTier.RARE, pools = null))
    }

    @Test
    fun anEmptyTierGetsTheWordOfTheDayAsBefore() = runTest {
        // `en-core` holds few curious words; a pack may hold none. Never an empty word of the day.
        assertEquals(fallbackWord, inTier(DiscoverTier.CURIOUS, pools = mapOf(commonNoun to 50)))
        assertEquals(fallbackWord, inTier(DiscoverTier.RARE, pools = mapOf(rareNoun to 0)))
    }

    @Test
    fun aRowThatReadsNothingGetsTheWordOfTheDayAsBefore() = runTest {
        assertEquals(fallbackWord, inTier(DiscoverTier.COMMON, read = { null }))
    }
}
