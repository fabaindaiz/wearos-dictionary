# Architecture

## The constraints that rule

| Constraint | Design consequence |
|---|---|
| The watch's disk is scarce | One pack per language, compressed payloads, a soft budget of 50 MB (D-028) |
| ~1.2" screen, search while typing | Covering index: the list comes out of the index without touching the table (D-012) |
| Input by voice or a tiny keyboard | The search tolerates errors instead of demanding exactness (D-027) |
| Limited RAM and battery | Nothing is loaded into memory; read-only SQLite with mmap |

> **ASSUMPTION about disk space.** The official Wear OS principles say storage is scarce but
> **give no number at all**. Any concrete figure about free GB on watches is unsourced. What
> *is* measurable is the pack's size, and that is what gets chased.

## Modules

```
:app            Wear Compose screens, ViewModel, where packs come from   ✔
:dict-data      opens packs, implements the queries                      ✔
:dict-core      pure Kotlin: normalization, keys, payload                 ✔
tools/          Python builder + Unicode repertoire                       ✔  (outside Gradle)

The glanceable surface is two dictionary tiles -- last words and word of the
day -- and the template's complication was turned off (D-106 to D-109).
```

**Allowed direction:** `:app` → `:dict-data` → `:dict-core`. Never the other way.

`:app` depends on `:dict-data`, which depends on `:dict-core`, which depends on nobody. **It is
checked by `audit_dictionary.py` → `check_module_direction`**, which looks at the build
declarations and not at the imports: `:app` and `:dict-data` share the package name
`cl.fadiaz.dictionary.data`, so an import does not say which module it came from.

What breaks if the direction is inverted is not cosmetic: `:dict-core` is the one tested in
milliseconds without an emulator and the one mirrored against the builder (D-005). A dependency
pointing upward ties it to Android and those tests stop being able to run.

`:dict-core` depends on neither Android nor SQLite, and that is not tidiness: it is what has to
stay in sync with the builder and what gets tested most. Its tests run in milliseconds without an
emulator.

## Where a new file goes

| If the file… | Goes in | And also |
|---|---|---|
| Knows nothing of Android, SQLite, network or paths | `dict-core/` | If it touches a JVM API, it goes in `PlatformJvm.kt` or it does not go |
| Opens packs or runs SQL | `dict-data/` | Its tests are **instrumented**: they are the only ones that close assumptions about Android |
| Is a screen, a Tile or a Complication | `app/` | Read `app/CLAUDE.md` first: there are three known traps |
| Builds or validates packs | `tools/packbuilder/` | Python stdlib only. A new source goes in `sources/` |
| Generates data both languages consume | `tools/unicode/` | It has to emit **both** copies and tie them together by sha256 |
| Talks to the device over `adb` | `tools/` | Its pure logic enters the gate; running `adb` does not. See `devpack.py` |

## The life of a pack

```
lexical source (Wiktionary, JSONL)
        │  tools/packbuilder/sources/*.py   ← pruning: size is decided here
        ▼
   Record, streamed
        │  PackBuilder, two passes over staging
        ▼
   pack.db  ──► verify_pack.py ──► catalogue + sha256 ──► download to the watch
        │                                                  (charging + Wi-Fi)
        ▼
   filesDir/packs/<id>.db   ← read-only, one connection per pack,
                              confined to a single-threaded dispatcher
```

That last detail is not optional: the bundled SQLite reports `THREADSAFE=2`, which is
multi-thread and **not** serialized.

## The layers of the search

`DictionarySource` is the only surface through which the app reaches the data. Neither the UI nor
the ViewModels see SQL. That interface lives in `:dict-core` and deliberately never mentions
SQLite: it allows changing the storage without touching anything above, and testing with
in-memory implementations.

The search is not one query but five in a cascade, each more expensive and less reliable than the
last. The detail is in `docs/formato-pack.md`.

## Deliberate deviations from what the framework recommends

| Usual recommendation | What we do | Why |
|---|---|---|
| Room for anything that is SQLite | Room **only** for app data; the packs through `BundledSQLiteDriver` directly | `createFromFile()` copies the file and duplicates tens of MB (D-039) |
| The system's SQLite is enough | We bundle our own SQLite, ~1–1.5 MB per ABI | FTS5 is not guaranteed on Android (D-002) |
| Hilt/Dagger for injection | A manual container | At this scale Hilt adds KSP and build time with no benefit |
| JSON or Protobuf for structured data | Delimited text in the payload | It parses with no dependencies in both languages; compressed, the difference is noise (D-009) |
| Kotlin up to date | Kotlin 2.2.10, not 2.4.20 | `allWarningsAsErrors` turns a compiler bump into a broken build (D-033) |

## What you changed → what moves, in the same change

A change is not done until the documents it falsified are true again. The cheapest moment to fix
that sentence is while you still know which one it is. The test for this step is not *"did I write
documentation?"*, it is **"is there any sentence in the repo that my change just made false?"**.

| You touched | What moves, in the same change |
|---|---|
| `norm()` or `fuzzy()` | **both** implementations, `NORM_VERSION` in both, `vectors/normalization-vectors.tsv`, D-005 and D-006 |
| The Unicode repertoire | `gen_repertoire.py`, both generated copies, their sha256, `NORM_VERSION`, and **every pack gets rebuilt** |
| The pack schema | `schema_version`, `docs/formato-pack.md`, `verify_pack.py`, `PackFile.open()`, and the toy pack |
| A query or an index | `docs/formato-pack.md` §queries, the `EXPLAIN QUERY PLAN` in `verify_pack.py`, and D-012/D-013 if the reason changed |
| The payload codec | `payload.py`, `PayloadCodec.kt`, `gen_payload_fixture.py`, `payload_codec` in `meta`, D-008/D-009 |
| A rule, or the answer to a closed question | `docs/decisions.md`: a new row, **with the Enforced in column filled** |
| A number some document asserts | the document that **owns** that number, with the new measurement next to it |
| A module, a layer, a public name | `docs/architecture.md` and every map that names it |
| A command or a Gradle task | `CLAUDE.md` §Commands, the `<area>/CLAUDE.md` that cites it, and the skills — it is what goes stale fastest |
| Something the roadmap planned | the **status** of that entry, and what is still missing |
| Anything at all | the changelog, including what went wrong along the way |
