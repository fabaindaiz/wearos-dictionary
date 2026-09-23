"""Text normalization for building the packs.

THIS FILE HAS A MIRROR:
    dict-core/src/main/kotlin/cl/fadiaz/dictionary/core/TextNormalizer.kt
    dict-core/src/main/kotlin/cl/fadiaz/dictionary/core/FuzzyProfile.kt

The `norm` and `fuzzy` keys are computed here while building the pack and computed again on the
watch over what the user types. If the two implementations diverge by even one character, the
query stops matching and the symptom is "that word is missing": no error, no crash, no log. So any
change here is replicated in Kotlin in the same commit, cases are added to
vectors/normalization-vectors.tsv (which tests both sides), and NORM_VERSION is bumped.

Only str.replace() is used and never regular expressions: literal replacement has identical
semantics in Python and in Kotlin (global, left to right, non-overlapping), whereas two
"equivalent" regexes are exactly the kind of thing that diverges in silence.
"""

import unicodedata

import repertoire

# Bumped when the result of norm() or fuzzy() changes. Written to meta.norm_version.
#
# 1 -> 2: code point classification moved from unicodedata.category to repertoire.py.
NORM_VERSION = 2

# Letters NFD does not decompose and that we want folded anyway, so that "Straße" and "strasse"
# land on the same key.
EXPANSIONS = {
    "ß": "ss",
    "æ": "ae",
    "œ": "oe",
    "ø": "o",
    "đ": "d",
    "ð": "d",
    "þ": "th",
    "ł": "l",
    "ı": "i",
    "ŋ": "ng",
    "ſ": "s",
    # Latin ligatures: NFD does not decompose them (that is NFKD, which would bring other,
    # less predictable effects such as "½" -> "1/2").
    "ﬁ": "fi",
    "ﬂ": "fl",
    "ﬀ": "ff",
}

# Code point classification does NOT come from unicodedata: it comes from repertoire.py, which
# carries its own data pinned to Unicode 13.
#
# The reason: every platform ships its own Unicode version (this Python -> 13.0, Java 26 -> 16,
# and Android a different one per system release). Measured over the whole repertoire, 14,773 code
# points classified differently between Python and Java, and that made the same pack behave
# differently on two watches with different Wear OS versions.
#
# unicodedata is still used ONLY for NFD, and that is safe: over the 133,730 code points of the
# pinned repertoire, Java 26's NFD and Python 3.9's give zero differences.

# Per-language phonetic folding rules, in order. The order is part of the contract:
# "ce" -> "se" has to run before "c" -> "k", or "cerrar" ends up as "kerar" and stops
# colliding with "serrar".
FUZZY_PROFILES = {
    "generic": (),
    # Spanish: seseo (c/z/s), b/v, y/ll, silent h, silent u of que/qui/gue/gui.
    # "ch" is protected with a numeric marker before the h is deleted and restored at the end.
    # Known limitation: "mexico" -> "mesiko" and "mejico" -> "mejiko" do not collide; treating
    # the x as a j would break "examen" -> "esamen", which is the more frequent case.
    "es": (
        ("ch", "8"),
        ("qu", "k"),
        ("gue", "ge"),
        ("gui", "gi"),
        ("h", ""),
        ("ll", "y"),
        ("v", "b"),
        ("z", "s"),
        ("ce", "se"),
        ("ci", "si"),
        ("c", "k"),
        ("y", "i"),
        ("x", "s"),
        ("w", "b"),
        ("8", "ch"),
    ),
    # Ingles: digrafos mudos (kn, wr, gh), ph/f, c dura y blanda, x/ks, y/i.
    "en": (
        ("ck", "k"),
        ("ph", "f"),
        ("wh", "w"),
        ("kn", "n"),
        ("wr", "r"),
        ("gh", ""),
        ("qu", "kw"),
        ("ce", "se"),
        ("ci", "si"),
        ("cy", "si"),
        ("c", "k"),
        ("x", "ks"),
        ("y", "i"),
    ),
    # German: sch/s, v/f, w/v, z/ts, h digraphs. The eszett's ss was already produced by norm().
    "de": (
        ("sch", "s"),
        ("ck", "k"),
        ("ph", "f"),
        ("th", "t"),
        ("dt", "t"),
        ("v", "f"),
        ("w", "v"),
        ("z", "ts"),
        ("y", "i"),
    ),
}


def norm(text):
    """The indexing key: lowercase, no diacritics, letters/digits only, spaces collapsed.

    The result is compared under BINARY collation, so no ICU is needed on the watch and the
    behaviour is identical on every device.
    """
    lowered = text.lower()
    expanded = "".join(EXPANSIONS.get(ch, ch) for ch in lowered)
    decomposed = unicodedata.normalize("NFD", expanded)

    kept = []
    for ch in decomposed:
        klass = repertoire.classify(ord(ch))
        if klass == repertoire.CLASS_COMBINING_MARK:
            # Combining marks are the diacritics NFD left behind.
            continue
        if klass in (repertoire.CLASS_LETTER, repertoire.CLASS_DIGIT):
            kept.append(ch)
        else:
            # Puntuacion, simbolos y todo lo ajeno al repertorio fijado pasan a ser
            # separadores, no desaparecen: "self-made" debe quedar "self made".
            kept.append(" ")
    return " ".join("".join(kept).split())


def fuzzy(text, profile):
    """The typo-tolerant key: norm() plus phonetic folding and a collapse of repeated letters.

    It is deliberately aggressive because it is only used as a last resort, when the prefix search
    returned nothing, and afterwards it is reordered by edit distance. False positives are cheap
    here; false negatives are not.
    """
    if profile not in FUZZY_PROFILES:
        raise ValueError("perfil fuzzy desconocido: %r" % (profile,))
    result = norm(text)
    for source, replacement in FUZZY_PROFILES[profile]:
        result = result.replace(source, replacement)
    return " ".join(_collapse_doubled_letters(result).split())


def _collapse_doubled_letters(text):
    """Collapses adjacent repeated letters: "correr" -> "corer".

    Letters only: collapsing digits would turn "1000" into "10", which is why the repertoire
    separates the two classes.
    """
    out = []
    previous = None
    for ch in text:
        if ch == previous and repertoire.classify(ord(ch)) == repertoire.CLASS_LETTER:
            continue
        out.append(ch)
        previous = ch
    return "".join(out)
