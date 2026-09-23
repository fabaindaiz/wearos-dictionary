"""Generates the pinned Unicode repertoire table shared by the builder and the app.

    python3 tools/unicode/gen_repertoire.py

It writes two artifacts, both committed:

    tools/unicode/repertoire.txt                       <- data, the source of truth
    dict-core/src/main/.../UnicodeRepertoire.kt        <- generated from the .txt

WHY THIS EXISTS
---------------
norm() classifies every code point as letter/digit, combining mark, or separator. At first that
classification was delegated to Character.getType (Kotlin) and unicodedata.category (Python), and
that was broken: every platform ships its own Unicode version.

    Python 3.9 -> Unicode 13.0        Java 26 -> Unicode 16
    Android    -> a different version FOR EVERY system release

Measured over the full repertoire: 14,773 code points classify differently between Python 13 and
Java 16, all of them because they were assigned after Unicode 13. The symptom in the app is not an
error but a word that does not appear, and the SAME pack would behave differently on two watches
with different Wear OS versions.

WHY IT IS PINNED TO UNICODE 13
------------------------------
13.0 is the floor: it is what Python 3.9 ships (the builder's interpreter) and it is below what
Android 13 ships, which is the app's minSdk 33 (Unicode 14). Every platform above the floor knows
the whole repertoire, and that is what makes it safe to keep delegating the other two operations.
Verified over the table's 133,730 code points:

    lowercase() Java 26 vs Python 3.9 -> 0 differences
    NFD         Java 26 vs Python 3.9 -> 0 differences

NFD and lowercase still come from the platform; the classification does not.

RAISING THE UNICODE VERSION
---------------------------
It is a deliberate act, not a side effect of upgrading Python:
  1. Check that the new floor is supported by ALL platforms (the oldest Python that builds packs
     and the oldest Android that runs the app).
  2. Regenerate with this script and review repertoire.txt's diff.
  3. Bump NORM_VERSION in normalize.py and TextNormalizer.kt. Old packs reject themselves.
"""

import hashlib
import os
import sys
import unicodedata

PINNED_UNICODE_VERSION = "13.0.0"

CLASS_OTHER = 0           # separator: punctuation, symbols, and everything unassigned at the floor
CLASS_LETTER = 1
CLASS_COMBINING_MARK = 2
CLASS_DIGIT = 3           # kept apart from letter because fuzzy() collapses repeated letters and
                          # digits it does not: "1000" must not become "10"

LETTER_CATEGORIES = ("Lu", "Ll", "Lt", "Lm", "Lo")

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
DATA_PATH = os.path.join(HERE, "repertoire.txt")
KOTLIN_PATH = os.path.join(
    ROOT, "dict-core", "src", "main", "kotlin", "cl", "fadiaz", "dictionary", "core",
    "UnicodeRepertoire.kt",
)

_BASE36 = "0123456789abcdefghijklmnopqrstuvwxyz"


def base36(value):
    if value == 0:
        return "0"
    out = ""
    while value:
        out = _BASE36[value % 36] + out
        value //= 36
    return out


def compute_ranges():
    """Classifies the whole code point space and collapses it into ranges."""
    rows = []
    for cp in range(0x110000):
        if 0xD800 <= cp <= 0xDFFF:
            continue  # surrogates are not code points on their own
        category = unicodedata.category(chr(cp))
        if category in LETTER_CATEGORIES:
            rows.append((cp, CLASS_LETTER))
        elif category == "Nd":
            rows.append((cp, CLASS_DIGIT))
        elif category == "Mn":
            rows.append((cp, CLASS_COMBINING_MARK))
        # The rest is not stored: absence IS CLASS_OTHER. That keeps the table small and makes a
        # code point unassigned at the floor fall into "separator" by default.

    ranges = []
    start = previous = rows[0][0]
    kind = rows[0][1]
    for cp, klass in rows[1:]:
        if cp == previous + 1 and klass == kind:
            previous = cp
        else:
            ranges.append((start, previous, kind))
            start = previous = cp
            kind = klass
    ranges.append((start, previous, kind))
    return ranges


def encode(ranges):
    """Compact encoding: start as a delta from the previous range, length, class. Base 36.

    The delta matters: the ranges sit very close together, so the deltas are small and the whole
    table fits in a few KB of text.
    """
    parts = []
    last_end = 0
    for start, end, kind in ranges:
        parts.append("%s.%s.%d" % (base36(start - last_end), base36(end - start), kind))
        last_end = end
    return ",".join(parts)


def main():
    actual = unicodedata.unidata_version
    if actual != PINNED_UNICODE_VERSION:
        print(
            "ERROR: this Python ships Unicode %s but the table is pinned to %s.\n"
            "Raising the version is deliberate: read this file's header."
            % (actual, PINNED_UNICODE_VERSION),
            file=sys.stderr,
        )
        return 1

    ranges = compute_ranges()
    blob = encode(ranges)
    digest = hashlib.sha256(blob.encode("ascii")).hexdigest()
    covered = sum(end - start + 1 for start, end, _ in ranges)

    with open(DATA_PATH, "w", encoding="ascii") as handle:
        handle.write(
            "# Pinned Unicode repertoire, generated by tools/unicode/gen_repertoire.py.\n"
            "# DO NOT EDIT BY HAND. Read the generator's header to learn why it exists.\n"
            "#\n"
            "# It is read by tools/packbuilder/repertoire.py and (already decoded into\n"
            "# generated Kotlin) by dict-core UnicodeRepertoire.kt. The sha256 ties the two\n"
            "# copies together: if somebody regenerates only one, both sides' tests fail.\n"
            "#\n"
            "# Classes: 1 = letter, 2 = combining mark, 3 = digit. What is absent is a separator.\n"
            "unicode_version %s\n"
            "ranges %d\n"
            "code_points %d\n"
            "sha256 %s\n"
            "data %s\n" % (PINNED_UNICODE_VERSION, len(ranges), covered, digest, blob)
        )

    _write_kotlin(blob, digest, len(ranges), covered)

    print("unicode %s | %d ranges | %d code points | %.1f KB"
          % (PINNED_UNICODE_VERSION, len(ranges), covered, len(blob) / 1024.0))
    print("  %s" % DATA_PATH)
    print("  %s" % KOTLIN_PATH)
    return 0


def _write_kotlin(blob, digest, range_count, covered):
    # The blob is split into chunks to stay away from the 64 KB limit on a String constant in the
    # class file, and so the generated file stays readable in a diff.
    chunks = [blob[i : i + 100] for i in range(0, len(blob), 100)]
    literal = "\n".join('        "%s" +' % chunk for chunk in chunks).rstrip(" +")

    with open(KOTLIN_PATH, "w", encoding="utf-8") as handle:
        handle.write('''package cl.fadiaz.dictionary.core

// GENERATED FILE, by tools/unicode/gen_repertoire.py -- DO NOT EDIT BY HAND.
// Source of truth: tools/unicode/repertoire.txt
//
// Classifies every code point as letter/digit, combining mark or separator, from its own data
// instead of Character.getType. The reason: every platform ships its own Unicode version
// (Python 3.9 -> 13.0, Java 26 -> 16, and Android a different one per release), and that made
// the builder and the app classify 14,773 code points differently. The symptom was not an error
// but a word that did not appear, and the same pack behaved differently depending on the watch's
// Wear OS version.
//
// Pinned to Unicode %s. Read the generator's header before touching the version.
//
// Pure Kotlin with no dependencies: this is one of the pieces that let :dict-core compile for
// any Kotlin Multiplatform target.
internal object UnicodeRepertoire {

    const val UNICODE_VERSION: String = "%s"

    /** sha256 of the encoded blob. Ties this copy to tools/unicode/repertoire.txt. */
    const val DIGEST: String = "%s"

    const val RANGE_COUNT: Int = %d

    const val CLASS_OTHER: Int = 0
    const val CLASS_LETTER: Int = 1
    const val CLASS_COMBINING_MARK: Int = 2

    /** Kept apart from letter because fuzzy() collapses repeated letters and digits it does not. */
    const val CLASS_DIGIT: Int = 3

    /** Start as a delta from the previous end, length, class. Base 36, comma separated. */
    internal const val ENCODED: String =
%s

    private val starts: IntArray
    private val ends: IntArray
    private val classes: ByteArray

    init {
        val entries = ENCODED.split(',')
        starts = IntArray(entries.size)
        ends = IntArray(entries.size)
        classes = ByteArray(entries.size)
        var previousEnd = 0
        for (i in entries.indices) {
            val entry = entries[i]
            val firstDot = entry.indexOf('.')
            val secondDot = entry.indexOf('.', firstDot + 1)
            val start = previousEnd + entry.substring(0, firstDot).toInt(36)
            val end = start + entry.substring(firstDot + 1, secondDot).toInt(36)
            starts[i] = start
            ends[i] = end
            classes[i] = entry.substring(secondDot + 1).toInt().toByte()
            previousEnd = end
        }
    }

    /**
     * A code point's class according to the pinned repertoire.
     *
     * A code point not in the table -- because it is punctuation, a symbol, or because it was
     * assigned in a Unicode version later than the floor -- returns [CLASS_OTHER], which norm()
     * treats as a separator. That is precisely the decision the platform used to take, and every
     * platform answered differently.
     */
    fun classify(codePoint: Int): Int {
        var low = 0
        var high = starts.size - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            when {
                codePoint < starts[middle] -> high = middle - 1
                codePoint > ends[middle] -> low = middle + 1
                else -> return classes[middle].toInt()
            }
        }
        return CLASS_OTHER
    }
}
''' % (PINNED_UNICODE_VERSION, PINNED_UNICODE_VERSION, digest, range_count, literal))


if __name__ == "__main__":
    sys.exit(main())
