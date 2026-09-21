# Cross-cutting contracts

This project has agreements between pieces that break **in silence**: they produce no exception,
no error, not a single log line. The symptom is always the same and hard to attribute: *a word is
missing from the results*. Somebody reports it as "the app can't find X", months later, and there
is nothing in the stack trace.

This document is the list of those agreements and of the mechanism that protects each one.

> **Before opening this document, rule out what is not a bug.** Since D-116 the pack **carries no
> proper nouns** —surnames, place names, given names— except the ones with lexical life. They are
> 22.1 % of the entries in Spanish and 17.1 % in English: *Ivanivka*, *Troya*, *Etchechury* are
> **missing on purpose**, and *January*, *Paris*, *España* and *Chile* are there through the
> exception. A `SELECT value FROM meta WHERE key='proper_nouns'` that says `lexical-only` answers
> the question without reading anything else.
>
> The symptom of a product decision and that of a broken contract **are identical**, and that is
> precisely why the policy is written into `meta` and not only into the code: a pack has to be
> able to explain on its own why a word is missing from it.

## 1. `norm()` and `fuzzy()` exist twice

| | |
|---|---|
| **Files** | `dict-core/src/main/kotlin/cl/fadiaz/dictionary/core/TextNormalizer.kt` ⟷ `tools/packbuilder/normalize.py` |
| **Agreement** | They must give the same result **bit for bit**, for every input |
| **Protection** | `tools/packbuilder/vectors/normalization-vectors.tsv`, run by the tests on **both** sides |

The builder computes these keys while building the pack and stores them in indexed columns. The
watch recomputes them over what the user types and compares. If the two implementations differ by
even one character, the comparison fails and the word simply does not show up.

**When changing the normalization:**

1. Change both files in the same commit.
2. Add cases to `normalization-vectors.tsv`.
3. Bump `NORM_VERSION` on both sides. Old packs reject themselves when opened.

**Why there are no regular expressions.** The phonetic foldings use only literal string
replacement, which has identical semantics in Kotlin and in Python: global, left to right, no
overlap. Two "equivalent" regexes in two languages are exactly the kind of thing that diverges on
an edge case nobody notices.

**The order of the rules is part of the contract.** In the Spanish profile, `"ce" → "se"` has to
run before `"c" → "k"`; otherwise "cerrar" ends up as "kerar" and stops colliding with "serrar",
which was the point.

## 2. The platform's Unicode version

| | |
|---|---|
| **Files** | `tools/unicode/repertoire.txt` → `repertoire.py` + `UnicodeRepertoire.kt` |
| **Agreement** | Code point classification comes from our table, never from the platform |
| **Protection** | Both copies are generated from the same source and tied together by sha256 |

This is a bug that already happened, and it is worth understanding because intuition gets it
wrong.

Every platform ships its own Unicode version:

```
Python 3.9  ->  Unicode 13.0
Java 26     ->  Unicode 16
Android     ->  a different version PER SYSTEM RELEASE
```

While classification was delegated to `unicodedata.category` and `Character.getType`, the builder
and the app did not agree: measured over the complete repertoire, **14,773 code points were
classified differently**, all of them assigned after Unicode 13. And the worst part was not the
builder/app difference, but that **the same pack would have behaved differently on two watches
running different Wear OS versions**.

The table is pinned to **Unicode 13.0** because it is the *floor*: what the builder's Python
ships, and below Android 13 (`minSdk 33`, Unicode 14). Every platform above the floor knows the
whole repertoire.

**What is still delegated to the platform, and why that is safe.** NFD and `lowercase()`. Verified
over the 133,730 code points in the table: **zero differences** in both operations between Java 26
and Python 3.9. Unicode's stability policy guarantees that a character's canonical decomposition
does not change once it is assigned.

**Bumping the Unicode version is a deliberate act**, not a side effect of updating Python. The
procedure is in the header of `tools/unicode/gen_repertoire.py`.

## 3. deflate does not validate its preloaded dictionary

| | |
|---|---|
| **Files** | `dict-core/src/main/kotlin/cl/fadiaz/dictionary/core/PayloadCodec.kt` ⟷ `tools/packbuilder/payload.py` |
| **Agreement** | `meta.payload_dict_sha256` is verified when the pack is opened |
| **Protection** | `vectors/payload-fixture.tsv`, compressed by Python and decompressed by Kotlin |

The payloads are compressed with deflate and a shared dictionary stored in the pack. The dangerous
part is that **deflate does not detect a wrong dictionary**: if it is long enough, it decompresses
without throwing anything and returns corrupt text. Verified:

```
correct   ->  "moverse rapidamente"
wrong     ->  " nadrse rapidamente"     with no exception at all
```

Java did throw the first time it was tried, but by luck: the fake dictionary was shorter and the
references fell outside the window. That is not a guarantee.

Hence the hash, checked **once when the pack is opened**, not per entry.

> A compressed canary derived from the dictionary itself was tried earlier and discarded: since
> the expected value was computed from that same dictionary, a truncated one still validated. An
> integrity mechanism that validates itself validates nothing.

## 4. `fts_def.rowid` is `entry.id`

| | |
|---|---|
| **Agreement** | The FTS5 table is *contentless*; the rowid is the only thing it returns |
| **Protection** | `verify_pack.py` checks there is one FTS row per entry |

`fts_def` is declared with `content=''` so it does not keep a second copy of the text, which
already lives compressed in `entry.payload`. The consequence is that a free-text search **returns
only rowids**: if the builder misaligns them, the search points at the wrong entries and shows
results that have nothing to do with what was searched.

Accepted consequence: no `snippet()` and no `highlight()`. Highlighting is done in Kotlin over the
decompressed payload of the few results actually shown.

## 5. An entry's identity across packs

| | |
|---|---|
| **Files** | `tools/packbuilder/build.py` → `entry.uid` ⟷ the auxiliary pack that references it |
| **Agreement** | `entry.uid` identifies the same word in two different packs, and survives rebuilding the base one |
| **Protection** | `verify_pack.py` (uniqueness + `meta.uid_recipe`) and `check_forbidden_mirror` in `audit_dictionary.py` |

An auxiliary pack —synonyms, translations— adds information to an entry of the base pack by
pointing at it through `uid`. The two ways to break it produce no error:

- **A repeated `uid`**: the auxiliary hits two entries at once and one of them shows content
  belonging to another word.
- **A different `uid` recipe**: the auxiliary points at the wrong entry, or at none. That is why
  the recipe travels in `meta.uid_recipe` and gets compared.

**Why this is not a contract between two languages, and has to stay that way.** `uid` is computed
by the builder alone; the app reads it from the column. As long as there is a single
implementation it cannot diverge, and no shared vectors are needed. The day somebody writes a
`TextNormalizer.uid()` —out of convenience, to avoid reading the row— the whole class of bug from
§1 comes back, and this time with no vectors to catch it. `audit_dictionary.py` breaks the build
if one appears.

**Why the build fails on a collision instead of resolving it.** Any tie-break rule that depends on
insertion order breaks exactly the stability across rebuilds that `uid` exists to give: the same
dictionary, rebuilt, would hand out the identities differently. The source provides `sense_key` to
separate homographs with the same headword and the same pos.

## 6. `sense_code()` exists twice — el segundo contrato entre los dos lenguajes

**El síntoma**: un enlace a una acepción **lleva a otra palabra, o a ninguna**. Sin excepción,
sin log, y `verify_pack.py` no lo ve — porque cada lado, por separado, calcula algo perfectamente
válido.

Desde D-180 una acepción se nombra así, y los dos lenguajes tienen que producir el mismo string:

    sense_code(uid, glosa) = sha256(uid ␟ fold_gloss(glosa))[:12]

        Python  tools/packbuilder/payload.py      sense_code / fold_gloss
        Kotlin  .../core/PayloadCodec.kt          senseCode  / foldGloss

**Es el mismo modo de falla que `norm()`** (§1) y merece la misma desconfianza. Lo fija un vector
idéntico en los dos lados: `sense_code(1, "casa")` = **`8ec316909e48`**.

### ⚠️ `\s` no significa lo mismo en Python que en Java

La trampa concreta que apareció escribiendo el espejo, y que **ningún test habría encontrado por
casualidad**:

| | qué matchea `\s` |
|---|---|
| Python, sobre `str` | **Unicode**: incluye el espacio duro U+00A0, el fino, el de tabulación ideográfico… |
| Java / Kotlin `Regex` | **ASCII**: sólo `[ \t\n\x0B\f\r]` salvo que se pida `UNICODE_CHARACTER_CLASS` |

Con `\s` en los dos lados, una glosa con un espacio duro se plegaría **de un lado y del otro no**,
y la misma acepción tendría dos códigos distintos. El pack quedaría bien construido y los enlaces
rotos.

**Por eso el espacio se enumera a mano** en `fold_gloss` y en `foldGloss`, con el mismo conjunto
explícito, y hay un test en **cada lenguaje** que fija que **ninguno de los dos** colapse el
espacio duro. Se pierde plegar ese carácter; se gana que los dos hagan exactamente lo mismo, que
es el trato que este repo ya eligió para `norm()`.

⚠️ **La regla general, para el próximo espejo**: una clase de caracteres de una expresión regular
**no es portable entre lenguajes**. Si un contrato cruzado necesita una, se enumera.

### Y `fold_gloss` es una regla versionada, no un estándar

`norm()` está atada a `NORM_VERSION` y eso está bien, porque un bump reconstruye los packs.
`sense_code` **no** pasa por `norm()` a propósito (D-181, precedente de D-055): si lo hiciera, un
bump de `NORM_VERSION` —que D-005 permite en cualquier momento— cambiaría **todos** los códigos y
dejaría apuntando a la nada cada enlace de cada pack ya construido.

Pero `fold_gloss` es **nuestra**, y cambiarla tiene exactamente ese efecto. **No se toca sin
reconstruir todo lo que tenga enlaces escritos.**

## 7. `:dict-core`'s portability

| | |
|---|---|
| **Agreement** | Every JVM API lives in `PlatformJvm.kt` |
| **Protection** | `ArchitectureTest` breaks the build if another file touches one |

"It is pure Kotlin" is a claim that decays on its own: all it takes is for somebody to use
`String.format`, `java.util.Locale` or `codePoints()` without thinking. The test turns it into an
immediate failure instead of a surprise on the day of the KMP conversion.

One detail the test does not catch and is worth keeping in mind: **a Kotlin extension never wins
over a native JVM member**. That is why `appendUtf16` is named that and not `appendCodePoint`
—with that name, the JVM's `StringBuilder.appendCodePoint` would have won and the portable code
would never have run, working fine today and failing only when compiled for another target.

## How to verify all of it at once

`./gradlew check` runs the six mechanisms above. The individual commands are in
[CLAUDE.md](../CLAUDE.md); they are not repeated here so there are not two lists that diverge.
