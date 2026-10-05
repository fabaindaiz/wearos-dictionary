---
# bundle-proposal: a change this repository offers to the agent-guides bundle. Only the home repository integrates it; nothing here is guidance.
proposal: p-99aa6e1da6
bundle: agent-guides
carrier: r-a2f271
base: 0.0.26
digest: 8ef533bce57d
kind: knowledge
action: new
target: a-derived-artefact-copies-metadata-by-allowlist
lacks: a second repository; provenance literature not consulted
seen: "2026-10-01"
---

An artefact derived from another and built by copying the parent's metadata wholesale declares facts about a run it never had, and a configuration object copied whole into an artefact leaks build-only keys into it. Copy metadata by an explicit allowlist, and keep what an artefact declares about itself apart from how it is built.

## Evidence

One repository, one session: a smaller artefact cut from a larger one by size inherited a ledger describing an extraction it never performed, on the order of a hundred thousand drops it never made; every test and the artefact verifier were green, and it was found by deriving a sample and reading its metadata. Separately, adding one key to a per-artefact build declaration leaked it into the metadata writer, which failed three layers away with a storage binding error that named no field.
