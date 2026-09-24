---
bundle: agent-guides
lineage: g-8b5800/main
version: 9
slug: ratchet-in-a-pinned-environment
topic: verification
claim: Introduce a rule against an existing backlog as a ratchet that can only go down — violations listed inside the check, or an advisory with a written promotion condition — never as zero and never by disabling the rule; and pin the tools that produce the count.
confidence: measured
reach: planning, debugging, review
---

# Ratchet in a pinned environment

## Why it works

A check that is red on the day it is introduced gets switched off, so a codebase with existing errors cannot adopt "zero" as its gate. A baseline can: the gate fails when the count grows, never when it stays, and the number is lowered in the same change that fixes errors. It can only go down.

The same shape works for any rule introduced against a backlog, not only a type checker: **list or count the existing violations inside the check**, so they are recorded rather than forgiven and the next one still fails; or ship the rule as an advisory **with a written promotion condition** ("becomes a failure when the count reaches zero"). What never works is forgiving by disabling the rule — that removes the check for the next violation too. Anchor a grandfathered entry to something stable: a `file:line` anchor drifts on any edit above it.

A baseline is only meaningful if the count is reproducible, and a count is produced by a toolchain. An unpinned checker, or unpinned type stubs, change the count with no code change; the gate goes red in one place and stays green in another, and the first instinct — raise the baseline — quietly gives back what the ratchet held.

That a count depends on more than the toolchain — on the interpreter that runs it, on files in the working tree, on the locale — is `reproduce-the-checkout-not-only-the-environment`; this note needs only the part a baseline cannot live without: the counting tools are pinned.

## When it does NOT apply

A greenfield codebase that can hold zero from day one; there, zero with strict settings is cheaper than a baseline. Hermetic builds (containers, lockfile-driven or Nix-style environments) already pin resolution, and the pinning half of the note is paid for.

A backlog small enough to clear in the same change should be cleared, not ratcheted. A rule with real legitimate exceptions needs an exemption mechanism, not a list of violations.

## What it costs

Pins must be bumped deliberately, in the change that re-measures and adjusts the baseline. Choosing one environment as the one numbers come from means knowingly living with a gap to the others — here a known one-error difference with CI. An advisory is not a gate: new violations of an advisory rule land silently until someone reads the output. Line anchors need re-stamping.

## Where it came from

A typecheck ratchet on a data-science codebase. A new release of the dataframe library's type stubs added **a few errors to untouched code**; CI went red while every local shell was green, and pinning the checker and that one stub package was measured to be sufficient. Later, the main branch measured **one above its declared baseline** over a clean export: the baseline had come from a CI replica on another interpreter version, and the gate had been failing on main unseen.

A second repository, a transactional service, applied the backlog form: two pre-existing destructive store calls are grandfathered **by location, listed inside the audit** so a third still fails; about ten comment-rule violations ship as an advisory promoted when the count reaches zero; a dangerous decision (dozens of exact quantities typed as binary floats) is counted on every run so the number is visible when it is revisited. Its structural audit failed **about a dozen times on its first run, a third of them the script's own fault** — the reason a rule that cries wolf on arrival gets switched off. Leaving tests advisory elsewhere let **several failures sit unnoticed across two commits**. And the anchor boundary was measured: an unreviewed repo-wide reformat moved suppression pragmas off the lines they covered, and type errors **rose by about 2 %** with no semantic change.

## Literature

Baseline files are settled tool practice — static analysers such as PHPStan, Psalm and detekt ship a baseline mechanism. No paper we are confident of. **What we take:** the mechanism. **What we add:** the baseline is only as good as the pinning of the toolchain that counts it.

## Evidence

**Measured in two repositories:** in the first, a stub release that moved the count with no code change, and a baseline one short of main's own count; in the second, a first-run audit a third of whose failures were its own, advisory failures that went unnoticed, and an anchor drift of about 2 % from a reformat. The environment measurements that used to be counted here (a stale environment on the path, a locale-dependent digest) are counted once, in `reproduce-the-checkout-not-only-the-environment` (moved 2026-09-24). What would settle the backlog half more generally: across repositories that adopted a rule against a backlog, count how many adopted it at zero and later disabled it, against how many ratcheted it and still have it on.
