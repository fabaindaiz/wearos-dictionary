---
bundle: agent-guides
lineage: g-8b5800/main
version: 7
slug: test-double-fidelity
topic: verification
claim: A test double that accepts more than the real dependency makes the test pass against a fiction; check its fidelity rather than assuming it, and make tests fail closed on the real network.
confidence: reasoned
reach: review, debugging, planning
---

# Test double fidelity

## Why it works

A fake is a second implementation of the dependency's semantics, written quickly by someone who needed a test to pass. Wherever it is more permissive than the real thing — it ignores a projection, merges where the real one replaces, accepts a call the real one rejects — the test asserts against a world production does not have. The test is green, it does assert, and it is wrong. This is different from low-quality coverage: the assertion is present; the ground under it is fictional.

The failure has recognisable forms:

- **Ignored arguments.** A fake that ignores a field mask returns the whole document, so a test "proves" a code path production can never reach.
- **Different semantics.** A fake that replaces nested maps where the real store deep-merges, or the reverse.
- **The wrong method stubbed.** The stub overrides a method the code no longer calls; it is never consulted, and the test asserts the absence of the behaviour it is named for.
- **Errors swallowed by the code under test.** The code's own handling catches the failure the double caused, and the suite passes for the wrong reason.

The companion rule is about the other direction of infidelity. When the test configuration can point at real endpoints, **deny network sockets by default**: a test that slips its mock does not error, it succeeds against production — and in a system with physical or financial effects, it performs them. The guard itself needs a test, because an untested safety mechanism quietly stops working.

## When it does NOT apply

Pure-function tests with no double. Doubles generated from the real implementation, or contract-tested against it on the same cases.

## What it costs

Contract tests that run fake and real on the same inputs — the real side needs an emulator or the network — or faithfully re-implementing semantics such as projections and merges in the fake, which is real work.

## Where it came from

A transactional service, five occurrences: an in-memory store fake that ignored field masks, so a fixture reached a gate production could not; the same fake replacing nested maps where the real store deep-merges; a stub on a method the code no longer called; a synchronous stub standing in for an asynchronous method, which broke several tests and left half of a change unverified; and suites that passed because the code's own error handling swallowed a wiring failure. The same repository denies sockets in tests because a test that slipped its mock would perform real effects outside the test, and tests the guard against a reserved documentation address. An evaluation of that repository named the gap between the store and its double (indexes, contention) as its blind class.

## Literature

- **Trenk & Bly, ["Test Doubles"](https://abseil.io/resources/swe-book/html/ch13.html)**, ch. 13 of Winters, Manshreck & Wright (eds.), 2020, *Software Engineering at Google*: fidelity is the property that matters ("how closely the behavior of a test double resembles the behavior of the real implementation"), and "a fake must have its own tests". *Verified 2026-09-23 against the chapter.* **What we take:** fidelity as a named, testable property.
- **Fowler, 2011, ["ContractTest"](https://martinfowler.com/bliki/ContractTest.html)**: keep testing against the double, and periodically run separate contract tests against the real service that "check that all the calls against your test doubles return the same results as a call to the external service would." *Verified 2026-09-23 against the page; earlier wording here said "the same tests against both", which the page does not say.* **What we take:** the mechanism for checking fidelity.

## Evidence

**Reasoned: the occurrences are counted, the rate is not.** What would measure it: run each fake's test cases against the real dependency in an emulator and count the disagreements.
