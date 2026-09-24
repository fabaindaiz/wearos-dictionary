---
bundle: agent-guides
lineage: g-8b5800/main
version: 8
slug: store-choices-not-defaults
topic: evolving-contracts
claim: Persist what the user chose, and that they chose it, and keep defaults in code — a stored default cannot be told from a choice of the same value, so changing the default later either overrides users or strands them.
confidence: reasoned
reach: architecture, implementation
retired_because: Merged 2026-09-24 into persist-inputs-derive-verdicts, of which it was the application to preferences (a stored default is a frozen verdict); its occurrence and citation moved there.
superseded_by: persist-inputs-derive-verdicts
---

# Store choices, not defaults

## Why it works

The convenient way to save preferences writes every value, defaults included. From then on, the file says "X" for two different people: the one who picked X and the one who never opened the setting. When the product later wants a better default, it has two bad options — overwrite X for everyone, taking the choice away from the first person, or leave it, keeping the second person on the old default forever. A stored default is a verdict frozen in storage — the product's answer at the time of writing, kept as if it were the user's input — which is `persist-inputs-derive-verdicts` applied to preferences.

Keeping defaults out of storage (a fallback layer consulted when a key is absent), or storing a flag beside the value that says it was chosen, keeps the two people apart. A new default then reaches exactly those who never chose, and a suggestion the product makes can change a value without counting as the user's decision.

The same separation argues for two neighbours: repair stored data per key, so one malformed value loses that key and not the whole file; and never store live state that the platform owns (whether a window is fullscreen) — read it each time, so a request the platform refused does not leave a stored value lying.

## When it does NOT apply

- **Settings the user expects frozen at what they saw** — a price, a legal consent version. Snapshot those deliberately.
- **Values with no meaningful default** — they are always a choice.
- **Stores that already layer defaults under choices** by design; then the rule is simply not to copy the default layer into the persisted one.

## What it costs

A flag per preference, or a layered store; a migration that must infer intent for files written before the flag existed, and whose inference can be wrong.

## Where it came from

One repository, a client application whose settings live on the device. It wanted some content to open in a different display mode by default, and found no way to tell a stored default from a stored choice of the same value — and a preference silently overridden is worse than one that was never offered. It added a chosen-flag, and a suggestion changes the mode without setting it. The migration had to infer what was never saved: one value had always been the only default, so a file holding anything else had been set by hand — whoever changed it keeps their choice, whoever never did gets the new behaviour. The same store repairs per key (a wrong-typed value loses that key and keeps the rest) and keeps live window state out of its defaults list, reading it every time.

## Literature

- **Apple, ["About the User Defaults System"](https://developer.apple.com/library/archive/documentation/Cocoa/Conceptual/UserDefaults/AboutPreferenceDomains/AboutPreferenceDomains.html).** Preferences are looked up through a list of domains; the registration domain, which holds the defaults an application registers at launch, is **volatile** and consulted last, below the persistent application domain. *Verified 2026-09-22 against the archived Apple developer page.* **What we take:** defaults as a non-persisted fallback layer — the structural form of this note. **What we add:** the chosen-flag for stores that cannot layer, and the migration that infers it.

## Evidence

**Reasoned, from one design occurrence** with its migration; no incident was counted. The experiment: in any product that stores all values, count users whose stored value equals the shipped default, then change that default in a test build and count whose experience changes without their having chosen anything.
