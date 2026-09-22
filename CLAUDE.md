# CLAUDE.md

A dictionary for Wear OS where **every search runs with no network, ever**. Languages install as
*packs* of read-only SQLite; downloading one is the only thing that uses the internet. The unusual
keys are computed twice, in two languages, and have to produce the same string** — the Python
builder writes them into the pack, the watch recomputes them in Kotlin over what the user types.

## The central invariant

> `norm(x)` computed by the builder == `norm(x)` computed by the app, for every `x`, on every
> platform and forever.

If it breaks there is no exception and no log: **a word is missing from the results**, and the
report arrives months later with nothing in the stack trace. Everything else —the BINARY
collation, the covering index, the pinned repertoire, `NORM_VERSION`— exists to hold it up.

Enforcer: `vectors/normalization-vectors.tsv`, run by the tests of **both** languages.

## Non-negotiable constraints

Decisions taken, not preferences. Do not propose alternatives unless asked to review them. Each
one has its row in `docs/decisions.md`.

**Normalization and keys**
- Code point classification comes from `UnicodeRepertoire` / `repertoire.py`, **never** from
  `Character.getType` or `unicodedata.category` — every platform ships its own Unicode version and
  14,773 code points differed. Enforcer: `ArchitectureTest` compares both sha256. (D-003)
- NFD and `lowercase()` **are** delegated to the platform: 0 differences over the 133,730 code
  points of the pinned repertoire. (D-004)
- A change to `norm()` or `fuzzy()` touches both languages in the same commit and bumps
  `NORM_VERSION`. Enforcer: `tools/audit_dictionary.py` compares the constants. (D-005, D-006)

**Pack format**
- The pack is immutable and opened read-only. There are no migrations: a different
  `schema_version` is rejected and downloaded again. (D-001)
- `fts_def` is contentless and its `rowid` **is** `entry.id`. If they drift apart, free-text
  search points at the wrong entries. Enforcer: `verify_pack.py`. (D-011)
- `meta.payload_dict_sha256` is verified on open. deflate does **not** detect a wrong preloaded
  dictionary: it decompresses without error and returns corrupt text. (D-008)
- Packs are built with `tools/packbuilder`. The app never parses raw sources. (D-015)

**`:dict-core` portability**
- Every JVM API lives in `PlatformJvm.kt`. No other file in the module imports `java.*` or uses
  `Character.`, `.codePoints()` or `.format()`. Enforcer: `ArchitectureTest`. (D-017)
- KMP was evaluated and **is not adopted**: Wear Compose is Android only. (D-018)

**Wear OS surface**
- Tiles and widgets accept no text input: the search lives inside the app. The glanceable
  surface is for word of the day, recent searches or a shortcut. (D-026)
- **Never** `androidx.glance:glance-wear-tiles` — it is deprecated and will be removed. The naming
  confuses: it is not the Wear Widgets library. (D-025)
- Wear Widgets (Glance + RemoteCompose) is postponed, not discarded: the packages are in alpha and
  only exist on Wear OS 7. (D-024)
- Downloads are deferred to **charging and on Wi-Fi**, per the official Wear OS guidance. (D-029)

## Guardrails that do not get relaxed

- **The shared vectors.** They look like one more test; they are the only mechanism that detects
  that the two implementations of `norm()` drifted apart.
- **The payload dict hash.** It looks redundant because "deflate already fails if something is
  wrong". It does not fail: it is measured to return corrupt text in silence.
- **`verify_pack.py` before publishing a pack.** A half-built pack opens without error and returns
  fewer results than it holds.
- **The on-device check.** It does not exist yet and it is the class of bug this repo cannot see:
  see `docs/contratos-cruzados.md`.

## Files that are never edited by hand

Generated. Editing them creates two sources of truth that diverge in silence.

- `dict-core/src/main/kotlin/cl/fadiaz/dictionary/core/UnicodeRepertoire.kt`
- `tools/unicode/repertoire.txt`
- `tools/packbuilder/vectors/payload-fixture.tsv`
- `local.properties`

Backed by `.claude/settings.json` → `permissions.deny`.

## Commands

```sh
./gradlew check                                                 # THE GATE. Compile, lint and :dict-core:test
./gradlew :dict-core:test                                       # the core only (fast, no emulator)
cd tools/packbuilder && python3 -m unittest discover -s tests    # the builder
python3 tools/packbuilder/build_toy.py                          # regenerate the toy pack
python3 tools/packbuilder/verify_pack.py [--como-la-app] <pack.db>  # invariants; the flag = what the APP checks
python3 tools/unicode/gen_{repertoire,casefold}.py              # regenerate the pinned Unicode tables (a deliberate act)
python3 tools/packbuilder/gen_payload_fixture.py                # regenerate the codec fixture
./gradlew :dict-data:devicePrecheck                             # is there an emulator or a watch? says what is missing
./gradlew :dict-data:connectedDebugAndroidTest                  # THE ON-DEVICE TESTS (see below)
```

The Hatch environment for the Python pipeline is optional and **the gate does not need it**
(D-046): it lives in `tools/CLAUDE.md`, which is the document that owns it.

## Verification

`./gradlew check` runs the full gate: the core's tests, the structural audit, and **the Python
tests** via `:tools:pythonTest`.

After touching `norm()`, `fuzzy()` or the pack format, additionally:
`python3 tools/packbuilder/build_toy.py && python3 tools/packbuilder/verify_pack.py dict-data/src/androidTest/assets/toy-es-en.db`

**A claim needs a measurement**, carrying its date and its environment, and **a retraction is
written everywhere the claim was**. **The test comes first**, and if it was already green, a
targeted mutation proves it bites. **The uncommitted diff is the work**: no `checkout`, `restore`,
`stash`, `reset` or `clean` over files you did not write. The four in full: the `verify` skill.

**The emulator and the watch do not measure the same thing** (D-043): the emulator closes
correctness —normalization, FTS5, query plans— because it ships the ICU and SQLite of its API
level. Performance and battery only count measured on a **physical watch**. See `benchmark`.

**`:dict-data`'s tests are instrumented and the gate does NOT run them** (they need a device).
They are the only ones that close the assumptions about Android. Run them on every supported API
level: the whole point is that ICU versions differ.

## How a session runs

**Open with the brief**, before answering or planning. A line with nothing says `nada`: omitting
it does not distinguish *I looked and it is clean* from *I did not look*. It stays in Spanish
because it is **spoken to the user**, not written into the repo.

```
En movimiento   qué quedó a medias, según el roadmap y el changelog
En el árbol     trabajo sin commitear, en qué branch, y de quién es
Restringe       las decisiones y los números que pesan sobre lo que se pidió
Obsoleto        qué hay que re-chequear antes de creerle
Lo cambia       cómo lo de arriba altera el pedido — una frase
Fricción        items de proceso abiertos que este trabajo va a tocar
```

**Uncommitted work is not yours.** Name it in the report and do not drag it into your own.

**Questions go together and before writing**, capped at three, with a recommendation up front.
Every option is priced **in this repo's units** —MB per million entries, ms at p99, bytes per
row— never in "more complex", and says **what it closes**: that is what nobody reconstructs from
the code a year later. Anything reversible in ten minutes is decided alone, in one line.

**Look at the output, not just the numbers.** A green gate says the code did what it was told, not
that what it was told was right. Here that means opening the pack and **reading real entries**,
not counting rows. See the `pack-workflow` skill.

**Close by giving back what the session learned.** Capturing is unconditional; proposing has a
threshold: a friction goes into the changelog the first time and **up to the roadmap §Proceso y
herramientas the second**, with the arithmetic. Process improvements **are proposed, not
executed**, except the one-line reversible kind. The closing question: *if the next session is
another agent with no memory of this one, what would it have to re-derive?*

## Commits

They are offered when the work is finished, never on your own initiative mid-task — the `commit` skill. They are split
**by dependency, not by size**: every commit has to be green on its own, so the history is
bisectable. Verify it with `git worktree` before assuming it is.

The message says **why**, not which files changed — the diff already says that.

## Logging obligation

Every session writes its entry in `.claude/logs/agent-changelog.md`, at the very top. It exists
because **two parallel sessions cannot see each other** and the conflict shows up at compile time,
or worse, at review time.

The entry also says **what went wrong along the way** and **what was left undone**. A log of
successes is bookkeeping: what warns the next session are the mistakes and the debt.

## Working style

- **Spanish in the conversation, English in the repo.** Identifiers, comments, documents and
  commit messages are English; technical terms stay untranslated either way (gate, covering index,
  payload, rung). The UI strings the user reads are the exception: they belong to the
  localization, not to a translation pass.
- **Trade-offs and concepts before code dumps.** Explain what is gained and what is lost.
- **Clarifying questions before detailed solutions.** A wrong assumption costs more than a
  question.
- **Primary sources.** Official documentation or the artifact repository, not a summary. Mark
  **ASSUMPTION** anything that comes from memory or from a summary.
- **Extend before creating.** A second file doing the job of one that already exists is how a
  codebase forgets what it decided.
- **Architectural integrity wins over the request.** If something breaks a constraint from here,
  say the cost and propose the right path; deviate only with explicit confirmation, and record
  it as ⚠️ Desviación.

## The documents, and which one answers what

| Question | Document |
|---|---|
| Why is this decided this way? Can I change it? | `docs/decisions.md` |
| A word is missing / duplicates show up / the pack will not open | the `troubleshoot-diccionario` skill, `docs/contratos-cruzados.md` |
| What does the `.db` look like inside? Which query do I use? | `docs/formato-pack.md` |
| Where does a new file go? What are the layers? | `docs/architecture.md` |
| What is next? What does what I want to do collide with? | `docs/roadmap.md` |
| How do I measure this? Is it slow? What does it cost in battery? | the `benchmark` skill, `docs/bateria.md`, `docs/roadmap.md` §Optimización |
| Which source should the next pack be built from? Is it worth it? | `docs/fuentes.md` |
| Did we already research this? What does the official source say? | `docs/references.md` |
| What changed and why, in the last few sessions? | `.claude/logs/agent-changelog.md` |
| What is this project? (for an outsider) | `README.md` |
| How is this repo worked with an agent? Where do these rules come from? | `.agents/method/prompt-context.md` |
| Which prompt do I run —evaluate, update, harvest, merge, sync? | `.agents/method/prompt-context.md` §*Which document to run* |
| Is the instruction system healthy? Is the bundle stale? | `.agents/method/prompt-evaluate.md`, the `state-review` skill |
