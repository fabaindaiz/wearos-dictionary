"""Are two packs' calibrations compatible? It gets measured, not assumed.

    python3 compare_calibration.py <pack_a.db> <pack_b.db>

**The problem.** `SearchRepository` merges the results of several packs of the same language
(D-136). Each pack brings its own `rank`, computed by its builder with its own proxy: ours weighs
senses, examples, forms, translations and etymology (D-063), and Wikidata's cannot weigh the last
two because the source does not carry them. Two packs can **both be right** and not agree on which
word is more common.

**Why it can be measured.** `entry.uid` is an entry's logical identity and it is **the same across
every pack** of the same language (D-055), so the entries both hold can be paired unambiguously.
Over that overlap the **Spearman correlation** between the two ranks is computed: it is a
correlation of ORDERS, not of values, so it does not care that one pack uses 0..1000 and the other
0..100 -- it cares whether they agree on which comes first.

**The measured reference** (2026-09-20), between `es-def-wikc` and `es-def-wd`:

    entries in common        8,595
    Spearman's rho           0.388
    of each one's 200 most
    common, they share         110

That is **two honest sources that do not entirely agree**: there is shared signal, but they are
not interchangeable. A pack with a random rank would give a rho near 0, and the shuffled control
this tool prints exists precisely to have that number alongside rather than comparing the rho
against intuition.

⚠️ **What a low rho does NOT mean.** It does not mean the pack is bad: it means **its order cannot
be compared with the other's**. The app no longer depends on that -- D-142 put a coverage band
computed from the typed text, looking at no pack datum, ahead of the internal order. This tool
serves to know **how much** the merge is leaning on somebody else's calibration, not to accept or
reject a pack.
"""

import math
import os
import sqlite3
import sys


def _rangos(valores):
    """Averaged ranks, which is what Spearman needs when there are ties."""
    orden = sorted(range(len(valores)), key=lambda i: valores[i])
    out = [0.0] * len(valores)
    i = 0
    while i < len(orden):
        j = i
        while j + 1 < len(orden) and valores[orden[j + 1]] == valores[orden[i]]:
            j += 1
        promedio = (i + j) / 2.0 + 1
        for k in range(i, j + 1):
            out[orden[k]] = promedio
        i = j + 1
    return out


def spearman(xs, ys):
    """Spearman correlation. 1 identical, 0 unrelated, -1 opposite."""
    if len(xs) < 2:
        return 0.0
    ra, rb = _rangos(xs), _rangos(ys)
    n = len(ra)
    ma, mb = sum(ra) / n, sum(rb) / n
    cov = sum((p - ma) * (q - mb) for p, q in zip(ra, rb))
    va = math.sqrt(sum((p - ma) ** 2 for p in ra))
    vb = math.sqrt(sum((q - mb) ** 2 for q in rb))
    return cov / (va * vb) if va and vb else 0.0


def _ranks(path):
    conn = sqlite3.connect(path)
    try:
        return dict(conn.execute("SELECT uid, rank FROM entry"))
    finally:
        conn.close()


def comparar(path_a, path_b, salida=sys.stdout):
    a, b = _ranks(path_a), _ranks(path_b)
    comunes = sorted(set(a) & set(b))
    print("pack A: %s (%d entradas)" % (os.path.basename(path_a), len(a)), file=salida)
    print("pack B: %s (%d entradas)" % (os.path.basename(path_b), len(b)), file=salida)
    print("entradas en comun (por uid): %d" % len(comunes), file=salida)
    if len(comunes) < 2:
        print("\nsin solapamiento: no hay nada que comparar.", file=salida)
        print("Los dos packs aportan vocabularios disjuntos, asi que la mezcla no depende",
              file=salida)
        print("de que sus calibraciones coincidan.", file=salida)
        return 0

    xs = [a[u] for u in comunes]
    ys = [b[u] for u in comunes]
    rho = spearman(xs, ys)
    # The control: the same data with the relation destroyed. It gives the "unrelated" scale for
    # THIS n, which is what avoids comparing the rho against intuition.
    mezclado = ys[len(ys) // 2:] + ys[: len(ys) // 2]
    control = spearman(xs, mezclado)

    top_a = set(sorted(comunes, key=lambda u: a[u])[:200])
    top_b = set(sorted(comunes, key=lambda u: b[u])[:200])

    print("\nrho de Spearman   : %+.3f" % rho, file=salida)
    print("control (barajado): %+.3f   <- esto es 'sin relacion' para este n" % control,
          file=salida)
    print("top 200 compartido: %d de 200" % len(top_a & top_b), file=salida)
    print("\nreferencia medida: es-def-wikc vs es-def-wd dio rho = +0,388 y 110/200.", file=salida)
    print("Son dos fuentes honestas que comparten señal sin ser intercambiables.", file=salida)
    print("\nUn rho bajo NO condena al pack: dice que su orden no se puede comparar con el del",
          file=salida)
    print("otro, y que la mezcla se esta apoyando en la banda de cobertura (D-142), que no",
          file=salida)
    print("depende de ninguna calibracion.", file=salida)
    return 0


def main(argv):
    if len(argv) != 3:
        print(__doc__.strip())
        return 2
    return comparar(argv[1], argv[2])


if __name__ == "__main__":
    sys.exit(main(sys.argv))
