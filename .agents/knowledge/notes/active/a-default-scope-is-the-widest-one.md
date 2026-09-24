---
bundle: agent-guides
lineage: g-8b5800/main
version: 3
slug: a-default-scope-is-the-widest-one
topic: failure-behaviour
claim: When a dangerous operation infers which targets it acts on, the inferred set is the widest one available, and a guard computed by subtracting the operated set from that same source is empty exactly when the fallback fired.
confidence: measured
reach: architecture, review, debugging
---

# A default scope is the widest one

## Why it works

A rule limits a dangerous operation to an explicit set: write only the repositories this session has open, delete only the rows this job was given, deploy only the services named on the command line. Then someone adds a convenience — with no argument, work it out — and the inference has to draw on something. The only thing available is the full list: the machine's manifest, the whole registry, every service in the config. So the rule protects exactly the caller who passed the argument, and stands aside for exactly the caller who did not, which is the one who was not paying attention.

The second half is the one that makes it hard to see. The guard that reports what was **left out** is naturally written as *everything known, minus what we operated on*. When the fallback supplied the operated set **from that same known list**, the difference is empty, and the report says "nothing was left out". That sentence is true. It is also true when the operation touched every target there is, and the two cases produce identical output. **A vacuously satisfied guard reads exactly like a satisfied one** — and a line that never prints is a line nobody has seen fail, which is `a-check-must-be-seen-to-fail` arriving from the other direction.

It is worth separating from its neighbours, because the remedy differs. In `absent-constraint-widens` the narrowing clause is **lost**, by a refactor, and the query returns a superset. Here nothing is lost: the clause is **supplied, at its widest**, by a helper doing what it was asked. And `fail-closed-defaults` is about the default of a *value*; this is about the default of an *extent*. The fix is that note's shape applied to scope: **no argument is a refusal, not a default**, and the guard is computed against the declared scope rather than against whatever the operation drew from.

## When it does NOT apply

- **Read-only operations where the widest scope costs only time.** Listing, checking and reporting over everything is a slow answer, not a wrong one — and a checker that silently narrows is the worse defect there.
- **When the full set genuinely is the intended default, and it is small, named and shown before acting.** A confirmation that lists its targets *is* an explicit scope; the caller read it and said yes.
- **When the inference draws on something narrower than the risk** — the current directory, the open project, the branch — and the boundary of that narrower thing is itself checked. Then the inference is a scope, not a fallback.

## What it costs

One more argument at every call site, and a caller that has to say what it is working on. In exchange, the report about what was not reached means something: it is computed against what the caller declared, so it can be non-empty, which is the only way anyone will ever see it work.

## Where it came from

One tool — a standard-library script that carries a shared set of documents across several repositories — and two independent mechanisms inside it, both measured on **2026-09-22**.

**The scope fallback.** The tool's written rule is that a session writes only the repositories it has open, and the document that ships with it says that the tool *refuses* anything else. With the scope declared, it behaves: of three carriers it names **2 of 3** as outside and untouched. With no argument it silently takes **all 3** from the machine's manifest and names **none** — because the "outside" report is the manifest minus the operated set, and the operated set had just been read from the manifest. The subcommand that writes into repositories shares that path. The prose was not describing the tool; it was describing what everyone assumed the tool did.

**The ancestor fallback, the same shape one layer down.** The tool finds the common ancestor of several copies by a content fingerprint that covers the documents and, deliberately, not the queue files that each copy writes between releases. Every commit made since the last release therefore carries the same fingerprint, so the ancestor resolves to the newest commit of whichever copy happens to be listed first — including that copy's own queue additions. Measured in a release across three copies: the first copy's **7 additions were reported as "unchanged"**, they appeared as *deletions* in the other two, and the check for lines lost in the merge went blind on exactly those 7, because a line that is already in the ancestor is never "added over the ancestor" by anyone.

## Literature

- **[Saltzer & Schroeder, 1975, "The Protection of Information in Computer Systems"](https://doi.org/10.1109/PROC.1975.9939)**, fail-safe defaults ([accessible copy](https://www.cs.virginia.edu/~evans/cs551/saltzer/)) — "base access decisions on permission rather than exclusion". *Cited and verified in `absent-constraint-widens`, 2026-09-22.* **What we take:** the default of a scope is a permission decision, and building from explicit inclusion means that losing the scope yields nothing rather than everything. **Where we go further:** their subject is whether a principal may act at all; this is the *extent* of an action the caller is fully authorised to take — and the part they do not address is the guard, which here is derived from the same source as the action and therefore cannot contradict it.

## Evidence

**Measured in one tool, two mechanisms:** a writing command that acts on 3 of 3 targets and names 0 as outside when its scope is omitted, against 2 of 3 named when it is declared; and an ancestor rule under which 7 additions of one copy became invisible to both the report and the loss check. Both were found by using the tool for the job it was written for, not by reading it.

**Not measured, and it is the note's real gap:** whether this shape appears outside that one tool. Two mechanisms in one codebase is a claim about a habit of thought, not yet about software in general. The experiment, cheap in any repository: for every command that writes, deletes or deploys over a set of targets, run it with no scope argument in whatever dry-run mode it has, record how many targets it would touch, and check whether its "not reached" report is empty. Each empty report over a full target list is an occurrence.
