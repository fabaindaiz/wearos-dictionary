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
                langSource = "es",
                langTarget = if (kind == PackKind.BILINGUAL) "en" else null,
                translationsTo = translationsTo ?: if (kind == PackKind.BILINGUAL) "en" else null,
                fuzzyProfile = FuzzyProfile.SPANISH,
                entryCount = 1,
                dataVersion = 1,
                license = "CC0-1.0",
                attribution = "fake",
            ),
        ),
    )

    private fun actions(translation: PackHandle.Open?) = wordActions(
        isFavorite = false,
        onToggleFavorite = {},
        translationPack = translation,
        onViewTranslation = {},
        onCopy = {},
    ).map { it.label }

    @Test
    fun withNoTranslationPackTranslateIsNotOffered() {
        assertEquals(listOf(R.string.action_save, R.string.action_copy), actions(null))
    }

    @Test
    fun withABilingualPackItIsOffered() {
        assertEquals(
            listOf(R.string.action_save, R.string.action_translate, R.string.action_copy),
            actions(pack("es-en", PackKind.BILINGUAL)),
        )
    }

    @Test
    fun aMonolingualPackThatDeclaresTranslationsIsOne() {
        // ⚠️ El pack español ahora **traduce** --lee la tabla del Wikcionario y llena `T`/`W`--
        // pero sigue siendo `monolingual`, porque sus DEFINICIONES son en español. Filtrar por
        // `kind` dejaba la acción sin ofrecer nunca sobre un pack que sí traduce: `kind`
        // contesta en qué idioma están las definiciones, no qué sabe hacer el pack.
        val opened = listOf(pack("es-def", PackKind.MONOLINGUAL),
                            pack("es-otro", PackKind.MONOLINGUAL, translationsTo = "en"))
        assertEquals("es-otro", translationPack(opened, "es-def", entryHasTranslations = false)?.packId)
    }

    @Test
    fun theActionDisappearsWhenTheEntryAlreadyShowsItsOwn() {
        // La acción existía para ir a buscar la palabra a OTRO pack. Ahora las traducciones se
        // muestran dentro de la ficha, así que ofrecerla además sería mandar al lector a otra
        // pantalla por lo que ya está viendo.
        val opened = listOf(pack("es-def", PackKind.MONOLINGUAL),
                            pack("es-en", PackKind.BILINGUAL))
        assertNull(translationPack(opened, "es-def", entryHasTranslations = true))
    }

    @Test
    fun anotherMONOLINGUALDictionaryIsNotATranslationPack() {
        // This is today's real case: Spanish and English, both monolingual. Looking "house" up
        // in the Spanish dictionary returns no translation, it returns nothing.
        val opened = listOf(
            pack("es-def", PackKind.MONOLINGUAL),
            pack("en-def", PackKind.MONOLINGUAL),
        )
        assertNull(translationPack(opened, packOfTheEntry = "es-def", entryHasTranslations = false))
    }

    @Test
    fun theTranslationPackIsBilingualAndNotTheOneBeingRead() {
        val opened = listOf(
            pack("es-def", PackKind.MONOLINGUAL),
            pack("es-en", PackKind.BILINGUAL),
        )
        assertEquals("es-en", translationPack(opened, "es-def", entryHasTranslations = false)?.packId)
        // While reading the bilingual one, translating to itself is not offered.
        assertNull(translationPack(opened, "es-en", entryHasTranslations = false))
    }

    @Test
    fun saveChangesItsLabelDependingOnWhetherItIsSaved() {
        val saved = wordActions(
            isFavorite = true,
            onToggleFavorite = {},
            translationPack = null,
            onViewTranslation = {},
            onCopy = {},
        )
        assertTrue(saved.first().label == R.string.action_unsave)
    }
}

/** The minimum needed to wrap a metadata. No action queries the pack. */
private class EmptySource(override val metadata: PackMetadata) : DictionarySource {
    override suspend fun suggest(query: String, limit: Int) = emptyList<Suggestion>()
    override suspend fun entry(entryId: Long): Entry? = null
    override suspend fun searchDefinitions(query: String, limit: Int) = emptyList<Suggestion>()
    override suspend fun resolveHeadwords(norms: Set<String>) = emptyMap<String, Long>()
    override suspend fun summary(entryId: Long): EntrySummary? = null
    override fun close() = Unit
}
