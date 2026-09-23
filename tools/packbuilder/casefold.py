"""Pinned case folding: the operation the standard defines for *caseless matching*.

THIS FILE HAS A MIRROR: dict-core/src/main/kotlin/cl/fadiaz/dictionary/core/CaseFolding.kt

⚠️ **It reads the generated table instead of calling `str.casefold()`, and that is the point.**
Python has `casefold()` and Java does **not**: the only thing that offers it is ICU, and D-003
forbids the platform's Unicode data because every Android ships its own version --14,773 code
points classified differently between watches. If this side called the function and the other read
the table, the two would drift apart the day Python's version changes, **with no error and no
log**.

The standard is explicit (rule R4, section 3.13): `toLowerCase()` is **case mapping**, to DISPLAY
text; `toCaseFold()` is **case folding**, to COMPARE it. Measured over the pinned repertoire, **242
of 133,730 code points** differ: `ß`→`ss`, `ſ`→`s`, `ς`→`σ`.

It is generated with `python3 tools/unicode/gen_casefold.py`, which aborts if the Python running it
does not ship the Unicode version the repertoire is pinned to.
"""

import os

_DATA_PATH = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "unicode", "casefold.txt"
)


def _cargar():
    cabecera, blob = {}, ""
    with open(_DATA_PATH, encoding="ascii") as handle:
        for linea in handle:
            if linea.startswith("#") or not linea.strip():
                continue
            clave, _, valor = linea.strip().partition(" ")
            if clave == "data":
                blob = valor
            else:
                cabecera[clave] = valor
    mapa = {}
    for entrada in blob.split(","):
        cp, _, destino = entrada.partition(":")
        mapa[int(cp, 16)] = "".join(
            chr(int(destino[i : i + 4], 16)) for i in range(0, len(destino), 4)
        )
    return cabecera, mapa


HEADER, TABLE = _cargar()
UNICODE_VERSION = HEADER["unicode_version"]
DIGEST = HEADER["sha256"]
PAIR_COUNT = int(HEADER["pairs"])


def fold(text):
    """`toCaseFold()` over the pinned repertoire: lowercase first, then the table.

    `lower()` goes first because it resolves the vast majority of cases identically in both
    languages --D-004 measured it, zero differences over 133,730 code points-- and the table
    corrects the ones the standard asks for differently.
    """
    bajo = text.lower()
    if not TABLE:
        return bajo
    return "".join(TABLE.get(ord(c), c) for c in bajo)
