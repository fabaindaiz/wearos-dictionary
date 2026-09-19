package cl.fadiaz.dictionary.data

import cl.fadiaz.dictionary.core.EntrySummary
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * La politica de la palabra del dia, entera en la JVM porque la fecha entra por parametro.
 *
 * Lo que defiende no es "que salga una palabra" sino las dos cosas que la hacen usable: que sea
 * **la misma todo el dia** --si cambia al recomponer, deja de ser del dia y no se la podes
 * mostrar a nadie-- y que **no sea un nombre propio ni un termino oscuro**, que es lo que la
 * version ingenua devolvia: medido sobre los packs reales, diez dias seguidos dieron *Eyaralar,
 * piscigranja, Ynda* y *Voorschoten, Negerhollands, nonparaxiality*.
 */
class WordOfTheDayTest {

    /** Un pack de mentira donde el `rank` y el `pos` los decide una funcion del id. */
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
        // Es la propiedad que la hace "del dia": si cambiara al recomponer, no se la podrias
        // mostrar a nadie ni volver a ella.
        val first = pick()
        assertNotNull(first)
        repeat(5) { assertEquals(first, pick()) }
    }

    @Test
    fun twoDifferentDatesGiveDifferentWords() = runTest {
        val days = (1..20).map { pick(date = "2026-09-%02d".format(it))?.entryId }
        // No se exige que las 20 sean distintas --una colision en 1000 entradas es esperable--
        // pero si que no sea siempre la misma, que es como se ve un hash mal usado.
        assertTrue(days.toSet().size > 15, "demasiadas repeticiones entre dias: $days")
    }

    @Test
    fun twoDifferentPacksGiveDifferentWordsOnTheSameDay() = runTest {
        // Si la semilla ignorara el pack, cambiar de idioma mostraria la entrada del mismo id,
        // que en otro diccionario es una palabra sin relacion.
        val es = pick(packId = "es-def")?.entryId
        val en = pick(packId = "en-def")?.entryId
        assertTrue(es != en, "la semilla no esta mirando el packId: los dos dieron $es")
    }

    @Test
    fun itNeverPicksAProperNoun() = runTest {
        // "Ynda", "Voorschoten", "Ivanivka": son los que devolvia la version ingenua.
        val picked = pick(read = pack(pos = { id -> if (id % 5L == 0L) "noun" else "name" }))
        assertNotNull(picked)
        assertEquals("noun", picked.partOfSpeech)
    }

    @Test
    fun itPicksTheLowestRankAmongTheCandidates() = runTest {
        // rank menor = pagina mas rica = palabra que la gente conoce (D-067). No hay umbral que
        // ajustar por idioma: se muestrea y gana la mejor del muestreo.
        val picked = pick(read = pack(rank = { id -> if (id % 7L == 0L) 880 else 995 }))
        assertNotNull(picked)
        assertEquals(880, picked.rank)
    }

    @Test
    fun itNeverPicksAProperNoun_inEitherVocabulary() = runTest {
        // Los packs reales de kaikki dicen "name"; el de juguete dice "proper noun". Excluir solo
        // uno deja pasar nombres propios en el otro, y eso no se ve con un fixture de un vocabulario.
        for (comoSeLlame in listOf("name", "proper noun")) {
            // El nombre propio tiene el MEJOR rank: sin excluirlo gana siempre, asi que el
            // test no puede pasar por casualidad.
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
        // "-ito" o "EE. UU." no son palabras que alguien quiera aprender hoy.
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
        // El bug que esto arregla estaba MEDIDO sobre el pack real: 28 dias seguidos daban 28
        // verbos, porque en espanol las paginas de verbos son las mas ricas y `rank` mide riqueza
        // (D-067). Rotando la categoria objetivo por dia, la misma muestra da noun 12, verb 8,
        // adj 7, adv 1.
        // Los verbos tienen el mejor rank, igual que en el pack español real. Sin rotacion,
        // los 28 dias dan verbo.
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
        // Un pack sin adverbios no puede quedarse sin palabra del dia el dia que toca adverbio.
        val picked = pick(read = pack(pos = { "noun" }, rank = { id -> 900 + (id % 5L).toInt() }))
        assertNotNull(picked)
        assertEquals("noun", picked.partOfSpeech)
        assertEquals(900, picked.rank)
    }

    @Test
    fun ifAllAreProperNounsItStillReturnsTheBest() = runTest {
        // Un hueco en la pantalla es peor que un nombre propio. Y tiene que seguir siendo
        // determinista tambien por este camino.
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
        // Los id de `entry` son densos, 1..entry_count. Un id fuera de rango seria una pantalla
        // vacia silenciosa, que es la clase de bug que este repo persigue.
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
        // El coste es exactamente `candidatos` lecturas de una fila: acotado y predecible, que
        // es lo que deja ponerlo en la pantalla de inicio sin pensarlo dos veces.
        var reads = 0
        pick(
            read = { id -> reads++; EntrySummary(id, "p$id", "noun", 999) },
            candidates = 10,
        )
        assertEquals(10, reads)
    }
}
