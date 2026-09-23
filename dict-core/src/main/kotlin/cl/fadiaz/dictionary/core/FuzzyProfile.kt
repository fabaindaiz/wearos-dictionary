package cl.fadiaz.dictionary.core

/**
 * Per-language phonetic folding rules, applied in order over the output of
 * [TextNormalizer.norm].
 *
 * THIS FILE HAS A MIRROR: tools/packbuilder/normalize.py
 *
 * Each pack declares its profile in `meta.fuzzy_profile`. Rule order matters and is part of the
 * contract: "ce" -> "se" has to run before "c" -> "k", or "cerrar" ends up as "kerar" and stops
 * colliding with "serrar".
 *
 * These are orthographic heuristics, not a full phonetic algorithm. The goal is that each
 * language's frequent confusions (and the typical errors of voice dictation) land on the same
 * key, not to transcribe pronunciation.
 */
enum class FuzzyProfile(val id: String, val rules: List<Pair<String, String>>) {

    /** No phonetic folding: only the repeated-letter collapse [TextNormalizer.fuzzy] does. */
    GENERIC("generic", emptyList()),

    /**
     * Spanish. Covers seseo (c/z/s), b/v, y/ll, silent h and the silent u of que/qui/gue/gui.
     *
     * "ch" is protected with a numeric marker before the h is deleted and restored at the end;
     * otherwise "chico" would lose its h and collide with things it should not.
     *
     * Known limitation: "mexico" -> "mesiko" and "mejico" -> "mejiko" do not collide. The x of
     * Mexican Spanish sounds like a j, but treating it that way would break "examen" -> "esamen",
     * which is the far more frequent case. The edit-distance reordering covers it.
     */
    SPANISH(
        "es",
        listOf(
            "ch" to "8",
            "qu" to "k",
            "gue" to "ge",
            "gui" to "gi",
            "h" to "",
            "ll" to "y",
            "v" to "b",
            "z" to "s",
            "ce" to "se",
            "ci" to "si",
            "c" to "k",
            "y" to "i",
            "x" to "s",
            "w" to "b",
            "8" to "ch",
        ),
    ),

    /** English. Silent digraphs (kn, wr, gh), ph/f, hard and soft c, x/ks, y/i. */
    ENGLISH(
        "en",
        listOf(
            "ck" to "k",
            "ph" to "f",
            "wh" to "w",
            "kn" to "n",
            "wr" to "r",
            "gh" to "",
            "qu" to "kw",
            "ce" to "se",
            "ci" to "si",
            "cy" to "si",
            "c" to "k",
            "x" to "ks",
            "y" to "i",
        ),
    ),

    /** German. sch/s, v/f, w/v, z/ts and h digraphs. The eszett's ss was already produced by norm. */
    GERMAN(
        "de",
        listOf(
            "sch" to "s",
            "ck" to "k",
            "ph" to "f",
            "th" to "t",
            "dt" to "t",
            "v" to "f",
            "w" to "v",
            "z" to "ts",
            "y" to "i",
        ),
    ),
    ;

    companion object {
        /**
         * Resolves the profile declared in `meta.fuzzy_profile`. An unknown id falls back to
         * [GENERIC] instead of failing: a pack built with a newer profile stays usable, it just
         * loses typo tolerance.
         */
        fun fromId(id: String?): FuzzyProfile =
            entries.firstOrNull { it.id == id } ?: GENERIC
    }
}
