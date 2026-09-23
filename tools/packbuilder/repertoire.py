"""Reads the pinned Unicode repertoire shared by the builder and the app.

THIS FILE HAS A GENERATED MIRROR:
    dict-core/src/main/kotlin/cl/fadiaz/dictionary/core/UnicodeRepertoire.kt

Both come out of tools/unicode/repertoire.txt. Unlike normalize.py / TextNormalizer.kt, which are
hand-written mirrors, these two are GENERATED from the same origin and the sha256 ties them: if
somebody regenerates only one, both sides' tests fail.

See the header of tools/unicode/gen_repertoire.py for why it exists.
"""

import bisect
import hashlib
import os

CLASS_OTHER = 0
CLASS_LETTER = 1
CLASS_COMBINING_MARK = 2
# Kept apart from letter because fuzzy() collapses repeated letters and digits it does not.
CLASS_DIGIT = 3

_DATA_PATH = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "unicode", "repertoire.txt"
)


def _load():
    header = {}
    with open(_DATA_PATH, encoding="ascii") as handle:
        for line in handle:
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            key, _, value = line.partition(" ")
            header[key] = value

    blob = header["data"]
    digest = hashlib.sha256(blob.encode("ascii")).hexdigest()
    if digest != header["sha256"]:
        raise AssertionError(
            "repertoire.txt esta corrupto: el sha256 no corresponde a los datos. "
            "Regenerar con tools/unicode/gen_repertoire.py"
        )

    starts = []
    ends = []
    classes = []
    previous_end = 0
    for entry in blob.split(","):
        start_delta, length, kind = entry.split(".")
        start = previous_end + int(start_delta, 36)
        end = start + int(length, 36)
        starts.append(start)
        ends.append(end)
        classes.append(int(kind))
        previous_end = end

    if len(starts) != int(header["ranges"]):
        raise AssertionError("repertoire.txt declara otra cantidad de rangos de la que trae")

    return header, starts, ends, classes


_HEADER, _STARTS, _ENDS, _CLASSES = _load()

UNICODE_VERSION = _HEADER["unicode_version"]
DIGEST = _HEADER["sha256"]
RANGE_COUNT = len(_STARTS)


def classify(code_point):
    """A code point's class according to the pinned repertoire.

    A code point absent from the table -- punctuation, a symbol, or one assigned in a Unicode
    version later than the floor -- returns CLASS_OTHER, which norm() treats as a separator. That
    is precisely the decision unicodedata used to take, and it differs between platforms.
    """
    index = bisect.bisect_right(_STARTS, code_point) - 1
    if index >= 0 and code_point <= _ENDS[index]:
        return _CLASSES[index]
    return CLASS_OTHER
