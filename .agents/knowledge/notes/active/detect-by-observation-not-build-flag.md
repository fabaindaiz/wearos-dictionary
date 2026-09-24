---
bundle: agent-guides
lineage: g-8b5800/main
version: 8
slug: detect-by-observation-not-build-flag
topic: failure-behaviour
claim: A build or platform flag describes the artefact, not the device it runs on; detect a capability by observing it, and let an unobservable guess only ever withhold a feature, never enable one.
confidence: reasoned
reach: architecture, implementation, review
---

# Detect by observation, not by build flag

## Why it works

A flag such as "mobile" answers a question about how the artefact was built or which export target produced it. A web build is a web build whether it runs on a desktop or in a handheld's browser, so a guard keyed on that flag never fires on the handheld — exactly the device it was written for. The failure is silent: the guarded path is simply never taken.

Observation answers the real question. Did a key ever arrive? Is a touch surface present? Does the pointer hover? Does the API exist and accept the call? These are facts about the running device and they change with it.

Some questions cannot be observed before acting — "is this a handheld?" is a judgement from several signals. Such a guess will sometimes be wrong, so its only allowed use is the safe direction: hiding a tool that would be unusable. A wrong guess then costs a missing option on an unusual device, never a broken control on the target.

## When it does NOT apply

- **Where the flag and the device coincide by construction** — a native build for one platform.
- **Behaviour that must be decided before any observation is possible** (the first frame's layout); then pick the safe default and correct on the first observation.
- **When a signal is itself unverified on the target** — observing it is still a claim until the device confirms it; keep a readout.
- **This is not a licence to fail silently.** A withheld feature is withheld by design, and the guess that withholds it gets a readout on the device, so a wrong guess is seen rather than inferred from a missing button. The sibling is `fail-closed-defaults`, whose boundary for a runtime nobody can observe is "fail visible": the same move applied to a missing capability — withhold, never enable, and show that you did.

## What it costs

Detection code per capability instead of one flag; state for "has this ever been observed"; and features hidden on devices that could have used them.

## Where it came from

One client application, shipped as a web build and used in the browser of a handheld its developers cannot observe. Twice a guard was about to key on the engine's mobile-platform feature flag, and twice the flag was found to be **false** for the web build running in that browser — the real target, so a guard mounted there would not have fired where it mattered (2026-09-17). The first guard became an observation: whether a key has ever arrived, combined with whether a touch screen is available. The second (2026-09-21) asks the browser — coarse pointer, touch points, no hover anywhere — so a laptop with a touchscreen reports hover and keeps everything. It is a guess, allowed to be wrong because it is only ever used to withhold a tool: an editor whose timeline is too dense for a fingertip, where one touch target spans more than a second of timeline and several marks. Measured with the guess forced both ways: the quick panel carries the edit button, or does not. The same product offers fullscreen only where the platform says it can be granted, on the rule that a control that does nothing is worse than no control.

## Literature

- **MDN, ["Browser detection using the user agent string (UA sniffing)"](https://developer.mozilla.org/en-US/docs/Web/HTTP/Guides/Browser_detection_using_the_user_agent).** Knowing the browser "is irrelevant, what we're actually looking for … is feature detection"; detecting "mobile" is usually a proxy for a touch-friendly small screen, better asked with `maxTouchPoints` and media queries. *Verified 2026-09-22 against the MDN page.* **What we take:** ask about the capability. **Where we go further:** a build flag is a worse proxy than the user agent — it describes the artefact — and a guess is confined to withholding.

## Evidence

**Reasoned, from two design occurrences.** The flag's value on the target was stated, never read on the device itself. The only measurement was a desktop probe of the withholding plumbing: with the guess forced both ways, the withheld feature appeared or did not. **Not measured:** whether the touch-availability signal is trustworthy in the target browser — an open question on the device. The experiment: on the target, read each detection signal into a diagnostic readout and compare it with what the device actually has.
