---
bundle: agent-guides
lineage: g-8b5800/main
version: 8
slug: fail-closed-defaults
topic: failure-behaviour
claim: A fallback value should be the one that refuses to run, not the one that matches production.
confidence: reasoned
reach: architecture, review, debugging
---

# Fail-closed defaults

## Why it works

A default exists for the case where configuration is absent. That case is almost never "everything is fine and the value happens to be missing" — it is a misconfigured deploy, a missing secret, a container started by hand. If the default matches production, every one of those starts successfully and runs **as if configured**, and the failure surfaces later, somewhere unrelated, as data.

If the default is deliberately *below* the threshold the code checks, the same misconfigured start aborts at boot with a message naming the variable. The bug becomes a refusal instead of a silent divergence, and a refusal is diagnosable by whoever is standing there.

This is the same move as a database `NOT NULL` with no default: make the absence impossible to ignore rather than convenient to overlook.

## When it does NOT apply

- **When the process cannot afford to refuse.** A watchdog, an init container, anything whose job is to come up so that something else can be fixed. There, a permissive default is correct and the check belongs somewhere that can fail loudly without taking the system down.
- **When absence is genuinely the common case**, e.g. an optional feature flag that is off for most deployments. Refusing to start because an optional thing is unset is a fail-closed default applied where it does not belong.
- **When the refusal lands on someone who cannot act on it.** Failing closed in front of an end user who cannot set the variable converts a config bug into an outage. Fail closed at boot, not in the request path.
- **When failing at boot removes the runtime lever.** A kill switch needs a running process; if turning a feature off requires configuration that stops the process, the switch is a deploy. See `kill-switch-reaches-every-path`.
- **When the absence is of *evidence*, in an adversarial request path.** Refusing a request because a signal is missing filters honesty, not abuse: the abuser supplies the signal, the honest user does not know it is expected — which signals may refuse at all is `refuse-only-on-unassertable-evidence`. A transactional service removed a verification gate that refused an estimated **few hundred legitimate requests a month** and that an abuser satisfied by switching browsers — Saltzer & Schroeder's own eighth principle, *psychological acceptability*, is the counterweight here.
- **When the runtime cannot be observed by whoever must fix it.** Software that runs only where its developers cannot look — a device they do not hold, a user's machine — gets no report from a refusal: the thing simply disappears, and a silent absence looks the same as a working feature with nothing to show. There, split the default in two. **At runtime, fail visible**: an honest substitute that names what is missing (a placeholder saying which part could not be built, a readout drawn crossed out rather than empty, a missing text key shown as the key). **In the gate, fail closed** on the same condition, so the substitute is for seeing the problem and never for shipping it; the permission to substitute is passed explicitly by the one caller that runs in the field, and the checker never receives it. A client application whose only target its developers cannot observe does this: a malformed entry stays listed under a substitute name, a composition that cannot be assembled becomes a note saying what is missing while the rest still plays, and its validator fails on the same warnings. The capability form of this boundary is `detect-by-observation-not-build-flag`: a guess about a device nobody can observe may only withhold a feature, never enable one. Where a wrong answer is worse than none — money, access — the original rule stands.
- **When either default is wrong for half the callers.** Then there should be no default: a required parameter makes each caller decide.

## What it costs

A local environment that "just works" stops just working: every new developer hits the refusal once and has to be told which variable to set. That is a real onboarding cost and the reason this gets argued away. Pay it once with a clear error message naming the variable.

## Where it came from

Reading a service where the base-image fallback was two minor versions below what the startup check required. It read as version drift and was nearly "fixed" to match — which would have turned the startup contract off silently, since an unset variable would then have passed the check. The low value was the whole mechanism.

Judgement, unmeasured: no incident was observed, only the code path.

## Literature

- **[The Protection of Information in Computer Systems](https://www.cs.virginia.edu/~evans/cs551/saltzer/)** — Saltzer & Schroeder, 1975, *Proceedings of the IEEE* 63(9) ([DOI 10.1109/PROC.1975.9939](https://doi.org/10.1109/PROC.1975.9939)). The second of its eight design principles is **fail-safe defaults**: *"base access decisions on permission rather than exclusion"* — the default is lack of access, and the protection scheme identifies conditions under which access is permitted rather than conditions under which it is denied.

  **What we take from it:** the asymmetry of a design mistake. Under a fail-safe default a mistake shows up as a refused operation, which is noticed and fixed; under a permissive one it shows up as unauthorised access, which may go unnoticed indefinitely. That asymmetry is the whole argument and it is fifty years old.

  **Where we go further:** Saltzer and Schroeder frame it as a security principle about access. This note applies the same asymmetry to **configuration**, where the "access" being granted is permission to run at all. The mechanism is identical; the domain is wider than the original.

## Evidence

**None measured for the claim.** No incident was observed — the pattern was recognised by reading a startup check whose fallback sat deliberately below the version it required, and nearly "fixing" it. The two boundaries above with a number or an occurrence are measured separately from the claim.

**2026-09-22 — the queued experiment, asked where it cannot be answered.** In a transactional backend, a probe imported one utility module with no environment set: the import refused twice in a row, each time naming the missing variable, before any code ran. About four in five of its configuration reads, across dozens of modules, have no default and raise at import; the rest have one. That confirms the mechanism half, and shows the experiment below cannot be run in a fleet without defaults: "how often does a service start with a required variable unset" is *never* by construction, which measures the principle being applied, not its benefit being theoretical. The reads that do have a default are unpriced.

What *would* settle it: instrument how often a service starts with a required variable unset, in any fleet. If the answer is never, the cost of this principle is real and its benefit is theoretical, and the honest move is to write that number here and downgrade the note.
