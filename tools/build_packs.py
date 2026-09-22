#!/usr/bin/env python3
"""Construye TODOS los packs, en orden, y separa lo intermedio de lo que se publica.

    python3 tools/build_packs.py <raiz-de-datos> [--dry-run] [--solo es|en]

## Por que existe

El orden del rebuild estaba escrito **solo en prosa** (`tools/CLAUDE.md`), y ahi no se puede
correr ni comprobar. Y no es un detalle: `--flexiones` lee un pack YA CONSTRUIDO del idioma
destino, asi que el bilingue **tiene que** ir despues del ingles. Saltarse un flag no da error --
el pack sale bien formado, pasa `verify_pack.py` y es **peor en silencio**: sin `--flexiones`, la
cobertura inversa del bilingue cae 8,6 puntos, medidos.

Pedido: *«que los scripts que construyen estos packs esten en el repo pero los packs no, porque
son muy pesados»*. Esto es ese script. Los artefactos siguen fuera del repo, y `.gitignore` ya
cubre `/*.db`.

## La estructura que espera

    <raiz>/dumps/   las entradas: los .jsonl de kaikki, los corpus, WordNet, Wikidata

⚠️ **Cada paso tiene que recibir el dump que SU lector sabe leer, y no es obvio**: los
cuatro son formatos distintos y tres de ellos vienen comprimidos. `sources/wikidata` hace
`json.loads` por linea sobre el bz2 de lexemas; `wordnet.spanish` abre el `.tab` de OMW
como TEXTO PLANO y parte por tabs; `wordnet.english` lee WN-LMF comprimido. Pasarle a
cualquiera de ellos el archivo de otro **revienta en la primera linea**, que es la suerte
de este caso: la version anterior le pasaba los volcados de DBnary a los dos primeros.

⚠️ **Y DBnary no era una alternativa razonable, era un descuido**: esta en
`docs/fuentes.md` como **rechazada y medida** --misma fuente que el Wikcionario, la mitad
del rendimiento--, y sus `.ttl` estan en el directorio porque se bajaron para medirla.
Lo fija `test_build_packs`, que ahora comprueba los nombres ademas del orden.
    <raiz>/build/   los INTERMEDIOS: packs que son entrada de un merge y no se distribuyen
    <raiz>/dist/    lo que se publica, y lo unico que `packserver.py` debe servir

⚠️ **La separacion es el punto.** Con todo en un directorio plano, `es-def-wd` --que es una
ENTRADA del merge espanol, no un diccionario para nadie-- aparecio en el catalogo del emulador
como un pack descargable. Un diccionario de una sola fuente es justo el modelo que se descarto.

⚠️ **El ingles completo esta en `dist/` aunque TAMBIEN sea una entrada** (del bilingue, por
`--flexiones`). Es las dos cosas, y lo que decide donde vive es si se distribuye.
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys

AQUI = os.path.dirname(os.path.abspath(__file__))
BUILD_PACK = os.path.join(AQUI, "packbuilder", "build_pack.py")
BUILD_CORE = os.path.join(AQUI, "packbuilder", "build_core.py")
VERIFY = os.path.join(AQUI, "packbuilder", "verify_pack.py")

DUMPS, BUILD, DIST = "dumps", "build", "dist"

#: Los presupuestos de cada nivel, en MB (D-215).
#:
#: ⚠️ **Un idioma cuyo `full` ya cabe en el presupuesto de `main` NO genera `main`**: el espanol
#: completo son 73,6 MB, por debajo de los 130, asi que un `main` espanol seria un segundo pack con
#: el mismo contenido. Eso lo decide [_niveles], no una lista escrita a mano.
PRESUPUESTO = {"core": 40, "main": 130}

#: La lista de frecuencias de cada idioma. Es la misma con la que se construyo el `full`, y usar
#: la misma importa: el corte de un nivel y el `rank` del pack tienen que hablar del mismo corpus.
LISTAS_DE_FRECUENCIA = {"en": "freq-en-opensubs.txt", "es": "freq-es-opensubs.txt"}


def _ruta(raiz, *partes):
    return os.path.join(raiz, *partes)


def _niveles(raiz, idioma, tamano_full_mb):
    """Los niveles derivados que le tocan a un idioma, dado lo que pesa su `full`."""
    pasos = []
    full = _ruta(raiz, DIST, "%s-full.db" % idioma)
    for nivel in ("core", "main"):
        presupuesto = PRESUPUESTO[nivel]
        if nivel == "main" and tamano_full_mb is not None and tamano_full_mb <= presupuesto:
            continue
        pasos.append({
            "nombre": "%s-%s" % (idioma, nivel),
            "salida": _ruta(raiz, DIST, "%s-%s.db" % (idioma, nivel)),
            # ⚠️ **`--frecuencias` no es opcional en la practica.** Sin la lista el nivel se
            # corta por `rank`, y eso esta medido: entre 0,97 y 1,53 puntos menos de cobertura
            # del corpus, con menos lemas adentro. `build_core` avisa por stderr si falta.
            "comando": [sys.executable, BUILD_CORE, full,
                        _ruta(raiz, DIST, "%s-%s.db" % (idioma, nivel)),
                        "--budget-mb", str(presupuesto), "--tier", nivel,
                        "--frecuencias", _ruta(raiz, DUMPS, LISTAS_DE_FRECUENCIA[idioma])],
            "verifica": True,
        })
    return pasos


def plan(raiz, solo=None, tamanos=None):
    """El plan completo, **sin ejecutar nada**.

    Se devuelve en vez de correrse para que el gate pueda comprobar el ORDEN sin tener los dumps,
    que son 4,4 GB. Es el mismo reparto que `devpack.py`: la forma del plan entra al gate, correrlo
    necesita los datos y no entra.

    `tamanos` mapea idioma -> MB de su `full` ya construido, para decidir si toca un `main`. En un
    plan en seco no se sabe, y entonces se planean los dos niveles.
    """
    tamanos = tamanos or {}
    pasos = []

    # ⚠️ **El ingles va PRIMERO**, y no es alfabetico: el bilingue lo necesita construido para
    # `--flexiones`.
    if solo in (None, "en"):
        pasos.append({
            "nombre": "en-full",
            "salida": _ruta(raiz, DIST, "en-full.db"),
            "comando": [sys.executable, BUILD_PACK, "en",
                        _ruta(raiz, DUMPS, "en.jsonl"),
                        _ruta(raiz, DIST, "en-full.db"),
                        "--tesauro", _ruta(raiz, DUMPS, "oewn-2024.xml.gz"),
                        "--frecuencias", _ruta(raiz, DUMPS, "freq-en-opensubs.txt")],
            "verifica": True,
        })

    if solo in (None, "es"):
        # Wikidata: una ENTRADA del merge espanol. No se distribuye, asi que va a build/.
        pasos.append({
            "nombre": "es-wd (intermedio)",
            "salida": _ruta(raiz, BUILD, "es-def-wd.db"),
            "comando": [sys.executable, BUILD_PACK, "es-wd",
                        _ruta(raiz, DUMPS, "wikidata-lexemes.json.bz2"),
                        _ruta(raiz, BUILD, "es-def-wd.db")],
            "verifica": False,
        })
        pasos.append({
            "nombre": "es-full",
            "salida": _ruta(raiz, DIST, "es-full.db"),
            "comando": [sys.executable, BUILD_PACK, "es",
                        _ruta(raiz, DUMPS, "es.jsonl"),
                        _ruta(raiz, DIST, "es-full.db"),
                        "--frases", _ruta(raiz, DUMPS, "tatoeba-spa.tsv"),
                        "--tesauro", _ruta(raiz, DUMPS, "wn-data-spa.tab"),
                        "--frecuencias", _ruta(raiz, DUMPS, "freq-es-opensubs.txt"),
                        "--sumar", "es-wd", _ruta(raiz, BUILD, "es-def-wd.db")],
            "verifica": True,
        })

    # El bilingue, DESPUES del ingles. No tiene niveles: su proposito no es un tamano del mismo
    # diccionario, sino otra cosa (D-215).
    if solo is None:
        pasos.append({
            "nombre": "es-en (bilingue)",
            "salida": _ruta(raiz, DIST, "es-en.db"),
            "comando": [sys.executable, BUILD_PACK, "es-en",
                        _ruta(raiz, DUMPS, "es-en-wikt.jsonl"),
                        _ruta(raiz, DIST, "es-en.db"),
                        "--flexiones", _ruta(raiz, DIST, "en-full.db"),
                        "--frecuencias", _ruta(raiz, DUMPS, "freq-es-opensubs.txt")],
            "verifica": True,
        })

    for idioma in ("en", "es"):
        if solo in (None, idioma):
            pasos.extend(_niveles(raiz, idioma, tamanos.get(idioma)))
    return pasos


def tamano_mb(path):
    return os.path.getsize(path) / 1048576 if os.path.exists(path) else None


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("raiz", help="el directorio de datos, FUERA del repo")
    parser.add_argument("--dry-run", action="store_true", help="imprime el plan y no corre nada")
    parser.add_argument("--solo", choices=("es", "en"), help="un solo idioma")
    args = parser.parse_args(argv)

    raiz = os.path.abspath(args.raiz)
    for sub in (DUMPS, BUILD, DIST):
        os.makedirs(_ruta(raiz, sub), exist_ok=True)

    tamanos = {i: tamano_mb(_ruta(raiz, DIST, "%s-full.db" % i)) for i in ("es", "en")}
    pasos = plan(raiz, solo=args.solo, tamanos=tamanos)

    if args.dry_run:
        for i, paso in enumerate(pasos, 1):
            print("%2d. %-18s -> %s" % (i, paso["nombre"], os.path.relpath(paso["salida"], raiz)))
            print("    " + " ".join(os.path.basename(x) if os.sep in x else x
                                    for x in paso["comando"][1:]))
        return 0

    for i, paso in enumerate(pasos, 1):
        print("\n[%d/%d] %s" % (i, len(pasos), paso["nombre"]), flush=True)
        if subprocess.call(paso["comando"]) != 0:
            print("  fallo %s; se para aqui" % paso["nombre"], file=sys.stderr)
            return 1
        # ⚠️ **Se verifica cada pack ANTES de seguir.** Un pack a medias se abre sin error y
        # devuelve menos palabras de las que tiene; encadenar sobre el propaga el defecto.
        if paso["verifica"] and subprocess.call([sys.executable, VERIFY, paso["salida"]]) != 0:
            print("  %s no paso verify_pack; se para aqui" % paso["nombre"], file=sys.stderr)
            return 1
    print("\nlisto. Lo publicable esta en %s" % _ruta(raiz, DIST))
    return 0


if __name__ == "__main__":
    sys.exit(main())
