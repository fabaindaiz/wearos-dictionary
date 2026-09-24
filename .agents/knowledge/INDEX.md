---
# bundle-header — provenance for this document. Identical across the bundle.
bundle:    agent-guides
lineage:   g-8b5800/main
ancestry:  [g-c7344c, g-099a8a, g-8b5800]
version:   11
component: knowledge
released:  2026-09-24
---

# Index — when to read which note, and how to check it holds

**This file is the mechanism.** The notes are read on demand; this is what decides which one, and what to run to show that the heuristic was respected. Keep it scannable, or nothing below it gets read.

Every note in `notes/active/` and `notes/review/` is reachable from here and every listed note exists — checked by the repository's audit, because a dead pointer in an index is worse than an index nobody wrote.

This file is the entry point: the phase guide for every note, and a route to the area index that holds each note's check. There is no cap on notes — the reason is in `README.md`, *Keeping it usable*.

**A note marked ⚠ review** sits in `notes/review/`: its evidence or its admission is disputed and a verdict is pending. Use it, and say in the report that it is under review; its *Evidence* section opens with the reason. The folder a note sits in is its state — `README.md`, *The lifecycle of a note*.

## How an agent uses this index during a task

1. **At the start**, find the phase you are in under *By phase of work* and skim the claims of the notes it lists. Open a note only when its claim touches what you are about to do.
2. **Before a design decision**, look up the action under *By what you are about to do*, in the area index (`areas/behaviour.md`, `areas/evidence.md`). The third column is the case where the default answer is wrong — if it matches, read the note.
3. **Before claiming the work is done**, run the *Verify by* check of every note you relied on (in its area index), and say in the report which ones ran and what they showed. A heuristic that was read but not checked is an opinion that happened to be nearby.
4. **When the work contradicts a note** — a measurement, a test, an incident — do not quietly work around it. Record it in `../tracking/experiments.md`; a note that is contradicted by evidence goes to review, and is revised or retired, never ignored.

## By phase of work

| Phase | The question to ask | Notes |
|---|---|---|
| **Plan and design** | What will this touch that already depends on it, and what happens under retries, a second instance, a crash, an abuser? | [retry-over-irreversible-effect](notes/active/retry-over-irreversible-effect.md) · [order-writes-by-failure-residue](notes/active/order-writes-by-failure-residue.md) · [in-process-guarantees](notes/active/in-process-guarantees.md) · [no-simultaneous-deploy](notes/active/no-simultaneous-deploy.md) · [persist-inputs-derive-verdicts](notes/active/persist-inputs-derive-verdicts.md) · [same-answer-or-refuse](notes/active/same-answer-or-refuse.md) · [kill-switch-reaches-every-path](notes/active/kill-switch-reaches-every-path.md) · [one-write-gate-makes-read-only-real](notes/active/one-write-gate-makes-read-only-real.md) · [derived-over-chosen-identifiers](notes/active/derived-over-chosen-identifiers.md) · [a-default-scope-is-the-widest-one](notes/active/a-default-scope-is-the-widest-one.md) · [secrets-survive-rotation](notes/active/secrets-survive-rotation.md) · [abuser-controlled-exemption](notes/active/abuser-controlled-exemption.md) · [refuse-only-on-unassertable-evidence](notes/active/refuse-only-on-unassertable-evidence.md) · [derive-state-from-one-clock](notes/active/derive-state-from-one-clock.md) · [untrusted-package-is-parsed-never-loaded](notes/active/untrusted-package-is-parsed-never-loaded.md) · [merge-by-shared-fact-not-shared-shape](notes/active/merge-by-shared-fact-not-shared-shape.md) · [detect-by-observation-not-build-flag](notes/active/detect-by-observation-not-build-flag.md) · [copied-instruction-claims-its-origin](notes/active/copied-instruction-claims-its-origin.md) |
| **Design a dataset, a population or a model** | Could any row, filter or feature know something that did not exist yet at its instant? | [population-by-outcome](notes/active/population-by-outcome.md) · [truncate-the-world-to-test-as-of](notes/active/truncate-the-world-to-test-as-of.md) · [censoring-is-information](notes/active/censoring-is-information.md) · [absolute-level-is-a-time-index](notes/active/absolute-level-is-a-time-index.md) · [same-answer-or-refuse](notes/active/same-answer-or-refuse.md) · [read-a-snapshot-at-its-own-end](notes/active/read-a-snapshot-at-its-own-end.md) |
| **Implement** | What does this code do when a value is missing, a clause is lost, a side call fails, a nested object is sent? | [fail-closed-defaults](notes/active/fail-closed-defaults.md) · [a-default-scope-is-the-widest-one](notes/active/a-default-scope-is-the-widest-one.md) · [absent-constraint-widens](notes/active/absent-constraint-widens.md) · [absence-is-a-third-value](notes/active/absence-is-a-third-value.md) · [best-effort-side-channels](notes/active/best-effort-side-channels.md) · [nested-partial-update-replaces](notes/active/nested-partial-update-replaces.md) · [derive-state-from-one-clock](notes/active/derive-state-from-one-clock.md) · [close-the-loop-in-the-actuators-frame](notes/active/close-the-loop-in-the-actuators-frame.md) · [derived-copy-goes-stale-silently](notes/active/derived-copy-goes-stale-silently.md) · [cleanup-belongs-to-the-supervisor](notes/active/cleanup-belongs-to-the-supervisor.md) · [validate-each-transformation-run](notes/active/validate-each-transformation-run.md) · [sanitised-value-must-replace-the-raw](notes/active/sanitised-value-must-replace-the-raw.md) |
| **Write tests** | Would this test fail if the code were wrong, against the real dependency? | [test-double-fidelity](notes/active/test-double-fidelity.md) · [a-check-must-be-seen-to-fail](notes/active/a-check-must-be-seen-to-fail.md) · [same-answer-or-refuse](notes/active/same-answer-or-refuse.md) · [coverage-measures-execution](notes/active/coverage-measures-execution.md) · [truncate-the-world-to-test-as-of](notes/active/truncate-the-world-to-test-as-of.md) · [sweep-the-rendered-extremes](notes/active/sweep-the-rendered-extremes.md) · [unrunnable-system-moves-the-gate](notes/active/unrunnable-system-moves-the-gate.md) |
| **Review** | Which constraint, switch or cap could this diff have removed without any test noticing? | [absent-constraint-widens](notes/active/absent-constraint-widens.md) · [a-default-scope-is-the-widest-one](notes/active/a-default-scope-is-the-widest-one.md) · [kill-switch-reaches-every-path](notes/active/kill-switch-reaches-every-path.md) · [abuser-controlled-exemption](notes/active/abuser-controlled-exemption.md) · [same-answer-or-refuse](notes/active/same-answer-or-refuse.md) · [fail-closed-defaults](notes/active/fail-closed-defaults.md) · [untrusted-package-is-parsed-never-loaded](notes/active/untrusted-package-is-parsed-never-loaded.md) · [merge-by-shared-fact-not-shared-shape](notes/active/merge-by-shared-fact-not-shared-shape.md) · [sweep-the-rendered-extremes](notes/active/sweep-the-rendered-extremes.md) · [copied-instruction-claims-its-origin](notes/active/copied-instruction-claims-its-origin.md) · [sanitised-value-must-replace-the-raw](notes/active/sanitised-value-must-replace-the-raw.md) |
| **Verify and report** | Does the number mean what the sentence says, measured where it is claimed? | [reproduce-the-checkout-not-only-the-environment](notes/active/reproduce-the-checkout-not-only-the-environment.md) · [metric-against-trivial-predictor](notes/active/metric-against-trivial-predictor.md) · [report-coverage-before-findings](notes/active/report-coverage-before-findings.md) · [ratchet-in-a-pinned-environment](notes/active/ratchet-in-a-pinned-environment.md) · [a-check-must-be-seen-to-fail](notes/active/a-check-must-be-seen-to-fail.md) · [validate-each-transformation-run](notes/active/validate-each-transformation-run.md) · [sweep-the-rendered-extremes](notes/active/sweep-the-rendered-extremes.md) · [unrunnable-system-moves-the-gate](notes/active/unrunnable-system-moves-the-gate.md) |
| **Debug or investigate** | Is the discrepancy in the world, in the join, in the clock or in the environment? | [reproduce-the-checkout-not-only-the-environment](notes/active/reproduce-the-checkout-not-only-the-environment.md) · [count-both-sides-and-use-a-control-window](notes/active/count-both-sides-and-use-a-control-window.md) · [derive-state-from-one-clock](notes/active/derive-state-from-one-clock.md) · [read-a-snapshot-at-its-own-end](notes/active/read-a-snapshot-at-its-own-end.md) · [ratchet-in-a-pinned-environment](notes/active/ratchet-in-a-pinned-environment.md) · [report-coverage-before-findings](notes/active/report-coverage-before-findings.md) · [nested-partial-update-replaces](notes/active/nested-partial-update-replaces.md) · [derived-copy-goes-stale-silently](notes/active/derived-copy-goes-stale-silently.md) · [detect-by-observation-not-build-flag](notes/active/detect-by-observation-not-build-flag.md) · [close-the-loop-in-the-actuators-frame](notes/active/close-the-loop-in-the-actuators-frame.md) |

## The areas, and where each check lives

Each note is under exactly one topic, and each topic in one area. The area index holds the topic tables with their *Verify by* checks, the *about to do* rows, and how well founded each note is.

| Area | Index | Topics |
|---|---|---|
| What the system does | [areas/behaviour.md](areas/behaviour.md) | `distributed-correctness` · `failure-behaviour` · `evolving-contracts` · `time-and-control` · `adversarial-controls` · `identity-and-naming` |
| What the data and the checks tell you | [areas/evidence.md](areas/evidence.md) | `data-correctness` · `measurement` · `verification` |

## How well founded is any of this

**Read this before weighting a note.** The two axes are independent and confusing them is the main way a knowledge base misleads:

| | What it means |
|---|---|
| **The claim** | how well the general principle is established — usually by the literature each note cites |
| **Our application** | whether *we* demonstrated it here — usually not |

Each area index lists every note's two answers. A note is `measured` only when a number came from running the method in a repository and was written down at the time — which is the only way a note earns it; its `confidence` field says which it is. The rest are honest `reasoned` notes; each one's *Evidence* section names the experiment that would settle it, and `../tracking/experiments.md` is where those experiments are queued.

## Keeping it usable

What is kept bounded is attention, not the number of notes; the rules, and why there is no cap, are in `README.md` under *The lifecycle of a note*. In short: every note is reachable (under its topic, in at least one phase above, and in at least one *about to do* row of its area); an index that stops being readable in one pass is split by area; a new occurrence, number or boundary extends the note it belongs to; every harvest closes with a review that ends in verdicts; and a learning that has not passed admission waits as a line in `../tracking/candidates.md`.

## Retired

Retired notes are kept in `notes/retired/` and leave the tables above; `../tracking/retired.md` is the chronological log of each retirement and its evidence.

## What is deliberately not here

- **Patterns the agent already knows.** Clean architecture, DI, repository pattern, the standard library. If the default behaviour is already right, a note costs attention and buys nothing.
- **Anything that needs a project noun to state.** That is a decision; it belongs in that repository's `docs/decisions.md`.
- **The rituals of how work is done** — the session loop, committing, reviewing, documenting. That is the method, in `../method/`. A claim about what a check or a tool can and cannot tell you is knowledge, and the method points to it.
