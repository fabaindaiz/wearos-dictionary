---
bundle: agent-guides
lineage: g-8b5800/main
version: 7
slug: report-coverage-before-findings
topic: measurement
claim: Zero findings and zero coverage read identically and mean opposite things; every report states how much of its input it could evaluate before stating what it found.
confidence: measured
reach: review, debugging
---

# Report coverage before findings

## Why it works

A scan, an audit, a reconciliation or a data-quality report that finds nothing prints the same line whether it examined everything or nothing. The second happens easily and silently: a misspelled field in a projection returns documents without the column, a filter excludes the whole input, a lookup falls through to a default. The report is then "clean" in exactly the cases where it is blind.

Putting coverage first — *evaluated N of M; found K* — makes blindness visible in the same line as the result. A report with no coverage number cannot be distinguished from a broken one, so it should not be trusted as a clean one.

The same blindness has a structural form: **a check whose inputs are a hand-written list covers the day the list was written.** Everything added afterwards is outside it, and the report still says "ok". Find the population from the tree — every file of the kind, every item that exists — and have the tool name what it could not evaluate rather than skip it.

**The gate form: fail on zero.** A check whose subjects are found rather than listed can still go blind with no change to itself, because its subjects disappear. A folder it scans is legitimately retired and it returns early; a marker it searches for is translated or renamed; a pattern it matches is loose enough to match the artefact that means *not yet*. It keeps reporting success over nothing, and the edit that blinded it was a correct piece of maintenance, not a regression, so no review looks at the check. The guard is one line and belongs in every such check: **count the subjects, and fail on zero** unless zero is a legitimate state that the check names. It is the coverage line turned into a gate; whether the check could go red at all is `a-check-must-be-seen-to-fail`.

A related trap lives in defaults: a lookup that returns a neutral value for an unknown key (a weight of one) counts every undeclared label as if it had been declared. The coverage question — how many labels were actually known — is the one that exposes it.

## When it does NOT apply

Reports whose input schema is enforced upstream with presence guarantees, so that "evaluable" is true by construction. Even there the line is cheap.

## What it costs

One coverage line per report, and field names used in projections and lookups kept as checked constants rather than string literals.

## Where it came from

A projection field misspelled by one letter. The store returned complete documents without the column: **not one appearance in hundreds of megabytes**, and a report that found nothing. With the name fixed, **about three quarters of rows** had the field. A covered count added to the report is what made two later blind runs read as blind rather than clean. Separately, a value estimate used a lookup whose default for an unknown label was a weight of one, and counted undeclared labels in full.

A second repository met the list form. Its determinism check named only the pieces that existed when it was written — **under a third** of them by the time it was looked at; the ones added later, precisely those simulated at load, which the check exists for, were in no list. Found rather than listed, it covers all of them. Its document-path check covered only the documents the root file named, after a manual sweep had missed a dead pointer several times; made to find every document, it found **about 1 % of its hundreds of paths missing** on its first run. A content validator returned early for one kind of element and so skipped every check after it — that kind's assets were checked by nobody until a code review read the early return; with it removed, a planted missing asset of that kind fails; earlier, a renamed key had left rows naming an asset that did not exist, and the gate had not noticed. A regeneration mode reported success without looking at inputs of a kind it did not handle, and a formatter wrapper said there was nothing to format in a file it could not parse; both now name what they skipped and fail.

A third repository — an offline-search application with a packaging toolchain — met the zero form three times (moved 2026-09-24 from `a-check-must-be-seen-to-fail`). The enforcer of a method digest opened a folder and began with *return if the folder is absent*; retiring that folder for another would have left the audit at 0 failures over 65 unchecked files, and the same pass found it had been checking 4 files where 65 travel. Translating eight mirror-declaration markers took their check to 0 matches; it failed loudly only because an earlier session had written *fail if nothing was found*, without which the gate would have gone green over 8 unchecked mirror pairs.

## Literature

None known. It is the empty-set mirror of `absent-constraint-widens`: there a lost constraint returns a superset that looks like success; here a lost field returns an empty set that looks like a clean bill of health.

## Evidence

**Measured in three repositories:** in the first, no appearance of the field before the fix and about three quarters of rows after; in the second, a listed check covering under a third of its inputs, and about 1 % dead paths on the first run of a found one; in the third, an audit that would have passed over 65 unchecked files and a marker check at 0 matches over 8 pairs, each stopped only by a fail-on-zero guard or the lack of one. What would generalise it: add a coverage line to every existing report and count how many of them turn out, on first run, to have been evaluating less than they claimed.
