# dict-core

Pure Kotlin/JVM: normalization, search keys, edit distance and the payload codec. **No Android
and no SQLite.** The tests run in milliseconds, with no emulator.

## The local rule

Every platform API lives in `PlatformJvm.kt`. No other file imports `java.*`/`javax.*` or uses
`Character.`, `.codePoints()` or `.format()`.

`ArchitectureTest` breaks the build if that stops being true, and it is proven to catch real
violations. The KMP conversion —if a companion app ever exists— would be moving that file into
`jvmMain/` and declaring its four functions as `expect`.

## The file to copy

`PrefixRange.kt`. A small module, an `object`, KDoc that explains **why** and not what,
hand-written code point iteration, zero dependencies.

For a test: `PrefixRangeTest.kt` — it verifies the property ("the range contains exactly the words
with that prefix"), not just isolated cases.

## The trap that already bit us

**A Kotlin extension never wins over a native JVM member.** `appendUtf16` is named that and not
`appendCodePoint` because with that name the JVM's `StringBuilder.appendCodePoint` would have won
and the portable code would never have run: working fine today, failing only when compiled for
another target.

If you write a portable replacement for something in the JVM, **give it a different name**.

## Adding a `FuzzyProfile`

It is always a two-language change:

1. `FuzzyProfile.kt` — the enum entry, with the rules **in order**. The order is part of the
   contract: `"ce" → "se"` before `"c" → "k"`, or "cerrar" stops colliding with "serrar".
2. `tools/packbuilder/normalize.py` — the same rules in `FUZZY_PROFILES`.
3. `vectors/normalization-vectors.tsv` — cases for the new profile. `NormalizationVectorsTest`
   fails if a declared profile has no vectors.
4. Bump `NORM_VERSION` on both sides.

**No regular expressions.** Literal string replacement only, which has identical semantics in
Kotlin and in Python. Two "equivalent" regexes diverge on an edge case nobody notices.

## What does NOT go here

Anything that knows about SQLite, Android, file paths or the network. `DictionarySource` is an
interface on purpose: the implementation lives in the module that is allowed to touch SQLite.
