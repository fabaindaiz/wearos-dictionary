---
bundle: agent-guides
lineage: g-8b5800/main
version: 8
slug: absent-constraint-widens
topic: failure-behaviour
claim: A constraint that is dropped does not raise an error — it returns more, quietly, and every test that asserts on presence still passes.
confidence: measured
reach: architecture, review, debugging
---

# An absent constraint widens

## Why it works

Most defects announce themselves: something is missing, a type is wrong, a call throws. **A lost filter is the opposite shape.** The query still runs, the response is still well formed, the caller still gets rows. There is simply more than there should be, and nothing in the system is in a position to notice — the database was asked a legal question and answered it.

This makes the class invisible to the usual defences. A test asserting "the record I created is in the list" passes with a widened scope. A schema check passes. A type checker passes. The only test that catches it is one asserting on **absence** — that a record belonging to someone else is *not* in the list — and absence assertions are the ones people forget to write, because the happy path never needs them.

And an absence assertion needs something to be absent. **In a fixture world that holds one subject, the widened query returns exactly the rows it should**, so even that test passes: the clause is not unasserted, it is unobservable *in the rows*. A test that asserted on the query itself would still see it, and that is the one kind that does. The fidelity of the test double is not what hides it — a double that evaluates the filter correctly hides it just as well — the single-subject world is.

The same shape appears wherever a narrowing clause can be omitted: a tenant filter, an `active` flag, a date range, a permission check, a `WHERE` on a soft-delete column, a role list that defaults to empty-means-all. In each case the bug produces a superset, and a superset looks like success.

## When it does NOT apply

- **When the constraint is structural rather than a clause** — a foreign key, a view that cannot be queried without its predicate, row-level security enforced by the database. Then omitting it is not expressible, which is the real fix (make the bad state unrepresentable rather than remembered).
- **When more is harmless.** A dropped filter on a list of public help articles is a performance issue, not a correctness one. Spend the effort where the superset crosses a boundary someone cares about: a tenant, a user, a permission, a price.
- **When the caller re-filters anyway** and that re-filter is the actual contract. Then the query is an optimisation, and this note's weight belongs on the caller's filter instead.

## What it costs

Guarding against it means writing the tests nobody asks for — the ones that assert what is *not* returned — and usually adding a mechanism (a scoped repository, a query builder that cannot be constructed without the tenant, a base queryset) that makes the narrowing automatic. That mechanism is real complexity, and on a single-tenant system it buys nothing. The cost is justified by the boundary being crossed, not by the pattern being tidy.

## Where it came from

A multi-tenant service where every query was scoped by the owning tenant, with one documented bypass. The rule was written down and had no automatic enforcement; what made it worth a note was the failure mode stated plainly in the decision that recorded it: a widened scope raises nothing, and quietly returns another tenant's data.

Judgement, unmeasured, at first writing: no incident was observed, and the scoping held everywhere it was checked. Both occurrences since are under *Evidence*.

## Literature

- **[The Protection of Information in Computer Systems](https://www.cs.virginia.edu/~evans/cs551/saltzer/)** — Saltzer & Schroeder, 1975, *Proceedings of the IEEE* 63(9) ([DOI 10.1109/PROC.1975.9939](https://doi.org/10.1109/PROC.1975.9939)). Their fail-safe defaults principle says to *"base access decisions on permission rather than exclusion"* — build from explicit inclusion, so that a mistake in the mechanism denies access rather than granting it.

  **What we take from it:** the same asymmetry, applied to queries rather than to access control lists. A query built as "everything, minus what this clause excludes" fails open when the clause is lost; one built as "nothing, plus what this scope includes" cannot lose it, because losing it returns nothing and somebody notices immediately.

  **Where we go further:** Saltzer and Schroeder are concerned with an attacker. This note is about an ordinary refactor, where nobody is attacking and the clause is dropped by someone simplifying a query they did not fully read. The defence is the same; the threat model is much more common.

## Evidence

**Before 2026-09-22 — none measured.** No cross-tenant leak was observed; the class was recognised from a written guardrail and its stated failure mode, not from an incident.

**2026-09-22 — observed, in a transactional service.** A record belonging to one account refused service to another, whichever owner the matched record had: a scoping constraint absent from a refusal gate's lookup, found as production over-blocking. A second form appeared in the same code: the constraint lived in a projection (a field mask) far from the gate, and widening that projection for performance would have widened the gate. This one is an occurrence, not a measurement — but "no leak observed" is no longer true.

**2026-09-22 — measured, in an analytics repository.** A population bound (rows before a coverage date are excluded) was applied by the code that read the stored dataset but not by the code that built it in memory: the cut lived in one hand-written notebook cell. The two populations differed by **about 9 % of rows**, all entering as failures that never happened, and every test passed. The fix is the general form of this note: **a constraint is enforced at every path that produces rows and every path that reads them**, with a test that builds through each path, asserts the constraint *selects rows* without changing the surviving rows' contents, and covers the unbounded case so it cannot pass by the filter doing nothing. With that number the note is `measured`.

**2026-09-22 — measured, the detection half.** In the same analytics repository, on a clean export of `HEAD`, the per-subject equality filter was deleted from the read that fetches a subject's records. The whole suite was run against the mutated export and against a pristine one: **identical**, test for test, down to one unrelated failure present in both. One fixture row for a second subject was then added, and the mutated code failed at once. The double under test evaluated the filter correctly; what hid the constraint was the world it was given. **No assertion about returned rows can see a scoping clause in a world that holds one subject**, however faithful the double — so a single-subject fixture is not a cheap version of this test, it is no version of it. What remains available there is asserting on the query the code emits, which is a different and weaker test: it pins the clause, not its effect.

What *would* settle it further: seed two tenants, then delete the scoping clause from one repository method and run the existing test suite. If it stays green, that number — tests passing with a deliberately widened query — is the note's evidence, and it is cheap to obtain.
