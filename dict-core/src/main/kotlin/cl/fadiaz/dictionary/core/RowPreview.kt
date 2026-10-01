package cl.fadiaz.dictionary.core

/**
 * How much of a definition a result row shows.
 *
 * ⚠️ **It is a budget in CHARACTERS and not in words**, because what runs out is the row: at
 * 234 dp the second line holds about this much before the ellipsis does the cutting, and a word
 * count cannot promise that. The exact fit is an emulator question (`la-ui-se-decide-viendo-el-reloj`);
 * what this fixes is that the cut is decided **once**, here, and not by three call sites.
 */
const val ROW_PREVIEW_CHARS: Int = 48

/** What a result row can say under its headword. */
sealed interface RowPreview {
    /** The start of the first sense. */
    data class Definition(val text: String) : RowPreview

    /**
     * The entry has no senses because its pack answers *"how is it said"* (D-196).
     *
     * ⚠️ **It carries the equivalents rather than a fixed phrase**, because `perro, can` IS the
     * answer somebody searching `dog` wants, and a row reading *"translation"* would make them
     * open it to find out what the translation is.
     */
    data class TranslationOnly(val equivalents: List<String>) : RowPreview

    /**
     * A monolingual lemma whose source gives no gloss at all (`d-a2f271-e0e67e`).
     *
     * The card says so in full; here it only has to stop the row looking like a definition is
     * coming.
     */
    data object NoDefinition : RowPreview

    /** Nothing is known: the pack's kind was not available. The row falls back to what it had. */
    data object None : RowPreview
}

/**
 * What to show under a row's headword, from the entry's already decoded body.
 *
 * ⚠️ **The two cases are ONE mechanism, which is why this is one function.** A row with no senses
 * means different things in the two kinds of pack, and only the pack's `kind` separates them:
 * in a bidirectional one it is a reverse entry doing its job, in a monolingual one it is a lemma
 * the source could not define. Asking *"are there senses"* alone answers neither.
 *
 * ⚠️ **`null` kind previews NOTHING rather than guessing.** Calling a reverse entry undefined is
 * the error that would hit 164,249 rows, so an unknown pack leaves the row as it was.
 *
 * It is pure and free of Android, so the gate covers it on the JVM (D-072).
 */
fun rowPreview(body: PayloadCodec.Body, packKind: PackKind?): RowPreview {
    val first = body.senses.firstOrNull()?.gloss?.trim()
    if (!first.isNullOrEmpty()) return RowPreview.Definition(ellipsize(first))
    return when (packKind) {
        PackKind.BILINGUAL -> RowPreview.TranslationOnly(body.wordTranslations)
        PackKind.MONOLINGUAL -> RowPreview.NoDefinition
        null -> RowPreview.None
    }
}

/**
 * Cuts to [ROW_PREVIEW_CHARS], **on a space**.
 *
 * ⚠️ A fragment ending mid-word reads as corrupt text rather than as a shortened one, which is
 * worse than a shorter fragment: the reader stops to check whether the pack is broken.
 */
private fun ellipsize(text: String): String {
    if (text.length <= ROW_PREVIEW_CHARS) return text
    val cut = text.take(ROW_PREVIEW_CHARS)
    val lastSpace = cut.lastIndexOf(' ')
    // With no space at all --a single long word-- the hard cut is the only option left.
    val kept = if (lastSpace > ROW_PREVIEW_CHARS / 2) cut.take(lastSpace) else cut
    return kept.trimEnd(' ', ',', ';', ':') + "…"
}
