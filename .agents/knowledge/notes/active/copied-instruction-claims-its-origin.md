---
bundle: agent-guides
lineage: g-8b5800/main
version: 4
slug: copied-instruction-claims-its-origin
topic: evolving-contracts
claim: An instruction file copied from another repository keeps asserting facts about that repository — its stack, its commands, its layout — and it is read as if it described this one.
confidence: reasoned
reach: planning, review
---

# A copied instruction claims its origin

## Why it works

Instruction files are copied precisely because writing one is expensive, and the copy is made at the moment somebody wants the *shape*, not the content. The shape is what transfers; the content is what is read later, by an agent that has no way to tell which sentences were about somewhere else.

It is worse than stale documentation in one specific way. Stale documentation was true here once, so its claims are at least the right *kind* of claim. A copied file may assert a build command that never existed here, a directory that does not exist, or a guardrail protecting an invariant this system does not have — and each of those reads exactly like a fact somebody established.

The failure is silent by construction:

- **It is confident.** Nothing in the prose marks which parts were inherited.
- **It is load-bearing.** Instruction files are consulted before acting, so a wrong one steers work before anyone checks it.
- **It survives review.** A reviewer who knows the repository skims a file that looks like the one they remember writing.

The defence is to treat a copy as *data until verified*: every command run once, every path resolved, every guardrail matched against something that actually exists here. The parts that cannot be verified are removed, not softened — a sentence nobody can check is a sentence that will be believed.

## When it does NOT apply

A bundle explicitly designed to travel, whose repository-specific fields are enumerated and whose content is written without local nouns — the whole point of separating what is portable from what is local. Even then, the fields that describe *this* repository are the ones a copy must never take from upstream.

## What it costs

Running every command and resolving every path in a file somebody already wrote, which feels like redoing finished work and is the reason it gets skipped.

## Where it came from

A hardware-bound service found, during a documentation audit, that one of its language-specific instruction files had been copied from another repository — it had been shipping instructions for a different project — and corrected it. A second repository in the same workspace had a set of rule files and a prompt file cloned from a sibling; the fields that described the sibling were only noticed when a distribution pass compared them.

## Literature

The closest established work is on documentation drift, which is a weaker claim: drift starts true and decays, while a copy starts false. No source was found on copied instruction files specifically.

- **[Aghajani et al., 2019, "Software Documentation Issues Unveiled"](https://doi.org/10.1109/ICSE.2019.00122)** (ICSE). "Up-to-dateness problems account for 39% of issues related to documentation content"; an outdated document is one "not in sync with other parts of a system" whose information "was correct and complete before a change was introduced." *Verified 2026-09-23 against the paper.* **What we take:** that content going false is the dominant documentation defect, not a corner case. **Where we go further:** their definition assumes the text was once correct here; a copy never was, so no change in this repository marks the moment it went wrong.
- **[Lethbridge, Singer & Forward, 2003, "How Software Engineers Use Documentation"](https://doi.org/10.1109/MS.2003.1241364)** (IEEE Software). Out-of-date documentation "has value, particularly if the high-level abstractions remain valid." *Verified 2026-09-23 against the paper.* **Where we differ:** that tolerance rests on the abstractions having been true of this system; a copied file's abstractions describe another one, which is why this note removes unverifiable sentences rather than keeping them as approximately right.

## Evidence

**Reasoned: two occurrences in one workspace, no rate.** What would measure it: for each instruction file in a set of repositories, run every command it names and resolve every path, and count the assertions that fail per file — separating "was never true here" from "stopped being true".
