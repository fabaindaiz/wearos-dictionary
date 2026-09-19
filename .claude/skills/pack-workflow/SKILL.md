---
name: pack-workflow
description: Construir, validar y medir un pack de diccionario real. Usar cuando se pida "construí el pack", "generá el diccionario de español", "agregá una fuente", "cuánto pesa el pack" o se trabaje con datos de Wiktionary o del Wikcionario.
allowed-tools: Bash, Read, Write, Edit
---

# Building a pack

> The `description` above stays in Spanish: those are the phrases **the user says**.

## Before starting: which source

The Wikcionario and the English Wiktionary are **different datasets**, and it is the easy mistake:

| What you want | Source | Size |
|---|---|---|
| Spanish definitions of Spanish words | eswiktionary, Español section | 1,036,458 senses |
| English definitions of English words | enwiktionary, English section | 1,787,236 senses |
| Spanish words with English glosses (bilingual) | enwiktionary, Spanish section | 875,726 senses |

Spanish definitions of English words **is not a source that exists** in usable quality: the
Wikcionario covers English with 35,021 senses.

Use kaikki.org's per-language processed pages. The raw-data format is deprecated.

## The flow

Spanish is already built. **Do not write a new source to redo it**:

```bash
# 0. The dump (1.42 GB; the 2026-09-15 one gave 1,036,458 senses)
curl -o es.jsonl "https://kaikki.org/eswiktionary/Espa%C3%B1ol/kaikki.org-dictionary-Espa%C3%B1ol.jsonl"

# 1. Build. --sample N makes a pilot with 1 in every N headwords, with no positional bias:
#    look at it before spending the full build.
python3 tools/packbuilder/build_pack.py es es.jsonl es-def-wikc.db --sample 20  # pilot, ~30 s
python3 tools/packbuilder/build_pack.py es es.jsonl es-def-wikc.db             # full, 63.6 s measured

# 2. ALWAYS validate. A half-built pack opens without error.
python3 tools/packbuilder/verify_pack.py es-def-wikc.db

# 3. Measure and record
ls -lh es-def-wikc.db

# 4. Put it on the watch. Do NOT use `adb push` by hand: it is not atomic (D-082).
python3 tools/devpack.py install es-def-wikc.db
```

For **another** language or pack kind: the source goes in `tools/packbuilder/sources/` and yields
`Record`. Always streaming. Look at `kaikki.py` before writing one — the pruning is already solved
there, with the measurements that decided it.

## Pruning is where the size is decided

It is not an implementation detail: it is the work. Keep `word`, `pos`, glosses, forms and
translations. Discard etymologies, pronunciations, categories, templates and citations.

**Record the size in the changelog along with the pruning that produced it.** Spanish is already
measured and lives in `docs/formato-pack.md` §Presupuestos: **114,619 entries, 68.2 MB** — 36 %
over D-028's soft budget, which was an assumption until 2026-09-17.

**46.3 % of the pack is the `form` table**, almost all verb conjugations. If you come to shrink a
pack, that is the number you are fighting, and it does not get trimmed: it is what makes
"corriendo" find "correr".

## What to look at in `verify_pack.py`'s output

- **`[normalizacion]`** — that `entry.norm == norm(headword)` on every row. If it fails, the pack
  was built with another version of `normalize.py`.
- **`[planes de consulta]`** — that the prefix uses `COVERING INDEX`. It is the design's central
  claim and the only thing holding it up.
- **`[tamanos]`** — where the pack goes. With definitions, `fts_def_data` is going to be large;
  that number is the one that decides whether `detail=none` is worth looking at (an open
  decision).

## After `verify_pack.py`: open the pack and read it

A green `verify_pack.py` says the pack meets its invariants. **It does not say the content is
good.** A pack can pass every check with empty glosses, with the source badly parsed, or with the
accents eaten, because none of that violates an invariant — and it is obvious to the first human
who looks.

Before calling a pack good, **look at it**:

```sh
sqlite3 <pack.db> "SELECT headword, pos, norm FROM entry ORDER BY random() LIMIT 15;"
sqlite3 <pack.db> "SELECT headword, length(payload) FROM entry ORDER BY length(payload) LIMIT 5;"
sqlite3 <pack.db> "SELECT headword, length(payload) FROM entry ORDER BY length(payload) DESC LIMIT 5;"
```

What you are looking for, which no invariant catches:

- **The 15 random ones**: are they real words? Does the `pos` make sense? Do the accents survive?
- **The shortest ones**: a 3-byte gloss is an empty entry that still counts as an entry.
- **The longest ones**: a 40 kB gloss is usually source markup the pruning did not remove.
- **Decompress a real one** and read it whole. The codec can return corrupt text with no error:
  that is why `payload_dict_sha256` exists (D-008), and why looking is still worth it.

⚠️ `meta.payload_dict` is stored **in hex**, not in binary. If you hand the string to
`payload.decompress()` it does not blow up: it returns text that *looks* corrupt and sends you
hunting a codec bug that does not exist. **This has already cost a session an hour** even with the
warning written here — read it before writing the reader, not after. The correct path, which is
the one `verify_pack.py` uses:

```python
import payload as codec                              # from tools/packbuilder/
dic = bytes.fromhex(db.execute("SELECT value FROM meta WHERE key='payload_dict'").fetchone()[0])
print(codec.decompress(blob, dic))
```

**Tell the two silences apart.** "Empty because the source had nothing" and "empty because the
pruning ate it" are the same empty cell and two completely different bugs. If the pack has empty
entries, say which of the two it is, with the number.

## License, and it is not optional

The content is CC BY-SA. Every pack declares `license` and `attribution` in `meta`, and **the app
has to show them**. It is not bureaucracy: it is the condition for using the data (D-031).
