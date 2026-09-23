"""Wikidata lexemes: Spanish's **second base pack**, and the only CC0 one.

It is not an improvement on the Wiktionary pack: it is **another dictionary**, from another
community, installed alongside and queried together with it (D-136). The gain is the union of
lemmas.

**Why this and not a merge.** Fusing it into pack 1 would force deciding which definition wins
when both have the word, and no measurement resolves that. As a separate pack there is nothing to
decide: both are there, and whichever has the word answers it.

**What it contributes, measured** over the 2026-09-20 dump (450 MB compressed):

    Spanish lexemes                         66,935
    of those, with a SPANISH gloss          15,814   (23.6 %)
    of those, NOT in es-def-wikc             5,283   (33.4 %)

And what it contributes is **complementary, not redundant**: regional demonyms --"iquiteño",
"huantino", "ucayalino", "abiyanés"-- and set phrases --"a su vez", "entre tanto", "así como
así"-- precisely what a wiki edited mostly from Spain covers worst.

⚠️ **CC0 licence**, which is the biggest practical difference from the other sources: it adds no
attribution obligation to anybody. It is declared in the manifest all the same (D-138), because
declaring where a datum comes from is useful even when it is not compulsory.

Source: https://dumps.wikimedia.org/wikidatawiki/entities/latest-lexemes.json.bz2
"""

import bz2
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from build import Record  # noqa: E402
from sources import kaikki  # noqa: E402

# The Wikidata item representing the Spanish language.
IDIOMA_ES = "Q1321"

# The lexical categories, **identified by looking at each one's lemmas in the dump** and not from
# memory: `Q1084` comes out with berilio/vino/viernes/maiz, `Q147276` with Miguel/Portugal/Turquia.
#
# Set phrases are mapped to their head ("año nuevo" -> noun, "hacer coro" -> verb): on a watch the
# label says what class of word it is, and "locución nominal" neither fits nor helps.
#
# ⚠️ `Q147276` maps to **"name"** on purpose: that is the vocabulary the proper-noun pruning uses
# (D-116, D-134), so all three policies work the same in this pack with nothing to touch.
CATEGORIAS = {
    "Q1084": "noun",          # sustantivo
    "Q34698": "adj",          # adjetivo
    "Q24905": "verb",         # verbo
    "Q29888377": "noun",      # locucion nominal: "año nuevo", "reloj de pulsera"
    "Q380057": "adv",         # adverbio
    "Q10976085": "verb",      # locucion verbal: "hacer coro", "rezar a coros"
    "Q5978303": "adv",        # locucion adverbial: "por el contrario", "entre tanto"
    "Q147276": "name",        # nombre propio -- lo poda la politica, igual que en kaikki
    "Q12734432": "adj",       # locucion adjetiva: "de buenas", "de pocas palabras"
    "Q102047": "suffix",      # sufijo: "-miento", "-al"
    "Q134830": "prefix",      # prefijo: "a-", "anti-"
    "Q83034": "intj",         # interjeccion
    "Q187931": "phrase",      # refran o formula: "albarda sobre aparejo"
    "Q102786": "abbrev",      # abreviatura: "n.º", "JJ. OO."
    "Q1298743": "conj",       # conjuncion
    "Q4833830": "prep",       # preposicion
    "Q36224": "pron",         # pronombre
    "Q103184": "num",         # numeral
    "Q161873": "det",         # determinante
}

# The rank ceiling, same as in kaikki: "lower is more common", so it is computed by subtracting.
RANK_BASE = 1000

# ⚠️ **The rank proxy is ANOTHER one and cannot be compared with kaikki's.** Wikidata carries
# neither etymology nor translations, which are two of the five terms in `sources/kaikki`'s
# profile. Here there are only senses and forms. D-136 already puts in writing that `score` is not
# comparable across packs from different sources; this is the concrete reason.
PESO_ACEPCION = 3
PESO_FORMA = 1
TOPE_DE_FORMAS = 80

# The same cap as the rest of the pipeline: it is the width of a watch line, not a source datum.
MAX_ACEPCIONES = 8


def _valor(mapa, idioma="es"):
    entrada = (mapa or {}).get(idioma)
    return (entrada or {}).get("value", "").strip()


def _lexemas(path):
    """Iterates the dump. It is a giant JSON array served one entity per line."""
    abrir = bz2.open if path.endswith(".bz2") else open
    with abrir(path, "rt", encoding="utf-8") as handle:
        for line in handle:
            line = line.strip().rstrip(",")
            if not line or line in ("[", "]"):
                continue
            try:
                yield json.loads(line)
            except ValueError:
                # A broken line cannot bring down a 450 MB build. No logic error is being
                # silenced: a line the dump served badly is skipped.
                continue


def _contar_homografos(path, politica):
    """How many times each `(lemma, pos)` appears. The first of the two passes. See `records`.

    It costs ~2 minutes over the compressed dump and keeps an entry's logical identity from
    depending on the order in which the file served it.
    """
    cuenta = {}
    for lexema in _lexemas(path):
        if lexema.get("language") != IDIOMA_ES:
            continue
        lema = _valor(lexema.get("lemmas"))
        if not lema:
            continue
        pos = CATEGORIAS.get(lexema.get("lexicalCategory"))
        if pos == "name" and politica != "included":
            continue
        if not any(_valor(s.get("glosses")) for s in (lexema.get("senses") or [])):
            continue
        clave = (lema, pos)
        cuenta[clave] = cuenta.get(clave, 0) + 1
    return cuenta


def records(path, lang="es", politica=kaikki.POLITICA_POR_DEFECTO):
    """Yields Records from the lexemes dump. See the module docstring.

    `politica` exists so the signature matches `sources/kaikki`'s --`build_pack` calls both the
    same way-- and here it only decides whether the `Q147276` get in. There is no lexical signal to
    measure, so "lexical-only" and "definitions-only" behave like excluding them: the only real
    difference is `included`, **which is the default** (D-141).

    ⚠️ **What IS discarded and is not a pruning: the lexemes with NO Spanish gloss** (76.4 % of the
    dump). It is not a content decision -- it is that the source **has no definition to give**. A
    lexeme like that carries a category and forms and nothing else; emitting it would be a lemma
    that is empty when opened, and `build.add()` rejects it anyway. If Wikidata ever completes
    them, they come in on their own.
    """
    repetidos = _contar_homografos(path, politica)
    for lexema in _lexemas(path):
        if lexema.get("language") != IDIOMA_ES:
            continue
        lema = _valor(lexema.get("lemmas"))
        if not lema:
            continue
        pos = CATEGORIAS.get(lexema.get("lexicalCategory"))
        if pos == "name" and politica != "included":
            continue
        glosas = [
            _valor(s.get("glosses"))
            for s in (lexema.get("senses") or [])
        ]
        senses = [
            {"gloss": g, "examples": [], "translations": [], "synonyms": [], "antonyms": [],
             "related": []}
            for g in glosas if g
        ][:MAX_ACEPCIONES]
        # With no Spanish gloss it is not an entry of a Spanish dictionary: the lexeme exists all
        # the same --it has forms and a category-- but says nothing. They are 76.4 % of the dump.
        if not senses:
            continue
        formas = tuple(
            f for f in (_valor(x.get("representations")) for x in (lexema.get("forms") or []))
            if f and f != lema
        )
        score = PESO_ACEPCION * len(senses) + PESO_FORMA * min(len(formas), TOPE_DE_FORMAS)
        yield Record(
            headword=lema,
            senses=senses,
            part_of_speech=pos,
            rank=max(0, RANK_BASE - score),
            forms=formas,
            translations=(),
            # ⚠️ **Only when there is a homograph, and this cost an extra pass.**
            #
            # The lexeme's id (`L12345`) is a stable identity declared by the source, which is more
            # than kaikki has, and the temptation is to use it always. **It cannot be done**: `uid`
            # exists to join the SAME entry across different packs (D-055), and if this pack puts
            # `L12345` into the hash and the Wiktionary one puts nothing, the two packs' "vino"
            # entries stop joining. The convention has to be the same in all of them.
            #
            # And since the dump does NOT come grouped by lemma, knowing whether there is a
            # homograph forces counting first: giving it only to the second would make the first's
            # uid depend on the file's order. `verify_pack.py` caught exactly that.
            sense_key=lexema.get("id") if repetidos.get((lema, pos), 0) > 1 else None,
        )
