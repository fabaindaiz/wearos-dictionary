---
bundle: agent-guides
lineage: g-8b5800/main
version: 8
slug: same-answer-or-refuse
topic: data-correctness
claim: A second path to an answer — another source, a cache, a faster reduction, a separate reader for your own content, a validator reading a convenient form — is removed where it can be, and where it cannot, it must agree with the first exactly, on the whole serialized output, or refuse and name what it is missing.
confidence: measured
reach: architecture, planning, review
---

# Same answer, or refuse

## Why it works

A second computation path usually arrives for a good reason: the first is slow, batch-only, or reads something the deployment does not have. It usually arrives *almost* equivalent, and "almost" is the dangerous state. A model or a decision downstream was fitted or calibrated on the first path's numbers; feeding it the second path's slightly different numbers moves decisions with no error, no alarm and no failing test, because each number on its own looks plausible.

Exact agreement is testable and approximate agreement is not: a tolerance has to be chosen, and the one chosen is whatever makes today's comparison pass. Comparing the **whole serialized output**, rather than the headline number, catches the differences a numeric test cannot see — a timestamp rendered in a different offset, a field present on one side only, an ordering change.

Exact agreement needs exact representations and a quiet comparison. A value that does not survive a round trip bit for bit (a colour through a hex string, a float through text) makes two equal paths differ; a field minted fresh on every run (a random id, a timestamp) makes every comparison differ, and is normalised out by a rule written down, not by eye; anything that reads the wall clock inside the compared region turns the comparison into a comparison of two moments.

Refusing is what makes the rule livable. A path that cannot yet reproduce the answer returns an explicit refusal naming the missing datum, which is an honest state; a path that returns a close answer is a silent one.

### Remove the second path first

The cheapest agreement is the one that needs no test: where the second path can be removed, remove it. A system that accepts the same kind of content from several places — shipped with it, imported by users, written by its own editor — is tempted to give each a path: the built-in content read from the source tree, imports through a careful reader, the validator reading a convenient intermediate form. Each path then has its own bugs, and only the one used daily is ever exercised. The import path, which is the risky one, is the least used; the validator, which is the trusted one, checks an artefact the product never opens.

With one path, the product's own content runs through the untrusted reader every day, where a failure is seen by the people who can fix it. An editor that saves by importing can never produce a file the reader refuses. A validator that assembles content the way the runtime does reports on the tree the user will see. And there is no copy of the assembly logic to be left behind when the format changes. A second path that stays — because it is faster, or reads a source the first cannot — is the case the rest of this note governs.

## When it does NOT apply

- **When tolerance is part of the contract**: an estimate served with a declared error bound, a cache of a quantity that is itself approximate, an endpoint labelled as approximate. Then the bound is the test.
- **As a proof of correctness.** Agreement shows that two paths are the same, not that either is right: a defect both paths share — an off-by-one in a helper both call — agrees with itself perfectly. One repository kept four small copies of one search routine for exactly this reason: its two-path check could not catch an off-by-one that shifts both directions equally.
- **When the two paths are not meant to answer the same question** — a monitoring aggregate that watches the first path is not a second path to its answer, as long as nothing feeds it back as input.
- **Removing a path, when the sources genuinely need different trust.** If first-party content may do things third-party content may not (run code), the shared path must be the stricter one, and first-party features that need more belong elsewhere.
- **Removing a path, when the shared one is too expensive for a hot loop** — measure first; in the occurrence below, opening every package through the shared reader cost milliseconds.
- **Diagnostic-only forms** (a baked preview opened in a tool) may exist beside the one path, provided the product never reads them and a rule says the source wins when both are present — the rule of `derived-copy-goes-stale-silently`.

## What it costs

The alternative path stays unusable until it reproduces exactly; here the faster path answered with a refusal for weeks. Every route with two sources needs an equivalence test over its full output, and "probably equivalent" optimisations do not go in until they are proven. Removing a path instead makes your own content pay the untrusted path's price: packaging before every run, hashing, the refusal rules — and every tool that runs the product must package first, or it tests the old path again.

## Where it came from

A risk service with a batch source and a document-backed one. Measured before any code was written, the document path gave an aggregate risk **about 5 % lower** than the batch path; it shipped as a refusal naming the missing input instead. Comparing the entire JSON response, not the risk value, later found the same instant rendered in UTC by one path and with a local offset by the other, which no numeric test had seen. A proposal to refresh features from database aggregation queries was kept to "watch, do not compute" for the same reason: it would have been a second feature computation feeding the model numbers it never saw in training.

A second repository, a renderer whose content moved from code to a data description composed at load time, kept both paths side by side while it converted them: each data description had to render the same frames as the code builder it replaced, compared byte for byte — every compared frame of every converted piece. The comparison found two defects nothing else did — a tint applied twice, which on screen passed for "a bit darker", and a reordering that changed **about a tenth of a frame's pixels**.

The same repository then removed a second path. It ships a handful of its own content packages and imports users' packages in the same format; on 2026-09-18 it was changed to read its own content exactly as it reads an import, so that a bug in the reader shows on the application's own content, where it can be seen, and not only on an import. Every rendered frame of its determinism check stayed byte-identical to the baseline read from folders, and the shipped bundle became a few percent smaller because no asset was stored twice. Running the validator from *inside the shipped bundle*, rather than the source tree, exposed a check that had worked only because source images sat beside it. Earlier, it had three copies of the code that turns a content description into objects — the runtime, a menu background and the validator; when one input form was retired, the menu background was left behind, its content disappeared and **nothing failed**. The copies became one function, its validator now assembles content the way it is run, and its editor saves by importing.

## Literature

- **Zinkevich, ["Rules of Machine Learning: Best Practices for ML Engineering"](https://developers.google.com/machine-learning/guides/rules-of-ml)** (Google). Training/serving skew as a primary failure, and logging serving-time features to detect it (Rule #29: "save the set of features used at serving time, and then pipe those features to a log"). *Verified 2026-09-23 against the guide.* **What we take:** the failure. **Where we go further:** exact agreement on the full output, and refusal instead of approximation.
- **Breck, Cai, Nielsen, Salib & Sculley, 2017, ["The ML Test Score"](https://doi.org/10.1109/BigData.2017.8258038)** (IEEE BigData; Monitor 3, "training and serving are not skewed"). Tests for training/serving skew as part of production readiness. **What we take:** that skew is tested, not assumed away.
- **Humble & Farley, 2010, *Continuous Delivery*, ch. 5** ([excerpt: "Deployment Pipeline Practices"](https://www.informit.com/articles/article.aspx?p=1621865&seqNum=3)). "Only build your binaries once" and "use the same process to deploy to every environment", so the deployment path is itself tested by use. *Verified 2026-09-22 against the chapter excerpt on InformIT.* **What we take:** a path is tested by being the only one. **Where we go further:** the same rule for content sources and for the validator, not only for deployments.
- **Sibling in this base:** `validate-each-transformation-run` — a rewrite of one artefact, where the output differs by design and is compared by its preserved projection rather than whole.

## Evidence

**Measured in two repositories:** in the first, a gap of about 5 % between two paths and an offset difference found only by full-output comparison; in the second, two defects found only by byte comparison of converted content against its code builders (one changing about a tenth of a frame's pixels). The transformation runs of the same repository are counted in `validate-each-transformation-run`, not here. Not measured: how often an approximate second path, left in production, changed a decision. The experiment is to replay a period through both paths and count decisions that cross a threshold.

**The removal half is reasoned, from two occurrences in one repository** (absorbed 2026-09-24 from the retired `one-path-for-every-source`): a third copy of the assembly code left behind silently when an input form was retired, and a validator check that worked only beside the source tree. The frame identity when the paths were unified shows that the unified path was equivalent at that moment — evidence for the agreement half, counted here once — and is not a measurement of drift. Not measured: how many defects a separate import path would have hidden. The experiment: for a month, count defects in the shared reader found by first-party runs versus by imports.
