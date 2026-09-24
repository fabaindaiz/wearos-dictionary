---
bundle: agent-guides
lineage: g-8b5800/main
version: 6
slug: read-a-snapshot-at-its-own-end
topic: data-correctness
claim: Evaluate stored or extracted data at its own last event, never at the wall clock; a window that reaches past the data's end reads as the world having stopped.
confidence: measured
reach: architecture, debugging, review
---

# Read a snapshot at its own end

## Why it works

Most features are windows — "events in the last 7 days", "days since the last event", "is this mature yet". Computed against *now*, they are only correct if the data is complete up to now. An extract, a cache or a batch store is complete up to its own end, which is earlier. The gap between the two is read by every window as silence: counts drop to zero, recency grows, rates become undefined, and a model interprets a quiet week as a changed world. Nothing errors, and the answer changes every day the data is not refreshed.

Anchoring evaluation at the data's own last event makes the same data give the same answer whenever it is read. Anchoring at the last row of one entity type instead is a subtler version of the same mistake: it cuts trailing events of the other types.

## When it does NOT apply

- **A genuinely live and complete stream up to now**, where the wall clock and the data's end coincide. Even there, say how old the oldest input is, because mixed sources rarely end together.
- **Questions that are about the wall clock on purpose** — "how stale is this store" is answered against now, and it is a monitoring question, not a feature.

## What it costs

Every answer has to carry its data's cutoff, and a stale answer is visibly stale instead of looking fresh — which will be reported as a regression by someone who preferred the fresh-looking number.

## Where it came from

A user-reported incident: after a week without a refresh, a served risk went to about 1 and a value estimate to 0. Measured on the test world, reading data ending at *T* at *T*+7 days emptied the 7-day windows: **undefined features rose by more than half, and about a quarter of all features changed**. It is now pinned by a test that serves a week-old store and demands the stored half unchanged. Earlier, the maturity of an outcome had been measured against the extract's last event after both alternatives were tried: against today, the same extract answered differently each day; against the last row of one kind, trailing events of other kinds were cut.

## Literature

None known that states it in this form. It is a special case of the as-of discipline in the leakage literature, applied to the *reader's* clock rather than the row's.

## Evidence

**Measured in one repository:** undefined features up by more than half and about a quarter of features changed, on the test world. What would strengthen it: the same measurement across refresh delays (1, 3, 7, 14 days), to show the degradation is monotone and to price a refresh cadence.
