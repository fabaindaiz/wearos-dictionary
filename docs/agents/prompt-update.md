# Update a repository to a newer version of the method

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

**Copy the block below into the agent, at the root of the repository being
updated.** Put the newer copies of the method somewhere readable first — a
scratch path is fine — and **do not overwrite the old ones**: the old header is
the only record of what this repository already adapted and already declined.

**╔══════════ COPY EVERYTHING INSIDE THE BOX BELOW ══════════╗**

~~~text
You are updating this repository's copy of the method. Follow
`prompt-update.md` — in `docs/agents/` or wherever it was dropped in this
repo — with `prompt-context.md` beside it for the reasoning.

**FIRST, before reading anything else: run the pre-flight.** Open that document
§*Before you start*, ask me those questions in one message, and wait. I have to
tell you where the newer copy is before you can do anything at all.

**1. Establish both headers.** Read the method documents in this repo — every
file the `set` field names, and anything else in that folder — and report the
full header: `lineage`, `ancestry`, `version`, `adapted`, `declined`. If
there is no header, treat it as version 0, ancestry unknown, and say so. Read
the newer copy at `<path>` and report its header and its **Method changelog**.

**Stop immediately and report both headers if:** the ancestries diverge rather
than sharing a prefix (that is a human decision, not a triage); our version is
*higher* than theirs (an older copy is being pasted over a newer one); or the
header claims `adopted` but the artifacts it names do not exist (that is
bootstrap in repair mode, not an update). In any of those three cases, do
nothing else.

**2. List the deltas.** For every version between ours and theirs, list what it
added — principles, artifacts, phases, steps, sections — by name and number.
This is a list of *what is new*, not a diff of the prose.

**3. Triage each delta against THIS repository.** Read our `adapted` and
`declined` **first**: anything already adapted here is not a rename to propose
again, and anything already declined is not re-litigated unless the new
version changes the reason it was declined. Then one line per delta, with one
of the four verdicts, saying what each implies concretely here. Adapt before
you discard.

**4. Then look the other way.** What does **this** repository know that the
method does not? Apply the generality test to each candidate and report only
what survives — and say plainly if nothing does.

**5. Propose, and wait.** Which deltas you will apply, the concrete edit each
implies, what you will adapt and how, what you will skip and why, and any
candidates to send back up. **Write nothing until I approve.**

**6. After approval.** Replace the method file with the newer copy, then
**carry our header forward into it**: keep our `lineage`, `ancestry`,
`upstream`, `adopted` and every existing `adapted` and `declined` entry, take
the new `version`, and append this update's new adaptations and declines with
their reasons. A new copy that arrives with an empty header has erased
everything this repository learned about the method.

Apply each accepted delta as a **real edit** to this repo's own files — the
root instruction file, the decisions log, the roadmap, the audit script, the
skills — not as a note saying it should be done. Extend what exists; create
only for a guarantee with no home; never reorganise as part of an update.

**7. Then prune**, following §*The prune*. List every file in the method
folder the `set` does not name, diff each one against the current set section
by section, and give me the table — what is covered, what is covered worse and
should be lifted first, what is general and missing and should be added to a
prompt, and what is about *this* repository and must be **moved rather than
deleted**. Follow every inbound link. **Report it and wait before removing
anything**, and never delete a file you have not read in full.

Add a decisions row for anything now settled differently, and write one
changelog entry covering the update, including what you skipped and why.

Do not touch anything unrelated, and do not sweep in work that is already
uncommitted in the tree.

If you are running with limited context or reasoning, say so first: do steps 1
and 2 and present the deltas, but **do not decide the triage and do not
prune** — both are judgement about this repository, and the prune deletes
things. They need a larger pass.
~~~

**╚══════════════════ END OF WHAT YOU COPY ══════════════════╝**

---

**Run `prompt-evaluate.md` first** if you are not sure this repository needs an
update. It is read-only and its report says which document you want.

**What you will be asked for.** One decision point: the triage, where the agent
says what each delta means *here* and you say which ones to take. Nothing is
written before you approve it.

---

## Before you start — ask these

**Ask all of them in one message, then stop.** The rules behind this block are
in `prompt-context.md` §*The pre-flight*.

*The agent will print something like this, and then wait:*

~~~text
Before I update the method here, four things — reply **`defaults`** to take
the last three as proposed. The first one I need from you.

1. **Where is the newer copy?** A path I can read. I will **not** overwrite
   the copies already here until you approve the triage — the old header is
   the only record of what this repo already adapted and declined.
2. **Prune.** After the deltas, I will list the method files that are no
   longer part of the set and **propose** what to do with each. I delete
   nothing without your approval, and nothing at all until its content is
   verified as covered, lifted, or moved.
3. **Declined.** I will respect everything in the header's `declined` list and
   not re-propose it, unless the new release changes the reason it was
   declined. Say if you want any of it reconsidered.
4. **The gate.** After applying, I will run this repository's gate if it has
   one and it writes nothing.

Not asking, because the header and the repo answer them: which version we are
on, what this repo already adapted, and where each artifact lives here.
~~~

---

## What this document is, and what `prompt-context.md` holds

**This document owns the upgrade procedure: establishing both headers, listing the deltas, triaging them against this repository, the prune, and carrying the header forward.** Everything shared with the other prompts — the
set and which one to run, the method header and how copies are matched, the
enforcement ladder, the nineteen principles, the engineering standards, the ten
artifacts, adopting into a repository that already works, the platform's loading
mechanics, workspaces, model tiers, the pre-flight rules, and how the method
itself improves and travels — lives in **`prompt-context.md`**, once.

**Read `prompt-context.md` before triaging.** Its sections *Improving the
method, and distributing it*, *The pre-flight*, *Which document to run*, and
principle 19 are what the triage assumes. If it is absent, the deltas cannot be
judged properly: say so and stop.

**Precedence:** `prompt-context.md` is the record for principles, artifacts and
standards; each executable prompt is the record for its own procedure. A
disagreement is a bug in whichever document is not the record for that subject.

**Do not assume how many documents the set has.** Read the `set` field in the
header and list what is actually beside this file; a later release may add more.

---

## The triage

Every delta gets exactly one of four verdicts. **The third and fourth are as
valuable as the first**, and a triage that produces only "applies" has not been
done.

| Verdict | Means | Must also say |
|---|---|---|
| **Applies** | take it | the concrete edit it implies, naming the file |
| **Already have** | this repo arrived at it independently | where |
| **Does not apply** | it assumes something not true here | why, in this repo's own nouns — and what it **adapts to** instead, where it can |
| **Applies, but not yet** | it depends on something missing | what has to exist first |

*Does not apply* is rarely a discard. A delta about inspecting a rendered image
does not vanish in a repository with no images — it becomes "read the generated
SQL", or "print the report and read it". **Adapt first; discard only when there
is nothing to adapt to.**

---

## The other direction: harvesting

Rarer and more valuable. A repository that has been running the method for a
while usually knows something the method does not.

Ask it directly, then apply the **generality test**, strictly: *state the
learning without a single noun specific to this project.* If you cannot — if it
needs that repo's domain, its framework, its deploy script — it belongs in that
repository's own instructions and it is finished there. If you can, and the
sentence still says something, it is a candidate for the next method release.

Report only the candidates that survive, and say plainly when none do. **That is
the common and correct outcome.** A method that accepts every offered learning
accretes one repository's idiosyncrasies until it is portable to nowhere.

---

---

## The prune

A release does not only add. It supersedes documents, renames them, and folds
one into another — and a repository that has been through three updates without
a prune is carrying files that contradict the current set while still being
read, which is the worst state of the lot.

**So every update ends with a prune, and the prune's rule is absolute:**

> **Nothing is deleted until its content is verified as covered somewhere else,
> or moved to where it belongs.** Not "looks superseded". Not "the new document
> probably says this". Verified, section by section, and reported.

### The procedure

**1. List the candidates.** Every file in the method's folder that the `set`
field does not name, plus anything the set does name that is no longer the right
shape. Include files marked deprecated, superseded, or "kept so links do not
dangle" — that last note is how a dead file survives four releases.

**2. Diff each candidate against the set, by content and not by title.** Walk
its sections and classify every one:

| Verdict | Means | What happens |
|---|---|---|
| **Covered** | the current set says this, at least as well | the section is dropped with the file |
| **Covered, worse** | the set says it, less clearly than here | **lift the better wording into the set first**, then drop |
| **Not covered, general** | true of any repository, and the set is missing it | **add it to the right prompt**, then drop |
| **Not covered, local** | it is about *this* repository, not the method | **move it out of the method folder** to where that repository keeps such things |

The fourth verdict is the one that gets skipped, and it is the one that loses
things. A document about how *this* repo's rule system was built is not method —
but it is also not disposable. It goes somewhere; it does not evaporate.

**3. Follow every inbound link before removing anything.** Search the whole
repository — documents, the root instruction file, scripts, CI, settings, the
decisions log's enforcer column — for the file's name. **Every pointer is
updated or removed in the same change.** A dead pointer is worse than no pointer,
and it is the single most common breakage an update causes.

**4. Report the prune before doing it**, as a table: file, verdict per section,
where anything not covered went, and every inbound link found. **Then wait.**

**5. Only then remove.** And record the removal in the changelog entry with what
happened to its content — not just that it is gone.

### What the prune must never do

- **Never delete a file whose content you did not read in full.** Length is not
  a reason to skim; it is a reason to take longer.
- **Never delete something because it is old.** Age is not a verdict. A
  three-year-old document that is still true is still true.
- **Never fold a repository-specific document into the method** to avoid moving
  it. The method carries no project; that is what keeps it forwardable.
- **Never prune and update the version in silence.** Both go in the changelog
  entry, named.

---

## The invocation

**It is at the top of this file**, under *Paste this to start*, in a code
block so it copies clean — not repeated here, because two copies of a prompt
is one copy that will drift.


**What this costs, and why the triage is the job.** Copying the file is trivial.
A delta list of eight will typically land as four *applies*, two *already have*,
one *adapted*, and one *not yet* — and those four concrete edits are the entire
improvement. Skipping the triage and simply overwriting gives you a repository
whose method document describes practices it does not follow, which is worse
than being a version behind: it is being a version behind while claiming not to
be.

**Where the deltas come from.** Each session's closing review captures what it
learned; the learnings that pass the generality test become the next method
release. If there is nothing to bump, the honest move is not to bump — a version
history of empty releases teaches the next reader that versions mean nothing.
