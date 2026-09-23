"""Source: Open English WordNet, WN-LMF XML format.

**SPIKE** (D-120). It exists to produce a number --how much an English pack built from OEWN rather
than from Wiktionary weighs and covers-- and it **decides nothing**. The app does not use it, it is
not in the catalog, and the pack it produces does not go onto the watch.

Why XML and not the JSON, the Turtle or the WNDB the project also publishes: `iterparse` and `gzip`
are **stdlib** (D-045) and they stream. The JSON is a single `@graph` `json.load` would have to
hold whole in memory, and streaming it asks for `ijson`; the Turtle asks for `rdflib`. Both would
be a third-party dependency this repo does not have.

**Two passes, and it is not optional**: the `Synset` elements --where the gloss lives-- appear
AFTER all the `LexicalEntry` ones in document order. With a single pass every gloss comes out empty
and the pack is built with no error and nothing inside. The ~120,000 synsets fit in memory easily;
the 135,969 entries are emitted streaming.

**iterparse's trap**: without `elem.clear()` after each child, the whole tree accumulates and the
process dies on RAM over a file that "is small" (89 MB uncompressed).

What OEWN gives for free and kaikki's English dump cannot: **a synset's `members` IS the set of
synonyms**, per sense and structural. In kaikki, English brings 0 of 43,679 synonyms with a
`sense_index` (D-117). It is the finding that decides whether OEWN is worth it, which is why the
spike extracts them even though the production pack does not use them yet.
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

# OEWN uses a single letter; the rest of the repo --PalabraDelDia.POS_EXCLUIDOS, the UI, kaikki's
# pack-- uses the long vocabulary. Translating here and not later is what keeps the two English
# packs from having different `pos` vocabularies, which is a debt the roadmap already names.
#
# "s" is WordNet's satellite adjective: it is an adjective and does not deserve a category of its
# own on a watch. "x" and "u" are residual and are left with no pos, which the format allows.
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
    """The tag without its namespace, if it carries one."""
    return tag.rsplit("}", 1)[-1]


def _open(path):
    return gzip.open(path, "rb") if path.endswith(".gz") else open(path, "rb")


def _synsets(path):
    """Pasada 1: synset_id -> (definicion, ejemplos, miembros)."""
    out = {}
    with _open(path) as handle:
        for _event, elem in ElementTree.iterparse(handle, events=("end",)):
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

    # A synset's `members` are LexicalEntry ids, not words. The id -> lemma map is needed to turn
    # them into readable synonyms, and it is built in the same pass 1.
    #
    # It also counts how many entries share a (lemma, pos): OEWN has homographs nothing but the id
    # distinguishes --"pate" appears twice as a noun-- and without a `sense_key` the two collapse
    # onto the same `entry.uid` and the build ABORTS. Aborting is the right thing (D-058): fusing
    # them silently would lose a sense.
    por_id = {}
    repetidos = {}
    with _open(path) as handle:
        for _event, elem in ElementTree.iterparse(handle, events=("end",)):
            if _strip(elem.tag) != "LexicalEntry":
                continue
            lemma = next((c for c in elem if _strip(c.tag) == "Lemma"), None)
            if lemma is not None:
                por_id[elem.get("id")] = lemma.get("writtenForm")
                # The key goes with the ALREADY TRANSLATED pos, which is the one stable_uid()
                # sees. With the raw one, "a" (adjective) and "s" (satellite adjective) look
                # different and both map to "adj": "green" collided because of that.
                clave = (lemma.get("writtenForm"), POS.get(lemma.get("partOfSpeech")))
                repetidos[clave] = repetidos.get(clave, 0) + 1
            elem.clear()

    with _open(path) as handle:
        for _event, elem in ElementTree.iterparse(handle, events=("end",)):
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
                # The sense_key only when it is needed: setting it always would make the uid
                # unstable for free, same as in kaikki's source.
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
    """The spike's rank proxy, and **kaikki's is not inherited**.

    `PERFILES["en"]` weighs `translations` and `etymology_texts`, which OEWN does not have:
    inheriting it would give a flat and **silent** rank. Here the only thing available is how much
    structure the word has. It is a worse proxy than kaikki's and it is declared as such: if the
    spike prospers, what should be used is WordNet's sense order, which comes ordered by
    SemCor-tagged frequency -- a real frequency signal, which is exactly what D-067 says kaikki's
    pack lacks.
    """
    score = 12 * len(senses) + 3 * sum(len(s["examples"]) for s in senses) + min(len(forms), 8)
    return max(0, RANK_BASE - score)
