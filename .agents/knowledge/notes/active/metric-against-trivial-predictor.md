---
bundle: agent-guides
lineage: g-8b5800/main
version: 7
slug: metric-against-trivial-predictor
topic: measurement
claim: Never report a model metric without the score a trivial predictor gets on the same data, and never read a delta smaller than run-to-run noise as a result.
confidence: measured
reach: review, planning, debugging
---

# Metric against the trivial predictor

## Why it works

A metric is a number with no scale until it is placed next to what knowing nothing would score. On rare, zero-inflated or multiclass labels several standard metrics are dominated by the base rate: a constant predictor scores almost as well as a good model, and sometimes better. The model's number looks excellent in isolation and is indistinguishable from the constant's.

- **Brier score** on a rare event is mostly the base rate's variance; a model and the constant can be fractions of a percent apart.
- **Mean absolute error** on a zero-inflated quantity is won by predicting zero.
- **Mean probability deviation** in a multiclass problem is exactly zero for the marginal predictor.
- **Ranking metrics with a known floor** (AUC-PR, whose floor is the base rate) and **lift normalised by its ceiling** (`min(1/base, …)`) stay interpretable, because the trivial score is part of their definition.

The second half of the rule is the same idea in time: a single split has a run-to-run variance, and a comparison inside it is noise. Pool out-of-fold predictions or bootstrap an interval before claiming a difference.

A cheap corollary is the polarity check: **assert every new label's base rate against its expected value.** An inverted label trains, converges and scores very well on the opposite question; the base rate is often the only signal.

## When it does NOT apply

Balanced labels where standard metrics are already informative. The rule still costs only one row there, so it is usually kept anyway.

## What it costs

One baseline column per metric table, and interval estimation — here tens of seconds of bootstrap per report. It also removes some good-looking numbers from presentations.

## Where it came from

A family of models over rare failure events, measured on the same test blocks: the model's Brier score was **under half a percent** better than the trivial predictor's; an apparent MAE improvement turned out, once compared with predicting zero, to rest on a bias that inverted the verdict; a multiclass deviation was exactly 0.0 for the marginal predictor. A single split showed **a few percent run-to-run** and **tens of percent across periods**. Normalising lift by its ceiling showed one target near its ceiling (about 90 %) and another at a few percent, with near-identical AUC. Separately, a negated label produced a base rate several times the expected one and an AUC-PR that looked excellent until set against its floor; only the base rate gave it away.

## Literature

- **Brier, 1950, ["Verification of forecasts expressed in terms of probability"](https://doi.org/10.1175/1520-0493(1950)078%3C0001:VOFEIT%3E2.0.CO;2)** (Monthly Weather Review), and the skill-score tradition in forecast verification that followed it: a score is reported relative to a reference forecast. Brier compares against climatology — "in the complete absence of any forecasting skill he is encouraged to predict the climatological probabilities" — but the formal skill score came later. *Verified 2026-09-23 against the paper.* **What we take:** the reference. **Where we go further:** the zero-inflated and multiclass cases where the reference wins outright.
- **Saito & Rehmsmeier, 2015, ["The Precision-Recall Plot Is More Informative than the ROC Plot When Evaluating Binary Classifiers on Imbalanced Datasets"](https://doi.org/10.1371/journal.pone.0118432)** (PLoS ONE). **What we take:** prefer metrics whose floor moves with the base rate.

## Evidence

**Measured in one repository**, on one family of models and one dataset: the comparisons above. Not established: that the same metrics fail the same way on other label distributions; the check is to compute each metric for a constant predictor on a new dataset before choosing which to report.
