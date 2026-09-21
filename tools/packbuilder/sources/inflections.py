"""Flexiones del idioma DESTINO, leidas de un pack ya construido.

## Que problema cierra, medido

La direccion inversa de un pack bilingue esta floja y **el motivo es estructural, no accidental**:
el lado español tiene la tabla `form`, asi que toda flexion llega a su lema; el lado ingles solo
tiene las claves derivadas, asi que una flexion inglesa se encuentra **unicamente si alguna glosa
la escribe**. Medido sobre las palabras inglesas mas usadas, la cobertura era **92,7 % / 85,9 % /
78,1 %** en el top 1.000 / 3.000 / 8.000, y con las flexiones sube a **99,4 % / 99,6 / 98,9 %**.

Lo que sigue faltando despues de esto **no es vocabulario sino el tokenizador**: `didn`, `doesn`,
`wasn`, `shouldn` son mitades de contracciones que `tatoeba.frequencies` parte por el apostrofo.

## Por que la fuente es un PACK y no un dump

Las flexiones inglesas ya estan construidas y podadas dentro de `en-def-wikt.db`. Volver al dump
de 3,2 GB para recalcular lo que ya tenemos seria otra hora de build y una segunda poda que puede
divergir de la primera -- el mismo razonamiento por el que `build_core.py` **deriva** en vez de
reconstruir (D-175).

## El filtro NO es opcional, y se descubrio leyendo filas

⚠️ La tabla `form` del pack ingles **esta sucia**: de sus 985.992 filas, el **38,7 % contiene un
espacio** --`big fat hairy deals`, `ate breathed and slept`, `1 000 000 questions`-- y
`no table tags` (577) y `glossary` (575) son **artefactos del parser de wiktextract** sentados ahi
como si fueran flexiones. El español, en comparacion, tiene como forma mas repetida `unas`, 16
veces.

Quedarse con una palabra, alfabetica y no artefacto **descarta el 35 % de los candidatos y no
mueve la cobertura ni una decima**. El filtro sale gratis.
"""

import sqlite3

# Artefactos del parser que aparecen en `form` como si fueran flexiones. Se delatan por
# repeticion: una flexion real casi no se repite.
ARTEFACTOS = {"no table tags", "glossary"}


def _sirve(forma, lema):
    """Una flexion sirve si es UNA palabra, alfabetica, y no un artefacto del parser."""
    return (
        forma not in ARTEFACTOS
        and " " not in forma
        and forma != lema
        and any(c.isalpha() for c in forma)
        and not forma[0].isdigit()
    )


def por_lema(pack, claves=None):
    """`lema -> [flexion, ...]`, leido de `pack`.

    `claves` acota a las palabras por las que el pack bilingue ya llega a alguna entrada. Con
    `None` trae el mapa entero, que es lo que hace el build real: **las claves no se conocen hasta
    haber leido todos los registros**, y consultar por registro serian 124.000 consultas. El mapa
    completo del pack ingles son ~600.000 pares despues del filtro y entra de sobra en memoria de
    una maquina de build.
    """
    con = sqlite3.connect("file:%s?mode=ro" % pack, uri=True)
    try:
        # Una sola pasada y un solo JOIN: `form` tiene PRIMARY KEY (norm, entry_id), asi que
        # filtrar por `entry_id` **no usa indice** y seria un scan por lema. Eso ya hizo que un
        # build no terminara nunca (ver build_core.py).
        sql = "SELECT f.norm, e.norm FROM form f JOIN entry e ON e.id = f.entry_id"
        if claves is not None:
            con.execute("CREATE TEMP TABLE claves(norm TEXT PRIMARY KEY)")
            con.executemany("INSERT OR IGNORE INTO claves VALUES (?)", ((c,) for c in claves))
            sql += " WHERE e.norm IN (SELECT norm FROM claves)"
        salida = {}
        for forma, lema in con.execute(sql):
            if not _sirve(forma, lema):
                continue
            if claves is not None and forma in claves:
                continue
            salida.setdefault(lema, [])
            if forma not in salida[lema]:
                salida[lema].append(forma)
        return salida
    finally:
        con.close()
