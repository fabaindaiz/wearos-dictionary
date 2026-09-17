"""Construye el pack real de español monolingue desde el dump del Wikcionario.

    python3 build_es.py <kaikki-es.jsonl> <salida.db> [--sample N]

`--sample N` construye un pack piloto con 1 de cada N lemas, elegidos por hash del headword:
determinista y **sin sesgo posicional**, a diferencia de cortar por las primeras N lineas. Sirve
para mirar el contenido y estimar el tamano antes de gastar el build completo.

El dump se baja de kaikki.org (paginas procesadas por idioma; el formato crudo esta deprecado):

    https://kaikki.org/eswiktionary/Español/kaikki.org-dictionary-Español.jsonl

**Es eswiktionary, no enwiktionary.** Son datasets distintos y es el error facil: enwiktionary
seccion Spanish da palabras espanolas con glosa **en ingles**, que es un pack bilingue, no este.
"""

import hashlib
import os
import sys

from sources import kaikki_es

from build import PackBuilder

# D-031: el contenido es CC BY-SA y la pantalla de atribucion no es opcional. Estas dos claves
# son lo que la app tiene que mostrar; sin ellas el pack no cumple la licencia de los datos.
METADATA = {
    "pack_id": "es-def-wikc",
    "kind": "monolingual",
    "name": "Español — definiciones",
    "lang_src": "es",
    "fuzzy_profile": "es",
    # La fecha del dump, no la del build: dos builds del mismo dump son el mismo diccionario.
    # AAAAMMDD y no "2026-09-15": la app hace `data_version.toInt()`, asi que tiene que ser un
    # entero. Ademas asi ordena, que es lo que un instalador necesita para saber cual es mas nuevo.
    "data_version": "20260915",
    "license": "CC-BY-SA-4.0",
    "attribution": (
        "Definiciones del Wikcionario (es.wiktionary.org), licencia CC BY-SA 4.0. "
        "Extracción: kaikki.org / wiktextract (Tatu Ylonen)."
    ),
    "source_url": "https://kaikki.org/eswiktionary/Espa%C3%B1ol/",
}


def _keep(headword, sample):
    if sample <= 1:
        return True
    digest = hashlib.sha256(headword.encode("utf-8")).digest()
    return int.from_bytes(digest[:4], "big") % sample == 0


def main(argv):
    if len(argv) < 3:
        sys.stderr.write(__doc__)
        return 2
    source, output = argv[1], argv[2]
    sample = 1
    if "--sample" in argv:
        sample = int(argv[argv.index("--sample") + 1])

    metadata = dict(METADATA)
    if sample > 1:
        metadata["pack_id"] += "-sample%d" % sample
        metadata["name"] += " (piloto 1/%d)" % sample

    if os.path.dirname(output):
        os.makedirs(os.path.dirname(output), exist_ok=True)

    with PackBuilder(output, metadata) as builder:
        for record in kaikki_es.records(source):
            if _keep(record.headword, sample):
                builder.add(record)

    print("%s: %d entradas, %d bytes" % (output, builder.count, os.path.getsize(output)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
