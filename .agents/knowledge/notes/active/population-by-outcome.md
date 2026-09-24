---
bundle: agent-guides
lineage: g-8b5800/main
version: 7
slug: population-by-outcome
topic: data-correctness
claim: Never define a training, evaluation or reporting population by a quantity the process itself produces; define it by what was knowable before the fact.
confidence: measured
reach: planning, architecture, review
---

# Population by outcome

## Why it works

A filter such as "only rows where the amount is positive", "only completed orders" or "only sessions that reached checkout" looks like cleaning. When the filtered quantity is produced by the very process being measured, it is a selection on the outcome: the rows that failed early, were abandoned or never resolved are the ones removed, and they are disproportionately the interesting ones. What remains is a sub-population whose base rate is not the population's, and every metric computed on it inherits the bias with no error anywhere.

The same move hides inside "balancing": resampling splits so that each has the same positive rate defines each split by its labels. The test block's base rate is the denominator of every ranking metric, so equalising it changes what the metrics mean.

A population defined by what was knowable at the decision instant cannot be moved by the outcome, because the outcome did not exist yet when membership was decided.

## When it does NOT apply

- **When the conditioning is the stage itself.** A second-stage model that only ever runs on "was attempted" may train on that population, provided the condition is declared as the stage, is knowable at that stage's decision instant, and the model is never evaluated on the unconditioned population.
- **When the outcome is the object of study and not a predictor's population**: describing the completed orders is a legitimate report, as long as it is not presented as a description of all orders.

## What it costs

Populations get messier: rows with unresolved labels, censored rows and zero-valued rows stay in, and every consumer has to declare its population and its stage instead of inheriting a convenient filter. Spreading evidence across splits has to be done with cut points chosen on event counts, which is more work than resampling.

## Where it came from

A transaction dataset where a filter on a positive amount — an outcome of the transaction — removed **about half of the failures** and left a reported failure base rate under one percent, which turned out to be exactly the failure rate of the processed sub-population. Later, a request to equalise representation across temporal splits was refused for the same reason and met instead with event-count cut points, so that no row changed split because of its label.

## Literature

- **Heckman, 1979, ["Sample Selection Bias as a Specification Error"](https://doi.org/10.2307/1912352)** (Econometrica 47(1); *verified 2026-09-23 against the abstract*). Selecting a sample on a variable correlated with the outcome biases the estimates drawn from it. **What we take:** the mechanism. **Where we go further:** Heckman corrects for the selection; here the rule is to not select, because a filter in a pipeline is rarely recognised as a selection at all.
- **Kaufman, Rosset, Perlich & Stitelman, 2012, ["Leakage in Data Mining"](https://doi.org/10.1145/2382577.2382579)** (ACM TKDD; *verified 2026-09-23*). Leakage as information that would not be available at prediction time. **What we take:** the test — "was membership knowable before the fact?" — is a leakage test applied to row membership rather than to columns. **Where we differ:** we also forbid rebalancing splits by base rate, which is common advice.

## Evidence

**Measured once, in one repository:** about half the failures removed by the filter, and a reported base rate identical to the processed sub-population's. What was not measured is how much a model trained on the filtered population degrades on the full one; the experiment is to train both, evaluate both on the unfiltered test block, and report the difference in the ranking metric.
