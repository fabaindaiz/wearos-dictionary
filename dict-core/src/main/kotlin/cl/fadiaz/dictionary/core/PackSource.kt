package cl.fadiaz.dictionary.core

/**
 * One source a pack declares in its manifest: who it came from, and under which licence (D-138).
 *
 * ⚠️ **A pack can carry content from several sources under DIFFERENT licences**, and that is why
 * this exists. The Spanish pack mixes definitions under CC BY-SA 4.0 with corpus sentences under
 * CC BY 2.0 FR; the single `meta.license` string that came before either over-claimed or
 * under-credited, and attribution is the **condition of use** of the data, not a courtesy
 * (D-031).
 *
 * **The credit travels inside the pack**, never in the app: a pack built by someone else, from a
 * source this app has never heard of, has to be able to state its own terms and have them shown.
 * That is also what keeps user-generated packs first-class (D-130).
 *
 * **Format: one source per line, five tab-separated fields.** Delimited text and not JSON, the
 * same choice the payload made and for the same reasons — it parses with no dependency in both
 * languages, it can be read by eye while debugging a pack, and `:dict-core` stays free of a JSON
 * library it has no other use for.
 *
 * ```
 * definitions<TAB>Wikcionario<TAB>https://es.wiktionary.org/<TAB>CC BY-SA 4.0<TAB>https://…
 * sentences<TAB>Tatoeba<TAB>https://tatoeba.org/<TAB>CC BY 2.0 FR<TAB>https://…
 * ```
 */
data class PackSource(
    /** What this source contributed. See [Role]. */
    val role: Role,
    /** How the source wants to be named. Shown verbatim. */
    val name: String,
    /** Where it came from, for someone who wants to check. May be empty. */
    val url: String,
    /** The licence **of this source's contribution**, as its own name for it ("CC BY-SA 4.0"). */
    val license: String,
    /** The licence text. May be empty: not every licence has a canonical URL. */
    val licenseUrl: String,
) {

    /**
     * What a source contributed to the pack.
     *
     * ⚠️ **An unknown role becomes [OTHER] instead of dropping the source**, the same tolerance
     * the payload's unknown tags have. Losing a credit because a newer builder used a word this
     * reader does not know would be exactly the breach this class exists to prevent.
     */
    enum class Role(val id: String) {
        /** The definitions: what makes it a dictionary. */
        DEFINITIONS("definitions"),

        /** Usage examples attributed to a sense by the source itself. */
        EXAMPLES("examples"),

        /** Corpus sentences: they contain the word, they do not explain it (D-137). */
        SENTENCES("sentences"),

        /** Synonyms, antonyms, related words. */
        RELATIONS("relations"),

        /** Translations, in a bilingual pack. */
        TRANSLATIONS("translations"),

        /** A role this reader does not know. The credit is kept; only the label is generic. */
        OTHER("other"),
    }

    companion object {
        private const val CAMPOS = 5

        /** Parses `meta.sources`. Null or blank gives an empty list; it never throws. */
        fun parse(text: String?): List<PackSource> {
            if (text.isNullOrBlank()) return emptyList()
            val out = mutableListOf<PackSource>()
            for (line in text.split('\n')) {
                if (line.isBlank()) continue
                val campos = line.split('\t')
                // A malformed line is skipped, not fatal: a third-party pack may be built by
                // hand, and refusing to open a 68 MB dictionary over one line would be worse
                // than showing the sources that are readable.
                if (campos.size < CAMPOS) continue
                val name = campos[1].trim()
                if (name.isEmpty()) continue
                out.add(
                    PackSource(
                        role = Role.entries.firstOrNull { it.id == campos[0].trim() } ?: Role.OTHER,
                        name = name,
                        url = campos[2].trim(),
                        license = campos[3].trim(),
                        licenseUrl = campos[4].trim(),
                    ),
                )
            }
            return out
        }

        /**
         * The distinct licences, in declaration order.
         *
         * What a screen needs to say "this pack is distributed under X and Y" without repeating
         * the same name once per source.
         */
        fun licenses(sources: List<PackSource>): List<String> =
            sources.map { it.license }.filter { it.isNotEmpty() }.distinct()
    }
}
