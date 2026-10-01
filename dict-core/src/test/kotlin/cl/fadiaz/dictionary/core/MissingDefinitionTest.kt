package cl.fadiaz.dictionary.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When the card has to say that this dictionary has no definition for the word.
 *
 * ⚠️ **It exists because the lexicographic literature asks for it outright**, which measurement
 * alone had not surfaced. On zero equivalence the recommendation is that *"the importance of
 * explicitly marking"* the gap be recognised, and that *"if the examples remain untranslated, the
 * user is not made aware of the problem of non-equivalence"*. See `docs/references.md`
 * §*Lexicographic method* and `i-a2f271-0e2a2f`.
 *
 * ⚠️ **The hard half is the case that must stay SILENT.** A bidirectional pack's reverse entry has
 * no senses **by design** (D-196): `dog` answers *"how is it said"* --`perro`, `can`-- and never
 * *"what does it mean"*. Marking it would call the pack's whole purpose a defect, on **164,249**
 * English entries.
 */
class MissingDefinitionTest {

    private fun entry(senses: List<Sense>, lang: String? = "es") = Entry(
        packId = "es-def-wikc",
        entryId = 1L,
        uid = 1L,
        lang = lang,
        headword = "introducir",
        partOfSpeech = "verbo",
        senses = senses,
    )

    private val unaAcepcion = listOf(Sense(gloss = "Meter algo en otra cosa."))

    @Test
    fun `a monolingual entry with no senses is a gap worth saying`() {
        assertTrue(entry(emptyList()).lacksDefinition(PackKind.MONOLINGUAL))
    }

    @Test
    fun `a monolingual entry that HAS a sense says nothing`() {
        assertFalse(entry(unaAcepcion).lacksDefinition(PackKind.MONOLINGUAL))
    }

    @Test
    fun `a bilingual reverse entry is silent, because the absence is its design`() {
        // D-196: 164,249 English entries of `es-en` have no senses and are not defective.
        assertFalse(entry(emptyList(), lang = "en").lacksDefinition(PackKind.BILINGUAL))
    }

    @Test
    fun `a bilingual entry with senses is silent too`() {
        assertFalse(entry(unaAcepcion).lacksDefinition(PackKind.BILINGUAL))
    }
}
