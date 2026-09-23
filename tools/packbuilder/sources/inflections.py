"""Inflections of the TARGET language, read from an already built pack.

## What problem it closes, measured

A bilingual pack's reverse direction is weak and **the reason is structural, not accidental**: the
Spanish side has the `form` table, so every inflection reaches its lemma; the English side has
only the derived keys, so an English inflection is found **only if some gloss writes it**.
Measured over the most used English words, coverage was **92.7 % / 85.9 % / 78.1 %** in the top
1,000 / 3,000 / 8,000, and with the inflections it rises to **99.4 % / 99.6 / 98.9 %**.

What is still missing after this **is not vocabulary but the tokenizer**: `didn`, `doesn`, `wasn`,
`shouldn` are halves of contractions `tatoeba.frequencies` splits on the apostrophe.

## Why the source is a PACK and not a dump

The English inflections are already built and pruned inside `en-def-wikt.db`. Going back to the
3.2 GB dump to recompute what we already have would be another hour of build and a second pruning
that can diverge from the first -- the same reasoning by which `build_core.py` **derives** rather
than rebuilds (D-175).

## The filter is NOT optional, and it was discovered by reading rows

⚠️ The English pack's `form` table **is dirty**: of its 985,992 rows, **38.7 % contains a space**
--`big fat hairy deals`, `ate breathed and slept`, `1 000 000 questions`-- and `no table tags`
(577) and `glossary` (575) are **wiktextract parser artifacts** sitting there as if they were
inflections. Spanish, by comparison, has `unas` as its most repeated form, 16 times.

Keeping what is one word, alphabetic and not an artifact **discards 35 % of the candidates and
does not move coverage by a tenth**. The filter is free.
"""

import sqlite3

# Parser artifacts that appear in `form` as if they were inflections. Repetition gives them away:
# a real inflection hardly ever repeats.
ARTEFACTOS = {"no table tags", "glossary"}


def _sirve(forma, lema):
    """An inflection is usable if it is ONE word, alphabetic, and not a parser artifact."""
    return (
        forma not in ARTEFACTOS
        and " " not in forma
        and forma != lema
        and any(c.isalpha() for c in forma)
        and not forma[0].isdigit()
    )


def por_lema(pack, claves=None):
    """`lemma -> [inflection, ...]`, read from `pack`.

    `claves` narrows it to the words by which the bilingual pack already reaches some entry. With
    `None` it brings the whole map, which is what the real build does: **the keys are not known
    until every record has been read**, and querying per record would be 124,000 queries. The
    English pack's full map is ~600,000 pairs after the filter and fits easily in a build machine's
    memory.
    """
    con = sqlite3.connect("file:%s?mode=ro" % pack, uri=True)
    try:
        # A single pass and a single JOIN: `form` has PRIMARY KEY (norm, entry_id), so filtering by
        # `entry_id` **uses no index** and would be a scan per lemma. That already made one build
        # never finish (see build_core.py).
        sql = "SELECT f.norm, e.norm FROM form f JOIN entry e ON e.id = f.entry_id"
        if claves is not None:
            con.execute("CREATE TEMP TABLE claves(norm TEXT PRIMARY KEY)")
            con.executemany("INSERT OR IGNORE INTO claves VALUES (?)", ((c,) for c in claves))
            sql += " WHERE e.norm IN (SELECT norm FROM claves)"
        salida = {}
        for forma, lema in con.execute(sql):
            if not _sirve(forma, lema):
                continue
            if claves is not None and forma in claves:
                continue
            salida.setdefault(lema, [])
            if forma not in salida[lema]:
                salida[lema].append(forma)
        return salida
    finally:
        con.close()
