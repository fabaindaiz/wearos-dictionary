---
bundle: agent-guides
lineage: g-8b5800/main
version: 8
slug: sweep-the-rendered-extremes
topic: verification
claim: Check a visual or layout invariant on the rendered output across the whole configuration matrix — every screen, language, user size, aspect ratio and moment — because the extremes break it, and a number derived from the source or measured once goes stale.
confidence: measured
reach: review, implementation, debugging
---

# Sweep the rendered extremes

## Why it works

Whether text fits, whether a control stays still, whether a figure is visible — these are functions of content length, the user's chosen size, the viewport and the instant, and they fail at the corners of that space: the longest string in the wordiest language at the largest size on the narrowest screen. A developer looks at the typical case. A number written in a document ("clears it by a few pixels") was true for the configuration it was measured in, and stays in the document after the layout moves.

Rendering every configuration headlessly and asserting geometry on the result — no label shows a raw key, nothing reaches past an edge, the tabs do not move between pages, a line is drawn whole or not at all, a subject is covered less than a budget — makes the invariant a check instead of an impression, and finds the corner before a user does.

## When it does NOT apply

- **Layout guaranteed by construction** (fixed cells, text that is never user-sized).
- **A matrix too large to enumerate**: sample the extremes on each axis and their combinations rather than every point.
- **Deliberate overflow** — a scrolled list runs off the screen by design; it needs an exemption written in the check, not a silent skip — and the check then reports what it exempted beside what it found (`report-coverage-before-findings`).

## What it costs

A headless renderer in the gate and the time to open every screen in every configuration; an exemption list that must be maintained; covered-fraction measurements that must be re-run whenever what they measure moves.

## Where it came from

A client application drawn on a fixed small canvas, used on a device its developers cannot observe. Sweeping a whole piece at the three user text sizes found a scrolling text column reaching **past both the top and the bottom** of the screen at the largest size; after the fix (a line is drawn whole or not at all) every size fits inside it with a margin. The same sweep showed that a document's claim about how far the column cleared a strip was old: the column overlapped it.

Its interface check opens every screen in each language and fails if a label shows a raw key or passes any edge. Proved by breaking it: an emptied translation cell failed two checks, **one over-long caption failed about ten controls**, and a long hint pushed several controls off the screen. A settings page that quietly grew shifted the tabs and the back button with nothing watching; the check added for it fails with the reserved height and names the page. At the device's real width, **nearly all** text lines fit in two rows at the largest size, which is why the size table was left alone.

The same practice applies to occlusion. Planting decoration across the path of small moving figures left them **covered nearly all the time**; uniform rows gave about 90 %, measured clearings where they stand still about 50 %, against about 30 % with the rows in the figures' own lane removed altogether (what still covers them then is mostly other decoration elsewhere in their band) — at one instant, fully covered became about a third. A pointer drawn inside the figures' own depth band kept a few pixels visible over one figure and none over another.

## Literature

- **Microsoft, ["Pseudolocalization"](https://learn.microsoft.com/en-us/globalization/methodology/pseudolocalization)** (Globalization documentation). Expand strings about 40% — real translations reach "200% or even 400%" — and wrap them in delimiters so truncation is visible, to find layout faults before translating. *Verified 2026-09-22 against the Microsoft Learn page.* **What we take:** test layout with the long case, not the source language. **Where we go further:** assert the geometry on the rendered output, and sweep user sizes, aspect ratios and moments as well as languages.
- **Myers, Badgett & Sandler, [*The Art of Software Testing*](https://doi.org/10.1002/9781119202486), 3rd ed., ch. 4 "Test-Case Design", boundary-value analysis:** "Boundary conditions are those situations directly on, above, and beneath the edges of input equivalence classes and output equivalence classes." *Verified 2026-09-23 against the book.* **What we take:** test on the edges, not in the middle — the rendered extremes are the output-side edges.

## Evidence

**Measured in one repository:** a text column overflowing both edges before the fix and fitting after; about ten controls failed by one long caption; a small layout shift caught; nearly all lines fitting at the real width; occlusion from nearly 100 % through about 90 % and 50 % to 30 %; a pointer visible over one figure and invisible over another. **Not measured:** how many of these a typical-case review would have caught. The experiment: hand a reviewer the default-configuration screenshots of each defect above and count how many they flag.
