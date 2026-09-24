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

# Align every carrier of the bundle in one meta-session

## ▶ Paste this to start

**List the carriers first**, in the local manifest `~/.config/agent-guides/carriers.toml`
(`carriers = ["/path/to/repo", ...]`) — this machine's paths, never written into the bundle.
Then paste the block below.

**╔══════════ COPY EVERYTHING INSIDE THE BOX BELOW ══════════╗**

~~~text
You are running a meta-session over every carrier of the agent guides. Follow
`.agents/method/prompt-sync.md`, with `prompt-context.md` beside it for the
reasoning, and run its mechanical steps with `.agents/tools/bundle.py` — never
by a script written for the occasion.

**FIRST, before reading anything else: run the pre-flight.** Open that document
§*Before you start*, ask me those questions in one message, and wait. Then
read only this:

Reads:
- method/prompt-sync.md
- method/prompt-merge.md §Phase 2 — Classify every item (read-only). Report before writing. §Reconciling two divergent knowledge notes §Reconciling the headers
- method/prompt-context.md §Workspaces: several repositories at once §Keeping the set versioned, so other copies can catch up §20. Nothing private travels, directly or by reconstruction
- README.md §The fields that are this repository's
- knowledge/README.md
- tracking/candidates.md
- tracking/experiments.md

Then work the three phases. **Phase 1 is read-only, and you write nothing into
any carrier until I approve the reconciliation table.** Phase 2 happens in each
carrier by itself, under that carrier's own conventions. Phase 3 does not end
until `bundle.py align` reports every carrier aligned, or names the ones that
are not and why.

Non-negotiable while you work: every item gets a verdict; nothing a carrier
added is lost without being named; each carrier keeps its own `adopted`,
`adapted` and `declined`; each carrier's gate runs in that carrier; uncommitted
work in a carrier belongs to whoever left it.

Privacy (principle 20): nothing that enters the release or `tracking/` may
identify, directly or by reconstruction, a private carrier, its people or its
users; generalise first, and `bundle.py privacy` must pass before stamping.
~~~

**╚══════════ COPY EVERYTHING INSIDE THE BOX ABOVE ══════════╝**

---

## Before you start — ask these

**Ask all of them in one message, then stop.**

~~~text
Before I bring every carrier onto one version, three things — reply `defaults`
to take them as proposed.

1. The carriers. I will use the manifest's list and compare it with
   `tracking/carriers.md`. A carrier registered there with no path here stays
   out and is named as not reached. Say if any path is missing or should be left out.

2. Uncommitted work. Where a carrier's `.agents/` has uncommitted changes, I
   will stop on that carrier and ask, unless you tell me now that they are
   yours and belong in this release.

3. Commits. By default I prepare each carrier's change and offer one commit per
   carrier, following that repository's own commit rules and branch. Say if I
   should commit, or leave every tree uncommitted.
~~~

---

## Why one meta-session and not an update per repository

`prompt-update.md` carries one release from one copy to another, and `prompt-merge.md` joins two copies that diverged. **When several carriers have moved, running those pairwise is how a bundle loses things**, and each of these was observed when it was done by hand:
the base re-derived by hand from one repository's history; one repository's `adapted`/`declined`
carried into all of them, and same-day `tracking/` rows dropped by the copy that overwrote them; a
digest "equal in every repository" that depended on the locale, because several documents each
carried a recipe; a copy distributed with its own audit never run; and nobody recording who the
carriers were — the next session knew how many there had been and could not find them all.

The meta-session is the same verdicts as a merge, taken **once for all carriers**, applied **in each carrier by itself**, and **closed by a check** rather than by a sentence in a changelog.

## The cycle: two meta-sessions around one round of harvests

A group update is **three steps, in this order**, and each one exists because of what the next one
would otherwise get wrong:

| | What runs | Why it is where it is |
|---|---|---|
| **1. Align first** | this document — and when every carrier is already on one version, only `bundle.py align` and `bundle.py check-local` | every harvest then reads the *same* knowledge base and writes its candidates against it. A carrier a version behind proposes what another already has, and a carrier with a forked note proposes a conflict instead of a learning |
| **2. Harvest, per carrier** | `prompt-harvest.md` — phase 0 closes what that repository has in flight, phase 1 writes its candidates into `tracking/` | the learning is inside each repository, and only a session with that repository open can read its record, run its gate and have its owner review the commit |
| **3. Release** | this document, in full | every carrier's candidates on one table is the only place the generality test is honest — a claim offered by two of them is a second occurrence, and by one is a candidate. One release, applied to all |

**Step 1 is cheap when nothing diverged, and it is not skipped for that reason.** `align` says every
carrier holds one version; `check-local` says none of them edited what only a release may write.
Both clean, the harvests start. Either one dirty, this document runs in full first — "nothing
diverged" is a claim, and those two commands are what turns it into a fact.

**Between step 2 and step 3, nothing is written to the bundle but `tracking/`.** That is the whole
reason the local step is defined by what it may not write: it is what makes the third step a
gathering rather than a merge.

## The tool

`.agents/tools/bundle.py` is one file, standard library only (Python 3.9+; 3.11+ reads any TOML
manifest, older ones its documented form), with its own tests inside (`bundle.py selftest`). It
finds its own bundle from where it lives, so it runs the same from any folder, and it needs no
environment. It is outside the digest recipe — the recipe covers documents — so `align` compares
it byte for byte across carriers instead.

**A command that writes is given the repositories; it never works them out** (the workspace rule,
`prompt-context.md` §*Workspaces: several repositories at once*). Read-only commands may fall back
to the machine's manifest, and when they do they say so in place of the list of carriers left out.

| Step | Command | Writes |
|---|---|---|
| verify one copy | `bundle.py digest [TREE] --check` | nothing |
| a carrier's id | `bundle.py carrier-id [REPO]` prints the stored one and refuses if there is none; `--mint` writes a random one, once | nothing; `--mint`: the carrier's `carrier:` header field |
| the privacy check | `bundle.py privacy [TREE] [--paths FILE...]` — also run by `digest --check` | nothing |
| the record-id check | `bundle.py ids FILE...` — format, prefix, duplicates | nothing |
| a new lineage id | `bundle.py id g|m|k PART...` — derived from the parents, never chosen | nothing |
| a record id in a carrier | `bundle.py id d|i|s TEXT... [--repo REPO]` — minted once, frozen | nothing |
| what each session loads | `bundle.py report [TREE] [--json]` | nothing |
| phase 1 | `bundle.py gather --out DIR` | only `DIR` |
| the base it found | printed by `gather`: the rule, and **which carrier's commit** |  |
| the loss check | `bundle.py lost BASE MERGED SNAPSHOT...` | nothing |
| the release's headers | `bundle.py stamp TREE [--bump K,...] [--fork K=ID] [--against DIR]` | the tree |
| the carriers table | `bundle.py register TREE` | the tree |
| phase 2 | `bundle.py splice MERGED REPO...`, then `--write --backup DIR` | each carrier, after a backup |
| phase 3 | `bundle.py align` | nothing |

## Phase 1 — Gather (read-only)

0. **Every carrier should have closed its open session and run its local step** (`prompt-harvest.md`,
   phases 0 and 1) since the last release: that is what there is to gather. A carrier with work in
   flight has learnings that are in nobody's record yet. Run `bundle.py check-local` in each. A carrier that changed something
   outside `tracking/` has forked it locally — that is a finding, reported and triaged as a divergence
   of its own, never folded in silently.
1. **List the carriers**: this session's workspace — **every repository open here and only those** —
   each with its carrier id, set against the rows of `tracking/carriers.md` and against the local
   manifest. A reached carrier with no `carrier:` field mints one with `bundle.py carrier-id --mint`
   before anything else; an id is never derived from anything about the repository. A carrier registered, or known to this machine, that the workspace does not hold is
   **not reached**: it stays in the table, `bundle.py` names it on every run, and nothing is written
   into it.
2. **Verify every copy**: `bundle.py digest --check` in each. A header whose digest does not match
   its content is not believed; say so, and let content decide from here.
3. **Run `bundle.py gather --out DIR`.** It snapshots every carrier, finds the **base** — the newest
   state of *everything that travels* that every carrier's history once held; only if there is none,
   a commit whose header states the `forked_at` another carrier declares — and writes the N-way
   table: every file `same`, `changed by …`, `all changed` or `new in …`. It prints which rule found
   the base **and which carrier's commit it is**: read that line. The base is never found by the
   stamped digest, which does not cover `tracking/` (method changelog, row 21, has the incident).
4. **Give every item a verdict**, with the rules of `prompt-merge.md` Phase 2 — *same*, *only in*,
   *divergent*, *undecidable* — now across all carriers at once. An item one carrier lacks is checked
   against that carrier's `declined` before it is called a gap. Two notes that disagree are reconciled
   by `prompt-merge.md` §*Reconciling two divergent knowledge notes*, never by date.
5. **Report one reconciliation table and wait for approval.** One table and one approval for every
   carrier: this is the whole negotiation, and it is cheap to read.

## Build the release, once

**Where it is built depends on where it came from.** When it merges what several carriers moved,
build it in a scratch tree, never inside a carrier: no carrier's tree is the base of the others.
When it was authored in one carrier and every other one is still at the base — the gather table
says `changed by` that carrier and nothing else — that carrier's tree **is** the release
(`prompt-update.md`, *One release, several repositories*, step 1). Either way:

1. **Start from the carrier that holds the most**, and apply the approved verdicts.
   **Candidates become the bundle here, and only here.** Each approved candidate is generalised
   first (`prompt-context.md`, principle 20): what it says may not identify a private carrier,
   directly or by reconstruction, and a changelog row is no exception. Then each approved candidate
   from the carriers' `tracking/candidates.md` runs admission (`knowledge/README.md`, *The lifecycle of a
   note*) or, for the method, the generality test. **Extend before adding**: a new occurrence of an
   existing claim goes into that note, and notes whose *Evidence* is out of date are updated. There
   is no cap on notes, and a note admitted in this release may only be *kept* or *queued* by its
   closing review — merging, superseding or retiring it waits for a later release. What does not
   pass stays a line in `tracking/candidates.md` with what it lacks; experiments named by new notes
   go to `tracking/experiments.md`. Every new note gets its index rows in the same change —
   `knowledge/INDEX.md` and its area index, at least one phase and one *about to do* row, with its
   *Verify by* check. A method change gets its row in `method/changelog.md`.
2. **Run `bundle.py lost BASE RELEASE SNAPSHOT...` against every carrier's snapshot.** Every line a
   carrier added over the base that the release does not contain is printed. The release is not
   finished while any line is printed that is not an approved removal — and each approved removal
   is named in the changelog. **A line removed for privacy is an approved removal**, and it is
   named generically ("carrier-specific detail removed for privacy"), never restated. Then run
   `bundle.py privacy RELEASE`: the release is not stamped while it reports a failure, and every
   allowance it lists was given by the user, explicitly.
3. **Stamp it**: `--bump` when one line moves forward, `--fork` with an id from `bundle.py id` when
   lines were merged (`prompt-merge.md` §*Reconciling the headers*), `--against` the base so each
   document's own version moves only if its body did. The digests are written by the same recipe
   `digest` checks.
4. **Register every reached carrier** in the release's `tracking/carriers.md` with `bundle.py
   register`, at the release's versions.

## Phase 2 — Apply, in each carrier by itself

For each carrier, and **with that carrier as today's repository** (`prompt-context.md`
§*Workspaces*):

1. **Dry run first**: `bundle.py splice RELEASE REPO`. It lists what would be written and removed.
2. **Write**: `--write --backup DIR`. It refuses a carrier whose travelling bundle files carry
   uncommitted changes — they belong to whoever left them — keeps the carrier's `adopted`, `adapted`
   and `declined` in every header that has them, keeps its local files (`evaluation-*`, anything in
   `incoming/`), and backs up the carrier's whole bundle before writing.
3. **Triage what this carrier refuses.** The body is the same in every carrier by definition; a
   carrier that does not take a delta records it in its own `declined`, with its reason written for a
   stranger — it never edits its copy of the body.
4. **Adapt the host**, if the release asks for it: an audit that must read a new structure, a
   linter that must leave `.agents/` alone. That is the carrier's own code, in its own style.
5. **Run that carrier's gate**, and report exactly which selection ran there. There is no
   workspace-level green.
6. **One changelog entry** in that carrier's format and language, at its structural insertion
   point, with an `s-` id minted in that carrier (`prompt-context.md`, artifact 5), and **one
   commit** on that carrier's branch under its own rules — offered, or made if the pre-flight said
   so.

## Phase 3 — Align, and close

1. **`bundle.py align` over every reached carrier.** It fails on a declared digest that is not true,
   a file present in one carrier and not another, a body or a tool that differs, a header that
   differs outside the carrier's own fields, and a carrier missing from `tracking/carriers.md` or
   registered at another version. **The meta-session is not closed while it reports anything.**
2. **Empty every `incoming/`** that this session triaged.
3. **Name what was not reached**: every registered carrier with no path here goes into
   `roadmap.md`, *Blocked outside*, by its id alone and never with a description, to receive the
   release through its `incoming/`.
4. **The closing report**: per carrier, its branch, its commit, the gate selection that ran, and what
   it declined; the verdict counts; every *divergent* item and how it was reconciled; every
   *undecidable* one, which is the agenda for the next meta-session; and where the backups are.

## What a meta-session must never do

- **Never write a carrier before the one table is approved**, and never build the release inside a
  carrier.
- **Never compute a digest, a base or a splice by a script written for the occasion.** The tool is
  the one recipe; a second recipe is how the locale got into a published digest.
- **Never carry one carrier's `adopted`, `adapted` or `declined` into another.**
- **Never write over uncommitted bundle files** without their owner's word.
- **Never close on "the digests match"** — close on `align`, which also compares tools, headers and
  the registry.
- **Never guess a carrier.** One that is not reached is named, not assumed aligned.
- **Never let a private carrier become recognisable** in the release, in `tracking/` or in a
  changelog row, and never write a description beside a carrier id. `bundle.py privacy` is the
  check; the rule holds where the check cannot see.
- **Never write into a repository this session does not have open**, even when its path is in the
  manifest and the splice would be identical. `bundle.py splice` refuses it; that refusal is the
  rule, not an obstacle to work around.
