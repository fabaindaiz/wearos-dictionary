# Bootstrap a repository for Claude Code

**A portable prompt.** Copy this single file into any repository — any language,
any domain — paste the invocation at the bottom, and Claude will research the
stack, evaluate what is there, and build the instruction system that makes every
later session cheaper and safer.

It is self-contained. It does not read anything else, and nothing outside it is
needed to run it.

> **Who this is for.** This document is addressed to **Claude**, in the
> imperative. The two boxed sections — *How to use this* and *The invocation* —
> are addressed to the human running it.

---

## How to use this (human)

1. Drop this file at `docs/agents/bootstrap-prompt.md` in the target repository.
2. Open Claude Code at the repository root.
3. Paste **The invocation** (last section of this file).
4. Answer Claude's Phase 1 report and Phase 2 research with corrections. **This
   is where your judgement enters and it is the part that cannot be automated:**
   you know which of the repo's conventions are decisions and which are
   accidents. Claude cannot tell those apart from the outside.
5. Approve the Phase 3 proposal. Nothing is written before that.

Expect the first pass to take a long session and to produce roughly: a root
`CLAUDE.md` under 200 lines, one nested `CLAUDE.md` per natural area, two to
four skills, a permissions file, a decisions log, a references register, a
roadmap, a changelog, and — the part most people skip — **a script that checks
the structural rules the prose claims.**

**On the length of this document.** It argues for a 200-line budget and is
itself long. There is no contradiction, and the distinction is load-bearing:
`CLAUDE.md` is **context loaded into every session** and paid for every time,
while this is a **procedure read once, on demand**. Budget what is always
loaded; be generous with what is fetched deliberately. Apply the same split in
the repo you are bootstrapping.

---

## Why this exists

An agent is fast, tireless, and arrives with no memory of yesterday. That
combination has three failure modes, and everything below is aimed at them:

1. **It re-derives.** Every session re-discovers the same constraints, and
   re-opens the same settled questions, because nothing wrote them down in a
   form that survives a new context window.
2. **It breaks what it cannot see.** A rule that exists only in prose gets
   broken silently. The agent is not careless; it simply had no way to check.
3. **It parallelises into collision.** Sessions are cheap to run side by side.
   Two of them working the same feature will not see each other, and the merge
   is discovered at compile time — or worse, at review time.

The system below is not documentation for humans that an agent happens to read.
It is a **control surface**: the set of files that decide what the agent treats
as settled, what it must verify, and what it must refuse to do.

---

## The enforcement ladder

This is the single most transferable idea in this document. Every rule in a
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

## The principles

Thirteen. Each one states what it prevents, because a principle whose failure
mode you cannot picture will not survive contact with a deadline.

### 1. Every rule names its consequence and its enforcer

Not "use X". Write: **what breaks if you do not, and where it is caught.**

> **Example.** `get_time_since_last_mix()` is clamped to 100 ms — unclamped it reports the
> time since the audio context started (measured at 17.86 s after a wait in the
> menu), which starts the show halfway through the song. Enforced in
> `SongClock.MAX_MIX_AHEAD`.

Compare with "clamp the mix-ahead value", which any later session will read as
advice and drop when it is inconvenient.

**Prevents:** rules being negotiated away by an agent that cannot see the cost.
**Test:** for every rule in your root file, can you name the file, script or
command that would catch a violation? If not, it is on rung 1 — either promote
it or admit it is guidance.

### 2. Rules are checked, not remembered

Anything verifiable by reading the tree should be verified by a script that runs
in the gate. Write it early, and expect it to fail on day one.

> **Example.** When the *Worked example* project's structural audit was first
> written, **seven of its seven rules were being violated somewhere** — every one of them documented, every
> one believed to be held.

Structural rules worth a script in almost any repo:

- **Dependency direction** between layers or packages.
- **Naming**: file name ↔ exported symbol, test file ↔ subject, migration
  ordering.
- **Uniqueness**: two definitions of the same public name (the most expensive
  failure in the *Worked example* project's history — two classes with one name,
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
  include two meaningless ones. *(This repo folded two lyric booleans into a
  three-state enum for exactly that reason: of four combinations, three meant
  anything.)*
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
> editor showed `Repetir`; the phone showed `< 2 >`. Nobody could see it, because nobody
> on the development side owns the target device.

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

> **Example.** The official Godot audio-sync recipe has three terms. The
> *Worked example* project uses two — deliberately — because the third returns
> nothing usable on the web driver, and its reference entry says so. Without that note, every future
> session "discovers" the official recipe and proposes the bug back.

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
**Measured.** The number, if a claim was made.
```

Newest on top. The header of the file states the obligation and the incident
that motivated it.

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

## The process

Nine phases. **Write nothing until Phase 3 is approved.** Phases 0 to 2 are
read-only, and they are the ones that decide whether the rest is any good.

### Phase 0 — Read the repository (read-only)

Manifests and lockfiles. The task runner and every script it defines. CI
workflows. Container and service definitions. The source tree. The tests. `docs/`
in full. The git history. Existing agent instructions of any flavour
(`CLAUDE.md`, `AGENTS.md`, `.cursorrules`, `.github/copilot-instructions.md`).

End by listing what you read. If the repo is too large to read exhaustively, say
so and state your sampling strategy — never imply coverage you do not have.

### Phase 1 — Evaluate the state (read-only). Report before proposing anything.

Ten questions. Answer all ten, in order, and mark **ASSUMPTION** on anything
inferred rather than read.

1. **Stack and versions.** Languages, runtimes, frameworks, package manager, the
   dependencies that constrain design. Pinned or floating?
2. **Tooling, with the real commands.** Formatter, linter, type checker, test
   runner, build, deploy — each quoted from where it is defined, never invented.
   If a command in the README does not exist in the task runner, say so: that is
   a finding.
3. **Structure, and the natural areas.** Which folders have rules of their own?
   Which would a nested `CLAUDE.md` serve? What is the dependency direction, and
   **is it currently respected** — check, do not assume.
4. **How it is launched and how it ships.** Local run, test, build, release. What
   the user actually receives.
5. **Implicit conventions.** Naming, error handling, logging, configuration,
   test layout, repeated patterns. Name an **exemplary file** for each: the one a
   new file should be copied from.
6. **Critical contracts the code cannot relax.** Adapt the failure model to the
   domain — see the table in *Adapting to another stack*. State each as an
   invariant, in the vocabulary of this repo.
7. **Where the failure knowledge actually lives.** Look for `fix:` and `wip` in
   the history — but if it is not there (young repo, squash merges, a team that
   writes its hard-won facts into prose), **say so and find where it really
   is**: comments recording a measurement, an existing instructions file, issues,
   incident notes, a decisions log. Build the troubleshooting layer from the real
   source rather than pretending.
8. **The class of bug this repo cannot observe.** Name it explicitly. It decides
   what must be checked *before* shipping rather than after.
9. **Existing agent instructions.** Count the lines of any `CLAUDE.md`, say how
   far past 200 it is, and classify each of its sections as *invariant* or *area
   detail* — that classification is the cut list for Phase 3.
10. **What you could not determine.** Explicitly. An unanswered question is
    information; a guessed answer is damage.

**Then state the repo's maturity honestly**, because it changes the plan:

| Signal | If yes | If no |
|---|---|---|
| Is there a gate that currently passes? | wire the rules into it | building the gate is the first task |
| Do the structural rules hold today? | write the audit as a regression guard | the audit will fail on day one — expected, and it is the point |
| Is `docs/` true? | keep it in step | Phase 7 becomes real work |
| Is there history to mine? | build troubleshooting from it | item 7 above |

### Phase 2 — Research the outside (read-only). Report before proposing anything.

This is the phase most bootstraps skip, and it is what makes the rest
non-generic. **Three searches, and nothing enters the repo unless it changed or
confirmed a decision.**

1. **The domain.** The recognised references for this kind of software —
   the canonical paper, talk, book chapter or engineering blog post that the
   people who build these things cite. *(In this repo: side-scroller camera
   theory, rhythm-game calibration, game feel. In a payments service:
   idempotency keys, exactly-once semantics, reconciliation. In a data platform:
   slowly changing dimensions, late-arriving data, backfill strategy.)*
2. **The language and its ecosystem.** Official style guides, the idioms the
   community treats as settled, the standard project layout, the known traps of
   the version you are pinned to. Prefer the primary source over a summary.
3. **The framework or platform.** Its own best-practice pages, its issue tracker
   for anything that bites you, and **especially** the places where its
   recommendation assumes an environment you are not in.

For each finding, produce the four-part entry from artifact 7: what it confirms,
what you do differently on purpose, what is not applied yet, what decisions it
produced. **Contradictions with current practice are the valuable output** — do
not soften them, and do not resolve them yourself: report them and let the human
decide.

Rules for this phase:

- **Verify, do not recall.** Anything version-specific about a tool or platform
  gets checked against its documentation. State which claims you verified and
  which are from memory, and mark the second kind **ASSUMPTION**.
- **Cite the source you actually used.** Not a plausible URL.
- **A recipe written for a different environment is the most dangerous kind of
  correct.** Say which environment a recommendation assumes.
- **Cap it.** Five to fifteen sources that changed something beats fifty that
  did not. If a search produced nothing that changes a decision, say that — it is
  a valid and useful result.

### Phase 3 — Propose (still do not write)

The full topology: root `CLAUDE.md` with its section list, each nested
`CLAUDE.md` with its area and contents, each skill with name/trigger/contents,
permissions, hooks, the decisions to seed, the references to register, the
roadmap items, and the audit script's check list.

Say explicitly **what will be cut** from any existing file and where each piece
goes. Justify the slicing. **Then wait.**

### Phase 4 — Generate

Concise, imperative, no filler. Every rule carries its consequence and its
enforcer. Reference an exemplary file by path instead of describing it. Mark
**ASSUMPTION** rather than inventing a frontmatter field or a settings key. No
contradictions between layers — if the root and an area file disagree, the root
wins and the area file is wrong.

### Phase 5 — Make the rules executable

Write the audit script. Wire it into the gate. Add the schema if there is
structured data. Add hooks for what must not be left to judgement. Add
`permissions.deny` for the files that should not be hand-edited.

**Expect failures on the first run and fix them** — they are real violations,
not tooling noise. Report how many of the documented rules were already being
broken. That number is the best available argument for the whole exercise.

### Phase 6 — Anti-duplication sweep

Remove from nested files anything the root already says. Remove from the root
anything that has an owner document. Every remaining fact should exist exactly
once, with pointers to it.

### Phase 7 — Audit `docs/` against the code

Contrast every document with what is on disk. Update or delete what is stale.
**A document pointing at a deleted file is worse than no document**, and this is
the single most common decay in an agent-assisted repo, because agents delete
code faster than they re-read prose. Sweep for the names of anything you removed.

### Phase 8 — Roadmap and progress evaluation

Write `docs/roadmap.md` with the collision analysis (artifact 8). Then write the
**Where we are** section: an honest statement of the current state of whatever is
in motion, in the same vocabulary as the invariants.

### Phase 9 — The recurring review

Bootstrapping is not the end; the system rots without a ritual. Add a
`state-review` skill that runs on demand and answers:

- Does every document named in the map exist, and is every claim in it still
  true?
- Does every rule still have an enforcer, and did any of them fire this period?
- What changed that should have become a decision row and did not?
- What in the roadmap is now closed — by being built, or by a measurement?
- Has the root `CLAUDE.md` drifted past its budget, and which section grew?
- Which rules are still on rung 1 and could cheaply be promoted?

---

## Adapting to another stack

**What never changes:** the enforcement ladder, the thirteen principles, the ten
artifacts, the nine phases, and the requirement that every rule name its
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

## Checklists

### Done

- [ ] Root `CLAUDE.md` under 200 lines: invariants, guardrails, real commands,
      the gate, the commit bar, the logging obligation, the document map.
- [ ] Every rule in it names a consequence **and** an enforcer.
- [ ] Invariants pass the *survives a file move* test: classes, constants and
      contracts, not paths. Every inference marked **ASSUMPTION**.
- [ ] A nested `CLAUDE.md` per natural area, adding only what is local.
- [ ] Skills for the on-demand procedures, with trigger-shaped descriptions.
- [ ] The troubleshooting layer built from where the failure knowledge really
      lives, not from an assumed git history.
- [ ] `docs/decisions.md`: every settled question, numbered, with the enforcer
      column filled — including the rows that say `—`.
- [ ] `docs/references.md`: only entries that changed or confirmed a decision,
      each stating what you do differently on purpose.
- [ ] `docs/roadmap.md`: collisions, what must be decided first, closed-by-
      measurement, and the cost-to-the-invariant table.
- [ ] A ship-blocking check for the class of bug this repo cannot see.
- [ ] The audit script runs in the gate, and the first run's failures are fixed.
- [ ] `.claude/settings.json`: permissions, and hooks for what must not be left
      to judgement.
- [ ] `.claude/logs/agent-changelog.md` with the format and the obligation.
- [ ] `.editorconfig` aligned to the linter, one block per language.
- [ ] `docs/` audited against the code; no pointer to a file that does not exist.

### Smells that mean it went wrong

- **The root file is 600 lines.** Its middle is not being read. Move the detail
  to the document that owns it and leave a pointer.
- **A rule with no enforcer and no `—`.** It is a wish presenting as a rule.
- **`references.md` is a link list.** Nobody will read it and it will not stop a
  single reintroduced bug.
- **The roadmap has dates but no collisions.** It is a wish list; the collisions
  are the part that saves time.
- **The changelog is bookkeeping.** Entries with a *what* and no *why* are worth
  less than the seconds they cost.
- **The audit script passed on its first run.** Either the rules are trivial or
  the checks are not checking. Look again.
- **Two documents state the same number.** One of them is already stale; you
  just do not know which.
- **Every decision row says "code review".** Review is not an enforcer, it is a
  hope with a meeting attached.
- **The agent keeps proposing something already decided.** That decision is
  missing from `decisions.md`, or its *why* has no number in it.

---

## Worked example: how this landed in one repository

A 2D music-synced animation player, Godot/GDScript, shipping to the web and
consumed on **one specific iPhone that the developer does not own**. Every device
test goes through the end user. That single constraint shaped everything, and it
is why the example transfers: most repositories have some version of *a place
where the software runs and you cannot look.*

**The invariant.** Audio is the master clock; every visual is a pure function of
`song_time`. Never accumulate `delta`.

**The enforcement chain for that one invariant**, rung by rung:

| Rung | Mechanism |
|---|---|
| 1 | Two paragraphs at the top of `CLAUDE.md`, each rule carrying the measurement that produced it |
| 2 | A decisions table, D-006 to D-013, naming the class or constant that enforces each |
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
silently emptied by the build. The editor read `Repetir`; the phone read `< 2 >`.
It was invisible from the development side, and it is the ancestor of the rule
*anything only visible in the shipped artefact gets a check that runs before it
ships.*

**The transfer.** Nothing above depends on Godot. Substitute your own nouns:

| Here | A backend service | A Python package | A data platform |
|---|---|---|---|
| the audio clock | the transaction boundary | the public API | the partition window |
| seek from both directions and compare pixels | replay the request twice and compare state | install the wheel in a clean venv and smoke-test | rerun the window and diff the table |
| the phone nobody can debug | production under concurrency | the user's machine | the full-size dataset |
| the scene format that cannot express a bad depth | a config type with no optional timeout | a `Money` type with no float constructor | a contract that rejects an unknown column |

---

## The invocation

Paste this into Claude Code at the root of the repository you are preparing.

> You are preparing this repository to be worked on with **Claude Code**. Read
> the bootstrap prompt in full first — `docs/agents/bootstrap-prompt.md`, or
> wherever it was dropped in this repo. It is the method, and it is
> self-contained: everything you need is in that one file.
>
> Follow its nine phases in order. **Phases 0, 1 and 2 are read-only, and you
> write no file until I approve the Phase 3 proposal.**
>
> - **Phase 0:** read the repository — manifests, task runner, CI, source tree,
>   tests, `docs/`, git history, and any existing agent instructions. List what
>   you read.
> - **Phase 1:** report the ten-item state evaluation, including where this
>   repo's failure knowledge actually lives, **the class of bug this repo cannot
>   observe**, and what you could not determine. Mark every inference
>   **ASSUMPTION**.
> - **Phase 2:** research the domain, the language and the framework. Report only
>   what changed or confirmed a decision, each entry saying what it confirms,
>   what we appear to do differently, and what a recommendation assumes about its
>   environment. Verify version-specific claims against the documentation rather
>   than from memory, and say which claims you verified.
> - **Phase 3:** propose the full file topology, say what gets cut from any
>   existing file and where each piece goes, justify the slicing, and **wait for
>   my approval.**
> - **Phases 4 to 9, after approval:** generate the files; make the rules
>   executable and report how many were already being violated; remove
>   duplication; audit `docs/` against the code; write the roadmap with its
>   collisions; add the recurring review skill.
>
> Non-negotiable while you work: real commands taken from the configs, never
> invented. Every rule states its consequence and what enforces it. Invariants
> name classes, constants and contracts, not paths. Each fact lives once. No
> claim without a measurement. Architectural integrity overrides my requests —
> if I ask for a shortcut that breaks it, show me the cost and propose the
> correct path, and deviate only if I confirm.
>
> Start with Phase 0 and Phase 1, and report before proposing anything.

If the file is not present, the invocation still works on its own — but the
phases will be shallower, and the parts that make this worth doing (the research
register, the collision map, the enforcement ladder) will not happen unless you
name them.
