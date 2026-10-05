---
# bundle-proposal: a change this repository offers to the agent-guides bundle. Only the home repository integrates it; nothing here is guidance.
proposal: p-2e680bc5bb
bundle: agent-guides
carrier: r-a2f271
base: 0.0.26
digest: 8ef533bce57d
kind: method
action: new
target: a-precondition-check-lives-where-the-action-already-looks
lacks: a second repository
seen: "2026-09-28"
---

A defence placed in a step nobody runs before the risky action does not prevent the second occurrence: put the readout into the output the action already reads. This is one form of raising a rule's rung on the enforcement ladder.

## Evidence

One repository: screenshots were reviewed on an emulator with the wrong screen geometry (about a fifth narrower than the target) twice, three days apart. After the first, a warning was added to a precheck task, which is not run before taking a screenshot; after the second, the app prints its geometry in the startup log that every session already reads.
