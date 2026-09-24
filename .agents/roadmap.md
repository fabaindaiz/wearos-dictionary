---
# bundle-header — provenance for this document. Identical across the bundle.
bundle:    agent-guides
lineage:   g-8b5800/main
ancestry:  [g-c7344c, g-099a8a, g-8b5800]
version:   14
component: roadmap
released:  2026-09-24
---

# Roadmap — how this bundle itself improves

**What is planned for the method and the knowledge base, and what each item will collide
with.** Not a promise: a ledger of accepted work on the bundle, in the same five states the
method uses for a repository's roadmap. Work on a *repository* goes in that repository's
roadmap; this file is only about `.agents/` itself.

Newest first within each state. Each item names its **collision** — what it will touch that
something else depends on — because that is what decides its order.

| State | Means |
|---|---|
| **Next** | accepted, and nothing blocks it |
| **Later** | accepted, waiting on something named |
| **Blocked outside** | depends on a decision or a party outside the bundle |
| **Closed by measurement** | was proposed, and a number said no — kept so it is not proposed again |
| **Done** | shipped in a named version; kept one release, then removed |

## Next

- **`i-5ed7e8-973bd7` · Run the cheapest queued experiments** in `tracking/experiments.md`, one
  per note that has none. *Collides with:* the notes' `confidence`, which moves in both directions.
- **`i-5ed7e8-705aa8` · Clean up the duplicated prose that remains in the method.** The worked
  examples are still long, and a few rules are still restated in more than one prompt. *Collides
  with:* the `Reads:` lists, which name exact headings.
- **`i-5ed7e8-13a8be` · Keep the private-terms list of each machine complete.** `bundle.py
  privacy` checks the names in `~/.config/agent-guides/private-terms.txt`, which never travels; a
  private name missing from it is caught only by the generic rules. Each machine's owner adds the
  names of private repositories, organisations, products and people. *Collides with:* nothing in
  the bundle; it is the one list that must never enter it.

## Later

- **`i-5ed7e8-aca224` · A host-side record-id and renumbering check in every carrier's audit.**
  `bundle.py ids` checks record ids for format, prefix and duplicates; each carrier's audit should
  call it over its own records, and check that principles, artifacts and phases are never
  renumbered. *Waits on:* one carrier adopting it first.

## Blocked outside

- **`i-5ed7e8-b83f16` · Offer the current release to the carriers not reached.** They are listed
  in `tracking/carriers.md` without ids. Each receives it through its `incoming/`, mints its own
  random id, and keeps its repository fields. *Blocked on:* a session with those repositories open.

## Closed by measurement

None yet.

## Done

Only the last release is kept here; every earlier one is a row of `method/changelog.md`.

- **Bundle v20 (method v24, knowledge v11), 2026-09-24: privacy is enforced, not remembered.**
  Nothing in the bundle may identify, directly or by reconstruction, a private repository, its
  owners, customers or infrastructure, or any person who uses it. Every file was scrubbed:
  figures became ratios or orders of magnitude, quotes were paraphrased, code, schema and product
  names became roles, and location and personal context went. Changelogs and history were not
  exempt. Carrier ids are random and stored in each carrier, because a hash of a guessable remote
  had been reversed; the unreached carriers' old ids were withdrawn. `bundle.py privacy` runs
  generic rules and a local list of private terms, inside `digest --check`, in a hook that blocks
  commits in the home repository, and in CI. An exception needs the user's instruction and is
  listed on every run. Principle 20 states the rule and every invocation repeats it. The four
  notes under review got their verdicts, and the six overlapping pairs were resolved: two merges,
  one split, three kept with transfers. `bundle.py ids` checks record ids.
