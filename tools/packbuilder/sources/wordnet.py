"""Tesauro desde WordNet: sinonimos y antonimos que el Wikcionario no tiene.

**Por que hace falta otra fuente.** Los sinonimos del pack salen del wiki, que los escribe a
mano y por lo tanto de forma desigual: **18,4 % de las entradas en español y 15,4 % en ingles**.
WordNet esta construido al reves -- agrupa por SIGNIFICADO, asi que cada *synset* **es** un
conjunto de sinonimos, y la cobertura no depende de que alguien se acordara de escribirlos.

**Dos formatos, dos idiomas, la misma idea:**

    ingles    Open English WordNet 2024, formato WN-LMF (XML).  CC BY 4.0
              120.630 synsets, 161.646 pares lema+pos, 7.996 relaciones de antonimia
    español   Multilingual Central Repository via OMW, formato .tab.  CC BY 3.0
              78.417 synsets con lemas españoles, 90.899 lemas

⚠️ **La regla de atribucion es la de siempre y aca muerde fuerte**: un synset es *una* acepcion.
Si nuestra entrada tiene varias, o si el lema esta en varios synsets de su categoria, **no se
sabe de cual son** esos sinonimos, y colgarlos de la primera acepcion es el error de D-117.
Solo entran cuando hay **una acepcion de nuestro lado y un synset del suyo**.

**Lo que rinde, medido contra los packs reales:**

    ingles    21.143 entradas ganan sinonimos  ·  2.486 ganan antonimos
    español    5.504 entradas ganan sinonimos

⚠️ **Y el español trae ruido que el ingles no.** El MCR se construyo automaticamente y mete
flexiones dentro del synset --"coreano" con "coreana, coreanos"-- y algun synset mal mapeado
("uno" con "dos"). Las flexiones se filtran contra la tabla `form` del propio pack, que es un
dato que ya tenemos; el synset mal mapeado no tiene filtro estructural y pasa. Medido: de 5.737
entradas candidatas, 233 pierden algun candidato por flexion o grafia.

**La antonimia solo viene del ingles, y es deliberado.** En WordNet es una relacion **lexica**,
entre acepciones y no entre synsets, asi que **no se puede transferir a otro idioma** por el
synset compartido: el MCR da lemas por synset y eso no alcanza para saber que acepcion española
es el opuesto de cual. Inventarlo seria peor que no tenerlo, porque un antonimo mal atribuido se
lee como lo contrario de otra cosa (D-126).
"""

import collections
import gzip
import os
import re
import sys
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import normalize  # noqa: E402

# Las categorias de WordNet mapeadas al vocabulario de `pos` del pack. "s" es "adjetivo
# satelite", que en WordNet es un matiz de adjetivo y en un reloj no es una distincion util.
POS = {"n": "noun", "v": "verb", "a": "adj", "s": "adj", "r": "adv"}

# Mismo tope que el resto del pipeline: un renglon de reloj.
MAX_POR_ACEPCION = 4

# Un termino tiene que EMPEZAR con letra y no traer digitos ni simbolos raros. Saca los "1" y
# los "2" que el MCR mete en el synset de los numerales.
_TERMINO = re.compile(r"^[^\W\d_][\w\s'’.-]*$", re.UNICODE)


# Cuando dos terminos son **la misma palabra con otra terminacion** y no dos palabras distintas.
#
# El MCR mete `decolorarse` con `decolorar`, `organismos` con `organismo`, `basicamente` con
# `básicamente`. No es informacion: es la palabra otra vez, y en un reloj gasta el unico renglon.
#
# La regla no sabe español: **uno es prefijo del otro y solo cambia una terminacion corta**. El
# piso de raiz evita matar abreviaturas legitimas ("Oct" de "October"), y el techo de diferencia
# evita matar parientes de verdad ("ente"/"entidad", que difieren en cuatro).
#
# Medido sobre el tesauro español: saca 6.318 de 99.292 candidatos (6,4 %).
RAIZ_MINIMA = 4
DIFERENCIA_MAXIMA = 3


def _es_variante_morfologica(uno, otro):
    corto, largo = sorted((normalize.norm(uno), normalize.norm(otro)), key=len)
    return (
        len(corto) >= RAIZ_MINIMA
        and largo.startswith(corto)
        and len(largo) - len(corto) <= DIFERENCIA_MAXIMA
    )


def _limpio(termino):
    termino = (termino or "").strip()
    return termino if termino and _TERMINO.match(termino) else None


def english(path):
    """Tesauro del Open English WordNet (WN-LMF comprimido).

    Devuelve `(lema, pos) -> {"synonyms": [...], "antonyms": [...]}`, **solo para los lemas que
    estan en UN synset de su categoria**: con varios no se sabe de cual acepcion son.

    Los antonimos no llevan esa restriccion porque en WordNet **la antonimia es una relacion
    entre acepciones concretas**, no entre synsets: la atribucion ya viene dada.
    """
    miembros = collections.defaultdict(list)
    synsets_de = collections.defaultdict(set)
    antonimos = collections.defaultdict(set)
    sense_de = {}
    abrir = gzip.open if path.endswith(".gz") else open
    with abrir(path, "rb") as handle:
        for _evento, el in ET.iterparse(handle, events=("end",)):
            if not el.tag.endswith("LexicalEntry"):
                continue
            lemma = el.find("Lemma")
            if lemma is not None:
                clave = (lemma.get("writtenForm"), POS.get(lemma.get("partOfSpeech")))
                for sense in el.findall("Sense"):
                    sid, syn = sense.get("id"), sense.get("synset")
                    sense_de[sid] = clave
                    miembros[syn].append(clave)
                    synsets_de[clave].add(syn)
                    for rel in sense.findall("SenseRelation"):
                        if rel.get("relType") == "antonym":
                            antonimos[sid].add(rel.get("target"))
            el.clear()

    out = {}
    for clave, syns in synsets_de.items():
        if len(syns) != 1:
            continue
        hermanos = _hermanos(miembros[next(iter(syns))], clave)
        if hermanos:
            out.setdefault(clave, {})["synonyms"] = hermanos
    for sid, destinos in antonimos.items():
        origen = sense_de.get(sid)
        if origen is None:
            continue
        opuestos = []
        for destino in destinos:
            termino = _limpio((sense_de.get(destino) or (None,))[0])
            if termino and termino != origen[0] and termino not in opuestos:
                opuestos.append(termino)
        if opuestos:
            out.setdefault(origen, {})["antonyms"] = opuestos[:MAX_POR_ACEPCION]
    return out


def spanish(path):
    """Tesauro del MCR via OMW (formato `.tab` de tres columnas).

    `synset<TAB>spa:lemma<TAB>termino`. Solo sinonimos: ver el docstring del modulo sobre por que
    la antonimia no se transfiere.
    """
    miembros = collections.defaultdict(list)
    synsets_de = collections.defaultdict(set)
    with open(path, encoding="utf-8") as handle:
        for linea in handle:
            if linea.startswith("#"):
                continue
            campos = linea.rstrip("\n").split("\t")
            if len(campos) < 3 or not campos[1].endswith("lemma"):
                continue
            synset, termino = campos[0], campos[2].strip()
            pos = POS.get(synset[-1:])
            if not termino or pos is None:
                continue
            clave = (termino, pos)
            miembros[synset].append(clave)
            synsets_de[clave].add(synset)

    out = {}
    for clave, syns in synsets_de.items():
        if len(syns) != 1:
            continue
        hermanos = _hermanos(miembros[next(iter(syns))], clave)
        if hermanos:
            out[clave] = {"synonyms": hermanos}
    return out


def _hermanos(miembros_del_synset, clave):
    """Los otros lemas del synset, limpios, deduplicados y topeados."""
    out = []
    for termino, _pos in miembros_del_synset:
        limpio = _limpio(termino)
        if (
            limpio
            and limpio != clave[0]
            and limpio not in out
            and not _es_variante_morfologica(limpio, clave[0])
        ):
            out.append(limpio)
        if len(out) == MAX_POR_ACEPCION:
            break
    return out
