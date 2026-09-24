---
# bundle-header — provenance for this document. Identical across the bundle.
bundle:    agent-guides
lineage:   g-8b5800/main
ancestry:  [g-c7344c, g-099a8a, g-8b5800]
version:   3
component: tracking
released:  2026-09-24
---

# Retired — notes and rules that evidence removed

**A note or method rule that was withdrawn or superseded, with the evidence that removed it.**
Kept, one line each and in the order they happened, so that a refuted idea is recognised when it
is proposed again. A retired note's file is kept in `../knowledge/notes/retired/` — moved there
with `bundle.py note-state SLUG retired` — with `retired_because:` and, when replaced,
`superseded_by:` in its frontmatter; it leaves the tables of `../knowledge/INDEX.md` and its area
index. Each line here points at that file. The procedure is in `../knowledge/README.md`, *The
lifecycle of a note*.

| Date | Note | Withdrawn or superseded | Evidence that removed it | Replaced by |
|---|---|---|---|---|
| 2026-09-24 | [store-choices-not-defaults](../knowledge/notes/retired/store-choices-not-defaults.md) | superseded | it was the application of its successor to preferences (a stored default is a frozen verdict); its occurrence, citation and experiment moved there | persist-inputs-derive-verdicts |
| 2026-09-24 | [one-path-for-every-source](../knowledge/notes/retired/one-path-for-every-source.md) | superseded | its claim is the first remedy of its successor (remove the second path, else hold it to exact agreement); its measured figure showed equivalence at unification, not drift, and its two drift occurrences moved there | same-answer-or-refuse |
