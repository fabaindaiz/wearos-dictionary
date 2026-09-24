---
bundle: agent-guides
lineage: g-8b5800/main
version: 7
slug: refuse-only-on-unassertable-evidence
topic: adversarial-controls
claim: An automated refusal must rest on an identifier that cannot collide and that a third party cannot assert about the subject; weaker signals raise friction, they do not refuse.
confidence: reasoned
reach: architecture, review
---

# Refuse only on evidence nobody else can assert

## Why it works

A control that refuses service has two failure modes and they are not symmetric. A missed abuser costs one incident. A refused legitimate subject costs a customer, and — when the evidence can be asserted by someone else — hands every attacker a **lockout vector**: enter the victim's personal identifier in your own enrolment, present the victim's device id, register the victim's contact number, and the control refuses the victim.

So the question for a refusing signal is not "is it strong evidence of abuse?" but two narrower ones:

1. **Can it collide?** A fingerprint, a hash of device attributes, a name: many honest subjects share it. A refusal on it refuses the crowd.
2. **Can a third party assert it about the subject?** Any value the client types or sends unverified can be sent by someone else.

Adding a second signal of the same kind does not help: two forgeable signals make the gate harder to fire, not harder to fool. What helps is an identifier minted and signed by the server, which neither collides nor can be claimed by another party — and without the signing secret configured, the unsafe pair (enforcement on, verification off) is refused at startup rather than run.

Signals that fail either test still have a use: they **price** — raise a hold, feed a score, request a step-up — and never refuse on their own.

## When it does NOT apply

- **Friction, not refusal**: a higher hold, a score feature, a verification step.
- **When the subject can self-clear instantly**, so a false refusal costs seconds.
- **Accepted, documented risks** where forging requires access to the victim's own device storage.

## What it costs

Giving up blocking on cheap signals, which will be argued for on every incident. A server-minted identifier and a way to re-serve it to the legitimate client.

## Where it came from

A transactional service's abuse controls. Letting a typed personal identifier refuse service was rejected as a lockout vector rather than a looser threshold, because anyone can enter someone else's identifier in their own enrolment. An empty signing secret together with enforcement was identified as a lockout vector and now logs an error at import. A requirement for a corroborating signal was dropped because the second signal was equally forgeable and measured an unrelated fact — what the gate needed was a signal that cannot collide, not a second one that is hard to forge. A colliding fingerprint hash may be recorded but never refuse, excluded structurally in two places.

## Literature

- **Gómez-Boix, Laperdrix & Baudry, 2018, ["Hiding in the Crowd: an Analysis of the Effectiveness of Browser Fingerprinting at Large Scale"](https://doi.org/10.1145/3178876.3186097)** (WWW). On a real population of 2,067,942 fingerprints, only **33.6 %** were unique. **What we take:** a client-side fingerprint labels a cohort, not a device, so it cannot carry a refusal.
- **[Saltzer & Schroeder, 1975](https://doi.org/10.1109/PROC.1975.9939)** ([accessible copy](https://www.cs.virginia.edu/~evans/cs551/saltzer/)), principle 8, **psychological acceptability** ("users routinely and automatically apply the protection mechanisms correctly"; *verified 2026-09-23 against the accessible copy*): a control that refuses honest users is bypassed or removed. **What we take:** false refusals are a security cost, not only a product cost.

## Evidence

**Reasoned; the only number is the external 33.6 %.** No lockout attack was observed here. What would measure it: for each refusing signal, count how many distinct subjects share each value in production, and whether any value is settable by a client.
