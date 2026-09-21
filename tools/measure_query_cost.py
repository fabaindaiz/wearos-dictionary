#!/usr/bin/env python3
"""What a search and an entry-open actually cost, measured on a real pack.

**This does not measure battery.** It measures the WORK the battery pays for: how many rungs of
the cascade run, how many rows they touch, and how long the SQL takes. Battery itself only counts
measured on a physical watch (D-043) -- see `docs/bateria.md` for the protocol and for what this
number is and is not evidence of.

It exists because the battery discussion kept running on beliefs. One of them was written in the
code -- *"el prefijo es el 95% del uso"* -- and this script is what killed it: it is true while
you type and false for the search that actually runs, because D-128 only searches when the
keyboard closes, on the COMPLETE word.

It replicates the SQL of `SqlitePackSource` and imports the same `normalize.py` that built the
pack, so the fuzzy key is the real one and not an approximation. If the two ever drift, this
script reports the wrong rung and that is itself the signal.

    python3 tools/measure_query_cost.py <pack.db> [<pack.db> ...]
"""
import os
import re
import sqlite3
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "packbuilder"))

import binascii  # noqa: E402

import normalize  # noqa: E402
import payload  # noqa: E402

# Los mismos numeros que SqlitePackSource. Si alla cambian, aca tienen que cambiar: este script
# miente en silencio si se desincroniza, que es el precio de replicar en vez de instrumentar.
LIMIT = 30
PREFIX_OVERFETCH = 3
FUZZY_TRIGGER = 5
FUZZY_PREFIX_LENGTH = 4
FUZZY_CANDIDATES = 200
MAX_PALABRAS_POR_CONSULTA = 64

# Palabras de prueba por idioma: comunes, raras y una que no existe. No son una muestra de uso
# real --no la tenemos-- pero cubren los tres caminos distintos de la cascada.
PALABRAS = {
    "es": ["perro", "casa", "arbol", "murcielago", "esdrujula", "guanaco",
           "italiano", "tomate", "llover", "biblioteca", "ornitorrinco", "mesa"],
    "en": ["dog", "house", "tree", "aardvark", "serendipity", "guanaco",
           "italian", "tomato", "rain", "library", "platypus", "table"],
}
INEXISTENTES = {
    "es": ["perrp", "muercielago", "biblioteka", "zzzqx", "esdrujla"],
    "en": ["dogg", "hoyse", "libary", "zzzqx", "serendipety"],
}


def _upper(prefix):
    """El limite superior del rango, igual que `PrefixRange.upperBound`."""
    return prefix[:-1] + chr(ord(prefix[-1]) + 1) if prefix else None


class Cascade:
    """Los cuatro peldanos, con el mismo corte temprano que la implementacion real."""

    def __init__(self, path):
        self.con = sqlite3.connect("file:%s?mode=ro" % path, uri=True)
        self.profile = self._meta("fuzzy_profile")
        self.entries = self.con.execute("SELECT COUNT(*) FROM entry").fetchone()[0]
        self.dictionary = binascii.unhexlify(self._meta("payload_dict"))

    def _meta(self, key):
        return self.con.execute("SELECT value FROM meta WHERE key=?", (key,)).fetchone()[0]

    def _timed(self, sql, params):
        start = time.perf_counter()
        rows = self.con.execute(sql, params).fetchall()
        return rows, (time.perf_counter() - start) * 1000

    def run(self, raw):
        """Devuelve (peldanos corridos, filas tocadas, ms de SQL)."""
        key = normalize.norm(raw)
        rows, ms = self._timed(
            "SELECT id FROM entry WHERE norm >= ? AND norm < ? "
            "ORDER BY CASE WHEN norm = ? THEN 0 ELSE 1 END, rank, norm LIMIT ?",
            (key, _upper(key), key, LIMIT * PREFIX_OVERFETCH))
        rungs, seen, touched = 1, len(rows), len(rows)

        if seen < LIMIT:
            rows, extra = self._timed(
                "SELECT e.id FROM form f JOIN entry e ON e.id = f.entry_id "
                "WHERE f.norm = ? ORDER BY e.rank LIMIT ?", (key, LIMIT))
            rungs, seen, touched, ms = 2, seen + len(rows), touched + len(rows), ms + extra

        if seen < LIMIT:
            rows, extra = self._timed(
                "SELECT e.id FROM entry e WHERE e.id IN "
                "(SELECT entry_id FROM trans WHERE norm >= ? AND norm < ?) "
                "ORDER BY e.rank LIMIT ?", (key, _upper(key), LIMIT))
            rungs, seen, touched, ms = 3, seen + len(rows), touched + len(rows), ms + extra

        if seen < FUZZY_TRIGGER:
            fuzzy_key = normalize.fuzzy(raw, self.profile)
            if fuzzy_key:
                prefix = fuzzy_key[:FUZZY_PREFIX_LENGTH]
                rows, extra = self._timed(
                    "SELECT id, norm FROM entry WHERE fuzzy >= ? AND fuzzy < ? LIMIT ?",
                    (prefix, _upper(prefix), FUZZY_CANDIDATES))
                rungs, touched, ms = 4, touched + len(rows), ms + extra

        return rungs, touched, ms

    def open_entry(self, sample):
        """El costo de ABRIR una palabra: el inflate y la UNICA consulta de D-094."""
        rows = self.con.execute(
            "SELECT id, headword, payload FROM entry ORDER BY rank LIMIT ?", (sample,)).fetchall()
        inflate = query = 0.0
        keys_total = measured = 0
        for _id, _headword, blob in rows:
            start = time.perf_counter()
            text = payload.decompress(blob, self.dictionary)
            inflate += (time.perf_counter() - start) * 1000
            _pos, senses = payload.parse(text)
            glosses = " ".join(s["gloss"] for s in senses if s.get("gloss"))
            keys = list({normalize.norm(w)
                         for w in re.findall(r"[^\W\d_]{3,}", glosses, re.UNICODE)})
            keys = keys[:MAX_PALABRAS_POR_CONSULTA]
            if not keys:
                continue
            measured += 1
            keys_total += len(keys)
            holes = ",".join("?" * len(keys))
            _r, ms = self._timed(
                "SELECT norm, id, rank FROM entry WHERE norm IN (%s)" % holes, keys)
            query += ms
        return measured, inflate / len(rows), keys_total / measured, query / measured

    def by_rowid(self, count):
        """Lo que cuestan N lecturas por rowid: la palabra del dia (32) y la muestra (64)."""
        step = max(1, self.entries // count)
        start = time.perf_counter()
        for i in range(count):
            self.con.execute("SELECT headword, norm FROM entry WHERE id = ?",
                             (1 + i * step,)).fetchone()
        return (time.perf_counter() - start) * 1000


def report(path):
    pack = Cascade(path)
    lang = pack._meta("lang_src")
    words = PALABRAS.get(lang, PALABRAS["es"])
    missing = INEXISTENTES.get(lang, INEXISTENTES["es"])

    print("=" * 72)
    print("%s -- %s entradas, perfil fuzzy %r"
          % (os.path.basename(path), format(pack.entries, ","), pack.profile))
    print("=" * 72)

    print("\nUna busqueda de PALABRA COMPLETA -- el caso real, porque con el teclado")
    print("abierto no se busca (D-128) y la unica consulta sale sobre la palabra entera.")
    print("  %-16s %8s %7s %10s" % ("palabra", "peldanos", "filas", "ms de SQL"))
    totals = [0, 0, 0.0]
    for word in words:
        rungs, touched, ms = pack.run(word)
        totals = [totals[0] + rungs, totals[1] + touched, totals[2] + ms]
        print("  %-16s %8d %7d %10.2f" % (word, rungs, touched, ms))
    n = len(words)
    print("  %-16s %8.1f %7.0f %10.2f"
          % ("PROMEDIO", totals[0] / n, totals[1] / n, totals[2] / n))

    print("\nLo que NO existe -- dictado por voz y tipeo malo, que es cuando corre el")
    print("peldano tolerante entero.")
    print("  %-16s %8s %7s %10s" % ("consulta", "peldanos", "filas", "ms de SQL"))
    for word in missing:
        rungs, touched, ms = pack.run(word)
        print("  %-16s %8d %7d %10.2f" % (word, rungs, touched, ms))

    print("\nLetra a letra -- lo que costaria si el teclado NO frenara la busqueda.")
    for word in words[:2]:
        acc = [0, 0, 0.0]
        for i in range(1, len(word) + 1):
            rungs, touched, ms = pack.run(word[:i])
            acc = [acc[0] + rungs, acc[1] + touched, acc[2] + ms]
        print("  %-16s %d pulsaciones -> %d consultas, %d filas, %.2f ms"
              % (word, len(word), acc[0], acc[1], acc[2]))

    measured, inflate, keys, query = pack.open_entry(300)
    print("\nAbrir una palabra (%d fichas):" % measured)
    print("  inflate del payload          : %.3f ms" % inflate)
    print("  claves de glosa por ficha    : %.1f  (tope duro %d)"
          % (keys, MAX_PALABRAS_POR_CONSULTA))
    print("  la UNICA consulta de D-094   : %.3f ms" % query)

    print("\nUna vez, no por interaccion:")
    print("  palabra del dia, 32 lecturas : %.2f ms  (una vez por semana y pack, D-097)"
          % pack.by_rowid(32))
    print("  abrir el pack, 64 lecturas   : %.2f ms  (una vez por proceso, D-142)"
          % pack.by_rowid(64))


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    for path in argv[1:]:
        if not os.path.exists(path):
            print("no existe: %s" % path)
            return 1
        report(path)
    print("\nEstos ms son de la maquina donde corrio esto, no del reloj. Sirven para COMPARAR")
    print("caminos entre si; el valor absoluto en el reloj hay que medirlo en el reloj (D-043).")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
