---
# bundle-header — provenance for this document. Identical across the bundle.
bundle:    agent-guides
lineage:   g-8b5800/main
ancestry:  [g-c7344c, g-099a8a, g-8b5800]
version:   3
component: references
released:  2026-09-24
---

# References — the external work behind how these documents are written

**This register covers the method's rules about documents: how they are shaped, kept true, and
written for an agent.** Notes in `knowledge/` cite their own literature under *Literature*.
This file holds what backs `method/` and the note format itself. It is not a reading list.
Following principle 8, an entry is here because it confirmed or changed a rule, and each one
names that rule.

Every link below was opened and checked against the claim on the date shown. A quote is
verbatim. A source that could not be opened is either marked as unverified or left out.

## Writing documents that stay true

- **[Parnas & Clements, 1986, "A Rational Design Process: How and Why to Fake It"](https://doi.org/10.1109/TSE.1986.6312940)**
  (IEEE TSE 12(2)). Documents are designed from the questions they must answer, and each fact
  has one home: *"There must be one, and only one, place for every fact that will be in the
  document."* Rejected alternatives are recorded along with the reason each was rejected. A
  decision does not count as made until the documents include it.
  **Confirms:** principle 5 (each fact once, a map built from questions), principle 10 (record
  the non-decisions), and principle 16.
  **We differ on purpose:** Parnas admits the result "is not easy or relaxing reading". The
  method trades some of his completeness for principle 13's short, imperative rules, because
  its reader is an agent in the middle of a task, not a reviewer.
  **Backs:** principles 5, 10, 13, 16. *Verified 2026-09-23 against the preprint.*

- **[Lethbridge, Singer & Forward, 2003, "How Software Engineers Use Documentation: The State of the Practice"](https://doi.org/10.1109/MS.2003.1241364)**
  (IEEE Software 20(6)). *"Documentation of all types is frequently out of date,"* and engineers
  kept it current *"only when it was attached to the process for completing a change request."*
  **Confirms:** principle 16's mechanism. The update belongs to the change itself, not to a
  later cleanup.
  **Limits it:** outdated high-level documents *"has value, particularly if the high-level
  abstractions remain valid,"* and *"the closer you get to the real code, the more accurate
  the documentation must be."* Principle 16 is therefore strongest for the documents a change
  actually falsifies, and for those nearest the code.
  **Backs:** principle 16; the literature of `copied-instruction-claims-its-origin`.
  *Verified 2026-09-23 against the paper.*

- **[Aghajani et al., 2019, "Software Documentation Issues Unveiled"](https://doi.org/10.1109/ICSE.2019.00122)**
  (ICSE, 878 artefacts). *"Up-to-dateness problems account for 39% of issues related to
  documentation content."* Code examples were the documentation type most affected by
  correctness problems, and the authors recommend *"to apply testing techniques on code
  examples as done on production code."*
  **Confirms:** principle 16, and the *Documentation, in the code* rule that an example should
  be a test where the language allows it.
  **Limits it:** many examples are fragments that *"cannot be actually compiled and run"*. That
  is why the rule says "when the language lets it be".
  **Backs:** principle 16; *Documentation, in the code*; `copied-instruction-claims-its-origin`.
  *Verified 2026-09-23 against the paper.*

- **[Nygard, 2011, "Documenting Architecture Decisions"](https://www.cognitect.com/blog/2011/11/15/documenting-architecture-decisions)**.
  The architecture decision record (ADR): context, decision, status and consequences. *"All
  consequences should be listed here, not just the 'positive' ones,"* and a replaced record is
  marked *"superseded"* with a pointer to what replaced it.
  **Confirms:** the decisions log (artifact 6), a note's *What it costs* section, and the
  `superseded_by` field.
  **We differ on purpose:** an ADR records decisions that were made. Principle 10 also records
  the ones that were declined, and that part comes from Parnas.
  **Backs:** principle 10; artifact 6; the knowledge lifecycle. *Verified 2026-09-23 against the post.*

- **[Diátaxis](https://diataxis.fr/)** (Procida). Documentation comes in four kinds: tutorial,
  how-to, reference and explanation. A how-to guide *"serves the work of the already-competent
  user"*: it contains action and nothing else.
  **Confirms:** principle 13. Instruction files are how-to and reference material for a
  competent reader in the middle of a task, not tutorials.
  **We differ on purpose:** Diátaxis says reference material must *"describe and only
  describe,"* using examples only as illustration. Principle 13 instead points at an exemplary
  file in place of a prose description, because an agent copies an example more reliably than
  it follows a description.
  **Backs:** principle 13. *Verified 2026-09-23 against the site.*

- **[Google developer documentation style guide, "Procedures"](https://developers.google.com/style/procedures)**.
  *"Make sure that the first sentence in a procedural step includes an imperative verb."*
  **Confirms:** principle 13's imperative rules.
  **We differ on purpose:** Google states the purpose before the action. The method states the
  rule first and its consequence straight after. Both put the reason next to the instruction.
  **Backs:** principle 13. *Verified 2026-09-23 against the page.*

- **[Write the Docs, "Docs as Code"](https://www.writethedocs.org/guide/docs-as-code/)**.
  Documentation is written with the same tools as code, and *"you can block merging of new
  features if they don't include documentation."*
  **Confirms:** principles 2 and 16. A document rule belongs in the gate, not in someone's memory.
  **Limits it:** this is practitioner guidance, not research, and Lethbridge argues against
  forcing updates on every document.
  **Backs:** principles 2, 16. *Verified 2026-09-23 against the page.*

- **[Python `doctest`](https://docs.python.org/3/library/doctest.html)** and
  **[rustdoc, "Documentation tests"](https://doc.rust-lang.org/rustdoc/write-documentation/documentation-tests.html)**.
  Examples in documentation run as tests: *"This makes sure that examples within your
  documentation are up to date and working"* (rustdoc).
  **Confirms:** *Documentation, in the code*: a docstring's example is a test.
  **Limits it:** doctest compares output exactly, so unordered sets, addresses and floats break
  it. Choose examples for the reader, not for coverage.
  *Verified 2026-09-23 against both pages.*

## Writing for an agent

- **[AGENTS.md](https://agents.md/)**. Stewarded by the Agentic AI Foundation under the Linux
  Foundation. *"Agents automatically read the nearest file in the directory tree, so the
  closest one takes precedence."*
  **Confirms:** `layout.md`, *The one thing that is settled*.
  **Not confirmed, and corrected in `layout.md`:** it said the specification requires uppercase
  explicitly. The page uses `AGENTS.md` throughout, but no explicit case rule was found on it.
  *Verified 2026-09-23 against the site.*

- **[Anthropic, Claude Code docs, "How Claude remembers your project"](https://code.claude.com/docs/en/memory)**.
  *"Target under 200 lines per CLAUDE.md file. Longer files consume more context and reduce
  adherence."* `@path` imports are supported, but *"imported files still load and enter the
  context window at launch."*
  **Confirms:** artifact 1 (root `CLAUDE.md` under 200 lines) and `@AGENTS.md` as the bridge.
  **Corrected `layout.md`** in bundle v18: it said *"`AGENTS.md` is not read by Claude Code."*
  Current versions read `AGENTS.md` directly when there is no `CLAUDE.md`; when one exists,
  the import is still the route, so the recommended layout stands. And an import is not
  progressive disclosure: whatever is imported is paid for on every request. *Verified 2026-09-23 against the page.*

- **[GitHub Docs, "Adding repository custom instructions for GitHub Copilot"](https://docs.github.com/en/copilot/how-tos/configure-custom-instructions/add-repository-instructions)**
  and **[the support matrix](https://docs.github.com/en/copilot/reference/custom-instructions-support)**.
  Repository-wide instructions live in `.github/copilot-instructions.md`; path-specific ones in
  `.github/instructions/NAME.instructions.md`, whose frontmatter `applyTo` takes a glob, and
  `excludeAgent` can keep one from code review or the cloud agent. Copilot also reads agent
  instructions: `AGENTS.md` anywhere in the tree, where *"the nearest `AGENTS.md` file in the
  directory tree will take precedence"*, or a root `CLAUDE.md` or `GEMINI.md`.
  **Confirms:** `prompt-context.md`, *Three agents, one source* — per-area rules are one glob in
  three spellings; `AGENTS.md` as the shared source.
  **Corrected the method:** it said Copilot had no per-area rules. **Limits it:** support
  differs by surface — Visual Studio reads path-specific files in chat only, and agent
  instructions are read by the cloud agent, the CLI and VS Code chat but not everywhere.
  *Verified 2026-09-24 against both pages.*

- **[GitHub Docs, "About hooks for GitHub Copilot"](https://docs.github.com/en/copilot/concepts/agents/hooks)**.
  Hooks are JSON files in `.github/hooks/*.json`, *"available for use with: Copilot cloud agent
  on GitHub"* and *"GitHub Copilot CLI"*; `preToolUse` *"can approve or deny tool executions."*
  **Corrected the method:** it said Copilot had no enforcement of any kind. **Limits it:** the
  page names no editor surface, so a rule held by a Copilot hook does not hold in the IDE.
  *Verified 2026-09-24 against the page.*

- **[GitHub Docs, "Excluding content from GitHub Copilot"](https://docs.github.com/en/copilot/how-tos/configure-content-exclusion/exclude-content-from-copilot)**.
  Content exclusion is configured by repository, organisation or enterprise administrators in
  settings, not by a file in the tree, and *"Agent mode in Copilot Chat in IDEs does not support
  content exclusion."*
  **Confirms:** a path the agent must not see cannot rely on one assistant's exclusion.
  *Verified 2026-09-24 against the page.*

- **[GitHub Docs, "Adding agent skills for GitHub Copilot"](https://docs.github.com/en/copilot/how-tos/copilot-on-github/customize-copilot/customize-cloud-agent/add-skills)**.
  Project skills go in *"a `.github/skills`, `.claude/skills`, or `.agents/skills` directory"*, with
  `name` and `description` required, and *"Copilot will decide when to use your skills based on
  your prompt and the skill's description."* *"Agent skills work with Copilot cloud agent, Copilot
  code review, the GitHub Copilot CLI, the GitHub Copilot app, and agent mode in Visual Studio
  Code."*
  **Corrected the method:** the *On-demand procedures* row of *What each surface can actually do*
  said "not verified here". **Confirms:** `layout.md` point 3 — one skill folder can serve Claude
  Code and Copilot. **Limits it:** Visual Studio and JetBrains are not in that list.
  *Verified 2026-09-24 against the page.*

- **[GitHub Docs, "Your first prompt file"](https://docs.github.com/en/copilot/tutorials/customization-library/prompt-files/your-first-prompt-file)**.
  *"Prompt files are only available in VS Code, Visual Studio, and JetBrains IDEs,"* are *"in
  public preview and subject to change,"* live in `.github/prompts` as `NAME.prompt.md`, and are
  run as a slash command.
  **Confirms:** a procedure a person runs by hand on the IDE surfaces that have no skills.
  **Limits it:** a prompt file never fires by itself, so nothing that must hold can rest on one.
  *Verified 2026-09-24 against the page.*

- **[GitHub Docs, "About custom agents"](https://docs.github.com/en/copilot/concepts/agents/cloud-agent/about-custom-agents)**
  and **[the configuration reference](https://docs.github.com/en/copilot/reference/custom-agents-configuration)**.
  Repository profiles are `.github/agents/CUSTOM-AGENT-NAME.md`, available on GitHub.com, in
  *"Visual Studio Code, JetBrains IDEs, Eclipse, and Xcode"*, the app and the CLI.
  `disable-model-invocation` *"Disables Copilot cloud agent from automatically using this custom
  agent based on task context. When true, the agent must be manually selected."*
  **Confirms:** the third on-demand shape in the capability table. **We differ on purpose:** the
  method does not use a persona as the home of a procedure (`layout.md`, *What to decline*); a
  custom agent is a surface to point at the source, not a source. *Verified 2026-09-24 against both pages.*

- **[Cursor Docs, "Rules"](https://cursor.com/docs/context/rules)**. Project rules are `.mdc`
  files in `.cursor/rules` with `description`, `globs` and `alwaysApply` frontmatter, applied
  always, intelligently (by description), to specific files (by glob) or manually (by
  @-mention); a plain `.md` there is ignored. *"Nested `AGENTS.md` support in subdirectories is
  now available."*
  **Confirms:** Cursor's row in *What each surface can actually do*. **Not confirmed:** the page
  does not say whether `.cursorrules` is deprecated; the method still treats it as a file to
  read on adoption. *Verified 2026-09-24 against the page.*

- **[Cursor Docs, "Hooks"](https://cursor.com/docs/agent/hooks)** and
  **[Cursor Docs, "Ignore files"](https://cursor.com/docs/context/ignore-files)**. Project hooks
  live in `.cursor/hooks.json`; exit code 2 blocks the action. `.cursorignore` hides files from
  the agent, but *"The terminal and MCP server tools used by Agent cannot block access to code
  governed by `.cursorignore`"*.
  **Corrected the method:** it said Cursor had no deterministic enforcement. **Limits it:** an
  ignore file is not a boundary; what must not be read needs a permission or a hook.
  *Verified 2026-09-24 against both pages.*

- **[Anthropic, 2025, "Effective context engineering for AI agents"](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents)**.
  Aim for *"the smallest possible set of high-signal tokens"*. Prefer *"diverse, canonical
  examples"* to *"a laundry list of edge cases"*. Load files just in time, through
  progressive disclosure.
  **Confirms:** principle 5 (a root file that is a map), principle 13 (point at an example),
  and the note index read on demand.
  **Limits it:** *"minimal does not necessarily mean short"*. A root file cut below what the
  agent needs up front is a different failure.
  *Verified 2026-09-23 against the post.*

- **[Liu et al., 2024, "Lost in the Middle: How Language Models Use Long Contexts"](https://doi.org/10.1162/tacl_a_00638)**
  (TACL 12). Performance *"significantly degrades when models must access relevant information
  in the middle of long contexts."*
  **Confirms:** the reason behind principle 5 and behind `INDEX.md` staying readable in one pass.
  **Limits it:** the study measured retrieval and question answering, not instruction
  following, and used models older than the ones this bundle is used with. It is indirect
  evidence. *Verified 2026-09-23 against the abstract.*

## Privacy and re-identification

- **[Sweeney, 2002, "k-anonymity: a model for protecting privacy"](https://doi.org/10.1142/S0218488502001648)**
  (International Journal of Uncertainty, Fuzziness and Knowledge-Based Systems 10(5)). Data
  released *"with all explicit identifiers, such as name, address and telephone number, removed"*
  can still be re-identified *"by linking or matching the data to other data or by looking at
  unique characteristics found in the released data"*: *"Combinations of few characteristics
  often combine in populations to uniquely or nearly uniquely identify some individuals."* The
  set of attributes that does it is a *quasi-identifier*.
  **Confirms:** principle 20's premise — removing names is not enough; it is the combination of
  ordinary details that identifies, so each is generalised.
  **We differ on purpose:** k-anonymity is a measurable guarantee over a released table, where
  every record hides among at least k−1 others. The bundle is prose, and a private carrier is one
  of very few, so no k can be computed: the rule generalises by category (figures, constants,
  quotes, identifiers, nouns, personal context) and enforces it with patterns and a local term
  list, which is weaker and says so.
  *Verified 2026-09-24 against the article's author copy.*

- **[Narayanan & Shmatikov, 2008, "Robust De-anonymization of Large Sparse Datasets"](https://doi.org/10.1109/SP.2008.33)**
  (IEEE Symposium on Security and Privacy). *"an adversary who knows only a little bit about an
  individual subscriber can easily identify this subscriber's record in the dataset"*; *"very
  little background knowledge is needed (as few as 5-10 attributes in our case study)"*, and the
  attack is *"robust to the imprecision of the adversary's background knowledge"*. Unlike earlier
  work, it does not assume *"that the adversary's knowledge is limited to a fixed set of
  'quasi-identifier' attributes."*
  **Confirms:** why a public carrier's readers are the adversary with background knowledge, and
  why a fixed list of forbidden words cannot be the whole defence: any rare detail — an exact
  threshold, a quote a code search finds — is an attribute. It is why the rule removes exact
  constants and verbatim quotes, not only names.
  **Limits it:** the study is about sparse records of individuals, not about describing
  codebases; the analogy is the mechanism, not a measured rate for this bundle.
  *Verified 2026-09-24 against the authors' copy.*

## The shape of a knowledge note

- **[Gamma, Helm, Johnson & Vlissides, 1994, *Design Patterns*](https://www.informit.com/store/design-patterns-elements-of-reusable-object-oriented-9780201633610)**.
  *"Each pattern describes the circumstances in which it is applicable … and the consequences
  and trade-offs of using the pattern."* The template's *Applicability* and *Consequences*
  sections are the precedent for a note's *When it does NOT apply* and *What it costs*.
  **We go further:** in a pattern catalogue, applicability describes where to use the pattern.
  A note instead has to state where the heuristic is **wrong**, because that is the case an
  agent's training does not cover.
  *Verified 2026-09-23 against the publisher's description; the book's §1.3 template was not opened.*

## What to read first

| If you are about to change… | Read | And watch out for |
|---|---|---|
| the root file or the layout | Claude Code memory docs; AGENTS.md; context engineering | imports are not free, and minimal is not the same as short |
| another assistant's surface | the Copilot and Cursor pages above | support differs per surface, and the products move monthly — re-verify before relying on a row |
| the privacy rule or `bundle.py privacy` | Sweeney; Narayanan & Shmatikov | a list of forbidden words is not the defence — a combination of harmless details identifies |
| a rule about keeping documents current | Lethbridge; Aghajani | outdated high-level documents keep their value, so do not force updates everywhere |
| the decisions log or the `declined` field | Parnas & Clements; Nygard | an ADR alone does not record rejected options |
| the note format | *Design Patterns*; Nygard | applicability is not the same thing as a boundary |
