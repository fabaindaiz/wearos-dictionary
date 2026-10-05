---
# bundle-proposal: a change this repository offers to the agent-guides bundle. Only the home repository integrates it; nothing here is guidance.
proposal: p-f43f143cdd
bundle: agent-guides
carrier: r-a2f271
base: 0.0.26
digest: 8ef533bce57d
kind: method
action: overlaps
target: a-red-from-a-build-failure-is-not-the-red
lacks: "refused: already answered: principle 18, fail for the reason you expect"
seen: "2026-10-01"
---

A mutation probe that dies at the compiler proves nothing about the tests; probes report whether they compiled.

## Evidence

One repository: a no-op mutation reported red because it failed to compile; every probe since reports whether it compiled.
