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
hatch run test              # the 328 tests
hatch run audit             # the structural audit
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

## Why the version matrix matters most

The builder writes keys that the watch recomputes. That it behaves identically across every Python
is not a convenience: it is the central invariant.

**Measured 2026-09-17:** the tests pass identically under Python 3.9 (Unicode 13.0) and 3.14
(Unicode 16.0), including `abࡰcd` → `ab cd`, which is the exact case that diverged before the
repertoire was pinned. The builder is independent of the Python version.

The one exception is `gen_repertoire.py`, which **requires Python 3.9.x** because it needs exactly
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

## Adding a source

It goes in `sources/`, and it hands back `Record` — the builder knows nothing about formats. What
the source has to solve:

- **Pruning.** This is where the pack's size is decided. Keep `word`, `pos`, glosses, forms and
  translations; drop etymologies, pronunciations, categories and citations.
- **Streaming.** Never load the whole file.
- **Which edition and section it comes from.** The Spanish Wiktionary and the English Wiktionary are
  different datasets: the first gives Spanish glosses, the second English glosses about Spanish
  words.

## Regenerating the Unicode repertoire is a deliberate act

`gen_repertoire.py` aborts if the Python running it does not ship exactly Unicode 13.0.0. That is
not a bug: it pins the repertoire to the **floor** shared by the builder and the oldest Android we
support.

Raising the version requires checking that every platform supports the new floor, reviewing the diff
of `repertoire.txt`, and raising `NORM_VERSION`. Read the generator's header first.

## The mirror obligation

`normalize.py` is the hand-written mirror of `TextNormalizer.kt`. Any file with a mirror declares it
in its header:

```
ESTE ARCHIVO TIENE UN ESPEJO: <path>
```

`tools/audit_dictionary.py` checks that the declared path exists. That the **contents** match is
checked by the shared vectors, not by the audit.
