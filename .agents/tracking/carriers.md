---
# bundle-header — provenance for this document. Identical across the bundle.
bundle:    agent-guides
lineage:   g-8b5800/main
ancestry:  [g-c7344c, g-099a8a, g-8b5800]
version:   6
component: tracking
released:  2026-09-24
---

# Carriers — every repository known to hold this bundle

**One row per carrier, by its id**, and the versions it was last aligned to. `bundle.py
register` writes the rows and `bundle.py align` refuses to close a meta-session while a reached
carrier is missing here or registered at another version. The id is random, minted once by
`bundle.py carrier-id --mint` and stored in the carrier's own header field `carrier:`; it is not
derived from anything about the repository, so it cannot be reversed. Nothing describing a carrier
is ever written next to its id, here or anywhere in the bundle. **Paths are never written here**: they are per machine, in the local manifest.

| Carrier | Bundle | Method | Knowledge | Aligned on |
|---|---|---|---|---|
| r-a2f271 | g-8b5800 v20 | m-351cc8 v24 | k-7159bd v11 | 2026-09-24 |
| r-5ed7e8 | g-8b5800 v20 | m-351cc8 v24 | k-7159bd v11 | 2026-09-24 |

**Every row is written by `bundle.py register`**, never by hand; a carrier that cannot run the
tool reports that as a defect in `candidates.md`.

## Not reached

Carriers known to exist and not aligned by the last meta-session. They are described here without
ids: the ids they were registered under were derived from their remotes and could be reversed by
guessing, so they were withdrawn on 2026-09-24. Each mints a random id with
`bundle.py carrier-id --mint` when a meta-session next reaches it, and receives the current release
then, keeping its own repository fields.

- **Several carriers aligned at an older release of this line.**
- **Several carriers of the `g-c7344c` line**, never reached since that line merged into this one.
