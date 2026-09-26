#!/usr/bin/env python3
"""Builds ALL the packs, in order, and separates the intermediate from what gets published.

    python3 tools/build_packs.py <data-root> [--dry-run] [--solo es|en]

## Why it exists

The rebuild's order was written **only in prose** (`tools/CLAUDE.md`), and there it can neither be
run nor checked. And it is not a detail: `--flexiones` reads an ALREADY BUILT pack of the target
language, so the bilingual one **has to** come after English. Skipping a flag raises no error --
the pack comes out well formed, passes `verify_pack.py` and is **worse in silence**: without
`--flexiones`, the bilingual's reverse coverage drops 8.6 points, measured.

Asked for: *"that the scripts building these packs be in the repo but the packs not, because they
are very heavy"*. This is that script. The artifacts stay outside the repo, and `.gitignore`
already covers `/*.db`.

## The structure it expects

    <root>/dumps/   the inputs: kaikki's .jsonl files, the corpora, WordNet, Wikidata

⚠️ **Each step has to receive the dump ITS reader knows how to read, and that is not obvious**:
the four are different formats and three of them come compressed. `sources/wikidata` does a
`json.loads` per line over the lexemes bz2; `wordnet.spanish` opens OMW's `.tab` as PLAIN TEXT
and splits on tabs; `wordnet.english` reads compressed WN-LMF. Handing any of them another's
file **blows up on the first line**, which is this case's good fortune: the previous version
handed DBnary's dumps to the first two.

⚠️ **And DBnary was not a reasonable alternative, it was an oversight**: it is in
`docs/fuentes.md` as **rejected and measured** --the same source as Wiktionary, half the
yield-- and its `.ttl` files are in the directory because they were downloaded to measure it.
`test_build_packs` pins that, and now checks the names as well as the order.
    <root>/build/   the INTERMEDIATES: packs that are an input to a merge and are not distributed
    <root>/dist/    what gets published, and the only thing `packserver.py` should serve

⚠️ **The separation is the point.** With everything in one flat directory, `es-def-wd` --which is
an INPUT to the Spanish merge, not a dictionary for anybody-- showed up in the emulator's catalog
as a downloadable pack. A single-source dictionary is exactly the model that was discarded.

⚠️ **Full English is in `dist/` even though it is ALSO an input** (to the bilingual, through
`--flexiones`). It is both things, and what decides where it lives is whether it is distributed.
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

#: Each tier's range, in MB: the `(minimum, maximum)` the FILE has to satisfy (D-215, D-220).
#:
#: ⚠️ **It is a range and not a number because a number cannot be met.** The derived pack's size is
#: estimated by scaling the payloads by the SOURCE pack's ratio, and the derived one has a
#: different ratio --it takes its own lemmas' forms and not the others'--: asking for 25 MB gave
#: **17.7 MB**. The range is met by measuring the file and deriving again, which is what
#: `build_core.derivar_en_rango` does.
#:
#: ⚠️ **Every number comes from a measurement, and the one that ties them is D-220.** In one line:
#: **30** is the elbow of the coverage/size curve of the **most demanding** language (English:
#: +10 MB buys under 0.5 points there); **x1.5 of width** because the file/budget ratio moves
#: between 0.60 and 1.26 and a range narrower than that spread does not converge; **x2 of gap**
#: between `maximum(core)` and `minimum(main)` so the two ranges cannot overlap.
#:
#: ⚠️ **The MAXIMA are validated by no content metric, and that is said.** Coverage saturates
#: (96.63 % at 45.2 MB, the full pack's value) and `lemma_coverage` is **convex** -- it
#: accelerates: 0.168, 0.248, 0.301 and 0.388 points per MB -- so by that yardstick spending more
#: is always better. The maximum is a product decision about how much is asked of the watch; the
#: only measured thing is the WIDTH.
RANGO = {"core": (30, 50), "main": (100, 150)}

#: Each language's frequency list. It is the same one the `full` was built with, and using the
#: same one matters: a tier's cut and the pack's `rank` have to speak about the same corpus.
LISTAS_DE_FRECUENCIA = {"en": "freq-en-opensubs.txt", "es": "freq-es-opensubs.txt"}


def _ruta(raiz, *partes):
    return os.path.join(raiz, *partes)


def _niveles(raiz, idioma, tamano_full_mb):
    """The derived tiers a language gets, given what its `full` weighs."""
    pasos = []
    full = _ruta(raiz, DIST, "%s-full.db" % idioma)
    for nivel in ("core", "main"):
        minimo, maximo = RANGO[nivel]
        # ⚠️ A `full` that already fits whole in `main`'s range would make `main` a copy of it.
        if nivel == "main" and tamano_full_mb is not None and tamano_full_mb <= maximo:
            continue
        pasos.append({
            "nombre": "%s-%s" % (idioma, nivel),
            "salida": _ruta(raiz, DIST, "%s-%s.db" % (idioma, nivel)),
            # ⚠️ **`--frecuencias` is not optional in practice.** Without the list the tier is cut
            # by `rank`, and that is measured: between 0.97 and 1.53 points less corpus coverage,
            # with fewer lemmas inside. `build_core` warns on stderr when it is missing.
            "comando": [sys.executable, BUILD_CORE, full,
                        _ruta(raiz, DIST, "%s-%s.db" % (idioma, nivel)),
                        "--rango-mb", str(minimo), str(maximo), "--tier", nivel,
                        "--frecuencias", _ruta(raiz, DUMPS, LISTAS_DE_FRECUENCIA[idioma])],
            "verifica": True,
        })
    return pasos


def plan(raiz, solo=None, tamanos=None):
    """The complete plan, **without running anything**.

    It is returned rather than run so the gate can check the ORDER without holding the dumps,
    which are 4.4 GB. It is the same split as `devpack.py`: the plan's shape enters the gate,
    running it needs the data and does not.

    `tamanos` maps language -> MB of its already built `full`, to decide whether a `main` applies.
    In a dry plan that is unknown, and then both tiers are planned.
    """
    tamanos = tamanos or {}
    pasos = []

    # ⚠️ **El ingles va PRIMERO**, y no es alfabetico: el bilingue lo necesita construido para
    # `--flexiones`.
    if solo in (None, "en"):
        # ⚠️ **`--etimologia-hasta` reads the PREVIOUS build's `en-main`, and that is not a
        # shortcut.** The rule is that `full` carries the origin up to `main`'s vocabulary, and
        # `main` is defined by a byte budget: it does not exist until this same run derives it,
        # after this step. The previous one is the only exact statement of that vocabulary
        # available here, and between rebuilds it moves by a handful of rare words -- what those
        # few lose is one line, not their entry. Without the flag `en-full` grows +179 MB.
        #
        # ⚠️ **Spanish gets NO flag on purpose.** `es-full` IS the Spanish `main` (D-220: it falls
        # below that tier's range and no `es-main` is built), so it carries the origin for all of
        # its own vocabulary. Passing it a filter would take the datum away from words its own
        # reader can look up.
        vocabulario = _ruta(raiz, DIST, "en-main.db")
        comando = [sys.executable, BUILD_PACK, "en",
                   _ruta(raiz, DUMPS, "en.jsonl"),
                   _ruta(raiz, DIST, "en-full.db"),
                   "--tesauro", _ruta(raiz, DUMPS, "oewn-2024.xml.gz"),
                   "--frecuencias", _ruta(raiz, DUMPS, "freq-en-opensubs.txt")]
        if os.path.exists(vocabulario):
            comando += ["--etimologia-hasta", vocabulario]
        pasos.append({
            "nombre": "en-full",
            "salida": _ruta(raiz, DIST, "en-full.db"),
            "comando": comando,
            "verifica": True,
        })

    if solo in (None, "es"):
        # ⚠️ **Wikidata is NOT built as a separate pack, and the rebuild corrected that.** The plan
        # built `build/es-def-wd.db` believing it was *an input to the merge*; `--sumar` reads the
        # **dump**, so nobody consumed that file: 30 s and 4.5 MB for nothing. What goes into the
        # Spanish pack is the dump, merged by D-146.
        pasos.append({
            "nombre": "es-full",
            "salida": _ruta(raiz, DIST, "es-full.db"),
            "comando": [sys.executable, BUILD_PACK, "es",
                        _ruta(raiz, DUMPS, "es.jsonl"),
                        _ruta(raiz, DIST, "es-full.db"),
                        "--frases", _ruta(raiz, DUMPS, "tatoeba-spa.tsv"),
                        "--tesauro", _ruta(raiz, DUMPS, "wn-data-spa.tab"),
                        "--frecuencias", _ruta(raiz, DUMPS, "freq-es-opensubs.txt"),
                        # ⚠️ **`--sumar <pack> <dump>` reads the DUMP, not a built pack.** The name
                        # `es-wd` selects the reader and the attribution (D-146); the path is the
                        # dump that reader parses.
                        "--sumar", "es-wd",
                        _ruta(raiz, DUMPS, "wikidata-lexemes.json.bz2")],
            "verifica": True,
        })

    # The bilingual one, AFTER English. It has no tiers: its purpose is not a size of the same
    # dictionary but something else (D-215).
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
        # ⚠️ **Every pack is verified BEFORE going on.** A half-built pack opens with no error and
        # returns fewer words than it holds; chaining onto it propagates the defect.
        if paso["verifica"] and subprocess.call([sys.executable, VERIFY, paso["salida"]]) != 0:
            print("  %s no paso verify_pack; se para aqui" % paso["nombre"], file=sys.stderr)
            return 1
    print("\nlisto. Lo publicable esta en %s" % _ruta(raiz, DIST))
    return 0


if __name__ == "__main__":
    sys.exit(main())
