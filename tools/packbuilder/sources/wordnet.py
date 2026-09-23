"""A thesaurus from WordNet: synonyms and antonyms Wiktionary does not have.

**Why another source is needed.** The pack's synonyms come from the wiki, which writes them by
hand and therefore unevenly: **18.4 % of the Spanish entries and 15.4 % of the English ones**.
WordNet is built the other way round -- it groups by MEANING, so each *synset* **is** a set of
synonyms, and the coverage does not depend on somebody having remembered to write them.

**Two formats, two languages, the same idea:**

    English   Open English WordNet 2024, WN-LMF format (XML).  CC BY 4.0
              120,630 synsets, 161,646 lemma+pos pairs, 7,996 antonymy relations
    Spanish   Multilingual Central Repository via OMW, .tab format.  CC BY 3.0
              78,417 synsets with Spanish lemmas, 90,899 lemmas

⚠️ **The attribution rule is the usual one and here it bites hard**: a synset is *one* sense. If
our entry has several, or if the lemma is in several synsets of its category, **it is unknown which
one** those synonyms belong to, and hanging them off the first sense is D-117's mistake. They only
get in when there is **one sense on our side and one synset on theirs**.

**What it yields, measured against the real packs:**

    English   21,143 entries gain synonyms  ·  2,486 gain antonyms
    Spanish    5,504 entries gain synonyms

⚠️ **And Spanish brings noise English does not.** The MCR was built automatically and puts
inflections inside the synset --"coreano" with "coreana, coreanos"-- and the odd badly mapped
synset ("uno" with "dos"). The inflections are filtered against the pack's own `form` table, which
is a datum we already have; the badly mapped synset has no structural filter and gets through.
Measured: of 5,737 candidate entries, 233 lose some candidate to an inflection or a spelling.

**Antonymy comes only from English, and that is deliberate.** In WordNet it is a **lexical**
relation, between senses and not between synsets, so **it cannot be transferred to another
language** through the shared synset: the MCR gives lemmas per synset and that is not enough to
know which Spanish sense is the opposite of which. Inventing it would be worse than not having it,
because a badly attributed antonym reads as the opposite of something else (D-126).
"""

import collections
import gzip
import os
import re
import sys
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import normalize  # noqa: E402

# WordNet's categories mapped to the pack's `pos` vocabulary. "s" is "satellite adjective", which
# in WordNet is a shade of adjective and on a watch is not a useful distinction.
POS = {"n": "noun", "v": "verb", "a": "adj", "s": "adj", "r": "adv"}

# The same cap as the rest of the pipeline: one watch line.
MAX_POR_ACEPCION = 4

# A term has to START with a letter and carry no digits or odd symbols. It removes the "1"s and
# "2"s the MCR puts in the numerals' synset.
_TERMINO = re.compile(r"^[^\W\d_][\w\s'’.-]*$", re.UNICODE)


# When two terms are **the same word with a different ending** and not two different words.
#
# The MCR puts `decolorarse` with `decolorar`, `organismos` with `organismo`, `basicamente` with
# `básicamente`. That is not information: it is the word again, and on a watch it spends the only
# line.
#
# The rule knows no Spanish: **one is a prefix of the other and only a short ending changes**. The
# stem floor avoids killing legitimate abbreviations ("Oct" for "October"), and the difference
# ceiling avoids killing real relatives ("ente"/"entidad", which differ by four).
#
# Measured over the Spanish thesaurus: it removes 6,318 of 99,292 candidates (6.4 %).
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
    """A thesaurus from the Open English WordNet (compressed WN-LMF).

    It returns `(lemma, pos) -> {"synonyms": [...], "antonyms": [...]}`, **only for the lemmas that
    are in ONE synset of their category**: with several it is unknown which sense they belong to.

    The antonyms do not carry that restriction because in WordNet **antonymy is a relation between
    concrete senses**, not between synsets: the attribution comes already given.
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
    """A thesaurus from the MCR via OMW (three-column `.tab` format).

    `synset<TAB>spa:lemma<TAB>term`. Synonyms only: see the module docstring on why antonymy is not
    transferred.
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
    """The synset's other lemmas, cleaned, deduplicated and capped."""
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
