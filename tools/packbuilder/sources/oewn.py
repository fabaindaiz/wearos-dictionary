"""Fuente: Open English WordNet, formato WN-LMF XML.

**SPIKE** (D-120). Existe para producir un numero --cuanto pesa y cuanto cubre un pack de ingles
construido desde OEWN en vez de desde Wiktionary-- y **no decide nada**. No lo usa la app, no
esta en el catalogo, y el pack que produce no se sube al reloj.

Por que XML y no el JSON, el Turtle o el WNDB que el proyecto tambien publica: `iterparse` y
`gzip` son **stdlib** (D-045) y streamean. El JSON es un unico `@graph` que `json.load` tendria
que sostener entero en memoria, y streamearlo pide `ijson`; el Turtle pide `rdflib`. Los dos
serian una dependencia de terceros que este repo no tiene.

**Dos pasadas, y no es opcional**: los `Synset` --donde vive la glosa-- aparecen DESPUES de
todas las `LexicalEntry` en orden de documento. Con una sola pasada, todas las glosas salen
vacias y el pack se construye sin error y sin nada adentro. Los ~120.000 synsets entran en
memoria de sobra; las 135.969 entradas se emiten en streaming.

**La trampa de iterparse**: sin `elem.clear()` despues de cada hijo, el arbol entero se acumula
y el proceso muere por RAM en un archivo que "es chico" (89 MB descomprimidos).

Lo que OEWN da gratis y el dump ingles de kaikki no puede dar: **el `members` de un synset ES el
conjunto de sinonimos**, por acepcion y estructural. En kaikki el ingles trae 0 de 43.679
sinonimos con `sense_index` (D-117). Es el hallazgo que decide si OEWN vale, y por eso el spike
los extrae aunque el pack de produccion no los use todavia.
"""

import gzip
import os
import sys
import xml.etree.ElementTree as ElementTree

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from build import Record  # noqa: E402

MAX_EXAMPLES_PER_SENSE = 1
MAX_SYNONYMS_PER_SENSE = 4

RANK_BASE = 1000

# OEWN usa una letra; el resto del repo --PalabraDelDia.POS_EXCLUIDOS, la UI, el pack de
# kaikki-- usa el vocabulario largo. Traducir aca y no despues es lo que evita que los dos packs
# de ingles tengan vocabularios distintos de `pos`, que es una deuda que el roadmap ya nombra.
#
# "s" es el adjetivo satelite de WordNet: es un adjetivo y no merece una categoria propia en un
# reloj. "x" y "u" son residuales y quedan sin pos, que el formato permite.
POS = {
    "n": "noun",
    "v": "verb",
    "a": "adj",
    "s": "adj",
    "r": "adv",
    "c": "conj",
    "p": "prep",
    "x": None,
    "u": None,
}


def _strip(tag):
    """El tag sin el namespace, si lo trae."""
    return tag.rsplit("}", 1)[-1]


def _open(path):
    return gzip.open(path, "rb") if path.endswith(".gz") else open(path, "rb")


def _synsets(path):
    """Pasada 1: synset_id -> (definicion, ejemplos, miembros)."""
    out = {}
    with _open(path) as handle:
        for event, elem in ElementTree.iterparse(handle, events=("end",)):
            if _strip(elem.tag) != "Synset":
                continue
            definition = ""
            examples = []
            for child in elem:
                name = _strip(child.tag)
                if name == "Definition" and not definition:
                    definition = (child.text or "").strip()
                elif name == "Example" and len(examples) < MAX_EXAMPLES_PER_SENSE:
                    text = (child.text or "").strip()
                    if text:
                        examples.append(text)
            members = (elem.get("members") or "").split()
            out[elem.get("id")] = (definition, examples, members)
            elem.clear()
    return out


def _lemma_de_id(member_id, por_id):
    return por_id.get(member_id)


def records(path, lang="en-core"):
    """Itera el WN-LMF y entrega Records. `lang` se acepta y se ignora: OEWN es solo ingles."""
    synsets = _synsets(path)

    # Los `members` de un synset son ids de LexicalEntry, no palabras. Hace falta el mapa
    # id -> lema para convertirlos en sinonimos legibles, y se arma en la misma pasada 1.
    #
    # Se cuenta ademas cuantas entradas comparten (lema, pos): OEWN tiene homografos que no
    # distingue nada mas que el id --"pate" aparece dos veces como sustantivo-- y sin
    # `sense_key` los dos colapsan al mismo `entry.uid` y el build ABORTA. Que aborte es lo
    # correcto (D-058): fundirlos silenciosamente perderia una acepcion.
    por_id = {}
    repetidos = {}
    with _open(path) as handle:
        for event, elem in ElementTree.iterparse(handle, events=("end",)):
            if _strip(elem.tag) != "LexicalEntry":
                continue
            lemma = next((c for c in elem if _strip(c.tag) == "Lemma"), None)
            if lemma is not None:
                por_id[elem.get("id")] = lemma.get("writtenForm")
                # La clave va con el pos YA TRADUCIDO, que es el que ve stable_uid(). Con el
                # crudo, "a" (adjetivo) y "s" (adjetivo satelite) parecen distintos y los dos
                # mapean a "adj": "green" colisionaba por eso.
                clave = (lemma.get("writtenForm"), POS.get(lemma.get("partOfSpeech")))
                repetidos[clave] = repetidos.get(clave, 0) + 1
            elem.clear()

    with _open(path) as handle:
        for event, elem in ElementTree.iterparse(handle, events=("end",)):
            if _strip(elem.tag) != "LexicalEntry":
                continue
            lemma = next((c for c in elem if _strip(c.tag) == "Lemma"), None)
            if lemma is None:
                elem.clear()
                continue
            headword = lemma.get("writtenForm")
            entry_id = elem.get("id")
            forms = tuple(
                c.get("writtenForm")
                for c in elem
                if _strip(c.tag) == "Form" and c.get("writtenForm") != headword
            )
            senses = []
            for child in elem:
                if _strip(child.tag) != "Sense":
                    continue
                definition, examples, members = synsets.get(child.get("synset"), ("", [], []))
                if not definition:
                    continue
                synonyms = []
                for member in members:
                    if member == entry_id:
                        continue
                    word = _lemma_de_id(member, por_id)
                    if word and word != headword:
                        synonyms.append(word)
                senses.append({
                    "gloss": definition,
                    "examples": list(examples),
                    "synonyms": synonyms[:MAX_SYNONYMS_PER_SENSE],
                })
            if headword and senses:
                # El sense_key solo cuando hace falta: ponerlo siempre volveria el uid
                # inestable de gratis, igual que en la fuente de kaikki.
                clave = (headword, POS.get(lemma.get("partOfSpeech")))
                yield Record(
                    headword=headword,
                    senses=senses,
                    part_of_speech=POS.get(lemma.get("partOfSpeech")),
                    rank=_rank(senses, forms),
                    forms=forms,
                    translations=(),
                    sense_key=entry_id if repetidos.get(clave, 0) > 1 else None,
                )
            elem.clear()


def _rank(senses, forms):
    """Proxy de rank del spike, y **no se hereda el de kaikki**.

    `PERFILES["en"]` pesa `translations` y `etymology_texts`, que OEWN no tiene: heredarlo daria
    un rank plano y **silencioso**. Aca lo unico disponible es cuanta estructura tiene la
    palabra. Es peor proxy que el de kaikki y esta declarado como tal: si el spike prospera, lo
    que hay que usar es el orden de acepcion de WordNet, que viene ordenado por frecuencia
    etiquetada en SemCor -- una señal de frecuencia de verdad, que es justo lo que D-067 dice
    que al pack de kaikki le falta.
    """
    score = 12 * len(senses) + 3 * sum(len(s["examples"]) for s in senses) + min(len(forms), 8)
    return max(0, RANK_BASE - score)
