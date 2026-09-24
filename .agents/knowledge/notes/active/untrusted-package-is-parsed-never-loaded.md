---
bundle: agent-guides
lineage: g-8b5800/main
version: 9
slug: untrusted-package-is-parsed-never-loaded
topic: adversarial-controls
claim: Accept content from outside only as data — an allowlist of types, each handed to a parser that can only parse, limits checked before anything is inflated, every file listed with its hash — and keep an import only after the whole check passed, exactly as it arrived.
confidence: measured
reach: architecture, review, planning
---

# An untrusted package is parsed, never loaded

## Why it works

A platform's general-purpose loader does two things: it parses bytes and it constructs objects, and in many runtimes a constructed object can carry code (a serialized resource with a script attached, a pickled object, a deserializer with type hints). A parser for one format — an image decoder, a JSON reader, an audio decoder given a buffer — has no path to construction, so what it cannot express it cannot run. The allowlist is what keeps every file on such a path.

The other controls each close one door:

- **Limits read from the archive's directory** — file count, total unpacked size, names that cannot climb out of the destination — reject a decompression bomb or a path-traversal entry while it is still a few bytes, before any inflation is paid for.
- **An index of every file with its size and hash** moves "damaged or altered" to the point of entry, not halfway through use, and makes an unlisted file a refusal rather than a surprise.
- **A format version the reader refuses when newer** stops an old reader from silently dropping keys it does not know.
- **Write beside, check completely, then rename into place** means a failed import leaves nothing behind, and what is kept is the bytes the user chose, not a re-encoding.

## When it does NOT apply

- **A declared size is a claim.** The sizes in an archive's directory are written by whoever built the archive. Overlapping entries whose directory understates what they inflate to are a documented construction (see Literature), so the directory check is a cheap first rejection, not a bound. Bound the actual inflation as well — stream with a byte budget, or reject overlapping entry ranges. Probed 2026-09-22, and the bound held — for a reason worth stating, because it is the bound and the declared size is not. The decompressor stops at the declared size, so a lie in the directory buys no allocation, and the two independent declarations, the directory and the hashed index, have to agree. A directory understating one entry by tens of megabytes was refused at open after **a few kilobytes**. The same lie told in the directory *and* in the index was accepted at open, and the read then returned empty and named the entry, with a peak allocation **within a fraction of a percent of the honest package's** — the understated entry was never allocated. Twenty entries pointing at one local header, declaring more than the total unpacked limit, were refused after **a couple of kilobytes**.
- **Content signed by a party you already trust and executed in a sandbox** — a plugin system is a different design, with its own controls.
- **Formats whose parser is itself the attack surface** (a decoder with known memory bugs): parse-only moves the risk into the parser, it does not remove it; keep decoders patched and bounded (image dimensions, audio length).

## What it costs

A hand-written directory reader, since library readers inflate first. A closed type list: every new media type is a code change. A verify pass that reads every file once more. Refusal messages good enough that a legitimate author can fix their file.

## Where it came from

A client application whose content packages are zip archives, imported from users and also shipped with the app. The reader accepts a handful of extensions, hands each to a format parser, and never calls the runtime's general resource loader on package bytes, because a native resource can carry a script. The directory is read by hand first: caps on file count and on total unpacked size (tens of files, tens of megabytes), names that cannot leave the folder, every file listed in an index with its hash, and a cap on image dimensions.

On 2026-09-18, **nine packages broken on purpose** — a native resource with a script, `../` in a name, an altered file, an unlisted file, hundreds of megabytes of zeros that deflate to under a megabyte, an image over the dimension cap, text under an audio extension, a reference pointing into the application, a newer format number — were all refused, by the offline tool and by the app, each with its reason. The same test found a defect in the checker: after the first bad file it reported every later file as bad too, so the count of refusals was right and the reasons were not. An import is written beside the store, verified, validated as a whole and decoded, and only then renamed into place; a probe showed a damaged file and a non-package refused with nothing kept.

## Literature

- **OWASP, [File Upload Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/File_Upload_Cheat_Sheet.html).** Allowlist extensions, validate type by signature, sanitise names, limit size, and check decompressed size "by using secure methods". *Verified 2026-09-22 against the cheat sheet page.* **What we take:** the allowlist and name rules. **Where we differ:** it checks after decompression; we reject on the directory first and bound inflation too.
- **Fifield, 2019, ["A better zip bomb"](https://www.usenix.org/conference/woot19/presentation/fifield)** (USENIX WOOT). Overlapping entries make a 42 kB archive expand to 5.5 GB without nesting, and 10 MB to 281 TB at the limits of the format (Table 1; *corrected 2026-09-23 against the paper, which says 5.5 GB, not 5.4*). *Verified 2026-09-22 against the USENIX listing and abstract summaries; the author's page refused the fetch.* **What we take:** declared sizes are not a bound — the boundary above.
- **Snyk, 2018, ["Zip Slip"](https://security.snyk.io/research/zip-slip-vulnerability).** Path traversal through entry names, across archive libraries. *Verified 2026-09-22 against the disclosure page.* **What we take:** names are validated before anything is written.

## Evidence

**Measured in one repository:** 9 of 9 planted packages refused by two independent readers with correct reasons; one reason-reporting defect found by that test; an import that fails leaves nothing.

**Measured, 2026-09-22 — the gap did not open in this reader, and the directory check is not why.** Three further forged packages, with peak allocation recorded: an understated entry refused after a few kilobytes; twenty overlapping entries refused after a couple of kilobytes; and, the interesting one, **the lie told consistently in the directory and in the index was accepted at open** — the check the boundary is about did not reject it — and cost nothing anyway, its peak within a fraction of a percent of the honest package's, because the decompressor stops at the declared size and the read returned empty. One reader, three constructions: that is an occurrence, not a general result, and the boundary above stays as it is written.

**What the same probe found instead** is a disagreement between the two readers of one rule set: on two of the three, the implementation that runs before the engine raised an uncaught archive error and a traceback where the reader on the device named the file and carried on. Both agreed word for word on the third. Two implementations of one rule set held to the same verdicts and not to the same refusals is its own claim; it is a candidate, and a defect in that repository's roadmap, not part of this note.

What *would* settle it beyond one reader: run the same forged constructions — understated, overlapping and consistently misdeclared sizes, plus the nine planted packages — against a second, independently written reader, recording peak allocation and the reason each gives.
