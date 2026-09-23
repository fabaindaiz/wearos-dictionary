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
import os
import re
import sys
import unicodedata

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import casefold as _casefold  # noqa: E402
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

# Where the example above it was quoted from: year and author, already trimmed by the source
# reader. Additive like [TAG_ANTONYM] and [TAG_RELATED], so it **does not bump [CODEC_ID]**
# either -- an old reader ignores it and shows the example with no attribution, which is correct
# degradation.
#
# ⚠️ **A tag and not a [REF_SEPARATOR] suffix on `E`, and the difference is not cosmetic.** The
# suffix mechanism is right there and translations use it, but it is only additive on a tag that
# is *born* with it: bolting it onto `E` would make an old reader paint the raw `\x1f` byte in
# the middle of the example. A new tag degrades to nothing; a new suffix degrades to garbage.
#
# ⚠️ **It only ever names the example written immediately above it.** Measured on the English
# dump, 86,5 % of the examples are `type: quotation` -- lines lifted from a published text -- so
# an example with no provenance is the common case, not the exception: without the citation the
# reader sees a sentence from an 1897 novel and cannot tell it from a definition. See the
# strictness in [parse]: a `C` that does not directly follow its `E` is DROPPED, because
# choosing an example for it would be the invented attribution of D-179.
TAG_CITATION = "C"

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

#: A **principal part** of the word: gerund, participle, plural or feminine.
#:
#: WARNING: the value is `key:form`, and the key is deliberately neutral. Storing the label
#: already translated --"gerundio"-- would put the interface's language INSIDE the pack, and the
#: same file is shared by a user running the app in Spanish and one running it in English. The app
#: maps the key to its localized string, which is where translation belongs (CLAUDE.md, Working
#: style).
#:
#: WARNING: these are FEW and chosen, not the whole conjugation. `correr` carries 137 forms in the
#: source and 202 rows in `form`; dumping those into the payload would be unreadable on a watch
#: and expensive in bytes -- `form` is already 42 % of the Spanish pack. What travels here are the
#: parts the rest derive from, which is what a printed dictionary puts beside the headword.
TAG_FORM = "F"

#: Separates the key from the form inside a [TAG_FORM]. A colon and not a tab: `sanitize` strips
#: tabs because they would split the line, and this value has to survive it.
FORM_SEPARATOR = ":"

# Deflate crudo: sin encabezado zlib. El encabezado trae un DICTID que obliga al lector a
# esperar needsDictionary(); sin encabezado los dos lados fijan el diccionario de entrada.
_RAW_DEFLATE = -15

# Separa el termino de la acepcion a la que apunta, DENTRO del valor de un item.
#
# ⚠️ **El reparto de las tres partes de una referencia `(pack, palabra, acepcion)` es el diseño
# entero, y cada una vive donde cuesta menos:**
#
#     pack      -> NO se nombra. El destino se declara por IDIOMA en `meta.translations_to`,
#                  una vez por pack. Nombrar un pack concreto mataba el enlace del usuario
#                  que tiene instalado el nucleo y no el completo (D-180).
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


# La puntuacion que una fuente pone al final de una glosa y otra no.
_CIERRE = " .;:,"

# ⚠️ **El espacio se enumera a mano y NO se usa `\s`, y eso es una trampa entre lenguajes.**
# En Python `\s` sobre `str` es **Unicode** y en Java/Kotlin es **ASCII**: un espacio duro
# (U+00A0) se colapsaria de un lado y del otro no, y los dos codigos de la misma acepcion
# quedarian distintos **sin error y sin log**. Se pierde plegar el espacio duro --que aparece en
# alguna glosa-- a cambio de que los dos lenguajes hagan exactamente lo mismo, que es el trato
# que este repo ya eligio para `norm()`.
_ESPACIO = re.compile("[ \t\n\r\f\v]+")


def fold_gloss(gloss):
    """Pliega una glosa para decidir si dos fuentes escribieron **la misma** acepcion.

    ⚠️ **El plegado de caja SIGUE EL ESTANDAR**: `toCaseFold()`, regla R4 de la seccion 3.13 del
    Estandar Unicode, que es la operacion que UAX #31 define para *caseless matching*. El estandar
    separa explicitamente las dos: `toLowerCase()` es **case mapping**, para MOSTRAR texto;
    `toCaseFold()` es **case folding**, para COMPARARLO. La primera version usaba `lower()`, que es
    la equivocada -- medido, **242 de 133.730** code points del repertorio fijado difieren
    (`ß`→`ss`, `ſ`→`s`, `ς`→`σ`), y sobre las glosas reales 8 de 97.337 en español.

    Viene de la tabla fijada de [casefold] y no de `str.casefold()`, porque Java **no tiene**
    `toCaseFold()` y lo unico que lo ofrece es ICU, que D-003 prohibe.

    ⚠️ **Lo que si es una regla NUESTRA y versionada es quitar la puntuacion final**: ningun
    estandar lo hace. Es una decision de CONTENIDO --el Wikcionario escribe "Casa." y Wikidata
    "casa"-- y cambiarla invalida todos los enlaces ya escritos, asi que es un acto deliberado y
    lo fija un vector en los dos lenguajes.

    Decidido con el numero sobre la mesa: entre el Wikcionario y Wikidata sube la coincidencia de
    **34,40 % a 42,21 % (+1.531 acepciones)**. Los fallos que recupera se ven leyendo:

        wikcionario: "Condición o carácter de torpe."
        wikidata   : "condición o carácter de torpe"

    **Ligero a proposito**: minusculas, espacios colapsados y puntuacion final fuera. **NO** saca
    acentos -- `publico` y `público` son palabras distintas, y dos glosas que solo difieren en eso
    no son la misma acepcion. Cuanto mas plegara, mas acepciones distintas fundiria en silencio.

    ⚠️ **Lo usan `sense_code` Y `merge_duplicate_senses`, y tiene que ser asi**: si solo plegara
    el codigo, dos acepciones que difieren en un punto compartirian codigo sin fusionarse y una
    quedaria **inalcanzable** -- justo la excepcion que el invariante cierra.
    """
    plegada = _casefold.fold(unicodedata.normalize("NFC", gloss).strip())
    return _ESPACIO.sub(" ", plegada).strip(_CIERRE)


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

    ⚠️ **Se calcula sobre la glosa plegada por [fold_gloss], NO sobre `norm()`, y esa distincion
    es el precedente de D-055 aplicado tal cual.** El plegado es ligero y propio; `norm()` es la
    funcion del invariante central y esta atada a `NORM_VERSION`. `stable_uid` ya decidio lo mismo y dejo escrito por que: *«asi no
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
    material = "%d\x1f%s" % (uid, fold_gloss(gloss))
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


def _example_parts(item):
    """`(text, ref)` of one example. A bare string is an example with no known source.

    ⚠️ **Both shapes are accepted on purpose, and the bare string is the canonical one.** Five
    sources emit examples today -- `oewn`, `wikidata`, `bilingual`, `toy`, `enwikt_examples` --
    plus the Tatoeba sentence that `build.py` appends to an already rendered body, and none of
    them has a citation to give: Tatoeba is credited once per pack in `meta.sources`, not per
    sentence. Widening the type instead of migrating them is what keeps that true without
    touching any of the five.

    [parse] returns the same two shapes, so a round trip is stable in both directions: an
    example with no citation goes out a string and comes back a string.
    """
    if isinstance(item, dict):
        return item.get("text", ""), item.get("ref")
    return item, None


def example_text(item):
    """Solo el texto de un ejemplo, sea cual sea su forma. Ver [_example_parts].

    Existe para `build._fts_body`, que indexa el ejemplo y **no** su cita: publicarla como
    funcion en vez de dejar que cada llamador haga su propio `isinstance` es lo que evita que
    dentro de un mes haya dos ideas distintas de que es un ejemplo.
    """
    return _example_parts(item)[0]


# Los campos de una acepcion que son listas, en el orden en que se escriben.
_LISTAS = ("examples", "translations", "synonyms", "antonyms", "related")


def merge_duplicate_senses(senses):
    """Funde las acepciones que comparten glosa, conservando el orden y los adjuntos de todas.

    ⚠️ **Es lo que hace cierta la propiedad "toda acepcion es alcanzable por `(idioma, palabra,
    acepcion)`".** El codigo de una acepcion sale de `(uid, glosa)`, asi que dos acepciones de la
    misma entrada con la glosa identica **comparten codigo** y una de las dos queda inalcanzable.
    Medido sobre los seis packs reales: **350 acepciones de 1,7 millones** caian en ese caso --
    todas glosas que la fuente escribe dos veces (`y` → *and*, cinco veces).

    ⚠️ **Se fusionan y no se descartan, y eso lo decidio una medicion**: de 12 grupos duplicados
    inspeccionados, **5 traian adjuntos distintos** -- `them` repite *"Used as the direct object
    of a verb"* con **ejemplos diferentes**. Descartar la copia habria perdido ese dato en
    silencio, que es justo el modo de falla que este repo no acepta.

    No se vuelven a topear los adjuntos. El desborde esta acotado y medido: son ~5 acepciones en
    todo el corpus las que quedan con un ejemplo de mas, contra 1,7 millones.

    Vive aca y no en cada fuente porque `render` es el **unico** paso por el que pasan todos los
    packs: puesto en `kaikki` habria que repetirlo en `oewn`, `wikidata` y `bilingual`, y la
    propiedad seria cierta sólo en los packs cuyo autor se acordo.
    """
    salida = []
    por_glosa = {}
    for sense in senses:
        # ⚠️ La MISMA clave que usa `sense_code`: si divergieran, dos acepciones compartirian
        # codigo sin fusionarse y una quedaria inalcanzable.
        gloss = fold_gloss(sense.get("gloss", ""))
        previa = por_glosa.get(gloss)
        if previa is None:
            copia = dict(sense)
            for campo in _LISTAS:
                copia[campo] = list(sense.get(campo, ()))
            por_glosa[gloss] = copia
            salida.append(copia)
            continue
        for campo in _LISTAS:
            for valor in sense.get(campo, ()):
                if valor not in previa[campo]:
                    previa[campo].append(valor)
    return salida


def _sin_repetir(valores):
    """Los items, sin los que ya aparecieron, **conservando el orden de la primera aparicion**.

    ⚠️ **El orden es informacion y por eso no se ordena ni se usa un set**: la fuente pone
    primero lo que mas se usa, y con topes de 4 y de 8 el orden decide QUE SE VE.

    ⚠️ **Lo encontro barrer los packs construidos, no un test.** Medido sobre los reales: el
    **3,1 %** de las entradas con traducciones de palabra del pack bilingue repetian un termino
    --`where` traia `donde, donde` y `do, do`; `Brazil` traia `carioca` dos veces-- y en el pack
    español eran **88 de 407** de las que traen traducciones por acepcion. En una fila de reloj
    eso es la misma palabra dos veces, ocupando un ancho que ya se corta.

    Se compara el item **entero y exacto**: `donde` y `dónde` son palabras distintas y las dos
    se quedan.
    """
    vistos = set()
    salida = []
    for valor in valores:
        # La clave es el item tal como se emite -- una tupla de traduccion con su acepcion es
        # distinta de la misma palabra sin acepcion, y las dos tienen sentido.
        clave = valor if isinstance(valor, (str, tuple)) else repr(valor)
        if clave in vistos:
            continue
        vistos.add(clave)
        salida.append(valor)
    return salida


def render(part_of_speech, senses, word_translations=(), forms=()):
    """Serializa a texto. `senses` es una lista de dicts con gloss/examples/translations.

    Los valores se sanean aca: un tab perdido en una glosa de Wiktionary corromperia la
    entrada entera y el sintoma apareceria recien en el reloj.

    ⚠️ **Y se deduplican las listas, aca y no en cada fuente.** Mismo argumento que
    `merge_duplicate_senses`: `render` es el **unico** paso por el que pasan todos los packs, asi
    que puesto en `kaikki` habria que repetirlo en `oewn`, `wikidata` y `bilingual` y la
    propiedad seria cierta solo en los packs cuyo autor se acordo. Ver [_sin_repetir].
    """
    lines = []
    if part_of_speech:
        pos = sanitize(part_of_speech)
        if pos:
            lines.append(TAG_PART_OF_SPEECH + "\t" + pos)
    senses = merge_duplicate_senses(senses)
    for translation in _sin_repetir(word_translations):
        value = _sanitize_item(translation)
        if value:
            lines.append(TAG_WORD_TRANSLATION + "\t" + value)
    # Before the senses, like `W`: they describe the WORD, not one of its senses.
    for key, form in forms:
        clave = sanitize(key).replace(FORM_SEPARATOR, "")
        valor = sanitize(form)
        if clave and valor:
            lines.append(TAG_FORM + "\t" + clave + FORM_SEPARATOR + valor)
    for sense in senses:
        gloss = sanitize(sense.get("gloss", ""))
        if not gloss:
            # Una acepcion sin glosa no aporta nada y descolgaria sus ejemplos.
            continue
        lines.append(TAG_SENSE + "\t" + gloss)
        for example in _sin_repetir(sense.get("examples", ())):
            texto, cita = _example_parts(example)
            value = sanitize(texto)
            if not value:
                # Sin ejemplo no hay de que colgar la cita, y una cita suelta nombraria al
                # ejemplo que venga despues. Se caen las dos juntas.
                continue
            lines.append(TAG_EXAMPLE + "\t" + value)
            atribucion = sanitize(cita) if cita else None
            if atribucion:
                lines.append(TAG_CITATION + "\t" + atribucion)
        for translation in _sin_repetir(sense.get("translations", ())):
            value = _sanitize_item(translation)
            if value:
                lines.append(TAG_TRANSLATION + "\t" + value)
        for synonym in _sin_repetir(sense.get("synonyms", ())):
            value = sanitize(synonym)
            if value:
                lines.append(TAG_SYNONYM + "\t" + value)
        for antonym in _sin_repetir(sense.get("antonyms", ())):
            value = sanitize(antonym)
            if value:
                lines.append(TAG_ANTONYM + "\t" + value)
        for related in _sin_repetir(sense.get("related", ())):
            value = sanitize(related)
            if value:
                lines.append(TAG_RELATED + "\t" + value)
    return "".join(line + "\n" for line in lines)


def parse_forms(text):
    """A payload's principal parts, as `[(key, form), ...]`.

    Separate from [parse] because almost no caller wants them, and changing the tuple parse
    returns would mean touching `verify_pack.py` and all its tests over a datum they do not read.

    A line with no separator is ignored: the cost is losing that form, and throwing over one bad
    line would lose the whole entry.
    """
    salida = []
    for line in text.split("\n"):
        if len(line) < 3 or line[0] != TAG_FORM or line[1] != "\t":
            continue
        valor = line[2:]
        if FORM_SEPARATOR not in valor:
            continue
        clave, forma = valor.split(FORM_SEPARATOR, 1)
        if clave and forma:
            salida.append((clave, forma))
    return salida


def parse(text):
    """Inverso de render(). Existe para verify_pack.py y los tests, no para el camino normal.

    Returns `(pos, senses, word_translations)`. Forms are read with [parse_forms]: `parse` keeps
    its signature because `verify_pack.py` and the tests use it, and changing it would mean
    touching both over a list almost no caller wants.
    """
    part_of_speech = None
    senses = []
    word_translations = []
    # La acepcion cuyo ULTIMO ejemplo todavia puede recibir una cita, o None. Se pone al emitir
    # un `E` y lo borra cualquier otra linea: un `C` que no venga pegado a su `E` se descarta en
    # vez de elegirle un ejemplo. Ver [TAG_CITATION].
    citable = None
    for line in text.split("\n"):
        if not line or len(line) < 2 or line[1] != "\t":
            continue
        tag, value = line[0], line[2:]
        if not value:
            continue
        if tag == TAG_CITATION:
            if citable is not None:
                texto = citable["examples"][-1]
                citable["examples"][-1] = {"text": texto, "ref": value}
            citable = None
            continue
        citable = None
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
                citable = senses[-1]
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
