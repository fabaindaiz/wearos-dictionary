# Battery: where the energy actually goes

**Status.** One real datum, a measured cost model, and **no on-watch measurement yet**.

The datum: the watch reported **9.4 %** attributed to the dictionary, over a stretch the user
describes as *"lo he tenido abierto harto rato"*. That is the first battery number this project
has ever had. Everything §O-4 of `docs/roadmap.md` said before it was reasoning, not measurement.

This document exists to keep the next session from re-deriving the same thing, and to say plainly
which parts are measured and which are still arithmetic.

---

## What the app does when nobody is touching it

**Nothing.** This is not an aspiration, it is the current state, and it is checkable in one pass:

| Mechanism | Present? | How it was checked |
|---|---|---|
| `WorkManager` | no | no reference anywhere in `app/src/main` |
| `AlarmManager` | no | idem |
| A wakelock actually acquired | no | `WAKE_LOCK` is declared in the manifest and **never used** |
| `keepScreenOn` / `FLAG_KEEP_SCREEN_ON` | no | no reference |
| Ambient mode | no | no reference |
| Complication refresh | **switched off** | D-108, and with it 24 daily wakeups |
| Tile periodic refresh | no | the history tile is `freshnessIntervalMillis = 0`; the word of the day emits a `Timeline` the renderer walks on its own (D-107) |
| Network | **impossible** | the app is 100 % offline; there is no download path yet |

So **every joule the dictionary spends, it spends while you are looking at it**. There is no
background term to optimise, which narrows the search enormously: the answer is in the foreground
or it is in the screen.

---

## What one interaction costs

Measured with `tools/measure_query_cost.py`, which replicates the SQL of `SqlitePackSource` and
imports the **same** `normalize.py` that built the pack, so the fuzzy key is the real one.

⚠️ **These milliseconds are from a desktop, not from the watch.** They are good for comparing
paths against each other; the absolute number on the watch has to be measured on the watch
(D-043). What does transfer is the *shape*: rows touched, queries issued, rungs run.

### The Spanish pack — 152,281 entries

| Interaction | Queries | Rows | ms of SQL |
|---|---|---|---|
| One search, complete word (average of 12) | **3.0 rungs** | 19 | 1.04 |
| A word that does not exist (voice, typo) | 4 rungs | 0–36 | 0.07–0.51 |
| Opening a word: payload inflate | — | 1 | **0.006** |
| Opening a word: all the tappable gloss words | **1** | 39.9 keys | **0.181** |
| Word of the day | 32 by rowid | 32 | 0.40 — *once a week, per pack* |
| Opening the pack (key sample, D-142) | 64 by rowid | 64 | 0.48 — *once per process* |

### The English pack — 956,150 entries, 6.3× bigger

| Interaction | Queries | Rows | ms of SQL |
|---|---|---|---|
| One search, complete word | 2.2 rungs | 97 | 0.64 |
| Opening a word: gloss words | 1 | 58.8 keys | 0.861 |

**6.3× the entries costs roughly the same time.** That is the covering index doing its job: a
B-tree lookup is logarithmic, so the pack being huge is a *storage* problem (§O-3), not a battery
one. The one place size does show is the gloss query, 0.18 → 0.86 ms, because English glosses are
longer and hit the 64-key cap far more often (58.8 keys against 39.9).

### The UI term, which the first version of this document did not price

⚠️ **The cost model above covers the SQL and nothing else, and on a watch the CPU hog is usually
Compose, not SQLite.** That gap is now closed by measurement rather than by argument.

`./gradlew :app:assembleDebug -Pdictionary.composeReports` writes the Compose compiler's own
report into the module's `build/compose_compiler/` directory — a build output, so it is not
in the repo and there is nothing to read until you ask for it. Measured on 2026-09-20:

| | |
|---|---|
| Composables declared in `:app` | **21** |
| Of those, restartable **and skippable** | **21** |
| Strong skipping | **on** |

**Every composable in the app can skip.** That is the whole answer: a recomposition that changes
nothing gets skipped instead of re-running.

⚠️ **And it killed the fix I was about to propose.** Ten classes report as unstable —
`SearchState`, `PackHandle.Open`, `PackSet.Ready` — because they carry `List<T>` and types from
`:dict-core`. The textbook response is a Compose *stability configuration file*, which would have
been the architecturally correct way to do it here (annotating `:dict-core` would drag
`compose-runtime` into the module that D-017 and D-018 keep portable). **With strong skipping on,
it would have bought nothing**, because unstable parameters are already compared by instance. The
report is what said so; reasoning about stability would have led straight into the work.

What the review did fix in this area is smaller and is **not** a battery item: three pure
functions — `resultTags`, `languageChips` and the word-of-the-day grouping — were being recomputed
on every recomposition instead of being `remember`ed. It is microseconds against minutes of
screen. It was fixed because it costs one line, not because it shows up anywhere.

### A belief this killed

`SqlitePackSource` says, in a comment, *"Prefijo del lema: el 95% del uso."* **Measured, on
complete words it is 1 rung in 2 of 12 cases; the average is 3.0 rungs.** The comment is not
wrong about typing — while you type, the prefix rung fills its limit 37 times out of 70 — it is
wrong about **the search that actually runs**. D-128 does not search while the keyboard is open:
it searches once, when the keyboard closes, over the **complete word**, and a complete word is
precisely the case where the prefix rung returns few rows and the cascade continues.

This costs nothing today (3 rungs is still ~1 ms) but it matters for anyone reasoning about the
cascade from that comment.

---

## The question that started this

> *"No encuentro tan útil que todas las palabras indexables puedan ser presionables en la pantalla
> de una definición, y no sé si esto afecta el uso de batería al tener que hacer tantas búsquedas"*

**It does not do many searches. It does exactly one, per word opened.** D-094 collects every
token of every gloss, normalises them, and resolves them in a **single** `WHERE norm IN (...)`
query capped at 64 keys — one round trip, served by the same covering index as the search.

Measured both ways, on the same 64 keys:

| | ms |
|---|---|
| One query per word (what the concern assumes) | 0.378 |
| One batched query (what the code does) | **0.077** |

So the feature's real price is **0.18 ms per word opened** in Spanish, 0.86 ms in English.
Opening 100 words in a session spends **18 ms**. Removing it would be giving up navigation that
works, to save a number that does not appear in any battery report.

**Recommendation: do not remove it.** If it is unwanted, it should be removed because the blue
words are visual noise on a 234 dp screen — a design argument, which is legitimate — and not on
battery grounds, which the measurement does not support.

---

## The arithmetic that closes the CPU hypothesis

Take a deliberately heavy session: **60 searches, 40 words opened, the app launched 10 times**.

```
searches     60 × 1.04 ms  =  62 ms
words        40 × 0.18 ms  =   7 ms
app launches 10 × 0.48 ms  =   5 ms
                              ------
                              74 ms of SQL
```

A watch CPU is far slower than the machine this was measured on and its storage is not NVMe. Be
brutal and assume **50× slower**: that is **3.7 seconds of CPU** for the whole session.

Against that, *"lo he tenido abierto harto rato"* — say 30 minutes — is **1,800 seconds of
screen**.

**Three orders of magnitude apart.** For the CPU to account for 9.4 % it would have to be doing
something this cost model does not contain, and the way to find out is to look, not to guess (see
below).

The primary source for how the framework builds that percentage says the two terms are computed
separately: it *"multiplies the CPU time for each application by the mA required to run the CPU at
a specific speed"*, while for the display it *"tracks the time spent at each brightness level,
then multiplies those time intervals by an interpolated display brightness cost"*
([Power profiles for Android](https://source.android.com/docs/core/power)). CPU time per app is
therefore the term this project controls directly — and it is the term the arithmetic above bounds
at seconds.

⚠️ **ASSUMPTION, not verified:** that the 9.4 % the watch showed includes the display term
attributed to foreground time, rather than being CPU-only. This changes the whole reading and is
the first thing `dumpsys` settles.

---

## How to get the real number

Nothing below is a substitute for this. The watch was not connected while this was written.

```sh
# 1. Zero the counters, then use the dictionary normally for a while, unplugged.
adb shell dumpsys batterystats --reset

# 2. The app's own CPU time, wakelocks and wakeups. This is the term we control.
adb shell dumpsys batterystats --charged cl.fadiaz.dictionary

# 3. The global screen term, to compare against it.
adb shell dumpsys batterystats | grep -iE "screen on|screen brightness|Screen:"

# 4. How long the app was actually in the foreground.
adb shell dumpsys usagestats | grep -A 5 cl.fadiaz.dictionary

# 5. If CPU turns out to be non-trivial, a trace says WHERE:
#    Perfetto or the Power Profiler. NOT Battery Historian -- the official
#    documentation says it is no longer maintained.
```

**What decides it:** the app's `Total cpu time` in step 2, next to the screen-on time in step 3.
If CPU is seconds and screen is tens of minutes, the answer is the screen and every CPU
optimisation below is theatre. If CPU is minutes, something in this cost model is wrong and that
is a far more interesting finding.

---

## Strategies, ordered by what the measurement would have to say first

### If it is the screen — the likely case

None of these are code optimisations. They are about **how long the screen stays on**, which is
the only lever that matters at this ratio.

1. **Fewer scrolls to the answer.** Every row the user has to scroll past is screen time. This is
   why D-148 (three recents + a button) and D-154 (proper nouns below common words) are battery
   decisions and were never filed as such: a result list that answers in the first row ends the
   session sooner. The pending **cross-language fallback** is in the same family — a search that
   returns nothing because the wrong language was active costs a second search, which is double
   the screen.
2. **The long example is a battery item too.** With senses expanded, a 900-character example
   pushes the next one off screen (roadmap, §Diseño de la interfaz). Scrolling past it is paid in
   screen-on seconds, every time.
3. **Do not add animations.** The official Wear OS guidance asks to minimise them; today there are
   effectively none, and that is worth keeping (§O-5).
4. **What is NOT worth doing: dimming, or an ambient mode.** The system already turns the screen
   off on its own timeout, and the app holds nothing open to prevent it. Adding an ambient mode
   would make the app draw *more* often, not less.

### If it is the CPU — measure before believing it

**The UI half of this is already measured and clean** (see above): all 21 composables skip. What
is left is the query side, in rough order of what the cost model says is biggest:

1. **The tolerant rung (`byFuzzy`) is the only expensive operation in the app**: up to 200
   candidates scored with Damerau-Levenshtein. It runs only when the previous rungs returned fewer
   than 5 results — which, per the measurement above, is more often than the code comment implies:
   4 of 12 complete words reached it. Tightening `FUZZY_CANDIDATES` from 200 or raising
   `FUZZY_TRIGGER` are both one-line knobs. **Do not touch them without a before/after**: the
   tolerant rung is what makes voice dictation usable, which is the primary input path.
2. **With two packs installed, the merge queries both, sequentially** (D-136). That doubles the
   per-search work. It is the price of packs coexisting and should not be paid back by turning
   packs off — the user already ruled that out — but it is the reason the cross-language fallback
   must stay behind its threshold (no exact match *and* nothing in the top coverage band) instead
   of always querying everything.
3. **The 64-key gloss query in English** is the one place where pack size shows, at 0.86 ms.
   Lowering `MAX_PALABRAS_POR_CONSULTA` would cut it, at the cost of leaving late words in a long
   gloss untappable. Not worth it at this magnitude.

### What must not be cut on battery grounds

- **The tappable gloss words (D-094).** Measured at 0.18 ms per word opened. See above.
- **The key sample on open (D-142).** 64 reads, 0.48 ms, **once per process**. It is what turns
  `norm_version` from a claim the pack makes about itself into something verified, and it defends
  the project's central invariant.
- **The payload dictionary hash (D-008).** Once per open. A wrong preloaded dictionary
  decompresses *without error* into corrupt text.
- **The word of the day (D-097).** 32 reads, once a week, per pack.

---

## What this leaves open

- **No measurement on the watch.** Everything above is a cost model plus arithmetic. The protocol
  is in this document; running it needs the watch connected.
- **The attribution question** — whether the 9.4 % is screen or CPU — is unresolved and is the
  single fact that decides which half of the strategy list applies.
- **The cost model is a replica, not an instrument.** `tools/measure_query_cost.py` re-implements
  the cascade's SQL in Python. If `SqlitePackSource` changes its constants and the script does
  not, the script reports the wrong rung in silence. It says so in its own docstring.
- **Nothing here measures drawing.** The Compose report says a composable *can* skip; it does not
  say how expensive the frames that do run are. That needs a trace on the watch, same as
  everything else in the protocol above.
