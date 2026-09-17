# Bootstrap a repository for Claude Code

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

## ▶ Paste this to start

**Copy the block below into the agent, at the root of the repository you are
preparing.** Everything after it is the method the agent will follow; you do not
need to read it first.

**╔══════════ COPY EVERYTHING INSIDE THE BOX BELOW ══════════╗**

~~~text
You are preparing this repository to be worked on with **Claude Code**. The
method is `prompt-bootstrap.md` and `prompt-context.md`, in `docs/agents/` or
wherever they were dropped in this repo.

**FIRST, before reading anything else: run the pre-flight.** Open
`prompt-bootstrap.md` §*Before you start*, ask me those questions in one
message, and wait for my answer. Do not read the repository, plan or write
until I have replied. Ask fewer if the repository already answers one, and tell
me in one line what you are not asking.

**Once I have answered**, read the method header, then `prompt-bootstrap.md`
§*The process*, and in `prompt-context.md` the
sections *The enforcement ladder*, *The principles*, *Engineering standards*,
*The artifacts*, *The platform's own mechanics* and *Adapting to another
stack*. Do not read either document in full — each names what a job needs.

If `prompt-context.md` is not next to this file, say so and stop: the
procedure without the reference produces a plausible instruction system with
no argument behind any of its rules.

**First, check the header.** If it already carries a `version` at or above
this file's, stop and tell me: an older copy has been pasted over a newer one.
If `adopted` is set but the artifacts it claims are missing, say so — that is
repair, not a fresh bootstrap, and I need to know which I am getting.

**If this repository already has conventions of its own** — any of
`CLAUDE.md`, `AGENTS.md`, `.cursorrules`, an ADR folder, a planning document,
an existing gate — you are in **adopt mode**: read principle 19 and *Adopting
into a repository that already works* before anything else, and produce the
guarantee → existing-file table as part of Phase 3. **Nothing existing is
discarded, renamed or reorganised** without my explicit approval, and every
substitution goes in the header's `adapted` list.

Follow its nine phases in order. **Phases 0, 1 and 2 are read-only, and you
write no file until I approve the Phase 3 proposal.**

- **Phase 0:** read the repository — manifests, task runner, CI, source tree,
  tests, `docs/`, git history, and any existing agent instructions. List what
  you read.
- **Phase 1:** report the ten-item state evaluation, including where this
  repo's failure knowledge actually lives, **the class of bug this repo cannot
  observe**, and what you could not determine. Mark every inference
  **ASSUMPTION**.
- **Phase 2:** research the domain, the language and the framework. Report only
  what changed or confirmed a decision, each entry saying what it confirms,
  what we appear to do differently, and what a recommendation assumes about its
  environment. Verify version-specific claims against the documentation rather
  than from memory, and say which claims you verified.
- **Phase 3:** propose the full file topology, say what gets cut from any
  existing file and where each piece goes, justify the slicing, and **wait for
  my approval.**
- **Phases 4 to 9, after approval:** generate the files; make the rules
  executable and report how many were already being violated; remove
  duplication; audit `docs/` against the code; write the roadmap with its
  collisions; add the recurring review skill.

Non-negotiable while you work: real commands taken from the configs, never
invented. Every rule states its consequence and what enforces it. Invariants
name classes, constants and contracts, not paths. Each fact lives once. No
claim without a measurement. Tests are written before the code they describe.
Architectural integrity overrides my requests — if I ask for a shortcut that
breaks it, show me the cost and propose the correct path, and deviate only if
I confirm.

Finish by filling in the method header: `adopted`, plus every substitution in
`adapted` and everything I turned down in `declined`, each with its reason.

If you are running with limited context or reasoning, read
`prompt-context.md` §*Models, reasoning levels and cost* and say so in your
first message.

Start with Phase 0 and Phase 1, and report before proposing anything.
~~~

**╚══════════════════ END OF WHAT YOU COPY ══════════════════╝**

---

**Run `prompt-evaluate.md` before this one** unless you already know the
repository has nothing. It is read-only, it takes minutes, and its report says
whether you want bootstrap, update, or three targeted fixes instead.

**What you will be asked for.** Two decision points, both unavoidable: the
Phase 1 and Phase 2 reports, where you correct what the agent inferred — **you
know which of the repo's conventions are decisions and which are accidents, and
it cannot tell from the outside** — and the Phase 3 proposal, which is the last
moment before anything is written. In a repository that already has its own
conventions, read the guarantee → existing-file table in that proposal
carefully: that table is where a helpful adoption turns into an unwanted
rewrite.

**What it produces**, roughly: a root instruction file under 200 lines, one
nested file per natural area, two to four skills, a permissions file, a
decisions log, a references register, a roadmap, a changelog, and — the part
most people skip — **a script that checks the structural rules the prose
claims.**

---

## Before you start — ask these

**Ask all of them in one message, then stop.** Do not read the repository, do
not plan, do not write. The rules behind this block are in `prompt-context.md`
§*The pre-flight*.

*The agent will print something like this, and then wait:*

~~~text
Before I prepare this repository, six things — reply `defaults` to take all of
them as proposed.

1. Scope. I will prepare the whole repository, and give a nested instruction
   file to each area that turns out to have rules of its own. (Monorepo? Say
   which packages, or "all".)

2. Is there a place this software runs where nobody here can look? A device, a
   customer's environment, production under load, a shipped artefact. You
   probably know this in one sentence, and it decides what has to be checked
   before shipping rather than after. If there is none, say so.

3. Language. I will write this repository's own documents in the language you
   are writing to me in, keeping identifiers, commands and paths exactly as they
   are. The method documents themselves stay in English.

4. Off limits. I will not touch anything you name here, and I will not run
   installs, migrations, or anything that reaches the network. I will run the
   existing gate if it writes nothing.

5. Agents. I will detect which assistants are already configured here and set up
   a surface for each — Claude Code, Cursor and Copilot are the common set — all
   generated from ONE source so they cannot drift. Tell me if any of them is not
   in use, or if there is another.

6. Readers. I will assume this repository is worked on by you and by agents. If
   there is a team, or external contributors, say so — it changes how strict the
   rules are worth making.

Not asking, because the repository answers them: the stack, the real commands,
the structure, the conventions already in use, and which assistants have
instruction files here today. I will report all of that back at Phase 1, and
that is where you tell me which of those conventions are decisions and which are
accidents — I cannot tell from the outside.
~~~

**Ask fewer when the repository answers one.** Drop question 1 in a
single-package repo rather than asking it and answering it yourself.

**This is not the only checkpoint.** Questions that cannot be asked before
reading — above all *is this convention a decision or an accident* — belong to
the Phase 1 report and the Phase 3 proposal, and they are not folded in here.

---

## What this document is, and what `prompt-context.md` holds

**This document owns the procedure**: the nine phases that prepare a repository,
and the session loop that every working session follows afterwards. Everything
shared with the other prompts — the set and which one to run, the method header
and how copies are matched, the enforcement ladder, the nineteen principles, the
engineering standards, the ten artifacts, adopting into a repository that
already works, the platform's loading mechanics, workspaces, model tiers, the
pre-flight rules, and how the method itself improves and travels — lives in
**`prompt-context.md`**, once.

**Read `prompt-context.md` alongside this one.** Its sections *The principles*,
*Engineering standards*, *The artifacts* and *The platform's own mechanics* are
what the phases below assume you have. **If it is not beside this file, say so
and stop:** the procedure without the reference produces a plausible instruction
system with no argument behind any of its rules.

**Precedence:** `prompt-context.md` is the record for principles, artifacts and
standards; each executable prompt is the record for its own procedure. A
disagreement is a bug in whichever document is not the record for that subject.

**Do not assume how many documents the set has.** Read the `set` field in the
header and list what is actually beside this file; a later release may add more.

## Reading list for this job

| Doing | Read |
|---|---|
| Preparing a repository | this document's *The process*, plus context's *The principles*, *Engineering standards*, *The artifacts*, *The platform's own mechanics* |
| Preparing one that already has conventions | context's principle 19 and *Adopting into a repository that already works*, **before anything else** |
| Working a normal session afterwards | this document's *The session loop*, plus context's *Engineering standards* |
| Running on a small model | context's *Models, reasoning levels and cost*, the self-limiting part, first |

Nothing here asks you to read either document in full.

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
   domain — see `prompt-context.md` §*Adapting to another stack*. State each as an
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

For each finding, produce the four-part entry from context's artifact 7: what it confirms,
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

Write `docs/roadmap.md` with the collision analysis (context's artifact 8). Then write the
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
- **What friction has been recorded more than once and not yet fixed?** Price
  each one and rank it against the product work in the same sitting.
- **What have we learned that the method itself does not know?** Apply the
  generality test in `prompt-context.md` §*Improving the method* and name only
  what survives it.
- **Is this repo's method file behind?** If so, run the update invocation before
  the next significant piece of work, not after it.

---

---

## The session loop

The nine phases happen once. **This is what every session after them looks
like**, and it is where the artifacts either earn their keep or sit unread. A
repository can be perfectly bootstrapped and still be worked on badly.

**Two bookends and seven steps.** Step 0 and step 8 are the bookends, and they
are what make the loop compound instead of just repeat: one loads the state of
the world before deciding anything, the other returns what the session learned
to the repository. Of the seven in between, steps 1, 2, 6 and 7 are the ones
agents skip, and they are the ones that cost the most when skipped.

### 0. The opening brief — load the world before you touch the request

**Do this before answering, before planning, and before agreeing that the
request makes sense.** A session that starts by reading only the request is
working from the one source that has no memory.

Gather, in this order, stopping as soon as a source has nothing to add:

- **What is in motion.** The roadmap's *Where we are*, and the last handful of
  changelog entries. What is half-finished, and what did the last session say it
  left undone?
- **What is in the tree.** Uncommitted and staged changes. Some of it may be
  another session's work, and none of it is yours to fold in.
- **What constrains today.** The decisions and measurements that bear on the
  area the request touches. Read the *why*, not just the rule — you will be
  tempted to re-open it in about an hour.
- **What is stale.** Anything that must be re-checked before it can be trusted:
  a number from a version ago, a research entry whose upstream may have shipped,
  a document that names a file you should confirm still exists.
- **What plans already exist here**, and — the part that is actually valuable —
  **how today's request changes their conditions.** A request often silently
  unblocks, forecloses or reprioritises something already written down, and the
  human asking for it usually does not know that.
- **What friction is still open.** Process items recorded and not yet fixed,
  especially any that today's work will hit again.

**Then say it out loud, in six lines. Not a dump — a brief.**

```
Workspace      <only with more than one repo open — see context's Workspaces>
Today's repo   <the one this work is in; conventions come from HERE>
In motion      <what is half-finished, from the roadmap and the changelog>
In the tree    <uncommitted work, on which branch, and whose it is>
Constrains     <the decisions and numbers that bear on what you asked>
Stale          <what must be re-checked before it is trusted>
Changes it     <how the above alters your request — one sentence>
Friction       <process items still open that this work will touch>
```

The first two lines are dropped entirely when a single repository is open. The
other six are never dropped.

Four rules for the brief, all of which exist because the alternative has been
seen:

- **A line with nothing in it says `nothing`.** An omitted line is ambiguous
  between *checked and clean* and *did not check*, and the reader cannot tell
  which. This single rule is most of the brief's value.
- **`Changes it` is the point of the whole exercise.** If the state of the repo
  does not alter the request, say so plainly — but say it, because "nothing here
  changes what you asked" is information the human paid for.
- **Written for someone who has not been here since last week**, because that is
  usually true. Name things, do not allude to them.
- **Six lines, not six paragraphs.** Anything longer gets skimmed, and a skimmed
  brief is worse than none: it has been paid for and not read.

> **Example.** A session opened on "keep adding roadmap items". The brief that
> mattered was three lines: a cosmetic change to the task-runner config was
> **staged by another session** and was not to be touched; the last four entries
> showed the deploy path had just been fixed twice, so anything touching the
> build was hot; and the only roadmap item that cost the core invariant nothing
> was the one the human had not named. That last line changed what got built.

### 1. Pick from the roadmap, and price it before you touch anything

Do not start with the most interesting item. Survey what is available and rank
it on two axes the repo can actually see — **what it costs the core invariant**
(the roadmap's own table) and **whether this repo can verify it** — then report
the ranking before building. It is a cheap message, and a human who disagrees
will reorder it in one line.

Sort every candidate into one of three buckets, and say which:

- **Ready.** Nothing to decide that the repo has not already answered.
- **Blocked on a decision that is the human's.** Name the decision. "It's
  complicated" is not a bucket.
- **Blocked outside.** Upstream, hardware, licensing, someone else's roadmap.
  Say what would reopen it and move on.

**Also name what you would not take, and why.** That is not padding; it is the
most reusable part of the survey, because the reason usually outlives the
session.

> **Example.** "Any animation on demand" looked ready — the five-action limit is
> an interface constant, not an engine one. Checking the asset definitions
> showed **no character sheet declares more than five actions**. It was not
> ready; it was interface for a problem that does not exist yet, and saying so
> with the count is what stops it being picked up again next month.

### 2. Ask the few decisions, all at once, before writing

Principle 15 has the protocol. The step that comes before asking is evaluating,
and it has a shape worth following:

**How to evaluate a trade-off**

1. **Name the axis in the repo's own units.** Not "heavier" — *"a row of tabs
   costs half of one of the three cards that fit in the list"*, *"this adds
   40 ms at p99"*, *"this is the first native dependency in a repo that is
   currently script and data"*. An option priced in adjectives cannot be
   compared with anything.
2. **Say what each option forecloses**, not only what it costs today. Foreclosure
   is the part nobody can reconstruct from the code a year later.
3. **Count the exceptions.** If an option needs an exception to a stated rule,
   *that* is the real price, and it has to be written into the roadmap **with a
   name** — one mechanism, one owner, one way back. An exception nobody named
   becomes the new rule.
4. **Name the cheap reversible option**, and say so plainly when it is the one
   you recommend. Reversibility is worth a lot of elegance.
5. **Keep "do nothing" on the table.** It wins more often than it is offered,
   and a survey that never recommends it is not being honest about cost.
6. **Say what would change your answer.** A trade-off you cannot falsify is a
   preference. *"If the phone ever reports dropped frames, this measurement gets
   redone and the conclusion may flip"* is an analysis; *"this should be fine"*
   is not.

Then recommend one. A trade-off presented without a recommendation is the work,
undone.

### 3. Build — extend before creating, and let the format do the work

Prefer extending the module that already does this job. Before adding a new
concept, check whether the rung-4 move is available: can the **format** absorb
the rule so the bad state stops being expressible? That question is cheapest
before the code exists and nearly worthless after.

Two habits that carry the reasoning forward:

- **The reason travels with the number.** A constant whose comment records the
  measurement that produced it — *"alpha at 0.45 let the backdrop through and
  the song's name stopped being readable"* — defends itself against the next
  session in a way a line in a document does not, because it is read at the
  moment of changing it.
- **A throwaway probe is the normal way to measure.** Write it, have it print
  the **state transitions** rather than a bare pass/fail, paste its output into
  the changelog as the measurement, and delete it in the same change. A probe
  that survives becomes untested infrastructure nobody owns.

### 4. Verify — run the invariant test when you *claim* it, not when you think you touched it

The gate is the floor. The core invariant's own test — the seek comparison, the
replay, the round trip, the wheel installed clean — is cheap relative to a
wrong claim, and the rule that keeps reports honest is simple: **if the report
will say the invariant holds, run the thing that proves it**, even when you are
confident nothing you touched could have moved it. Confidence is not a
measurement, and the sentence in the log is a claim either way.

### 5. Look at what you made

Principle 14, applied. Render it, run it, open it, read it. Budget for the fact
that this step finds things — it found two defects in the example above, one of
them in a branch that predated the change by months.

### 6. Put the documents back to true — in this change, not later

Write this table once for your repo, in Phase 4, and follow it every session:

| What you changed | What must move, in the same change |
|---|---|
| A rule, or the answer to a settled question | the decisions log: a new row, **enforcer column filled** |
| A number some document claims | the document that owns that number, with the new measurement |
| A file, a layer, a public name | the architecture document, and every map that names it |
| Something the roadmap planned | that entry's **state**, and what is still missing |
| A command, a flag, a task-runner entry | every document that quotes it — these go stale fastest |
| A user-visible behaviour | the guide written for the non-programmer, if the repo has one |
| Anything at all | the changelog, including what went wrong on the way |

The test for this step is not "did I write documentation". It is: **is there a
sentence anywhere in the repo that my change just made false?**

### 7. Report honestly, and let the human decide what is theirs

The report is not a victory lap. It says what was built, **what the first
attempt got wrong and what caught it**, what was measured, what was left
undone, and — separately and explicitly — **any structural decision you
declined to make on your own**. Put that last one where it cannot be missed; it
is the one part of the report the human must act on.

Then offer commits, split by *what changed and why*, and offer only the ones
that pass the repo's stated bar. A commit that needs an "and" in its subject is
two commits.

**What to show the user**

**Never hide or soften the technical content.** There is no "simplified version"
that drops a constraint, a cost or a caveat — a human who is given a summary
without the constraint will make a decision the constraint would have changed,
and they will be right to be angry about it. What varies is **order and
emphasis**, never inclusion.

The order that works, and it is the same order every time:

1. **What is true now** — what was built, and whether it works. One or two lines.
2. **What it cost, and what it forecloses.** In the repo's own units.
3. **What you need from them** — the structural decision you declined to take,
   the question you could not answer, the thing they must schedule. Separate and
   unmissable, because this is the part that must be acted on.
4. **The mechanism**, for anyone who wants it: how it works, what you measured,
   what you rejected and why.
5. **What you did not do**, always, even when nothing was left out — "nothing was
   left out" is a sentence worth writing.

Three rules underneath it:

- **Technical names stay in their own language.** Identifiers, commands, types,
  file paths and error strings are quoted exactly, in English, whatever language
  the prose around them is in. A translated command is a command that does not
  run, and a translated symbol cannot be searched for.
- **Write the prose in the language the human is using.** The method file and
  the header are English (see the top of this document); a session's
  conversation is not the method.
- **A number always brings its method.** "Faster" is worthless; "63 → 41 ms at
  p99, measured over 200 runs with the cache warm" can be checked, argued with,
  and reused.

### 8. The closing review — return what the session learned

Commits and documents are step 6 and step 7. **This is the step after them, and
it is what makes a hundred sessions add up to something rather than just
happen.** It has three parts and takes a few minutes.

**A. Re-run what this change invalidated.** Work does not only add; it ages
things. Walk the list:

| Ask | If yes |
|---|---|
| Did a measurement recorded elsewhere just become wrong? | re-measure, or mark it superseded with the date |
| Did this settle a question that was open? | a decisions row, enforcer column filled |
| Did this **unblock** something the roadmap called blocked? | change that entry's state now, while you know why |
| Did this make a rule checkable that was on rung 1? | write the check, or add it to the process area |
| Did the outside change under a research entry you relied on? | update the entry with what you verified and when |
| Did any document you did not open become false? | the *what changed → what must move* table, run properly |

**B. Harvest the learnings, and route each one.** The question is not "did I
learn something" — it is **"what does this session know that the repository does
not?"** Then send each answer to its home; a learning with no home is a learning
that dies with the context window.

| What you learned | Where it goes |
|---|---|
| A question is now settled | `decisions.md`, with its enforcer |
| A number, and how you got it | the document that owns that number |
| An external fact that changed or confirmed a decision | `references.md`, saying what you do differently on purpose |
| A rule a script could check | the audit script, and note the rung it moved to |
| A trap that will be hit again | root `CLAUDE.md` if it is always relevant, the area file if it is local |
| A procedure you performed more than twice | a skill |
| Friction, hit for the second time | the roadmap's process area, with the arithmetic |
| A plan whose conditions changed | that roadmap entry's state |
| Something true only of this change | the changelog entry — and that is a complete answer, not a failure |

**C. Propose the process improvements you found, and do not perform them.**
Principle 17 has the discipline. List them with the arithmetic, say which is the
one-line reversible one you already took, and leave the rest for the human to
schedule.

**Capture is unconditional; proposing is throttled.** These are two different
things and conflating them is why process work is either absent or exhausting:

- **Always capture, every session, without being asked and without judging
  whether it matters.** A learning or a friction that is not written down when
  it happens is gone, and no later session can recover it. This costs seconds
  and it is never skipped. Not every session is a session about improving the
  process — but **no session is allowed to lose what it learned.**
- **Surface a proposal only when it has earned it**: the friction has been hit a
  second time, or the human asked, or the recurring review is running. Otherwise
  it stays recorded and silent.
- **In the report, the whole thing is one line.** `Captured: 3 learnings, 1
  friction (2nd hit — see roadmap). Nothing needs you.` Expand only if asked.

That last line is the whole discipline in practice: the human who wants to ship
a feature is not interrupted, and the human who wants to improve the process
finds a year of honest observations waiting when they go looking.

**The closing question, asked plainly:** *if the next session is a different
agent with no memory of this one, what would it have to re-derive?* Everything
that answers that question is a gap you can close in the next two minutes, and
will never close as cheaply again.

> **Example.** One session's harvest was four rows: two decisions with
> enforcers, one number that moved into the document owning it, one roadmap
> entry flipped from *planned* to *done, and here is what is still missing* —
> and one honest `nothing generalises` for the rest. The whole thing took less
> time than re-reading the diff.

### Working safely in a tree you do not own

Four hazards, all of which have cost real time:

- **The tree may already hold another session's work.** Check before you start.
  Never fold someone else's uncommitted or staged change into yours, and name it
  in your report so the human knows it is there.
- **A formatter that can lose data never runs on a dirty tree.** When the gate
  demands formatting and the tree has unsaved work, do not run it in place:
  **copy the file to a scratch directory, format the copy, diff it, and apply
  the diff by hand.** Thirty seconds, and it cannot destroy anything. Prefer the
  `--check` variant everywhere else — it writes nothing and is always safe.
- **Generated artefacts churn.** Regenerating can rewrite a file with new random
  identifiers and no real change. Revert the ones that did not actually change:
  a diff nobody can read is a diff nobody reads, and the real change hides in
  it.
- **When the gate's own limits block your change**, take the moves in this
  order. (a) **Relocate** — is there code in this file that belonged somewhere
  else anyway? This is usually available and usually an improvement. (b)
  **Tighten what you added**, not what was already there. (c) **Raise the
  limit** — never silently, never as part of unrelated work. And if the honest
  answer is that the file needs splitting, **say so and leave it**: that is a
  structural decision, and mid-feature is the worst possible moment to take it
  unasked.

### Research does not end at Phase 2

When an item comes off the roadmap for implementation, **re-check the outside
for that item specifically.** The upstream issue that blocked it may have
shipped; the platform's recommendation may have changed; the version you are
pinned to may have grown the API you worked around. This is a five-minute check
and it is the only thing that keeps a *blocked outside* entry from being blocked
forever out of habit.

The same rule as Phase 2 applies: nothing enters the register unless it changed
or confirmed a decision, and a recommendation written for a different
environment is the most dangerous kind of correct.

---

---

## Checklists

### Done — the bootstrap

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
- [ ] The *what changed → what must move* table exists, in the root file or in
      the review skill. Without it, step 6 of the loop is left to memory.
- [ ] The **method header** is filled in: `lineage`, `version`, `adopted`, and
      every substitution in `adapted` and every refusal in `declined`, each with
      its reason.
- [ ] In a repo that already had conventions: the guarantee → existing-file
      table was shown and approved, and **most verdicts were *extend*, not
      *create***.
- [ ] The engineering standards are stated in the repo's own vocabulary: the
      test discipline, the strictness level types are set to, what a docstring
      owes, and the domain's non-negotiables.
- [ ] Nothing existing was renamed or reorganised without explicit approval.

### Done — every change after that

The short one. Run it before you report, every time.

- [ ] The session opened with the six-line brief, and every line said something
      — `nothing` counts, an omitted line does not.
- [ ] It came off the roadmap, and the roadmap's entry now says what it became.
- [ ] Every decision that was the human's was asked **before** the code, priced
      in the repo's own units, with a recommendation.
- [ ] The gate is green, quoted, not paraphrased.
- [ ] The core invariant's own test was run if the report claims it holds.
- [ ] The output was **looked at**, not only asserted about — and any rare
      branch this change made reachable was exercised once.
- [ ] Every document the change falsified is true again, in this change.
- [ ] The changelog entry names what went wrong on the way and what was left
      undone.
- [ ] Any structural decision you declined to take is stated plainly in the
      report, where the human cannot miss it.
- [ ] Nothing of somebody else's was swept into your change.
- [ ] The closing review ran: what this change invalidated was re-run, the
      learnings were routed to their homes, and the answer to *"what would the
      next agent have to re-derive?"* is written down somewhere.
- [ ] Friction hit twice is in the roadmap's process area, priced. Friction hit
      once is in the changelog entry.
- [ ] Process improvements were **proposed, not performed** — except a one-line
      reversible one, which is named in the report.
- [ ] Learnings and friction were **captured in full**, regardless of whether
      this was a session about improving anything, and summarised in one line.
- [ ] The test was written before the code, and it failed first for the reason
      expected. Any exception is named as one.
- [ ] In a workspace: today's repo was named, its conventions were re-read
      rather than recalled, and no rule crossed a repository boundary.
- [ ] Nothing technical was simplified away from the report — order was chosen,
      content was not trimmed.

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
- **The roadmap has no `done` entries.** Either nothing shipped, or finishing
  never writes back — and the second is the one that quietly makes the file
  fiction.
- **Every changelog entry went perfectly.** Nobody works like that. The wrong
  turns are being edited out, which is exactly the information the next session
  needed.
- **The agent asked nothing all session**, or asked at every branch. Principle
  15 is being read as a formality in one direction or the other.
- **A feature was declared done on a green gate alone.** Nobody looked at it.
- **A limit in the gate was raised in the same commit as a feature.** The limit
  was the message; raising it quietly deleted the message.
- **No session has ever proposed a process improvement.** Failure mode 6, and it
  is invisible precisely because nothing is going wrong.
- **The roadmap's process area is empty after months of work.** Either the
  workflow is perfect or friction is not being written down. It is not the
  first one.
- **A process improvement rode along inside a feature commit.** Now a revert
  takes it with it, and no number can be attributed to either.
- **The opening brief is three paragraphs.** It is being skimmed, which means it
  has been paid for and not read.
- **The method file has a version history of empty releases.** Bumping without
  learnings teaches the next reader that the version number means nothing.
- **Every learning from every session gets promoted to the method.** The
  generality test is not being applied, and the method is becoming portable to
  nowhere.
- **The tests were written after the code.** They now certify the bugs. This is
  visible from the outside: the assertions describe the implementation's shape
  rather than the requirement's.
- **A test suite that has never failed on a real change.** It is testing that
  the code is the code.
- **Adoption created seven new files in a repo that already had documents.** That
  is a replacement wearing adoption's clothes, and it will be reverted.
- **`adapted` and `declined` are empty after three updates.** Either nothing was
  ever adapted — unlikely — or the header is not being maintained, and the next
  update will re-propose everything already refused.
- **Two repositories in a workspace have silently converged on one repo's
  conventions.** Context leaked; the promotion path was bypassed.
- **A small model produced a complete-looking Phase 1.** Check what it actually
  read. Announced limits are a feature; inferred coverage is a defect.
- **The report reads as reassuring.** Someone trimmed a cost to make it read
  well, which is the one edit that is never allowed.

---

---

## The bootstrap invocation

**It is at the top of this file**, under *Paste this to start*, in a code
block so it copies clean. It is not repeated here: two copies of a prompt is
one copy that will drift.

### Bootstrapping a repository

Paste this into Claude Code at the root of the repository you are preparing.


If the file is not present, the invocation still works on its own — but the
phases will be shallower, and the parts that make this worth doing (the research
register, the collision map, the enforcement ladder) will not happen unless you
name them.

---

## The working invocation

This is the one you will actually use. It assumes the repository has been
bootstrapped, and it is short on purpose: the files carry the rest.

**╔══════════ COPY EVERYTHING INSIDE THE BOX BELOW ══════════╗**

~~~text
Work through **The session loop** in `prompt-bootstrap.md` — that section,
plus `prompt-context.md` §*Engineering standards*, and nothing else unless one
of them points you there.

**Open with step 0's six-line brief, and with any question you cannot answer by
reading.** Ask those in the same message as the brief, each with the default you
will take, and wait. One message, then stop — not a question every few
minutes.

**Open with the six-line brief** (step 0). If more than one repository is open,
add the workspace lines and name today's repo; conventions come from that repo
and nowhere else.

Then survey what is available on the roadmap and ranking it by what it
costs the core invariant and whether this repo can verify it. Tell me what you
would **not** take and why. Then ask me — before writing anything — only the
decisions that are genuinely mine, all at once, each option priced in this
repo's own units, with your recommendation first.

While you build: **write the test before the code**, and watch it fail for the
reason you expect. Extend before creating, and check whether the format can
absorb the rule instead of a new validator. Measure with a throwaway probe and
delete it. Run the gate, and run the core invariant's own test if your report
is going to claim it holds. **Then look at what you made** — render it, run
it, open it — and exercise any rare branch this change made reachable.

Before you report: put back every document the change made false, in this
change, and write the changelog entry including what went wrong on the way and
what you left undone. Do not sweep anyone else's uncommitted work into this.

**Close with step 8.** Capture every learning and every friction — always, in
full, whether or not I am in the mood to improve anything. Then tell me about
it in **one line**: how many were captured, and whether any has crossed the
threshold that needs me. Expand only if I ask.

In the report, state plainly and separately any structural decision you
declined to take. Never simplify away a constraint or a cost; order the
information, do not trim it.

Nothing is committed unless I ask.
~~~

**╚══════════════════ END OF WHAT YOU COPY ══════════════════╝**

**Why the second one is short.** Everything it leaves out is in the files the
bootstrap produced. If a normal session needs a long prompt, the bootstrap did
not take — and the fix is in `CLAUDE.md`, not in the prompt.

### Updating a repository to a newer method

**That invocation lives in `prompt-update.md`**, together with the header
mechanics, the prefix-matching rules, the four triage verdicts and the prune. It
is a separate document because it is a separate job, run at a different time,
often by a different person — and because it must be runnable without loading
this one.

The reasoning behind it is in `prompt-context.md`: *Which document to run*,
*Improving the method, and distributing it*, and principle 19.

**Precedence, when two documents disagree:** `prompt-context.md` is the
reference of record for principles, artifacts and standards; each executable
prompt is the record for its own procedure. A conflict is a bug in whichever
document is not the record for that subject.
