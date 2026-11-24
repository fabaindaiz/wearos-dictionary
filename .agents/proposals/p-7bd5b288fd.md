---
# bundle-proposal: a change this repository offers to the agent-guides bundle. Only the home repository integrates it; nothing here is guidance.
proposal: p-7bd5b288fd
bundle: agent-guides
carrier: r-a2f271
base: 0.0.32
digest: 68717f030a4c
kind: method
action: extends
target: two-contradicting-answers-need-a-third-question
lacks: a carrier under a second owner
seen: "2026-11-24"
---

# Proposal: two-contradicting-answers-need-a-third-question

When two of the owner's answers cannot both hold, the agent asks a third question built from the case where they conflict, and expects that answer to reopen the decisions that depended on the first two.

## Evidence

One carrier, one owner. In a decision review the owner asked for a debug build "for productive use" on the target device and for a startup profile measured only on the emulator; the platform does not compile a debuggable application ahead of time, so the profile would have had no effect on the device. The agent asked a third question naming the conflict, the owner chose a non-debuggable build, and that answer reopened a downstream decision (the debug-only channel used to copy data onto the device stopped working), which was asked and answered in turn. Separately, a request to remove a filter, put back as a question when the removal came due, was reversed by the owner. Literature: the candidate's own; no new source consulted.
