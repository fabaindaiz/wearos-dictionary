---
bundle: agent-guides
lineage: g-8b5800/main
version: 8
slug: no-simultaneous-deploy
topic: evolving-contracts
claim: Two things that deploy separately can never change at the same instant, so any change requiring them to is not a plan.
confidence: reasoned
reach: architecture, planning
---

# No simultaneous deploy

## Why it works

A schema and the code that reads it. Two services either side of an API. A producer and its consumers. A mobile client and its backend. In every pair, one is updated before the other — by seconds in a rolling restart, by weeks when the other end is a device in the field.

So there is always a window in which **the old and the new are both live**, and a change that is only correct once both sides have moved is not a migration, it is an outage with a plan attached. This is not a deployment-tooling problem to be solved by better orchestration: even a perfectly coordinated deploy has an interval where half the fleet is on each version, and rollback puts you back in that interval deliberately.

The general remedy has three phases and a name: **expand** — add the new thing while the old keeps working; **migrate** — move readers and writers across, one at a time, at their own pace; **contract** — remove the old thing only once nothing refers to it. Each phase is independently deployable and independently revertible, which is the property that makes it safe rather than merely orderly.

It generalises well past databases, because the constraint is not about storage — it is about **two things with independent release cycles**. Message formats, configuration schemas, file formats, feature flags, and enum values written to a store all obey it.

## When it does NOT apply

- **When both sides genuinely deploy as one artefact.** A single binary with an embedded database, a desktop app shipping its own migrations. Then the simultaneous change is real and expand/contract is ceremony.
- **When nothing has read the old shape yet.** Before first release, or on a table nothing consumes, a direct change is correct and three phases are waste.
- **When the window is provably empty** — a maintenance stop with the system offline and no queued work. Rare, expensive, and worth it occasionally; the mistake is assuming the window is empty when it is merely short.

Two refinements from a transactional service. A **tolerant reader** is what makes data-before-code safe — do not forbid unknown fields on a model that reads another system's documents — but tolerance is per *field*, not per enum *value*: a reader that ignores unknown keys still raises on an unknown member of a known enum. And a cutover that moves **ownership** of in-flight work strands whatever the old owner had already rejected: it loses its last owner unless the cutover says who drains it.

## What it costs

Three deploys instead of one, a period where both shapes exist and the code tolerates both, and the discipline to actually run the contract phase — which is the one that gets skipped, leaving systems full of columns nobody reads and branches nobody takes. **A missing contract phase is the real cost of this pattern**, and it should be scheduled when the expand is written, not left to be noticed.

## Where it came from

Generalised from a migration checklist that listed the safe form of each schema change (add a column nullable, backfill, then enforce; stop using a column, deploy, then drop it). The checklist is correct but reads as a list to memorise; the reason behind every line is the single constraint above, and stating that instead makes the same rules derivable — including for the cases the checklist does not list.

Judgement, unmeasured.

**An occurrence, later.** A client application that keeps data on the device — settings, lists, imported files — meets the same constraint without any server: the file on the device outlives the build that wrote it, and meets builds both older and newer. That repository migrates step by step (`N` to `N+1`, so a device that skipped three versions arrives by the same path), adds a key without a version bump because an older file gets the default and an older build ignores the key, appends to an enum stored as a number instead of inserting into it (inserting would have turned every saved choice into another), stores ids rather than positions, and rejects only a file from a *newer* build — with a notice, never by deleting it.

## Literature

- **[ParallelChange](https://martinfowler.com/bliki/ParallelChange.html)** — Danilo Sato, on Martin Fowler's bliki, 2014 (*byline verified 2026-09-23; earlier copies credited Fowler*), naming and formalising a practice already in use for schema and API evolution. The three-phase structure **expand, migrate, contract** is the canonical framing, and the property that matters is that it changes long-lived shared state *"without taking the system offline and without forcing a coordinated deploy."*

  **What we take from it:** the last clause is the whole point and is usually dropped when the pattern is taught as a database technique. The pattern exists because a coordinated deploy is not available, not because databases are special.

  **Where we go further:** we state the constraint first and the three phases as its consequence, because an agent that has memorised the phases will apply them to a single-artefact deploy where they are pure cost, and will fail to apply them to a message format where they are essential.

## Evidence

**None measured.** No incompatible-deploy incident was observed here; the note is the general form of a checklist rather than the record of a failure. The on-device occurrence above is a design, with its migrations exercised on a handful of cases; it shows the constraint reaching a single-artefact client, not a failure it caused.

What *would* settle it: take one pair with independent release cycles and measure the window — how long both versions are live during a normal rolling deploy, and how long during a rollback. A number there converts "there is always a window" from an argument into a fact about this system.
