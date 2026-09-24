---
bundle: agent-guides
lineage: g-8b5800/main
version: 7
slug: count-both-sides-and-use-a-control-window
topic: measurement
claim: Count both sides of an event before joining them on a key one side may lack, and compare every incident-window finding against a control window.
confidence: measured
reach: debugging, review
---

# Count both sides, and use a control window

## Why it works

"Debits without a matching credit", "orders without a shipment", "requests without a response": each is a join, and a join reports a gap wherever the key is missing — whether or not the event is. Keys are added to schemas over time, often to one side first. Across that migration the join manufactures a discrepancy whose shape is convincing: it starts and stops at dates, it has a clean total, and it lines up with whatever incident is being investigated.

Two cheap checks remove it. **Count each side independently** in the window, without the join: if both counts match, the gap is in the key, not in the world. **Run the same analysis on a control window** where nothing happened: a finding that appears there too is a property of the data, not of the incident. The control window also catches the opposite error — an "incident" whose rate is actually below baseline.

## When it does NOT apply

Joins on a key that the schema guarantees on both sides from the start of the data. The control window is still cheap insurance, and it is the check people skip.

## What it costs

One extra aggregation per side, one control window per claim, and the discipline of not announcing a number until both are in.

## Where it came from

An investigation reported well over a thousand one-sided events — a first leg with no matching reversal — with a clean total attached. It was retracted: the window held **exactly as many reversals as first legs**. The first leg carried the join key almost every time and the reversal almost never did, because the key had been added to the two sides at different times — the coverage ramp, from none to about a third to all on one side and from none to about three quarters on the other, was exactly the "closed window" that looked like an incident. Without a control window, the same analysis would also have invented about a thousand broken records of a kind that is keyless by design in every window; and the incident window turned out to sit *below* baseline (about 2 % against about 3 %).

## Literature

The control-group logic of difference-in-differences — e.g. **Card & Krueger, 1994, ["Minimum Wages and Employment"](https://davidcard.berkeley.edu/papers/min-wage-ff-nj.pdf)** (American Economic Review 84(4); the paper never says "difference-in-differences", but New Jersey against Pennsylvania is that design; *verified 2026-09-23 against the paper*) — compares the treated window with an untreated one to separate the effect from the background. **What we take:** the comparison. **What we add:** join-key asymmetry across a schema migration as the specific background that fabricates effects. No source on that known.

## Evidence

**Measured in one repository:** a retracted finding whose two sides counted exactly equal, the key-coverage ramps, and control-window rates that put the incident below baseline. What would generalise it: for each join used in an investigation, publish per-side key coverage by month next to the finding.
