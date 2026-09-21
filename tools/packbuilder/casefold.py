"""Case folding fijado: la operacion que el estandar define para *caseless matching*.

ESTE ARCHIVO TIENE UN ESPEJO: dict-core/src/main/kotlin/cl/fadiaz/dictionary/core/CaseFolding.kt

⚠️ **Lee la tabla generada en vez de llamar a `str.casefold()`, y eso es el punto.** Python tiene
`casefold()` y Java **no**: lo unico que lo ofrece es ICU, y D-003 prohibe los datos Unicode de la
plataforma porque cada Android trae su version --14.773 code points se clasificaban distinto entre
relojes. Si este lado llamara a la funcion y el otro leyera la tabla, los dos se separarian el dia
que cambie la version de Python, **sin error y sin log**.

El estandar es explicito (regla R4, seccion 3.13): `toLowerCase()` es **case mapping**, para
MOSTRAR texto; `toCaseFold()` es **case folding**, para COMPARARLO. Medido sobre el repertorio
fijado, **242 de 133.730 code points** difieren: `ß`→`ss`, `ſ`→`s`, `ς`→`σ`.

Se genera con `python3 tools/unicode/gen_casefold.py`, que aborta si el Python que lo corre no
trae la version de Unicode a la que esta fijado el repertorio.
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
    """`toCaseFold()` sobre el repertorio fijado: minusculas y despues la tabla.

    `lower()` va primero porque resuelve la inmensa mayoria de los casos identico en los dos
    lenguajes --D-004 lo midio, cero diferencias sobre 133.730 code points-- y la tabla corrige
    los que el estandar pide distinto.
    """
    bajo = text.lower()
    if not TABLE:
        return bajo
    return "".join(TABLE.get(ord(c), c) for c in bajo)
