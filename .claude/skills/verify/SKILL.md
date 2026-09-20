---
name: verify
description: Corre el gate de este proyecto y reporta con honestidad qué pasó y qué no. Usar antes de commitear, después de tocar normalización o el formato de pack, y cuando se pida "verificá", "corré el gate", "chequeá", "¿está listo?" o "¿esto funciona?".
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

It runs compilation, Android Lint, the **77 of `:dict-core`**, the **206 JVM of `:app`** (screens
included, under Robolectric), the **250 of the Python builder** and the structural audit
(**21 checks**). **Measured: ~1m07s cold.**

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

On top of the above, `verify_pack.py` over any real pack there is. Look specifically at the
`[planes de consulta]` section: if the prefix stops using `COVERING INDEX`, the incremental search
stops meeting its latency budget and **nothing else would notice**.

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
