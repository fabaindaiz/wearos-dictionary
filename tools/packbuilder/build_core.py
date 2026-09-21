#!/usr/bin/env python3
"""Deriva un pack NUCLEO de un pack completo ya construido.

    python3 tools/packbuilder/build_core.py <completo.db> <nucleo.db> <corpus.tsv> [--top N]

## Por que se deriva y no se construye en paralelo

El roadmap habia planteado un filtro dentro de `build_pack`, o sea reconstruir desde los dumps con
menos vocabulario. Derivar del pack completo es mejor por dos razones, y la primera es de
correctitud:

1. ⚠️ **El nucleo declara `subset_of`, y derivandolo eso es cierto POR CONSTRUCCION.** Construido
   en paralelo seria cierto sólo mientras las dos corridas usaran las mismas fuentes, los mismos
   filtros y la misma poda -- una promesa que nada comprueba y que se rompe en silencio la primera
   vez que alguien agrega una opcion a una sola de las dos.
2. Tarda segundos en vez de una hora, y no necesita los 4,5 GB de dumps.

## La trampa que esto evita, medida

**No se elige por `rank`.** `rank` es riqueza de pagina del diccionario, no frecuencia de uso: un
nucleo de 14.388 entradas elegido por `rank` se lleva **el 91 % de la tabla de flexiones** del pack
español, porque las paginas mas ricas son los verbos y un verbo tiene 33 formas. Elegido por
frecuencia de uso, las 7.349 entradas del top 8.000 se llevan **el 5,5 %**. Dieciseis veces menos.

La señal es `sources/tatoeba.frequencies`, que cuenta sobre el corpus con el mismo tokenizador y el
mismo `norm()` que indexa el pack.
"""

import binascii
import os
import sqlite3
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import build  # noqa: E402
import payload as payload_codec  # noqa: E402

# Cuantas palabras entran por defecto.
#
# ⚠️ **Medido sobre los dos corpus reales, y el techo lo pone el ingles.** En el puesto 8.000 una
# palabra española aparece en 21 frases y una inglesa en 5; en el puesto 15.000, en 9 y en 2. Mas
# alla de ~8.000 el corpus ingles --41.512 frases contra 442.135 del español-- deja de ser
# evidencia y pasa a ser ruido. Se usa el mismo N para los dos para que "nucleo" signifique lo
# mismo en los dos idiomas.
TOP_POR_DEFECTO = 8000

# Las claves que escribe el builder y que no se pueden heredar (ver PackBuilder.__init__).
DERIVADAS = {
    "schema_version", "norm_version", "payload_codec", "payload_dict", "payload_dict_sha256",
    "entry_count", "built_at", "uid_recipe", "data_version", "trans_dropped",
}


def vocabulario_del_corpus(corpus, lang, top=TOP_POR_DEFECTO):
    """Las `top` palabras mas usadas del corpus, como claves `norm()`."""
    from sources import tatoeba

    frecuencias = tatoeba.frequencies(corpus, lang=lang)
    orden = sorted(frecuencias.items(), key=lambda par: (-par[1], par[0]))
    return {palabra for palabra, _veces in orden[:top]}


def _meta_del_nucleo(meta):
    """La meta del nucleo: la del completo, mas lo que lo declara subconjunto."""
    salida = {k: v for k, v in meta.items() if k not in DERIVADAS}
    completo = salida["pack_id"]
    salida["pack_id"] = completo + "-core"
    # ⚠️ **La clave que hace que los dos puedan estar instalados sin trabajo de mas** (D-171): con
    # el completo presente, la app no le pregunta al nucleo. Es una afirmacion de CONTENIDO, y
    # derivando el nucleo del completo es verdadera por construccion.
    salida["subset_of"] = completo
    salida["name"] = salida.get("name", "") + " (núcleo)"
    # El credito se mueve con el contenido (D-138): el nucleo distribuye las mismas definiciones,
    # asi que hereda las mismas fuentes. Y el corpus que ELIGIO las palabras se declara tambien,
    # porque `sources` contesta como se armo el pack -- aunque no se distribuya una sola frase suya.
    salida["sources"] = (salida.get("sources", "") +
                         "vocabulary\tTatoeba\thttps://tatoeba.org/\tCC BY 2.0 FR\t"
                         "https://creativecommons.org/licenses/by/2.0/fr/\n")
    return salida


def _agrupar(origen, tabla):
    """`entry_id -> [norm, ...]` para las entradas elegidas, en una sola pasada sobre la tabla."""
    salida = {}
    for entry_id, norm in origen.execute(
        "SELECT entry_id, norm FROM %s WHERE entry_id IN "
        "(SELECT id FROM entry WHERE norm IN (SELECT norm FROM vocab))" % tabla
    ):
        salida.setdefault(entry_id, []).append(norm)
    return salida


def derive(completo, salida, vocabulario):
    """Escribe en `salida` las entradas de `completo` cuyo lema este en `vocabulario`."""
    origen = sqlite3.connect("file:%s?mode=ro" % completo, uri=True)
    meta = dict(origen.execute("SELECT key, value FROM meta"))
    diccionario = binascii.unhexlify(meta["payload_dict"])

    origen.execute("CREATE TEMP TABLE vocab(norm TEXT PRIMARY KEY)")
    origen.executemany("INSERT OR IGNORE INTO vocab VALUES (?)", ((p,) for p in vocabulario))

    filas = origen.execute(
        "SELECT id, uid, headword, pos, rank, payload FROM entry "
        "WHERE norm IN (SELECT norm FROM vocab) ORDER BY id"
    ).fetchall()
    if not filas:
        raise ValueError(
            "el vocabulario no coincide con una sola entrada de %s: un pack de cero entradas se "
            "abre sin error y no encuentra nada" % os.path.basename(completo)
        )

    # ⚠️ **Las formas se leen en UNA pasada y no una consulta por entrada, y la diferencia es
    # entre segundos y no terminar nunca.** `form` tiene PRIMARY KEY (norm, entry_id), asi que
    # filtrar por `entry_id` solo **no usa indice**: es un scan completo. Con 986.000 formas y
    # 16.652 entradas seleccionadas eso son 16 mil millones de filas visitadas. Se descubrio
    # esperando a que el pack ingles terminara, y no termino.
    formas_por_entrada = _agrupar(origen, "form")
    traducciones_por_entrada = _agrupar(origen, "trans")

    escritos = 0
    with build.PackBuilder(salida, _meta_del_nucleo(meta)) as constructor:
        for entry_id, uid, headword, pos, rank, blob in filas:
            _pos_payload, senses, _palabra = payload_codec.parse(
                payload_codec.decompress(blob, diccionario))
            formas = formas_por_entrada.get(entry_id, ())
            traducciones = traducciones_por_entrada.get(entry_id, ())
            constructor.add(build.Record(
                headword=headword,
                senses=senses,
                part_of_speech=pos,
                rank=rank,
                forms=formas,
                translations=traducciones,
                # ⚠️ **El uid se COPIA y no se recalcula.** Es la identidad logica y la llave de
                # join entre packs (D-055): si el nucleo recalculara su `sense_key` contando SUS
                # homografos, una entrada con gemelo en el completo podria perderlo y quedarse con
                # otra identidad -- que es exactamente el fallo que D-145 encontro al fusionar.
                uid=uid,
            ))
            escritos += 1
    origen.close()
    return escritos


def main(argv):
    if len(argv) < 4:
        sys.stderr.write(__doc__)
        return 2
    completo, salida, corpus = argv[1], argv[2], argv[3]
    top = TOP_POR_DEFECTO
    if "--top" in argv:
        top = int(argv[argv.index("--top") + 1])

    con = sqlite3.connect("file:%s?mode=ro" % completo, uri=True)
    lang = con.execute("SELECT value FROM meta WHERE key='lang_src'").fetchone()[0]
    con.close()
    # Tatoeba usa ISO 639-3 en su columna de idioma; el pack usa 639-1.
    lang_corpus = {"es": "spa", "en": "eng"}.get(lang, lang)

    vocabulario = vocabulario_del_corpus(corpus, lang_corpus, top)
    escritos = derive(completo, salida, vocabulario)
    print("%s: %d entradas de las %d palabras mas usadas (%.1f MB)"
          % (os.path.basename(salida), escritos, top,
             os.path.getsize(salida) / 1048576))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
