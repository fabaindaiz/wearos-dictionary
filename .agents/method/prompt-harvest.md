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

# The local step — what this repository learned, written where a release can take it

**Where this sits:** step 2 of the cycle in `prompt-sync.md` §*The cycle* — the carriers are aligned
first, every repository harvests, and one release then gathers what they wrote.

## ▶ Paste this to start

**╔══════════ COPY EVERYTHING INSIDE THE BOX BELOW ══════════╗**

~~~text
You are running the local step of the agent guides in the repositories this
session has open — **all of them and only those**. Follow
`.agents/method/prompt-harvest.md`, with `prompt-context.md` beside it for the
reasoning.

**FIRST, before reading anything else: run the pre-flight.** Open that document
§*Before you start*, ask me those questions in one message, and wait. Then
read only this:

Reads:
- method/prompt-harvest.md
- method/prompt-bootstrap.md §8. The closing review — return what the session learned
- method/prompt-context.md §20. Nothing private travels, directly or by reconstruction
- knowledge/README.md
- knowledge/INDEX.md
- tracking/candidates.md
- tracking/experiments.md

**Close what is open before reading anything.** In each repository, finish the
session that is in flight — its closing review, its documents put back to true,
its changelog entry, its gate — or, if I tell you to leave it, say so and
harvest only what is already recorded. A harvest reads the record, so a session
that was never closed is a harvest of everything except the freshest thing that
happened.

Then read what this repository recorded since its last harvest and write down
what it learned that the bundle does not know — **as candidates and evidence in
`.agents/tracking/`, and nowhere else in `.agents/`**. Notes, method documents,
indexes, headers and versions belong to a release, which is written once for
every carrier; a note edited here is a lineage forked here.

Do each repository as its own pass, with that repository as today's repo, and
never carry one's conventions into another.

Privacy (principle 20): every candidate and every line of evidence is
generalised before it is written — no figure, quote, identifier, domain noun or
personal detail that could identify a private repository, its people or its
users — and `bundle.py privacy .agents` passes before the commit.

Finish with `python3 .agents/tools/bundle.py check-local`, which has to report
that only `tracking/` changed in every one of them, and a report per repository
of what you found, what you refused and why, and what now waits for the next
meta-session.
~~~

**╚══════════ COPY EVERYTHING INSIDE THE BOX ABOVE ══════════╝**

---

## Before you start — ask these

**Ask all of them in one message, then stop.**

~~~text
Before I harvest this repository, four things — reply `defaults` to take them
as proposed.

1. The repositories and the period. I will harvest **every repository this
   session has open and only those** — say if one should be left out — and read
   everything each of them recorded since its own last harvest (the newest date
   in its `.agents/tracking/`, or its bundle's `adopted` date). A carrier this
   machine knows that is not open here is named as not harvested, never guessed
   at. Say if you want a different starting point.

2. Experiments. `tracking/experiments.md` queues experiments that would move a
   note's confidence. I will run only the ones that cost minutes here and
   record what they showed; the rest stay queued. Say if you want none run, or
   a specific one run whatever it costs.

3. Work in flight. Where a repository has an unclosed session — uncommitted
   work, a changelog entry not written, documents a change made false — I will
   close it first and then harvest it. Say if you want it left alone instead,
   and I will harvest only what is already recorded and name what I skipped.

4. Scope. I will write only in `.agents/tracking/`, and process friction that
   belongs to this repository in its own roadmap. Nothing else in `.agents/`
   changes. Say if you want something else touched, and I will tell you what it
   forks.
~~~

---

## Why the local step is separate, and why it writes so little

A meta-session takes one release to every carrier. **The local step is what gives it something to
take**, and the discipline that makes it cheap is a negative one:

> A carrier that edits its own note, adds its own, or bumps its own version has **forked the
> lineage**. Nothing fails that day. It fails at the next meta-session, which now has to merge two
> lines and decide which of two true sentences to keep — and that is how a base gets re-derived by
> hand, how a `tracking/` folder gets overwritten, and how three carriers end up on two versions.

So the local step writes **candidates and evidence**, in `tracking/`, which the bundle merges line
by line precisely because it is written in several carriers at once. Everything else — a note, its
index row, a method rule, a version, a digest — is written **once, for every carrier**, by
`prompt-sync.md`.

`python3 .agents/tools/bundle.py check-local` is that rule as a check: it compares this carrier's
working bundle with its committed one and names anything changed outside `tracking/`.

## The scope: every repository open here, and no other

The rule and its reasons are in `prompt-context.md` §*Workspaces: several repositories at once*.
What it means here: every open repository is harvested, because a learning seen in two of them is
the second occurrence admission asks for; a carrier of the manifest that is not open is named in
the report as not harvested, never reached into.

## Phase 0 — close the session that is open, in each repository

**A harvest reads what was written down, so it is worth nothing until what happened is written
down.** The order is not a preference: an unclosed session keeps its learnings in a working tree and
in the head of whoever ran it, and the head is gone by the time the harvest runs.

For each repository, before reading it, run the closing review of the session loop
(`prompt-bootstrap.md` §*The session loop*, step 8) over whatever it has in flight:

1. **Re-run what the work invalidated** — a measurement recorded elsewhere that is now wrong, a
   roadmap entry a change unblocked, a rule that became checkable.
2. **Put the documents back to true**, in the same change: the *what changed → what must move* table.
3. **Write the changelog entry**, including what the first attempt got wrong and what caught it. That
   entry is most of what the harvest will read tomorrow.
4. **Run the gate** and report which selection ran.
5. **Commit**, in that repository's own style, so the bundle is clean before the harvest touches
   `tracking/` — otherwise `check-local` cannot tell the harvest's writes from the session's.

**If the maintainer says to leave work in flight alone**, harvest only what is already recorded, and
name in the report what was skipped and why. Never close somebody else's session without being told:
the uncommitted diff is their work.

## Phase 1 — the harvest, per repository

1. **Read what this repository recorded**, not what it summarises: the root instruction file, the
   area rules, the decisions log and its evidence, the changelog in full (it holds the discarded
   alternatives and the numbers), the roadmap, the audit script's comments, and the commit
   messages of the period. With several repositories open, one read-only agent per repository is
   cheap and keeps the reading from crowding out the judgement.
2. **Write each candidate in the form admission needs** (`knowledge/README.md`, *The lifecycle of a
   note*), **generalised before it is written** (`prompt-context.md`, principle 20): the claim
   **without a single project noun**, whether it is knowledge or method, the occurrence with its
   numbers as orders of magnitude or ratios, paraphrased rather than quoted, its identifiers named
   by their role, and its date, the boundary, the cost, the literature consulted
   against its source, and which existing note or principle it overlaps. **Consult the literature
   before writing anything**, and check each citation against its source: an established result is
   cited with what it contributes, a contradicting one kills or narrows the candidate, and nothing
   found is written as "none known".
3. **Apply the generality test strictly**, and report plainly when nothing survives. **That is the
   common and correct outcome**; a bundle that takes every offered learning is portable to nowhere.
4. **Extend before adding — as a candidate.** A new occurrence, number or boundary of an existing
   note is the most valuable thing a harvest produces, and it is *still* not written into the note
   here: the candidate line names the note it extends and carries the evidence, and the release
   applies it. Same for a claim a measurement here contradicts: that is a line in
   `tracking/experiments.md`, with the verdict — *confirms*, *moves the boundary* or *falsifies* —
   never an edit to the note.
5. **Run the cheap queued experiments** that this repository can answer, and record each one in
   `tracking/experiments.md` under *Run*: the date, the repository by mechanism rather than by name,
   what was run, the number, and the verdict.
6. **Friction that belongs to this repository** — a command that is awkward here, a check this host
   needs — goes in **this repository's** roadmap, not in the bundle's. Only the part that is true of
   any repository is a method candidate.
7. **Check and commit**: `bundle.py privacy .agents` passes, `bundle.py check-local` reports only
   `tracking/` changed, then one commit in this repository's own style. An allowance the privacy
   check lists exists only because the user asked for it. The bundle's own `roadmap.md` is not touched by the local step.
8. **Report**, per repository: what was found, what was refused and the reason for each, what
   experiments ran and what they showed, and what now waits for the next meta-session — plus, once,
   the carriers that were not open here and so were not harvested.

## When to run it

| When | Why |
|---|---|
| Before a meta-session, in every carrier | it is what `prompt-sync.md` phase 1 gathers; a carrier that has not run it contributes nothing but its divergence |
| When a friction is hit a second time here | the second occurrence is the evidence admission asks for, and it is available now |
| When a repository has run for a while without one | the changelog still holds the numbers; a year later it holds the summaries |

## What the local step must never do

- **Never write outside `tracking/`** in the bundle: not a note, not an index row, not a method
  document, not a header, not a version, not a digest.
- **Never bump a version or recompute a digest.** One writer per lineage, and it is the release.
- **Never promote a candidate to a note here**, however obviously true it is. It is one line in
  `candidates.md` with what it lacks, and the release decides — with every other carrier's
  candidates on the table, which is the only place the generality test can honestly be applied.
- **Never write what identifies a private repository** into `tracking/` — not its figures, quotes,
  identifiers, domain nouns or anyone's personal context — and never trust memory over
  `bundle.py privacy`.
- **Never invent a second occurrence.** One repository seeing something twice is one repository;
  the count that matters is across carriers, and the meta-session is where it is taken.
- **Never harvest a repository this session does not have open**, however reachable its path is. It
  is named as not harvested, and its own session writes its candidates.
- **Never harvest over an unclosed session in silence.** Either it is closed first, or what was left
  out is named in the report. A harvest that quietly misses the last week's work is worse than none:
  it will be trusted.
- **Never leave a refusal unwritten.** A learning that did not pass admission is a line with the
  reason, so the next harvest does not offer it again with the same gap.
