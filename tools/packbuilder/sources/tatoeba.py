"""Spanish usage sentences, from the Tatoeba corpus.

**It is not a dictionary and defines nothing.** It is a corpus of standalone sentences,
contributed by people, and the only thing it brings is what the pack lacks: **real usage**. 70.4 %
of the Spanish pack is single-sense entries with no example at all, and D-132 left measured that
the Wiktionary dump has nothing more to give them.

**How much it yields, against the other source of examples.** Measured over the same pack:

    enwiktionary §Spanish (D-135)      307 entries
    Tatoeba                          7,019 entries      23 times more

The difference is not that Tatoeba has more text: it is that **an example does not need the two
sources to agree on how they number the senses**. It only needs to contain the word. That is why
D-132's attribution rule --which killed 94 % of enwiktionary's yield-- does not bite here: there
are no two lists of senses to reconcile.

⚠️ **The ambiguity is resolved on the pack's side, not here.** This module yields
`norm(word) -> sentence` knowing nothing about the dictionary; it is `build.py` that requires that
`norm` to lead to **one single** entry (a lemma or an inflected form) before gluing anything. That
is the condition that matters: "vino" is a lemma and also a form of "venir", and hanging a
sentence off the wrong one is incorrect content that looks correct. That filter is expensive --of
14,023 reachable entries it comes down to 7,019-- and it is paid in full.

⚠️ **Licence.** The sentences are **CC BY 2.0 FR**, not CC0. The `sentences_CC0` export exists and
was the first thing tried: it brings **37 Spanish sentences** out of 562,186, so it is no use.
There is compulsory attribution, just as in D-135, and that is why this too is an option and not a
default.

Source: https://downloads.tatoeba.org/exports/per_language/spa/spa_sentences.tsv.bz2
"""

import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import normalize  # noqa: E402

# Spanish's ISO 639-3 code in Tatoeba. Column 2 of the TSV.
LANG = "spa"

# The length window, in characters.
#
# The floor removes the sentences that illustrate nothing ("Si.", "Ya."). The ceiling is what fits
# in two lines at 234 dp without pushing the definition off screen -- the same number as
# `enwikt_examples.MAX_LARGO`, because the watch's line does not change with where the text came
# from.
MIN_LARGO = 15
MAX_LARGO = 80

# What counts as a word: letters, no digits. `\w` would bring in "25" and "covid19".
_PALABRA = re.compile(r"[^\W\d_]+", re.UNICODE)


def shortest_by_norm(path, lang=LANG):
    """A `norm(word) -> the shortest sentence containing it` map.

    **The shortest and not the first**: on a watch the line is the scarce resource and both are
    equally valid as an example. It also makes the result **independent of the file's order**,
    which is what makes two builds of the same dump give the same pack.

    The key is `norm()`, the same one that indexes the pack: anything else --lowercasing by hand,
    stripping accents with a table-- would be a second definition of word equality, which is
    exactly what this repo's central invariant exists to prevent.
    """
    comunes = _vistas_en_minuscula(path, lang)
    out = {}
    for texto in _frases(path, lang):
        for palabra in _PALABRA.findall(texto):
            clave = normalize.norm(palabra)
            # ⚠️ The filter that saved "nadal". See `_vistas_en_minuscula`.
            if not clave or clave not in comunes:
                continue
            previa = out.get(clave)
            # A tie on length: the alphabetically smaller wins, so the map does not depend on the
            # order in which the file served the sentences.
            if previa is None or (len(texto), texto) < (len(previa), previa):
                out[clave] = texto
    return out


def frequencies(path, lang=LANG):
    """A `norm(word) -> how many sentences use it` map. A core pack's signal of **usage**.

    ⚠️ **It exists because `rank` is no use for choosing vocabulary, and that is measured.** `rank`
    is richness of the dictionary's page, not frequency of speech. A 14,388-entry core chosen by
    `rank` takes **1,366,667 of the Spanish pack's 1,499,895 inflected forms** --91 % of the table,
    which is the largest in the file-- because the richest pages are the verbs and a Spanish verb
    has 33 forms. Chosen by usage, it does not.

    ⚠️ **It counts over ALL the language's sentences, not over [shortest_by_norm]'s window.** That
    length filter exists because an example has to fit in two watch lines; applying it here would
    bias the count towards medium-length sentences, which have no reason to use the most common
    words.

    ⚠️ **And it discards what the corpus never writes in lowercase**, by the same fact that saved
    "nadal" (see [_vistas_en_minuscula]) and with a measured reason of its own: **"Tom" appears in
    36,694 of the 442,135 Spanish sentences, 8.3 %**. Without the filter it would be one of the
    language's most frequent words and would enter the core ahead of "agua".

    It counts **sentences containing it** and not occurrences: a sentence that repeats a word does
    not make it more common, it makes it more emphatic.
    """
    # ⚠️ **Over ALL the sentences for this filter too, and not over the examples window.** "a word
    # written in lowercase at least once" is a fact about the CORPUS; restricting the evidence to
    # the sentences of 15 to 80 characters makes it less true, and with a small corpus --English
    # has 41,512 sentences against Spanish's 442,135-- it leaves genuinely common words out.
    # `shortest_by_norm` keeps the window because its job IS choosing examples.
    comunes = _mayormente_en_minuscula(path, lang)
    out = {}
    for texto in _todas_las_frases(path, lang):
        for clave in {normalize.norm(p) for p in _PALABRA.findall(texto)}:
            if not clave or clave not in comunes:
                continue
            out[clave] = out.get(clave, 0) + 1
    return out


# What proportion of its occurrences has to be lowercase for it to count as a common word.
#
# ⚠️ **Measured, and the separation is enormous**: `tom` 0.0 % (1 of 36,749), `maria` 0.3 %, `juan`
# and `john` 0.0 %, against `agua` 99.4 %, `water` 98.1 % and `enero` 89.2 %. Any number between 5
# and 85 separates them equally well; 50 % reads as "the corpus writes it lowercase more often than
# not".
UMBRAL_MINUSCULA = 0.5


def _mayormente_en_minuscula(path, lang):
    """The keys the corpus writes in lowercase **most of the time**.

    ⚠️ **"At least once" is not enough for choosing vocabulary, and that was measured.** It is the
    rule [_vistas_en_minuscula] uses and it serves its own purpose --choosing an example-- but in
    442,135 sentences almost any word appears in lowercase once, and **"tom" passed with 1 of
    36,749 occurrences**, landing in position 11 of Spanish's most frequent words.

    The cost of being wrong differs in each case, which is why there are two rules: a badly chosen
    example is an odd sentence on a card; a badly chosen vocabulary is a core pack full of proper
    nouns.

    ⚠️ **And it has a known consequence in English**: the days and months are always written
    capitalized --`monday` 0.0 %-- so they fall outside the core. In Spanish that does not happen,
    because `enero` goes lowercase 89.2 % of the time. The full pack has them either way.
    """
    minusculas, total = {}, {}
    for texto in _todas_las_frases(path, lang):
        for palabra in _PALABRA.findall(texto):
            clave = normalize.norm(palabra)
            if not clave:
                continue
            total[clave] = total.get(clave, 0) + 1
            if palabra[:1] == palabra[:1].lower():
                minusculas[clave] = minusculas.get(clave, 0) + 1
    return {c for c, n in total.items() if minusculas.get(c, 0) / n >= UMBRAL_MINUSCULA}


def _todas_las_frases(path, lang):
    """All the language's sentences, without the length window. One pass, loading nothing."""
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            partes = line.rstrip("\n").split("\t")
            if len(partes) < 3 or partes[1] != lang:
                continue
            texto = partes[2].strip()
            if texto:
                yield texto


def _frases(path, lang):
    """The requested language's sentences that fit on a screen. One pass, loading nothing."""
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            partes = line.rstrip("\n").split("\t")
            if len(partes) < 3 or partes[1] != lang:
                continue
            texto = partes[2].strip()
            if MIN_LARGO <= len(texto) <= MAX_LARGO:
                yield texto


def _vistas_en_minuscula(path, lang, frases=None):
    """The keys the corpus writes in lowercase **at least once**: the common words.

    ⚠️ **It exists because of an error no test could see and that turned up reading the built
    pack.** The dialectal word "nadal" (Christmas) had been given *"Alonso, Nadal y Pau Gasol,
    entre los mejor pagados del mundo."*: `norm()` lowercases, so the tennis player's surname
    collides with the common noun.

    And the builder's ambiguity filter **cannot see it**: D-116 prunes the pack's proper nouns, so
    there is no second entry for "nadal" to tie with. The key looks unambiguous precisely because
    its competitor was pruned.

    ⚠️ **The first attempt was discarding capitals mid-sentence, and it was NOT enough**: *"Nadal,
    mejor deportista español de la historia..."* starts with the surname and passed through the
    first-word exception -- every sentence starts capitalized, so that exception cannot be removed.
    What really separates them is not a position but **a fact about the corpus**: a word written in
    lowercase at least once is common; one always written capitalized, across 442,135 sentences, is
    a proper noun.

    It costs a second pass over the file --40 MB, uncompressed-- and no extra memory that would not
    have been needed anyway.
    """
    vistas = set()
    for texto in (frases or _frases)(path, lang):
        for palabra in _PALABRA.findall(texto):
            if palabra[:1] == palabra[:1].lower():
                clave = normalize.norm(palabra)
                if clave:
                    vistas.add(clave)
    return vistas
