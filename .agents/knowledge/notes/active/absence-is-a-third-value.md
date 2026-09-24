---
bundle: agent-guides
lineage: g-8b5800/main
version: 7
slug: absence-is-a-third-value
topic: failure-behaviour
claim: Keep "absent" distinct from false and from empty, and make sure the query engine and the code agree on what a missing field means.
confidence: reasoned
reach: architecture, review, debugging
---

# Absence is a third value

## Why it works

A boolean read from a schemaless store, an optional API field or a sparse log has three states: true, false, and never written. Code collapses the third into one of the others by default — `get(field, False)`, `if not doc.get(field)` — and the collapse is invisible until the distinction matters:

- **It destroys denominators.** "What share of users opted in?" needs the users for whom the question was answered; counting "not recorded" as "no" deflates the rate.
- **The engine and the code disagree.** A query filtering `field == false` skips documents where the field is missing, while the application reads missing as false — or, worse, as the dangerous value. Whatever is left implicit fails open on one side.
- **"No document" and "document without these fields" become the same empty value**, and code that only means to tolerate missing fields also tolerates a missing record — then writes derived state for an entity that never existed.
- **A log that records only one outcome** implies the others do not happen.

Keeping absence explicit — an optional type, a tri-state, an explicit `false` written at creation, a separate "not found" — makes the question "what does missing mean here?" answered once, on purpose.

## When it does NOT apply

Fields required by the schema at write time. That is the real fix (make absence impossible), and where it is available the tri-state is unnecessary.

It does **not** stop at fields. A reference whose target has gone is a third state between "present" and "empty by choice", and collapsing it into either lies: shown as empty it hides what was lost, dropped silently it cannot be seen or removed. Keep with each reference the last label seen for its target, so a dangling one can still be displayed and taken out.

## What it costs

Optional or tri-state types wherever a value crosses a boundary; writing `false` explicitly; backfilling existing records so query filters and code agree.

## Where it came from

A transactional service: a client-reported flag kept three-valued on purpose, because collapsing absent into false would have deflated the rate it fed; an equality filter that skipped documents missing a status flag while the rest of the code read missing as the restrictive value, so leaving the meaning implicit failed open on one side; a read option that returned "no document" and "document without these fields" as the same empty value, so a refused registration wrote derived state for an account that never existed; and an access history that recorded only one kind of refusal, and so implied that only that kind ever happened.

A client application that keeps users' lists on the device met the reference form. An empty list and a list whose items the build no longer has are two different silences: a menu with no entries because content is missing cannot be told from a menu that failed to load, so the first says in words that it is empty and the second falls back to the whole library. An ordering that does not contain the current item is not an order, and falls back too. Later each list kept the last name seen for every id, so an item whose file was deleted still shows, dimmed, named and removable — checked with a probe: the missing row drawn dimmed, its name surviving a reload, the order holding the one item that remains.

## Literature

SQL's `NULL` and three-valued logic are the standard statement that unknown is not false. No specific paper known on the query-engine / application disagreement. **Sibling in this base:** `absent-constraint-widens` — there absence widens a query; here absence changes a meaning.

## Evidence

**Reasoned, from four occurrences in one repository and three in another** (the reference form, observed with a probe but not counted). What would measure it: for each boolean in a schemaless store, count documents where it is missing, and compare what the query layer and the application each conclude for them.
