---
bundle: agent-guides
lineage: g-8b5800/main
version: 7
slug: derived-over-chosen-identifiers
topic: identity-and-naming
claim: An identifier that must be unique across boundaries is derived from something already unique, never chosen by whoever creates it.
confidence: reasoned
reach: architecture, planning
---

# Derived over chosen identifiers

## Why it works

A chosen identifier is chosen independently by each party, from the same small pool of obvious words. Two repositories both pick `CORE`; two teams both pick `API`; two services both call their tenant `default`. The collision is not unlikely — it is *selected for*, because the obvious name is obvious to everyone.

Deriving it from something already globally unique — a remote URL, a UUID, a content hash — removes the choice and therefore the collision. It also survives renaming: the derived value tracks the identity, not the label someone typed.

## When it does NOT apply

- **When humans type it.** A derived id nobody can read is a derived id that gets copied wrong, and readability is worth real money in anything people operate under pressure. Prefer chosen names for things humans address, derived ids for things systems match.
- **When the source of derivation is not stable.** Deriving from a directory path or a branch name produces an identifier that changes when nothing important did — worse than a chosen one, because it changes *silently*.
- **When the source of derivation is content that will be edited.** An identifier derived from a record's own text changes the moment the text is corrected, and every reference to it breaks — silently, which is the previous boundary again. Derive it **once, at creation, and freeze it**: later edits never recompute it, and a check verifies only its format, its prefix and its uniqueness, never that it still matches the content.
- **When the source is private, guessable, and the identifier travels in public.** A short hash of something drawn from a small candidate space — a repository remote, an email address, a host name — is reversed by hashing the candidates, so a published identifier names what it was only meant to distinguish. Mint a random value once, store it with the thing it names, and check its presence and format rather than its derivation: it is still not *chosen*, which is what the claim is about.
- **When the namespace is genuinely closed and small.** Three services owned by one team, with a list in one place, do not need this.

## What it costs

Readability, and it is not a small cost: `d-abcdef-123456` does not tell you which repository it belongs to or what it records, where a chosen word and a sequence number name their owner at a glance. Pay for it by making the derivation **checkable** — where the source is stable, have something re-derive the identifier from it on every run and fail if the stored value has drifted; where the source is content that will be edited, freeze the value at creation and check its format, prefix and uniqueness instead. A derived id that is verified is worth more than a legible one that silently goes stale; a derived id that is *not* verified is the worst of both.

## Where it came from

Decision ids in a two-repository workspace, where one repo's index cites the other's rows. A bare number was ambiguous; a chosen prefix was picked by one person and would be picked again elsewhere. A short hash of the normalised git remote solved it, and the readability loss was paid for with a check that re-derives the prefix every run — which caught a drifted constant on its first real execution, before any human saw it.

The first scheme put a sequence number after that prefix — `d-abcdef-017`, the seventeenth decision — and the sequence was itself a chosen identifier: two sessions writing in parallel would both take the next free number. The scheme that replaced it keeps the derived repository prefix and replaces the sequence with a derived one: `<kind>-<repo6>-<content6>`, where `content6` is the first six hex of `sha256` over the record's text at creation, whitespace collapsed, **minted once and frozen**. A collision on the same id is resolved by the writer adding a distinguishing word and minting again. Ids written under the old scheme stay valid as written and are never rewritten.

**A later turn, about what the source reveals (2026-09-24).** The repository part derived from the remote turned out to be reversible: anyone with a list of candidate remotes hashes each one and matches the prefix, so a published id named the private repository it was only meant to distinguish. That part is now a random value minted once and stored in the repository itself — the boundary *When the source is private, guessable, and the identifier travels in public* above.

Judgement, unmeasured — though the check firing on day one is evidence the failure mode is real rather than theoretical.

**A second occurrence, about build identity.** A client application's store-distributed build carried a *chosen* version number that never changed, so the device refused to install over what it already had; the web build stamped its identity after export and the desktop builds had none — three identities, none complete. It now derives one stamp before every export and embeds it: the store version is a count of seconds since a fixed recent epoch, in UTC so that a clock going back cannot give two builds the same number, and it stays under the store's version ceiling for decades. That count is the store version only; a single date-time build stamp derived alongside it is what busts the offline cache and is shown in the diagnostics readout, so what the device reports is what invalidated its cache.

## Literature

- **[Reverse domain name notation](https://en.wikipedia.org/wiki/Reverse_domain_name_notation)** — the convention behind Java packages, Android and iOS bundle ids, and macOS UTIs. Identifiers are derived from a **registered domain name**, reversed for grouping, precisely because *a domain is globally unique to its owner* — so the namespace inherits a uniqueness someone else already guarantees and pays to maintain.

  **What we take from it:** the move generalises past packages. Derive the identifier from whatever globally unique thing you already own — a domain, a repository remote, a content hash — instead of asking a person to invent a unique string, because people reach for the same obvious words.

  **Where we differ:** reverse-DNS stays **readable** (`com.google.android` says who and what). A hash does not, and this note treats that as the cost to be paid for rather than a detail. When readability matters more than mechanical guarantee, reverse-DNS is the better trade and this note should not be used to argue otherwise.

## Evidence

**One occurrence, in-session and not a measurement.** After moving to derived prefixes, the check that re-derives the value from the git remote failed on its first real execution: a constant had been left at the old chosen value because the rename matched a pattern the constant did not fit. That is evidence the *drift* failure mode is real. It is **not** evidence that a chosen prefix would have collided, which is the note's actual claim and remains unobserved.

The second occurrence is an observed failure of a chosen identifier (an install refused), though of monotonicity rather than of collision.

What *would* settle the claim: count prefixes independently chosen across a real set of repositories and look for duplicates. With two repositories, the sample cannot say anything.
