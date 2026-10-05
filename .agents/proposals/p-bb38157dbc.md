---
# bundle-proposal: a change this repository offers to the agent-guides bundle. Only the home repository integrates it; nothing here is guidance.
proposal: p-bb38157dbc
bundle: agent-guides
carrier: r-a2f271
base: 0.0.26
digest: 8ef533bce57d
kind: method
action: overlaps
target: a-filtered-gate-cannot-block
lacks: "refused: already admitted; occurrence only"
seen: "2026-09-25"
---

A gate read through a pipe reported success over a failed build, and a name-filtered test run was green with none of the new tests run.

## Evidence

One repository, two occurrences in the period; caught by reading the exit status and the run count.
