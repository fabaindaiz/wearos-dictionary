---
name: benchmark
description: Medir rendimiento, latencia de búsqueda, arranque, tamaño de pack o consumo de batería. Usar cuando se pida "medí", "cuánto tarda", "está lento", "optimizá", "cuánto gasta de batería", "cuánto pesa", o antes de cerrar cualquier decisión sobre tamaño o velocidad.
allowed-tools: Bash, Read, Write, Edit
---

# Measure before optimising

> The `description` above stays in Spanish: those are the phrases **the user says**.

In this repo **no performance budget is measured**. The four in `docs/formato-pack.md` are targets
written a priori. Until there are numbers, any claim about speed or size is a hunch (D-042).

## First: emulator or watch?

The answer is not "whichever is at hand" (D-043):

| What is measured | Where | Why |
|---|---|---|
| Normalization, FTS5, query plans, codec | **Emulator** | It depends on the system image. An API 33 emulator ships the ICU and SQLite of API 33 |
| Latency, startup, battery, jank | **Physical watch** | The official guide asks for *"physical Wear OS devices"*. The emulator's CPU numbers represent nothing |

Using the emulator to measure performance is worse than not measuring: it gives a number that
looks real.

## Which tool for what

- **Latency and startup** → Macrobenchmark.
- **Battery** → Macrobenchmark's power metric, Perfetto, or the Power Profiler. **Not Battery
  Historian**: its own documentation says it is no longer maintained (D-044).
- **Query plan** → `EXPLAIN QUERY PLAN`, which `verify_pack.py` already runs. It is the only thing
  measurable today without a device.
- **Pack size** → `verify_pack.py`'s `[tamanos]` section, which breaks it down by table and index.

## The order of the phases

It is in `docs/roadmap.md` §Optimización, and the order matters: O-1 (making it measurable) is a
prerequisite for all of them. Do not jump to O-2 or O-3 without a baseline, because you will not
be able to say whether it improved.

## Where the real cost is, and it is not where you look

For an app used in short bursts on a wrist, the official guidance orders it like this:

1. **Network** — *very high impact*. A pack of tens of MB is the biggest consumption this app will
   ever cause.
2. **Screen on** — *high*.
3. **Sustained high CPU** — *high*, but our work lasts milliseconds.

Optimising the search before the download is optimising third place.

## When you are done

**Write the number where it lives**, not in the chat: the pack's size goes to
`docs/formato-pack.md`, the latency too, and the full measurement to the changelog with the date
and the device.

And if the number **kills a belief**, that is the most valuable entry you will write: it goes to
`docs/decisions.md` as a discarded row, with the number that discarded it, so it does not get
proposed again.
