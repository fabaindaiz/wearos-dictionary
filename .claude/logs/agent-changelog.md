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

**Por qué.** Pedido explícito, siguiendo el método de `docs/agents/bootstrap-prompt.md`. El repo
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
