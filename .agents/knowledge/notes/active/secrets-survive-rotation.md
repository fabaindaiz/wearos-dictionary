---
bundle: agent-guides
lineage: g-8b5800/main
version: 6
slug: secrets-survive-rotation
topic: identity-and-naming
claim: Anything persisted that depends on a secret must survive the secret's rotation — store the verified result, not the signed token, and key caches by the credential's fingerprint.
confidence: reasoned
reach: architecture, review
---

# Persisted state survives secret rotation

## Why it works

Secrets rotate — on schedule, on exposure, on a staff change. Everything derived from one at write time and stored is bound to the version that existed then:

- A **stored signed token** (`<id>.<signature>`) stops verifying the moment the key changes, silently, for every record at once. Verify at the boundary, then store the verified identifier.
- **Copies of a credential** spread into records (one per receipt, per job, per tenant row) are all stranded by a rotation and all have to be found. Store a reference to the one credential, not the credential.
- **A cache keyed by name** keeps serving what the old credential produced. Keyed by a fingerprint of the credential, a rotation invalidates it by construction.

The failure is always the same shape: rotation is treated as an operational event, and it turns out to be a data migration nobody planned.

## When it does NOT apply

Tokens meant to expire with the key — sessions, short-lived capabilities — where invalidation on rotation is the intended behaviour.

## What it costs

Verification moves to the boundary, and the verified result is trusted afterwards, which has to be justified. Fingerprint-keyed caches miss once per rotation.

## Where it came from

A transactional service, three occurrences: storing a signed device identifier would have made every stored binding stop verifying after a rotation, so the verified identifier is stored instead; a design with a copy of an integration credential per receipt was rejected because one rotation would strand every copy; and a token-service cache is keyed by credential fingerprint, so a rotation invalidates it by construction.

## Literature

None known that states it this way. It is adjacent to `derived-over-chosen-identifiers`: what is persisted should depend only on what stays true.

## Evidence

**Reasoned, from three design decisions; no rotation incident observed.** What would measure it: rotate each secret in a staging environment and count the stored records that stop verifying or the caches that keep serving.
