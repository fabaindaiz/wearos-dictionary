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
import unicodedata
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

# Palabra RELACIONADA de esta acepcion: hiperonimo, hiponimo o pariente morfologico (D-132).
# Tag aditivo como el anterior, asi que tampoco sube CODEC_ID.
#
# **No es un sinonimo y el tag separado es toda la diferencia.** "frances" trae `galo` como
# related; emitido como sinonimo afirmaria una equivalencia que la fuente no da. Se emite solo
# para entradas de una sola acepcion -- ver `sources/kaikki._relacionadas`.
TAG_RELATED = "R"

# Traducciones de la PALABRA, sin acepcion atribuida.
#
# ⚠️ **Es el segundo canal, y existe para que la opcion deshonesta deje de ser la barata.** Con
# `T` solo --que vive dentro de una acepcion-- un builder con dato no atribuible podia tirarlo o
# embadurnarlo por todas las acepciones, y embadurnar es gratis, invisible y pasa `verify_pack`:
# el error de D-117. Medido sobre el dump español, el **37,7 %** de las traducciones no trae
# `sense_index`, y en el pack de muestra eso era el **34,8 % del dato tirado**.
#
# ⚠️ **Se escribe antes de la primera `S` y NO sube [CODEC_ID]** (D-119): un lector viejo lo
# descarta por su guarda `if senses:` y muestra la entrada sin la lista. Pero la posicion es una
# convencion de escritura y **no** la semantica: `parse` lo toma como de la entrada aparezca
# donde aparezca, porque si la posicion decidiera, un `W` mal ubicado se volveria una traduccion
# de acepcion -- la atribucion inventada que este canal existe para evitar.
TAG_WORD_TRANSLATION = "W"

# Deflate crudo: sin encabezado zlib. El encabezado trae un DICTID que obliga al lector a
# esperar needsDictionary(); sin encabezado los dos lados fijan el diccionario de entrada.
_RAW_DEFLATE = -15

# Separa el termino de la acepcion a la que apunta, DENTRO del valor de un item.
#
# ⚠️ **El reparto de las tres partes de una referencia `(pack, palabra, acepcion)` es el diseño
# entero, y cada una vive donde cuesta menos:**
#
#     pack      -> `meta.translations_pack`, UNA vez por pack. Es constante para todas las
#                  traducciones del pack; repetirlo por item costaria ~280 KB de una cadena.
#     palabra   -> el valor del item. Ya estaba ahi: es el termino que se muestra.
#     acepcion  -> este sufijo, OPCIONAL, porque solo existe cuando la fuente la supo.
#
# De ese reparto sale la propiedad que importa: **una traduccion sin acepcion ya es un link a la
# palabra y no cuesta un byte extra**. El caso comun es el gratis.
#
# Se elige `\x1f` porque es el mismo juntador que usa `stable_uid()` y porque **no es whitespace
# para `str.split()`**, asi que sobrevive a `sanitize`. Por eso mismo entra en los prohibidos: si
# la fuente pudiera escribirlo, podria FORJAR una referencia a otra acepcion.
REF_SEPARATOR = "\x1f"

# Caracteres que romperian el formato delimitado. Se sanean al construir, no al leer: el reloj
# no deberia gastar ciclos defendiendose de datos que nosotros mismos generamos.
_FORBIDDEN = str.maketrans({"\t": " ", "\n": " ", "\r": " ", REF_SEPARATOR: ""})


def sanitize(value):
    """Deja un valor apto para el formato delimitado, o None si queda vacio."""
    cleaned = " ".join(value.translate(_FORBIDDEN).split())
    return cleaned or None


# Cuantos caracteres hex del sha256 nombran una acepcion.
#
# 12 hex son 48 bits. Con las 210.249 acepciones del pack español la probabilidad de que dos
# distintas choquen es ~4e-7: despreciable frente a las **22 colisiones reales (0,0105 %)** que
# ya tiene el dato por glosas que el wiki define dos veces. Alargarlo no compraria nada y cada
# caracter se paga en cada referencia.
SENSE_CODE_LENGTH = 12


def sense_code(uid, gloss):
    """Nombra una acepcion **sin nombrar un pack**: unico para `(idioma, palabra, acepcion)`.

    ⚠️ **El idioma y la palabra ya estan dentro de `uid`** --`stable_uid(lang, headword, pos,
    sense_key)`-- asi que alcanza con combinarlo con la glosa. De ahi salen las tres propiedades
    que se pidieron:

    1. **No nombra un pack.** Cualquier pack instalado de ese idioma puede resolverlo, asi que el
       enlace no muere porque el usuario tenga el nucleo en vez del completo.
    2. **El nucleo y el completo lo comparten.** Verificado sobre los packs reales: los **21.534**
       codigos del nucleo español son **identicos** en el completo, porque `build_core.py`
       **copia** el uid en vez de recalcularlo (D-175) y conserva la glosa.
    3. **Degrada a la palabra.** Si ningun pack tiene esa acepcion pero alguno tiene la palabra,
       el termino sigue siendo un enlace util: el codigo es un *sufijo* del termino, no lo
       reemplaza.

    ⚠️ **Se calcula sobre la glosa CRUDA en NFC, no sobre `norm()`, y eso es el precedente de
    D-055 aplicado tal cual.** `stable_uid` ya decidio lo mismo y dejo escrito por que: *«asi no
    depende de NORM_VERSION, y subir las reglas de normalizacion no invalida los packs
    auxiliares»*. Aca muerde mas fuerte todavia -- un bump de `NORM_VERSION`, que D-005 permite
    en cualquier momento, cambiaria **todos** los codigos y dejaria apuntando a la nada cada
    enlace de cada pack ya construido, sin error y sin log.

    NFC y no los bytes crudos porque dos fuentes pueden entregar "á" precompuesta o descompuesta
    para la misma glosa, y serian codigos distintos para la misma acepcion.

    ⚠️ **ESTE ARCHIVO TIENE UN ESPEJO**: `PayloadCodec.senseCode` en Kotlin. Si los dos calculan
    distinto, los enlaces apuntan a la nada **sin error y sin log**, que es el modo de falla
    central de este repo. Lo fija un vector en `test_payload.py` y su gemelo en Kotlin.
    """
    material = "%d\x1f%s" % (uid, unicodedata.normalize("NFC", gloss))
    return hashlib.sha256(material.encode("utf-8")).hexdigest()[:SENSE_CODE_LENGTH]


def make_ref(term, sense_ref=None):
    """Un item de traduccion, como TUPLA `(termino, acepcion_o_None)`.

    ⚠️ **Devuelve una tupla y no una cadena a proposito, y esto no es estilo: es la defensa.**
    La primera version devolvia la cadena ya juntada y `render` tenia que adivinar si un valor
    traia referencia partiendolo por el separador. Con eso, un termino de la fuente que
    **contuviera** el separador --`ho\x1fuse`-- se leia como el termino `ho` apuntando a `use`:
    la fuente podia FORJAR una referencia a otra acepcion. Lo agarro su propio test.

    Con la tupla no hay nada que adivinar: una cadena es siempre un termino y se limpia entera,
    y una referencia solo la puede construir quien llama a esto.
    """
    return (term, sense_ref) if sense_ref else term


def split_ref(value):
    """`(termino, acepcion_o_None)`. Lo que no trae sufijo apunta a la palabra entera."""
    termino, _, destino = value.partition(REF_SEPARATOR)
    return termino, destino or None


def _sanitize_item(value):
    """Serializa un item de traduccion: una cadena es un termino, una tupla es una referencia.

    Las dos partes se limpian **por separado** y recien despues se juntan, asi que el separador
    del formato solo puede venir de nosotros. Ver [make_ref].
    """
    termino, destino = value if isinstance(value, tuple) else (value, None)
    limpio = sanitize(termino)
    if not limpio:
        return None
    apunta = sanitize(destino) if destino else None
    return limpio + REF_SEPARATOR + apunta if apunta else limpio


def render(part_of_speech, senses, word_translations=()):
    """Serializa a texto. `senses` es una lista de dicts con gloss/examples/translations.

    Los valores se sanean aca: un tab perdido en una glosa de Wiktionary corromperia la
    entrada entera y el sintoma apareceria recien en el reloj.
    """
    lines = []
    if part_of_speech:
        pos = sanitize(part_of_speech)
        if pos:
            lines.append(TAG_PART_OF_SPEECH + "\t" + pos)
    for translation in word_translations:
        value = _sanitize_item(translation)
        if value:
            lines.append(TAG_WORD_TRANSLATION + "\t" + value)
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
            value = _sanitize_item(translation)
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
        for related in sense.get("related", ()):
            value = sanitize(related)
            if value:
                lines.append(TAG_RELATED + "\t" + value)
    return "".join(line + "\n" for line in lines)


def parse(text):
    """Inverso de render(). Existe para verify_pack.py y los tests, no para el camino normal.

    Devuelve `(pos, acepciones, traducciones_de_la_palabra)`.
    """
    part_of_speech = None
    senses = []
    word_translations = []
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
                    "related": [],
                }
            )
        elif tag == TAG_WORD_TRANSLATION:
            word_translations.append(value)
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
        elif tag == TAG_RELATED:  # noqa: SIM102
            if senses:
                senses[-1]["related"].append(value)
        # Los tags desconocidos se ignoran a proposito: un builder mas nuevo puede agregar
        # campos sin romper un lector viejo.
    return part_of_speech, senses, word_translations


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
