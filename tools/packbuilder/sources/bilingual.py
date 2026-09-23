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

    ⚠️ **`for_search` separates the two channels, and the difference was seen while writing the
    card.** To SEARCH you have to index `to run` **and** `run`, because nobody types the
    preposition; to DISPLAY, both together are noise -- the list read *"to run, run, to jog, jog"*
    on a 234 dp screen. The reading channel keeps the form the source wrote, which is also the
    dictionary form.
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


# How many of the other language's equivalents a reverse entry shows.
#
# ⚠️ **8, the same cap as the per-sense translations, and for the same reason**: at 234 dp a longer
# list stops being read and starts pushing. The equivalents are ordered by `rank`, so the 8 that
# remain are the 8 most common and not the dump's first 8.
MAX_EQUIVALENTES = 8


def records(path, lang="es", politica=None, frequencies=None, lang_dst=None,
            flexiones=None):
    """The bilingual records: what `kaikki` yields, with `translations` filled in.

    ⚠️ **The entry side is not re-implemented and that is the point.** Pruning, homograph grouping,
    the proper-noun policy and the inbound forms are decisions with their own measurements in
    `kaikki.py`; a second copy of them here would be a second place to fix every bug.

    ⚠️ **With `lang_dst` the pack becomes BIDIRECTIONAL BY CONSTRUCTION**, which is a different
    thing from what was there: until here the English words lived only in `trans`, an index from
    `norm` to a Spanish entry. That made `dog` **find** `perro`, but `dog` was not a lemma: there
    was no card to open, no language tag, and no way for the app to know the pack knew it. Now each
    word of the other language is a `Record` with its `lang`, its equivalents and its `rank`.

    ⚠️ **They are derived from the same dump already read, not from a new source.** It is D-175's
    argument for the core: deriving makes the claim true **by construction**. If `dog` leads to
    `perro` it is because `perro`'s gloss said `dog`; not because two sources agreed and nobody
    checked. It costs zero build hours and zero new dumps.

    ⚠️ **And `trans` stops being filled**: it would be a second copy of the same index, because
    searching `dog` already works through `entry.norm`. Measured over the real pack: 474,849 rows,
    **13.3 MiB**.
    """
    from . import kaikki

    if lang_dst is None:
        yield from _una_direccion(path, lang, politica, frequencies)
        return
    yield from _dos_direcciones(path, lang, politica, frequencies, lang_dst, flexiones)


def bidireccional(registros, lang, lang_dst, flexiones=None):
    """The pack's own records, and behind them the other language's entries derived from its keys.

    It lives here and not in each source because **the toy has to do exactly the same**: a toy pack
    that was not bidirectional would leave the new branch without the only fixture that runs on a
    device, and a channel with no fixture breaks with nothing to warn -- that already happened with
    the `W` tag.

    ⚠️ **The reverse map accumulates in memory and that breaks the streaming on purpose.** The rest
    of the builder never loads the whole dump --it is 1.1 GB-- but this is not the dump: it is the
    already pruned keys. Measured over the real pack, **164,249 English terms** with 474,849 pairs,
    which is ~100 MB in memory and **+20.4 MiB** of entries on disk against the 13.3 MiB saved by
    emptying `trans`. There is no way round it: a term of the other language does not know how many
    equivalents it has until the whole dump has been read.
    """
    inverso = {}
    for record in registros:
        for clave in record.translations:
            # `rank` goes in the tuple to sort by later: the most common equivalent first, which is
            # the same rule any list in this app is ordered by.
            inverso.setdefault(clave, []).append(
                (record.rank, record.headword, record.part_of_speech))
        # ⚠️ The search channel is EMPTIED here and not in the builder: `trans` exists for the
        # monolingual packs, where the other language's word is not a lemma. Here it is.
        record.translations = ()
        # ⚠️ **Explicit and not inherited from the pack.** `Record.lang = None` means "the primary
        # one", which suffices in a single-language pack; here reordering `meta.langs` would
        # relabel every one of the pack's own entries **in silence**. In a bidirectional pack each
        # entry says which is its own.
        record.lang = lang
        yield record

    flexiones = flexiones or {}
    for clave, equivalentes in inverso.items():
        equivalentes.sort()
        yield _entrada_inversa(clave, equivalentes, lang_dst, flexiones.get(clave, ()))


def _dos_direcciones(path, lang, politica, frequencies, lang_dst, flexiones=None):
    """The dump read once, and the two directions that come out of it. See [bidireccional]."""
    yield from bidireccional(
        _una_direccion(path, lang, politica, frequencies), lang, lang_dst, flexiones)


def _entrada_inversa(headword, equivalentes, lang_dst, forms=()):
    """A word of the other language, with its equivalents as its body.

    ⚠️ **It carries no senses, and that absence is honest.** A bilingual dictionary answers *"how
    is it said"*, not *"what does it mean"*: the English definitions belong to `en-def-wikt` and
    putting them here would be fusing two packs. The body goes in the `W` channel --translation of
    the WORD-- because that is exactly what it is: it cannot be attributed to a sense that does not
    exist (D-117).

    ⚠️ **The `pos` is inherited from the most common equivalent**, with 100 % coverage measured:
    the dump carries no `pos` for the English side, and the translation of a noun is a noun. It is
    an inference, not a datum, but it is the same one the reader would make.

    ⚠️ **And the `rank` is inherited too**, from the best equivalent: `dog` is as common as
    `perro`. Without this the reverse entries would all come in at rank 0 and bury the pack's own.

    ⚠️ **`forms` are the OTHER language's inflections, and without them the reverse direction
    collapses.** Measured: on turning the English words into entries and emptying `trans`, the
    English top 1,000's coverage fell from **98.4 % to 89.8 %**. `trans` was tokenized (D-014) and
    `--flexiones` put `got`, `been`, `were`, `could` in there, which thereby reached the Spanish
    lemma -- and the answers being lost were correct (`been -> ser, estar, tener`). Their place in
    the new model is the English entry's `form`: `got` is an inflection of `get`, and `get` is
    already a lemma. It comes out symmetric with the Spanish side, which is what the bidirectional
    pack asserts.
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
    # ⚠️ `frequencies` is chained here and it is not a detail: this is the pack where the ordering
    # defect was WORST. `orderFor` applies D-142's band only to `PREFIX`, so its `TRANSLATION` rung
    # orders by raw `rank` -- `house` returned `solar, alojar, albergar` and never `casa`. Without
    # this line, the one rung with no defence would still be broken.
    for record in kaikki.records(path, lang=lang, politica=politica,
                                 frequencies=frequencies):
        claves = []
        for sense in record.senses:
            propias = translation_keys(sense.get("gloss"), for_search=False)
            # ⚠️ **The READING channel, and until today these keys were computed and thrown away.**
            # `record.translations` feeds the `trans` table, which `PackBuilder` normalizes and
            # D-014 tokenizes: it serves for searching and not for reading. Measured over the real
            # pack, it was **206,727 rows in `trans` and ZERO in `T`/`W`** -- the catalog's pack
            # with the most translations was the only one that could not show them.
            #
            # ⚠️ **The attribution here is STRUCTURAL**, like D-124's nested synonyms: each term
            # comes out of THAT sense's gloss, so there is nothing to guess and the entry-level
            # channel stays empty by construction.
            sense["translations"] = propias
            # El canal de busqueda lleva ADEMAS las formas derivadas (`run` de `to run`).
            for clave in translation_keys(sense.get("gloss")):
                if clave not in claves:
                    claves.append(clave)
        record.translations = tuple(claves)
        yield record
