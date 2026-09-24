---
bundle: agent-guides
lineage: g-8b5800/main
version: 8
slug: one-path-for-every-source
topic: verification
claim: Read first-party content, third-party imports, editor output and validation through the one path the product runs, because a second path for your own content or for checking drifts silently and a check on it reports on something nobody runs.
confidence: measured
reach: architecture, planning, review
retired_because: Merged 2026-09-24 into same-answer-or-refuse as its first remedy (remove the second path, else hold it to exact agreement); its measured figure showed equivalence at unification, not drift.
superseded_by: same-answer-or-refuse
---

# One path for every source

## Why it works

A system that accepts the same kind of content from several places — shipped with it, imported by users, written by its own editor — is tempted to give each a path: the built-in content read from the source tree, imports through a careful reader, the validator reading a convenient intermediate form. Each path then has its own bugs, and only the one used daily is ever exercised. The import path, which is the risky one, is the least used; the validator, which is the trusted one, checks an artefact the product never opens.

With one path, the product's own content runs through the untrusted reader every day, where a failure is seen by the people who can fix it. An editor that saves by importing can never produce a file the reader refuses. A validator that assembles content the way the runtime does reports on the tree the user will see. And there is no copy of the assembly logic to be left behind when the format changes.

## When it does NOT apply

- **When the sources genuinely need different trust.** If first-party content may do things third-party content may not (run code), the shared path must be the stricter one, and first-party features that need more belong elsewhere.
- **When the shared path is too expensive for a hot loop** — measure first; here opening every package cost milliseconds.
- **Diagnostic-only forms** (a baked preview opened in a tool) may exist, provided the product never reads them and a rule says the source wins when both are present — the rule of `derived-copy-goes-stale-silently`.

## What it costs

Your own content pays the untrusted path's price: packaging before every run, hashing, the refusal rules. Every tool that runs the product must package first, or it tests the old path again.

## Where it came from

A client application that ships its own handful of content packages and imports users' packages in the same format. On 2026-09-18 it was changed to read its own content exactly as it reads an import, so that a bug in the reader shows on the application's own content, where it can be seen, and not only on an import. Measured then: every shipped package, tens of megabytes in all, opened in tens of milliseconds; **every rendered frame of the determinism check byte-identical** to the baseline read from folders; the shipped bundle a few percent smaller because no asset was stored twice. Running the validator from *inside the shipped bundle*, rather than the source tree, exposed a check that only worked because source images sat beside it.

Earlier, the same repository had three copies of the code that turns a content description into objects — the runtime, a menu background and the validator. When one input form was retired, the menu background was left behind: its content disappeared and **nothing failed**. The copies became one function. Its validator now assembles content the way it is run, because checking the pre-baked form would be checking a file the runtime never opens, and its editor saves by importing.

## Literature

- **Humble & Farley, 2010, *Continuous Delivery*, ch. 5** ([excerpt: "Deployment Pipeline Practices"](https://www.informit.com/articles/article.aspx?p=1621865&seqNum=3)). "Only build your binaries once" and "use the same process to deploy to every environment", so the deployment path is itself tested by use. *Verified 2026-09-22 against the chapter excerpt on InformIT.* **What we take:** a path is tested by being the only one. **Where we go further:** the same rule for content sources and for the validator, not only for deployments.
- **Sibling in this base:** `same-answer-or-refuse` makes two paths agree; this note removes the second path where it can be removed.

## Evidence

**Measured in one repository:** every compared frame identical through the unified path, a size saving from not storing content twice, one check exposed by running from the shipped bundle, and one silent regression caused by a third copy. **Not measured:** how many defects the import path would have hidden had it stayed separate. The experiment: for a month, count defects found in the shared reader by first-party runs versus by imports.
