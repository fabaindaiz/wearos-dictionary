---
bundle:    agent-guides
lineage:   g-8b5800/main
ancestry:  [g-c7344c, g-099a8a, g-8b5800]
version:   9
component: layout
released:  2026-09-24
---

# Where agent files go, and against which standard

A guide to laying out agent-facing files in any repository. It states what is **actually
standardised**, what is **three competing drafts**, and what a repository can safely adopt
from the overlap. The concrete examples are Claude Code, because that is the assistant this
copy is used with; the reasoning is not specific to it.

## The one thing that is settled

**`AGENTS.md` at the repository root.** Stewarded by the Agentic AI Foundation under the
Linux Foundation, present in more than 60,000 repositories. Its rules are few and worth
knowing exactly:

- **Uppercase.** `AGENTS.md`, not `agents.md` — the name every implementation and the spec's own
  pages use, and the one that behaves the same on case-sensitive and case-insensitive
  filesystems. The spec's page does not state it as a rule (checked 2026-09-23); it is
  followed here because one of the drafts below lowercases it and breaks on exactly that.
- **Plain Markdown, no schema.** *"Use any headings you like; the agent simply parses the text
  you provide."* There is no frontmatter requirement and no field list to satisfy.
- **Nearest file wins.** In a monorepo, a package may hold its own `AGENTS.md`, and the one
  closest to the edited file takes precedence. An explicit instruction in the conversation
  overrides all of them.

**The specification says nothing about a `.agents/` directory.** That absence is the single
most useful fact in this guide, because everything below is people filling the gap.

## What `.agents/` actually is: three drafts that disagree

| | bgreenwell/dotagents | dotagentsprotocol.com | agentsstandard.com |
|---|---|---|---|
| Self-described status | "Draft Proposal" | "draft… open and evolving" | maintained, not universally adopted |
| Layout | `personas/`, `skills/`, `settings/` | `skills/`, `agents/`, `tasks/`, `memories/`, `mcp.json`, `models.json` | `.agents/AGENTS.md` plus behaviour, MCP and skills |
| The root file | `AGENTS.md`, uppercase, acts as a router | `agents.md`, **lowercase**, inside `.agents/` | both `~/.agents/AGENTS.md` and a root `AGENTS.md` |
| Human `docs/` | explicitly stays outside, human-facing | not addressed | not addressed |

They are not variations on one idea. One of them lowercases the filename the actual standard
requires to be uppercase; one introduces a five-tier loading cascade; one keeps `docs/`
deliberately separate. **Adopting any single draft wholesale is a bet on that draft**, and the
cost of being wrong is a layout that the next tool does not recognise and that a reader has to
be taught.

## What to adopt: the intersection

These four are common to all three drafts and consistent with the real standard. They are
safe, and they are most of the value:

1. **`AGENTS.md` at the root is the source.** Uppercase, human-readable, the thing every
   assistant is pointed at.
2. **`.agents/` holds agent-facing resources that are not human documentation.** The split is
   the point: a document a person reads to understand the system belongs in `docs/`; a
   document that exists to configure or instruct an assistant belongs in `.agents/`.
3. **Skills live in `.agents/skills/`**, one folder per skill containing `SKILL.md` — **when
   more than one assistant reads them.** A repository served by one assistant keeps one skills
   folder, the one that assistant loads (for Claude Code, `.claude/skills/`, which is what the
   method's artifact 3 specifies). Two folders holding the same skill are two sources of truth.
   Copilot reads project skills from `.github/skills/`, `.claude/skills/` or `.agents/skills/`
   (checked 2026-09-24), so a repository served by Claude Code and Copilot can still keep one.
4. **Directories do not load themselves.** The root file must *route* to what a task needs.
   This is progressive disclosure, and it is why a well-organised `.agents/` still needs
   `AGENTS.md` to name what is in it.

And one rule the drafts agree on that is easy to get wrong: **generated state is not version
controlled.** Memory, logs and caches produced by an assistant go in `.agents/` only if they
are also in `.gitignore`.

## What to decline, and say so

- **`mcp.json` / `models.json` as committed files.** They carry endpoints, model choices and
  sometimes keys. Vendor configuration that differs per developer belongs in that vendor's own
  ignored config, not in a shared directory.
- **`agents.md` lowercase.** It contradicts the one rule the real standard is strict about.
- **`personas/`.** A persona is a prompt fragment; unless a repository actually maintains
  several, the directory is structure with nothing in it.
- **The five-tier cascade.** Useful if your assistant implements it. None currently does
  natively, and a cascade nobody evaluates is four files that do not load.

Write each declined item down where the layout is recorded. A refusal without a reason gets
re-proposed every time somebody reads a different draft.

## Mapping any repository onto it

Ask two questions per file, in this order:

1. **Would a person read this to understand the system?** → `docs/`. Architecture, decisions,
   operations, runbooks. These outlive any assistant and belong to the project.
2. **Does this exist to instruct or configure an assistant?** → `.agents/`. Skills, method,
   accumulated judgement, intake.

The awkward cases are the useful ones:

| File | Where | Why |
|---|---|---|
| A decisions log | `docs/` | a person reads it in a review; the assistant is a consumer, not the reason it exists |
| A skill | `.agents/skills/` | it has no meaning without an assistant to invoke it |
| Accumulated engineering judgement | `.agents/` | written *for* an assistant that would otherwise give the textbook answer — a person would read a book instead |
| A structural audit script | `tools/` | it runs in the gate; it is code, and code has a home already |
| The bundle's own tool | `.agents/tools/` | it travels with the bundle and is identical in every carrier: one standalone file, standard library only, that finds its bundle from its own location. The host's audit stays in the host's `tools/`; the two never import each other |
| Architecture documentation | `docs/` | the assistant reads it, but it would exist without one |

**The test that resolves most disputes:** would this file still be written if no assistant
existed? If yes, it is documentation. If no, it is agent configuration.

## The Claude Code case, concretely

Claude Code reads its own paths and does not know about `.agents/`. So the layout is
**one source, one surface**, and the surface is thin:

| Claude reads | Holds | Relationship to `.agents/` |
|---|---|---|
| `CLAUDE.md` at the root | loaded on every request | first line is `@AGENTS.md`, then Claude-specific additions only |
| `.claude/skills/<name>/SKILL.md` | chosen on demand from its `description` | Claude's copy or link of what `.agents/skills/` holds |
| `.claude/rules/*.md` with `paths:` | loaded when a matching file is read | per-area rules; the same glob Cursor writes as `globs:` and Copilot as `applyTo:` |
| `.claude/settings.json` | permissions and hooks | vendor-specific, stays vendor-side |

Three consequences worth stating, because each one is a mistake people make:

- **`AGENTS.md` is read by Claude Code only when there is no `CLAUDE.md`.** Once a `CLAUDE.md`
  exists — and one does as soon as anything Claude-specific is written down — `AGENTS.md` is
  seen only through `@AGENTS.md` in it, which is the documented bridge and keeps one source.
  An import is not progressive disclosure: the imported file is loaded on every request and
  counts against the root file's budget. (Corrected 2026-09-23; earlier copies said Claude Code
  never reads `AGENTS.md`, which was true of older versions. Sources in `references.md`.)
- **`permissions` only checks `Edit(path)` and `Read(path)`.** A `Write(path)` rule is
  accepted, never consulted, and warned about at startup — a deny written that way protects
  nothing.
- **Nothing in `.agents/` loads by itself.** If a knowledge base or a method lives there, the
  root file has to name it and say when to reach for it, or it is a folder of files nobody
  opens.

**For other assistants**, the same shape applies with different filenames (checked against
their documentation 2026-09-24; sources in `references.md`). **Cursor** reads `AGENTS.md`,
nested ones included, and `.cursor/rules/*.mdc` rules that apply always, by glob, by
description or by @-mention; it has hooks in `.cursor/hooks.json`. **Copilot** reads
`.github/copilot-instructions.md`, path-scoped `.github/instructions/*.instructions.md` with an
`applyTo` glob, and `AGENTS.md` (nearest wins) on the surfaces that read agent instructions —
which surfaces read which file differs, and Visual Studio chat reads no path-scoped file; its
hooks in `.github/hooks/*.json` run on the cloud agent and the CLI only. Its on-demand
procedures come in three shapes: **agent skills** (`SKILL.md` folders, chosen from their
`description`, on the cloud agent, code review, the CLI and VS Code agent mode), **prompt files**
(`.github/prompts/*.prompt.md`, run by hand as a slash command in VS Code, Visual Studio and
JetBrains, in public preview) and **custom agents** (profiles in `.github/agents/`). So every assistant now
reads `AGENTS.md` in some form, and **no two enforce the same way**: anything that must hold
cannot depend on an assistant's machinery — it belongs in the gate, a script, a type or a
schema.

## Fences, and why two kinds

Two fence styles appear in these documents and the difference is deliberate:

| Fence | Means | Example |
|---|---|---|
| ```` ``` ```` | **a code example you read** — a command, a config, a snippet | a settings file, a template |
| `~~~text` | **a block you copy whole** — an invocation pasted into an agent | every *Paste this to start* block |

The distinction earns its place twice over. It is a **semantic marker**: a reader scanning a
prompt for the thing to copy finds it by shape, without reading the prose around it. And it is
**load-bearing in at least one place** — a tilde fence can contain triple backticks, so a paste
block that quotes a fenced example does not break. One block in this set relies on that today;
the others do not, and using the same fence for all of them is what keeps the next one from
breaking when somebody adds an example inside.

**Do not normalise them to backticks.** That is the specific edit this note exists to prevent:
it looks like tidying, it silently truncates any paste block that quotes a fence, and the
damage shows up as a prompt that copies half of itself.

The `text` hint on the tilde fences is there to stop renderers from syntax-highlighting a
block that is not code in any language — it is an instruction to a reader, and colouring it
implies a structure it does not have.

## The honest summary

`AGENTS.md` at the root is a standard and worth following exactly. `.agents/` is a
**convention in flux**: adopt the intersection above, record what you declined, and expect the
drafts to move. The layout costs little to change later — a directory move and a set of
pointers — so the reasonable position is to take what is common, skip what is contested, and
avoid building anything that only works if one particular draft wins.
