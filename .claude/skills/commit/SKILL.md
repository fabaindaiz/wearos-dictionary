---
name: commit
description: Prepara y verifica commits para este repo, y define **cuándo se ofrece uno y cómo se parte una serie después de hecha**. Usar **cuando una pieza del trabajo ya pasa el gate y todavía falta el resto**, antes de partir o rehacer commits, cuando el árbol tiene trabajo de otra sesión, y cuando se pida "commiteá", "hacé los commits", "guardá esto", "partí esto en commits" o "subí los cambios".
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

## When one is offered: as soon as ITS piece passes

**Not when the whole task ends.** A commit is offered the moment its own piece is green, and the
rest of the work continues on top. Waiting until the end produces one pile that has to be taken
apart afterwards, and taking it apart is strictly harder than never piling it: the files are
already entangled, so splitting needs an intermediate version of each shared file built by hand.

**Measured in this repo, 2026-09-23**: eight changes held back to the end needed five commits, and
three of them shared `app/build.gradle.kts`, `SearchViewModel.kt` or `MainActivity.kt` with a
later one. Three intermediate files had to be constructed to keep each commit coherent.

⚠️ **This does not license committing mid-task on your own initiative.** Offering is not doing:
the user decides. What changes is *when the offer is made*, not who decides.

## Splitting a series after the fact

When it is already one pile, the procedure that keeps every commit green:

1. **Build every tree from the starting commit forward**, never by undoing from the end.
2. **Prove both ends first**: the starting commit and the final tree. If the final tree is not
   green, splitting it is arranging a failure into five pieces.
3. **Back up before touching anything**, and never with `checkout`, `restore`, `stash`, `reset` or
   `clean` over files this session did not write.
4. **A shared file needs an intermediate version.** Copy it to a scratch directory, remove the
   hunks that belong to later commits, and stage *that* copy without touching the working tree:

   ```bash
   git --work-tree=/tmp/stage-N add -- <path>     # stages the copy, tree untouched
   ```

5. **Gate every intermediate tree**, not just the last one.

## Verify it, do not assume it

```bash
git worktree add -q --detach /tmp/wt-<ref> <ref>
cd /tmp/wt-<ref> && ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew check
git worktree remove --force /tmp/wt-<ref>
```

⚠️ **`ANDROID_HOME` and not a `local.properties` written into the worktree**: that file is in
`permissions.deny` and writing a second one teaches the habit of creating it. The env var touches
nothing.

⚠️ **The test counts in the documents will fail in every intermediate commit**, because `--fix`
reads the working tree and the working tree is ahead. The way that works: run `--fix` **inside the
worktree**, where only that commit's code exists, and stage its result into the commit without
touching the main tree:

```bash
git --work-tree=/tmp/wt-<ref> add -- README.md app/CLAUDE.md docs/roadmap.md tools/CLAUDE.md
git commit --amend --no-edit
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
