---
# bundle-header — provenance for this document. Identical across the bundle.
bundle:    agent-guides
lineage:   g-8b5800/main
ancestry:  [g-c7344c, g-099a8a, g-8b5800]
version:   10
component: knowledge-area
released:  2026-09-24
---

# What the data and the checks tell you — area index

**Notes about what a row, a number or a check actually tells you, as opposed to what it suggests.** Reached from `../INDEX.md`, which holds the phase guide for every
note; this file holds the checks. Every note here is under its topic and under at least one
*about to do* row — checked by the repository's audit.

## By topic, with the check that shows each one holds

### `data-correctness`
What a row, a population or a stored snapshot may contain, and at which instant it may know it.

| Note | Claim | Verify by |
|---|---|---|
| [population-by-outcome](../notes/active/population-by-outcome.md) | Never define a training, evaluation or reporting population by a quantity the process itself produces; define it by what was knowable before the fact. | for every filter on the population: was the filtered value knowable at the decision instant? |
| [truncate-the-world-to-test-as-of](../notes/active/truncate-the-world-to-test-as-of.md) | Test point-in-time correctness by rebuilding from a world truncated at an instant and demanding identical rows for everything cut before it — and know that state without history survives the truncation. | a truncation test over the whole feature set, plus a planted leak that makes it fail |
| [censoring-is-information](../notes/active/censoring-is-information.md) | An outcome that has not happened yet means "it lasted at least this long"; model it as censored where the objective allows it, and filter by a maturity horizon where it does not — never count it as a negative. | no unresolved row carries a filled-in label; the horizon is applied where the label is defined |
| [same-answer-or-refuse](../notes/active/same-answer-or-refuse.md) | A second path to an answer — another source, a cache, a faster reduction, a separate reader for your own content, a validator reading a convenient form — is removed where it can be, and where it cannot, it must agree with the first exactly, on the whole serialized output, or refuse and name what it is missing. | an equivalence test on the full serialized output of both paths; no reader exists that only first-party content, or only the checker, uses, and the validator runs against the shipped artefact |
| [read-a-snapshot-at-its-own-end](../notes/active/read-a-snapshot-at-its-own-end.md) | Evaluate stored or extracted data at its own last event, never at the wall clock; a window that reaches past the data's end reads as the world having stopped. | the evaluation instant is the data's last event, and the answer says how old its data is |
| [absolute-level-is-a-time-index](../notes/active/absolute-level-is-a-time-index.md) | In a model trained over time, any cumulative or absolute magnitude encodes the date; emit it as a ratio against the population's value at the same instant, and allow raw levels only by explicit per-consumer permission. | a classifier trained to recover the period from the features scores near chance |

### `measurement`
What a number means before you act on it.

| Note | Claim | Verify by |
|---|---|---|
| [metric-against-trivial-predictor](../notes/active/metric-against-trivial-predictor.md) | Never report a model metric without the score a trivial predictor gets on the same data, and never read a delta smaller than run-to-run noise as a result. | every metric table has the trivial-predictor row and an interval; each new label's base rate is asserted |
| [count-both-sides-and-use-a-control-window](../notes/active/count-both-sides-and-use-a-control-window.md) | Count both sides of an event before joining them on a key one side may lack, and compare every incident-window finding against a control window. | per-side counts, per-side key coverage and a control window accompany the finding |
| [report-coverage-before-findings](../notes/active/report-coverage-before-findings.md) | Zero findings and zero coverage read identically and mean opposite things; every report states how much of its input it could evaluate before stating what it found. | the report's first line is "evaluated N of M"; a check whose subjects are found rather than listed fails on zero of them |

### `verification`
What a check actually tells you, as opposed to what its number suggests.

| Note | Claim | Verify by |
|---|---|---|
| [coverage-measures-execution](../notes/active/coverage-measures-execution.md) | Coverage measures which lines ran, not whether anything checked them — so it cannot rank a single suite, and against buggy code it cannot rank anything. | a mutation score is reported next to any coverage figure used as quality |
| [a-check-must-be-seen-to-fail](../notes/active/a-check-must-be-seen-to-fail.md) | A gate, audit, filter or count earns trust only after it has been seen to go red on a planted violation; a check that reads the wrong stream or dies before its main step reports green forever. | each new check was seen red on a planted violation once, and that is recorded |
| [ratchet-in-a-pinned-environment](../notes/active/ratchet-in-a-pinned-environment.md) | Introduce a rule against an existing backlog as a ratchet that can only go down — violations listed inside the check, or an advisory with a written promotion condition — never as zero and never by disabling the rule; and pin the tools that produce the count. | the baseline has one home and lives inside the check; the counting tools are pinned |
| [reproduce-the-checkout-not-only-the-environment](../notes/active/reproduce-the-checkout-not-only-the-environment.md) | A number from a check is a function of the commit, the environment and the checkout; reproducing it elsewhere means reproducing all three — pin the environment, and run from a clean export of the commit, because the working tree holds every untracked file the real runner will not have. | the number a gate is quoted for was taken from a clean export of the commit, in the declared environment, with the interpreter path and the collation recorded |
| [test-double-fidelity](../notes/active/test-double-fidelity.md) | A test double that accepts more than the real dependency makes the test pass against a fiction; check its fidelity rather than assuming it, and make tests fail closed on the real network. | a contract test runs the fake and the real dependency on the same cases; tests cannot open sockets |
| [validate-each-transformation-run](../notes/active/validate-each-transformation-run.md) | When a tool you cannot fully trust rewrites an artefact — a formatter, a translator, a generator, a refactor — check each run's output against the projection it must preserve, instead of trusting the tool or eyeballing the result. | the projection (tokens, comments, numbers, identifiers, pixels) is compared after every run, and a planted loss is rejected |
| [sweep-the-rendered-extremes](../notes/active/sweep-the-rendered-extremes.md) | Check a visual or layout invariant on the rendered output across the whole configuration matrix — every screen, language, user size, aspect ratio and moment — because the extremes break it, and a number derived from the source or measured once goes stale. | a gate renders the whole matrix; an over-long string planted in one language fails it and names the control |
| [unrunnable-system-moves-the-gate](../notes/active/unrunnable-system-moves-the-gate.md) | When a system cannot be booted in the environment it is written in — it needs hardware, credentials or a network that only production has — the questions "does it run?" and "do the tests pass?" stop being available, and both the gate and the dominant bug class move. | the gate names what it can and cannot see; composition is traced by reading before a change is called done |

## By what you are about to do

| …do this | Read | Because the default answer is wrong when |
|---|---|---|
| Filter, balance or resample a training, evaluation or reporting population | [population-by-outcome](../notes/active/population-by-outcome.md) | the filter is on something the process produced, so it selects on the outcome |
| Label rows whose outcome resolves over time | [censoring-is-information](../notes/active/censoring-is-information.md) | the newest rows have not resolved yet, and the convenient fill-in is the negative class |
| Add a feature to a point-in-time dataset | [truncate-the-world-to-test-as-of](../notes/active/truncate-the-world-to-test-as-of.md) | the feature reads state without history, or its knowable-at timestamp is not its event timestamp |
| Add a feature that is a count, a total or an age | [absolute-level-is-a-time-index](../notes/active/absolute-level-is-a-time-index.md) | the population grows, so the level is a clock the model will extrapolate |
| Add a cache, a faster reduction or a second source for an existing answer | [same-answer-or-refuse](../notes/active/same-answer-or-refuse.md) | it is *almost* equivalent, and a tolerance chosen today makes the comparison pass |
| Compute windows or recency over an extract, cache or batch store | [read-a-snapshot-at-its-own-end](../notes/active/read-a-snapshot-at-its-own-end.md) | the data ends before now, and every window reads the gap as silence |
| Report or compare a model metric, or add a new label | [metric-against-trivial-predictor](../notes/active/metric-against-trivial-predictor.md) | the label is rare or zero-inflated, and a constant scores almost as well |
| Investigate a discrepancy with a join, especially around an incident | [count-both-sides-and-use-a-control-window](../notes/active/count-both-sides-and-use-a-control-window.md) | one side gained the join key later, so the gap is in the key and not in the world |
| Write a scan, audit, reconciliation or data-quality report | [report-coverage-before-findings](../notes/active/report-coverage-before-findings.md) | a misspelled field or an empty filter makes "nothing found" mean "nothing looked at" |
| Reproduce a gate, a CI run or any check that runs elsewhere, read a number from another environment, or explain why two runs disagree | [reproduce-the-checkout-not-only-the-environment](../notes/active/reproduce-the-checkout-not-only-the-environment.md) | the replica was built in the working tree, so it holds credentials, caches and artefacts the real runner has never seen — or a stale environment or another locale produced the number |
| Add a gate, an audit rule or a CI step | [a-check-must-be-seen-to-fail](../notes/active/a-check-must-be-seen-to-fail.md) | the glue reads the wrong stream or dies early, or its subjects vanish, and the check is a constant green |
| Introduce a rule into a codebase that already violates it | [ratchet-in-a-pinned-environment](../notes/active/ratchet-in-a-pinned-environment.md) | zero is red on arrival and gets switched off, or the toolchain moved the count with no code change |
| Write a fake, a stub or a mock for a dependency | [test-double-fidelity](../notes/active/test-double-fidelity.md) | the double accepts more than the real thing, or the test can still reach the real network |
| Set a coverage target, or read a coverage report as a measure of test quality | [coverage-measures-execution](../notes/active/coverage-measures-execution.md) | the number rises when tests run code without asserting on it — and falls silent entirely against buggy code |
| Add a way to load your own content, a preview, or a validator beside the product's reader | [same-answer-or-refuse](../notes/active/same-answer-or-refuse.md) | the second path is exercised by nobody, so its drift is found in the field |
| Run a formatter, generator, translator or bulk refactor over files | [validate-each-transformation-run](../notes/active/validate-each-transformation-run.md) | the tool is trusted because it usually works, and the run that loses a comment or a number is never looked at |
| Change layout, text, fonts, sizes or anything drawn on screen | [sweep-the-rendered-extremes](../notes/active/sweep-the-rendered-extremes.md) | it is checked at the default size, language and aspect, and the extremes are where it breaks |
| Verify a change in a system that cannot be booted where you write it | [unrunnable-system-moves-the-gate](../notes/active/unrunnable-system-moves-the-gate.md) | more tests of what already runs does not touch composition, which is the class that bites at startup |

## How well founded is each note

The claim and our application are two different questions — see `../INDEX.md`, *How well founded is any of this*.

| Note | Claim rests on | Our evidence |
|---|---|---|
| population-by-outcome | Heckman 1979; Kaufman et al. 2012 — **well established** | measured in one repository |
| truncate-the-world-to-test-as-of | Kaufman et al. 2012 — **well established** | enforced; detection power shown by a planted leak, no real leak counted |
| censoring-is-information | Kaplan & Meier 1958; Cox 1972 — **foundational statistics** | enforced by tests; the cost of the naive labelling unmeasured |
| same-answer-or-refuse | Zinkevich; Breck et al. 2017; Humble & Farley 2010, build once — **well established** | measured in two repositories; the removal half reasoned |
| read-a-snapshot-at-its-own-end | none specific | measured in one repository |
| absolute-level-is-a-time-index | adversarial validation — **folk practice** | measured in one repository |
| metric-against-trivial-predictor | Brier 1950 skill scores; Saito & Rehmsmeier 2015 — **well established** | measured in one repository |
| count-both-sides-and-use-a-control-window | difference-in-differences — **well established** | measured in one repository |
| report-coverage-before-findings | none known | measured in three repositories |
| coverage-measures-execution | **two empirical studies**: Inozemtseva & Holmes 2014 and a 2026 replication on LLM-generated tests — **strong** | per-change mutation only; no suite-wide score |
| a-check-must-be-seen-to-fail | mutation testing, DeMillo et al. 1978 — **well established** | measured in three repositories |
| ratchet-in-a-pinned-environment | baseline files in static analysers — **practice** | measured in two repositories |
| reproduce-the-checkout-not-only-the-environment | hermetic builds (Bazel); reproducible-builds.org on locales — **practice** | measured in two repositories |
| test-double-fidelity | *Software Engineering at Google* ch. 13; Fowler's ContractTest — **practice** | occurrences; no rate |
| validate-each-transformation-run | Pnueli et al. 1998, translation validation — **established** | reasoned; one caught loss, planted losses rejected |
| sweep-the-rendered-extremes | pseudolocalization — **practice**; boundary-value analysis — **textbook** | measured in one repository |
| unrunnable-system-moves-the-gate | hardware-in-the-loop practice; Xu et al. 2016 on latent configuration errors — **practice and one empirical study** | reasoned; two repositories arriving at one arrangement |
