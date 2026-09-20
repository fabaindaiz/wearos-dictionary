"""¿Las calibraciones de dos packs son compatibles? Se mide, no se supone.

    python3 compare_calibration.py <pack_a.db> <pack_b.db>

**El problema.** `SearchRepository` mezcla los resultados de varios packs del mismo idioma
(D-136). Cada pack trae su propio `rank`, calculado por su builder con su propio proxy: el
nuestro pesa acepciones, ejemplos, formas, traducciones y etimologia (D-063), y el de Wikidata no
puede pesar las dos ultimas porque la fuente no las trae. Dos packs pueden estar **los dos bien**
y no estar de acuerdo sobre que palabra es mas comun.

**Por que se puede medir.** `entry.uid` es la identidad logica de una entrada y es **la misma en
todos los packs** del mismo idioma (D-055), asi que las entradas que los dos tienen se pueden
aparear sin ambiguedad. Sobre ese solapamiento se calcula la **correlacion de Spearman** entre los
dos ranks: es una correlacion de ORDENES, no de valores, asi que no le importa que un pack use
0..1000 y el otro 0..100 -- le importa si coinciden en cual va antes.

**La referencia medida** (2026-09-20), entre `es-def-wikc` y `es-def-wd`:

    entradas en comun        8.595
    rho de Spearman          0,388
    de las 200 mas comunes
    segun cada uno, comparten  110

Eso es **dos fuentes honestas que no se ponen de acuerdo del todo**: hay señal compartida, pero
no son intercambiables. Un pack con el rank al azar daria un rho cerca de 0, y el control barajado
que imprime esta herramienta es justamente para tener ese numero al lado y no compararlo contra la
intuicion.

⚠️ **Lo que un rho bajo NO significa.** No significa que el pack sea malo: significa que **su
orden no se puede comparar con el del otro**. La app ya no depende de eso -- D-142 puso una banda
de cobertura calculada del texto escrito, sin mirar ningun dato del pack, delante del orden
interno. Esta herramienta sirve para saber **cuanto** se esta apoyando la mezcla en una
calibracion ajena, no para aceptar o rechazar un pack.
"""

import math
import os
import sqlite3
import sys


def _rangos(valores):
    """Rangos promediados, que es lo que Spearman necesita cuando hay empates."""
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
    """Correlacion de Spearman. 1 identico, 0 sin relacion, -1 opuesto."""
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
    # El control: los mismos datos con una relacion destruida. Da la escala de "sin relacion"
    # para ESTE n, que es lo que evita comparar el rho contra la intuicion.
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
