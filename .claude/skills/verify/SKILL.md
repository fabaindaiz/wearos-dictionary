---
name: verify
description: Corre el gate y define **cómo se escribe una medición, cómo se confirma que un test sirve, cómo se prueba un cambio que no debe cambiar nada, y qué no se toca del árbol**. Usar también **cuando un refactor o una regeneración no deberían alterar el resultado**, y **después de correr una sonda de mutación**. Usar antes de commitear, después de tocar normalización o el formato de pack, **antes de escribir un número en un documento**, **cuando un test pasó a la primera**, **antes de descartar o restaurar archivos** (`checkout`, `restore`, `stash`, `reset`, `clean`), al corregir algo que ya se afirmó, y cuando se pida "verificá", "corré el gate", "chequeá", "¿está listo?" o "¿esto funciona?".
allowed-tools: Bash, Read
---

# Verify

> The `description` above stays in Spanish on purpose: those are the phrases **the user says**,
> and that is what this skill is matched against. The body is English like the rest of the repo.

This repo has contracts that break without producing any error: the symptom is a missing word,
months later. The gate is the only thing that catches them before that happens.

## The gate

```bash
./gradlew check
```

It runs compilation, Android Lint, the **77 of `:dict-core`**, the **233 JVM of `:app`** (screens
included, under Robolectric), the **250 of the Python builder** and the structural audit
(**28 checks**). **Measured: ~1m07s cold.**

## If you touched `norm()`, `fuzzy()` or the Unicode repertoire

The gate already runs the shared vectors, but a pack built before the change was indexed with the
old rules:

```bash
python3 tools/packbuilder/build_toy.py
python3 tools/packbuilder/verify_pack.py dict-data/src/androidTest/assets/toy-es-en.db
```

If `NORM_VERSION` did not go up and the keys changed, **the tests pass and the bug stays**. It is
the one case where the gate is not enough.

## If you touched the pack format

And before sideloading anything, `--como-la-app` over every pack you are about to push: it
answers the other question, *"if I install this, does it show up?"*, and it takes seconds.

```sh
python3 tools/packbuilder/verify_pack.py --como-la-app <dir>/dist/*.db
```

On top of the above, `verify_pack.py` over any real pack there is. Look specifically at the
`[planes de consulta]` section: if the prefix stops using `COVERING INDEX`, the incremental search
stops meeting its latency budget and **nothing else would notice**.

## How a measurement is written, and how a test is trusted

**A number carries its date and its environment, not only its method.** The same commands on the
same commit answer differently in two environments —a stale virtualenv earlier on `PATH`, another
interpreter, an unpinned stub— and a number written without saying where it came from becomes
somebody else's baseline. So: say how and where; **never bump a date without re-running**; and when
a measurement disagrees with the written one, establish *which binary produced each* before
anything else. That one check has turned three separately recorded anomalies into one fact.

**A retraction is written everywhere the claim was.** When a finding turns out false, correct it in
the docstring, the document and the report, saying what it was and why it was wrong, and add a new
changelog entry rather than editing the old one. A finding corrected in one of three places is
still being believed in the other two.

**If the red step could not be watched, prove the test bites.** A test written after the code, or
one that was green the first time it ran, has not been shown to detect anything. Mutate its target
—narrow the range, flip the comparison, delete the branch— and watch that same test fail. Restore
by rewriting the file, not with `git checkout`.

**A change that must change nothing is proved by its invariant, compared mechanically.** A
refactor, a regeneration, a rename: *"I read the diff and it looks equivalent"* is not a proof, and
this is where an agent is weakest — the diff is exactly what it just wrote. Pick the invariant the
change promises to preserve and compare it before and after **with a command**, not with an eye.

> **Measured, 2026-09-23.** The two core packs were regenerated to fix their name. The invariant
> that had to hold was *the content does not move*, and the check was the byte size: **50,843,648
> and 42,856,448, identical before and after**. That is what let the change be called a rename.
> Reading the diff of a `.db` proves nothing at all.

The shapes this repo uses: byte size or `sha256` for an artefact, `verify_pack.py --como-la-app`
for a pack, the shared vectors for `norm()`, the query plan for an index. If no invariant can be
named, the change is not the one being claimed.

**A probe does not survive the session that ran it.** After a mutation, restore by rewriting the
file, and check the tree is clean of markers before offering anything — `check_no_probes_left_behind`
in the audit fails on `MUTACION`, `MUTATION PROBE` and `SONDA:`. ⚠️ **A forgotten mutation breaks
nothing**: the test that would catch it is the one being probed, so what remains is deliberately
wrong code with a green gate. And macOS caches bytecode outside the repo, so a mutation of the
same width has survived a restore before.

**The uncommitted diff is the work.** No `checkout`, `restore`, `stash`, `reset` or `clean` over
files this session did not write: a parallel session's work is in that diff and it is not yours.
Probe on a copy under the scratchpad, or in a `git worktree`, which is also the only place a
split commit can be proved green on its own.

## The emulator and the watch measure different things

Not different amounts of the same thing — **different things** (D-043), and confusing them is how
a number gets believed that was never about the watch.

- **The emulator closes correctness**: normalization, FTS5, query plans. It ships the ICU and the
  SQLite of its API level, so what it says about those is true of that level.
- **Performance and battery only count on a physical watch.** A start-up time or a p99 from an
  emulator is not a slow measurement of the watch; it is a measurement of this laptop. See the
  `benchmark` skill.
- **And a debug APK is a ceiling, not a number**: `debuggable = true` means ART never compiles it
  AOT. Measure on `benchmark`.

The questions that need a wrist live in `docs/preguntas-del-reloj.md`, each with the readout that
answers it. **A claim this session could not verify goes there**, in the same change as its
changelog entry.

## Reporting

Say what happened and what did not, with the output. **Never call something verified that you did
not run.** If something was already failing, name it so it does not get presented as new.

## The on-device tests, which the gate does not run

```bash
./gradlew :dict-data:devicePrecheck             # is there anything to run them on? says what is missing
./gradlew :dict-data:connectedDebugAndroidTest  # the 34 tests
./gradlew :app:connectedDebugAndroidTest        # the 7 that really do need a device
```

They need an emulator or a connected watch, which is why they are outside the gate.
`devicePrecheck` exists because without a device Gradle fails with an error that does not say what
to do. They are the only ones that close the assumptions about Android: that the bundled SQLite
carries FTS5, that the prefix uses the covering index on that device, and above all that `norm()`
gives the same thing on the watch as in the builder.

Run them on **every supported API level**. Running one alone does not prove what the test is
trying to prove, which is precisely that the versions differ.

What the gate does **not** cover, and has to be said when somebody asks whether it is ready:

- **The instrumented tests exist but may never have been run.** They compile in the gate; running
  is another matter. Check before claiming something works on Android.
- The latency and size budgets in `docs/formato-pack.md` are **unmeasured targets**.
