# Información externa que vale la pena conocer

No es una lista de links: cada entrada dice qué aporta **a este proyecto**, y cuando contradice
algo ya decidido, lo dice.

Regla para mantenerlo: algo entra cuando **cambió o confirmó una decisión**, no porque esté bien
escrito.

Marcado **ASSUMPTION** lo que viene de un resumen de búsqueda en vez de la fuente primaria.
Si una de esas entradas va a sostener una decisión, leé la fuente antes.

## SQLite y FTS5

- **[SQLite FTS5 Extension](https://sqlite.org/fts5.html)** — *(fuente primaria, leída)* La
  referencia de las opciones `detail`, `columnsize` y `content`.

  **Qué confirma:** que `content=''` era la elección correcta, y por el motivo documentado: no
  guardar una segunda copia del texto.

  **Qué contradice, y no está resuelto:** `columnsize=0` implica que *"todas las consultas deben
  ser full-text"* y `SELECT COUNT(*)` **da error** — eso rompería la comprobación de
  `verify_pack.py` que cuenta filas de `fts_def`. Y `detail=none`/`detail=column` eliminan las
  **consultas de frase**, que es exactamente la forma de la consulta #5 documentada en
  `formato-pack.md`.

  **Sin aplicar:** las dos opciones achican el índice, y con definiciones como contenido
  principal ese índice es una fracción grande del pack. Es un trade-off real entre tamaño y
  capacidad de búsqueda.

  Produjo: D-011. Abrió dos decisiones abiertas.

## Wear OS

- **[Principios de desarrollo Wear OS](https://developer.android.com/training/wearables/principles)**
  — *(fuente primaria, leída)*

  **Qué confirma:** WorkManager para tareas largas, y el diseño offline-first.

  **Qué corrige:** el texto oficial es *"Defer downloads until the watch is charging **and**
  connected to Wi-Fi"*. El plan original pedía red no medida y espacio suficiente, **pero no
  exigía carga**.

  **Lo que NO dice, y se afirmó igual:** la página no da ningún límite de almacenamiento ni de
  tamaño de descarga. Cualquier número sobre espacio libre en relojes es **ASSUMPTION** hasta
  que aparezca una fuente.

  Produjo: D-029.

- **`android:allowBackup` deprecado desde Android 12** — **ASSUMPTION**, de resumen citando la
  documentación oficial de cambios de comportamiento.

  **Qué corrige:** con `minSdk 33`, `fullBackupContent` **no aplica** — es el mecanismo de
  Android 11 e inferiores. Solo cuenta `android:dataExtractionRules`, con reglas separadas de
  `<cloud-backup>` y `<device-transfer>`. El lint del propio proyecto ya lo reporta.

  Produjo: D-030.

- **[Rendimiento de Compose en Wear OS](https://developer.android.com/training/wearables/compose/performance)**
  — *(fuente primaria, leída)*

  **Qué establece:** *"Start with the most effective performance tool types: baseline profiles
  (including startup profiles) and the R8 code optimizer."* Y el motivo de fondo: *"many Wear OS
  devices have limited CPU and GPU resources compared to larger mobile devices"*.

  **Qué contradice directamente:** `app/build.gradle.kts` tiene **R8 desactivado**
  (`optimization { enable = false }`), heredado del template. La guía oficial lo nombra como una
  de las dos palancas principales y nosotros la tenemos apagada sin haberlo decidido.

  **Qué corrige de nuestro plan:** *"Run all final performance tests on a suite of physical Wear
  OS devices"*. El emulator sirve para correctitud, no para cerrar el rendimiento.

  **Sin aplicar:** startup profiles. La página advierte que aumentan el tamaño del APK, y ya
  sumamos ~1–1,5 MB por ABI de SQLite nativo.

  Produjo: D-042, D-043. Abrió dos decisiones abiertas (R8, startup profile).

- **[Conservar energía y batería en Wear OS](https://developer.android.com/training/wearables/apps/power)**
  — *(fuente primaria, leída)*

  **Qué confirma:** que diferir descargas hasta que el reloj cargue no es una precaución sino la
  guía (D-029). El acceso a red está clasificado *very high impact*, por encima de encender la
  pantalla.

  **Qué contradice:** *"Disable automatic refresh, or increase the refresh rate to 2 hours or
  longer"* para tiles y complications. El manifest tiene hoy `UPDATE_PERIOD_SECONDS = 3600` —
  una hora, la mitad del mínimo recomendado. También viene del template.

  **Qué corrige de la intuición:** el gasto no está donde uno lo busca. Para esta app el orden
  real es red, después pantalla, y recién después CPU — y nuestro trabajo de CPU dura
  milisegundos.

  **Sin aplicar:** *"Batch any related operations, to maximize the time that your app's process
  is idle"*. Relevante para el instalador de packs cuando exista.

  Produjo: confirma D-029; abrió la decisión del período de refresco.

- **Battery Historian está sin mantenimiento** — **ASSUMPTION**, de resumen citando su propia
  documentación, que recomienda *"system tracing, the Macrobenchmark power metric, or the Power
  Profiler"*.

  **Por qué esta entrada existe:** casi toda la guía de terceros sobre batería en Android empieza
  por Battery Historian. Sin esta nota, cada sesión futura lo va a redescubrir y proponer.

  Produjo: D-044.

## Datos lexicográficos

- **[kaikki.org / wiktextract](https://kaikki.org/eswiktionary/index.html)** — *(fuente
  primaria, leída)* Wiktionary procesado a JSONL, por edición y por sección de idioma.

  **Qué confirma:** no parsear fuentes crudas en la app, y podar en el builder.

  **Qué corrige:** para definiciones **en español** la fuente es el **Wikcionario**
  (eswiktionary), no el Wiktionary inglés — son datasets distintos. Además,
  [la página de datos crudos](https://kaikki.org/eswiktionary/rawdata.html) declara ese formato
  **deprecado** y dirige a las páginas procesadas por idioma; el plan original apuntaba al
  artefacto deprecado.

  **Los números que importan:** eswiktionary sección Español, **1.036.458 senses**. enwiktionary
  sección English, **1.787.236 senses**. El Wikcionario cubre inglés con solo 35.021 senses, así
  que definiciones en español de palabras inglesas no es una fuente que exista.

  Produjo: D-034. Dejó el presupuesto de D-028 sin respaldo.

- **[Formatos de diccionario — GoldenDict](https://xiaoyifang.github.io/goldendict-ng/dictformats/)**
  — **ASSUMPTION**, de resumen. dictzip, StarDict, slob.

  **Qué confirma:** comprimir las definiciones es obligatorio, no una optimización.

  **Qué hacemos distinto, y sin nada que lo respalde:** el dominio comprime en **bloques de
  50–64 kB** con índice de offsets, degradando la compresión "menos de un 10%". Nosotros
  comprimimos **por fila** con diccionario precargado. La única medición que existe es un 9,5%
  sobre una muestra de juguete hecha a mano, que no sostiene una decisión a 2,8 millones de
  senses.

  Produjo: nada todavía. Es una decisión abierta.

- **RAE / DLE** — **ASSUMPTION**, de resumen.

  **Qué establece:** el DLE no tiene API pública ni licencia abierta; las APIs que circulan
  scrapean el sitio.

  **Por qué esta entrada existe:** para que cuando alguien proponga "sacamos las definiciones
  del DLE", la respuesta esté escrita. **Antes de actuar sobre esto, leer los términos de uso
  directamente.**

  Produjo: D-031.

## Kotlin Multiplatform

- **[Compose Multiplatform y watchOS](https://slack-chats.kotlinlang.org/t/13151865/are-there-plans-for-compose-to-target-watchos-apple-watch)**
  — **ASSUMPTION**, de resumen.

  **Qué establece:** Wear Compose es solo Android; Compose Multiplatform no apunta a watchOS.
  Es el hecho que decide que KMP no compra nada hoy: la UI no se comparte con ningún segundo
  destino.

  Produjo: D-018.

- **[ktecma262](https://github.com/mgilbir/ktecma262)** — normalización Unicode en Kotlin puro
  con tablas propias.

  **Qué confirma:** su propia documentación dice que *"java.text.Normalizer es solo JVM y
  Kotlin/Native no tiene nada, así que el código multiplataforma ha estado comparando secuencias
  que se ven idénticas y no lo son"*. **Leer eso fue lo que llevó a encontrar el bug de D-003 en
  este repo**, que no tenía nada que ver con KMP.

  **Sin aplicar:** si `:dict-core` va a KMP, es el reemplazo de NFD.

  Produjo: indirectamente D-003.

- **[KFlate](https://github.com/rafambn/KFlate)** — deflate en Kotlin puro.

  **Qué confirma:** **soporta diccionario precargado**, que es lo que usa nuestro payload. Era
  la dependencia que más riesgo tenía de no existir para KMP.

  **Sin aplicar:** reemplazo de `java.util.zip` si el módulo va a KMP.

- **[androidx.sqlite](https://developer.android.com/kotlin/multiplatform/sqlite)** —
  `BundledSQLiteDriver`.

  **Qué confirma:** que empacar SQLite propio es el camino soportado. Verificado ejecutando:
  SQLite 3.50.1 con `ENABLE_FTS5`, `THREADSAFE=2`, `MAX_ATTACHED=10`, sin ICU.

  `THREADSAFE=2` es multi-thread, no serialized: **cada conexión necesita su dispatcher de un
  solo hilo**. No era una precaución, es un requisito.

  Produjo: D-002.

## Kotlin

- **Explicit API mode (`explicitApi()`)** — **ASSUMPTION**, de resumen citando kotlinlang.org.

  **Sin aplicar:** `:dict-core` es un módulo librería cuya superficie pública **es** el contrato
  con el resto de la app, y hoy nada impide que algo se vuelva público sin querer. Promovería
  esa regla de rung 1 a rung 3 con una línea en el build.

## Qué leer antes de tocar cada cosa

| Si vas a tocar… | Leé | Y cuidado con |
|---|---|---|
| `norm()` o `fuzzy()` | `docs/contratos-cruzados.md` §1 y §2 | Tocar un solo lenguaje. Subir Unicode sin pensarlo |
| El esquema del pack | `docs/formato-pack.md`, FTS5 arriba | `columnsize=0` rompe `verify_pack`; `detail=none` mata las frases |
| El codec del payload | `contratos-cruzados.md` §3 | deflate **no** avisa si el diccionario está mal |
| La compresión | la entrada de GoldenDict | No hay medición que respalde por-fila vs bloques |
| Las fuentes de datos | la entrada de kaikki | Wikcionario ≠ Wiktionary inglés. CC BY-SA obliga atribución |
| Cualquier cosa de Wear OS | `app/CLAUDE.md` | `glance-wear-tiles` está deprecado y sale primero al buscar |
| Rendimiento o arranque | la página de rendimiento de Wear OS | R8 está apagado hoy; el emulador no mide rendimiento |
| Batería | la página de energía de Wear OS | Battery Historian ya no se mantiene; el gasto está en la red, no en la CPU |
| Descargas | los principios de Wear OS | "cargando **y** Wi-Fi", no solo Wi-Fi |
