---
name: pack-workflow
description: Construir, validar y medir un pack de diccionario real. Usar cuando se pida "construí el pack", "generá el diccionario de español", "agregá una fuente", "cuánto pesa el pack" o se trabaje con datos de Wiktionary o del Wikcionario.
allowed-tools: Bash, Read, Write, Edit
---

# Construir un pack

## Antes de empezar: de qué fuente

El Wikcionario y el Wiktionary inglés son **datasets distintos**, y es el error fácil:

| Querés | Fuente | Tamaño |
|---|---|---|
| Definiciones en español de palabras españolas | eswiktionary, sección Español | 1.036.458 senses |
| Definiciones en inglés de palabras inglesas | enwiktionary, sección English | 1.787.236 senses |
| Palabras españolas con glosa en inglés (bilingüe) | enwiktionary, sección Spanish | 875.726 senses |

Definiciones en español de palabras inglesas **no es una fuente que exista** en calidad usable:
el Wikcionario cubre inglés con 35.021 senses.

Usá las páginas procesadas por idioma de kaikki.org. El formato de datos crudos está deprecado.

## El flujo

```bash
# 1. Una fuente nueva va en tools/packbuilder/sources/ y entrega Record.
#    Streaming siempre: el JSONL del Wikcionario son 1,1 GB y no entra en memoria.

# 2. Construir
python3 tools/packbuilder/build.py   # o el script de la fuente

# 3. Validar SIEMPRE. Un pack a medio construir se abre sin error.
python3 tools/packbuilder/verify_pack.py <pack.db>

# 4. Medir y registrar
ls -lh <pack.db>
```

## La poda es donde se decide el tamaño

No es un detalle de implementación: es el trabajo. Conservar `word`, `pos`, glosas, formas y
traducciones. Descartar etimologías, pronunciaciones, categorías, plantillas y citas.

**Registrá el tamaño en el changelog con la poda que lo produjo.** El presupuesto de 50 MB
(D-028) es blando y **no tiene ninguna medición detrás** — la primera vez que se construya un
pack real, ese número deja de ser una suposición y hay que escribirlo donde vive: en
`docs/formato-pack.md`.

## Qué mirar en la salida de `verify_pack.py`

- **`[normalizacion]`** — que `entry.norm == norm(headword)` en todas las filas. Si falla, el
  pack se construyó con otra versión de `normalize.py`.
- **`[planes de consulta]`** — que el prefijo use `COVERING INDEX`. Es la afirmación central del
  diseño y lo único que la sostiene.
- **`[tamanos]`** — dónde se va el pack. Con definiciones, `fts_def_data` va a ser grande; ese
  número es el que decide si vale la pena mirar `detail=none` (decisión abierta).

## Licencia, y no es opcional

El contenido es CC BY-SA. Cada pack declara `license` y `attribution` en `meta`, y **la app
tiene que mostrarlos**. No es burocracia: es la condición de uso de los datos (D-031).
