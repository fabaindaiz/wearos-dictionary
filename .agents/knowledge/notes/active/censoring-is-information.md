---
bundle: agent-guides
lineage: g-8b5800/main
version: 7
slug: censoring-is-information
topic: data-correctness
claim: An outcome that has not happened yet means "it lasted at least this long"; model it as censored where the objective allows it, and filter by a maturity horizon where it does not — never count it as a negative.
confidence: reasoned
reach: architecture, review, planning
---

# Censoring is information

## Why it works

Any label that resolves over time — paid or defaulted, churned or retained, recovered or written off — is unresolved for the most recent rows at the moment the dataset is built. The default in most pipelines is to fill that gap with the convenient value: "not yet paid" becomes "not paid", "not yet churned" becomes "retained". The recent rows are then systematically mislabelled in one direction, and they are exactly the rows closest to the period the model will be used on.

An unresolved row is not missing data. It carries a lower bound: the event had not happened by the cutoff. Survival methods use that bound directly — the row contributes "survived at least *t*" to the likelihood — so no row is thrown away and none is lied about. Where the objective has no censored form, the honest alternative is a **maturity horizon**: keep only rows old enough for the label to have resolved, and apply the horizon at the point where the label is defined, not in each caller.

## When it does NOT apply

- **Labels that resolve instantly**, or at a fixed, short delay that every row has already passed.
- **Objectives with no censored form** — ordinary classification, counts, many multiclass losses. There the horizon filter is the answer, and its cost (the newest rows are unusable for training) is real and should be stated rather than worked around.
- **An exemption for one target is not a licence for its neighbours.** A quantity that is valid on immature rows (an amount that is final at birth) may skip the horizon; a reader that reuses those rows for a resolving label may not.

## What it costs

A censored objective, or two populations — one with the horizon, one without — and a declared horizon per label. Survival models are less familiar to most readers than classifiers, and the horizon discards the most recent period from training.

## Where it came from

A panel of transaction outcomes with several resolving labels. Unresolved rows become intervals — "at least this long" — for the survival target, which keeps the rows the other targets drop; counts and multiclass objectives have no censored form in the library used, so they keep the horizon filter, recorded as a real limitation rather than an oversight. The adjacent failure was measured in the same repository: a population bound that lived only in one hand-written cell let **about 9 % of rows** enter as failures that never happened (see `absent-constraint-widens`).

## Literature

- **Kaplan & Meier, 1958, ["Nonparametric Estimation from Incomplete Observations"](https://doi.org/10.1080/01621459.1958.10501452)** (*verified 2026-09-23 against the paper; it says "loss" where later work says "censoring"*) (Journal of the American Statistical Association). The product-limit estimator: censored observations contribute to the risk set up to their censoring time. **What we take:** an unresolved row is data up to its cutoff.
- **Cox, 1972, ["Regression Models and Life-Tables"](https://doi.org/10.1111/j.2517-6161.1972.tb00899.x)** (*verified 2026-09-23 against the abstract*) (Journal of the Royal Statistical Society, Series B). Proportional hazards regression with censored observations. **What we take:** censoring can be carried into a predictive model rather than filtered out.
- **Where we go further:** the rule for objectives that cannot carry censoring — a declared horizon applied where the label is defined — and the warning that a per-target exemption does not transfer.

## Evidence

**Reasoned.** The mechanism is enforced by tests (an unresolved row becomes an interval, not a failure; the survival target keeps what the others drop), but the cost of the naive labelling has not been measured here. What would settle it: train the same model with unresolved rows labelled negative and with the horizon, evaluate both on a matured test block, and report the difference in calibration on the most recent months.
