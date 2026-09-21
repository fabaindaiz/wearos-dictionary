#!/usr/bin/env python3
"""Genera la tabla de CASE FOLDING fijada que comparten el builder y la app.

    python3 tools/unicode/gen_casefold.py

## Por que existe, y por que no alcanza con `lowercase()`

El codigo que nombra una acepcion (`payload.sense_code`) pliega la glosa antes de hashearla, para
que dos diccionarios que escriben la misma definicion con otra caja la reconozcan como la misma.
La operacion que el estandar define para eso es **`toCaseFold()`** --regla R4, seccion 3.13 del
Estandar Unicode, referenciada por UAX #31 como la operacion para *caseless matching*-- y el
estandar es explicito: `toLowerCase()` es **case mapping**, que sirve para MOSTRAR texto;
`toCaseFold()` es **case folding**, que sirve para COMPARARLO.

Se usaba `lowercase()`. Medido sobre el repertorio fijado, **242 de 133.730 code points (0,181 %)**
dan resultados distintos: `ß`→`ss`, `ſ`→`s`, `ς`→`σ`, `ͅ`→`ι`. Sobre las glosas reales son 8 de
97.337 en español y 19 de 162.820 en ingles -- `ß-endorfina`, `µm`, texto griego.

## Por que una TABLA y no la funcion de la plataforma

⚠️ **Python tiene `str.casefold()`; Java y Kotlin no tienen equivalente.** Lo unico que lo ofrece
es ICU, y **D-003 prohibe los datos Unicode de la plataforma**: cada Android trae su propia
version y eso clasificaba 14.773 code points distinto entre relojes. Usar `android.icu` para
plegar reintroduciria exactamente el bug que D-003 existe para impedir.

Asi que se fija la tabla, que es el mismo patron que `UnicodeRepertoire` ya usa y por el mismo
motivo: **los dos lenguajes aplican los MISMOS datos**, y el sha256 ata las dos copias.

## Subir de version es un acto deliberado

Aborta si el Python que lo corre no trae **exactamente** la version de Unicode a la que esta
fijado el repertorio: una tabla de plegado de Unicode 16 contra un repertorio de Unicode 13 seria
una inconsistencia silenciosa entre dos archivos que se leen juntos.

⚠️ **Y regenerarla invalida todos los `sense_code` ya escritos.** Es el mismo peso que subir
`NORM_VERSION`: se hace a sabiendas o no se hace.
"""

import hashlib
import os
import sys
import unicodedata

PINNED_UNICODE_VERSION = "13.0.0"

_AQUI = os.path.dirname(os.path.abspath(__file__))
DATA_PATH = os.path.join(_AQUI, "casefold.txt")
KOTLIN_PATH = os.path.join(
    _AQUI, "..", "..", "dict-core", "src", "main", "kotlin", "cl", "fadiaz",
    "dictionary", "core", "CaseFolding.kt",
)


def compute_pairs():
    """`[(code_point, plegado)]` para lo que `casefold()` hace y `lower()` no.

    Solo entra la diferencia: lo que `lower()` ya resuelve bien no necesita tabla, y meterlo
    seria duplicar datos que la plataforma da identicos en los dos lenguajes (D-004).
    """
    pares = []
    for cp in range(0x110000):
        ch = chr(cp)
        bajo, plegado = ch.lower(), ch.casefold()
        if bajo != plegado:
            pares.append((cp, plegado))
    return pares


def encode(pares):
    """`cp:plegado` separados por comas, el cp en base 16 y el plegado en escapes \\uXXXX."""
    return ",".join(
        "%x:%s" % (cp, "".join("%04x" % ord(c) for c in plegado)) for cp, plegado in pares
    )


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

    pares = compute_pairs()
    # ⚠️ El lado Kotlin indexa por `Char` para no usar APIs de la JVM que D-017 prohibe en
    # :dict-core. Eso vale solo si nada cae fuera del BMP, asi que se comprueba aca en vez de
    # confiarlo: si algun dia Unicode agrega un plegado fuera del BMP, esto aborta y avisa.
    fuera = [cp for cp, dest in pares
             if cp > 0xFFFF or any(ord(c) > 0xFFFF for c in dest)]
    if fuera:
        print("ERROR: %d pares caen fuera del BMP (%s...). El lado Kotlin indexa por Char:\n"
              "hay que volver a code points y mover esas APIs a PlatformJvm.kt."
              % (len(fuera), ", ".join(hex(c) for c in fuera[:5])), file=sys.stderr)
        return 1
    blob = encode(pares)
    digest = hashlib.sha256(blob.encode("ascii")).hexdigest()

    with open(DATA_PATH, "w", encoding="ascii") as handle:
        handle.write(
            "# Tabla de case folding fijada, generada por tools/unicode/gen_casefold.py.\n"
            "# NO EDITAR A MANO. Leer el encabezado del generador para saber por que existe.\n"
            "#\n"
            "# Solo lleva lo que casefold() hace y lower() no: lo demas lo dan las dos\n"
            "# plataformas identico y esta medido (D-004). El sha256 ata esta copia con\n"
            "# dict-core CaseFolding.kt.\n"
            "#\n"
            "unicode_version %s\n"
            "pairs %d\n"
            "sha256 %s\n"
            "data %s\n" % (PINNED_UNICODE_VERSION, len(pares), digest, blob)
        )

    _write_kotlin(blob, digest, len(pares))
    print("Tabla de case folding fijada en Unicode %s" % PINNED_UNICODE_VERSION)
    print("  %d pares · sha256 %s" % (len(pares), digest[:16]))
    print("  %s" % DATA_PATH)
    print("  %s" % KOTLIN_PATH)
    return 0


def _write_kotlin(blob, digest, pair_count):
    chunks = [blob[i : i + 100] for i in range(0, len(blob), 100)]
    literal = "\n".join('        "%s" +' % chunk for chunk in chunks).rstrip(" +")
    with open(KOTLIN_PATH, "w", encoding="utf-8") as handle:
        handle.write('''package cl.fadiaz.dictionary.core

// ARCHIVO GENERADO por tools/unicode/gen_casefold.py -- NO EDITAR A MANO.
// Fuente de verdad: tools/unicode/casefold.txt
//
// Case folding: la operacion que el estandar define para *caseless matching* --regla R4,
// seccion 3.13 del Estandar Unicode-- y que NO es `lowercase()`. El estandar lo separa
// explicitamente: case mapping sirve para MOSTRAR texto, case folding para COMPARARLO.
//
// ⚠️ Es una TABLA y no una llamada a la plataforma porque Java y Kotlin **no tienen**
// `toCaseFold()`: lo unico que lo ofrece es ICU, y D-003 prohibe los datos Unicode de la
// plataforma porque cada Android trae su version --14.773 code points se clasificaban distinto
// entre relojes. Fijar la tabla es el mismo patron que UnicodeRepertoire, por el mismo motivo.
//
// Solo lleva los %d code points donde casefold() difiere de lower(); el resto lo dan las dos
// plataformas identico y esta medido (D-004).
//
// Fijado en Unicode %s. Regenerar invalida todos los sense_code ya escritos.
internal object CaseFolding {

    const val UNICODE_VERSION: String = "%s"

    /** sha256 del blob codificado. Ata esta copia a tools/unicode/casefold.txt. */
    const val DIGEST: String = "%s"

    const val PAIR_COUNT: Int = %d

    /** `cp:plegado` en hexadecimal, separados por comas. */
    internal const val ENCODED: String =
%s

    // ⚠️ **Se indexa por `Char` y no por code point, y eso esta verificado en el generador**:
    // ninguno de los pares --ni origen ni destino-- cae fuera del BMP. Iterar por char evita
    // `Character.charCount` y `appendCodePoint`, que son APIs de la JVM y D-017 no las admite
    // fuera de PlatformJvm.kt.
    private val mapa: Map<Char, String> = buildMap {
        for (entrada in ENCODED.split(',')) {
            val corte = entrada.indexOf(':')
            val origen = entrada.substring(0, corte).toInt(16).toChar()
            val destino = entrada.substring(corte + 1)
            val sb = StringBuilder()
            var i = 0
            while (i < destino.length) {
                sb.append(destino.substring(i, i + 4).toInt(16).toChar())
                i += 4
            }
            put(origen, sb.toString())
        }
    }

    /**
     * `toCaseFold()` sobre el repertorio fijado: minusculas y despues la tabla.
     *
     * `lowercase()` va primero porque resuelve la inmensa mayoria de los casos identico en los
     * dos lenguajes (D-004, cero diferencias sobre 133.730 code points); la tabla corrige los
     * %d donde el estandar pide otra cosa.
     */
    fun fold(text: String): String {
        val bajo = text.lowercase()
        if (bajo.none { it in mapa }) return bajo
        val salida = StringBuilder(bajo.length)
        for (c in bajo) salida.append(mapa[c] ?: c)
        return salida.toString()
    }
}
''' % (pair_count, PINNED_UNICODE_VERSION, PINNED_UNICODE_VERSION, digest, pair_count,
       literal, pair_count))


if __name__ == "__main__":
    sys.exit(main())
