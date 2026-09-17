"""Genera la tabla de repertorio Unicode que comparten el builder y la app.

    python3 tools/unicode/gen_repertoire.py

Escribe dos artefactos, los dos commiteados:

    tools/unicode/repertoire.txt                       <- datos, fuente de verdad
    dict-core/src/main/.../UnicodeRepertoire.kt        <- generado desde el .txt

POR QUE EXISTE ESTO
-------------------
norm() clasifica cada code point en letra/digito, marca combinante, o separador. Al principio
esa clasificacion se delegaba en Character.getType (Kotlin) y unicodedata.category (Python), y
eso estaba roto: cada plataforma trae su propia version de Unicode.

    Python 3.9 -> Unicode 13.0        Java 26 -> Unicode 16
    Android    -> una version distinta POR CADA release del sistema

Medido sobre el repertorio completo: 14.773 code points se clasifican distinto entre Python 13
y Java 16, todos por estar asignados despues de Unicode 13. El sintoma en la app no es un error
sino una palabra que no aparece, y el MISMO pack se comportaria distinto en dos relojes con
distinta version de Wear OS.

POR QUE SE FIJA EN UNICODE 13
-----------------------------
13.0 es el piso: es lo que trae Python 3.9 (el interprete del builder) y esta por debajo de lo
que trae Android 13, que es el minSdk 33 de la app (Unicode 14). Toda plataforma por encima del
piso conoce el repertorio entero, y eso es lo que hace seguro seguir delegando las otras dos
operaciones. Verificado sobre los 133.730 code points de la tabla:

    lowercase() Java 26 vs Python 3.9 -> 0 diferencias
    NFD         Java 26 vs Python 3.9 -> 0 diferencias

NFD y lowercase siguen viniendo de la plataforma; la clasificacion no.

SUBIR DE VERSION UNICODE
------------------------
Es un acto deliberado, no un efecto secundario de actualizar Python:
  1. Comprobar que el nuevo piso lo soporten TODAS las plataformas (el Python mas viejo que
     construya packs y el Android mas viejo que corra la app).
  2. Regenerar con este script y revisar el diff de repertoire.txt.
  3. Subir NORM_VERSION en normalize.py y TextNormalizer.kt. Los packs viejos se rechazan solos.
"""

import hashlib
import os
import sys
import unicodedata

PINNED_UNICODE_VERSION = "13.0.0"

CLASS_OTHER = 0           # separador: puntuacion, simbolos, y todo lo no asignado en el piso
CLASS_LETTER = 1
CLASS_COMBINING_MARK = 2
CLASS_DIGIT = 3           # separado de letra porque fuzzy() colapsa letras repetidas y
                          # digitos no: "1000" no debe volverse "10"

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
    """Clasifica todo el espacio de code points y lo colapsa en rangos."""
    rows = []
    for cp in range(0x110000):
        if 0xD800 <= cp <= 0xDFFF:
            continue  # los surrogates no son code points por si mismos
        category = unicodedata.category(chr(cp))
        if category in LETTER_CATEGORIES:
            rows.append((cp, CLASS_LETTER))
        elif category == "Nd":
            rows.append((cp, CLASS_DIGIT))
        elif category == "Mn":
            rows.append((cp, CLASS_COMBINING_MARK))
        # El resto no se guarda: la ausencia ES CLASS_OTHER. Eso mantiene la tabla chica y
        # hace que un code point no asignado en el piso caiga en "separador" por defecto.

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
    """Codificacion compacta: inicio como delta del rango anterior, largo, clase. Base 36.

    El delta importa: los rangos estan muy juntos, asi que los deltas son chicos y la tabla
    entera entra en pocos KB de texto.
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
            "ERROR: este Python trae Unicode %s pero la tabla esta fijada en %s.\n"
            "Subir de version es deliberado: leer el encabezado de este archivo."
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
            "# Repertorio Unicode fijado, generado por tools/unicode/gen_repertoire.py.\n"
            "# NO EDITAR A MANO. Leer el encabezado del generador para saber por que existe.\n"
            "#\n"
            "# Lo leen tools/packbuilder/repertoire.py y (ya decodificado en Kotlin generado)\n"
            "# dict-core UnicodeRepertoire.kt. El sha256 ata las dos copias: si alguien\n"
            "# regenera una sola, los tests de los dos lados fallan.\n"
            "#\n"
            "# Clases: 1 = letra, 2 = marca combinante, 3 = digito. Lo ausente es separador.\n"
            "unicode_version %s\n"
            "ranges %d\n"
            "code_points %d\n"
            "sha256 %s\n"
            "data %s\n" % (PINNED_UNICODE_VERSION, len(ranges), covered, digest, blob)
        )

    _write_kotlin(blob, digest, len(ranges), covered)

    print("unicode %s | %d rangos | %d code points | %.1f KB"
          % (PINNED_UNICODE_VERSION, len(ranges), covered, len(blob) / 1024.0))
    print("  %s" % DATA_PATH)
    print("  %s" % KOTLIN_PATH)
    return 0


def _write_kotlin(blob, digest, range_count, covered):
    # El blob se parte en trozos para no acercarse al limite de 64 KB de una constante String
    # en el class file, y para que el archivo generado siga siendo legible en un diff.
    chunks = [blob[i : i + 100] for i in range(0, len(blob), 100)]
    literal = "\n".join('        "%s" +' % chunk for chunk in chunks).rstrip(" +")

    with open(KOTLIN_PATH, "w", encoding="utf-8") as handle:
        handle.write('''package cl.fadiaz.dictionary.core

// ARCHIVO GENERADO por tools/unicode/gen_repertoire.py -- NO EDITAR A MANO.
// Fuente de verdad: tools/unicode/repertoire.txt
//
// Clasifica cada code point en letra/digito, marca combinante o separador, con datos propios
// en vez de Character.getType. Motivo: cada plataforma trae su propia version de Unicode
// (Python 3.9 -> 13.0, Java 26 -> 16, y Android una distinta por cada release), y eso hacia
// que el builder y la app clasificaran 14.773 code points de forma distinta. El sintoma no era
// un error sino una palabra que no aparecia, y el mismo pack se comportaba distinto segun la
// version de Wear OS del reloj.
//
// Fijado en Unicode %s. Ver el encabezado del generador antes de tocar la version.
//
// Kotlin puro y sin dependencias: esta es una de las piezas que permiten que :dict-core
// compile para cualquier target de Kotlin Multiplatform.
internal object UnicodeRepertoire {

    const val UNICODE_VERSION: String = "%s"

    /** sha256 del blob codificado. Ata esta copia a tools/unicode/repertoire.txt. */
    const val DIGEST: String = "%s"

    const val RANGE_COUNT: Int = %d

    const val CLASS_OTHER: Int = 0
    const val CLASS_LETTER: Int = 1
    const val CLASS_COMBINING_MARK: Int = 2

    /** Separado de letra porque fuzzy() colapsa letras repetidas y digitos no. */
    const val CLASS_DIGIT: Int = 3

    /** Inicio como delta del fin anterior, largo, clase. Base 36, separado por comas. */
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
     * Clase de un code point segun el repertorio fijado.
     *
     * Un code point que no este en la tabla -- porque es puntuacion, un simbolo, o porque se
     * asigno en una version de Unicode posterior al piso -- devuelve [CLASS_OTHER], que norm()
     * trata como separador. Esa es justamente la decision que antes tomaba la plataforma y
     * cada una respondia distinto.
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
