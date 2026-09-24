---
bundle: agent-guides
lineage: g-8b5800/main
version: 8
slug: one-write-gate-makes-read-only-real
topic: failure-behaviour
claim: Route every write to an external store through one gate, let a process-wide switch override any caller's parameter, and fail an audit on any write call outside the gate — then read-only is a guarantee, not a convention.
confidence: measured
reach: architecture, review
---

# One write gate makes read-only real

## Why it works

"This run does not write" is the property that makes it safe to point tooling, tests and exploratory work at a database you do not own. As a convention it holds until the first helper that opens its own batch. As a guarantee it needs three parts, and each covers the others' hole:

1. **One gate.** All writes go through a small number of methods on one client. A read-only mode can only refuse the writes it knows about, so it must know about all of them.
2. **The environment beats the parameter.** A read-only flag passed by the caller is a default; any caller can pass the other value. A process-wide switch, read before any client is built and not overridable by arguments, is a guarantee. The gate refuses **before anything is sent**, not after.
3. **An audit for writes outside the gate.** Without it, the guarantee silently shrinks the day someone calls the SDK's write primitive directly. With it, that change fails the build and names the line.

## When it does NOT apply

- **SDKs that write on their own** — caches, telemetry, auto-migrations, lazy index creation. The gate never sees those writes.
- **Writes from a second process or language** the audit cannot parse.
- **Aliasing defeats a textual audit.** The audit is a pattern over the SDK's write-call names; a write through a renamed reference passes it. It raises the cost of a mistake, it does not stop an adversary.

## What it costs

All writes funnel through one module, which some code will find inconvenient. One audit rule to maintain as the SDK evolves. The switch must be set before any client is constructed, which constrains test setup order.

## Where it came from

An analytics repository reading a production database owned by another service. With a single write gate, a process-wide read-only switch and an audit over the SDK's write calls, a live test suite and a full census — **over a hundred thousand documents read, no write possible** — could be run against the real database. Two design judgements decided it: a mode a caller can switch off is a default and not a guarantee, and without the audit, read-only stops only the writes it already knows about.

## Literature

- **[Saltzer & Schroeder, 1975, "The Protection of Information in Computer Systems"](https://doi.org/10.1109/PROC.1975.9939)** ([accessible copy](https://www.cs.virginia.edu/~evans/cs551/saltzer/)), principle of **complete mediation**: "Every access to every object must be checked for authority." *Verified 2026-09-23 against the accessible copy.* **What we take:** the gate must see every write.
- **Anderson, 1972, ["Computer Security Technology Planning Study"](https://csrc.nist.gov/publications/history/ande72a.pdf)** (ESD-TR-73-51, vol. I, §3.2.2; *verified 2026-09-23 against the report*): the reference monitor — always invoked, tamper-proof, small enough to verify. **What we take:** the three properties map onto gate, environment override and audit. **Where we go further:** applied to a client library rather than an operating system, and with read-only operation as the payoff.
- **Sibling in this base:** `kill-switch-reaches-every-path` — the same mediation for a feature, flipped at runtime and stopping reads as well; this gate is fixed before any client exists and lets reads through.

## Evidence

The census ran with zero writes, but nothing attempted a write through an unmediated path, so at first writing the guarantee had not been tested against the case it exists for.

**Measured, 2026-09-22.** On a clean export of `HEAD`, a module was planted beside the gate with (a) a direct batch and commit on the store client, and (b) the same write reached through attribute lookup on names built from strings. The audit **went red on (a) and named both lines**, and **stayed green on (b)**. That is the claim confirmed and its stated boundary confirmed with it, by measurement rather than by argument. **And the two defences have the same blind spot, which is worth being exact about:** a write reached through an alias is invisible to the audit *and* outside the gate, so the process-wide switch never sees it either. Neither half stops someone determined; what each buys is different. The audit makes an ordinary mistake — a write written the way writes are written — visible before it ships. The switch makes every write that does go through the gate refuse, which is what lets the suite run against the real store. The guarantee is over honest code, and the boundary above is not a detail of it.

What *would* settle the part still open: run a suite against the real store in read-only mode with the store's own access log enabled, and count the writes that reached it anyway — each one is a write the gate never saw, from an SDK or a second process (the first two boundaries above).
