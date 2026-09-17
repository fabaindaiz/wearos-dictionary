---
name: benchmark
description: Medir rendimiento, latencia de búsqueda, arranque, tamaño de pack o consumo de batería. Usar cuando se pida "medí", "cuánto tarda", "está lento", "optimizá", "cuánto gasta de batería", "cuánto pesa", o antes de cerrar cualquier decisión sobre tamaño o velocidad.
allowed-tools: Bash, Read, Write, Edit
---

# Medir antes de optimizar

En este repo **ningún presupuesto de rendimiento está medido**. Los cuatro de
`docs/formato-pack.md` son objetivos escritos a priori. Hasta que haya números, cualquier
afirmación sobre velocidad o tamaño es una intuición (D-042).

## Primero: ¿emulador o reloj?

La respuesta no es "el que haya a mano" (D-043):

| Qué se mide | Dónde | Por qué |
|---|---|---|
| Normalización, FTS5, planes de consulta, codec | **Emulador** | Depende de la imagen del sistema. Un emulador de API 33 trae el ICU y el SQLite de API 33 |
| Latencia, arranque, batería, jank | **Reloj físico** | La guía oficial pide *"physical Wear OS devices"*. Los números de CPU del emulador no representan nada |

Usar el emulador para medir rendimiento es peor que no medir: da un número que parece real.

## Qué herramienta para qué

- **Latencia y arranque** → Macrobenchmark.
- **Batería** → el power metric de Macrobenchmark, Perfetto, o el Power Profiler.
  **No Battery Historian**: su propia documentación dice que ya no se mantiene (D-044).
- **Plan de consulta** → `EXPLAIN QUERY PLAN`, que `verify_pack.py` ya corre. Es lo único que se
  puede medir hoy sin dispositivo.
- **Tamaño del pack** → `verify_pack.py` sección `[tamanos]`, que desglosa por tabla e índice.

## El orden de las fases

Está en `docs/roadmap.md` §Optimización, y el orden importa: O-1 (hacerlo medible) es
prerrequisito de todas. No saltes a O-2 o O-3 sin baseline, porque no vas a poder decir si
mejoró.

## Dónde está el gasto real, y no es donde uno busca

Para una app que se usa en ráfagas cortas en una muñeca, la guía oficial ordena así:

1. **Red** — *very high impact*. Un pack de decenas de MB es el mayor consumo que esta app va a
   provocar en su vida.
2. **Pantalla encendida** — *high*.
3. **CPU alta sostenida** — *high*, pero nuestro trabajo dura milisegundos.

Optimizar la búsqueda antes que la descarga es optimizar el tercer lugar.

## Al terminar

**Escribí el número donde vive**, no en el chat: el tamaño del pack va a
`docs/formato-pack.md`, la latencia también, y la medición completa al changelog con la fecha y
el dispositivo.

Y si el número **mata una creencia**, esa es la entrada más valiosa que vas a escribir: va a
`docs/decisions.md` como fila descartada, con el número que la descartó, para que no se proponga
de nuevo.
