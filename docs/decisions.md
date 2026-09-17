# Decisiones

Índice de todo lo que está cerrado. Una decisión se toma **una vez**: si se reabre sin un hecho
nuevo, la respuesta es este documento.

Los números **no se reutilizan ni se renumeran**. `CLAUDE.md`, el changelog y el roadmap los
citan.

La cuarta columna es la que importa. **Una fila con `—` es una decisión que se puede romper en
silencio.** Está permitido, pero tiene que verse.

La prosa y las mediciones viven en el documento que las posee; esto es el índice que las
encuentra.

## Normalización y claves de búsqueda

| # | Decisión | Por qué | Enforced in |
|---|---|---|---|
| D-003 | La clasificación de code points sale de una tabla propia fijada en Unicode 13.0, nunca de `Character.getType`/`unicodedata.category` | Cada plataforma trae su Unicode: Python 3.9 → 13.0, Java 26 → 16, Android una por release. **14.773 code points se clasificaban distinto**; el mismo pack se habría comportado distinto en dos relojes | `ArchitectureTest` (sha256 de ambas copias) + `repertoire.py` |
| D-004 | NFD y `lowercase()` **sí** se delegan en la plataforma | **0 diferencias** medidas sobre los 133.730 code points del repertorio, entre Java 26 y Python 3.9. La política de estabilidad de Unicode garantiza que la descomposición no cambia una vez asignada | — *(medición, no mecanismo)* |
| D-005 | `norm()`/`fuzzy()` se espejan a mano en Kotlin y Python, sin regex, solo reemplazo literal | Dos regex "equivalentes" divergen en un caso borde que nadie nota. El reemplazo literal tiene semántica idéntica en ambos | `normalization-vectors.tsv`, en los tests de ambos lados |
| D-006 | `NORM_VERSION` distinta ⟹ el pack se rechaza | El pack está indexado con otras reglas: no falla, devuelve menos resultados | `audit_dictionary.py` compara las constantes; `PackFile.open()` rechaza el pack |
| D-019 | El reemplazo portable de una API JVM lleva **nombre distinto** (`appendUtf16`, no `appendCodePoint`) | En la JVM el miembro nativo gana sobre la extensión: el código portable nunca se ejecutaría, funcionando hoy y fallando al compilar para otro target | — *(convención; la captura `dict-core/CLAUDE.md`)* |

## Formato de pack

| # | Decisión | Por qué | Enforced in |
|---|---|---|---|
| D-001 | Un SQLite read-only por pack, inmutable, sin migraciones | Permite optimizar el esquema solo para lectura. Room queda descartado para packs: `createFromFile()` **copia** el archivo, duplicando decenas de MB en el reloj | `verify_pack.py` |
| D-002 | Los packs se abren con `BundledSQLiteDriver`, no con el SQLite del sistema | FTS5 no está garantizado en Android. El build de androidx trae `ENABLE_FTS5`; verificado además `THREADSAFE=2` y `MAX_ATTACHED=10` | `PlatformAssumptionsTest` (instrumentado) |
| D-009 | El payload es texto delimitado, no JSON ni CBOR | Se parsea sin dependencias en los dos lenguajes y se lee con la vista al depurar. Después de comprimir, la diferencia de tamaño con un binario es ruido | `PayloadCodecTest` + `test_payload.py` |
| D-010 | `form` y `trans` son `WITHOUT ROWID` con PK compuesta | La tabla **es** el índice: sin rowid y sin un B-tree secundario que duplique los mismos datos | `verify_pack.py` (plan de consulta) |
| D-011 | `fts_def` es contentless (`content=''`) y su rowid es `entry.id` | No guarda una segunda copia del texto, que ya vive comprimido en `entry.payload`. Consecuencia asumida: sin `snippet()`/`highlight()` | `verify_pack.py` + `PlatformAssumptionsTest` |
| D-012 | El prefijo usa un covering index y un rango explícito, no `LIKE 'x%'` | `LIKE` solo se optimiza a range scan si `case_sensitive_like` está bien, y ante la duda SQLite hace full scan | `verify_pack.py` + `PlatformAssumptionsTest`, los dos con `EXPLAIN QUERY PLAN` |
| D-013 | `idx_entry_fuzzy` es angosto a propósito: no es covering | Incluir `headword`/`pos` duplicaría varios MB por un camino que solo se recorre cuando el prefijo no dio resultados | — |
| D-014 | `trans` indexa la frase completa **y** cada palabra, con tope `TRANS_MAX_PER_KEY = 50` | Sin tokenizar, buscar "run" no encuentra "to run". Sin tope, "to" apuntaría a decenas de miles de entradas. Se topea en vez de descartar: buscar "to" sigue devolviendo algo útil | `test_build.py` |
| D-016 | Los índices se crean al final, sobre las tablas ya pobladas | Mantenerlos durante la ingesta es mucho más lento | `test_build.py` (no queda staging) |
| D-028 | Presupuesto **blando** de 50 MB por pack | Es un objetivo, no un límite duro. **Todavía sin medición**: el pack real de español son 1.036.458 senses y nunca se construyó | — *(lo mide `pack-workflow`)* |

## Compresión del payload

| # | Decisión | Por qué | Enforced in |
|---|---|---|---|
| D-007 | deflate con diccionario precargado compartido, **no zstd** | deflate está en `java.util.zip` y en el `zlib` de la stdlib. zstd obligaría a una librería nativa en el reloj *además* de la de SQLite, y a una dependencia de pip | `payload-fixture.tsv` (round-trip Python→JVM) |
| D-008 | `meta.payload_dict_sha256` se verifica al abrir el pack | **deflate no detecta un diccionario equivocado**: descomprime sin lanzar nada y devuelve texto corrupto — medido: "moverse rapidamente" → " nadrse rapidamente" | `verify_pack.py` + `PackFile.open()` |

## Portabilidad

| # | Decisión | Por qué | Enforced in |
|---|---|---|---|
| D-017 | Toda API de JVM en `PlatformJvm.kt`; el resto de `:dict-core` es Kotlin puro | Deja la conversión a KMP como tres pasos mecánicos en vez de una reescritura | `ArchitectureTest` |
| D-018 | KMP evaluado y **no adoptado** | Wear Compose es solo Android y Compose Multiplatform no apunta a watchOS: la UI, que es la mayor parte del trabajo restante, no se comparte con ningún segundo destino | — *(decisión, no mecanismo)* |

## Plataforma Wear OS

| # | Decisión | Por qué | Enforced in |
|---|---|---|---|
| D-023 | `minSdk 33`, `compileSdk`/`targetSdk 37` | Wear OS 7 = API 37. Bajo API 33 solo queda hardware descontinuado. Targetear 37 evita migración forzada en ~12 meses | `app/build.gradle.kts` |
| D-024 | Wear Widgets pospuesto; se usan Tiles + Protolayout | `androidx.glance.wear:*` y `androidx.compose.remote:*` están en alpha con packages moviéndose, y solo existen en Wear OS 7 | — |
| D-025 | **Prohibido** `androidx.glance:glance-wear-tiles` | Deprecado y será removido. El naming confunde: no es la librería de Wear Widgets | — *(candidato a check en la auditoría)* |
| D-026 | La búsqueda vive dentro de la app | Tiles y widgets no aceptan text input | — |
| D-027 | Input por voz (`RecognizerIntent`) primero, teclado como fallback | Es la razón de existir del nivel tolerante a errores: el dictado no coincide exacto con ningún lema | — |
| D-029 | Descargas diferidas a **cargando + Wi-Fi** | Guía oficial de Wear OS, literal. Con packs de decenas de MB no es opcional | — *(el instalador no existe todavía)* |
| D-030 | `android:dataExtractionRules`, no `fullBackupContent` | Con `minSdk 33`, `fullBackupContent` es el mecanismo de Android 11 e inferiores. Sin esto, Drive sube decenas de MB regenerables | — *(lint ya reporta `DataExtractionRules`)* |
| D-032 | Dependencias en stable; nada alpha ni rc en el camino crítico | `sqlite 2.8.0-alpha01` y `work 2.12.0-rc01` existen y no se usan | `gradle/libs.versions.toml` |
| D-033 | Kotlin queda en 2.2.10, no sube a 2.4.20 | `:dict-core` tiene `allWarningsAsErrors`: un bump de compilador convierte cualquier warning nuevo en build roto, y nada del roadmap necesita 2.4 | `gradle/libs.versions.toml` |

## Rendimiento y batería

| # | Decisión | Por qué | Enforced in |
|---|---|---|---|
| D-042 | No se optimiza nada sin una medición previa | Hoy los cuatro presupuestos de `docs/formato-pack.md` son objetivos escritos a priori, sin un solo número real detrás | `CLAUDE.md` §Verificación; `benchmark` skill |
| D-043 | La correctitud se verifica en **emulador**; el rendimiento y la batería, en **reloj físico** | La imagen del emulador trae el ICU y el SQLite de su nivel de API, así que sirve para normalización y planes de consulta. Para rendimiento la guía oficial pide *"physical Wear OS devices"* | — *(convención; la lleva el `benchmark` skill)* |
| D-044 | La batería se mide con el power metric de Macrobenchmark, Perfetto o el Power Profiler | **Battery Historian ya no se mantiene**, según su propia documentación, y es lo que recomienda casi toda la guía de terceros | — |

## Contenido y licencias

| # | Decisión | Por qué | Enforced in |
|---|---|---|---|
| D-015 | Los packs se construyen en `tools/packbuilder`; la app nunca parsea fuentes crudas | El JSONL del Wikcionario son 1,1 GB. La poda es trabajo de ingeniería, no configuración | `audit_dictionary.py` (sin parsers en `:app`/`:dict-core`) |
| D-031 | El contenido viene de Wiktionary (CC BY-SA); la pantalla de atribución **no es opcional** | El DLE de la RAE no tiene licencia abierta ni API pública. Wiktionary es efectivamente la única fuente utilizable para definiciones en español | — *(candidato a ship-blocking check)* |
| D-034 | El primer pack es **monolingüe con definiciones**, español | Decisión de producto. Cambia el caso primario del esquema: `trans` pasa a ser opcional, porque en monolingüe duplica lo que `fts_def` ya indexa mejor | — *(el roadmap lo lleva)* |

## Higiene del repositorio

| # | Decisión | Por qué | Enforced in |
|---|---|---|---|
| D-020 | El pack de juguete **no** se commitea | Es determinista y se regenera; commitearlo metería un binario que cambia en cada build por `meta.built_at` | `.gitignore` |
| D-021 | `gradle-wrapper.jar` **sí** se commitea | La regla `*.jar` lo excluía y un clone no podía correr `./gradlew`. Es el punto del wrapper | `.gitignore` (excepción explícita) |
| D-022 | `local.properties` no se trackea | Contiene la ruta absoluta al SDK de una máquina concreta | `.gitignore` |
| D-035 | Cada commit queda verde por sí solo; se parten por dependencia, no por tamaño | Un historial no bisecable no sirve para encontrar cuándo se rompió algo | `commit` skill (verificación por worktree) |

## Tooling de Python

| # | Decisión | Por qué | Enforced in |
|---|---|---|---|
| D-045 | El builder no tiene dependencias de terceros: solo stdlib | Un pipeline que solo necesita `python3` se puede correr desde cualquier clone sin preparar nada | `pyproject.toml` → `dependencies = []` |
| D-046 | **El gate no depende de Hatch.** `./gradlew check` corre `python3 -m unittest` a secas | Si el gate necesitara Hatch, un clone limpio dejaría de verificarse solo. Hatch es la capa de desarrollo | `tools/build.gradle.kts` |
| D-047 | La matriz de versiones de Python es la razón principal de tener Hatch | El builder escribe claves que el reloj recalcula: que dé lo mismo en todo Python **es** el invariante central. Medido: 35 tests idénticos bajo Unicode 13.0 y 16.0 | `hatch run matrix:test` |
| D-048 | `requires-python = ">=3.9"`, abierto hacia arriba | 3.9 es el piso solo para **regenerar** el repertorio (exige Unicode 13.0.0). Construir packs funciona en cualquier versión moderna, y está medido | `pyproject.toml`; el guardián de `gen_repertoire.py` |

## Decisiones descartadas, con el número que las descartó

Están acá para que no se propongan de nuevo. Son de las filas más útiles del archivo.

| # | Se descartó | Por qué, con el número |
|---|---|---|
| D-036 | **Un canario comprimido** para validar el diccionario de payload | Se derivaba del propio diccionario, así que uno **truncado seguía validando**. Un mecanismo de integridad que se valida a sí mismo no valida nada. Reemplazado por el sha256 de D-008 |
| D-037 | **zstd** para el payload | Comprime más, pero costaba una librería nativa en el reloj además de la de SQLite, y una dependencia de pip en el builder. Ver D-007 |
| D-038 | **Play Asset Delivery** para distribuir packs | No tiene soporte documentado en Wear OS. No se basa la distribución en algo no verificable |
| D-039 | **Room para leer los packs** | `createFromFile()` copia el archivo al directorio de Room, duplicando decenas de MB. Room se usa solo para los datos de la app |
| D-040 | **OkHttp** para descargar packs | `HttpURLConnection` hace `Range` y progreso sin sumar un byte al APK, y ya se agrega ~1–1,5 MB por ABI de SQLite nativo. Se revisa si reintentos o TLS resultan insuficientes |
| D-041 | **Extraer un parser de SQL** de `schema.sql` para separar los índices | Un comentario con un punto y coma rompía el split. Se resolvió con dos archivos, `schema.sql` e `indexes.sql`. Un parser de SQL hecho a mano se rompe así de nuevo |
| D-049 | **La regla `UP031` de ruff** (usar f-strings en vez de `%`) | De los **89 hallazgos de la primera corrida, 69 eran esa sola regla**. Un linter que grita 69 veces por una preferencia se termina apagando entero. Además hay un motivo técnico: `gen_repertoire.py` emite una plantilla de Kotlin llena de llaves, que en un f-string habría que duplicar una por una |

## Decisiones abiertas

**No están decididas.** Si te encontrás resolviendo una de estas por tu cuenta, pará y preguntá.

| Tema | Qué hay que decidir | Por qué bloquea |
|---|---|---|
| Join key entre packs | `(norm, pos)` laxo, o `entry.id` como hash estable | Condiciona el formato del pack base, que es el primero que se va a construir. Ver `docs/roadmap.md` |
| `detail=none` en `fts_def` | Achica el índice pero mata las consultas de frase | Con definiciones como contenido principal, buscar frases dentro puede ser *la* feature |
| `columnsize=0` en `fts_def` | Achica más, pero `SELECT COUNT(*)` pasa a dar error | Rompería una comprobación de `verify_pack.py` |
| Compresión por fila vs bloques de 50–64 kB | El dominio usa bloques (dictzip); nosotros por fila | Sin medición que lo decida a escala real |
| **R8 en release** | Hoy está **desactivado** (`optimization { enable = false }`), heredado del template | La guía oficial de Wear OS lo nombra como una de las dos herramientas más efectivas. Activarlo reintroduce la clase de bug que solo aparece en release, así que va atado a la comprobación en dispositivo. Ver roadmap O-2 |
| **Período de refresco de la complication** | El manifest tiene `UPDATE_PERIOD_SECONDS = 3600`, heredado del template | La guía oficial pide *"2 hours or longer"*, o desactivar el refresco. Se decide junto con qué muestra la superficie glanceable |
| Startup profile | Reduce latencia de arranque a cambio de tamaño de APK | Ya sumamos ~1–1,5 MB por ABI de SQLite nativo. Necesita el número de O-1 |
