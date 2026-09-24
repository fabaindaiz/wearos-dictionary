---
bundle: agent-guides
lineage: g-8b5800/main
version: 8
slug: a-check-must-be-seen-to-fail
topic: verification
claim: A gate, audit, filter or count earns trust only after it has been seen to go red on a planted violation; a check that reads the wrong stream or dies before its main step reports green forever.
confidence: measured
reach: review, debugging, planning
---

# A check must be seen to fail

## Why it works

A check has two ways to be green: nothing is wrong, or the check cannot see. From the outside they are identical, and the second is common, because checks are glue — a command piped into a filter, a count grepped from a log, a chain of steps where one exit code stops the rest. Every link in that glue can silently turn the check into a constant.

The only way to tell the two greens apart is to make the thing wrong on purpose and watch the check notice: plant the violation, see red, remove it, see green. This is what mutation testing does for unit tests; it applies with more force to CI plumbing, audits and data filters, which nobody tests at all.

A related failure hides inside chains: **stacked failures hide each other.** When step 2 fails, steps 3 and 4 never run, and whatever is wrong with them is invisible until step 2 is fixed. A gate that "fails" is not evidence that its later steps pass.

Two more forms of the same constant, both common in tools that were never meant to be gates. **An exit code is not a verdict**: an interpreter that reports a compile error, carries on and exits 0 turns every run into a pass, so the glue has to read the output for the tool's own error markers. And **"identical" needs a negative control**: a comparison that finds no difference proves something only once a deliberately different input has been seen to differ. A refusal test likewise asserts each refusal's *reason*, not only how many there were — the count can be right while every reason after the first is wrong.

**A check that was seen red once can still go blind** with no change to itself: its subjects disappear — a folder it scans is retired, a marker it searches for is renamed. Guarding against that is a property of every run rather than of the wiring, and it is `report-coverage-before-findings`: count the subjects, and fail on zero. A completion check has the same shape here: its *false* answer must be distinguishable from silence — the command's own exit status, not the pipe's; an empty match treated as unknown, not as clean.

## When it does NOT apply

Checks whose detection is already proven by an external conformance suite — and even then, the local wiring that runs them can still swallow the output, so the plant-and-watch is still owed once per wiring.

## What it costs

One planted-violation run per new check, usually minutes. It is skipped because the check "obviously" works, which is exactly the belief it exists to test.

## Where it came from

A typecheck gate in CI piped the tool's standard output into a log and counted errors in it. With the option that installs missing stubs, the tool printed its errors to **standard error** — a single line on stdout against dozens on stderr — so the count was zero on every run and the gate could not fail. In the same change, the local chain *audit, lint, typecheck, test* stopped at the typecheck's exit code and **never ran a test**, while the documentation said it did. When the chain was later rebuilt on the lowest supported interpreter, **every module failed to import** because of an annotation the newer interpreter evaluates lazily — hidden until then because the gate had died at the typecheck first: three stacked causes, each hiding the next.

The same discipline recurs in that repository as tests whose names say they prove the strict check can detect a violation, guards verified by deleting what they guard and watching them name it, and an audit check that caught a stale document on its first run.

A second repository — an interactive renderer whose engine can also run headless — met the exit-code form twice. On a compile error the engine printed the error and a cascade of script errors, carried on, and **exited 0**, so a measurement probe with a broken script produced output that looked like a measurement; the runner now fails any run whose output carries the error marker. Later the whole gate was **green and blind**: a stale class cache left several classes unresolved, the validator failed to load, and the script error did not fail the run. The cause — a stale cache read in place of its source — is covered by `derived-copy-goes-stale-silently`; what belongs here is that the gate could not go red. The same repository records the negative-control form: a packaged texture rendered the same frames as the built-in copy, and only swapping in a different image proved the packaged one was drawn at all; and a package verifier reported every file after a bad one as bad too — the refusal count was right and the reasons were not. Its checks are introduced by breaking them: an emptied table cell and an over-long caption each turned red the checks that watch them.

A third repository — an offline-search application with a packaging toolchain — met the completion form in one session: three completion checks answered *done* wrongly. `echo "EXIT=$status"` after a pipe printed 0 over a failed build, an empty log filter was read as a clean probe rather than a dead receiver, and a transfer monitor declared three packs complete while the last was 53 of 314 MB in, because an unescaped `.` in the pattern matched the partial file whose existence means the opposite. The same repository's zero-subjects occurrences are counted in `report-coverage-before-findings`.

## Literature

- **DeMillo, Lipton & Sayward, 1978, ["Hints on Test Data Selection: Help for the Practicing Programmer"](https://doi.org/10.1109/C-M.1978.218136)** (IEEE Computer 11(4)). *Bibliographic record verified 2026-09-23; the full text was not opened.* Mutation testing: plant faults to measure whether tests detect them. **What we take:** detection is measured, not assumed. **Where we go further:** applied to gates, audits, pipelines and filters, where there is no test framework to host the mutant.

## Evidence

**Measured in three repositories:** in the first, errors sent to the stream the gate did not read, a chain that ran no tests, and an import failure in every module hidden behind it; in the second, a compile error that exited 0 and a gate that stayed green with classes unresolved; in the third, three completion checks whose *false* could not be told from silence, one of them a monitor that matched its own partial file. What would generalise it: for every check in a gate, record whether a planted violation was ever observed turning it red, and count how many never were.
