---
bundle: agent-guides
lineage: g-8b5800/main
version: 6
slug: best-effort-side-channels
topic: failure-behaviour
claim: A channel that reports on an operation must never be able to fail it.
confidence: reasoned
reach: architecture, review, debugging
---

# Best-effort side channels

## Why it works

Notifications, webhooks, analytics events, audit pings, cache invalidations — these report that something happened. They are downstream of the thing, not part of it. But they are usually *called* from inside the same function, and an exception there propagates by default.

So an outbound call that nobody considers critical acquires the power to fail the operation it was only supposed to describe. The result is backwards in a way that is obvious once seen and invisible while writing: **the record of the work destroys the work.** Worse, the effect has often already happened — the money moved, the door opened, the email sent — so the failure rolls back a database row while leaving the real-world effect in place, producing exactly the inconsistency the transaction was supposed to prevent.

The fix is not a `try/except` sprinkled at the call site. It is deciding, per outbound call, **which side of the boundary it is on**, and making the answer visible: critical calls propagate, best-effort calls are wrapped once, logged with enough context to replay, and never re-raised.

## When it does NOT apply

- **When the side channel is the product.** If the customer's contract is "you will receive a webhook", it is not a side channel, it is the deliverable — and dropping it silently is the bug. The answer there is durable delivery with retries, not swallowing.
- **When the channel is the only record.** An audit log that is legally required is not best-effort; if it cannot be written, refusing the operation is correct.
- **When swallowing hides a systemic failure.** A wrapper that logs at `debug` and moves on turns a broken integration into silence for months. Best-effort means *does not fail the caller*, never *nobody finds out* — it needs a metric or an alert on the failure rate, or the note has been used to justify neglect.

The missing direction: **the record must also survive the operation failing.** Deferred side work attached to a request is often dropped when the request raises — and the request that raises is the one whose record matters most. The mirror trap is side work scheduled before the operation's verdict, which records an outcome that never happened. The minimum signal for a swallowed failure is a distinct, searchable log line, not a generic warning.

## What it costs

A decision per outbound call instead of a default, and the discipline to keep it visible in review. Plus the monitoring the third boundary above demands, which is the part usually skipped — and skipping it converts a stability pattern into a way of not knowing.

## Where it came from

A service where webhook delivery was wrapped and logged rather than raised, recorded explicitly as a guardrail: a failed notification never fails the operation it reports on. The history behind it showed the same call moving from raising, to logging an error, to logging at info over several commits — the shape of a team learning this the slow way.

Judgement, unmeasured.

## Literature

- **[Release It! Design and Deploy Production-Ready Software](https://www.oreilly.com/library/view/release-it-2nd/9781680504552/)** — Michael Nygard, 2007 and 2018. Names **integration points** as *"the number-one killer of systems"* — every socket, process, RPC and REST call can hang — and **cascading failure** as the mechanism by which one component's problem becomes everyone's: the failure *"jumps the gap"* when bad behaviour in the calling layer is triggered by a failure in the called one. His **bulkhead** pattern partitions resources so a breach in one compartment cannot sink the ship.

  **What we take from it:** the framing that an unguarded outbound call is not a detail but the most common cause of systemic failure, and that the remedy is a boundary decided in advance rather than an exception handler added after the first outage.

  **Where we go further:** Nygard's bulkheads are mostly about *resources* — threads, connections, pools. This note is about **control flow**: the caller's success being coupled to the callee's, which is the cheapest cascading failure to prevent and the easiest to introduce, because propagating is what the language does if you write nothing.

## Evidence

**Before 2026-09-22 — none measured.** No outage was traced to a side channel here; the guardrail predates this note and the commit history suggests it was learned from experience nobody wrote down at the time.

**2026-09-22 — occurrences in a transactional service.** Background tasks recording a link were dropped when the request raised; a capture scheduled before a handler refused recorded an account that never existed; and swallowing inside the code under test hid a broken dependency wiring from the suite. Occurrences, not a ratio: the note stays `reasoned`.

What *would* settle it: count outbound calls in a service, classify each as critical or best-effort, and measure how many currently propagate by default. That ratio is the note's real evidence and it has not been taken.
