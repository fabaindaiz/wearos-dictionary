---
# bundle-proposal: a change this repository offers to the agent-guides bundle. Only the home repository integrates it; nothing here is guidance.
proposal: p-a0d6d191cd
bundle: agent-guides
carrier: r-a2f271
base: 0.0.32
digest: 68717f030a4c
kind: knowledge
action: extends
target: sweep-the-rendered-extremes
lacks: a second occurrence; the formatting library's region rules checked against its documentation
seen: "2026-11-24"
---

# Proposal: sweep-the-rendered-extremes

A locale is swept down to its region, not only its language: formatting libraries pick short forms, separators and plural words by region, so a test run under the language alone describes a device that does not exist.

## Evidence

One carrier, one occurrence. A compact count was rendered in a test under a language-only locale and passed; the target device runs a language-plus-region locale, for which the formatting library's short form is a different word, and the owner saw the wrong form on the device. It was reproduced on the emulator, fixed test-first with the test pinned to the region, and the format was fixed for the language whatever the region. Literature: the note's own; the formatting library's region-specific compact forms are documented behaviour not re-checked here.
