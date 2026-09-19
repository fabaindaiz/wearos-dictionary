"""Formato del cuerpo de una entrada (columna entry.payload).

ESTE ARCHIVO TIENE UN ESPEJO:
    dict-core/src/main/kotlin/cl/fadiaz/dictionary/core/PayloadCodec.kt

El payload va comprimido con deflate crudo y un diccionario precargado compartido por todo el
pack, guardado en meta.payload_dict. Las entradas son de unos cientos de bytes, demasiado
cortas para que deflate encuentre redundancia por si solo; el diccionario le da la ventana ya
primada con los fragmentos frecuentes del corpus.

Se eligio deflate y no zstd aunque comprime menos, porque deflate esta en java.util.zip
(plataforma Android, sin .so extra) y en el zlib de la stdlib de Python. zstd obligaria a una
libreria nativa en el reloj ADEMAS de la de SQLite, y a una dependencia de pip aca, para ganar
unos puntos de compresion.

Formato una vez descomprimido: texto UTF-8, una linea por campo, tag de un caracter + TAB.
Ver la documentacion del espejo en Kotlin para el detalle.

`CODEC_ID` sube cuando cambia el formato del TEXTO, no solo cuando cambia la compresion, porque
`PackFile.open` lo compara con `!=` y rechaza el pack. Hoy eso cuesta reconstruir y volver a
sideloadear los packs, y nada mas. **Cuando exista el instalador, un tag ADITIVO no lo sube**:
para eso esta la tolerancia a tags desconocidos, y forzar a redescargar 300 MB por un campo
nuevo que el lector viejo ignora seria tirar esa propiedad a la basura (D-119).
"""

import hashlib
import zlib

# Sube cuando cambia el formato. Se escribe en meta.payload_codec.
PAYLOAD_VERSION = 2
CODEC_ID = "deflate-v2"

TAG_PART_OF_SPEECH = "P"
TAG_SENSE = "S"
TAG_EXAMPLE = "E"
TAG_TRANSLATION = "T"
TAG_SYNONYM = "Y"

# Antonimo de ESTA acepcion (D-126). **No sube CODEC_ID y eso es deliberado**: es un tag
# puramente aditivo, y D-119 dejo escrito que forzar a redescargar por uno de esos seria tirar
# a la basura la tolerancia que el formato tiene. Un lector viejo lo ignora y muestra la entrada
# sin antonimos, que es degradacion correcta.
TAG_ANTONYM = "A"

# Deflate crudo: sin encabezado zlib. El encabezado trae un DICTID que obliga al lector a
# esperar needsDictionary(); sin encabezado los dos lados fijan el diccionario de entrada.
_RAW_DEFLATE = -15

# Caracteres que romperian el formato delimitado. Se sanean al construir, no al leer: el reloj
# no deberia gastar ciclos defendiendose de datos que nosotros mismos generamos.
_FORBIDDEN = str.maketrans({"\t": " ", "\n": " ", "\r": " "})


def sanitize(value):
    """Deja un valor apto para el formato delimitado, o None si queda vacio."""
    cleaned = " ".join(value.translate(_FORBIDDEN).split())
    return cleaned or None


def render(part_of_speech, senses):
    """Serializa a texto. `senses` es una lista de dicts con gloss/examples/translations.

    Los valores se sanean aca: un tab perdido en una glosa de Wiktionary corromperia la
    entrada entera y el sintoma apareceria recien en el reloj.
    """
    lines = []
    if part_of_speech:
        pos = sanitize(part_of_speech)
        if pos:
            lines.append(TAG_PART_OF_SPEECH + "\t" + pos)
    for sense in senses:
        gloss = sanitize(sense.get("gloss", ""))
        if not gloss:
            # Una acepcion sin glosa no aporta nada y descolgaria sus ejemplos.
            continue
        lines.append(TAG_SENSE + "\t" + gloss)
        for example in sense.get("examples", ()):
            value = sanitize(example)
            if value:
                lines.append(TAG_EXAMPLE + "\t" + value)
        for translation in sense.get("translations", ()):
            value = sanitize(translation)
            if value:
                lines.append(TAG_TRANSLATION + "\t" + value)
        for synonym in sense.get("synonyms", ()):
            value = sanitize(synonym)
            if value:
                lines.append(TAG_SYNONYM + "\t" + value)
        for antonym in sense.get("antonyms", ()):
            value = sanitize(antonym)
            if value:
                lines.append(TAG_ANTONYM + "\t" + value)
    return "".join(line + "\n" for line in lines)


def parse(text):
    """Inverso de render(). Existe para verify_pack.py y los tests, no para el camino normal."""
    part_of_speech = None
    senses = []
    for line in text.split("\n"):
        if not line or len(line) < 2 or line[1] != "\t":
            continue
        tag, value = line[0], line[2:]
        if not value:
            continue
        if tag == TAG_PART_OF_SPEECH:
            if part_of_speech is None:
                part_of_speech = value
        elif tag == TAG_SENSE:
            senses.append(
                {
                    "gloss": value,
                    "examples": [],
                    "translations": [],
                    "synonyms": [],
                    "antonyms": [],
                }
            )
        elif tag == TAG_EXAMPLE:
            if senses:
                senses[-1]["examples"].append(value)
        # noqa de SIM102 a proposito: las tres ramas con guarda (P, E, T) tienen la misma
        # forma. Aplanar solo esta la volveria asimetrica respecto de las otras dos, que ruff
        # no marca, y el paralelismo es lo que hace legible la cadena.
        elif tag == TAG_TRANSLATION:  # noqa: SIM102
            if senses:
                senses[-1]["translations"].append(value)
        elif tag == TAG_SYNONYM:  # noqa: SIM102
            if senses:
                senses[-1]["synonyms"].append(value)
        elif tag == TAG_ANTONYM:  # noqa: SIM102
            if senses:
                senses[-1]["antonyms"].append(value)
        # Los tags desconocidos se ignoran a proposito: un builder mas nuevo puede agregar
        # campos sin romper un lector viejo.
    return part_of_speech, senses


def compress(text, dictionary):
    """Comprime a los bytes que van en entry.payload."""
    compressor = zlib.compressobj(9, zlib.DEFLATED, _RAW_DEFLATE, zdict=dictionary)
    return compressor.compress(text.encode("utf-8")) + compressor.flush()


def decompress(blob, dictionary):
    """Inverso de compress(). Tiene que dar exactamente lo mismo que PayloadCodec.decode."""
    decompressor = zlib.decompressobj(_RAW_DEFLATE, zdict=dictionary)
    return (decompressor.decompress(blob) + decompressor.flush()).decode("utf-8")


def dictionary_digest(dictionary):
    """Hash del diccionario precargado, para guardar en meta.payload_dict_sha256.

    Motivo: deflate NO detecta un diccionario precargado equivocado. Si tiene el largo
    suficiente, descomprime sin error y devuelve texto corrupto -- se verifico que
    "moverse rapidamente" sale como " nadrse rapidamente", sin ninguna excepcion. Un pack con
    el diccionario mal llenaria la pantalla de basura sin una sola pista del motivo.

    El lector comprueba este hash UNA VEZ al abrir el pack, no por entrada. Se probo antes un
    canario comprimido derivado del propio diccionario y se descarto: como el valor esperado se
    calculaba desde el mismo diccionario, un diccionario truncado seguia validando.
    """
    return hashlib.sha256(dictionary).hexdigest()


def build_dictionary(samples, max_bytes=32 * 1024):
    """Arma el diccionario precargado a partir de payloads de muestra.

    zlib no entrena diccionarios como zstd: el diccionario es simplemente texto, y lo que sirve
    es que contenga las subcadenas frecuentes del corpus, con las mas frecuentes al FINAL
    (deflate prefiere las coincidencias mas cercanas al inicio de los datos, que corresponden
    al final de la ventana del diccionario).

    Estrategia: contar n-gramas de palabras completas y quedarse con los mas repetidos hasta
    llenar el presupuesto de 32 KB, que es el maximo que usa deflate.
    """
    from collections import Counter

    counts = Counter()
    for sample in samples:
        tokens = sample.replace("\t", " \t ").replace("\n", " \n ").split(" ")
        tokens = [t for t in tokens if t]
        for size in (2, 3, 4):
            for i in range(len(tokens) - size + 1):
                counts[" ".join(tokens[i : i + size])] += 1

    # Se ordena de menos a mas frecuente para que los mas frecuentes queden al final.
    ranked = sorted(
        (item for item in counts.items() if item[1] > 1),
        key=lambda item: (item[1], len(item[0])),
    )

    chosen = []
    total = 0
    for phrase, _count in reversed(ranked):
        encoded = (phrase + " ").encode("utf-8")
        if total + len(encoded) > max_bytes:
            continue
        chosen.append(encoded)
        total += len(encoded)

    # chosen esta de mas a menos frecuente; se invierte para que el mas frecuente quede al final.
    return b"".join(reversed(chosen))
