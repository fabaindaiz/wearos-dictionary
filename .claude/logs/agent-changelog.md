# Changelog de sesiones

**Cada sesión escribe su entrada acá, arriba de todo, antes de ofrecer commits.**

Existe porque **dos sesiones en paralelo no se ven entre sí**. Son baratas de correr al mismo
tiempo, ninguna sabe de la otra, y el conflicto se descubre al compilar — o peor, al revisar.
Con un agente vale más que con un equipo: las personas se cruzan en un pasillo, las sesiones no.

Formato:

```
## AAAA-MM-DD — <título de una línea>
**Qué.** Concretamente qué cambió.
**Áreas.** Archivos o carpetas.
**Por qué.** El motivo, incluyendo el pedido que lo originó.
**Arquitectura.** ✅ Cumple · ⚠️ Desviación · REVISAR — y por qué.
**Medido.** El número, si se afirmó algo.
```

---

## 2026-09-17 — El join key entre packs: `entry.uid`, decidido con medición

**Qué.** Se cerró la decisión abierta del join key. El pack sube a `schema_version = 2` con una
columna `entry.uid`: identidad lógica, estable entre reconstrucciones, sin índice. `entry.id`
sigue siendo el rowid secuencial. Cuatro decisiones nuevas (D-055 a D-058), un enforcer nuevo en
`audit_dictionary.py`, 7 tests de builder y 2 instrumentados.

**Áreas.** `tools/packbuilder/build.py`, `schema.sql`, `verify_pack.py`, `tests/test_build.py`,
`tools/audit_dictionary.py`, `dict-core/src/main/kotlin/cl/fadiaz/dictionary/core/Model.kt`,
`dict-data/src/main/kotlin/cl/fadiaz/dictionary/data/SqlitePackSource.kt`,
`dict-data/src/main/kotlin/cl/fadiaz/dictionary/data/PackFile.kt`, las dos suites instrumentadas,
`docs/decisions.md`, `docs/formato-pack.md`, `docs/contratos-cruzados.md`, `docs/roadmap.md`.

**Por qué.** Era la tarea #2 del roadmap y condicionaba el formato del pack base, que es lo
próximo que se construye. Se pidió comparar todas las condiciones antes de decidir.

**Arquitectura.** ✅ Cumple. La opción elegida deja intactos D-010, D-011, D-012 y D-013: no
toca `entry.id`, así que `fts_def` sigue alineado y los índices no cambian.

**Medido.** La medición es la que decidió, y mató la opción que el roadmap proponía.

- **Método:** cuatro packs sintéticos de 200.000 entradas con **contenido idéntico**, cambiando
  solo el esquema de ids. Glosas con vocabulario Zipf de 40.000 palabras —importa, porque FTS5
  guarda *deltas* de rowid y un vocabulario chico exagera la ventaja del id secuencial—, payload
  deflate, `VACUUM` al final, medido con `dbstat` (bytes por objeto). Scripts en el scratchpad de
  la sesión: `joinkey_size.py`, `joinkey_aux.py`.
- **Hash como `entry.id`: +35,2 %** (76,30 → 103,16 MB). El 67 % de ese costo es un solo objeto:
  `fts_def_data`, de 10,39 a **28,35 MB**. FTS5 no guarda el rowid de cada posting sino el delta
  contra el anterior: con ids secuenciales son 1–3 bytes, con hashes de 63 bits son 8–9. El resto
  (`form` +2,08, `trans` +2,07, `idx_entry_norm` +1,04, `idx_entry_fuzzy` +1,05 MB) paga el mismo
  impuesto, porque el id se repite en cada índice. **Extrapolado: +134 MB por millón de entradas**,
  contra un presupuesto blando de 50 MB por pack (D-028).
- **Columna `uid` sin índice: +2,3 %** (+8,9 MB por millón). Con índice único serían +6,8 %
  (+25,9 MB por millón), y por eso no lo lleva: el join ocurre al abrir una entrada, cuando la
  fila ya se leyó, no en la lista —que la sirve el covering index sin tocar la tabla (D-012).
- **El pack auxiliar, al revés:** con `uid INTEGER PRIMARY KEY` ocupa **13,5 % menos** que con
  `(norm, pos)` TEXT `WITHOUT ROWID` (13,34 vs 15,43 MB en 200.000 filas; −10,4 MB por millón).
  Sumando los dos lados, `uid` es más barato que `(norm, pos)` en cuanto exista un solo auxiliar.
- **Límites de la medición, para que nadie la sobre-interprete:** el contenido es sintético y el
  payload comprimido (~150 B) es más chico que el real, así que **con payloads de verdad el
  porcentaje baja y los MB absolutos se mantienen**. La extrapolación a 1M es lineal por entrada;
  la brecha de FTS se angosta despacio al crecer N. Y el Wikcionario son 1.036.458 *senses*, no
  entradas: las entradas `(headword, pos)` van a ser bastantes menos.
- **`(norm, pos)` se descartó por correctitud, no por tamaño** (cuesta 0 en el pack base): funde
  en silencio homógrafos que comparten `pos` y distinta etimología, y degenera con las entradas
  sin `pos` — el pack de juguete ya tiene una (`arbol`, `pos` vacío).
- **El enforcer nuevo se probó fallando**: se agregó un `fun stableUid()` de mentira en
  `:dict-core` y `check_forbidden_mirror` rompió el audit con las dos reglas; se borró y volvió a
  verde. Un check que nunca falló no se sabe si enforcea.
- **22/22 tests instrumentados en API 33 y API 37.0** con `schema_version = 2` y el toy pack
  reconstruido. El toy pack sigue pesando 53.248 bytes: la columna no agregó ni una página.

**Drift corregido de paso.** `docs/roadmap.md` afirmaba que *"`SearchRepository` ya fusiona varios
sources"* como punto a favor de la composición. **No existe**: aparecía solo en esa línea del
roadmap, en ningún `.kt`.

---

## 2026-09-17 — Los 20 tests instrumentados corrieron por primera vez, en dos niveles de API

**Qué.** Se creó el entorno que faltaba (cmdline-tools, dos imágenes de sistema Wear OS arm64,
dos AVD) y se corrieron los tests de `:dict-data` en **API 33 (Wear OS 4, Android 13)** y
**API 37.0 (Wear OS 7.0, Android 17)**. La primera corrida dio **19/20**; se corrigió la
expectativa que fallaba y ahora es **20/20 en los dos niveles**. Se actualizó `docs/roadmap.md`
(§Dónde estamos, la tabla de las tres cosas, y §Comprobación que falta), y el conteo de tests en
`dict-data/CLAUDE.md` y en la skill `verify`, que decían 16.

**Áreas.** `dict-data/src/androidTest/kotlin/cl/fadiaz/dictionary/data/PlatformAssumptionsTest.kt`, `docs/roadmap.md`,
`dict-data/CLAUDE.md`, `.claude/skills/verify/SKILL.md`. Fuera del repo: el SDK de Android.

**Por qué.** Era la tarea #1 del roadmap: el único paso que convierte el comportamiento en
Android de ASSUMPTION a verificado. `devicePrecheck` diagnosticó exactamente qué faltaba, y la
máquina no tenía `cmdline-tools` ni ninguna imagen de sistema.

**Arquitectura.** ✅ Cumple. La corrección no tocó código de producción: el test hardcodeaba una
cota de prefijo que no seguía la convención de `PrefixRange.upperBound`.

**Medido.**

- **La falla era del test, no del producto.** `PlatformAssumptionsTest#lasCincoConsultasDevuelvenLoEsperado`
  pedía el rango `fuzzy >= 'kore' AND fuzzy < 'koref'`. La clave fuzzy de *correr* en el pack de
  juguete es `korer`, y `'korer' < 'koref'` es **falso** porque `'r' > 'f'`: la cota se había
  escrito *agregando* una letra en vez de **incrementando el último code point**, que es lo que
  hace `PrefixRange.upperBound("kore") == "korf"`. Con `'korf'` el rango devuelve 2 entradas
  (`korer`, `koregir`). El código de producción nunca tuvo el bug: arma la cota con
  `PrefixRange`, y por eso los 13 tests de `SqlitePackSource` —que pasan por ahí— ya pasaban.
- **Misma clase de error, latente, en la consulta inversa** del mismo test: `norm >= 'run' AND
  norm < 'rus'`. Pasaba por suerte —en el pack de juguete la única clave que empieza con `run`
  es `run`— pero `'rus'` es una cota **más laxa** que la convención (`'ruo'`), así que en un
  pack real habría incluido claves que no son del prefijo. Corregida a `'ruo'`: la aserción
  sigue dando 1.
- **El invariante central pasa en Android**, en los dos extremos de ICU soportados:
  `NormalizationOnDeviceTest` (3 tests) verde en API 33 y 37.0. Con esto, NFD y `lowercase()`
  delegados a la plataforma (D-004) dejan de ser ASSUMPTION en Android dentro de ese rango.
- **`THREADSAFE=2` y `ENABLE_FTS5` confirmados en dispositivo**, y el prefijo usa
  `COVERING INDEX idx_entry_norm` en los dos niveles: las asunciones de D-002, D-012 y D-050
  quedan verificadas donde importa.
- **Lo que el emulador no midió:** nada de rendimiento ni batería (D-043). Sigue sin haber un
  solo número de latencia real.
- **Entorno instalado** (queda en la máquina, no en el repo): `cmdline-tools` 16111833 arm64,
  sha1 `ad03…e830` verificado contra el declarado por `dl.google.com/android/repository`;
  imágenes `system-images;android-33;android-wear;arm64-v8a` (1,06 GB) y
  `system-images;android-37.0;android-wear-signed;arm64-v8a` (1,26 GB); AVD `wear_api33` y
  `wear_api37`, perfil `wearos_small_round`. Nota para la próxima sesión: **`sdkmanager` está
  deprecado** en esta versión de las cmdline-tools —el reemplazo es el binario `android`
  (`android sdk install`)— pero `android emulator create` todavía **no ofrece perfiles de
  watch**, así que los AVD de Wear hay que crearlos con `avdmanager`.

## 2026-09-17 — `.idea/` deja de trackearse

**Qué.** `.gitignore` ignora `.idea/` entero y los 7 archivos que estaban trackeados se
destrackean. Siguen en disco.

**Áreas.** `.gitignore`, `docs/decisions.md`.

**Por qué.** El proyecto se mueve a Android Studio como IDE de pruebas. Un sync de Gradle en
esta sesión ya reescribió `gradle.xml`, `misc.xml` y `workspace.xml`, y generó cinco archivos
nuevos — sin que nadie tocara el IDE.

**Arquitectura.** ✅ Cumple. El estilo de código no se pierde: vive en `.editorconfig`, que es
portable y lo respetan Android Studio, VS Code y los linters.

**Medido.** `workspace.xml` estaba en la lista de ignorados del `.gitignore` desde el bootstrap
y aparecía modificado igual: **`.gitignore` no aplica a archivos ya trackeados**. Es el mismo
mecanismo que hacía que `local.properties` viajara con la ruta del SDK de una máquina concreta.

---

## 2026-09-17 — `devicePrecheck` y `dict-data/CLAUDE.md`

**Qué.** Tarea Gradle que diagnostica si se pueden correr los tests instrumentados antes de
intentarlo, y un `CLAUDE.md` anidado para `:dict-data`.

**Áreas.** `dict-data/build.gradle.kts`, `dict-data/CLAUDE.md`, `CLAUDE.md`, skill `verify`.

**Por qué.** Se preguntó cómo integrarse con Android Studio. **La premisa era incorrecta: el
plugin oficial de Claude Code para JetBrains soporta Android Studio explícitamente**, la
documentación lo nombra. Lo que sí faltaba era que el paso pendiente —correr los tests en
dispositivo— no se trabara en un error críptico.

**Arquitectura.** ✅ Cumple.

**Medido.** Al revisar el entorno: **no hay ningún AVD creado ni imagen de Wear OS instalada**
en esta máquina. `~/.android/avd/` está vacío, el SDK no tiene `system-images/`, solo la
platform `android-37.0`. Sin eso los 16 tests instrumentados no se pueden correr, y todo lo que
el repo afirma sobre Android sigue siendo ASSUMPTION.

El `doLast` volvió a romper el configuration cache, esta vez por referenciar una función
declarada a nivel de build script. Es el mismo error que con el `copy {}` de la sesión anterior,
así que quedó documentado en `dict-data/CLAUDE.md` para no tropezar una tercera vez.

---

## 2026-09-17 — `SqlitePackSource`: la cascada de cinco consultas

**Qué.** Implementación de `DictionarySource` sobre SQLite, con los cinco caminos de búsqueda y
su cascada. 13 tests instrumentados, más `ToyPackFixtureTest` en Python que protege lo que esos
tests suponen del contenido del pack de juguete.

**Áreas.** `dict-data/`, `tools/packbuilder/tests/test_build.py`, `docs/decisions.md`,
`docs/roadmap.md`.

**Por qué.** Era el item más grande que quedaba sin bloquear.

**Arquitectura.** ✅ Cumple. Cuatro decisiones nuevas (D-050 a D-053), una de ellas marcada
explícitamente como **sin medición**: los umbrales del nivel tolerante son números elegidos a
priori y con un pack de 22 entradas no significan nada.

**Medido.**

- **Una expectativa de test estaba mal y se encontró antes de gastar un emulador.** Simulé la
  cascada en Python contra el mismo pack: 18 expectativas, 1 incorrecta. El prefijo `"cor"` da
  **4** resultados y `FUZZY_TRIGGER` es 5, así que el test que afirmaba "el nivel tolerante no
  se dispara" habría fallado. Se cambió a `"c"`, que da 7.
- Eso motivó `ToyPackFixtureTest`: los tests instrumentados dependen del **contenido** del pack
  de juguete, y romper esa suposición editando `toy.py` no se notaría hasta conectar un
  dispositivo — y el fallo se leería como un bug del código, no del fixture.

**Sigue sin correr en Android.** Los 13 tests compilan. Nada de esto se ejecutó.

---

## 2026-09-17 — `:dict-data` y los tests que cierran las asunciones sobre Android

**Qué.** Módulo Android `:dict-data` con `PackFile` (abre read-only, valida `schema_version`,
`norm_version`, `payload_codec` y el sha256 del diccionario) y dos suites instrumentadas:
`NormalizationOnDeviceTest` y `PlatformAssumptionsTest`. Más una tarea Gradle que genera los
assets del test para que no haya un paso manual previo.

**Áreas.** `dict-data/`, `gradle/libs.versions.toml`, `settings.gradle.kts`, `.gitignore`,
`docs/decisions.md`, `docs/roadmap.md`, `docs/architecture.md`, `CLAUDE.md`, skill `verify`.

**Por qué.** Era el item de mayor valor no bloqueado: convierte el invariante central de
*asumido en Android* a *verificable en Android*.

**Arquitectura.** ✅ Cumple. No se implementó `DictionarySource` todavía porque depende de si
`trans` sobrevive en packs monolingües, que es una decisión abierta.

**Medido.** Nada en dispositivo: **los tests compilan pero nunca se ejecutaron**, porque no hay
emulador ni reloj conectado. Mientras no se corran, el comportamiento en Android sigue siendo
ASSUMPTION — el test existe, que no es lo mismo que haber pasado.

Cinco decisiones dejaron de estar en rung 1 al ganar enforcer: D-002 (FTS5), D-006
(`norm_version`), D-008 (hash del diccionario), D-011 (rowid de FTS), D-012 (covering index).
Las decisiones sin enforcer bajaron de 16/44 a **15/49**.

Dos cosas que AGP 9 no deja hacer y costaron una iteración cada una: no acepta un `Provider` en
la SourceSet API, y un `copy {}` dentro de `doLast` rompe el configuration cache. Las dos están
resueltas en `dict-data/build.gradle.kts` con el motivo escrito.

---

## 2026-09-17 — Entorno Hatch para el tooling; el builder es independiente de la versión de Python

**Qué.** `pyproject.toml` con tres entornos Hatch (default, matrix, lint), ruff configurado, y
el tooling documentado en `tools/CLAUDE.md` y el README.

**Áreas.** `pyproject.toml`, `tools/CLAUDE.md`, `README.md`, `.gitignore`, `docs/decisions.md`,
y arreglos de lint en 10 archivos de `tools/`.

**Por qué.** Se pidió definir el entorno con Hatch y documentarlo.

**Arquitectura.** ✅ Cumple. **El gate sigue sin depender de Hatch** (D-046): `./gradlew check`
corre `python3 -m unittest` a secas, para que un clone limpio se verifique solo. Hatch es la
capa de desarrollo.

**Medido.**

- **El builder es independiente de la versión de Python.** Los 35 tests pasan idénticos bajo
  Python 3.9 (Unicode 13.0) y 3.14 (Unicode 16.0), y `norm()` da salida byte a byte igual —
  incluido `ab\u0870cd` → `ab cd`, el caso exacto que divergía antes de fijar el repertorio.
  Esto cierra una incógnita que quedó abierta en la evaluación inicial: **Python 3.9 EOL no es
  una restricción para correr el builder**, solo para regenerar el repertorio.
- El guardián de `gen_repertoire.py` funciona: bajo Python 3.14 se niega con exit 1 y explica
  por qué.
- **Primera corrida de ruff: 89 hallazgos.** 69 eran una sola regla estilística (`UP031`,
  f-strings en vez de `%`). Se desactivó con ese número como razón (D-049): un linter que grita
  69 veces por una preferencia se apaga entero. Los 20 restantes se arreglaron, salvo dos
  `noqa` con su motivo escrito en el código.
- El formateador movió **8 líneas en 3 archivos**: el código ya estaba cerca de su estilo, así
  que no hubo reformateo masivo de código que funciona.

---

## 2026-09-17 — Fases de optimización; el emulador parte la clase de bug en dos

**Qué.** Se agregó `docs/roadmap.md` §Optimización con cinco fases (O-1 a O-5), el `benchmark`
skill, tres filas de decisión sobre rendimiento y batería, y tres entradas de referencia de
fuente primaria.

**Áreas.** `docs/roadmap.md`, `docs/decisions.md`, `docs/references.md`, `CLAUDE.md`,
`app/CLAUDE.md`, `.claude/skills/benchmark/`.

**Por qué.** Se pidió que la app sea eficiente, rápida y que no gaste batería, y se informó que
hay Android Studio con emulador y acceso a un reloj físico.

**Arquitectura.** ✅ Cumple. No se tocó código: las dos correcciones que aparecieron son
decisiones abiertas, no cambios aplicados.

**Medido.** Nada todavía, y ese es justamente el punto: **cero de los cuatro presupuestos de
rendimiento tiene una medición detrás**. O-1 existe para arreglar eso antes que nada.

Dos hallazgos contra fuente primaria, los dos heredados del template y ninguno decidido:

- **R8 está desactivado** en release, y la guía oficial de Wear OS lo nombra como una de las dos
  herramientas de rendimiento más efectivas, junto con baseline profiles.
- **La complication refresca cada hora**; la guía oficial pide 2 horas o más, o desactivar el
  refresco.

Tercero: **Battery Historian ya no se mantiene** — es lo que recomienda casi toda la guía de
terceros, así que sin registrarlo cada sesión futura lo iba a redescubrir.

El acceso a un emulador y a un reloj parte la clase de bug que el repo no podía observar: el
emulador cierra **correctitud** (trae el ICU y el SQLite de su nivel de API), el reloj físico
cierra **rendimiento y batería**. Usar el emulador para medir rendimiento sería peor que no
medir, porque da un número que parece real.

---

## 2026-09-17 — Bootstrap del sistema de instrucciones

**Qué.** Se creó el sistema completo de instrucciones para trabajo asistido por agentes:
`CLAUDE.md` raíz más tres anidados, cinco skills, `docs/decisions.md` con 41 filas,
`docs/references.md`, `docs/roadmap.md`, `docs/architecture.md`, `.claude/settings.json`,
`.editorconfig`, y `tools/audit_dictionary.py` cableado al gate.

**Áreas.** Raíz, `.claude/`, `docs/`, `tools/`, `dict-core/CLAUDE.md`, `tools/CLAUDE.md`,
`app/CLAUDE.md`.

**Por qué.** Pedido explícito, siguiendo el método de `docs/agents/` — entonces un solo archivo,
`bootstrap-prompt.md`, reemplazado el 2026-09-17 por el set `prompt-*.md` (ver esa entrada). El repo
no tenía ninguna instrucción de agente: cada sesión re-derivaba las mismas restricciones y
re-abría las mismas preguntas cerradas.

**Arquitectura.** ✅ Cumple. No se cambió código de producto: solo dependencias, la auditoría y
su cableado.

Se agregó además un hook `PostToolUse` que corre los vectores compartidos cuando se edita
`TextNormalizer.kt` o `normalize.py`, silencioso en éxito y bloqueante (exit 2) en fallo. Es la
única regla que se automatizó: el gate completo tarda demasiado para correr en cada edición, y un
hook lento se termina desactivando.

**Medido.**
- El hook se verificó introduciendo una divergencia real (`ß → sz` solo en Python): detecta y
  nombra el caso exacto, `norm('Straße')` dio `'strasze'` en vez de `'strasse'`.
- La auditoría **falló en su primera corrida**, con 4 fallas reales: tres punteros a un
  changelog que todavía no existía y uno a `TextNormalizer.kt`, una abreviación
  con puntos suspensivos que a un humano le parece correcta y es un puntero muerto.
- **14 de 41 decisiones no tienen enforcer** y se pueden romper en silencio. Casi todas son de
  plataforma Wear OS, cuyo código todavía no existe.
- Gate: ~1m26s en frío, ~40s templado.

---

## 2026-09-17 — Dependencias a stable; se elimina play-services-wearable

**Qué.** Bump de tiles 1.5.0→1.6.2, protolayout 1.3.0→1.4.2, wear compose 1.5.6→1.6.2,
complications 1.2.1→1.3.0, activity-compose 1.8.0→1.13.0, compose-bom 2025.12.00→2026.09.00,
guava 33.2.1→33.7.1. Se reservan en el catálogo las versiones de `:dict-data`.

**Áreas.** `gradle/libs.versions.toml`, `app/build.gradle.kts`.

**Por qué.** Se pidió confirmar qué dependencias se adaptan mejor al proyecto. Versiones
verificadas contra Google Maven y Maven Central, no contra memoria.

**Arquitectura.** ✅ Cumple. Todo stable: `sqlite 2.8.0-alpha01` y `work 2.12.0-rc01` existen y
se descartaron por estar en el camino crítico (D-032).

**Medido.** `play-services-wearable` estaba declarado desde el template con **cero usos** en el
código. El bump de wear compose era el único con riesgo real —`MainActivity` usa APIs
recientes— y compiló sin cambios. Gate en 42s.

---

## 2026-09-17 — Repertorio Unicode fijado; `:dict-core` portable

**Qué.** La clasificación de code points pasó de `Character.getType`/`unicodedata.category` a una
tabla propia generada. Toda API de JVM se movió a `PlatformJvm.kt`, con `ArchitectureTest` que
lo hace verificable. `NORM_VERSION` 1→2.

**Áreas.** `dict-core/`, `tools/unicode/`, `tools/packbuilder/normalize.py`.

**Por qué.** Al investigar viabilidad de KMP apareció que `java.text.Normalizer` es solo JVM. Eso
llevó a revisar el caso propio, donde el problema **ya existía sin KMP de por medio**.

**Arquitectura.** ✅ Cumple. Es el mecanismo que sostiene el invariante central.

**Medido.**
- **14.773 code points** se clasificaban distinto entre Python 3.9 (Unicode 13) y Java 26
  (Unicode 16), todos asignados después de Unicode 13.
- **0 diferencias** en NFD y en `lowercase()` sobre los 133.730 code points del repertorio: es
  la medición que permite seguir delegando esas dos operaciones en la plataforma.
- La tabla son 1.010 rangos, 6,2 KB.
