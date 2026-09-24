---
bundle: agent-guides
lineage: g-8b5800/main
version: 9
slug: coverage-measures-execution
topic: verification
claim: Coverage measures which lines ran, not whether anything checked them — so it cannot rank a single suite, and against buggy code it cannot rank anything.
confidence: reasoned
reach: planning, review, architecture
---

# Coverage measures execution

## Why it works

A line is covered when a test executes it. Nothing in that definition requires the test to *assert* anything about what the line did. A test that calls a function and discards the result raises coverage exactly as much as one that pins the output to the correct value — and only the second one would notice if the function were wrong.

So coverage is a measure of **reach**, not of **checking**. That makes it useful for one thing and misleading for another. Low coverage is informative: code no test reaches cannot be protected by any of them. High coverage is not: it says the code was run, which is a precondition for being tested and not the same thing.

The mechanism that turns this from a limitation into a hazard is **Goodhart's law**: once a coverage number becomes a target, the cheapest way to raise it is to execute more code while asserting less. The tests that result look thorough in the report and protect nothing.

For an agent the failure has a second, sharper form. **A test written from code that is already wrong encodes the wrongness as the expected behaviour**, passes, and adds coverage. The number goes up precisely when the suite has started certifying a bug.

## When it does NOT apply

- **Finding what is untested.** Uncovered lines are a genuine signal — nothing reaches them. Using coverage to locate gaps is exactly what it is good for; the note is against using it as a *score*, not as a map.
- **Comparing approaches, not ranking one suite.** Across different generators or strategies, coverage correlates moderately to strongly with fault detection. It can say *which of two ways of writing tests* does better, while saying little about whether *this* suite is good.
- **When the tests carry real assertions by construction** — property-based tests, golden files, snapshot comparisons. There the execution *is* the checking, and coverage tracks effectiveness far better. The gap this note describes is the gap between running and asserting, and some techniques close it.

## What it costs

Rejecting a coverage target means replacing it with something harder to game and harder to compute. **Mutation score** — deliberately inject faults and count how many the suite catches — measures checking directly, and is proportionally more expensive: it runs the suite once per mutant. It also asks reviewers to read tests for their assertions rather than glance at a percentage, which costs attention the number was saving. Keep coverage for finding gaps; do not let it stand in for whether the gaps that remain matter.

## Where it came from

A widely installed testing skill instructs *"Target: 80%+ code coverage; critical paths: 100% coverage required"*, with no boundary. The method this bundle carries says the opposite — coverage is a smoke detector, not a goal — so an agent that loads both receives contradictory instructions and has no way to tell which is right. This note is the literature that settles it, and it is why the skill was adapted rather than left as written.

Judgement, unmeasured at first writing: no mutation run was performed on the suite of the repository it came from.

## Literature

- **[Coverage Is Not Strongly Correlated with Test Suite Effectiveness](https://www.cs.ubc.ca/~rtholmes/papers/icse_2014_inozemtseva.pdf)** — Inozemtseva & Holmes, ICSE 2014 ([DOI 10.1145/2568225.2568271](https://dl.acm.org/doi/10.1145/2568225.2568271)). **31,000 test suites across five systems.** Once test-suite size is controlled for, the correlation between coverage and fault-detection effectiveness is **low to moderate**, and stronger coverage criteria — branch, modified condition — **do not** provide greater insight than statement coverage.

  **What we take from it:** most of the apparent link between coverage and quality is the number of tests. A bigger suite covers more *and* catches more; coverage is riding along.

- **[Do Coverage and Mutation Scores of LLM-Generated Test Suites Correlate with Their Effectiveness? (Replicability Study)](https://arxiv.org/html/2607.22880)** — 2026, and the reason this note exists rather than a line in a style guide. It replicates the question **for test suites written by language models**, and reaches a more precise answer:

  · **within one model**, coverage correlates weakly with bug detection; · **across models**, the correlation is moderate to strong — coverage can compare generators; · **on buggy code, the correlations become uniformly weak in every view**; · raw mutation score correlates strongly with real-bug detection across models (**r = 0.863**) and weakly within one; · unlike the 2014 study, suite size is **not** the dominant confounder for generated tests.

  **What we take from it:** the third finding is the one that matters for an agent. When the code under test is wrong, generated tests stop tracking effectiveness at all — which is the empirical form of *an agent that writes the test second writes the test its code passes.* It is also the strongest support in this base for writing the expected behaviour **before** the implementation exists.

  **Where it corrects the popular reading:** "coverage is useless" is wrong. It is useful for comparing approaches and for locating gaps; it fails at ranking a single suite and fails completely against buggy code.

- **[Goodhart's law](https://lawsofsoftwareengineering.com/laws/goodharts-law/)** — *"any observed statistical regularity will tend to collapse once pressure is placed upon it for control purposes."* Cited as the **mechanism** by which a coverage target degrades into assertion-free tests, not as evidence that it does: the software-engineering accounts of coverage gaming are consistent and anecdotal, and no peer-reviewed study of the specific effect was found.

## Evidence

**Before 2026-09-22 — none measured.** The repository the note came from had a real test suite, but no mutation run and no coverage-versus-fault comparison was performed on it. The note's claim rests on two empirical studies — the strongest foundation in this base — and on none of our own data.

**2026-09-22 — a small version of it, run.** In a transactional service, a series of fixes was each verified by a targeted mutation of its own implementation, each failing exactly its own test. Extending one change without tests left **two genuinely surviving mutations** until tests were added, and one mutation "survived" only because a formatter had rewritten its target — the mutation script now asserts its target text exists first. That is per-change mutation, not a suite-wide score, so the coverage-versus-mutation gap is still unmeasured.

What *would* settle it for one repository: run a mutation tool over its existing suite and compare the mutation score to the coverage percentage. A large gap between the two — high coverage, low mutation score — is this note's claim, measured on our own code, and it is the single cheapest experiment in the base to run.
