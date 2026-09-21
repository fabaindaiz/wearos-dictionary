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


def translation_keys(gloss):
    """The English terms a reader could search to reach this entry. Empty if the gloss describes.

    The keys are returned raw: `PackBuilder` normalises them, the same way it normalises forms, so
    that `trans.norm` is computed by exactly the function that indexes everything else.
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
        if len(palabras) == 2 and palabras[0].lower() == "to":
            candidatos.append(palabras[1])
        for candidato in candidatos:
            if candidato and candidato not in salida:
                salida.append(candidato)
    return salida


def records(path, lang="es", politica=None):
    """The bilingual records: what `kaikki` yields, with `translations` filled in.

    ⚠️ **The entry side is not re-implemented and that is the point.** Pruning, homograph grouping,
    the proper-noun policy and the inbound forms are decisions with their own measurements in
    `kaikki.py`; a second copy of them here would be a second place to fix every bug.
    """
    from . import kaikki

    if politica is None:
        politica = kaikki.POLITICA_POR_DEFECTO
    for record in kaikki.records(path, lang=lang, politica=politica):
        claves = []
        for sense in record.senses:
            for clave in translation_keys(sense.get("gloss")):
                if clave not in claves:
                    claves.append(clave)
        record.translations = tuple(claves)
        yield record
