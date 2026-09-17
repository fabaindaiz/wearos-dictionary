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
| D-019 | El reemplazo portable de una API JVM lleva **nombre distinto** (`appendUtf16`, no `appendCodePoint`) | En la JVM el miembro nativo gana sobre la extensión: el código portable nunca se ejecutaría, funcionando hoy y fallando al compilar para otro target | `audit_dictionary.py` → `check_shadowed_extensions` |

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
| D-063 | `schema_version = 3`: `idx_entry_norm` pasa de `(norm, rank DESC, …)` a `(norm, rank, …)`, **ascendente** | En `rank` menor es más común (`schema.sql`), pero el prefijo ordenaba `rank DESC`: devolvía la entrada **menos** común primero. Con 22 entradas de juguete es invisible —`rank` solo desempata dentro de un mismo `norm`—; en el pack real son **375 de 7.265 norms** los que colisionan, y el síntoma se lee: `escrit` daba *escrito / Participio de escribir* (rank 994) antes que el sustantivo (988). Corregir solo la consulta no alcanza: el índice y el `ORDER BY` separados hacen que SQLite agregue `USE TEMP B-TREE` y la consulta deje de ser de cobertura (D-012) | `test_build.py` (orden **y** plan sin `TEMP B-TREE`) + `verify_pack.py` + `PlatformAssumptionsTest` |
| D-064 | `norm` vacío hace fallar el pack; `fuzzy` vacío **no** | No son el mismo problema. Sin `norm` la entrada es inalcanzable por todo camino. Sin `fuzzy` solo queda fuera del nivel tolerante, que es exactamente lo que corresponde a un lema de una letra muda: `fuzzy("h")` es vacío en el perfil español, y **"h" y "H" son entradas reales del Wikcionario**. Tratarlos igual hacía fallar el primer pack real por dos filas legítimas | `verify_pack.py` (falla por `norm`, cuenta las de `fuzzy`) + `test_build.py` |
| D-070 | `verify_pack.py` comprueba que `schema_version`, `norm_version` y `data_version` sean **enteros** | La app les hace `.toInt()` al abrir (`PackFile.parseMetadata`). Un valor que no lo sea no falla al construir ni al validar: falla **en el reloj**, con un `NumberFormatException` que ni siquiera nombra la clave. No es hipotético — el primer pack real se construyó con `data_version = "2026-09-15"` y `verify_pack.py` dio verde | `verify_pack.py` + `test_build.py` |
| D-028 | Presupuesto **blando** de 50 MB por pack. **Medido: el pack real lo pasa — 72,2 MB** | Ya no es una suposición. El Wikcionario español (1.036.458 senses, dump 2026-09-15) da **146.194 entradas y 72.212.480 bytes**, un 44 % por encima del objetivo. El 46 % del pack es la tabla `form` (33,4 MB, 1.487.695 filas, **93,5 % de ellas conjugaciones de verbos**): el precio de que "corriendo" encuentre "correr". Sigue siendo blando y no bloquea; lo que decide si se recorta es O-3, con este número | `verify_pack.py` §tamanos *(reporta, no falla)* |
| D-055 | La clave de join entre packs es **`entry.uid`**, una columna aparte: `entry.id` sigue siendo el rowid secuencial | Medido sobre 200.000 entradas sintéticas: hacer que `entry.id` *fuera* el hash cuesta **+35,2 % de tamaño**, casi todo en `fts_def_data` (10,39 → 28,35 MB) porque FTS5 guarda **deltas** de rowid. La columna aparte cuesta **+2,3 %**. `(norm, pos)` costaría 0 pero funde homógrafos del mismo `pos` en silencio, y degenera con las entradas sin `pos` | `verify_pack.py` (unicidad + receta) + `PlatformAssumptionsTest` + `LogicalIdentityTest` |
| D-056 | `uid` **no lleva índice** en el pack base | El join ocurre al **abrir** una entrada, cuando la fila ya se leyó entera, no en la lista de resultados —que la sirve el covering index sin tocar la tabla (D-012). El índice costaría +4,5 puntos porcentuales por un camino que nadie recorre. El índice vive en el pack auxiliar, que sí busca por `uid` | `PlatformAssumptionsTest.elUidEsUnicoYNoLlevaIndice` |
| D-057 | `uid` lo calcula **solo el builder**, en Python; la app lo lee y nunca lo recalcula | A diferencia de `norm()`/`fuzzy()` no es un contrato espejado entre dos lenguajes, así que no puede divergir (D-005). Agregar un `TextNormalizer.uid()` reintroduciría esa clase de bug entera | `audit_dictionary.py` → `check_forbidden_mirror` |
| D-058 | Dos entradas con la misma identidad lógica **hacen fallar el build**; no se funden ni se desempatan | Cualquier desempate que dependa del orden de inserción rompe justo la estabilidad entre rebuilds que `uid` existe para dar. La fuente entrega `sense_key` para separarlas | `LogicalIdentityTest` |

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
| D-024 | Wear Widgets pospuesto; se usan Tiles + Protolayout | `androidx.glance.wear:*` y `androidx.compose.remote:*` están en alpha con packages moviéndose, y solo existen en Wear OS 7 | `audit_dictionary.py` → `check_forbidden_dependency` |
| D-025 | **Prohibido** `androidx.glance:glance-wear-tiles` | Deprecado y será removido. El naming confunde: no es la librería de Wear Widgets | `audit_dictionary.py` → `check_forbidden_dependency` |
| D-026 | La búsqueda vive dentro de la app | Tiles y widgets no aceptan text input | — |
| D-027 | Input por voz (`RecognizerIntent`) primero, teclado como fallback | Es la razón de existir del nivel tolerante a errores: el dictado no coincide exacto con ningún lema | — |
| D-029 | Descargas diferidas a **cargando + Wi-Fi** | Guía oficial de Wear OS, literal. Con packs de decenas de MB no es opcional | — *(el instalador no existe todavía)* |
| D-030 | `android:dataExtractionRules`, no `fullBackupContent` | Con `minSdk 33`, `fullBackupContent` es el mecanismo de Android 11 e inferiores. Sin esto, Drive sube decenas de MB regenerables | — *(lint ya reporta `DataExtractionRules`)* |
| D-032 | Dependencias en stable; nada alpha ni rc en el camino crítico | `sqlite 2.8.0-alpha01` y `work 2.12.0-rc01` existen y no se usan | `gradle/libs.versions.toml` |
| D-033 | Kotlin queda en 2.2.10, no sube a 2.4.20 | `:dict-core` tiene `allWarningsAsErrors`: un bump de compilador convierte cualquier warning nuevo en build roto, y nada del roadmap necesita 2.4 | `gradle/libs.versions.toml` |

## Capa de consulta

| # | Decisión | Por qué | Enforced in |
|---|---|---|---|
| D-050 | Una conexión por pack, confinada a un dispatcher de **un solo hilo** | El SQLite empacado reporta `THREADSAFE=2`: multi-thread, **no** serialized. Una conexión no se puede usar desde dos hilos a la vez | `SqlitePackSource` + `PlatformAssumptionsTest` verifica el pragma |
| D-051 | El nivel tolerante solo se intenta si la cascada confiable devolvió menos de `FUZZY_TRIGGER` | Es el camino más caro y el menos confiable. Agregar candidatos por distancia cuando ya hay resultados buenos solo ensucia la lista | `SqlitePackSourceTest`; el fixture lo protege en `ToyPackFixtureTest` |
| D-052 | Los umbrales del nivel tolerante (`FUZZY_TRIGGER`, `FUZZY_PREFIX_LENGTH`, `MAX_EDIT_DISTANCE`) son **provisorios** | Elegidos a priori, sin medición. Con un pack de 22 entradas no significan nada: se ajustan con el pack real (roadmap O-1) | — *(sin medición; es lo que O-1 existe para arreglar)* |
| D-053 | Todo token de una búsqueda full-text se envuelve en comillas antes de llegar a FTS5 | Sin eso, un `"` suelto o un `OR` escrito por el usuario cambian la consulta o la hacen fallar | `SqlitePackSourceTest.elTextoLibreNoSeRompeConSintaxisDeFts` |
| D-068 | El prefijo ordena **`(exacta primero, rank, norm)`** y deduplica por `(headword, pos)`, aceptando que el covering index ya **no** satisfaga el `ORDER BY` | Ordenar por `norm` es ordenar alfabéticamente, y con 146.194 entradas eso entierra la palabra buscada: medido, `perro` salía en la **posición 619 de 782** para el prefijo "per", y la lista muestra 30. Con este orden sale **5ª**. El costo es un `USE TEMP B-TREE` sobre el rango: **1,8 ms p95 en el peor caso** (una letra, 22.358 filas) contra 0,01 ms, con un presupuesto de 20 ms — **en escritorio; el número de reloj falta (O-1)**. La deduplicación es en Kotlin con over-fetch ×3 y no `GROUP BY`: el `GROUP BY` cuesta 7,9 ms p95 y **no saca los duplicados que se ven**, que difieren en `pos` | `SqlitePackSourceTest` (orden, exacta primero y sin repetidos) + `test_build.py` (el fixture tiene las dos trampas) |
| D-069 | La deduplicación va **al final de la cascada**, no dentro del nivel de prefijo | `accumulated` se indexa por `entryId`, así que dos entradas distintas con el mismo lema y pos sobreviven las dos. Lo encontró un test: deduplicando solo en `byPrefix`, el **nivel tolerante volvía a meter** la entrada que el prefijo ya había fundido. Hacerlo después de ordenar además conserva la de mejor nivel y mejor score, en vez de una al azar | `SqlitePackSourceTest.noRepiteElMismoLemaConElMismoPos` |

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
| D-065 | Las páginas de forma flexionada (`form-of`) no son entradas, pero **la fuente hace dos pasadas** para recolectarlas como formas de su lema | Son el **82,33 %** de los registros del dump (703.506 de 854.460). Parecía que bastaba una pasada, porque el lema trae su conjugación en `forms` (`amigar` trae 137 formas). **Medido, no basta**: el `forms` de los lemas cubre el **92,31 %** de las palabras-forma y el 7,66 % restante —**53.708 palabras**, entre ellas "palpitaciones", "curvilínea" y "animalito"— se perdería sin error y sin log | `test_source_kaikki.py` |
| D-066 | Las páginas de forma **sin** el tag `form-of` ("Participio de escribir") **se conservan como entradas**, aunque sean el 19,35 % | Son ruido en la lista de resultados y cuestan ~7 % del payload, así que descartarlas es tentador: hay una señal estructural buena, `tags: [form-of]` a nivel de registro, que agarra 28.414 de ellas. **La medición lo prohíbe**: 1.341 de esas palabras (4,72 %) no llegan a ningún lema por ningún otro camino. En un repo cuyo único bug invisible es *falta una palabra*, 4,72 % de pérdida no se cambia por 7 % de tamaño | `test_source_kaikki.py` *(la poda; la conservación es por omisión)* |
| D-067 | `rank` en el pack del Wikcionario es un **proxy de riqueza de la página**, no frecuencia de uso | El Wikcionario no trae frecuencia y cruzar un corpus externo suma una fuente y una licencia. El proxy suma acepciones, ejemplos, formas, traducciones y etimología. **Medido y sirve**: ordenando por él, `perro` sube de la posición **619 a la 5** en el prefijo "per", y "hac"/"com" encabezan con `hacer` y `comer`. Se declara proxy a propósito: es reemplazable por frecuencia real sin tocar el formato | `test_source_kaikki.py` §RankTest *(que ordene bien es medición, no mecanismo)* |

## Higiene del repositorio

| # | Decisión | Por qué | Enforced in |
|---|---|---|---|
| D-020 | El pack de juguete **no** se commitea | Es determinista y se regenera; commitearlo metería un binario que cambia en cada build por `meta.built_at` | `.gitignore` |
| D-021 | `gradle-wrapper.jar` **sí** se commitea | La regla `*.jar` lo excluía y un clone no podía correr `./gradlew`. Es el punto del wrapper | `.gitignore` (excepción explícita) |
| D-022 | `local.properties` no se trackea | Contiene la ruta absoluta al SDK de una máquina concreta | `.gitignore` |
| D-035 | Cada commit queda verde por sí solo; se parten por dependencia, no por tamaño | Un historial no bisecable no sirve para encontrar cuándo se rompió algo | `commit` skill (verificación por worktree) |
| D-054 | `.idea/` no se trackea | Android Studio lo reescribe en cada sync y `workspace.xml` guarda estado de máquina. El estilo de código no se pierde: vive en `.editorconfig`, que es portable | `.gitignore` |

## El método de trabajo con agentes

| # | Decisión | Por qué | Enforced in |
|---|---|---|---|
| D-059 | El método vive en `docs/agents/`, versionado por el `method-header` de sus cuatro archivos, y **una lineage tiene un solo writer**: editar el método acá **es forkear**, no bumpear la versión | Dos repos en la misma lineage que mejoran el método y suben ambos a la misma versión producen dos versiones distintas con el mismo número, y la comparación ingenua concluye "idénticas" y **descarta un lado en silencio**. Forkear cuesta seis caracteres hex | `audit_dictionary.py` → `check_method_digest` |
| D-060 | Este repo es **single-agent a propósito**: no hay `.cursor/`, ni `.github/copilot-instructions.md`, ni `AGENTS.md` | Dos archivos de reglas que *pueden* discrepar *van* a discrepar, y nadie lo nota porque nadie lee más de uno. La asimetría que el método señala ya es la política de acá: el invariante central lo sostienen el hook, los vectores compartidos y el gate — rungs 3 y 4 — no la prosa de ningún agente. **Se reabre** el día que aparezca el archivo de reglas de un segundo agente | — *(política; se reabre por evento)* |
| D-061 | Este repo reconoce **cuatro capas de evaluación** y nombra cuál falta: gate (`./gradlew check`), invariant test (los vectores compartidos), **pre-ship check (la comprobación en reloj físico — NO EXISTE)** y mirar el output | Tener una capa y creer que se tienen cuatro es la forma normal de esta falla. Nombrar la que falta es lo que impide que un gate verde se lea como "funciona" | `docs/roadmap.md` §Comprobación que falta; `:dict-data:devicePrecheck` |
| D-062 | La fricción se registra en el changelog al primer golpe y **sube a `docs/roadmap.md` §Proceso y herramientas al segundo**, con la aritmética; las mejoras de proceso se **proponen, no se ejecutan** | Cada instancia de fricción es individualmente trivial —nadie nota los treinta segundos— y la aritmética es lo que arma el caso. Reescribir el workflow a mitad de tarea le saca al humano el modelo mental justo mientras lo está usando | `CLAUDE.md` §Cómo corre una sesión; `state-review` skill |

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
| Granularidad de la composición | ¿El pack auxiliar se une a la **entrada** o a la **acepción**? Hoy `uid` es por entrada (D-055) | Un sinónimo es de una acepción, no de la palabra. Un join por acepción necesita un ordinal estable dentro de la entrada, y el ordinal se corre cuando la fuente agrega una acepción. **Verificado al construir el pack real (2026-09-17)**: los senses de kaikki.org **sí** traen `id` (`es-leonino-es-adj-toe7A5EW`), distinto por acepción. Pero el sufijo parece derivado del contenido, así que **editar una glosa probablemente lo cambia**: sirve como ordinal dentro de la entrada, no como identidad estable. Queda por medir contra dos dumps de fechas distintas |
| `detail=none` en `fts_def` | Achica el índice pero mata las consultas de frase | Con definiciones como contenido principal, buscar frases dentro puede ser *la* feature |
| `columnsize=0` en `fts_def` | Achica más, pero `SELECT COUNT(*)` pasa a dar error | Rompería una comprobación de `verify_pack.py` |
| Compresión por fila vs bloques de 50–64 kB | El dominio usa bloques (dictzip); nosotros por fila | Sin medición que lo decida a escala real |
| **R8 en release** | Hoy está **desactivado** (`optimization { enable = false }`), heredado del template | La guía oficial de Wear OS lo nombra como una de las dos herramientas más efectivas. Activarlo reintroduce la clase de bug que solo aparece en release, así que va atado a la comprobación en dispositivo. Ver roadmap O-2 |
| **Período de refresco de la complication** | El manifest tiene `UPDATE_PERIOD_SECONDS = 3600`, heredado del template | La guía oficial pide *"2 hours or longer"*, o desactivar el refresco. Se decide junto con qué muestra la superficie glanceable |
| Startup profile | Reduce latencia de arranque a cambio de tamaño de APK | Ya sumamos ~1–1,5 MB por ABI de SQLite nativo. Necesita el número de O-1 |
