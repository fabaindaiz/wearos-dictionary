package cl.fadiaz.dictionary.presentation

import cl.fadiaz.dictionary.core.PackKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The label that replaces the long half of the pack's name (D-125), now as a resource id (D-127).
 *
 * The test that matters is not the wording of each case but that **there is one per `PackKind`
 * and that they differ**: the day a third kind shows up --a separate synonyms pack, say-- the
 * exhaustive `when` will not compile, but if somebody "fixes" it with an `else` the new pack
 * announces itself with the wrong label and nothing fails.
 *
 * It stayed a plain JVM test on purpose. `packTypeLabelRes` is a pure mapping, so it needs no
 * Android; resolving the TEXT does, and that is [packTypeLabel]'s job. The wording itself, and
 * that it fits a watch row **in both languages**, is checked by the audit over the two
 * `strings.xml` -- which is the only place that can see both at once.
 */
class PackTypeLabelTest {

    @Test
    fun everyKindHasItsLabel() {
        for (kind in PackKind.entries) {
            assertTrue(packTypeLabelRes(kind) != 0, "sin etiqueta: $kind")
        }
    }

    @Test
    fun theLabelsDifferFromEachOther() {
        // Two kinds sharing a label distinguish nothing, which is the whole point of showing it.
        val ids = PackKind.entries.map { packTypeLabelRes(it) }
        assertEquals(ids.size, ids.toSet().size, "hay etiquetas repetidas: $ids")
    }
}
