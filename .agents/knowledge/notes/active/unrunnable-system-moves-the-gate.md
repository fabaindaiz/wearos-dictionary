---
bundle: agent-guides
lineage: g-8b5800/main
version: 3
slug: unrunnable-system-moves-the-gate
topic: verification
claim: When a system cannot be booted in the environment it is written in — it needs hardware, credentials or a network that only production has — the questions "does it run?" and "do the tests pass?" stop being available, and both the gate and the dominant bug class move.
confidence: reasoned
reach: planning, verification, debugging
---

# An unrunnable system moves the gate

## Why it works

Most verification advice assumes the loop *change → run → observe*. Remove the middle step and two things shift at once, and only the first is usually noticed.

**The gate moves to what can be evaluated without running.** Type checking stops being a tidiness tool and becomes the main evidence that a change is sound, because it is the only check that examines every path without executing one. A partial test suite is then genuinely advisory — not as an excuse, but as a description: it cannot be the gate, because the parts it cannot reach are the parts the machine actually runs.

**The bug class moves to composition.** Logic errors inside a function are what tests catch and what type checking largely catches. What neither catches is *assembly*: a dependency-injection container that fails to resolve, a plugin that registers under a name nothing requests, a config model that validates but describes a device that is not there. These surface at startup — the one moment that cannot be rehearsed. So the reasoning has to be explicit where it would otherwise be empirical: trace the wiring by reading it, because nothing else will.

The mistake this note exists to prevent is treating an unrunnable system as a normal one with worse tooling, and concluding that the answer is more tests. More tests of the parts that already run does not touch the class that bites.

## When it does NOT apply

- **When the system can be run and the obstacle is effort** — a docker-compose nobody wrote, a fixture nobody made. Then the correct move is to make it runnable, and this note is an excuse. The distinction is whether the obstacle is *physical* (hardware, a credential that must not exist in development) or merely unbuilt.
- **When the environment can be simulated.** Hardware-in-the-loop practice exists to remove exactly this obstacle: the real controller runs against a simulated plant. The arrangement this note describes holds until such a rig exists, and a rig for composition alone is often cheap: resolve the whole dependency graph at startup with stand-ins for the hardware and credentials, and fail the gate if resolution fails — which turns the class this note says bites at startup into a check that runs before it.

## What it costs

Reasoning about composition by reading is slower and less certain than running the thing, and it does not degrade gracefully: a wiring graph too large to hold in the head gets skimmed rather than half-traced.

## Where it came from

A hardware-bound service states it directly in its own instructions: the type checker is the primary correctness gate because the suite is partial and the application needs real hardware and credentials to boot, and changes to its plugins most often break when the dependency container resolves them at startup, so the wiring is to be reasoned about by reading. A second repository in the same family reaches the same arrangement — typecheck plus lint as the real gate, tests explicitly non-blocking — from the same cause.

## Literature

- **NI, ["What Is Hardware-in-the-Loop (HIL)?"](https://www.ni.com/en/solutions/transportation/hardware-in-the-loop/what-is-hardware-in-the-loop-.html).** "HIL is an embedded software test technique during which real signals from a controller are connected to a test system (plant). HIL simulates reality by using software models and simulation." *Verified 2026-09-24 against the page.* **What we take:** the boundary — an unrunnable system becomes runnable once its environment is simulated. **Where we go further:** what the gate and the bug class look like *before* that rig exists, which is where most small hardware-bound services live.
- **Xu et al., 2016, ["Early Detection of Configuration Errors to Reduce Failure Damage"](https://www.usenix.org/conference/osdi16/technical-sessions/presentation/xu)** (OSDI). Many configuration settings are not checked at initialization, so their errors stay latent until the path that uses them runs; emulating that later execution at initialization catches most of them (75+ % in their evaluation). *Verified 2026-09-24 against the USENIX abstract.* **What we take:** errors in assembly and configuration are a class of their own that surfaces late, and running the assembly early is the remedy. **Where we differ:** their subject is configuration values in large systems; ours is the wiring of components, which fails the same way.

## Evidence

**Reasoned: two repositories arriving at the same arrangement.** What would measure it: classify a period of defects in such a repository as logic, contract or composition, and compare the distribution against a service that can be run locally. The prediction is a composition share visibly higher. A cheaper experiment first: build a composition-only boot with stand-ins, and count how many past startup failures it would have caught.
