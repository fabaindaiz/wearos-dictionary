package cl.fadiaz.dictionary.presentation

import cl.fadiaz.dictionary.core.GlossTokenizer
import cl.fadiaz.dictionary.core.WordSpan

/** Where a gloss says one of its sense's translations, and the headword that span opens. */
internal data class GlossHead(val start: Int, val end: Int, val term: String, val key: String)

/**
 * The translations a bilingual gloss already says.
 *
 * The bilingual pack's gloss is itself the translation (`bank (financial institution)`) and its
 * `T` lines are cut from it for search, so a card drawing both says the same word twice
 * (d-a2f271-e4e747). A term found here is painted inside the gloss; one that is not keeps its line.
 *
 * The words must be contiguous and in order: `chase it away` does not say `chase away`.
 */
internal object GlossHeads {

    /** An English infinitive's marker: `to run` is in the gloss, but the headword is `run`. */
    private const val INFINITIVE = "to"

    fun find(gloss: String, translations: List<String>): List<GlossHead> {
        val words = GlossTokenizer.tokenize(gloss)
        return translations.mapNotNull { term -> locate(gloss, words, term) }
    }

    private fun locate(gloss: String, words: List<WordSpan>, term: String): GlossHead? {
        val wanted = GlossTokenizer.tokenize(term).map { it.norm }
        if (wanted.isEmpty() || wanted.size > words.size) return null
        val from = (0..words.size - wanted.size).firstOrNull { at ->
            wanted.indices.all { words[at + it].norm == wanted[it] }
        } ?: return null
        val skip = if (wanted.size > 1 && wanted.first() == INFINITIVE) 1 else 0
        val start = words[from + skip].start
        val end = words[from + wanted.size - 1].end
        return GlossHead(start, end, term, wanted.drop(skip).joinToString(" "))
    }
}
