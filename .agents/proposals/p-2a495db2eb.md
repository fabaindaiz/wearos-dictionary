---
# bundle-proposal: a change this repository offers to the agent-guides bundle. Only the home repository integrates it; nothing here is guidance.
proposal: p-2a495db2eb
bundle: agent-guides
carrier: r-a2f271
base: 0.0.32
digest: 68717f030a4c
kind: method
action: new
target: no-foreign-shell-constructs
lacks: a carrier under a second owner
seen: "2026-11-24"
---

# Proposal: no-foreign-shell-constructs

A command or an argument list stored in a shell variable is an interpreter-specific construct: in some shells the variable expands to one word, in others it is split into fields, and where it is not split the command fails without an error while a trailing status message still reports success. The agent must not emit constructs of an interpreter it has not confirmed the owner's session uses: when a variable must hold a command or a list, wrap the whole step in an explicit `bash -c`, use a function or an array, or write the loop in a script language. And a rule that was broken while the agent had it loaded does not get rewritten as better prose: it moves up a rung, from prose to a hook that refuses the construct before it runs.

## Evidence

One carrier, one owner. The owner's interactive shell is not bash, and the agent's own command tool runs a third interpreter, so the bash reflex fails in two different ways. The same trap was counted five times over about two months, in the session log and in the carrier's roadmap: a loop over a list stored in a variable ran with empty arguments and produced rubbish (twice in one day); a resolution loop failed silently; a copy loop saved nothing and a following checkout then discarded uncommitted work; and a script that kept the device command prefix in one variable ran none of its calls while its closing message said the step was done. The rule was written into a skill after the second incident, rewritten after the fourth, and broken a fifth time with the rewritten text loaded. The carrier priced a hook that refuses a command whose first word is an unquoted variable, and a second one that refuses discarding commands while uncommitted changes exist, but had built neither at the time of writing. Literature: the candidate's own; no new source consulted.
