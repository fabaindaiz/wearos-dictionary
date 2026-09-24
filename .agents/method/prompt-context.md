---
# method-header — the only machine-readable part of this file. Keep it first.
# Identical in every document of the set; they travel together.
method:    claude-code-repo-method
set:       [context, evaluate, bootstrap, update, merge, sync, harvest]
lineage:   m-351cc8/main    # opaque id of the line this copy descends from
ancestry:  [m-7c41a9, m-bc50b0, m-a194bd, m-351cc8]  # root -> current; a fork appends its own new id
version:   24                   # monotone within a lineage; only its owner bumps it
forked_at: {lineage: m-bc50b0, version: 12}   # what this line branched from
digest:    "4b2506d91c12"            # content fingerprint; see Keeping the set versioned
released:  2026-09-24
upstream:  ""           # where this copy pulls updates from
adopted:   "2026-09-17"         # date this repository took the method
adapted:                        # local renamings and substitutions, one line each
  - "Spanish in the conversation, English in the repo; technical terms stay untranslated either
    way (gate, covering index, payload, rung), and the UI strings belong to the localization.
    ~3,000 lines of existing prose are still Spanish: measured 2026-09-21, staged in
    docs/roadmap.md §Proceso y herramientas"
  - "decisions log is docs/decisions.md, with an `Enforced in` column"
  - "troubleshooting layer is split in two: the skill troubleshoot-diccionario and docs/contratos-cruzados.md"
  - "the one gate command is ./gradlew check, which includes :tools:pythonTest"
  - "structural audit script is tools/audit_dictionary.py"
  - "skills are named and written in Spanish, with trigger-shaped descriptions"
  - "pack format reference lives in docs/formato-pack.md, owned by the packbuilder"
  - "discarded and open decisions are their own sections of docs/decisions.md, with a three-column
    shape, rather than inline marks on the rows; the enforcer-count excludes them (D-224)"
  - "a rule that leaves CLAUDE.md for a skill keeps a one-line invariant plus a pointer there, and
    no skill is left unnamed by any document (D-222)"
declined:                       # deltas deliberately not taken, with the reason
  - "v7 Three agents, one source: this repo is single-agent on purpose — no .cursor/, no
    .github/copilot-instructions.md, no AGENTS.md. Its asymmetry is already the policy here:
    the core invariant is held by a hook, the shared vectors and the gate, not by prose in
    an agent's file. Reopen the day a second agent's rule file appears in the tree."
  - "v3 Workspaces: one repository. Nothing to do until there is a second one; reopen then."
  - "v21 artifact 6, marking rows inline as *decisions, not rules* and *discarded, with the number*:
    measured, the artifact's spec is byte-identical to v7's, and this repo already meets the
    guarantee by section rather than by mark. Re-proposing it would be a rename, not a delta"
---

# Method context — the shared reference

> ## This file is not a prompt
>
> **It is a library.** There is nothing here to paste and nothing to execute. It
> holds what more than one of the executable prompts needs, so each of them can
> stay about its own job: the method header and how copies are matched, the
> enforcement ladder, the principles, the engineering standards, the
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
| `prompt-context.md` | the shared reference every executable prompt reads from | **no — library** | nothing |
| `prompt-evaluate.md` | assess a repository's AI instruction system | yes | one Markdown report |
| `prompt-bootstrap.md` | build the instruction system into a repository | yes | the instruction system |
| `prompt-update.md` | bring an older copy up to the current release | yes | edits, after approval |
| `prompt-merge.md` | join two copies that both moved after a common point | yes | the merged bundle, after approval |
| `prompt-sync.md` | a meta-session over every carrier: gather, apply in each, align | yes | every carrier, after approval, each backed up |
| `prompt-harvest.md` | the local step: what one repository learned, as candidates | yes | that repository's `tracking/` only |

Beside them, `changelog.md` holds the method's version history. It travels with the set and is
read by an update to list the deltas; it is not a prompt.

Every one carries the identical method header above. **Same `ancestry` and
`version` everywhere means you hold a matched set**; if they disagree, someone
mixed releases, and the oldest is the one to trust least.

**The set can grow.** Nothing should hard-code how many documents it has: read
the `set` field, and list what is actually present next to this file.
`prompt-evaluate.md` is required to do exactly that.

**Who owns what.** Each executable prompt owns its own procedure and nothing
else. Everything they share — the set and which one to run, the method header
and how copies are matched, the enforcement ladder, the principles, the
engineering standards, the artifacts, adopting into a repository that already
works, the platform's loading mechanics, workspaces, model tiers, the pre-flight
rules, and how the method improves and travels — lives here, once. If this file
is not beside a prompt that needs it, that prompt says so and stops: the
procedure without the reference produces plausible output with no argument
behind any of its rules.

**Precedence, when two documents disagree:** this file is the record for
principles, artifacts and standards; each executable prompt is the record for
its own procedure. A disagreement is a bug in whichever document is not the
record for that subject.

**Every invocation carries a `Reads:` list**: one line per file, its path
relative to `.agents/`, followed by the sections it needs, each written `§` and
the exact heading text; a file with no `§` is read whole. No job needs the whole
of this file, and nothing in it is written to be read in sequence.
`tools/bundle.py` declares the same lists, `digest --check` fails when a named
heading does not exist, and `bundle.py report` measures what each session type
costs to load.

**These documents are written in English and stay in English**, whatever
language the repository they are pointed at documents itself in. Two practical
reasons: the method is matched and merged across repositories that do not share
a language, and technical identifiers are English everywhere anyway. A
repository whose prose is in another language keeps its prose. Reports
and conversations follow the reader.

**They carry no project, product, person, customer or organisation, and nothing
that lets one be reconstructed.** Every example is stated by its mechanism, never
by where it came from. Keep it that way when you edit them: these files get
forwarded, and principle 20 is the rule, with the tool that checks it.

---

## Using the set (for the human)

1. Copy the whole bundle into the target repository as `.agents/`. **If that
   repo already has one, do not overwrite it** — put the new one in
   `.agents/incoming/` and run `prompt-update.md`: the old header is the only
   record of what that repo already adapted and turned down.
2. Open the agent at the repository root.
3. **Run `prompt-evaluate.md` first.** It writes one report and changes nothing,
   so it is safe on any repository. Its report says which document you need
   next, and sometimes the answer is none.
4. Paste the invocation it points at — each executable prompt carries its block
   **at the top of its own file**. *Which document to run* below covers the
   awkward cases.
5. Correct the Phase 1 report and the Phase 2 research. **This is where your
   judgement enters and it cannot be automated:** you know which of the repo's
   conventions are decisions and which are accidents; the agent cannot tell
   from the outside, and assumes they are decisions until you say otherwise.
6. Approve the Phase 3 proposal; nothing is written before it. In a repository
   that already works, **read the guarantee → existing-file table carefully**:
   it is where a helpful adoption turns into an unwanted rewrite.

The first pass is a long session and produces roughly: a root `CLAUDE.md` under
200 lines, one nested file per natural area, two to four skills, a permissions
file, a decisions log, a references register, a roadmap, a changelog, and — the
part most people skip — **a script that checks the structural rules the prose
claims.**

7. From then on, every session runs **The session loop** in
   `prompt-bootstrap.md` (its working invocation). The bootstrap is the cheap
   half; the loop is what the files were for.
8. Keep the method moving. Sessions capture learnings at step 8;
   `prompt-harvest.md` writes them as candidates in `tracking/`; a release
   (`prompt-sync.md`) applies the generality test across every carrier's
   candidates, cuts the next version, and it reaches your other repositories
   through the same meta-session or their own `incoming/`. It is the only part
   that compounds across projects rather than within one.

**On the length of these documents.** The method argues for a 200-line budget
and is itself long. `CLAUDE.md` is **loaded into every session** and paid for
every time; this is a **procedure read on demand, in parts**, and each
invocation's `Reads:` list names the parts. Budget what is always loaded; be
generous with what is fetched deliberately — and apply the same split in the
repo you are bootstrapping.

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

**A rule the agent had loaded and broke anyway is the signal to raise its rung**
— a template the tool copies, a command that refuses — keeping the prose only to
explain it. Repeating it louder changes nothing: in one repository a documented
trap was broken twice in one session by the agent that had just read it, and in
another session a formatter was run on a dirty tree while the rule against it sat
in the always-loaded root file. The first was fixed by a template copied by a
command; the second was raised to a safe command beside the unsafe one, which
still runs unguarded — half a raise, and the half left is where it will recur.

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
| Layering in a rendered view | "an element's position decides what it is drawn over" | a validator walking the view's data | a data **format** in which an element names a *layer*, never a number — so an element whose number contradicts its layer cannot be written down |

That last row is from the worked example at the end, and it is the cleanest
instance of rung 4: the check did not get better, **the thing being checked
stopped being expressible.**

---

## The principles

Each one states what it prevents, because a principle whose failure
mode you cannot picture will not survive contact with a deadline. The first
thirteen are about the repository; 14 to 17 are about the session and the
process it runs in; 18 and 19 are about how code is written and how the method
enters a repository that already has its own way of doing things; 20 is about
what may leave a repository at all.

### 1. Every rule names its consequence and its enforcer

Not "use X". Write: **what breaks if you do not, and where it is caught.**

> **Example.** A platform clock call is clamped to a small bound — unclamped, it
> reports the time since its subsystem started (tens of seconds after an idle
> wait), which starts a timed sequence far from its beginning. Enforced in a
> named constant on the clock, cited by the rule.

Compare with "clamp the clock value", which any later session will read as
advice and drop when it is inconvenient.

**Prevents:** rules being negotiated away by an agent that cannot see the cost.
**Test:** for every rule in your root file, can you name the file, script or
command that would catch a violation? If not, it is on rung 1 — either promote
it or admit it is guidance.

**And the failure mode of this principle itself, which is worse than ignoring
it: a rule that names an enforcer that does not do the job.** A citation to a
check that was never written, or to a real check that covers a different rule,
reads as rung 3 and behaves as rung 0 — so nobody goes looking for the gap, and
the rule is *more* trusted than an honest `—` would make it.

> **Example.** Two area files in one repository cited enforcers that did not
> hold: one named a check that had never been written, the other named a real
> check for an unrelated rule. Both were written **in the same session as the
> checks themselves**, which is how fast this happens.

Make it checkable: have the audit verify that **every enforcer cited in the
documents exists** in the script that is supposed to define it. That catches the
first kind mechanically and is perhaps ten lines. The second kind — a real name
cited for the wrong rule — survives it, and stays human review; say so where you
write the rule rather than implying coverage you do not have. **`—` is a better
answer than a wrong one**, because `—` is read as "this can break silently" and
a wrong citation is read as "this is covered."

This is why the audit's subject is not only the source tree. **The instruction
system is part of what the gate checks**: that every document in the map exists,
that every decision cited is defined, that every enforcer named is real. Those
are the claims that rot fastest, because nothing else ever runs them.

### 2. Rules are checked, not remembered

Anything verifiable by reading the tree should be verified by a script that runs
in the gate. Write it early, and expect it to fail on day one.

> **Example.** When the worked example's structural audit was first
> written, **every one of its rules was being violated somewhere** — every one of them documented, every
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
  Not only the map: every repository path any instruction document names — area
  files and skills included — resolved and checked, with exemptions *listed*
  (ignored paths, patterns, a history table with a reason per entry), never
  inferred. A manual sweep kept missing a dead pointer until one repository
  made this a check.
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
numbers and not paths. `Config.SCHEMA_VERSION migrates one step at a time` is an
invariant; `see src/config.py:412` is a bookmark that will be wrong within a week.

Area files (`<area>/CLAUDE.md`) may cite paths freely; that is what they are
for, and they live next to what they describe.

**Prevents:** an instruction file that quietly becomes fiction after a refactor.

### 5. Each fact lives once, and the root file is invariants plus a map

The root `CLAUDE.md` holds what must hold **always**. Everything else points.
The map is a table of *question → document*, because that is how a mid-task
agent actually searches.

> **Example.** One root file went **from about 800 lines to about 200** when the measured detail
> moved to the document that owned each number. Nothing was lost; it stopped being
> paid for on every request.

**Prevents:** a root file whose middle nobody reads, and two copies of a rule
that will drift.

### 6. Measure before claiming, and record what the measurement killed

A number belongs in the document that owns it, together with how it was
obtained. And when a measurement **kills** a belief, that is the most valuable
entry you will ever write.

> Measuring the renderer's batching killed two claims at once: a comment in the
> code asserting that everything still drew in one batch, and an earlier
> analysis that had counted layers across the whole scene instead of the visible
> ones. Actual cost of the interleaving: **about a third more batches, not an
> order of magnitude** — so the layering rule stayed.

Also record checks you decided **not** to write, with the number that decided
it: a check for unassigned exports would report a couple of dozen cases, almost
all legitimate, and a check that cries wolf twenty times for one real hit gets
switched off. That is a decision, and without the number someone will propose it again
every quarter.

**A number carries its date and its environment, not only its method.** The same
commands on the same commit give different answers in two environments — a stale
virtualenv earlier on `PATH`, a CI runner on another interpreter, an unpinned
stub package — and a number written without saying where it came from becomes
the baseline for someone measuring somewhere else. So: numbers written into a
document come from the environment the repository declares; a figure is either
re-measured or left with its old date, and **a date is never bumped without
re-running**; and when a measurement disagrees with the written one, first
establish *which binary produced each* — that one check has turned three
separately recorded "anomalies" into one fact.

**A retraction is written everywhere the claim was.** When a finding turns out
false, correct it in every place it was stated — the docstring, the exported
report, the document — saying what it was and why it was wrong, and add a new
log entry rather than editing the old one. A finding corrected in one of three
places is still being believed in the other two.

**The same holds for a rule and for a decision, and there the stale copy does
worse than being believed: it is followed, and copied forward.** Correct a rule
where it is *loaded* — the root file, the skill, the rule file an assistant
actually reads — not only where the mistake was found; and when a decision is
reversed, the reversed row says so in place, with a pointer to what replaced
it, and keeps its measurement. A superseding row that cites the old one is not
enough: readers arrive at the old row from the index, and it still reads as live.

> **Example.** One repository corrected a shipping fact in a new section of the
> document where it was found; the loaded guardrail and the other statements of
> the rule stayed wrong for most of a week, and the changelog entries written
> after the correction repeated the wrong step. A second reversed a decision and
> did not mark the old row: several places went on describing the reverted
> behaviour as current for days.

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

> **Example.** A configured list was silently emptied by the build step. The
> development build showed the label; the shipped one showed a raw index. Nobody
> could see it, because nobody on the development side could run the shipped
> artefact.

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

**A pre-ship check is half of it; the other half is observing after shipping.**
When the software runs where the developers cannot observe it, debugging is a
protocol run through whoever can, and each part of it has failed once without
the others:

- **Every failure that can be silent there gets a readout anyone present can
  report**, and a readout shows what was *asked* beside what *happened* — at a
  glance, a request that worked and one that was silently dropped look the
  same. A readout that cannot find its subject shows a distinct *blocked*
  state, never blank. A symptom that arrives with no readout gets one as its
  first fix.
- **Every build shows its identity there**: when, which commit, whether the tree
  was dirty — generated by the build step, never typed. Without it, "the fix did
  not work" and "that runtime runs yesterday's build" are one report, and every
  cache between you and that runtime makes the second likely.
- **Questions only that place can answer live in one standing brief**: the
  question, the readout that answers it, what the answer unblocks. A pass
  without it returns impressions; with it, decisions — which go into the
  decisions log.
- **A claim you could not verify says so in its changelog entry, and its
  question joins the brief in the same change.** Before asking for a pass,
  reproduce the report on the nearest build you can run: in one repository, two
  faults reported from the unobservable runtime reproduced on a build the
  developers could run, once the input handling was ruled out.

The readouts are code and the identity is generated by the export (rung 3–4);
the brief is a document (rung 1), and should say so.

**Prevents:** the only bug class that reaches users with no warning.

### 8. External knowledge is registered by what it changed

Reading the field's literature is part of the work. Collecting links is not.
**An entry earns its place when it changed or confirmed a decision.**

Every entry must say: what it confirms in what you already do, **what you do
differently on purpose and why**, and what it suggests that you have *not*
applied yet. Point at the decisions it produced.

> **Example.** A platform's official synchronisation recipe combines three
> corrections. One project uses two — deliberately — because the third returns
> nothing usable on the target it ships to, and its reference entry says so. Without that note,
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
- **What each idea costs the core invariant** — a table: does this idea break
  the one thing that must always hold, with a yes/no and a sentence per item. In
  one repository some entries were an honest `yes`, and one a `no` for the best
  reason: it turned something impure back into data.

**Prevents:** a roadmap that is a wish list, and a feature discovering the
architectural conflict halfway through implementation.

### 10. Record the non-decisions and the deliberate duplication

The log is not only for what you did. Write down what you **chose not to do**,
or the next session will do it, sincerely, as a cleanup.

> **Example.** Several small search routines are duplicated **on purpose**: their
> return values encode different semantics, the collections have different
> shapes, and — decisively — the invariant's own test would not catch an
> off-by-one in a merged version, because it would affect both directions
> equally.

> **Example.** There is no shared base class for a family of similar interface
> rows, and it is not a backlog item: single inheritance plus two different
> parent types make it impossible without wrapping each row in a container,
> which is exactly the thing it must not be. Residual duplication: a few
> identical lines per file.

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

**For the same reason, a record written by parallel sessions never takes a
sequential number.** Two sessions on different branches or machines cannot see
each other's "next number", so both write `D-018` and the merge makes one of
them lie. Changelog entries, decision rows and roadmap items carry an id built
from what the writer *can* see — the repository and the record's own text:
`<kind>-<repo6>-<content6>`, minted by `bundle.py id` (*Workspaces* has the
scheme). Numbers stay only for what a single writer, the release, produces:
principles, artifacts, phases, method changelog rows and versions.

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

> **Example.** A list screen passed every assertion it had: the right rows, the
> right labels, the state surviving a round trip to disk. Rendering the screen
> and looking at the image killed two defects in one pass. An item outside the
> selection was dimmed with transparency, which let the background through and
> made the item's own title unreadable over one of the backgrounds. And an
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

> **Example.** Adding user-made lists to a small fixed-height screen came down to
> three questions: whose the lists are, where the selector goes, and how one is
> edited. The second was presented as three layouts priced in the unit that
> actually binds — screen height, counted in visible list items — and the answer
> took one word. Everything else in the feature
> was decided without asking.

**Content the owner authored is not yours to complete.** Words in their voice, a
dedication, a design or a sequence they made by hand: an empty slot there is a
choice until they say otherwise — leave it empty and say so; an element they left
unused may be unused on purpose. One repository filled a slot in the owner's
content that looked like free space; it was empty on purpose, and the change was
reverted. A pick you had to make inside their content is labelled
*provisional* in the entry. And a change that touches something they told you to
leave alone is reported under its own heading with the reason, never as a side
effect.

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
> forces a manual dance — copy, format the copy, diff, apply by hand, re-run —
> measured in one repository at under a minute, several times in one session.
> Hit by every session that touches a file, for a year, it is the clearest
> possible case for the one command that replaced it (a short script, written
> once; its rules are in `prompt-bootstrap.md`, *Working safely in a tree you do
> not own*). The thirty seconds is why nobody wrote it.

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

**When the red step could not be watched — a bug fix found after the code, a test
added in review — prove the test bites by mutation.** Break the implementation
in the one place the test is about and confirm that exactly that test fails,
then restore it. Make the mutation assert that its target text exists before
changing it: a formatter that rewrote the line turns the mutation into a no-op,
and a no-op mutation "survives" for the wrong reason. This is cheap, it is the
same evidence the red step would have given, and it catches the stub that is
never consulted and the test that asserts the absence of the behaviour it is
named for.

**Assert the reason, not only the outcome.** A test that pins *which* item a
check reported lets a whole class of mutation survive: with the check deleted,
the same item is still reported — by another check, for another reason — and the
assertion passes. Pin the sentence the check produces, or whatever distinguishes
one reason from another. Measured in one repository: of a set of planted
mutations, all but one were caught, and that one survived exactly this way until
its test asserted the reason instead of the path.

And **when the code, its test and its comment disagree, none of them is the
specification.** Prose written from code in progress records the bug as intent;
a test written to the comment asserts what was never implemented. Go back to the
requirement.

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

### 20. Nothing private travels, directly or by reconstruction

**Nothing in the bundle may let a reader identify a private repository, its
owner, organisation, customers, users or infrastructure, or any person who uses
or iterates the bundle — directly, or by putting several details together.** The
bundle is published through its public carriers and forwarded further, so
whatever enters it is public for good, and no later edit recalls it.

The leak is rarely a name. It is a combination: an exact threshold, a quoted
comment, a field name and a domain noun, each harmless alone, together enough to
find the one codebase they came from — re-identification works from
combinations of ordinary attributes (`../references.md`, *Privacy and
re-identification*). So the lesson is kept and the fingerprint is removed:

| What | Becomes |
|---|---|
| business figures, volumes, revenue, prices, currency amounts | an order of magnitude or a ratio — "hundreds a month", "about 9 %" |
| measured constants that fingerprint a codebase — thresholds, counters, sizes, an exact N of M | a ratio or an order of magnitude, one significant figure |
| a verbatim quote from a private carrier's code, commits or documents | a paraphrase — a quote is exactly what code search finds |
| a private carrier's identifiers, schema fields, endpoints, files, config keys, library versions, platform calls | the role it plays — "a projection field misspelled by one letter", "a platform clock call" |
| product and domain nouns that narrow a private carrier | a neutral kind — "a transactional service", "an interactive renderer", "a hardware-bound service" |
| personal context — time zones, countries, the languages of maintainers or users, habits, schedules, who can reach which device | removed, or made neutral — "a runtime the developers cannot observe" |

A generic domain word in a method table that describes a *kind of software* (a
"Payments / ledger" row) is not a leak; the same word in an example that came
from one carrier is.

**Public and private carriers.** A carrier that is itself public is not a secret:
its identity, and what its public code already shows, may stay. Every other
carrier is private, is described only by a neutral kind, and **never has its
carrier id written beside a description** — the id is what would join every
other detail to it.

**Privacy wins over record-keeping.** Where this rule meets "never lose
information", this rule wins. Sensitive detail is generalised while the lesson
is still useful, and deleted when the specific detail was its only value, or
when it is obsolete and nothing current depends on it. Changelogs, history,
evidence and every other record are **not exempt because they are history**: a
record that is otherwise never rewritten may be rewritten or deleted by a privacy
scrub, and the release that does it says so. A deletion is recorded generically —
"carrier-specific detail removed for privacy" and the date — never by restating
what was removed.

**It holds whether or not you remember loading it.** An agent writing into
`tracking/`, a note, a method document or any other file that travels applies
the rule even when this section is not in its context — and does not trust
itself to have applied it. `bundle.py privacy` checks the tree against generic
patterns (addresses, home paths, forge URLs, currency amounts, time-zone
offsets, pinned versions, chosen ids, code identifiers in evidence, a record id
beside a domain noun) and against this machine's own
`~/.config/agent-guides/private-terms.txt`, which is never committed;
`digest --check` runs it, and the home repository's hooks run it before a
commit. The one override is a `privacy-allow: <reason>` marker on the line,
written only when the user explicitly says so; every run lists every allowance,
so none is silent.

**Prevents:** a private codebase, its customers or the people behind it being
found by anyone who reads a public copy of the bundle — the one mistake in this
method that cannot be reverted.

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
- **A comment describes the code as it stands, never the change that produced
  it.** No ticket identifiers, no "previously / used to / no longer / now", no
  "out of scope for this change". Each is true for one release and silently
  false afterwards. The change's story belongs in the commit and the changelog,
  which are dated; a comment is not.
- **Durable documents never cite session-local artefacts.** A plan directory, a
  review's finding codes ("since F1 removed…"), a scratch file: the next reader
  cannot resolve them. State the fact, not the task that produced it. Both rules
  are cheap to check in the structural audit, and a repository that adopted them
  found about ten comment violations and a document that was unreadable without
  the plan behind it.

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

## The artifacts

Build them in this order; each one assumes the ones above it.

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
A table. Questions in the words someone would actually ask. One row is always:
before a design decision or before claiming done, consult
`.agents/knowledge/INDEX.md` and read only the notes it points to.
```

**What does not go here:** any number that has an owner document, anything the
formatter already enforces, anything you cannot name an enforcer for, and every
kind of area detail. If a section is growing, that is the signal that it has
become a document.

### 2. `<area>/CLAUDE.md`

One per natural area, and **only where the area genuinely has rules of its
own** — `src/`, `tests/`, `infra/`, `migrations/`, `web/`, `packages/<x>/`.
Contents: internal structure, the patterns used there, **the exemplary file to
copy**, the mistakes that have already been made there. No commands, no repeated
invariants.

Nested files load only when a file in that directory is read (*The platform's
own mechanics*), so anything that must hold *always* belongs in the root file.

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
actually lives** (`prompt-bootstrap.md`, Phase 1, item 7) — often not the git history.

### 4. `.claude/settings.json`

```json
{
  "$schema": "https://json.schemastore.org/claude-code-settings.json",
  "permissions": {
    "allow": ["Bash(<read-only repo commands>)"],
    "deny": ["Edit(**/*.<generated>)", "Read(./<secrets>)", "Edit(./<vendored>/**)"]
  }
}
```

`allow` the repo's own read-only commands so the agent stops asking. `deny`
secrets, generated files and anything destructive — **a rule that can be
enforced by permissions should not be left to judgement.** Add `hooks` for
lifecycle rules that must run deterministically.

**File paths are checked against `Edit(...)` and `Read(...)` rules only.** A
path rule written for `Write`, `NotebookEdit` or `Glob` is accepted, **never
consulted**, and warned about at startup — so a `deny` written that way protects
nothing while looking like it does. Use `Edit(path)` where you mean "do not
change this file" and `Read(path)` where you mean "do not even look at it". This
is the exact shape of a rule that reads as rung 3 and behaves as rung 0.

Commit this file; keep `settings.local.json` out of git. The `.claude/`
directory is how the repo explains itself — it belongs to the team.

### 5. `.claude/logs/agent-changelog.md`

```markdown
## YYYY-MM-DD · s-<repo6>-<content6> — <one-line title>
**What.** What changed, concretely.
**Areas.** Files or folders.
**Why.** The reason, including the request that prompted it.
**Architecture.** ✅ Complies · ⚠️ Deviation · REVIEW — and why.
**What went wrong on the way.** What the first attempt got wrong, and what
caught it. Omit only if nothing did.
**What was left undone.** Debt this change created or walked past, named, so the
next session does not rediscover it as a surprise.
**Deviation from the plan.** Where what was built departs from what the human
approved, and the measurement that decided it. Omit when there is none.
**Not verified.** What could not be checked in this environment, and where the
question now waits. Omit when everything was.
**Measured.** The number, if a claim was made.
```

Newest on top. The header of the file states the obligation and the incident
that motivated it. The `s-` id is minted once with `bundle.py id s "<title>"` and
never recomputed; it is how another entry, a decision or a roadmap item cites
this one. Entries written before ids existed keep what they had.

**Put the format reference at the end of the file, and nothing that can capture
text between the top of the file and the first entry.** "Newest on top" is a
*relative* instruction, so whatever sits directly under the title is where the
next writer inserts — and if that is the opening fence of the format example,
the entry lands inside a code block that then never closes. Every entry below it
renders as source, and the defect is invisible in a diff, so neither the author
nor a reviewer catches it by reading. Observed twice in one repository, by two
different sessions, and never where the reference happened to sit at the
bottom. The same
shape applies to any document agents edit by insertion: **the insertion point
has to be unambiguous by structure, because the instruction will be read
quickly.**

**The last three fields are the ones that pay for the file.** A log of successes
is bookkeeping; a log that says *"the dimming made the item transparent and only
the rendered frame showed it"* or *"this file is now one function away from its line
budget and splitting it is a structural call I did not make"* is the only
mechanism by which one session warns another. Write the failures in the same
voice as the successes — an entry that hides a wrong turn will send the next
session down it.

### 6. `docs/decisions.md` — the index of everything settled

ADR-lite, as tables grouped by subject. **Four columns, and the fourth is the
one that matters.**

```markdown
| Id | Decision | Why | Enforced in |
|---|---|---|---|
| d-abcdef-123456 | <the decision, imperative> | <the cost of the alternative, with the number> | <script, class, constant, config — or "—" for a decision with no enforcer> |
```

Rules for it:

- An id is **minted once** with `bundle.py id d "<the decision>"`, **frozen at
  creation** — a later edit to the row never recomputes it — and never reused;
  `CLAUDE.md`, the changelog and the roadmap cite it. Ids written before this
  scheme (`D-001`, `d-abcdef-017`) stay valid as written and are never rewritten.
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

  Produced: d-abcdef-123456, d-abcdef-654321.

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
### i-<repo6>-<content6> · <Idea>
What it is, in two lines.
**What it collides with.** The rule, by its id, and why the collision is real.
**What is already in its favour.** The mechanisms that exist.
**What must be decided first.** Questions, not tasks.

## Closed by measurement
Ideas retired by a number, with the number, so they stay retired.

## What each one costs the invariant
| Idea | Does it break <the core invariant>? |
```

Each item's `i-` id is minted once with `bundle.py id i "<idea>"` when the item is
written, and it does not change when the item's state or wording does.

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

> **Example.** Two entries in one repo's roadmap are blocked outside: one needs
> licensed assets that are absent on almost every machine, and the other needs a
> platform API that an upstream proposal has not shipped. Both say so, and both name the
> event that would reopen them. Neither has been re-proposed since.

**Finishing an item edits the roadmap in the same change that finishes it.** Not
in a follow-up, and not "when things settle": a roadmap that describes a feature
as planned while the feature is running is worse than no roadmap, because it is
believed.

**Give the roadmap a `## Process and tooling` area**, alongside the product
areas. This is where friction goes once it has been hit twice (principle 17),
and each entry takes the same shape as any other, plus the arithmetic:

```markdown
### i-<repo6>-<content6> · <The friction, named as what it costs>
**What happens now.** The manual steps, counted.
**Cost.** <seconds or steps> × <how often> × <how many sessions>.
**The fix.** One line if it is one line.
**Seen in.** The sessions that hit it — at least two, or it is not here yet.
```

**Why it lives in the roadmap and not in a file of its own.** A separate
friction log is a file nobody opens: process work loses every prioritisation
argument against product work when the two are in different documents. In the
roadmap it is ranked against features, by the same human, in the same sitting —
which is the only place the comparison is honest. The same holds for debt
written anywhere else — a decision row marked pending, a reference's "not
applied yet", a review's leftovers: each gets a one-line pointer in the roadmap,
and its reasoning stays where it is. It also inherits the five
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

Checked against each vendor's documentation on 2026-09-24; the sources are in
`../references.md`, *Writing for an agent*.

| | Claude Code | Cursor | Copilot |
|---|---|---|---|
| Always-on file | `CLAUDE.md` at the root; `AGENTS.md` only when there is no `CLAUDE.md` | `.cursor/rules/*.mdc` with `alwaysApply: true`; `AGENTS.md`, nested ones included | `.github/copilot-instructions.md`; `AGENTS.md` (nearest wins), root `CLAUDE.md` or `GEMINI.md` on the surfaces that read agent instructions |
| Per-area rules | nested `CLAUDE.md` loaded **on demand** when files there are read, **and** `.claude/rules/*.md` **auto-attached by glob** via `paths:` | `.mdc` rules with `globs:` ("Apply to specific files"); nested `AGENTS.md` | `.github/instructions/*.instructions.md` with an `applyTo:` glob — **support differs by surface** (Visual Studio: chat only) |
| On-demand procedures | skills, chosen from their `description` | rules chosen by `description` ("Apply intelligently") or by @-mention | **agent skills** (`SKILL.md` in `.github/skills/`, `.claude/skills/` or `.agents/skills/`), chosen from their `description` — cloud agent, code review, CLI and VS Code agent mode; **prompt files** (`.github/prompts/*.prompt.md`), run by hand as a slash command in VS Code, Visual Studio and JetBrains (public preview); **custom agents** (`.github/agents/`), profiles selected by hand, or used by the cloud agent from the task's context unless `disable-model-invocation: true` |
| Deterministic enforcement | **hooks** on lifecycle events | **hooks** in `.cursor/hooks.json`; a hook can deny an action | **hooks** in `.github/hooks/*.json`, `preToolUse` can deny — **only on the cloud agent and the CLI** |
| Path exclusion | `permissions.deny` | `.cursorignore` — **not a boundary** for the agent's terminal and MCP tools | content exclusion, set in repository or organisation **settings**, not a file; IDE agent mode does not honour it |

**ASSUMPTION beyond that date:** all three products move quickly, and this table
is the shape rather than the specification. Verify file names, frontmatter
fields and loading behaviour against the versions actually installed, and mark
what you could not confirm.

### The asymmetry that decides the design

Read the table by column and one conclusion falls out, which is the single most
useful thing on this page:

> **Every surface now has some machinery, and no two have the same.** Hooks
> exist in all three, but Copilot runs them on two of its surfaces and not in
> the editor; exclusion is a permission in one, an ignore file the terminal
> walks past in another, and a settings page in the third. So a guarantee that
> must hold **cannot depend on any agent's machinery.** It has to live where
> every agent and every human meets it: **the gate, a script, a type, a
> schema** — rung 3 or rung 4.

Working with three agents is therefore not extra work bolted onto the method. It
is **another, sharper reason to push every rule up the ladder**, and a good test
of whether you did: *if the only thing this rule has is prose in one agent's
file, the other two are already breaking it.* A hook is worth writing for the
agent that runs it; a rule that holds only through one agent's hook is a
deviation, and is written down as one.

Per-area targeting is the one place the shapes now line up: Claude Code's
`paths:`, Cursor's `globs:` and Copilot's `applyTo:` are three spellings of one
glob, so per-area rules can be **generated from one source** rather than written
three times.

### What to write where

| Content | Goes | Why |
|---|---|---|
| The invariants — what must always hold | **all three always-on files**, from one source | they are the only thing every agent sees |
| Area depth | nested `CLAUDE.md` or `.claude/rules/`; Cursor globs; Copilot `applyTo` | three spellings of one glob — generate them from one source |
| Procedures — verify, commit, troubleshoot | skills; Cursor description-chosen rules; Copilot skills, or a prompt file on the surfaces without them | Copilot also reads `.claude/skills/` and `.agents/skills/`, so one skill folder can serve both; a prompt file that runs a procedure points at its source rather than copying it |
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
| `.claude/rules/*.md` with `paths:` | **on demand**, when a file matching the glob is read | per-area targeting without a nested file; it mirrors Cursor's and Copilot's globs, so one source serves all three |
| `@path` imports inside an instruction file | **at launch**, and they spend context | importing a docs tree into the root file defeats the budget — **prefer nesting over importing**; the one import worth its cost is `@AGENTS.md` as the root file's first line, the documented bridge that keeps one source |

**What is still not unconditional, and it is load-bearing:** every per-area
mechanism is *reactive*. A nested file loads when the agent reads a file in that
directory; a path-scoped rule loads when a matching file is read; a skill still
needs the agent to judge it relevant. None of them fire on a file the agent
never opens. **Design for that:** anything that MUST hold whether or not a
particular file is touched belongs in the root file, in a hook, or on rung 3 or
4 of the ladder.

There is no ignore-file equivalent for hiding paths from the agent either. Use
`permissions.deny` — with `Read(...)` for what must not be seen and `Edit(...)`
for what must not be changed — and the repository's own ignore rules for
generated noise.

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
| `.cursorrules` or `.cursor/rules/*.mdc` | content into the source; the files stay as Cursor's surface | keep the globs — they are the per-area rules |
| `AGENTS.md` at the root | often **is** the source; `CLAUDE.md` imports it with `@AGENTS.md` | Cursor and Copilot read it directly; Claude Code reads it only when no `CLAUDE.md` exists |
| `.github/copilot-instructions.md`, `.github/instructions/*.instructions.md` | stay, regenerated from the source | Copilot's own surfaces; the `applyTo` globs are its per-area rules |
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
| It names a **constraint only the human knows** — a runtime nobody here can observe, a team, a deadline | Anything you could determine by looking for five more minutes |

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

Evaluate comes before the others and writes nothing, so it cannot conflict
with any of them. Bootstrap and update are the ones a single repository runs to write, and they are
designed so that **neither can undo the other's work.** The header is what makes that true:
bootstrap writes it, update reads it and carries it forward, and both leave it
correct.

| The situation you are in | Run | Because |
|---|---|---|
| No method header, no agent instructions of any kind | **bootstrap** | nothing to preserve; the full nine phases |
| No method header, but the repo already has `CLAUDE.md`, `AGENTS.md`, `.cursorrules` or its own conventions | **bootstrap, in adopt mode** | Phase 1 classifies what exists and **nothing is discarded**; see principle 19 |
| Header present, `version` lower than the incoming set's | **update** | only the deltas, triaged against this repo |
| Header present, same version, and the repo has learned something | **harvest** (`prompt-harvest.md`) | the repo is ahead of the method; it writes candidates, a release takes them |
| Header present, ancestries share a prefix and then **diverge** | **merge** (`prompt-merge.md`) | both lines moved; it stops at a reconciliation table the human approves |
| Header present but the artifacts it claims do not exist | **bootstrap, in repair mode** | the file was copied without the work; list what is missing and build it |
| Several carriers of the bundle at once | **sync** (`prompt-sync.md`) | one base, one table, one release, applied in each carrier |

**What update will not do.** It will not bootstrap. If the repo has the file but
not the artifacts, update stops, says which guarantees have no home, and hands
back to bootstrap in repair mode. An update that silently creates a decisions
log has skipped the phases that make a decisions log worth anything — Phase 1
and Phase 2 — and will fill it with plausible rows nobody verified.

**What bootstrap will not do.** It will not run from scratch on a repository
whose header has `adopted` set by this repository: it has already taken the
method, so a newer set reaches it by update, and missing artifacts are repair
mode, which bootstrap runs only when told. (A bundle just copied in from
elsewhere carries its source's repository fields; those are cleared, not
believed.) Rebuilding on top of work that is already there is how it gets lost.

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
- Every *extend* and every substitution is one line in `adapted`; every refusal
  is one line in `declined` **with the reason, written for a stranger** — the
  next update reads both (`../README.md`, *The fields that are this repository's*).

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

## Adapting to another stack

**What never changes:** the enforcement ladder, the principles, the artifacts,
the phases, the session loop, the engineering standards, and the requirement
that every rule name its consequence and its enforcer.

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
- **The workspace is what gets written: all of the repositories open here, and only those.** This
  is the one statement of the rule; every prompt points here. A machine usually holds more carriers
  of the shared bundle than a session has open; one that is not open gets no report read, no gate
  run and no commit reviewed, so nothing reaches it from here — it is named as not reached, and it
  receives the release from its own session or its own `incoming/`. `tools/bundle.py` takes the
  workspace from its arguments, else `AGENT_WORKSPACE`, else the local manifest; a command that
  writes refuses a workspace it had to infer, refuses to write outside it, and names every carrier
  it did not touch. All of them, too: a learning seen in two open repositories is a second
  occurrence, and that count exists only while both are open.
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
- **A release that has to reach several carriers is one meta-session, not several
  updates**: `prompt-sync.md`, run by `tools/bundle.py`. One base found by content,
  one reconciliation table, one release built outside every carrier, then each
  carrier by itself, and a close that `align` has to confirm.
- **Record ids are derived, never counted and never chosen.** Anything parallel
  sessions write into a repository's own logs — a decision row, a roadmap item,
  a changelog entry — gets `<kind>-<repo6>-<content6>`, for example
  `d-abcdef-123456`: `d-` decision, `i-` roadmap item, `s-` session (changelog)
  entry. `repo6` is the six hex of the repository's **carrier id** (`r-abcdef`),
  which is **random**: minted once by `bundle.py carrier-id --mint` and stored in
  the `carrier:` field of the repository's own `.agents/README.md` header, among
  the fields that are the repository's, so a splice keeps it and `align` ignores
  it; `bundle.py carrier-id` prints it and refuses when there is none. It is
  **never derived from the remote**: a hash of a remote someone can guess is
  reversed by guessing — confirmed in one carrier from 66 guesses — and would
  name a private repository in every id it prefixes. A row cited from another
  repository still says where it lives and survives renaming the directory; a
  prefix someone picks (`EDGE`, `API`) is picked independently in two
  repositories and collides. `content6` is the first six hex of the sha256 of
  the record's text at creation, whitespace collapsed, so two sessions that
  cannot see each other still mint different ids. Mint with `bundle.py id
  d|i|s TEXT...`, which refuses without a stored carrier id; if the id already
  exists, add a distinguishing word and mint again.

  **Minted once, frozen.** Editing a record never recomputes its id, because
  every citation of it would break. So the check — `bundle.py ids FILE...`, run
  by the host's audit — verifies the format, that the prefix is this
  repository's `repo6`, and that no id appears twice; it never re-derives the
  content hash. Legacy ids (`D-001`, `d-abcdef-017`, a changelog entry's `(15)`)
  stay valid as written and are never rewritten; new records use the new form.

**What a workspace makes cheap, and is the reason to tolerate it:** the harvest.
Seeing the same friction in three repositories in one week is the strongest
possible evidence that it belongs at level 3 of the promotion path. Write it
down when you notice it — the observation is only available from inside a
workspace, and it disappears the moment you close the other windows.

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

**If you are running with limited context or limited reasoning, say so in one
line in the first message, and hold to this list.** A small model that works
within its limits is useful; one that pretends to be large is worse than
neither, because its output is indistinguishable from good work until it is
acted on.

Do, in full: step 0's brief, the gate, the documents in step 6, the per-change
checklist — the mechanical steps, where a smaller model is reliable and most of
the method's value lies. **Capture** learnings and friction verbatim at step 8,
unjudged; recording needs no judgement and is never skipped. Prefer the
checklist to the judgement call, and an exemplary file to an invented pattern.

Do not:

- **Sample a codebase and imply coverage.** Say what you read and what you did
  not.
- **Make rung-4 decisions** — a type, a schema, a data format. They are the most
  expensive to reverse. Propose and stop.
- **Run the generality test.** It needs breadth across repositories you do not
  have in context; record candidates raw, marked `needs promotion review`.
- **Decide a trade-off with foreclosure in it.** Present the options with their
  costs and stop; principle 15 is satisfied by asking.
- **Silently drop a phase or a step.** Name it as skipped, with the reason: an
  announced skip is a scheduling problem, a hidden one is a defect with no
  symptom.

**The escalation sentence**, verbatim rather than something plausible: *"This
exceeds what I can do reliably here — it needs <the specific thing: whole-repo
context / image inspection / a design decision>. I have done <what was done> and
stopped at <where>."*

### Spending well

- **Read what the invocation names, not the whole method.** That is what each
  invocation's `Reads:` line is for.
- **Do not regenerate the brief when nothing moved.** "No change since the last
  session" is a complete step 0.
- **Cache expensive reasoning into the repo**, not into a longer conversation: a
  decision row is re-read at zero cost forever, a conversation is not.
- **Where a cheap check exists, run it before the expensive reasoning.** The
  gate before the review; the type checker before the argument about design.
- **Do not pay twice for the same read.** If Phase 0 listed the tree, later
  phases cite that list rather than walking it again.

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
| 4 | Every other repository | a release carries it: `prompt-sync.md`, or `prompt-update.md` for a single copy |

**The generality test, and it is strict.** Try to state the learning without a
single noun specific to your project. If you cannot — if it needs *our render
clock*, *our data format*, *our deploy script* — it belongs at level 2 and it
is finished. If you can, and the sentence still says something, it is a
candidate for level 3.

> **Passes.** *"A change is not done until the documents it falsified are true
> again."* No nouns. True of a web service and a firmware project alike.

> **Fails, and correctly.** *"Never advance visual state by accumulating
> delta."* Entirely about one domain. It is an excellent rule; it belongs in
> that repo's `CLAUDE.md`, and it appears here only as an *example* of a
> core invariant — which is the right way for a level-2 rule to earn a mention
> at level 3.

**The procedure is split in two, by who may write.** In a carrier,
`prompt-harvest.md` writes candidates and evidence into `../tracking/` and
nothing else; a release (`prompt-sync.md`, *Build the release, once*) applies
the generality test across every carrier's candidates and writes the notes,
principles and versions, once. A learning about building software rather than
about working goes through the knowledge base's admission and lifecycle
(`../knowledge/README.md`) instead of becoming a principle. What does not pass
waits in `../tracking/candidates.md`.

**The second filter: has it earned it?** A principle costs every future reader
attention on every future read. Add one only when you can name the failure it
prevents **and** point at an occurrence. A principle with no incident behind it
is an opinion that will be obeyed anyway, which is worse than one that is
argued.

### Keeping the set versioned, so other copies can catch up

A distributed document without a version cannot be updated — the receiving repo
has no way to tell what it already has. The **method header**, identical at the
top of every document of the set, is that record, and it carries more than a number:

| Field | What it is for | Read by |
|---|---|---|
| `set` | which documents this release ships as | checking you hold a matched set |
| `lineage` | which line of the method this copy descends from | matching two repos before merging anything |
| `ancestry` | root → current; the visible shape of the tree, with opaque nodes | seeing where two copies separated |
| `version` | how far along **that lineage** this copy is | the update triage, to compute the deltas |
| `forked_at` | the `{lineage, version}` this line branched from, or `null` for a root | making a merge conversation concrete |
| `digest` | a fingerprint of the set's **content** | catching two copies that claim the same version and are not the same |
| `upstream` | where this copy pulls from — a repo, a path, or `""` if it is a root | whoever runs the update; it is this repository's, like the three below |
| `adopted` | when this repository took the method | telling a prepared repo from one that only has the files |
| `adapted` | every local renaming and substitution, one line each | principle 19, so a rename is never re-proposed |
| `declined` | every delta deliberately not taken, **with its reason** | the update, so a decision is not re-litigated every release |

**The header splits into two kinds of field, and mixing them up is the most common way
a distribution goes wrong.** `lineage`, `ancestry`, `version`, `forked_at`, `digest` and
`released` describe **the set**: they travel with the body, always — on a descendant line
they are taken from the incoming set — and a copy that takes the body without them ends up
claiming a version it is not. `upstream`, `adopted`, `adapted` and `declined` — and, in the
bundle `README.md` only, `carrier` — describe **the repository** and stay put; the rule and its reasons have one home, `../README.md`, *The
fields that are this repository's*. After any propagation, `bundle.py digest --check` and
`bundle.py align` compare version, lineage and computed digest; equal digests with different
versions is the signature of getting it backwards.

**On lineage, and why the id is deliberately meaningless.** A lineage id is an
**opaque token** — `m-7c41a9` — never derived from a repository, a product, a
team or a person, so a copy can travel to organisations that use, match and fork
it **without learning anything about where it came from**; `acme-billing/main`
would leak a customer, a project and a team every time the file is forwarded.
**`ancestry` is what stays readable**: an ordered list, root first, of every line
this copy descends through. A copy that tracks its parent does not touch it; a
copy that forks **appends one new opaque id** and sets `lineage` to it. So a
reader sees the **shape of the tree** — how deep their line is, where two copies
separated — and never what any node *is*.

### Version numbers, and the collision they would otherwise have

**A monotone integer does not survive two writers.** If two repositories on
lineage `m-7c41a9` at version 6 both improve the method and bump to 7, there are
**two different version 7s on one lineage**, and the naive comparison calls them
identical — silent, and it discards one side's work. The fix is one rule, which
makes the collision impossible, and one check, which catches the rule being
broken.

**The rule: a lineage has exactly one writer.**

> **`version` is monotone *within a lineage*, and only the owner of that lineage
> may bump it.** Anyone else who changes the method **forks**: appends a new
> opaque id to `ancestry`, sets `lineage` to it, records `forked_at`, and
> continues numbering from where they branched.

That is rung 4 of the ladder applied to the method itself: changing the method
*is* forking, and forking is cheap. **Numbering does not restart at a fork** — a
line branching from version 6 makes its first release **7** — so `version`
still means "how much has happened", `ancestry` says whose 7 it is, and version 3
of two lines never looks comparable when it is not.

**The check: the digest.**

`digest` is a fingerprint of what the documents actually say, so equality stops
being a claim and becomes something you can verify. **The tool is the one
recipe**: `bundle.py digest [TREE] --check` recomputes every digest a header
declares and fails on any that is not true, and `bundle.py stamp` writes them.
In words, for a reader who cannot run it: each file's body with its frontmatter
stripped, concatenated in byte order of the path (never a locale's collation,
which skips punctuation and changes the result between machines), sha256, the
first twelve hex characters. The method digest covers the set's documents; the
knowledge digest covers every `.md` under `knowledge/notes/`, recursively, bodies
only, and refuses an empty set.

Twelve characters: short enough to compare by eye in a report, long enough that
two different sets will not share one. The header is excluded so that writing
the digest into the header does not change the digest. A second recipe written
for the occasion is how a locale once got into a published digest; do not write
one.

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

1. Mint one new opaque id with `bundle.py id m PARENT...` — derived from the
   parents, never chosen.
2. Append it to `ancestry` and set `lineage` to it.
3. Set `forked_at` to the `{lineage, version}` you branched from.
4. Continue numbering: parent's version **+ 1**. `bundle.py stamp --fork` does
   steps 2 to 4 and writes the digests.

Then the two lines evolve independently, and anything general that either learns
travels the other way through the harvest — not by overwriting a file. Never
regenerate a lineage id, never make it mnemonic, and never encode a date, a name
or a counter in it — each of those turns an opaque token back into a leak.

Then the numbering discipline:

- **Versions are cut by a release, never by a carrier.** A release bumps
  `version` in every document of the set (`bundle.py stamp`), and adds one row to
  [`changelog.md`](changelog.md) — saying what a reader *does differently* now,
  not what was edited.
- **Never renumber the principles.** Append. A repository referring to
  "principle 14" must still be right after the next revision, exactly as an enum
  written to disk is appended to and never inserted into. If a principle dies,
  mark it withdrawn and leave the number spent.
- Same for the artifacts, the phases, the steps of the loop and the method
  changelog's rows. A repository's own records are not numbered at all
  (*Workspaces*, record ids).
- **The header is written in the same change that changes the method, in every
  document of the set.** A file whose header disagrees with its body, or with
  its siblings, is worse than one with no header — the triage trusts it.

### The two directions

**Down — distributing an improvement.** Put the newer bundle in the target's
`.agents/incoming/` — never over the live one — and run the **update
invocation**; for every carrier open at once, `prompt-sync.md` does the same in
one pass. The update reads the version the repo had, lists only the deltas
since, and — the part that matters — decides which of them apply *there*,
because a repo with no rendered output does not need the rule about looking at
the artifact.

**Up — harvesting from a repository.** Rarer and more valuable.
`prompt-harvest.md` asks the repository *what have you learned that the method
does not know?* and writes the answers as candidates in `tracking/`. The release
then applies the generality test, brutally, and takes the two that survive
rather than the nine that were offered. The failure mode here is a method that
accretes one repo's idiosyncrasies until it is portable to nowhere.

### A cadence that works

Tie the reviews to events rather than to the calendar, because a monthly ritual
gets skipped and an event-driven one does not:

| When | Do |
|---|---|
| Every session close | step 8 — harvest to level 1 and 2 |
| When a friction is hit a second time | promote it to the roadmap's process area |
| When the local review skill runs (Phase 9) | ask which level-2 rules pass the generality test |
| When you notice yourself explaining the same thing to a second repository | record it as a candidate for level 3 in `tracking/candidates.md`; the next release decides and bumps |
| Before a round of harvests | align every carrier first (`prompt-sync.md` §*The cycle*): two commands when nothing diverged, and what makes every harvest read the same base |
| Before a meta-session, in every carrier | run the local step, `prompt-harvest.md`: it is what the meta-session gathers |
| When a repo gets significant new work after a gap | run the update invocation before starting, not after |

**The last row is the one people get wrong.** Updating the method *after* the
work means the work was done under the old method, and the first thing the new
method says is usually something the work should have done.

---

## Worked example: how this landed in one repository

An interactive time-synced renderer, scripted, **deployed where its developers
cannot observe it**: every test on the real target goes through somebody outside
the development side. That single constraint shaped everything, and it is why
the example transfers: most repositories have some version of *a place where the
software runs and you cannot look.*

**The invariant.** One clock is the master; every visual is a pure function of
that clock's current time. Never accumulate frame delta.

**The enforcement chain for that one invariant**, rung by rung:

| Rung | Mechanism |
|---|---|
| 1 | Two paragraphs at the top of `CLAUDE.md`, each rule carrying the measurement that produced it |
| 2 | A decisions table, a run of consecutive rows, each naming the class or constant that enforces it |
| 3 | A validator that fails the gate on the constructs that cannot be seeked — anything that simulates forward from the previous frame, or reads a clock other than the master — plus a renderer that reaches each moment **from both directions and compares the pixels** |
| 4 | The data format itself: an element names a *layer*, never a number, so the layering rule cannot be violated in the data |

**What the numbers did, and the bug that justified the system**, are the
examples already told in principles 2, 5, 6 and 7: the audit failing every one
of its rules on its first run, the root file shrinking to about a quarter of its
size, the batching measurement that kept the layering rule, and the list the
build emptied. One more: a refactor of the rendering system was accepted only
because every reference frame rendered byte-identical before and after.
Its sessions supply the examples of principles 14 and 15 and of the session
loop's steps 0 and 1 — one roadmap item taken through the whole loop, with the
screen rendered and looked at, three questions asked and no more, and one
structural decision handed back rather than taken mid-feature. None of it is
specific to that kind of software.

**The transfer.** Nothing above depends on that platform or that domain.
Substitute your own nouns:

| Here | A backend service | A Python package | A data platform |
|---|---|---|---|
| the master clock | the transaction boundary | the public API | the partition window |
| seek from both directions and compare pixels | replay the request twice and compare state | install the wheel in a clean venv and smoke-test | rerun the window and diff the table |
| the runtime nobody here can observe | production under concurrency | the user's machine | the full-size dataset |
| the data format that cannot express a bad layering | a config type with no optional timeout | a `Money` type with no float constructor | a contract that rejects an unknown column |

---

## Method changelog

The table of what each method version changed for the reader lives in
[`changelog.md`](changelog.md), beside this file. A release adds its row there.
