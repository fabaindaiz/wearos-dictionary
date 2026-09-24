---
bundle: agent-guides
lineage: g-8b5800/main
version: 8
slug: validate-each-transformation-run
topic: verification
claim: When a tool you cannot fully trust rewrites an artefact — a formatter, a translator, a generator, a refactor — check each run's output against the projection it must preserve, instead of trusting the tool or eyeballing the result.
confidence: reasoned
reach: review, implementation, debugging
---

# Validate each transformation run

## Why it works

Proving a transformer correct in general is expensive or impossible: a formatter's grammar, a translation model, a generator with a hundred options. Checking one run is cheap, because the thing that must survive can usually be named and extracted mechanically — the token sequence once whitespace is ignored, the multiset of comments, every number and identifier in a translated document, the rendered pixels of a refactored view. Compare that projection before and after; accept the output only if it matches.

This turns an untrusted tool into a safe one without changing it, and it catches exactly the failures review misses: a dropped comment in a long diff, a number altered in a fluent translation, a changed string literal that looks like reformatting.

## When it does NOT apply

- **Transformations meant to change the protected projection.** A real refactor changes tokens; then the projection is the behaviour (rendered output, test results), not the text.
- **Where the invariant cannot be stated** — a free prose rewrite has nothing mechanical to preserve.
- **Tools already verified at the level you need** (a certified compiler); even then, the wiring around them is not.

## What it costs

Writing the projection, which is small but specific per artefact type; false rejections when the tool legitimately normalises something the projection counts (the projection then learns an exception, written down). Working on copies, so the original is untouched until the check passes.

## Where it came from

One repository, a client application developed by an agent. Its formatter's own documentation warns that it can lose data, so formatting a dirty tree was a manual ritual of several steps, repeated several times in one session. The replacement formats a copy and writes it back only if every comment survived the same number of times and the code is identical once whitespace, brackets, commas and line continuations are ignored; it rejects a copy that dropped a comment or changed a token, and a later review made it keep string literals whole, so `"a b"` becoming `"ab"` is a change.

When several agents translated every document in parallel, a script compared each file with its original: every decision number, every number, every backticked span and every link target was identical, except a written list of placeholders — including a decision log of a few hundred rows. A generator of metadata files proved faithful by a second pass that rewrote **none of its few dozen files**. Refactors that should not change output were accepted by byte comparison of rendered frames — every compared frame in two refactors, and again after the code builders the content had been converted from were deleted.

A generated layout file is where the projection needed a written exception: the generator mints random ids on every save, so a regeneration with nothing changed rewrote **a couple of hundred lines** that differ in nothing else. With those ids normalised out by rule, a later regeneration from split generator code compared at **about two thousand lines with no difference** — two different regenerations, the first showing the noise and the second the check passing through it. An editor that writes a file back is a transformation too: it must change only what was edited, and a probe found it re-sorting keys alphabetically and writing one float back with fewer digits than it was stored with. One comparison was a false start: it captured a region where wall-clock fades differ between two runs of the same code, so it compared nothing, and the content's own values were compared instead.

## Literature

- **Pnueli, Siegel & Singerman, 1998, ["Translation Validation"](https://doi.org/10.1007/BFb0054170)** (TACAS, LNCS 1384). Instead of proving a compiler correct, validate each translation it produces against its source. *Verified 2026-09-22 against the Springer and ACM listings.* **What we take:** the per-run check. **Where we go further:** the translators here are formatters, generators and language models, and the "semantics" checked is a cheap projection rather than a proof.
- **Siblings in this base:** `same-answer-or-refuse` removes a second path or compares whole outputs of two paths meant to agree; here the output differs by design and only its preserved projection is compared. `a-check-must-be-seen-to-fail` applies to the projection check itself: each was seen rejecting.

## Evidence

**Reasoned, from one repository** (downgraded from `measured` on 2026-09-24). One real loss was caught by a per-run check: an editor that writes files back re-sorted keys and stored one float with fewer digits than it held. The formatter guard was seen rejecting planted losses (a dropped comment, a changed token, a merged string literal), which shows the check can fail (`a-check-must-be-seen-to-fail`) but not how often it is needed. The runs that changed nothing — regenerated files, translated documents compared by projection, frames across refactors — show that no loss occurred in them, not that checking was necessary. One regeneration also showed why the projection needs written exceptions: a couple of hundred lines of freshly minted ids, and none left once the ids were normalised by rule. **Not measured:** how often the unguarded tool would actually have lost something. The experiment: run the formatter unguarded over a year of dirty trees in a scratch copy and count the runs the guard would have rejected.
