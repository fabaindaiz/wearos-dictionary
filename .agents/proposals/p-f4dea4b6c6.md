---
# bundle-proposal: a change this repository offers to the agent-guides bundle. Only the home repository integrates it; nothing here is guidance.
proposal: p-f4dea4b6c6
bundle: agent-guides
carrier: r-a2f271
base: 0.0.20
digest: ""
kind: experiment
target: a-check-must-be-seen-to-fail
verdict: "**confirms**, with a count, and **names a boundary the note does not**: several never-red checks have a *mirror* on the consumer side that IS tested, through a different entry point, so the subject is covered while this implementation of it is not. Counting by subject would have read 65 % as much smaller. Instrumenting the reporter answers the queued question over every site at a cost of one suite run rather than the hour of planting it priced; the queued row stays, since this repository has a second gate"
where: an offline dictionary for a wearable, whose data packs are produced by a separate pipeline and cleared by a pre-publish validator before they may be distributed
seen: "2026-09-30"
---

the validator's reporter instrumented to record the **call site** of every check, then its whole suite run once; each site classified as ever seen red, exercised but never red, or never reached. Call sites counted from the source so a check nobody runs is visible too

## Result

60 check sites. **21 ever seen red**, 33 exercised and never red, 6 never reached — so **39 of 60 (65 %) have never been observed to fail**. The never-red set is not the periphery: it holds the format version, the normalisation version, the payload codec, the compression-dictionary hash, the one-row-per-entry link between the text index and the entries, and the query plan that the whole design rests on. Four of the six never reached are report-a-failure branches inside error paths, which is what a planted violation would enter
