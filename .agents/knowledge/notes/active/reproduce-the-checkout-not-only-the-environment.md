---
bundle: agent-guides
lineage: g-8b5800/main
version: 4
slug: reproduce-the-checkout-not-only-the-environment
topic: verification
claim: A number from a check is a function of the commit, the environment and the checkout; reproducing it elsewhere means reproducing all three — pin the environment, and run from a clean export of the commit, because the working tree holds every untracked file the real runner will not have.
confidence: measured
reach: debugging, review, verification
---

# Reproduce the checkout, not only the environment

## Why it works

A check that runs somewhere else has two inputs, and only one of them is ever discussed. The **environment** — interpreter, pinned dependencies, stubs, operating system — is declared, visible and the first thing blamed when two runs disagree, and reproducing it is real work that people do. The **checkout** is the other input, and it is invisible for the same reason a fish does not discuss water: you are standing in it.

The difference between the two is exactly the set of files nobody declared. Credentials, caches, local datasets, editor state, generated artefacts, a stale virtual environment, the output of the last experiment — every one of them is untracked or ignored, which is another way of saying that the remote runner will not have it. A check that reads one of them gives an answer locally for a reason that does not exist there, and the answer looks like every other answer.

It fails in both directions, and the permissive one is worse. A replica built in the tree has **more** than the real runner, so a check passes because something is present; the report says green, the remote gate says red, and the difference is a file nobody thought of as an input. The strict direction — a leftover file the runner will not have making a check fail — at least announces itself.

The fix is cheap enough that there is no reason to discuss it: export the commit into a scratch directory (`git archive HEAD | tar -x -C …` or an equivalent), and run the replica there. That is seconds, and it is the one operation that reproduces the checkout exactly, because it reproduces it by definition — what is in the commit, and nothing else.

The environment has its own quiet forms. Two environments on one machine — a stale one earlier on the path than the declared one — produce two different truths with the same commands, so when two measurements disagree, **identify which binary produced each before believing either**, and invoke tools through the project's own interpreter rather than whatever the name resolves to. And any number whose computation orders, formats or compares text is a number about the locale too: fix the collation (`LC_ALL=C`) in every recipe that publishes one.

## When it does NOT apply

- **A hermetic build system already does this**, by declaring every input and sandboxing every action (see *Literature*). Where the tool guarantees that the host and the tree cannot leak in, reproducing the checkout by hand is repeating work that is already done.
- **When the artefact under test is the working tree** — a formatter over uncommitted work, a check on staged changes, a pre-commit hook. The tree is the subject, not the contamination.
- **When the untracked file is the point**: reproducing someone's local state to debug their failure. Then the export is the wrong direction, and what is worth writing down is which file made the difference.

## What it costs

An export and a second install: seconds to minutes, once per investigation. And a sentence in the report saying which inputs were reproduced — because "I reproduced CI" is three claims, and it is usually only one of them.

## Where it came from

One analytics repository, whose remote gate counts type errors against a baseline and runs a suite, measured on **2026-09-22** in two halves that arrived a week apart.

**The environment half.** With the remote logs unavailable, the gate's environment was rebuilt locally to reproduce a count: a replica built without the package installer reported **nearly three times as many errors** as the same replica built with it. Reproducing the environment was necessary, it was what the exercise was about, and it worked — the number that had to be explained moved by more than half on a difference nobody would have called an input.

**The checkout half, which is the one that names this note.** A clean export of `HEAD` **fails one test** that the same suite passes in the working tree, because that test builds a real store client from an ignored credential file which exists only there. The remote gate runs on a checkout and installs no credential. The last replica of that gate had been run **inside the tree**, and had reported the suite green: the environment had been reproduced with care, and the answer was still about a machine nobody else has.

**The same environment half, earlier, in the same repository** (moved 2026-09-24 from `ratchet-in-a-pinned-environment`, where it used to be counted). A stale environment first on the path explained three separately recorded "anomalies" at once: parallel tests "not working" (the plugin was absent there), a few more type errors with the same checker and library versions, and a directory of **dozens of tests that did not collect** because a declared dependency was missing.

**The locale, in a second repository.** A content digest computed over files ordered with the system `sort` depended on the locale: in `en_US` collation the hyphen is skipped, so the same unchanged content hashed to `fdc2ae7e7d36` there and to `2fa14bd0c1e5` in byte order.

## Literature

- **Bazel, [*Hermeticity*](https://bazel.build/basics/hermeticity).** "When given the same input source code and product configuration, a hermetic build system always returns the same output by isolating the build from changes to the host system"; hermetic builds "are insensitive to libraries and other software installed on the local or remote host machine", tools are treated as source code, and among the named non-hermetic behaviours is "writing to the source tree during the build". *Verified 2026-09-22 against the page.* **What we take:** the framing that the inputs of a check include things nobody wrote down. **Where we go further:** the literature isolates the build from the **host**; this note is about isolating it from the **tree** — files that are there because of your own history, which no declaration mentions and which a hermetic system would never see in the first place. For a project without such a system, an export of the commit is the poor version of the same isolation, and it costs seconds.
- **reproducible-builds.org, ["Locales"](https://reproducible-builds.org/docs/locales/).** "The C locale will sort according to the byte values and is always available." *Verified 2026-09-22 against the page.* **What we take:** pin the collation like any other tool.

## Evidence

**Measured in two repositories.** In the first, three mechanisms: an error count nearly three times higher, produced by a missing installer in the replica's environment; a stale environment first on the path that produced three "anomalies"; and one failing test against none, produced by an ignored credential file in the replica's tree. In the second, one content digest with two values from the locale alone. All were found while trying to explain a number that two runs reported differently.

**Not measured:** how often it happens per gate. The experiments: record the interpreter path next to every number written into a document, and count how many disagreements turn out to be environmental; and for every check in a gate, run it once from a clean export of the commit and once in the working tree, and count the checks whose answers disagree. Each disagreement names a file that is an input and was never declared to be one.
