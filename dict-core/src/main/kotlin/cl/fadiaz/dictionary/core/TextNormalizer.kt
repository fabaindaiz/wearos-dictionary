package cl.fadiaz.dictionary.core

/**
 * Text normalization for indexing and querying.
 *
 * THIS FILE HAS A MIRROR: tools/packbuilder/normalize.py
 *
 * The `norm` and `fuzzy` keys are computed in the builder (Python) and stored in the pack; on the
 * watch they are computed again over what the user types. If the two implementations diverge by
 * even one character, the query stops matching and the symptom is simply "that word is missing"
 * -- no error, no crash, no log. Hence:
 *
 *  - Any change here is replicated in normalize.py in the same commit.
 *  - Cases are added to vectors/normalization-vectors.tsv, which test BOTH sides.
 *  - [NORM_VERSION] is bumped; packs store their own in meta and are rejected on a mismatch.
 *
 * Regular expressions are deliberately avoided: only literal string replacement is used, which has
 * identical semantics in Kotlin and in Python (global, left to right, non-overlapping). An
 * "equivalent" regex in the two languages is exactly the kind of thing that diverges in silence.
 *
 * CHARACTER CLASSIFICATION DOES NOT COME FROM THE PLATFORM
 * -------------------------------------------------------
 * It comes from [UnicodeRepertoire], which carries its own data. The previous version used
 * Character.getType and was broken: every platform carries its own Unicode version (Python 3.9 ->
 * 13.0, Java 26 -> 16, and Android a different one per system release). Measured over the full
 * repertoire, 14,773 code points classified differently, and that made the SAME pack behave
 * differently on two watches with different Wear OS versions.
 *
 * What is still delegated to the platform is NFD and lowercase, and that is safe: over the 133,730
 * code points of the pinned repertoire, Java 26 and Python 3.9 give ZERO differences in both
 * operations.
 */
object TextNormalizer {

    /**
     * Bumped when the result of [norm] or [fuzzy] changes. Compared against meta.norm_version.
     *
     * 1 -> 2: code point classification moved from Character.getType to [UnicodeRepertoire].
     */
    const val NORM_VERSION: Int = 2

    /**
     * Letters NFD does not decompose and that we want folded anyway, so that "Straße" and
     * "strasse" land on the same key.
     */
    private val EXPANSIONS: Map<Int, String> = mapOf(
        'ß'.code to "ss",
        'æ'.code to "ae",
        'œ'.code to "oe",
        'ø'.code to "o",
        'đ'.code to "d",
        'ð'.code to "d",
        'þ'.code to "th",
        'ł'.code to "l",
        'ı'.code to "i",
        'ŋ'.code to "ng",
        'ſ'.code to "s",
        // Latin ligatures: NFD does not decompose them (that is NFKD, which would bring other,
        // less predictable effects such as "½" -> "1/2").
        'ﬁ'.code to "fi",
        'ﬂ'.code to "fl",
        'ﬀ'.code to "ff",
    )

    /**
     * The indexing key: lowercase, no diacritics, letters/digits only, spaces collapsed.
     *
     * The result is compared under BINARY collation, so no ICU is needed on the watch and the
     * behaviour is identical on every device.
     */
    fun norm(input: String): String {
        val lowered = input.lowercase()

        val expanded = StringBuilder(lowered.length)
        forEachCodePoint(lowered) { codePoint ->
            val replacement = EXPANSIONS[codePoint]
            if (replacement != null) expanded.append(replacement) else expanded.appendUtf16(codePoint)
        }

        val decomposed = decomposeToNfd(expanded.toString())

        val kept = StringBuilder(decomposed.length)
        forEachCodePoint(decomposed) { codePoint ->
            when (UnicodeRepertoire.classify(codePoint)) {
                // Combining marks are the diacritics NFD left behind.
                UnicodeRepertoire.CLASS_COMBINING_MARK -> Unit
                UnicodeRepertoire.CLASS_LETTER,
                UnicodeRepertoire.CLASS_DIGIT -> kept.appendUtf16(codePoint)
                // Punctuation, symbols and everything outside the pinned repertoire become
                // separators, they do not vanish: "self-made" has to end up "self made".
                else -> kept.append(' ')
            }
        }
        return collapseSpaces(kept.toString())
    }

    /**
     * The typo-tolerant key: [norm] plus the language's phonetic folds and a collapse of repeated
     * letters. It is deliberately aggressive because it is only used as a last resort, when the
     * prefix search returned nothing, and afterwards it is reordered by edit distance. False
     * positives are cheap here; false negatives are not.
     */
    fun fuzzy(input: String, profile: FuzzyProfile): String {
        var result = norm(input)
        for ((from, to) in profile.rules) {
            result = result.replace(from, to)
        }
        return collapseSpaces(collapseDoubledLetters(result))
    }

    /**
     * Collapses adjacent repeated letters: "correr" -> "corer". Letters only; collapsing digits
     * would turn "1000" into "10", which is why the repertoire separates the two classes.
     */
    private fun collapseDoubledLetters(text: String): String {
        val out = StringBuilder(text.length)
        var previous = -1
        forEachCodePoint(text) { codePoint ->
            val isRepeatedLetter = codePoint == previous &&
                UnicodeRepertoire.classify(codePoint) == UnicodeRepertoire.CLASS_LETTER
            if (!isRepeatedLetter) {
                out.appendUtf16(codePoint)
                previous = codePoint
            }
        }
        return out.toString()
    }

    private fun collapseSpaces(text: String): String {
        val out = StringBuilder(text.length)
        var pendingSpace = false
        for (character in text) {
            if (character == ' ') {
                if (out.isNotEmpty()) pendingSpace = true
            } else {
                if (pendingSpace) {
                    out.append(' ')
                    pendingSpace = false
                }
                out.append(character)
            }
        }
        return out.toString()
    }

    /**
     * Walks by code point and not by Char, so as not to split the surrogate pairs of the
     * supplementary planes. Hand-written because `String.codePoints()` is a JVM API.
     */
    private inline fun forEachCodePoint(text: String, action: (Int) -> Unit) {
        var index = 0
        while (index < text.length) {
            val first = text[index]
            val codePoint = if (first.isHighSurrogate() && index + 1 < text.length &&
                text[index + 1].isLowSurrogate()
            ) {
                index += 2
                CodePoint.fromSurrogatePair(first, text[index - 1])
            } else {
                index += 1
                first.code
            }
            action(codePoint)
        }
    }
}

/** Code point utilities with no platform dependencies. */
internal object CodePoint {
    private const val SURROGATE_OFFSET = 0x10000
    private const val HIGH_SURROGATE_START = 0xD800
    private const val LOW_SURROGATE_START = 0xDC00

    fun fromSurrogatePair(high: Char, low: Char): Int =
        SURROGATE_OFFSET +
            ((high.code - HIGH_SURROGATE_START) shl 10) +
            (low.code - LOW_SURROGATE_START)

    /** The inverse: appends a code point to a StringBuilder, with or without a surrogate pair. */
    fun appendTo(builder: StringBuilder, codePoint: Int) {
        if (codePoint < SURROGATE_OFFSET) {
            builder.append(codePoint.toChar())
        } else {
            val offset = codePoint - SURROGATE_OFFSET
            builder.append((HIGH_SURROGATE_START + (offset shr 10)).toChar())
            builder.append((LOW_SURROGATE_START + (offset and 0x3FF)).toChar())
        }
    }
}

/** Replaces StringBuilder.appendCodePoint, which is a JVM API. */
internal fun StringBuilder.appendUtf16(codePoint: Int) {
    CodePoint.appendTo(this, codePoint)
}
