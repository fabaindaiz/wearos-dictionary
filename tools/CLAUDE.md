# tools

Dos áreas de Python, **sin dependencias de terceros** (solo stdlib). Esa propiedad es
deliberada y se conserva: `dependencies = []` en `pyproject.toml`.

- `packbuilder/` — construye los packs `.db` a partir de fuentes lexicográficas.
- `unicode/` — genera el repertorio Unicode fijado que comparten el builder y la app.

## El entorno

Hatch, configurado en `pyproject.toml` en la raíz. No hace falta para correr el gate —
`./gradlew check` usa `python3` a secas, para que un clone limpio funcione sin instalar nada.
Hatch es la capa de desarrollo.

```sh
hatch run test             # los 101 tests
hatch run audit            # la auditoría estructural
hatch run all              # ambos
hatch run matrix:test      # LOS TESTS BAJO TODAS LAS VERSIONES DE PYTHON
hatch run lint:check       # ruff (lint + formato)
hatch run lint:fix         # arregla lo que se pueda solo
hatch run build-toy        # regenera el pack de juguete
hatch run verify <pack.db> # invariantes de un pack real
hatch run gen-repertoire   # regenera el repertorio (solo corre bajo Python 3.9)
hatch run push <pack.db>   # instala un pack en el reloj por adb
hatch run packs            # que packs hay instalados
```

## `devpack.py` no es el instalador

`tools/devpack.py` es la capa de desarrollo del sideload, igual que Hatch es la capa de
desarrollo del gate: mete un `.db` en `filesDir/packs/` por adb y nada más. No descarga, no
conoce catálogos y no sabe de D-029. El instalador de verdad está bloqueado en una decisión de
producto —dónde se hostea el catálogo— y cuando exista escribirá en el mismo directorio.

Lo que resuelve, y por lo que no es un `adb push`: la copia es **atómica** (`.part` + `mv`, la
misma convención que usa `PackStore.instalarAtomico`) y se comprueba con **sha256 de los dos
lados** antes de renombrar. Un `.db` copiado a medias se abre sin error y devuelve menos palabras
de las que tiene. Ver D-082.

Vive en `tools/` y no en `packbuilder/` porque no construye ni valida packs: habla con el
dispositivo. Su lógica pura —armar el plan de comandos, elegir dispositivo, comparar hashes—
entra al gate por `:tools:pythonTest`; ejecutar adb necesita un reloj y **no entra**.

## Por qué la matriz de versiones es lo que más importa

El builder escribe claves que el reloj vuelve a calcular. Que se comporte igual en todo Python
no es comodidad: es el invariante central.

**Medido el 2026-09-17:** los tests pasan idénticos bajo Python 3.9 (Unicode 13.0) y 3.14
(Unicode 16.0), incluido `ab\u0870cd` → `ab cd`, que es el caso exacto que divergía antes de
fijar el repertorio. El builder es independiente de la versión de Python.

La única excepción es `gen_repertoire.py`, que **exige Python 3.9.x** porque necesita
exactamente Unicode 13.0.0. El guardián está verificado: bajo 3.14 se niega con exit 1.

## El archivo a copiar

Un módulo: `payload.py` — docstring que explica la decisión y su alternativa descartada,
funciones cortas, cero estado global.

Un test: `tests/test_build.py` — construye packs de verdad en un directorio temporal y los
inspecciona con SQL. No mockea SQLite.

## El builder es de dos pasadas, y no por gusto

Las fuentes reales son de gigabytes (el JSONL del Wikcionario son 1,1 GB) y no entran en
memoria. Por eso `PackBuilder` escribe a una tabla de staging dentro del propio archivo, arma el
diccionario de compresión con una muestra por reservorio, y recién en la segunda pasada
comprime y llena `entry` y `fts_def`.

Los índices se crean **al final**, sobre las tablas ya pobladas. Mantenerlos durante la ingesta
es mucho más lento.

Si algo falla a mitad, el pack se borra. Un pack a medio construir es peor que ninguno: se abre
sin error y devuelve menos resultados de los que debería.

## Agregar una fuente

Va en `sources/`, y entrega `Record` — el builder no sabe de formatos. Lo que la fuente debe
resolver:

- **Poda.** Es donde se decide el tamaño del pack. Conservar `word`, `pos`, glosas, formas y
  traducciones; descartar etimologías, pronunciaciones, categorías y citas.
- **Streaming.** Nunca cargar el archivo entero.
- **De qué edición y sección viene.** El Wikcionario y el Wiktionary inglés son datasets
  distintos: el primero da glosas en español, el segundo glosas en inglés sobre palabras
  españolas.

## Regenerar el repertorio Unicode es un acto deliberado

`gen_repertoire.py` aborta si el Python que lo corre no trae exactamente Unicode 13.0.0. Eso no
es un bug: fija el repertorio al **piso** común entre el builder y el Android más viejo que
soportamos.

Subir de versión exige comprobar que el piso nuevo lo soporten todas las plataformas, revisar el
diff de `repertoire.txt` y subir `NORM_VERSION`. Leé el encabezado del generador antes.

## La obligación de espejo

`normalize.py` es el espejo escrito a mano de `TextNormalizer.kt`. Cualquier archivo con espejo
lo declara en su encabezado:

```
ESTE ARCHIVO TIENE UN ESPEJO: <ruta>
```

`tools/audit_dictionary.py` comprueba que la ruta declarada exista. Que el **contenido**
coincida lo comprueban los vectores compartidos, no la auditoría.
