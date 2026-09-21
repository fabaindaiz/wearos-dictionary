"""The English Wiktionary's Spanish section, read as a BILINGUAL pack (ES to EN).

The entries come straight from `kaikki.records`: the dump is the same wiktextract JSONL the
monolingual packs are built from, only a different edition -- the words are Spanish and the glosses
are English. That module already prunes inflection pages into the `form` table and applies the
proper-noun policy, so none of it is repeated here.

⚠️ **What IS new is the reverse index, and it has to be derived.** The dump has **no
`translations` field at all**. Answering "what is `dog` in Spanish" means deciding which English
glosses are translations rather than descriptions, and the split is not close: measured over
141,166 senses, **11.7 % are a translation and 88.3 % are a paraphrase**.

So the rule here is deliberately conservative. `MatchKind.TRANSLATION` promises the reader that a
row answers the word they typed; a key taken from a description would make it mean "some definition
mentions this", which is what the FTS rung already does and says so. A wrong key sends the reader to
another word, which is worse than finding nothing -- the same reasoning D-126 applies to synonyms.
"""

import re

# The longest a term can be and still be a translation rather than a description.
#
# ⚠️ **Two words, and the second one earns its place**: "without charge", "dining room", "to run".
# At three the measured content stops being translations and becomes definitions with commas --
# "a thing that goes fast" -- and indexing those makes the translation rung mean nothing.
MAX_WORDS_PER_TERM = 2

# Wordings that mark a gloss as a description of the word rather than a word.
#
# They are matched at the START of a term on purpose: "the act of running" is a description, while
# "the" alone is a translation of "el". Matching anywhere would drop real terms.
# ⚠️ `or` and `and` are in the list and `of` is NOT, and that pair is the whole subtlety: a
# translation never STARTS with a conjunction --"or cultures)" is the tail of a phrase the
# separator cut-- while "of course" is a perfectly good translation of "por supuesto".
_DESCRIBES = re.compile(
    r"^(a|an|the|used|any|one|someone|something|act|state|quality|form|or|and|"
    r"plural|singular|feminine|masculine|past|present|future|gerund|participle|"
    r"first-person|second-person|third-person|diminutive|augmentative|superlative)\b",
    re.IGNORECASE,
)

# A parenthetical is a disambiguation glued onto a translation: "foot (a part of the body)".
_PARENTHETICAL = re.compile(r"\([^)]*\)")

# What can sit around a term without being part of it.
#
# ⚠️ **Found by reading the real output, not by reasoning about it.** The dump has glosses with
# unbalanced parentheses and stray typographic quotes, and they came through as keys like
# `CAT scan")` and `or cultures)` -- strings nobody can type, so dead weight in the index.
_BORDES = " \t.,;:\"'()[]\u201c\u201d\u2018\u2019"

# Where a gloss lists alternatives. Semicolons separate senses more often than terms, but in this
# dump both appear as alternatives of the same idea.
_SEPARATORS = re.compile(r"[,;]")


def translation_keys(gloss, for_search=True):
    """The English terms of a gloss. Empty if the gloss describes rather than translates.

    The keys are returned raw: `PackBuilder` normalises them, the same way it normalises forms, so
    that `trans.norm` is computed by exactly the function that indexes everything else.

    ⚠️ **`for_search` separa los dos canales, y la diferencia se vio al escribir la ficha.** Para
    BUSCAR hace falta indexar `to run` **y** `run`, porque nadie teclea la preposicion; para
    MOSTRAR, las dos juntas son ruido -- la lista quedaba *"to run, run, to jog, jog"* en una
    pantalla de 234 dp. El canal de lectura se queda con la forma que la fuente escribio, que
    ademas es la forma de diccionario.
    """
    if not gloss or not gloss.strip():
        return []

    salida = []
    for bruto in _SEPARATORS.split(gloss):
        termino = " ".join(_PARENTHETICAL.sub(" ", bruto).split()).strip(_BORDES)
        # A leftover bracket means the gloss had an unbalanced one and what is left is a fragment
        # of a description, not a term.
        if not termino or "(" in termino or _DESCRIBES.match(termino):
            continue
        palabras = termino.split()
        if not palabras or len(palabras) > MAX_WORDS_PER_TERM:
            continue
        # "to run" is how the dump writes an infinitive; somebody looking up the translation types
        # "run". Both are indexed, and the bare form goes in as its own key.
        candidatos = [termino]
        if for_search and len(palabras) == 2 and palabras[0].lower() == "to":
            candidatos.append(palabras[1])
        for candidato in candidatos:
            if candidato and candidato not in salida:
                salida.append(candidato)
    return salida


# Cuantos equivalentes del otro idioma muestra una entrada inversa.
#
# ⚠️ **8, el mismo tope que las traducciones por acepcion, y por el mismo motivo**: en 234 dp una
# lista mas larga deja de leerse y empieza a empujar. Los equivalentes van ordenados por `rank`,
# asi que los 8 que quedan son los 8 mas comunes y no los 8 primeros del volcado.
MAX_EQUIVALENTES = 8


def records(path, lang="es", politica=None, frequencies=None, lang_dst=None,
            flexiones=None):
    """The bilingual records: what `kaikki` yields, with `translations` filled in.

    ⚠️ **The entry side is not re-implemented and that is the point.** Pruning, homograph grouping,
    the proper-noun policy and the inbound forms are decisions with their own measurements in
    `kaikki.py`; a second copy of them here would be a second place to fix every bug.

    ⚠️ **Con `lang_dst` el pack se vuelve BIDIRECCIONAL POR CONSTRUCCION**, que es una cosa
    distinta de lo que habia: hasta aca las palabras inglesas vivian solo en `trans`, un indice de
    `norm` a entrada española. Eso hacia que `dog` **encontrara** `perro`, pero `dog` no era un
    lema: no habia ficha que abrir, ni etiqueta de idioma, ni forma de que la app supiera que el
    pack lo conoce. Ahora cada palabra del otro idioma es una `Record` con su `lang`, sus
    equivalentes y su `rank`.

    ⚠️ **Se derivan del mismo volcado que ya se leyo, no de una fuente nueva.** Es el argumento de
    D-175 para el nucleo: derivar hace la afirmacion cierta **por construccion**. Si `dog` lleva a
    `perro` es porque la glosa de `perro` decia `dog`; no porque dos fuentes coincidieran y nadie
    lo comprobara. Cuesta cero horas de build y cero dumps nuevos.

    ⚠️ **Y `trans` deja de llenarse**: seria una segunda copia del mismo indice, porque buscar
    `dog` ya funciona por `entry.norm`. Medido sobre el pack real: 474.849 filas, **13,3 MiB**.
    """
    from . import kaikki

    if lang_dst is None:
        yield from _una_direccion(path, lang, politica, frequencies)
        return
    yield from _dos_direcciones(path, lang, politica, frequencies, lang_dst, flexiones)


def bidireccional(registros, lang, lang_dst, flexiones=None):
    """Los registros propios, y detras las entradas del otro idioma derivadas de sus claves.

    Vive aca y no en cada fuente porque **el toy tiene que hacer exactamente lo mismo**: un pack
    de juguete que no fuera bidireccional dejaria la rama nueva sin el unico fixture que corre en
    un dispositivo, y un canal sin fixture se rompe sin que nada avise -- ya paso con el tag `W`.

    ⚠️ **El mapa inverso se acumula en memoria y eso rompe el streaming a proposito.** El resto
    del builder nunca carga el volcado entero --son 1,1 GB-- pero esto no es el volcado: son las
    claves ya podadas. Medido sobre el pack real, **164.249 terminos ingleses** con 474.849 pares,
    que en memoria son ~100 MB y en disco **+20,4 MiB** de entradas contra los 13,3 MiB que se
    ahorran vaciando `trans`. No hay forma de evitarlo: un termino del otro idioma no sabe
    cuantos equivalentes tiene hasta que se leyo el volcado entero.
    """
    inverso = {}
    for record in registros:
        for clave in record.translations:
            # `rank` va en la tupla para ordenar despues: el equivalente mas comun primero, que
            # es la misma regla con la que se ordena cualquier lista de esta app.
            inverso.setdefault(clave, []).append(
                (record.rank, record.headword, record.part_of_speech))
        # ⚠️ El canal de busqueda se VACIA aca y no en el builder: `trans` existe para los packs
        # monolingues, donde la palabra del otro idioma no es un lema. Aca si lo es.
        record.translations = ()
        # ⚠️ **Explicito y no heredado del pack.** `Record.lang = None` significa "el primario",
        # que alcanza en un pack de un solo idioma; aca reordenar `meta.langs` reetiquetaria
        # todas las entradas propias **en silencio**. En un pack bidireccional cada entrada dice
        # cual es el suyo.
        record.lang = lang
        yield record

    flexiones = flexiones or {}
    for clave, equivalentes in inverso.items():
        equivalentes.sort()
        yield _entrada_inversa(clave, equivalentes, lang_dst, flexiones.get(clave, ()))


def _dos_direcciones(path, lang, politica, frequencies, lang_dst, flexiones=None):
    """El volcado leido una vez, y las dos direcciones que salen de el. Ver [bidireccional]."""
    yield from bidireccional(
        _una_direccion(path, lang, politica, frequencies), lang, lang_dst, flexiones)


def _entrada_inversa(headword, equivalentes, lang_dst, forms=()):
    """Una palabra del otro idioma, con sus equivalentes como cuerpo.

    ⚠️ **No lleva acepciones, y esa ausencia es honesta.** Un diccionario bilingue contesta *«como
    se dice»*, no *«que significa»*: las definiciones inglesas son de `en-def-wikt` y meterlas aca
    seria fundir dos packs. El cuerpo va en el canal `W` --traduccion de la PALABRA-- porque es
    exactamente lo que es: no se puede atribuir a una acepcion que no existe (D-117).

    ⚠️ **El `pos` se hereda del equivalente mas comun**, con 100 % de cobertura medido: el volcado
    no trae `pos` para el lado ingles, y la traduccion de un sustantivo es un sustantivo. Es una
    inferencia, no un dato, pero es la misma que haria el lector.

    ⚠️ **Y el `rank` tambien se hereda**, del mejor equivalente: `dog` es tan comun como `perro`.
    Sin esto las entradas inversas entrarian todas con rank 0 y taparian a las propias.

    ⚠️ **`forms` son las flexiones del OTRO idioma, y sin ellas la direccion inversa se cae.**
    Medido: al volver entradas las palabras inglesas y vaciar `trans`, la cobertura del top 1.000
    ingles cayo de **98,4 % a 89,8 %**. `trans` estaba tokenizada (D-014) y `--flexiones` metia
    ahi `got`, `been`, `were`, `could`, que asi alcanzaban el lema español -- y las respuestas
    que se perdian eran correctas (`been -> ser, estar, tener`). Su lugar en el modelo nuevo es
    `form` de la entrada inglesa: `got` es una flexion de `get`, y `get` ya es un lema. Queda
    simetrico con el lado español, que es lo que el pack bidireccional afirma.
    """
    from build import Record

    mejor_rank, _, mejor_pos = equivalentes[0]
    return Record(
        headword=headword,
        senses=[],
        part_of_speech=mejor_pos,
        rank=mejor_rank,
        word_translations=tuple(
            hw for _, hw, _ in equivalentes[:MAX_EQUIVALENTES]),
        forms=tuple(forms),
        lang=lang_dst,
    )


def _una_direccion(path, lang, politica, frequencies):
    from . import kaikki

    if politica is None:
        politica = kaikki.POLITICA_POR_DEFECTO
    # ⚠️ `frequencies` se encadena aca y no es un detalle: este es el pack donde el defecto
    # de orden era PEOR. `orderFor` aplica la banda de D-142 solo a `PREFIX`, asi que su
    # peldaño `TRANSLATION` ordena por `rank` puro -- `house` devolvia `solar, alojar,
    # albergar` y nunca `casa`. Sin esta linea, el unico peldaño sin defensa seguiria roto.
    for record in kaikki.records(path, lang=lang, politica=politica,
                                 frequencies=frequencies):
        claves = []
        for sense in record.senses:
            propias = translation_keys(sense.get("gloss"), for_search=False)
            # ⚠️ **El canal de LECTURA, y hasta hoy estas claves se calculaban y se tiraban.**
            # `record.translations` alimenta la tabla `trans`, que `PackBuilder` normaliza y D-014
            # tokeniza: sirve para buscar y no para leer. Medido sobre el pack real, eran
            # **206.727 filas de `trans` y CERO en `T`/`W`** -- el pack con mas traducciones del
            # catalogo era el unico que no podia mostrarlas.
            #
            # ⚠️ **La atribucion aca es ESTRUCTURAL**, como los sinonimos anidados de D-124: cada
            # termino sale de la glosa de ESA acepcion, asi que no hay nada que adivinar y el
            # canal de nivel de entrada queda vacio por construccion.
            sense["translations"] = propias
            # El canal de busqueda lleva ADEMAS las formas derivadas (`run` de `to run`).
            for clave in translation_keys(sense.get("gloss")):
                if clave not in claves:
                    claves.append(clave)
        record.translations = tuple(claves)
        yield record
