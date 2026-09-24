---
bundle: agent-guides
lineage: g-8b5800/main
version: 8
slug: derive-state-from-one-clock
topic: time-and-control
claim: Make time-dependent state a pure function of one authoritative clock, never an accumulation of per-tick deltas, and treat every time value the platform reports as a claim to bound.
confidence: measured
reach: architecture, planning, debugging, review
---

# Derive state from one clock

## Why it works

State advanced by adding each tick's elapsed time has two defects that do not show on a healthy machine. **Error accumulates**: every tick's rounding, every dropped or doubled tick, is kept forever, so two parts that started together drift apart at a rate nobody measured. And **no instant can be reached directly**: to know the state at time *t* you must replay every tick before it, so jumping, rewinding or resuming from a stored position gives a different answer from playing through.

State computed as `f(clock)` has neither. Any instant is one evaluation; jumping forward and jumping back to the same instant give the same output; two parts reading the same clock cannot drift from each other. It also makes the property testable, which accumulation never is: reach one instant from the start and from the end, and demand identical output.

That leaves the clock itself as the single point everything trusts, so it is the one place that must be distrusted. Timing values reported by a platform layer — a latency, an elapsed interval, an "ended" event, the direction of a step — are **claims**, and on the target they may not hold even where they held in development:

- **Clamp** a reported interval to a plausible range; an unbounded one moves the whole state by its error.
- **Check an end event against what you already know** (the length of the thing that ended) and ignore one that is implausibly early.
- **Never infer an intent from the shape of a noisy value.** A clock that is not strictly monotonic produces small backward steps; reading each as a rewind fires the rewind path many times a second. Route explicit intent — a seek, a reset — through its own call, and let the clock only report time.

Anything that genuinely cannot be a function of the clock — live input, a user steering something — is **quarantined**: one override of one value, one call that releases it and gives the clock back authority at once, and nothing touched when it is off.

## When it does NOT apply

- **Path-dependent simulation.** Physics with collisions, anything whose state at *t* depends on the route taken, has no closed form in *t*. Fixed-step integration is correct there; if seeking is still required, simulate once from a seed and interpolate the recorded result, which makes the path pure data.
- **Genuinely interactive state**, which cannot be reconstructed at an arbitrary instant because its input did not exist yet. That is the quarantined exception, not a reason to abandon the rule for everything else.
- **When evaluating at an arbitrary instant is too expensive** for every frame and nothing ever seeks. Then the accumulation's drift is the price of speed, and it should be measured and written down, not assumed small.
- **Values whose semantics you control and have verified on the target.** Bounding is for claims; a value that is already a fact needs no clamp.
- **The viewport is an input to drawing, not to state.** A zoom or resize that hands the content a different view to be placed for makes state a function of the clock *and* the view, and a round trip no longer returns the same picture. Keep placement a function of the clock alone and scale the drawn result.

## What it costs

Every component must be able to evaluate its state at an arbitrary instant, which forbids some whole categories of effect (anything that only knows how to step forward) and makes others more expensive per frame. The bounds are guesses: a clamp can hide a real long pause, and a tolerance for backward steps can swallow a genuine small seek — which is exactly why intent needs its own path. And a quarantined exception is a second authority that has to be kept small by discipline, because it leaks: one did, through the clock's own non-monotonic steps.

## Where it came from

A 2D renderer whose picture runs in lockstep with an audio track, used on a runtime its developers cannot observe. Audio is the master clock; every visual element is a function of the playback position, and a check renders each checked instant reached from the start and from the end and compares pixels: every instant of every piece matched, in seconds of runtime. Constructs that can only simulate forward are refused by the validator. Randomised motion stays pure by simulating once at load from a seed and interpolating: frames identical between two different processes. A drift readout exists to be looked at, not consumed: on desktop it settles at a small constant offset and stays; what would matter is whether it grows.

The clock's own inputs lied on the web platform in three ways: a platform audio-clock interval read many seconds after a wait in a menu, which started playback far into the track, and is now clamped to a fraction of a second; an "ended" event arrived far short of the track's length and is ignored when it comes implausibly early; the platform's output-latency value returned nothing useful, so the offset is calibrated by the user. Later, the quarantine for a user-steered object leaked through the clock: frames arriving a few milliseconds out of order were each read as a rewind, and each rewind reset the object to its authored position — with the pointer held to one side, the object moved a little toward it and was then dragged away from it. Backward steps under a fraction of a second are now playback, and a requested seek resyncs through its own call.

The same property broke through the view rather than the clock (2026-09-18): a 2x zoom first handed the content a smaller view, the wrapping elements were re-placed for it and came back elsewhere — hundreds of pixels differed after zooming in and out. The content now keeps its 1x view and the picture is scaled; at 2x the render equals the 1x picture's region pixel for pixel at every instant checked, and a round trip gives back the identical picture.

## Literature

- **[Elliott & Hudak, 1997, "Functional Reactive Animation"](https://dl.acm.org/doi/10.1145/258948.258973)** (ICFP, pp. 263–273). A *behaviour* is a value that varies over continuous time, built from a primitive `time` and sampled at any instant; the paper that started functional reactive programming. *Verified 2026-09-22 against the ACM record and a summary of the paper.* **What we take:** the representation, and that it makes sampling at any instant well defined. **Where we go further:** the claim is about correctness under seeking and drift in an ordinary imperative codebase, and it adds the half the paper does not address — the clock is supplied by a platform that may misreport it.
- **[Fiedler, 2004, "Fix Your Timestep!"](https://gafferongames.com/post/fix_your_timestep/)** Variable-delta integration makes a simulation's behaviour depend on the delta passed in and prevents exact reproducibility between runs; the remedy is a fixed step, rendering by interpolating between the previous and the current state. *Verified 2026-09-22 against the article.* **What we take:** the boundary — where state is path-dependent, a fixed step is right, and interpolating its recorded result is how it becomes seekable. It is also independent evidence for the first half of the claim: accumulating a variable delta is what it argues against.
- **Kleppmann, *Designing Data-Intensive Applications*, "Unreliable Clocks"** (ch. 8 of the [first edition](https://www.oreilly.com/library/view/designing-data-intensive-applications/9781491903063/), ch. 9 of the [second](https://www.oreilly.com/library/view/designing-data-intensive-applications/9781098119058/ch09.html)). Time-of-day clocks can jump backwards; only a monotonic clock is fit for measuring durations. *Verified 2026-09-22 against the chapter's published outline and summaries, and 2026-09-23 against a third-party copy of the first-edition text ("it may be forcibly reset and appear to jump back to a previous point in time"); the publisher's pages are paywalled.* **What we take:** a backward step is a normal event of a clock, not a fault — and a clock assembled from several sources, as an audio position plus an estimated latency is, has no monotonic guarantee at all.

## Evidence

**Measured in one repository:** seek purity at every checked instant of every piece, and identical frames across processes for the seeded simulation; three platform timing values that misreported on the target (an interval many seconds long when unclamped, an early end event, a useless latency); one leak of the quarantine through backward steps, with its movement measured before and after; one leak through the view (hundreds of pixels after a zoom round trip, none after the fix). Not measured: the drift an accumulating implementation of the same content would have produced over a whole track. The experiment is to run one piece both ways for its full length on the target and record the divergence at the end.
