---
bundle: agent-guides
lineage: g-8b5800/main
version: 3
slug: sanitised-value-must-replace-the-raw
topic: failure-behaviour
claim: Clamping, defaulting or validating a value into a new name leaves both in scope, and the raw one keeps being used wherever it already was.
confidence: reasoned
reach: implementation, review
---

# A sanitised value must replace the raw one

## Why it works

The sanitising line reads as the fix — it is where the author's attention was, and it is what a reviewer's eye lands on. But validation only protects the uses that reference its result, and the uses that were written *first* reference the original. The code contains its own correct answer and ignores it.

This is invisible in review for a mechanical reason: the file contains the words that prove the case was handled. A reviewer checking "is the window size validated?" finds the line that clamps it to at least one and moves on, because the question they asked has been answered. Nothing prompts the second question — *and is that the one that gets used?*

The characteristic shapes:

- **Constructor clamps, the field is built from the argument.** The clamped size is stored on the object, and the buffer beside it is sized from the raw parameter.
- **A guard computes a safe default and the caller re-reads the config.**
- **Normalisation returns a value the caller discards**, keeping the input it already had.

The defence is structural rather than attentional: **shadow the name**. If the sanitised value is bound to the same name, there is no raw value left to use by accident. Where the language will not allow that, the raw value should be consumed immediately and not be in scope afterwards.

## When it does NOT apply

When both values are genuinely needed — keeping the original for an error message or an audit record is a real requirement. The rule then is that the original is named for what it is (`requested_size`, `raw_input`) so that using it is a decision rather than a default.

## What it costs

Nothing at write time. At review time it costs a second question that does not come naturally, which is why the structural fix is preferred over remembering to ask it.

## Where it came from

A hardware-bound service: a smoothing filter sanitised its window size and then built its buffer from the raw argument, so a non-positive configured value made the filter never accumulate — or raise — while the code visibly contained a guard against exactly that.

## Literature

- **King, 2019, ["Parse, don't validate"](https://lexi-lambda.github.io/blog/2019/11/05/parse-don-t-validate/).** A check that returns nothing "just throws it away", while a parser returns a refined value the caller must use; "because its return value is unused, it can always be omitted, and the code that needs it would still typecheck." *Verified 2026-09-24 against the post.* **What we take:** a check protects only the uses that are forced to go through its result. **Where we go further:** the case where the check *does* return a sanitised value and the code keeps reading the raw one anyway; shadowing the name is the fix available where the language cannot carry the refinement in a type.
- **Momot, Bratus, Hallberg & Patterson, 2016, ["The Seven Turrets of Babel: A Taxonomy of LangSec Errors and How to Expunge Them"](https://ieeexplore.ieee.org/document/7839788/)** (IEEE SecDev). Shotgun parsing: checks spread across processing code instead of held at one boundary. *Bibliographic record verified 2026-09-24; the definition was checked in the form King quotes it, and the paper itself was not opened.* **What we take:** a check placed away from the uses is a named class of weakness, not a style issue.

## Evidence

**Reasoned: one occurrence.** What would measure it: a lint rule that flags a sanitised binding whose source name is still read afterwards in the same scope, run across a repository to count how often the raw name survives.
