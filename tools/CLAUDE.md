# tools

Two areas of Python, **with no third-party dependencies** (stdlib only). That property is
deliberate and is kept: `dependencies = []` in `pyproject.toml`.

- `packbuilder/` — builds the `.db` packs from lexicographic sources.
- `unicode/` — generates the pinned Unicode repertoire shared by the builder and the app.

Plus five loose scripts at the root: `audit_dictionary.py` (the structural audit, which *does*
run in the gate), `devpack.py` (sideloading over adb), `avd_como_el_reloj.py` (the emulator with
the watch's geometry, D-150) and `measure_query_cost.py`.

`measure_query_cost.py` **does not measure battery — it measures the work the battery pays for**:
how many rungs of the cascade run, how many rows they touch, and how much the SQL costs, over a
real pack. It is a **replica** of `SqlitePackSource`'s SQL with its constants copied, so if those
change and this does not, it lies quietly — it says so in its own docstring. See `docs/bateria.md`.

## The environment

Hatch, configured in the root `pyproject.toml`. The gate does not need it — `./gradlew check` runs
plain `python3`, so a clean clone works without installing anything. Hatch is the development
layer.

```sh
hatch run test              # the 521 tests
hatch run audit             # the structural audit
python3 tools/audit_dictionary.py --fix   # rewrites the test counts it finds wrong
hatch run all               # both
hatch run matrix:test       # THE TESTS UNDER EVERY PYTHON VERSION
hatch run lint:check        # ruff, defects only
hatch run lint:fix          # fixes what it can on its own
hatch run format-check      # the formatter, kept separate on purpose (see below)
hatch run build-toy         # regenerates the toy pack
hatch run verify <pack.db>  # invariants of a real pack
hatch run gen-repertoire    # regenerates the repertoire (only runs under Python 3.9)
hatch run push <pack.db>    # installs a pack on the watch over adb
hatch run packs             # which packs are installed
```

⚠️ **`check` is the linter and not the formatter, and that split is measured.** They used to run
together and the result was that neither ran: `ruff format --check` reports **2,359 lines across 28
of 36 files**, because this repo's Python was written by hand in another style and the formatter
never ran. With that much noise the 26 real findings the linter did have — unused variables,
ambiguous names — were invisible. So `check` looks for **defects** and has to be zero; adopting the
formatter is a separate act with its own commit (D-160).

## `devpack.py` is not the installer

`tools/devpack.py` is the development layer of sideloading, the same way Hatch is the development
layer of the gate: it puts a `.db` into `filesDir/packs/` over adb and nothing else. It does not
download, it knows no catalogue, and it knows nothing about D-029. The real installer is blocked on
a product decision — where the catalogue is hosted — and when it exists it will write into that same
directory.

What it solves, and why it is not an `adb push`: the copy is **atomic** (`.part` + `mv`, the same
convention `PackStore.installAtomically` uses) and it is checked with **sha256 on both sides**
before renaming. A half-copied `.db` opens without error and returns fewer words than it holds. See
D-082.

It lives in `tools/` and not in `packbuilder/` because it neither builds nor validates packs: it
talks to the device. Its pure logic — assembling the command plan, picking a device, comparing
hashes — enters the gate through `:tools:pythonTest`; running adb needs a watch and **does not**.

## `packserver.py`: the download path, without deciding where the catalogue lives

The installer was blocked on a product question — *where is the catalogue hosted* — and that also
blocked writing the mechanism. This is the development answer: a static file server over a
directory of packs, stdlib only.

```sh
adb reverse tcp:8765 tcp:8765                                       # the tunnel, first
python3 tools/packserver.py ../wearos-dictionary-data/dist --port 8765
python3 tools/packserver.py ../wearos-dictionary-data --index-only   # just print the index

# ⚠️ **The APK's build consumes the index TOO**, which is why it is worth leaving it written in
# `dist/`. `:app:bundlePacks` reads it to know which `data_version` each core it bundles has and
# copies it to `assets/core-index.tsv` (D-229). Without it, the app cannot decide whether the APK's
# core is newer than the one the user downloaded, and **leaves the user's** -- which is safe, but
# means a core updated from the catalog never updates again.
#
# It gets regenerated after building packs. If it goes stale it does NOT lie: `bundlePacks`
# compares the declared `db_bytes` against the real file and, on a mismatch, declares no version.
python3 tools/packserver.py ../wearos-dictionary-data/dist --index-only \
    > ../wearos-dictionary-data/dist/index.json
```

⚠️ **Start with `adb reverse`, and the reason is measured.** The app's default catalogue URL used
to be `10.0.2.2` — the host alias an emulator normally has — and **it does not work**: verified on
this project's emulator on 2026-09-22, `SocketTimeoutException: failed to connect to /10.0.2.2
(port 8765) from /10.0.2.15 after 8000ms`, while the same server answered 200 to curl from the host
on both loopback and the LAN address.

⚠️ **The cause is not proven, and saying so is part of the record**: macOS's firewall is enabled
(`socketfilterfw --getglobalstate` returns 1) and Python is not in its allow list, which is
*consistent* with the timeout — but the block itself was not measured. What is a fact is that
`10.0.2.2` did not arrive and `adb reverse` did.

`adb reverse tcp:8765 tcp:8765` tunnels the device's own `localhost:8765` to this machine over adb,
which sidesteps the firewall, **needs no IP discovery** (that changes network to network), and
**works identically on a real watch** over wireless debugging, where `10.0.2.2` means nothing. The
server still prints the LAN address for when serving over Wi-Fi is what you want.

**The index is generated by reading the packs, and that is correctness, not convenience.** Every
field comes from the `.db`'s own `meta` or from measuring the file, so it cannot disagree with
what is served. A hand-kept catalogue that claims `data_version` 202609211937 for a file that is
now a different file is precisely the failure this repo cannot observe: the app believes the
version and never downloads again.

### Two fields are in the index for efficiency, not for display

| | |
|---|---|
| `schema_version`, `norm_version` | Published **before the url**. They are the two that reject a pack whole (D-001, D-006), so the watch can discard an incompatible pack **without spending 192 MB to throw it away** |
| `sha256` **and** `db_sha256` | Two hashes: of the `.gz` that travels and of the `.db` that lands. Verification happens at both moments, and `installAtomically` compares the second (D-165) |

⚠️ **`attribution` is deliberately NOT published**: ~600 characters per pack — the Spanish core
cites five sources — and **the pack already carries it**, so the app reads it after installing.
`license` is published because it is short and the screen shows it before downloading.

### Why `.db.gz` as an opaque file, and why the raw `.db` too

Measured: Spanish 73.6 → ~37 MB, English 306.8 → ~192 MB. But with `Content-Encoding: gzip` the
decompression is transparent, so `Range` would apply to the **decompressed** stream: no resume, and
`Content-Length` lies. On a watch that downloads 192 MB only while charging, losing resume costs
more than the bytes saved. So the `.gz` travels as just another file and the app inflates it.

**And the raw `.db` is served alongside, on purpose.** A block-level delta update (zsync) needs
`Range` over bytes that resemble the previous version, and a gzip stream resembles nothing after
the first changed byte. Keeping the raw file costs nothing on a development machine and is what
holds that door open. See `docs/roadmap.md` §Actualización versionada de packs.

### `Range` is implemented here because the stdlib does not have it

`SimpleHTTPRequestHandler` ignores `Range`. Without it, a Wi-Fi drop at 90 % of 192 MB means
starting over — and **D-040 picked `HttpURLConnection` precisely because it does `Range`**, so a
server without it makes that decision unverifiable. The three forms of RFC 9110 §14.1.1 are
handled, and an unsatisfiable range is a **416 and not the whole file**: swallowing it would make a
miscalculated resume look like success.

⚠️ **Three things reading the real output found that the tests did not.** `.OLD` backups sit next
to the good packs in the data directory and publishing them would serve a stale pack as the
catalogue; `attribution` is dead weight; and `es-def-wd` is `schema_version` 3 and therefore has
**no `langs` field at all** — published as-is, because rejecting it cheaply is the whole point.

## Why the version matrix matters most

The builder writes keys that the watch recomputes. That it behaves identically across every Python
is not a convenience: it is the central invariant.

**Measured 2026-09-17:** the tests pass identically under Python 3.9 (Unicode 13.0) and 3.14
(Unicode 16.0), including `abࡰcd` → `ab cd`, which is the exact case that diverged before the
repertoire was pinned. The builder is independent of the Python version.

The exceptions are `gen_repertoire.py` **and `gen_casefold.py`**, which **require Python 3.9.x** because they need exactly
Unicode 13.0.0. The guard is verified: under 3.14 it refuses with exit 1.

## The file to copy

One module: `payload.py` — a docstring that explains the decision and the alternative that was
rejected, short functions, zero global state.

One test: `tests/test_build.py` — it builds real packs in a temporary directory and inspects them
with SQL. It does not mock SQLite.

## The builder is two-pass, and not for fun

The real sources are gigabytes (the Wiktionary JSONL is 1.1 GB) and do not fit in memory. So
`PackBuilder` writes to a staging table inside the file itself, assembles the compression dictionary
from a reservoir sample, and only on the second pass compresses and fills `entry` and `fts_def`.

Indexes are created **at the end**, over already-populated tables. Maintaining them during ingestion
is far slower.

If something fails halfway, the pack is deleted. A half-built pack is worse than none: it opens
without error and returns fewer results than it should.

## Deriving a core pack

`build_core.py` takes a **complete pack that is already built** and keeps the entries whose headword
is among the most-used words of the language.

⚠️ **It derives rather than rebuilding from the dumps, and that is correctness rather than
convenience.** The core declares `subset_of`, and deriving it makes that claim true **by
construction** — built separately it would hold only while both runs used the same sources and the
same filters, a promise nothing checks. It also takes seven seconds instead of an hour.

⚠️ **The vocabulary comes from usage frequency (Tatoeba), never from `rank`, and the gap is
measured**: by `rank` a core takes **91 %** of the Spanish inflection table — the richest pages are
verbs, and a Spanish verb has 33 forms — while by frequency it takes **5.5 %**. See D-175.

## `build_packs.py`: the whole pipeline, and where each thing lives

**The scripts go in the repo; the packs do not.** They are 4.4 GB of dumps and hundreds of MB of
artifacts, and `.gitignore` already covers `/*.db`. What was missing is that **the rebuild's order
lived only in prose** --on this very page-- and there it can neither be run nor checked.

```sh
python3 tools/build_packs.py ../wearos-dictionary-data --dry-run   # the plan, touching nothing
python3 tools/build_packs.py ../wearos-dictionary-data             # and running it
python3 tools/build_packs.py ../wearos-dictionary-data --solo es
```

### Three directories, and the separation is the point

```
<root>/dumps/   the inputs: kaikki's .jsonl files, the corpora, WordNet, dbnary
<root>/build/   the INTERMEDIATES: packs that are an input to a merge and are not distributed
<root>/dist/    what gets published, and the only thing `packserver.py` should serve
```

⚠️ **With everything in one flat directory, `es-def-wd` appeared in the emulator's catalog as a
downloadable pack.** It is not: it is a source the Spanish pack carries **fused inside**.
Publishing it offers a single-source dictionary, which is exactly the model D-215 discarded.

⚠️ **And today `build/` is empty, because that intermediate turned out not to exist.** The plan
built it believing `--sumar` read a pack; `--sumar <pack> <dump>` reads the **dump** --the name
only selects the reader and the attribution (D-146)-- so nobody consumed `build/es-def-wd.db`: 30 s
and 4.5 MB for nothing. The **rebuild** caught it, not the gate, and
`test_nada_se_construye_para_que_NADIE_lo_consuma` now pins it. The directory stays: separating the
intermediate from the publishable is still the rule, and what has no subject today has one
tomorrow.

⚠️ **Full English lives in `dist/` even though it is ALSO an input** --to the bilingual one,
through `--flexiones`--. It is both things, and what decides where it lives is **whether it is
distributed**.

### The order, and why a test pins it

`--flexiones` reads an **already built** pack of the target language, so the bilingual one has to
come after English. ⚠️ **Skipping it raises no error**: the pack comes out well formed, passes
`verify_pack.py` and is **worse in silence** -- 8.6 points of reverse coverage, measured.

That is why `plan()` is returned rather than run: **the plan's shape enters the gate** and running
it needs the dumps, which do not. It is the same split as `devpack.py`.

And every publishable pack **is verified before going on**: chaining onto a half-built pack
propagates the defect, and a half-built pack opens with no error.

### What gets generated, and what does not

The tiers come out of `build_core.py --rango-mb <min> <max>`, deriving from the `full` and never
from another tier --deriving a `core` from a `main` would make `subset_of` point at the
intermediate--. ⚠️ **A language whose `full` already fits whole in `main`'s range generates no
`main`**: full Spanish is 73.6 MB, below the maximum of 150, so it would be a second pack with the
same content. The measured size decides it, not a hand-written list.

⚠️ **It is a range and not a budget, and that is what makes it get met.** `--budget-mb` estimates
the size by scaling the payloads by the **source** pack's ratio, and the derived one has another
--it takes its own lemmas' forms and not the others'--: **asking for 25 MB gave 17.7**, below the
tier's minimum, with no error and with the pack passing every invariant. `--rango-mb` derives,
**measures the file**, corrects the factor and goes round again, up to four times, and **exits with
code 1 if it does not manage it** -- because an exit 0 with an out-of-range artifact tells the
pipeline *this complies*. `--budget-mb` still exists to explore the curve; publishing with it
guarantees nothing (D-220).

## ⚠️ Rebuilding a pack: the flags that are not optional

**A pack built without its flags comes out well-formed, passes `verify_pack.py`, and is quietly
worse.** There is no error to notice, so they are listed here rather than only in
`build_pack.py --help`:

| pack | worse without | what is lost |
|---|---|---|
| `es` | `--frases` · `--tesauro` · `--sumar es-wd` | examples, WordNet synonyms, 5,283 lemmas |
| **`es-en`** | **`--flexiones en-def-wikt.db`** | **the reverse direction**: without it `ran`, `went` and `eaten` do not arrive. ⚠️ **Since D-196 the inflections go to the ENGLISH ENTRY's `form`** --`went` is an inflection of `go`, and `go` is already a lemma-- instead of expanding inside `trans`, which in a bidirectional pack is empty. Skipping the flag cost **8.6 points** of reverse coverage in the top 1,000, measured over the built pack: 97.0 % with it, 89.8 % without it |
| `en` | `--tesauro` | +30,423 entries with synonyms |

⚠️ **And the bilingual one is built AFTER English, not in any order**: `--flexiones` reads an
already built pack, so `en-def-wikt.db` has to exist first. A rebuild's complete order is
**English → Spanish → bilingual → the two cores**.

`--flexiones` takes an **already built pack** of the target language, not a dump: the inflections
are already extracted and pruned there, and going back to the 3.2 GB dump would be another hour of
build plus a second pruning that can diverge from the first -- D-175's same reasoning.

## `--como-la-app`: would the watch accept this pack?

`verify_pack.py` answers *"is this pack well built?"*. That is not the question you have before
sideloading, which is *"if I install this, does it show up?"* — and the two differ, because the
app checks **less** than the builder and rejects on a different set.

```sh
python3 tools/packbuilder/verify_pack.py --como-la-app ../wearos-dictionary-data/dist/*.db
```

It runs **exactly** what `PackFile.open` rejects on, in the same order, and prints the
`PackRejection` the user would read. Several packs at once, because the question is always "do
they *all* pass?". Exit 1 if any would be rejected.

⚠️ **This is the repo's fourth cross-language contract, and the only one born with an enforcer.**
The other three — `norm()`, `sense_code`, the catalogue index — earned theirs after drifting.
`audit_dictionary.py` → `check_rejection_mirror` compares the ids of `MOTIVOS_DE_LA_APP` against
the `PackRejection` enum **including the order**: a reason added in Kotlin and not here makes the
verifier say yes to a pack the app will reject.

⚠️ **The order is part of the contract.** Every check rejects (D-217), so the order does not
decide whether a pack gets in — it decides **which reason is reported**, which is the only line
the user reads. The seven schema-3 packs in the data directory came out as "incomplete metadata"
instead of "another format version" purely from having it backwards.

## `repair_meta.py`: fixing the metadata without re-exporting the content

A full rebuild of the English pack is an hour and needs the dumps. Some defects live entirely in
the `meta` table, and rewriting those rows takes a second.

```sh
python3 tools/packbuilder/repair_meta.py ../wearos-dictionary-data/dist/*.db            # reports
python3 tools/packbuilder/repair_meta.py --write ../wearos-dictionary-data/dist/en-main.db
gzip -9 -kf ../wearos-dictionary-data/dist/en-main.db          # the .gz carries the size
python3 tools/packserver.py ../wearos-dictionary-data/dist --index-only \
    > ../wearos-dictionary-data/dist/index.json                # and so does the catalogue
```

⚠️ **It touches ONLY `meta`, and that boundary is what makes it safe.** A test hashes every
other table before and after and requires them identical, so **no repair can change a search
result**. What it cannot fix is content -- a missing payload channel, a proper noun that slipped
the frequency filter -- and asking it for more would make it a second, worse builder.

⚠️ **It reports by default and writes only with `--write`**, and that is not politeness. The
first dry run against the real packs wanted to rename `Español ↔ English` to
`Español ↔ English (full)`: the bilingual pack DECLARES `tier=full` and its name carries no tier
**on purpose**, because `build_pack` skips the stamping for `kind == bilingual`. The rule had
invented a defect out of a deliberate choice, over an artifact nobody would rebuild to check.

⚠️ **Repairing a pack that travels in the APK also needs `dictionary.versionCode` bumped.** The
app skips the comparison entirely when the installed `versionCode` equals the running one, on the
stated assumption that the same APK carries the same content -- true of every build until this
tool made it possible to change a pack's bytes without touching a line of Kotlin. Measured on the
emulator: a repaired `en-core` under an unchanged versionCode never replaced the old one and
**logged nothing**. The two cores are the bundled packs today.

⚠️ **It refuses when `data_version` would not move.** That field has minute resolution, and its
docstring justifies that with *"a build takes minutes, so two never land on the same one"* -- true
for a build, false for a repair that rewrites six rows in under a second. Two files claiming one
version is how an installer keeps the broken copy, so it fails out loud and tells you to wait.

## The coverage lists: what a pack MUST be able to find

`vectors/cobertura-es.txt` and `cobertura-en.txt`. `verify_pack.py` runs them **on its own**, with
no flag, when the pack declares that language, and fails naming what is missing. A word counts as
found if it is a lemma **or** one of its inflected forms: `fui` reaches `ir`, which is what the
user experiences.

⚠️ **It is the repo's first CONTENT check, and that is why it exists.** The gate compiles, the
invariants pass and `--como-la-app` says yes about an `en-core` that **has not one month of the
year** -- there is nothing structurally broken to look at. The list found it the day it was
written: `dist/en-core.db` gives **0 entries** for `january`…`sunday`.

⚠️ **Adding a word is documenting a product decision**, not fattening a test: it asserts that the
dictionary, without it, is broken. That is why they go grouped and each group says what it
defends.

⚠️ **And what it does NOT solve has to be said**: a hand-written list has the same bias as looking
by hand -- it does not know what nobody thought to put in. It serves against **regressions**, not
against unknown gaps. A fixture pack (fewer entries than the list has words) skips the check.

## Adding a source

It goes in `sources/`, and it hands back `Record` — the builder knows nothing about formats. What
the source has to solve:

- **Pruning.** This is where the pack's size is decided. Keep `word`, `pos`, glosses, forms,
  translations and **the citation of the example** (D-216); drop etymologies, pronunciations and
  categories.

  ⚠️ The citation used to be on the drop list, and measuring is what moved it: **86.5 %** of the
  English dump's examples are quotations lifted from a published text, so an example with no
  attribution reads as a definition that does not add up. It is trimmed to year and author in
  the source reader — **119 bytes become 31** — and the trimming separator is per-language, in
  `Perfil`: the Spanish dump writes its `ref` with periods and the author first, so the English
  rule would produce garbage there.
- **Streaming.** Never load the whole file.
- **Which edition and section it comes from.** The Spanish Wiktionary and the English Wiktionary are
  different datasets: the first gives Spanish glosses, the second English glosses about Spanish
  words.

## Regenerating the Unicode repertoire is a deliberate act

**Two generators, same rule.** `gen_casefold.py` emits the pinned case-folding table that
`payload.fold_gloss` and `PayloadCodec.foldGloss` both read.

⚠️ **It is a table and not a function call for a measured reason**: `toCaseFold()` — the operation
the standard defines for *caseless matching*, rule R4 of §3.13 — exists in Python as
`str.casefold()` and **does not exist in Java or Kotlin**. The only thing that offers it is ICU,
and D-003 forbids the platform's Unicode data because every Android ships its own version. So the
table is pinned, the way `UnicodeRepertoire` already is, and the sha256 ties the two copies.

It also **verifies its own assumption**: the Kotlin side indexes by `Char` to avoid JVM APIs that
D-017 bans in `:dict-core`, which only holds while nothing folds outside the BMP. The generator
aborts if that ever stops being true.

`gen_repertoire.py` aborts if the Python running it does not ship exactly Unicode 13.0.0. That is
not a bug: it pins the repertoire to the **floor** shared by the builder and the oldest Android we
support.

Raising the version requires checking that every platform supports the new floor, reviewing the diff
of `repertoire.txt`, and raising `NORM_VERSION`. Read the generator's header first.

## The mirror obligation

**There are now THREE mirrors, not one**, and the second and third are easy to forget because
neither looks like normalisation:

| Python | Kotlin | what breaks if they diverge |
|---|---|---|
| `normalize.py` | `TextNormalizer.kt` | **a word is missing** from the results |
| `payload.py` → `sense_code` / `fold_gloss` | `PayloadCodec.kt` → `senseCode` / `foldGloss` | **a link to one sense leads to another**, or to none |
| `packserver.py` → `META_FIELDS` and `catalog_entry` | `Catalog.kt` → `Catalog.parse` | **the download screen says "nothing new" forever**: a renamed field leaves the list empty, with no exception and no log |

The third is pinned by a **fixture of the real index**
--`app/src/test/resources/catalog-index-fixture.json`-- which `CatalogTest` parses and verifies
field by field. It is regenerated by hand, and that is deliberate: its being an explicit act is
what makes a rename get noticed. **Verified by mutation**: reading `db_sha` instead of `db_sha256`
fells four tests.

```sh
python3 tools/packserver.py <dir> --index-only > app/src/test/resources/catalog-index-fixture.json
```

⚠️ **The fixture deliberately includes a pack with an OLD schema** (`es-def-wd`, schema 3). That
way the test also checks it gets classified as incompatible, which is what avoids downloading 192
MB to throw them away.

⚠️ **It NO LONGER describes the real directory, and that is deliberate.** It said *"because the
real data directory has it"* and that stopped being true with the 2026-09-22 rebuild: `dist/` has
six packs and **none with schema 3** (checked on 2026-09-23). Regenerating the fixture from today's
directory **would weaken the test** --it would be left without the incompatible case, which is half
of what it watches-- so the fixture stays as it is and becomes a synthetic case. Whoever
regenerates it has to put an old-schema pack back in by hand.

Both fail the same way: with no exception, no log, and the pack passing every invariant. The second
is pinned by an identical vector on both sides -- `sense_code(1, "casa")` = `8ec316909e48`.

⚠️ **And it brought a general lesson for the next mirror**: a regular expression's character class
**is not portable**. `\s` in Python over `str` is **Unicode** and in Java it is **ASCII**, so a
hard space would fold on one side and not the other. In `fold_gloss` whitespace is enumerated by
hand because of that. See `docs/contratos-cruzados.md` §6.

`normalize.py` is the hand-written mirror of `TextNormalizer.kt`. Any file with a mirror declares it
in its header:

```
THIS FILE HAS A MIRROR: <path>
```

`tools/audit_dictionary.py` checks that the declared path exists. That the **contents** match is
checked by the shared vectors, not by the audit.
