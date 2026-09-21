# Battery: where the energy actually goes

**Status.** One real datum, a measured cost model, a diagnostic against the official sources, and an action plan — with **no on-watch measurement yet**, which is item 1 of that plan because it decides the order of the rest.

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

## What the official sources actually say

Researched 2026-09-20. Primary sources only; each claim links to the page it came from.

### The power model, which settles the whole argument

Android's power profile gives the display and the CPU separate entries
([Power profiles for Android](https://source.android.com/docs/core/power/values)):

| Entry | What it is | Order of magnitude |
|---|---|---|
| `screen.on` | *"Additional power used when screen is turned on at minimum brightness"* | **~200 mA** |
| `screen.full` | *"...at maximum brightness, compared to screen at minimum brightness"* | **100–300 mA** |
| `cpu.active` | *"Additional power used by CPUs when running at different speeds"* | **100–200 mA** |
| `cpu.idle` | *"Total power drawn by the system when CPUs...are in system suspend state"* | **~3 mA** |

So a lit screen costs **200–500 mA continuously**, and a busy CPU costs 100–200 mA **for the
milliseconds it is busy**. That is the whole ranking, and it is why this document keeps repeating
the same sentence: on this app, nothing about the CPU is ever going to be the battery.

⚠️ **And a consequence that is easy to get backwards**: the framework models the display by
**time at brightness**, not by what is drawn. Dark pixels save real energy on an OLED panel, but
that saving **does not appear in Android's estimate** — you cannot verify it with `dumpsys`, only
with a power monitor. Anyone who dims the palette and then points at a battery screen to prove it
worked is reading a model that does not contain the effect.

### The Wear OS guidance, and the threshold that is not ours

[Conserve power and battery](https://developer.android.com/training/wearables/apps/power) is
prescriptive and short. What applies here:

- Animations: *"avoid long-running animations and loops. If a loop is required, add a pause
  between loops that's at least as long as the animation itself."*
- CPU: *"Keep usage short."* and *"Batch any related operations, to maximize the time that your
  app's process is idle."*
- Tiles: *"Disable automatic refresh, or increase the refresh rate to 2 hours or longer."*
  **Already done** — D-107 and D-108.
- Screen-on locks: *"Avoid whenever possible."* **Already true** — the app holds none.

⚠️ **The famous "3.2 % per hour" number is a watch-face metric and does not apply to this app.**
[Excessive battery usage](https://developer.android.com/topic/performance/vitals/excessive-battery-usage)
defines it over *watch face sessions*, measured *"when devices aren't charging and no apps are in
use"* — that is background drain, and a dictionary you are reading is neither. Quoting it against
our 9.4 % would be comparing two different things.

**What does transfer is its CPU sub-threshold**: a session is flagged when it uses the CPU for
**90 seconds or more per hour**. Our deliberately-heavy session is ~3.7 s of CPU *in total*. Two
orders of magnitude under a bar that was set for something else.

### The SQLite guidance, and how much of it is for us

[Best practices for SQLite performance](https://developer.android.com/topic/performance/sqlite-performance-best-practices)
is mostly about writes, and **the pack is read-only and immutable** (D-001). So:

- **WAL and `synchronous = NORMAL`: not applicable.** There are no writes. Nobody should "add WAL"
  to this repo.
- Transactions, batching inserts, uniqueness constraints: **build-time concerns**, already handled
  by `tools/packbuilder`.
- *"Read only the rows you need"* / *"Read only the columns you need"*: already the design.
  `byPrefix` selects three columns and takes them **whole from the covering index** (D-012).
- *"Use `EXPLAIN QUERY PLAN`"*: done below, and it found something worth writing down.

What the page gives that we did **not** have is a set of on-device diagnostics:

```sh
adb shell setprop log.tag.SQLiteTime VERBOSE   # query times, on the watch
adb shell dumpsys meminfo cl.fadiaz.dictionary # SQLite page cache hits/misses
# and a Perfetto config with atrace_categories: "database"
```

The page-cache hit rate is the one number that would tell us whether `mmap_size = 8 MB` and
`cache_size = -2000` are the right sizes on a 301 MB pack. Today those two values are **reasoned,
not measured** — the comment in `PackFile.open` says so.

---

## The diagnostic

Measured on 2026-09-20 against the real packs and a real release build.

### What the app pays on every process start

The app has no rescan: the pack scan is one-shot in the ViewModel's `init`. So **every time the
process starts, every installed pack is opened and fully validated** — metadata, a 32 KB dictionary
hashed, and 64 rows read by rowid with `norm()` recomputed over each (D-142).

| Pack | Size | open + meta | sha256 | 64 keys | total |
|---|---|---|---|---|---|
| `es-def-wikc` | 71.7 MB | 4.04 ms | 0.03 ms | 13.99 ms | **18.06 ms** |
| `en-def-wikt` | 300.9 MB | 2.17 ms | 0.07 ms | 22.03 ms | **24.27 ms** |
| | | | | | **42.33 ms** |

Desktop milliseconds; a watch is far slower. **This is not a battery item — it is a
seconds-of-screen item**, which on this app is the same thing as a battery item.

### What a release build actually contains

| | APK | dex | native libs |
|---|---|---|---|
| Today (R8 off) | **33.0 MB** | **29.5 MB** | 2.4 MB |
| With R8 on | **5.5 MB** | **2.7 MB** | 2.4 MB |

**The dex drops by 91 %.** It builds with zero keep rules, and the three classes named in
`AndroidManifest.xml` plus the bundled SQLite JNI driver all survive the shrink — checked by
reading the strings out of the shrunk dex, which is not the same as running it.

### The query plan, and a trap inside it

```
EXPLAIN QUERY PLAN <the prefix query>
|--SEARCH entry USING COVERING INDEX idx_entry_norm (norm>? AND norm<?)
`--USE TEMP B-TREE FOR ORDER BY
```

The hot query **does** build a temp B-tree, because it range-scans on `norm` and orders by `rank`
(D-068), and no index can serve both. Anyone who runs `EXPLAIN QUERY PLAN` will find this and want
to fix it. **Measured before wanting to:**

| | ms per query |
|---|---|
| With the `ORDER BY` (temp B-tree) | 0.093 |
| Without it (index only) | 0.036 |
| **What the ordering costs** | **0.057 ms — 62 % of the query** |

62 % of 0.093 ms. At sixty searches that is **3.4 ms**, in exchange for giving up the ordering that
D-068 exists to provide. It is the textbook shape of a large percentage of a tiny number.

---

## The action plan

Ordered by **seconds of screen removed**, because that is the only currency this app spends in.
Anything whose effect is measured in milliseconds of CPU is not on this list, and the section after
it says why.

### 1. Decompose the 9.4 % — blocks everything else

Nothing below should be prioritised before this runs, because it decides whether the list is even
ordered correctly. The protocol is above; it needs the watch connected. **Cost: minutes.**

### 2. ~~Turn R8 on~~ — **done** (D-163), and the device check is what is left

**33.0 MB → 5.47 MB, dex 29.5 → 2.70 (−91 %).** Less dex is less class loading, less memory and
less JIT at every process start, and on this app every process start is a user staring at a screen.

Three keep rules were added, none of which the build needs — it compiles and shrinks without any.
They exist so it keeps working when someone changes something, and `check_r8_keep_rules` fails if
they stop being wired in or start naming a class that no longer exists.

⚠️ **The risk O-2 named is real and unchanged**: R8 removes code that only reflection reaches, and
it shows up *only* in a release build. The size is now known; what is not known is whether it runs.
**Gate: install the R8 release on the watch and exercise every surface** — search, entry, both
tiles, settings, pack deletion. The two `TileService` classes are the sharp edge, because
`app/CLAUDE.md` already records that breaking them produces no compile error and no test.

### 3. Baseline and startup profiles

Official numbers: Baseline Profiles *"improve code execution speed by about 30 % from the first
launch"*, and with Startup Profiles *"app startup is usually between 15 % and 30 % faster than with
Baseline Profiles alone"*
([overview](https://developer.android.com/topic/performance/baselineprofiles/overview)). Pairs with
R8 — Reddit's 51 % came from both together.

This is worth more here than on a phone: a dictionary is opened for fifteen seconds at a time, so
startup is a large fraction of every session rather than a one-off.

### 4. Cut the searches that return nothing

A search that fails because the wrong language was active costs a **second search** — double the
screen time, for one answer. The cross-language fallback is designed and unbuilt (roadmap, §Diseño
de la interfaz): fall back to the other languages **only** when there is no exact match and nothing
in the top coverage band, so the normal case pays nothing.

Same family, same currency: the long-example wall that pushes the next sense off screen, and
proper-noun demotion (D-154, done) which puts the likely answer in the first row instead of the
fourth.

### 5. ~~Reconsider validating every pack on every launch~~ — **done** (D-164)

Asked for explicitly after this section said it was not an agent's call. It was done the way the
paragraph described — not by removing the proof, but by not re-proving an immutable file.

| | per process start |
|---|---|
| First launch after installing | **41.33 ms** |
| Every launch after that | **6.30 ms** (−85 %) |

The fingerprint carries `NORM_VERSION`, so a change to `norm()` or `fuzzy()` re-proves every pack;
and the switch turns off **only** the key sample — `schema_version`, `norm_version`,
`payload_codec` and the dictionary's sha256 still run on every open, because they are 6.30 ms and
they cover different failures.

### 6. The palette — real, and unverifiable from here

`DictionaryTheme` is still the template's *"Empty theme to customize for your app"*, so the app
runs Wear Material3's defaults. On an OLED panel every lit pixel costs current, and this is the
only lever that touches the dominant term directly rather than by shortening it.

⚠️ **But it cannot be verified with software** — Android's model is brightness-based, not
content-based. Doing this means committing to a design without being able to measure the payoff,
which is a product decision, not an optimisation.

---

## What is deliberately NOT on the plan, with the number that disqualified it

Each of these looks like an optimisation and is measured to be noise. They are listed so the next
session does not rediscover them as ideas.

| Idea | Measured cost of the thing being "saved" |
|---|---|
| Remove the temp B-tree from the hot query | 0.057 ms per search — and it costs D-068's ordering |
| Make the tappable gloss words optional (D-094) | 0.18 ms per word opened |
| Compose stability configuration file | Nothing: all 21 composables already skip |
| `remember` around the pure grouping functions | Microseconds — done anyway, because one line |
| WAL / `synchronous = NORMAL` | Not applicable: the pack is read-only |
| Bigger `page_size` in the pack | Both packs are 4 KB pages with a zero freelist; the covering index already serves the hot path whole |
| Querying packs in parallel instead of sequentially | Same total work; changes latency, not energy |

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
- **The R8 build was measured, not run.** 33.0 → 5.5 MB is a fact about a file. Whether that file
  works is the exact thing O-2 has always said needs a device, and reading class names out of the
  shrunk dex is weaker evidence than launching it.
- **`mmap_size` and `cache_size` are reasoned, not measured.** 8 MB of mmap and 2 MB of page cache
  against a 301 MB pack are guesses that `dumpsys meminfo`'s cache hit/miss counters would settle
  in one session on the watch.
- **Nobody has counted how often the process actually starts.** The whole weight of items 2, 3 and
  5 depends on it, and `dumpsys usagestats` answers it.
- **The R8 release cannot be installed yet**: it comes out unsigned because no keystore is
  configured (D-086), and the human generates that, never the agent.
- **`PackStore.openFile`'s wiring is not covered by the gate.** The pure decision is, and the
  switch is, but that `PackStore` actually consults the memo would need a `Context` *and* a real
  pack — an on-device test.
