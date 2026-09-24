---
bundle: agent-guides
lineage: g-8b5800/main
version: 8
slug: cleanup-belongs-to-the-supervisor
topic: failure-behaviour
claim: Restore whatever a probe, test or job may dirty from the process that launched it, with a timeout, because a child that hangs or crashes never reaches its own cleanup.
confidence: measured
reach: implementation, debugging, review
---

# Cleanup belongs to the supervisor

## Why it works

The natural place to undo a side effect is next to the code that caused it: a line at the end, a `finally`, a teardown hook. Each runs only if the child reaches it. A child that hangs, is killed, or dies in a way its runtime does not unwind never gets there, and the side effect — a setting switched on, a file written into user storage — outlives it and contaminates the next run, or the user's own data.

The supervisor is outside that failure. It can snapshot what the child may touch before starting it, enforce a timeout, and restore the snapshot afterwards however the child ended. It can also report what the child changed, which turns silent contamination into a line in the output.

## When it does NOT apply

- **When the child's effects are already isolated** — a throwaway container, a temporary profile, a transaction rolled back by the store.
- **When the supervisor cannot find the state** — a per-platform storage path it does not know; then it reports "untouched" while the child dirtied the real one. Prove the path on each platform before trusting the report.
- **Effects outside the machine** (a sent request, a remote write) cannot be restored by snapshot; those need the idempotency and ordering notes in this base.

## What it costs

A runner around every probe or test, a snapshot per run, and one platform path per operating system that must each be proven.

## Where it came from

One repository measures things with temporary probes run inside its engine. Twice a probe left user storage dirty — once the diagnostics overlay switched on, once a list written into the user's data — and the machine was found carrying leftovers of earlier probes. A third time a probe file itself stayed committed. On 2026-09-17 the restore moved out of the probe into a runner that backs up user storage and restores it **from outside the engine**, because a probe that hangs never reaches its own last line. Measured then: a probe that overwrote one file and added another was reported as having added one and changed one, and the hashes came back identical; a probe with no exit call was killed at the timeout, with the timeout's exit code, and storage restored. A later session on another operating system ran a dozen probes through the runner, which restored storage and correctly reported the one probe that added a file; a third operating system's path is still unproven.

## Literature

- **Candea & Fox, 2003, ["Crash-Only Software"](https://www.usenix.org/conference/hotos-ix/crash-only-software)** (HotOS IX). "A component's power-off switch implementation is entirely external to the component, thus not invoking any of the component's code"; recovery code that runs on every start is exercised, and so reliable. *Verified 2026-09-22 against the paper's PDF.* **What we take:** control from outside the component. **Where we go further:** applied to the cleanup of test and probe side effects rather than to service recovery.

## Evidence

**Measured in one repository:** two contamination incidents before, a byte-identical restore after, and a hung child killed at its timeout with storage restored. **Not measured:** the unproven platform. The experiment: on that platform, run a probe that writes a marker file into user storage and confirm the runner reports and removes it.
