---
bundle: agent-guides
lineage: g-8b5800/main
version: 8
slug: order-writes-by-failure-residue
topic: distributed-correctness
claim: When two writes cannot be atomic, order them so a crash between them leaves the state you can recover from; acknowledge an at-least-once delivery right after its one non-idempotent step, never after slow side work.
confidence: reasoned
reach: architecture, review
---

# Order writes by what a failure leaves behind

## Why it works

Two writes to two systems — a local record and a gateway, evidence and state, a ledger and a notification — cannot share a transaction. A process can die between them, and it will. The question is not whether the gap exists but **which half-done state it leaves**, and the order of the writes decides that:

- Write the **evidence before the state** it justifies: if the state write fails, the user is asked again; the reverse marks someone as consented with nothing to prove it.
- Update the **local record before deleting the remote resource**: the reverse leaves the local side pointing at something that no longer exists.
- When the second write can no longer be retried — the external effect has already happened — say so in a distinct, searchable log line that names the manual reconciliation required.

The same reasoning decides when to acknowledge a message that will be redelivered on timeout. Everything after the non-idempotent step is at risk of being re-executed together with it: a slow receipt or a notification after the balance update turns a timeout into a second balance update. Acknowledge as soon as the non-idempotent step commits, and move the side work after the acknowledgement, where it is best-effort.

## When it does NOT apply

- **Both writes share a transaction.** Use it.
- **Both residues are equally bad.** Then ordering cannot help, and the answer is a saga with compensations or a transactional outbox.

## What it costs

A reconciliation path for the residue, which someone has to own. Side work moved after the acknowledgement becomes best-effort and needs monitoring, because it can now fail silently.

## Where it came from

A transactional service: consent evidence written before consent state; a local record of a stored credential updated before the remote deletion, because the reverse would leave the user holding a reference to something that no longer exists; a reversal that, once it has left the provider, cannot have its local update retried and so logs a manual-reconciliation line; and a settlement task handler changed to answer as soon as the settlement commits, because an error or a timeout after it would make the task queue retry and apply the amount a second time, so a slow receipt could corrupt a balance.

A client application that imports user packages met the local form, with no network in sight: an import is written beside the store, verified, validated and decoded, and only then renamed into place, so a failure leaves nothing behind (a probe refused a damaged file and kept nothing). Switching which object the user steers now checks that the new one can be taken **before** releasing the current one — a failed pick used to leave nothing steered and the controls dead. A build step that rewrites a tracked configuration file has it restored by the supervising build tool in a `finally`, tested by running it against a target that does not exist. That the restore sits in the tool and not in the step is `cleanup-belongs-to-the-supervisor`: a child's own `finally` is not enough, and the tool is the supervisor of the engine it launches.

## Literature

- **Garcia-Molina & Salem, 1987, ["Sagas"](https://doi.org/10.1145/38713.38742)** (SIGMOD; *verified 2026-09-23 against the paper*): split a long transaction into steps, each with a compensating action. **What we take:** the non-atomic sequence as a designed object. **Where we differ:** before reaching for compensations, choose the order whose residue needs none.
- **Mohan et al., 1992, ["ARIES"](https://doi.org/10.1145/128765.128770)** (ACM TODS; *verified 2026-09-23 against the paper*): write-ahead logging — the log before the data, so a crash leaves a recoverable state. **What we take:** the ordering principle itself.
- The acknowledgement half extends `retry-over-irreversible-effect` in this base.

## Evidence

**Reasoned, from four occurrences in one repository and three in another**; the local ones were exercised once each (a refused import, a failed pick, a failed build), not fault-injected. What would measure it: fault-inject a crash between each pair of writes in a test environment and classify the resulting state as recoverable or not.
