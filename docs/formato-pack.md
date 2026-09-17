# Formato de pack (`schema_version = 1`)

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
| `payload_codec` | `deflate-v1` |
| `payload_dict` | Diccionario de compresión compartido, en hex |
| `payload_dict_sha256` | Integridad del anterior. **No es opcional** |
| `entry_count`, `data_version`, `built_at` | Metadatos del build |
| `license`, `attribution`, `source_url` | Obligaciones legales de la fuente |
| `trans_dropped` | Cuántas filas recortó el tope por clave de traducción |

**`norm_version` distinta no es un detalle cosmético.** El pack está indexado con unas reglas de
normalización concretas; si la app calcula otras, las consultas no matchean y el pack devuelve
menos resultados de los que tiene, sin ningún error. Hay que rechazarlo, no intentar usarlo.

## Tablas

### `entry`

```sql
CREATE TABLE entry (
    id       INTEGER PRIMARY KEY,   -- alias de rowid: lo comparte fts_def
    headword TEXT NOT NULL,         -- forma de display, con acentos: "Ärztin"
    norm     TEXT NOT NULL,         -- clave de prefijo: "arztin"
    fuzzy    TEXT NOT NULL,         -- clave tolerante a errores, plegada por idioma
    pos      TEXT,                  -- desambigua lemas repetidos en la lista
    rank     INTEGER NOT NULL,      -- frecuencia; menor es más común
    payload  BLOB NOT NULL          -- cuerpo comprimido
);
```

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
CREATE INDEX idx_entry_norm  ON entry (norm, rank DESC, headword, pos);
CREATE INDEX idx_entry_fuzzy ON entry (fuzzy, norm);
```

`idx_entry_norm` es **de cobertura**: tiene las cuatro columnas que la lista de resultados
necesita, así que SQLite responde la búsqueda por prefijo sin tocar la tabla y sin leer un solo
payload. El orden `(norm, rank DESC)` además satisface el `ORDER BY` sin paso de sort. `id` no
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
ORDER BY norm, rank DESC LIMIT 30;
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

⚠️ **Los cuatro son objetivos escritos a priori, ninguno está medido.** No existe todavía un
pack real: el de juguete tiene 22 entradas y la sección Español del Wikcionario son 1.036.458
senses. El primer item del roadmap es construir uno y pesarlo, porque es la medición que decide
si el formato aguanta (D-028).

| Métrica | Objetivo |
|---|---|
| Pack en disco | ≤ 50 MB **(blando, y sin medición — ver abajo)** |
| `suggest()` con prefijo de 3 letras | p95 < 20 ms |
| Primer resultado visible desde la última tecla | < 150 ms |
| Cold start hasta pantalla de búsqueda usable | < 700 ms |
