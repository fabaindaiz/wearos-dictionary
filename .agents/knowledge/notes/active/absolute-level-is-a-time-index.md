---
bundle: agent-guides
lineage: g-8b5800/main
version: 6
slug: absolute-level-is-a-time-index
topic: data-correctness
claim: In a model trained over time, any cumulative or absolute magnitude encodes the date; emit it as a ratio against the population's value at the same instant, and allow raw levels only by explicit per-consumer permission.
confidence: measured
reach: architecture, review
---

# An absolute level is a time index

## Why it works

A growing system grows in every absolute number: total users, total volume, account age, cumulative counts, even a rolling-window count when the population itself grows. Each of these is strongly correlated with the calendar. A model trained over a period learns "large means late", which is true in the training data and useless going forward, where every value is larger than anything seen. The model extrapolates on a feature it treats as signal and is actually a clock.

Dividing by the population's value **at the same instant** removes the trend and keeps the relative information — "this entity is twice as active as the typical one right now". Calendar features follow the same rule: phase (day of week, day of month), never level (the date itself).

The check is adversarial: train a classifier to predict the period from the features. If it can, some feature is a clock.

## When it does NOT apply

- **Targets measured in absolute units.** Predicting an amount needs the scale; a model given only ratios cannot say how much. There, levels are required — and declared, per consumer, as a permission rather than a default.
- **Stationary processes**, where levels do not trend.

## What it costs

Every feature depends on a global baseline at each instant, so serving needs that global state as well as the entity's — here almost every feature needed the population-wide level. The permission list for levels has to be maintained per model.

## Where it came from

A panel of snapshots over a growing transactional platform. Rolling windows were expected to be enough and were not: the adversarial AUC for recovering the epoch stayed near 1 with rolling windows, no better than with cumulative levels. Dividing by the contemporaneous baseline worked. In the other direction, a target in absolute units needed the scale: with ratios only the model did worse than predicting the mean (a negative r²), and with two declared levels it explained a substantial share (r² about 0.4).

## Literature

Adversarial validation — training a classifier to separate train from test — is widespread practice in applied machine learning competitions; no formal citation we are confident of. **What we take:** the test. **What we add:** using it on the time axis as a gate for every feature, and the ratio-to-contemporaneous-baseline as the default transformation.

## Evidence

**Measured in one repository:** an epoch-recovery AUC near 1 with rolling windows and with levels alike, and an r² below zero with ratios only against about 0.4 with two declared levels. What would strengthen it: the same adversarial check on a second dataset with a different growth curve, and the forward-period degradation of a model trained with and without the transformation.
