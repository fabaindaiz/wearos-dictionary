"""Construye un pack real monolingue desde un dump de kaikki.org.

    python3 build_pack.py <lang> <kaikki.jsonl> <salida.db> [--sample N] [--nombres POLITICA]
                          [--ejemplos <es-en-wikt.jsonl>]

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

**Los nombres propios no entran** (D-116): apellidos, toponimos y nombres de pila se descartan
por defecto. Lo que se saca: 32.305 entradas en español (22,1 %, de las cuales 26.265 definen
solamente "Apellido.") y 163.470 en ingles (17,1 %, 40,7 MB).

`--nombres POLITICA` cambia eso. Son tres y estan medidas en español (D-134):

    lexical-only       el default. 114.619 entradas, 68,3 MB
    definitions-only   entra el que DEFINE y no el que solo se registra, con el rank
                       castigado. Rescata ciudades, generos taxonomicos, grafias anticuadas
    included           entran todos. 146.193 entradas, 73,3 MB. Existe para MEDIR

Las dos que no son el default **sufijan el `pack_id`**, asi que se pueden instalar al lado del
pack normal y compararse en el reloj. `--con-nombres` sigue funcionando como alias de
`--nombres included`.

`--ejemplos` suma una **segunda fuente**: los ejemplos de uso en español del Wiktionary ingles,
seccion Spanish (D-135). Solo llena entradas flacas y solo donde no hay atribucion que inventar,
asi que el numero es chico -- **326 entradas, 0,28 %**. ⚠️ **Cambia la atribucion del pack**,
porque usar dos fuentes obliga a nombrar a las dos: por eso es una opcion y no un default. Las
dos son CC BY-SA 4.0.
"""

import hashlib
import os
import sys

from sources import enwikt_examples, kaikki, oewn

from build import PackBuilder

# D-031: el contenido es CC BY-SA y la pantalla de atribucion no es opcional. Estas dos claves
# son lo que la app tiene que mostrar; sin ellas el pack no cumple la licencia de los datos.
#
# `name` es CORTO y `description` lleva el texto largo (D-125). El nombre se muestra en una
# fila de reloj --en el selector del inicio, en la pantalla de diccionarios, en el dialogo de
# borrar y en la atribucion-- y "Español - definiciones" son 22 caracteres: se cortaba en los
# cuatro. Lo que el nombre largo decia --que trae definiciones-- sale ahora de `kind`, que es un
# dato y no una cadena que alguien tiene que leer.
#
# `proper_nouns` declara la politica de contenido del pack (D-116). Se escribe en `meta` el
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
        "name": "Español",
        "description": (
            "Definiciones en español del Wikcionario, sin nombres propios. "
            "Incluye sinónimos, antónimos y palabras relacionadas por acepción."
        ),
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
        "name": "English",
        "description": (
            "English definitions from Wiktionary, proper nouns pruned. "
            "Includes synonyms, antonyms and related words per sense."
        ),
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
    # SPIKE (D-120). Existe para medir, no es un pack de produccion: no esta en el catalogo y
    # no se sube al reloj. Ver sources/oewn.py.
    "en-core": {
        "pack_id": "en-core-oewn",
        "kind": "monolingual",
        "name": "English core",
        "description": "Spike: Open English WordNet 2025. Not a production pack (D-120).",
        # `lang_src` se queda en "en" y NO en "en-core": entra en stable_uid(), y mantenerlo
        # igual al pack de kaikki es lo unico que deja comparable la identidad logica de las
        # dos fuentes si algun dia se quieren cruzar.
        "lang_src": "en",
        "fuzzy_profile": "en",
        "data_version": "20251231",
        "license": "CC-BY-4.0",
        "attribution": (
            "Open English WordNet 2025 (en-word.net), CC BY 4.0. "
            "Derived from Princeton WordNet 3.0."
        ),
        "source_url": "https://en-word.net/",
        # La edicion estandar de OEWN 2025 no trae nombres propios: estan en Open English
        # Namenet / la edicion 2025+. La fuente de referencia del dominio llego a D-116 sola.
        "proper_nouns": "excluded",
    },
}

# De que modulo sale cada pack. Dos lineas en vez de un build_spike_oewn.py aparte, que
# duplicaria el manejo de --sample, de la metadata y de PackBuilder.
READERS = {"es": kaikki, "en": kaikki, "en-core": oewn}


def _keep(headword, sample):
    if sample <= 1:
        return True
    digest = hashlib.sha256(headword.encode("utf-8")).digest()
    return int.from_bytes(digest[:4], "big") % sample == 0


def _pegar_ejemplo(record, ejemplos):
    """Le pega a una entrada FLACA el ejemplo de la segunda fuente. Ver `sources/enwikt_examples`.

    ⚠️ **Solo si la entrada tiene una acepcion y ninguna todavia**, que es la mitad de la regla
    que impide inventar la atribucion -- la otra mitad la aplica la fuente, exigiendo que alla
    tambien haya una sola. Con varias acepciones nuestras no se sabe a cual pegarlo, y pegarlo a
    la primera es contenido incorrecto que parece correcto: el lector no tiene como sospecharlo.
    """
    if not ejemplos or len(record.senses) != 1 or record.senses[0]["examples"]:
        return
    traidos = ejemplos.get((record.headword, record.part_of_speech))
    if traidos:
        record.senses[0]["examples"] = list(traidos)


def main(argv):
    if len(argv) < 4 or argv[1] not in PACKS:
        sys.stderr.write(__doc__)
        sys.stderr.write("\nIdiomas: %s\n" % ", ".join(sorted(PACKS)))
        return 2
    lang, source, output = argv[1], argv[2], argv[3]
    sample = 1
    if "--sample" in argv:
        sample = int(argv[argv.index("--sample") + 1])
    politica = "lexical-only"
    if "--con-nombres" in argv:
        politica = "included"
    if "--nombres" in argv:
        politica = argv[argv.index("--nombres") + 1]
    dump_ejemplos = None
    if "--ejemplos" in argv:
        dump_ejemplos = argv[argv.index("--ejemplos") + 1]

    metadata = dict(PACKS[lang])
    if sample > 1:
        metadata["pack_id"] += "-sample%d" % sample
        metadata["name"] += " (piloto 1/%d)" % sample
    if politica != "lexical-only":
        # El pack por defecto conserva el pack_id pelado: si cambiara, el `pack_activo`, el
        # historial y los favoritos del reloj quedarian apuntando a un pack que ya no existe.
        # Los otros lo sufijan para que dos politicas puedan convivir instaladas y compararse.
        metadata["pack_id"] += "-" + politica.replace("-", "")
        metadata["proper_nouns"] = politica
    ejemplos = {}
    if dump_ejemplos:
        # ⚠️ Dos fuentes obligan a nombrar a las DOS, y eso es lo caro de esta opcion (D-135).
        # No es una formalidad: la atribucion es la condicion de la licencia con la que se
        # distribuye el pack, y las dos fuentes son CC BY-SA 4.0. Se escribe aca, junto al
        # merge, para que no exista manera de mezclar el contenido sin mover el credito.
        ejemplos = enwikt_examples.examples_by_entry(dump_ejemplos)
        metadata["pack_id"] += "-ej"
        metadata["attribution"] += (
            " Ejemplos de uso del Wiktionary en inglés (en.wiktionary.org), sección Spanish, "
            "licencia CC BY-SA 4.0."
        )
        metadata["description"] += " Con ejemplos de uso de una segunda fuente."

    if os.path.dirname(output):
        os.makedirs(os.path.dirname(output), exist_ok=True)

    with PackBuilder(output, metadata) as builder:
        reader = READERS[lang]
        argumentos = (source, lang) if reader is oewn else (source, lang, politica)
        for record in reader.records(*argumentos):
            if not _keep(record.headword, sample):
                continue
            _pegar_ejemplo(record, ejemplos)
            builder.add(record)

    print("%s: %d entradas, %d bytes" % (output, builder.count, os.path.getsize(output)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
