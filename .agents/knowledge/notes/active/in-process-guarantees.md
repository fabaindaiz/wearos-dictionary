---
bundle: agent-guides
lineage: g-8b5800/main
version: 6
slug: in-process-guarantees
topic: distributed-correctness
claim: A guarantee enforced by an in-process primitive holds for one process and silently holds for none when a second appears.
confidence: reasoned
reach: architecture, planning, review
---

# In-process guarantees

## Why it works

A mutex, a semaphore, a module-level cache, a singleton — these coordinate the threads or tasks *inside one process address space*. Correctness arguments built on them are correct, and they are correct **only while the premise holds**.

What makes this expensive is the failure mode: the premise is broken by an operational change, not a code change. Someone raises the worker count, adds a replica, enables autoscaling, or runs a second instance for a migration. **No test fails, no line is edited, no review happens.** The guarantee is gone and the code that depended on it looks untouched and correct — because it is untouched, and it is no longer correct.

## The version that survives losing the premise

When the guarantee has to hold across processes, the shape that works is **ownership carried in the data, not held in memory**: the shared record names its current owner, and a writer checks it owns the record in the same operation that writes. A stale holder's write is rejected on arrival rather than prevented at the door — which is the only order that works, since the door is exactly what a paused process walks back through.

This is Kleppmann's fencing token generalised: the token can be a monotonic number, a lease id, a version column, or the id of the operation that currently owns the resource. What matters is that **the resource itself does the checking**, because it is the one thing both contenders agree on.

Note what this costs and what it does not: it needs a field and a conditional write, not a coordination service. A great deal of what is reached for as "we need distributed locking" is answered by a compare-and-set on a column that was already there.

## When it does NOT apply

- **When the process really is the boundary**, and that is enforced somewhere real: a singleton deployment with `replicas: 1` in the manifest, a device driver, a desktop app. The premise is fine; **write it down next to the primitive** so the next person changing the manifest sees what they are changing.
- **When the in-process primitive is an optimisation over a durable guarantee**, not the guarantee itself. A lock that reduces contention in front of a unique constraint is doing a different job and is not affected by this.
- **When losing the guarantee is cheap.** Deduplicating log lines does not need distributed coordination. Reserve the cost for effects that cannot be undone.
- **When the in-process state is a cache of negative, self-expiring results.** A replica that caches "not found / not allowed" for a short time is safe across replicas as long as a *refusal* is never cached as a permission. The opposite — a per-process cache of positive configuration — diverges between replicas and is affected by this note.

## What it costs

Moving a guarantee out of process means persisting something — a key, a row, a lease — and paying a round trip on the hot path, plus the failure modes of the store you moved it into. That is a real cost and it is why in-process is the right first answer for a single-instance service. The note is not "never do this"; it is **"know which premise you are standing on, and say so."**

## Where it came from

A transaction service guarded a one-active-operation rule with an in-process async lock around a check-then-insert. Narrow, deliberate, correct — and correct for one worker. The effect it protected was irreversible and externally visible. The lock's scope was documented; the premise it rested on was not, until it was written down explicitly as the thing a second replica would remove.

Judgement, unmeasured.

## Literature

- **[How to do distributed locking](https://martin.kleppmann.com/2016/02/08/how-to-do-distributed-locking.html)** — Martin Kleppmann, 2016. Written against Redlock, but its durable contribution is a distinction that reframes this note: **efficiency locks vs correctness locks.** An efficiency lock avoids duplicate work and a failure costs you a wasted computation. A correctness lock prevents an invalid state, and a failure costs you corrupted data or a duplicated real-world effect. *"If you are using locks merely to save yourself from rarely doing duplicated work, it's fine to take the risk. If the lock is required for correctness, do not use Redlock."*

  He also shows why timeouts are not a fix: **a process can pause** — garbage collection, a descheduled thread, a stalled network — for longer than any lease, and resume believing it still holds the lock. The remedy he proposes is a **fencing token**: a monotonically increasing number issued with the lock, checked by the resource, so a stale holder's write is rejected on arrival.

  **What we take from it:** the question to ask about any lock is not "is it correct" but **"which kind is it"**. An in-process lock guarding an irreversible effect is a correctness lock resting on a premise about deployment, and the premise is not written down anywhere the person raising the replica count will see it.

  **Where we differ, on purpose:** Kleppmann's context is a lock already distributed and arguing about the algorithm. This note is one step earlier — a lock that is not distributed at all, and is correct until an operational change silently makes it a correctness lock across processes.

## Evidence

**None measured.** No double execution was observed; the premise was found by reading the critical section and asking what a second worker would do. The service runs single-worker today, so the failure this predicts has never had the opportunity to occur.

What *would* settle it: run two workers against the same resource in a staging environment and fire concurrent requests. That test does not exist, and until it does this note is an argument, not a finding.
