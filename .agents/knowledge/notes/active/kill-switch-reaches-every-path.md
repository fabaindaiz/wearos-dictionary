---
bundle: agent-guides
lineage: g-8b5800/main
version: 7
slug: kill-switch-reaches-every-path
topic: failure-behaviour
claim: A kill switch is checked first and unconditionally, by one predicate, on every path the feature has — reads included — and never sits behind configuration that fails at boot.
confidence: reasoned
reach: architecture, review
---

# A kill switch reaches every path

## Why it works

A kill switch exists for the worst moment: the feature is doing damage and a deploy is too slow. Its whole value is that flipping it stops the damage, so every way it can fail to stop it is a failure of the switch, not of the feature:

- **A path it does not reach.** Features grow second paths — a background writer, a reader in another handler, an issuance step added later. A switch wired into the path that existed when it was written leaves the others running.
- **Reads count.** When a feature's data is append-only, stopping the writes is not enough: what already landed keeps being read and acted upon. An operator cannot un-write it; they can only stop reading it.
- **Evaluated after another rule.** A switch consulted after a version check, a mode flag or a "force" override is reachable only when those let it through. "First and unconditionally" is the whole rule.
- **Behind boot-time configuration.** Failing closed at boot is right for required configuration, and it removes the runtime lever: a switch needs a running process. If turning the feature off requires configuration that stops the process, the switch is a deploy.

One predicate that every path must call — or a structural wrapper — makes the reach auditable, and an audit that fails on any path of the feature not calling it makes the reach checked rather than enumerated by hand.

## When it does NOT apply

- A flag that only gates a UI affordance, with one path and one reader.
- **A safety control whose off state is itself the danger.** That one should refuse to boot when misconfigured, and its emergency lever belongs elsewhere.

## What it costs

A call-site discipline or a wrapper on every path. A switch that also stops reads loses the feature's observability while it is off, which is the moment people most want to look.

## Where it came from

A transactional service with an abuse-control feature behind switches. Four separate defects, all found in review: a feature switch stopped writes but not reads, so records already linked kept being acted on with the switch off; a consent switch was consulted after a version rule that every client satisfied, so it reached neither gate it existed for — and the test for that fix surfaced a second instance, a force flag that waived the switch too; credential issuance sat outside every switch, so the only lever was a deploy; and the configuration and documentation had, in several places, described the bug as the design.

## Literature

- **Hodgson, 2017, ["Feature Toggles (aka Feature Flags)"](https://martinfowler.com/articles/feature-toggles.html)** (martinfowler.com). Names ops toggles and kill switches and their lifecycle: "a small number of long-lived 'Kill Switches' which allow operators of production environments to gracefully degrade non-vital system functionality". *Verified 2026-09-23 against the article.* **What we take:** the category. **Where we go further:** reads, evaluation order and boot-time configuration as the three ways a kill switch silently fails to kill.
- **Sibling in this base:** `one-write-gate-makes-read-only-real` — the same complete mediation for a class of operation (writes to one store), fixed per process and letting reads through; this note gates a feature on every path, reads included, flipped at runtime. Its audit — fail on any path outside the gate — is what makes "reaches every path" checkable here too.

## Evidence

**Reasoned, with four occurrences and no number.** What would measure it: for each switch, enumerate the feature's paths (writers, readers, schedulers) and test that flipping the switch stops each one; count the paths not covered.
