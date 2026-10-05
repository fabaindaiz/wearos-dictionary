---
# bundle-proposal: a change this repository offers to the agent-guides bundle. Only the home repository integrates it; nothing here is guidance.
proposal: p-a9ede7e057
bundle: agent-guides
carrier: r-a2f271
base: 0.0.26
digest: 8ef533bce57d
kind: knowledge
action: extends
target: absence-is-a-third-value
lacks: a second repository; the instrumentation literature checked against its source
seen: "2026-10-01"
---

A per-reason counter that writes only the buckets that fired cannot show a rule that stopped firing: absent is not zero. Declare every reason up front and write zeros.

## Evidence

One repository: a per-rule drop ledger written into each built artefact listed only the rules that had dropped something, so the case it existed for, a rule silently no longer firing, showed nothing. Found by building a sample and reading its metadata, not by a test. Literature: metrics-instrumentation practice of initialising every label to zero so a series exists before its first event; ASSUMPTION, not checked against the source.
