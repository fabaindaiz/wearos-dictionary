package cl.fadiaz.dictionary.data

/**
 * An entry the user opened, stored so they can get back to it with one tap.
 *
 * It stores what is needed to **open** it, not to search for it again: `packId` and `entryId`
 * navigate straight to the entry, in the right dictionary. Going back re-runs no query.
 *
 * The OPENED entry is stored and not the typed query, because opening is the signal that the
 * result was useful; a query can be a half-finished word, and a history full of "per", "perr",
 * "perro" saves nobody a gesture.
 *
 * Without a single reference to Android, same as [PackLoad] and [PackSet]: that is what lets the
 * recency policy be tested on the JVM, inside the gate (D-072).
 */
data class Visit(
    val packId: String,
    val entryId: Long,
    val headword: String,
    val partOfSpeech: String?,
    /**
     * The first sense, **only** when whoever stores the visit needs it in order to show it.
     *
     * ⚠️ **The tile's weekly cache fills it and nobody else.** The word of the day read as
     * `futuro · sust.` and that teaches nothing: the gloss is what makes it useful at a glance.
     * History and saved words leave it null, because their row is a single line and does not draw
     * it.
     *
     * ⚠️ **It is a contract with the disk and that is why it is optional.** A preference written
     * before this field has four columns and is still read; the parser tolerates both shapes. What
     * is not done is migrating: what a watch stored is read as it stands.
     */
    val gloss: String? = null,
)

/**
 * The field separator: UNIT SEPARATOR (U+001F).
 *
 * Deliberately not a tab and not a `|`. Headwords come from Wiktionary, which holds sayings with
 * quotes in them, and a stray tab inside a gloss **has already broken something in this project**
 * --that is why `payload.sanitize()` exists--. A C0 control character cannot appear in a headword.
 */
internal const val SEPARATOR: String = ""

/** Marks a null `pos`. It cannot be confused with a real one: none of them is empty. */
private const val NO_POS = ""

internal fun serializeVisits(visits: List<Visit>): String =
    visits.joinToString("\n") { visit ->
        listOf(
            visit.packId,
            visit.entryId.toString(),
            visit.headword,
            visit.partOfSpeech ?: NO_POS,
            // ⚠️ **No line breaks**: `\n` separates RECORDS, so a gloss carrying one would split
            // the list and the second half would be discarded in silence. It is the same care
            // `payload.sanitize()` takes with the tab.
            visit.gloss?.replace('\n', ' ')?.replace(SEPARATOR, " ").orEmpty(),
        ).joinToString(SEPARATOR)
    }

/**
 * Reads the stored history, **dropping whatever it does not understand** instead of failing.
 *
 * Deliberate: this is read at startup, and an old format after an update or a corrupt byte
 * cannot stop the app from opening. Losing the history is acceptable; not starting is not.
 */
internal fun parseVisits(text: String): List<Visit> =
    text.lineSequence()
        .mapNotNull { line ->
            val fields = line.split(SEPARATOR)
            // ⚠️ **`< 4` and not `!= 4`.** Demanding exactly four made adding a column break the
            // reading of the records the app itself had just written. With this, an old preference
            // still reads and a new one contributes whatever it carries.
            if (fields.size < 4) return@mapNotNull null
            val entryId = fields[1].toLongOrNull() ?: return@mapNotNull null
            if (fields[0].isEmpty() || fields[2].isEmpty()) return@mapNotNull null
            Visit(
                packId = fields[0],
                entryId = entryId,
                headword = fields[2],
                partOfSpeech = fields[3].ifEmpty { null },
                gloss = fields.getOrNull(4)?.ifEmpty { null },
            )
        }
        .toList()

/**
 * Where a stored visit leads, when its `entryId` may have gone stale.
 *
 * `entry.id` is the pack's PHYSICAL identity --the rowid-- and **does not survive a rebuild**: one
 * new word in the middle shifts every following one (D-055). History and saved words are backed
 * up by `entryId`, so after a rebuild they point at ANOTHER word: no error, no log, and with the
 * right headword still written in the row.
 */
internal sealed interface VisitTarget {
    /** The `entryId` still belongs to that word. The normal case. */
    data class Direct(val entryId: Long) : VisitTarget

    /** The pack was rebuilt and the id shifted; it was fixed through the headword. */
    data class Relocated(val entryId: Long) : VisitTarget

    /** The word is no longer in this pack. */
    data object Missing : VisitTarget
}

/**
 * Decides whether the stored `entryId` is still good, and if not, what to replace it with.
 *
 * Pure and free of Android so it enters the gate (D-072): **which entry gets opened** is the
 * decision, and the decision is what goes wrong.
 *
 * @param headwordAtId the headword that lives at `saved.entryId` today, or `null` if that id no
 *   longer exists --the pack may have SHRUNK, which is what D-116 did--.
 * @param relocated the id that looking the headword up through `idx_entry_norm` gives, or `null`
 *   if the word is no longer in the pack.
 */
internal fun visitTarget(
    saved: Visit,
    headwordAtId: String?,
    relocated: Long?,
): VisitTarget = when {
    // With no headword there is nothing to fix by: the only question left is whether that id
    // exists. This happens with a tile deep link, which builds the visit from the intent extras.
    saved.headword.isEmpty() ->
        if (headwordAtId != null) VisitTarget.Direct(saved.entryId) else VisitTarget.Missing
    // The normal case: the pack was not rebuilt. It costs the read that has to happen anyway to
    // show the entry, and NO extra query.
    headwordAtId == saved.headword -> VisitTarget.Direct(saved.entryId)
    // The id shifted --or the pack shrank--. The headword is all that survives, and it goes
    // through an index. Precision is lost between homographs of the SAME pos (3,024 pairs in
    // Spanish, the ones `sense_key` separates in `entry.uid`): the best ranked one opens. That is
    // strictly better than opening an unrelated word, which is what happened before.
    relocated != null -> VisitTarget.Relocated(relocated)
    // The word is gone. The row is lost instead of opening some other word.
    else -> VisitTarget.Missing
}
