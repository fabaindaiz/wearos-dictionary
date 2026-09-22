# Pack format (`schema_version = 4`)

A pack is a **read-only** SQLite file holding a dictionary. The app opens one per active language
and never writes to it.

Being immutable and read-only is what allows the schema to be optimised purely for reading: no
migrations, no foreign keys to maintain, no journal. A pack with a different `schema_version` is
rejected on open and downloaded again.

Executable definition: [`tools/packbuilder/schema.sql`](../tools/packbuilder/schema.sql) and
[`indexes.sql`](../tools/packbuilder/indexes.sql).

> **The primary case is monolingual** (D-034): the first pack is Spanish definitions. The schema
> supports both kinds and every pack declares its own in `meta.kind`, but bilingual is the
> secondary case.
>
> Consequence still pending in the code: in a monolingual pack, `trans` stores "the words that
> appear in the gloss", which is exactly what `fts_def` already indexes better. That table should
> become optional. See the [roadmap](roadmap.md#reorientar-el-esquema-a-monolingüe).

## Anatomy of a dictionary: what a pack IS

Before the tables, the shape of the thing. A pack answers four questions and nothing else.

```
                         ┌─────────────────────────────────────────┐
  what you type  ──────► │ FORM          corriendo, corrí, corres  │
                         │   ↓ every inflected form points to      │
                         │ ENTRY         correr        (a lemma)   │
                         │   ├── headword   "correr"   ← as shown  │
                         │   ├── norm       "correr"   ← as searched
                         │   ├── pos        verb                   │
                         │   ├── rank       769        ← how common│
                         │   ├── uid                   ← logical id│
                         │   └── payload  (compressed) ────────┐   │
                         └─────────────────────────────────────│───┘
                                                               ▼
                         ┌─────────────────────────────────────────┐
                         │ P  verb                   part of speech│
                         │ S  Moverse rápidamente.   ── SENSE 1 ───│
                         │ E    Corrió hasta la esquina.   example │
                         │ Y    desplazarse                synonym │
                         │ A    detenerse                  antonym │
                         │ R    trotar                     related │
                         │ S  Gestionar algo.        ── SENSE 2 ───│
                         │ E    Corre con los gastos.      example │
                         └─────────────────────────────────────────┘
```

**Entry** = one lemma with one part of speech. *"fantasma"* the noun and *"fantasma"* the
adjective are **two entries**, not one with two senses — the source distinguishes them and so does
the pack.

**Sense** = one meaning of that entry. Everything inside a sense —example, synonym, antonym,
related word— belongs **to that sense and not to the entry**. This is the single rule that
generates most of the decisions in this repo: attributing a synonym to the wrong sense produces
*content that looks correct*, which is worse than a missing word because the reader has no way to
suspect it (D-117, D-132, D-135, D-137).

**Form** = an inflected form that must lead to its lemma. Typing *"corriendo"* has to find
*"correr"*. In Spanish this is most of the file: **12,98 forms per entry**, and `form` is 33 of
the pack's 68 MB.

**Two columns for one word, and that is deliberate.** `headword` is what is **shown** —`Aarón`,
`A°`, `Abanto y Ciérvana`, with their capitals and accents intact— and `norm` is what is
**searched**: lowercased and accent-folded. Nothing in the app ever lowercases a word for display;
the only `lowercase()` in the whole repo lives in `TextNormalizer`, which builds that key (D-004).
So the search is case- and accent-insensitive in both directions —typing `MÉXICO`, `mexico` or
`México` all find the entry— and what comes back on screen is always the original spelling.

**What a pack is NOT.** It has no etymology, no pronunciation, no syllabification and no images:
they do not get shown on a watch and they are most of the weight of the source dumps.

## The manifest: what a pack declares about itself

The `meta` table **is** the manifest. Someone handed a 68 MB `.db` has to be able to answer three
questions without asking anyone, and each one has a key:

| Question | Keys | Enforced by |
|---|---|---|
| **What is this?** | `pack_id`, `name`, `description`, `kind`, `langs`, `tier`, `entry_count` | `verify_pack.py` |
| **Where did it come from?** | `sources`, `source_url`, `data_version`, `built_at`, `proper_nouns` | `verify_pack.py` |
| **How may I use it?** | `sources` (a licence **per source**), `license`, `attribution` | `verify_pack.py`, and the app's attribution screen |

### `pack_id` is a code, not a name

```
<lang>-<kind>-<source>[-<variant>]*

es-def-wikc            Spanish, definitions, from the Wikcionario
es-def-wikc-tat        the same, plus Tatoeba sentences
es-def-wd              Spanish, definitions, from Wikidata Lexemes
en-def-wikt            English, definitions, from Wiktionary
```

`lang` is ISO 639-1, `kind` is `def` or `tr`, `source` is the code from
[`docs/fuentes.md`](fuentes.md), and variants come from the builder's flags.

⚠️ **It exists because of collisions, not tidiness.** Since D-136 two packs of the same language
from different sources are installed and searched **together**: they share language and kind, so
**the source code is the only thing separating them**. With a generic `pack_id` —`espanol`,
`dict`— one overwrites the other on install, and the watch's history and saved words end up
pointing at entries of a pack that is no longer there. Nothing throws: the surviving pack opens
and works. `verify_pack.py` rejects an id that does not match the grammar.

### `sources`: one line per source, with its own licence

```
definitions<TAB>Wikcionario (es.wiktionary.org)<TAB>https://…<TAB>CC BY-SA 4.0<TAB>https://…
sentences<TAB>Tatoeba<TAB>https://tatoeba.org/<TAB>CC BY 2.0 FR<TAB>https://…
```

Fields: `role`, `name`, `url`, `license`, `license_url`. Roles: `definitions`, `examples`,
`sentences`, `relations`, `translations`. **An unknown role is kept as "other"** rather than
dropped — losing a credit over an unrecognised word would be the very breach this prevents.

⚠️ **One licence per source, not one per pack** (D-138). The Spanish pack built with `--frases`
mixes definitions under CC BY-SA 4.0 with corpus sentences under CC BY 2.0 FR. A single name for
the whole pack either over-claims or under-credits, and attribution is the **condition of use** of
the data, not a courtesy (D-031). The app reads this list and shows every line.

**Delimited text and not JSON**, the same choice the payload made and for the same reason: it
parses with no dependency in either language, it can be read by eye while debugging a pack, and
`:dict-core` stays free of a JSON library it has no other use for.

## The `meta` table

⚠️ **`translations_to` declara una CAPACIDAD, no el tipo del pack** (D-183). Dice en qué idioma
están las traducciones del payload; `kind` sigue contestando en qué idioma están las
**definiciones**. El pack español es `monolingual` **y** traduce al inglés, y mientras la app
preguntó por `kind` la acción de traducir no apareció nunca sobre él.

El destino se nombra por **idioma y no por `pack_id`**: nombrar el pack mataba el enlace del
usuario que tiene instalado el núcleo y no el completo.


Everything the app needs to know before querying. It is read whole, once, on open.

| Key | What for |
|---|---|
| `schema_version` | Schema compatibility. Different → reject the pack |
| `norm_version` | Version of the normalization rules. Different → **reject**, see below |
| `pack_id`, `name` | The pack's identity. `name` is **short** — "Español", not "Español — definiciones" (D-125) |
| `description` | The long text, for the attribution screen. Optional: a pack older than D-125 does not carry it |
| `sources` | **The manifest of sources**, one per line with its own licence (D-138). See above. Optional in the reader so a pack older than D-138 still opens; required by `verify_pack.py` for a new one |
| `kind` | `bilingual` or `monolingual` |
| `langs` | The pack's languages, **as peers**: `es` or `es,en`. A bilingual one declares two and neither is the principal |
| `fuzzy_profiles` | One folding profile per language, **positional against `langs`** |
| `tier` | `full` or `core`. Declared, so a core can step aside without the app guessing from the name |
| `rank_signal_boundary` | Where `rank`'s frequency-signal band ends, when `rank_basis` is frequency. Declared so the app does not copy the builder's constant |
| `fuzzy_profile` | Phonetic folding profile: `es`, `en`, `de`, `generic` |
| `payload_codec` | `deflate-v2`. **Different → reject the pack** (`PackFile.open` compares with `!=`). See D-119 |
| `payload_dict` | Shared compression dictionary, in hex |
| `payload_dict_sha256` | Integrity of the above. **Not optional** |
| `uid_recipe` | Which recipe computed `entry.uid`. A different recipe ⟹ auxiliary packs point at the wrong thing |
| `entry_count`, `data_version`, `built_at` | Build metadata |
| `license`, `attribution`, `source_url` | Legal obligations of the source |
| `trans_dropped` | How many rows the per-translation-key cap trimmed |
| `proper_nouns` | Content policy: `lexical-only` (the real packs), `excluded` or `included`. **Mandatory**: without it nobody knows whether a pack is missing its proper nouns because that was decided or because the source was broken. D-116 |

**A different `norm_version` is not cosmetic.** The pack is indexed with one concrete set of
normalization rules; if the app computes different ones, the queries do not match and the pack
returns fewer results than it holds, with no error at all. It has to be rejected, not used anyway.

## Tables

### `entry`

```sql
CREATE TABLE entry (
    id       INTEGER PRIMARY KEY,   -- alias de rowid: lo comparte fts_def
    uid      INTEGER NOT NULL,      -- identidad estable entre rebuilds; join entre packs
    lang     TEXT NOT NULL,         -- idioma de ESTA entrada, no del pack (schema_version 4)
    headword TEXT NOT NULL,         -- forma de display, con acentos: "Ärztin"
    norm     TEXT NOT NULL,         -- clave de prefijo: "arztin"
    fuzzy    TEXT NOT NULL,         -- clave tolerante a errores, plegada por idioma
    pos      TEXT,                  -- desambigua lemas repetidos en la lista
    rank     INTEGER NOT NULL,      -- frecuencia; menor es más común
    payload  BLOB NOT NULL          -- cuerpo comprimido
);
```

**`id` and `uid` are two different identities and are not interchangeable** (D-055):

| | `entry.id` | `entry.uid` |
|---|---|---|
| What it is | **Physical** identity: the local rowid | **Logical** identity of the word |
| Who references it | `fts_def.rowid`, `form.entry_id`, `trans.entry_id` | The auxiliary packs |
| Survives rebuilding the pack | **No**: one new word in the middle shifts every following one | **Yes** |
| Why it is that way | Sequential is what makes it cheap: FTS5 stores rowid *deltas* | It is a hash of `(entry.lang, NFC(headword), pos, sense_key)` |

Measured over 200,000 synthetic entries: using the hash *as* `entry.id` costs **+35.2 %** in size
—`fts_def_data` goes from 10.39 to 28.35 MB— while a separate column costs **+2.3 %**.

`uid` **has no index in this pack** (D-056): the join happens when an entry is **opened**, when
the row has already been read whole to fetch the payload, not in the results list. The index over
`uid` lives in the auxiliary pack, which does search by it.

`uid` is computed over the **raw** headword, not over `norm`: that way it does not depend on
`NORM_VERSION` and bumping the normalization rules does not invalidate the auxiliary packs. It is
computed by **the builder alone** (D-057); the app reads it from the row and never recomputes it,
which is what keeps it from becoming a second cross-cutting contract like `norm()`/`fuzzy()`.

`norm` is compared with **BINARY collation over text already normalized at build time**. That is
what avoids needing ICU on the watch and makes the behaviour identical on every device.

### `form` and `trans`

```sql
CREATE TABLE form (
    norm TEXT NOT NULL, entry_id INTEGER NOT NULL,
    PRIMARY KEY (norm, entry_id)
) WITHOUT ROWID;
```

`form` holds the inflected forms (plurals, conjugations). `trans` is the target-language word in a
bilingual pack.

**`WITHOUT ROWID` with a composite PK makes the table *be* the index**: no rowid and no secondary
B-tree duplicating the same data.

**`trans` indexes the whole phrase and each separate word.** Translations are phrases ("to run"),
so without tokenizing, searching "run" would find nothing — and that is what a user types on a
watch. The side effect is that function words ("to", "of") would point at tens of thousands of
entries, so the builder caps at `TRANS_MAX_PER_KEY = 50`, keeping the best-ranked ones. It caps
instead of dropping the key: searching "to" still returns something useful instead of nothing.

### `fts_def`

```sql
CREATE VIRTUAL TABLE fts_def USING fts5(
    body, content = '', tokenize = "unicode61 remove_diacritics 2"
);
```

Contentless: it stores only the inverted index, not a second copy of the text. It returns rowids
alone, which is exactly what is needed because `fts_def.rowid == entry.id`.

> **FTS5 is not guaranteed in Android's system SQLite.** The pack requires the app to use bundled
> SQLite (`androidx.sqlite:sqlite-bundled`), whose build includes `ENABLE_FTS5`.

## Indexes

```sql
CREATE INDEX idx_entry_norm  ON entry (norm, rank, headword, pos);
CREATE INDEX idx_entry_fuzzy ON entry (fuzzy, norm);
```

`idx_entry_norm` is a **covering** index: it holds the four columns the results list needs, so
SQLite answers the prefix search without touching the table and without reading a single payload.
The `(norm, rank)` order also satisfies the `ORDER BY` with no sort step — and it is **ascending**
because in `rank` lower is more common. It was `DESC` until `schema_version 3`: the symptom only
shows with a real pack, where "escrit" returned *escrito / Participio de escribir* ahead of the
noun. If the index and the `ORDER BY` drift apart, SQLite adds `USE TEMP B-TREE` and the query
stops being covering. `id` is not included because, being an alias of rowid, it is already in
every index.

`idx_entry_fuzzy` is deliberately narrow: it includes `norm` so the candidates can be reordered by
edit distance without reading the table, and only the ten that survive are then read from the
table. Adding `headword`/`pos` would make it covering but would duplicate several MB for a path
only walked when the prefix returned nothing.

`verify_pack.py` checks with `EXPLAIN QUERY PLAN` that the prefix uses a `COVERING INDEX`. If that
plan ever changed to a table scan, the incremental search would stop meeting the latency budget
and nothing else would notice.

## The five queries

**1. Prefix** — served entirely from the covering index:

```sql
SELECT id, headword, pos FROM entry
WHERE norm >= :q AND norm < :qUpper
ORDER BY norm, rank LIMIT 30;
```

`:qUpper` is the lexicographic successor computed by `PrefixRange.upperBound`. The explicit range
is used instead of `LIKE 'q%'` because LIKE is only optimised into a range scan if
`case_sensitive_like` is set correctly, which depends on the connection; in doubt SQLite does a
full scan.

**2. Inflected form** — `form.norm = :q`, join to `entry`, LIMIT 10.

**3. Reverse translation** — the same range pattern over `trans.norm`. **It needs deduplicating by
entry**: the range matches several keys of the same entry ("to", "to run", "to pass") and without
this it comes out repeated.

```sql
SELECT e.id, e.headword, e.pos FROM entry e
WHERE e.id IN (SELECT entry_id FROM trans WHERE norm >= :q AND norm < :qUpper)
ORDER BY e.rank LIMIT 20;
```

**4. Error tolerant** — fires **only if 1+2+3 returned fewer than 5 results**. It queries by a
**prefix** of the fuzzy key (not the whole key) to bring in a neighbourhood and not just exact
collisions; it is then reordered in Kotlin by Damerau-Levenshtein against `norm(q)`, anything past
distance 2 is discarded and the best ten are returned.

**5. Free text** — an explicit user action, **never** while typing:

```sql
SELECT rowid FROM fts_def WHERE fts_def MATCH :ftsQuery ORDER BY rank LIMIT 30;
```

`:ftsQuery` has to be sanitised by wrapping every token in double quotes, so free user text is
never interpreted as FTS5 syntax.

## Payload

Delimited UTF-8 text, compressed with raw deflate and the pack's shared dictionary:

```
P<TAB>verb                     part of speech, opcional, antes de cualquier S
W<TAB>to race                  traducción de LA PALABRA, sin acepción (D-179)
S<TAB>moverse rapidamente      abre una acepción
E<TAB>corrio hasta la esquina  ejemplo de la acepción abierta
C<TAB>1897, Richard Marsh      de dónde se citó el ejemplo de ARRIBA (D-216)
T<TAB>to run                   traducción de la acepción abierta (D-178)
Y<TAB>desplazarse              sinónimo de la acepción abierta (D-117, D-124)
A<TAB>detenerse                antónimo de la acepción abierta (D-126)
R<TAB>camélido                 palabra relacionada de la acepción abierta (D-132)
```

### ⚠️ `C` es el único tag que nombra a la línea de arriba y no a la acepción

Todos los demás cuelgan de **la acepción abierta**, así que su posición dentro del bloque da
igual. `C` cuelga de **su `E`**, y por eso la regla es más estricta que la de los otros: una `C`
que no venga **inmediatamente** después de un `E` se **descarta** —al parsear y al verificar—,
en vez de asignarse al último ejemplo visto.

No es una precaución teórica. El 86,5 % de los ejemplos del dump inglés son citas de textos
publicados, así que la mayoría de los ejemplos del pack tiene una `C` que le corresponde y sólo
a ella: elegirle un ejemplo a una cita suelta produce **una atribución inventada**, que es el
modo de falla que D-179 existe para cerrar y que D-122 califica de peor que *falta una palabra*.

Los tres lados aplican la misma regla y **eso es un contrato cruzado**: `payload.parse`,
`PayloadCodec.parse` y `verify_pack._citas_huerfanas`. Si se separaran, el mismo pack mostraría
atribuciones distintas según quién lo lea.

**La cita NO entra a `fts_def`** (como el antónimo y la relacionada, y a diferencia del
ejemplo): buscar *Richard Marsh* no tiene que devolver `Thomas`. Verificado midiendo — con y sin
citas, `fts_def_data` pesa exactamente lo mismo.

### ⚠️ `T` y `W` son dos canales, y la diferencia es una afirmación

`T` vive **dentro** de una acepción y por lo tanto **afirma que la traducción pertenece a esa
acepción**. `W` es de la entrada: *«la palabra puede significar esto, no sabemos en cuál de sus
acepciones»*.

**El segundo canal existe para que la opción deshonesta deje de ser la barata.** Con sólo `T`, un
builder con una traducción que la fuente no atribuyó podía **tirarla** o **embadurnarla por todas
las acepciones** — y lo segundo es gratis, invisible y pasa `verify_pack.py`. Medido: el **37,7 %**
de las traducciones del dump español no trae `sense_index`, y sin `W` era dato tirado.

⚠️ **La posición en el texto NO es la semántica.** `W` se escribe antes de la primera `S` para que
un lector viejo lo descarte por su guarda `if senses:`, pero `parse` lo toma como de la entrada
aparezca donde aparezca. Si la posición decidiera, un `W` mal ubicado se volvería una traducción de
acepción — la atribución inventada que el canal existe para evitar.

⚠️ **Y se dibujan en lugares distintos**, o la separación se pierde en el último paso: `T` va
dentro del bloque de la acepción, `W` en su propia sección debajo de todas.

### ⚠️ El canal de BÚSQUEDA y el de LECTURA no llevan lo mismo

Esto no es obvio y conviene saberlo antes de contar filas:

| | qué lleva | para qué |
|---|---|---|
| tabla `trans` | **todas** las traducciones, atribuidas y sueltas, **normalizadas** y **tokenizadas** por D-014. ⚠️ **Vacía en un pack bidireccional**: ahí las palabras del otro idioma son entradas de verdad (D-196) | encontrar la entrada |
| tags `T` / `W` | las mismas, en **forma de display**, repartidas por atribución | mostrarlas |

**Contar `trans` esperando que coincida con lo que la ficha muestra no cuadra, y es correcto que
no cuadre.** `trans` guarda `norm()` —`U-turn` es `u turn`— y D-014 tokeniza cada clave en sus
palabras, así que `cloud cover` deja además `cloud` y `cover`: claves útiles, lista ilegible.

El pack bilingüe da el ejemplo más claro: `translation_keys` indexa `to run` **y** `run` porque
nadie teclea la preposición al buscar, pero la ficha se queda con `to run`, que es la forma de
diccionario.

### El código de una acepción: `sense_code`

    sense_code(uid, glosa) = sha256(uid ␟ fold_gloss(glosa))[:12]

Nombra una acepción **sin nombrar un pack**, porque `entry.uid` ya lleva idioma y palabra. Tres
propiedades, todas verificadas sobre los packs reales (D-180, D-181):

- **Cualquier pack instalado de ese idioma puede resolverlo** — el enlace no muere porque el
  usuario tenga el núcleo en vez del completo.
- **Núcleo y completo comparten el código**: 21.534 de 21.534, **100,0 %**, porque `build_core.py`
  copia el uid en vez de recalcularlo.
- **Degrada a la palabra**: el código es un *sufijo* del término (`término␟código`), no lo
  reemplaza.

⚠️ **Es un SEGUNDO contrato entre Python y Kotlin**, con el mismo peso que `norm()`: si los dos
lados calculan distinto, los enlaces apuntan a la nada **sin excepción y sin log**. Lo fija el
mismo vector en los dos — `sense_code(1, "casa")` = `8ec316909e48`.

⚠️ **Y `fold_gloss` es una regla nuestra y versionada**, a diferencia de NFC que es un estándar:
cambiarla invalida todos los enlaces ya escritos.

### Invariante: toda acepción es direccionable

**Dos acepciones de la misma entrada no pueden compartir código.** Si lo comparten, una es
inalcanzable y un enlace escrito contra ella lleva a la otra, sin error. `render` las **fusiona**
—uniendo sus adjuntos, porque de 12 grupos duplicados medidos **5 traían ejemplos distintos**— y
`verify_pack.py` lo comprueba sobre los bytes, que es lo único que vale para un pack ajeno (D-182).

Delimited text instead of JSON or CBOR on purpose: it parses with no dependency at all in both
languages, it can be read by eye while debugging a pack, and after compression the size
difference against a binary format is noise.

**Unknown tags are ignored**, so a newer builder can add fields without breaking an older app.
That tolerance is exactly why the antonym tag `A` **did not** bump `payload_codec`
(D-126), and neither did the related-words tag `R` (D-132).

**Why deflate and not zstd**, even though zstd compresses better: deflate is in `java.util.zip`
(an Android platform API, no extra `.so`) and in Python's stdlib `zlib`. zstd would force a native
library on the watch *in addition* to SQLite's, and a pip dependency in the builder.

**Why the shared dictionary**: entries are a few hundred bytes, far too short for deflate to find
redundancy on its own. The dictionary gives it a window already primed with the corpus's frequent
fragments.

⚠️ deflate **does not validate** the dictionary: with the wrong one it decompresses without error
and returns corrupt text. See
[contratos-cruzados.md](contratos-cruzados.md#3-deflate-does-not-validate-its-preloaded-dictionary).

## Extending the format without breaking what is installed

The question this answers: **what can be added later, and what costs every installed pack?**
There are no migrations (D-001) — a pack that is not compatible is rejected at open and has to be
rebuilt — so the only cheap extension is one an old reader can ignore.

**The cost of getting it wrong, in today's numbers: 372.6 MB** of packs to rebuild and push again.
That is the figure that makes the table below worth following.

### The four surfaces, and what each one tolerates

| Surface | Adding is safe? | How, and what makes it safe |
|---|---|---|
| **Payload tag** | ✅ **both directions** | The parser ignores unknown tags on purpose. An old app skips a new tag; a new app simply does not find it in an old pack. ⚠️ **Do not bump `payload_codec`** for an additive tag — it is compared with `!=`, so bumping it throws the whole property away. Already the rule for `A` and `R` |
| **`meta` key** | ✅ **both directions** | Read it with `meta[...]`, **never** `getValue`. Done three times with zero broken packs: `description` (D-125), `sources` (D-138), `subset_of`. Enforced by `check_required_meta_keys` |
| **Column or table** | ⚠️ **one direction only** | Nothing breaks an **old app**: there is not a single `SELECT *` in the codebase, every query names its columns. But a **new app** querying a column an old pack lacks fails **at query time**, which is the worst place — the whole point of D-001 is failing loudly at open |
| **Meaning of something that already exists** | ❌ never | `schema_version`. Bump it and every installed pack is rejected |

⚠️ **And `norm()` / `fuzzy()` are outside this table entirely.** They bump `NORM_VERSION` always
(D-005, D-006) and the rejection is the point: a pack indexed with other rules **returns fewer
words, with no error** — the failure this whole repo is built to prevent.

### The asymmetry worth knowing about

**The code is more tolerant than the gate, on exactly one surface.** Adding a column to `entry`
would not break a single query in the app — and `schema_version`'s `!=` rejects the pack anyway.

That is not a bug to fix today; it is a lever that exists if it is ever needed. Using it would
mean splitting the version in two — one number for *structure a reader must understand* and
another for *things it may ignore* — and the moment to pay for that is when there is a change that
wants it, not before.

### If a new column really is needed

The additive path exists but it has to be walked deliberately:

1. The new app must **detect the column at open**, with `PRAGMA table_info(entry)`, not discover
   its absence in the middle of a query.
2. Everything that uses it degrades when it is missing — the same shape `meta[...]` already has.
3. `schema_version` bumps **only** if the app cannot work without it.

⚠️ **If that degradation is not written, bump `schema_version` instead.** Rejecting a pack at open
is expensive and honest; a query that fails on some devices and not others is cheap and a lie.

## How the builder builds it

Two passes over a staging table inside the file itself, never in memory: the real sources are
gigabytes and do not fit.

1. **Pass 1** — writes the uncompressed bodies and takes a reservoir sample.
2. Builds the compression dictionary from that sample.
3. **Pass 2** — compresses and fills `entry` and `fts_def`.
4. Materialises `trans`, applying the per-key cap.
5. Drops the staging table and **only then creates the indexes**, over already populated tables.
6. `PRAGMA optimize` and `VACUUM`, which leaves the file compact and its pages in the order they
   will be read on the watch.

The result is deterministic: two builds of the same input give the same content, except for
`meta.built_at`.

## Budgets

**The first one is already measured and is not met.** The other three are still goals written a
priori: they are latency figures, and latency is only worth anything measured on a physical watch
(D-043), which there still is not.

| Metric | Target | Measured |
|---|---|---|
| Pack on disk | ≤ 50 MB *(soft)* | **68.2 MB** — 36 % over (2026-09-19, after D-116 to D-126) |
| `suggest()` with a 3-letter prefix | p95 < 20 ms | unmeasured: no watch |
| First result visible from the last keystroke | < 150 ms | unmeasured: no watch |
| Cold start to a usable search screen | < 700 ms | unmeasured: no watch |

### Both packs, weighed — and why each one weighs what it does

*(Current state, after D-116 —proper nouns go— D-117/D-124 —synonyms come in, in both languages—
and D-126 —antonyms—. The right-hand column is what was there before, so the delta can be read.)*

| | Spanish | English | *Before D-116 (ES / EN)* |
|---|---|---|---|
| Source | eswiktionary Español, 1.42 GB | enwiktionary English, **3.24 GB** | same |
| Entries | **114,619** | **794,355** | *146,194 / 956,150* |
| On disk | **68,173,824 B (65.0 MiB)** | **271,015,936 B (258.5 MiB)** | *72,212,480 B (68.9 MiB) / 309,424,128 B (295.1 MiB)* |
| Build | **63.6 s** | **2 min 45 s** | *53.9 s / 180.6 s* |
| Proper nouns | 731 (0.6 %) | 1,675 (0.2 %) | *32,305 (22.1 %) / 163,470 (17.1 %)* |
| Entries with synonyms | **26,481 (23.1 %)** — 71,779 items | **122,454 (15.4 %)** — 240,195 items | *0 / 0* |
| Entries with antonyms | **3,317 (2.9 %)** — 6,979 items | **9,095 (1.1 %)** — 18,537 items | *0 / 0* |

**The two packs are not shaped alike, and that kills the easy intuition.** A pack is not "mostly
morphology": that is a truth **about Spanish**.

| Object | Spanish | English |
|---|---|---|
| `form` | **34.2 MB (47.4 %)** — 1,487,695 rows, 93.5 % conjugations | 20.0 MB (**7 %**) — 984,473 rows |
| `entry` | 18.2 MB (25.2 %) | **142.3 MB (48 %)** |
| `fts_def` | 10.3 MB (14.1 %) | 80.7 MB (26 %) |
| `idx_entry_norm` + `idx_entry_fuzzy` | 9.5 MB (13.1 %) | 66.0 MB (21 %) |

A Spanish verb brings up to 222 forms; an English one, four. **English weighs what it does because
it has 6.5× more entries**, not because of inflection. It also compresses worse (37 % against
50 %) because nearly all of its weight is payloads that are already compressed.

### The alternative source, measured (spike D-120)

| | pruned enwiktionary | Open English WordNet 2025 |
|---|---|---|
| Entries | 794,355 | **135,969** |
| On disk | 255.7 MiB | **38.4 MiB** (6.7× smaller) |
| Build | ~180 s | **17.9 s** |
| Common-vocabulary probe (57) | 57/57 | 56/57 |
| Hard probe: modern, slang, technical (39) | **39/39** | **25/39 (64 %)** |
| Entries with synonyms | 0 % *(at the time; D-124 changed this)* | **70.8 %** |
| License | CC BY-SA 4.0 | CC BY 4.0 |

Concretely, what OEWN lacks: *selfie, blockchain, deepfake, ghosting, woke, burnout, workaround,
mitochondria, petrichor*. It is frozen academic vocabulary. **It does not replace**, and the
reasoning is in D-120.

⚠️ Its headline advantage —synonyms— **shrank with D-124**: English now has synonyms from
enwiktionary itself, on 15.4 % of the entries. OEWN still reaches 70.8 %, but the gap is no longer
"zero against everything".

### What can be trimmed, measured — and why it was not

| Lever | Saving | What is lost |
|---|---|---|
| Lossless dedup: the form is a prefix of its headword | **0.2 MB (0.3 %)** | nothing — which is why it is useless |
| ~~Proper nouns~~ — **pulled, D-116** | **39.4 MiB in English (13.4 %)**, 4.1 MB in Spanish | place names and surnames with no lexical life. *January*, *Paris*, *España* and *Chile* **are kept** |
| Dropping `fts_def` | 10.3 MB in Spanish (14 %) | searching by definition |
| Dropping `idx_entry_fuzzy` | 4.1 MB in Spanish (5.7 %) | error tolerance — the whole point of dictation (D-027) |
| Pruning `form` by divergence ≥4 | 13.6 MB in Spanish (19 %) | **602,681 forms stop resolving** when typed in full |
| An algorithmic conjugator instead of a table | up to 34 MB in Spanish (47 %) | a second contract between two languages (the class of bug in D-005) |

**The pack already is the internal database**: 1.42 GB of dump → 68.2 MB. What is left is not
Wiktionary garbage, it is search capability, and **every lever costs a feature** (D-077).

**The proper-noun row is the only one that was pulled, and it taught something the rest of the
table does not say**: in Spanish it saved almost nothing —the payload of those 32,305 was
0.63 MB— but it removed the noise from 22.1 % of the `norm` values. **A lever can be worth it for
what it does NOT weigh.** In English it did save: 295.1 → 255.7 MiB.

And there are two rows going the other way: **synonyms** (D-117, D-124) **add** 0.89 MB to Spanish
and 2.82 MB to English, and **antonyms** (D-126) add 0.04 and 0.11 MB. It is content the source
already carried and the builder was throwing away.

### The real Spanish pack, weighed

Built on **2026-09-19** from the kaikki.org Wikcionario dump of **2026-09-15** (eswiktionary,
Español section, 1,423,631,693 bytes). Reproducible:

```sh
python3 tools/packbuilder/build_pack.py es <kaikki-es.jsonl> es-def-wikc.db
python3 tools/packbuilder/verify_pack.py es-def-wikc.db
```

| | |
|---|---|
| Senses in the dump | 1,036,458 *(across 854,460 records)* |
| Records that are inflected-form pages | 703,506 = **82.33 %** — not entries (D-065) |
| `pos = name` entries pruned (D-116) | 31,574 of 32,305 — 731 remain through lexical life |
| Senses with editorial markup cleaned (D-121) | 665 → **0** |
| **Entries in the pack** | **114,619** |
| **Pack on disk** | **68,173,824 bytes (65.0 MiB)** |
| Build | **63.6 s** |

Where the pack goes, and it is the answer that decides O-3:

| Object | Size | Share |
|---|---|---|
| `form` | 33.4 MB | **46.3 %** — 1,487,695 rows, **93.5 % verb conjugations** |
| `entry` | 17.8 MB | 24.6 % — compressed payloads, 2.8 senses per entry |
| `fts_def_data` | 8.6 MB | 11.8 % |
| `idx_entry_norm` | 5.2 MB | 7.3 % |
| `idx_entry_fuzzy` | 4.0 MB | 5.6 % |
| `fts_def_docsize` | 1.4 MB | 2.0 % |
| `trans` | 4 KB | empty: the pack is monolingual (D-034) |

**The pack is the `form` table**, and that is not fat: it is the price of "corriendo" finding
"correr". A Spanish verb brings up to 222 forms. Any cut there is paid in the currency this repo
refuses to pay — *a word is missing*.
