package cl.fadiaz.dictionary.presentation

import cl.fadiaz.dictionary.core.DictionarySource
import cl.fadiaz.dictionary.core.Entry
import cl.fadiaz.dictionary.core.EntrySummary
import cl.fadiaz.dictionary.core.FuzzyProfile
import cl.fadiaz.dictionary.R
import cl.fadiaz.dictionary.core.PackKind
import cl.fadiaz.dictionary.core.PackMetadata
import cl.fadiaz.dictionary.core.Suggestion
import cl.fadiaz.dictionary.data.PackHandle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a word's menu offers.
 *
 * What it defends is that **nothing that does not work gets offered**: "view translation" looked
 * up the same headword in the other dictionary, and with two monolingual packs that almost never
 * finds anything. A button that does nothing teaches you to distrust the ones that do.
 */
class WordActionsTest {

    private fun pack(id: String, kind: PackKind, translationsTo: String? = null) = PackHandle.Open(
        source = EmptySource(
            PackMetadata(
                packId = id,
                schemaVersion = 3,
                normVersion = 2,
                kind = kind,
                name = "Diccionario $id",
                // null: exercises the path of a pack older than D-125, which does not carry the key.
                description = null,
                langs = if (kind == PackKind.BILINGUAL) listOf("es", "en") else listOf("es"),
                fuzzyProfiles = if (kind == PackKind.BILINGUAL)
                    listOf(FuzzyProfile.SPANISH, FuzzyProfile.SPANISH)
                else listOf(FuzzyProfile.SPANISH),
                translationsTo = translationsTo ?: if (kind == PackKind.BILINGUAL) "en" else null,
                entryCount = 1,
                dataVersion = 1,
                license = "CC0-1.0",
                attribution = "fake",
            ),
        ),
    )

    private fun actions() = wordActions(
        isFavorite = false,
        onToggleFavorite = {},
        onCopy = {},
    ).map { it.label }








    @Test
    fun saveChangesItsLabelDependingOnWhetherItIsSaved() {
        val saved = wordActions(isFavorite = true, onToggleFavorite = {}, onCopy = {})
        assertTrue(saved.first().label == R.string.action_unsave)
    }
}

/** The minimum needed to wrap a metadata. No action queries the pack. */
private class EmptySource(override val metadata: PackMetadata) : DictionarySource {
    override suspend fun suggest(query: String, limit: Int, lang: String?) = emptyList<Suggestion>()
    override suspend fun entry(entryId: Long): Entry? = null
    override suspend fun searchDefinitions(query: String, limit: Int, lang: String?) = emptyList<Suggestion>()
    override suspend fun resolveHeadwords(norms: Set<String>, lang: String?) = emptyMap<String, Long>()
    override suspend fun summary(entryId: Long): EntrySummary? = null
    override fun close() = Unit
}
