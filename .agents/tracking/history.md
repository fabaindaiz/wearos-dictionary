---
# bundle-header — provenance for this document. Identical across the bundle.
bundle:    agent-guides
lineage:   g-8b5800/main
ancestry:  [g-c7344c, g-099a8a, g-8b5800]
version:   1
component: tracking
released:  2026-09-24
---

# History — where each candidate went when it left the queue

Newest first. One section per release or harvest; each row says where the candidate went, so it
is not offered again as a fresh idea. The queue itself is `candidates.md`.

## Taken out of the queue by the release of 2026-09-24

| Candidate | Where it went |
|---|---|
| `parallel-session-id-allocation` | **answered by the method**: records written by parallel sessions (decisions, roadmap items, session entries) take `<kind>-<repo6>-<content6>` ids minted by `bundle.py id`, so no session needs to see another's numbers. Its evidence — a parallel session's rows already held nine ids — is the occurrence the change cites |

## Taken out of the queue by the release of 2026-09-23

Nine candidates left this table in that release, and two were merged into one row of `candidates.md`.

| Candidate | Where it went |
|---|---|
| `correction-lands-where-the-rule-is-enforced` | **admitted into the method**, principle 6: a retraction reaches the copy that is loaded and a superseded decision's own row. The second repository was the one it waited for, and it extended the claim from guardrails to decision records |
| `a-safe-degrade-must-know-whether-its-target-should-exist`, `a-check-whose-subject-is-prose-is-one-translation-from-vacuous`, `a-completion-check-states-its-negative` | **folded** into `a-check-must-be-seen-to-fail` as a named form, *a check over zero subjects*, with all three measured occurrences. A new occurrence of an existing claim extends its note |
| `incoming-cannot-be-checked-empty-while-the-bundle-ships-a-file-into-it` | **fixed**: the rule is now *empty except its own `README.md`*, in `incoming/README.md` and `prompt-update.md` |
| `the-set-declares-a-version-the-changelog-does-not-reach` | **fixed**: Method changelog rows 17–21 written from the bundle roadmap's *Done* entries; row 17 says it is a number nobody holds |
| `a-principle-can-have-no-subject-in-a-carrier` | **already in the method**: the triage verdict *Does not apply* is exactly this. What was missing was one sentence — it is not `declined`, and it is re-triaged the day the subject appears — now in `prompt-update.md` §*The triage* |
| `skills-have-two-declared-homes-in-one-bundle` | **fixed** in `layout.md`: a repository served by one assistant keeps one skills folder, the one that assistant loads; `.agents/skills/` is the source only when several assistants read it |

## Taken out of the queue by the release of 2026-09-22

Six candidates left this table in that release. Where each one went, so that a later harvest
offering it again can see it was answered rather than lost.

| Candidate | Where it went |
|---|---|
| `a-replica-inherits-the-tree-it-is-built-in` + `rebuild-ci-to-reproduce-ci` | **admitted, as one note**: `reproduce-the-checkout-not-only-the-environment`. The first was the second occurrence the second had been waiting for, and both are measured |
| `default-scope-is-the-widest-one` | **admitted**: `a-default-scope-is-the-widest-one`. Two measured occurrences in one tool, two different mechanisms; the boundary says so |
| `a-constraint-needs-a-second-subject-to-be-visible` | **folded** into `absent-constraint-widens` as a boundary: a new boundary of a claim extends its note |
| `a-cap-that-hands-off-is-not-a-cap-that-exempts` | **folded** into `abuser-controlled-exemption` as a boundary. It keeps its open half — what the human queue behind the hand-off is worth — which is `detect-only-control-is-its-queue`, still a candidate |
| `assert-the-reason-not-only-the-outcome` | **folded into the method**, principle 18: a mutation test asserts the *reason* a check reported, not only which item it named |
| `fingerprint-fixes-its-collation` | **discarded**: already in `ratchet-in-a-pinned-environment`, with both of its values. Two carriers reached that verdict independently in the same harvest |

## Refused by the harvest of 2026-09-22, second pass

Offered by the record of that period and not written as candidates, with the reason, so the
next harvest does not offer them again with the same gap.

- **A fingerprint whose file order depends on the locale is not a fingerprint.** Already the
  third repository's evidence in `ratchet-in-a-pinned-environment`, with both of its values.
  Nothing here adds to it.
- **A version number stamped by a run that then failed is spent, and is never reissued.**
  Arrived with the release that records it; the run happened in the carrier the release was
  authored in, not here. Counting it here would be the same occurrence twice. It stays with
  that carrier's harvest, and it is close enough to `parallel-session-id-allocation` that the
  two may be one claim about monotone ids.
- **A digest over an empty input returns a well-formed value indistinguishable from an
  answer.** Same reason: it is in the release's own record, not in this repository's. Worth
  offering by the carrier that met it, where it would extend `report-coverage-before-findings`.
