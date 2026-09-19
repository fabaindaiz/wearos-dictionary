---
name: commit
description: Prepara y verifica commits para este repo. Usar cuando el trabajo está terminado y se pida "commiteá", "hacé los commits", "guardá esto" o "subí los cambios".
allowed-tools: Bash, Read
---

# Commit

> The `description` above stays in Spanish: those are the phrases **the user says**, and that is
> what this skill is matched against.

## They split by dependency, not by size

**Every commit has to be green on its own**, or the history is not bisectable and is no use for
finding when something broke.

In this repo that imposes a concrete order: `tools/` before `dict-core/`, because the Kotlin tests
consume the shared vectors that live there. The other way round, the intermediate commit is red.

## Verify it, do not assume it

```bash
git worktree add -q --detach /tmp/wt-<ref> <ref>
cd /tmp/wt-<ref> && echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
./gradlew check
git worktree remove --force /tmp/wt-<ref>
```

A clean clone too: that is what caught that `gradle-wrapper.jar` had never been tracked and the
repo could not be built from scratch (D-021).

## The message says why

Which files changed is already in the diff. The body explains the decision, and if a measurement
killed a belief, that is the most valuable content in the commit.

## Before offering to commit

- The gate passes (the `verify` skill).
- The changelog entry is written: `.claude/logs/agent-changelog.md`.
- If a new decision was taken, it has its row in `docs/decisions.md` with the *Enforced in* column
  filled — even if it says `—`.
- Nothing generated enters the repo: see the list in `CLAUDE.md`.

**Never commit on your own initiative mid-task.** It is offered when the work is finished.
