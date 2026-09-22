---
name: state-review
description: Estado del sistema de instrucciones y **qué prompt del método corresponde correr**. Usar SIEMPRE antes de configurar, actualizar o ejecutar cualquier prompt de `.agents/method/` —update, bootstrap, evaluate, harvest, merge, sync— y cuando se pida "revisá el estado del repo", "¿está actualizada la documentación?", "state review", "actualizá el método", "aplicá el bundle", "¿queda algo por aplicar?", o al empezar a trabajar después de un tiempo sin tocar el proyecto.
allowed-tools: Bash, Read, Grep
---

# State review

> The `description` above stays in Spanish: those are the phrases **the user says**.

Bootstrapping is not the end: the system rots without a ritual. These questions, with evidence,
not from memory.

## 0. Which prompt does this actually want? — run this FIRST

**Before configuring, updating or executing any prompt in `.agents/method/`.** A prompt run in the
wrong situation does not fail; it does the wrong work and leaves a header saying it succeeded. So
the state is established first, and then the routing table decides — never the dates, never which
copy looks newer.

```bash
grep -h '^version:\|^digest:\|^adopted:\|^lineage:' .agents/method/prompt-update.md
sed -n '/^adapted:/,/^---$/p' .agents/README.md | head -20      # empty = the record is not kept
ls .agents/incoming/                                            # only README.md = no triage pending
python3 tools/audit_dictionary.py | grep -E 'digest|bundle'     # silence = the three check out
```

Then answer, in one line each, and **report before doing anything**:

| What you found | What it means | Run |
|---|---|---|
| `incoming/` holds more than its own `README.md` | the last triage never finished, and that is this session's first finding | finish it: `prompt-update.md` |
| a digest does not match its content | the header is a claim, not a fact | stop; compare content directly and tell the lineage owner |
| our `version` < the incoming one, ancestry a prefix | one side is ahead | `prompt-update.md` |
| ancestries share a prefix and then diverge | both moved; neither is newer | `prompt-merge.md` |
| same version, and this repo learned something | the repo is ahead of the method | `prompt-harvest.md` — and it may write **only** `.agents/tracking/` |
| header present, artifacts it names missing | the files were copied without the work | `prompt-bootstrap.md`, repair mode |
| nothing above, and the ask was "update the method" | there is nothing to update; say so | sections 1–8 below |

**And say what is still pending from the last one.** An update is finished when: the three
repository fields are carried, every accepted delta is a real edit, the prune ran, `incoming/` is
empty, a decisions row exists for anything now settled, and there is one changelog entry. Anything
on that list not done is what the user probably meant, whatever they asked for.

⚠️ **A carrier may not add or edit a method document.** `prompt-harvest.md` §*What the local step
must never do* is explicit: not a note, not an index row, **not a method document**, not a header,
not a version, not a digest. What this repo finds missing in the method goes as one line in
`.agents/tracking/candidates.md`, and the release decides. Editing the method here **is forking**
(D-059), with a new opaque lineage id and a recomputed digest.

## 1. Does every document on the map exist, and is it still true?

```bash
python3 tools/audit_dictionary.py
```

The audit checks that the map resolves. Whether the **content** is still true has to be looked at:
check every number in `docs/formato-pack.md` and `docs/architecture.md` against the code. **A
document pointing at a deleted file is worse than no document**, and it is the most common decay
in an agent-assisted repo, because agents delete code faster than they re-read prose.

## 2. Does every rule still have an enforcer, and did any of them fire this period?

```bash
grep -c "| —" docs/decisions.md     # decisions that can be broken in silence
```

That number going up is the alarm. A rule that lost its enforcer went back to rung 1 without
anyone deciding it.

## 3. What changed that should have been a decision row and was not?

```bash
git log --oneline $(git log -1 --format=%H -- docs/decisions.md)..HEAD
```

Commits after the last change to `decisions.md`. If any of them took a design decision, its row is
missing.

## 4. What on the roadmap is already closed?

By being built, or **by measurement**. The second is the valuable one: if a number retired an
idea, it goes to *Cerrado por medición* with the number, so it stays retired.

## 5. Did `CLAUDE.md` blow its budget?

```bash
wc -l CLAUDE.md    # has to stay under 200
```

If it grew, **which section grew** is the question. A section that grows is the sign that it
became a document and has to be moved, leaving a pointer.

## 6. Which rules are still at rung 1 and could be promoted cheaply?

The ones in `docs/decisions.md` with `—` in *Enforced in*. In this repo, the known candidates:

- **D-025** (`glance-wear-tiles` forbidden) → a grep in the audit, trivial.
- **D-031** (CC BY-SA attribution visible) → a ship-blocking check once the UI exists.
- **D-002** (packs through `BundledSQLiteDriver`) → checkable once `:dict-data` exists.

## 7. Is the method still the one we say we follow?

```sh
grep -h "^version:\|^digest:\|^adopted:" .agents/method/prompt-update.md
python3 tools/audit_dictionary.py | grep -E "digest|bundle"   # silence = the three check out
```

`adapted` and `declined` **empty** after an update is the sign the header is not being maintained,
and the next update will re-propose everything already rejected (D-059, D-060).

## 8. The smells, checkable in a minute

Each one has a short answer; what matters is that none of them gets answered from memory.

| Smell | How to look |
|---|---|
| The roadmap has no **Hecho** entry at all | `grep -c "Estado.*Hecho" docs/roadmap.md` — either nothing was finished, or finishing does not write back |
| §Proceso y herramientas is **empty** | Friction is not being written down. Not that there is none |
| Every changelog entry came out perfect | `grep -c "Qué salió mal" .claude/logs/agent-changelog.md` against the total number of entries. Nobody works like that: the detours are being edited out |
| A decision with enforcer `—` that **could** be checked | Question 6 |
| Two documents assert the same number | One of them is already stale and nobody knows which |
| A gate limit was raised alongside a feature | `git log -p -- tools/audit_dictionary.py` — the limit *was* the message |
| No session ever proposed a process improvement | It is failure mode 6, and it is invisible precisely because nothing breaks |

## And the question that is not on the list

**Is there still no test running on a watch?** It is the class of bug this repo cannot see, and
while the answer is "yes", any claim about behaviour on Android is ASSUMPTION — however well
tested it is on the desktop.
