# Formato de pack (`schema_version = 3`)

Un pack es un archivo SQLite de **solo lectura** con un diccionario. La app abre uno por idioma
activo y nunca le escribe.

Que sea inmutable y read-only es lo que permite optimizar el esquema únicamente para lectura: no
hay migraciones, no hay claves foráneas que mantener, no hay journal. Un pack con
`schema_version` distinta se rechaza al abrirlo y se descarga de nuevo.

Definición ejecutable: [`tools/packbuilder/schema.sql`](../tools/packbuilder/schema.sql) y
[`indexes.sql`](../tools/packbuilder/indexes.sql).

> **El caso primario es monolingüe** (D-034): el primer pack son definiciones en español. El
> esquema soporta ambos tipos y cada pack declara el suyo en `meta.kind`, pero el bilingüe es el
> caso secundario.
>
> Consecuencia pendiente en el código: en un pack monolingüe, `trans` guarda "las palabras que
> aparecen en la glosa", que es exactamente lo que `fts_def` ya indexa mejor. Esa tabla debería
> volverse opcional. Ver [roadmap](roadmap.md#reorientar-el-esquema-a-monolingüe).

## Tabla `meta`

Todo lo que la app necesita saber antes de consultar. Se lee entera, una vez, al abrir.

| Clave | Para qué |
|---|---|
| `schema_version` | Compatibilidad del esquema. Distinta → rechazar el pack |
| `norm_version` | Versión de las reglas de normalización. Distinta → **rechazar**, ver abajo |
| `pack_id`, `name` | Identidad del pack |
| `kind` | `bilingual` o `monolingual` |
| `lang_src`, `lang_dst` | Idiomas; `lang_dst` es obligatorio si es bilingüe |
| `fuzzy_profile` | Perfil de plegado fonético: `es`, `en`, `de`, `generic` |
| `payload_codec` | `deflate-v2`. **Distinta → rechazar el pack** (`PackFile.open` compara con `!=`). Ver D-119 |
| `payload_dict` | Diccionario de compresión compartido, en hex |
| `payload_dict_sha256` | Integridad del anterior. **No es opcional** |
| `uid_recipe` | Con qué receta se calculó `entry.uid`. Otra receta ⟹ los packs auxiliares apuntan mal |
| `entry_count`, `data_version`, `built_at` | Metadatos del build |
| `license`, `attribution`, `source_url` | Obligaciones legales de la fuente |
| `trans_dropped` | Cuántas filas recortó el tope por clave de traducción |
| `proper_nouns` | Política de contenido: `lexical-only` (los packs reales), `excluded` o `included`. **Obligatoria**: sin ella nadie sabe si a un pack le faltan los nombres propios porque se decidió o porque la fuente venía rota. D-116 |

**`norm_version` distinta no es un detalle cosmético.** El pack está indexado con unas reglas de
normalización concretas; si la app calcula otras, las consultas no matchean y el pack devuelve
menos resultados de los que tiene, sin ningún error. Hay que rechazarlo, no intentar usarlo.

## Tablas

### `entry`

```sql
CREATE TABLE entry (
    id       INTEGER PRIMARY KEY,   -- alias de rowid: lo comparte fts_def
    uid      INTEGER NOT NULL,      -- identidad estable entre rebuilds; join entre packs
    headword TEXT NOT NULL,         -- forma de display, con acentos: "Ärztin"
    norm     TEXT NOT NULL,         -- clave de prefijo: "arztin"
    fuzzy    TEXT NOT NULL,         -- clave tolerante a errores, plegada por idioma
    pos      TEXT,                  -- desambigua lemas repetidos en la lista
    rank     INTEGER NOT NULL,      -- frecuencia; menor es más común
    payload  BLOB NOT NULL          -- cuerpo comprimido
);
```

**`id` y `uid` son dos identidades distintas y no son intercambiables** (D-055):

| | `entry.id` | `entry.uid` |
|---|---|---|
| Qué es | Identidad **física**: el rowid local | Identidad **lógica** de la palabra |
| Quién lo referencia | `fts_def.rowid`, `form.entry_id`, `trans.entry_id` | Los packs auxiliares |
| Sobrevive a reconstruir el pack | **No**: una palabra nueva en el medio corre todos los siguientes | **Sí** |
| Por qué es así | Secuencial es lo que lo hace barato: FTS5 guarda *deltas* de rowid | Es hash de `(lang_src, NFC(headword), pos, sense_key)` |

Medido sobre 200.000 entradas sintéticas: usar el hash *como* `entry.id` cuesta **+35,2 %** de
tamaño —`fts_def_data` pasa de 10,39 a 28,35 MB—, mientras que la columna aparte cuesta **+2,3 %**.

`uid` **no tiene índice en este pack** (D-056): el join ocurre al **abrir** una entrada, cuando la
fila ya se leyó entera para traer el payload, no en la lista de resultados. El índice sobre `uid`
vive en el pack auxiliar, que sí busca por él.

`uid` se calcula sobre el headword **crudo**, no sobre `norm`: así no depende de `NORM_VERSION` y
subir las reglas de normalización no invalida los packs auxiliares. Lo calcula **solo el builder**
(D-057); la app lo lee de la fila y nunca lo recalcula, que es lo que evita que sea un segundo
contrato cruzado como `norm()`/`fuzzy()`.

`norm` se compara con collation **BINARY sobre texto ya normalizado en build-time**. Eso es lo
que evita necesitar ICU en el reloj y hace que el comportamiento sea idéntico en todo
dispositivo.

### `form` y `trans`

```sql
CREATE TABLE form (
    norm TEXT NOT NULL, entry_id INTEGER NOT NULL,
    PRIMARY KEY (norm, entry_id)
) WITHOUT ROWID;
```

`form` son las formas flexionadas (plurales, conjugaciones). `trans` es la palabra del idioma
destino en un pack bilingüe.

**`WITHOUT ROWID` con PK compuesta hace que la tabla *sea* el índice**: sin rowid y sin un
B-tree secundario que duplique los mismos datos.

**`trans` indexa la frase completa y cada palabra suelta.** Las traducciones son frases ("to
run"), así que sin tokenizar, buscar "run" no encontraría nada — que es lo que un usuario
escribe en un reloj. El efecto colateral es que las palabras funcionales ("to", "of") apuntarían
a decenas de miles de entradas, así que el builder topea en `TRANS_MAX_PER_KEY = 50`
conservando las de mejor `rank`. Se topea en vez de descartar la clave: buscar "to" sigue
devolviendo algo útil en lugar de nada.

### `fts_def`

```sql
CREATE VIRTUAL TABLE fts_def USING fts5(
    body, content = '', tokenize = "unicode61 remove_diacritics 2"
);
```

Contentless: guarda solo el índice invertido, no una segunda copia del texto. Devuelve únicamente
rowids, que es exactamente lo que hace falta porque `fts_def.rowid == entry.id`.

> **FTS5 no está garantizado en el SQLite del sistema Android.** El pack requiere que la app use
> SQLite empacado (`androidx.sqlite:sqlite-bundled`), cuyo build incluye `ENABLE_FTS5`.

## Índices

```sql
CREATE INDEX idx_entry_norm  ON entry (norm, rank, headword, pos);
CREATE INDEX idx_entry_fuzzy ON entry (fuzzy, norm);
```

`idx_entry_norm` es **de cobertura**: tiene las cuatro columnas que la lista de resultados
necesita, así que SQLite responde la búsqueda por prefijo sin tocar la tabla y sin leer un solo
payload. El orden `(norm, rank)` además satisface el `ORDER BY` sin paso de sort — y es
**ascendente** porque en `rank` menor es más común. Estuvo en `DESC` hasta `schema_version 3`:
el síntoma solo se ve con un pack real, donde "escrit" devolvía *escrito / Participio de
escribir* antes que el sustantivo. Si el índice y el `ORDER BY` se separan, SQLite agrega
`USE TEMP B-TREE` y la consulta deja de ser de cobertura. `id` no
se incluye porque, al ser alias de rowid, ya está en todo índice.

`idx_entry_fuzzy` es deliberadamente angosto: incluye `norm` para poder reordenar los candidatos
por distancia de edición sin leer la tabla, y después se leen de la tabla solo los diez que
sobrevivieron. Agregar `headword`/`pos` lo haría de cobertura pero duplicaría varios MB por un
camino que solo se recorre cuando el prefijo no dio resultados.

`verify_pack.py` comprueba con `EXPLAIN QUERY PLAN` que el prefijo use `COVERING INDEX`. Si ese
plan cambiara a un scan de tabla, la búsqueda incremental dejaría de cumplir el presupuesto de
latencia y nada más lo notaría.

## Las cinco consultas

**1. Prefijo** — sale íntegra del índice de cobertura:

```sql
SELECT id, headword, pos FROM entry
WHERE norm >= :q AND norm < :qUpper
ORDER BY norm, rank LIMIT 30;
```

`:qUpper` es el sucesor lexicográfico que calcula `PrefixRange.upperBound`. Se usa el rango
explícito y no `LIKE 'q%'` porque LIKE solo se optimiza a un range scan si `case_sensitive_like`
está en el valor correcto, cosa que depende de la conexión; ante la duda SQLite hace un full
scan.

**2. Forma flexionada** — `form.norm = :q`, join a `entry`, LIMIT 10.

**3. Traducción inversa** — mismo patrón de rango sobre `trans.norm`. **Necesita deduplicar por
entrada**: el rango matchea varias claves de la misma entrada ("to", "to run", "to pass") y sin
esto sale repetida.

```sql
SELECT e.id, e.headword, e.pos FROM entry e
WHERE e.id IN (SELECT entry_id FROM trans WHERE norm >= :q AND norm < :qUpper)
ORDER BY e.rank LIMIT 20;
```

**4. Tolerante a errores** — se dispara **solo si 1+2+3 devolvieron menos de 5 resultados**.
Consulta por un **prefijo** de la clave fuzzy (no la clave completa) para traer un vecindario y
no solo las colisiones exactas; después se reordena en Kotlin por Damerau-Levenshtein contra
`norm(q)`, se descarta lo que pase de distancia 2 y se devuelven los diez mejores.

**5. Texto libre** — acción explícita del usuario, **nunca** mientras escribe:

```sql
SELECT rowid FROM fts_def WHERE fts_def MATCH :ftsQuery ORDER BY rank LIMIT 30;
```

Hay que sanitizar `:ftsQuery` envolviendo cada token en comillas dobles, para que texto libre
del usuario nunca se interprete como sintaxis de FTS5.

## Payload

Texto UTF-8 delimitado, comprimido con deflate crudo y el diccionario compartido del pack:

```
P<TAB>verb                     part of speech, opcional, antes de cualquier S
S<TAB>moverse rapidamente      abre una acepción
E<TAB>corrio hasta la esquina  ejemplo de la acepción abierta
T<TAB>to run                   traducción de la acepción abierta
```

Texto delimitado en vez de JSON o CBOR a propósito: se parsea sin ninguna dependencia en los dos
lenguajes, se puede leer con la vista al depurar un pack, y después de comprimir la diferencia
de tamaño con un formato binario es ruido.

**Los tags desconocidos se ignoran**, así un builder más nuevo puede agregar campos sin romper
una app vieja.

**Por qué deflate y no zstd**, aunque zstd comprime más: deflate está en `java.util.zip`
(plataforma Android, sin `.so` extra) y en el `zlib` de la stdlib de Python. zstd obligaría a una
librería nativa en el reloj *además* de la de SQLite, y a una dependencia de pip en el builder.

**Por qué el diccionario compartido**: las entradas son de unos cientos de bytes, demasiado
cortas para que deflate encuentre redundancia por sí solo. El diccionario le da la ventana ya
primada con los fragmentos frecuentes del corpus.

⚠️ deflate **no valida** el diccionario: con uno equivocado descomprime sin error y devuelve
texto corrupto. Ver [contratos-cruzados.md](contratos-cruzados.md#3-deflate-no-valida-su-diccionario-precargado).

## Cómo lo construye el builder

Dos pasadas sobre una tabla de staging dentro del propio archivo, nunca en memoria: las fuentes
reales son de gigabytes y no caben.

1. **Pasada 1** — escribe los cuerpos sin comprimir y toma una muestra por reservorio.
2. Arma el diccionario de compresión con esa muestra.
3. **Pasada 2** — comprime y llena `entry` y `fts_def`.
4. Materializa `trans` aplicando el tope por clave.
5. Borra el staging y **recién ahí crea los índices**, sobre las tablas ya pobladas.
6. `PRAGMA optimize` y `VACUUM`, que deja el archivo compacto y con las páginas en el orden en
   que se va a leer en el reloj.

El resultado es determinista: dos builds del mismo input dan el mismo contenido, salvo
`meta.built_at`.

## Presupuestos

**El primero ya está medido y no se cumple.** Los otros tres siguen siendo objetivos escritos a
priori: son de latencia, y la latencia solo vale medida en un reloj físico (D-043), que todavía
no hay.

| Métrica | Objetivo | Medido |
|---|---|---|
| Pack en disco | ≤ 50 MB *(blando)* | **72,2 MB** — 44 % por encima (2026-09-17) |
| `suggest()` con prefijo de 3 letras | p95 < 20 ms | sin medir: falta reloj |
| Primer resultado visible desde la última tecla | < 150 ms | sin medir: falta reloj |
| Cold start hasta pantalla de búsqueda usable | < 700 ms | sin medir: falta reloj |

### Los dos packs, pesados — y por qué pesa cada uno

| | Español | Inglés | Inglés sin nombres propios |
|---|---|---|---|
| Fuente | eswiktionary Español, 1,42 GB | enwiktionary English, **3,24 GB** | ídem |
| Entradas | 146.194 | **956.150** | 792.680 |
| En disco | 72.212.480 B (68,9 MiB) | **309.424.128 B (295,1 MiB)** | 266.711.040 B (254,4 MiB) |
| Comprimido | 34,3 MiB (50 %) | **184,7 MiB (37 %)** | — |
| Build | 53,9 s, 214 MB RSS | 180,6 s, 290 MB RSS | — |

**La forma de los dos packs no se parece, y eso mata la intuición fácil.** Un pack no es
"sobre todo morfología": eso es una verdad **del español**.

| Objeto | Español | Inglés |
|---|---|---|
| `form` | **34,2 MB (47,4 %)** — 1.487.695 filas, 93,5 % conjugaciones | 20,0 MB (**7 %**) — 984.473 filas |
| `entry` | 18,2 MB (25,2 %) | **142,3 MB (48 %)** |
| `fts_def` | 10,3 MB (14,1 %) | 80,7 MB (26 %) |
| `idx_entry_norm` + `idx_entry_fuzzy` | 9,5 MB (13,1 %) | 66,0 MB (21 %) |

Un verbo español trae hasta 222 formas; uno inglés, cuatro. **El inglés pesa porque tiene 6,5×
más entradas**, no por flexión. También comprime peor (37 % contra 50 %) porque casi todo su
peso son payloads que ya están comprimidos.

### La fuente alternativa, medida (spike D-120)

| | enwiktionary podado | Open English WordNet 2025 |
|---|---|---|
| Entradas | 794.355 | **135.969** |
| En disco | 255,7 MiB | **38,4 MiB** (6,7× menos) |
| Build | ~180 s | **17,9 s** |
| Sonda de vocabulario común (57) | 57/57 | 56/57 |
| Sonda dura: moderno, slang, técnico (39) | **39/39** | **25/39 (64 %)** |
| Entradas con sinónimos | 0 % | **70,8 %** |
| Licencia | CC BY-SA 4.0 | CC BY 4.0 |

Lo que a OEWN le falta, concreto: *selfie, blockchain, deepfake, ghosting, woke, burnout,
workaround, mitochondria, petrichor*. Es vocabulario académico congelado. **No reemplaza**, y el
razonamiento está en D-120.

### Qué se puede recortar, medido — y por qué no se recortó

| Palanca | Ahorro | Qué se pierde |
|---|---|---|
| Dedup sin pérdida: la forma es prefijo de su lema | **0,2 MB (0,3 %)** | nada — y por eso no sirve |
| ~~Nombres propios~~ — **tirada, D-116** | **39,4 MiB en inglés (13,4 %)**, 4,1 MB en español | los topónimos y apellidos sin vida léxica. *January*, *Paris*, *España* y *Chile* **se conservan** |
| Quitar `fts_def` | 10,3 MB en español (14 %) | buscar por definición |
| Quitar `idx_entry_fuzzy` | 4,1 MB en español (5,7 %) | tolerancia a errores — el punto del dictado (D-027) |
| Podar `form` por divergencia ≥4 | 13,6 MB en español (19 %) | **602.681 formas dejan de resolver** escritas enteras |
| Conjugador algorítmico en vez de tabla | hasta 34 MB en español (47 %) | un segundo contrato entre dos lenguajes (la clase de bug de D-005) |

**El pack ya es la base de datos interna**: 1,42 GB de dump → 68,1 MB. Lo que queda no es basura
de Wiktionary, es capacidad de búsqueda, y **toda palanca cuesta una función** (D-077).

**La fila de los nombres propios es la única que se tiró, y enseñó algo que el resto de la tabla
no dice**: en español no ahorró casi nada —el payload de los 32.305 era 0,63 MB— pero sacó el
ruido del 22,1 % de los `norm`. **Una palanca puede valer la pena por lo que NO pesa.** En
inglés sí ahorró: 295,1 → 255,7 MiB.

Y hay una fila nueva que va en el sentido contrario: los **sinónimos** (D-117) **suman** 0,89 MB
al español. Es contenido que la fuente ya traía y el builder tiraba.

### El pack real de español, pesado

Construido el **2026-09-17** desde el dump del Wikcionario de kaikki.org del **2026-09-15**
(eswiktionary, sección Español, 1.423.631.693 bytes). Reproducible:

```sh
python3 tools/packbuilder/build_pack.py es <kaikki-es.jsonl> es-def-wikc.db
python3 tools/packbuilder/verify_pack.py es-def-wikc.db
```

| | |
|---|---|
| Senses en el dump | 1.036.458 *(en 854.460 registros)* |
| Registros que son página de forma flexionada | 703.506 = **82,33 %** — no son entradas (D-065) |
| **Entradas en el pack** | **146.194** |
| **Pack en disco** | **72.212.480 bytes (68,9 MiB)** |
| Build | 53,9 s, **214 MB** de RSS máximo (la pasada 1 arma el mapa de formas en memoria) |

Dónde se va el pack, y es la respuesta que decide O-3:

| Objeto | Tamaño | Parte |
|---|---|---|
| `form` | 33,4 MB | **46,3 %** — 1.487.695 filas, **93,5 % conjugaciones de verbos** |
| `entry` | 17,8 MB | 24,6 % — payloads comprimidos, 2,8 acepciones por entrada |
| `fts_def_data` | 8,6 MB | 11,8 % |
| `idx_entry_norm` | 5,2 MB | 7,3 % |
| `idx_entry_fuzzy` | 4,0 MB | 5,6 % |
| `fts_def_docsize` | 1,4 MB | 2,0 % |
| `trans` | 4 KB | vacía: el pack es monolingüe (D-034) |

**El pack es la tabla `form`**, y no es grasa: es el precio de que escribir "corriendo"
encuentre "correr". Un verbo español trae hasta 222 formas. Cualquier recorte ahí se paga en
la moneda que este repo no acepta pagar — *falta una palabra*.
