#!/usr/bin/env python3
"""Repair a built pack's METADATA, without re-exporting its content.

A full rebuild of the English pack is an hour and needs the dumps. Some defects, though, live
entirely in the `meta` table -- a name with two tier tokens, a description assembled in the wrong
language -- and rewriting six rows takes a second. This is the tool that does that, and it exists
so that repairing a pack is a **reproducible act with a record**, not someone typing UPDATE into
sqlite3 at a prompt (D-015: packs are built by tools, never by hand).

⚠️ **It touches ONLY `meta`.** It never reads or writes `entry`, `payload`, `fts_def` or any
index, so no repair here can change a single search result. That is the boundary that makes it
safe, and it is enforced by `test_repair_meta.py`, which hashes every content table before and
after and requires them identical.

⚠️ **What it CANNOT fix, and the distinction matters.** A missing payload channel, a proper noun
that slipped the frequency filter, a wrong `rank` -- those are content, and content comes from a
rebuild. This tool repairs what the builder computed wrongly *about* the pack, never what it
extracted *into* it. Asking it for more would make it a second, worse builder.

⚠️ **It bumps `data_version`, and that is not bookkeeping.** A repaired pack is a different file
with the same content; if its version did not move, an installer comparing versions would decide
it already had this one and keep the broken copy. That is exactly the failure the roadmap records
under *"a rebuilt pack is indistinguishable from the old one"*, and the repair would silently not
arrive. ⚠️ **And when the bump would not move it, it REFUSES instead of writing anyway**:
`data_version` has minute resolution, justified by *"a build takes minutes"* -- which a repair,
at under a second, does not.

After running it, the `.gz` and the catalogue have to be regenerated, because both carry the
file's size and its sha256:

    gzip -9 -k -f <pack>.db
    python3 tools/packserver.py <dist> --index-only > <dist>/index.json

⚠️ **Repairing a BUNDLED pack also needs `dictionary.versionCode` bumped.** The app skips the
whole comparison when the installed `versionCode` equals the running one -- `assetsToExtract`
says so in as many words: *"the same APK as last time: its content did not change"*. That was
true of every build until this tool made it possible to change a pack's bytes without touching a
line of Kotlin. Measured on the emulator 2026-09-23: a repaired `en-core` inside an unchanged
versionCode 8 never replaced the old one, and **nothing was logged** -- the app simply opened the
stale pack. The cores are the bundled ones today.

Usage:
    python3 tools/packbuilder/repair_meta.py <pack.db> [...]      # says what it would do
    python3 tools/packbuilder/repair_meta.py --write <pack.db>    # does it
"""

import argparse
import os
import sqlite3
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import build  # noqa: E402
import build_pack  # noqa: E402


def name_without_tiers(name):
    """The name with **every** trailing tier token removed, not just the last one.

    ⚠️ **This is deliberately not `build.name_with_tier`'s behaviour, and the difference is the
    whole reason this function exists.** That one strips a single token because the builder never
    sees more: it takes a clean name, or one that inherited exactly one tier from the pack it was
    derived from. Repair sees the accumulated damage -- `English (full) (main)`, which is what
    shipped -- and feeding that to a single-token strip returns it unchanged, because removing
    `(main)` still leaves `(full)` at the end.

    >>> name_without_tiers("English (full) (main)")
    'English'
    >>> name_without_tiers("Griego (koine)")
    'Griego (koine)'
    """
    base = (name or "").strip()
    while True:
        for conocido in build.TIERS:
            sufijo = " (%s)" % conocido
            if base.endswith(sufijo):
                base = base[: -len(sufijo)].rstrip()
                break
        else:
            return base


def repairs_for(meta):
    """The repairs this pack needs, as `[(key, old, new, why)]`. Empty when it is healthy."""
    salida = []

    tier = meta.get("tier")
    nombre = meta.get("name", "")
    # ⚠️ **The bidirectional pack is excluded, and a dry run is what caught it.** It DECLARES
    # `tier=full` and yet its name carries no tier on purpose: `build_pack` skips the stamping for
    # `kind == bilingual`, because its tiers are not sizes of one dictionary -- it is a different
    # thing. Without this the repair would have renamed `Español ↔ English` to
    # `Español ↔ English (full)`, inventing a defect out of a deliberate choice.
    #
    # This is why the tool reports by default and writes only when asked.
    if tier and meta.get("kind") != "bilingual":
        correcto = "%s (%s)" % (name_without_tiers(nombre), tier)
        if correcto != nombre:
            salida.append(("name", nombre, correcto,
                           "the tier was stamped twice; the name has to say one thing"))

    descripcion = meta.get("description")
    if descripcion and build_pack.es_ingles(meta):
        arreglada = descripcion
        for en, es in build_pack.FRASES.values():
            if es in arreglada:
                arreglada = arreglada.replace(es, en)
        if arreglada != descripcion:
            salida.append(("description", descripcion, arreglada,
                           "an English pack described with Spanish sentences"))
    return salida


def leer_meta(con):
    return dict(con.execute("select key, value from meta").fetchall())


def repair(path, write=False, ahora=None):
    """Reports --and optionally applies-- the metadata repairs `path` needs."""
    con = sqlite3.connect(path)
    try:
        meta = leer_meta(con)
        if "pack_id" not in meta:
            raise SystemExit("%s: no parece un pack (meta sin pack_id)" % path)
        arreglos = repairs_for(meta)
        etiqueta = os.path.basename(path)
        if not arreglos:
            print("%s: nada que reparar" % etiqueta)
            return []
        for clave, viejo, nuevo, porque in arreglos:
            print("%s: %s" % (etiqueta, porque))
            print("   - %s" % viejo)
            print("   + %s" % nuevo)
        if not write:
            print("%s: %d cambio(s) NO aplicados (falta --write)" % (etiqueta, len(arreglos)))
            return arreglos
        version = build.data_version(ahora)
        # ⚠️ **A repair can be too fast for the version to move, and it REFUSES rather than
        # writing a version that does not distinguish.** `data_version` has minute resolution and
        # its own docstring justifies that with *"a build takes minutes, so two never land on the
        # same one"* -- true for a build, false for this, which rewrites six rows in under a
        # second. Repairing a pack built in this same minute would leave two different files
        # claiming one version, and an installer comparing versions would keep the broken copy.
        # Refusing is the visible failure; silently not bumping is the invisible one.
        if version <= meta.get("data_version", ""):
            raise SystemExit(
                "%s: la reparacion cae en el mismo minuto que el build (%s). "
                "Esperá un minuto y repetí: si no, dos archivos distintos declaran la misma "
                "version y el instalador se queda con el roto." % (etiqueta, version))
        with con:
            for clave, _viejo, nuevo, _porque in arreglos:
                con.execute("update meta set value = ? where key = ?", (nuevo, clave))
            # The version moves so an installer can tell the repaired file from the broken one.
            con.execute("update meta set value = ? where key = 'data_version'", (version,))
        print("%s: aplicado, data_version -> %s" % (etiqueta, version))
        return arreglos
    finally:
        con.close()


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("packs", nargs="+")
    ap.add_argument("--write", action="store_true",
                    help="aplica los cambios; sin esto solo los reporta")
    args = ap.parse_args(argv)
    total = 0
    for p in args.packs:
        total += len(repair(p, write=args.write))
    if total and not args.write:
        print("\n%d cambio(s) pendientes. Repetí con --write." % total)
        print("Después: regenerá el .gz y el index.json (ver el encabezado de este archivo).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
