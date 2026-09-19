package cl.fadiaz.dictionary.data

import cl.fadiaz.dictionary.core.PackKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The label that replaces the long half of the pack's name (D-125).
 *
 * The test that matters is not the wording of each case but that **there is one per `PackKind`**:
 * the day a third kind shows up --a separate synonyms pack, say-- the exhaustive `when` will not
 * compile, but if somebody "fixes" it with an `else` the new pack announces itself with the wrong
 * label and nothing fails.
 */
class PackTypeLabelTest {

    @Test
    fun everyKindHasItsLabel() {
        assertEquals("definiciones", packTypeLabel(PackKind.MONOLINGUAL))
        assertEquals("traducción", packTypeLabel(PackKind.BILINGUAL))
    }

    @Test
    fun noKindIsLeftWithoutALabel() {
        for (kind in PackKind.entries) {
            assertTrue(packTypeLabel(kind).isNotBlank(), "sin etiqueta: $kind")
        }
    }

    @Test
    fun theLabelsDifferFromEachOther() {
        // Two kinds sharing a label distinguish nothing, which is the whole point of showing it.
        val labels = PackKind.entries.map { packTypeLabel(it) }
        assertEquals(labels.size, labels.toSet().size, "hay etiquetas repetidas: $labels")
    }

    @Test
    fun theyFitInAWatchRow() {
        // The reason the name was shortened. The row's detail has ~140 dp left after the check
        // and the delete button; a long label repeats the problem.
        for (kind in PackKind.entries) {
            assertTrue(packTypeLabel(kind).length <= 14, "etiqueta muy larga: ${packTypeLabel(kind)}")
        }
    }
}
