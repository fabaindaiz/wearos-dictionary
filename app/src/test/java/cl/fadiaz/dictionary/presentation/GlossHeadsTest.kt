package cl.fadiaz.dictionary.presentation

import org.junit.Test
import kotlin.test.assertEquals

/** Which of a bilingual sense's translations its gloss already says, and where. */
class GlossHeadsTest {

    private fun keys(gloss: String, vararg terms: String) =
        GlossHeads.find(gloss, terms.toList()).map { it.key }

    @Test
    fun `the head of a gloss is found before its parenthesis`() {
        val gloss = "bank (financial institution)"
        val head = GlossHeads.find(gloss, listOf("bank")).single()
        assertEquals("bank", gloss.substring(head.start, head.end))
        assertEquals("bank", head.term)
    }

    @Test
    fun `the infinitive's to stays in the term and out of the link`() {
        val gloss = "to run, to jog"
        val heads = GlossHeads.find(gloss, listOf("to run", "to jog"))
        assertEquals(listOf("run", "jog"), heads.map { gloss.substring(it.start, it.end) })
        assertEquals(listOf("run", "jog"), heads.map { it.key })
    }

    @Test
    fun `a two-word term is one link`() {
        val gloss = "to shoo, chase away, drive away"
        assertEquals(listOf("chase away"), keys(gloss, "chase away"))
    }

    @Test
    fun `the words must be contiguous`() {
        assertEquals(emptyList(), keys("chase it away", "chase away"))
    }

    @Test
    fun `a term the gloss does not say is not a head`() {
        assertEquals(emptyList(), keys("financial institution", "bank"))
    }

    @Test
    fun `a bare to is the word itself`() {
        assertEquals(listOf("to"), keys("to", "to"))
    }

    @Test
    fun `accents and case do not hide a head`() {
        assertEquals(listOf("cafe"), keys("Café (drink)", "cafe"))
    }
}
