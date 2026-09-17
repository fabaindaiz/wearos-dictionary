"""Lee el repertorio Unicode fijado que comparten el builder y la app.

ESTE ARCHIVO TIENE UN ESPEJO GENERADO:
    dict-core/src/main/kotlin/cl/fadiaz/dictionary/core/UnicodeRepertoire.kt

Los dos salen de tools/unicode/repertoire.txt. A diferencia de normalize.py / TextNormalizer.kt,
que son espejos escritos a mano, estos dos se GENERAN del mismo origen y el sha256 los ata: si
alguien regenera uno solo, los tests de ambos lados fallan.

Ver el encabezado de tools/unicode/gen_repertoire.py para el motivo de existir.
"""

import bisect
import hashlib
import os

CLASS_OTHER = 0
CLASS_LETTER = 1
CLASS_COMBINING_MARK = 2
# Separado de letra porque fuzzy() colapsa letras repetidas y digitos no.
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
    """Clase de un code point segun el repertorio fijado.

    Un code point ausente de la tabla -- puntuacion, simbolo, o asignado en una version de
    Unicode posterior al piso -- devuelve CLASS_OTHER, que norm() trata como separador. Esa es
    justamente la decision que antes tomaba unicodedata y que difiere entre plataformas.
    """
    index = bisect.bisect_right(_STARTS, code_point) - 1
    if index >= 0 and code_point <= _ENDS[index]:
        return _CLASSES[index]
    return CLASS_OTHER
