---
bundle: agent-guides
lineage: g-8b5800/main
version: 8
slug: merge-by-shared-fact-not-shared-shape
topic: evolving-contracts
claim: Merge copies that must agree by contract and whose divergence would be invisible; keep copies that only look alike, especially where merging would hide an error from a symmetric test, and write down why they stay.
confidence: reasoned
reach: review, implementation, architecture
---

# Merge by shared fact, not by shared shape

## Why it works

Two kinds of duplication look the same in a diff. In the first, the copies encode **one fact** — how an object is torn down, how a description becomes objects, which numbers a file carries — and they must agree; any divergence is a bug, and nothing notices it, because each copy works on its own. In the second, the copies only share **a shape** — a loop, a search, a guard — while their semantics differ. Merging the first kind removes a class of silent bug. Merging the second produces a function with modes and parameters that reads worse than either copy, and can do worse: when the copies are tested by a check that is symmetric in them, a shared defect moves both the same way and the check stays green.

So the question is not "is this code repeated?" but "is this knowledge repeated?". Only the answer to the second decides.

## When it does NOT apply

- **When you cannot yet tell fact from shape.** Two occurrences rarely show which; wait for the third, and meanwhile keep the copies and a note.
- **When one copy is generated from the other** — then there is only one source, and the duplication is output.

## What it costs

A judgement at every copy, and a written record for each copy kept on purpose — or the next reader "cleans it up". Merging a fact can force an interface that spans layers; that is sometimes the real cost.

## Where it came from

One repository, an interactive renderer. Merged, because the copies were one fact:
- four ways to leave the running content were four blocks that differed without anyone having decided it — one never discarded a view, only one reset a clock; unified, a live-object counter returns to the same value over repeated cycles;
- three copies of content assembly, one of which was silently left behind when an input form was retired;
- a packer and a metadata writer made to share their functions because keeping them apart had already failed once: one wrote a cell size the sheet did not have;
- an animation's clip and facing read from its samples rather than stored separately, because two records of one fact drift apart.

Kept, and recorded as a decision rather than a backlog item: four small binary searches whose return values mean different things, where the two-direction purity test could not catch an off-by-one because it would shift both directions equally; two placement routines that differ in half a dozen respects, where a many-parameter helper with two callables would read worse than either copy; and a few identical lines per file in widgets that cannot share a parent class under single inheritance.

## Literature

- **Thomas & Hunt, *The Pragmatic Programmer*, 20th anniversary ed. (2019), [Tip 15, DRY](https://pragprog.com/tips/):** "Every piece of knowledge must have a single, unambiguous, authoritative representation within a system." *Verified 2026-09-22 against the publisher's tips page.* **What we take:** the unit is knowledge, not text.
- **Metz, 2016, ["The Wrong Abstraction"](https://sandimetz.com/blog/2016/1/20/the-wrong-abstraction):** "duplication is far cheaper than the wrong abstraction". *Verified 2026-09-22 against the blog post.* **What we take:** the cost of merging shape. **Where we go further:** the symmetric-test criterion, which says when a merge also blinds a check.
- **Sibling in this base:** `same-answer-or-refuse` records the binary-search case as a boundary of equivalence testing.

## Evidence

**Reasoned, from seven occurrences** (four merges, three kept). The object counter and the cell-size mismatch are observations, not a rate. The experiment: over one repository's history, classify every duplication removed or kept as fact or shape, and count the regressions that followed each kind.
