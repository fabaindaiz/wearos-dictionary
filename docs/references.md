# External information worth knowing

This is not a link list: each entry says what it contributes **to this project**, and when it
contradicts something already decided, it says so.

Rule for maintaining it: something gets in when it **changed or confirmed a decision**, not
because it is well written.

Anything coming from a search summary instead of the primary source is marked **ASSUMPTION**. If
one of those entries is about to hold up a decision, read the source first.

## SQLite and FTS5

- **[SQLite FTS5 Extension](https://sqlite.org/fts5.html)** — *(primary source, read)* The
  reference for the `detail`, `columnsize` and `content` options.

  **What it confirms:** that `content=''` was the right choice, and for the documented reason: not
  keeping a second copy of the text.

  **What it contradicts, still unresolved:** `columnsize=0` implies that *"all queries must be
  full-text"* and `SELECT COUNT(*)` **errors out** — that would break the `verify_pack.py` check
  that counts `fts_def` rows. And `detail=none`/`detail=column` remove **phrase queries**, which
  is exactly the shape of query #5 documented in `formato-pack.md`.

  **Not applied:** both options shrink the index, and with definitions as the main content that
  index is a large fraction of the pack. It is a real trade-off between size and search
  capability.

  Produced: D-011. Opened two open decisions.

## Wear OS

- **[Wear OS development principles](https://developer.android.com/training/wearables/principles)**
  — *(primary source, read)*

  **What it confirms:** WorkManager for long tasks, and offline-first design.

  **What it corrects:** the official text is *"Defer downloads until the watch is charging **and**
  connected to Wi-Fi"*. The original plan asked for an unmetered network and enough space, **but
  did not require charging**.

  **What it does NOT say, and was asserted anyway:** the page gives no storage limit and no
  download-size limit. Any number about free space on watches is **ASSUMPTION** until a source
  turns up.

  Produced: D-029.

- **Tiles API: [overview](https://developer.android.com/training/wearables/tiles),
  [periodic updates](https://developer.android.com/training/wearables/tiles/update) and the
  `tiles-1.6.2-sources.jar` / `protolayout-1.4.2-sources.jar`** — *(primary source, read — the
  page and the artifact's javadoc)*

  **What it confirms:** that a tile must not open a pack. It is not a performance hunch —asserting
  one without measuring would be forbidden (D-042)— but the contract: `onTileRequest` is annotated
  `@MainThread` and *"must complete after at most 10 seconds"*. The prose repeats it: *"Don't
  fetch content frequently or start long-running asynchronous work in your tile service"*, and it
  recommends *"cache or store the results in local storage"*.

  **What it corrects about intuition, and changed the design:** `setFreshnessIntervalMillis` is
  **not wall clock**. Verbatim: *"how many milliseconds of **elapsed time (not wall clock
  time)**"*, plus *"inexact"* and throttled. Asking it for 24 h for a "word of the day" would make
  it drift a few minutes per day. What *is* wall clock is `TimelineBuilders.TimeInterval`: *"in
  milliseconds since the Unix epoch"*. Hence D-107.

  **What to know before touching the manifest:** `METADATA_GROUP_KEY`
  (`androidx.wear.tiles.GROUP`) — *"tile providers in the same group represent the same tile on
  the device"*, and the default is the class's full name. Declaring it would fuse two tiles into
  one. It only applies on **API 37+**, which is the project watch's level.

  **Also verified in the artifact:** `freshnessIntervalMillis = 0` means *"that auto-refreshes
  should not be used"*, and `ActionBuilders.launchAction(ComponentName, Map<String, AndroidExtra>)`
  exists with `AndroidLongExtra`, so an `entryId` travels typed.

  **What could NOT be verified:** whether the real renderer honours a `Timeline` of seven entries
  without truncating it. The javadoc documents no limit. **ASSUMPTION** until seen on the watch.

  Produced: D-106, D-107, D-108, D-109.

- **`androidx.wear.tiles:tiles-testing:1.6.2`** — *(resolved and measured, not adopted)*

  It exists, and **it drags in Robolectric 4.16.1**. Putting a new runner into a gate that runs in
  seconds is a much bigger change than what it buys, so it was discarded: the tiles' decision is
  tested on the JVM (`TileContentTest`) and that the layout builds, on a device (`TilesTest`),
  with a real Context and no new dependency.

- **`android:allowBackup` deprecated since Android 12** — **ASSUMPTION**, from a summary citing
  the official behaviour-changes documentation.

  **What it corrects:** with `minSdk 33`, `fullBackupContent` **does not apply** — it is the
  mechanism for Android 11 and below. Only `android:dataExtractionRules` counts, with separate
  `<cloud-backup>` and `<device-transfer>` rules. The project's own lint already reports it.

  Produced: D-030.

- **[Compose performance on Wear OS](https://developer.android.com/training/wearables/compose/performance)**
  — *(primary source, read)*

  **What it establishes:** *"Start with the most effective performance tool types: baseline
  profiles (including startup profiles) and the R8 code optimizer."* And the underlying reason:
  *"many Wear OS devices have limited CPU and GPU resources compared to larger mobile devices"*.

  **What it directly contradicts:** `app/build.gradle.kts` has **R8 disabled**
  (`optimization { enable = false }`), inherited from the template. The official guide names it as
  one of the two main levers and we have it switched off without ever deciding to.

  **What it corrects about our plan:** *"Run all final performance tests on a suite of physical
  Wear OS devices"*. The emulator is for correctness, not for closing performance questions.

  **Not applied:** startup profiles. The page warns they increase the APK's size, and we already
  add ~1–1.5 MB per ABI of native SQLite.

  Produced: D-042, D-043. Opened two open decisions (R8, startup profile).

- **[Conserve power and battery on Wear OS](https://developer.android.com/training/wearables/apps/power)**
  — *(primary source, read)*

  **What it confirms:** that deferring downloads until the watch is charging is not a precaution
  but the guidance (D-029). Network access is classified *very high impact*, above turning the
  screen on.

  **What it contradicts:** *"Disable automatic refresh, or increase the refresh rate to 2 hours or
  longer"* for tiles and complications. The manifest today has `UPDATE_PERIOD_SECONDS = 3600` —
  one hour, half the recommended minimum. That also comes from the template.

  **What it corrects about intuition:** the cost is not where you look for it. For this app the
  real order is network, then screen, and only then CPU — and our CPU work lasts milliseconds.

  **Not applied:** *"Batch any related operations, to maximize the time that your app's process is
  idle"*. Relevant for the pack installer once it exists.

  Produced: confirms D-029; opened the refresh-period decision.

- **Battery Historian is unmaintained** — **ASSUMPTION**, from a summary citing its own
  documentation, which recommends *"system tracing, the Macrobenchmark power metric, or the Power
  Profiler"*.

  **Why this entry exists:** nearly every third-party guide on Android battery starts with Battery
  Historian. Without this note, every future session will rediscover and propose it.

  Produced: D-044.

## Lexicographic data

- **[kaikki.org / wiktextract](https://kaikki.org/eswiktionary/index.html)** — *(primary source,
  read)* Wiktionary processed into JSONL, by edition and by language section.

  **What it confirms:** do not parse raw sources in the app, and prune in the builder.

  **What it corrects:** for definitions **in Spanish** the source is the **Wikcionario**
  (eswiktionary), not the English Wiktionary — they are different datasets. On top of that,
  [the raw-data page](https://kaikki.org/eswiktionary/rawdata.html) declares that format
  **deprecated** and points to the per-language processed pages; the original plan pointed at the
  deprecated artifact.

  **The numbers that matter:** eswiktionary, Español section, **1,036,458 senses**. enwiktionary,
  English section, **1,787,236 senses**. The Wikcionario covers English with only 35,021 senses,
  so Spanish definitions of English words is not a source that exists.

  Produced: D-034. Left D-028's budget unsupported.

- **The alternative sources, evaluated and rejected** — *(a full sweep, with local measurement over
  the dumps and the packs)*

  **Why this entry exists:** the request was *"compare different sources of English and Spanish
  definition dictionaries"*, with two symptoms — Spanish feels incomplete, English brings too many
  proper nouns. **The conclusion was that no alternative source improves on what is there, and
  that both symptoms were the same pruning problem.** Without this written down, the next session
  downloads 1 GB again to get here.

  ⚠️ **A correction, and it is the most valuable entry on this page.** The first pass compared
  **totals** —118,458 headwords from enwiktionary §Spanish against our 113,889— and concluded
  *"zero coverage gain"*. **That is a fallacy**: two sets of the same size need not overlap.
  Measured properly, by downloading the dataset and crossing by `norm()`:

  | | headwords |
  |---|---|
  | Only in the Wikcionario | 57,852 |
  | In both sources | **46,326** (only 44 %) |
  | **Only in enwiktionary §Spanish** | **56,741** |
  | Union | **160,919** (+54 % over what is there) |

  So **the coverage gain exists and is large**. What rules it out is something else: **its glosses
  are not definitions, they are translations into English**. A real sample of what would be gained
  — *entretecho* → "loft; attic; garret", *triturador* → "shredder", *chilero* → "cool, terrific",
  *regresor* → "regressor". They are **legitimate Spanish words** (and there are 806 marked as
  Mexican, 756 as Salvadoran), but putting them into the monolingual pack would place English
  glosses in a Spanish dictionary, against D-034. **It is not a worse source: it is another
  product** — an es-en bilingual pack, which the schema already contemplates (`kind`, the `trans`
  table).

  **What IS usable without breaking anything: the usage examples.** Of the 46,326 shared
  headwords, **38,306 have no example in the Wikcionario and 5,307 of those do have one in
  enwiktionary** — and they are **in Spanish**, with the English translation in a separate
  `english` field that is ignored: *"Lo acordaron por unanimidad."*, *"Reían y lloraban al mismo
  tiempo."* It would raise shared headwords with an example from **17.3 % to 28.8 %**. It is still
  pending because the risk is not the license (both are CC BY-SA 4.0) but **which sense the
  example attaches to**: crossing by headword and not by sense puts "Lo acordaron por unanimidad"
  on the wrong sense of *acordar*, which is incorrect content that looks correct.

  **Spanish coverage was never the problem.** A probe of 70 common, technical and Chilean words
  (*pololear*, *cachai*, *flaite*, *marraqueta*, *luca*, *carrete*, *guagua*, *teletrabajo*,
  *algoritmo*) gives **70/70 present as a direct entry**; English gives 57/57. Spanish has 6.5×
  fewer entries because **the English Wiktionary project is 6.5× larger**, not because it lacks
  usable vocabulary. What felt wrong was the noise: **39.6 % of the entries defined nothing** in
  fewer than 25 characters.

  | Source | License | Verdict |
  |---|---|---|
  | **enwiktionary §Spanish** (kaikki, 1.04 GB) | CC BY-SA | **Not as a replacement and not as a merge.** It would bring **56,741 new headwords** (+54 %), but with **English glosses**: that is a bilingual pack, not an improvement to the monolingual one (D-034). Its **Spanish examples** are usable and remain pending (D-122). |
  | **RAE / DLE** | closed | **No.** Already rejected in D-031. See its entry below. |
  | **[Spanish WordNet / MCR 3.0](https://adimen.ehu.eus/web/MCR)** | CC BY 3.0 | **No.** Smaller than what is there and its Spanish glosses are sparse or inherited from the English WordNet. |
  | **[DBnary](http://kaiko.getalp.org/about-dbnary/)** (Ontolex RDF) | CC BY-SA 3.0 | **No.** It is the **same Wiktionary** with another extractor: zero new content and a format more expensive to parse than JSONL. |
  | **Wikidata Lexemes** | CC0 | **No.** Sparse sense glosses, no critical mass. |
  | **[GCIDE 0.54](https://gcide.gnu.org.ua/) / Webster 1913** | GPL | **No.** ~130,000 English headwords **from 1913**. A wrist dictionary cannot define *house* with a Victorian entry. |
  | **[Open English WordNet 2025](https://en-word.net/)** | **CC BY 4.0** | **The only real alternative.** 120,068 synsets / 153,261 entries, published 2025-12-31. Proper nouns are **out by design**, in *Open English Namenet* / the `2025+` edition. WN-LMF XML, 10.8 MB compressed. Measured as a spike and not adopted (D-120). |

  **What it confirms, and this is what is worth most:** that OEWN 2025 moved proper nouns out into
  a separate resource is the domain's reference source reaching the same conclusion as D-116.

  Produced: D-116, D-117, D-118, D-122.

- **[Dictionary formats — GoldenDict](https://xiaoyifang.github.io/goldendict-ng/dictformats/)**
  — **ASSUMPTION**, from a summary. dictzip, StarDict, slob.

  **What it confirms:** compressing the definitions is mandatory, not an optimisation.

  **What we do differently, with nothing backing it:** the domain compresses in **50–64 kB
  blocks** with an offset index, degrading compression "by less than 10%". We compress **per row**
  with a preloaded dictionary. The only measurement that exists is a 9.5% figure over a
  hand-made toy sample, which does not hold up a decision at 2.8 million senses.

  Produced: nothing yet. It is an open decision.

- **RAE / DLE** — **ASSUMPTION**, from a summary.

  **What it establishes:** the DLE has no public API and no open license; the APIs circulating
  scrape the site.

  **Why this entry exists:** so that when somebody proposes "let's take the definitions from the
  DLE", the answer is already written down. **Before acting on this, read the terms of use
  directly.**

  Produced: D-031.

## Kotlin Multiplatform

- **[Compose Multiplatform and watchOS](https://slack-chats.kotlinlang.org/t/13151865/are-there-plans-for-compose-to-target-watchos-apple-watch)**
  — **ASSUMPTION**, from a summary.

  **What it establishes:** Wear Compose is Android only; Compose Multiplatform does not target
  watchOS. It is the fact that decides KMP buys nothing today: the UI is not shared with any
  second destination.

  Produced: D-018.

- **[ktecma262](https://github.com/mgilbir/ktecma262)** — Unicode normalization in pure Kotlin
  with its own tables.

  **What it confirms:** its own documentation says that *"java.text.Normalizer is JVM only and
  Kotlin/Native has nothing, so multiplatform code has been comparing sequences that look
  identical and are not"*. **Reading that is what led to finding the D-003 bug in this repo**,
  which had nothing to do with KMP.

  **Not applied:** if `:dict-core` goes to KMP, it is the replacement for NFD.

  Produced: indirectly, D-003.

- **[KFlate](https://github.com/rafambn/KFlate)** — deflate in pure Kotlin.

  **What it confirms:** **it supports a preloaded dictionary**, which is what our payload uses. It
  was the dependency at highest risk of not existing for KMP.

  **Not applied:** replacement for `java.util.zip` if the module goes to KMP.

- **[androidx.sqlite](https://developer.android.com/kotlin/multiplatform/sqlite)** —
  `BundledSQLiteDriver`.

  **What it confirms:** that bundling our own SQLite is the supported path. Verified by running
  it: SQLite 3.50.1 with `ENABLE_FTS5`, `THREADSAFE=2`, `MAX_ATTACHED=10`, no ICU.

  `THREADSAFE=2` is multi-thread, not serialized: **every connection needs its own
  single-threaded dispatcher**. It was not a precaution, it is a requirement.

  Produced: D-002.

## Kotlin

- **Explicit API mode (`explicitApi()`)** — **ASSUMPTION**, from a summary citing kotlinlang.org.

  **Not applied:** `:dict-core` is a library module whose public surface **is** the contract with
  the rest of the app, and today nothing stops something becoming public by accident. It would
  promote that rule from rung 1 to rung 3 with one line in the build.

## What to read before touching each thing

| If you are going to touch… | Read | And beware of |
|---|---|---|
| `norm()` or `fuzzy()` | `docs/contratos-cruzados.md` §1 and §2 | Touching only one language. Bumping Unicode without thinking |
| The pack schema | `docs/formato-pack.md`, FTS5 above | `columnsize=0` breaks `verify_pack`; `detail=none` kills phrases |
| The payload codec | `contratos-cruzados.md` §3 | deflate does **not** warn you if the dictionary is wrong |
| The compression | the GoldenDict entry | There is no measurement backing per-row vs blocks |
| The data sources | the kaikki entry | Wikcionario ≠ English Wiktionary. CC BY-SA requires attribution |
| Anything Wear OS | `app/CLAUDE.md` | `glance-wear-tiles` is deprecated and comes up first in searches |
| Performance or startup | the Wear OS performance page | R8 is off today; the emulator does not measure performance |
| Battery | the Wear OS power page | Battery Historian is unmaintained; the cost is in the network, not the CPU |
| Downloads | the Wear OS principles | "charging **and** Wi-Fi", not just Wi-Fi |

- **[sqldiff: Database Difference Utility](https://www.sqlite.org/sqldiff.html)** — *(primary
  source, read 2026-09-21)* The official SQLite tool that emits the SQL (or a binary changeset with
  `--changeset`) to turn one database into another.
  **What it confirms:** FTS5 *is* supported, but **only with `--vtab`**; without it sqldiff
  compares the shadow tables and the page warns that running the result on a slightly different
  database **can corrupt the virtual table**. It also does not diff triggers or views, and it is
  "forgiving with respect to differing column definitions" — it will not warn about an
  incompatibility.
  **What it rules out for us, and this is the finding:** a changeset **mutates** the target, so the
  patched file is not byte-identical to any published artifact. SQLite writes are not
  byte-deterministic — page allocation and the freelist depend on history — so **the published
  `sha256` would no longer match**, which is the entire verification model of D-165. Content-level
  verification would have to replace it.

- **[zsync](http://zsync.moria.org.uk/)** — *(primary source, read 2026-09-21)* rsync's algorithm
  moved to the client, over plain HTTP.
  **What it confirms:** **no special server is needed** — an HTTP/1.1 server plus a precomputed
  `.zsync` metafile of block checksums, and the client fetches only the changed blocks. That fits
  `tools/packserver.py` (static files + `Range`) and D-040 exactly, and unlike sqldiff it yields a
  **byte-identical** result, so the published hash keeps working.
  **What it corrects:** the current release **removed** look-inside-compressed-file support (it
  needed a patched zlib); that lives on in 0.6.4 only. So delta updates want the **raw `.db`**, not
  the `.gz` — which is why the server publishes both.
