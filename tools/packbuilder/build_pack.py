"""Construye un pack real monolingue desde un dump de kaikki.org.

    python3 build_pack.py <lang> <kaikki.jsonl> <salida.db> [--sample N] [--con-nombres]

`--sample N` construye un pack piloto con 1 de cada N lemas, elegidos por hash del headword:
determinista y **sin sesgo posicional**, a diferencia de cortar por las primeras N lineas. Sirve
para mirar el contenido y estimar el tamano antes de gastar el build completo.

Los dumps se bajan de kaikki.org (paginas procesadas por idioma; el formato crudo esta
deprecado). **Cual es cual es el error facil**, porque los tres existen y son datasets distintos:

    es -> https://kaikki.org/eswiktionary/Español/...   definiciones EN ESPAÑOL de palabras
                                                        españolas. 1,42 GB.
    en -> https://kaikki.org/dictionary/English/...     definiciones EN INGLES de palabras
                                                        inglesas. 3,24 GB.

    enwiktionary seccion Spanish da palabras españolas con glosa **en ingles**: eso es un pack
    BILINGUE y no lo construye este script.

**Los nombres propios no entran** (D-111): apellidos, toponimos y nombres de pila se descartan
por defecto. `--con-nombres` los trae de vuelta y deja el pack marcado --`pack_id` sufijado y
`meta.proper_nouns = "included"`-- porque sigue siendo la forma de MEDIR cuanto pesan contra un
dump nuevo. Lo que se saca: 32.305 entradas en español (22,1 %, de las cuales 26.265 definen
solamente "Apellido.") y 163.470 en ingles (17,1 %, 40,7 MB).
"""

import hashlib
import os
import sys

from sources import kaikki

from build import PackBuilder

# D-031: el contenido es CC BY-SA y la pantalla de atribucion no es opcional. Estas dos claves
# son lo que la app tiene que mostrar; sin ellas el pack no cumple la licencia de los datos.
#
# `proper_nouns` declara la politica de contenido del pack (D-111). Se escribe en `meta` el
# valor EFECTIVO, no el declarado: meta tiene que decir que paso, no que se pretendia.
#
# "lexical-only" y no "excluded" porque la poda tiene una excepcion medida: el nombre propio con
# vida lexica --los meses, los paises, los idiomas-- se conserva. Ver SENAL_LEXICA_MINIMA en
# sources/kaikki.py.
#
# `data_version` es la fecha del dump en AAAAMMDD, **entero**: la app le hace `.toInt()` al abrir
# y un string revienta en el reloj (D-070). Ademas asi ordena, que es lo que un instalador
# necesita para saber cual de dos packs es mas nuevo.
PACKS = {
    "es": {
        "pack_id": "es-def-wikc",
        "kind": "monolingual",
        "name": "Español — definiciones",
        "lang_src": "es",
        "fuzzy_profile": "es",
        "data_version": "20260915",
        "license": "CC-BY-SA-4.0",
        "attribution": (
            "Definiciones del Wikcionario (es.wiktionary.org), licencia CC BY-SA 4.0. "
            "Extracción: kaikki.org / wiktextract (Tatu Ylonen)."
        ),
        "source_url": "https://kaikki.org/eswiktionary/Espa%C3%B1ol/",
        "proper_nouns": "lexical-only",
    },
    "en": {
        "pack_id": "en-def-wikt",
        "kind": "monolingual",
        "name": "English — definitions",
        "lang_src": "en",
        "fuzzy_profile": "en",
        "data_version": "20260909",
        "license": "CC-BY-SA-4.0",
        "attribution": (
            "Definitions from Wiktionary (en.wiktionary.org), CC BY-SA 4.0. "
            "Extraction: kaikki.org / wiktextract (Tatu Ylonen)."
        ),
        "source_url": "https://kaikki.org/dictionary/English/",
        "proper_nouns": "lexical-only",
    },
}



def _keep(headword, sample):
    if sample <= 1:
        return True
    digest = hashlib.sha256(headword.encode("utf-8")).digest()
    return int.from_bytes(digest[:4], "big") % sample == 0


def main(argv):
    if len(argv) < 4 or argv[1] not in PACKS:
        sys.stderr.write(__doc__)
        sys.stderr.write("\nIdiomas: %s\n" % ", ".join(sorted(PACKS)))
        return 2
    lang, source, output = argv[1], argv[2], argv[3]
    sample = 1
    if "--sample" in argv:
        sample = int(argv[argv.index("--sample") + 1])
    con_nombres = "--con-nombres" in argv

    metadata = dict(PACKS[lang])
    if sample > 1:
        metadata["pack_id"] += "-sample%d" % sample
        metadata["name"] += " (piloto 1/%d)" % sample
    if con_nombres:
        # El pack por defecto conserva el pack_id pelado: si cambiara, el `pack_activo`, el
        # historial y los favoritos del reloj quedarian apuntando a un pack que ya no existe.
        metadata["pack_id"] += "-connombres"
        metadata["proper_nouns"] = "included"

    if os.path.dirname(output):
        os.makedirs(os.path.dirname(output), exist_ok=True)

    with PackBuilder(output, metadata) as builder:
        for record in kaikki.records(source, lang, con_nombres):
            if _keep(record.headword, sample):
                builder.add(record)

    print("%s: %d entradas, %d bytes" % (output, builder.count, os.path.getsize(output)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
