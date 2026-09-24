#!/usr/bin/env python3
# bundle-header — provenance for this file. Identical across the bundle.
# ---
# bundle:    agent-guides
# lineage:   g-8b5800/main
# ancestry:  [g-c7344c, g-099a8a, g-8b5800]
# version:   10
# component: tool
# released:  2026-09-24
# ---
"""The mechanical half of a meta-session over every carrier of this bundle. Standard library only.

`method/prompt-sync.md` is the procedure; this is what it runs, so that no session re-derives a digest
recipe, a base or a splice by hand. Every command reads, except six: `splice --write` (which backs up
what it rewrites first), `stamp`, `register` and `note-state` write into the tree they are given,
`carrier-id --mint` writes one line into a repository's README header, and `gather` writes its own
output folder and nothing else.

    python3 .agents/tools/bundle.py digest [TREE] [--check]      the three digests, one recipe; --check adds
                                                                 provenance, links, reachability, session
                                                                 reads and privacy
    python3 .agents/tools/bundle.py carrier-id [REPO] [--mint]   a repository's stored id; --mint writes a
                                                                 random one where there is none
    python3 .agents/tools/bundle.py id g|m|k PART...             a new lineage id, derived
    python3 .agents/tools/bundle.py id d|i|s TEXT... [--repo R]  a record id: decision, roadmap item, session
    python3 .agents/tools/bundle.py ids [--carrier REPO] FILE... record ids in files: malformed, defined twice,
                                                                 or defined under another carrier's id
    python3 .agents/tools/bundle.py privacy [TREE] [--paths FILE...] [--terms FILE]
                                                                 nothing that identifies a private repository,
                                                                 its people or its infrastructure
    python3 .agents/tools/bundle.py note-state SLUG active|review|retired [TREE]
                                                                 move a note between states, links rewritten
    python3 .agents/tools/bundle.py report [TREE] [--json]       size per folder and per session type
    python3 .agents/tools/bundle.py gather [REPO...] --out DIR   phase 1: snapshots, base, N-way table
    python3 .agents/tools/bundle.py lost BASE MERGED SNAP...     nothing any carrier added is missing
    python3 .agents/tools/bundle.py stamp TREE [--bump K,...] [--fork K=ID] [--against DIR]
    python3 .agents/tools/bundle.py register TREE [REPO...]      the carriers table, by stored carrier id
    python3 .agents/tools/bundle.py splice MERGED [REPO...] [--write --backup DIR] [--allow-dirty]
    python3 .agents/tools/bundle.py check-local [REPO...]        the local step wrote only `tracking/`
    python3 .agents/tools/bundle.py align [REPO...]              phase 3: every carrier on one version
    python3 .agents/tools/bundle.py selftest                     the tool's own tests, in this file

With no REPO, the repositories are this session's workspace: `AGENT_WORKSPACE` if it is set, else the
local manifest (see `MANIFEST`), which lists this machine's paths and never travels with the bundle. A
carrier of the manifest that the workspace does not hold is **named and never written**: only the
repositories a session has open are edited, and all of them are.
"""

from __future__ import annotations

import argparse
import builtins
import datetime
import functools
import hashlib
import io
import json
import math
import os
import posixpath
import re
import secrets
import shutil
import subprocess
import sys
import tarfile
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Callable
from urllib.parse import unquote

try:
    import tomllib
except ModuleNotFoundError:  # before Python 3.11; the manifest is read by `manifest_carriers` instead
    tomllib = None  # type: ignore[assignment]

# This script lives in `<repo>/.agents/tools/`, so its own bundle and repository are found from here and
# never from the working directory: it runs the same from any folder.
HERE = Path(__file__).resolve().parent
OWN_BUNDLE = HERE.parent
OWN_REPO = OWN_BUNDLE.parent

# Where this machine lists its carriers. Paths are per machine and never belong in the bundle: the
# bundle names a carrier only by its stored random id, in `tracking/carriers.md`.
MANIFEST = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config")) / "agent-guides/carriers.toml"

# The headers whose repository fields describe the repository and not the bundle: `upstream`, where
# this copy pulls from, and everything from `adopted:` to the closing fence. They stay the carrier's own
# through every splice, and `align` ignores them. `upstream` sits among the bundle's fields in the
# header, so it is named on its own: until 2026-09-24 only the tail was kept, and a carrier pointing at
# another upstream than the release's would have been rewritten to the release's.
OWN_FIELDS_START = "adopted:"
OWN_FIELD_UPSTREAM = re.compile(r"^upstream:.*\n", re.MULTILINE)
# The carrier's id (`carrier:`, see `repo_carrier_id`) is one of them, written after `adopted:`. It is
# named on its own too, because a release tree is authored in a carrier and holds that carrier's id:
# a splice into a carrier whose header has no tail to graft kept the release's line, and two carriers
# would then answer to one id.
OWN_FIELD_CARRIER = re.compile(r"^carrier:.*\n", re.MULTILINE)

CARRIERS_TABLE = (
    "\n# Carriers\n\n"
    "Every repository known to carry this bundle, by its random id (`bundle.py carrier-id`), and the\n"
    "version it was last aligned to. Paths are never written here; they are per machine.\n\n"
    "| Carrier | Bundle | Method | Knowledge | Aligned on |\n"
    "|---|---|---|---|---|\n"
)


# How a session says which repositories it has open. A machine carries more of this bundle than any
# session works on: the workspace is **all** the repositories open in this session and **only** those,
# because a carrier nobody is looking at must not receive a release, and an open one must not be left
# behind on an older version. Declared by argument, or by `AGENT_WORKSPACE` (paths separated as this
# platform separates them); the manifest is the fallback and says so.
WORKSPACE_ENV = "AGENT_WORKSPACE"


class NotACarrierError(RuntimeError):
    """A path given as a repository of the workspace holds no `.agents` bundle."""


class OutsideWorkspaceError(RuntimeError):
    """A carrier that this session does not have open, and therefore does not write to."""


class AlreadyBumpedError(RuntimeError):
    """A version already ahead of the release it is being bumped against: bumping again skips a number."""


class UndeclaredScopeError(RuntimeError):
    """Raised when a command that writes was given no scope, and would otherwise take every carrier."""


class DirtyTreeError(RuntimeError):
    """A carrier's bundle holds uncommitted changes that a splice would overwrite."""


class RefusedError(RuntimeError):
    """A request the tool will not carry out as given: an empty set, an unknown name, an ambiguous key.

    Each of these used to surface as a traceback (`IndexError`, `KeyError`, `CalledProcessError`) or,
    worse, as silence; a refusal says what was asked and why it is not done, and exits 2.
    """


# --- documents -------------------------------------------------------------------------------------


def body(text: str) -> str:
    """A document without its frontmatter, exactly as the shell recipe's `awk` prints it.

    Including when the frontmatter never closes: the recipe in the bundle README opens it on line 1
    and prints nothing until a closing `---`, so an unclosed one hides the whole rest of the file.
    This function used to keep the file whole in that case, and the two halves of "one recipe" then
    hashed the same broken document to two digests.
    """
    lines = text.split("\n") if text else []
    if text.endswith("\n"):
        lines = lines[:-1]
    if lines and lines[0] == "---":
        try:
            lines = lines[lines.index("---", 1) + 1 :]
        except ValueError:
            lines = []
    return "".join(line + "\n" for line in lines)


# How each kind of file carries its provenance. A document has yaml frontmatter; a file that cannot
# have one carries the same fields as a comment block, `# ---` to `# ---`, under its shebang. A file
# type missing from here has NO provenance form, and `provenance_problems` fails on it: a new kind of
# file is not exempt because nobody taught the tool its comment.
COMMENT_FORMS = {".py": "# "}
# What every header carries, notes included: the line it descends from and its version on that line.
PROVENANCE_FIELDS = ("lineage", "version")


def _header_span(text: str, suffix: str) -> tuple[int, int] | None:
    """Where a file's header block starts and ends, in characters, or None when it has none."""
    if suffix == ".md":
        if not text.startswith("---\n"):
            return None
        end = text.find("\n---\n", 4)
        return (0, end + 5) if end != -1 else None
    prefix = COMMENT_FORMS.get(suffix)
    if prefix is None:
        return None
    fence = prefix + "---\n"
    start = text.find(fence)
    if start == -1 or (start and text[start - 1] != "\n"):
        return None
    end = text.find("\n" + fence, start + len(fence) - 1)
    return (start, end + 1 + len(fence)) if end != -1 else None


def header(path: Path) -> str:
    """A file's provenance header, always in frontmatter form (`---` ... `---`), or the empty string.

    A comment header (`# ---` ... `# ---`) comes back with its comment prefix removed, so every field
    reads the same way whatever kind of file carries it.
    """
    text = path.read_text(encoding="utf-8")
    span = _header_span(text, path.suffix)
    if span is None:
        return ""
    block = text[span[0] : span[1]]
    prefix = COMMENT_FORMS.get(path.suffix)
    if prefix is None:
        return block
    return "".join(
        line[len(prefix) :] if line.startswith(prefix) else line.lstrip("#").lstrip() + ("\n" if not line.lstrip("#").lstrip() else "")
        for line in block.splitlines(keepends=True)
    )


def without_header(path: Path) -> str:
    """A file with its provenance header removed: what changes when the file itself changes."""
    text = path.read_text(encoding="utf-8")
    span = _header_span(text, path.suffix)
    return text if span is None else text[: span[0]] + text[span[1] :]


def field(front: str, name: str) -> str | None:
    """One top-level field's value, without its trailing comment."""
    match = re.search(rf"^{name}:[ \t]*(.*?)[ \t]*(?:#.*)?$", front, re.MULTILINE)
    return match.group(1) if match else None


def set_field(front: str, name: str, value: str) -> str:
    """Replaces one top-level field's value, keeping its alignment and its comment."""
    pattern = rf"^({name}:[ \t]*)(.*?)([ \t]*#.*)?$"
    if not re.search(pattern, front, re.MULTILINE):
        return front
    return re.sub(
        pattern, lambda m: f"{m.group(1)}{value}{m.group(3) or ''}", front, count=1, flags=re.MULTILINE
    )


def own_fields(front: str) -> str:
    """The repository's own fields of a header: its `upstream` line, then its tail from `adopted:`."""
    start = front.find("\n" + OWN_FIELDS_START)
    upstream = OWN_FIELD_UPSTREAM.search(front)
    return (upstream.group(0) if upstream else "") + ("" if start == -1 else front[start + 1 : front.rindex("---\n")])


def without_own_fields(front: str) -> str:
    """A header with the repository's own fields taken out: what must be equal in every carrier."""
    start = front.find("\n" + OWN_FIELDS_START)
    front = front if start == -1 else front[: start + 1] + "---\n"
    return OWN_FIELD_CARRIER.sub("", OWN_FIELD_UPSTREAM.sub("", front))


def with_own_fields(front: str, own: str) -> str:
    """A header from the release carrying one carrier's own fields, as `own_fields` returned them.

    The release's own `carrier:` line never survives: it is the id of the carrier the release was
    authored in, and the carrier receiving it either has its own (in `own`) or has none yet.
    """
    front = OWN_FIELD_CARRIER.sub("", front)
    upstream = OWN_FIELD_UPSTREAM.match(own)
    if upstream:
        front = OWN_FIELD_UPSTREAM.sub(lambda _: upstream.group(0), front, count=1)
        own = own[upstream.end():]
    start = front.find("\n" + OWN_FIELDS_START)
    if start == -1 or not own:
        return front
    return front[: start + 1] + own + "---\n"


def sha12(text: str) -> str:
    return hashlib.sha256(text.encode()).hexdigest()[:12]


def is_local(rel: str) -> bool:
    """A file of the carrier and not of the bundle: an evaluation report, or anything offered in `incoming/`."""
    parts = rel.split("/")
    return (len(parts) == 1 and parts[0].startswith("evaluation-")) or (
        parts[0] == "incoming" and rel != "incoming/README.md"
    )


def _hidden(rel: str) -> bool:
    """A dotfile or anything under a dot-folder: `.DS_Store`, an editor's swap file, a cache."""
    return any(part.startswith(".") for part in rel.split("/"))


def travelling(tree: Path) -> list[str]:
    """Every file of the bundle that travels, as paths relative to the tree, in byte order.

    Dotfiles never travel: a `.DS_Store` written by a file browser was otherwise a travelling file
    with no provenance form, spliced into every carrier and failing every check there.
    """
    return sorted(
        (
            rel
            for p in tree.rglob("*")
            if p.is_file()
            and "__pycache__" not in p.parts
            and not _hidden(rel := p.relative_to(tree).as_posix())
            and not is_local(rel)
        ),
        key=str.encode,
    )


def _markdown(tree: Path) -> list[str]:
    """The travelling documents: what links, headings and sessions are read from."""
    return [rel for rel in travelling(tree) if rel.endswith(".md")]


# The note lifecycle: the folder a note sits in is its state, and there is no `status:` field to
# disagree with it. `review` is still indexed; `retired` is kept, and read only when asked for.
NOTE_STATES = ("active", "review", "retired")
NOTES = "knowledge/notes"


def _counterpart(tree: Path, rel: str) -> Path:
    """`tree/rel`, or the one note of the same name elsewhere under `knowledge/notes/`.

    A note that changed state changed folder and nothing else. Every comparison by path — `lost`
    against the merged tree, `stamp` against the previous release — otherwise reads the move as one
    note deleted and another written, and a moved note's every line as lost.
    """
    path = tree / rel
    if path.exists() or not rel.startswith(NOTES + "/"):
        return path
    name = rel.rsplit("/", 1)[-1]
    found = [p for p in (tree / NOTES).rglob(name) if p.is_file()] if (tree / NOTES).is_dir() else []
    return found[0] if len(found) == 1 else path


def own_headers(tree: Path) -> list[str]:
    """The documents whose header carries the repository's own fields."""
    return ["README.md", *sorted(str(p.relative_to(tree)) for p in tree.glob("method/prompt-*.md"))]


# --- digests ---------------------------------------------------------------------------------------


def _a_bundle(tree: Path) -> Path:
    """The tree, or a refusal: a digest computed over no documents is `sha256("")` and looks like one."""
    if not (tree / "README.md").exists() or not (tree / "method").is_dir():
        raise NotACarrierError(f"{tree} is not a bundle: expected `README.md` and `method/` in it")
    return tree


def _digest(tree: Path, rels: list[str], what: str) -> str:
    """The recipe: bodies, frontmatter stripped, concatenated in byte order of the path; 12 hex of sha256.

    An empty set is refused for the reason `_a_bundle` exists: `sha256("")` is a digest too, and a
    glob that stopped matching (a folder renamed, notes moved one level down) would declare it with
    every check passing.
    """
    if not rels:
        raise RefusedError(f"{tree}: no documents for the {what} digest; a digest over nothing reads like an answer")
    return sha12("".join(body((tree / r).read_text(encoding="utf-8")) for r in sorted(rels, key=str.encode)))


def _rels(tree: Path, pattern: str) -> list[str]:
    return [p.relative_to(tree).as_posix() for p in tree.glob(pattern) if p.is_file() and not _hidden(p.relative_to(tree).as_posix())]


def bundle_digest(tree: Path) -> str:
    """`method/`, `knowledge/` and `layout.md`, every `.md`, in byte order of the path (LC_ALL=C)."""
    rels = [*_rels(_a_bundle(tree), "method/**/*.md"), *_rels(tree, "knowledge/**/*.md")]
    return _digest(tree, [*rels, "layout.md"] if (tree / "layout.md").exists() else rels, "bundle")


def method_digest(tree: Path) -> str:
    """`method/prompt-*.md` in byte order of the name."""
    return _digest(tree, _rels(_a_bundle(tree), "method/prompt-*.md"), "method")


def knowledge_digest(tree: Path) -> str:
    """Every `.md` under `knowledge/notes/`, at any depth, in byte order of the bundle-relative path.

    Recursive because the folder a note sits in is its state (`active/`, `review/`, `retired/`), and
    a retired note is still part of the knowledge a carrier holds. Only bodies are hashed, never the
    paths; the path decides the order alone, so a move can still change the digest by reordering.
    """
    return _digest(tree, _rels(_a_bundle(tree), NOTES + "/**/*.md"), "knowledge")


def _declared(path: Path) -> str | None:
    value = field(header(path), "digest") if path.exists() else None
    return value.strip('"') if value else None


def digest_problems(tree: Path) -> list[str]:
    """Every header whose declared digest is not the digest of the content it describes."""
    problems = []
    checks = [("README.md", "bundle", bundle_digest(tree)), ("knowledge/README.md", "knowledge", knowledge_digest(tree))]
    checks += [(str(p.relative_to(tree)), "method", method_digest(tree)) for p in sorted(tree.glob("method/prompt-*.md"))]
    for rel, kind, computed in checks:
        declared = _declared(tree / rel)
        if declared != computed:
            problems.append(f"{rel}: declares {kind} digest {declared!r}, content is {computed}")
    return problems


def provenance_problems(tree: Path) -> list[str]:
    """Every travelling file that does not say which line it came from.

    A file with no provenance cannot be traced once it is copied alone, and a bundle is copied file by
    file as often as whole: that is why every file carries a header, a tool as much as a document.
    """
    problems = []
    for rel in travelling(tree):
        path = tree / rel
        if path.suffix != ".md" and path.suffix not in COMMENT_FORMS:
            problems.append(f"{rel}: no provenance form for `{path.suffix or 'no extension'}` files -- teach COMMENT_FORMS its comment")
            continue
        front = header(path)
        if not front:
            problems.append(f"{rel}: carries no provenance header")
            continue
        missing = [name for name in PROVENANCE_FIELDS if not field(front, name)]
        if missing:
            problems.append(f"{rel}: its header lacks {', '.join(missing)}")
    return problems


# --- links -----------------------------------------------------------------------------------------
# A link is `](target)`, optionally `](<target> "title")`. Only relative ones are the bundle's to keep
# true: a scheme (`https:`, `mailto:`), an absolute path and a bare `#anchor` are skipped. Fenced
# blocks and inline code are examples, not pointers, and are skipped too.

LINK = re.compile(r"\]\(\s*<?([^()\s<>]+)>?(?:\s+(?:\"[^\"]*\"|'[^']*'))?\s*\)")
INLINE_CODE = re.compile(r"(`+).+?\1")
FENCE = re.compile(r"^[ \t]{0,3}(`{3,}|~{3,})")


def _prose(lines: list[str]) -> list[bool]:
    """For each line, whether it is prose: outside every fenced block, fence lines included."""
    out, fence = [], None
    for line in lines:
        m = FENCE.match(line)
        if fence is None and m:
            fence = m.group(1)
            out.append(False)
        elif fence is not None:
            if m and m.group(1)[0] == fence[0] and len(m.group(1)) >= len(fence) and not line.strip()[len(m.group(1)):].strip():
                fence = None
            out.append(False)
        else:
            out.append(True)
    return out


def _each_link(text: str, visit) -> tuple[str, int]:  # noqa: ANN001 -- visit: (target) -> str | None
    """Calls `visit` on every link target in prose; a string it returns replaces that target.

    Returns:
        The text with the replacements made, and how many were made.
    """
    lines = text.split("\n")
    count = 0
    for i, (line, prose) in enumerate(zip(lines, _prose(lines))):
        if not prose or "](" not in line:
            continue
        code = [m.span() for m in INLINE_CODE.finditer(line)]
        pieces, at = [], 0
        for m in LINK.finditer(line):
            if any(a <= m.start() < b for a, b in code):
                continue
            new = visit(m.group(1))
            if new is not None and new != m.group(1):
                pieces += [line[at : m.start(1)], new]
                at = m.end(1)
                count += 1
        lines[i] = "".join(pieces) + line[at:]
    return "\n".join(lines), count


def _relative(target: str) -> tuple[str, str] | None:
    """(path, `#anchor` or ""), or None for a link that is not a relative path."""
    if target.startswith(("#", "/")) or re.match(r"^[A-Za-z][A-Za-z0-9+.-]*:", target):
        return None
    path, _, anchor = target.partition("#")
    return unquote(path), ("#" + anchor if anchor else "")


def _resolve(rel: str, target: str) -> str | None:
    """A link in document `rel`, as a bundle-relative path; None when relative to nothing in the bundle."""
    parts = _relative(target)
    if parts is None or not parts[0]:
        return None
    resolved = posixpath.normpath(posixpath.join(posixpath.dirname(rel), parts[0]))
    # Outside the bundle is the carrier's own repository, which differs per carrier and is not here.
    return None if resolved == ".." or resolved.startswith("../") else resolved


def _links(tree: Path, rel: str) -> list[tuple[str, str]]:
    """(target as written, bundle-relative path it resolves to) for every checkable link in `rel`."""
    found: list[tuple[str, str]] = []

    def visit(target: str) -> None:
        resolved = _resolve(rel, target)
        if resolved is not None:
            found.append((target, resolved))

    _each_link((tree / rel).read_text(encoding="utf-8"), visit)
    return found


def link_problems(tree: Path) -> list[str]:
    """Every relative link in a travelling document whose target is not in the tree.

    A dead pointer reads as a reference until somebody follows it, and a note moved between state
    folders makes one in every document that named it by path. `incoming/` contents and evaluation
    reports are the carrier's, not the bundle's, and are not read.
    """
    problems = []
    for rel in _markdown(tree):
        for target, resolved in _links(tree, rel):
            if not (tree / resolved).exists():
                problems.append(f"{rel}: links to {target}, which does not exist")
    return problems


def reachability_problems(tree: Path) -> list[str]:
    """Every active or under-review note that neither `knowledge/INDEX.md` nor an area index links to.

    The index is the mechanism: a note it does not route to is never read at the moment it applies.
    A retired note is exempt, because being out of the routing tables is what retiring it means.
    """
    indexes = [rel for rel in ("knowledge/INDEX.md",) if (tree / rel).exists()] + sorted(_rels(tree, "knowledge/areas/*.md"))
    linked = {resolved for index in indexes for _, resolved in _links(tree, index)}
    return [
        f"{rel}: an {rel.split('/')[2]} note that no index links to (knowledge/INDEX.md or knowledge/areas/*.md)"
        for state in ("active", "review")
        for rel in sorted(_rels(tree, f"{NOTES}/{state}/**/*.md"), key=str.encode)
        if rel not in linked
    ]


# --- privacy ---------------------------------------------------------------------------------------
# Nothing in the bundle may let a reader identify, directly or by putting details together, a private
# repository, its owner, organisation, customers, users or infrastructure, or any person who uses or
# iterates the bundle: it travels through public carriers. Until 2026-09-24 that was a sentence in the
# method, and a review that day found the bundle full of what it forbids -- figures, exact constants,
# quoted code, field names, time zones, and carrier ids that could be reversed -- each one written by a
# session that had the sentence in front of it. A rule that is only remembered is not kept.
#
# The rules are generic and need no private word to run: they are the shapes a leak takes. What only
# this machine knows -- the names of its private repositories, owners, customers -- lives in a local
# terms file that never travels (`default_terms_path`), and every term in it is a failure too.
#
# A FAIL is a leak. A WARN is advisory: a large exact count or a long quote is sometimes the evidence
# itself, and only a reader tells a fingerprint from a round number. `privacy-allow: <reason>` on a line
# (in markdown, inside an HTML comment) waives that line, only on the user's explicit instruction, and
# every waiver is printed on every run, so none is silent.

# Where the evidence of a note is written. What a carrier's own code said is quoted and named there,
# so there, and in all of `tracking/`, code names and long quotes are read as what they usually are.
EVIDENCE_HEADINGS = ("where it came from", "evidence")
# Where published work is cited. A version, a count or a title quoted from a paper names the paper, not
# a carrier, so these sections and `references.md` are exempt from the quote, count and version rules.
LITERATURE_HEADINGS = ("literature",)
LITERATURE_FILES = ("references.md",)

# Mail domains the IETF reserves for examples and tests: an address there reaches nobody.
EXAMPLE_MAIL_DOMAINS = ("example.com", "example.org", "example.net", "example", "test", "invalid", "localhost")
EMAIL = re.compile(r"(?<![\w.%+-])([A-Za-z0-9._%+-]+)@([A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,})(?![\w-])")

# The forges whose paths are `owner/repo`. A path on one names somebody, and the owner is the part a
# reader follows to the rest of their work.
FORGE_HOSTS = ("github.com", "gitlab.com", "bitbucket.org", "codeberg.org", "gitea.com", "git.sr.ht",
               "gist.github.com", "raw.githubusercontent.com")
FORGE = re.compile(
    r"(?<![\w.-])(?:[a-z+]+://)?(?:[\w.-]+@)?(" + "|".join(map(re.escape, FORGE_HOSTS)) + r")(?::\d+)?[/:]~?([\w.-]+)(?:/([\w.-]+))?",
    re.IGNORECASE)
# Owners that are placeholders in examples (`owner/repo`), and first path segments of a forge that are
# its own pages rather than an account.
FORGE_PLACEHOLDER_OWNERS = frozenset({"owner", "user", "you", "your-org", "your-name", "your-user", "org", "example", "me", "someone", "name"})
FORGE_PAGES = frozenset({"about", "features", "pricing", "marketplace", "topics", "settings", "login", "apps", "enterprise",
                         "security", "site", "explore", "collections", "readme", "en", "docs", "solutions", "resources", "trending"})
# Public projects the bundle cites as literature, as `owner/repo`, lowercase. A citation names a project
# the world already knows; any other forge path names somebody. None is cited on a forge today.
PUBLISHED_PROJECTS: frozenset[str] = frozenset()

# A home folder carries its owner's login name. Placeholders written in examples are not anybody's.
HOME_PATH = re.compile(r"(?<![\w/.~-])(?:/Us(?:ers)/|/ho(?:me)/|[A-Za-z]:\\Us(?:ers)\\)([^\s/\\`'\"<>)\]]+)|(?<=/)-Users-([A-Za-z0-9._]+)-")
HOME_PLACEHOLDERS = frozenset({"you", "user", "username", "me", "name", "example", "runner", "shared", "someone", "$user", "${user}", "<you>"})

# An id somebody chose, `[A-Z]{2,}-\d{2,}`, is a tracker's key: it names the project it was chosen in.
CHOSEN_ID = re.compile(r"(?<![\w-])([A-Z]{2,})-(\d{2,})(?![\w-])")
# Prefixes of the same shape that name a published standard, report or primitive, never a project.
CITATION_ID_PREFIXES = frozenset({"RFC", "CVE", "CWE", "ISO", "IEC", "IEEE", "ECMA", "ES", "PEP", "UTF", "UCS", "SHA", "AES", "RSA",
                                  "CRC", "HMAC", "ECDSA", "FIPS", "NIST", "SP", "ANSI", "HTTP", "TLS", "WCAG", "COVID"})
# Whole citation ids of that shape, for a report number that holds a prefix-and-number pair inside it.
CITATION_IDS = frozenset({"ESD-TR-73-51"})

CURRENCY_CODES = "USD|EUR|GBP|JPY|CNY|CHF|CAD|AUD|NZD|MXN|BRL|ARS|CLP|COP|PEN|UYU|INR|KRW|SGD|HKD|SEK|NOK|DKK|PLN|ZAR"
_AMOUNT = r"\d{1,3}(?:[,.\u00a0\u202f ]\d{3})+(?:[.,]\d+)?|\d+(?:[.,]\d+)?"
CURRENCY = re.compile(
    rf"(?<![\w$\\{{])(?:US|CA|AU|NZ|HK|MX|R)?[$€£¥₹₩]\s?(?:{_AMOUNT})(?:\s?(?:k|K|M|MM|bn|million|thousand|billion)\b)?"
    rf"|\b(?:{CURRENCY_CODES})\s?\$?\s?(?:{_AMOUNT})"
    rf"|(?<![\w.])(?:{_AMOUNT})\s?(?:{CURRENCY_CODES}|(?i:dollars?|euros?|pesos?|yen))\b")
# `$1` is a shell's first argument far more often than a price, so a symbol before one lone digit is
# not read as an amount. A price that small fingerprints nothing.
SHELL_ARGUMENT = re.compile(r"^\$\d$")

# An offset or a zone name places a person on the map.
TIMEZONE = re.compile(
    r"\b(?:UTC|GMT)\s?[+\-−–]\s?\d{1,2}(?::?\d{2})?\b"
    r"|\b\d\d:\d\d(?::\d\d(?:\.\d+)?)?[+\-−]\d\d:?\d\d\b"
    r"|\bEtc/GMT[+-]\d+"
    r"|\b(?:Africa|America|Antarctica|Asia|Atlantic|Australia|Europe|Indian|Pacific)/[A-Z][A-Za-z_]+(?:/[A-Z][A-Za-z_]+)?")

# A pinned library version is the dependency list of one codebase, which a search engine matches.
_VERSION = r"v?\d+(?:\.\d+)+(?:[-+.]?(?:a|b|rc|f|p|post|dev)\d+)*"
VERSION_PIN = re.compile(
    rf"[\w.\[\]-]+\s?(?:===?|~=|>=|<=|!=|~>)\s?{_VERSION}"
    r"|(?<![\w.])@?[A-Za-z][\w./-]*@[\^~]?\d+\.\d+(?:\.\d+)*"
    r"|\"[@\w./-]+\"\s*:\s*\"[\^~>=<]*\s*\d+\.\d+(?:\.\d+)*[^\"]*\""
    r"|\b[A-Za-z][\w+.-]*\s+v?\d+\.\d+\.\d+(?:[-+][\w.]+)?(?![.\d])"
    r"|\b\d{4}\.\d+\.\d+[abfp]\d+\b")

# A carrier or record id next to what the carrier does ties the id to a kind of business. The list is
# broad on purpose: a list of only the domains this bundle's own carriers work in would name them.
CARRIER_OR_RECORD_ID = re.compile(r"(?<![\w-])(?:r-([0-9a-f]{6})|[dis]-([0-9a-f]{6})-[0-9a-f]{3,6})(?![\w-])")
# The hex of ids written as examples (`r-abcdef`, `d-abcdef-123456`): obviously nobody's.
EXAMPLE_ID_HEX = frozenset({"abcdef", "aaaaaa", "bbbbbb", "cccccc", "000000", "fedcba"})
DOMAIN_NOUNS = (
    "payment", "bank", "banking", "loan", "credit", "debt", "fraud", "card", "wallet", "invoice", "billing", "tax", "payroll",
    "insurance", "trading", "crypto", "national id", "passport", "citizen", "election", "voter", "court", "patient", "clinic",
    "hospital", "medical", "pharmacy", "student", "school", "university", "song", "music", "playlist", "album", "game",
    "character", "scene", "video", "podcast", "movie", "phone", "mobile", "tablet", "firmware", "iot", "controller", "sensor",
    "updater", "device fleet", "fleet", "vehicle", "drone", "robot", "printer", "cart", "checkout", "merchant", "retail",
    "restaurant", "booking", "hotel", "flight", "dating", "social network")
DOMAIN_NOUN = re.compile(r"\b(?:" + "|".join(re.escape(n) for n in DOMAIN_NOUNS) + r")(?:e?s)?\b", re.IGNORECASE)

# Names in code: `camelCase`, `PascalCase` with two humps or more, `snake_case`, `SCREAMING_CASE`.
CODE_NAME_FORMS = (
    re.compile(r"^_*[a-z][a-z0-9]*[A-Z][A-Za-z0-9]*$"),
    re.compile(r"^[A-Z][a-z0-9]+(?:[A-Z][a-z0-9]*)+$"),
    re.compile(r"^_*[a-z0-9]+(?:_[a-z0-9]+)+_*$"),
    re.compile(r"^_*[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+_*$"),
)
# Keys and names that vendors publish: an API's parameters, a CI's variables, a product's spelling.
# Naming one says which tool was used, which thousands of repositories share, not which repository.
VENDOR_KEYS = frozenset({
    "max_tokens", "stop_reason", "stop_sequences", "tool_use", "tool_result", "tool_choice", "cache_control", "input_schema",
    "pull_request", "workflow_dispatch", "GITHUB_TOKEN", "GITHUB_OUTPUT", "GITHUB_ENV", "XDG_CONFIG_HOME", "LC_ALL",
    "PYTHONPATH", "node_modules", "site_packages", "iOS", "macOS", "iPadOS", "tvOS", "watchOS", "GitHub", "GitLab",
    "JavaScript", "TypeScript", "PostgreSQL", "MySQL", "SQLite", "MongoDB", "OpenAPI", "WebSocket", "PowerShell", "DuckDB",
    "BigQuery", "PyPI", "YouTube", "LaTeX", "NumPy", "SciPy", "PyTorch", "TensorFlow", "FastAPI", "OpenAI", "DeepMind",
    "WebAssembly", "GraphQL", "DynamoDB", "CloudFormation", "PyInstaller", "OAuth",
})
# The bundle's own vocabulary, which every carrier already holds: header fields, the report's keys, the
# workspace variable. The tool's own names and Python's are added in `_public_names`.
BUNDLE_NAMES = frozenset({"forked_at", "retired_because", "superseded_by", "tokens_estimate", "context_share", "base_rule",
                          "base_from", "AGENT_WORKSPACE", "CLAUDE_md", "AGENTS_md"})

QUOTE = re.compile(r"\"([^\"\n]+)\"|“([^”\n]+)”")
BLOCKQUOTE = re.compile(r"^\s*>\s+(.*\S)")
# A large number, with or without thousands separators; years, dates, ids and decimals are not read.
LARGE_NUMBER = re.compile(r"(?<![\w.,:/#@$€£¥-])(\d{1,3}(?:[,\u00a0\u202f ]\d{3})+|\d{4,})(?![\w/:@%-]|[.,]\d)")
N_OF_M = re.compile(r"\b\d+\s+(?:of|out of)\s+(?:the\s+)?\d+\b")
# The waiver. Assembled, so that this file's own source does not read as a waiver of this line.
PRIVACY_ALLOW = re.compile("privacy-" + r"allow:[ \t]*([^`\n]*?)[ \t]*(?:-->|$)")
# A waiver written as documentation (`<reason>`) is an example of the syntax, not a waiver.
PLACEHOLDER_REASON = re.compile(r"^<[^>]*>$")
# What makes a fenced block an example rather than a record: a placeholder a reader has to replace.
PLACEHOLDER = re.compile(r"<[A-Za-z][\w .-]*>|\bexample\.(?:com|org|net)\b|\b(?:owner|OWNER)/(?:repo|REPO)\b|\b(?:YOUR|your)[-_]")


def default_terms_path() -> Path:
    """The local private terms file: one term per line, case-insensitive, never committed anywhere."""
    return Path(os.environ.get("XDG_CONFIG_HOME") or Path.home() / ".config") / "agent-guides/private-terms.txt"


def read_terms(path: Path) -> list[str]:
    """The terms of a terms file; blank lines and `#` comments skipped."""
    lines = path.read_text(encoding="utf-8").splitlines()
    return [line.strip() for line in lines if line.strip() and not line.strip().startswith("#")]


@functools.lru_cache(maxsize=None)
def _public_names() -> frozenset[str]:
    """Names that are public wherever they appear: vendor keys, the bundle's own, this tool's, Python's.

    This tool travels in every carrier, so its own names are already public; so are Python's builtins
    and the standard library modules it imports, which an incident about the tool names in its evidence.
    """
    names = set(VENDOR_KEYS) | set(BUNDLE_NAMES) | set(globals()) | set(dir(builtins))
    for module in (os, re, sys, io, json, math, hashlib, shutil, subprocess, tarfile, tempfile, argparse, datetime, posixpath,
                   secrets, functools, unittest, Path, str, dict, list, unittest.TestCase):
        names |= set(dir(module))
    return frozenset(names)


def _emails(text: str) -> list[str]:
    found = []
    for m in EMAIL.finditer(text):
        domain = m.group(2).lower()
        if any(domain == d or domain.endswith("." + d) for d in EXAMPLE_MAIL_DOMAINS):
            continue
        if m.group(1) == "git" and domain in FORGE_HOSTS:
            continue  # the ssh user of a public forge; the forge rule judges the path after it
        found.append(m.group(0))
    return found


def _home_paths(text: str) -> list[str]:
    return [m.group(0) for m in HOME_PATH.finditer(text) if (m.group(1) or m.group(2) or "").lower() not in HOME_PLACEHOLDERS]


def _forge_paths(text: str) -> list[str]:
    found = []
    for m in FORGE.finditer(text):
        owner, repo = m.group(2).lower(), (m.group(3) or "").lower().removesuffix(".git")
        if owner in FORGE_PLACEHOLDER_OWNERS or owner in FORGE_PAGES or f"{owner}/{repo}" in PUBLISHED_PROJECTS:
            continue
        found.append(m.group(0))
    return found


def _chosen_ids(text: str) -> list[str]:
    for citation in CITATION_IDS:
        text = text.replace(citation, " ")
    return [m.group(0) for m in CHOSEN_ID.finditer(text) if m.group(1) not in CITATION_ID_PREFIXES]


def _amounts(text: str) -> list[str]:
    return [m.group(0).strip() for m in CURRENCY.finditer(text) if not SHELL_ARGUMENT.match(m.group(0).strip())]


def _offsets(text: str) -> list[str]:
    return [m.group(0) for m in TIMEZONE.finditer(text)]


def _version_pins(text: str) -> list[str]:
    return [m.group(0) for m in VERSION_PIN.finditer(text)]


def _ids_beside_domains(text: str) -> list[str]:
    ids = [m.group(0) for m in CARRIER_OR_RECORD_ID.finditer(text) if (m.group(1) or m.group(2)) not in EXAMPLE_ID_HEX]
    nouns = [m.group(0) for m in DOMAIN_NOUN.finditer(text)]
    return [f"{ids[0]} beside {nouns[0]!r}"] if ids and nouns else []


def _code_names(text: str) -> list[str]:
    found = []
    for span in INLINE_CODE.finditer(text):
        for token in re.findall(r"[A-Za-z_][A-Za-z0-9_]*", span.group(0)):
            if token not in _public_names() and any(form.match(token) for form in CODE_NAME_FORMS):
                found.append(token)
    return found


def _large_counts(text: str) -> list[str]:
    found = []
    for m in LARGE_NUMBER.finditer(text):
        digits = re.sub(r"\D", "", m.group(1))
        if len(m.group(1)) == 4 and 1900 <= int(digits) <= 2100:
            continue  # a year
        if len(digits.rstrip("0")) < 2:
            continue  # one significant figure: an order of magnitude, which is what the rule asks for
        found.append(m.group(1))
    return found


def _n_of_m(text: str) -> list[str]:
    return [m.group(0) for m in N_OF_M.finditer(text)]


def _quotes(text: str) -> list[str]:
    quoted = [q for m in QUOTE.finditer(text) if len((q := m.group(1) or m.group(2)).split()) >= 5]
    block = BLOCKQUOTE.match(text)
    return quoted + ([block.group(1)] if block and len(block.group(1).split()) >= 5 else [])


@dataclass(frozen=True)
class PrivacyRule:
    """One shape of leak: how bad it is, what finds it, and where it is not read.

    Attributes:
        level: "FAIL" (a leak; `privacy` exits 1 and `digest --check` fails) or "WARN" (advisory).
        find: The offending pieces of one line.
        evidence_only: Read only in *Where it came from* and *Evidence* sections and in `tracking/`.
        literature_exempt: Not read in *Literature* sections nor in `references.md`.
        placeholder_exempt: Not read in a fenced block that shows an obvious placeholder.
    """

    level: str
    find: Callable[[str], list[str]]
    evidence_only: bool = False
    literature_exempt: bool = False
    placeholder_exempt: bool = False


PRIVACY_RULES: dict[str, PrivacyRule] = {
    "email": PrivacyRule("FAIL", _emails, placeholder_exempt=True),
    "home-path": PrivacyRule("FAIL", _home_paths, placeholder_exempt=True),
    "forge-url": PrivacyRule("FAIL", _forge_paths, placeholder_exempt=True),
    "chosen-id": PrivacyRule("FAIL", _chosen_ids),
    "currency": PrivacyRule("FAIL", _amounts),
    "timezone": PrivacyRule("FAIL", _offsets),
    "version-pin": PrivacyRule("FAIL", _version_pins, literature_exempt=True),
    "id-beside-domain": PrivacyRule("FAIL", _ids_beside_domains),
    "code-identifier": PrivacyRule("FAIL", _code_names, evidence_only=True),
    "exact-count": PrivacyRule("WARN", _large_counts, literature_exempt=True),
    "n-of-m": PrivacyRule("WARN", _n_of_m, literature_exempt=True),
    "quote": PrivacyRule("WARN", _quotes, evidence_only=True, literature_exempt=True),
}


def term_hits(text: str, terms: list[str]) -> list[int]:
    """The positions, in the terms file, of every private term the line holds."""
    return [i for i, term in enumerate(terms, 1)
            if re.search(r"(?<![A-Za-z0-9])" + re.escape(term) + r"(?![A-Za-z0-9])", text, re.IGNORECASE)]


@dataclass(frozen=True)
class PrivacyLine:
    """One line to read, and the scope it sits in."""

    shown: str
    number: int
    text: str
    evidence: bool
    literature: bool
    placeholder_fence: bool


@dataclass(frozen=True)
class Finding:
    level: str
    rule: str
    where: str
    match: str


@dataclass
class PrivacyReport:
    """What one `privacy` run found, what it was told to let pass, and which private terms it used."""

    findings: list[Finding]
    allowances: list[tuple[str, str]]
    files: int
    terms: str

    @property
    def failures(self) -> list[Finding]:
        return [f for f in self.findings if f.level == "FAIL"]

    @property
    def warnings(self) -> list[Finding]:
        return [f for f in self.findings if f.level == "WARN"]

    def notes(self) -> list[str]:
        """The warnings, every waiver, and which terms were checked: printed whether or not anything failed."""
        return ([f"  ! WARN {f.where} {f.rule}: {f.match}" for f in self.warnings]
                + [f"  . allowed {where}: {reason}" for where, reason in self.allowances] + [f"  . {self.terms}"])

    def summary(self) -> str:
        counts: dict[str, int] = {}
        for f in self.findings:
            counts[f"{f.level} {f.rule}"] = counts.get(f"{f.level} {f.rule}", 0) + 1
        per_rule = ", ".join(f"{k} {v}" for k, v in sorted(counts.items()))
        return (f"privacy over {self.files} files: {len(self.failures)} FAIL, {len(self.warnings)} WARN, "
                f"{len(self.allowances)} allowed" + (f" ({per_rule})" if per_rule else ""))


def _bundle_rel(path: Path) -> str:
    """A file's path inside the nearest `.agents` folder above it, or its name when it is in none."""
    parts = path.resolve().parts
    if ".agents" in parts:
        at = len(parts) - 1 - parts[::-1].index(".agents")
        return "/".join(parts[at + 1 :])
    return path.name


def _privacy_lines(path: Path, rel: str, shown: str) -> list[PrivacyLine]:
    """Every line of a file with its scope: evidence, literature, and fenced with a placeholder.

    Headings are read in markdown only, outside its frontmatter and its fenced blocks, as `section`
    reads them; a section runs to the next heading of the same or a higher level.
    """
    text = path.read_text(encoding="utf-8", errors="replace")
    lines = text.split("\n")
    evidence_file, literature_file = rel.startswith("tracking/"), rel in LITERATURE_FILES
    if path.suffix != ".md":
        return [PrivacyLine(shown, n, line, evidence_file, literature_file, False) for n, line in enumerate(lines, 1)]
    span = _header_span(text, ".md")
    skip = text[: span[1]].count("\n") if span else 0
    prose = _prose(lines)
    placeholder = [False] * len(lines)
    i = 0
    while i < len(lines):
        if prose[i]:
            i += 1
            continue
        j = i
        while j < len(lines) and not prose[j]:
            j += 1
        shows = bool(PLACEHOLDER.search("\n".join(lines[i:j])))
        placeholder[i:j] = [shows] * (j - i)
        i = j
    out, stack = [], []  # stack: (level, heading text, lowercased)
    for n, line in enumerate(lines):
        m = HEADING.match(line) if prose[n] and n >= skip else None
        if m:
            level = len(m.group(1))
            stack = [h for h in stack if h[0] < level] + [(level, m.group(2).strip("*_ ").lower())]
        titles = [t for _, t in stack]
        evidence = evidence_file or any(t.startswith(EVIDENCE_HEADINGS) for t in titles)
        literature = literature_file or any(t.startswith(LITERATURE_HEADINGS) for t in titles)
        out.append(PrivacyLine(shown, n + 1, line, evidence, literature, placeholder[n] and not prose[n]))
    return out


def privacy_check(tree: Path | None = None, paths: list[Path] | None = None, terms_file: Path | None = None) -> PrivacyReport:
    """Reads every travelling file of a bundle, or the given files, for what could identify somebody.

    Args:
        tree: The `.agents` folder whose travelling files are read. Ignored when `paths` is given.
        paths: Files to read instead, anywhere: a hook checks a repository's README with the same rules.
        terms_file: The private terms; default `default_terms_path()`. A default that is missing means
            no terms, said in the report; a file named here that is missing is refused, because a typo
            would otherwise check nothing and pass.

    Returns:
        The findings, the waivers honoured, the number of files read, and a line about the terms.
    """
    if terms_file is not None and not terms_file.is_file():
        raise RefusedError(f"--terms {terms_file}: no such file")
    source = terms_file or default_terms_path()
    terms = read_terms(source) if source.is_file() else []
    # Printed with `~` for the home folder: this line is pasted into sessions, and a home path is one
    # of the things this check exists to catch.
    shown = str(source).replace(str(Path.home()), "~", 1)
    note = f"private terms: {len(terms)} from {shown}" if source.is_file() else f"no private terms checked: {shown} does not exist"
    if paths:
        targets = [(p, _bundle_rel(p), str(p)) for p in paths]
    else:
        tree = _a_bundle(tree if tree is not None else OWN_BUNDLE)
        targets = [(tree / rel, rel, rel) for rel in travelling(tree)]
    findings: list[Finding] = []
    allowances: list[tuple[str, str]] = []
    for path, rel, shown in targets:
        for line in _privacy_lines(path, rel, shown):
            where = f"{line.shown}:{line.number}"
            found: list[Finding] = []
            for name, rule in PRIVACY_RULES.items():
                if (rule.evidence_only and not line.evidence) or (rule.literature_exempt and line.literature) \
                        or (rule.placeholder_exempt and line.placeholder_fence):
                    continue
                found += [Finding(rule.level, name, where, hit) for hit in rule.find(line.text)]
            # The term itself is not printed: this output is pasted into sessions and changelogs, and
            # the one thing it must not carry is the word it caught.
            found += [Finding("FAIL", "private-term", where, f"term on line {i} of the terms file")
                      for i in term_hits(line.text, terms)]
            code = [m.span() for m in INLINE_CODE.finditer(line.text)]
            marker = next((m for m in PRIVACY_ALLOW.finditer(line.text)
                           if not any(a <= m.start() < b for a, b in code)), None)
            reason = marker.group(1).strip() if marker else ""
            if marker and PLACEHOLDER_REASON.match(reason):
                marker = None  # the syntax, written as an example of itself
            if marker and not reason:
                found.append(Finding("FAIL", "allow-without-reason", where, "a waiver must say why, and who asked"))
            elif marker:
                allowances.append((where, reason))
                found = []
            findings += found
    return PrivacyReport(findings, allowances, len(targets), note)



# --- sessions and their size ------------------------------------------------------------------------
# What each type of session loads is declared once, in the method: every invocation carries a `Reads:`
# list, one line per file, `- <bundle-relative path>` followed by ` §<heading>` for each section it
# needs (none: the whole file). A heading means only that section, from its line to the next heading
# of the same or a higher level. The tool reads those lists rather than keeping a copy of them: a table
# here would be a second source of truth, and the first draft of this command was exactly that, marked
# provisional until somebody reconciled it by hand. `SESSION_SOURCES` only says where each list is:
# the file, and which `Reads:` list in it counting from 0, because the bootstrap document carries two
# (the bootstrap itself, and the working invocation it installs for every coding session).
SESSION_SOURCES: dict[str, tuple[str, int]] = {
    "coding": ("method/prompt-bootstrap.md", 1),
    "consult": ("README.md", 0),
    "evaluate": ("method/prompt-evaluate.md", 0),
    "bootstrap": ("method/prompt-bootstrap.md", 0),
    "update": ("method/prompt-update.md", 0),
    "merge": ("method/prompt-merge.md", 0),
    "sync": ("method/prompt-sync.md", 0),
    "harvest": ("method/prompt-harvest.md", 0),
}
Sessions = "dict[str, list[tuple[str, str | None]]]"


def reads_lists(text: str) -> list[list[tuple[str, str | None]]]:
    """Every `Reads:` list in a document, in order, as (path, heading or None) parts."""
    lists, lines = [], text.split("\n")
    for i, line in enumerate(lines):
        if line.strip() != "Reads:":
            continue
        parts: list[tuple[str, str | None]] = []
        for item in lines[i + 1 :]:
            if not item.startswith("- "):
                break
            path, *headings = [piece.strip() for piece in item[2:].split(" §")]
            parts += [(path, h) for h in headings] if headings else [(path, None)]
        lists.append(parts)
    return lists


def sessions_of(tree: Path) -> tuple[dict[str, list[tuple[str, str | None]]], list[str]]:
    """The load set of every session type, read from the method's `Reads:` lists, and what is missing."""
    sessions, missing = {}, []
    for name, (rel, index) in SESSION_SOURCES.items():
        path = tree / rel
        found = reads_lists(path.read_text(encoding="utf-8")) if path.is_file() else []
        if index < len(found):
            sessions[name] = found[index]
        else:
            missing.append(f"session {name!r}: {rel} has no `Reads:` list number {index}")
    return sessions, missing


CONTEXT_WINDOW = 200_000
HEADING = re.compile(r"^(#{1,6})[ \t]+(.*?)[ \t]*#*[ \t]*$")


def section(path: Path, heading: str | None) -> str | None:
    """A document, or one section of it: the heading line to the next heading of the same or higher level.

    Headings are read outside the provenance header and outside fenced blocks, where `# CLAUDE.md`
    in a template is an example and not a section of the method. None when the heading is absent.
    """
    text = path.read_text(encoding="utf-8")
    if heading is None:
        return text
    span = _header_span(text, path.suffix)
    skip = text[: span[1]].count("\n") if span else 0
    lines = text.split("\n")
    prose = _prose(lines)
    start = level = None
    for i in range(skip, len(lines)):
        m = HEADING.match(lines[i]) if prose[i] else None
        if not m:
            continue
        if start is None and m.group(2) == heading:
            start, level = i, len(m.group(1))
        elif start is not None and len(m.group(1)) <= level:
            return "\n".join(lines[start:i]) + "\n"
    return None if start is None else "\n".join(lines[start:])


def _session_parts(tree: Path, entries: list[tuple[str, str | None]]) -> tuple[list[tuple[str, str]], list[str]]:
    """(label, text) for every part of one session that is found, and a problem for every part that is not."""
    parts, missing = [], []
    for pattern, heading in entries:
        rels = sorted(_rels(tree, pattern), key=str.encode) if any(c in pattern for c in "*?[") else [pattern]
        if not rels:
            missing.append(f"{pattern}: matches no file")
        for rel in rels:
            label = rel + (f" § {heading}" if heading else "")
            if not (tree / rel).is_file():
                missing.append(f"{rel}: no such file")
                continue
            text = section(tree / rel, heading)
            if text is None:
                missing.append(f"{rel}: no heading {heading!r}")
            else:
                parts.append((label, text))
    return parts, missing


def session_problems(tree: Path, sessions: dict[str, list[tuple[str, str | None]]] | None = None) -> list[str]:
    """Every session whose `Reads:` list is missing, or names a file or a heading the tree does not have."""
    missing: list[str] = []
    if sessions is None:
        sessions, missing = sessions_of(tree)
    return missing + [f"session {name!r}: {problem}" for name, entries in sessions.items() for problem in _session_parts(tree, entries)[1]]


def check_problems(
    tree: Path,
    sessions: dict[str, list[tuple[str, str | None]]] | None = None,
    privacy: PrivacyReport | None = None,
) -> list[str]:
    """Everything `digest --check` fails on: digests, provenance, dead links, unreachable notes, session
    reads, and every privacy FAIL. Privacy warnings are advisory and printed by the caller, not failed on.
    """
    privacy = privacy if privacy is not None else privacy_check(tree)
    return (
        digest_problems(tree)
        + provenance_problems(tree)
        + link_problems(tree)
        + reachability_problems(tree)
        + session_problems(tree, sessions)
        + [f"privacy: {f.where} {f.rule}: {f.match}" for f in privacy.failures]
    )


def _tokens(chars: int) -> int:
    return math.ceil(chars / 4)


def _folder(rel: str) -> str:
    """The folder a file is counted under: its first segment, two more under `knowledge/notes/` so states show."""
    parts = rel.split("/")
    if len(parts) == 1:
        return "(root)"
    if parts[:2] == ["knowledge", "notes"]:
        return "/".join(parts[:3]) if len(parts) > 3 else NOTES
    return parts[0]


def report(tree: Path, sessions: dict[str, list[tuple[str, str | None]]] | None = None) -> dict:
    """The size of what travels, per folder and per session type.

    Tokens are an **estimate**, `ceil(characters / 4)`, not a tokenizer's count: close enough to
    compare one release with the last and one session type with another, and labelled so nobody
    quotes it as a measurement.
    """
    sessions = sessions_of(tree)[0] if sessions is None else sessions
    folders: dict[str, dict[str, int]] = {}
    total = {"files": 0, "bytes": 0, "chars": 0}
    for rel in travelling(_a_bundle(tree)):
        data = (tree / rel).read_bytes()
        chars = len(data.decode("utf-8", errors="replace"))
        for row in (folders.setdefault(_folder(rel), {"files": 0, "bytes": 0, "chars": 0}), total):
            row["files"] += 1
            row["bytes"] += len(data)
            row["chars"] += chars
    for row in (*folders.values(), total):
        row["tokens_estimate"] = _tokens(row["chars"])
    by_session = {}
    for name, entries in sessions.items():
        parts, missing = _session_parts(tree, entries)
        chars = sum(len(text) for _, text in parts)
        tokens = _tokens(chars)
        by_session[name] = {
            "parts": [label for label, _ in parts],
            "missing": missing,
            "bytes": sum(len(text.encode()) for _, text in parts),
            "chars": chars,
            "tokens_estimate": tokens,
            "context_share": round(tokens / CONTEXT_WINDOW, 4),
        }
    return {
        "tree": str(tree),
        "tokens": "estimate: ceil(characters / 4)",
        "context_window": CONTEXT_WINDOW,
        "total": total,
        "folders": dict(sorted(folders.items(), key=lambda item: item[0].encode())),
        "sessions": by_session,
    }


def report_markdown(data: dict) -> str:
    """The report as two markdown tables, the estimate labelled in both."""
    lines = [
        f"# Bundle size — {data['tree']}",
        "",
        f"Tokens are an {data['tokens']}, not a tokenizer's count.",
        "",
        "| Folder | Files | Bytes | Tokens (est.) |",
        "|---|---:|---:|---:|",
        *[f"| {name} | {r['files']} | {r['bytes']} | {r['tokens_estimate']} |" for name, r in data["folders"].items()],
        f"| **total** | {data['total']['files']} | {data['total']['bytes']} | {data['total']['tokens_estimate']} |",
        "",
        f"| Session | Parts | Bytes | Tokens (est.) | Share of {data['context_window'] // 1000}k |",
        "|---|---:|---:|---:|---:|",
    ]
    for name, s in data["sessions"].items():
        flag = f" (missing: {'; '.join(s['missing'])})" if s["missing"] else ""
        lines.append(f"| {name}{flag} | {len(s['parts'])} | {s['bytes']} | {s['tokens_estimate']} | {s['context_share']:.1%} |")
    return "\n".join(lines) + "\n"


# --- ids -------------------------------------------------------------------------------------------


# A carrier's id is random, minted once, and stored in the carrier's own README header as `carrier:`,
# among the repository's fields after `adopted:`, so a splice keeps it and `align` ignores it.
#
# Until 2026-09-24 it was derived: six hex of the sha256 of the normalised `origin` remote, on the
# claim that it "names the repository without naming it". It did not. A hash of an input somebody can
# guess is reversed by guessing, and a remote is `host/owner/repo`, the one part worth hiding drawn from
# a short public list: confirmed 2026-09-24, one carrier reversed in 66 guesses from its owner's public
# repositories. Six random hex say nothing about the repository, and nothing can be computed back from
# them; what ties them to the repository is only that the repository stores them.
CARRIER_FIELD = "carrier"
CARRIER_ID = re.compile(r"^r-[0-9a-f]{6}$")


def git(repo: Path, *args: str, binary: bool = False) -> str | bytes:
    result = subprocess.run(["git", "-C", str(repo), *args], check=True, capture_output=True, text=not binary)
    return result.stdout


def stored_carrier_id(repo: Path) -> str | None:
    """The id a repository stores in `.agents/README.md`, or None when it stores none.

    Raises:
        NotACarrierError: If the repository holds no bundle README.
        RefusedError: If the stored value is not `r-` and six hex: a hand edit, and not an id to build on.
    """
    readme = repo / ".agents/README.md"
    if not readme.is_file():
        raise NotACarrierError(f"{repo}: no .agents/README.md, so no carrier id to read")
    value = field(header(readme), CARRIER_FIELD)
    if value is None:
        return None
    value = value.strip("\"'")
    if not CARRIER_ID.match(value):
        raise RefusedError(f"{readme}: `{CARRIER_FIELD}: {value}` is not `r-` and six hex")
    return value


def repo_carrier_id(repo: Path) -> str:
    """A repository's stored id, or a refusal that names the command minting one.

    Never computed as a fallback: an id worked out when none is stored would have to come from
    something about the repository, which is the reversible id this replaced, written into the
    carriers table by the first command that asked.
    """
    value = stored_carrier_id(repo)
    if value is None:
        raise RefusedError(
            f"{repo}: its .agents/README.md stores no `{CARRIER_FIELD}:` id; mint one with "
            f"`bundle.py carrier-id --mint {repo}`, once, and commit it")
    return value


def mint_carrier_id(repo: Path) -> str:
    """Writes a new random id into a repository's README header, after `adopted:`, and returns it.

    Refused when one is already stored, never replaced: records carry the id as their prefix
    (`d-<repo6>-...`) and the carriers table keys its rows by it, so a second id would orphan both.

    Raises:
        RefusedError: If an id is stored already, or the header has no `adopted:` line to follow.
    """
    readme = repo / ".agents/README.md"
    present = stored_carrier_id(repo)
    if present is not None:
        raise RefusedError(f"{repo}: already stores {present}; an id is minted once and never replaced")
    text = readme.read_text(encoding="utf-8")
    span = _header_span(text, ".md")
    adopted = re.search(r"^adopted:.*\n", text[: span[1]] if span else "", re.MULTILINE)
    if adopted is None:
        raise RefusedError(f"{readme}: its header has no `adopted:` line; the id is one of the repository's "
                           "own fields and goes after it")
    minted = "r-" + secrets.token_hex(3)
    line = f"{CARRIER_FIELD}:   {minted}".ljust(31) + "# this repository's id: random, minted once, never derived\n"
    readme.write_text(text[: adopted.end()] + line + text[adopted.end() :], encoding="utf-8")
    return minted


def carrier_ids(repos: list[Path]) -> dict[Path, str]:
    """Every repository's stored id, or a refusal when two of them store the same one.

    The folder is the unit: a bundle copied whole into a new repository brings the old one's README,
    id included, and the two would then be one row of the carriers table and one prefix of records.
    """
    ids = {repo: repo_carrier_id(repo) for repo in repos}
    seen: dict[str, Path] = {}
    for repo, value in ids.items():
        if value in seen:
            raise RefusedError(
                f"{seen[value]} and {repo} both store {value}: a bundle copied from one repository carries its id; "
                f"delete the `{CARRIER_FIELD}:` line in the copy and run `bundle.py carrier-id --mint` there")
        seen[value] = repo
    return ids


def derive_id(prefix: str, *parts: str) -> str:
    """A new lineage id derived from what it descends from, so two sessions cannot pick the same one."""
    return prefix + hashlib.sha256(" + ".join(parts).encode()).hexdigest()[:6]


# Records that parallel sessions write in one carrier: a decision, a roadmap item, a session entry.
# A counter (`D-017`) is what two sessions pick at once, and what one carrier's log shares with the
# next carrier's; an id derived from the carrier and the text is neither.
RECORD_KINDS = {"d": "decision", "i": "roadmap item", "s": "session entry"}


def record_id(kind: str, text: str, carrier: str) -> str:
    """`<kind>-<repo6>-<content6>`: the carrier's six hex, then six hex of the text, whitespace collapsed.

    Minted once, when the record is written, and frozen: a later edit to the record does not move
    its id, so an audit checks the form, the prefix and uniqueness, never the hash against the text.
    """
    if kind not in RECORD_KINDS:
        raise RefusedError(f"record kind {kind!r}: one of {', '.join(RECORD_KINDS)}")
    content = " ".join(text.split())
    if not content:
        raise RefusedError("a record id is minted from the record's text, and none was given")
    return f"{kind}-{carrier.removeprefix('r-')}-{hashlib.sha256(content.encode()).hexdigest()[:6]}"


# How a record id is found in a document, and what counts as writing one down rather than citing it.
# A record is defined once, where it is written: a row whose first cell is the id (`| d-... |`), or a
# heading that carries it after a `·` (`## 2026-01-01 · s-... — title`). Anywhere else the id is a
# citation, and a citation may repeat. Fenced blocks are examples and are not read.
RECORD_ID = re.compile(r"\b[dis]-[0-9a-f]{6}-[0-9a-f]{6}\b")
# The first scheme ended in a sequence number (`d-abcdef-017`). Ids written under it stay valid as
# written and are never rewritten, so they are recognised, not reported as malformed.
LEGACY_RECORD_ID = re.compile(r"\b[dis]-[0-9a-f]{6}-\d{3}\b")
# What looks like an attempt at one: the kind letter, two parts, a digit somewhere. `d-abcdef-12345`
# (a digit dropped) and `D-ABCDEF-123456` (a case changed) are ids somebody meant, and a check that
# only reads well-formed ids never sees the ones that went wrong.
RECORD_ID_ATTEMPT = re.compile(r"(?<![\w-])[disDIS]-([0-9A-Za-z]{2,12})-([0-9A-Za-z]{2,12})(?![\w-])")
RECORD_DEFINITION = re.compile(r"^\s*\|\s*([dis]-[0-9a-f]{6}-[0-9a-f]{3,6})\s*\||^#{1,6}\s.*·\s*([dis]-[0-9a-f]{6}-[0-9a-f]{3,6})\b")


def record_id_check(files: list[Path], carrier: str | None) -> tuple[list[str], list[str], dict[str, int]]:
    """The record ids of some files: what is wrong with them, what is suspect, and how many there are.

    An id is minted once and frozen, so an audit checks its form, its prefix and its uniqueness, never
    its hash against the text (`record_id`). Uniqueness is across every file given: the same record
    written down twice is two records that will be cited as one.

    The prefix is only a warning. A record this carrier writes carries this carrier's id, but the tool
    cannot tell this carrier's own records from a file that keeps another carrier's, so a definition
    under another prefix is named, with its file, and left to the reader.

    Args:
        files: The documents to read.
        carrier: This carrier's id (`r-...`), or None when it has none: then no prefix is checked.

    Returns:
        (errors: duplicates and malformed ids, warnings: another carrier's prefix, counts).
    """
    errors: list[str] = []
    warnings: list[str] = []
    counts = {"ids": 0, "definitions": 0, "citations": 0, "legacy": 0}
    defined: dict[str, str] = {}
    own = carrier.removeprefix("r-") if carrier else None
    for path in files:
        lines = path.read_text(encoding="utf-8", errors="replace").split("\n")
        for number, (line, prose) in enumerate(zip(lines, _prose(lines)), 1):
            if not prose:
                continue
            where = f"{path}:{number}"
            found_here = sorted([*RECORD_ID.finditer(line), *LEGACY_RECORD_ID.finditer(line)], key=lambda m: m.start())
            well_formed = {m.group(0) for m in found_here}
            for m in RECORD_ID_ATTEMPT.finditer(line):
                if m.group(0) not in well_formed and any(c.isdigit() for c in m.group(1) + m.group(2)):
                    errors.append(f"{where}: malformed record id {m.group(0)!r} (expected <d|i|s>-<6 hex>-<6 hex>)")
            definition = RECORD_DEFINITION.match(line)
            name = definition and (definition.group(1) or definition.group(2))
            if name and name not in well_formed:
                name = None  # a malformed id in a definition slot, reported above
            for index, occurrence in enumerate(found_here):
                found = occurrence.group(0)
                counts["ids"] += 1
                counts["legacy"] += bool(LEGACY_RECORD_ID.fullmatch(found))
                if found != name or index != [m.group(0) for m in found_here].index(name):
                    counts["citations"] += 1
                    continue
                counts["definitions"] += 1
                if found in defined:
                    errors.append(f"{where}: {found} defined twice (first at {defined[found]})")
                else:
                    defined[found] = where
                if own and found.split("-")[1] != own:
                    warnings.append(f"{where}: {found} is defined here under r-{found.split('-')[1]}, "
                                    f"not this carrier's {carrier}; fine only if this file keeps another carrier's records")
    return errors, warnings, counts


# --- stamp -----------------------------------------------------------------------------------------

SET_HEADERS = {"bundle": ["README.md"], "knowledge": ["knowledge/README.md"]}
STAMP_KINDS = ("bundle", "method", "knowledge")


def _set_docs(tree: Path, kind: str) -> list[Path]:
    if kind == "method":
        return sorted(tree.glob("method/prompt-*.md"))
    return [tree / rel for rel in SET_HEADERS[kind] if (tree / rel).exists()]


def _write_header(path: Path, front: str) -> None:
    """Writes a header given in frontmatter form back into the file, in that file's own form."""
    text = path.read_text(encoding="utf-8")
    span = _header_span(text, path.suffix)
    if span is None:
        return
    prefix = COMMENT_FORMS.get(path.suffix)
    if prefix is not None:
        front = "".join(prefix + line if line.strip() else prefix.rstrip() + "\n" for line in front.splitlines(keepends=True))
    path.write_text(text[: span[0]] + front + text[span[1] :], encoding="utf-8")


def _forked(front: str, new_id: str) -> str:
    lineage = (field(front, "lineage") or "").split("/")[0]
    version = int(field(front, "version") or 0)
    ancestry = (field(front, "ancestry") or "[]").strip("[]")
    names = [a.strip() for a in ancestry.split(",") if a.strip()]
    front = set_field(front, "lineage", f"{new_id}/main")
    front = set_field(front, "ancestry", "[" + ", ".join([*names, new_id]) + "]")
    front = set_field(front, "forked_at", f"{{lineage: {lineage}, version: {version}}}")
    front = set_field(front, "upstream", f'"{lineage}"')
    return set_field(front, "version", str(version + 1))


def stamp(
    tree: Path,
    bump: set[str] | None = None,
    forks: dict[str, str] | None = None,
    against: Path | None = None,
    released: str | None = None,
) -> None:
    """Rewrites a tree's headers: versions bumped or forked, per-document versions, the three digests.

    Args:
        tree: The `.agents` folder to stamp.
        bump: Kinds (`bundle`, `method`, `knowledge`) whose version goes up by one on the same lineage.
        forks: Kinds to fork, each to the given new id; the version continues from the old line.
        against: A previous tree; a document whose body differs from it gets its own version bumped.
        released: The release date. Defaults to today.
    """
    bump, forks = bump or set(), forks or {}
    _a_bundle(tree)
    # `--bump foo` used to be a `KeyError` from `SET_HEADERS`, and `--fork bundle=` an empty lineage
    # written into every header; a kind or an id the tool does not know is refused before anything.
    unknown = sorted((set(bump) | set(forks)) - set(STAMP_KINDS))
    if unknown:
        raise RefusedError(f"unknown kind {', '.join(unknown)}: --bump and --fork take {', '.join(STAMP_KINDS)}")
    empty = sorted(kind for kind, new_id in forks.items() if not re.match(r"^[A-Za-z0-9][\w.-]*$", new_id or ""))
    if empty:
        raise RefusedError(f"--fork {', '.join(empty)}: needs a new lineage id, as `KIND=ID` (see `bundle.py id`)")
    released = released or datetime.date.today().isoformat()
    # A bump run twice against the same previous release raises the version twice and says nothing,
    # which is how a number gets skipped between one carrier and the next.
    for kind in sorted(bump):
        docs = _set_docs(tree, kind)
        previous = _set_docs(against, kind) if against else []
        if docs and previous and int(field(header(docs[0]), "version") or 0) > int(field(header(previous[0]), "version") or 0):
            raise AlreadyBumpedError(
                f"{kind} is already at v{field(header(docs[0]), 'version')} against v{field(header(previous[0]), 'version')}: "
                "it has been bumped since that release; stamp without --bump, or bump against the release it follows"
            )
    for kind in STAMP_KINDS:
        for doc in _set_docs(tree, kind):
            front = header(doc)
            if kind in forks:
                front = _forked(front, forks[kind])
            elif kind in bump:
                front = set_field(front, "version", str(int(field(front, "version") or 0) + 1))
            _write_header(doc, set_field(front, "released", released))
    readme = tree / "README.md"
    bundle_front = header(readme)
    for doc in [tree / r for r in travelling(tree)]:
        rel = str(doc.relative_to(tree))
        if rel == "README.md" or rel.startswith("method/") or rel == "knowledge/README.md" or is_local(rel):
            continue
        front = header(doc)
        if not front:
            continue
        if "bundle" in forks:
            front = set_field(front, "lineage", field(bundle_front, "lineage") or "")
            front = set_field(front, "ancestry", field(bundle_front, "ancestry") or "")
        # A note that changed state is compared with itself in its old folder, not taken for new.
        previous = _counterpart(against, rel) if against else None
        if previous and previous.exists() and without_header(previous) != without_header(doc):
            # Only while this document is still at the base's number. `AlreadyBumpedError` guards the
            # three set versions; without the same guard here, stamping twice against one base raised
            # every changed document twice and said nothing -- a correction made to a release that has
            # not shipped burns a number nobody will ever hold. Seen 2026-09-22, re-stamping v17 after
            # a review: notes went 5 -> 6 on the first run and 6 -> 7 on the second.
            current, was = int(field(front, "version") or 0), int(field(header(previous), "version") or 0)
            if current <= was:
                front = set_field(front, "version", str(current + 1))
        _write_header(doc, set_field(front, "released", released))
    method_docs, knowledge_docs = _set_docs(tree, "method"), _set_docs(tree, "knowledge")
    front = header(readme)
    for kind, docs in (("method", method_docs), ("knowledge", knowledge_docs)):
        if docs:
            h = header(docs[0])
            label = f"{(field(h, 'lineage') or '').split('/')[0]} v{field(h, 'version')}"
            front = re.sub(rf"^(  {kind}:[ \t]+)\S+ v\d+", lambda m, label=label: m.group(1) + label, front, count=1, flags=re.MULTILINE)
    _write_header(readme, front)
    for doc, value in [(readme, bundle_digest(tree)), *[(d, method_digest(tree)) for d in method_docs],
                       *[(d, knowledge_digest(tree)) for d in knowledge_docs]]:
        _write_header(doc, set_field(header(doc), "digest", f'"{value}"'))


# --- note lifecycle --------------------------------------------------------------------------------


def note_state(tree: Path, slug: str, state: str) -> list[str]:
    """Moves one note to `knowledge/notes/<state>/` and rewrites every relative link to it in the bundle.

    The folder is the state, so changing a state is a move, and a move by hand leaves every path
    that named the note dead: the index, the area indexes, other notes, the method. Links are
    resolved from the document that holds them — `notes/x.md`, `../notes/x.md`,
    `../knowledge/notes/active/x.md` are all the same note — and written back relative to it; the
    moved note's own links are re-relativised from its new folder. Fenced examples, `incoming/` and
    evaluation reports are left as they are, as `link_problems` leaves them.

    Returns:
        One line per change made: the move, then each document whose links were rewritten.
    """
    _a_bundle(tree)
    if state not in NOTE_STATES:
        raise RefusedError(f"unknown state {state!r}: one of {', '.join(NOTE_STATES)}")
    notes = tree / NOTES
    found = [p for p in [notes / f"{slug}.md", *sorted(notes.glob(f"*/{slug}.md"))] if p.is_file()]
    if not found:
        raise RefusedError(f"no note {slug!r} in {notes} (looked in it and in {', '.join(NOTE_STATES)})")
    if len(found) > 1:
        raise RefusedError(f"note {slug!r} is in more than one place: {[p.relative_to(tree).as_posix() for p in found]}")
    old = found[0].relative_to(tree).as_posix()
    new = f"{NOTES}/{state}/{slug}.md"
    if old == new:
        return []
    changes = [f"moved {old} -> {new}"]
    rewritten: dict[str, str] = {}
    for rel in _markdown(tree):
        here = new if rel == old else rel

        def visit(target: str, rel: str = rel, here: str = here) -> str | None:
            resolved = _resolve(rel, target)
            if resolved is None or (resolved != old and rel != old):
                return None
            path, anchor = _relative(target)  # type: ignore[misc]  # not None: it resolved
            pointed = new if resolved == old else resolved
            moved = posixpath.relpath(pointed, posixpath.dirname(here) or ".")
            return moved + ("/" if path.endswith("/") and not moved.endswith("/") else "") + anchor

        text = (tree / rel).read_text(encoding="utf-8")
        updated, count = _each_link(text, visit)
        if count:
            rewritten[rel] = updated
            changes.append(f"rewrote {count} link{'s' if count > 1 else ''} in {here}")
    (tree / new).parent.mkdir(parents=True, exist_ok=True)
    for rel, text in rewritten.items():
        (tree / rel).write_text(text, encoding="utf-8")
    (tree / old).rename(tree / new)
    if state == "retired":
        changes.append(f"left for you: `retired_because:` (and `superseded_by:`) in {new}, and a line in tracking/retired.md")
    return changes


# --- phase 1: gather -------------------------------------------------------------------------------


def _extract(repo: Path, commit: str, into: Path) -> Path:
    """The `.agents` folder of one commit, extracted from `git archive` without touching the working tree."""
    data = git(repo, "archive", commit, ".agents", binary=True)
    with tarfile.open(fileobj=io.BytesIO(data)) as archive:  # type: ignore[arg-type]
        # `filter` exists from 3.12 and in the security releases of older lines. Without it the entries
        # still come from `git archive` of a commit of a carrier this session has open, not from an
        # archive somebody sent.
        if hasattr(tarfile, "data_filter"):
            archive.extractall(into, filter="data")
        else:
            archive.extractall(into)
    return into / ".agents"


def travelling_fingerprint(tree: Path) -> str:
    """A fingerprint of everything that travels, per-repository header fields stripped.

    Not `bundle_digest`, and the difference is the whole point. That digest covers the documents a
    release stamps and deliberately not `tracking/`, so every commit made *after* a release carries
    the release's digest -- and a base chosen by it lands on the newest such commit, which is
    whichever carrier the workspace lists first, harvest included. That carrier's own additions
    then read as `same`, they read as deletions in every other carrier, and `lost` goes blind on
    exactly them, because a line already in the base was never added over it. Measured 2026-09-22
    over three carriers: 7 additions of one of them invisible to both the table and the check.
    """
    parts = [(rel, _content(tree / rel) or "") for rel in sorted(travelling(tree), key=str.encode)]
    return hashlib.sha256("".join(f"{rel}\0{text}\0" for rel, text in parts).encode()).hexdigest()[:12]


def _names(repos: list[Path]) -> list[str]:
    """The name each carrier is reported and keyed by: its folder name, or a refusal.

    Two checkouts in folders of the same name used to share one key: the second snapshot overwrote
    the first, and the table and `lost` compared one carrier with itself and called it a set.
    """
    if not repos:
        raise RefusedError("no repositories in scope: name the carriers this session has open")
    names = [repo.name for repo in repos]
    twice = sorted({n for n in names if names.count(n) > 1})
    if twice:
        raise RefusedError(f"two carriers are both called {', '.join(twice)}: the tool keys carriers by folder name; "
                           "give each checkout its own folder name")
    return names


GATHER_MARKER = ".bundle-gather"


def _history(repo: Path, scratch: Path) -> list[tuple[str, str, Path]]:
    """(fingerprint, commit, extracted tree) for every commit that touched the bundle, newest first."""
    out = []
    commits = str(git(repo, "log", "--format=%H", "--", ".agents")).split()
    for commit in commits:
        tree = _extract(repo, commit, scratch / repo.name / commit)
        if (tree / "README.md").exists():
            out.append((travelling_fingerprint(tree), commit, tree))
    return out


def _moves(base: Path, tree: Path) -> dict[str, str]:
    """{old path: new path} for every file of the base this tree holds at another path, body unchanged.

    Paired by identical content (bodies, for documents), one to one: a note that changed state is
    one note moved, not one deleted and one new that the merge then has to notice are the same.
    """
    ours, based = set(travelling(tree)), set(travelling(base))
    by_content: dict[str | None, list[str]] = {}
    for rel in sorted(ours - based, key=str.encode):
        by_content.setdefault(_content(tree / rel), []).append(rel)
    moves = {}
    for old in sorted(based - ours, key=str.encode):
        candidates = by_content.get(_content(base / old))
        if candidates:
            moves[old] = candidates.pop(0)
    return moves


def classify(base: Path | None, trees: dict[str, Path]) -> dict[str, str]:
    """Every travelling file: `same`, `changed by …`, `both changed` / `all changed`, `new in …`,
    `removed in …`, or `moved OLD -> NEW in …` (both paths carry the same verdict).

    The base is read first. Without it, a file every carrier deleted was `same` (every carrier held
    the same nothing), and a file one carrier deleted was `changed by` it, which reads as an edit to
    merge rather than a removal to agree to.
    """
    rels = sorted({r for t in trees.values() for r in travelling(t)} | (set(travelling(base)) if base else set()), key=str.encode)
    moves = {name: _moves(base, t) for name, t in trees.items()} if base else {name: {} for name in trees}
    verdicts = {}
    for rel in rels:
        contents = {name: _content(t / rel) for name, t in trees.items()}
        original = _content(base / rel) if base else None
        parts = []
        if original is not None:
            gone = {name: moves[name][rel] for name in trees if rel in moves[name]}
            parts += [f"moved {rel} -> {to} in " + ", ".join(n for n, t in gone.items() if t == to) for to in sorted(set(gone.values()))]
            removed = [n for n, c in contents.items() if c is None and n not in gone]
            parts += ["removed in " + ", ".join(removed)] if removed else []
        else:
            came = {name: old for name in trees for old, to in moves[name].items() if to == rel}
            parts += [f"moved {old} -> {rel} in " + ", ".join(n for n, o in came.items() if o == old) for old in sorted(set(came.values()))]
            fresh = [n for n, c in contents.items() if c is not None and n not in came]
            if came and fresh:
                parts.append("new in " + ", ".join(fresh))
        held = [c for c in contents.values() if c is not None]
        if not parts and len(held) == len(contents) and len(set(held)) == 1:
            verdicts[rel] = "same"
            continue
        if original is None:
            verdicts[rel] = "; ".join(parts) or "new in " + ", ".join(n for n, c in contents.items() if c is not None)
            continue
        changed = [n for n, c in contents.items() if c is not None and c != original]
        if parts:
            verdicts[rel] = "; ".join(parts + (["changed by " + ", ".join(changed)] if changed else []))
            continue
        whole = "both changed" if len(trees) == 2 else "all changed"
        verdicts[rel] = whole if len(changed) == len(trees) else "changed by " + ", ".join(changed)
    return verdicts


def _content(path: Path) -> str | None:
    if not path.exists():
        return None
    text = path.read_text(encoding="utf-8")
    return body(text) if path.suffix == ".md" else text


def gather(repos: list[Path], out: Path) -> dict:
    """Phase 1, read-only: snapshot every carrier, verify it, find the common base, classify every file.

    The base is found **by content** first: the newest state of *everything that travels* —
    `travelling_fingerprint`, not the stamped digest — that every carrier's history once held. Only
    when there is none is a header believed — a commit whose README states the `forked_at` another
    carrier declares — and the report says which rule found it, and which carrier's commit it is.

    `out` is deleted and rewritten, so it must be absent, empty, or a previous gather's output (it
    carries `GATHER_MARKER`). Anything else is refused: without that, `--out .` meant `rmtree` of the
    folder the session stood in.
    """
    names = _names(repos)
    if out.exists() and (not out.is_dir() or (any(out.iterdir()) and not (out / GATHER_MARKER).exists())):
        raise RefusedError(f"{out}: exists and is not a previous gather's output (no {GATHER_MARKER}); "
                           "gather deletes its output folder, so give it a new or empty one")
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    (out / GATHER_MARKER).write_text("written by bundle.py gather; the whole folder is replaced by the next one\n")
    snapshots: dict[str, Path] = {}
    for repo, name in zip(repos, names):
        snapshots[name] = out / "carriers" / name / ".agents"
        shutil.copytree(repo / ".agents", snapshots[name], ignore=shutil.ignore_patterns("__pycache__"))
    histories = {name: _history(repo, out / "history") for repo, name in zip(repos, names)}
    base, rule, origin = None, "none", None
    first = names[0]
    common = set.intersection(*[{d for d, _, _ in h} for h in histories.values()])
    for fingerprint, commit, tree in histories[first]:
        if fingerprint in common:
            base, rule, origin = tree, "content", f"{first} {commit[:9]}"
            break
    if base is None:
        wanted = {field(header(repo / ".agents/README.md"), "forked_at") for repo in repos} - {None, "null"}
        # Sorted, so two runs over the same carriers pick the same base; and every loop stops at the
        # first match. The spec loop used to run on after a match and let a later spec overwrite it,
        # and `origin` was never set by this rule, so the report said "origin unknown" for a base it
        # had in hand.
        for spec in sorted(s for s in wanted if s):
            m = re.match(r"\{lineage:\s*(\S+),\s*version:\s*(\d+)\}", spec)
            if not m:
                continue
            for name, history in histories.items():
                for _, commit, tree in history:
                    h = header(tree / "README.md")
                    if (field(h, "lineage") or "").split("/")[0] == m.group(1) and field(h, "version") == m.group(2):
                        base, rule, origin = tree, "header", f"{name} {commit[:9]}"
                        break
                if base is not None:
                    break
            if base is not None:
                break
    if base is not None:
        shutil.copytree(base, out / "base/.agents")
        base = out / "base/.agents"
    files = classify(base, snapshots)
    report = {
        "base_rule": rule,
        "base": str(base) if base else None,
        "base_from": origin,
        "carriers": {name: digest_problems(tree) for name, tree in snapshots.items()},
        "files": files,
    }
    lines = [f"# Gather — {len(repos)} carriers\n", f"Base: {rule}, {origin or 'origin unknown'} ({base})\n"]
    for name, problems in report["carriers"].items():
        lines.append(f"- {name}: " + ("digests verified" if not problems else "; ".join(problems)))
    lines += ["", "| File | Verdict |", "|---|---|", *[f"| {r} | {v} |" for r, v in files.items()]]
    (out / "gather.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    return report


def lost(base: Path, merged: Path, snapshots: dict[str, Path]) -> list[tuple[str, str, str]]:
    """Every non-blank line a carrier added over the base that the merged tree does not contain.

    A note is looked up by its path, and when that path is absent, by its name anywhere under
    `knowledge/notes/`: a release that moves notes into state folders would otherwise report every
    line of every moved note as lost, and a check that always fires is read past.
    """
    missing = []
    for name, tree in snapshots.items():
        for rel in travelling(tree):
            added = _lines(tree / rel) - _lines(_counterpart(base, rel))
            present = _lines(_counterpart(merged, rel))
            missing += [(name, rel, line) for line in sorted(added - present)]
    return missing


def _lines(path: Path) -> set[str]:
    content = _content(path)
    return {line for line in (content or "").split("\n") if line.strip()}


# --- phase 2: register and splice ------------------------------------------------------------------


def _label(front: str) -> str:
    return f"{(field(front, 'lineage') or '').split('/')[0]} v{field(front, 'version')}"


def register(tree: Path, repos: list[Path], date: str | None = None) -> None:
    """Writes, in the tree's carriers table, one row per repository at the tree's versions, by stored id."""
    date = date or datetime.date.today().isoformat()
    ids = carrier_ids(repos)
    path = tree / "tracking/carriers.md"
    text = path.read_text(encoding="utf-8") if path.exists() else "---\n---\n" + CARRIERS_TABLE
    method = _set_docs(tree, "method")
    knowledge = _set_docs(tree, "knowledge")
    values = [
        _label(header(tree / "README.md")),
        _label(header(method[0])) if method else "—",
        _label(header(knowledge[0])) if knowledge else "—",
        date,
    ]
    for repo in repos:
        row = f"| {ids[repo]} | " + " | ".join(values) + " |"
        pattern = rf"^\| {re.escape(ids[repo])} \|.*$"
        if re.search(pattern, text, re.MULTILINE):
            text = re.sub(pattern, row, text, count=1, flags=re.MULTILINE)
        else:
            separator = "|---|---|---|---|---|\n"
            at = text.index(separator) + len(separator)
            rows_end = at
            while rows_end < len(text) and text[rows_end] == "|":
                rows_end = text.index("\n", rows_end) + 1
            text = text[:rows_end] + row + "\n" + text[rows_end:]
    path.write_text(text, encoding="utf-8")


# What the local step of a meta-session may write. Everything else in the bundle is written by the
# release, in one place, for every carrier at once: a note edited or a version bumped in one carrier
# is a lineage forked, and the next meta-session has to merge it.
LOCAL_WRITES = ("tracking/",)


def check_local_all(repos: list[Path]) -> list[tuple[str, list[str]]]:
    """The local-step check over every repository of the workspace, keeping only the ones with problems."""
    return [(repo.name, problems) for repo in repos if (problems := check_local(repo))]


def check_local(repo: Path) -> list[str]:
    """Every change to this carrier's bundle that the local step is not allowed to make.

    Compares the working bundle with the carrier's committed one: candidates and evidence in
    `tracking/` are the local step's to write; a note, a method document, a header or a version are
    the release's. Returns an empty list when the carrier only did its own step.
    """
    changed = [path.removeprefix(".agents/") for path in _status(repo)]
    return [
        f"{rel}: changed outside `tracking/` -- that is the release's to write, not this carrier's"
        for rel in sorted(changed)
        if not is_local(rel) and not rel.startswith(LOCAL_WRITES)
    ]


def _status(repo: Path) -> list[str]:
    """Every path under `.agents` that differs from the commit, as git names it, unquoted.

    Read with `-z`: the plain porcelain quotes a path holding a space (`"tracking/a b.md"`), and the
    quoted form matched neither `.agents/` nor `tracking/`, so a candidate file with a space in its
    name was reported as a change the local step may not make.
    """
    entries = str(git(repo, "status", "--porcelain", "-z", "--untracked-files=all", "--", ".agents")).split("\0")
    paths, skip = [], False
    for entry in entries:
        if skip:  # the original path of a rename or copy, which `-z` puts after the new one
            skip = False
            continue
        if len(entry) < 4:
            continue
        paths.append(entry[3:])
        skip = entry[0] in "RC" or entry[1] in "RC"
    return paths


def _dirty(repo: Path) -> list[str]:
    return [p for p in _status(repo) if not is_local(p.removeprefix(".agents/"))]


def splice(  # noqa: PLR0913 -- six parameters, each a different decision; keyword-only past the fourth
    merged: Path,
    repo: Path,
    write: bool,
    backup: Path | None,
    *,
    allow_dirty: bool = False,
    workspace: list[Path] | None = None,
) -> list[str]:
    """Phase 2 for one carrier: the merged body, with the carrier's own fields and its local files kept.

    Refuses to write over uncommitted changes to travelling files unless `allow_dirty`, and backs up the
    carrier's whole bundle to `backup` before writing anything.

    Returns:
        What was, or with `write=False` would be, written and removed.
    """
    # A MERGED that is not a bundle (a repository root, a mistyped scratch path) holds none of the
    # carrier's paths, so without this every file of the carrier's bundle reads as "not in the release".
    _a_bundle(merged)
    target = repo / ".agents"
    if target.resolve() == merged.resolve():
        return []  # the carrier the release was authored in: there is nothing to carry into it
    if workspace is not None and repo.resolve() not in [p.resolve() for p in workspace]:
        raise OutsideWorkspaceError(f"{repo}: not a repository of this workspace; it is not this session's to write")
    if write:
        dirty = _dirty(repo)
        if dirty and not allow_dirty:
            raise DirtyTreeError(f"{repo}: uncommitted bundle files {dirty}; commit them or pass allow_dirty")
    tails = {rel: own_fields(header(target / rel)) for rel in own_headers(target) if (target / rel).exists()}
    method_tail = next((t for r, t in tails.items() if r.startswith("method/") and t), "")
    incoming, present = set(travelling(merged)), set(travelling(target))
    actions = [f"write {r}" for r in sorted(incoming, key=str.encode)]
    actions += [f"remove {r}" for r in sorted(present - incoming, key=str.encode)]
    if not write:
        return actions
    if backup is not None:
        destination = backup / repo.name / ".agents"
        if destination.exists():
            shutil.rmtree(destination)
        shutil.copytree(target, destination)
    for rel in present - incoming:
        (target / rel).unlink()
    for rel in incoming:
        (target / rel).parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(merged / rel, target / rel)
    for rel in own_headers(target):
        tail = tails.get(rel) or (method_tail if rel.startswith("method/") else "")
        # Also with no tail to graft: `with_own_fields` takes the release's `carrier:` line out, and a
        # carrier with no id of its own must not leave the splice holding the release's.
        _write_header(target / rel, with_own_fields(header(target / rel), tail))
    return actions


# --- phase 3: align --------------------------------------------------------------------------------


def align(repos: list[Path]) -> list[str]:
    """Phase 3: every carrier declares true digests, holds one body, keeps its own fields, is registered.

    Returns:
        Every problem found; an empty list is an aligned set of carriers.
    """
    problems = []
    trees = {name: repo / ".agents" for repo, name in zip(repos, _names(repos))}
    ids = carrier_ids(repos)
    for name, tree in trees.items():
        problems += [f"{name}: {p}" for p in digest_problems(tree)]
        problems += [f"{name}: {p}" for p in provenance_problems(tree)]
    reference_name, reference = next(iter(trees.items()))
    for name, tree in list(trees.items())[1:]:
        ours, theirs = set(travelling(reference)), set(travelling(tree))
        problems += [f"{name}: lacks {r}" for r in sorted(ours - theirs)]
        problems += [f"{name}: has {r}, which {reference_name} does not" for r in sorted(theirs - ours)]
        for rel in sorted(ours & theirs, key=str.encode):
            if _content(reference / rel) != _content(tree / rel):
                problems.append(f"{name}: {rel} differs from {reference_name}")
            elif rel.endswith(".md"):
                a, b = header(reference / rel), header(tree / rel)
                if rel in own_headers(reference):
                    a, b = without_own_fields(a), without_own_fields(b)
                if a != b:
                    problems.append(f"{name}: the header of {rel} differs from {reference_name}")
    table = (reference / "tracking/carriers.md")
    text = table.read_text(encoding="utf-8") if table.exists() else ""
    version = _label(header(reference / "README.md"))
    for repo in repos:
        row = re.search(rf"^\| {re.escape(ids[repo])} \| ([^|]+) \|", text, re.MULTILINE)
        if not row:
            problems.append(f"{repo.name}: {ids[repo]} is not in tracking/carriers.md")
        elif row.group(1).strip() != version:
            problems.append(f"{repo.name}: registered at {row.group(1).strip()}, carries {version}")
    return problems


# --- selftest --------------------------------------------------------------------------------------
# The tests travel inside the tool so the file is the whole unit: no import, no path, no second file.
# Each one builds throwaway git repositories in a temporary directory; nothing reads a real carrier.
# What they pin is what went wrong by hand before this tool existed: a digest that depended on the
# locale, repository fields carried from one carrier into another, lines lost by a merge, a set of
# carriers reported aligned that was not, and a base found by trusting a header.

import contextlib  # noqa: E402 -- only the selftest needs these
import unittest  # noqa: E402
from unittest import mock  # noqa: E402


HEADER_README = """---
bundle:    agent-guides
lineage:   g-aaaaaa/main
ancestry:  [g-aaaaaa]
version:   1
forked_at: null
digest:    "000000000000"
released:  2026-01-01
upstream:  ""
contains:
  method:    m-aaaaaa v1
  knowledge: k-aaaaaa v1
adopted:   "2026-01-01"
adapted:   {adapted}
declined:  []
---
"""
HEADER_METHOD = """---
method:    test-method
set:       [context]
lineage:   m-aaaaaa/main
ancestry:  [m-aaaaaa]
version:   1
forked_at: null
digest:    "000000000000"
released:  2026-01-01
upstream:  ""
adopted:   "2026-01-01"
adapted:   {adapted}
declined:  []
---
"""
HEADER_KNOWLEDGE = """---
set:       knowledge
lineage:   k-aaaaaa/main
ancestry:  [k-aaaaaa]
version:   1
digest:    "000000000000"
released:  2026-01-01
upstream:  ""
---
"""
HEADER_DOC = """---
bundle:    agent-guides
lineage:   g-aaaaaa/main
ancestry:  [g-aaaaaa]
version:   1
component: {component}
released:  2026-01-01
---
"""
NOTE = """---
bundle: agent-guides
lineage: g-aaaaaa/main
version: 1
slug: {slug}
topic: t
claim: A claim.
confidence: reasoned
reach: review
---

# {slug}

**A claim.**
"""


TOOL = """#!/usr/bin/env python3
# bundle-header — provenance for this file. Identical across the bundle.
# ---
# bundle:    agent-guides
# lineage:   g-aaaaaa/main
# ancestry:  [g-aaaaaa]
# version:   1
# component: tool
# released:  2026-01-01
# ---
print('same everywhere')
"""


def _git(repo: Path, *args: str) -> str:
    return subprocess.run(
        ["git", "-C", str(repo), *args], check=True, capture_output=True, text=True
    ).stdout


def make_carrier(root: Path, name: str, adapted: str = "[]", remote: str | None = None) -> Path:
    """A git repository holding a minimal, stamped bundle with its own minted carrier id, committed."""
    repo = root / name
    agents = repo / ".agents"
    for folder in ("method", "knowledge/notes/active", "tracking", "incoming", "tools"):
        (agents / folder).mkdir(parents=True, exist_ok=True)
    (agents / "README.md").write_text(HEADER_README.format(adapted=adapted) + "\n# Guides\n")
    (agents / "method/prompt-context.md").write_text(HEADER_METHOD.format(adapted=adapted) + "\n# Context\n")
    (agents / "knowledge/README.md").write_text(HEADER_KNOWLEDGE + "\n# Knowledge\n")
    (agents / "knowledge/INDEX.md").write_text(
        HEADER_DOC.format(component="knowledge")
        + "\n# Index\n\n- [a-check](notes/active/a-check.md)\n- [absence](notes/active/absence.md)\n"
    )
    for slug in ("a-check", "absence"):
        (agents / f"knowledge/notes/active/{slug}.md").write_text(NOTE.format(slug=slug))
    (agents / "layout.md").write_text(HEADER_DOC.format(component="layout") + "\n# Layout\n")
    (agents / "roadmap.md").write_text(HEADER_DOC.format(component="roadmap") + "\n# Roadmap\n")
    (agents / "tracking/carriers.md").write_text(HEADER_DOC.format(component="tracking") + CARRIERS_TABLE)
    (agents / "incoming/README.md").write_text(HEADER_DOC.format(component="incoming") + "\n# Incoming\n")
    (agents / "tools/example.py").write_text(TOOL)
    stamp(agents, released="2026-01-01")
    mint_carrier_id(repo)
    _git(repo, "init", "-q")
    _git(repo, "config", "user.email", "t@example.com")
    _git(repo, "config", "user.name", "t")
    _git(repo, "remote", "add", "origin", remote or f"git@example.com:owner/{name}.git")
    _git(repo, "add", "-A")
    _git(repo, "commit", "-q", "-m", "bundle")
    return repo


class Base(unittest.TestCase):
    def setUp(self) -> None:
        # Resolved, because `workspace` resolves the paths it is given and macOS keeps the temporary
        # folder behind a symlink (`/var` -> `/private/var`): unresolved, four tests failed there only.
        self.root = Path(tempfile.mkdtemp()).resolve()
        # No test reads this machine's private terms: they are not the fixture's, and a term that
        # happened to match a fixture would fail a test on one machine only.
        environment = mock.patch.dict(os.environ, {"XDG_CONFIG_HOME": str(self.root / "config")})
        environment.start()
        self.addCleanup(environment.stop)

    def tearDown(self) -> None:
        shutil.rmtree(self.root)


class NotABundle(Base):
    """A digest over nothing is the sha of the empty string, and it reads like an answer."""

    def test_a_path_that_is_not_a_bundle_is_refused(self) -> None:
        (self.root / "plain").mkdir()

        with self.assertRaises(NotACarrierError):
            bundle_digest(self.root / "plain")

    def test_the_repository_root_is_not_its_bundle(self) -> None:
        repo = make_carrier(self.root, "one")

        with self.assertRaises(NotACarrierError):
            bundle_digest(repo)


class Digests(Base):
    def test_the_digest_orders_paths_by_bytes_not_by_locale(self) -> None:
        """`a-check` sorts before `absence` in bytes (0x2d < 0x62); a locale skips the hyphen."""
        repo = make_carrier(self.root, "one")
        notes = repo / ".agents/knowledge/notes/active"
        expected = sha12(
            body((notes / "a-check.md").read_text()) + body((notes / "absence.md").read_text())
        )

        self.assertEqual(knowledge_digest(repo / ".agents"), expected)

    def test_a_header_field_does_not_move_the_digest(self) -> None:
        """The recipe strips frontmatter, so writing the digest into a header cannot change it."""
        agents = make_carrier(self.root, "one") / ".agents"
        before = bundle_digest(agents)
        readme = agents / "README.md"
        readme.write_text(readme.read_text().replace("released:  2026-01-01", "released:  2027-01-01"))

        self.assertEqual(bundle_digest(agents), before)

    def test_a_stamped_tree_declares_what_it_contains(self) -> None:
        agents = make_carrier(self.root, "one") / ".agents"

        self.assertEqual(digest_problems(agents), [])

    def test_a_body_edit_after_stamping_is_reported(self) -> None:
        agents = make_carrier(self.root, "one") / ".agents"
        note = agents / "knowledge/notes/active/absence.md"
        note.write_text(note.read_text() + "\nMore.\n")

        problems = digest_problems(agents)

        self.assertTrue(any("knowledge" in p for p in problems), problems)


class CarrierIds(Base):
    """A carrier's id is random and stored; nothing about the repository computes it back.

    It was six hex of the sha256 of the remote until 2026-09-24, when one carrier was reversed in 66
    guesses from its owner's public repositories.
    """

    def test_a_minted_id_is_typed_six_hex_and_stored_after_adopted(self) -> None:
        repo = make_carrier(self.root, "one")
        front = header(repo / ".agents/README.md")

        self.assertRegex(repo_carrier_id(repo), r"^r-[0-9a-f]{6}$")
        self.assertIn(f"carrier:   {repo_carrier_id(repo)}", own_fields(front))
        self.assertLess(front.index("adopted:"), front.index("carrier:"))
        self.assertEqual(digest_problems(repo / ".agents"), [], "a header line moved a digest")

    def test_the_id_is_not_a_function_of_the_remote(self) -> None:
        one = make_carrier(self.root, "one", remote="git@example.com:owner/same.git")
        two = make_carrier(self.root, "two", remote="git@example.com:owner/same.git")

        self.assertNotEqual(repo_carrier_id(one), repo_carrier_id(two))

    def test_the_id_survives_a_splice_and_the_releases_id_does_not_travel(self) -> None:
        source = make_carrier(self.root, "source")
        target = make_carrier(self.root, "target")
        bare = make_carrier(self.root, "bare")
        readme = bare / ".agents/README.md"
        readme.write_text(OWN_FIELD_CARRIER.sub("", readme.read_text()))
        _git(bare, "commit", "-qam", "no id yet")
        before = repo_carrier_id(target)

        splice(source / ".agents", target, write=True, backup=self.root / "backup")
        splice(source / ".agents", bare, write=True, backup=self.root / "backup")

        self.assertEqual(repo_carrier_id(target), before)
        self.assertNotIn(repo_carrier_id(source), (target / ".agents/README.md").read_text())
        self.assertIsNone(stored_carrier_id(bare))

    def test_a_missing_id_is_refused_with_the_command_that_mints_one(self) -> None:
        repo = make_carrier(self.root, "one")
        readme = repo / ".agents/README.md"
        readme.write_text(OWN_FIELD_CARRIER.sub("", readme.read_text()))

        with self.assertRaisesRegex(RefusedError, "carrier-id --mint"):
            repo_carrier_id(repo)
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(main(["carrier-id", str(repo)]), 2)
            self.assertEqual(main(["id", "d", "text", "--repo", str(repo)]), 2)
            self.assertEqual(main(["carrier-id", "--mint", str(repo)]), 0)
        self.assertRegex(repo_carrier_id(repo), r"^r-[0-9a-f]{6}$")

    def test_a_second_mint_is_refused(self) -> None:
        repo = make_carrier(self.root, "one")
        before = (repo / ".agents/README.md").read_text()

        with self.assertRaises(RefusedError):
            mint_carrier_id(repo)

        self.assertEqual((repo / ".agents/README.md").read_text(), before)

    def test_a_bundle_copied_with_its_id_is_refused_by_register_and_align(self) -> None:
        one = make_carrier(self.root, "one")
        two = self.root / "two"
        shutil.copytree(one, two)

        with self.assertRaisesRegex(RefusedError, "both store"):
            register(one / ".agents", [one, two])
        with self.assertRaises(RefusedError):
            align([one, two])


class Splice(Base):
    def test_each_carrier_keeps_its_own_repository_fields(self) -> None:
        """The failure the bundle warns about: one repository's fields carried into another."""
        source = make_carrier(self.root, "source", adapted='\n  - "source adapted this"')
        target = make_carrier(self.root, "target", adapted='\n  - "target adapted that"')

        splice(source / ".agents", target, write=True, backup=self.root / "backup")

        for rel in ("README.md", "method/prompt-context.md"):
            text = (target / ".agents" / rel).read_text()
            self.assertIn("target adapted that", text, rel)
            self.assertNotIn("source adapted this", text, rel)

    def test_the_body_arrives_whole(self) -> None:
        source = make_carrier(self.root, "source")
        target = make_carrier(self.root, "target")
        note = source / ".agents/knowledge/notes/active/new-note.md"
        note.write_text(NOTE.format(slug="new-note"))

        splice(source / ".agents", target, write=True, backup=self.root / "backup")

        self.assertTrue((target / ".agents/knowledge/notes/active/new-note.md").exists())

    def test_repository_local_files_stay(self) -> None:
        """An evaluation report and a pending intake belong to the repository, never to the bundle."""
        source = make_carrier(self.root, "source")
        target = make_carrier(self.root, "target")
        (target / ".agents/evaluation-2026-01-01.md").write_text("mine")
        (target / ".agents/incoming/offered.md").write_text("waiting")

        splice(source / ".agents", target, write=True, backup=self.root / "backup")

        self.assertEqual((target / ".agents/evaluation-2026-01-01.md").read_text(), "mine")
        self.assertEqual((target / ".agents/incoming/offered.md").read_text(), "waiting")

    def test_uncommitted_work_in_the_bundle_is_refused(self) -> None:
        """The uncommitted diff is somebody's work: nothing is written over it unless asked."""
        source = make_carrier(self.root, "source")
        target = make_carrier(self.root, "target")
        roadmap = target / ".agents/roadmap.md"
        roadmap.write_text(roadmap.read_text() + "\nsomebody's afternoon\n")

        with self.assertRaises(DirtyTreeError):
            splice(source / ".agents", target, write=True, backup=self.root / "backup")

        self.assertIn("somebody's afternoon", roadmap.read_text())

    def test_a_dry_run_writes_nothing(self) -> None:
        source = make_carrier(self.root, "source")
        target = make_carrier(self.root, "target")
        (source / ".agents/roadmap.md").write_text(HEADER_DOC.format(component="roadmap") + "\nchanged\n")

        splice(source / ".agents", target, write=False, backup=None)

        self.assertNotIn("changed", (target / ".agents/roadmap.md").read_text())

    def test_what_it_rewrites_is_backed_up_first(self) -> None:
        source = make_carrier(self.root, "source")
        target = make_carrier(self.root, "target")
        before = (target / ".agents/README.md").read_text()

        splice(source / ".agents", target, write=True, backup=self.root / "backup")

        self.assertEqual((self.root / "backup/target/.agents/README.md").read_text(), before)


class Lost(Base):
    def test_a_line_a_carrier_added_and_the_merge_dropped_is_named(self) -> None:
        base = make_carrier(self.root, "base") / ".agents"
        side = self.root / "side"
        shutil.copytree(base, side)
        (side / "roadmap.md").write_text((side / "roadmap.md").read_text() + "\n- a line only this side had\n")
        merged = self.root / "merged"
        shutil.copytree(base, merged)

        missing = lost(base, merged, {"side": side})

        self.assertEqual([(c, f) for c, f, _ in missing], [("side", "roadmap.md")])

    def test_nothing_is_lost_when_the_merge_carries_it(self) -> None:
        base = make_carrier(self.root, "base") / ".agents"
        side = self.root / "side"
        shutil.copytree(base, side)
        (side / "roadmap.md").write_text((side / "roadmap.md").read_text() + "\n- a line only this side had\n")

        self.assertEqual(lost(base, side, {"side": side}), [])


class Align(Base):
    def aligned_pair(self) -> tuple[Path, Path]:
        one = make_carrier(self.root, "one", adapted='\n  - "one"')
        two = make_carrier(self.root, "two", adapted='\n  - "two"')
        register(one / ".agents", [one, two])
        splice(one / ".agents", two, write=True, backup=self.root / "backup", allow_dirty=True)
        return one, two

    def test_two_carriers_with_one_body_and_their_own_fields_are_aligned(self) -> None:
        one, two = self.aligned_pair()

        self.assertEqual(align([one, two]), [])

    def test_a_body_that_differs_in_one_carrier_is_named(self) -> None:
        one, two = self.aligned_pair()
        (two / ".agents/layout.md").write_text(HEADER_DOC.format(component="layout") + "\n# Other\n")

        problems = align([one, two])

        self.assertTrue(any("layout.md" in p for p in problems), problems)

    def test_a_tool_that_differs_in_one_carrier_is_named(self) -> None:
        """Tools are outside the digest recipe, so alignment compares them byte for byte."""
        one, two = self.aligned_pair()
        (two / ".agents/tools/example.py").write_text("print('drifted')\n")

        problems = align([one, two])

        self.assertTrue(any("tools/example.py" in p for p in problems), problems)

    def test_a_carrier_missing_from_the_registry_is_named(self) -> None:
        one = make_carrier(self.root, "one")
        two = make_carrier(self.root, "two")
        splice(one / ".agents", two, write=True, backup=self.root / "backup")

        problems = align([one, two])

        self.assertTrue(any(repo_carrier_id(two) in p for p in problems), problems)


class BumpTwice(Base):
    """A bump is not idempotent: run twice it skips a version, and nobody sees it happen."""

    def test_a_second_bump_against_the_same_release_is_refused(self) -> None:
        agents = make_carrier(self.root, "one") / ".agents"
        previous = self.root / "previous"
        shutil.copytree(agents, previous)
        stamp(agents, bump={"bundle"}, against=previous, released="2026-02-02")

        with self.assertRaises(AlreadyBumpedError):
            stamp(agents, bump={"bundle"}, against=previous, released="2026-02-03")

        self.assertEqual(field(header(agents / "README.md"), "version"), "2")

    def test_a_second_stamp_against_one_base_does_not_raise_a_document_twice(self) -> None:
        """A correction to a release that has not shipped is the same release, not the next version of the note."""
        repo = make_carrier(self.root, "one")
        agents = repo / ".agents"
        base = self.root / "base"
        shutil.copytree(agents, base)
        note = agents / "knowledge/notes/active/absence.md"
        note.write_text(note.read_text() + "\nan edit\n")

        stamp(agents, against=base, released="2026-01-02")
        once = field(header(note), "version")
        note.write_text(note.read_text() + "a correction\n")
        stamp(agents, against=base, released="2026-01-02")

        self.assertEqual(field(header(base / "knowledge/notes/active/absence.md"), "version"), "1")
        self.assertEqual(once, "2")
        self.assertEqual(field(header(note), "version"), "2")

    def test_a_bump_of_another_kind_still_runs(self) -> None:
        agents = make_carrier(self.root, "one") / ".agents"
        previous = self.root / "previous"
        shutil.copytree(agents, previous)
        stamp(agents, bump={"bundle"}, against=previous, released="2026-02-02")

        stamp(agents, bump={"method"}, against=previous, released="2026-02-03")

        self.assertEqual(field(header(agents / "method/prompt-context.md"), "version"), "2")


class Stamp(Base):
    def test_a_bump_raises_the_version_and_the_contains_block(self) -> None:
        agents = make_carrier(self.root, "one") / ".agents"

        stamp(agents, bump={"bundle", "method"}, released="2026-02-02")

        readme = header(agents / "README.md")
        self.assertEqual(field(readme, "version"), "2")
        self.assertIn("m-aaaaaa v2", readme)
        self.assertEqual(field(header(agents / "method/prompt-context.md"), "version"), "2")

    def test_a_fork_appends_a_new_lineage_and_records_where_it_branched(self) -> None:
        agents = make_carrier(self.root, "one") / ".agents"

        stamp(agents, forks={"bundle": "g-bbbbbb"}, released="2026-02-02")

        readme = header(agents / "README.md")
        self.assertEqual(field(readme, "lineage"), "g-bbbbbb/main")
        self.assertEqual(field(readme, "ancestry"), "[g-aaaaaa, g-bbbbbb]")
        self.assertEqual(field(readme, "forked_at"), "{lineage: g-aaaaaa, version: 1}")
        self.assertEqual(field(readme, "version"), "2")
        index = header(agents / "knowledge/INDEX.md")
        self.assertEqual(field(index, "lineage"), "g-bbbbbb/main")


class Provenance(Base):
    """Every file of the bundle says which line it came from, a tool as much as a document."""

    def test_a_complete_bundle_has_no_file_without_provenance(self) -> None:
        agents = make_carrier(self.root, "one") / ".agents"

        self.assertEqual(provenance_problems(agents), [])

    def test_a_document_without_its_header_is_named(self) -> None:
        agents = make_carrier(self.root, "one") / ".agents"
        note = agents / "knowledge/notes/active/absence.md"
        note.write_text(body(note.read_text()))

        self.assertTrue(any("absence.md" in p for p in provenance_problems(agents)))

    def test_a_tool_without_its_comment_header_is_named(self) -> None:
        agents = make_carrier(self.root, "one") / ".agents"
        (agents / "tools/example.py").write_text("print('no header')\n")

        self.assertTrue(any("tools/example.py" in p for p in provenance_problems(agents)))

    def test_a_file_of_a_kind_with_no_header_form_is_named(self) -> None:
        """Fail closed: a new file type is not exempt because nobody taught the tool its comment."""
        agents = make_carrier(self.root, "one") / ".agents"
        (agents / "tools/data.json").write_text("{}")

        problems = [p for p in provenance_problems(agents) if "tools/data.json" in p]
        self.assertEqual(len(problems), 1, problems)
        self.assertIn("no provenance form", problems[0])

    def test_the_tool_header_reads_like_a_document_header(self) -> None:
        agents = make_carrier(self.root, "one") / ".agents"

        self.assertEqual(field(header(agents / "tools/example.py"), "lineage"), "g-aaaaaa/main")

    def test_a_fork_moves_the_tools_lineage_too(self) -> None:
        agents = make_carrier(self.root, "one") / ".agents"

        stamp(agents, forks={"bundle": "g-bbbbbb"}, released="2026-02-02")

        tool = (agents / "tools/example.py").read_text()
        self.assertIn("# lineage:   g-bbbbbb/main", tool)
        self.assertTrue(tool.endswith("print('same everywhere')\n"), "the code under the header moved")

    def test_align_refuses_a_file_without_provenance(self) -> None:
        one = make_carrier(self.root, "one")
        register(one / ".agents", [one])
        (one / ".agents/roadmap.md").write_text("# no header\n")

        self.assertTrue(any("roadmap.md" in p for p in align([one])))

    def test_this_tool_carries_its_own_provenance(self) -> None:
        own = header(Path(__file__))

        self.assertTrue(field(own, "lineage") and field(own, "version"), own)


class LocalStep(Base):
    """The local step writes candidates and evidence, never notes, method or versions.

    A carrier that edits its own copy of a note or bumps its own version forks the lineage, which is
    what a meta-session then has to merge. `check_local` is what makes that rule visible before a
    commit rather than a week later.
    """

    def test_a_carrier_that_only_added_candidates_is_local(self) -> None:
        repo = make_carrier(self.root, "one")
        candidates = repo / ".agents/tracking/candidates.md"
        candidates.write_text(HEADER_DOC.format(component="tracking") + "\n| a candidate | K | a number | here |\n")

        self.assertEqual(check_local(repo), [])

    def test_an_edited_note_is_named(self) -> None:
        repo = make_carrier(self.root, "one")
        note = repo / ".agents/knowledge/notes/active/absence.md"
        note.write_text(note.read_text() + "\nA second occurrence.\n")

        problems = check_local(repo)

        self.assertEqual(len(problems), 1, problems)
        self.assertIn("knowledge/notes/active/absence.md", problems[0])

    def test_a_bumped_version_is_named(self) -> None:
        repo = make_carrier(self.root, "one")

        stamp(repo / ".agents", bump={"bundle"}, released="2026-02-02")

        self.assertTrue(any("README.md" in p for p in check_local(repo)))

    def test_a_new_note_is_named_too(self) -> None:
        """A note written locally is a note the other carriers will not have; it waits as a candidate."""
        repo = make_carrier(self.root, "one")
        (repo / ".agents/knowledge/notes/active/local-idea.md").write_text(NOTE.format(slug="local-idea"))

        self.assertTrue(any("local-idea" in p for p in check_local(repo)))

    def test_a_clean_carrier_is_local(self) -> None:
        self.assertEqual(check_local(make_carrier(self.root, "one")), [])


class Workspace(Base):
    """Only the repositories this session has open are edited, and all of them are considered.

    A machine carries more of this bundle than a session has open. Writing into a carrier nobody is
    looking at is how a repository gets a release its owner never saw, and leaving one of the open
    ones out is how two of them end up on different versions.
    """

    def test_the_declared_workspace_wins_over_the_manifest(self) -> None:
        one = make_carrier(self.root, "one")
        manifest = self.root / "carriers.toml"
        manifest.write_text('carriers = ["/elsewhere/two"]\n')

        self.assertEqual(workspace([str(one)], manifest).repos, [one])

    def test_without_a_declared_workspace_the_manifest_is_used(self) -> None:
        one = make_carrier(self.root, "one")
        manifest = self.root / "carriers.toml"
        manifest.write_text(f'carriers = ["{one}"]\n')

        self.assertEqual(workspace([], manifest).repos, [one])

    def test_the_manifest_is_read_without_tomllib(self) -> None:
        """Before Python 3.11 there is no `tomllib`; the manifest's documented form is still read, and nothing else is guessed."""
        global tomllib
        manifest = self.root / "carriers.toml"
        saved, tomllib = tomllib, None
        try:
            manifest.write_text('# this machine\ncarriers = [\n  "/a/one",  # first\n  \'/b/two\',\n]\n')
            self.assertEqual(manifest_carriers(manifest), ["/a/one", "/b/two"])
            manifest.write_text("carriers = [1]\n")
            with self.assertRaises(ValueError):
                manifest_carriers(manifest)
        finally:
            tomllib = saved

    def test_a_command_that_writes_is_given_its_scope_and_never_infers_it(self) -> None:
        """The manifest lists the carriers of a machine; it never says which ones a session may write."""
        one = make_carrier(self.root, "one")
        manifest = self.root / "carriers.toml"
        manifest.write_text(f'carriers = ["{one}"]\n')

        with self.assertRaises(UndeclaredScopeError):
            workspace([], manifest, writing=True)

        self.assertEqual(workspace([str(one)], manifest, writing=True).repos, [one])

    def test_an_empty_outside_list_says_whether_it_means_anything(self) -> None:
        """The guard is the manifest minus the operated set: taken *from* the manifest it is empty by construction."""
        one, two = make_carrier(self.root, "one"), make_carrier(self.root, "two")
        manifest = self.root / "carriers.toml"
        manifest.write_text(f'carriers = ["{one}", "{two}"]\n')

        declared = workspace([str(one)], manifest)
        inherited = workspace([], manifest)

        self.assertTrue(declared.declared)
        self.assertEqual(outside(declared, manifest), [str(two)])
        self.assertFalse(inherited.declared)
        self.assertEqual(outside(inherited, manifest), [])
        self.assertIn("scope taken from the manifest", _scope_report(inherited, "written")[0])

    def test_a_path_that_carries_no_bundle_is_refused(self) -> None:
        (self.root / "plain").mkdir()

        with self.assertRaises(NotACarrierError):
            workspace([str(self.root / "plain")], None)

    def test_a_carrier_outside_the_workspace_is_not_written(self) -> None:
        inside = make_carrier(self.root, "inside")
        outside = make_carrier(self.root, "outside")

        with self.assertRaises(OutsideWorkspaceError):
            splice(inside / ".agents", outside, write=True, backup=self.root / "b", workspace=[inside])

        self.assertNotIn("inside", (outside / ".agents/README.md").read_text())

    def test_the_carrier_the_release_came_from_is_left_alone(self) -> None:
        """A workspace includes the carrier the release was authored in; splicing it into itself is nothing."""
        source = make_carrier(self.root, "source")
        other = make_carrier(self.root, "other")

        actions = splice(source / ".agents", source, write=True, backup=self.root / "b", workspace=[source, other])

        self.assertEqual(actions, [])
        self.assertTrue((source / ".agents/README.md").exists())

    def test_every_repository_of_the_workspace_is_checked_for_the_local_step(self) -> None:
        one = make_carrier(self.root, "one")
        two = make_carrier(self.root, "two")
        note = two / ".agents/knowledge/notes/active/absence.md"
        note.write_text(note.read_text() + "\nlocal edit\n")

        problems = check_local_all([one, two])

        self.assertEqual([name for name, _ in problems], ["two"])


class Gather(Base):
    def test_the_base_is_found_by_content_in_the_histories(self) -> None:
        """The newest tree both carriers once held, recognised by its fingerprint and not by a header."""
        one = make_carrier(self.root, "one")
        two = self.root / "two"
        shutil.copytree(one, two)
        _git(two, "remote", "set-url", "origin", "git@example.com:owner/two.git")
        for repo, text in ((one, "one moved"), (two, "two moved")):
            (repo / ".agents/layout.md").write_text(HEADER_DOC.format(component="layout") + f"\n{text}\n")
            stamp(repo / ".agents", released="2026-01-02")
            _git(repo, "commit", "-qam", "moved")

        report = gather([one, two], self.root / "out")

        self.assertEqual(report["base_rule"], "content")
        self.assertEqual(report["files"]["layout.md"], "both changed")
        self.assertEqual(report["files"]["roadmap.md"], "same")

    def test_a_carrier_that_harvested_first_does_not_become_the_base(self) -> None:
        """`tracking/` is outside the stamped digest, so a base chosen by it would be the harvest itself."""
        one = make_carrier(self.root, "one")
        candidates = one / ".agents/tracking/candidates.md"
        candidates.write_text(HEADER_DOC.format(component="tracking") + "\n# Candidates\n\n- shared\n")
        _git(one, "add", "-A")
        _git(one, "commit", "-qm", "release")
        two = self.root / "two"
        shutil.copytree(one, two)
        _git(two, "remote", "set-url", "origin", "git@example.com:owner/two.git")
        candidates.write_text(candidates.read_text() + "- one learned something\n")
        _git(one, "add", "-A")
        _git(one, "commit", "-qm", "harvest")

        report = gather([one, two], self.root / "out")

        self.assertEqual(report["files"]["tracking/candidates.md"], "changed by one")
        self.assertNotIn("one learned something", (self.root / "out/base/.agents/tracking/candidates.md").read_text())
        self.assertEqual(
            lost(self.root / "out/base/.agents", self.root / "out/carriers/two/.agents",
                 {"one": self.root / "out/carriers/one/.agents"}),
            [("one", "tracking/candidates.md", "- one learned something")])


class KnowledgeDigest(Base):
    """The folder is the note's state, so the knowledge digest reads every folder under `notes/`."""

    def test_a_note_in_a_state_folder_counts(self) -> None:
        agents = make_carrier(self.root, "one") / ".agents"
        before = knowledge_digest(agents)
        (agents / "knowledge/notes/retired").mkdir()
        (agents / "knowledge/notes/retired/old.md").write_text(NOTE.format(slug="old"))
        notes = agents / "knowledge/notes"
        expected = sha12("".join(body((notes / rel).read_text()) for rel in ("active/a-check.md", "active/absence.md", "retired/old.md")))

        self.assertNotEqual(knowledge_digest(agents), before)
        self.assertEqual(knowledge_digest(agents), expected)

    def test_the_paths_are_not_hashed(self) -> None:
        """Same bodies in the same order: a move that keeps the order keeps the digest."""
        agents = make_carrier(self.root, "one") / ".agents"
        before = knowledge_digest(agents)
        (agents / "knowledge/notes/review").mkdir()
        (agents / "knowledge/notes/active/absence.md").rename(agents / "knowledge/notes/review/absence.md")

        self.assertEqual(knowledge_digest(agents), before)

    def test_no_notes_is_refused(self) -> None:
        """`sha256("")` is a digest too: a glob that stopped matching would declare it and pass."""
        agents = make_carrier(self.root, "one") / ".agents"
        shutil.rmtree(agents / "knowledge/notes")

        with self.assertRaises(RefusedError):
            knowledge_digest(agents)


class Moves(Base):
    """A note that changed state is one note moved, in the table, in `lost` and in `stamp`."""

    def three(self) -> tuple[Path, Path, Path]:
        base = make_carrier(self.root, "base") / ".agents"
        one, two = self.root / "one", self.root / "two"
        shutil.copytree(base, one)
        shutil.copytree(base, two)
        return base, one, two

    @staticmethod
    def move(tree: Path, state: str = "review") -> None:
        (tree / f"knowledge/notes/{state}").mkdir(exist_ok=True)
        (tree / "knowledge/notes/active/absence.md").rename(tree / f"knowledge/notes/{state}/absence.md")

    def test_a_note_moved_in_one_carrier_is_reported_as_a_move(self) -> None:
        base, one, two = self.three()
        self.move(one)

        verdicts = classify(base, {"one": one, "two": two})

        move = "moved knowledge/notes/active/absence.md -> knowledge/notes/review/absence.md in one"
        self.assertEqual(verdicts["knowledge/notes/active/absence.md"], move)
        self.assertEqual(verdicts["knowledge/notes/review/absence.md"], move)

    def test_a_file_every_carrier_deleted_is_removed_not_same(self) -> None:
        base, one, two = self.three()
        (one / "roadmap.md").unlink()
        (two / "roadmap.md").unlink()

        self.assertEqual(classify(base, {"one": one, "two": two})["roadmap.md"], "removed in one, two")

    def test_a_file_one_carrier_deleted_is_removed_in_it(self) -> None:
        base, one, two = self.three()
        (one / "roadmap.md").unlink()

        self.assertEqual(classify(base, {"one": one, "two": two})["roadmap.md"], "removed in one")

    def test_lost_finds_a_moved_note_in_its_new_folder(self) -> None:
        base, side, merged = self.three()
        note = side / "knowledge/notes/active/absence.md"
        note.write_text(note.read_text() + "\n- a line only this side had\n")
        self.move(merged)

        self.assertEqual([(c, f) for c, f, _ in lost(base, merged, {"side": side})],
                         [("side", "knowledge/notes/active/absence.md")])
        moved = merged / "knowledge/notes/review/absence.md"
        moved.write_text(moved.read_text() + "\n- a line only this side had\n")
        self.assertEqual(lost(base, merged, {"side": side}), [])

    def test_stamp_compares_a_moved_note_with_itself(self) -> None:
        agents = make_carrier(self.root, "one") / ".agents"
        previous = self.root / "previous"
        shutil.copytree(agents, previous)
        self.move(agents, "retired")

        stamp(agents, against=previous, released="2026-02-02")

        self.assertEqual(field(header(agents / "knowledge/notes/retired/absence.md"), "version"), "1")
        note = agents / "knowledge/notes/retired/absence.md"
        note.write_text(note.read_text() + "\nwithdrawn\n")
        stamp(agents, against=previous, released="2026-02-02")
        self.assertEqual(field(header(note), "version"), "2")


class NoteState(Base):
    """A state change is a move, and a move by hand leaves every path that named the note dead."""

    def tree(self) -> Path:
        agents = make_carrier(self.root, "one") / ".agents"
        context = agents / "method/prompt-context.md"
        context.write_text(context.read_text() + "\nSee [absence](../knowledge/notes/active/absence.md#why).\n")
        index = agents / "knowledge/INDEX.md"
        index.write_text(index.read_text() + "\n```text\n[example](notes/active/absence.md)\n```\n")
        sibling = agents / "knowledge/notes/active/a-check.md"
        sibling.write_text(sibling.read_text() + "\nRelated: [absence](absence.md).\n")
        note = agents / "knowledge/notes/active/absence.md"
        note.write_text(note.read_text() + "\nRelated: [a-check](a-check.md), [index](../../INDEX.md).\n")
        return agents

    def test_the_note_moves_and_every_link_to_it_follows(self) -> None:
        agents = self.tree()

        changes = note_state(agents, "absence", "review")

        self.assertEqual(changes[0], "moved knowledge/notes/active/absence.md -> knowledge/notes/review/absence.md")
        self.assertFalse((agents / "knowledge/notes/active/absence.md").exists())
        self.assertIn("](notes/review/absence.md)", (agents / "knowledge/INDEX.md").read_text())
        self.assertIn("[example](notes/active/absence.md)", (agents / "knowledge/INDEX.md").read_text(), "a fenced example was rewritten")
        self.assertIn("](../knowledge/notes/review/absence.md#why)", (agents / "method/prompt-context.md").read_text())
        self.assertIn("](../review/absence.md)", (agents / "knowledge/notes/active/a-check.md").read_text())
        moved = (agents / "knowledge/notes/review/absence.md").read_text()
        self.assertIn("[a-check](../active/a-check.md)", moved)
        self.assertIn("[index](../../INDEX.md)", moved)
        self.assertEqual(link_problems(agents), [])
        self.assertEqual(reachability_problems(agents), [])

    def test_a_note_already_in_its_state_changes_nothing(self) -> None:
        agents = make_carrier(self.root, "one") / ".agents"

        self.assertEqual(note_state(agents, "absence", "active"), [])

    def test_an_unknown_slug_or_state_is_refused(self) -> None:
        agents = make_carrier(self.root, "one") / ".agents"

        with self.assertRaises(RefusedError):
            note_state(agents, "no-such-note", "review")
        with self.assertRaises(RefusedError):
            note_state(agents, "absence", "archived")
        self.assertTrue((agents / "knowledge/notes/active/absence.md").exists())


class RecordIds(Base):
    """Parallel sessions pick the same counter; an id from the carrier and the text they cannot."""

    def test_a_record_id_is_typed_carrier_scoped_and_derived(self) -> None:
        minted = record_id("d", "Keep the port out of the id.", "r-abcdef")

        self.assertRegex(minted, r"^d-abcdef-[0-9a-f]{6}$")
        self.assertEqual(minted, record_id("d", "Keep the port out of the id.", "r-abcdef"))
        self.assertEqual(minted[-6:], hashlib.sha256(b"Keep the port out of the id.").hexdigest()[:6])

    def test_whitespace_does_not_move_the_id(self) -> None:
        self.assertEqual(record_id("i", "  one\n two\tthree ", "r-abcdef"), record_id("i", "one two three", "r-abcdef"))

    def test_two_texts_are_two_ids(self) -> None:
        self.assertNotEqual(record_id("s", "one entry", "r-abcdef"), record_id("s", "another entry", "r-abcdef"))

    def test_an_unknown_kind_is_refused(self) -> None:
        with self.assertRaises(RefusedError):
            record_id("x", "text", "r-abcdef")

    def test_the_command_takes_the_carrier_from_the_repository(self) -> None:
        repo = make_carrier(self.root, "one")
        out = io.StringIO()

        with contextlib.redirect_stdout(out):
            self.assertEqual(main(["id", "d", "two", "words", "--repo", str(repo)]), 0)

        self.assertEqual(out.getvalue().strip(), record_id("d", "two words", repo_carrier_id(repo)))

    def test_a_lineage_id_is_derived_as_before(self) -> None:
        self.assertEqual(derive_id("g-", "a", "b"), "g-" + hashlib.sha256(b"a + b").hexdigest()[:6])


class Links(Base):
    """A dead pointer reads as a reference until somebody follows it."""

    def test_a_complete_bundle_has_no_dead_link(self) -> None:
        self.assertEqual(link_problems(make_carrier(self.root, "one") / ".agents"), [])

    def test_a_link_to_a_missing_file_is_named(self) -> None:
        agents = make_carrier(self.root, "one") / ".agents"
        roadmap = agents / "roadmap.md"
        roadmap.write_text(roadmap.read_text() + "\nSee [gone](knowledge/notes/gone.md#part).\n")

        problems = link_problems(agents)

        self.assertEqual(len(problems), 1, problems)
        self.assertIn("roadmap.md: links to knowledge/notes/gone.md#part", problems[0])

    def test_what_is_not_a_relative_pointer_is_not_checked(self) -> None:
        agents = make_carrier(self.root, "one") / ".agents"
        roadmap = agents / "roadmap.md"
        roadmap.write_text(roadmap.read_text() + (
            "\n[web](https://example.com/x) [mail](mailto:a@example.com) [here](#anchor)"
            " `[code](gone.md)` [up](../../outside.md) [ok](layout.md#layout)\n"
            "\n~~~text\n[paste](gone.md)\n~~~\n\n```\n[example](gone.md)\n```\n"))
        (agents / "incoming/offered.md").write_text("[x](gone.md)\n")
        (agents / "evaluation-2026-01-01.md").write_text("[x](gone.md)\n")

        self.assertEqual(link_problems(agents), [])

    def test_an_active_or_review_note_no_index_links_to_is_named(self) -> None:
        agents = make_carrier(self.root, "one") / ".agents"
        for state in ("active", "review", "retired"):
            (agents / f"knowledge/notes/{state}").mkdir(exist_ok=True)
            (agents / f"knowledge/notes/{state}/orphan-{state}.md").write_text(NOTE.format(slug=f"orphan-{state}"))

        problems = reachability_problems(agents)

        self.assertEqual(len(problems), 2, problems)
        self.assertTrue(problems[0].startswith("knowledge/notes/active/orphan-active.md"))
        self.assertTrue(problems[1].startswith("knowledge/notes/review/orphan-review.md"))

    def test_an_area_index_is_an_index(self) -> None:
        agents = make_carrier(self.root, "one") / ".agents"
        (agents / "knowledge/notes/active/routed.md").write_text(NOTE.format(slug="routed"))
        (agents / "knowledge/areas").mkdir()
        (agents / "knowledge/areas/one.md").write_text(HEADER_DOC.format(component="knowledge") + "\n[r](../notes/active/routed.md)\n")

        self.assertEqual(reachability_problems(agents), [])


class OwnFields(Base):
    """`upstream`, `adopted`, `adapted` and `declined` are the carrier's; the rest of a header is the release's."""

    FRONT = '---\nbundle: b\nversion: 2\nupstream: "{u}"\ncontains: x\nadopted: "{a}"\nadapted: []\n---\n'

    def test_a_splice_keeps_the_carriers_upstream_and_tail(self) -> None:
        release = self.FRONT.format(u="", a="2026-01-01").replace("version: 2", "version: 3")
        carrier = self.FRONT.format(u="elsewhere", a="2026-02-02")

        spliced = with_own_fields(release, own_fields(carrier))

        self.assertIn('upstream: "elsewhere"', spliced)
        self.assertIn('adopted: "2026-02-02"', spliced)
        self.assertIn("version: 3", spliced)
        self.assertEqual(without_own_fields(spliced), without_own_fields(release))


class Sessions(Base):
    """What each session type loads, measured; a heading that moved must not empty a session."""

    DOC = "---\nx: 1\n---\n# Top\n\n## A\n\none\n\n### A.1\n\ntwo\n\n```text\n## B\n```\n\n## B\n\nthree\n"

    def test_a_section_runs_to_the_next_heading_of_its_level(self) -> None:
        path = self.root / "doc.md"
        path.write_text(self.DOC)

        self.assertEqual(section(path, "A"), "## A\n\none\n\n### A.1\n\ntwo\n\n```text\n## B\n```\n\n")
        self.assertEqual(section(path, "B"), "## B\n\nthree\n")
        self.assertIsNone(section(path, "C"))

    def test_a_heading_the_file_does_not_have_fails_the_check(self) -> None:
        agents = make_carrier(self.root, "one") / ".agents"
        ok = {"s": [("layout.md", "Layout"), ("knowledge/notes/active/*.md", None)]}
        bad = {"s": [("layout.md", "Nowhere"), ("method/prompt-none.md", None)]}

        self.assertEqual(session_problems(agents, ok), [])
        problems = session_problems(agents, bad)
        self.assertEqual(len(problems), 2, problems)
        self.assertTrue(any("Nowhere" in p for p in problems))
        self.assertTrue(any(p in check_problems(agents, bad) for p in problems))

    def test_the_report_counts_files_folders_and_sessions(self) -> None:
        agents = make_carrier(self.root, "one") / ".agents"
        layout = (agents / "layout.md").read_text()

        data = report(agents, {"s": [("layout.md", None)], "t": [("layout.md", "Layout")]})

        self.assertEqual(data["total"]["files"], len(travelling(agents)))
        self.assertEqual(data["folders"]["knowledge/notes/active"]["files"], 2)
        self.assertIn("(root)", data["folders"])
        self.assertEqual(data["sessions"]["s"]["tokens_estimate"], math.ceil(len(layout) / 4))
        self.assertEqual(data["sessions"]["t"]["chars"], len("# Layout\n"))
        self.assertEqual(data["sessions"]["s"]["context_share"], round(math.ceil(len(layout) / 4) / 200_000, 4))
        self.assertIn("Tokens (est.)", report_markdown(data))

    def test_the_command_prints_json(self) -> None:
        agents = make_carrier(self.root, "one") / ".agents"
        out = io.StringIO()

        with contextlib.redirect_stdout(out):
            self.assertEqual(main(["report", str(agents), "--json"]), 0)

        self.assertIn("folders", json.loads(out.getvalue()))

    def test_the_sessions_are_read_from_the_methods_reads_lists(self) -> None:
        """One source: the invocation's `Reads:` list is what the report measures and the check verifies."""
        agents = make_carrier(self.root, "one") / ".agents"
        (agents / "method/prompt-harvest.md").write_text(
            "---\nx: 1\n---\n~~~text\nReads:\n- layout.md §Layout\n- knowledge/INDEX.md\n~~~\n")

        sessions, missing = sessions_of(agents)

        self.assertEqual(sessions["harvest"], [("layout.md", "Layout"), ("knowledge/INDEX.md", None)])
        self.assertTrue(any("'coding'" in m for m in missing), missing)
        self.assertTrue(any("'coding'" in p for p in session_problems(agents)))

        # The bootstrap document carries two lists; the coding session is the second one.
        (agents / "method/prompt-bootstrap.md").write_text(
            "---\nx: 1\n---\nReads:\n- layout.md\n\nlater\n\nReads:\n- knowledge/INDEX.md\n")
        sessions, _ = sessions_of(agents)
        self.assertEqual(sessions["bootstrap"], [("layout.md", None)])
        self.assertEqual(sessions["coding"], [("knowledge/INDEX.md", None)])
        self.assertEqual(reads_lists("Reads:\n- a.md §One §Two, with a comma\n\nReads:\n- b.md\n"),
                         [[("a.md", "One"), ("a.md", "Two, with a comma")], [("b.md", None)]])


class Refusals(Base):
    """Each of these was a traceback, a silent default or a wrong answer; each is now a refusal."""

    def test_splice_and_stamp_refuse_what_is_not_a_bundle(self) -> None:
        source = make_carrier(self.root, "source")
        target = make_carrier(self.root, "target")

        with self.assertRaises(NotACarrierError):
            splice(source, target, write=True, backup=self.root / "backup")
        with self.assertRaises(NotACarrierError):
            stamp(source)
        self.assertTrue((target / ".agents/roadmap.md").exists())

    def test_gather_refuses_an_output_folder_it_did_not_write(self) -> None:
        one = make_carrier(self.root, "one")
        out = self.root / "out"
        out.mkdir()
        (out / "keep.txt").write_text("somebody's")

        with self.assertRaises(RefusedError):
            gather([one], out)

        self.assertTrue((out / "keep.txt").exists())
        gather([one], self.root / "fresh")
        gather([one], self.root / "fresh")  # its own previous output is replaced

    def test_two_carriers_with_one_folder_name_are_refused(self) -> None:
        (self.root / "a").mkdir()
        (self.root / "b").mkdir()
        first, second = make_carrier(self.root / "a", "same"), make_carrier(self.root / "b", "same")

        with self.assertRaises(RefusedError):
            gather([first, second], self.root / "out")
        with self.assertRaises(RefusedError):
            align([first, second])

    def test_the_header_rule_names_where_the_base_came_from(self) -> None:
        one = make_carrier(self.root, "one")
        two = make_carrier(self.root, "two")
        readme = two / ".agents/README.md"
        readme.write_text(readme.read_text().replace("forked_at: null", "forked_at: {lineage: g-aaaaaa, version: 1}"))
        (two / ".agents/roadmap.md").write_text(HEADER_DOC.format(component="roadmap") + "\nonly two\n")
        _git(two, "commit", "-q", "--amend", "-am", "forked")

        result = gather([one, two], self.root / "out")

        self.assertEqual(result["base_rule"], "header")
        self.assertTrue((result["base_from"] or "").startswith("one "), result["base_from"])

    def test_an_empty_scope_is_refused(self) -> None:
        manifest = self.root / "carriers.toml"
        manifest.write_text("carriers = []\n")

        with self.assertRaises(RefusedError):
            workspace([], manifest)
        with self.assertRaises(RefusedError):
            gather([], self.root / "out")
        with self.assertRaises(RefusedError):
            align([])

    def test_a_malformed_stored_id_is_refused_not_a_traceback(self) -> None:
        repo = make_carrier(self.root, "one")
        readme = repo / ".agents/README.md"
        readme.write_text(OWN_FIELD_CARRIER.sub("carrier:   r-XYZ\n", readme.read_text()))

        with self.assertRaises(RefusedError):
            repo_carrier_id(repo)
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(main(["carrier-id", str(repo)]), 2)

    def test_a_dry_run_splice_makes_no_backup_folder(self) -> None:
        source = make_carrier(self.root, "source")
        target = make_carrier(self.root, "target")
        made = []
        real = tempfile.mkdtemp

        def recording(*args: object, **kwargs: object) -> str:
            made.append(kwargs.get("prefix"))
            return real(*args, **kwargs)  # type: ignore[arg-type]

        with mock.patch("tempfile.mkdtemp", recording), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(main(["splice", str(source / ".agents"), str(source), str(target)]), 0)

        self.assertNotIn("bundle-backup-", made)

    def test_dotfiles_do_not_travel(self) -> None:
        agents = make_carrier(self.root, "one") / ".agents"
        (agents / ".DS_Store").write_bytes(b"\0")
        (agents / "knowledge/.cache").mkdir()
        (agents / "knowledge/.cache/x.md").write_text("x")

        self.assertFalse([r for r in travelling(agents) if "DS_Store" in r or ".cache" in r])
        self.assertEqual(provenance_problems(agents), [])

    def test_an_unknown_stamp_kind_or_an_empty_fork_is_refused(self) -> None:
        agents = make_carrier(self.root, "one") / ".agents"
        before = (agents / "README.md").read_text()

        with self.assertRaises(RefusedError):
            stamp(agents, bump={"foo"})
        with self.assertRaises(RefusedError):
            stamp(agents, forks={"bundle": ""})
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(main(["stamp", str(agents), "--fork", "bundle"]), 2)
            self.assertEqual(main(["stamp", str(agents), "--bump", "foo"]), 2)
        self.assertEqual((agents / "README.md").read_text(), before)

    def test_a_path_with_a_space_is_read_as_git_names_it(self) -> None:
        repo = make_carrier(self.root, "one")
        (repo / ".agents/tracking/new candidates.md").write_text(HEADER_DOC.format(component="tracking"))

        self.assertEqual(check_local(repo), [])

    def test_body_strips_as_the_shell_recipe_does(self) -> None:
        """Unclosed frontmatter included: the `awk` of the bundle README drops everything after it."""
        awk = shutil.which("awk")
        if not awk:
            self.skipTest("no awk on this machine")
        program = "BEGIN{h=0} NR==1 && /^---$/{h=1;next} h&&/^---$/{h=0;next} !h"
        for text in ("---\na: 1\n---\nbody\n---\nmore\n", "---\na: 1\nnever closed\n", "no header\n---\n", ""):
            path = self.root / "doc.md"
            path.write_text(text)
            shell = subprocess.run([awk, program, str(path)], check=True, capture_output=True, text=True).stdout
            self.assertEqual(body(text), shell, repr(text))

    def test_help_shows_the_usage_block_and_every_command(self) -> None:
        out = io.StringIO()

        with contextlib.redirect_stdout(out), self.assertRaises(SystemExit):
            main(["--help"])

        for text in ("note-state SLUG", "id d|i|s TEXT", "report [TREE]", "move a note to active/", "carrier-id [REPO] [--mint]",
                     "ids [--carrier REPO] FILE", "privacy [TREE] [--paths FILE...] [--terms FILE]"):
            self.assertIn(text, out.getvalue())


class RecordIdCheck(Base):
    """A record id is checked for form, prefix and uniqueness, never re-derived from its text."""

    def write(self, name: str, text: str) -> Path:
        path = self.root / name
        path.write_text(text)
        return path

    def test_a_record_defined_twice_is_named_and_a_citation_may_repeat(self) -> None:
        one = self.write("one.md", "| d-abcdef-111111 | a decision |\n\nSee d-abcdef-111111, and again d-abcdef-111111.\n")
        two = self.write("two.md", "## 2026-01-01 · s-abcdef-222222 — a session\n\n| d-abcdef-111111 | the same, again |\n")

        errors, warnings, counts = record_id_check([one, two], "r-abcdef")

        self.assertEqual(len(errors), 1, errors)
        self.assertIn("d-abcdef-111111 defined twice (first at", errors[0])
        self.assertIn("two.md:3", errors[0])
        self.assertEqual(warnings, [])
        self.assertEqual((counts["definitions"], counts["citations"]), (3, 2))

    def test_a_malformed_id_is_named_and_the_first_scheme_is_not(self) -> None:
        path = self.write("log.md", "| d-abcdef-12345 | a digit dropped |\n| D-ABCDEF-123456 | a case changed |\n"
                                    "| d-abcdef-017 | the first scheme |\n\nnot an id: re-run, i-th, s-curve\n")

        errors, _, counts = record_id_check([path], "r-abcdef")

        self.assertEqual(len(errors), 2, errors)
        self.assertTrue(all("malformed" in e for e in errors))
        self.assertEqual(counts["legacy"], 1)

    def test_a_definition_under_another_carriers_id_is_a_warning(self) -> None:
        path = self.write("log.md", "| d-fedcba-333333 | copied from elsewhere |\n\nCites d-fedcba-444444.\n")

        errors, warnings, _ = record_id_check([path], "r-abcdef")

        self.assertEqual(errors, [])
        self.assertEqual(len(warnings), 1, warnings)
        self.assertIn("log.md:1", warnings[0])

    def test_fenced_examples_are_not_read(self) -> None:
        path = self.write("method.md", "~~~text\n| d-abcdef-555555 | example |\n| d-abcdef-555555 | example |\n| d-abcdef-5 |\n~~~\n")

        self.assertEqual(record_id_check([path], "r-abcdef")[0], [])

    def test_the_command_exits_one_on_an_error(self) -> None:
        repo = make_carrier(self.root, "one")
        good = self.write("good.md", f"| d-{repo_carrier_id(repo)[2:]}-666666 | fine |\n")
        bad = self.write("bad.md", "| d-abcdef-6666 | wrong |\n")

        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(main(["ids", "--carrier", str(repo), str(good)]), 0)
            self.assertEqual(main(["ids", "--carrier", str(repo), str(good), str(bad)]), 1)


class Privacy(Base):
    """Nothing in the bundle identifies a private repository, its people or its infrastructure.

    Every planted string is assembled at run time, so this file's own source never holds the leak it
    plants: the tool is itself a travelling file, and `test_this_tool_passes_its_own_check` reads it.
    """

    # Each FAIL rule, and one line that breaks it.
    PLANTED = {
        "email": "Write to " + "jdoe" + "@" + "corp-mail.io" + " for access.",
        "home-path": "The script lived in " + "/Us" + "ers/jdoe/src/tool.",
        "forge-url": "Cloned from https://git" + "hub.com/" + "jdoe/ledger.",
        "chosen-id": "Tracked as " + "PAY" + "-1042" + " in the board.",
        "currency": "It saved " + "$" + "12" + ",400" + " a month.",
        "timezone": "Runs at 03:00 " + "UTC" + "-3" + " every night.",
        "version-pin": "Pinned to " + "somelib" + "==" + "2.4.1" + " since then.",
        "id-beside-domain": "r-" + "4c1d2e" + " is the " + "pay" + "ments service.",
    }

    def tree(self) -> Path:
        return make_carrier(self.root, "one") / ".agents"

    @staticmethod
    def plant(path: Path, text: str) -> None:
        path.write_text(path.read_text() + "\n" + text + "\n")

    @staticmethod
    def rules(report: PrivacyReport, level: str = "FAIL") -> list[str]:
        return [f.rule for f in report.findings if f.level == level]

    def test_a_clean_bundle_passes(self) -> None:
        report = privacy_check(self.tree())

        self.assertEqual(report.findings, [])
        self.assertIn("no private terms checked", report.terms)

    def test_each_rule_fails_on_its_planted_leak(self) -> None:
        for rule, line in self.PLANTED.items():
            with self.subTest(rule=rule):
                agents = make_carrier(self.root, f"t-{rule}") / ".agents"
                self.plant(agents / "roadmap.md", line)

                report = privacy_check(agents)

                self.assertEqual(self.rules(report), [rule], report.findings)
                self.assertTrue(report.failures[0].where.startswith("roadmap.md:"))

    def test_a_code_name_fails_in_the_evidence_and_nowhere_else(self) -> None:
        agents = self.tree()
        name = "settle" + "Amount"
        note = agents / "knowledge/notes/active/absence.md"
        self.plant(note, f"## Why it works\n\nThe field `{name}` held it.\n\n## Evidence\n\nThe field `{name}` held it.\n"
                         f"\n## Literature\n\nThe field `{name}` held it.")
        self.plant(agents / "tracking/carriers.md", f"The field `{name}` held it.")

        report = privacy_check(agents)

        where = sorted(f.where for f in report.failures)
        self.assertEqual(self.rules(report), ["code-identifier"] * 2, report.findings)
        self.assertTrue(where[0].startswith("knowledge/notes/active/absence.md:"), where)
        self.assertTrue(where[-1].startswith("tracking/carriers.md:"), where)
        self.assertEqual(self.rules(privacy_check(self.tree_with(f"`{name}` and `forked_at` and `max_tokens`"))), [])

    def tree_with(self, line: str) -> Path:
        agents = make_carrier(self.root, "w" + str(len(list(self.root.iterdir())))) / ".agents"
        self.plant(agents / "roadmap.md", line)
        return agents

    def test_literature_and_references_are_exempt_from_versions_counts_and_quotes(self) -> None:
        agents = self.tree()
        pin = self.PLANTED["version-pin"]
        self.plant(agents / "knowledge/notes/active/absence.md", f"## Literature\n\n{pin} Read 4 " + "of 7 " + "of 12" + ",345 pages.")
        (agents / "references.md").write_text(HEADER_DOC.format(component="references") + f"\n{pin}\n")
        self.plant(agents / "knowledge/notes/active/a-check.md", f"## What it costs\n\n{pin}")

        report = privacy_check(agents)

        self.assertEqual([(f.rule, f.where.split(":")[0]) for f in report.findings],
                         [("version-pin", "knowledge/notes/active/a-check.md")])

    def test_warnings_are_advisory(self) -> None:
        agents = self.tree()
        self.plant(agents / "tracking/carriers.md", 'It failed 7 ' + 'of 12 runs over 12' + ',480 rows: "the queue was never drained at all".')

        report = privacy_check(agents)

        self.assertEqual(sorted(self.rules(report, "WARN")), ["exact-count", "n-of-m", "quote"])
        self.assertEqual(report.failures, [])
        self.assertFalse([p for p in check_problems(agents, privacy=report) if p.startswith("privacy:")])
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(main(["privacy", str(agents)]), 0)

    def test_a_fence_with_placeholders_is_an_example_for_addresses_paths_and_urls_only(self) -> None:
        terms = self.root / "terms.txt"
        terms.write_text("# mine\n" + "Zebra" + "corp\n")
        agents = self.tree()
        email = self.PLANTED["email"]
        self.plant(agents / "layout.md", f"```text\n{email}\nuser = <your-name>\n" + "zebra" + "corp\n```\n\n```text\n" + email + "\n```")

        report = privacy_check(agents, terms_file=terms)

        self.assertEqual(sorted(self.rules(report)), ["email", "private-term"])
        self.assertNotIn("ebra", " ".join(f.match for f in report.findings), "the output repeats the term it caught")

    def test_a_private_term_fails_and_the_terms_file_is_named(self) -> None:
        terms = self.root / "config/agent-guides/private-terms.txt"
        terms.parent.mkdir(parents=True)
        terms.write_text("Zebra" + "corp\n\n")
        agents = self.tree()
        self.plant(agents / "roadmap.md", "Built for " + "ZEBRA" + "CORP" + " last year.")

        report = privacy_check(agents)

        self.assertEqual(self.rules(report), ["private-term"])
        self.assertIn(str(terms), report.terms)
        with self.assertRaises(RefusedError):
            privacy_check(agents, terms_file=self.root / "no-such-terms.txt")

    def test_a_waiver_suppresses_its_line_and_is_listed(self) -> None:
        agents = self.tree()
        marker = "<!-- privacy-" + "allow: the maintainer asked, 2026-01-01 -->"
        self.plant(agents / "roadmap.md", self.PLANTED["email"] + " " + marker)
        self.plant(agents / "layout.md", self.PLANTED["currency"] + " <!-- privacy-" + "allow: -->")
        self.plant(agents / "layout.md", "Mark a line with `privacy-" + "allow: <reason>`.")

        report = privacy_check(agents)

        self.assertEqual(sorted(self.rules(report)), ["allow-without-reason", "currency"])
        self.assertEqual(len(report.allowances), 1)
        where, reason = report.allowances[0]
        self.assertTrue(where.startswith("roadmap.md:"))
        self.assertEqual(reason, "the maintainer asked, 2026-01-01")
        self.assertIn(f"  . allowed {where}: {reason}", report.notes())

    def test_paths_outside_a_bundle_are_read_with_the_same_rules(self) -> None:
        readme = self.root / "README.md"
        readme.write_text("# A project\n\n" + self.PLANTED["forge-url"] + "\n")

        report = privacy_check(paths=[readme])

        self.assertEqual(self.rules(report), ["forge-url"])
        self.assertEqual(report.failures[0].where, f"{readme}:3")
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(main(["privacy", "--paths", str(readme)]), 1)

    def test_digest_check_fails_on_a_privacy_fail(self) -> None:
        agents = self.tree()
        self.plant(agents / "roadmap.md", self.PLANTED["timezone"])

        self.assertTrue(any(p.startswith("privacy: roadmap.md:") for p in check_problems(agents)))
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(main(["digest", str(agents), "--check"]), 1)

    def test_what_is_not_a_leak_is_not_reported(self) -> None:
        agents = self.tree()
        self.plant(agents / "roadmap.md", "\n".join([
            "Mail t" + "@" + "example.com, see https://git" + "hub.com/owner/repo and " + "/Us" + "ers/you/code.",
            "SHA" + "-256, UTF" + "-16, RFC" + "-2119 and ESD-TR-73-51 are citations; " + "`awk '{print $1}'` is a shell.",
            "Released 2026-09-24 at 10:00; " + "r-" + "abcdef" + " is the payments example; about 9 % and hundreds a month.",
            "https://docs.git" + "hub.com/en/copilot is documentation, and 200 000 is round.",
        ]))

        self.assertEqual(privacy_check(agents).findings, [])

    def test_this_tool_passes_its_own_check(self) -> None:
        """Warnings included: the tool travels in every carrier, and its fixtures are assembled so it stays quiet."""
        self.assertEqual(privacy_check(paths=[Path(__file__)]).findings, [])


# --- command line ----------------------------------------------------------------------------------


@dataclass(frozen=True)
class Scope:
    """The repositories this session works on, and where that list came from.

    The source is carried because the report about what was *left out* depends on it. A scope the
    session declared can be compared with what the machine knows, so `outside` names the carriers
    nobody is looking at. A scope taken from the manifest **is** what the machine knows, so that
    comparison is empty however wide the operation was — and an empty list reads exactly like a
    narrow scope carefully respected. Whoever prints it has to say which of the two it is.
    """

    repos: list[Path]
    source: str  # "argument", "environment" or "manifest"

    @property
    def declared(self) -> bool:
        """Whether this session said what it works on, rather than inheriting every carrier."""
        return self.source != "manifest"

    def __iter__(self):  # noqa: ANN204 -- an iterator of Path; the annotation needs typing.Iterator
        return iter(self.repos)

    def __len__(self) -> int:
        return len(self.repos)


def workspace(args: list[str], manifest: Path | None = MANIFEST, *, writing: bool = False) -> Scope:
    """The repositories this session works on: the ones given, else `AGENT_WORKSPACE`, else the manifest.

    Every path has to carry a bundle, so a mistyped one stops the session instead of being skipped.

    **A command that writes never falls back to the manifest.** The manifest is this machine's list
    of carriers; it says which repositories exist, never which ones a session may edit. Inferring
    the second from the first hands every carrier to whoever forgot the argument, and the guard
    that would have named the ones left out is derived from the same list, so it stays silent.

    Raises:
        NotACarrierError: If a path holds no `.agents` folder.
        UndeclaredScopeError: If a writing command was given no scope at all.
    """
    if args:
        given, source = args, "argument"
    elif environment := [p for p in os.environ.get(WORKSPACE_ENV, "").split(os.pathsep) if p]:
        given, source = environment, "environment"
    elif writing:
        raise UndeclaredScopeError(
            f"no repositories given and no {WORKSPACE_ENV}: the manifest lists this machine's carriers, "
            "it does not say which ones this session may write. Name the repositories this session has open.")
    elif manifest is None or not manifest.exists():
        sys.exit(f"no repositories given, no {WORKSPACE_ENV}, and no manifest at {manifest}")
    else:
        given, source = manifest_carriers(manifest), "manifest"
    repos = [Path(p).expanduser().resolve() for p in given]
    missing = [str(r) for r in repos if not (r / ".agents").is_dir()]
    if missing:
        raise NotACarrierError(f"no bundle in {missing}")
    if not repos:
        # A manifest with `carriers = []` gave an empty scope, and every command then failed on its
        # first carrier: `repos[0]` in gather, `next(iter(...))` in align.
        raise RefusedError(f"the scope is empty (from the {source}): name the carriers this session has open")
    return Scope(repos, source)


def manifest_carriers(manifest: Path) -> list[str]:
    """The `carriers` list of the local manifest.

    `tomllib` reads it from Python 3.11. Before that, only the manifest's documented form is read —
    `carriers = ["/a", "/b"]`, one list of quoted strings, comments allowed — and anything else is
    refused rather than guessed at. Without this a carrier on an older interpreter cannot run the tool
    at all, and a carrier that cannot run the one recipe reproduces it by hand: seen 2026-09-23, a
    carriers row written by hand on a machine with 3.9.
    """
    text = manifest.read_text(encoding="utf-8")
    if tomllib is not None:
        return list(tomllib.loads(text).get("carriers", []))
    found = re.search(r"^[ \t]*carriers[ \t]*=[ \t]*\[(.*?)\]", text, re.MULTILINE | re.DOTALL)
    if not found:
        return []
    carriers = []
    for double, single, comment, other in re.findall(r'"([^"\\]*)"|\'([^\']*)\'|(#[^\n]*)|([^\s,])', found.group(1)):
        if other:
            raise ValueError(f"{manifest}: `carriers` must be a list of quoted paths; install Python 3.11+ for full TOML")
        if not comment:
            carriers.append(double or single)
    return carriers


def outside(scope: Scope, manifest: Path | None = MANIFEST) -> list[str]:
    """The carriers this machine knows that the scope does not hold: never written, always named.

    Empty for a scope that came from the manifest, because there the two lists are the same one.
    `Scope.declared` is what tells the caller whether an empty answer means anything.
    """
    if not scope.declared or manifest is None or not manifest.exists():
        return []
    listed = [Path(p).expanduser().resolve() for p in manifest_carriers(manifest)]
    return [str(p) for p in listed if p not in scope.repos]


def _scope_report(scope: Scope, verb: str) -> list[str]:
    """One line per carrier this session leaves alone — or one line saying why it can name none."""
    if not scope.declared:
        return [f"  . scope taken from the manifest: every carrier this machine knows is in it, so none is named as {verb}"]
    return [f"  . outside this workspace, not {verb}: {name}" for name in outside(scope)]


# Every refusal the tool raises on purpose. Caught in `main`, printed as one line, exit 2: a refusal
# is an answer, not a crash.
REFUSALS = (NotACarrierError, OutsideWorkspaceError, DirtyTreeError, AlreadyBumpedError, UndeclaredScopeError, RefusedError)


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="bundle.py", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    sub = parser.add_subparsers(dest="command", required=True, metavar="COMMAND")
    p = sub.add_parser("digest", help="the three digests, one recipe; --check adds provenance, links, reachability, session reads, privacy")
    p.add_argument("tree", nargs="?", default=str(OWN_BUNDLE))
    p.add_argument("--check", action="store_true", help="also fail on any problem check_problems finds, privacy FAILs included")
    p = sub.add_parser("carrier-id", help="a repository's stored random id; --mint writes one where there is none")
    p.add_argument("repo", nargs="?", default=str(OWN_REPO))
    p.add_argument("--mint", action="store_true", help="write a new random id into the README header, after `adopted:`; refused if one is stored")
    p = sub.add_parser("id", help="a new id: lineage (g|m|k PART...) or record (d|i|s TEXT...)")
    p.add_argument("kind", choices=["g", "m", "k", *RECORD_KINDS])
    p.add_argument("parts", nargs="+", metavar="PART_OR_TEXT")
    p.add_argument("--repo", help="d|i|s only: the carrier the record belongs to, by its stored id (default: this tool's repository)")
    p = sub.add_parser("ids", help="record ids in files: malformed or defined twice (exit 1), defined under another carrier's id (warned)")
    p.add_argument("files", nargs="+", metavar="FILE")
    p.add_argument("--carrier", metavar="REPO", help="the carrier whose prefix a definition should carry (default: this tool's repository, if it stores an id)")
    p = sub.add_parser("privacy", help="nothing that identifies a private repository, its people or its infrastructure (exit 1 on a FAIL)")
    p.add_argument("tree", nargs="?", default=str(OWN_BUNDLE), help="the bundle whose travelling files are read")
    p.add_argument("--paths", nargs="+", metavar="FILE", help="read these files instead of the tree, anywhere (a repository's README, a staged file)")
    p.add_argument("--terms", metavar="FILE", help="the private terms file (default: $XDG_CONFIG_HOME or ~/.config, agent-guides/private-terms.txt)")
    p = sub.add_parser("note-state", help="move a note to active/, review/ or retired/, links rewritten")
    p.add_argument("slug")
    p.add_argument("state", choices=NOTE_STATES)
    p.add_argument("tree", nargs="?", default=str(OWN_BUNDLE))
    p = sub.add_parser("report", help="files, bytes and estimated tokens per folder and per session type")
    p.add_argument("tree", nargs="?", default=str(OWN_BUNDLE))
    p.add_argument("--json", action="store_true", help="the report as JSON instead of markdown tables")
    p = sub.add_parser("gather", help="phase 1: snapshots, base, N-way table (writes only --out)")
    p.add_argument("repos", nargs="*")
    p.add_argument("--out", required=True)
    p = sub.add_parser("lost", help="every line a carrier added that the merged tree lacks")
    p.add_argument("base")
    p.add_argument("merged")
    p.add_argument("snapshots", nargs="+", help="gather's carriers/<name>/.agents folders")
    p = sub.add_parser("stamp", help="versions, released dates and digests of a release tree")
    p.add_argument("tree")
    p.add_argument("--bump", default="", help=f"comma-separated kinds: {', '.join(STAMP_KINDS)}")
    p.add_argument("--fork", action="append", default=[], metavar="KIND=ID")
    p.add_argument("--against", help="the previous release; changed documents get their own version bumped")
    p.add_argument("--released")
    p = sub.add_parser("register", help="the carriers table, by stored carrier id")
    p.add_argument("tree")
    p.add_argument("repos", nargs="*")
    p = sub.add_parser("splice", help="phase 2: the release into each carrier, its own fields kept")
    p.add_argument("merged")
    p.add_argument("repos", nargs="*")
    p.add_argument("--write", action="store_true")
    p.add_argument("--backup")
    p.add_argument("--allow-dirty", action="store_true")
    p = sub.add_parser("align", help="phase 3: every carrier on one version")
    p.add_argument("repos", nargs="*")
    p = sub.add_parser("check-local", help="the local step wrote only tracking/")
    p.add_argument("repos", nargs="*")
    sub.add_parser("selftest", help="the tool's own tests, in this file")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        return _run(args)
    except REFUSALS as refusal:
        print(f"  x {refusal}")
        return 2


def _run(args: argparse.Namespace) -> int:  # noqa: C901, PLR0911, PLR0912 -- one branch per command
    if args.command == "digest":
        tree = Path(args.tree)
        print(f"bundle {bundle_digest(tree)}  method {method_digest(tree)}  knowledge {knowledge_digest(tree)}")
        problems = []
        if args.check:
            privacy = privacy_check(tree)
            problems = check_problems(tree, privacy=privacy)
            for note in privacy.notes():
                print(note)
        for problem in problems:
            print("  x " + problem)
        return 1 if problems else 0
    if args.command == "carrier-id":
        if args.mint:
            print(f"minted {mint_carrier_id(Path(args.repo))} in {Path(args.repo) / '.agents/README.md'}; commit it")
        else:
            print(repo_carrier_id(Path(args.repo)))
        return 0
    if args.command == "ids":
        if args.carrier:
            carrier = repo_carrier_id(Path(args.carrier))
        else:
            carrier = stored_carrier_id(OWN_REPO) if (OWN_BUNDLE / "README.md").is_file() else None
            if carrier is None:
                print("  . no carrier id stored here: prefixes not checked (give --carrier REPO)")
        errors, warnings, counts = record_id_check([Path(f) for f in args.files], carrier)
        for line in [f"  x {e}" for e in errors] + [f"  ! {w}" for w in warnings]:
            print(line)
        print(f"{counts['ids']} record ids in {len(args.files)} files: {counts['definitions']} defined, {counts['citations']} cited, "
              f"{counts['legacy']} of the first scheme; {len(errors)} errors, {len(warnings)} warnings")
        return 1 if errors else 0
    if args.command == "privacy":
        result = privacy_check(Path(args.tree), [Path(f) for f in args.paths] if args.paths else None,
                               Path(args.terms) if args.terms else None)
        for f in result.failures:
            print(f"  x FAIL {f.where} {f.rule}: {f.match}")
        for note in result.notes():
            print(note)
        print(result.summary())
        return 1 if result.failures else 0
    if args.command == "id":
        if args.kind in RECORD_KINDS:
            print(record_id(args.kind, " ".join(args.parts), repo_carrier_id(Path(args.repo) if args.repo else OWN_REPO)))
        elif args.repo:
            raise RefusedError("--repo names the carrier of a record id (d|i|s); a lineage id is derived from its parts only")
        else:
            print(derive_id(args.kind + "-", *args.parts))
        return 0
    if args.command == "note-state":
        for change in note_state(Path(args.tree), args.slug, args.state) or [f"{args.slug} is already {args.state}; nothing changed"]:
            print(change)
        return 0
    if args.command == "report":
        data = report(Path(args.tree))
        print(json.dumps(data, indent=2) if args.json else report_markdown(data), end="\n" if args.json else "")
        return 0
    if args.command == "gather":
        scope = workspace(args.repos)
        for line in _scope_report(scope, "gathered"):
            print(line)
        result = gather(scope.repos, Path(args.out))
        print(Path(args.out) / "gather.md", f"-- base by {result['base_rule']}, from {result['base_from']}")
        return 0
    if args.command == "lost":
        snapshots = [Path(s) for s in args.snapshots]
        names = _names([s.parent for s in snapshots])
        missing = lost(Path(args.base), Path(args.merged), dict(zip(names, snapshots)))
        for name, rel, line in missing:
            print(f"  x {name} {rel}: {line[:120]}")
        print(f"{len(missing)} lines lost")
        return 1 if missing else 0
    if args.command == "stamp":
        malformed = [f for f in args.fork if "=" not in f]
        if malformed:
            raise RefusedError(f"--fork {', '.join(malformed)}: expected KIND=ID")
        forks = dict(f.split("=", 1) for f in args.fork)
        bump = {k.strip() for k in args.bump.split(",") if k.strip()}
        stamp(Path(args.tree), bump, forks, Path(args.against) if args.against else None, args.released)
        print(f"stamped {args.tree}: bundle {bundle_digest(Path(args.tree))}")
        return 0
    if args.command == "register":
        scope = workspace(args.repos, writing=True)
        register(Path(args.tree), scope.repos)
        print(f"registered {len(scope)} carriers in {args.tree}/tracking/carriers.md")
        return 0
    if args.command == "splice":
        scope = workspace(args.repos, writing=args.write)
        _names(scope.repos)
        # The temporary backup folder only when something is written: a dry run used to leave an
        # empty `bundle-backup-*` behind on every call.
        backup = None
        if args.write:
            backup = Path(args.backup) if args.backup else Path(tempfile.mkdtemp(prefix="bundle-backup-"))
        for line in _scope_report(scope, "written"):
            print(line)
        for repo in scope:
            actions = splice(Path(args.merged), repo, args.write, backup, allow_dirty=args.allow_dirty, workspace=scope.repos)
            if not actions:
                print(f"{repo.name}: the release itself, left alone")
                continue
            verb = "spliced" if args.write else "would splice"
            print(f"{repo.name}: {verb} {sum(a.startswith('write') for a in actions)} files, "
                  f"remove {sum(a.startswith('remove') for a in actions)}" + (f" (backup {backup})" if args.write else ""))
        return 0
    if args.command == "selftest":
        suite = unittest.defaultTestLoader.loadTestsFromModule(sys.modules[__name__])
        return 0 if unittest.TextTestRunner(verbosity=1).run(suite).wasSuccessful() else 1
    if args.command == "check-local":
        repos = workspace(args.repos).repos
        found = check_local_all(repos)
        for name, problems in found:
            for problem in problems:
                print(f"  x {name}: {problem}")
        print(f"local step over {len(repos)} repositories: " + ("only `tracking/` changed" if not found else f"{sum(len(p) for _, p in found)} files they may not write"))
        return 1 if found else 0
    if args.command == "align":
        scope = workspace(args.repos)
        for line in _scope_report(scope, "aligned"):
            print(line)
        repos = scope.repos
        problems = align(repos)
        for problem in problems:
            print("  x " + problem)
        print(f"{len(repos)} carriers " + ("aligned" if not problems else f"NOT aligned: {len(problems)} problems"))
        return 1 if problems else 0
    return 2


if __name__ == "__main__":
    sys.exit(main())
