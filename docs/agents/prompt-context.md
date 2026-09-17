# Method context — the shared reference

```yaml
# method-header — the only machine-readable part of this file. Keep it first.
# Identical in every document of the set; they travel together.
method:    claude-code-repo-method
set:       [context, evaluate, bootstrap, update]
lineage:   m-7c41a9/main        # opaque id of the line this copy descends from
ancestry:  [m-7c41a9]           # root -> current; a fork appends its own new id
version:   7                    # monotone within a lineage; only its owner bumps it
forked_at: null                 # {lineage, version} this line branched from, or null
digest:    "dee484b4cc29"            # content fingerprint; see Keeping the set versioned
released:  2026-09-17
upstream:  ""                   # where this copy pulls updates from; "" = it is a root
adopted:   2026-09-17           # date this repository took the method
adapted:                        # local renamings and substitutions, one line each
  - "prose in Spanish; technical terms stay in English, untranslated"
  - "decisions log is docs/decisions.md, in Spanish, with an Enforced-in column"
  - "troubleshooting layer is split in two: the skill troubleshoot-diccionario and docs/contratos-cruzados.md"
  - "the one gate command is ./gradlew check, which includes :tools:pythonTest"
  - "structural audit script is tools/audit_dictionary.py"
  - "skills are named and written in Spanish, with trigger-shaped descriptions"
  - "pack format reference lives in docs/formato-pack.md, owned by the packbuilder"
declined:                       # deltas deliberately not taken, with the reason
  - "v7 Three agents, one source: this repo is single-agent on purpose — no .cursor/, no
    .github/copilot-instructions.md, no AGENTS.md. Its asymmetry is already the policy here:
    the core invariant is held by a hook, the shared vectors and the gate, not by prose in
    an agent's file. Reopen the day a second agent's rule file appears in the tree."
  - "v3 Workspaces: one repository. Nothing to do until there is a second one; reopen then."
```

> ## This file is not a prompt
>
> **It is a library.** There is nothing here to paste and nothing to execute. It
> holds what more than one of the executable prompts needs, so each of them can
> stay about its own job: the method header and how copies are matched, the
> enforcement ladder, the nineteen principles, the engineering standards, the
> artifacts and the guarantees behind them, how to adopt into a repository that
> already works, the platform's own mechanics, workspaces, model tiers, and how
> the method itself improves and travels.
>
> If you arrived here looking for something to run, you want
> **`prompt-evaluate.md`** — it is the front door, it is read-only, and its
> report says which of the others you need.

---

## The set

| Document | Job | Executable? | Writes |
|---|---|---|---|
| `prompt-context.md` | the shared reference the other three read from | **no — library** | nothing |
| `prompt-evaluate.md` | assess a repository's AI instruction system | yes | one Markdown report |
| `prompt-bootstrap.md` | build the instruction system into a repository | yes | the instruction system |
| `prompt-update.md` | bring an older copy up to the current release | yes | edits, after approval |

All four carry the identical method header above. **Same `ancestry` and
`version` everywhere means you hold a matched set**; if they disagree, someone
mixed releases, and the oldest is the one to trust least.

**The set can grow.** A later release may add a fifth document. Nothing should
hard-code "three prompts" or "four prompts": read the `set` field, and list what
is actually present next to this file. `prompt-evaluate.md` is required to do
exactly that.

**These documents are written in English and stay in English**, whatever
language the repository they are pointed at documents itself in. Two practical
reasons: the method is matched and merged across repositories that do not share
a language, and technical identifiers are English everywhere anyway. A
repository whose prose is Spanish, Japanese or German keeps its prose. Reports
and conversations follow the reader.

**They carry no project, product, person, customer or organisation.** Every
example is stated by its mechanism, never by where it came from. Keep it that
way when you edit them: these files get forwarded.

---

## Using the set (for the human)

1. Drop **all four documents** into the target repository, in `docs/agents/`.
   **If that repo already has copies, do not overwrite them** — put the new ones
   at a scratch path and use `prompt-update.md` instead. The old header is the
   only record of what that repo already adapted and already turned down.
2. Open Claude Code at the repository root.
3. **Run `prompt-evaluate.md` first.** It reads, it writes one report, and it
   changes nothing — so it is safe on any repository, including one you did not
   write. Its report tells you which of the other two documents you need, and
   sometimes the answer is neither.
4. Then paste the invocation the report points at — each executable prompt
   carries its copy-paste block **at the top of its own file**: bootstrap for a
   repository that has not been prepared, update for one that has an older copy,
   and the working invocation in `prompt-bootstrap.md` for every session
   afterwards. *Which document to run* below covers the awkward cases, including
   a repo that already has its own conventions and one that has the files but
   not the work.
5. Answer Claude's Phase 1 report and Phase 2 research with corrections. **This
   is where your judgement enters and it is the part that cannot be automated:**
   you know which of the repo's conventions are decisions and which are
   accidents. Claude cannot tell those apart from the outside, and it is
   instructed to assume they are decisions until you say otherwise.
6. Approve the Phase 3 proposal. Nothing is written before that. In a repository
   that already works, **read the guarantee → existing-file table carefully**:
   that table is where a helpful adoption turns into an unwanted rewrite, and
   it is easier to say "leave that alone" now than to revert it later.

Expect the first pass to take a long session and to produce roughly: a root
`CLAUDE.md` under 200 lines, one nested `CLAUDE.md` per natural area, two to
four skills, a permissions file, a decisions log, a references register, a
roadmap, a changelog, and — the part most people skip — **a script that checks
the structural rules the prose claims.**

7. From then on, work through **The session loop** in `prompt-bootstrap.md`. The bootstrap is the cheap
   half; the loop is what the files were for. If you only ever read one section
   of the set again, read that one — it is in `prompt-bootstrap.md`.
8. Keep the method itself moving. Sessions produce learnings at step 8; the ones
   that pass the generality test become a new **Method version** here, and the
   **update invocation** in `prompt-update.md` carries them into your other
   repositories. That is the
   part that turns a good process in one repo into a good process everywhere,
   and it is the only part that compounds across projects rather than within
   one.

**On the length of these documents.** The method argues for a 200-line budget
and is itself long. There is no contradiction, and the distinction is load-bearing:
`CLAUDE.md` is **context loaded into every session** and paid for every time,
while this is a **procedure read on demand, in parts**. Budget what is always
loaded; be generous with what is fetched deliberately. Apply the same split in
the repo you are bootstrapping.

The mitigation is structural rather than editorial: **every invocation names the
sections it needs**, and the navigation table above is the index. No job here
requires the whole file, and nothing in it is written to be read in sequence.

---

---

## Why this exists

An agent is fast, tireless, and arrives with no memory of yesterday. That
combination has six failure modes, and everything below is aimed at them:

1. **It re-derives.** Every session re-discovers the same constraints, and
   re-opens the same settled questions, because nothing wrote them down in a
   form that survives a new context window.
2. **It breaks what it cannot see.** A rule that exists only in prose gets
   broken silently. The agent is not careless; it simply had no way to check.
3. **It parallelises into collision.** Sessions are cheap to run side by side.
   Two of them working the same feature will not see each other, and the merge
   is discovered at compile time — or worse, at review time.
4. **It stops at green.** A passing gate says the code did what it was told. It
   does not say that what it was told was right, and an agent that never looks
   at what it produced will ship something that satisfies every assertion and is
   obviously wrong to the first human who sees it.
5. **It mis-sorts the decisions.** Either it guesses on the two choices that
   were genuinely the human's and builds the wrong thing confidently, or it
   escalates everything and hands the work back. Both look like diligence.
6. **It accepts friction as given.** It will run the same nine-step manual
   ritual forty times without once proposing to automate it, because each
   instance is individually cheap and no single session is the one where the
   cost is obvious. Tirelessness, which is a strength everywhere else, is
   exactly what hides this.

The first three are solved by the artifacts. The last three are solved by how a
session is *run* — see **The session loop** in `prompt-bootstrap.md`, which is
the half of the method that applies forever after the bootstrap is over.

The system below is not documentation for humans that an agent happens to read.
It is a **control surface**: the set of files that decide what the agent treats
as settled, what it must verify, and what it must refuse to do.

---

---

## The enforcement ladder

This is the single most transferable idea in the method. Every rule in a
repository sits on one of five rungs:

| Rung | Form | What it costs | What it is worth |
|---|---|---|---|
| 0 | An unwritten habit | nothing | nothing — it dies with the session |
| 1 | Prose in `CLAUDE.md` | a line | the agent *usually* honours it |
| 2 | Prose **plus a named enforcer** ("checked in `X`") | a line and a column | the agent knows where to look and reviewers know what to run |
| 3 | An automated check in the gate | a script | it cannot be broken silently |
| 4 | Impossible by construction | a type, a schema, a format | it cannot be *expressed* |

**Push every invariant as far up as it goes cheaply, and write down which rung
it is on.** A rule left on rung 1 is a wish. A rule on rung 4 needs no
discipline at all.

The arc of a mature rule usually runs 1 → 3 → 4, and the jumps are worth naming
in the decisions log when they happen. Examples of the same rule at different
rungs:

| Rule | Rung 1 (prose) | Rung 3 (checked) | Rung 4 (unrepresentable) |
|---|---|---|---|
| Layering | "the domain must not import the web layer" | `import-linter` (Python), `dependency-cruiser` (TS), ArchUnit (Java), `depguard` (Go), a custom tree-walking script | separate packages/crates that cannot see each other |
| Config shape | "every service needs a `timeout`" | a JSON Schema run in CI | a parsed config type with no optional `timeout` |
| Money | "never use floats for money" | a lint rule banning `float` in the billing package | a `Money` newtype with no float constructor |
| IDs | "do not pass a user id where an order id goes" | a naming convention and review | branded/newtype ids (`UserId`, `OrderId`) |
| Migrations | "always write a down migration" | a CI check that every up has a down | a migration framework that will not accept one without |
| Depth in a 2D scene | "a sprite's bottom edge decides its depth" | a validator walking the scene tree | a scene **format** in which a piece names a *plane*, never a speed — so a higher baseline travelling faster cannot be written down |

That last row is from the worked example at the end, and it is the cleanest
instance of rung 4: the check did not get better, **the thing being checked
stopped being expressible.**

---

---

## The principles

Nineteen. Each one states what it prevents, because a principle whose failure
mode you cannot picture will not survive contact with a deadline. The first
thirteen are about the repository; 14 to 17 are about the session and the
process it runs in; 18 and 19 are about how code is written and how the method
enters a repository that already has its own way of doing things.

### 1. Every rule names its consequence and its enforcer

Not "use X". Write: **what breaks if you do not, and where it is caught.**

> **Example.** `get_time_since_last_mix()` is clamped to 100 ms — unclamped it reports the
> time since the audio context started (measured at 17.86 s after a wait in the
> menu), which starts the show halfway through the song. Enforced in
> a named constant on the clock, cited by the rule.

Compare with "clamp the mix-ahead value", which any later session will read as
advice and drop when it is inconvenient.

**Prevents:** rules being negotiated away by an agent that cannot see the cost.
**Test:** for every rule in your root file, can you name the file, script or
command that would catch a violation? If not, it is on rung 1 — either promote
it or admit it is guidance.

### 2. Rules are checked, not remembered

Anything verifiable by reading the tree should be verified by a script that runs
in the gate. Write it early, and expect it to fail on day one.

> **Example.** When the worked example's structural audit was first
> written, **seven of its seven rules were being violated somewhere** — every one of them documented, every
> one believed to be held.

Structural rules worth a script in almost any repo:

- **Dependency direction** between layers or packages.
- **Naming**: file name ↔ exported symbol, test file ↔ subject, migration
  ordering.
- **Uniqueness**: two definitions of the same public name (the most expensive
  failure in the worked example's history — two classes with one name,
  the runtime resolved one, and callers of the other failed with errors that
  named neither).
- **Orphans**: dead exports, signals/events with no listener, files nothing
  imports, generated artefacts with no source.
- **The document map itself**: every doc referenced from `CLAUDE.md` exists.
  A dead pointer is worse than no pointer.
- **Anything the packaging step can drop**: files not in `MANIFEST.in` /
  `files` / `include`, assets outside the bundler's graph.

Per stack: `ruff`/`import-linter`/`pytest` plugins (Python), ESLint
`no-restricted-imports` + `dependency-cruiser` (TS), ArchUnit (Java/Kotlin),
NetArchTest (C#), `golangci-lint` with `depguard` (Go), `cargo deny` + clippy
(Rust), custom tree-walking script (always available, and often the fastest way
to express a rule specific to your repo).

**Prevents:** documented architecture that nobody can tell has already eroded.

### 3. Make the bad state unrepresentable before you validate it

When a rule keeps being broken, ask whether the **format** can be changed so it
cannot be written. Validation is rung 3; unrepresentability is rung 4.

Concrete moves, by stack:

- Newtypes / branded types for ids, money, units, untrusted strings.
- Parse, don't validate: a `Config` type with no optional fields, built once at
  the boundary.
- Sum types / tagged unions instead of a boolean pair whose four combinations
  include two meaningless ones. *(One project folded two related booleans into a three-state enum for exactly
  that reason: of the four combinations, only three meant anything.)*
- Schemas on data files, run in the gate **and** wired to the editor so the red
  underline appears while typing.
- Database constraints instead of application-level checks.
- A data format that names a *category* (a plane, a tier, a role) rather than a
  *number*, so a contradiction between category and number cannot be authored.

**Prevents:** a class of bug recurring after each fix, with a new validator each
time.

### 4. An invariant survives a file move

In the root file, name **classes, constants, commands and contracts** — not line
numbers and not paths. `Settings.VERSION migrates step by step` is an invariant;
`see src/settings.gd:412` is a bookmark that will be wrong within a week.

Area files (`<area>/CLAUDE.md`) may cite paths freely; that is what they are
for, and they live next to what they describe.

**Prevents:** an instruction file that quietly becomes fiction after a refactor.

### 5. Each fact lives once, and the root file is invariants plus a map

The root `CLAUDE.md` holds what must hold **always**. Everything else points.
The map is a table of *question → document*, because that is how a mid-task
agent actually searches.

> **Example.** One root file went **817 → 209 lines** when the measured detail
> moved to the document that owned each number. Nothing was lost; it stopped being
> paid for on every request.

**Prevents:** a root file whose middle nobody reads, and two copies of a rule
that will drift.

### 6. Measure before claiming, and record what the measurement killed

A number belongs in the document that owns it, together with how it was
obtained. And when a measurement **kills** a belief, that is the most valuable
entry you will ever write.

> Measuring draw calls here killed two claims at once: a comment in the code
> (*"the whole field still batches into a single draw"*) and the prior
> theoretical analysis, which had counted depth bands across the whole world
> instead of the visible ones. Actual cost of the interleaving: **about a third
> more batches, not an order of magnitude** — so the depth rule stayed.

Also record checks you decided **not** to write, with the number that decided
it: *"a check for unassigned exports would report 23 cases and almost all are
legitimate; a check that cries wolf 23 times for one real hit gets switched
off."* That is a decision, and without the number someone will propose it again
every quarter.

Tools, by stack: `hyperfine`, `pytest-benchmark`, `criterion` (Rust), `go test
-bench`, `k6`/`wrk`/`vegeta` for load, `EXPLAIN ANALYZE` for queries,
`source-map-explorer`/`bundlephobia` for bundle weight, flame graphs, and a
throwaway probe in the code — normal, and deleted afterwards.

**Prevents:** architecture argued from vibes, and the same optimisation being
proposed forever.

### 7. Name the class of bug this repo cannot see — then check it before it ships

Every project has one: a category of defect invisible where development happens.
Find it in Phase 1 and give it a **pre-ship** check, because "we will notice" is
false there.

> **Example.** An exported array property was silently emptied by the build. The
> editor showed the label; the device showed a raw index. Nobody could see it,
> because nobody on the development side could run the shipped artefact.

The same shape, elsewhere:

| Repo | The bug you cannot see | The pre-ship check |
|---|---|---|
| Python package | data files missing from the wheel; works from the source tree | install the built wheel into a clean venv and run the smoke test |
| JS/TS app | tree-shaken side effect, env var absent in the container, CSS purged | test the **built** bundle, not the dev server |
| Mobile app | release-only ProGuard/R8 stripping, permissions denied | run the release build on a device farm |
| Backend service | races, timeouts, connection limits — everything that needs concurrency | load/soak test in CI, chaos on staging, replay real payloads |
| Data pipeline | anything that only appears at full data size or with real nulls | run on a production sample with schema assertions |
| ML | non-determinism, train/serve skew | fixed-seed determinism test + a serving parity test |
| Embedded | timing, memory, peripheral behaviour | hardware-in-the-loop, static stack analysis |
| Library/SDK | breaking a downstream API by accident | public-API snapshot diff, semver check, compile a real consumer |
| Infra/IaC | drift, and anything that only fails on apply | `plan` in CI, policy checks, ephemeral environment apply |

**Prevents:** the only bug class that reaches users with no warning.

### 8. External knowledge is registered by what it changed

Reading the field's literature is part of the work. Collecting links is not.
**An entry earns its place when it changed or confirmed a decision.**

Every entry must say: what it confirms in what you already do, **what you do
differently on purpose and why**, and what it suggests that you have *not*
applied yet. Point at the decisions it produced.

> **Example.** An engine's official audio-sync recipe has three terms. One
> project uses two — deliberately — because the third returns nothing usable on
> the platform it ships to, and its reference entry says so. Without that note,
> every future session "discovers" the official recipe and proposes the bug
> back.

That last sentence is the whole value: **the register is what stops a fixed
problem from being reintroduced by a well-read agent.**

Close the file with a triage table: *if you are about to touch X, read Y, and
watch out for Z.*

**Prevents:** re-litigating solved problems, and cargo-culting a recipe written
for a different platform.

### 9. The roadmap is a collision map, not a promise

Every planned item states: what it is, **which rule it collides with**, what
already exists in its favour, and **what must be decided before any code is
written**. Order and dates are optional; collisions are not.

Add two sections that are usually missing:

- **Closed by measurement** — ideas retired by a number, so they stay retired.
- **What each idea costs the core invariant** — a table. In this repo: *"does
  this break 'everything is a pure function of song time'?"* with a yes/no and a
  sentence per item. Two entries are honest `yes`; one is a `no — the reverse:
  it turns the impure thing back into data.`

**Prevents:** a roadmap that is a wish list, and a feature discovering the
architectural conflict halfway through implementation.

### 10. Record the non-decisions and the deliberate duplication

The log is not only for what you did. Write down what you **chose not to do**,
or the next session will do it, sincerely, as a cleanup.

> **Example.** Four small binary searches are duplicated **on purpose**: the three
> return values encode different semantics, the collections have different
> shapes, and — decisively — the seek-purity test would not catch an off-by-one
> in a merged version, because it would affect both directions equally.

> **Example.** There is no shared base class for the settings rows, and it is
> not a backlog item: single inheritance plus two different parent widgets make it impossible
> without wrapping the row in a container, which is exactly the thing it must
> not be. Residual duplication: three identical lines per file.

**Prevents:** helpful refactors that undo a considered decision, and the same
"cleanup" being proposed every few months.

### 11. The changelog exists because parallel sessions cannot see each other

One entry per change, newest first: **what, which areas, why, architecture
impact** (✅ complies · ⚠️ deviation · REVIEW), and the measurement if there was
one.

> Two sessions worked the same feature here without knowing. It left duplicate
> functions and the project did not compile until they were merged by hand.

This is worth more with an agent than with a team, because agents do not
overhear each other in a corridor.

**Prevents:** silent concurrent divergence, and changes whose reasoning is lost
the moment the session ends.

### 12. Architectural integrity overrides the request; extend before creating

If asked for a shortcut that breaks the architecture: **state the cost, propose
the correct path, and deviate only on explicit confirmation.** When confirmed,
log it as ⚠️ Deviation rather than pretending it complies.

And prefer extending an existing module to adding a new one. New files are the
exception; a second file doing a first file's job is how a codebase forgets what
it decided.

**Prevents:** an agent that is agreeable rather than useful.

### 13. Write for the reader who arrives mid-task

Every session begins in the middle. Rules are imperative, short, and state their
consequence. Point at an exemplary file to copy instead of describing a pattern
in prose. Assume the reader has no history and no time — because that is
literally true.

**Prevents:** instructions that only make sense to whoever wrote them.

### 14. Look at the output, not only at the numbers

A green gate says the code did what it was told. It does not say that what it
was told was right. For anything whose output a human perceives — a rendered
frame, a generated document, a CLI's stdout, an error message, a chart, an email
— **produce it and look at it** before claiming it works.

> **Example.** A menu feature passed every assertion it had: the right rows, the
> right labels, the state surviving a round trip to disk. Rendering the screen
> and looking at the PNG killed two defects in one pass. A card outside the
> selection was dimmed with alpha, which let the scenery behind it through and
> made the song's own name unreadable over one of the backdrops. And an
> empty-list message inherited the project's default font size and came out
> bigger than the title — a branch that had existed, untested, long before the
> feature, because no build had ever reached it.

Two rules fall out of that, and both generalise:

- **A rare branch that your change makes reachable is now yours.** It has never
  been seen. Exercise it deliberately, once, and look.
- **Distinguish silences that look identical.** "Empty because you have not
  filled it in" and "empty because the data is missing" are the same blank
  screen and completely different bugs. If your output has two silences, say
  which one it is in words.

What "look at it" means per domain: render and open the image; run the CLI and
read the output; print the generated file; hit the endpoint and read the JSON;
open the built page; read the log line as an on-call engineer would at 3am.

**Prevents:** the defect class that every automated check passes and every human
sees immediately.

### 15. Ask the few decisions that are the human's; decide the rest

An agent that asks nothing guesses wrong on the choices that were not its to
make. An agent that asks everything has moved the work back to the human and
charged them for the privilege. The line is not subtle:

- **Theirs** when different answers produce **materially different work** —
  where something lives, who owns it, what the product does, what gets
  foreclosed.
- **Yours** when there is a conventional default, when the repo already answered
  it somewhere, or when the choice is reversible in ten minutes. Pick, say which
  you picked in one line, and keep going.

The protocol:

- **Ask before writing, and ask them together.** A question that arrives in the
  middle of the work has already been answered by the code.
- **Every option states its cost in the repo's own units.** Vertical units of a
  screen, lines of a budgeted file, p99 milliseconds, bundle kilobytes, draw
  calls, rows scanned. Never "more complex".
- **Lead with a recommendation.** Four options and no opinion is not neutrality;
  it is the work, undone.
- **Say what each option forecloses**, because that is the part the human cannot
  reconstruct from the code later.
- **Cap it at three.** If you have seven questions you have not finished
  thinking. Answer four of them yourself.

> **Example.** Adding playlists to a 180-unit-tall phone screen came down to
> three questions: whose the lists are, where the selector goes, and how one is
> edited. The second was presented as three layouts priced in the units that
> actually bind — *"a row of tabs costs half of one of the three cards that fit
> in the list"* — and the answer took one word. Everything else in the feature
> was decided without asking.

**Prevents:** confident work on the wrong product, and a human doing the
agent's thinking.

### 16. A change is not done until the documents it falsified are true again

Phase 7 audits the documents once. **This is the per-change version, and it is
an obligation, not a tidy-up.** The moment a change lands, some sentence
somewhere became false; the cheapest time to fix it is while you still know
which sentence.

Write down, once, the map of *what changed → what must move*. It is short, it is
repo-specific, and without it the decay is invisible until a session acts on a
document that has been wrong for a month. See the table in **The session loop**.

**Prevents:** the slowest and most expensive rot in an agent-assisted repo — a
document that is still read, still believed, and no longer true.

### 17. The process is part of the system; improve it incrementally, and price it

The repository's **way of working** — its commands, its rituals, its manual
steps, its documents — is under the same rules as its code. It has friction, the
friction is measurable, and it is almost always the cheapest thing in the
project to improve. **Evaluating that is part of the work, not a favour.**

The reason it gets missed is failure mode 6: each instance of friction is
individually trivial. Nobody notices the thirty seconds. The arithmetic is what
makes the case, and it is always the same shape:

> **cost saved per occurrence × how often it occurs × how many sessions will
> live with it** — against **what it costs to fix, once.**

> **Example.** A gate that fails on formatting while the tree holds unsaved work
> forces a five-step manual dance — copy to a scratch directory, format the
> copy, diff, apply by hand, re-run. Thirty seconds. Hit twice in one session,
> it is a minute; hit by every session that touches a file, for a year, it is
> the clearest possible case for a `fmt-check`-shaped command that writes
> nothing. The fix is one line in the task runner. The thirty seconds is why
> nobody wrote it.

**Three disciplines, and the third is the one that keeps this from becoming a
menace:**

1. **Record friction where you hit it, fix it when it repeats.** A single
   annoyance is noise; the *second* occurrence is data. Write the first one in
   the session's changelog entry, and on the second promote it to the roadmap's
   process area (artifact 8) with the arithmetic above. A threshold is what
   stops every small irritation becoming a refactor.
2. **One improvement at a time, and it is its own change.** A process
   improvement never rides along inside a feature. It gets its own commit, its
   own entry, and its own measurement — otherwise a revert of the feature takes
   the improvement with it, and nobody can tell which change moved the number.
3. **Propose; do not perform.** Rewriting the workflow mid-task is the most
   expensive kind of helpfulness: the human loses the mental model they were
   using, in the middle of using it. State the friction, the arithmetic and the
   fix, and let them schedule it. The exception is the one-line, reversible,
   obviously-correct fix — take it, and say you did in one sentence.

**What counts as process, so the search has somewhere to look:** commands that
should exist and do not; steps always run together that should be one; a check
that runs too late to be useful; a document that gets read for one line that
belongs in the root file; a question asked of the human that the repo could have
answered; the same manual verification done by hand every time; a rule still on
rung 1 that a ten-line script would move to rung 3; anything you had to work out
twice in one session.

That last one is the highest-yield signal in the list. **If you re-derived
something inside a single session, the repo failed to tell you**, and that is a
defect in the instruction system with a known fix.

**Prevents:** a workflow that is never as good as the code it produces, and an
agent that is tireless in exactly the way that hides recurring waste.

### 18. The test is written first, because an agent that writes it second writes the wrong one

Test-first is good practice for a human team. **For an agent it is close to
load-bearing**, and for one specific reason that is worth stating plainly:

> An agent that writes the code first, then the test, will write **the test that
> the code passes.** It has just spent its attention deciding what the code
> does, and that is precisely the wrong frame for deciding what it *should* do.
> The bugs get encoded as expected behaviour, with confident assertions and a
> green run, and now they are protected by a test.

Test-after with an agent does not merely fail to catch the defect — it
**ratifies** it, and makes the defect harder to remove later because something
now depends on it. That is a different and worse failure than having no test.

So: **the expected behaviour is written down before the implementation exists**,
in whatever form the work admits — a failing unit test, a golden file, a schema,
a property, a recorded frame, an example in the docstring that the doc runner
executes. The form varies; the ordering does not.

The loop, and what each step means when an agent runs it:

1. **Red.** Write the assertion and **watch it fail for the reason you expect.**
   A test that passes before the code exists is testing nothing, and this is the
   single most common defect in agent-written tests. If you cannot make it fail,
   you have not understood the requirement yet.
2. **Green.** The smallest change that makes it pass. Resist the urge to write
   the rest of the feature — the rest of the feature is the next test.
3. **Refactor.** Now, with the assertion holding. This is the only moment in the
   cycle where an agent's appetite for rewriting is safe, because the test is
   what makes it safe.

**Where test-first genuinely does not fit**, and what replaces it — say which one
you are in, because "this case is exceptional" is otherwise how the discipline
dies:

| Case | What replaces the failing test |
|---|---|
| Exploratory spike, where the question is *what is even possible* | a throwaway probe, deleted, and then the real work begins at red |
| Visual and spatial output | the rendered artefact, looked at (principle 14), plus a golden-image comparison once it is right |
| A one-off migration or script that runs once | a dry-run against real data, and a review of the diff it would make |
| Legacy code with no seam to test against | a characterisation test of current behaviour **first**, written to pass, and labelled as what it is |

Note the last row: that is the one legitimate test-after, and it is legitimate
precisely because its job is to record what the code does rather than what it
should. Label those; never let them be mistaken for specifications.

**Prevents:** a suite that certifies the implementation's bugs, and the specific
agent failure of writing a green test around code it just wrote.

### 19. Adopt into the repository that exists; the host's shapes win, the method's guarantees do not bend

A repository that already works has conventions, and most of them are decisions
rather than accidents — you cannot tell which from the outside, and **the
assumption that they are accidents is the most expensive mistake this method can
make.** Nothing here is worth a repo-wide reorganisation, and a method that
arrives by renaming everything will be reverted, deservedly.

The split that makes adoption workable:

- **The method specifies guarantees** — that settled decisions live somewhere
  with their enforcer named; that friction is ranked against features by the
  same person in the same sitting; that there is one command that runs
  everything.
- **The host repository specifies shapes** — what the file is called, where it
  sits, what format it is in, what language its prose is in, how its sections
  are ordered.

When the two meet, **the shape wins and the guarantee is added into it.** A repo
with a `PLANNING.md` that already serves as a roadmap does not get a
`docs/roadmap.md`; it gets the missing *sections* inside `PLANNING.md`, and the
mapping is written into the method header's `adapted` list so the next update
does not propose the rename again.

Three rules that keep this from becoming drift:

1. **Map before you write.** Produce the guarantee → existing-file table
   (*Adopting into a repository that already works*) and show it before creating
   any file. A new file is only created for a guarantee that has **no** home.
2. **One artifact per session, never a big-bang.** Adoption is incremental by
   construction. A session that reorganises four things has made itself
   unreviewable.
3. **Record every substitution in the header.** An adaptation that is not
   written down will be re-proposed by the next update, and re-declined, forever.

And the direction that is easy to forget: **the host repo usually knows
something the method does not.** A convention you would not have chosen, which
has survived three years of that team's work, is evidence. Ask what it is
protecting before you touch it, and if the answer is good, it is a candidate for
the harvest (*Improving the method*).

**Prevents:** a method adopted by force and reverted within a month, and the
loss of conventions that were load-bearing for reasons the method could not see.

---

---

## Engineering standards

The principles say what the repository must guarantee. This says what the code
itself must look like. **These are the defaults the method assumes**; a host
repository that has already decided differently keeps its decision (principle
19), and the substitution goes in the header's `adapted` list.

Every standard here is stated as *what it prevents*, and every one of them is
something an agent gets wrong in a characteristic way.

### Tests

Principle 18 has the ordering. These are the properties of a test worth keeping.

- **A test asserts behaviour, not implementation.** If renaming a private helper
  breaks it, it is a change detector, not a test — it will fail on every honest
  refactor and be deleted in frustration, taking its real coverage with it.
- **One reason to fail per test.** When it goes red, the name should be enough
  to know what broke. Agent-written tests drift toward asserting nine things,
  because nine assertions look thorough; they are one test that can fail for
  nine reasons and tells you none of them.
- **The name is the specification.** `test_retries_then_gives_up_after_three`
  beats `test_retry_2`. It is read in the failure output, at speed, by someone
  who did not write it.
- **Arrange–act–assert, visibly.** Three blocks, in order. The shape is worth
  more than it looks: it makes a missing assertion obvious.
- **No sleeps, no real clock, no live network, no shared mutable fixtures.**
  Every one of these produces a suite that fails sometimes, and a suite that
  fails sometimes is a suite that gets re-run rather than read. Inject the clock;
  fake the boundary; freeze the seed and **record the seed in the failure
  message.**
- **Test the boundaries and the awful cases**, not the happy path twice: empty,
  one, many, the maximum, the negative, the duplicate, the out-of-order, the
  unicode, the timezone. Agents over-test the middle of the range and under-test
  its ends.
- **A bug fix starts with the test that reproduces it.** Always, and the test
  keeps the bug's identifier in its name. This is the cheapest regression guard
  in existence and the one most often skipped in the relief of having found it.
- **Coverage is a smoke detector, not a goal.** Use it to find what is untested,
  never as a number to reach. A repo that chases a percentage grows tests that
  execute code without asserting anything about it.

**The pyramid, per repo:** many fast unit tests, fewer integration tests at the
real boundaries, a very small number of end-to-end tests, and — above all of
them — **the core invariant's own test** (principle 7 and the domain table). That
last one is not part of the pyramid; it is the thing the pyramid is holding up.

### Types

- **Turn the strictness as high as the repo can currently hold, and record the
  level.** Not higher — a setting that produces two hundred errors gets disabled
  within a week. Raise it in steps and make each step stick.
- **Types at the boundaries, always**: every public function, every module edge,
  every deserialisation point. The interior can be inferred; the edges are the
  contract.
- **Parse, don't validate** (principle 3). A value that has crossed the boundary
  is a type that cannot be wrong, not a dict that has been checked.
- **`Any` and its equivalents are a decision, and decisions get a comment.**
  Untyped escapes spread silently; one with a reason beside it does not.
- **A type is documentation that cannot go stale**, which is why it outranks a
  docstring that says the same thing. Prefer the type; keep the docstring for
  the *why*.
- **Never widen a type to make an error go away.** That is the error winning,
  with the evidence removed. Narrow the value or fix the caller.

### Documentation, in the code

- **Comment the why; the what is the code's job.** `// increment i` is noise;
  `// the API returns 1-based pages and 0 means "no more"` is irreplaceable.
- **The reason travels with the number.** A constant whose comment records the
  measurement that produced it defends itself at the exact moment someone is
  about to change it — which no document elsewhere can do.
- **Every public symbol says what it is for**, in one line, including what it
  does *not* do. The negative half is usually the useful half.
- **A docstring's example is a test when the language lets it be** (doctest,
  `///` examples in Rust, executable notebooks). Wire those into the gate, and
  the documentation stops being able to lie.
- **Match the density of the file you are in.** A repo that comments heavily and
  a repo that comments sparsely are both coherent; a file that switches halfway
  through is not.

### Evaluation — how you know it works

Four layers, and they answer different questions. A repo needs all four; most
have one and believe they have four.

| Layer | Answers | Example |
|---|---|---|
| The gate | did we break a rule we already knew about? | format, lint, types, tests, the structural audit |
| The invariant test | does the one thing that must always be true still hold? | replay, round trip, seek-and-compare, clean-install smoke |
| The pre-ship check | does it work **in the artefact we actually deliver**? | the built wheel, the release bundle, the container (principle 7) |
| Looking at it | is the thing we produced any good? | render it, run it, read the output (principle 14) |

**For repositories that ship model behaviour, add a fifth: evals.** They are
tests whose subject is non-deterministic, and they follow the same discipline
with two additions — a **frozen dataset** with the cases that have burned you,
and a **recorded baseline** so a change is measured as a delta rather than
argued about. Treat a dropped eval score exactly as a red test: it blocks.

### The domain's own standards

Every domain has practices that are not general software engineering and that an
agent will not infer. **Find them in Phase 2 and write them into the root file**,
because they are the ones a well-read generalist agent will confidently violate.

| Domain | The standards that are not optional there |
|---|---|
| Payments / ledger | double-entry, idempotency keys, integer minor units, an audit trail that is append-only, reconciliation against the provider |
| Health / regulated | data minimisation, retention windows, an access log that is itself auditable, validated changes with a record of who approved |
| Security-adjacent | no secret in a log or an error, constant-time comparison for anything secret, dependency and supply-chain checks in the gate |
| Data platform | idempotent backfills, late-arriving data, schema contracts at the edge, a documented lineage for every table |
| ML / AI | fixed seeds, train/serve feature parity, dataset and model versioning, evals before deploy, a documented failure mode |
| Realtime / media | a clock that is the single source of time, bounded allocation on the hot path, no work that can block the frame |
| Embedded | bounded memory, worst-case timing, no dynamic allocation after init, a watchdog story |
| Libraries / SDKs | semver honoured literally, a deprecation path, the public surface diffed in CI, a real consumer compiled |
| Accessibility-facing UI | keyboard reachable, contrast measured not eyeballed, focus order, motion that can be turned off |

Two per-domain questions worth asking in every bootstrap, whatever the list
says: **what is regulated or contractual here**, and **what does this domain
consider negligent** — because that is the vocabulary the rules should be
written in.

---

---

## The artifacts

Ten files. Build them in this order; each one assumes the ones above it.

```
CLAUDE.md                        invariants + the map          < 200 lines, every session
<area>/CLAUDE.md                 local depth                   on demand, when reading there
.claude/skills/<name>/SKILL.md   procedures                    on demand, by description
.claude/settings.json            permissions, env, hooks       always active
.claude/logs/agent-changelog.md  what changed and why          written every session
docs/decisions.md                every decision + enforcer     read when a rule is questioned
docs/references.md               external research, annotated  read before touching that area
docs/roadmap.md                  collisions and progress       read before starting something new
docs/architecture.md             layout, layers, where a new file goes
tools/audit_<repo>.py            the structural rules, executable
README.md                        what this is, for a newcomer
.editorconfig                    one block per language, matching the linter
```

### 1. Root `CLAUDE.md` — under 200 lines

The only file loaded on every request. Sections, in this order:

```markdown
# CLAUDE.md
One paragraph: what this project is, who it is for, the one thing that is unusual about it.

## Non-negotiable constraints
Settled decisions, not preferences. Grouped by subject. Each bullet:
the rule, one clause of consequence, and what enforces it.
Do not propose alternatives unless asked to revisit them.

## Guardrails that are NOT relaxed
The handful that survive pressure: diagnostics that are infrastructure rather than
features, the pre-ship check for the invisible bug class, anything load-bearing that
looks removable.

## Files you should not hand-edit
Generated artefacts, lockfiles, vendored code. Back it with `permissions.deny`.

## Commands
Real, copied from the task runner. Five to eight lines with a comment each.
Name the gate explicitly.

## Verification
What the gate runs, what to run after touching the risky area, and the rule that a
claim needs a measurement.

## Committing
When commits are offered, how they are split, and what bar they must pass.

## Logging obligation
The changelog, and the reason it exists.

## Working style
Explain trade-offs. Ask before structural changes. Extend before creating.
Language conventions for code and for docs.

## The documents, and which one answers what
| Question | Document |
A table. Questions in the words someone would actually ask.
```

**What does not go here:** any number that has an owner document, anything the
formatter already enforces, anything you cannot name an enforcer for, and every
kind of area detail. If a section is growing, that is the signal that it has
become a document.

### 2. `<area>/CLAUDE.md`

One per natural area, and **only where the area genuinely has rules of its
own** — `src/`, `tests/`, `infra/`, `migrations/`, `scenes/`, `packages/<x>/`.
Contents: internal structure, the patterns used there, **the exemplary file to
copy**, the mistakes that have already been made there. No commands, no repeated
invariants.

Nested files load when Claude reads a file in that directory. There is no
unconditional glob attachment — so anything that must hold *always* belongs in
the root file, not here.

### 3. Skills — `.claude/skills/<name>/SKILL.md`

~~~markdown
---
name: verify
description: Run this project's gate and report honestly what passed. Use before any
  deploy, after touching <the risky area>, and whenever asked to "check", "validate",
  "run the gate" or "is this ready to ship".
allowed-tools: Bash, Read
---

# Verify
Why this matters here, in two lines.

## The gate
```bash
<the real command>
```

## If <the risky thing> changed
<the extra commands, and what a failure means>

## Reporting
Say what passed and what did not, with the output. Never call something verified
that was not run. Name the known-failing item so it is not presented as new.
~~~

`description` is the **trigger**, not a title: write the phrasings that should
activate it. Four skills earn their place in most repos:

| Skill | Fires when | Core content |
|---|---|---|
| `verify` | before deploy, after touching the risky area | the gate, the extra checks, honest reporting |
| `workflow` | starting any change | locate the layer, reuse before adding, run the gate, log it |
| `troubleshoot-<domain>` | a symptom is reported | the real debugging tools, the failures already hit, WIP vs bug |
| `commit` | work is finished | split by spec, the bar each commit must pass |

Build `troubleshoot-<domain>` from **where this repo's failure knowledge
actually lives** (principle: Phase 1, item 7) — often not the git history.

### 4. `.claude/settings.json`

```json
{
  "$schema": "https://json.schemastore.org/claude-code-settings.json",
  "permissions": {
    "allow": ["Bash(<read-only repo commands>)"],
    "deny": ["Edit(**/*.<generated>)", "Write(<secrets or vendored paths>)"]
  }
}
```

`allow` the repo's own read-only commands so the agent stops asking. `deny`
secrets, generated files and anything destructive — **a rule that can be
enforced by permissions should not be left to judgement.** Add `hooks` for
lifecycle rules that must run deterministically.

Commit this file; keep `settings.local.json` out of git. The `.claude/`
directory is how the repo explains itself — it belongs to the team.

### 5. `.claude/logs/agent-changelog.md`

```markdown
## YYYY-MM-DD — <one-line title>
**What.** What changed, concretely.
**Areas.** Files or folders.
**Why.** The reason, including the request that prompted it.
**Architecture.** ✅ Complies · ⚠️ Deviation · REVIEW — and why.
**What went wrong on the way.** What the first attempt got wrong, and what
caught it. Omit only if nothing did.
**What was left undone.** Debt this change created or walked past, named, so the
next session does not rediscover it as a surprise.
**Measured.** The number, if a claim was made.
```

Newest on top. The header of the file states the obligation and the incident
that motivated it.

**The last three fields are the ones that pay for the file.** A log of successes
is bookkeeping; a log that says *"alpha made the card transparent and only the
rendered frame showed it"* or *"this file is now one function away from its line
budget and splitting it is a structural call I did not make"* is the only
mechanism by which one session warns another. Write the failures in the same
voice as the successes — an entry that hides a wrong turn will send the next
session down it.

### 6. `docs/decisions.md` — the index of everything settled

ADR-lite, as tables grouped by subject. **Four columns, and the fourth is the
one that matters.**

```markdown
| # | Decision | Why | Enforced in |
|---|---|---|---|
| D-001 | <the decision, imperative> | <the cost of the alternative, with the number> | <script, class, constant, config — or "—" for a decision with no enforcer> |
```

Rules for it:

- Numbers are **never reused and never renumbered**; `CLAUDE.md`, the changelog
  and the roadmap cite them.
- A decision with `—` in the last column is a decision that **can be broken
  silently**. That is allowed, but it should be visible.
- Some rows are *decisions, not rules* (deliberate duplication, a rejected
  refactor) and some are *discarded, with the number* (principle 6, 10). Mark
  them as such — they are among the most useful rows in the file.
- The prose and the measurements live in the document that owns them; this file
  is the index that finds them.
- A decision is made **once**. If it is reopened with no new fact, the answer is
  this document.

### 7. `docs/references.md` — the external research register

```markdown
# External information worth knowing

Not a link list: every entry says what it contributes **to this project**, and
when it contradicts something already decided, it says that too.

Rule for keeping it: something enters when it changed or confirmed a decision,
not because it is well written.

## <Subject area>

- **[Title](url)** — what it says, in your own words.

  **What it confirms:** <the thing you already do>.

  **What we do differently, on purpose:** <and the measurement that decided it>.

  **Not applied yet:** <the idea worth revisiting, and when it would matter>.

  Produced: D-0xx, D-0yy.

## What to read first

| If you are about to touch… | Read | And watch out for |
|---|---|---|
```

### 8. `docs/roadmap.md`

```markdown
# Planned work
Accepted ideas, not yet built. **Not a promise and not a work order**: this is
where each one will collide, written now while it is clear.

## Where we are
The honest current state of the thing that is in motion. Updated every time it moves.

## <Area>
### <Idea>
What it is, in two lines.
**What it collides with.** The rule, by number, and why the collision is real.
**What is already in its favour.** The mechanisms that exist.
**What must be decided first.** Questions, not tasks.

## Closed by measurement
Ideas retired by a number, with the number, so they stay retired.

## What each one costs the invariant
| Idea | Does it break <the core invariant>? |
```

**The roadmap is a ledger, not a wish list, and that means it is written to when
work finishes as well as when it starts.** Give every entry one of five states,
and never delete an entry — a deleted idea comes back next quarter with no
memory of why it left:

| State | What the entry must then say |
|---|---|
| **Planned** | the collision, what exists in its favour, what must be decided first |
| **Half done** | which half, and whether the missing half is mechanism or content — they are answered by different people |
| **Done** | what it actually became, the measurement that closed it, and **what is still missing**, since shipping something rarely ships all of it |
| **Closed by measurement** | the number that retired it |
| **Blocked outside** | what would reopen it, concretely — an upstream issue, a device, an asset licence, a decision elsewhere |

That last state is the one most roadmaps lack, and it is the one that saves the
most time. Without it, an agent that has never seen the last three refusals
proposes the same blocked idea every time it reads the file, in good faith.

> **Example.** Two entries in one repo's roadmap are blocked outside: a label
> over a character's head, which needs art from asset packs that are absent on
> almost every machine, and native desktop media controls, which need an engine
> API that an upstream proposal has not shipped. Both say so, and both name the
> event that would reopen them. Neither has been re-proposed since.

**Finishing an item edits the roadmap in the same change that finishes it.** Not
in a follow-up, and not "when things settle": a roadmap that describes a feature
as planned while the feature is running is worse than no roadmap, because it is
believed.

**Give the roadmap a `## Process and tooling` area**, alongside the product
areas. This is where friction goes once it has been hit twice (principle 17),
and each entry takes the same shape as any other, plus the arithmetic:

```markdown
### <The friction, named as what it costs>
**What happens now.** The manual steps, counted.
**Cost.** <seconds or steps> × <how often> × <how many sessions>.
**The fix.** One line if it is one line.
**Seen in.** The sessions that hit it — at least two, or it is not here yet.
```

**Why it lives in the roadmap and not in a file of its own.** A separate
friction log is a file nobody opens: process work loses every prioritisation
argument against product work when the two are in different documents. In the
roadmap it is ranked against features, by the same human, in the same sitting —
which is the only place the comparison is honest. It also inherits the five
states, so a process item can be *closed by measurement* like anything else:
*"measured: the step costs four seconds, not thirty. Not worth automating."*

### 9. The structural audit script

A single script, run by the gate, that checks the rules the prose claims. Start
its docstring by naming what it is for:

```python
"""Check the repository against the rules it writes down about itself.

Every rule below is written in CLAUDE.md, <area>/CLAUDE.md or docs/architecture.md.
If a rule changes there, change it here too; if a check here has no rule, it
should not be failing the build.
"""
```

Two severities: **failures** stop the build, **advisories** are printed every run
and stop nothing. Put a rule in advisories when its legitimate exceptions are
real — an event declared before its listener is a valid order of work; leaving
it forever is not.

Keep the rule and the check pointing at each other. A check with no rule is a
trap; a rule with no check is rung 1.

### 10. `README.md`, `docs/architecture.md`, `.editorconfig`

- **README** — what this is, the core invariant in one paragraph, how to run it,
  where to go next. Written for a newcomer, not for the agent.
- **architecture.md** — the tree, the layers and their allowed direction, the
  lifecycle of the main object, **where a new file goes**, and the deliberate
  deviations from the framework's own recommendations with the reason for each.
- **.editorconfig** — one block per language in the repo, each matching that
  language's linter. A missing block is a silent fight between the formatter and
  the editor.

---

---

## Three agents, one source

**Assume from the start that more than one assistant works in the repository.**
The common set is **Claude Code, Cursor and GitHub Copilot**, and they do not
load the same files, do not support the same mechanisms, and will not notice
each other. Design for that on day one; retrofitting it means writing the rules
three times and discovering the contradictions from their output.

### The rule

> **One source of truth, three surfaces.** The root instruction file is the
> source. Every other agent's file is a **pointer to it, or a generated copy of
> it — never an independent document.**

Two instruction files that *can* disagree *will* disagree, and nobody notices,
because no human reads more than one. A repository with three hand-maintained
rule files has three versions of its architecture, and the agent that happens to
be open decides which one is true today.

If an agent cannot follow a pointer, generate its file from the source in the
gate and **check that it is current** — a stale generated file is the same
failure with an extra step.

### What each surface can actually do

| | Claude Code | Cursor | Copilot |
|---|---|---|---|
| Always-on file | `CLAUDE.md` at the root | `.cursor/rules/*.mdc` with `alwaysApply: true` | `.github/copilot-instructions.md` |
| Per-area rules | nested `CLAUDE.md`, loaded **on demand** when files there are read | `.mdc` rules **auto-attached by glob** | **none** |
| On-demand procedures | skills, chosen from their `description` | "agent requested" rules | **none** |
| Deterministic enforcement | **hooks** on lifecycle events | **none** | **none** |
| Path exclusion | `permissions.deny` | `.cursorignore` | the repo's ignore rules |

**ASSUMPTION:** all three products move quickly, and this table is the shape
rather than the specification. Verify the file names, the frontmatter fields and
the loading behaviour against the versions actually installed, and mark what you
could not confirm.

### The asymmetry that decides the design

Read the table by column and one conclusion falls out, which is the single most
useful thing on this page:

> **Copilot has one flat always-on file and no enforcement of any kind.** So a
> guarantee that must hold **cannot depend on any agent's machinery.** It has to
> live where every agent and every human meets it: **the gate, a script, a type,
> a schema** — rung 3 or rung 4.

Working with three agents is therefore not extra work bolted onto the method. It
is **another, sharper reason to push every rule up the ladder**, and a good test
of whether you did: *if the only thing this rule has is prose in one agent's
file, the other two are already breaking it.*

Note also that Cursor **has** glob auto-attachment and Claude Code does not,
while Claude Code has hooks and the others do not. Do not design around a
mechanism only one of them has unless you accept that the rule holds for one
agent only — and if you accept that, write it down as a deviation.

### What to write where

| Content | Goes | Why |
|---|---|---|
| The invariants — what must always hold | **all three always-on files**, from one source | they are the only thing every agent sees |
| Area depth | nested `CLAUDE.md`; Cursor globs | Copilot cannot express it; keep it out of its file, which is shortest by necessity |
| Procedures — verify, commit, troubleshoot | skills; Cursor agent-requested rules | Copilot users run them by hand from the source |
| Decisions, references, roadmap, changelog | plain documents in `docs/` | **agent-neutral**, and the reason the method's memory survives changing tools |
| Enforcement | the gate and the audit script | the only layer all three cannot ignore |

That fourth row is worth dwelling on: **the decisions log, the references
register, the roadmap and the changelog belong to no assistant.** Tools will
come and go; those four files are what makes that a non-event.

---

## The platform's own mechanics

The artifacts above assume how each assistant loads them. **These are product
mechanics, not method**, they change between versions, and an agent that assumes
the wrong ones will build a correct-looking system that never loads.
**Verify against the installed version before relying on any of it, and mark
what you could not confirm `ASSUMPTION`.**

| What | When it is loaded | Consequence for the design |
|---|---|---|
| Root `CLAUDE.md` | **every request** | the only always-on surface, so anything that must ALWAYS hold goes here — and it is paid for every time, hence the budget |
| `<area>/CLAUDE.md` | **on demand**, when files in that directory are read | area depth belongs next to what it describes, and costs nothing until someone works there |
| `.claude/skills/<name>/SKILL.md` | **on demand**, chosen from its `description` | the description is a trigger, not a title: write the phrasings that should reach for it |
| Hooks in `.claude/settings.json` | **deterministically**, on lifecycle events | the one mechanism that does not depend on the agent's judgement |
| `@path` imports inside an instruction file | **at launch**, and they spend context | importing a docs tree into the root file defeats the budget — **prefer nesting over importing** |

**What Claude Code does not have, and it is load-bearing:** there is no
unconditional glob auto-attachment — nothing that says *"whenever this file is
edited, load that rule."* A nested file loads when the agent reads a file in its
directory; a skill still needs the agent to judge it relevant. **Design for
that:** anything that MUST hold unconditionally belongs in the root file, or in
a hook, or on rung 3 or 4 of the ladder. Not in a nested file, and not in a
skill.

There is no ignore-file equivalent for hiding paths from the agent either. Use
`permissions.deny` for what must not be touched, and the repository's own ignore
rules for generated noise.

### A skill's frontmatter

Fields seen in practice: `name`, `description`, `when_to_use`, `allowed-tools`,
`disallowed-tools`, `model`, `paths`, `argument-hint`,
`disable-model-invocation`, `user-invocable`, `context`, `agent`.

`paths` narrows when a skill may be reached for, which is the closest thing to
per-area targeting. **ASSUMPTION:** the exact field set, the permission-rule
syntax and the hook event names vary between versions — check yours rather than
copying this list, and never invent a field to make something work.

### Adopting a repository that already has another agent's rules

**They are evidence, not clutter** (principle 19). Somebody wrote them because
something went wrong. Read them for content before touching their form:

| Found | Becomes | Note |
|---|---|---|
| `.cursorrules` or `.cursor/rules/*.mdc` | content into the source; the files stay as Cursor's surface | keep the globs — Cursor can express targeting Claude Code cannot |
| `AGENTS.md` at the root | content into the source; import or symlink for cross-tool portability | not read natively by every tool |
| `.github/copilot-instructions.md` | stays, regenerated from the source, shortest of the three | it is Copilot's only surface |
| Rules that contradict each other across files | **a finding, reported before anything is written** | this is the most valuable thing an adoption finds |

**Do not delete another agent's file to consolidate.** Point it at the source or
generate it; deleting it silently removes that agent's only instructions and
whoever uses it will not find out until their output goes wrong.

---

## The pre-flight — ask before you execute

**Every executable prompt in this set opens by asking, and nothing else happens
until it is answered.** Not because the agent is unsure of the method, but
because two or three facts about *this* repository and *this* run cannot be read
from the tree, and guessing them wastes the whole run.

The tension to hold, and it is the whole design:

> **Assume nothing that is genuinely in doubt. Ask as little as possible.**

Those pull in opposite directions, and the resolution is not to compromise
between them — it is to be strict about what counts as doubt.

### What earns a question

| Ask | Do not ask |
|---|---|
| It **cannot be read** from the repository, at any cost | It is written somewhere in the repo — go and read it |
| A wrong answer **wastes the run** or writes the wrong thing | A wrong answer costs a minute to correct |
| It grants **permission** — to run something, to write somewhere, to delete | A convention with an obvious default |
| It names a **constraint only the human knows** — a device nobody can reach, a team, a deadline | Anything you could determine by looking for five more minutes |

**The test, applied to every candidate question:** *could I answer this by
reading, or by trying something reversible?* If yes, it is not a question, it is
work you have not done yet.

### The shape of the pre-flight

- **One message. All of them. Before anything.** A question that arrives in the
  middle has already been answered by what was written.
- **Every question carries a proposed default**, so the whole block can be
  accepted in one word. State the default as what you will do, not as an option
  among several.
- **Offer `defaults` explicitly.** The last line of the block says that replying
  `defaults` takes every proposal as written. Most runs should end there.
- **Cap it at five, and aim for three.** If you have seven you have not applied
  the test above to four of them.
- **Say what you are *not* asking**, in one line: the assumptions you are making
  because the repository already answers them. This is what keeps "ask as little
  as possible" from turning into "assume silently" — the human sees what was
  decided for them and can object.
- **Ask conditionally.** A question that only matters in a monorepo is not asked
  in a repository with one package. Detect first, then ask what remains.

### What the pre-flight is not

It is **not** the deeper checkpoints. Some questions cannot be asked before
reading — whether a convention is a decision or an accident is the clearest
example, and it is answered at a report, not at a pre-flight. The pre-flight
covers what is knowable up front; the prompt's own procedure keeps its own
approval points, and they are not merged into it.

It is also not a place to re-ask what the header already records. `adapted` and
`declined` are answers a previous run already obtained; re-asking them is how a
set of prompts becomes something people avoid running.

---

## Which document to run

Evaluate comes before the other two and writes nothing, so it cannot conflict
with either. Bootstrap and update are the ones that write, and they are
designed so that **neither can undo the other's work.** The header is what makes that true:
bootstrap writes it, update reads it and carries it forward, and both leave it
correct.

| The situation you are in | Run | Because |
|---|---|---|
| No method header, no agent instructions of any kind | **bootstrap** | nothing to preserve; the full nine phases |
| No method header, but the repo already has `CLAUDE.md`, `AGENTS.md`, `.cursorrules` or its own conventions | **bootstrap, in adopt mode** | Phase 1 classifies what exists and **nothing is discarded**; see principle 19 |
| Header present, `version` lower than the incoming set's | **update** | only the deltas, triaged against this repo |
| Header present, same version, and the repo has learned something | **harvest** — the up direction in *Improving the method* | the repo is ahead of the method, not behind |
| Header present, **different `lineage`** | **reconcile — stop and report** | two method lines have diverged; merging them is a human decision |
| Header present but the artifacts it claims do not exist | **bootstrap, in repair mode** | the file was copied without the work; list what is missing and build it |
| Several repositories at once | see *Workspaces* | each one is its own case, and they do not share state |

**What update will not do.** It will not bootstrap. If the repo has the file but
not the artifacts, update stops, says which guarantees have no home, and hands
back to bootstrap in repair mode. An update that silently creates a decisions
log has skipped the phases that make a decisions log worth anything — Phase 1
and Phase 2 — and will fill it with plausible rows nobody verified.

**What bootstrap will not do.** It will not run on a repository that already has
a header at this version or higher. That means somebody copied an older file
over a newer one, and the right move is to stop and say so rather than to
rebuild on top of work that is already ahead.

**What they share, and must both leave true:**

- the header block, including `adapted` and `declined`
- one row in this repo's decisions log for anything now settled
- one changelog entry, naming what was skipped and why
- the artifacts' *guarantees*, in whatever shapes this repo uses for them

### Adopting into a repository that already works

This is the common case and the one that goes wrong. Before writing anything,
produce this table and **show it**. It is the entire negotiation:

| Guarantee the method needs | Typical file | What this repo already has | Verdict |
|---|---|---|---|
| Always-loaded invariants + a map | `CLAUDE.md` | `CONTRIBUTING.md` §Architecture | **extend it**, add the map, leave the rest |
| Settled decisions, with enforcers | `docs/decisions.md` | `docs/adr/*.md` | **keep ADRs**, add the enforcer line to the template |
| External research, annotated | `docs/references.md` | a Notion page | **out of reach** — add a pointer, record in `adapted` |
| Planned work with collisions | `docs/roadmap.md` | `PLANNING.md` | **add the missing sections** to it; do not rename |
| Per-change log | `.claude/logs/agent-changelog.md` | Conventional Commits, squashed | **new file** — commit subjects cannot carry the *why* at this length |
| One command that runs everything | task runner entry | `make check` exists but skips types | **extend the existing target**, do not add a second |
| Structural rules, executable | `tools/audit_*.py` | nothing | **new file** — no home exists |

Read the verdict column: **two new files out of seven.** That ratio is what a
good adoption looks like. If yours is seven out of seven, you are not adopting,
you are replacing, and you should stop and re-read principle 19.

**Rules for the table:**

- *Extend* beats *create* every time; *create* is only for a guarantee with no
  home at all.
- A guarantee that lives outside the repo (a wiki, a tracker, a chat) is still a
  home. Add a pointer, record it in `adapted`, and do not import it.
- Every *extend* and every substitution goes in the header's `adapted` list, in
  one line, so the next update does not re-propose it.
- Every *declined* goes in `declined` **with the reason** — and the reason is
  read on the next update, so write it for a stranger.

### Reordering without breaking

Sometimes the host repository really is disordered, and the honest move is to
say so. Even then:

- **Propose the target shape and the order of moves, then do one.** A repo is
  reorganised over several sessions, each one reviewable on its own, or it is
  reorganised in one commit nobody can review and everybody resents.
- **Moves and edits never share a commit.** A pure move is reviewable at a
  glance; a move plus an edit is a rewrite in disguise, and it defeats
  `git log --follow` for everyone afterwards.
- **Update the pointers in the same move** — imports, docs, CI paths — and let
  the gate prove you did.
- **Stop at the first thing you cannot justify with a consequence.** "It would
  be tidier" is not a consequence, and tidiness is not worth a stranger's
  merge conflict.

---

---

## Adapting to another stack

**What never changes:** the enforcement ladder, the nineteen principles, the ten
artifacts, the nine phases, the session loop with its two bookends and seven
steps, the engineering standards, and the requirement that every rule name its
consequence and its enforcer.

**What always changes:** the nouns of the invariant, the gate's commands, the
class of bug you cannot see, and which rung each rule can reach.

### By domain — where to look for the core invariant

Most repositories have exactly one invariant that everything else serves. Find it
in Phase 1; if you cannot name it, you have not finished Phase 1.

| Domain | The invariant usually sounds like | Stated as a test |
|---|---|---|
| HTTP service / API | requests are idempotent; state transitions are atomic; the contract is versioned and never broken in place | replay the same request twice, compare; contract tests against the published schema |
| Payments / ledger | every movement balances; nothing is applied twice; money is never a float | property test that debits equal credits; idempotency-key replay |
| Data pipeline / ETL | a rerun of the same window produces the same table; late data does not corrupt history | run the job twice on a frozen input, diff the output |
| ML system | the same seed and data produce the same model; training and serving compute the same features | determinism test; train/serve parity on a fixed sample |
| Library / SDK | the public API only changes with the version number | API-surface snapshot diff in CI; compile a real consumer |
| CLI tool | exit codes and stdout are the contract; stderr is for humans | golden-file tests on stdout; explicit exit-code assertions |
| Compiler / parser / serializer | round trip is identity | property test: `parse(print(x)) == x` |
| Game / realtime / media | every visual is a pure function of the clock, so seeking either way yields the same frame | render a moment from both directions, compare the pixels |
| UI application | rendering is a pure function of state; no state lives in the widget tree | snapshot tests; restore from serialized state and compare |
| Embedded / firmware | the loop never misses its deadline; memory is bounded and static | worst-case timing measurement; static stack analysis |
| Infrastructure / IaC | the described state is the deployed state; apply is idempotent | `plan` is empty after `apply`; drift detection |
| Distributed system | ordering and delivery guarantees hold under partition | deterministic simulation, fault injection, linearizability checks |

The pattern: **the invariant is what makes a test possible that no unit test
replaces.** Write that test first; it becomes the thing the whole instruction
system protects.

### By language — the gate and the mechanics

| Language | Format | Lint / static | Types | Test | Layering enforceable by |
|---|---|---|---|---|---|
| Python | `ruff format` / `black` | `ruff` | `mypy` / `pyright` | `pytest` | `import-linter`, a tree-walking script |
| TypeScript / JS | `prettier` / `biome` | `eslint` / `biome` | `tsc --noEmit` | `vitest` / `jest` | `dependency-cruiser`, `no-restricted-imports`, project references |
| Rust | `cargo fmt` | `clippy` | the compiler | `cargo test` | crate boundaries, `cargo deny`, visibility |
| Go | `gofmt` | `golangci-lint` | the compiler | `go test` | internal packages, `depguard` |
| Java / Kotlin | `spotless` / `ktfmt` | `detekt` / `errorprone` | the compiler | JUnit | ArchUnit, module boundaries |
| C# | `dotnet format` | analyzers | the compiler | xUnit | NetArchTest, project references |
| C / C++ | `clang-format` | `clang-tidy` | compiler + sanitizers | ctest | include-what-you-use, layered targets |
| Ruby | `rubocop -a` | `rubocop` | `sorbet` (optional) | `rspec` | Packwerk |
| Swift | `swift-format` | `swiftlint` | the compiler | XCTest | modules, access control |
| PHP | `php-cs-fixer` | `phpstan` / `psalm` | `phpstan` | PHPUnit | `deptrac` |
| Elixir | `mix format` | `credo` | `dialyzer` | ExUnit | umbrella app boundaries |
| SQL / dbt | `sqlfluff format` | `sqlfluff` | schema contracts | `dbt test` | model layers, `dbt` contracts |
| Shell | `shfmt` | `shellcheck` | — | `bats` | — |
| GDScript | `gdformat` | `gdlint` | — | headless validators | a tree-walking script |

Three cross-cutting notes:

- **One gate command.** Whatever the language, there must be a single command
  that runs everything and is named in `CLAUDE.md`. If the repo has five
  commands, add the one that runs all five.
- **Never let the agent invoke the formatter on a dirty tree** when the
  formatter is known to be able to lose data. Prefer a `--check` variant, which
  writes nothing and is always safe.
- **Pin the tooling versions** and keep the pin in one place. Two files pinning
  the same tool is a bump that will be done half-way.

### Multi-language repositories

One `.editorconfig` block per language, each matching that language's linter. One
nested `CLAUDE.md` per language area, because the conventions differ. One gate
that runs every language's checks — and name, in the root file, which languages
exist and what each is for, since an agent that assumes a single-language repo
will apply the wrong idioms with complete confidence.

---

---

## Workspaces: several repositories at once

One person, several repositories, each on its own branch, several open at the
same time. It is now the normal case, and it has one dominant failure mode:

> **An agent that has just been working in repository A will apply A's
> conventions to repository B with complete confidence**, because the
> conventions are in its context and the boundary is not. It will use A's
> logging style, A's test layout, A's commit format, A's *language*, and every
> one of those choices will look deliberate.

Everything below exists to keep that from happening.

**The workspace brief.** Step 0's brief gains a first line when more than one
repository is in play:

```
Workspace      <repo>@<branch> (<clean|N uncommitted>) · <repo>@<branch> (…)
Today's repo   <the one this work is in> — conventions come from HERE
```

Then the normal six lines, **for that repository only.**

**The rules:**

- **One repository is "today's repo" at any moment, and it is named.** When the
  work crosses a boundary, say so explicitly and re-read the target's root file
  before touching it. Re-read it — do not recall it.
- **Never carry a decision across.** A rule learned in A applies to B only via
  the promotion path in *Improving the method*, which includes the generality
  test. "We do it this way in the other repo" is not a reason; it is context
  leakage wearing a reason's clothes.
- **Each repo's gate runs in its own repo.** There is no workspace-level green.
- **A change spanning several repos states its order and its blast radius**
  before starting — which repo must land first, what breaks between the two
  landings, and whether the intermediate state is shippable. If it is not, say
  so; that is a coordination problem and it is the human's.
- **Branch names go in the brief**, every time. The single most expensive
  workspace mistake is committing to the branch of the repo you were in an hour
  ago.
- **Uncommitted work belongs to whoever left it.** In a workspace there are more
  trees and more chances to sweep in somebody's half-finished afternoon.
- **Method headers are per repository.** Two repos in one workspace routinely sit
  at different versions and different lineages, and that is fine. Never update
  one because its neighbour was updated; run the update invocation, per repo,
  with its triage.

**What a workspace makes cheap, and is the reason to tolerate it:** the harvest.
Seeing the same friction in three repositories in one week is the strongest
possible evidence that it belongs at level 3 of the promotion path. Write it
down when you notice it — the observation is only available from inside a
workspace, and it disappears the moment you close the other windows.

---

---

## Models, reasoning levels and cost

**Clarity and correctness first; cost is a real parameter and not the deciding
one.** The expensive thing in this method is never tokens — it is a wrong
decision that ships, a re-derivation that happens for the fortieth time, or a
rule that was never checked. But cost is not nothing, and the way to manage it
is not to think less. It is to **not think the same thing twice.**

> **The artifacts are the cost optimisation.** Every file this method asks for
> exists so that a future session does not have to re-derive what an earlier one
> already knew. A decisions log is a cache. A references register is a cache. The
> changelog is a cache with a timestamp. Read in that light, the whole system is
> one long argument for spending expensive reasoning **once** and writing the
> result down where a cheap session can use it.

### Which stage wants which model

| Stage | Wants | Why |
|---|---|---|
| Phase 0 — read the repo | small–medium, large context | volume, little judgement |
| Phases 1–2 — evaluate and research | **large, high reasoning** | finding contradictions and naming the bug class nobody can see |
| Phase 3 — propose | **large** | it is the decision; everything downstream inherits it |
| Phases 4–6 — generate, wire, de-duplicate | medium | mechanical once the topology is fixed |
| Phases 7–9 — audit, roadmap, review | medium–large | Phase 8's collision analysis is judgement |
| Step 0 — the opening brief | small | reading known files and summarising them |
| Steps 1–2 — pick, price, decide what to ask | **large** | foreclosure and trade-offs are exactly what small models flatten |
| Step 3 — build | matches the change: a typed refactor is small, a new abstraction is large |
| Steps 4–5 — verify and look | small — **but it must actually look**, and a model that cannot see images cannot do step 5 |
| Steps 6–7 — documents and report | medium | writing for a stranger is harder than it looks |
| Step 8 — harvest | **capture small, promote large**: recording is cheap, the generality test is not |
| Update triage | **large** | it is Phase 3 again, once per delta |

### Self-limiting, for a smaller or faster model

**If you are running with limited context or limited reasoning, say so at the
start of the session and hold to this list.** A small model that works within
its limits is more useful than a large one that is unavailable; a small model
that pretends to be a large one is worse than neither, because its output is
indistinguishable from good work until it is acted on.

Do:

- **Say what you can and cannot do, in the first message.** One line.
- The mechanical steps in full: step 0's brief, the gate, the documents in step
  6, the per-change checklist. These are exactly where a smaller model is
  reliable, and they are most of the method's value.
- **Capture** learnings and friction verbatim at step 8 — raw, unfiltered,
  unjudged. Recording does not require judgement and it is the part that must
  never be skipped.
- Prefer the checklist to the judgement call, every time one is available.
- Follow an exemplary file rather than inventing a pattern.

Do not:

- **Do not sample a codebase and imply coverage.** Say what you read and what
  you did not. This is in Phase 0 already and it matters most here.
- **Do not make rung-4 decisions** — a type, a schema, a data format. Those are
  the most expensive decisions in the repository and the hardest to reverse.
  Propose and stop.
- **Do not run the generality test** in the harvest. It needs breadth across
  repositories you do not have in context. Record candidates raw and mark them
  `needs promotion review`.
- **Do not decide a trade-off with foreclosure in it.** Present the options with
  their costs and stop; principle 15 is satisfied by asking.
- **Do not silently drop a phase or a step.** Name it as skipped, with the
  reason. A skipped step that is announced is a scheduling problem; a skipped
  step that is hidden is a defect with no symptom.

**The escalation sentence**, and use it verbatim rather than producing something
plausible: *"This exceeds what I can do reliably here — it needs <the specific
thing: whole-repo context / image inspection / a design decision>. I have done
<what was done> and stopped at <where>."*

### Spending well

- **Read what the invocation names, not the whole method.** That is what the
  navigation map and the per-invocation reading lists are for.
- **Do not regenerate the brief when nothing moved.** "No change since the last
  session" is a complete step 0.
- **Cache expensive reasoning into the repo**, not into a longer conversation: a
  decision row is re-read at zero cost forever, a conversation is not.
- **Where a cheap check exists, run it before the expensive reasoning.** The
  gate before the review; the type checker before the argument about design.
- **Do not pay twice for the same read.** If Phase 0 listed the tree, later
  phases cite that list rather than walking it again.

---

---

## Improving the method, and distributing it

Everything above is a method, and a method that does not improve is just a habit
with better prose. This section is how a lesson learned in one repository
becomes a rule in all of them — and, just as importantly, how most lessons are
correctly stopped from doing that.

### The promotion path

A learning travels up through four levels, and **most learnings stop before the
top. That is the system working**, not failing.

| Level | Where it lives | It gets promoted when |
|---|---|---|
| 1 | A sentence in one changelog entry | it happens a second time |
| 2 | A rule in this repo's `CLAUDE.md`, a decisions row, or a check in the audit | it turns out not to depend on this repo's nouns |
| 3 | A principle, artifact or step in **the method documents** | it would be true in a repo that shares none of this one's vocabulary |
| 4 | Every other repository | the method file is distributed and the update invocation is run |

**The generality test, and it is strict.** Try to state the learning without a
single noun specific to your project. If you cannot — if it needs *the audio
clock*, *the scene format*, *our deploy script* — it belongs at level 2 and it
is finished. If you can, and the sentence still says something, it is a
candidate for level 3.

> **Passes.** *"A change is not done until the documents it falsified are true
> again."* No nouns. True of a payments service and a firmware project alike.

> **Fails, and correctly.** *"Never advance visual state by accumulating
> delta."* Entirely about one domain. It is an excellent rule; it belongs in
> that repo's `CLAUDE.md`, and it appears here only as an *example* of a
> core invariant — which is the right way for a level-2 rule to earn a mention
> at level 3.

**The second filter: has it earned it?** A principle costs every future reader
attention on every future read. Add one only when you can name the failure it
prevents **and** point at an occurrence. A principle with no incident behind it
is an opinion that will be obeyed anyway, which is worse than one that is
argued.

### Keeping the set versioned, so other copies can catch up

A distributed document without a version cannot be updated — the receiving repo
has no way to tell what it already has. The **method header**, identical at the
top of all four documents, is that record, and it carries more than a number:

| Field | What it is for | Read by |
|---|---|---|
| `set` | which documents this release ships as | checking you hold a matched set |
| `lineage` | which line of the method this copy descends from | matching two repos before merging anything |
| `ancestry` | root → current; the visible shape of the tree, with opaque nodes | seeing where two copies separated |
| `version` | how far along **that lineage** this copy is | the update triage, to compute the deltas |
| `forked_at` | the `{lineage, version}` this line branched from, or `null` for a root | making a merge conversation concrete |
| `digest` | a fingerprint of the set's **content** | catching two copies that claim the same version and are not the same |
| `upstream` | where this copy pulls from — a repo, a path, or `""` if it is a root | whoever runs the update |
| `adopted` | when this repository took the method | telling a prepared repo from one that only has the files |
| `adapted` | every local renaming and substitution, one line each | principle 19, so a rename is never re-proposed |
| `declined` | every delta deliberately not taken, **with its reason** | the update, so a decision is not re-litigated every release |

**`adapted` and `declined` are the two that make distribution survivable.**
Without them, every update re-proposes the same rename and the same rejected
delta, the human re-declines them, and within three releases the update
invocation is something people avoid running. They are `references.md`'s trick
applied to the method: *what we do differently on purpose, written down.*

**On lineage, and why the id is deliberately meaningless.**

A lineage id is an **opaque token** — `m-7c41a9`, `m-2fb098` — and it is never
derived from a repository, a product, a team or a person. That is the point. A
copy of this method may travel a long way from where it started, to
organisations that should be able to use it, match it and fork it **without
learning anything about where it came from.** A lineage called
`acme-billing/main` leaks a customer, a project and a team every time the file
is forwarded; `m-7c41a9` leaks nothing and does the same job.

**`ancestry` is what stays readable**, and it is the whole mechanism:

- It is an ordered list, root first, of every line this copy descends through.
- A copy that simply tracks its parent **does not touch it**.
- A copy that forks — diverges enough to evolve on its own — **appends one new
  opaque id** and sets `lineage` to it.

So a downstream reader can always see the **shape of the tree** they are part of
— how deep their line is, where two copies separated, how much they share — and
can never see what any node *is*. Two people who both hold `m-7c41a9` recognise
each other instantly, because they both have it in their own repositories;
everyone else sees an anonymous ancestor.

### Version numbers, and the collision they would otherwise have

**A monotone integer does not survive two writers.** If two repositories both
hold lineage `m-7c41a9` at version 6, and both improve the method locally and
bump to 7, there are now **two different version 7s on the same lineage** — and
the naive comparison concludes "same lineage, same version, identical, nothing
to do". That is the worst kind of wrong: silent, and it discards one side's work.

The fix is one rule and one check, in that order — the rule makes the collision
impossible, the check catches the rule being broken, because rules about human
process do get broken.

**The rule: a lineage has exactly one writer.**

> **`version` is monotone *within a lineage*, and only the owner of that lineage
> may bump it.** Anyone else who changes the method **forks**: appends a new
> opaque id to `ancestry`, sets `lineage` to it, records `forked_at`, and
> continues numbering from where they branched.

That single sentence removes the collision by construction rather than by
convention, which is rung 4 of the ladder applied to the method itself. "Improve
locally without forking" is not a state you can get into: changing the method
*is* forking, and forking is cheap — six hex characters.

**Numbering does not restart at a fork.** A line that branches from `m-7c41a9`
at version 6 makes its first release **7**, not 1. Then `version` still means
"how much has happened", `ancestry` disambiguates whose 7 it is, and comparing
two copies never needs a lookup table. A restart would make version 3 of one
line and version 3 of another look comparable when they are not.

**The check: the digest.**

`digest` is a fingerprint of what the documents actually say, so equality stops
being a claim and becomes something you can verify:

```bash
# Concatenate the set in filename order, with each file's yaml header stripped,
# and take the first 12 hex characters of the sha256.
for f in $(ls prompt-*.md | sort); do
  awk 'BEGIN{h=0} /^```yaml$/{h=1;next} h&&/^```$/{h=0;next} !h' "$f"
done | shasum -a 256 | cut -c1-12
```

Twelve characters: short enough to compare by eye in a report, long enough that
two different sets will not share one. The header is excluded so that writing
the digest into the header does not change the digest.

**It degrades safely.** If a copy's `digest` does not match the content you are
reading, **do not trust the header** — treat the copy as unversioned and compare
content directly. A stale digest is a stale claim, and the one thing it must
never do is be believed.

**Matching two copies**, with both mechanisms in play — six outcomes:

| Comparison | Meaning | What to do |
|---|---|---|
| Same `ancestry`, same `version`, same `digest` | identical | nothing |
| Same `ancestry`, same `version`, **different `digest`** | **the collision** — someone changed the method without forking | **stop.** Report both digests and both changelogs. One side must fork retroactively before anything else happens |
| Same `ancestry`, different `version` | one is simply behind | update, mechanically |
| Shared **prefix**, then divergence | forked lines; `forked_at` says exactly where | **stop**, report the common ancestor and the versions each side added since; merging is a human decision |
| No shared prefix | unrelated methods sharing a filename | **never merge**; they are different documents |
| `digest` absent or not matching its own content | an untrustworthy header | ignore the header, compare content, and say so |

Silently overwriting one lineage with another discards everything the first line
learned, which is the entire asset. That is why three of those six rows stop.

### How to fork, in four lines

1. Generate one new opaque id — six hex characters after `m-`.
2. Append it to `ancestry` and set `lineage` to it.
3. Set `forked_at` to the `{lineage, version}` you branched from.
4. Continue numbering: parent's version **+ 1**, and recompute `digest`.

Then the two lines evolve independently, and anything general that either learns
travels the other way through the harvest — not by overwriting a file.

**Generating an id.** Six hex characters after `m-`, taken from anything random,
once, when the fork happens. Never regenerate it, never make it mnemonic, and
never encode a date, a name or a counter in it — each of those turns an opaque
token back into a leak.

Then the numbering discipline:

- Every change bumps `version` **in all four documents**, recomputes `digest`,
  and adds one line to the **Method changelog** below — one line, saying what a reader *does differently* now, not what was
  edited.
- **Never renumber the principles.** Append. A repository referring to
  "principle 14" must still be right after the next revision, exactly as an enum
  written to disk is appended to and never inserted into. If a principle dies,
  mark it withdrawn and leave the number spent.
- Same for the artifacts, the phases and the steps of the loop.
- **The header is written in the same change that changes the method, in every
  document of the set.** A file whose header disagrees with its body, or with
  its siblings, is worse than one with no header — the triage trusts it.

### The two directions

**Down — distributing an improvement.** Copy the newer file over the older one
in the target repo and run the **update invocation**. It reads the version the
repo had, lists only the deltas since, and — the part that matters — decides
which of them apply *there*, because a repo with no rendered output does not
need the rule about looking at the artifact.

**Up — harvesting from a repository.** Rarer and more valuable. When a repo has
been running the loop for a while, ask it directly: *what have you learned that
the method does not know?* Then apply the generality test to each candidate,
brutally, and bring back the two that survive rather than the nine that were
offered. The failure mode here is a method file that accretes one repo's
idiosyncrasies until it is portable to nowhere.

### A cadence that works

Tie the reviews to events rather than to the calendar, because a monthly ritual
gets skipped and an event-driven one does not:

| When | Do |
|---|---|
| Every session close | step 8 — harvest to level 1 and 2 |
| When a friction is hit a second time | promote it to the roadmap's process area |
| When the local review skill runs (Phase 9) | ask which level-2 rules pass the generality test |
| When you notice yourself explaining the same thing to a second repository | that is the signal to promote to level 3 and bump the version |
| When a repo gets significant new work after a gap | run the update invocation before starting, not after |

**The last row is the one people get wrong.** Updating the method *after* the
work means the work was done under the old method, and the first thing the new
method says is usually something the work should have done.

---

---

## Worked example: how this landed in one repository

A 2D animation player synced to audio, scripted, shipping to the browser and
consumed on **a single phone that nobody on the development side can reach**.
Every device test goes through the end user. That single constraint shaped
everything, and it is why the example transfers: most repositories have some
version of *a place where the software runs and you cannot look.*

**The invariant.** Audio is the master clock; every visual is a pure function of
the song's current time. Never accumulate frame delta.

**The enforcement chain for that one invariant**, rung by rung:

| Rung | Mechanism |
|---|---|
| 1 | Two paragraphs at the top of `CLAUDE.md`, each rule carrying the measurement that produced it |
| 2 | A decisions table, eight consecutive rows, each naming the class or constant that enforces it |
| 3 | A validator that fails the gate on the constructs that cannot be seeked — forward-simulating particles, `TIME` in shaders, method-call animation tracks — plus a renderer that reaches each moment **from both directions and compares the pixels** |
| 4 | The scene format itself: a piece names a *plane*, never a speed, so the depth rule cannot be violated in the data |

**What the numbers did.** The root file went 817 → 209 lines. The structural
audit failed seven of seven rules on its first run. A refactor of the scene
system was accepted only because 14/14 rendered frames were byte-identical
before and after. A proposed optimisation was closed by measuring draw calls —
63–86, where a typical mobile 2D game lives between 50 and 200 — and the depth
rule survived because the number said the cost was a third, not an order of
magnitude.

**The bug that justified the whole system.** An exported array property was
silently emptied by the build. The editor read the label; the phone read a raw
index. It was invisible from the development side, and it is the ancestor of the rule
*anything only visible in the shipped artefact gets a check that runs before it
ships.*

**What the loop looks like there, once the bootstrap is over.** A later session
took one roadmap item — playlists — through the seven steps: it ranked four
candidates and said which it would *not* take and why (a selector for more than
five on-demand actions, when no character sheet in the project declares more
than the five that already fit); asked three
questions and no more, one of them priced as *"a row of tabs costs half of one
of the three cards that fit in the list"*; built it by turning an existing label
into a button rather than adding a screen; ran the gate and the seek comparison
even though nothing time-dependent had moved; **rendered the menu and looked at
it**, which killed a dim that made cards transparent over one backdrop and
exposed an empty-state message that had been mis-sized since long before the
feature; moved five documents in the same change; and reported one structural
decision — a builder file now one function from its line budget — as the
human's to take, rather than splitting it mid-feature.

That paragraph is the whole method in one item's worth of work, and none of it
is specific to a music player.

**The transfer.** Nothing above depends on that engine or that domain.
Substitute your own nouns:

| Here | A backend service | A Python package | A data platform |
|---|---|---|---|
| the audio clock | the transaction boundary | the public API | the partition window |
| seek from both directions and compare pixels | replay the request twice and compare state | install the wheel in a clean venv and smoke-test | rerun the window and diff the table |
| the phone nobody can debug | production under concurrency | the user's machine | the full-size dataset |
| the scene format that cannot express a bad depth | a config type with no optional timeout | a `Money` type with no float constructor | a contract that rejects an unknown column |

---

---

## Method changelog

Newest first. One line per version, saying what a reader does differently.

| Version | Date | What changed for the reader |
|---|---|---|
| 7 | 2026-09-17 | **Versioning survives divergence.** A monotone integer silently collides when two repositories on one lineage both improve the method and both bump — the naive comparison calls them identical and discards one side. Fixed by a rule and a check: **a lineage has exactly one writer**, so changing the method *is* forking (`forked_at` records where, and numbering continues rather than restarting); and **`digest`**, a content fingerprint of the set with headers stripped, catches the rule being broken. Matching went from four outcomes to six, three of which stop. Also: the method now assumes **three agents — Claude Code, Cursor and Copilot** — under one rule, *one source of truth, three surfaces*, with the asymmetry that decides the design: Copilot has one flat file and no enforcement, so **a guarantee that must hold cannot depend on any agent's machinery** and belongs on rung 3 or 4. Decisions, references, roadmap and changelog are agent-neutral by design, which is what makes changing tools a non-event. |
| 6 | 2026-09-17 | **Every executable prompt now asks before it executes.** Each opens with a *Before you start* pre-flight — three to five questions, in one message, each carrying the default it will take, answerable with the single word `defaults` — and each invocation orders it as its first act. `prompt-context.md` §*The pre-flight* holds the shared rule, whose whole design is one sentence: **assume nothing genuinely in doubt, ask as little as possible**, resolved by being strict about what counts as doubt — if it can be read, or tried reversibly, it is not a question. The prompt must also say in one line **what it is not asking**, so minimal never becomes silent. Paste blocks became **code fences instead of blockquotes**, because `>` markers are copied along with the text, and each invocation now appears **once**, at the top of its file. `prompt-context.md` absorbed the last of the shared prose — the set table, the language rule, the header mechanics and the adoption rules now exist once. |
| 5 | 2026-09-17 | The set is **four documents with one naming scheme**: `prompt-context.md` (new — this file: the shared reference the other three read from, and **not executable**), `prompt-evaluate.md`, `prompt-bootstrap.md`, `prompt-update.md`. Each executable prompt now opens with its **copy-paste block at the very top**. `prompt-update.md` gained **the prune**, which removes superseded method files only after verifying section by section that their content is covered, lifted or **moved out**, and after following every inbound link. `prompt-evaluate.md` became **set-aware**: it reads the `set` field, lists what is actually present, uses every prompt it finds as criteria, and reports whether the set is complete and consistent. Added *The platform's own mechanics*: what loads when, what does not exist (no unconditional glob auto-attach), a skill's frontmatter, and the mapping from other assistants' formats. |
| 4 | 2026-09-17 | The method **ships as three documents**: `evaluate-prompt.md` (new — read-only assessment of a repository's AI instruction system, producing one summarised Markdown report with a maturity table and a cheapest-first next-steps list) is now the front door; `update-prompt.md` (new — the upgrade job, extracted so it runs without loading the method); and this document. Lineage ids became **opaque tokens with a visible `ancestry` chain**, so a copy can be matched and forked without revealing where it came from. The worked example and every illustration were anonymised: the method carries no project, product, person or customer. |
| 3 | 2026-09-17 | The method is **distributable and complete**: a machine-readable **method header** (lineage, version, adapted, declined) replaces the version line; *The pair* makes bootstrap and update complementary and mutually non-destructive; **principle 18** puts the test before the code and says why that is sharper for an agent than for a human; **principle 19** adopts into a repository that already works instead of replacing it; *Engineering standards* covers tests, types, in-code documentation, the four layers of evaluation and the per-domain non-negotiables; *Workspaces* covers several repos at once; *Models, reasoning levels and cost* says which model each stage wants and how a smaller one self-limits; step 8 separates **capture — always** from **proposing — throttled**; and step 7 gains *What to show the user*. |
| 2 | 2026-09-17 | The method covers the **process itself**: principle 17 (friction is recorded, priced and promoted), the session's two bookends — the **opening brief** (step 0) and the **closing review** (step 8) — a `Process and tooling` area in the roadmap, and this versioning-and-distribution section with its update invocation. |
| 1 | 2026-09-17 | The method covers **the recurring session**, not only the bootstrap: *The session loop*, principles 14–16 (look at the output; sort the decisions; put the documents back), the roadmap as a five-state ledger, and a second invocation for normal work. |
| 0 | — | The bootstrap: the enforcement ladder, principles 1–13, ten artifacts, nine phases, stack adaptation, checklists, worked example. |

---
