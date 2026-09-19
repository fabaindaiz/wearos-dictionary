---
name: troubleshoot-diccionario
description: Diagnostica fallas del diccionario a partir del síntoma. Usar cuando se reporte "falta una palabra", "no encuentra X", "no aparece en la búsqueda", "salen resultados repetidos", "el pack no abre", "se ve texto corrupto o con símbolos raros", o cualquier resultado incorrecto de búsqueda.
allowed-tools: Bash, Read, Grep
---

# Diagnosing the dictionary

> The `description` above stays in Spanish: those are the phrases **the user says**, and the
> symptoms they are matched against.

**This repo's failure knowledge is not in the git history.** It is in `docs/contratos-cruzados.md`
and in the headers of the mirrored files. Start there, not with `git log`.

Almost every failure in this project shares one symptom —*a word is missing*— and none of them
produces an exception. Diagnose by symptom.

## "A word is missing" / "it cannot find X"

The most common and the most dangerous. In order of likelihood:

0. **Is it a proper noun?** Since D-116 the packs **carry no surnames, place names or given
   names**, except the ones with lexical life. *Ivanivka*, *Troya*, *Etchechury* and *Hopewell*
   **are not there, and that is not a bug**: they are 22.1 % of the entries in Spanish and 17.1 %
   in English, and 90.4 % of what was removed defined nothing. Check it in a second, before
   touching anything:
   ```bash
   sqlite3 <pack.db> "SELECT value FROM meta WHERE key='proper_nouns'"   # lexical-only
   ```
   If the headword is a proper noun and the pack says `lexical-only`, **the answer is "that is how
   it was designed"**. The ones that stayed are those with `translations + descendants +
   derived >= 5` in the dump: the months, the countries, the languages — *January*, *Paris*,
   *España*, *Chile*. To measure how much that changes, rebuild with `--con-nombres`.

1. **The two implementations of `norm()` diverged.** Check it directly:
   ```bash
   cd tools/packbuilder && python3 -c "import normalize; print(repr(normalize.norm('LA PALABRA')))"
   ```
   and compare against what the pack stored:
   ```bash
   sqlite3 <pack.db> "SELECT headword, norm, fuzzy FROM entry WHERE headword LIKE 'LA PALABRA%'"
   ```
   If they differ, somebody touched only one language. See `contratos-cruzados.md` §1.

2. **The pack's `norm_version` ≠ the app's `NORM_VERSION`.** The pack is indexed with different
   rules. `SELECT value FROM meta WHERE key='norm_version'`.

3. **The word has a character outside the pinned repertoire.** Code points assigned after Unicode
   13 are treated as separators, so "abXcd" gets indexed as two words. See
   `contratos-cruzados.md` §2.

4. **The word is not in the source.** Check that before assuming a bug:
   `SELECT COUNT(*) FROM entry WHERE norm = '<the normalized key>'`.

## "Duplicate results show up"

Almost certainly the reverse search without deduplicating. The prefix range matches several
`trans` keys of the same entry ("to", "to run", "to pass") and without `DISTINCT` the entry comes
out once per key. It is documented in `docs/formato-pack.md`, query #3.

## "Corrupt text" / strange symbols in an entry

**The compression dictionary does not match.** deflate does not detect it: it decompresses without
throwing anything and returns garbage. Measured: "moverse rapidamente" → " nadrse rapidamente".

```bash
python3 tools/packbuilder/verify_pack.py <pack.db>   # checks payload_dict_sha256
```

If the hash does not match, the pack is badly built, not badly read.

## "The pack will not open"

In order: incompatible `schema_version` → incompatible `norm_version` → incompatible
`payload_codec` (`PackFile.open` compares with `!=`, D-119) → a truncated download (verify the
file's sha256) → FTS5 missing from the SQLite being used (if it is not the bundled one, there is
no FTS5).

## "The search got slow"

```bash
sqlite3 <pack.db> "EXPLAIN QUERY PLAN SELECT id, headword, pos FROM entry WHERE norm >= 'cor' AND norm < 'cos' ORDER BY norm, rank DESC LIMIT 30"
```

It has to say `COVERING INDEX idx_entry_norm`. If it says `SCAN entry`, the index was lost or the
query stopped fitting it.

## "The history opens the wrong word"

Not a broken contract: `entry.id` is the rowid and **does not survive rebuilding a pack** (D-055).
Since D-123 the app validates the stored id against the headword and fixes it through
`idx_entry_norm`, but a build older than that, or a tile deep link with no headword, still lands
on whatever now sits at that id.

## Before assuming it is a bug

**Ask first whether it is missing by decision or by bug.** Since D-116 there are words absent on
purpose, and since D-121 there is one entry fewer because its definition was a wiki maintenance
tag. Neither is a broken contract.

After ruling that out: this repo has **four known, documented failure modes**, and three of them
produce no error. Read `docs/contratos-cruzados.md` in full before writing new code to fix
something: the missing mechanism is probably already described there.
