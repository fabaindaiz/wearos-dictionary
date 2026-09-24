---
bundle: agent-guides
lineage: g-8b5800/main
version: 6
slug: retry-over-irreversible-effect
topic: distributed-correctness
claim: Any transport that retries will eventually re-execute an irreversible effect; idempotency is a property you build, not one you configure.
confidence: reasoned
reach: architecture, planning, debugging
---

# Retry over an irreversible effect

## Why it works

Retries exist because delivery is unreliable, and they cannot distinguish "the request never arrived" from "the request arrived, ran, and the response was lost". Those two look identical to the caller and require opposite actions. A backoff decorator resolves the ambiguity the same way every time: it sends it again.

When the effect is a write that can be overwritten, that is harmless. When the effect is physical, financial, or externally visible — a door opening, a charge, an email, a shipment — the second execution is the bug, and it happens under exactly the conditions that make it hardest to see: a bad network, a slow downstream, a deploy.

**At-least-once delivery plus an idempotent consumer** is the settled answer. Note the shape: the delivery guarantee is not strengthened, the *consumer* is changed. Attempts to buy exactly-once from the transport instead produce a system with several overlapping safeguards that sometimes cancel each other out.

The consumer needs a **stable identity for the logical operation**, chosen by whoever can generate the same one on a retry — usually the caller — and persisted with the effect. Not a timestamp, not a random id generated inside the handler: both differ on the retry, which is the one moment they need to match.

## When it does NOT apply

- **Naturally idempotent effects.** Setting a field to a value, `PUT` of a full resource, publishing a retained value. Re-execution converges; no key is needed.
- **When the state machine already carries the identity.** If the operation only ever applies to a resource in one state and moves it out of that state atomically, the state *is* the key. Adding a second mechanism is duplication — but check the atomicity claim, because check-then-act is where this quietly fails.
- **When the effect is cheap to duplicate and expensive to deduplicate.** A metrics increment does not deserve a key.

Two sharper boundaries, from a second repository:

- **Catch only the error that proves the effect did not happen.** A refusal the provider answered is safe to treat as "not applied"; a transport error is not — its outcome is unknown, and handling both the same way converts an unknown into a false negative.
- **Deterministic task ids deduplicate the wrong fork.** A queue that refuses a retry because its id already exists fails towards a retry nobody notices was refused. Bound retries by a budget re-read before every attempt, with one owner, rather than by id collisions.

## What it costs

A key on the contract, a write before the effect, storage that must outlive the retry window, and a decision about what a *conflicting* replay means — same key, different payload is a bug in the caller and should be rejected, not merged. It also pushes complexity onto the caller, who has to generate and remember the key, which is why it belongs in the contract rather than in one service's implementation.

## Where it came from

A cloud service and an edge service, each holding half of a guarantee: the cloud deduplicated by transaction status, the edge by an in-process lock. Each half was correct; **they did not compose into a distributed guarantee**, and nothing in either repository said so. The retry decorator sat on the call between them, with a window of tens of seconds.

Judgement, unmeasured. The literature agrees on the shape (idempotency is semantic, exactly-once is a delivery claim, prefer at-least-once with an idempotent consumer); the specific gap was found by reading both sides at once, which is only possible from a workspace.

## Literature

- **[You Cannot Have Exactly-Once Delivery](https://bravenewgeek.com/you-cannot-have-exactly-once-delivery/)** — Tyler Treat. Grounds the impossibility in the **Two Generals Problem** and **FLP**: a sender and receiver cannot both become certain a message arrived exactly once, so a transport may offer at-most-once or at-least-once and nothing else. *"The way we achieve exactly-once delivery in practice is by faking it"* — the messages are made idempotent, or duplicates are removed at the application layer.

  **What we take from it:** the vocabulary that ends the argument. **Delivery is a transport-layer semantic and is impossible; processing is an application-layer semantic and is achievable.** Anyone proposing to buy exactly-once from the broker is asking the wrong layer, and the sentence above is the cheapest way to say so.

- **[Designing robust and predictable APIs with idempotency](https://stripe.com/blog/idempotency)** — Stripe. The client generates a key per *logical operation* and resends it on every retry; the server stores the first outcome for that key and replays it, **including failures**, so a retry after a 500 returns the same 500 rather than charging twice.

  **What we take from it:** the key is the client's, not the handler's — which is why a timestamp or a server-side random id does not work. And storing the *result* rather than just a "seen" marker is what makes the replay honest.

- **[Implementing Stripe-like Idempotency Keys in Postgres](https://brandur.org/idempotency-keys)** — Brandur Leach. Supplies the boundary we did not have: **a key that is in flight is neither absent nor complete.** Treating in-flight as absent turns a fast retry into the duplicate the whole mechanism exists to prevent; Stripe's own API answers a concurrent reuse with `409 Conflict`. Same key with a *different* payload is a client bug and is rejected, not merged.

  **What we take from it:** the third state. An idempotency implementation with two states is incomplete and fails precisely under the impatient-client retry it was built for.

## Evidence

**Before 2026-09-22 — none measured.** The gap was found by reading both halves of a cloud/edge pair at once and noticing that each deduplicated by a different mechanism and neither composed with the other. No duplicate physical effect was observed or reproduced.

The literature above is well-established rather than novel, which raises this note's confidence in the *claim* but not in **our** application of it: we have not demonstrated that our retry window can actually produce a duplicate, only that nothing prevents it.

**2026-09-22 — occurrences in a second repository, a transactional service.** Duplicates did happen there: operations applied several times, which produced an administrative per-operation reversal endpoint; duplicate stored credentials that made a retry loop apply the same effect repeatedly; a task-queue replay that double-counted an authorised response, with settlement still non-idempotent on replay and left open deliberately; and a replayed signup, written without merge, that was one repeated call away from erasing a restriction placed on the account. These are occurrences, not a measured rate, so the note stays `reasoned` — but the claim is no longer only an argument.

What *would* settle it: inject a lost response on the call between the two services and observe what the downstream does. Until that exists, this is a `reasoned` note resting on `measured` literature — which is a different and weaker thing than a measured note.
