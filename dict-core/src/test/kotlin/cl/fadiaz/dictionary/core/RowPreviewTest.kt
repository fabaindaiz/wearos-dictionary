package cl.fadiaz.dictionary.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a result row can say under the headword, beyond its part of speech.
 *
 * ⚠️ **Asked for because the list is where a word gets CHOSEN**, and a part of speech does not help
 * choose: four rows reading `verbo` are four rows that all have to be opened. The literature's
 * log-file research says the same thing from the other side -- what users do is look up frequent
 * words, often, and a list that makes them open each candidate spends the one thing a watch does
 * not have.
 *
 * ⚠️ **The two cases are ONE mechanism and that is why they are here together.** A row with no
 * senses in a bilingual pack is a **translation entry** (D-196) -- `dog` answers *"how is it
 * said"*; the same row in a monolingual pack is a lemma whose source gave no gloss
 * (`d-a2f271-e0e67e`). Reading the payload answers both at once, so the row either previews the
 * definition or says which kind of answer it is.
 */
class RowPreviewTest {

    private fun body(vararg glosses: String, translations: List<String> = emptyList()) =
        PayloadCodec.Body(
            partOfSpeech = "verbo",
            senses = glosses.map { Sense(it) },
            wordTranslations = translations,
        )

    @Test
    fun `the first sense is the preview`() {
        assertEquals(
            RowPreview.Definition("Meter algo en otra cosa."),
            rowPreview(body("Meter algo en otra cosa.", "Presentar a alguien."),
                PackKind.MONOLINGUAL),
        )
    }

    @Test
    fun `a long sense is cut on a word and not mid-letter`() {
        // ⚠️ **This gloss is chosen so the HARD cut falls inside a word**: at 48 characters it ends
        // `...enseñanz`. A fixture whose 48th character happens to be a boundary cannot tell the
        // two implementations apart, and the first version of this test could not -- the mutation
        // that drops the word-boundary search survived it.
        val gloss = "Dicho de una persona que se dedica a la enseñanza de una materia concreta."
        val preview = rowPreview(body(gloss), PackKind.MONOLINGUAL) as RowPreview.Definition
        assertEquals(true, preview.text.length <= ROW_PREVIEW_CHARS + 1, preview.text)
        assertEquals(true, preview.text.endsWith("…"), preview.text)

        val kept = preview.text.dropLast(1)
        assertEquals(true, gloss.startsWith(kept), preview.text)
        // The character the original continues with has to be a space: that is what "it did not
        // cut a word in half" means, and it is what the length alone cannot say.
        assertEquals(' ', gloss[kept.length], preview.text)
    }

    @Test
    fun `a cut that lands after a comma does not leave it hanging`() {
        // ⚠️ **Another fixture chosen so the case actually occurs**: here the word-boundary cut
        // ends `...cuatro patas,` and `patas,…` reads as a typo rather than as a shortened
        // sentence. A mutation that drops the trim survived until this test existed.
        val preview = rowPreview(
            body("Dicho de un animal: que tiene cuatro patas, pelo y cola larga."),
            PackKind.MONOLINGUAL,
        ) as RowPreview.Definition
        assertEquals(true, preview.text.endsWith("patas…"), preview.text)
    }

    @Test
    fun `no senses in a BILINGUAL pack is a translation entry`() {
        assertEquals(
            RowPreview.TranslationOnly(listOf("perro", "can")),
            rowPreview(body(translations = listOf("perro", "can")), PackKind.BILINGUAL),
        )
    }

    @Test
    fun `no senses in a MONOLINGUAL pack is a word the dictionary cannot define`() {
        assertEquals(
            RowPreview.NoDefinition,
            rowPreview(body(), PackKind.MONOLINGUAL),
        )
    }

    @Test
    fun `an unknown pack kind previews nothing rather than guessing`() {
        assertEquals(RowPreview.None, rowPreview(body(), null))
    }
}
