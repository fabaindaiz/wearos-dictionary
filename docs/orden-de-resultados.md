# El orden de los resultados

**What decides which word appears first, assembled in one place.** It is computed in four stages
that live in four files, and no single one of them can be read as *the* order — which is why this
document exists: every improvement so far was found by running a query against a real pack and
reading the list, and every time, half the reasoning had to be re-derived from comments.

| Stage | Where | When it happens |
|---|---|---|
| 1. `rank` | `tools/packbuilder/sources/kaikki.py` → `_rank` | at **build** time, written into the pack |
| 2. the cascade | `dict-data` → `SqlitePackSource` | per query, per pack |
| 3. `score` | `SqlitePackSource` | per query, per pack |
| 4. the merge | `dict-core` → `SearchRepository.orderFor` | per query, across packs |

---

## 1. `rank`, decided when the pack is built

Lower is better. It is **two disjoint bands** and reading it as one number is the commonest
mistake:

| band | who lands there | how it is ordered |
|---|---|---|
| `[0, 500)` | the word has a real usage frequency (OpenSubtitles Zipf) | by that frequency |
| `[500, 1000]` | no frequency signal at all | by page richness, compressed into half the range |
| `+1000` on top | a proper noun | `CASTIGO_NOMBRE_PROPIO`, D-134 |

⚠️ **Page richness was the only proxy there was and it turned out bad.** Measured over the Spanish
pack it correlates **−0.250** with real frequency where −1 would be expected, because it counts
inflected forms and a verb carries up to 222 of them. That is why the frequency band goes in front,
and it is why `house` used to return `solar, alojar, albergar` and never `casa` (D-142).

⚠️ **`rank` is per pack, computed against its own dump with its own profile** (D-063). Two packs
of the same language from different sources are not on the same scale: measured, `es-def-wikc`
ranges 668..1997 and `es-def-wd` 911..997. Stage 4 is built so this cannot matter; see *what the
order does not promise*.

## 2. The cascade: which rung answered

`MatchKind` is an enum and **its declaration order is the priority**.

⚠️ **What a test pins is narrower than it looks, and saying so is the point of this document.**
`DictLogTest` feeds the trace a map in the REVERSE order and asserts the log line comes out in the
enum's order — so it guarantees the readout is stable enough to compare two lines, **not** that
the enum itself may not be reshuffled. Reordering the enum would move the priority and no test
would go red.

| | rung | what it means |
|---|---|---|
| 0 | `PREFIX` | the lemma starts with what was typed. The normal path |
| 1 | `INFLECTED_FORM` | a form matched: `corriendo` typed, `correr` is the lemma |
| 2 | `TRANSLATION` | the other language's side matched: `run` typed in an es→en pack |
| 3 | `FUZZY` | the typo-tolerant key matched. **Only attempted when everything above returned nothing** |
| 4 | `DEFINITION` | the definition text matched. **Only on an explicit action by the user** |

## 3. `score`: position inside one pack's own answer

⚠️ **`score` is NOT `rank`, and this is the single easiest thing here to get wrong.** It is the
**relative position within its own pack's result list** — except in `FUZZY`, where it is the
**edit distance**. Two different quantities under one name, and the comment that says so lives in
`Model.kt` beside the field.

Being ordinal is what makes the merge immune to a badly calibrated pack: whatever scale a pack
uses for `rank`, its own list is 0, 1, 2, …, and those are comparable across packs.

## 4. The merge, step by step

`SearchRepository.orderFor`. **With no query** the order is the short one — `matchKind`, `score`,
`headword`, `packId`, `entryId` — and everything below applies only when there is something typed.

| # | key | applies to | what it defends against |
|---|---|---|---|
| 1 | `matchKind.ordinal` | all | an exact prefix must not sit under a phonetic near-miss |
| 2 | proper-noun demotion | `PREFIX` only | `ital` returned **`Italia` first and `italiano` second**: the builder's penalty is not enough here, because step 4 goes ahead of `rank` |
| 3 | no frequency signal → last | `PREFIX` only | `hous` left `Hous.` first and `tim` put four `Tim`s before `time`. Mean position of the obvious word: **5.1 → 3.6** |
| 4 | coverage band | `PREFIX` only | how much of the lemma the user typed, which needs no calibration |
| 5 | the active language wins | all | **after** quality, never before |
| 6 | `matchKind.ordinal` again | all | re-applied once language has broken the tie |
| 7 | `score` | all | position inside the pack's own list — see stage 3 |
| 8 | the better-calibrated pack | all | **after** `score`: it breaks ties, it does not reorder |
| 9–11 | `headword`, `packId`, `entryId` | all | total order, so the list never reshuffles between identical queries |

**Four of those positions are load-bearing, and each was measured rather than reasoned:**

⚠️ **The proper-noun demotion goes BEFORE the coverage band.** Afterwards it would do nothing —
the band would already have put the short toponym on top.

⚠️ **The active language goes AFTER quality.** If the other language simply went to the end, an
exact answer there would sit below ten phonetic near-misses from the active one — off screen,
which is the same as never having searched for it. And all else being equal, the language the user
chose wins, because they chose it.

⚠️ **The calibration tie-break goes AFTER `score`.** Putting it before would sink the whole other
pack and with it its exclusive lemmas, which are exactly the gain D-136 measured.

⚠️ **Steps 9 to 11 are not decoration.** Without a total order two identical queries can produce
different lists; the list then restructures, the text field is recomposed and **the keyboard
closes**, intermittently and with no visible cause (D-089).

---

## What this order does NOT promise

- **That the first row is the best of two packs.** `score` derives from `rank`, which each pack
  computes against its own dump. Comparing across packs from different sources is an approximation
  **nobody has measured**, and measuring it needs two real same-language packs.
- **That step 3 fixes the frequency problem.** `wat`, `boo` and `beaut` have a signal too — they
  are real subtitle tokens — so they stay in front. It is a tie-break, not a cure.
- **That a translation-only row is distinguishable.** Whether a row has a definition behind it does
  not enter the order at all; see `docs/roadmap.md` §*Palabras del pack de traducción*.

## How to see it change

Every improvement here was found the same way: run a query against a real pack and read the list.
`tools/measure_query_cost.py` opens real packs and replicates the cascade's SQL, so it is the place
to turn *«it looks better»* into a diff — ⚠️ it is a **replica** with the constants copied, so if
those change and it does not, it lies quietly. Its own docstring says so.
