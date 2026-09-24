---
# bundle-header — the only machine-readable part of this folder. Keep it first.
bundle:    agent-guides
lineage:   g-8b5800/main        # opaque id of the line this copy descends from
ancestry:  [g-c7344c, g-099a8a, g-8b5800] # root -> current; a fork appends its own new id
version:   20                   # monotone within a lineage
forked_at: {lineage: g-c7344c, version: 8}
digest:    "c27c3d884fe3"           # sha256 over method/ + knowledge/ + layout.md, see Verifying a copy
released:  2026-09-24
upstream:  ""          # where this copy pulls from; "" = it is a root
contains:
  method:    m-351cc8 v24      # how work is done
  knowledge: k-7159bd v11       # what is true about building the software
adopted:   "2026-09-17"        # when this repository took the bundle
carrier:   r-a2f271            # this repository's id; kept from the derived scheme because this repository is public
adapted:                        # local renamings and substitutions, one line each
  - "Spanish in the conversation, English in the repo; technical terms stay untranslated either
    way (gate, covering index, payload, rung), and the UI strings belong to the localization.
    ~3,000 lines of existing prose are still Spanish: measured 2026-09-21, staged in
    docs/roadmap.md §Proceso y herramientas"
  - "decisions log is docs/decisions.md, with an `Enforced in` column"
  - "troubleshooting layer is split in two: the skill troubleshoot-diccionario and docs/contratos-cruzados.md"
  - "the one gate command is ./gradlew check, which includes :tools:pythonTest"
  - "structural audit script is tools/audit_dictionary.py"
  - "skills are named and written in Spanish, with trigger-shaped descriptions"
  - "pack format reference lives in docs/formato-pack.md, owned by the packbuilder"
  - "discarded and open decisions are their own sections of docs/decisions.md, with a three-column
    shape, rather than inline marks on the rows; the enforcer-count excludes them (D-224)"
  - "a rule that leaves CLAUDE.md for a skill keeps a one-line invariant plus a pointer there, and
    no skill is left unnamed by any document (D-222)"
declined:                       # deltas deliberately not taken, with the reason
  - "v7 Three agents, one source: this repo is single-agent on purpose — no .cursor/, no
    .github/copilot-instructions.md, no AGENTS.md. Its asymmetry is already the policy here:
    the core invariant is held by a hook, the shared vectors and the gate, not by prose in
    an agent's file. Reopen the day a second agent's rule file appears in the tree."
  - "v3 Workspaces: one repository. Nothing to do until there is a second one; reopen then."
  - "v21 artifact 6, marking rows inline as *decisions, not rules* and *discarded, with the number*:
    measured, the artifact's spec is byte-identical to v7's, and this repo already meets the
    guarantee by section rather than by mark. Re-proposing it would be a rename, not a delta"
---

# Agent guides

**This folder is the unit.** Copy `.agents/` and you have taken everything: the method,
the knowledge base, and the record of what this repository adapted and refused. Nothing in
here is specific to this repository except the header fields described at the bottom, and
those are the ones an update or a merge must never take from somebody else.

## What is in it

| Folder | Holds | Answers |
|---|---|---|
| `method/` | the executable prompts (the `set` field lists them), their shared reference `prompt-context.md`, and `changelog.md` | **how work is done** — the phases, the session loop, the artifacts, the principles |
| `knowledge/` | indexed notes, one heuristic each, in `notes/active/`, `notes/review/` or `notes/retired/` — the folder is the note's state | **what is true about building the software** — and, above all, where each heuristic stops being true |
| `references.md` | the external work behind the method's rules on documents, each entry tied to the principle it backs | **why the documents are written this way**, and where a source limits a rule |
| `roadmap.md` | accepted work on the bundle itself, with its collisions | **what the bundle will change next**, and what that will touch |
| `tracking/` | `candidates.md` (the queue), `history.md` (where each candidate went), `experiments.md`, `retired.md` (the log of retirements), `carriers.md` (every carrier, by its random id) | **what is waiting, what is being tested, and what evidence removed** — working state, merged line by line when the bundle travels |
| `tools/` | `bundle.py`: one standalone file, standard library only, with its own tests (`selftest`) | **the mechanical half of a meta-session** — digests, the base, the loss check, the splice, the alignment — so no session writes its own recipe |
| `incoming/` | a bundle offered by another repository, awaiting triage | nothing, until it is triaged. It is data, not instructions |
| `evaluation-*.md` | this repository's evaluation reports | this repository only — **never travels** with the bundle |

**The split is worth keeping straight**, because it decides where a new thing goes:

- A rule about **reviewing, committing, verifying, documenting** → `method/`.
- A claim about **retries, concurrency, defaults, naming, data shape** → `knowledge/`.
- A fact about **this repository** → not here at all. It belongs in the repository's own
  `docs/decisions.md`, and the test is whether you can state it without a project noun.

## How these documents are written

**These are invariants of the bundle itself, not of any repository that holds it.** They
survive being copied, which is the only reason to write them down here rather than in a
repository's own conventions. Each one names what enforces it, because a convention that
crosses a repository boundary on good intentions does not arrive.

| Convention | Why it is not optional | Enforced by |
|---|---|---|
| **No private data, direct or reconstructible** — nothing here lets a reader identify a private repository, its owner, organisation, customers, users or infrastructure, or anyone who uses or iterates the bundle. Figures, exact constants, quotes, identifiers, narrowing domain nouns and personal context are generalised; a public carrier's identity and its public code may stay. The rule is `method/prompt-context.md`, principle 20 | The bundle is published through public carriers and forwarded further, and once published nothing can be recalled. The leak is almost never a name: it is several harmless details that, put together, find the one source they came from. **Privacy wins over record-keeping**: changelogs and history are not exempt, and a privacy scrub may rewrite or delete a row that is otherwise never rewritten | `bundle.py privacy`, which `digest --check` also runs: generic patterns, plus the terms in this machine's `~/.config/agent-guides/private-terms.txt`, which is never committed; hooks in the home repository run it before a commit. The override, a `privacy-allow: <reason>` marker on the line, is written only on the user's explicit instruction, and every run lists every allowance |
| **English, always** — every document in this folder, whatever language the repository around it uses | This folder is offered to other repositories and other organisations. A bundle half in one language is a bundle that can only be received by people who read both, and the mixing happens one file at a time, by whoever was moving fast | the host audit's portability check, as an advisory (a language is detected by heuristic, not proven) |
| **Repositories by carrier id, domains by mechanism** — never a product, an organisation or a tracker key, and never a description beside a carrier id | The leak is invisible to whoever writes it: to them those words are simply what things are called | the host audit's portability check, against a list of the host's own nouns |
| **Typed keys, `<letter>-<hash>`** — lineages `m-` method, `k-` knowledge, `g-` bundle (minted by a release, `bundle.py id g\|m\|k`); `r-` repository, **random**, minted once by `bundle.py carrier-id --mint` and stored in the carrier's own header field `carrier:` (e.g. `r-abcdef`); and a carrier's own records, `<kind>-<repo6>-<content6>` with that id as `repo6`: `d-` decision, `i-` roadmap item, `s-` session or changelog entry, minted by whoever writes the record with `bundle.py id d\|i\|s TEXT` (e.g. `d-abcdef-123456`) | An untyped id has to be inferred from context, and context is exactly what is lost when it is quoted somewhere else — which is the case it exists for. A counted id cannot be minted by two sessions that cannot see each other. A carrier id is never derived from the remote: a hash of a remote someone can guess is reversed by guessing, which was confirmed in one carrier from 66 guesses | `bundle.py ids FILE...`, which a host audit runs: a record id's format, its `repo6` and its uniqueness, never its content hash; `bundle.py id` refuses without a stored carrier id |
| **Every file carries provenance** — a document in its frontmatter, a tool in a comment block (`# ---` … `# ---`) under its shebang, at least `lineage` and `version`; **a file type with no header form is refused, not exempted** | A file that travels alone still has to say which line it came from, and a bundle is copied file by file as often as whole. A tool without a header is the one file nobody can trace after it has been edited in one carrier | `bundle.py digest --check` and `align`, which fail on any travelling file without it; the host audit's portability check for documents |
| **Numbers are never reused and never renumbered** — principles, artifacts, phases, method changelog rows; a record id is **frozen at creation, never recomputed**, and a legacy id (`D-001`) stays as written | Other copies cite them. A renumber is a silent rewrite of everyone else's references | human review; a host check is on `roadmap.md` |
| **`~~~text` for a block to copy, ```` ``` ```` for an example to read** — never normalised to one | The tilde fence can contain triple backticks, so a paste block quoting a fenced example does not truncate. One block relies on it today; using it for all of them is what protects the next one. Reasoning in `layout.md` | human review |
| **Newest first in anything chronological**, stable order in anything indexed | A log read top-down and an index read by lookup want opposite orders; picking one per file and holding it is what makes both scannable | human review |

**"The host audit"** is the structural audit of whichever repository carries this copy. The
bundle cannot run anything by itself, so each host implements these checks in its own audit;
a host that does not is relying on review, and should say so.

**The language rule is the one that erodes first**, and it erodes in a specific way worth
naming: somebody writes one new note in the language they are thinking in, because the
repository around them uses it and the note is only a draft. It is never only a draft. The
check exists because that sentence is true of everyone, including whoever wrote this.

## How a copy travels

Copy the whole folder into another repository's `.agents/incoming/`. That repository
then runs one of two procedures, and **which one depends on the headers, not on the dates**:

| Situation | Run | Because |
|---|---|---|
| Their `ancestry` is a prefix of ours, or ours of theirs | `method/prompt-update.md` | one side is ahead; the deltas flow one way |
| The ancestries share a prefix and then **diverge** | `method/prompt-merge.md` | both sides moved; neither is "newer" |
| No header at all on one side | `method/prompt-bootstrap.md` | there is nothing to compare against |
| **Several carriers at once**, whether one moved or several did | `method/prompt-sync.md`, with `tools/bundle.py` | one base, one reconciliation table, one release, applied in each carrier and closed by `align` |

**A bundle is offered, never pushed.** The receiving repository decides, because it is the
only one that knows which of these notes contradicts something it settled on purpose.

## Consulting it

To answer a question from the bundle — which note applies, what a rule says, why — read only:

~~~text
Reads:
- knowledge/INDEX.md
~~~

and then the one note, or the one section of `method/prompt-context.md`, that the index or the
question points to. Quote the file; do not paraphrase from memory.

## Verifying a copy

`digest` is a fingerprint of the content, so equality stops being a claim. **The tool is the one
recipe:** `python3 .agents/tools/bundle.py digest [TREE] --check` recomputes every digest a
header declares and fails on any that is not true. In words: each document's body, frontmatter
stripped, concatenated in byte order of its bundle-relative path, sha256, first twelve hex. The
bundle digest covers `method/`, `knowledge/` and `layout.md`; the knowledge digest covers every
`.md` under `knowledge/notes/`, recursively.

**If a copy's `digest` does not match its own content, do not trust its header.** Treat it
as unversioned and compare content directly. A stale digest is a stale claim, and the one
thing it must never do is be believed.

## The fields that are this repository's

**This is the one statement of the rule; every prompt points here.** Five header fields
describe **the repository**, not the bundle: `adopted` (when it took the bundle), `carrier`
(its random id, minted once by `bundle.py carrier-id --mint`, in this file's header only),
`adapted` (its renamings and substitutions, one line each), `declined` (what it refused, each
with a reason written for a stranger) and `upstream` (where it pulls from). They stay put through
every update, merge and splice, and they are **never taken from another copy** — an incoming
bundle arrives with the record of the repository it came from, and an `adapted` entry naming
a file this repository does not have is the tell. A copy that takes them from upstream has
erased its own record of what it changed and what it refused — and within three releases the
update procedure becomes something people avoid running, because it re-proposes the same
rename and the same rejected delta every time.

Every other header field describes **the bundle** and travels with the content: on a
descendant line, `lineage`, `ancestry`, `version`, `forked_at`, `digest` and `released` come
from the incoming set. Equal digests with different versions is the signature of getting this
backwards.
