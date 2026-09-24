# Trabajo planificado

Ideas aceptadas, todavía no construidas. **No es una promesa ni una orden de trabajo**: es
dónde va a chocar cada una, escrito ahora que está claro.

**Es un ledger, no una lista de deseos**, y por eso se escribe también cuando el trabajo
*termina*. Cada entrada lleva uno de cinco estados y **ninguna entrada se borra**: una idea
borrada vuelve el trimestre que viene sin memoria de por qué se fue.

| Estado | Qué tiene que decir entonces la entrada |
|---|---|
| **Planificado** | con qué choca, qué hay a favor, qué hay que decidir antes |
| **A medias** | cuál mitad, y si lo que falta es mecanismo o contenido — los deciden personas distintas |
| **Hecho** | en qué quedó, la medición que lo cerró, y **qué sigue faltando** |
| **Cerrado por medición** | el número que lo retiró |
| **Bloqueado afuera** | qué lo reabriría, concretamente: un issue upstream, un dispositivo, una licencia |

El último es el que más falta en un roadmap y el que más tiempo ahorra: sin él, un agente que
nunca vio los tres rechazos anteriores vuelve a proponer lo mismo, de buena fe.

⚠️ **A NEW item carries a minted id after a ` · ` in its heading** (d-a2f271-5ab6e3):

```sh
python3 .agents/tools/bundle.py id i "<the heading>"   # -> i-a2f271-<hash>
```

The **97 headings written without one stay as they are** — renumbering rewrites every citation.
What the id closes is that two sessions on different branches cannot see each other's next number.
`check_record_ids_are_minted` holds the edge: the count of heading-without-an-id may drop, never
rise.

## Dónde estamos

*Actualizado: 2026-09-22.*

✅ **El sistema de instrucciones está en bundle v20 —método v24— y el triage llega hasta ahí**
(2026-09-24). El método vive en `.agents/` como bundle —7 prompts, las notas de conocimiento,
`tracking/`, `bundle.py`— y `docs/agents/` se retiró (D-221). **Antes de configurar, actualizar o
correr cualquier prompt de `.agents/method/`, se corre la skill `state-review` §0**, que dice cuál
corresponde —o si falta terminar el anterior—. **Este repo no puede editar el método**: lo que le
falta va como una línea en `.agents/tracking/candidates.md`. Y sus heurísticas son parte del
trabajo: `CLAUDE.md` manda a `.agents/knowledge/INDEX.md` antes de decidir y antes de cerrar
(D-268).

✅ **Los packs se reconstruyeron el 2026-09-22 y ya lo reflejan todo.** Lo que el rebuild trajo,
medido sobre los packs reales:

| | antes | ahora |
|---|---|---|
| `cas` devuelve | `casar, casa, casta…` | **`casa`, caso, casi, casas** |
| `lib` devuelve | `libar, libro, libre…` | **`libro`, libre, libra** |
| `house` (inverso) | `solar, alojar, albergar` | **`casa`, hogar, ama** |
| `water` (inverso) | `gastar, regar, resbalar` | **`agua`, llave, canal** |
| irregulares (`went`, `children`) | no llegaban | **`ir, andar` · `hijo, niño`** |
| rho(`rank`, frecuencia real) | **−0,250** | **−0,787** |
| filas de `trans` en el bilingüe | 206.727 | **474.849** |
| ⚠️ *y después*, al volverse bidireccional (D-196) | 474.849 | **0** — las palabras inglesas son **entradas**, no claves |

Los cinco packs pasan `verify_pack.py` entero y declaran `rank_basis=frequency-zipf-v1`.

⚠️ **El 2026-09-21 el formato del pack cambió más que en ningún otro día.** Lo que cambió:

- Las traducciones entran al payload por **dos canales**: `T` por acepción y `W` de la palabra.
  Antes ninguno se llenaba.
- `trans` deja de estar vacía en los packs monolingües: **se busca en el otro idioma** sin pack
  bilingüe instalado.
- Toda acepción es direccionable por `(idioma, palabra, acepción)` con un código que **no nombra
  un pack**, y `verify_pack.py` lo exige.
- El pack inglés y el bilingüe también traducen; el bilingüe llena por fin su canal de lectura.
- Las flexiones del idioma destino cierran la dirección inversa.

**Hecho y verificado en escritorio.** El motor de búsqueda (`:dict-core`, **127 tests**) y el
pipeline de packs (`tools/`, **521 tests**) están completos y en el gate, junto con los **439 JVM
de `:app`** y **38 checks** de auditoría estructural — **1125 tests en total**. Los **46
instrumentados** (34 de `:dict-data` y 7 de `:app`) el gate no los corre: necesitan dispositivo, y
son los únicos que cierran las asunciones sobre Android. El pack de juguete pasa todas las
invariantes de `verify_pack.py`, incluido que el prefijo use `COVERING INDEX`.

⚠️ **Los 41 instrumentados corrieron el 2026-09-20 a 234 dp y en pantalla redonda**, sobre el
emulador de D-150: **0 fallas**. Es la primera vez que corren en la geometría del reloj — hasta
entonces el AVD por defecto los corría a 192 dp y en una pantalla cuadrada.

**Los packs, reconstruidos el 2026-09-21 con `schema_version` 4** (D-195 a D-198):

| | entradas | tamaño | fuentes |
|---|---|---|---|
| `es-def-wikc-tat-freq-wn-wd` | **152.281** | 73,6 MiB | Wikcionario · Tatoeba · OpenSubtitles · MCR/WordNet · Wikidata |
| `en-def-wikt-freq-wn` | **956.150** | 306,8 MiB | Wiktionary · OpenSubtitles · Open English WordNet |
| **`es-tr-enwikt-freq`** | **209.484** | **63,4 MiB** | Wiktionary en inglés · OpenSubtitles. **Bidireccional**: 123.979 entradas españolas + 85.505 inglesas |
| `…-core` (×2) | 7.349 y 16.652 | 5,0 y 12,1 MiB | derivados de los completos, declaran `tier=core` |

Cada uno declara **una licencia por fuente** en `meta.sources` (D-138) y la app las muestra todas.
⚠️ Los dos están **muy por encima** del presupuesto blando de 50 MB (D-028): ver §O-3 y
§Dividir los packs grandes.

**Hecho y verificado en emulador.** `:dict-data` existe con `PackFile` —abre read-only y valida
`schema_version`, `norm_version`, `payload_codec` y el sha256 del diccionario— y tres suites
instrumentadas. Los **31 tests corrieron y pasan** en dos niveles de API:
**Wear OS 4 / API 33** (Android 13, el minSdk) y **Wear OS 7.0 / API 37.0** (Android 17, el
compileSdk), ambos arm64 headless. Una falla real apareció en la primera corrida y era una
expectativa mal escrita, no un bug del producto: ver el changelog de esa fecha.

`SqlitePackSource` implementa la cascada de cinco consultas, más la resolución en lote que hace
tocables las palabras de una glosa (D-094) y la cabecera barata que alimenta la palabra del día
(D-097). Sus
expectativas se verificaron contra el contenido real del pack de juguete antes de escribirlas —
una estaba mal y se corrigió sin gastar un emulador.

**El reloj físico ya existe, y usarlo cambió cosas.** Un Galaxy Watch (SM-L715F, API 37) con el
APK instalado y el diccionario español adentro. En minutos destapó tres defectos que 65 tests no
veían, y una medición que contradice al repo: **la pantalla son 234 dp, no 192**. Lo que sigue
sin medirse es **rendimiento y batería** (D-043), y un crash de *Ver más* que no se reprodujo.

**Hay dos diccionarios y el MVP los usa.** Español (**114.619 entradas, 65,0 MiB**) e inglés
(**794.355 entradas, 255,7 MiB**), con selector de idioma. ⚠️ **Los packs que están hoy en el
reloj son los anteriores a D-116** y la app los rechaza: el `payload_codec` pasó a `deflate-v2`
(D-119). Hay que reconstruirlos y volver a instalarlos antes de probar en dispositivo. **El APK sólo lleva el pack de
demostración de 53 KB** (D-081): los diccionarios reales entran por `tools/devpack.py`
—atómico y con sha256 de los dos lados (D-082)— a `filesDir/packs/`, que es donde también
escribirá el instalador. Eso cerró D-071 antes de tiempo, y lo adelantó el número del inglés.

**La app hace lo que un diccionario tiene que hacer.** Busca por voz y teclado, muestra la
entrada con sus acepciones, deja **saltar de una palabra a otra tocándola** (D-094), guarda
favoritas, trae **una palabra del día por idioma cargado** (D-097), y permite **gestionar los
diccionarios**: ver cuánto ocupan, cuál está en uso y borrarlos (D-103, D-104). El APK lleva
`versionCode` monótono desde que se descubrió que el instalador rechaza un downgrade (D-095).

**Lo que eso NO cerró, y conviene no confundir.** El pack real se abrió en un **emulador**, no en
un reloj: no hay un solo número de arranque, latencia ni batería (D-043). Los instrumentados
siguen corriendo contra el **toy pack de 53 KB** — aunque el plan de consulta se midió en los dos
tamaños y **es el mismo**, así que ese riesgo concreto está descartado.

**El gate cubre la lógica de `:app`**: **85 tests JVM** sobre la concurrencia de la búsqueda, la
instalación del pack, el historial, las favoritas, los ajustes, la política de la palabra del día
y el borrado de un pack. La UI la cubren **47 instrumentados**, que **no entran al gate**: hacen
falta espresso 3.7.0 y un dispositivo, porque la 3.5.0 que venía por transitividad no inyecta
input en API 37 (D-093).

**Sin empezar.** **`SearchRepository` no existe**, así que la app abre **un** pack y la capa que
fusiona varios está entera por escribir. Los dos tiles ya son de diccionario y la complication se apagó (D-106 a D-109). Los umbrales del nivel tolerante (D-052) **ya se pueden** ajustar contra el pack real;
siguen sin ajustarse.

**El invariante central** —que el builder y la app calculen la misma clave— está sostenido por
los vectores compartidos, y se verificó que detecta divergencia real: encontró un desfase de
14.773 code points entre Python y la JVM (D-003). Medido después: el builder da resultados
idénticos bajo Python 3.9 (Unicode 13) y 3.14 (Unicode 16). **En Android ya no es ASSUMPTION**:
`NormalizationOnDeviceTest` pasa en API 33 y en API 37.0, los dos extremos de ICU que el
proyecto soporta.

## Las tres cosas que desbloquean todo lo demás

*Actualizado el 2026-09-21: las tres originales están hechas y quedan abajo como historia. Éstas
son las de ahora.*

| # | Qué | Por qué primero | Bloquea a |
|---|---|---|---|
| 1 | **Medir sobre un build de RELEASE en el reloj** | ✅ La sesión del 2026-09-21 (tarde) ya midió sobre el reloj, pero con un APK `DEBUGGABLE` que **ART nunca compiló** (`status=run-from-apk`): 2203 ms de arranque, 1401 tras forzar `verify`, contra los **500 ms del build con R8**. Todo número de rendimiento y batería que tenemos sale de ese build de debug, así que son **techos, no costes**. El redibujo en reposo quedó **confirmado en 4,0 fps** con ventana limpia, pero ya **no es el mayor gasto**: la pantalla son 33,2 mAh y toda nuestra CPU 6,05 | el número real de O-1 · verificar R8 en dispositivo · cualquier decisión de optimización |
| 2 | **§Alinear acepciones entre fuentes** | El problema abierto más caro, con **cuatro caras**: bloquea 20.644 aportes de contenido, el espacio de equivalencias entre packs, y la pregunta de qué se muestra cuando dos packs tienen la misma palabra — hoy **se elige uno y el otro se esconde** | sinónimos/ejemplos por acepción de fuentes externas · composición entre packs |
| 3 | ~~**El instalador**~~ **Dónde se hostea el catálogo** | ✅ El mecanismo funciona y está verificado en el emulador (D-214): descarga, reanuda, comprueba dos hashes, instala y recarga. Lo que falta es **de producto**: `BuildConfig.CATALOG_URL` apunta a un servidor de desarrollo, y sólo `debug` habla por `http://`. ~~Falta también cancelar una descarga~~ — **built**: `DownloadPackWorker.cancel` frees the `.part` and is wired from the manager, with a test. Verified 2026-09-24 by reading the code, not by remembering it | distribuir la app a cualquiera |

---

### Las tres originales, hechas

Cada una era barata y habilitaba varias de las de abajo.

| # | Qué | Por qué primero | Bloquea a |
|---|---|---|---|
| 1 | ~~**Correr los tests instrumentados** en el emulador~~ **HECHO 2026-09-17** | Convirtió el comportamiento en Android de ASSUMPTION a verificado: 20/20 en API 33 y API 37.0 | ~~Todo lo que toque el reloj~~ desbloqueado |
| 2 | ~~**Decidir el join key entre packs**~~ **HECHO 2026-09-17** | Se midió y se decidió: `entry.uid`, columna aparte sin índice (D-055 a D-058). Ya está en el formato | ~~Composición, y el pack real~~ desbloqueado |
| 3 | ~~**Construir el pack real y pesarlo**~~ **HECHO 2026-09-17** | Se midió: **146.194 entradas, 72,2 MB**, un 44 % por encima del presupuesto blando de D-028. Y leerlo destapó el problema de orden de abajo | ~~O-3 y el alcance del producto~~ desbloqueados; O-2 y O-4 siguen esperando el reloj |

---

## 🔁 Reconstruir los packs — TAREA PERMANENTE, nunca se cierra

**Estado.** **Siempre abierta, y a propósito.** No es un ítem que se termina: es la pregunta que
toda sesión que toque el builder tiene que volver a contestar — *¿lo que acabo de cambiar llega al
reloj sin reconstruir?* Si la respuesta es no, la deuda se anota **acá abajo** y no se olvida.

⚠️ **Existe porque el desfase es invisible.** Un pack viejo abre, busca y funciona; lo único que
pasa es que le falta lo que se construyó después. No hay error, no hay log, y `verify_pack.py` lo
da por bueno mientras sus invariantes se cumplan. La única forma de saberlo es comparar lo que el
builder hace **hoy** contra lo que los `.db` publicados dicen que son.

### Cómo se contesta, en dos comandos

```sh
python3 tools/build_packs.py <raíz-de-datos> --dry-run     # qué se construiría, en orden
python3 tools/packbuilder/verify_pack.py --como-la-app <raíz>/dist/*.db
```

⚠️ **El `--dry-run` sobre-reporta a propósito y hay que saberlo**: lista `es-main` aunque el
español completo (73,6 MB) esté por debajo del máximo del rango de `main` (150 MB) y la corrida
real lo salte. El guard está en `_niveles` y mira el tamaño del `full`, que **en un dry-run todavía
no existe**. El plan que se lee no es exactamente el plan que corre.

### La deuda de hoy — NINGUNA, reconstruido el 2026-09-22 a las 17:22

**Los seis packs de `dist/` salieron del builder de esta fecha** y pasan `verify_pack.py` completo
y `--como-la-app`. Las seis deudas que esta tabla listaba —la identidad `<idioma>-<nivel>`, los
tres tamaños, el rango medido, el calendario inglés, la cita del ejemplo y el deduplicado de
listas— **las cierra este build**.

| pack | tamaño | entradas | cobertura del corpus | fracción de lemas |
|---|---|---|---|---|
| `en-full` | 329,6 MB | 956.150 | — | — |
| `en-main` | 108,0 MB | 138.083 | 96,63 % | 16,40 % |
| `en-core` | 42,9 MB | 36.952 | 96,60 % | 4,39 % |
| `es-full` | 77,6 MB | 152.281 | — | — |
| `es-core` | 50,8 MB | 39.021 | 78,87 % | 28,18 % |
| `es-en` | 67,2 MB | 209.484 | — | — |

`es-main` no existe y eso es correcto: el español completo son 77,6 MB, por debajo del mínimo de
ese rango (D-220). Los packs anteriores quedaron en `build/*.PREV.db`, movidos y no borrados.

⚠️ **Lo que el rebuild encontró, y el gate no podía ver**, está en el changelog del 2026-09-22:
`--sumar` recibía un pack donde su lector abre el dump —tercera instancia de la misma clase—, un
intermedio que no consumía nadie, y la lista de cobertura exigiéndole a dos packs promesas que no
hicieron. Los tres tienen test ahora.

### Lo que sigue desfasado después del rebuild

| Qué | Por qué importa |
|---|---|
| ⚠️ **Ningún pack trae la PRONUNCIACIÓN** | The `I` channel landed on 2026-09-24 (d-a2f271-13da99) and `dist/` predates it: `verify_pack.py` reads **0.0 %** on `es-full` against **100.0 %** available in the dump. The card draws nothing, which is correct and invisible — the exact shape this section exists to catch. It needs `es-full` rebuilt from `es.jsonl`; the toy pack already carries one entry, so the gate covers the channel |
| **Los packs no están en el reloj** | `dist/` está listo; `devpack.py` no se corrió. Hasta que se corra, el reloj sigue con los packs del 2026-09-21 |
| ~~**El fixture del índice del catálogo**~~ ✅ **cerrado el 2026-09-23** | `app/src/test/resources/catalog-index-fixture.json` fija los `pack_id` viejos. Es **deliberado** —incluye un pack schema 3 que el `dist/` nuevo ya no puede producir, y regenerarlo debilitaría el test—. Lo que faltaba era la retractación: `tools/CLAUDE.md` afirmaba que el fixture describe el directorio real y ya lo dice al revés |
| **`Tuesday` en el bilingüe** | Ver §La traducción glosada no produce entrada inversa. Necesita una medición antes que código |
| ~~**Los núcleos se llaman `Español (full) (core)`**~~ ✅ **cerrado el 2026-09-23** | El builder le pegaba `(core)` al nombre del completo sin sacarle `(full)`. Arreglado en `build.name_with_tier` y los dos núcleos regenerados — **15 s cada uno**, derivan del completo y no necesitan los dumps. Salieron **byte a byte del mismo tamaño**: cambió el nombre y nada más. Verificado en pantalla: `English (core)`. (D-230) |
| ~~**Los conteos de `-core` del changelog no reconcilian**~~ ✅ **explicado el 2026-09-23** | No era una contradicción: **la CLI de `build_core.py` llamaba «entradas» a `len(vocabulario)`**, que es el conjunto de palabras elegidas, no las filas de `entry`. Medido sobre `es-core`: **39.021 = `count(DISTINCT norm)`** (el número del changelog), 41.219 lemas distintos y **48.292 filas**, que es lo que declara `meta.entry_count`. Los tres reconcilian. El rótulo dice ahora **«palabras»**, que es lo que evita que vuelva a costar una sesión |
| ⚠️ **El pack inglés completo mezcla español en su `description`** | Visto en la pantalla de atribución del emulador (2026-09-23): *«English definitions from Wiktionary. Includes synonyms, antonyms, related words and the source of each quoted example. **Resultados ordenados por frecuencia de uso real.** With WordNet synonyms…»*. Es **contenido del pack**, escrito por el builder, así que se arregla en el próximo rebuild del completo — no del núcleo, que lo hereda |
| ⚠️ **Un APK incremental carga ~25 MB de relleno muerto** | Medido el 2026-09-23 sobre el mismo código: build **incremental 135.881.265 bytes**, build **limpia 111.020.805**. La suma de las entradas comprimidas es 110,83 MB en las dos, así que los **24,9 MB de diferencia son padding**, no contenido. El APK va al reloj por adb y ya pasa los 75 MB en que la conexión inalámbrica se cortó una vez, así que **el que se sube se arma con `:app:clean` antes**. Sin explicar: qué lo introduce dentro de AGP |

### ✅ Lo que había que arreglar antes de reconstruir — HECHO el 2026-09-22

**`build_packs.py` le pasaba a dos lectores un dump que no saben leer**, y el pipeline no se
había corrido nunca de punta a punta. Corregido, y ahora lo fija `test_build_packs`, que
comprueba **los nombres además del orden**: cada paso recibe el archivo que su lector parsea, y
ningún paso puede pasar un Turtle ni nada que se llame `dbnary`.

Verificado ejecutando los dos lectores contra los dumps corregidos: `wordnet.spanish` devuelve
**48.402 lemas con sinónimos** en 1,1 s, y `wikidata.records` entrega `Record`s en streaming.

Lo que estaba mal:

| Paso | Le pasa | El lector espera | Qué hace |
|---|---|---|---|
| `es-wd` | `es_dbnary_ontolex.ttl.bz2` | `wikidata-lexemes.json.bz2` (`json.loads` por línea) | `JSONDecodeError` en la primera línea |
| `es-full --tesauro` | `es_dbnary_enhancement.ttl.bz2` | `wn-data-spa.tab` (OMW, tres columnas, **sin bz2**) | `UnicodeDecodeError` |

Los dos dumps correctos **están bajados** (`wikidata-lexemes.json.bz2`, `wn-data-spa.tab`), así
que son dos rutas. DBnary está en `docs/fuentes.md` como **rechazada y medida** —misma fuente,
la mitad del rendimiento—, así que esto no es un cambio de fuente: es un descuido al escribir el
pipeline, que **nunca se corrió de punta a punta** (su propio changelog lo dice: `plan()` entra al
gate, correrlo necesita los dumps).

Falla ruidosa, no silenciosa — lo cual fue una suerte: el rebuild se habría cortado en el paso 2
en vez de publicar un pack peor. La lección que queda es la otra: `test_build_packs.py` fijaba la
**forma** del plan —el orden, que es lo que «no da error» al saltárselo— y no sus **nombres**, así
que el orden correcto sobre los archivos equivocados pasaba el gate.

### Qué la vuelve a abrir

Cualquier cosa que toque: `payload.py`, `sources/*.py`, `build.py`, `build_pack.py`,
`build_core.py`, o una decisión que cambie qué entra al pack. Si tocaste uno de esos, **esta
sección se actualiza en el mismo commit**.

## Datos

### Construir el pack real de español monolingüe

**Estado.** **Hecho** (2026-09-17). Existe `tools/packbuilder/sources/kaikki.py` y
`build_pack.py`; el pack pasa `verify_pack.py` entero.

**En qué quedó.** **146.194 entradas, 72.212.480 bytes (68,9 MiB)**, desde el dump de kaikki.org
del 2026-09-15. Build: 53,9 s y 214 MB de RSS. El desglose por objeto vive en
`docs/formato-pack.md` §Presupuestos, que es el documento que posee ese número.

**La medición que lo cerró, y lo que mató.** D-028 pedía ≤ 50 MB: **se pasa en un 44 %**. El
46,3 % del pack es la tabla `form` (33,4 MB, 1.487.695 filas, 93,5 % conjugaciones de verbos),
y eso no es grasa: es el precio de que "corriendo" encuentre "correr". La poda ya descarta el
82,33 % de los registros del dump (D-065).

**Qué sigue faltando.**
- **El pack no está en ningún reloj ni emulador.** Pesarlo no es abrirlo en Android: los 22
  tests instrumentados siguen corriendo contra el toy pack de 53 KB. Una regresión de plan de
  consulta a 146.194 entradas no la ve nadie todavía.
- **El orden de los resultados no sirve**, y es la entrada nueva de acá abajo.
- Los umbrales del nivel tolerante (D-052) ya se **pueden** ajustar con este pack; no se hizo.
- `pos` se guarda con el código de kaikki (`noun`, `verb`, `adj`). Mostrarlo en español es una
  decisión de UI que nadie tomó.
- 3.137 pares `(headword, pos)` aparecen más de una vez —`hacer` cinco veces, por etimología—.
  Son entradas legítimas y `uid` las separa bien, pero la lista las muestra repetidas.

### El orden de la lista de resultados

**Estado.** **Hecho** (2026-09-17). Era lo que destapó el pack real y lo que hacía inusable el MVP.

⚠️ **Actualización 2026-09-20 (D-142).** Seguía mal y se vio midiendo: escribir *cas* devolvía `castigar, castreño, cascar` y **no devolvía `casa`**. La causa es que `rank` es un proxy de **riqueza de página**, no de frecuencia, y una página larga gana. Se puso delante una **banda de cobertura** —cuánto del lema se escribió— que no mira ningún dato del pack: *cas* → `casa, casar, cascar`; *per* → `perro, persa`; *arb* → `árbol`. **No reemplaza al rank, lo acota**: dentro de una banda sigue mandando el pack. Lo que falta sigue siendo una señal de frecuencia real, que esta fuente no tiene.

**En qué quedó.** El prefijo ordena `(coincidencia exacta, rank, norm)` y la cascada deduplica
por `(headword, pos)` al final, con over-fetch ×3 (D-068, D-069).

**La medición que lo cerró.** Antes, con el orden alfabético:

| Escribís | Empiezan así | Posición de la palabra obvia |
|---|---|---|
| `per` | 782 | **`perro`: 619** |
| `sal` | 496 | `salir`: 206 |
| `dec` | 286 | `decir`: 154 |
| `com` | 738 | `comer`: 131 |

La lista muestra 30: cuatro de esas cuatro búsquedas no contenían la palabra buscada. Después,
`per` devuelve **perder, permitir, perseguir, permanecer, perro** — verificado en el emulador
contra el pack real, no solo en escritorio.

**Qué costó.** El covering index sigue sirviendo el rango pero ya **no** el orden: SQLite agrega
`USE TEMP B-TREE`. Medido en escritorio, **1,8 ms p95** en el peor caso (una letra, 22.358
filas) contra 0,01 ms, con un presupuesto de 20 ms. **El número de reloj no existe** y es
exactamente lo que O-1 existe para dar: si en un reloj físico ese peor caso se acerca a 20 ms,
esta decisión se revisa.

**Qué sigue faltando.**
- El proxy de `rank` favorece a los verbos, porque las formas flexionadas pesan en el puntaje y
  un verbo trae hasta 222. Se ve: `cas` devuelve *castigar, cascar, casar* antes que `casa`
  (posición 6). Bajar el peso de las formas cuesta un rebuild de 54 s y no se probó.
- El prefijo de **una letra** sigue siendo malo: `a` devuelve *a, A, -a, a-, á*. Son entradas
  legítimas (prefijos, sufijos, la letra) pero nadie busca eso.
- La deduplicación es por `(headword, pos)`, así que `perro` sale dos veces si es sustantivo y
  adjetivo. Se respetó a propósito: hay un test que exige que los homógrafos de distinto `pos`
  se distingan. Si eso se revisa, se revisa ese test primero. En español son **3.024 pares**, y
  `hacer` aparece cinco veces por etimología.

**⚠️ Esto pesa más desde el 2026-09-19, y en dos direcciones opuestas.**

*A favor:* la poda de nombres propios (D-116) sacó ruido real del orden. Eran **4.267 `norm` del
inglés donde el topónimo le ganaba en rank a la palabra común** —buscar `freedom` devolvía
primero un *census-designated place* del condado de Santa Cruz— y esos casos desaparecieron por
construcción. En español se fueron los 29.599 `norm` que sólo resolvían a un apellido.

*En contra:* los sinónimos entraron a `fts_def` (D-118), así que **la búsqueda por texto libre
importa más que antes** — y es justamente la que no tiene orden propio. Buscar *bobo* devuelve
**42 entradas ordenadas por el mismo proxy de `rank`**, con las útiles abajo. Antes eso era una
función secundaria; ahora es media razón de que los sinónimos existan, y su valor queda
capado por el ranking.

O sea que la deuda no cambió de tamaño pero **sí de prioridad**: el recorte de ruido ya se
cobró, y lo que queda —recalibrar el proxy, o cruzar un corpus de frecuencias real (D-067)— es
ahora lo que más separa al diccionario de ser bueno. Sigue esperando el número de O-1 para saber
cuánto presupuesto de latencia hay para gastar.

### El rank mayúsculo: la lista de frecuencia lava los nombres propios — UNA CLASE HECHA

**Estado.** **La clase con regla, construida el 2026-09-22; las otras dos, medidas y abiertas.** Es lo que disparó D-216 y **no** lo que D-216
arregla: la cita hace legible a la entrada, no la baja de posición.

**El caso.** `Thomas` son dos entradas y la que molesta no es nombre propio:

```
id     headword  pos   rank
20551  Thomas    noun   179     ← "An infidel or doubter." — el epónimo de «doubting Thomas»
20552  Thomas    name  1179     ← 179 + CASTIGO_NOMBRE_PROPIO, correctamente castigada
```

⚠️ **La poda de nombres propios no toca a la primera**: `kaikki` sólo mira `pos == "name"`. El
179 sale de que `frequency.key()` baja a minúsculas (D-186, correcto: en español el acento
distingue palabras) y **la lista de OpenSubtitles ya viene toda en minúsculas** —0 líneas con una
sola mayúscula, verificado—, así que `Thomas` cobra las **27.442** apariciones del nombre de pila
en subtítulos de cine.

**La escala, sobre `en-def-wikt.db`.** 6.462 entradas con mayúscula y `pos != name` están en la
banda de frecuencia real `[0,500)`, que tiene 55.903 → **el 11,5 % de la banda «más frecuente»**.
Se parten en tres clases, y **no son el mismo problema**:

| clase | cuántas | qué son | ¿regla limpia? |
|---|---|---|---|
| tiene homógrafo en minúscula | **4.246** | siglas y formas honoríficas: `TO`, `OF`, `IS`, `WE`, `ME`, `HE`, `NO`, `ARE`, `BE`, `CAN` | ✅ **sí**: la frecuencia es del lema en minúscula, que ya tiene su propia entrada. Cero falsos positivos en las 25 más visibles, leídas |
| sin homógrafo, con hermano `pos=name` | **1.333** | `Thomas` 179, `Richard` 168 (*«A turd.»*), `Eddie` 172, `George` 150, `Jesus` 142 — **y también** `Christmas` 150, `American` 153, `Chinese` 174, `British` 177 | ❌ **no**: la misma señal cubre la basura y el vocabulario legítimo |
| ninguno de los dos | **883** | `DID` 52, `TOLD` 89, `Mrs.` 133, `Sunday` 177, `Friday` 178, `Rules` 161 | ❌ mezclado igual |

⚠️ **Una idea muerta por medición, y vale escribirla.** El truco de D-137 —*una palabra escrita
en minúscula alguna vez en el corpus es común*, lo que salvó a `nadal`— **no transfiere al
inglés**. Sobre las 41.512 frases inglesas de Tatoeba CC0: `american` 361 apariciones / **0 en
minúscula**; `british` 91/0; `chinese` 78/0; `christmas` 44/0; `ok` 52/0. El inglés capitaliza
gentilicios, feriados y siglas **por regla**, así que la señal es constante y no separa nada.

✅ **Construida la primera clase** (2026-09-22): *un lema con mayúscula cuyo homógrafo en minúscula
también es entrada del pack no toma la frecuencia de ese homógrafo*. Son las **4.246**, el 66 %
del problema, y la regla es inequívoca — esa frecuencia es del lema en minúscula, que ya tiene su
propia entrada para reclamarla. La señal sale de la pasada 1, que ya recorre el dump entero.

⚠️ **Un homógrafo que sólo existe como página `form-of` NO cuenta**, y esa mitad también está
fijada por un test: no es una entrada (D-065), así que `RAN` no debe perder su frecuencia por
`ran`, que es flexión de `run`.

⚠️ **Y eso deja un sub-caso afuera, que lo encontró leer el pack construido.** `CATS` (rank 201)
y `FIRES` (213) siguen en la banda: sus minúsculas existen **sólo como páginas de forma** —plural
de `cat`, de `fire`— así que la regla no las toca, y sin embargo es el mismo lavado. El argumento
que las excluye tiene su contra: la frecuencia de `cats` **sí** tiene dueño en minúscula, sólo
que es el lema `cat` y no esa grafía.

Medido sobre la muestra 1/20: de las 76 que siguen en la banda, **5** son este caso (`CATS`,
`FIRES`, `LAS`, `Lionesses`, `MODS`) — unas **100 en el pack completo**, contra las 4.246 ya
corregidas. Las otras 71 no tienen minúscula de ninguna forma y son la clase irreducible
(`Alaskan`, `Augustine`, `Baldwin`). **No se cambió**: extenderlo es revertir una decisión
documentada y con test, por un 2 % más de cobertura.

❌ **Las otras dos clases siguen abiertas, y a propósito.** No tienen regla disponible con los
datos en disco: mezclan `Thomas` 179 y `Richard` 168 con `Christmas` 150, `American` 153 y
`British` 177, y D-141 dice que equivocarse en una decisión de contenido no deja rastro. Lo que
las desbloquearía es una señal que hoy no existe — un corpus inglés que distinga mayúsculas.
El TSV con las 6.462 filas se genera con una consulta sobre el pack; está en el registro de la
sesión del 2026-09-22.

### El orden de las acepciones dentro de una ficha — VERIFICADO 2026-09-21, y **bloqueado por falta de evidencia**

**Estado.** ⚠️ **Seguro de hacer, y sin motivo medido para hacerlo.** Pedido: verificar que
reordenar no genere efectos adversos y dejar todo listo **sin aplicar nada**. Las dos mitades
tienen respuesta, y son opuestas.

#### Lo que SÍ está verificado: reordenar no rompe nada

Sonda sobre el pack español real, **13.072 entradas multi-acepción** de palabras con señal de
frecuencia, barajando las acepciones y recomputando:

| | resultado |
|---|---|
| Códigos de acepción que cambiaron | **0 de 13.072** |
| Adjuntos huérfanos (ejemplos, traducciones, sinónimos que pierden su acepción) | **0** |

⚠️ **La primera fila es la que el usuario intuía y ahora está medida**: `senseCode(uid, gloss)`
no toma la posición, así que un enlace escrito contra una acepción **sobrevive a cualquier
reordenamiento**. Es exactamente lo que D-180 compró y no se había ejercitado.

La segunda es la que faltaba comprobar: cada `Sense` es **autocontenida** después de `parse`.
Los tags `T`/`E`/`Y`/`A` se cuelgan de `senses.lastOrNull()` **mientras se parsea**, no después,
así que reordenar la lista ya construida no los desprende.

#### Lo que hay que tocar cuando se aplique — dos cosas, las dos chicas

| | qué | por qué |
|---|---|---|
| 1 | **La glosa que cachea el tile** (`SearchViewModel`, `senses.firstOrNull()`) | **El 65 % de las entradas multi cambiaría de primera acepción**, así que el tile mostraría una glosa distinta de la que la ficha muestra arriba. Es la clase de desfase entre superficies que ya apareció tres veces (D-200, D-203) |
| 2 | **El número que se dibuja** (`number = index + 1`) | Reordenar renumera, y eso rompe *«la acepción 2 de banco»* entre versiones. **Se puede conservar el número de la fuente sin tocar el pack**: `PayloadCodec.parse` construye la lista en orden, así que el índice original es la posición al parsear — un campo más en `Sense` con default, y `Sense` ya se construye con seis argumentos posicionales |

**Lo que NO hay que tocar, verificado**: `sense_index` sólo se usa en el builder y **nunca como
posición** (D-117); `verify_pack.py` lee `senses[0]` sólo para elegir una palabra con la que
probar FTS, y cualquiera sirve.

#### Lo que BLOQUEA: no hay señal que se sepa mejor que el orden de la fuente

Las señales disponibles en el payload, sobre **47.001 acepciones** de entradas multi comunes:

| señal | cobertura |
|---|---|
| sinónimos | 26,0 % |
| traducciones | 18,9 % |
| ejemplos | 15,2 % |
| antónimos | 3,5 % |
| relacionadas | **0,0 %** |

Con una fórmula de riqueza sobre eso: **65,5 %** de las entradas discriminan, 6,7 % empatan y
**27,8 % no tienen ninguna señal** —ahí el orden seguiría siendo el del volcado—. De las que
discriminan, el **45 %** cambiaría de primera acepción.

⚠️ **Pero mirar el resultado lo desmiente.** Las que más cambian son palabras funcionales, y el
reordenamiento propuesto no es mejor:

```
de     'Indica pertenencia.'            -> 'Indica el contenido de algo.'
a      'Indica complemento directo…'    -> 'Indica complemento destinativo.'
para   'Indica el objetivo o finalidad' -> 'Indica la dirección o destino…'
```

La riqueza **discrimina mecánicamente sin evidencia de que discrimine útilmente**, y el
Wikcionario ordena sus acepciones a propósito: el orden de la fuente es la mejor evidencia que
hay hasta que aparezca otra.

⚠️ **La hipótesis acotada también murió midiendo.** «Hundir sólo las primeras acepciones que
*remiten* en vez de definir» da **1,1 %**, y leyendo los casos el regex tiene falsos positivos
—`modo → "Forma de hacerse…"`, `calor → "Forma de energía…"` son definiciones de verdad— así que
el número real está **por debajo del 1 %**. No paga el riesgo.

#### Qué lo desbloquearía

Una señal que se sepa correlacionada con lo que el lector busca. Dos candidatas, las dos caras:

1. **Frecuencia por acepción** — qué acepción de `banco` se usa más. Necesita un corpus anotado
   por sentido, que hoy no tenemos (§Alinear acepciones entre fuentes). ⚠️ **No obliga a decidir
   ahora**: entraría como una etiqueta nueva del payload, y las desconocidas se ignoran por
   diseño (D-119).
2. **Lo que el usuario abre** — qué acepción se lee de verdad. Es telemetría, y este proyecto es
   **100 % offline**: está descartado por construcción, no por costo.

**Conclusión**: el formato está listo y verificado; lo que falta es una razón. Se deja
**preparado y sin aplicar**, que es exactamente lo que se pidió.

### El plan original, conservado — PLANIFICADO 2026-09-21

**Estado.** **Diseñado, no construido.** Pedido explícitamente como planificación: *«también me
interesa explorar el reordenado de acepciones pero esto solo quiero planificarlo y dejarlo en
roadmap»*.

**El problema.** Las acepciones se muestran **en el orden del volcado**, que es el del Wiktionary,
y la ficha pliega a partir de la cuarta (`VISIBLE_SENSES = 3`). Así que en toda entrada con más de
tres acepciones, **cuáles se ven las decide una fuente que no ordenó por uso**.

**Cuánto importa, medido sobre el pack español real (152.281 entradas):**

| Universo | n | acepciones de media | con más de 1 | **con más de 3 (se pliegan)** |
|---|---|---|---|---|
| Todas las entradas | 152.281 | 1,38 | 18,6 % | 4,2 % |
| Con señal de frecuencia (`rank < 500`) | 28.575 | 2,19 | 45,7 % | 15,4 % |
| **Las 2.000 más comunes** | 2.000 | **3,58** | 60,4 % | **32,9 %** |

⚠️ **El promedio por entrada engaña y por eso está la tercera fila.** Sobre el catálogo entero el
problema parece marginal —4,2 %—; sobre lo que alguien busca de verdad, **un tercio de las
entradas tiene acepciones escondidas** detrás de un `Ver más` que hay que tocar. Es el mismo error
de muestreo que ya costó una sesión: comparar el pack real contra una muestra de 1/12 y leer el
resultado como una regresión.

**Lo que NO hace falta, y es la conclusión que lo hace barato.** Las señales candidatas ya están
todas en el payload, porque el builder ya las escribe por acepción:

- `E` — si la fuente la ilustró con un ejemplo. Una acepción con ejemplo es una acepción en uso;
- `T` — si tiene traducción propia (D-117: sólo entra la que la fuente atribuyó);
- `Y` / `A` / `R` — sinónimos, antónimos, relacionadas;
- el largo de la glosa, y el orden de la fuente como prior débil.

**Así que esto es un cambio puro de `:app` y no toca el formato del pack ni obliga a reconstruir
nada.** Eso es lo que permite aplazarlo sin costo, que es justo lo que no pasaba con
`meta.rank_basis` — ese sí había que decidirlo antes del build o no existía.

**La única señal que SÍ exigiría tocar el pack** es una frecuencia **por acepción** —qué acepción
de `banco` se usa más—, que necesita un corpus anotado por sentido. Hoy no tenemos fuente para eso
(§Alinear acepciones entre fuentes), y si alguna vez la hay, entra como un campo nuevo del payload:
una etiqueta nueva no sube `CODEC_ID` porque las desconocidas se ignoran por diseño (D-119). **O
sea: tampoco esa obliga a decidir ahora.**

**Riesgo que hay que medir antes de construirlo, no después.** Reordenar acepciones rompe la
correspondencia entre el número que se dibuja (`1.`, `2.`) y el orden de la fuente. Eso no es
cosmético: `sense_code` **no** depende de la posición (D-117), así que los enlaces aguantan; pero
una captura, una cita o la memoria del usuario —«la acepción 2 de *banco*»— dejan de valer entre
versiones del pack. La salida probable es **ordenar sin renumerar**, mostrando el número de la
fuente.

**Cómo se verificaría.** Igual que el orden de resultados: una sonda descartable sobre el pack
real con lemas comunes y varias acepciones (`banco`, `carta`, `pie`, `tiempo`), impresa y leída a
ojo, **antes** de escribir la regla. Sin eso se estaría ratificando el orden que el código
produzca.


### La cita del ejemplo en español — MEDIDO, no construido

**Estado.** **Planificado.** El mecanismo existe y está construido para el inglés (D-216); lo que
falta en español es **una línea**: declarar `separador_de_cita` en su `Perfil`.

No se construyó en el mismo pase porque **el Wikcionario sirve el `ref` con otra forma** y
aplicarle el separador inglés produciría basura:

| | inglés | español |
|---|---|---|
| forma | `1897, Richard Marsh, The Beetle:` — año primero, comas | `Miguel Nicolau. Iniciación a la Teología. Página 85. 1984.` — autor primero, puntos |
| ejemplos con `ref` | 75,5 % | **68,4 %** |
| `ref` completo | 119 B | **92 B** |
| recortado a dos campos | 31 B | **50 B** |

Con una regla de puntos que **fusione iniciales** (`J. R. R. Tolkien` es un campo, no cuatro) el
recorte acierta en 7 de 8 muestras leídas a mano. Lo que falta es medirla como se midió la
inglesa —sus propios casos en `test_source_kaikki`, y **leer el pack construido**— antes de
encenderla.

⚠️ **El español no trae `type`**: 0 de 874 ejemplos muestreados. Ese campo es sólo del Wiktionary
inglés, así que ahí lo único que dice que una frase fue citada de un texto es que traiga `ref`.

Costo esperado: el pack español tiene ejemplo en el **11,4 %** de sus entradas contra el 33,3 %
del inglés, así que el +2,52 % medido en inglés debería quedar muy por debajo. **Sin medir.**

### La calidad del contenido del pack español

**Estado.** **En gran parte hecho** (2026-09-20). De una sola fuente se pasó a **cuatro**, y lo
que queda ya no es contenido sino tamaño.

| Fuente | Qué aportó | Estado |
|---|---|---|
| Wikcionario (base) | definiciones, formas, sinónimos, antónimos, relacionadas | ✅ y **agotada**, medido |
| Tatoeba | **6.499** entradas ganan frase de uso | ✅ `--frases` (D-137) |
| WordNet / MCR | **+3.801** entradas ganan sinónimos | ✅ `--tesauro` (D-144) |
| enwiktionary §Spanish | 307 entradas ganan ejemplo | ✅ `--ejemplos` (D-135), rinde 21× menos |

⚠️ **Los números de este párrafo estaban viejos y se corrigieron el 2026-09-22.** El pack real es
hoy **152.281 entradas y 73,6 MB** —lo medido sobre `dist/es-def-wikc.db`—, no las 114.619 que
esta sección declaraba: la diferencia son D-141 (ninguna fuente pierde palabras) y D-145 (la
fusión de Wikidata). Y **la tensión que aquí se describía ya no existe**: el presupuesto blando
de 50 MB lo retiró D-207, porque se usaba sólo para declararse roto y porque medía lo
equivocado — el reloj reporta 9,0 GiB libres. Lo que sí se vigila está en §O-3.

**Lo que ya se hizo, para no repetirlo.** Salieron los nombres propios (22,1 % de las entradas,
26.265 de ellas definiendo sólo *"Apellido."*), entraron 71.609 sinónimos por acepción en
26.369 entradas, después los antónimos (D-126), y se limpiaron las 665 etiquetas de mantenimiento
del wiki. En 2026-09-20 entraron las **palabras relacionadas** (D-132): hiperónimos, hipónimos y
`related`, que eran el último campo aprovechable que la fuente traía y el builder tiraba —
**5.395 entradas en español (4,7 %) y 90.310 en inglés (11,4 %)**, por +112 KB y +1,4 MB. El pack
quedó entonces en **114.619 entradas y 68,3 MB** — *entonces*, porque D-141 y D-145 lo llevaron
después a las 152.281 de hoy.

⚠️ **Y quedó medido que esta fuente ya no tiene mucho más que dar.** Las entradas flacas —una
acepción, sin ejemplo— son 80.744, el 70,4 % del pack. Barriendo 174.395 registros vivos, sólo el
**25,4 %** de las flacas traía algún campo sin usar, y **el 20,3 % eran sinónimos que ya
entraban**. Lo nuevo sumó 4,8 % + 1,7 % + 0,8 %. La conclusión: lo que queda de mejora **no está
en el dump del Wikcionario español**, está en la segunda fuente (los ejemplos) o en el orden de
resultados.

**Lo que queda, en orden de valor por esfuerzo:**

| Qué | Tamaño del problema | Qué costaría |
|---|---|---|
| ~~**Ejemplos desde enwiktionary §Spanish**~~ (D-122) → **construido y medido** (D-135) | El 5.307 era el cruce por lema. Con la regla de D-132 —no inventar atribución— son **307 entradas, +8 KB**. Está hecho y detrás de `--ejemplos` | Ya no cuesta código. Cuesta **atribución doble permanente por un 0,28 %**, y eso lo decide el usuario |
| **Entradas de una sola palabra** | **28,0 % del pack**; los sinónimos sólo alcanzaron al **6,8 %** de ellas | No se arregla desde esta fuente, y D-132 lo confirmó barriendo los campos sin usar: el Wikcionario no tiene más texto que dar |
| **Subíndices de referencia cruzada** (*"semejanza a un guanaco₁"*) | 2.204 glosas | Un `str.translate` en `_gloss()`. Barato, pero **pierde información**: el subíndice dice *qué acepción* |
| **Pares `(headword, pos)` duplicados** | 3.024 | Es de la capa de consulta, no del pack. Ver §El orden de la lista de resultados |

**Los ejemplos: hecho, medido, y la medición es lo que hay que leer** (2026-09-20, D-135). El
embudo, de la estimación al número real:

| Regla | Entradas |
|---|---|
| cruce por lema, como lo estimaba este documento | ~5.300 |
| + nuestra entrada tiene **una** acepción | 2.172 |
| + allá también tiene una, y el mismo `pos` | 510 |
| + trae `english` (confirma que el texto es el español) | 326 |
| **construido de verdad** | **307, +8 KB** |

La diferencia entre 5.307 y 307 **no es que la fuente tenga menos**: es que la mayoría de los
cruces exigen colgar el ejemplo de una acepción que nadie dice cuál es. Cada filtro tiene su
caso medido, y están en D-135.

Está implementado en `sources/enwikt_examples.py` detrás de `--ejemplos`. **No es el default**, y
el motivo no es técnico: dos fuentes obligan a nombrar a las dos en cada pack, para siempre. Es
una obligación permanente por un 0,28 %, y pagarla es una decisión de producto.

**El texto que sigue es la evaluación previa, y se conserva porque la parte de licencias sigue
valiendo.** El dataset está bajado
(`wearos-dictionary-data/es-en-wikt.jsonl`, 1,04 GB) y los ejemplos **están en español**, con la
traducción inglesa en un campo `english` aparte que se ignora. La licencia tampoco es el
problema: las dos fuentes son CC BY-SA 4.0, y lo que cambia es que la atribución tiene que
nombrar a las dos.

Lo que falta diseñar es **la atribución por acepción**. Cruzar por lema pone *"Lo acordaron por
unanimidad"* en la acepción equivocada de *acordar*, y eso es **contenido incorrecto que parece
correcto** — peor que *falta una palabra*, porque el lector no tiene forma de sospecharlo. Las
dos fuentes numeran las acepciones distinto, así que no hay una clave obvia: es el trabajo real
del ítem, no el merge.

**Con qué choca.** Con D-034 si alguien se tienta con traer también las glosas: son
**traducciones al inglés**, no definiciones, y eso es un pack bilingüe. Y con el presupuesto de
D-028, aunque poco: los ejemplos pagan en el payload y en `fts_def`, como pasó con los sinónimos
—estimados en 0,30 MB, medidos en 0,89.

### Alinear acepciones entre fuentes: lo que bloquea tres cosas a la vez

**Estado.** **Medido, sin decidir** (actualizado 2026-09-21). Es el mismo problema con **cuatro**
caras distintas, y por eso conviene tenerlo en un solo lugar. Reconocido explícitamente como
problema propio: *«esto es un problema separado por sí mismo que deberíamos incluir en el roadmap
y estudiarlo»*.

**El problema.** Una fuente externa da sinónimos, antónimos o ejemplos **por acepción** — pero por
**su** acepción, no por la nuestra. Nada en el dato dice cuál de nuestras acepciones corresponde.

**La cuarta cara, que apareció al diseñar el pack núcleo: qué se muestra cuando dos packs tienen
la misma palabra.** Hoy la respuesta honesta es **ninguna de las dos cosas que uno esperaría**: no
se duplican las acepciones ni se complementan — **se elige un pack y el otro se esconde**. En la
lista de resultados `casa · sust.` de dos fuentes produce una fila; al abrirla se ven las
acepciones de **un** pack, y las del otro no aparecen en ningún lado.

Eso es correcto hoy porque la alternativa es peor: sin saber qué acepción de acá es cuál de allá,
una ficha combinada muestra dos veces la misma idea escrita distinto, que en 234 dp es más confuso
que una sola fuente. **Pero es una pérdida real y no está contabilizada** en los 20.644 aportes de
abajo: ahí se cuenta lo que se descarta **al construir**, y esto es lo que se descarta **al
mostrar**, cada vez que alguien abre una palabra que dos packs tienen. Con los dos packs españoles
instalados son **8.595 entradas** donde pasa.

⚠️ **Para núcleo y completo esto no aplica**: mismos `uid`, mismas acepciones, mismos bytes. La
cara nueva sólo existe entre packs de **fuentes distintas**.

**Lo que cuesta hoy**, contando lo que se descarta por no poder alinear:

| Aporte | Se pierde | Por qué |
|---|---|---|
| Tesauro de WordNet, español | **3.511 entradas** | Tienen aporte y varias acepciones |
| Tesauro de WordNet, inglés | **17.133 entradas** | Igual |
| Ejemplos de enwiktionary | ~4.900 (de 5.307 a 307) | D-135 |
| Definiciones de Wikidata | **9.181** | El lema ya existía; la segunda definición se tira (D-145) |

**Lo que ya funciona y conviene no confundir**: los sinónimos **del wiki** sí son por acepción y
llegan a entradas con varias — el Wikcionario declara `sense_index` y el Wiktionary los anida
(D-117, D-124). Medido: **5.762 entradas españolas y 11.694 inglesas tienen sinónimos o antónimos
repartidos en dos o más acepciones**. La estructura del payload nunca fue el problema.

**Por qué no se resolvió por las malas.** Colgar el aporte de la primera acepción acierta a veces
y falla otras, **sin dejar rastro**: `bizarro` acepción 2 («lúcido, airoso») con los sinónimos de
la 1 («arrojado, gallardo») se lee perfectamente plausible. Es el error que D-117 existe para
impedir y el más caro que tiene este repo.

**Caminos posibles, ninguno medido:**

1. **Comparar la glosa de la fuente con las nuestras** (palabras de contenido compartidas). Barato
   y sucio; hay que medir cuánto acierta antes de creerle.
2. **Usar `pos` + orden de acepciones.** Las fuentes no numeran igual, así que probablemente falle.
3. **Pedir coincidencia fuerte y aceptar poco**: sólo alinear cuando la evidencia sea alta. Sube la
   precisión y baja el alcance — que es exactamente lo que ya se hizo con la regla de una acepción,
   sólo que con un umbral en vez de un absoluto.

⚠️ **Cualquiera de los tres introduce error silencioso si se calibra mal**, y el error silencioso
es el que este repo trata como inaceptable. **Decisión de producto**: aceptar una tasa de
desalineación a cambio de 20.644 aportes, o seguir perdiéndolos.

**Con qué más choca.** Es el mismo bloqueo de §Composición entre packs: sumar la definición de
Wikidata a una entrada que ya existe exige saber **a qué acepción** pertenece.

### Composición entre packs

**Estado.** **La capa existe; el join, no** (2026-09-20). `SearchRepository` se construyó (D-136)
y con él la **convivencia**: dos packs base del mismo idioma se instalan y se consultan juntos, y
la ganancia es la unión de sus lemas. Eso ya corre — `es-def-wikc-tat` y `es-def-wd` conviven
instalados.

⚠️ **Lo que falta es el join por `uid`, y ahora hay número**: entre los dos packs de español
**8.595 `uid` coinciden** (D-139). Son las entradas donde un pack podría sumarle campos al otro.
Lo que sigue bloqueando es la **granularidad**: `uid` es por entrada y un sinónimo es por acepción.

⚠️ Y hay una lección que costó una reconstrucción: **la convención de `sense_key` tiene que ser la
misma en todos los packs**. El pack de Wikidata usaba el id del lexema —una identidad mejor que la
de kaikki— y con eso los `uid` **no unían con nada**. `verify_pack.py` lo agarró.

### Reorientar el esquema a monolingüe

**Estado.** ✅ **La decisión que faltaba quedó tomada el 2026-09-21, y por medición.**

D-034 fijó que el primer pack es monolingüe con definiciones. Esta sección estaba bloqueada
esperando decidir **qué pasa con `trans`**, sobre esta premisa:

> *«`trans`, que en un pack monolingüe se definió como "las palabras que aparecen en la glosa" —
> que es exactamente lo que `fts_def` ya indexa, mejor. En monolingüe esa tabla es espacio
> gastado dos veces.»*

⚠️ **La premisa era correcta para lo que `trans` contenía entonces, y hoy dejó de serlo.** Desde
que el pack español lee las traducciones del Wikcionario, `trans` no guarda palabras de la glosa:
guarda **los términos del otro idioma por los que se llega a la entrada**. Medido sobre un pack
de muestra, pasó de **0 a 3.257 filas**, y buscar `build` en el pack **monolingüe** devuelve
`construir, edificar`.

**Entonces `trans` no se vuelve opcional ni desaparece: se queda, y cambia de significado.** Es
el **canal de búsqueda entre idiomas**, y es lo que permite que un pack monolingüe se busque en
el idioma del lector sin instalar nada más. `fts_def` no lo duplica — indexa el texto de la
definición, que está en español.

**Lo que sigue pendiente de esta sección es sólo mecanismo**: que el código refleje que un pack
monolingüe es el caso normal. Y sigue en pie que **hay que conservar un toy bilingüe mínimo**, o
la búsqueda inversa y el tope `TRANS_MAX_PER_KEY` quedan sin test.

---

## Traducciones

*Todo lo de traducciones vive acá. Antes estaba partido en dos bloques distantes del documento y
cada uno en orden cronológico inverso, así que se leía al revés de como se entiende.*

**Lo construido el 2026-09-21** (decisiones D-178 a D-184): las traducciones entran al payload por
**dos canales** —`T` por acepción, `W` de la palabra—, toda acepción es direccionable por
`(idioma, palabra, acepción)` con un código que **no nombra un pack**, `trans` deja de estar vacía
en los packs monolingües, y las flexiones del idioma destino cierran la dirección inversa.

✅ **Construido el 2026-09-22.** `dist/es-en.db` es el pack con todo eso adentro.

### ⚠️ La traducción glosada no produce entrada inversa — ABIERTO, medido el 2026-09-22

Lo encontró la lista de cobertura durante el rebuild: **`Tuesday` no es entrada del pack
bilingüe**, y `Monday` y `Wednesday` sí. No es una regla que se lleve los días: es que la fuente
escribe la traducción de `martes` **glosada dentro de la acepción** en vez de como término limpio.

```
lunes   S | Monday                                              T | Monday      ← sale la inversa
martes  S | Tuesday (the third day of the week in many…)        (sin T)         ← no sale
```

⚠️ **Y la palabra es frecuente, así que no es un caso de cola**: `tuesday` aparece **14.074**
veces en `freq-en-opensubs.txt`. Buscar *tuesday* con sólo el bilingüe instalado no devuelve nada.

⚠️ **Lo que NO se sabe es cuántas son**, y eso es lo primero que hay que medir antes de escribir
una regla: cuántas acepciones traen una traducción con paréntesis explicativo, y si recortar en el
primer `(` acierta o parte términos que legítimamente lo llevan. Es exactamente el modo de falla
que `kaikki.py:6-8` prohíbe atacar con heurísticas sobre la prosa, así que necesita el número
antes que el código.

El chequeo de cobertura **lo informa y no reprueba** al bilingüe: el lado inglés de `es-en` existe
para la dirección inversa (D-196), no para ser un diccionario de inglés, y exigirle la lista
completa mide una promesa que ese pack no hizo.

| | |
|---|---|
| **Qué falta** | §Lo que falta para cerrar el punto |
| **Qué se construyó y con qué número** | las cinco secciones ✅ |
| **Qué se decidió y qué quedó abierto** | §Revisión de las decisiones · §Qué falta del pack de idiomas |
| **Diseño sin construir** | §Naming a sense · §Enforcing the contract |
| **Las mediciones que fundamentan todo lo anterior** | las cinco últimas |


### 📋 Lo que falta para cerrar el punto de traducciones — corte 2026-09-21

Ordenado por lo que cuesta contra lo que cierra, y verificado contra el código, no contra la
memoria.

#### Barato y visible (horas)

| # | qué | por qué importa |
|---|---|---|
| 1 | **`wordActions` deja de filtrar por `kind == PackKind.BILINGUAL`** | Verificado que sigue así (`WordActions.kt:63`). El pack español **ya traduce** y la acción «ver traducción» **no aparece nunca**. Es el bug más visible de todos |
| 2 | **`bilingual.py` llena `T`/`W`** con las claves crudas que ya calcula | Verificado: **0 referencias** a esos campos. El pack con más traducciones (206.727 filas de `trans`) tiene el canal de lectura **vacío**, y su docstring admite que tira la forma de display |
| ~~3~~ | ~~El APK deja de empaquetar el toy~~ | ⚠️ **Ya estaba hecho y el corte lo listó mal.** `bundlePacks` prefiere los núcleos desde D-176 y cae al toy sólo si faltan; verificado corriendo la tarea: en `assets/` quedan `en-core.db` (12,5 MB) y `es-core.db` (5,0 MB). El error fue leer una línea suelta (`val toy = ...`) en vez del cuerpo de la tarea |

#### Mediano (un día)

| # | qué | precio medido |
|---|---|---|
| 4 | Resolver los enlaces en la ficha | El mapa de enlaces tiene que llevar `packId`: toca el límite de D-080 |
| 5 | Los **10.438 pares** de `en.jsonl` a `trans` del pack inglés | Hace que `perro` encuentre `dog`. No sirven para `T`: **0 `sense_index` de 9.987** |
| 6 | ~~Índice de flexiones inglesas~~ **hecho (D-184)**, y desde D-196 vive en el `form` de la entrada inglesa: `went` es flexión de `go` | **1,84 MB (+3,7 %)**; sube la inversa de 78,1 % a 98,9 % |
| 7 | **Reconstruir los packs reales** | ~1 hora. Decidido: **un solo build al final**. Sus 350 excepciones de direccionabilidad se cierran ahí |

#### Sin decidir — son decisiones, no trabajo

| # | qué | el número que lo decide |
|---|---|---|
| 8 | ¿Plegar el código de acepción? | +7,80 puntos (34,40 % → 42,21 %) entre diccionarios distintos, pero es una segunda regla versionada y obliga a plegar también la clave de fusión |
| 9 | ¿`kind` describe definiciones o capacidades? | Hoy un pack `monolingual` es buscable en inglés y muestra traducciones. Cambiar el filtro de `wordActions` (#1) o agregar un tercer `kind` |
| 10 | 🔭 **El espacio de equivalencias entre packs** | Cerraría el **65,60 %** que dos diccionarios del mismo idioma no comparten. Marcado por el usuario como **deseable**. Bloqueado por §Alinear acepciones entre fuentes: falta **quién las declara** |

#### El slot de acepción sigue vacío, y no es pereza

Para escribir una referencia a una acepción hace falta el `uid` y la glosa **del pack destino**, y
el Wikcionario español no los tiene. Sólo lo puede llenar un pack **derivado** del de definiciones
— la misma razón por la que `build_core.py` deriva en vez de reconstruir (D-175). **El mecanismo
quedó listo antes que el dato que lo va a usar**, y eso es correcto: al revés habría que adivinar
el formato.


### ✅ Traducciones por acepción en el pack español — CONSTRUIDO 2026-09-21

Lo que las secciones de abajo midieron, construido. `kaikki.py` lee la tabla de traducciones que
el dump siempre trajo y el pipeline nunca leyó, y la emite al tag `T` **por acepción**;
`SenseBlock` la dibuja como cuarta `TermList`.

**Verificado leyendo un pack real** (muestra 1/12, 12.158 entradas), no contando filas:

```
echar   1. Impulsar o empujar algo hacia algún lugar     ->  throw, cast
        3. Meter o poner algo en un lugar                ->  pour
        4. Expulsar algo o a alguien violentamente       ->  kick out, let out
        5. Remover a alguien de su posición              ->  boot
sentir  1. Percibir por cualquiera de los sentidos       ->  feel
        3. Percibir por medio del oído                   ->  hear
        5. Mostrar congoja o arrepentimiento             ->  be sorry, regret
```

Las acepciones sin traducción atribuida muestran **nada**, que es la regla de D-117 funcionando.

| medición | |
|---|---|
| entradas con traducción, en la muestra | **1.052 de 12.158 — 8,7 %** (coincide con el 16,6 % con traducción × 51 % atribuible medido sobre el dump) |
| items emitidos | 1.921 |
| **costo en bytes** | **+36 KB sobre 6,16 MB — +0,60 %**, contra un build gemelo del mismo dump y la misma muestra sin la función |
| `verify_pack.py` | pasa entero |

**Tres decisiones que el código fija, y cada una tiene su motivo escrito en el archivo:**

- ⚠️ **Se filtra por idioma.** El dump trae la tabla entera: `en` son **34.710 de 281.022** items.
  Sin filtro una entrada española mostraría su traducción al polaco.
- ⚠️ **Se expanden los rangos** (`1-2`, `1, 4`), que valen **+13,2 puntos** de cobertura. Tocar
  `_by_sense_index`, que es compartido, era el riesgo — **medido y nulo**: sinónimos y antónimos
  son **100 % índices simples**, 0 compuestos de 86.418 y 7.542.
- ⚠️ **Lo que no trae índice se descarta** (el 37,7 % de las traducciones). Es dato real y su
  lugar honesto es el canal de nivel de entrada que todavía no existe.

**Y una puerta que parecía cerrada con un número, y se reabrió el mismo día:** acá decía que el
pack inglés **no podía** declarar `translations_to`, porque sus traducciones al español traen el
**texto** de la acepción pero **0 `sense_index`** de 9.987.

⚠️ **El número es correcto y la conclusión era demasiado fuerte.** Eso cierra el canal `T`, que
exige atribución por acepción — **no el canal `W`, que existe justamente para lo no atribuible**.
Cuando `W` se construyó, esas 9.987 encontraron dónde vivir, y de paso llenan `trans`. El pack
inglés declara `translations_to = "es"` desde el #5.

#### ⚠️ La oportunidad que esto deja servida, y es justo la próxima pregunta

`meta.translations_to = "en"` es un **canal de lectura**: el pack es `monolingual`, declara un solo idioma en `meta.langs`
sigue vacío y **`trans` sigue pesando 4 KB**, o sea vacía. Escribir `casa → house` también en
`trans` haría que **el pack de definiciones se busque por palabra inglesa** — `house` encontraría
`casa` sin que haya un pack bilingüe instalado.

Eso es exactamente el puente que necesita la búsqueda multipack entre idiomas, y no está hecho.
Lo que hay que decidir antes: si un pack que se busca en dos idiomas sigue siendo `monolingual`,
y qué le pasa al peldaño `byTranslation` cuando **varios** packs lo contestan.


### ✅ El segundo canal y la referencia `(pack, palabra, acepción)` — CONSTRUIDO 2026-09-21

Cierra los pedidos **1, 2, 7 y 8** de la auditoría de abajo, y deja definido el mecanismo de
enlace para conversar alternativas.

#### El reparto de la referencia, que es el diseño entero

| parte | dónde vive | por qué |
|---|---|---|
| **pack** | `meta.translations_pack = "en-def-wikt"` — **una vez** | Es constante para todas las traducciones. Por item costaría ~280 KB de una sola cadena. Se nombra por **`pack_id` y no por archivo**: es la identidad del diccionario (D-138) y sobrevive a los rebuilds, que es lo que `data_version` no hace |
| **palabra** | el valor del item (`T` o `W`) | Ya estaba ahí: es el término que se muestra |
| **acepción** | sufijo opcional del item, `término\x1fref` | Sólo existe cuando la fuente la supo |

⚠️ **De ese reparto sale la propiedad que importa: una traducción sin acepción YA es un enlace a
la palabra, y no cuesta un solo byte extra.** El caso común es el gratis. Y sin
`translations_pack` declarado no hay a dónde ir, así que el término se muestra **sin pintar** —
que es D-084 y lo que se pidió.

#### Los dos canales

- **`T`**, dentro de una acepción: lo que la fuente atribuyó.
- **`W`**, de la entrada: lo que no pudo atribuir — **el 37,7 % del dato**, que hasta hoy se
  tiraba porque no tenía dónde vivir.

Aditivo: **no sube `CODEC_ID`** (D-119). Un lector viejo cae `W` por su `else` y muestra la
entrada sin la lista.

#### Medido sobre un pack real (muestra 1/12)

| | antes | ahora |
|---|---|---|
| items de traducción | 1.921 | **2.921** (+52 %) |
| entradas con algo que mostrar | 1.052 | **1.893** |
| tabla `trans` (búsqueda) | **0 filas** | **3.257 filas** |

```
construir   ->  build, construct, install, establish, implement, set, act, do   (antes: nada)
comprender  ->  understand, comprehend, realize, appreciate, apprehend, catch, see
atrapar     ->  capture, catch, grapple, captivate, grab, seize, trap, apprehend

buscar `build` en el pack MONOLINGÜE  ->  construir, edificar
```

⚠️ **Y eso último es el pedido 2 cerrado**: el pack de definiciones ahora **se busca en inglés**
sin que haya un pack bilingüe instalado. Sigue declarándose `monolingual` y con un solo idioma en `meta.langs`,
porque sus definiciones siguen siendo en español: lo que cambió es por dónde se llega a ellas.

#### ⚠️ Un agujero de seguridad que encontró su propio test

La primera versión juntaba la referencia en una cadena y dejaba que `render` **adivinara**
partiéndola por el separador. Con eso, un término de la fuente que **contuviera** el separador
—`ho\x1fuse`— se leía como el término `ho` apuntando a la acepción `use`: **la fuente podía
forjar una referencia a otra acepción.**

Arreglado haciéndolo explícito por tipo: `make_ref` devuelve una **tupla**, una cadena es siempre
un término y se limpia entera, y una referencia sólo la puede construir quien llama. No hay nada
que adivinar.

#### Lo que queda para conversar

- **La acepción todavía no se llena**: el slot está definido y sin usar, porque el Wikcionario
  español no nombra acepciones del pack inglés. Llenarlo pide el digest de glosa, y eso pide un
  pack **derivado** (ver §Naming a sense from another pack).
- **Nadie resuelve el enlace todavía**: `EntryScreen` dibuja los términos sin pintar. Resolverlos
  pide que el mapa de enlaces lleve `packId`, que es tocar el límite de D-080.


### ✅ El código de acepción: idioma y palabra, sin nombrar un pack — CONSTRUIDO 2026-09-21

Propuesta del usuario, y corrige la parte frágil del diseño anterior:

> *«en lugar de mostrar un pack, se debería mostrar un idioma, la palabra, y que el link a la
> acepción sea inequívoco y único para esa palabra, idioma y pack (core y completo aquí pueden
> repetir este código). Así si no está la acepción exacta pero sí la palabra, se puede
> referenciar a esta.»*

#### Por qué no hizo falta inventar nada

⚠️ **`entry.uid` ya lleva el idioma y la palabra adentro** — `stable_uid(lang, headword, pos,
sense_key)`. Así que el código es `sha256(uid ␟ NFC(glosa))[:12]` y **no nombra ningún pack**.

Las tres propiedades pedidas, verificadas:

| propiedad | verificación |
|---|---|
| **No nombra un pack** | por construcción: sólo entra `uid` y la glosa |
| **Núcleo y completo lo repiten** | **21.534 de 21.534 — 100,0 %** de los códigos del núcleo español son idénticos en el completo, porque `build_core.py` **copia** el uid (D-175) y conserva la glosa |
| **Degrada a la palabra** | el código es un **sufijo** del término, no lo reemplaza: sin acepción encontrada, el término sigue siendo un enlace |

Colisiones medidas sobre el pack entero: **22 de 210.249 (0,0105 %)**, y son glosas que el wiki
define dos veces — apuntan a dos acepciones de texto idéntico.

#### ⚠️ Sobre la glosa plegada, NO sobre `norm()`

> **Actualizado el mismo día**: acá decía *«sobre la glosa CRUDA en NFC»*. Sigue siendo cierto
> lo esencial —no pasa por `norm()`— pero desde la decisión #8 pasa por `fold_gloss`, que es un
> plegado ligero y propio. Ver §El plegado de glosa.

Es el precedente de D-055 aplicado tal cual — `stable_uid` ya lo decidió: *«así no depende de
NORM_VERSION, y subir las reglas de normalización no invalida los packs auxiliares»*. **Acá muerde
más fuerte**: un bump de `NORM_VERSION`, que D-005 permite en cualquier momento, cambiaría
**todos** los códigos y dejaría apuntando a la nada cada enlace de cada pack ya construido, sin
error y sin log. El primer intento sí pasaba por `norm()`; lo corrigió leer el docstring de
`stable_uid`.

#### `translations_pack` se elimina

Declaraba `en-def-wikt`. Con el núcleo inglés instalado y no el completo, **el enlace moría aunque
hubiera un diccionario inglés capaz de resolverlo**. Ahora sólo se declara `translations_to = "en"`
—el idioma— y lo resuelve cualquier pack instalado de ese idioma.

#### Es un segundo contrato entre los dos lenguajes, y tiene su guardrail

`payload.sense_code` ↔ `PayloadCodec.senseCode`. Si se separan, **los enlaces apuntan a la nada sin
excepción y sin log**, que es el modo de falla central del repo. Lo fija el mismo vector en los dos
lados —`sense_code(1, "casa")` = `8ec316909e48`— y el vector ya hizo su trabajo una vez: detectó el
cambio de `norm()` a NFC.

`toNfc` vive en `PlatformJvm.kt` por D-017, delegado a la plataforma como NFD por D-004.


### ✅ «Toda acepción direccionable, sin excepciones» — INVARIANTE CONSTRUIDO 2026-09-21

Pedido literal: *«toda palabra debería poder ser accesible mediante una tupla IDIOMA, PALABRA,
ACEPCIÓN sin excepciones»*. Se trató como invariante: medir, cerrar las excepciones, y que el
gate lo exija.

#### Las excepciones que había, medidas sobre los seis packs reales

| pack | acepciones | no direccionables | |
|---|---|---|---|
| `es-def-wikc` | 210.249 | 14 | 0,0067 % |
| `en-def-wikt` | 1.247.842 | 106 | 0,0085 % |
| `es-tr-enwikt` | 153.944 | **211** | **0,1371 %** |
| `es-core` | 21.534 | **0** | — |
| `en-core` | 65.042 | 14 | 0,0215 % |
| `es-def-wd` | 19.621 | 5 | 0,0255 % |

⚠️ **Ninguna es culpa del hash: los choques ENTRE entradas distintas son 0.** Todas son dos
acepciones de **la misma entrada** con la glosa idéntica — `y` → *and* cinco veces, `pound` →
*"Various non-English units of measure"* tres veces.

#### Se fusionan, no se descartan, y eso lo decidió una medición

De 12 grupos duplicados inspeccionados, **5 traían adjuntos distintos**: `them` repite *"Used as
the direct object of a verb"* con **ejemplos diferentes** (`She treated them.` / `Give it to
them.`), y `y` con `jamon y queso` / `setenta y seis`. **Descartar la copia habría perdido ese
dato en silencio.**

`payload.merge_duplicate_senses` funde por glosa conservando el orden y uniendo los adjuntos sin
duplicar valores. Vive en `render` porque es el **único** paso por el que pasan todos los packs:
puesto en `kaikki` habría que repetirlo en `oewn`, `wikidata` y `bilingual`, y la propiedad sería
cierta sólo en los packs cuyo autor se acordó.

#### El gate lo exige, y el check está probado contra un pack que lo viola

`verify_pack.py` recalcula los códigos de cada entrada muestreada y falla si dos coinciden.
Verificado **mutando un pack a propósito**:

```
la entrada correr tiene acepciones que comparten codigo: 2 acepciones, 1 codigos
```

#### ⚠️ Lo que el código NO consigue, medido

El pedido dice *«único para distintos diccionarios para poder compatibilizar esto»*. Medido:

| | coinciden |
|---|---|
| Wikcionario ↔ **núcleo** (derivado) | **21.534 / 21.534 — 100,0 %** |
| Wikcionario ↔ **Wikidata** (otra fuente, mismo idioma) | 6.748 / 19.616 — **34,40 %** |

**El hash sólo puentea redacciones idénticas.** Dos diccionarios que definen el mismo concepto con
otras palabras dan códigos distintos, y eso no tiene arreglo dentro del hash. Se ve leyendo los
fallos:

```
uid 2793751417803243523
   wikcionario: Condición o carácter de torpe.
   wikidata   : condición o carácter de torpe
```

✅ **Decidido y construido (2026-09-21): el plegado ligero entra.** Minúsculas, espacios
colapsados, puntuación final fuera — y **no** saca acentos, porque `publico` y `público` son
palabras distintas. Verificado sobre los packs reales: **34,40 % → 42,21 %**, con el 100,0 % del
derivado intacto.

Se aceptaron sus dos costos con los ojos abiertos:

- **Es una segunda regla versionada nuestra**, junto a NFC que es un estándar. Cambiarla invalida
  todos los enlaces escritos, así que la fija un vector en los dos lenguajes.
- **Se pliega también la clave de fusión**, en la misma función, o dos acepciones que difieren en
  un punto compartirían código sin fusionarse y una quedaría inalcanzable.

⚠️ **Y apareció una trampa entre lenguajes que no estaba en el precio**: en Python `\s` sobre
`str` es **Unicode** y en Java/Kotlin es **ASCII**, así que un espacio duro (U+00A0) se colapsaría
de un lado y del otro no — dos códigos distintos para la misma acepción, **sin error y sin log**.
El espacio se enumera a mano en los dos, y hay un test en cada lenguaje que fija que **ninguno**
lo colapse.

#### 🔭 Deseable, no construido: un espacio para declarar equivalencias

Propuesto por el usuario y marcado explícitamente como deseable: *«dar un espacio en el
diccionario de definiciones para que una acepción pueda apuntar a su equivalente en otros
packs»*.

**Es el mecanismo correcto para lo que el hash no puede**, y ahora tiene su número: cerraría los
**65,60 %** de acepciones que dos diccionarios del mismo idioma no comparten por redacción. La
forma ya existe — sería un tag aditivo cuyo valor es un `sense_code`, es decir la misma referencia
que ya se sabe escribir y resolver. Lo que no existe es **quién las declara**: alinear acepciones
entre fuentes es §Alinear acepciones entre fuentes, que sigue siendo el problema abierto más caro
del repo.


### ✅ Flexiones del idioma destino — CONSTRUIDO 2026-09-21 (#6)

`sources/inflections.py` lee las flexiones del pack **ya construido** del idioma destino y las
suma a `record.translations`. Se activa con `--flexiones <pack.db>`.

⚠️ **Se eligió la opción A —expandir `trans`— sobre la B —tabla de indirección— y eso cambia el
precio que se había cotizado.** El corte decía **1,84 MB**, que es lo que pesa B. A pesa más, pero
**no toca el esquema ni la cascada de consulta**: son más filas de lo mismo, contra una tabla
nueva, un peldaño nuevo y dos viajes por búsqueda.

**Medido sobre dos builds gemelos del mismo dump y la misma muestra (1/40):**

| | `trans` | tamaño |
|---|---|---|
| sin flexiones | 5.688 filas | 1,38 MB |
| **con flexiones** | **12.792 filas** | **1,48 MB (+7,2 %)** |

Extrapolado al pack completo de 50,2 MB son **~+3,6 MB**, entre las dos opciones medidas y sin
cambio de formato. **B queda anotada como optimización con su número**, disponible cuando esos
MB importen.

**Y los irregulares llegan, que era el punto**: `ran`, `went` y `eaten` alcanzan entradas en el
pack construido — antes no llegaban nunca, porque sólo se encontraban si alguna glosa los
escribía.

⚠️ **La fuente es un pack y no un dump**, por el mismo razonamiento de D-175: las flexiones ya
están construidas y podadas dentro de `en-def-wikt.db`, y volver al dump de 3,2 GB sería otra hora
de build y una segunda poda que puede divergir de la primera.

⚠️ **El filtro no es opcional** y lo descubrió leer filas: el 38,7 % de `form` del pack inglés
contiene un espacio, y `no table tags` (577) y `glossary` (575) son artefactos de wiktextract.
Filtrar descarta el 35 % de los candidatos **sin mover la cobertura ni una décima**.


### Revisión de las decisiones de traducción — 2026-09-21

#### ¿Se está usando todo el dato verificado? **No: faltan tres conjuntos**

| dato verificado | medido | ¿se usa? |
|---|---|---|
| `es.jsonl`, traducciones con `sense_index` | 34.710 pares | ✅ tag `T` |
| `es.jsonl`, traducciones sin índice | 37,7 % | ✅ tag `W` |
| `es.jsonl` → canal de búsqueda | 3.257 filas en la muestra | ✅ `trans` |
| **`en.jsonl`, 10.438 pares EN→ES curados** | 100 % con **texto** de acepción, 0 índices | ❌ **sin usar** |
| ~~**claves de display del pack bilingüe**~~ | `translation_keys` las calculaba y las tiraba | ✅ **cerrado por D-179** |
| ~~**índice de flexiones inglesas**~~ | 1,84 MB · 78,1 % → 98,9 % | ✅ **cerrado por D-184**, y reubicado por D-196 al `form` de la entrada inglesa |

> ⚠️ **Todo el bloque de abajo está SUPERADO (2026-09-21).** Se conserva porque explica de qué
> se partía. Hoy el bilingüe tiene **0 filas de `trans`** —las palabras inglesas son 85.505
> entradas de verdad (D-196)— y su canal de lectura está lleno: abrir `casa` muestra `house`
> como **traducción**, no como glosa.

~~El pack bilingüe tiene 206.727 filas de `trans` y CERO en `T`/`W`.~~ Su canal de lectura
estaba vacío y **reconstruirlo con el código de entonces no lo habría llenado**: `bilingual.py`
sólo escribía `record.translations`, nunca tocaba el payload.

⚠️ **El pack inglés no tiene traducciones de ninguna clase**: `trans` en 0 y sin `translations_to`.
Los 10.438 pares curados de `en.jsonl` no pueden llenar `T` —traen texto de acepción, no índice—
pero **sí pueden llenar `trans`**, que es lo que haría que buscar `perro` encuentre `dog` en el
pack inglés.

#### Las decisiones, y cuáles conviene revisar

Validadas por medición, sin deuda:

| decisión | qué la valida |
|---|---|
| Tope de **8** por acepción y por palabra | pierde **57 de 34.710 — 0,16 %** |
| Descartar el `sense_index` que no se parsea | **17 de 34.710 — 0,049 %** |
| Expandir rangos en el lector compartido | sinónimos y antónimos son **100 % índices simples**: no los toca |
| Referencia como **tupla**, no cadena | su test encontró que la cadena dejaba **forjar** una referencia |
| El `pack` en `meta` y no por item | por item costaría ~280 KB de una sola cadena |

⚠️ **Tres que sí conviene discutir, porque tienen un supuesto adentro:**

**1. ~~`translations_pack` nombra UN pack, y eso es frágil.~~** ✅ **Resuelto el mismo día**: la
clave **se eliminó** y el destino se nombra por **idioma** (`translations_to`), que es lo que esta
fila proponía. `SearchViewModel.resolveInLanguage` resuelve en el primer pack instalado de ese
idioma, así que un usuario con el núcleo inglés y no el completo **conserva los enlaces**.

**2. El canal de búsqueda y el de lectura se reparten distinto, y no está declarado.** `trans`
lleva **todas** las traducciones (atribuidas y sueltas) porque para buscar da igual; `T` y `W`
las reparten por atribución. Es correcto, pero **un lector no tiene cómo saberlo**: si alguien
cuenta `trans` esperando que coincida con lo que se muestra, no va a cuadrar. O se documenta en
`formato-pack.md`, o `verify_pack.py` lo afirma como invariante.

**3. ~~`kind = monolingual` ya no describe lo que el pack hace.~~** ✅ **Resuelto el mismo día**
por la vía que esta fila recomendaba: `kind` describe **las definiciones** y la capacidad se lee
de `translations_to`, que `PackMetadata` ahora expone. `wordActions` dejó de mirar `kind`, y la
acción además **desaparece cuando la ficha ya muestra las traducciones propias**.

#### Lo que yo cerraría primero — ✅ **los cuatro, cerrados el mismo día**

1. ~~`bilingual.py` llena `T`/`W`~~ — hecho, con `for_search` separando las dos formas del término.
2. ~~`wordActions` deja de mirar `kind`~~ — hecho.
3. ~~`translations_pack` pasa a ser preferencia sobre un idioma~~ — hecho, y más fuerte: la clave
   se eliminó y el destino es el idioma.
4. ~~Los 10.438 pares de `en.jsonl`~~ — hecho, por el canal `W`.

**Lo único que sigue abierto de esta revisión es la fila 2**: que el reparto distinto entre el
canal de búsqueda y el de lectura **no está declarado en ningún lado**. Va a `formato-pack.md` o
a `verify_pack.py` como invariante.


### Qué falta del pack de idiomas — auditado 2026-09-21

Auditado contra el pack de muestra **realmente construido**, no contra el diseño:

```
traducciones EN disponibles en el dump para sus lemas : 2947
   con sense_index : 1836 (62,3 %)
   SIN sense_index : 1111 (37,7 %)   <- no tienen donde vivir
emitidas por el pack                                  : 1921 (65,2 %)
lemas con traduccion disponible que muestran alguna   : 1007 de 1944 (51,8 %)
tabla `trans` (canal de BUSQUEDA)                     : 0 filas
```

⚠️ **`construir` tiene 16 traducciones en el dump y el pack muestra cero.** También
`comprender` (7), `comenzar` (6), `atrapar` (8). No es cobertura de la fuente: es que **no hay
canal donde ponerlas**.

> ⚠️ **Esta tabla es del 2026-09-20 y está SUPERADA.** Se conserva porque muestra de qué se
> partía; el estado real se marca en la columna de la derecha.

| # | pedido | estado entonces | estado hoy (2026-09-21) |
|---|---|---|---|
| 1 | completar con otras fuentes | ⚠️ barrido hecho, ninguna externa usable | ✅ se usa el **65 %** de la propia |
| 2 | cómo se **consulta** e integra | ⚠️ integra sí, consulta no | ✅ **las dos**: el otro idioma son entradas (D-196) |
| 3 | tablas en ambos sentidos | ❌ medido (1,84 MB), sin construir | ✅ **construido** (D-195/D-196) |
| 4 | mostrar palabras sin definición | ❌ diseñado (0 MB), sin construir | ✅ una entrada inversa **no tiene acepciones** y se muestra igual |
| 5 | sección de traducciones en la ficha | ✅ construido | ✅ |
| 6 | traducciones ↔ acepciones entre idiomas | ✅ dentro de un idioma | ✅ sin cambio: entre idiomas sigue medido y descartado |
| 7 | **dos modos: palabra y acepción** | ❌ sólo el de acepción | ✅ **los dos**: tags `T` y `W` (D-179) |
| 8 | degradar a modo lista | ❌ depende de 7 | ✅ el canal `W` **es** la lista |
| 9 | enlace inequívoco palabra+acepción | ❌ diseñado, sin construir | ✅ `sense_code` (D-180 a D-182) |
| 10 | enforcement | ❌ diseñado, sin construir | ✅ `verify_pack.py` comprueba el formato y las dos direcciones |

**El #7 desbloquea 1, 2, 7 y 8 a la vez**, y su costo está medido: un tag nuevo en el payload
—aditivo, sin subir `CODEC_ID` por D-119— más **22 llamadores de `parse()` en Python** (19 en
tests) y el `Body` de Kotlin, que gana un campo con default.


### Naming a sense from another pack: the reference, and the two modes — designed 2026-09-21

Asked as: *«¿hay alguna forma fácil o correcta de enlazar inequívocamente con una palabra y
acepción específica, para declararlo así en el pack de traducción?»*, with the degradation stated:
**no definitions pack → list mode; definitions pack present → per sense where there is a
reference, and the rest as a list.**

There is a correct way, the degradation needs no special mode, and there is one structural
constraint that decides which packs can use it at all.

#### The reference: a digest of the target's gloss

    sense_ref = h(entry_uid, norm(gloss))

⚠️ **Its virtue is not uniqueness, it is that the reader can verify it against the referenced
pack's own bytes** — which is exactly what D-142 did for `norm_version`: the app recomputes the
digests of the entry's senses and looks for the one named. A reference that resolves **is** proof
that it points where it claims. **No version negotiation, no trust.**

Measured over the 152,281 entries of the real Spanish pack: **22 senses out of 210,249 collide
with another sense of the same entry — 0.0105 %**, and reading them shows they are genuine
duplicate glosses in the wiki (`granadino` defines the same thing twice). A collision attaches the
term to two senses whose text is identical, which is harmless.

**Why not the alternatives**, all of them measured rather than reasoned about:

| candidate | why not |
|---|---|
| **sense ordinal** | Breaks on every insert or reorder, and breaks **all-or-nothing**: everything after the first inserted sense shifts. The gloss digest degrades *partially* — a rebuild that edits 5 % of glosses loses 5 % of references and the rest keep working |
| **the source's `sense_index`** | Only meaningful inside one wiki edition. `es-def-wikc` comes from the **Spanish** Wiktionary and `es-tr-enwikt` from the **English** one; their numbering has nothing to do with each other |
| **WordNet synset** | Measured: 0.6 % of Spanish synset ids survive into OEWN 2024, and four of five that do are collisions |
| **Wikidata `P5137`** | Real and CC0, on 30.3 % of Spanish lexeme senses — but the labels live in a >100 GB items dump |

#### ⚠️ The constraint that splits translation packs into two classes

A digest of the target's gloss can only be written by a builder that **has seen that gloss**. That
is not a detail, it decides which packs can ever carry sense references:

- **Derived packs can.** A translation pack built *from* the definitions pack — the way
  `build_core.py` derives the core, and for the same reason: *«derivándolo eso es cierto POR
  CONSTRUCCIÓN»* — has the glosses in hand and can name them.
- **Independently built packs cannot.** Today's `es-tr-enwikt` comes from the English Wiktionary
  and has **never seen a single Spanish Wiktionary gloss**. It can only ever declare entry-level
  translations. That is not a defect to fix; it is the honest description of what it knows.

This also gives the enforcement hook from §Enforcing the contract a concrete shape: a pack that
carries sense references must declare **what it was derived from**, and `verify_pack.py` can
refuse references in a pack that declares nothing.

#### The degradation needs no mode, which is the point

| situation | what happens | why it is automatic |
|---|---|---|
| definitions pack **absent** | everything renders as the entry-level list | there are no glosses to hash, so no reference resolves |
| definitions pack **present** | resolved references render under their sense; unresolved ones **and** those that never had a reference render in the list | resolution is a lookup that either finds the digest or does not |
| definitions pack present but a **different build** | the glosses that survived still resolve; the edited ones fall to the list | the digest is content, not a version number |

⚠️ **The one rule that must not be relaxed: a reference that fails to resolve falls to the LIST,
never to sense 1.** Falling back to the first sense is precisely the D-117 error, and it is
invisible: `bizarro` sense 2 carrying sense 1's material reads perfectly plausible.

#### Presenting it, priced

Measured over the 25,328 Spanish entries that have English translations:

| | entries | |
|---|---|---|
| **only per-sense** | 12,269 | **48.4 %** |
| **only list** | 12,306 | **48.6 %** |
| **both at once** | 753 | **3.0 %** |

**So the screen almost never has to show both sections** — the hard layout case is 3 % of entries,
not the norm. And the lists are short: **median 1 term, p90 2, max 17** in both modes, so each one
is typically a single line under its title.

⚠️ **`VISIBLE_SENSES = 3` was the risk and it is not one.** A translation attached to sense 7
would sit behind *«ver más»* and be invisible. Measured: of the 13,022 entries where some sense
receives a translation, **12,958 (99.5 %) have at least one inside the visible three**, and the
distribution is steep — 12,288 on sense 1, 3,765 on sense 2, 1,441 on sense 3. Only **64 entries**
would have all of them hidden.

**The layout, following what the screen already does:**

1. **Per-sense**: a fourth `TermList` inside `SenseBlock`, alongside synonyms, antonyms and
   related. Same two lines, same rules.
2. **Entry-level**: its own section **after** the senses. Not before — the definitions are what
   the reader opened the entry for — and never inside `SenseBlock`, because a list drawn under a
   sense **asserts** it belongs to that sense.
3. ⚠️ **Two different titles, and this is not decoration.** D-126 settled the principle for
   antonyms: *«las tres listas se ven idénticas, y lo único que separa "otra forma de decirlo" de
   "lo contrario" es esa palabra»*. Here the same word has to separate *«this sense means this»*
   from *«the word can mean this, we do not know in which sense»*. If both say **Traducciones**,
   the format distinguishes the two modes and the screen merges them again, and the claim the
   format was built to protect is lost at the last step.

The exact wording is a product decision and is not taken here. What the measurement settles is
that it needs to be **two** strings, and that the cost is one extra line on 3 % of entries.


### Enforcing the contract on a pack somebody else built — designed 2026-09-21

Asked as: *«mientras yo tenga el control de los packs puedo verificar que alguien externo no rompa
la compatibilidad o las funcionalidades más frágiles, pero ¿hay alguna forma de enforzar esto? Por
ejemplo, que las traducciones tengan dos modos, uno asociado sólo a la palabra y otro asociado
directamente a la acepción»*.

The example is the right one, and it is sharper than it looks: **the missing second mode is what
creates the incentive to lie.**

#### The pattern this repo already has a name for

D-142 is the precedent: `norm_version` used to be **a number a pack writes about itself**, and the
64-entry sample turned it into **a proof**, because the keys can be recomputed from the pack's own
bytes and compared. That is the whole rule:

> **A pack can be trusted only for what can be recomputed from its own bytes.** Everything else is
> a declaration, and a declaration from a stranger is a wish.

Sorting today's guarantees by that rule gives three tiers, and the third is the risk surface:

| tier | what | examples |
|---|---|---|
| **Proved** — recomputable, `verify_pack.py` fails on a mismatch | keys and structure | `entry.norm == norm(headword)`, `entry.fuzzy == fuzzy(...)`, `entry.uid == stable_uid(...)`, `fts_def.rowid == entry.id`, no orphan `entry_id`, the prefix query does not scan, `payload_dict_sha256` |
| **Safe because lying hurts only the liar** | selection | ⚠️ `subset_of`: `packsToQuery` removes **the pack that declares it**, never the one it names, so a false claim deletes yourself from the search. `rank`: the coverage band is computed from the query and the headword **without reading pack data** (D-142), so `rank` only reorders *within* a band |
| **Declared and unchecked** — the surface | **content attribution** | whether a synonym, antonym or translation really belongs to the sense it is sitting in. Nothing states it, so nothing can check it |

#### ⚠️ Why the two modes are not a convenience but the fix

Today the payload has **one channel**: `T` (and `Y`, `A`, `R`) live *inside* a sense, and
`payload.parse` **silently drops** any of them appearing before the first `S` — the `if senses:`
guard. There is no entry-level channel at all.

So a builder holding translations that are **not** attributable to a sense has exactly two moves:

1. **Drop them** — which is what D-132 does for entry-level `related` when the entry has several
   senses, and it costs real content.
2. **Smear them across every sense** — which is free, invisible, passes `verify_pack.py`, and is
   the exact error D-117 exists to prevent: *«`bizarro` acepción 2 con los sinónimos de la 1 se lee
   perfectamente plausible»*.

**A format with one channel makes the dishonest option the cheap one.** Two channels —
`T` = *this sense*, and a new entry-level tag = *this word, sense unknown* — give a builder a
truthful place to put weak data, and that is what makes the claim checkable: once there is
somewhere else to put it, putting it inside a sense **means something**.

⚠️ **And it is additive.** D-119 and D-126 already established that a new tag does **not** bump
`CODEC_ID`, because `payload.parse` ends with *«los tags desconocidos se ignoran a propósito: un
builder más nuevo puede agregar campos sin romper un lector viejo»*. An old reader shows the entry
without the entry-level list, which is correct degradation.

#### The check, with a measured baseline

Two channels do not make lying impossible — nothing does, the pack is just bytes. They make it
**detectable**, the same way D-142 makes wrong keys detectable rather than impossible. The
signature of smeared data is that **every sense carries the identical list**, and honest data does
not look like that. Measured over the 40,000 best-ranked entries of the real Spanish pack, using
the synonyms that already enter by `sense_index`:

| multi-sense entries with synonyms in ≥ 2 senses | 5,109 |
|---|---|
| **lists differ between senses** | **4,626 — 90.5 %** ← the signature of attributed data |
| lists identical in all of them | 483 — 9.5 % |

plus **6,010 entries carry synonyms in exactly one sense**, which is attribution too — smeared
data would have filled them all.

So a pack whose multi-sense entries sit near **0 % distinct** is claiming a granularity it does not
have, and `verify_pack.py` can say so. The 9.5 % says the threshold must be loose — two senses of
a word genuinely can share synonyms — which makes this a **smell with a number**, not a proof. That
is the honest description and it should be printed as a warning, not a failure.

#### What this implies, in order

1. **Add the entry-level channel** — a new payload tag, additive, no `CODEC_ID` bump.
2. **Declare the granularity in `meta`** so the claim exists to be checked, the same way `langs`
   is required of a bilingual pack.
3. **Add the distinctness check to `verify_pack.py`** as a warning with the 90.5 % baseline in its
   message.
4. ⚠️ **Render the two modes in different places.** A translation drawn *under a sense* **asserts**
   it belongs to that sense — that is what D-126 says about the category word, and the same logic
   applies here. The entry-level list goes at entry level, below the headword or after the senses,
   never inside `SenseBlock`. Otherwise the format distinguishes them and the screen re-merges them,
   and the lie comes back at the last step.

#### ⚠️ «Compatibles por construcción» son TRES cosas, y sólo una lo es — medido 2026-09-21

Pregunta directa: *«¿entonces por construcción mis 3 packs son compatibles entre sí, lo que
facilita la interconexión?»*. Medido sobre los seis `.db` reales, la respuesta se parte en tres
niveles y conviene no confundirlos, porque cada uno habilita cosas distintas.

**1. El formato: idéntico en los seis, y eso sí es por construcción.**

| | `schema_version` | `norm_version` | `uid_recipe` | `payload_codec` |
|---|---|---|---|---|
| los **seis** packs | `3` | `2` | `uid-v1` | `deflate-v2` |

Eso es lo que permite que convivan: mismas claves de búsqueda, misma receta de identidad, mismo
payload. Y no es una coincidencia que haya que vigilar — está **forzado**: los vectores
compartidos comparan `norm()` entre los dos idiomas, `PackFile.open` recalcula las claves de 64
entradas repartidas y rechaza el pack si no coinciden (D-142), y D-005/D-006 obligan a subir
`NORM_VERSION` en el mismo commit que toque `norm()` o `fuzzy()`.

**2. La identidad dentro de un idioma: depende del CONTENIDO, no del formato.**

| par | `uid` en común | |
|---|---|---|
| `es-core` ↔ `es-def-wikc` | **7.349 / 7.349 — 100,0 %** | ✅ el único *por construcción* |
| `en-core` ↔ `en-def-wikt` | **16.652 / 16.652 — 100,0 %** | ✅ ídem |
| `es-def-wikc` ↔ `es-tr-enwikt` | 47.646 — **31,3 %** | contenido |
| `es-def-wikc` ↔ `es-def-wd` | 14.609 — **9,6 %** | contenido |

⚠️ **El 100 % de los núcleos no sale del formato: sale de que `build_core.py` COPIA el `uid` en
vez de recalcularlo.** Esa línea es toda la diferencia entre «compatible» y «unido». Recalcularlo
contando los homógrafos del núcleo daría otra identidad para la misma palabra — que es el fallo
que D-145 encontró.

Y D-139 dejó la lección inversa: el pack de Wikidata usaba **otra convención de `sense_key`**, con
la misma receta `uid-v1`, y **sus uid no unían con nada**. Misma receta no es misma identidad.

**3. Entre idiomas: cero, y es por diseño.**

| par | `uid` en común |
|---|---|
| `es-def-wikc` ↔ `en-def-wikt` | **0** |
| `es-tr-enwikt` ↔ `en-def-wikt` | **0** |

⚠️ **`stable_uid(lang, headword, pos, sense_key)` lleva el idioma dentro del hash**, así que una
entrada española y una inglesa **no pueden compartir `uid` jamás**, ni con la misma grafía. Es
correcto —`casa` en español y `casa` en otro idioma son palabras distintas— y tiene una
consecuencia que gobierna todo el trabajo de traducción:

> **La integración ES↔EN no puede pasar por `uid`.** Pasa por las tablas de traducción — `trans`
> para buscar y el tag `T` para leer. `uid` es el join **dentro** de un idioma; entre idiomas el
> puente es el contenido de traducción, y por eso todo lo medido arriba aterriza ahí y no en el
> join.

**Resumen en una línea:** los packs son **interoperables** por construcción (se abren, conviven y
se consultan juntos), son **unibles** sólo donde el contenido coincide, y **entre idiomas no se
unen nunca por identidad** — se unen por traducción.

> **El selector de idioma NO es composición**, y conviene no confundirlos. El selector elige
> **un** pack y busca en él (D-078); la composición hace que un pack auxiliar le **sume**
> información a la misma entrada de otro. `SearchRepository` **ya existe** (D-136) y resuelve la
> convivencia —varios packs consultados y sus resultados fusionados—; lo que sigue sin existir es
> el **join por `uid`**, que es sumarle campos a una entrada.

Que un pack de sinónimos y uno de traducciones puedan sumar información **a la misma entrada**
del pack de definiciones.

**El join key ya está decidido y construido** (D-055, 2026-09-17): `entry.uid`, una columna
aparte, hash de `(entry.lang, NFC(headword), pos, sense_key)`. `entry.id` sigue siendo el rowid
secuencial. La comparación completa y las mediciones están en el changelog de esa fecha.

**Qué hay ya a favor.** El pack base ya escribe `uid`; `verify_pack.py` comprueba unicidad y
receta; `Entry` lo expone al abrir una entrada. `Suggestion` lleva `packId` además de `entryId`,
así que la capa de resultados distingue el origen.

**Qué falta para que la composición exista.**
- La capa que fusiona: **`SearchRepository` todavía no existe** — solo estaba nombrado en este
  documento.
- Construir un pack auxiliar de verdad, con `uid` como PK, y medir el join en el reloj.
- Decidir la **granularidad**: hoy `uid` es por entrada, y un sinónimo es de una acepción. Está
  en la tabla de decisiones abiertas.
- El vocabulario de `pos` tiene que normalizarse igual en el base y en los auxiliares.


### An English–Spanish translation pack: one pack or two?

**Status.** ✅ **Built 2026-09-21 (D-177).** The question was whether one pack serves both
directions; the first measurement said no and was measuring the wrong thing.

**Structurally, one pack does serve both.** The schema already has `trans` — `norm, entry_id` —
which is exactly a reverse index: an entry can be reached by a word in the *other* language, and
`MatchKind.TRANSLATION` is rung 3 of the cascade. A pack of Spanish entries with English glosses,
plus `trans` rows keyed by English, answers `perro` directly and `dog` through `trans`.

⚠️ **But the data does not come that way, and that is what settles it.** The dump already
downloaded — `es-en-wikt.jsonl`, 989 MB, the English Wiktionary's Spanish section — has **no
`translations` field at all**: it gives Spanish words with English *glosses*. To make the reverse
direction work, `trans` would have to be derived from those glosses. Measured over **141,166
senses**:

| | |
|---|---|
| Glosses that **are** a translation (terms of ≤ 2 words) | **16,532 — 11.7 %** |
| Glosses that are a paraphrase | **124,634 — 88.3 %** |

Examples of each: `gratis → free, without charge` against `pie → foot (a part of the body)` and
`pies → second-person singular voseo present subjunctive`.

⚠️ **That number was the wrong one, and building it showed why.** 11.7 % counts *senses whose
whole gloss* is a clean translation. What the reverse index needs is far weaker: **one usable term
from any sense of an entry**. Measured again over the records the builder actually emits:
**87.6 % of entries get at least one translation key**, 2.3 keys each.

So the recommendation flipped, and **one pack does serve both directions** — see D-177, built.
Spot-checked against the real pack: `hammer → martillo`, `pepper → ají, pimentón, pimiento`,
`scaffold → andamio, cadalso`.

#### How complete it is, and which direction the build favours

Measured 2026-09-21 against the real pack, using the most-used words of each language as the test
set — the same Tatoeba frequency signal that chose the core vocabularies.

| Words tested | **ES→EN** (headword or inflected form) | **EN→ES** (through `trans`) |
|---|---|---|
| top 1,000 | **100.0 %** | 92.7 % |
| top 3,000 | **99.9 %** | 85.9 % |
| top 8,000 | **99.8 %** | 78.1 % |

⚠️ **Forward is effectively complete; reverse is good and clearly behind — and the gap is
structural, not accidental.** The Spanish side has the `form` table, so every inflection reaches
its lemma systematically. The English side has only the derived keys, so an English inflection is
found **only if some Spanish gloss happens to spell it**:

| | found | | found (entonces) | (hoy) |
|---|---|---|---|---|
| `dogs` | ✅ | `ran` | ❌ | ✅ flexión de `run` |
| `running` | ✅ | `went` | ❌ | ✅ flexión de `go` |
| `houses` | ✅ | `bigger` | ❌ | ✅ |
| `children` | ✅ | | | |

The regulars survive by luck and the irregulars do not — which is the worst shape for a gap,
because it is invisible until you hit it.

> ✅ **Cerrado por D-184 y reubicado por D-196.** Las flexiones inglesas entran desde el pack
> inglés ya construido, y desde que el bilingüe es bidireccional viven en el **`form` de la
> entrada inglesa**: `went` es flexión de `go`, y `go` es un lema. Medido sobre el pack
> reconstruido: **97,0 %** del top 1.000 inglés, contra el 78,1 % de esta tabla.

⚠️ **A second asymmetry, and it is the one no amount of coverage fixes**: the two directions do not
return the same *kind* of answer. ES→EN opens an **entry** with its senses; EN→ES returns a **list
of Spanish lemmas** (2.3 per key on average) and there is no English entry to open, because the
pack contains none. It answers *"which Spanish words mean this"*, never *"what does this English
word mean"*.

**And against the monolingual pack, the entries themselves are thinner** — same 4,000 best-ranked
entries of each:

| | entries | senses/entry | only one sense | senses with an example |
|---|---|---|---|---|
| bilingual ES→EN | 123,979 | **2.49** | **31.6 %** | 22.0 % |
| monolingual ES | 152,281 | **3.63** | 8.7 % | 21.6 % |

That is the source, not the build: the English Wiktionary describes Spanish words more briefly than
the Spanish Wiktionary does. **It is a translation dictionary, not a Spanish dictionary**, and the
numbers say to keep both rather than treat this one as a replacement.

#### Closing the reverse gap: measured, 2026-09-21

The question was whether a translation pack can carry its index **in both directions** and what
that weighs. It was measured rather than argued, and the answer is that it is cheap — but only in
one of the two shapes, and only after the source is filtered.

**The source is the English monolingual pack's own `form` table**, which is already built and
already downloaded. Of the 89,049 English keys in `trans`, **70,504 (79.2 %) are a lemma there**,
and their inflections are exactly the rows the reverse direction is missing.

**What it buys** — the same top-N test as the table above, so the numbers are comparable:

| Words tested | EN→ES today | with the inflection index |
|---|---|---|
| top 1,000 | 92.7 % | **99.4 %** |
| top 3,000 | 85.9 % | **99.6 %** |
| top 8,000 | 78.1 % | **98.9 %** |

⚠️ **The residue is not vocabulary, it is the tokenizer.** What still misses at top 8,000 is
led by `didn`, `doesn`, `wasn`, `shouldn`, `hasn`, `hadn` — the halves of contractions that
`tatoeba.frequencies` splits on the apostrophe — plus `cannot` and `any`. Real misses
(`ambitious`, `amid`, `tend`, `altogether`) are a handful. **The gap this closes is essentially
all of the gap there was**, and the measurement of what remains is worth more than the coverage
number: it says the next thing to fix is the frequency tokenizer, not the pack.

##### ⚠️ Two storage shapes, and the expensive one is the obvious one

Both were built and weighed, not estimated:

| | rows | size | query cost |
|---|---|---|---|
| **A — expand into `trans`** `(norm, entry_id)` | 379,000 | **5.88 MB** | none: the `byTranslation` rung is unchanged |
| **B — an indirection table** `(norm, lemma)` | 84,319 | **1.84 MB** | one extra lookup before the rung |

**A costs 3.2× more for the same answers**, and the reason is worth stating because it is not
obvious from the row counts: an English key maps to **2.3 Spanish entries** on average — `dog` is
a key of *can, perro, chucha, choco, hotdog* — so expanding `dogs` writes that fanout **again**,
once per inflection. B stores each inflection **once** and pays the fanout at query time, where it
is already being paid.

On a 50.2 MB pack that is **+3.7 % against +11.7 %**. B is the recommendation; its cost is a new
table, which is a **schema change** and therefore D-001 territory: the pack is rejected and rebuilt
rather than migrated, which for a pack that is not published yet costs nothing.

##### ⚠️ The filter is not optional, and it was found by reading rows

A blind sample of 20 candidate rows — the discipline in root `CLAUDE.md`, *look at the output, not
just the numbers* — showed the raw source is dirty, and the dirt is in the **English monolingual
pack itself**:

- `no table tags` (577 rows) and `glossary` (575 rows) are **wiktextract parse artifacts** sitting
  in `form` as if they were inflections.
- **38.7 % of the English `form` table's 985,992 rows contain a space**: `big fat hairy deals`,
  `ate breathed and slept`, `1 000 000 questions`. The Spanish pack is clean by comparison — its
  most repeated form appears 16 times, and it is `unas`.

Keeping one word, alphabetic, non-artifact drops the candidates from 130,378 to **84,319 — 35 %
was junk — and the coverage numbers above do not move by a tenth of a point.** That is the
measurement that matters: the filter is free.

**This is a defect in `en-def-wikt.db` regardless of this feature**, and it is filed under §Pack
de inglés: those phrase rows are dead weight in the inflection rung of the English pack too, where
nothing filters them.

##### The other direction, priced and discarded

The alternative reading of *"tables in both directions"* is a pack **authored** EN→ES, with English
headwords and English senses — the thing that would answer *"what does this English word mean"*
rather than *"which Spanish words mean this"*. The English Wiktionary does carry a real
`translations` field for its **English** entries, unlike the Spanish section which has none at all.

⚠️ **It was counted over the full 3.2 GB dump and the data is not there**: of **1,492,836** English
entries, **9,221 (0.6 %)** have any `translations` and **5,080 (0.3 %)** have a Spanish one, for
**10,438** EN→ES pairs total. Against the **206,727** rows already derived from the glosses, a pack
built that way would be **twenty times smaller** than the reverse index it is meant to replace.

The curated pairs are good — `dictionary → diccionario, tumbaburros, mataburros`, sense-tagged —
so they are worth **folding in as extra keys**, which is cheap. They are not worth a pack.


### Completing the translations, and where they belong in an entry — measured 2026-09-21

Two questions, asked together: **complete the translation pack from other sources**, and work out
**how that information is queried and integrated into the entry structure that already exists**.
The second turned out to be almost entirely answered already, and the first has a source nobody
had looked at.

#### ⚠️ The structure already has the field, and it is empty in all three packs

`Sense.translations` exists in `Model.kt`, `payload.py` writes it as tag `T`, `PayloadCodec`
reads it, and the entry screen can render it. **Nothing fills it.** Read from the real packs:

```
BILINGUAL es-tr-enwikt      casa (noun) — 1 sense
   S: house                 related: hogar, lar
   trans table -> house

MONOLINGUAL es-def-wikc     casa (noun) — 15 senses
   S: Edificación destinada a vivienda.
   S: Domicilio.            synonyms: domicilio, hogar, lar, morada
   trans table -> (empty)
```

So the two halves each hold what the other needs and neither carries a translation in the field
meant for one. The bilingual pack puts the English **in the gloss** (`S: house`), which is why it
reads as a dictionary whose definitions happen to be English words; the monolingual pack has the
15 real senses and no English at all.

**This is why `wordActions` says the translate action shows up "today: never"** — it is not
waiting on a mechanism, it is waiting on data.

#### The source that was never looked at: the Wikcionario already has translation tables

⚠️ **`es.jsonl` — the very dump that builds `es-def-wikc` — carries a `translations` field**, and
nothing in the pipeline reads it. Measured over the whole dump:

| | |
|---|---|
| Spanish entries | 854,460 |
| with `translations` | 32,453 (3.8 %) |
| with an **English** translation | 25,328 (3.0 %) |
| ES→EN pairs | **34,710** over 22,520 lemmas |
| **of those, carrying `sense_index`** | **55 % of lemmas** |

That last row is the one that matters, and it is worth more than the coverage: **`sense_index` is
the same field D-117 and D-124 already use to attach wiki synonyms to the right sense.** The
alignment problem that blocks WordNet, Wikidata and the enwiktionary examples —§Alinear acepciones
entre fuentes, 20,644 contributions currently thrown away— **does not apply here**. Same dump,
same entry, same numbering, so the `uid` matches by construction and the sense is stated by the
source.

```
casa   -> home, house              idx=1
libro  -> book                     idx=1
       -> omasum, psalterium, third stomach   idx=6
```

`libro` is the whole argument in three lines: senses 1 and 6 get different English, and the source
says which is which.

#### Coverage: what fraction of entries would actually show a translation

Over the Spanish frequency list, asking *"the entry the user opens — does it carry an English
translation?"*, with inflections resolved through `form` the way the reverse-index numbers were:

| Words tested | Wikcionario `translations` | via `uid` join to the bilingual pack | **both** |
|---|---|---|---|
| top 1,000 | 94.5 % | 86.5 % | **98.3 %** |
| top 3,000 | 90.0 % | 84.0 % | **96.8 %** |
| top 8,000 | 84.2 % | 81.6 % | **94.8 %** |

The two sources are **complementary rather than redundant** — neither alone reaches what the pair
does — and the cheap one is also the better one: it needs no join, no new pack and no new rung.

#### ⚠️ The `uid` join is not the bottleneck, and that kills the obvious next idea

The natural reaction to 31 % is to blame the join key and loosen it. Measured, between the
monolingual and bilingual Spanish packs:

| key | in common | % of the monolingual pack |
|---|---|---|
| `uid` | 47,646 | 31.3 % |
| `(norm, pos)` | 50,882 | 34.6 % |
| `norm` alone | 52,552 | 37.9 % |

**Going all the way down to bare headword buys 6.6 points** and gives up everything D-055 bought.
What does not overlap is the **vocabulary**: the English Wiktionary's Spanish section and the
Spanish Wiktionary describe different words. No key recovers that, and the fix is a second source,
which is exactly what the row above is.

#### ⚠️ WordNet as a translation bridge is a trap, and the trap is silent

The tempting idea: `wn-data-spa.tab` gives Spanish lemmas per synset, OEWN gives English lemmas
per synset, a synset **is** one sense — so joining them would give translations aligned by sense
for free, solving §Alinear acepciones outright. `wordnet.py` already reads both files.

**It does not work, and the failure is the dangerous kind.** Of the 78,417 Spanish synset ids,
only **435 (0.6 %)** exist in OEWN 2024: the `.tab` carries Princeton WordNet 3.0 offsets and OEWN
renumbered. Worse, the 435 are not a usable subset — they split by offset magnitude:

| offset | count | what they are |
|---|---|---|
| 4–6 digits | 93 | **real matches**: `apto, capaz, competente ↔ able` · `ente, entidad ↔ entity` · `cosa ↔ thing` |
| 7–8 digits | **342** | **collisions**: `soñador ↔ diner` · `jefa, jefe ↔ girl` · `epidemiólogo ↔ easterner` · `lama ↔ joiner` · `hedonista ↔ groundskeeper` |

**Four out of five pairs are wrong and none of them looks wrong** — `hedonista ↔ groundskeeper`
reads as a bad dictionary, not as a bug, which is the failure mode this repo treats as
unacceptable. Bridging the two would need the **ILI** (OEWN declares `ili="i1"` per synset) plus a
PWN-3.0 → ILI map, which is not downloaded. Until that exists, **the MCR is usable within Spanish
and must not cross languages.** `wordnet.py` already refuses to transfer antonymy across
languages for a different reason (it is a lexical relation); this is a second, stronger reason
that applies to everything.

#### What this adds up to

In order, cheapest first, none of it built:

1. **Read `translations` from `es.jsonl` in `kaikki.py` and write tag `T` per sense**, gated on
   `sense_index` exactly as synonyms are. No schema change, no new table, no join, no alignment
   risk — `trans` and the `T` tag already exist and `verify_pack.py` already checks them. Gets
   **94.5 % of the top 1,000**.
2. **The same for `en.jsonl`**, which carries 10,438 curated EN→ES pairs (see above) — it makes the
   English monolingual pack translate too.
3. **Only then** the `uid` join to the bilingual pack for the remaining 3.8 points, which is the
   part that needs `SearchRepository` to compose across packs and is entry-level, not sense-level.

⚠️ **Step 1 changes what a pack contains, not how it is read**, so under D-001 the packs are
rebuilt rather than migrated — an hour of build for the Spanish pack, and the app needs no change
beyond rendering a field it already parses.


### ✅ Both directions as a pack feature — CONSTRUIDO 2026-09-21 (D-195 a D-198)

> **Construido, y la medición de abajo es la de ANTES.** El pack es hoy bidireccional por
> construcción: `meta.langs` declara los dos idiomas **como pares**, cada fila lleva `entry.lang`
> y las palabras inglesas son **entradas** —85.505 de ellas— en vez de claves de `trans`, que
> quedó vacía. Se llegó al **nivel 3** de la tabla de abajo, no al 0 ni al 1.
>
> Los cuatro bloqueadores que esta sección enumeraba se cerraron: la columna `lang` existe, el
> `uid` se calcula con el idioma **de la fila**, una entrada sin acepciones vale si trae
> traducciones, y el canal `W` ya vivía fuera de las acepciones desde D-179.
>
> **Medido sobre el pack construido**: 209.484 entradas en 63,4 MiB (**+8,2 MiB**), cobertura
> inversa **97,0 %** del top 1.000 inglés. El texto siguiente se conserva porque explica por qué
> se eligió este nivel y qué costaba cada uno.

### Both directions as a pack feature, and words with no definition — measured 2026-09-21

Asked as a design question, and it deserves the design answer: *can a translation pack hold tables
in both directions, as a **feature of the pack format** rather than a build trick — and can a word
be shown even when no definition for it is available?*

**Yes to both, and the second one costs nothing today**, because the data is already there and
already indexed. What throws it away is one line of SQL.

#### ⚠️ The reverse direction is already a table, already prefix-indexed, and the query discards it

`trans` is `(norm, entry_id)` `WITHOUT ROWID`, so **the table is the index** (D-010) and a prefix
range over English keys is a primary-key seek — verified, not assumed:

```
EXPLAIN QUERY PLAN SELECT norm, entry_id FROM trans WHERE norm >= 'hou' AND norm < 'hov'
  -> SEARCH trans USING PRIMARY KEY (norm>? AND norm<?)
```

And the content it reaches is good:

```
hound        -> can, sabueso, lebrel, podenco ibicenco
hour         -> hora, cuarto, horario, happy hour, hora pico
hourglass    -> ampolleta, reloj de arena, cintura de avispa
houndstooth  -> pata de gallo
```

⚠️ **But `byTranslation` keeps the entries and drops the key:**

```sql
SELECT e.id, e.headword, e.pos FROM entry e WHERE e.id IN
    (SELECT entry_id FROM trans WHERE norm >= ? AND norm < ?)   -- 'hour' is lost here
ORDER BY e.rank LIMIT ?
```

So typing `hou` returns `ampolleta, sabueso, hora, …` — a flat list of Spanish words with **no
indication of which English word each one answers**, and ordered by a `rank` that is page richness
(which is the `house → solar` bug in §Result ordering). The English word the reader typed is
matched, used, and then thrown away.

**Keeping it is the whole feature**: `hour` becomes a row, and opening it shows the Spanish words
it maps to. The pack does not change by one byte, and a word with no definition becomes
displayable — which is exactly the second half of the request.

#### The three levels, priced

| | what it delivers | cost |
|---|---|---|
| **0 — keep the key** | `hour` shows as a row and opens, listing its Spanish entries. Words with no definition become displayable | **0 MB** — `:app` and `:dict-data` only, no pack change |
| **1 — inflection index** (see above) | `hours`, `ran`, `went` reach it too: 78.1 % → 98.9 % | **1.84 MB** (+3.7 %) |
| **2 — stub entries in `entry`** | `hour` gets a `uid`, a `rank`, a fuzzy key: favouritable, in history, typo-tolerant, orderable | **7.84–11.00 MB** (+15.6 % to +21.9 %) |

Level 2 was built and weighed over the real 89,049 English keys, not estimated. ⚠️ **Its cost is
almost entirely structure, not content**: the payloads compress to **1.70 MB (20 bytes per
entry)** and the other 6–9 MB are the `entry` row itself plus its indexes — `idx_entry_fuzzy`
alone is 1.88 MB and the covering `idx_entry_norm` 3.18 MB. Dropping the fuzzy index and making
`idx_entry_norm` non-covering takes 11.00 MB down to 7.84, at the price of no typo tolerance on
English input.

**Level 0 first, and possibly only.** It delivers the visible feature; levels 1 and 2 buy reach
and identity, and can be decided separately once level 0 shows what is actually missing.

#### ⚠️ What level 2 collides with — three enforcers and a missing column

Stub entries are not merely absent today, they are **actively rejected**, and that is worth knowing
before treating them as a small change:

| blocker | where | what happens |
|---|---|---|
| ~~`entry` has **no `lang` column**~~ **cerrado por D-195** | schema | era: una fila inglesa en un pack que declara `es` no se distinguía de una española |
| ~~`uid` is recomputed with the **pack's** language~~ **cerrado por D-195** | `verify_pack.py` | ahora usa `entry.lang`, que es lo que hace imposible la colisión entre idiomas |
| ~~an entry with zero senses is a failure~~ **cerrado por D-196** | `verify_pack.py` | vale sin acepciones **si trae traducciones**: un bilingüe contesta *cómo se dice*, no *qué significa* |
| a `T` before the first `S` is **silently dropped** | `payload.parse`, the `if senses:` guard | entry-level translations have nowhere to live |

So level 2 is a **schema change** — D-001 territory, packs rebuilt rather than migrated — plus a
`uid` recipe that takes the entry's own language, which means bumping `UID_RECIPE` and therefore
invalidating every cross-pack join that exists.

⚠️ **The one door that is already open**: `payload.parse` ends with *«los tags desconocidos se
ignoran a propósito: un builder más nuevo puede agregar campos sin romper un lector viejo»*. A new
entry-level tag would be ignored by today's readers rather than breaking them, so the payload half
of level 2 is additive. **The `entry` table half is not.**

#### Declaring it, which is the part that makes it a format feature

~~Today `kind = bilingual` plus `lang_dst` says the pack **has** a target language.~~ **Cerrado
por D-195/D-196**: `meta.langs` declara los dos **como pares** y `verify_pack.py` comprueba que
haya entradas de ambos, así que la bidireccionalidad dejó de ser un accidente del build y pasó a
ser una propiedad declarada **y verificada**. Lo que decía antes: nothing says
whether the reverse direction is **usable** — the current pack's is 78.1 % at top 8,000 and
returns a different *kind* of answer, and a reader has no way to know that. If bidirectionality is
to be a declared property rather than an accident of the build, it needs to be stated in `meta`
and checked by `verify_pack.py`, the same way `sources` must carry a licence per source (D-138).

That is a decision, not a measurement, and it is not taken here.


### A translations section inside the entry — designed 2026-09-21

The product shape, stated: *translation mode is **complementary** to the structure the app already
has. Search a word, it appears in the results list as it does now; open it, and alongside the
definitions section (when there is one) there is a **translations section**, fed from the
translations DB, presented the way this repo is organised.*

The screen already knows how to do this. What is missing is the data, and the reason it is missing
is sharper than "nobody built it".

#### The shape is already written: a fourth `TermList`

`SenseBlock` renders, per sense: the gloss, the examples, then **three identical lists** built by
the same helper — `TermList(title, terms, links, onOpenWord)` — for synonyms, antonyms and related
(D-126, D-132, D-159). A translations section is **the fourth call**, in the same place, with the
same two-line cost and the same rule that the category word is never optional because the lists
look alike.

And its data has a home: **`Sense.translations` exists in `Model.kt`, `payload.py` writes it as tag
`T`, `PayloadCodec` parses it — and `SenseBlock` does not render it.** The field is empty in all
three real packs, so today the call would draw nothing.

⚠️ **That is the whole feature: one `TermList` call and a build that fills `T`.** No new screen,
no new navigation, no new module.

#### ⚠️ Why it cannot be read from the bilingual pack as it stands

The obvious implementation — join by `uid` and show what the translations DB has — was tried
against real entries, and **both of its two possible sources are the wrong shape for display**.

**Its glosses are definitions, not terms.** In a bilingual pack the gloss *is* the English, so it
looks like a translation list until you read one:

```
tiempo -> time · a while · period of time · tense ·
          weather (the short-term state of the atmosphere at a specific time and place,
          including the temperature, relative humidity, cloud cover, precipitation, wind, etc)
```

**Its `trans` table is a search index, not a reading list.** `bilingual.translation_keys` already
does the cleaning — that is what the module is for — but then D-014 tokenizes every key into its
words so that searching `run` finds `to run`, and `PackBuilder` stores the result of `norm()`:

```
tiempo -> cloud, cloud cover, cover, humidity, long, long time, precipitation,
          relative, relative humidity, tense, time, wind
```

`cover`, `relative` and `long` are tokenizer artifacts of `cloud cover` and `relative humidity`,
correct as search keys and wrong as a list someone reads. And the form is normalised: `U-turn` is
stored `u turn`, `Úbeda` is `ubeda`.

⚠️ **The pack has translations for searching and a place for translations for reading, and only
the first is filled.** `bilingual.py` says it outright — *"the keys are returned raw: `PackBuilder`
normalises them"* — so **the display form exists at build time and is discarded**. Filling `T` with
the raw keys, before tokenization and before `norm()`, is the fix, and it is a build change on a
field that already exists.

#### ⚠️ And the `uid` join is weaker than its headline number

Even with the display form solved, sourcing the section from another pack runs into this, measured
over the **3,000 best-ranked entries of the Spanish pack** — the ones actually opened:

| | |
|---|---|
| have a `uid` twin in the bilingual pack | **1,635 of 3,000 (54.5 %)** |
| terms per entry when they do | median **4**, p90 **10**, max **53** |

`casa`, `perro`, `libro` and `tiempo` have a twin. **`correr` and `mano` do not** — two of the
commonest words in the language. A section that is absent on half the entries a reader opens, with
no pattern they can learn, reads as broken rather than as partial.

#### The order this implies

1. **Render `Sense.translations` as a fourth `TermList`.** Pure `:app`, covered by the gate under
   Robolectric like the other screens (D-110). Draws nothing until step 2, which is why it is
   cheap to land first.
2. **Fill `T` in the monolingual pack from `es.jsonl`'s own `translations` field** — the source
   measured above: **94.5 % of the top 1,000**, 55 % of lemmas carrying `sense_index`, so the terms
   attach to the **right sense** and the `uid` matches by construction. This is what makes the
   section appear, with **one pack installed and no join at all**.
3. **Fill `T` in the bilingual pack** with `translation_keys`' raw output, so that pack also reads
   well on its own.
4. **Only then** the cross-pack `uid` join, for the entries step 2 misses — and priced against the
   54.5 % above, not against the 86.5 % headline.

⚠️ **One thing steps 1–3 do not give: tappable translations.** `EntryScreen` resolves links with
`resolveIn: suspend (Set<String>) -> Map<String, Long>`, which is **one pack's** `norm → entryId`,
and `onOpenWord` navigates inside that same pack — deliberately, because sending it elsewhere is
the D-080 bug. An English term cannot resolve against a Spanish pack, so translations render as
**plain text** unless the link map grows a `packId`, which is a real change to a boundary that
exists for a reason. Painting them as links without it would be a word painted tappable that
navigates nowhere, which D-084 and `TermList`'s own docstring both forbid.

Also note `MAX_PALABRAS_POR_CONSULTA = 64`: the link resolution already runs as **two** queries to
stay under it, and terms are the second. Translations would join that query and push it toward the
cap, where it **truncates silently**.


### Can translations be attached to the right sense, across languages? — measured 2026-09-21

Asked as *«is this very complex to achieve?»*, which deserves a measurement rather than a
judgement — §Alinear acepciones entre fuentes lists three possible paths and says of all of them
**«ninguno medido»**. Two of them are measured here.

**The short answer: within a language it is not complex at all — the source already labels both
sides and the machinery already exists. Across languages it is not complex either; it is empty.**

#### ⚠️ Within a language: the source declares it, and D-117 already built the reader

The suspicion that started this was that `sense_index` looked broken: `alemán` has **2 senses** in
its record and translations at `[1]`, `[2]` and **`[4]`**. It is not broken — **the index numbers
the wiki page, and kaikki splits a page into one record per part of speech**:

```
alemán (adj)    sense_index '1'   Originario, relativo a, o propio de Alemania.
alemán (noun)   sense_index '2'   Persona originaria de Alemania.
                sense_index '3'   Persona de piel clara, cabellos rubios…
alemán (noun)   sense_index '4'   Idioma de la familia germánica occidental…

translations (repeated on every record of the page):  German[1], German[2], German[4]
```

⚠️ **Each sense carries its own label, so the join is a key match and never arithmetic** — which
is exactly what `_by_sense_index` already does for synonyms, and exactly the trap D-117 spells
out: *«la clave es el `sense_index` que declara la fuente, NUNCA la posición»*. The full page's
translation table is attached to every record, and the indices that belong to another record
**simply fail to join**, which is the correct outcome rather than a bug. Sense `'3'` gets nothing,
because the wiki declares no translation for it.

Measured over the whole dump:

| | |
|---|---|
| Spanish senses in entries that have translations | 51,911 |
| **that declare a `sense_index`** | **51,850 — 99.9 %** |
| that receive a translation, exact string match | 11,961 — **23.0 %** |
| that receive one **once index ranges are expanded** | 18,817 — **36.2 %** |

**The one piece of real work is the ranges**: 18.8 % of pairs are written `1-2`, `1, 4` rather
than `3`, and `_by_sense_index` compares strings, so today a sense labelled `'1'` would not match
a translation labelled `'1-2'`. Expanding them is worth **+13.2 points**, and what remains
unparseable is a rounding error — `'1b'` (3 occurrences), `'1 y 2'` (2), `'2 (en el aire)'` (1) —
which is dropped, exactly as D-117 drops a synonym with no index rather than inventing an
attribution.

**And it works where it matters, which is polysemy:**

```
planta  ->  plant    (Forma de vida vegetal…)        vela  ->  candle  (Cilindro de cera…)
planta  ->  floor    (conjunto de habitaciones…)     vela  ->  sail    (Tela resistente…)
banco   ->  bank     ·  pila -> basin  ·  muñeca -> wrist  ·  copa -> cocktail, drink
```

That is the feature working: the same headword, a different English word per sense, with the
source stating the attribution rather than anybody guessing it.

#### Across languages, sense to sense: it works and there is almost none of it

The stronger reading — linking Spanish sense *k* to English sense *m*, not merely to the English
word — has a real mechanism, because the two dumps label from opposite ends:

- `es.jsonl` gives a translation **plus the index of OUR sense**.
- `en.jsonl` gives a translation **plus the text of THEIR sense** (`pound → libra`, *"unit of
  currency"*). Measured: **10,410 EN→ES pairs, and 100 % of them carry that sense text.**

Where both exist for the same word pair, the two halves form a bridge. Measured: **1,461 pairs**,
and they are good — the mechanism distinguishes senses correctly:

```
libra  <->  pound   [unit of mass (16 ounces avoirdupois)]
libra  <->  pound   [unit of currency]
gato   <->  cat     [domestic species]      castaño <-> brown  [colour]
día    <->  day     [period of 24 hours]    palabra <-> word   [unit of language]
```

⚠️ **So the obstacle is not difficulty, it is quantity — and that changes what to do about it.**
1,461 pairs against 152,281 Spanish entries is not a feature; it is a curiosity. And the English
side is **prose, not an index**: reaching an actual sense of the English pack needs that text
matched against its glosses, a second fuzzy step with its own error rate, on top of a base of
1,461.

**Compare with what the within-language path already yields, free, from a field the pipeline does
not read**: 18,817 senses with a correctly attributed translation, no matching, no threshold, no
silent-error risk.

#### What this says to do

1. **Read `translations` in `kaikki.py` exactly as `_by_sense_index` reads synonyms**, and emit
   them into tag `T` per sense. Same function, same discard rule, same test shape.
2. **Expand numeric ranges in the index** — a small generalisation of `_by_sense_index`, worth
   +13.2 points, and it benefits synonyms and antonyms at the same time since they share the
   reader.
3. **Leave sense-to-sense across languages alone.** It is measured, it works, and at 1,461 pairs
   it does not pay for the machinery. Revisit only if a source with real coverage appears —
   which is what the ILI bridge would have been, had the offsets lined up (see above).

⚠️ **Note what this does NOT unblock.** §Alinear acepciones entre fuentes stays open: its 20,644
discarded contributions come from sources that state no sense at all (WordNet, Wikidata, the
enwiktionary examples). This path works precisely because the Wikcionario **declares** the
attribution, and that is the property the other sources lack.

#### ⚠️ The bilingual pack made an ordering bug impossible to ignore

Building it surfaced the sharpest example this repo has of the problem in §Result ordering, and the
number is blunt: searching **`house` returns `solar, alojar, albergar, domiciliar` and `casa` is
nowhere near the top**. `dog` puts `perro` fourth, behind `encalzar` and `uña de gato`.

It is **not** a coverage bug — `casa` does carry `house` as a key. It is `rank`: 993 for `casa`
against **911 for `solar`**, and lower means more common. `rank` measures **page richness in the
dump** (D-063), and in the English Wiktionary's Spanish section `solar` has a longer page than
`casa`.

The forward direction hides this, because the coverage band puts the word you typed on top
(D-142). The reverse direction has no such anchor: every candidate for `house` is an exact hit on
the key, so **`rank` decides alone** and it is deciding badly.

⚠️ **And the signal that would fix it already exists and is already measured**: `tatoeba.frequencies`
ranks Spanish words by real usage — it is what chose the core packs' vocabulary, where `casa` is in
the top and `solar` is not. Wiring it into `rank` is a change to D-063 and needs its own
measurement, which is why it is written here and not done.

**A second pack is still worth considering, but for a different reason than coverage**: what
ES→EN cannot give is an English *entry*. Searching `dog` finds the Spanish words that mean it; it
never shows you an English headword with its own senses. Whether that matters is a product
question, and D-136 lets both coexist whenever it is answered.

**Two things to settle before building either:**

1. ⚠️ **Prune the inflection notes.** A large share of that 88.3 % are entries like *"plural of
   pie"* or *"second-person singular voseo…"*, which the monolingual pack already covers through
   the `form` table. A bilingual pack that keeps them is mostly grammar notes by weight.
2. **Ask whether it is still needed.** ⚠️ **This argument was written against D-168 and D-189
   reversed it**: the cross-language fallback is **off**, so `dog` with Spanish active finds
   nothing on its own. What exists is a manual escape hatch offering the other language (D-255).
   The question survives in a weaker form — one tap already gets you `dog`'s English entry, which
   is lookup across languages and not translation: it tells you what `dog` means, not that it is
   `perro`. Worth confirming the second thing is the one wanted before spending the MB.


### Un botón para filtrar sólo las palabras con traducción — MEDIDO Y APLAZADO 2026-09-21

**Estado.** **No construido, a propósito.** Pedido como *«un botón que muestre u oculte las
palabras que tienen traducción de algún tipo»*, con la condición que lo decidió: *«es solo por si
es que hubieran muchas palabras sin traducción»*. **Se midió, y no las hay donde el botón
serviría.**

**La medición que lo aplazó.** Lo que importa no es cuántas entradas del catálogo tienen
traducción, sino **cuántas de las ~30 filas que se ven en pantalla** la tienen. Sonda sobre los
packs reales, 20 prefijos comunes, aproximando la cascada —peldaño de prefijo, mezcla ordinal,
dedupe por `(lema, pos)`, corte en 30—:

| Idioma activo | Filas con traducción | |
|---|---|---|
| **ES** (`es-def-wikc` + `es-tr-enwikt`) | **396 / 600 = 66,0 %** | el filtro esconde 1 de cada 3 |
| **EN** (`en-def-wikt`) | **38 / 600 = 6,3 %** | el filtro dejaría **2 filas de 30** |

⚠️ **El botón falla en los dos extremos y por razones opuestas.** Con español **no hace falta**:
dos de cada tres filas ya califican. Con inglés **no puede**: vaciaría la pantalla, y un filtro
que deja dos filas no informa, hace parecer que el diccionario está roto.

⚠️ **Y el porcentaje de catálogo habría dado la respuesta contraria**: el pack español tiene
traducción en el **14,8 %** de sus 152.281 entradas, lo que sugiere que faltan muchísimas. Pero
las que faltan son las raras, y la lista de resultados muestra las comunes. Es el mismo error de
muestreo que ya costó una sesión — ver §El orden de las acepciones dentro de una ficha.

**Lo que lo haría valer.** No es trabajo de UI: es **contenido**. El pack inglés tiene traducción
en el **0,5 %** de sus entradas (4.689 de 956.150) porque **no existe el bilingüe inverso
`en-es`**. Construido ése, el inglés pasaría a parecerse al español y ahí el botón tendría algo
que filtrar. **Ésa es la precondición; el botón es el postre.**

**Cómo se construiría, ya medido, para no re-derivarlo.** El predicado es «el payload tiene una
etiqueta `T` o `W`», que es **exactamente lo que la ficha dibuja**, así que no puede discrepar de
lo que el usuario ve:

- **Descomprimir los 30 payloads del resultado cuesta 3,1–3,9 ms** sobre los packs reales, y
  **sólo se paga con el filtro encendido**.
- ⚠️ **Y desde D-196 ese camino ya ni existe en el pack bilingüe**: `trans` quedó vacía porque
  las palabras del otro idioma son entradas. La alternativa que sigue vigente para un pack
  **monolingüe** se describe abajo.
- ⚠️ **La alternativa obvia —consultar la tabla `trans`— es peor por dos motivos medidos.** Es
  más lenta: su PK es `(norm, entry_id)`, así que preguntar por `entry_id` **escanea la tabla
  entera** —21,3 ms para 474.849 filas, 6× el coste del payload— y haría falta un índice nuevo,
  o sea **reconstruir los packs**. Y es **incorrecta**: `trans` nunca sobre-afirma (0 casos) pero
  **se queda corta en 2.032 entradas** del pack bilingüe, porque el builder descartó 26.329 claves
  de búsqueda (`meta.trans_dropped`). Filtrar por ahí escondería 2.032 palabras que sí tienen
  traducción, **en silencio**.
- El método iría en `DictionarySource` **sin implementación por defecto**, como `resolveHeadwords`
  y por el mismo motivo: olvidarlo dejaría el filtro devolviendo cualquier cosa sin dar error.
- El ícono va como **vector drawable propio**, no con `material-icons-extended`: `Translate` no
  está en `material-icons-core`, que es lo único que este APK enlaza, y traer la librería
  extendida por un ícono en un reloj con R8 desactivado no se paga.

**Qué falta decidir antes de construirlo.** Si «activado» significa *mostrar sólo las que tienen
traducción* (semántica de filtro, la que se asumió al medir) o *esconderlas*. La segunda dejaría
la app por defecto sin las palabras traducibles, así que probablemente es la primera — pero está
sin confirmar.


## Aplicación

### Conectar `:app` a `:dict-data`

**Estado.** **Hecho** (2026-09-17). El MVP corre en el emulador contra el pack real.

**En qué quedó.** Tres pantallas —búsqueda, entrada y atribución— sobre `SearchViewModel`
(`debounce` 120 ms + `mapLatest`) y `PackStore`, que abre el pack prefiriendo `filesDir/packs/`
y extrayéndolo del asset del APK si no hay nada. Input por voz (`RecognizerIntent`, forzado a
`es`) y teclado. La atribución sale de `meta.license` y `meta.attribution`, que es lo que D-031
exige para poder distribuir.

**La verificación que lo cerró.** Recorrido completo en el emulador API 33, sobre las 146.194
entradas: buscar `per`, abrir `perro`, leer sus cuatro acepciones descomprimidas, y la pantalla
de licencia. Capturas en la sesión del changelog.

**Qué sigue faltando, y es bastante.**
- ~~`:app` no tiene un solo test~~ **cerrado**: **85 tests JVM** en el gate y **47 de pantalla**
  fuera de él, atribución incluida, que es ship-blocking (D-031).
- ~~**Nunca corrió en un reloj físico**~~ **cerrado el 2026-09-18**: corre en un Galaxy Watch
  (API 37). Lo que sigue sin número es **arranque, latencia y batería** (D-043): para eso hace
  falta Macrobenchmark, que es O-1.
- **El APK debug pesa 50 MB** sin diccionarios adentro (D-071). El release son 35 MB, sin firmar
  y con R8 desactivado (O-2).
- `SearchRepository` sigue sin existir: la app abre **un** pack, no fusiona varios.
- ~~El Tile y la Complication del template.~~ **Hecho** (2026-09-19, D-106 a D-109).

### Tests de UI para las tres pantallas

**Estado.** **Hecho**, y **cambió de sitio**: desde D-110 las pantallas se prueban con
Robolectric **en la JVM y dentro del gate**, no en un dispositivo. De los **89** tests de
pantalla, **88 corren así**; el único que no es tocar una palabra dentro de una glosa, que
depende del layout real del texto.

**En qué quedó.** Cubren densidad, truncado del lema largo, los estados que no son "hay
resultados", el tope de acepciones con su `Ver más`, y la navegación. No usan un
`DictionarySource`: las pantallas son funciones del estado, así que el estado se arma a mano y
no hubo que duplicar el fake de `src/test`.

**D-031 quedó cerrado con tres capas**, porque ninguna sola alcanzaba: un check en el audit
—que corre **en el gate**— comprueba que la pantalla exista y lea de `meta`; el test
instrumentado comprueba que se **vea**; y el test JVM del ViewModel, que la atribución venga del
pack y no de una constante.

**Qué sigue faltando.** Estos 13 **no corren en el gate**: necesitan dispositivo, como los 25 de
`:dict-data`. El gate ve la lógica de `:app` y la existencia de la pantalla de atribución, no
los pixeles.

### Diseño de la interfaz

**Estado.** **Lo que se decide en escritorio está hecho; lo que falta necesita un reloj puesto**
(2026-09-20).

**Cerrado desde la última revisión de esta sección:**

- 🔴 → ✅ **Los 192 dp estaban equivocados y ya no están escritos en ningún lado.** El reloj
  entrega **234 dp**, confirmado dentro de la app. `rowsThatFit` y `clockGap` se calculan contra
  la pantalla real (D-131, D-133) y hay un emulador que reporta lo mismo que el reloj (D-150).
- ✅ **El espacio bajo el reloj**, reportado dos veces, verificado en hardware.
- ✅ **Las filas de palabra dicen todas lo mismo** (D-152) y el selector elige idioma (D-147).
- ✅ **Tres recientes y un botón**, para que los ajustes no queden a varios scrolls (D-148).
- ✅ **Los nombres propios van abajo** salvo match exacto o casi (D-154).
- ✅ **Una guardada se quita manteniéndola apretada** y confirmando (D-155).
- ✅ **El selector de idioma va pegado a la barra y no desaparece al buscar** (D-156).
- ✅ **El botón de voz es un micrófono** y la cadena vive en `contentDescription` (D-157).
- ✅ **La barra del inicio es la de resultados**: buscar grande a la izquierda, micrófono chico a
  la derecha, selector debajo (D-156).
- ✅ **El tipo de palabra se escribe entero dentro de la ficha** y sigue abreviado en la fila
  (D-159). Las 15 claves `pos_full_*` estaban escritas y sin usar.
- ✅ **Ajustes tiene selector de idioma de la interfaz y diagnóstico al fondo** (D-158).

**Lo que falta y SÍ se puede cerrar desde acá** (de la lista de observaciones del 2026-09-20; las
tres que no se construyeron esa sesión):

1. **Respaldo automático entre idiomas.** Pedido: *«que evite generar conflictos cuando la palabra
   que busco está en inglés pero por error seleccioné español»*. El umbral está elegido y no
   implementado: **si la consulta no da ningún match exacto y ninguno en la banda de cobertura
   máxima**, se consultan también los packs de los otros idiomas y sus resultados van **después**
   de los del idioma activo. El punto medio que el pedido pide —*«que no se sobrecargue la
   búsqueda en varios packs innecesariamente»*— es ése: el caso normal no paga nada, porque
   escribir *perr* sí da banda máxima. Cuesta una segunda pasada de `SearchRepository` sobre los
   packs restantes, sólo en el caso malo. **Los packs no se pueden apagar** —decisión del usuario,
   para no complejizar— así que no hay estado nuevo que guardar.
2. **La vista de sinónimos y antónimos, con categoría arriba y palabras clicables.** Pedido:
   *«primero mostrando la categoría y abajo las palabras, pudiendo hacerles click para ir a
   ellas»*. Hoy son tres líneas de texto con prefijo `sin.` / `ant.` / `rel.` (D-126, D-132) y
   **nada es tocable**. El mecanismo para hacerlas tocables **ya existe**: `resolveHeadwords` en
   lote es exactamente lo que hace tocables las palabras de una glosa (D-094), así que esto es
   presentación, no capacidad nueva. Lo que falta decidir es el costo en filas: una categoría en
   su propia línea más las palabras debajo pasa de 1 fila a 2 por lista, y con las tres listas y
   varias acepciones eso empuja mucho hacia abajo en 234 dp.
3. **El caché del tile sigue siendo por pack** (ver §Varios packs por idioma, punto 2).

**Lo que falta, y por qué no se puede cerrar desde acá:**

- **La corona está cableada y nunca se movió.** El emulador no acepta input de corona por `adb`
  (`Unknown command: rotaryencoder`), ni siquiera el de D-150: eso es hardware, no geometría. En
  un reloj puede estar invertida, ser demasiado sensible, o no tener foco.
- **No hay paleta propia.** Se usan los defaults de Wear Material3, pensados para OLED. Elegir
  colores sin un reloj delante es decidir a ciegas sobre contraste y consumo.
- **El ejemplo largo sigue siendo un muro.** Con las acepciones desplegadas, un ejemplo de 900
  caracteres empuja la siguiente fuera de pantalla. La salida evaluada —ejemplos detrás de un
  toque— **no se tomó**, y sigue siendo una decisión de producto: esconder contenido que el
  usuario no pidió esconder. ⚠️ **Diferido a pedido explícito el 2026-09-20**: *«quiero que el
  problema con los ejemplos lo dejes en el roadmap»*. No es que no se haya mirado; es que la
  salida cuesta una decisión que no es del agente.

### Pack de inglés

**Estado.** **Hecho** (2026-09-17), y con una advertencia de calidad sin cerrar.

**En qué quedó.** 956.150 entradas, 309.424.128 bytes, desde enwiktionary sección English. Se
construye con el mismo pipeline que el español: la poda resultó **estructural**, no del idioma
(D-076). Pasa `verify_pack.py` entero.

✅ **MEDIDO el 2026-09-21, y la respuesta es «usable, y claramente peor que el español».**

Sonda sobre el pack reconstruido, replicando el orden real de la app —`matchKind`,
`demoteProperNoun`, `coverageBand`, `rank`— y deduplicando por `(lema, pos)`:

| escribís | posición de la palabra obvia | lo que va delante |
|---|---|---|
| `hous` | 2 | `Hous.` (una abreviatura) |
| `wor` | 3 | `wor`, `wor` |
| `tim` | 5 | `TIM, Tim, TIM, Tim` |
| `wat` | 9 | `wat` ×4, `WAT`, `Wat` |
| `boo` | 6 | `boo` ×3, `Boo`, `-boo` |
| `beaut` | 12 | `beaut` ×2, `beauty` ×4 |
| `chil` · `frien` | 2 | `Child` · `Friend` |

**Posición media: 5,1.** No es el `perro` en la 619 que destapó el español, pero la primera
pantalla —tres filas— **no contiene la palabra obvia** en la mitad de los casos.

⚠️ **La causa es estructural y no es el `rank`: es `coverageBand` (D-142).** Premia los lemas
**cortos** — teclear `wat` cubre `wat` al 100 % y `water` al 60 %, así que `wat` gana la banda
**sin importar la frecuencia**. En español apenas muerde porque hay pocos fragmentos de tres
letras como lema; el Wiktionary inglés está lleno de ellos: interjecciones, abreviaturas, formas
ligadas (`-boo`), siglas. Y `demoteProperNoun` **está desactivado justo en la banda 0**, que es
donde viven, porque ahí se asume que escribiste la palabra entera.

**Una mejora medida, y su límite.** Poner *«tener señal de frecuencia»* por delante de la banda
—usando `meta.rank_signal_boundary`, que el pack declara desde D-198— baja la posición media de
**5,1 a 3,6**. ⚠️ **Pero no resuelve el fondo**: `wat`, `boo` y `beaut` **también tienen señal**
—son tokens reales en subtítulos— así que siguen delante. Es un 30 % de mejora, no una solución.

⚠️ **No se implementó**: toca `orderFor`, que D-185 dejó explícitamente fuera de alcance, y una
mejora parcial a la regla que protege contra packs hostiles merece decidirse con el precio sobre
la mesa, no de paso.

**Lo demás que sigue faltando.**
- ~~El orden de resultados en inglés no se evaluó.~~ **Medido, arriba.** El proxy de `rank` está calibrado para
  verbos españoles: `forms_cap = 80` existe porque un verbo trae 137 formas, y en inglés trae
  cuatro, así que **el tope nunca muerde y `w_form` deja de discriminar**. El perfil `en` ajusta
  ese tope a 12, pero **eso es una corrección a ojo, no medida**. La verificación que falta es la
  misma que en español destapó el `perro` en la posición 619: que `hous`, `wor`, `tim` devuelvan
  `house`, `work`, `time` arriba. En el piloto 1/20 esas palabras **no estaban en la muestra**,
  así que no se pudo juzgar.
- **La búsqueda en inglés nunca se vio en la app.** Se verificó que el selector renderiza con los
  dos packs reales en el emulador; las capturas de la búsqueda en inglés se perdieron antes de
  mirarlas.
- **Nada de esto corrió en un reloj físico**, y menos con 295 MiB.

#### ⚠️ El 38,7 % de la tabla `form` inglesa no son flexiones — medido 2026-09-21

Encontrado de rebote, midiendo el índice inverso del pack bilingüe, y **es un defecto del pack
inglés por sí solo**: de sus **985.992** filas de `form`,

- **381.590 (38,7 %) contienen un espacio** — `big fat hairy deals`, `ate breathed and slept`,
  `1 000 000 questions`. Son frases de ejemplo que el parser dejó caer en la tabla de flexiones.
- **`no table tags` (577 filas) y `glossary` (575) son artefactos de wiktextract**, no palabras.
  Se delatan por repetición: una flexión real casi no se repite.

El contraste con el español cierra el diagnóstico: sus 1.499.895 filas tienen como forma más
repetida `unas`, **16 veces**. El defecto es de la fuente inglesa y del filtro que no está, no del
pipeline.

**Qué cuesta.** Peso muerto en el peldaño `byInflectedForm` del pack inglés —nadie va a teclear
`ate breathed and slept`— y filas que el índice nunca usa. **Qué lo arregla**: el mismo filtro que
el índice inverso ya necesita (una palabra, alfabética, no artefacto), aplicado en `kaikki.py` al
construir. Ahí se midió que **descarta el 35 % de los candidatos sin mover la cobertura ni una
décima**.

Se arregla cuando se reconstruya el pack inglés; no justifica reconstruir 295 MiB por sí solo.

### Qué contenido tiene el pack de demostración

**Estado.** ✅ **Cerrado el 2026-09-21, y por una vía que esta sección no contemplaba: el pack de
demostración se eliminó.** No se reorientó a los núcleos — **desapareció**, y `bundlePacks`
empaqueta los núcleos reales o nada. La pregunta *«qué contenido debería tener el demo»* dejó de
tener objeto.

⚠️ **Lo que se pierde y conviene tener presente**: un clone limpio sin los packs completos produce
un APK **sin diccionario**. La app degrada bien y el build compila (D-086), pero ya no se
auto-abastece. Lo de abajo queda como registro de la decisión previa.

**Estado anterior.** **Decidido el 2026-09-21, sin construir**: *«quiero reorientar el pack demo a usar
los packs core del idioma ES y EN»*. O sea que el demo deja de ser un juguete de 28 entradas y
pasa a ser el **núcleo real** del idioma — ver §Dividir los packs grandes, que tiene el diseño,
el precio en MB y la trampa del `rank`. Lo de abajo es lo que esta sección decía antes, y el
mecanismo que describe sigue siendo el que se usa.

**Estado anterior.** Planificado, y **es una decisión de producto, no de mecanismo**. El mecanismo está
hecho (D-081): `:app:buildDemoPack` genera el `.db` que viaja en el APK, y cambiarlo es apuntar
esa tarea a otro archivo.

Hoy el placeholder lo genera `build_toy.py` — 26 entradas escritas a mano para los tests, cuya
atribución dice literalmente que no es un diccionario real. **Se delata solo, que es lo correcto
para un placeholder**, pero como demo es raro: tiene trampas de test adentro (`vela` dos veces,
`self-made`, homógrafos de `bajo`).

**Con qué choca.** Con acoplar la demo al fixture de los tests: si alguien cambia el toy pack por
una razón de test —como pasó esta sesión, agregando `sol`/`soler`— cambia también lo que ve el
usuario recién instalado.

**Qué hay que decidir.** Qué se quiere mostrar. Un candidato obvio: las **N entradas de mejor
rank** del pack español real, que serían un diccionario chico de verdad. Cuesta una opción nueva
en `build_pack.py` (`--top N`) y volver a bajar el dump.

### Buscar por definición

**Estado.** **Hecho** (2026-09-17). Se llega por una escotilla con la lista vacía (D-084).

Arreglar el orden vino primero: `searchDefinitions` pedía a FTS5 por relevancia y resolvía con
`WHERE id IN (...)`, que devuelve por **rowid** — el ranking se calculaba y se tiraba (D-083).
El fixture no lo permitía ver, porque el mejor match tenía siempre el rowid más bajo; ahora
tiene una trampa con su guardián **en el gate**.

**Qué sigue faltando.** No hay forma de buscar por definición *sin* haber fallado antes: si ya
sabés que querés buscar por significado, tenés que escribir algo que no exista primero.

### Historial de entradas abiertas

**Estado.** **Hecho** (2026-09-17). Tres entradas, en el estado vacío (D-085).

**Qué sigue faltando.** No se puede borrar el historial desde la app, ni una entrada ni todo.
Con tres y move-to-front se recicla solo, pero una palabra que no querés volver a ver se queda
hasta que abras tres más.

### El inicio, las acciones de una palabra, y la búsqueda útil

**Estado.** **Cerrado salvo el catálogo** (2026-09-20). El inicio, el versionado del APK, el
refactor de componentes y **las acciones de una palabra** están hechos: `WordActions.kt` ofrece
guardar/quitar, copiar, y *ver en el otro idioma* **sólo si hay un pack bilingüe abierto** — que
hoy es nunca, porque los dos packs reales son monolingües (D-034). Lo único que sigue abierto de
esta sección es el catálogo de descarga, que vive en §Instalador de packs.

**El inicio NO es una pantalla propia, y esa fue la decisión que reorientó todo** (D-096). La guía
de Wear OS pide *"shallow and linear: avoid hierarchies deeper than two levels"* y elevar la
acción primaria; un menú que enruta a la búsqueda la hunde un toque. El inicio quedó siendo el
estado vacío de la búsqueda, que es lo que ya era, con palabra del día y ajustes agregados. Eso
además cerró D-091: el botón de volver de una entrada tiene un solo destino posible.

**Lo que falta, en orden de costo.**

- ~~**Las acciones de una palabra**~~ **HECHAS.** Wear Material3 **no tiene menú desplegable ni
  overflow**, verificado contra la referencia de API, así que se resolvió con las dos formas
  soportadas: botones lado a lado —48 dp, igual que uno; apilados costarían 96— y `AlertDialog`
  sobre `TransformingLazyColumn`.
- ~~**Gestionar packs: ver y borrar.**~~ **HECHO 2026-09-18** (D-103, D-104). Lo que falta de esa
  pantalla es la mitad de abajo: el **catálogo de descarga**, hoy un WIP explícito que dice cómo
  se instala un diccionario mientras tanto. Sigue bloqueado por lo mismo que el instalador: dónde
  se hostea el catálogo.
- **La búsqueda con opciones y sugerencias.** Lo primero cuando entre: ofrecer *buscar por
  definición* sin tener que fallar antes. Hoy sólo aparece con la lista vacía (D-084), así que
  quien ya sabe que quiere buscar por significado tiene que escribir algo que no exista primero.

**Qué hay que decidir antes.** Qué contiene el menú de opciones más allá de esas tres; y si la
palabra del día también va al Tile, que hoy sigue siendo el del template (D-087). Si va, la forma
correcta según la guía es un `Timeline` con ventanas de validez y refresco ≥ 2 h, sin WorkManager.

### El historial y las guardadas sobreviven a un backup, pero apuntan a otra palabra

**Estado.** ✅ **Cerrado el 2026-09-19 (D-123).** Se resolvió con una **tercera opción que esta
entrada no listaba**: validar el `entryId` guardado contra el lema al abrir, y corregirlo por
`idx_entry_norm` si no coincide. Conserva los datos entre relojes y entre rebuilds *y* cuesta cero
consultas extra en el caso normal —si el lema del id es el guardado, no se resuelve nada—, sin
migración del formato en disco. Lo que sigue abierto es la mitad chica: `pack_activo` también
viaja a un reloj donde ese `.db` no existe, y esa degradación sigue sin comprobarse.

*(Lo que sigue es el análisis original, que es lo que llevó a descartar las dos opciones que
estaban sobre la mesa.)*

**Qué pasa.** `res/xml/data_extraction_rules.xml` excluye **sólo** `packs/`. Las preferencias
viven en `domain="sharedpref"`, que no está excluido, así que `historial`, `favoritos` y
`pack_activo` **sí se respaldan y sí se transfieren** a un reloj nuevo. Y `Visita` guarda
`entryId`, que es la identidad **física**: `schema.sql` lo dice textual, *"`id` … **NO sobrevive
a reconstruir el pack**: una palabra nueva en el medio corre todos los ids siguientes"*.

**El síntoma.** Restaurar un backup —o simplemente reconstruir el pack, que es un flujo normal
con `devpack.py`— deja las tres últimas palabras abiertas y las **hasta 100 guardadas** apuntando
a **otras entradas**, sin un error. Es exactamente la clase de fallo que D-055 y `entry.uid`
existen para prevenir, entrando por la puerta de atrás: `uid` **sí** sobrevive al rebuild.

**Con qué choca.** Con D-102, que decidió reusar `Visita` y su codec para las guardadas: cambiar
el campo toca las tres superficies y su formato en disco, que ya tiene datos de usuarios.

**Qué hay que decidir antes.** Cuál de los dos arreglos, y no son equivalentes:

- **Excluir las preferencias del backup** es **una línea de XML**, y el costo es que un reloj
  nuevo empieza sin historial ni guardadas — que para 3 entradas es ruido y para 100 no.
- **Guardar `uid` junto a `entryId` y resolver por `uid`** conserva los datos entre relojes y
  entre rebuilds, pero cuesta un campo nuevo en el codec, una migración del formato en disco, y
  `uid` **no tiene índice** en el pack a propósito (D-056), así que resolver por él tiene un costo
  de consulta que no está medido.

**Aparte, y más chico:** `pack_activo` también viaja, a un reloj donde ese `.db` no existe.
`elegirActivo` ya cae al idioma del reloj si el preferido no está abierto, así que degrada bien,
pero está sin comprobar.

### ~~Terminar el bilingüe~~ — cerrado (2026-09-20)

**Estado.** ✅ **Cerrado.** Quedan 0 piezas de mecanismo.

**Lo que se hizo, en tres tandas.** D-127 puso las 83 claves en los dos idiomas, sacó
`packTypeLabel` de `PackSet.kt`, le dio a `SearchViewModel` un estado propio en vez de fabricar
texto, y pasó `posInSpanish` a recursos. **D-140** encontró que eso no alcanzaba: había **seis
textos escritos a mano** —*Guardadas*, *Ajustes*, *Opciones* ×2, *Buscar*, *Gestionar*,
*Recientes*— que en un reloj en inglés salían en español, y agregó el chequeo que lo impide.
**D-153** cerró el último hueco: la base inglesa ahora **se dibuja en tests**.

**La decisión que lo frenaba se tomó, y fue la tercera opción**: el locale por defecto de los
tests es español —para que sigan describiendo la pantalla que el usuario ve— **más un puñado de
tests que fijan `qualifiers = "en"`** sobre las pantallas con más texto. Las dos coberturas son
distintas y hacen falta las dos:

| Falla | Qué la agarra |
|---|---|
| Texto traducido escrito a mano en el código | `check_no_hardcoded_translations` (D-140) |
| Clave presente en `values-es/` y ausente en `values/` | `check_locale_parity` (D-127) |
| El valor inglés existe pero **no es el que se dibuja** | `EnglishLocaleTest` (D-153) |

**Verificado en el emulador y en el reloj**: un dispositivo en inglés muestra *type…*, *Say a
word*, *Word of the day*, *Saved*, *Settings*.

**Y ahora el idioma se puede elegir a mano** (2026-09-20, D-158). Las **117 claves** están en los
dos idiomas y Ajustes tiene un selector *Automático / English / Español*. ⚠️ **No guardamos la
elección**: desde API 33 `LocaleManager` la guarda por aplicación y la aplica antes de que corra
un solo Composable, así que una copia nuestra daría dos fuentes de verdad. `check_ui_language_picker`
vigila que la lista del selector y las carpetas `values-*` no se separen: una carpeta sin fila es
una traducción que **nadie puede elegir**.

**Para agregar un tercer idioma** hacen falta exactamente dos cosas: una carpeta `values-xx/` con
las 117 claves y una fila en `UiLanguage`. Si falta cualquiera de las dos, el audit lo dice.

### La voz nativa dicta en el idioma del reloj, no en el del pack

**Estado.** **Sólo queda la decisión** (2026-09-20). Todo el mecanismo que compensaba está puesto
y verificado; lo que falta no es código.

**Lo hecho.** La voz entra por `ACTION_REMOTE_INPUT` —el selector del sistema, con micrófono,
teclado y escritura a mano— en vez de `RecognizerIntent`, que abre sólo el reconocedor de Google
y puede no estar instalado. Verificado en dispositivo.

**Lo que se perdió, y lo que ya lo compensa.** `RecognizerIntent` aceptaba
`EXTRA_LANGUAGE = langSource`, así que se dictaba **en el idioma del pack**; el input del sistema
usa el **del reloj**. Con el reloj en español y el pack inglés activo, dictar transcribe en
español. Dos compensaciones están puestas: **la etiqueta nombra el diccionario** (*«Buscar en
English»*) y **el botón de acción del teclado ya es una lupa**, verificado en captura.

**Lo que hay que decidir.** Si eso alcanza —el caso real es un reloj en español buscando en
español— o si hace falta volver a `RecognizerIntent` **sólo cuando el idioma del pack activo no
es el del reloj**. Eso es un camino condicional, o sea **dos superficies que mantener** y una que
casi nadie ejercita.

**Cerrado por medición** (2026-09-20): el tipo de acción del input **no hace falta fijarlo**. Se
creía pendiente porque `INPUT_ACTION_TYPE_SEARCH` es `internal` en wear-input 1.2.0 —verificado
compilando, no leyendo, porque `javap` las muestra públicas: ésa es la vista de Java—. Pero el
sistema ya ofrece la lupa. Si alguna vez hiciera falta forzarlo, `setInputActionType` **sí** es
pública y el valor es **1**, leído del `.aar` con `javap -constants`.

### Los 234 dp están confirmados: cinco decisiones cotizadas contra 192

**Estado.** ✅ **CERRADO SIN TRABAJO el 2026-09-21 (D-208).** Decisión del usuario: *«ya se ve
bien en el tamaño del reloj y me interesa pensar en la resolución estándar»*. Se diseña contra el
estándar y la app se adapta hacia arriba sola —`rowsThatFit` es genérico—; lo que se descarta es
**rediseñar para aprovechar los 42 dp extra**. Los dos tests de densidad siguen protegiendo la
**relación**, que es lo que importaba.

**La medición, que sigue valiendo.**

Faltaba confirmarlo *dentro* de la app porque `wm density` es la densidad física y Compose puede
ver otra. Se resolvió preguntándole al sistema qué configuración entrega: **`sw234dp w234dp
h234dp 340dpi`**, con los bounds de la Activity en los 498×498 completos. Eso es lo que devuelve
`LocalConfiguration.screenWidthDp`.

**Qué habilita.** Son **22 % más pantalla**. A 48 dp de área tocable entra una **cuarta fila**, que
son **33 % más resultados** sin bajar del mínimo de Wear OS.

**Qué hay que decidir, y es lo que lo frena.** D-073, D-075, D-078, D-084 y D-085 se justificaron
con la aritmética de 192 dp. Revisarlas **una por una** no es mecánico: cada una cambió algo a
cambio de una fila, y con una fila más de presupuesto algunas de esas concesiones dejan de hacer
falta. Y hay que hacerlo **sin romper el reloj genérico**: un dispositivo de 192 dp tiene que
seguir mostrando tres filas, así que el número no se reemplaza — se vuelve **adaptable**
(`LocalConfiguration.screenWidthDp`), y `ScreensTest.threeResultsFitWithoutScrolling` pasa a medir
contra el tamaño que tenga el dispositivo que lo corre.

### ~~El margen lateral~~ — cerrado sin hacer (2026-09-20)

**Decisión del usuario**: *«el margen lateral no es problema, sólo el espacio abajo y arriba, que
ya añadiste, así que esto está bien»*. Los 16 dp se quedan como están.

La medición que lo acompañaba sigue valiendo por si alguna vez se reabre: Wear Compose Material3
declara el padding horizontal como **5,2 %** del ancho (`PaddingDefaults`, leído del `.aar`), que
a 192 dp da 10 y a 234 da 13 — o sea que pasar a la fracción **achicaría** el margen. Ver D-133,
que documenta por qué el espacio vertical sí era una fracción disfrazada de constante y éste no.

### Varios packs por idioma: qué está hecho y qué falta

**Estado.** **La mitad construida, la otra mitad planificada** (2026-09-20).

El modelo mental que ordena todo, y que costó tres decisiones descubrir:

> **Un pack no es un diccionario que el usuario elige. Es una FUENTE de un idioma.** Lo que el
> usuario elige es el **idioma**; los packs de ese idioma se consultan todos y sus resultados se
> mezclan.

**Lo que ya funciona:**

| Pieza | Decisión | Qué hace |
|---|---|---|
| Consultar varios packs a la vez | D-136 | `SearchRepository` consulta todos los del idioma activo y mezcla |
| Que el orden no dependa de un pack | D-142 | La banda de cobertura va delante del `rank`, que cada fuente calibra distinto |
| Saber de dónde vino un resultado | D-143 | Cada fila lleva su idioma abreviado |
| **Un chip por idioma, no por archivo** | **D-147** | Cierra la incoherencia que D-136 dejó abierta |
| Fundir vocabulario en vez de sumar packs | D-145 | Wikidata entró al pack español: 6.092 lemas, cero UI duplicada |
| Que dos packs no colisionen | D-138 | `pack_id` con gramática verificada |
| Que un pack ajeno no rompa nada | D-142 | Las claves se recalculan sobre una muestra al abrir |

**Construido después del plan** (2026-09-20, D-151):

- ✅ **Desambiguar el origen**: la fila dice la **fuente** cuando hay dos diccionarios del idioma
  activo, y el idioma cuando alcanza.
  ⚠️ **Superado por D-190 (2026-09-21)**: la etiqueta pasó a ser **siempre el idioma**. La sigla
  no se entiende sin conocer el `pack_id`, y de qué pack salió una fila es una pregunta de
  catálogo que tiene su propia pantalla. La ficha ahora también lleva la etiqueta, que es donde
  de verdad hacía falta: saltando por una traducción se llega a una entrada de otro idioma.
- ✅ **Una palabra del día por idioma**, no por pack.
- ✅ **La atribución ya era correcta**: la pantalla itera **todos** los packs abiertos, no el
  activo. El plan afirmaba lo contrario y estaba equivocado — se verificó antes de "arreglarlo".

**Lo que falta, y por qué:**

1. ⚠️ **Decidir qué es «el mismo diccionario».** Hoy `es-def-wikc` y `es-def-wikc-tat-wn-wd` son
   dos packs que **conviven**; el usuario espera que el segundo **reemplace** al primero. Ya se
   vio en el reloj: buscar *aquatic* devolvía la entrada del pack viejo, sin los antónimos nuevos.
   Toca D-138, D-070 y el instalador. **Es una decisión de producto y está fuera de lo que un
   agente puede resolver midiendo.**
2. **El caché del tile sigue siendo por pack.** Menos grave que la palabra del día de la pantalla
   —el tile muestra **uno solo**, el del activo— así que no duplica nada; pero comparte la causa.
   Se arregla usando `representativePacks` en `cacheWeekForTile`, y requiere tocar el formato de
   lo que se cachea, que hoy guarda `packId`.
3. **Composición** (sumar campos a una entrada ajena, no filas): la capa existe y el join está
   medido —**8.595 `uid` coinciden**— y sigue bloqueada por la granularidad: `uid` es por entrada
   y un sinónimo es por acepción. **Decisión abierta, no trabajo pendiente.**

**Mejores prácticas que se siguieron, y de dónde salen:**

- **La identidad la declara el artefacto, no el nombre del archivo** (`meta.pack_id` con gramática),
  que es lo que hace que dos fuentes del mismo idioma puedan convivir sin pisarse.
- **El orden no confía en datos ajenos.** La señal primaria —cuánto del lema escribió el usuario—
  se calcula de la consulta, no del pack. Es el equivalente local de no confiar en la entrada.
- **La validación al abrir es una prueba, no una declaración**: `norm_version` es un número que el
  pack se pone a sí mismo, así que se recalculan las claves sobre una muestra.
- **Degradación parcial**: un pack roto no tumba la búsqueda de los otros.

### El emulador que sí sirve para probar

**Estado.** **Hecho** (2026-09-20, D-150). `python3 tools/avd_como_el_reloj.py`.

El AVD que trae Android Studio para Wear (`wearos_small_round`) es **384×384 a 320 dpi → sw192dp**
y **`hw.lcd.circular=false`**. El reloj es **498×498 a 340 dpi → sw234dp** y redondo. El emulador
por defecto **miente en las dos cosas que este repo más pelea**.

Verificado comparando `am get-config` en los dos: ambos dicen
`sw234dp-w234dp-h234dp … round … 340dpi`. Difieren en `highdr`/`lowdr`, que no participa de
ninguna medida. La captura del inicio es indistinguible de la del reloj, incluida la curva.

⚠️ **Lo que sigue necesitando el reloj** (D-043): rendimiento y batería. El AVD iguala la
geometría, no el hardware.

### Dividir los packs grandes en vez de achicarlos

**Estado.** **Diseñado y medido, sin construir** (actualizado 2026-09-21). Decisión del usuario:
*«que los packs muy grandes, en lugar de reducirse, se pueda evaluar dividirlos funcionalmente»*,
y después *«lo del pack core por ahora quiero planificarlo y dejarlo en el roadmap; necesito
entender bien el mecanismo con que funcionará antes de querer implementarlo»*. Lo que sigue es el
diseño con precio; **nada de esto está implementado**.

#### Dónde está el peso, medido sobre los packs de hoy

| | español (73,6 MB) | inglés (315,5 MB) |
|---|---|---|
| `form` (flexiones) | **33,4 MB — 45 %** | 19,5 MB — 6 % |
| `entry` (payloads) | 18,7 MB — 25 % | **143,5 MB — 46 %** |
| FTS + índices | 19,7 MB — **27 %** | 145,0 MB — **46 %** |

Y dentro del payload descomprimido:

| | glosas | ejemplos | tesauro | relacionadas | pos |
|---|---|---|---|---|---|
| español | **70,6 %** | 17,0 % | 5,9 % | 0,8 % | 5,7 % |
| inglés | 49,5 % | **41,2 %** | 3,0 % | 1,8 % | 4,5 % |

**Tres conclusiones que ordenan el diseño:**

1. **Un «módulo de tesauro» ahorraría ~1 MB en español.** No vale un mecanismo. El peso está en
   las glosas, los ejemplos y las flexiones.
2. **Casi la mitad del pack inglés es derivable** (FTS + índices). Eso no es dividir: es qué viaja
   por la red, y ya tiene su propio ítem abierto.
3. ⚠️ **La distinción que hay que no perder: dividir por FILAS ya funciona hoy; dividir por CAMPOS
   necesita composición.** Un pack de nombres propios aparte, o uno de vocabulario núcleo, son
   diccionarios completos y autosuficientes que conviven vía `SearchRepository` (D-136) sin un
   solo mecanismo nuevo. Sacar los ejemplos a un módulo, en cambio, parte una entrada en dos y
   necesita el join por `uid` — que sigue bloqueado por la granularidad.

#### Lo elegido: dividir por NIVEL DE VOCABULARIO

**El bloqueo era no tener una señal de frecuencia** —`rank` es riqueza de página, no uso, y por
eso ponía `castigar` arriba de `casa` (D-142)—. **Resuelto midiendo: el corpus Tatoeba ya
descargado sirve**, contando palabras sobre 442.135 oraciones. Se probaron dos candidatas:

| señal | vocabulario | veredicto |
|---|---|---|
| **Tatoeba** (442.135 oraciones) | 77.340 palabras | ✅ frecuencia de **uso** real |
| glosas del propio diccionario | 80.970 palabras | ⚠️ sesgada: su top trae *apellido*, *gerundio*, *participio* — vocabulario de diccionario, no de habla |

Las dos meten `casa, perro, hacer, agua, comer, libro, verde, correr` en el top 8.000 y dejan
fuera `guanaco, lixiviar, tómbolo`. **Gana Tatoeba** porque la otra mide cómo se escriben las
definiciones, no cómo se habla.

**La curva de tamaño, estimada sobre el pack español real** (payload comprimido + sus formas + la
parte proporcional de FTS e índices):

| top N palabras | entradas | MB | % del pack completo |
|---|---|---|---|
| 3.000 | 3.535 | 1,9 | 3 % |
| 10.000 | 8.703 | 4,6 | 6 % |
| **20.000** | **14.388** | **7,5** | **10 %** |
| 40.000 | 22.831 | 12,1 | 16 % |
| 80.000 | 33.113 | 17,4 | 24 % |

**Un pack núcleo de 20.000 palabras pesa 7,5 MB en vez de 73,6.** Diez veces menos, con el
vocabulario que la gente usa.

**Lo que costaría construirlo**, y es poco: `tatoeba.frequencies()` en el módulo que ya lee ese
archivo —mismo tokenizador, para no tener dos ideas de qué es una palabra—, un filtro por lema en
`build_pack`, y el sufijo `-core` en el `pack_id`. ⚠️ **El corpus que ELIGE las palabras se
declara igual en `meta.sources`**: no se distribuye texto de Tatoeba, pero se usó para decidir el
contenido, y `sources` existe para contestar cómo se armó el pack (D-138).

⚠️ **Núcleo y completo son alternativas, no compañeros.** Instalar los dos no aporta nada: el
completo contiene al núcleo. Eso los distingue de los packs de fuentes distintas, que sí se suman.

#### ⚠️ La trampa que puede multiplicar por diez el tamaño: elegir por `rank`

Medido el 2026-09-21, y es lo que puede hacer fracasar la implementación sin que nadie entienda
por qué:

| | |
|---|---|
| Formas flexionadas del pack español | **1.499.895** para 152.281 entradas |
| De ésas, **de verbos** | **1.393.997 — el 93 %**, a 33 formas por verbo |
| Un core de 14.388 entradas elegido **por `rank`** | se lleva **1.366.667 formas: el 91 % de la tabla** |
| El mismo conteo **sin verbos** | 28.032 formas — **el 1,9 %** |

`form` es la tabla más grande del pack español —**32,9 MB de 71,7**, el 46 %— así que quien
construya el core filtrando por `rank` va a obtener un pack de decenas de MB y va a concluir que
la idea no servía. **No es la idea: es el criterio de selección.**

La estimación de 7,5 MB de arriba **sólo se sostiene con la selección por frecuencia**, porque un
top de uso trae unos pocos miles de verbos y no los 41.724 que tiene el pack. Es la misma razón
por la que se eligió Tatoeba sobre el `rank` del propio diccionario, ahora con un segundo motivo
que entonces no se conocía: no es sólo que ordena mejor, es que **evita arrastrar la tabla de
flexiones entera**.

En inglés el problema no existe: **1,0 formas por entrada** contra 9,8 del español.

#### Cómo conviven dos packs del mismo idioma sin duplicarse

La pregunta que hay que entender antes de construir nada. **La mayor parte ya funciona**, y
conviene saber qué parte es cuál.

**Lo que ya pasa hoy, sin agregar nada:**

1. `SearchViewModel` arma el repositorio con **todos** los packs del idioma activo (D-136).
2. `SearchRepository` los consulta a todos y junta las respuestas.
3. **Deduplica por `(lema, tipo)`** antes de recortar a 30: dos packs que tengan `casa · sust.`
   producen **una** fila, no dos. Eso ya está probado —`el mismo lema de dos packs sale UNA
   vez`— y el criterio de cuál gana es el orden completo, no el azar.
4. Cada fila lleva su `packId`, y la etiqueta de la fila dice **el idioma** (D-190, que
   revirtió D-151).

**O sea que «duplicarse en las queries» no es el problema: el problema es el trabajo de más.**
Con el completo instalado, consultar además el núcleo es preguntar dos veces por un subconjunto —
cada respuesta del núcleo o ya vino del completo y se descarta al deduplicar, o es un lema que el
completo no tiene, **lo cual no puede pasar si el núcleo es un subconjunto**.

**Lo que falta, entonces, es una sola cosa: que el núcleo se haga a un lado.** Y hay dos formas,
con precios distintos:

| Forma | Cómo | Qué cuesta |
|---|---|---|
| **Por declaración** | `meta.tier` = `core` \| `full`. Si hay un `full` del idioma X abierto, los `core` de X no se consultan | Una clave de meta nueva y una línea en `repositoryFor`. **Explícita**: el pack declara qué es, igual que `pack_id` declara su identidad (D-138) |
| Por nombre | inferir de que el `pack_id` termine en `-core` | Gratis y **frágil**: es adivinar del nombre lo que D-138 decidió que se declara. Ya se rechazó una vez para la fuente |

⚠️ **Recomendación: `meta.tier`**, y no porque sea más elegante. Un pack de la comunidad puede
llamarse como quiera; lo único que la app puede creer es lo que el artefacto declara y el
validador comprueba.

⚠️ **Y una asimetría que hay que decidir**, porque es donde esto se cruza con *«qué es el mismo
diccionario»* (la decisión abierta): ¿el núcleo se **desinstala** solo al instalar el completo, o
se queda en disco sin consultarse? Quedarse cuesta 7,5 MB y permite volver atrás si el usuario
borra el completo; desinstalarlo recupera el espacio y deja al usuario sin diccionario si la
descarga del completo falla después. **Esto es producto, no mecanismo.**

#### Las cinco preguntas que hay que contestar antes de construirlo

Planteadas el 2026-09-21. Tres ya tienen respuesta en el código o en una medición; dos son
decisiones abiertas y se marcan como tales.

**1. ¿Núcleo y completo son duplicados o complementarios?**

✅ **DECIDIDO el 2026-09-21: duplicados.** *«Quiero que el pack completo tenga duplicados de lo
que ya tiene el pack core»*. El núcleo es un subconjunto del completo. La alternativa (que el completo traiga
sólo lo que al núcleo le falta) elimina ~7,5 MB de repetición y a cambio rompe la propiedad que
hace que todo lo demás funcione: **hoy cada pack es un diccionario completo y autosuficiente**.
De eso vive que un pack roto no tumbe la búsqueda de los otros (`SearchRepository`), que se pueda
borrar cualquiera desde Ajustes (D-104), y que instalar packs de fuentes distintas sume en vez de
requerir un orden. Con packs disjuntos, borrar el núcleo mutila al completo y el completo solo no
sirve.

**El precio de duplicar está medido: ~10 % del pack completo.** Es barato comparado con convertir
dos archivos independientes en dos mitades que se necesitan.

**2. ¿De qué pack se sacan las definiciones?**

**Para núcleo y completo la pregunta no existe: son los mismos bytes**, construidos del mismo
volcado. Da igual cuál conteste.

Para packs de **fuentes distintas** sí existe, y ya está resuelta: `SearchRepository` ordena todo
junto y **deduplica por `(lema, tipo)`**, así que gana el que quedó primero según el orden
completo —tipo de coincidencia, nombre propio, banda de cobertura, `score`—. Y abrir una entrada
**lleva el `packId`** (D-080), así que la ficha que se abre es la del pack cuya fila tocaste, no
la del pack activo. ⚠️ Lo que ese orden **no** promete está escrito en `SearchRepository.ORDEN`:
`score` deriva de un `rank` que cada pack calcula contra su propio volcado, así que sirve para
poner un prefijo arriba de un parecido fonético, **no** para afirmar que la primera fila es la
mejor de los dos packs.

**3. ¿Se cargan y consultan todos los packs de un idioma? ¿Cómo se detecta compatibilidad?**

**Sí, todos los del idioma activo** (D-136). Y la compatibilidad ya se comprueba **al abrir**, en
dos niveles:

| Nivel | Qué comprueba | Qué pasa si falla |
|---|---|---|
| **Duro, al abrir** | `schema_version`, **`norm_version`**, `payload_codec`, el sha256 del diccionario de payload, y **64 claves recalculadas** (D-142) | El pack se rechaza entero y los demás siguen andando |
| **Blando, medible aparte** | `compare_calibration.py`: Spearman entre los `rank` de las entradas que comparten `uid` | Nada automático: es información |

⚠️ **`norm_version` ES la compatibilidad**, y por eso es la validación más importante del repo:
dice que los dos packs calcularon las claves de búsqueda con las mismas reglas. Dos packs que lo
comparten se pueden mezclar sin que falten palabras; uno que miente se agarra con la muestra de
claves.

El nivel blando responde otra cosa: **cuánto se están de acuerdo**. Medido entre los dos packs
españoles reales: **ρ = +0,388**, control barajado +0,001. Dos fuentes honestas que comparten
señal sin ser intercambiables. Un ρ bajo **no condena** a un pack: dice cuánto se apoya la mezcla
en una calibración ajena, y desde D-142 se apoya poco.

**¿Se paga en cada arranque?** Parte sí y parte no, y está medido (D-164):

| | Cuándo corre | Costo, los dos packs reales |
|---|---|---|
| `schema_version`, `norm_version`, `payload_codec`, sha256 del diccionario | **Cada vez que abre la app** | **6,30 ms** |
| Las 64 claves recalculadas (D-142) | **Sólo la primera vez** que se ve ese archivo | 35 ms, una vez |
| Spearman entre packs | **Nunca en el reloj**: es `compare_calibration.py`, de escritorio | — |

O sea que el arranque pasó de **41,33 a 6,30 ms**. Lo caro se hace una vez y se recuerda con una
huella que incluye `NORM_VERSION`, así que un cambio en `norm()` vuelve a probar todo.

**4. ¿Las entradas de una misma palabra se muestran juntas? ¿Se complementan o se duplican
acepciones?**

**Hoy: ninguna de las dos. Se elige una y la otra se esconde.** Ésa es la respuesta honesta y
conviene tenerla clara antes de decidir:

- En la **lista** de resultados, `casa · sust.` de dos packs produce **una** fila.
- En la **ficha**, se ven las acepciones de **un** pack. Las del otro no aparecen en ningún lado.

Complementarlas —una ficha con las acepciones de los dos— **es la composición**, y sigue
bloqueada por la granularidad: `uid` identifica una **entrada**, y una acepción es más fina. Con
lo que hay se puede decir *«estas dos entradas son la misma palabra»* (8.595 coinciden entre los
dos packs españoles) pero **no** *«esta acepción de acá es la misma que aquella de allá»*. Sin
eso, mezclarlas duplica acepciones equivalentes escritas distinto, que en una pantalla de reloj es
peor que mostrar una sola fuente.

⚠️ **Para núcleo y completo esto también es moot**: mismas entradas, mismos `uid`, mismas
acepciones.

**5. ¿Y si hay varias versiones del mismo pack?**

**El mecanismo para detectarlo está completo desde hoy, y no lo usa nadie:**

- `pack_id` es la identidad del diccionario, con gramática verificada (D-138). Dos archivos con el
  mismo `pack_id` **son el mismo diccionario**.
- `data_version` es `AAAAMMDDHHMM` derivado del build (D-170), así que **el mayor es el más
  nuevo**, y dos builds del mismo volcado ya no empatan — que era justo el bug.

✅ **CONSTRUIDO el 2026-09-21** (D-171), como una sola función con las dos reglas —eran la misma
forma, y separarlas habrían sido dos recorridos con el mismo bug:

| Regla | Qué hace | Quién la usa hoy |
|---|---|---|
| **De cada `pack_id`, el `data_version` mayor** | Dos archivos con el mismo `pack_id` son el mismo diccionario (D-138) y el build viejo es estrictamente peor (D-170) | **Sí**: cierra un defecto de hoy |
| **Un pack no se consulta si el que lo contiene está** | La que el núcleo necesita | **No**: ningún pack declara `subset_of` todavía |

⚠️ **Y la distinción que lo hace correcto: instalado y consultado dejan de ser la misma lista.**
Ajustes sigue mostrando **todo** lo que ocupa disco —si no, un pack que no se consulta se vuelve
invisible y no hay forma de borrarlo— y la búsqueda pregunta sólo a lo que puede aportar algo.

**¿Vale la pena declarar cuál es más completo?** Sí, y **`entry_count` no alcanza**: dice cuál es
más grande, que es otra cosa. Dos packs de fuentes distintas pueden ser los dos grandes sin que
ninguno contenga al otro, y ahí consultarlos a los dos es exactamente lo que se quiere (D-136). Lo
que hay que declarar es **contención** —*«todo lo que yo tengo, ése lo tiene»*—, que es justo lo
que no se puede deducir en el reloj: comparar 150.000 lemas costaría más que la búsqueda.

#### El núcleo dentro del APK

Pedido: *«reorientar el pack demo a usar los packs core del idioma ES y EN»*.

**Hoy el APK lleva un pack de juguete de 53 KB** con 28 entradas, que existe para que una app
recién instalada muestre algo y que **se nota a propósito** que no es un diccionario real (D-081).
Reemplazarlo por los núcleos cambia la app de *«instalá un diccionario»* a *«ya tenés uno»*.

**El precio, con los números de hoy:**

| | MB |
|---|---|
| APK actual, con R8 y el pack de juguete | **5,47** |
| \+ núcleo español (20.000 palabras, estimado) | ~13 |
| \+ núcleo inglés (falta el corpus para estimarlo) | ? |

⚠️ **Los `.db` NO se comprimen dentro del APK** y hay que declararlo, o el instalador los
descomprime a `filesDir` duplicando el espacio — que es exactamente el problema que D-071 vino a
cerrar cuando el pack viajaba como asset. Con `PackStore.installAtomically` ya resuelto, lo que
falta es medir si extraer 13 MB en el primer arranque es aceptable en un reloj.

##### ¿Se puede abrir el pack **dentro** del APK, sin extraerlo?

Pedido: *«que el pack core se pueda cargar desde dentro del apk y no se descargue nunca a
memoria»*. **Respuesta corta: no con este driver**, y conviene dejar escrito por qué para que
nadie lo vuelva a investigar.

Un asset vive **dentro del ZIP que es el APK**, en un offset. SQLite abre por **ruta** y asume que
el archivo empieza en el byte 0. Las tres formas conocidas de saltear eso necesitan una API que
`androidx.sqlite` **no expone** — verificado con `javap` sobre `sqlite-bundled 2.7.1`, cuya
superficie entera es `open(String)` y `open(String, Int)`:

| Camino | Qué haría falta | ¿Está? |
|---|---|---|
| `AssetManager.openFd()` + offset | Un **VFS propio** registrado con `sqlite3_vfs_register` | ❌ no expuesto |
| `sqlite3_deserialize` | Cargar el `.db` **entero en RAM** | ❌ no expuesto, y en un reloj 7,5 MB de heap permanente es caro |
| Descomprimir al vuelo | SQLite necesita acceso aleatorio; un stream no sirve | ❌ imposible por diseño |

**Lo que sí hay, y ya funciona hoy: extraer una vez.** Es exactamente lo que `PackStore` hace con
el pack de demostración desde siempre, con copia atómica (D-082) y sin volver a validar en cada
arranque (D-164). **Cuando el núcleo ocupe ese lugar no hace falta un solo mecanismo nuevo**: es
cambiar el `.db` que viaja en los assets.

⚠️ **Y el costo de duplicar, que es la objeción real, no aplica a esta escala.** D-071 mató la
extracción cuando el pack pesaba **295 MB**. Un núcleo de ~7,5 MB duplicado son 7,5 MB de más
sobre un reloj con **40 GB libres** medidos: **0,02 %**. La objeción era correcta y sigue siéndolo
para el pack completo; para el núcleo no.

##### Mostrarlo como incluido y sin borrar — ✅ **hecho** (D-173)

El pack del APK **ya** no ofrecía borrar —volvería sola al reiniciar— pero la fila **no decía por
qué**, y un botón que falta sin explicación se lee como un bug. Ahora la fila dice *«Incluido en la
app»* **en lugar del tamaño**, que es justo el dato que ahí sobra: el tamaño sólo sirve para
decidir si conviene borrarlo.

Y el flag pasó de `isDemo` a `isBundled`, porque lo que todos sus usos preguntan es *«¿lo trajo la
app o lo puso el usuario?»*. Que hoy el pack incluido sea uno de juguete es una propiedad de este
build, no de la regla.

⚠️ **Y decide algo que hoy es gratis: el APK deja de ser universal.** Un núcleo por idioma dentro
del APK significa que todos los usuarios cargan todos los idiomas, o que hay un APK por idioma.
La salida estándar —Play Feature Delivery por idioma— **no está documentada para Wear OS**, que
es la misma razón por la que D-038 descartó Play Asset Delivery.

**Lo que queda sin decidir**: el `N`. 20.000 es el punto donde la curva se aplana, no una medición
de qué necesita un usuario. Y para el inglés hace falta el corpus inglés de Tatoeba, que no está
descargado.

#### Las otras divisiones, cotizadas y no elegidas

| Qué | Ahorro | Por qué no ahora |
|---|---|---|
| **FTS e índices se reconstruyen en el reloj** | **145 MB inglés (46 %)**, 19,7 MB español | El mayor ahorro con diferencia, pero **el artefacto deja de ser el que `verify_pack` validó**. Tiene su propio ítem |
| **Ejemplos como módulo** | 41,2 % del payload inglés (~59 MB) | División por campos: necesita composición, bloqueada por granularidad. Y con la decisión de abajo, los ejemplos dejarían de ser buscables |
| **Nombres propios aparte** | ~5 MB español, ~43 MB inglés | División por filas: **funciona hoy sin mecanismo nuevo**. Es la más barata si se quiere algo inmediato |

#### Cómo interactúan varios packs del mismo idioma en la interfaz

**Decidido** (2026-09-20): **el origen se muestra sólo cuando desambigua**. Con un diccionario del
idioma activo la fila dice `sust. · ES`; con dos del mismo idioma pasa a decir `sust. · wikc`.
Cuesta cero —`packId` ya viaja en cada `Suggestion`— y no gasta ancho cuando no hace falta.

⚠️ **El costo que hay que vigilar**: la etiqueta **cambia sola** al instalar un segundo
diccionario, y eso puede leerse como inconsistencia. Sin construir.

#### Qué puede hacer un módulo en una consulta

**Decidido** (2026-09-20): **un módulo sólo enriquece una entrada ya encontrada; nunca produce
filas**. Es lo más simple y no toca la capa de consulta.

⚠️ **Y tiene una consecuencia que conviene tener presente antes de mover nada a un módulo: lo que
tiene que ser BUSCABLE no puede salir del pack base.** Hoy los sinónimos entran a `fts_def`
(D-118), así que escribir *bobo* encuentra *chulengo*; los ejemplos también, así que la búsqueda
por definición los alcanza. Con esta decisión, mover el tesauro o los ejemplos a un módulo
**rompería las dos cosas**.

En la práctica eso convierte la regla en un criterio de qué puede modularizarse: **contenido que
sólo se lee al abrir una palabra, sí; contenido que participa de la búsqueda, no**. Las
relacionadas (`R`) cumplen —nunca entraron a `fts_def` (D-132)—; los sinónimos y los ejemplos, no.

### Un pack reconstruido no se distingue del viejo: `data_version` es la fecha del DUMP

**Estado.** ✅ **Cerrado** (2026-09-22): la primera mitad por `data_version`, la segunda por D-215.

✅ **Resuelto: `data_version` ya NO es la fecha del dump.** Se tomó el primer camino de los tres de
abajo —la fecha del **build**, `AAAAMMDDHHMM`— y se pagó su objeción con una clave nueva:
`source_date` conserva de qué dump sale el contenido. Verificado en los packs reales:
`es-tr-enwikt` declara `data_version=202609210540` y `source_date=20260915`. El
`es-def-wikc.db` viejo todavía muestra `20260915` porque es anterior al cambio, y **el rebuild lo
corrige solo**.

✅ **Y el segundo síntoma lo cerró D-215, sin que esta sección se enterara** (visto el
2026-09-22 revisando el roadmap). La pregunta era *«qué cuenta como el mismo diccionario»*: D-138
sufijaba el `pack_id` por variante —`es-def-wikc-tat-wn`— así que dos variantes eran dos ids
distintos y **convivían** en vez de reemplazarse, y `packsToQuery` deduplica por `pack_id` (D-171)
pero ahí los ids difieren.

**D-215 ataca justamente eso**: la identidad pasa a ser `<idioma>-<nivel>` —`es-full`, `es-core`—
y **deja de cambiar al sumar una fuente**, que era la causa. Las fuentes siguen declaradas en
`meta.sources`, que es donde se consultan. Lo que queda no es diseño sino artefacto: los cinco
packs publicados todavía llevan la identidad vieja, y eso lo cierra el rebuild — está anotado en
§🔁 Reconstruir los packs.

⚠️ **Es la segunda sección del roadmap que se encuentra desfasada en dos sesiones**, después de
§La calidad del contenido del pack español. El patrón es el mismo: una decisión resuelve el
síntoma de otra sección y nadie vuelve a la que lo describía.

**Estado anterior.** **Encontrado construyendo, sin decidir** (2026-09-20).

⚠️ **Y ahora hay un segundo síntoma, visto en el emulador** (2026-09-20). D-138 hace que cada
variante sufije el `pack_id` —`es-def-wikc-tat-wn`— justamente para que dos variantes puedan
**convivir y compararse**. El costo apareció al instalar el pack mejorado: el viejo **no se
reemplaza, se queda al lado**, y como los dos declaran el mismo idioma, D-136 los consulta a los
dos. Buscar *aquatic* devolvió la entrada **del pack viejo, sin los antónimos de WordNet**, y no
había forma de notarlo desde la app: las dos filas se ven iguales.

Se resolvió a mano borrando el archivo viejo. **La pregunta de producto es qué cuenta como "el
mismo diccionario"**: si `es-def-wikc` y `es-def-wikc-tat-wn` son dos diccionarios que conviven o
dos versiones donde la nueva reemplaza a la vieja. Hoy el formato dice lo primero y el usuario
espera lo segundo. Toca D-138, D-070 y el §Instalador.

D-132 agregó contenido a los dos packs **sin cambiar el dump**: mismo `es.jsonl` del 20260915,
mismo `en.jsonl` del 20260909. Como `data_version` es la fecha del dump —y es lo que la app
compara con `.toInt()` para saber cuál de dos packs es más nuevo (D-070)— **los packs nuevos
declaran la misma versión que los viejos**. Un reloj con el pack anterior no tiene forma de saber
que hay uno mejor.

Hoy no rompe nada porque los packs se copian a mano y el instalador no existe. Pero el instalador
es lo siguiente (§Instalador de packs), y esto es un requisito suyo.

**Qué hay que decidir, y por eso está acá y no resuelto.** Son tres caminos con costos distintos
y ninguno es obviamente el correcto:

- **`data_version` pasa a ser la fecha del BUILD.** Una línea. Pierde el dato de qué dump es, que
  es justamente lo que hoy responde *«¿este pack trae las palabras de septiembre?»*.
- **Una clave nueva, `content_revision`,** que sube cuando cambia el builder y no el dump. Honesta
  y ordenada; hay que decidir quién la incrementa y que no se olvide, que es cómo mueren estas
  claves.
- **`data_version` se queda y el catálogo del instalador lleva su propia versión.** Empuja el
  problema al catálogo, que todavía no existe: puede ser lo correcto o puede ser patearlo.

Toca D-070 y el §Instalador. **No se resuelve construyendo un pack** — se resuelve decidiendo qué
pregunta tiene que contestar esa clave.

### La superficie glanceable: qué hay, qué se comparte y hacia dónde va — INVESTIGADO 2026-09-21

**Estado.** **Investigado y planificado, no construido** (salvo lo que se marca ✅). Pedido:
*«que los tiles se basen en los mismos componentes que muestran esas funciones en la app»* y
*«investigar y planear qué tipos de tiles hay disponibles y qué nuevas funciones podríamos
aplicar»*. Fuentes primarias, no resúmenes: la guía de diseño de Wear OS, la de migración a Wear
Widgets y las notas de versión de `glance-wear`.

#### La respuesta corta a «los mismos componentes»: en el render, **no se puede**

⚠️ **Un tile no dibuja con Compose.** ProtoLayout serializa un árbol a protobuf y lo **renderiza
el sistema**, en otro proceso: no hay recomposición, no hay `@Composable`, no hay estado. Por eso
`TileRender.kt` construye `LayoutElement` y no `Modifier`. **Compartir componentes de UI entre la
app y un tile es imposible hoy**, y no por cómo está escrito este repo.

Lo que **sí** se comparte, y ya estaba:

| | cómo |
|---|---|
| **Las decisiones de contenido** | `TileContent.kt` es puro, sin Android, y corre en el gate: cuántas filas, qué palabra toca hoy, cuándo mostrar vacío |
| **Cuántas filas caben** | `rowsThatFit(screenWidthDp)`, la misma función que el inicio, contra `requestParams.deviceConfiguration` |
| **Las etiquetas** | `posLabel(context, pos)` — la sobrecarga con `Context` existe para esto |
| ✅ **El detalle de una fila** | `wordDetail(context, …)`. **Nuevo**: el tile de recientes decía sólo `perro` donde el inicio dice `perro · sust.`, y no por densidad sino porque no compartía nada |

⚠️ **Lo que un tile NO puede compartir aunque quiera**: la etiqueta de idioma. Sale del idioma
activo, que un tile no conoce sin abrir un pack — prohibido por D-106 — y una `Visit` no lo
guarda. Inventarlo sería afirmar una procedencia que nadie comprobó (familia D-080).

#### Qué tipos de superficie glanceable existen, verificado

| | qué es | estado para este repo |
|---|---|---|
| **Tile full-screen** (`androidx.wear.tiles` + `protolayout`) | Lo de hoy. Estructura de tres ranuras: `titleSlot`, `mainSlot`, `bottomSlot`, con *breakpoint* en **225 dp** para revelar más contenido | ✅ **en uso**, con `protolayout-material3` y `primaryLayout` — que es la API vigente |
| **Wear Widget** (`glance-wear` + RemoteCompose) | Desde Wear OS 7 los tiles full-screen **evolucionan** a widgets: DSL declarativa tipo Compose, altura parcial, dos tamaños (**pequeño 2×1** y **grande 2×2**, alineados con los de móvil) | ⏸️ **sigue postergado (D-024)**, y hoy se verificó por qué: `glance-wear` va por **1.0.0-alpha14** y RemoteCompose por **1.0.0-alpha17** |
| **Complicación** | Un dato suelto en la esfera | ❌ apagada a propósito (D-108): costaba 24 despertares diarios |
| ~~`glance-wear-tiles`~~ | — | ❌ **prohibida** (D-025): deprecada, y el nombre confunde — no es la librería de widgets |

⚠️ **No hay fecha de deprecación anunciada para los tiles**, y la guía de migración **recomienda
mantener los dos servicios** enlazados por el atributo `group`, para que el sistema migre solo la
ranura del carrusel en Wear OS 7+. O sea: migrar no es tirar lo que hay.

#### Qué se gana y qué se pierde al migrar a widgets, según la guía

**Se gana**: DSL declarativa, actualizaciones de UI **sin ida y vuelta al servicio** (`ValueChange`,
`rememberMutableRemoteInt`), integración de altura parcial en el carrusel.
**Se pierde**: el `bottomSlot` fijo —las acciones pegadas al borde hay que rediseñarlas— y la
densidad: un widget es *glanceable*, no una pantalla llena. La guía dice explícitamente **no
portar un tile uno a uno**.

#### Mejoras propuestas, ninguna construida

| # | qué | por qué, y qué cuesta |
|---|---|---|
| 1 | **Usar el breakpoint de 225 dp** | La guía dice *«nunca mostrar menos información en pantallas más grandes»*. Hoy el tile **se topa en 3 filas y se niega a crecer**, con un motivo honesto escrito en el código: el chrome del renderer **no está medido**. El trabajo es medirlo en el reloj y dejar 4 filas donde entren |
| 2 | **Un tile de «seguir leyendo»** | La última palabra abierta, no la última visitada: hoy los recientes y el historial son lo mismo. Cuesta una clave de preferencia |
| 3 | **La palabra del día con su primera acepción** | El `titleCard` ya admite contenido; hoy muestra sólo el tipo. La glosa es lo que la vuelve útil de un vistazo — pero **la caché sólo guarda `Visit`**, que no la trae: costaría guardarla, y `Visit` es un contrato con el disco |
| 4 | **Atajo a la búsqueda por voz** | Un tile no acepta texto (D-026), pero **sí puede lanzar un intent**. El `bottomSlot` está vacío y es justo donde la guía pone la acción |
| 5 | **Preparar el salto a widgets** | Servicio dual, enlazado por `group`. ⚠️ **No antes de que `glance-wear` salga de alpha**: D-024 se revisó hoy y sigue valiendo |

⚠️ **El orden no es el de la lista**: el #1 es el único que necesita el reloj, y los demás son
independientes entre sí. El #3 es el que más cambia lo que el usuario ve y el único que toca un
formato en disco.

### 🔌 Preparado para la sesión con el reloj — 2026-09-21

**Todo lo de escritorio está cerrado.** Lo que queda necesita hardware, y esta sección existe
para que esa sesión no gaste su tiempo en redescubrir cómo se prepara.

#### Lo que ya está listo

| | |
|---|---|
| APK | `app/build/outputs/apk/debug/app-debug.apk`, **63,1 MB**, con los dos núcleos dentro |
| Packs | `../wearos-dictionary-data/`: `es-def-wikc` 73,6 · `en-def-wikt` 306,8 · `es-tr-enwikt` 63,4 MiB. **`schema_version` 4** |
| Verificado en emulador | los cinco packs cargados, **0 crashes**, 39+7 instrumentados verdes |

#### El orden, y por qué ese orden

1. **Conectar y confirmar la geometría.** `adb shell am get-config` tiene que decir
   `sw234dp … round … 340dpi`. ⚠️ El puerto de depuración inalámbrica **cambia en cada sesión**:
   hay que pedírselo al usuario, no adivinarlo.
2. **`./gradlew :app:connectedDebugAndroidTest` ANTES de empujar packs.** ⚠️ **Desinstala la
   app** y se lleva `filesDir/packs/` — si los packs ya están, son ~450 MB de vuelta.
3. **Instalar APK y empujar los tres packs** con `tools/devpack.py install` (nunca `adb push`:
   la copia tiene que ser atómica y con sha256, D-082).
4. **El trace de Perfetto** del redibujo en reposo. ⚠️ **Ya no es el objetivo principal**: el
   redibujo está **confirmado en 4,0 fps** (ventana con `mWakefulness=Awake` y `InputEventId == 0`
   en todos los frames), pero la batería lo desclasificó — **33,2 mAh de pantalla contra 6,05 de
   toda nuestra CPU**. Se arregla por corrección, no por batería. Lo que sí acotó la medición: el
   layout cuesta **0,09 ms** y la recomposición **3,87 ms**, los frames vienen en ráfagas de 60 fps
   y no en un tick parejo, y **el código de `:app` no tiene ni una animación ni un ticker** — el
   sospechoso es Wear Compose M3 1.6.x (`ScreenScaffold`, el transform de `TransformingLazyColumn`,
   o el `TimeText` que ese scaffold superpone).
5. **Los dos tiles**, que están **construidos y nunca vistos** en hardware. Mirar además el
   `bottomSlot` nuevo (buscar) y la palabra del día con su acepción.
6. **Medir el chrome del tile** para cerrar el *breakpoint* de 225 dp: hoy se topa en 3 filas y
   se niega a crecer porque ese número no está medido.
7. **R8 en release**: encendido desde D-163 y **sin verificar en dispositivo**. Lo que rompe, lo
   rompe sólo en release y sin error de compilación. ⚠️ **Y ahora se sabe que es peor que eso**: el
   APK que se sideloadea es `DEBUGGABLE`, y ART **nunca compila AOT un paquete debuggable** —
   `cmd package compile -m speed -f` contesta `Success` y el estado aterriza en `verify`. Así que
   además de R8, falta **el AOT**: ninguna cifra de rendimiento tomada por `devpack`/`adb install`
   del APK de debug representa lo que corre un usuario. Instalar el release exige el keystore, que
   vive fuera del repo con su ruta en `local.properties`.
8. **Antes de medir arranque, mirar `dumpsys package dexopt`.** Si dice `run-from-apk`, la cifra
   trae **~800 ms** de verificación de dex que no existen en régimen. Y si se corrió
   `cmd package compile`, el estado **persiste** y sesga la medición siguiente: se revierte con
   `cmd package compile --reset cl.fadiaz.dictionary`.

#### Las trampas, medidas en esta sesión

- ⚠️ **`adb shell pm clear` borra `filesDir/packs/`.** Me llevó 461 MiB y lo leí como un bug de
  la app antes de mirar.
- ⚠️ **Un `connectedAndroidTest` cuyo dispositivo desaparece a media corrida sale `BUILD
  SUCCESSFUL` con CERO tests.** Hay que leer el **conteo**, no el color.
- ⚠️ **El IME del reloj desordena `adb shell input text`** y descarta lo escrito con BACK. Las
  sondas funcionales van al **emulador** (`tools/avd_como_el_reloj.py`); del reloj, sólo datos.
- ⚠️ Un pack `schema_version 3` se **rechaza al abrir**. Si quedaba alguno viejo en el reloj, hay
  que borrarlo con `devpack.py rm` — los `pack_id` cambiaron y **coexistirían** en vez de
  reemplazarse.

### Ver los dos tiles funcionando en un reloj

**Estado.** **Construido y sin ver** (2026-09-19). Es lo primero a mirar cuando el reloj vuelva a
la red.

**Qué está verificado.** Que los dos quedan registrados (`dumpsys`), que sus layouts se
construyen sin tirar —6 tests instrumentados en API 37— y que los datos llegan: con el pack real,
la caché quedó escrita y su día 0 coincide con la palabra que muestra el inicio de la app.

**Qué NO.** **Nadie vio un tile dibujado.** Se perdieron cinco intentos buscando el carrusel en el
emulador y se paró ahí, que es lo que recomienda la fricción ya registrada más abajo. Quedan tres
cosas colgando de eso:

- **El `Timeline` de siete ventanas es ASSUMPTION.** El javadoc no documenta un límite de
  entradas; que el renderer las honre sin truncar no se comprobó. Si truncara, la palabra dejaría
  de cambiar a los pocos días — en silencio.
- **Que tocar una fila abra la entrada correcta.** El extra se valida contra los packs abiertos
  —caer al activo sería D-080 otra vez— pero está probado por lectura, no por uso.
- **`launchMode`.** Con el default y `taskAffinity=""`, si la app ya está en background el sistema
  puede reusar la tarea y **no** volver a llamar `onCreate`, perdiendo el extra. El arreglo
  canónico es `singleTask` + `onNewIntent`, pero eso toca el back stack de
  `SwipeDismissableNavHost`. Hay que probarlo antes de cambiarlo.

**Y lo que no se puede cerrar sin profiler:** cuánto ahorra en batería pasar de 24 despertares
diarios a cero. Sigue siendo O-4.

### Instalador de packs: descargar e instalar un idioma

**Estado.** ✅ **Funciona de punta a punta** (2026-09-22), verificado en el emulador contra
`tools/packserver.py`. Se consulta el catálogo, se toca una oferta, WorkManager espera a que el
reloj esté cargando con Wi-Fi sin medir (D-029), se descarga el `.gz` con reanudación, se
comprueban **los dos hashes**, se infla, se instala atómicamente, la app recarga y la fila pasa de
«descargar» a «instalado». El `sha256` del archivo instalado es **idéntico al publicado**.

⚠️ **Lo que queda abierto es de producto, no de mecanismo**: dónde se hostea el catálogo de
verdad. `BuildConfig.CATALOG_URL` apunta hoy a un servidor de desarrollo por `adb reverse`, y sólo
`debug` puede hablar por `http://`.

✅ **La deuda de UI se cerró el 2026-09-23** (D-231): una descarga en curso se cancela desde su
propia fila, con el mismo botón que borra, y **se libera el `.gz.part`**. `CANCELLED` dejó de
reportarse como `FAILED` —que prometía un reintento que no existe— y la fila vuelve a ser una
oferta. El costo, nombrado: pedirla de nuevo baja desde cero.

**No hay "importar a la base de datos", y conviene decirlo primero** porque es la confusión
natural. El pack **es** la base de datos: un SQLite inmutable que se abre read-only (D-001).
Instalar no es un ETL — es *verificar y renombrar*. Room quedó descartado justamente porque
`createFromFile()` **copia** el archivo (D-039), y `instalarAtomico` ya existe con esa forma:
se escribe un `.part` y recién al final se renombra, porque **un pack a medio escribir se abre
sin error y devuelve menos palabras de las que tiene**.

#### El hueco que mandaba sobre todo lo demás — **la mitad de mecanismo está cerrada** (D-165)

El problema era éste: `meta.payload_dict_sha256` cubre sólo el diccionario de compresión de 32 KB
(D-008), y `PackFile.open` valida `schema_version`, `norm_version` y `payload_codec`, todos en las
primeras páginas. Una descarga truncada o corrompida más allá de esa zona **abría igual y
devolvía menos resultados**, que es exactamente el bug que este repo no puede observar.

✅ **`installAtomically` ahora acepta un `sha256` esperado y lo comprueba en streaming**, mientras
copia — los bytes ya están pasando, así que no agrega una lectura. Se compara **antes de
renombrar**: un archivo que no coincide nunca llega a llamarse como el pack.

⚠️ **Lo que sigue faltando es de producto, no de código: el catálogo.** Sin un lugar donde estén
publicados los `sha256`, el parámetro existe y nadie tiene qué pasarle. Hoy sólo `devpack.py`
compara hashes, y lo hace desde el lado del escritorio.

**Decisión del 2026-09-21**: *«por ahora no es necesario hostearlo, considera todas las
preparaciones para esta update sin tener una fuente de descargas»*. O sea que el mecanismo se
construye completo y el catálogo queda como un dato que alguien llenará después — y eso es
exactamente lo que ya pasó con `expectedSha256` (D-165). **Lo que falta preparar, en orden:**
la forma del catálogo (un JSON con `pack_id`, `data_version`, `bytes`, `sha256`, `url`), el
trabajo de WorkManager con las restricciones de D-029, y la pantalla que hoy es un WIP explícito
en `PacksScreen`. Nada de eso necesita un servidor para escribirse ni para probarse.

#### Actualización versionada de packs: no volver a bajar 192 MB por un cambio chico

**Estado.** Investigado con fuentes primarias el 2026-09-21, **nada implementado**. Pedido como
*«más un deseable que un requisito»*, y la investigación explica por qué conviene que siga siendo
un deseable un rato más.

**El problema, con el número.** Un rebuild del inglés cambia `data_version` y hoy eso significa
**306,8 MB** otra vez (192 comprimidos), sólo mientras el reloj carga y con Wi-Fi (D-029). Entre
dos builds del mismo dump la mayoría de las filas son idénticas.

**Las dos familias, y por qué una queda descartada.**

| Vía | Qué produce | Veredicto |
|---|---|---|
| **`sqldiff`** (SQLite oficial) | SQL o un *changeset* binario que transforma la base | ⚠️ **Descartada, y por una razón de fondo** |
| **zsync** (rsync del lado del cliente) | Los bloques que cambiaron, por `Range` sobre HTTP | ✅ **La candidata** |

⚠️ **`sqldiff` rompe el modelo de verificación entero, y eso es más grave que su coste.** Un
changeset **muta** el archivo destino, así que el resultado **no es byte a byte idéntico** a ningún
artefacto publicado. Y las escrituras de SQLite no son deterministas a nivel de bytes —la
asignación de páginas y la lista de libres dependen de la historia del archivo— de modo que **el
`sha256` publicado dejaría de coincidir**, que es exactamente en lo que se apoya D-165. Habría que
reemplazar la verificación de bytes por una de contenido, y eso es tirar la única comprobación
que hoy detecta una descarga corrupta. Además `sqldiff` sólo soporta FTS5 con `--vtab`, y sin ese
flag la documentación oficial avisa de que aplicar el resultado **puede corromper** la tabla
virtual. Ver `docs/references.md`.

✅ **zsync no tiene ese problema porque reconstruye el archivo exacto.** El cliente descarga un
metafichero `.zsync` con sumas por bloque, compara contra el pack que ya tiene y pide por `Range`
sólo los bloques distintos. El resultado es **bit a bit el pack publicado**, así que el `sha256` de
D-165 sigue valiendo sin cambiar nada. Y no necesita servidor especial: HTTP/1.1 con `Range`, que
es lo que `tools/packserver.py` ya sirve y lo que D-040 eligió.

**Lo que esto ya cambió, aunque no se implemente nada.** El servidor de desarrollo publica el
`.db` **en crudo además del `.gz`**, porque zsync necesita bytes que se parezcan a la versión
anterior y un flujo gzip no se parece en nada después del primer byte que cambia. La versión
actual de zsync **quitó** el soporte de mirar dentro de archivos comprimidos (necesitaba una zlib
parcheada; sólo sigue en la 0.6.4).

**Con qué choca, y lo que falta medir antes de decidir.**

1. **Cuánto se ahorraría de verdad.** Nadie lo midió. Se mide barato: `sqldiff --summary` entre dos
   builds del mismo dump da las filas que cambiaron, y comparar páginas SQLite de 4 KB entre los
   dos `.db` da la cota real de bloques. **Sin ese número esto no se justifica**, igual que el
   presupuesto de 50 MB que se retiró por no haber cambiado nunca una decisión (D-207).
2. **Hay que tener el pack viejo mientras se reconstruye el nuevo**: 306 + 306 MB transitorios. Hay
   9,0 GiB libres, así que cabe, pero deja de caber si algún día hay varios idiomas completos.
3. **No hay implementación de zsync en Kotlin/Java** que valga la pena adoptar, y escribir el
   cliente es trabajo real: rolling checksum, el parseo del metafichero, y la reconstrucción por
   bloques. Es más código que el instalador completo.
4. **`--flexiones` y el orden del rebuild** hacen que el inglés cambie cada vez que cambia el
   español, así que dos packs se mueven juntos más de lo que parece.

**Qué hay que decidir antes.** Nada de producto. Es puramente *¿el ahorro medido justifica el
cliente?*, y el paso siguiente es la medición del punto 1, que no necesita ni reloj ni servidor.

#### Los tres momentos de validación, y por qué no usan lo mismo

| Momento | Qué pregunta | Con qué | Estado |
|---|---|---|---|
| Instalar o descargar | ¿Llegaron los bytes publicados? | sha256 en streaming | ✅ mecanismo listo, falta el catálogo |
| Descubrir un pack ajeno | ¿Están bien sus claves? | la muestra de 64 de D-142 | ✅ automático: sin anotación en el memo, se valida entero |
| Cada arranque | ¿Es el mismo archivo? | huella `(bytes, mtime, NORM_VERSION)` | ✅ D-164 |

⚠️ **Un hash no sirve para el tercero**, y es el error natural: comprobarlo obligaría a releer
301 MB, mucho peor que las 64 filas que se querían ahorrar.

#### Lo que ya está decidido y no se rediscute

| | |
|---|---|
| Transporte | `HttpURLConnection`, que hace `Range` y progreso sin sumar un byte al APK (D-040) |
| Cuándo | **Wi-Fi sin medir** siempre; el cargador **sólo** si la descarga es `QUEUED` (D-029, D-263) |
| Dónde aterriza | `filesDir/packs/`, el mismo directorio donde hoy entran por `adb` (D-071) |
| Rechazo | `schema_version` o `norm_version` distintas ⟹ no se instala (D-001, D-006) |
| Qué NO se usa | Play Asset Delivery, sin soporte documentado en Wear OS (D-038) |

#### Con qué choca: los números medidos

| | Español | Inglés |
|---|---|---|
| El `.db` | 72.212.480 B | **309.424.128 B** |
| Comprimido con gzip | 36.004.318 B (**al 49,9 %**) | 193.716.435 B (**al 62,6 %**) |

**El inglés comprime mucho peor, y no es casualidad**: el 48 % de su peso son payloads que ya
están deflateados con diccionario compartido, así que volver a comprimirlos no compra casi nada.
Cualquier plan que asuma "gzip lo arregla" está asumiendo el número del español.

Y con D-029 encima: 185 MB sólo cuando el reloj esté cargando y con Wi-Fi significa que la
primera instalación de inglés **puede tardar hasta la noche**, y la UI tiene que decirlo.

#### Qué hay a favor

- `instalarAtomico` existe, es `internal`, sin `Context`, y tiene tests en el gate — incluido el
  de que una copia cortada no deja un `.db` a medias.
- `PackStore` ya enumera `filesDir/packs/` y abre todo lo que haya: un pack nuevo aparece en el
  selector sin tocar nada más.
- El formato ya declara `entry_count`, `data_version` (entero, AAAAMMDD, D-070) y `built_at`, que
  es lo que un catálogo necesita para decidir si hay algo más nuevo.
- La pantalla de "Instalando" ya existe, y ya se usa para el pack de demo.

#### Qué hay que decidir antes

**1. Dónde vive el catálogo.** Es lo único que no es del agente. Un JSON con `pack_id`, `bytes`,
`sha256`, `data_version`, `schema_version`, `norm_version`, `license`, `attribution` y la URL.
Y si los packs se versionan independientemente de la app — que es lo que decide si una app vieja
puede rechazar un pack nuevo con un mensaje útil o simplemente no verlo.

**2. Qué viaja por la red: el `.db`, o algo que se reconstruye en el reloj.** Es la decisión de
"lo más compacto posible", y tiene un número: en inglés, `entry` + `form` son 162,3 MB de los
295,1, y **el 45 % restante —`fts_def` e índices— es derivable**. Mandar sólo lo no derivable y
reconstruir en el dispositivo achicaría la descarga de forma seria.

Lo que cuesta, y por qué no es obvio: construir FTS5 sobre 794.355 entradas y dos índices en una
CPU de reloj es **minutos de CPU sostenida**, que la guía oficial clasifica como *high impact*.
Pasa mientras carga, así que quizás se tolere — pero además **el artefacto deja de ser el que
`verify_pack.py` validó**, y ahí entra la clase de bug que este proyecto entero evita. Habría
que medir las dos cosas antes de elegir: los MB que ahorra y los minutos que cuesta.

**3. Qué pasa cuando sale un dump nuevo.** El pack se reconstruye entero y los rowids se corren,
así que **un diff binario no va a ser chico**: `entry.id` es secuencial y todo índice lo
referencia (D-055 midió exactamente ese efecto). Volver a bajar 185 MB por una actualización
mensual es caro; asumirlo es una decisión, no un olvido.

**4. Si hay un pack "núcleo" y uno completo.** Las N entradas de mejor rank serían un diccionario
chico y usable de inmediato — es el mismo mecanismo que el pack de demostración, con contenido de
verdad. **Pero dos packs del mismo idioma se pisan**: hoy el selector elige uno, y que uno le
sume al otro es composición, que necesita `SearchRepository`. No es gratis.

#### Lo que ninguna de estas opciones cambia

La verificación profunda —que `entry.norm == norm(headword)`, que el rowid de `fts_def` coincida
con `entry.id`— **sólo la hace `verify_pack.py`, en Python, antes de publicar**. En el reloj no
hay forma de repetirla a un costo razonable. El dispositivo confía en el `sha256` del archivo y
en que el publicador corrió el validador. Eso hace de `verify_pack.py` antes de publicar un
**paso obligatorio del pipeline de release**, no una cortesía.

---

### El tap sobre una palabra de la glosa acierta en la de al lado — EVALUADO 2026-09-23, sin construir

**Estado.** **Planificado**, con cinco opciones costeadas y una recomendación. Pedido explícito:
*«no hagas ningún cambio en esto y sólo evalúa opciones»*.

**La causa, medida.** El mecanismo es `LinkAnnotation.Clickable` dentro de un `AnnotatedString`
(D-094): **el área táctil es exactamente el glifo**. En `labelSmall` sobre 234 dp una palabra de
ocho letras —la media de estos packs— ocupa unos **40 × 14 dp**. El mínimo de Android es
**48 × 48**, y Wear OS admite 40 × 40 sólo en algunos casos. ⚠️ **La altura es el problema, no el
ancho**: 14 dp contra 48 es 3,4 × por debajo, y en un párrafo la palabra de arriba y la de abajo
están a ~4 dp. No es un defecto de implementación: es geometría.

| | Opción | Costo en unidades de este repo | Qué cierra / qué cuesta |
|---|---|---|---|
| **A** | Subir `lineHeight` sólo en glosas con enlaces | ~2 de las 3–4 filas que caben | Sube el alto efectivo de 14 a ~28 dp. Barato y reversible; **no llega a 48** |
| **B** | **El tap abre una confirmación** *«¿Ir a X?»* con la palabra ya resuelta | 1 fila temporal, 1 toque extra | ⚠️ **Convierte un error en un rechazo**: equivocarse deja de costar una navegación perdida. Es lo único que resuelve el problema sin pelear contra la geometría. **Recomendada** |
| **C** | Chips de 48 dp con las palabras tocables, debajo de la glosa | 1–2 filas por acepción | Toques perfectos y el dato ya existe (`links`). ⚠️ Rompe la lectura: la glosa deja de ser el objeto y pasa a ser un índice |
| **D** | Lupa propia (zoom al mantener apretado) | Alto: gesto, render y medición en reloj | ⚠️ **Wear OS ya trae una lupa del sistema** en accesibilidad. Construir una propia duplica plataforma y la haría peor. **Descartada** |
| **E** | Heurística de cercanía sobre `TextLayoutResult` | ~30 líneas | Es lo que hacen los navegadores. ⚠️ **Sin señal visual, un acierto y un "casi" se sienten igual**, y con dos enlaces contiguos elige mal con confianza — peor que fallar visiblemente. ✅ **CONSTRUIDA el 2026-09-23 (D-243), contra esta recomendación**, con tres cotas que acotan la objeción: un acierto exacto no se pisa, un tap dentro de una línea no salta de línea, y más allá del radio no pasa nada. Lo que sigue sin resolver es el hueco entre dos enlaces pegados |

**What unblocks it.** **P-11** of `docs/preguntas-del-reloj.md`. It used to point at P-2, which
was answered on the emulator on 2026-09-23 — the links exist and they hit — so P-2 no longer
blocks anything. What is missing is the wrist: choosing between A and B depends on how much the
extra tap annoys, and that is not something a desktop can feel.

### ✅ `abiertos=` in the debug dump undercounts — FIXED, and this row was stale for a day

**Status.** ✅ **Closed by D-256**, and left here because a deleted entry comes back next quarter
with no memory of what closed it.

**What it was.** Startup said `listo: 3 abiertos, 0 rechazados, activo=es-full`; the dump, seconds
later, said `abiertos=2`. `MainActivity` fed it `state.available`, which is `offerable()`'s output
and **drops a bundled core whose languages a non-bundled pack already covers** -- right on screen,
wrong under that label.

**What closed it.** `SearchState` carries two lists: `available` for drawing and `loaded` for
reporting, with the dump reading the second. Fixed by `SearchViewModelTest`
`aShadowedBundledPackIsStillReportedAsLoaded`, proven by mutation.

⚠️ **What this row is worth keeping for is the day it spent lying.** It kept saying *"FOUND, not
fixed"* after the fix shipped, with the decision row and the regression test already in the tree.
A roadmap entry that describes a solved defect costs the next session the same as one that
describes a real one: it gets read, believed, and worked on. **The state goes in the same change
as the fix**, which is what the ledger's five states exist for -- an entry that is only updated
when somebody notices is not a ledger.

### ✅ `LanguageScope.FALLBACK` is built, tested and unreachable — **REACHABLE 2026-09-24** (D-266)

**Status.** ✅ **Done.** The Settings screen carries a switch, *Buscar también en el otro idioma*,
off by default. It is the first caller in the app's history that constructs
`LanguageScope.FALLBACK`.

**What it was.** `SearchRepository` took a `scope: LanguageScope = LanguageScope.STRICT` and
branched on it (`if (scope == LanguageScope.STRICT) return false`). **Nothing in the app ever
constructed it with `FALLBACK`.** D-189 turned the cross-language fallback off and deliberately
kept it *as a value* rather than deleting it — the row says so — but no caller was ever given a way
to pick it. Dead-but-working code, which a well-meant deletion carries off with no test failing.

⚠️ **And the price was already measured, which is unusual for a roadmap item**: D-189 records that
**321 of 400 common English lemmas** stop appearing with Spanish active. That number is the whole
argument in both directions — it is why the fallback existed, and why turning it off was worth it.
The switch hands the choice to whoever is wearing the watch, and **off by default keeps D-189's
answer as the answer**.

⚠️ **What building it turned up, and it would have shipped silently.** The repository is
constructed when the packs load and when the language changes — **not per query** — and `scope` is
a constructor argument. So the first version of the switch persisted correctly, read back
correctly on the next launch, and **changed nothing in between**. The test caught it; nothing else
would have, because the symptom is indistinguishable from the fallback simply not triggering.

⚠️ **Where it does NOT reach: the free-text search over definitions.** `needsFallback`'s threshold
is computed from prefix coverage, and coverage means nothing when what was typed is a word from
inside a definition. `searchDefinitions` says so in its own comment and that has not changed.

**Still open**: the switch is off by default and nothing tells a user it exists. Whether the empty
result should point at it — *"nothing in Spanish; turn on the other language?"* — belongs with
§The two escape hatches on an empty result, which is the row that owns that screen.

### The APK keeps dead bytes when assets are removed — MEASURED 2026-09-23

**Status.** **A trap, written down.** Not a defect in this repo: AGP packages incrementally.

Building with `-Pdictionary.packsDir=<empty>` — which is how you get a pack-less APK to test the
first-run path — leaves the previous build's packs in the file as **orphaned bytes**. Measured:
the APK contained **no `.db` at all** and 54 MB of entries, and weighed **111 MB**. Deleting the
`.apk` first and rebuilding gave **53.96 MB**.

⚠️ **It is silent and it costs exactly where it hurts**: pushing 111 MB to a watch over
wireless `adb` for an app that is 54, which is minutes, to test the state that needs the *least*
data. Nothing warns; the zip is valid, because readers use the central directory.

```sh
rm -f app/build/outputs/apk/debug/app-debug.apk        # or --rerun-tasks
./gradlew :app:assembleDebug -Pdictionary.packsDir=<an empty directory>
```

### ✅ Downloading should not require the charger — **BUILT 2026-09-24 as option B** (D-263)

**Status.** ✅ **Done.** The owner's words were that requiring *«el cargador conectado»* for a
download is a requirement they can see no sense in. Option **B** landed: `DownloadPackWorker`
takes a `DownloadOrigin`, and only `QUEUED` waits for a charger. `UNMETERED` is untouched for both.

**Why B and not A**, which is the whole content of the decision. `DownloadPackWorker.constraints()`
set **both** `setRequiresCharging(true)` and `NetworkType.UNMETERED`, one line each, and D-029
justified the pair together as *"the official Wear OS guidance"*.

⚠️ **The two constraints defend different things and only one of them was actually argued.**
`UNMETERED` protects a **data plan** — a 314 MB pack over a metered connection is a real bill, and
a watch that is paired to a phone can be on one without saying so. `requiresCharging` protects the
**battery**, and that is the one with no number behind it in this repo: nothing here has measured
what a 72 MB download costs a watch battery. `docs/bateria.md` has the screen at 33.2 mAh and all
of our CPU at 6.05, so the budget exists — the download was simply never put against it.

⚠️ **And it had a cost that is measured.** The owner's own flow — install the app, point it at a
development server, download a pack — required plugging the watch in to test a download at all,
which is friction on every iteration of the thing being built.

⚠️ **B changes who decides rather than what it costs**, which is why it needed no measurement to
be defensible: a download somebody is *watching* is their choice to pay for; one that fires on its
own is not, because nobody consents to a cost they are not present for. It is the same reasoning
D-212 used for logging.

⚠️ **Today every download is `MANUAL`, so the observable effect is A's.** `MainActivity`'s
`startDownload` is reachable only from a press in the dictionary manager. The parameter is not
speculative generality — it is where the rule waits for the queued download to arrive, instead of
being deleted and rediscovered by whoever writes the first one — but it should not be described as
if the app already distinguished two cases at runtime. It does not.

**What is still open, and it is the smaller half.** The battery number. One measurement —
`dumpsys batterystats` around a 72 MB download on the watch, the `benchmark` skill's existing
procedure — would say whether `QUEUED` deserves the charger either, or whether D-029's remaining
half is also guidance-shaped rather than measured. **It does not block anything now**: nothing
produces `QUEUED`.

| | Option | What it costs | What it closes |
|---|---|---|---|
| **A** | Drop `requiresCharging`, keep `UNMETERED` | an unknown battery cost | The data plan stays protected. ⚠️ **Not chosen**: it is B's behaviour today with the rule thrown away, so the first automatic download inherits it silently |
| **B** | ✅ **BUILT.** Drop it only for downloads the user **started by hand**, keep it for anything queued | ~10 lines: an enum and two constraint sets | The honest one, and it needed no number to justify |
| **C** | Make it a setting | a row, and a decision the user has no numbers for either | ⚠️ Hands over a choice nobody can make informed |
| **D** | Keep it | 0 | The previous answer, and it was guidance-shaped rather than measured |

### ✅ The language code is missing on history and saved rows — **BUILT 2026-09-24 as option A** (D-265)

**Status.** ✅ **Done.** `Visit` carries a `lang`, written when the visit is recorded; `visitTag`
prefers it and falls back to the pack-derived map. Old rows stay untagged.

⚠️ **First, a correction to the report.** It was raised as the **word of the day** missing its
code. On the build installed that day it has it — verified on the watch, `ligar / verb · ES` and
`postal / adj. · EN` (D-253). What was missing it is the **Recent** section right below:
`atizar / verb` and `stoke / verb`, two different languages with nothing distinguishing them.

**The cause, and it was not an oversight.** `historyTags` tagged a row by looking up its `packId`
among the installed packs and taking `langs.singleOrNull()`. Both of those rows come from
`es-tr-enwikt-freq`, which declares **es+en**, so there is no single true language and the code
deliberately preferred **no tag over a wrong one** — the same rule that governs a gloss's links.

⚠️ **And the bilingual pack is exactly the one that survives a rebuild.** Its `pack_id` is derived
from its sources rather than stamped `<lang>-<tier>` (D-215 leaves it out on purpose), so every
other pack's history was orphaned by the 2026-09-22 rebuild while its rows kept resolving. The
untagged case was not the rare one; on that watch it was most of the list.

**The migration, which was the whole decision: old rows stay untagged.** A row written before the
field has no honest language to give it. Deriving one from the pack is the defect being fixed, and
from the active language is D-080's family. The screens already draw an untagged row — a pack that
is no longer installed has always produced one — so nothing new had to handle it.

⚠️ **What is NOT filled, and it is the case where a tag would help most.** A word tapped inside a
gloss or a translation is recorded through `recordVisit(packId, entryId)`, which reads an
`EntrySummary` — and that type carries no `lang`. So `langOf` answers for a monolingual pack and
**null for a bidirectional one**, which is precisely where a translation lands you in the other
language. Closing it means `entry.lang` reaching `EntrySummary`, which is a `:dict-core` and
`:dict-data` change whose tests need a device: deliberately not smuggled in here.

| | Option | Cost | What it closes |
|---|---|---|---|
| **A** | ✅ **BUILT.** Store the language **in `Visit`** | one more field in the preference; the parser already tolerated extra columns, so there was no format break | The only one that is actually correct: the language is a property of the visit, not of the pack. Old rows stay untagged, which is honest |
| **B** | Derive it from the entry when the row is drawn | a read per row, on a list that scrolls | ⚠️ Puts a pack read on the home's scroll path, which D-106's reasoning rules out for the tile and is uncomfortable here |
| **C** | Tag it with the **active** language | 0 | ⚠️ **Wrong, and the KDoc already refused it**: a history row can be from another language than the active one, and asserting otherwise is D-080's family |

### `full` and `main` of the same origin installed together — REVIEWED 2026-09-23, partly handled

**Status.** **Better than expected, and the gap is not where it looks.** Asked for: handle
explicitly what happens when a `full` pack is installed and then the `main` of the same origin
arrives, *«y viceversa»*.

**What already works.** A pack declares `meta.subset_of`, and `packsToQuery` drops any pack whose
absorber is installed. Read off `dist/` on 2026-09-23:

| pack | `subset_of` |
|---|---|
| `en-core`, `en-main` | `en-full` |
| `es-core` | `es-full` |
| `es-full`, `en-full`, `es-en` | — |

So installing `en-main` next to `en-full` — **in either order** — already means `en-main` is not
queried, and no result is duplicated. The mechanism is order-independent, which is what makes
*"and vice versa"* a non-question.

⚠️ **What is NOT handled, and these are the explicit decisions being asked for:**

1. **The shadowed pack keeps its disk.** `en-main` is **103 MB** that is never read once `en-full`
   is there. Nothing says so, nothing offers to delete it, and the dictionary manager lists it
   looking exactly like a working pack.
2. **The catalogue will happily sell it to you.** Downloading `en-main` with `en-full` already
   installed is **72 MB over the wire** for a pack that is shadowed the moment it lands. The
   download screen has the information to warn — `subset_of` is in the index — and does not.
3. **Two surfaces use different rules, which is this repo's recurring failure.** `packsToQuery`
   honours `subset_of`; `representativePacks` — which decides the attribution shown and whose
   word of the day appears — picks by `entryCount` and does not. Today it lands correctly by
   luck, because an absorber always has more entries than its subset. **It is a coincidence, not
   an invariant**, and nothing fails if it stops being true.

**Cheapest first**: (3) is a correctness question and costs a few lines plus a test; (1) is a row
in the dictionary manager saying *"covered by English (full)"*; (2) is the same string in the
catalogue. None needs a rebuild.

### The APK is 111 MB and the estimate said 60 — MEASURED 2026-09-23

**Status.** **Open**, and the gap is fully explained by two numbers nobody re-derived together.

**Where the 111 MB is**, read off the zip with `unzip -v`:

| | Uncompressed | In the APK | |
|---|---|---|---|
| `classes*.dex` | 45.2 MB | **45.2 MB** | ⚠️ **stored, not deflated** — ART mmaps it, so AGP never compresses it |
| `assets/es-core.db` | 50.8 MB | 24.9 MB | 51 % |
| `assets/en-core.db` | 42.9 MB | 32.2 MB | 25 % |
| libs, resources, the rest | | ~8.8 MB | |
| | | **111.1 MB** | |

**The two causes, and they are independent:**

1. ⚠️ **The cores grew 5.3× and the APK estimate was never redone.** The watch was carrying
   `es-core` at **4.9 MB** and `en-core` at **11.9 MB** — 16.8 MB together, which is the era the
   *"app plus compressed packs is about 60 MB"* figure comes from. Today they are **48.4 and
   40.8 MB, 89.2 MB together**. That was not a regression: the 2026-09-22 rebuild re-cut the core
   tier **by corpus coverage** rather than by size, and the table in §*La deuda de hoy* records
   the result — `en-core` 96.60 % coverage, `es-core` 78.87 %. What nobody did was carry that
   number back to the APK.
2. ⚠️ **The 45 MB of dex is R8 being off** (D-087, roadmap O-2), and it is **stored
   uncompressed**, so it costs its full size. A pack-less build measured today is **53.96 MB**;
   `CLAUDE.md` claims a release build is 35 MB, which is **documented and not re-measured**.

⚠️ **And the two cores are asymmetric in a way worth its own look**: `es-core` is **bigger**
(50.8 vs 42.9 MB) while covering **less** (78.87 % vs 96.60 %), and it spends **28.18 % of the
Spanish lemmas** to get there against English's **4.39 %**. Six times the lemma budget for worse
coverage is either a property of the language or a defect in how the Spanish core is cut, and
nothing here says which.

**What would close the gap, priced:**

| | Option | Saves | What it costs |
|---|---|---|---|
| **A** | Enable R8 (O-2) | ~19 MB, documented not measured | Reintroduces the class of bug that only shows in release, which is why D-087 deferred it |
| **B** | Ship ONE core, the watch's locale, and download the other | 25–32 MB | The second language needs the network before it works — against *"every search runs with no network"* for a user who has not downloaded yet |
| **C** | Re-cut the core tier smaller | up to ~40 MB | Gives back the coverage the 2026-09-22 rebuild bought on purpose. Needs the coverage/size curve, which `build_core.py --rango-mb` can produce |
| **D** | Accept it | 0 | ⚠️ It is today's answer by default rather than by decision, and it costs **2 min 17 s per install** over wireless adb (measured on SM-L715F) |

**None is free and none is mine to pick.** The measurement is here so the choice is made against
numbers rather than against a remembered 60 MB.

### `docs/decisions.md` is in the current format, and drifting in a direction nobody named

**Status.** **Measured 2026-09-23, an advisory now reports it, and the repair is NOT done.**
Raised as *"the decisions are still in the old format, update them to the new one"*.

⚠️ **The column format is current.** The file's headers are `| # | Decisión | Por qué |
Enforced in |`, which is artifact 6's four columns. And the one v21 delta that looks like a new
format — marking rows inline as *decisions, not rules* and *discarded, with the number* — was
**measured by an earlier session to be byte-identical to v7's spec** and declined with the
reason recorded in `.agents/method/prompt-context.md`: this repo meets that guarantee **by
section** (D-224) rather than by inline mark, so re-proposing it is a rename and not a delta.

⚠️ **The real drift is the opposite of the one suspected.** Artifact 6 says *"the prose and the
measurements live in the document that owns them; this file is the index that finds them"*.
Measured over the 262 rows:

| | |
|---|---|
| rows over 1,200 characters | **101 of 262** |
| median row | **1,049 characters** |
| p90 | **1,941** |
| longest | **3,996** (D-216) |

A 3,996-character table cell is not an index entry. And where a row holds the measurement, there
are now **two copies of it** — the row and the document that owns it — with nothing comparing
them, which is the repo's own *second source of truth* failure in the file that exists to prevent
it. ⚠️ **This session's ten rows are part of it**: 1,073–1,361 characters, above the median.

**What was built**: `check_decision_rows_stay_an_index`, which **only ever advises**. Turning it
into a ratchet is the next rung and it is a decision: a failure here would block writing a long
row at the moment somebody is recording something they just learned, which is the worst time to
argue about format. It carries the zero-rows guard D-253 taught — verified by mutation, a pattern
that stops matching **fails** instead of passing over nothing.

**What is NOT done**: moving the prose out of the 101 rows. That is real work — each one needs
its measurement rehomed in the document that owns it and a pointer left behind — and doing it
badly would lose the measurements, which are the most valuable thing in the file.

**Where this kind of sweep belongs, evaluated lightly as asked:**

| | Home | Fires | Cost |
|---|---|---|---|
| | `.agents/method/` | on every carrier | ⚠️ **not available**: a carrier may not write there, and repo-specific conformance does not belong in a shared method anyway |
| **✓** | the carrier's own gate, as an **advisory** | every run, blocking nothing | ✓ **chosen**: *suggested, the owner decides* is exactly what an advisory is, and it needs no new ritual |
| | the `state-review` skill | only when a meta-session is asked for | cheap, but it is the ritual that is already easy to skip |
| | the method, as a general step | on every carrier | proposed as a candidate instead, which is the only route open |

### An override outlives the tunnel it needs — MEASURED 2026-09-23

**Status.** **A trap, written down.** It is the cost of the override being durable, which is the
feature working as designed.

The catalogue override (D-259) lives in the app's preferences, so it survives a reboot, a
force-stop and a disconnect. **`adb reverse` does not**: the tunnel dies with the `adb`
connection, and wireless debugging on a watch drops on its own — it did on 2026-09-23, mid-probe,
seconds after the url had been set.

⚠️ **So the app is left pointing at `http://localhost:8799` with nothing on the other end**, and
what the user sees is a catalogue that will not load. **The url is right and the tunnel is gone**,
which are opposite fixes, and nothing on screen distinguishes them.

**On reconnecting, the tunnel has to be re-made and the override does not:**

```sh
adb devices                                # the serial changes between sessions
adb -s <serial> reverse tcp:8799 tcp:8799  # the part that did NOT survive
adb -s <serial> shell am broadcast -p cl.fadiaz.dictionary \
    -a cl.fadiaz.dictionary.DEBUG_DUMP     # `catalogo=` says what the app still believes
```

**What would close it.** A readout that distinguishes the two: the catalogue failure already
logs, but not in a way that separates *"nothing answered at that address"* from *"that address
is wrong"*. Naming the url in the failure line is one line and removes the ambiguity entirely —
the dump already does it, and the dump is what nobody thinks to run when something looks broken.

### The `adb` debug surface should exist only in a debug build — ✅ **ENFORCED 2026-09-24**, as *never in `release`*

**Status.** **Half done and the other half declined with its reason.**
Asked for verbatim: *«deja en el roadmap que este debug adb solo quede disponible en build de
debug»*.

✅ **What landed.** `check_debug_surface_stays_out_of_release` now fails the gate if the `release`
block sets `DEBUG_INTENTS` to anything but `false`, or stops declaring it. That is option **A with
the wording fixed**: the rule is *never in `release`*, which is the property that protects a user,
and it is now enforced by a check instead of by two comments. Verified by mutation in both
directions — flipping the constant and deleting the line each fail the audit with their own
message. ⚠️ **The silent edit is the flip, not the deletion**: removing the line does not compile,
because AGP needs the field in every variant, while `"false"` → `"true"` compiles, installs,
passes every test and ships an exported receiver.

⚠️ **`benchmark` keeps it on, and that is what was NOT applied.** The options below stand as the
record of why.

**What is true today**, read off `app/build.gradle.kts`:

| Build type | `DEBUG_INTENTS` | Why |
|---|---|---|
| `debug` | **true** | where probes run |
| `release` | **false** | an exported receiver in production is attack surface and battery. R8 folds the `if`, the class **leaves the dex** — it does not exist, rather than going unused |
| `benchmark` | **true** | ⚠️ **deliberate, and it is the conflict.** It inherits from `release` and is the build startup and battery are measured on (D-166), and the only release-like one that installs. If it were the only build unable to seed a query, measuring a search would need a finger again |

⚠️ **So the request is already satisfied for the build that ships and not for the one that
measures**, and those are different questions. The part that matters for safety — *nothing
reaches a user's watch* — holds today and is enforced by a constant rather than by a convention:
`release` cannot register the receiver because the code is not in the APK.

**What is actually open**, and it is narrower than the sentence suggests:

| | Option | Cost | What it closes |
|---|---|---|---|
| **A** | Leave it: `debug` + `benchmark`, `release` never | 0 | Today's behaviour, and the reasoning is written down in both places. ⚠️ The cost is that *"debug-only"* is **not literally true**, so anyone reading only this line would be wrong |
| **B** | Turn it off in `benchmark` too | measuring a search goes back to needing a finger on a watch whose IME reorders keystrokes | Makes the sentence literally true. ⚠️ It pays a real, measured cost to close a gap nobody has shown is a gap: a `benchmark` APK is sideloaded by hand, never distributed |
| **C** | Keep it in `benchmark` but require an extra flag at install time | a build property, and one more thing to forget | Honest middle. ⚠️ Something you must remember to pass is something you will fail to pass exactly when you are measuring under pressure |

**Chosen: A, with the wording fixed** — *"never in `release`"* rather than *"only in `debug`"*,
because that is the property that protects anybody and it is the one a constant enforces. ⚠️ **B
is still the owner's to take**: the ask was explicit, and what closed here is the enforcement, not
the question of whether `benchmark` should keep the surface.

⚠️ **What the check does NOT cover.** It watches the `release` block, so a **new build type**
added with `DEBUG_INTENTS` left true still passes. That is deliberate rather than an oversight:
`benchmark` is exactly such a build type and is correct, so a rule over every non-`debug` block
would have to name its exceptions, and a list of exceptions is the thing that goes stale. What
ships is what `release` produces, and that is the block the check watches.

### ✅ With no packs the home is a dead end — **BUILT 2026-09-24 as option A** (D-264)

**Status.** ✅ **Done.** `SearchScreen`'s `Status.NoDictionary` branch draws the message and **one**
row, *Conseguir un diccionario*, straight to the dictionary manager.

**What it was.** An APK built with no cores, on a device with an empty `packs/`, showed exactly one
line: *"No dictionary installed."* -- a single `Text` and nothing else: **no Options section, no
Settings row, no route to the download screen**. The dictionary manager, which is where a pack is
downloaded, hangs off Settings, and Settings is not drawn in this state.

⚠️ **So the one state that most needs the downloader was the one that could not reach it.** It is
invisible today because the APK carries the two cores, so nobody arrives here; it becomes the
**first-run experience** the moment the packs stop shipping inside the APK, which is the direction
§Instalador de packs is going.

⚠️ **And it blocked testing that path at all.** Verified on the emulator on 2026-09-23 while
testing the catalogue override (D-259): with the packs removed there was no way to drive a download
from the UI, and the probe had to put a pack back first. A state you cannot get out of is also a
state you cannot test from.

⚠️ **What building it turned up.** `ScreensTest.withNoDictionaryItSaysSoAndOffersNoSearch` asserts
over `Status.Failed`, **not** over this branch -- so despite its name nothing exercised
`NoDictionary` before. The two new tests are the first that do.

| | Option | Cost | What it closes |
|---|---|---|---|
| **A** | ✅ **BUILT.** One row under the message, straight to the dictionary manager | 1 row, drawn only in this state | It costs nothing anywhere else, because the state is mutually exclusive with having results. `onManagePacks` carries no default: a row that draws and does nothing looks like a broken app rather than an empty one |
| **B** | Draw the whole Options section in this state too | 3–4 rows | Consistent, and wrong: `Saved` and `Recent` are empty by construction with no packs, so it offers three dead rows to make one live. A test pins this |
| **C** | Leave it, and rely on the APK always carrying a core | 0 | ⚠️ It was today's accidental answer rather than a decision, and it expires the moment a pack-less build ships |

### The two escape hatches on an empty result — EVALUATED 2026-09-23, not built

**Status.** **Planned**, options priced, nothing built. The owner described the two buttons on
screen — one that searches the definitions, one that searches the other language — and asked for
**options rather than a change**: *«quiero mejorar esto pero dame opciones»*.

**What is there today.** When a submitted query returns nothing, `SearchScreen` draws up to two
`Pill`s: *Search the definitions* (free-text over `fts_def`, hidden when that is already the mode,
because it would loop) and *Search in ‹name›* (switches the active language). They exist because
the search became **strict by language** in D-189: nothing falls back on its own any more, so
these are the manual way out.

⚠️ **Two defects found by reading the code on 2026-09-23, neither observed on screen yet.**
They are stated as what the code says, not as what a user reported:

- **The label names a pack and the action switches a language.** `other` is *the first open pack
  whose `packId` is not the active one*; `otroIdioma` is *the first language that is not the
  active one*. They are computed independently, so with three packs installed the pill can read
  *Search in English (full)* and activate a language whose representative pack is a different
  file. It is the family of D-080 and D-141: **a label asserting a provenance nobody checked**.
- **A single bidirectional pack suppresses the hatch entirely.** The block is gated on
  `other != null && otroIdioma != null`, and with exactly one pack installed there is no *other
  pack* — so `other` is null and the pill never draws, even though that one file speaks both
  languages and `otroIdioma` is perfectly non-null. The user is left with no way out of a language
  the pack could answer. This is the case D-195 created and this screen never caught up with.

✅ **Two of the three were fixed on 2026-09-23 (D-255, D-261)**: the hatch is gated on the
language and labelled with the language's own name, `Buscar en English`. What is left below is
the interaction, not the defects.

⚠️ **And the first pill is NOT redundant, which was asked and is worth writing down.** *"Search
the definitions"* looks like a repeat of the search that just failed; it is a **different index**.
The normal cascade is headword prefix → inflected form → translation → fuzzy, and `MatchKind`
says so in its own comment: `DEFINITION` is *"only on an explicit action by the user"*. Measured
on `es-full`, 2026-09-23: **`color del cielo` is not a lemma and appears inside 2 definitions**;
`instrumento musical` matches 1 lemma and **112 definitions**. The normal search reaches none of
those.

⚠️ **But the confusion is itself the finding.** The label answers *what will be searched* and
not *what will be searched FOR*, and after a line that already says *"No results for zzzqx"* it
reads as "try again". A label naming the query --*Buscar «zzzqx» en las definiciones*-- costs no
rows and would have prevented the question. Not changed: a user-facing string is the owner's.

| | Option | Cost in this repo's units | What it closes / what it costs |
|---|---|---|---|
| **A** | Fix the two defects and leave two pills | ~10 lines; 0 extra rows | Gate the block on `otroIdioma` alone and label it with the **language**, not the pack name. Does not improve the interaction at all — but everything below is wrong while the label lies. **Prerequisite for B, C and D, not an alternative to them** |
| **B** | **One pill that says where the answer is**, after probing both | 2 `COUNT` queries before drawing; 1 row instead of 2 | *«3 in definitions»* / *«1 in EN»* / nothing at all. Turns two blind taps into one informed one, and **removes the dead end**: today both pills can be tapped to find another empty screen. ⚠️ Price unmeasured — `tools/measure_query_cost.py` prices the cascade on the desktop, and a bounded `COUNT` over `fts_def` is the number to get first. **Recommended, conditional on that measurement** |
| **C** | Run both automatically and show grouped results | 1 header row per group; the full cost of both queries, always | No taps at all. ⚠️ Pays the cascade on **every** empty query including typos, which is the common case, and it re-litigates D-189 through the back door — strict-by-language was chosen on purpose |
| **D** | Revert to the automatic fallback | 0 UI | ⚠️ **Discarded.** D-189 decided this: a result silently in another language is the D-080 family again, and the owner asked for the chip to be the thing that decides |

**What unblocks it.** Nothing on a watch: A is pure code and B needs one desktop measurement. What
it does need is the **rebuild**, because B's counts are only worth showing over a pack whose
`fts_def` is the final one.

### Pronunciación (IPA) y etimología en el pack — **IPA: CANAL CONSTRUIDO 2026-09-24**, etimología planificada

**Estado.** **Planificado**, con las dos mediciones hechas y el canal de la ficha ya construido:
las partes principales (D-242) abrieron el tag `F` y demostraron que **agregar un tag al payload
no rompe un pack viejo** — el lector ignora lo que no conoce, y `verify_pack.py` exige registrar
el tag nuevo, que es lo que convierte ese silencio en un fallo visible.

✅ **The IPA channel is built end to end (d-a2f271-13da99), and no pack carries it yet.**
`TAG_PRONUNCIATION = "I"` in `payload.py` and its mirror in `PayloadCodec.kt`, registered in
`TAGS_CONOCIDOS`, two cases in the shared `payload-fixture.tsv`, and four Python tests. Proven by
mutation on **both** sides: dropping the emission fails the Python tests, and dropping the Kotlin
decode fails *«descomprime lo que comprimió Python, byte por byte»* — the shared fixture catches
the mirror, which is the same mechanism that holds the central invariant.

✅ **And the source reads it.** `kaikki._pronunciation` takes the first `sounds[]` item that
carries an `ipa`, strips a matched `[…]` or `/…/`, and the toy pack carries one entry with it
(`casa`, `ˈkasa`) so the channel has a fixture in the gate.

**Re-measured over the WHOLE Spanish dump on 2026-09-24**, 854,460 lines rather than the 40,000
this entry first sampled — and it moves every number:

| | this entry said | measured |
|---|---|---|
| entries with IPA | 99.8 % | **100.0 %** (854,084) |
| median length | 11 | **12** |
| p90 | 18 | **16** |

⚠️ **And it found something neither figure implied**: **10 lines carry mismatched delimiters**
(`[…)`) and 3 use `/…/`. That is why the pair is stripped only when it matches — half-stripping
those ten would leave a stray `)` indistinguishable from real notation.

⚠️ **What is NOT done, and it is the half that reaches a user.** No pack carries the tag yet:
`verify_pack.py` reports **0 of 200 sampled entries (0.0 %)** on `es-full` and **1 of 82** on the
toy. The rebuild debt is in §*Reconstruir los packs*. And the card has no row for it.

⚠️ **A gap this work found and did not widen**: `PayloadCodec.render` in Kotlin does not emit
`TAG_WORD_TRANSLATION` or `TAG_FORM` either, so those two channels' round-trip through it is
untested. It is only used by tests, so it costs nothing today; fixing it is its own change.

#### La pronunciación entra. Es el dato más barato que queda sin usar

**Medido sobre el dump español, 40.000 entradas:**

| | |
|---|---|
| entradas con IPA | **99,8 %** |
| largo mediano | **11 caracteres** |
| p90 | 18 caracteres |

Once caracteres. Contra las **150,6 MB** que pesa `entry` en el pack inglés, la IPA de sus 956.150
entradas son **~10 MB sin comprimir** y menos con el diccionario precargado del payload; en
español, sobre 152.281 entradas, **~1,7 MB**. Es la segunda pregunta de cualquier diccionario
después del significado, y hoy el dato viaja en la fuente y se tira.

**Lo que hay que tocar**, y es exactamente la forma de D-242: un `TAG_PRONUNCIATION` en
`payload.py` y en `PayloadCodec.kt`, `kaikki.py` leyendo `sounds[].ipa`, el tag registrado en
`TAGS_CONOCIDOS`, una fila en la ficha y el toy pack con una entrada que lo lleve. **No necesita
`schema_version` nuevo**: el payload es texto con tags y un pack viejo simplemente no lo trae.

#### La etimología también, **pero con tope** — y el tope es lo que la hace aceptable

Pedido: *«no me interesa la etimología a menos que sea un dato muy corto, revisa esto»*. **Revisado
sobre 30.000 entradas del dump español:**

| | |
|---|---|
| entradas con etimología | **63,4 %** |
| largo **mediano** | **30 caracteres** |
| p90 | 127 caracteres |
| máximo | **1.541 caracteres** |

⚠️ **La mediana dice que sí y el máximo dice que no**, y por eso la respuesta es un tope y no un
sí o un no. El caso típico es `De Japón y el sufijo -és.` — treinta caracteres, una línea, y
contesta algo que la definición no. El caso p90 son 127 caracteres, que en 234 dp son **cuatro
filas** compitiendo con la definición. Y hay entradas de 1.541.

**Recomendación: entra con un tope de ~80 caracteres, descartando la que no quepa en vez de
cortarla.** Una etimología cortada a la mitad es peor que ninguna: `Del latín *cor, cordis*, y
éste del protoind…` no enseña nada y ocupa lo mismo. Con ese tope entra la mediana entera y se
cae la cola.

⚠️ **Y va DESPUÉS de las acepciones, no antes**, a diferencia de las formas. Una forma contesta
*cómo se escribe esta palabra*, que es sobre la palabra; una etimología contesta *de dónde viene*,
que es lo que alguien pregunta **después** de saber qué significa. Empujar la definición hacia
abajo por la etimología invierte el orden en que se lee un diccionario.

#### Lo que las dos comparten, y conviene decirlo una vez

**Ninguna de las dos necesita un rebuild propio**: entran en el próximo. Un rebuild completo es
~1 h con los dumps presentes, así que el costo de juntarlas es cero y el de separarlas es una hora
por cabeza.

⚠️ **What rides along is no longer four defects but two, and the distinction is the point.** Of
the four `dist/` carried on 2026-09-23, **two lived entirely in `meta`** — the name
`English (full) (main)` and a Spanish sentence inside an English `description` — and were
repaired **without re-exporting content** by `repair_meta.py` (D-258), in seconds rather than an
hour. The other two **are content and wait for the rebuild**: `Eddie`/`Richard` past the
proper-noun filter, and the absent `F` channel, which is what blocks P-12.

That boundary is the thing not to lose: **what the builder computed wrongly *about* a pack can be
repaired; what it extracted *into* it cannot.** The cause of the second defect was fixed in the
builder itself (D-257), so the next rebuild does not reintroduce it.

### Funcionalidades de diccionario que faltan — REVISADO contra la literatura 2026-09-23

**Estado.** **Planificado.** Comparado con OneLook, Fora Dictionary, WordWeb y LookUp.

**Ya está**: búsqueda por definición (FTS5 sobre `fts_def`), historial, favoritas, voz, nivel
tolerante —el *did you mean*—, palabra del día y prefijo incremental. Eso cubre la mayor parte de
lo que la comparación lista como esperable.

**Lo que falta, por relación valor/costo:**

1. ✅ ~~**Mostrar las flexiones en la ficha**~~ — **CONSTRUIDO el 2026-09-23** (D-242), y el
   diagnóstico de *«cuesta 0 bytes»* resultó **falso**, que es lo que más valió de hacerlo.
   `form` guarda `norm(forma)`: **`corrais`, no `corráis`**. Es una clave de búsqueda, y una
   ficha alimentada desde ahí estaría mal escrita. Las formas con su ortografía **existen al
   construir y el builder las tiraba**, así que hizo falta un canal nuevo en el payload — no
   cero bytes, pero sí muy pocos: dos formas por entrada contra las 202 filas que `correr` tiene
   en `form`.
2. **Pronunciación (IPA).** El esquema no tiene columna; el dato está en Wiktionary y es texto,
   así que es barato. Es la segunda pregunta de cualquier diccionario después del significado.
   Requiere `schema_version` nuevo y rebuild.
3. **Comodín `*` en medio de la palabra.** El sufijo ya funciona —es el peldaño de prefijo— pero
   un infijo necesita otro índice. Caro, y en una muñeca se escribe poco.
4. **Etimología.** Disponible en la fuente, sube el pack, y en 234 dp compite por altura con la
   definición, que es lo que la persona vino a leer.

**Lo que la comparación NO sugiere y conviene dejar escrito**: la búsqueda por cámara/OCR aparece
en todas las listas de diccionarios de teléfono y **no tiene sentido en un reloj** — no hay
encuadre posible con la muñeca levantada.

### Reportado usando la app en el reloj (2026-09-21)

Cuatro cosas que salieron de tener la app puesta, no de razonar sobre ella. **La primera está
cerrada; las otras tres no.**

#### ✅ ~~El pack de demostración del reloj quedó incompatible y nunca se reemplaza~~ — cerrado dos veces

**Cerrado el 2026-09-21, y por dos vías independientes**, lo que conviene distinguir porque sólo
una arregla la causa:

1. **La causa**: `assetsToExtract` re-extrae los assets cuando cambia el `versionCode` (D-176), en
   vez de sólo cuando el archivo falta. Cubierto por cuatro casos en `PackStoreTest`. **Esto es lo
   que importa**: vale para cualquier pack incluido, no sólo para el demo.
2. **El síntoma**: el pack de demostración **se eliminó**. `bundlePacks` ya no tiene respaldo —
   empaqueta los núcleos reales o nada — así que no hay ningún `demo-es-en.db` que pueda quedar
   incompatible.

⚠️ **Y la lección de D-119 sigue en pie**, que es lo que no hay que perder al tachar esto: subir
`CODEC_ID` tira la tolerancia a tags desconocidos, porque `PackFile.open` compara con `!=`. Se
aceptó *«porque hoy el costo es cero»* y dejó de serlo. **Con el núcleo real adentro el costo sería
un diccionario viejo, no una molestia.**

<details>
<summary>El diagnóstico original, conservado</summary>

La app mostraba la advertencia de que
`demo-es-en.db` no es compatible: fue construido con `deflate-v1` y la app de hoy lee `deflate-v2`.

**La causa exacta:** `PackStore.missingFromDisk` extrae un asset del APK **sólo si el nombre falta
en disco**. El demo se extrajo el 2026-09-18, el archivo sigue ahí, y el APK de hoy trae uno nuevo
—verificado: `payload_codec = deflate-v2`, `data_version = 202609210345`— que **nunca se copia**.
O sea que **un pack incluido se extrae una vez y no se actualiza nunca**, aunque el APK traiga uno
mejor.

⚠️ **Y esto es exactamente el costo que D-119 predijo** cuando subió `CODEC_ID` a `deflate-v2`:
*«subirlo tira la propiedad de que los tags desconocidos se ignoren, porque `PackFile.open` lo
compara con `!=` y rechaza el pack»*. Se aceptó *«porque hoy el costo es cero»*. Dejó de serlo.

**El arreglo, y es chico:** re-extraer los assets cuando cambia el `versionCode` de la app, en vez
de sólo cuando el archivo falta. Una clave de preferencia con el `versionCode` que extrajo por
última vez. Hoy son 53 KB; con el núcleo adentro serían ~7,5 MB una vez por actualización.

⚠️ **Y gana importancia con el núcleo**: si el pack incluido pasa a ser el diccionario de verdad,
que no se actualice con la app deja de ser una molestia y pasa a ser un diccionario viejo.

</details>

#### El selector de idioma principal no se explica

Los dos chips `ES` / `EN` dicen **qué** está activo y no **qué hacen**. Nada en pantalla dice que
se busca en *todos* los packs de ese idioma (D-136). Falta o una interfaz mejor, o una explicación
— y la segunda cuesta filas, que en 234 dp es la moneda cara.

⚠️ **Half of this item was written against D-168 and is no longer true.** It used to add *"nor
that the other languages answer when the active one has nothing"*; **D-189 turned that fallback
off** the day after this was reported, so there is nothing to explain there. What replaced it is
a manual escape hatch, which is its own roadmap item with four priced options — and two defects
in it went unnoticed for three days precisely because this section described behaviour the app no
longer had.

#### Las palabras del día son demasiado raras — ⚠️ **measured again 2026-09-23, still open**

⚠️ **P-10 answered on the emulator and the answer is narrower than it looks.** With the CORE
packs the words of the day read `polvo` (Español core) and `anywhere` (English core), against the
simulation, which had offered `acción`, `anillo`, `Christmas` and `afternoon`. Zero function
words among them, and nothing like `posterobuccally`.

**But that does not close this item**, and saying so is the point: a core pack
is a frequency-bounded slice by construction, so it cannot produce a rare word. The full packs,
which is where `posterobuccally` came from, are **unchanged** — nothing was measured there and
the two options below still stand for them.

The original report, which still applies to the full packs:

Reportado mirando el reloj: salieron **`posterobuccally`** y **`evangélicamente`**. La palabra del
día se elige de 32 candidatos repartidos por `rank` (D-097), y `rank` es **riqueza de página, no
frecuencia de uso** — el mismo defecto que D-142 arregló para el orden de resultados y que la señal
de Tatoeba resolvería acá.

Dos salidas, y la segunda es más barata de lo que parece:

1. **Acotar por frecuencia real**, con la misma señal de Tatoeba que el pack núcleo va a necesitar
   (§Dividir los packs grandes). Una sola medición sirve a las dos cosas.
2. **Acotar por tópico**, que es lo que el usuario pidió como alternativa. Necesita una etiqueta
   que hoy el pack no trae: sería una clave de meta o un campo nuevo, o sea que entra por la
   política de D-174.

### Herramientas para depurar la app EN el reloj

**El hueco que esta sesión hizo evidente.** Con el reloj conectado se pudo medir batería, arranque
y frames, y **no se pudo escribir en el campo de búsqueda**: no toma foco con un tap sintético, que
es la misma forma del problema que obligó a fijar espresso 3.7.0 (D-093). Eso dejó D-168 y D-169
sin verificar teniendo el dispositivo en la mano.

**Estado: los tres están hechos.** El 2 lo cerró D-212; el **1 y el 3, D-232 el 2026-09-23**.

| | Qué | Qué desbloquea |
|---|---|---|
| 1 | ✅ **Sembrar la consulta desde `adb`** — `DEBUG_SEARCH` / `DEBUG_CLEAR`, receiver sólo en builds no-release | Verificar cualquier cosa que dependa de buscar, sin depender de un dedo. **Usado el mismo día**: `hous` → `house` primero, por la vía del usuario, sobre el pack núcleo |
| 2 | ✅ **Logs por `logcat`** bajo el tag `Dict`, con el detalle por `setprop` (D-212) | Hoy la app no emitía **una sola línea**; todo lo que se sabía salía de `dumpsys` |
| 3 | ✅ **Volcado del estado** por `DEBUG_DUMP`: packs abiertos con su `data_version`, activo, rechazados con su motivo, y **el memo de verificación entero** | Que `PackVerification` se compruebe en el dispositivo. Usado para verificar D-225: el memo mostró `…c2.a5` con el APK en `versionCode 5` |

```sh
adb shell am broadcast -p cl.fadiaz.dictionary -a cl.fadiaz.dictionary.DEBUG_SEARCH -e q "hous"
adb shell am broadcast -p cl.fadiaz.dictionary -a cl.fadiaz.dictionary.DEBUG_CLEAR
adb shell am broadcast -p cl.fadiaz.dictionary -a cl.fadiaz.dictionary.DEBUG_DUMP
```

⚠️ **`-p` va antes del extra, y vaciar tiene acción propia.** Un extra vacío **no sobrevive a
`adb shell`**: medido, `-e q "" -p cl.fadiaz.dictionary` dejó la app buscando literalmente `-p` y
además perdió el filtro de paquete. Por eso `DEBUG_CLEAR` no lleva extras.

⚠️ **Y la restricción que ordena el diseño**: nada de esto puede quedar en el APK de release. Un
receiver exportado o un log verboso en producción son superficie de ataque y batería. El build
`benchmark` (D-166) es el lugar natural: ya existe, ya es instalable, y ya no es el release.

### ✅ El orden multiidioma, y lo que el pack tiene que declarar — CONSTRUIDO 2026-09-21

Pedido: incluir el orden multiidioma en la misma optimización, con investigación.

#### Lo que la investigación aporta, y lo que resultó ya estar bien

La recuperación multilingüe fusiona listas de tres formas: **round-robin**, **raw-score** y
**normalized-score**, más **Reciprocal Rank Fusion**, que *«evita el desajuste de escalas»*. El
problema conocido es que los scores no son comparables entre idiomas *«por variaciones en las
estadísticas del corpus»* — que es exactamente lo que `rank` era.

⚠️ **Pero medirlo mostró que la fusión de este repo ya era ordinal.** `Suggestion.score` es **la
posición dentro del propio pack**, no el `rank`, así que la escala se cancela sola: comparar
`score` entre packs es round-robin, no raw-score. La advertencia del código —*«comparar entre
packs es una aproximación que nadie midió»*— era correcta **por otra razón**: un pack mal
calibrado pone la palabra equivocada en la **posición 0**, y al interlevar recibe el mismo peso
que uno bien calibrado.

**O sea: arreglar `rank` arregló la fusión de rebote.** El orden multiidioma nunca fue un defecto
propio; heredaba el de adentro.

#### Zipf es comparable entre idiomas, y ahora está medido

La literatura lo respalda —*«las distribuciones son extremadamente similares entre idiomas, con
coeficientes de ley de potencia aproximadamente iguales»*— y se verifica con listas tipo Swadesh.
Medido sobre nuestras dos listas, 20 pares de conceptos básicos:

```
agua 5,45 / water 5,43      libro 5,19 / book 5,20      mano 5,44 / hand 5,45
diferencia media: −0,06 puntos de Zipf      desviación: 0,20
```

**El mismo concepto recibe prácticamente el mismo valor en los dos idiomas.** Una desviación de
0,20 son 14 puntos de rank sobre una banda de 500: ruido. Eso convierte el *raw-score merging* sin
medir en *normalized-score merging* justificado, que es el término que la literatura usa.

#### Lo único que había que decidir AHORA: `meta.rank_basis`

⚠️ **Es la pieza que no se puede agregar después sin reconstruir el pack**, y por eso entra con
este build y no más tarde.

El pack declara **qué significa su `rank`**: `frequency-zipf-v1` o `page-richness-v1`. Sin eso la
app no tiene forma de saberlo, y el problema es real y medido:

| pack | fórmula | rango de `rank` |
|---|---|---|
| `es-def-wikc` | kaikki, riqueza | **668 – 1997** |
| `es-def-wd` | `wikidata.py`, la suya propia | **911 – 997** |

Mismo idioma, escalas distintas, y nada se lo decía a la app.

**La regla en `orderFor` es la más débil que sirve: a igual posición manda el mejor calibrado.**
Va **después de `score`** a propósito — desempata, no reordena. Ponerla antes hundiría al otro
pack entero y con él sus lemas exclusivos, que son justo la ganancia que D-136 midió.

`RankBasis.fromId` **no lanza** ante un id desconocido: un pack más nuevo puede traer una base que
esta versión no lee, y eso degrada a `page-richness`, que es lo que todos eran.

### ✅ El prior de orden pasa a ser frecuencia de uso — CONSTRUIDO 2026-09-21

`rank` era **riqueza de página del dump** y eso premia verbos: el perfil `es` cuenta formas
flexionadas con tope 80, y un verbo español trae hasta 222. Medido: la correlación de Spearman
entre `rank` y la frecuencia real de uso era **−0,250**, donde se esperaría −1.

#### ⚠️ El defecto no estaba donde parecía, y eso acotó el alcance

`coverageBand` (D-142) ya defiende el peldaño de prefijo **sin confiar en el pack**, así que ahí
el daño estaba contenido. `orderFor` aplica la banda **sólo a `MatchKind.PREFIX`**; los peldaños
`INFLECTED_FORM` y `TRANSLATION` ordenan por `rank` puro y no tienen defensa:

```
house → solar, alojar, albergar, domiciliar    ← «casa» no aparecía
water → gastar, regar, resbalar                ← «agua» no aparecía
book  → reservar, fichar, multar               ← «libro» no aparecía
```

**Arreglar el prior era lo único que alcanza a los tres peldaños.** No se tocó `orderFor`, ni
`coverageBand`, ni la cascada: la investigación de autocompletado converge en *dos fases* —prior
estático de popularidad, después calidad del match— y este repo **ya tenía las dos**. La
arquitectura era correcta; el prior estaba mal calculado.

#### Dos fuentes, combinadas en escala Zipf

| Fuente | Licencia | Rol |
|---|---|---|
| **OpenSubtitles** vía FrequencyWords (`es_50k`, `en_50k`) | **CC BY-SA 4.0**, igual que el pack | señal principal |
| **Tatoeba** *(ya en disco, ya declarada)* | CC BY 2.0 FR | rellena lo que aquella no cubre |

**Por qué subtítulos**: la literatura de SUBTLEX es consistente en 6+ idiomas — predicen el
reconocimiento de palabras mejor que los corpus de libros, porque se parecen al habla. Es el
registro de alguien buscando en un reloj.

**Por qué Zipf**: la distribución es de ley de potencias —`de` aparece 14.459.520 veces y la
palabra 50.000 aparece 185—. Sin logaritmo la primera aplasta todo. Cada lema toma su valor de
**una** fuente: promediar ocurrencias contra frases-que-la-contienen sería calibrar una escala
contra otra sin medirlo.

⚠️ **`wordfreq` como librería quedó descartado**: es dependencia pip y `tools/CLAUDE.md` fija
*stdlib only* como propiedad deliberada. Se usó la idea, no el paquete.

#### Dos bandas disjuntas, porque el 82,6 % no tiene señal

Sólo el **17,4 %** de los lemas tiene frecuencia conocida. Quien la tiene se ordena por ella;
quien no, cae **en bloque** debajo y conserva entre pares el orden de riqueza de siempre. No
aparecer en 50.000 palabras de subtítulos ya es evidencia de rareza.

#### Medido sobre gemelos: misma muestra, mismo dump, sólo cambia el flag

| | rho(`rank`, frecuencia real) | tamaño |
|---|---|---|
| sin señal | **−0,169** | 6,03 MB |
| **con señal** | **−0,678** | **6,03 MB** |

**Cuatro veces mejor y cero bytes**, porque `rank` es una columna que ya existía.

#### ⚠️ Un defecto que sólo se vio en el pack construido: el acento

La primera versión usaba `norm()` como clave de búsqueda, que **pliega acentos** — y en español
el acento **distingue palabras**. Resultado: una palabra oscura heredaba la frecuencia de su
homógrafo común.

```
háber   (una unidad oscura)   rank= 97    ← se llevaba la de «haber», el verbo (puesto 210)
hábil                         rank=237
líbero                        rank=240    ← sumaba «libero» + «liberó»
liberal                       rank=248
```

Con la clave que conserva el acento (`frequency.key`), `háber` y `líbero` caen a 995 y 994 — la
banda sin señal, que es la respuesta correcta: nadie midió su frecuencia.

⚠️ **Y la métrica mintió a favor del bug**: `rho` se calcula contra `tatoeba.frequencies`, que
también usa claves `norm()`, así que la versión con el acento plegado **puntúa mejor** (−0,735)
por acertar contra una verdad igualmente plegada. Se eligió el número peor por ser el correcto.

#### Lo que queda

- **La verificación visible pide el pack completo.** La muestra 1/12 no contiene `casa`, `sol`,
  `agua` ni `libro`, así que la sonda `cas → casa` sólo se puede correr después del build real.
- `sources/oewn.py` y `sources/wikidata.py` tienen su **propia** fórmula de rank y quedan fuera:
  se anota, no se toca.

### Result ordering: document it, then improve it

**Status.** Asked for on 2026-09-21: *«documentar y mejorar los filtros sobre el orden en que
poner las palabras en los resultados»*. **Nothing built.**

**The ordering exists and is scattered.** It is assembled across three places, and nobody can see
the whole of it without reading all three:

| Where | What it contributes | Decision |
|---|---|---|
| `build.py` | `rank`: page richness in the dump, plus a proper-noun penalty | D-063, D-134 |
| `SqlitePackSource` | The cascade's rung order, and `score` = position within one pack's list | D-068 |
| `SearchRepository.orderFor` | Match kind → proper-noun demotion → coverage band → score → headword, packId, entryId | D-142, D-154 |

⚠️ **And the piece that is easiest to get wrong is already written down in the wrong place**:
`score` is *the position inside its own pack's list*, not `rank`. That is what makes the merge
ordinal and immune to a badly calibrated pack — and it lives in a comment inside
`SearchRepository`, not anywhere a person would look for "how are results ordered".

**What is missing, in order:**

1. **One document that states the whole order**, end to end, and what each step is defending
   against. Today the *why* of every step exists — in three files.
2. **A way to see the order change.** Every improvement so far was found by running a query
   against the real pack and reading the list (D-142's `cas`, D-154's `ital`). That was done by
   hand each time; `tools/measure_query_cost.py` already opens real packs and could print the
   ordered list for a set of queries, turning "it looks better" into a diff.
3. **The known-open filters**, none of them measured yet: whether an inflected form should rank
   below its lemma, whether a multi-word phrase should rank below a single word of the same
   coverage, and whether `rank` across two packs from different dumps is comparable at all — the
   ordering's own comment says it is not, and nothing acts on that.

## Publicar: qué falta para una build de producción

**Estado.** **Medido el 2026-09-20 corriendo `assembleRelease`.** Sale, pero sale
`app-release-unsigned.apk` de **33 MB**. Lo que falta, en orden de bloqueo:

### 1. La keystore — y es del humano, no del agente

No hay ninguna configurada, así que el build produce un APK **sin firmar** en vez de romper: un
clone limpio tiene que seguir compilando (`signingConfigs.findByName`, no `getByName`).

⚠️ **La restricción no es negociable y está en `.claude/settings.json`**: la keystore la genera el
humano, **vive fuera del repositorio**, y `local.properties` guarda **sólo su ruta**. Ni la clave
ni las contraseñas entran al repo, y `local.properties` está en `permissions.deny` — el agente no
puede editarlo. `audit_dictionary.py::check_release_signing` busca los dos errores silenciosos:
una keystore o contraseña trackeada, y firmar el release con la clave de debug.

```sh
# 1. Generar la keystore FUERA del repo (una sola vez, y guardarla: si se pierde,
#    ninguna app instalada se puede volver a actualizar).
keytool -genkeypair -v -keystore ~/claves/wearos-dictionary.jks \
        -keyalg RSA -keysize 4096 -validity 10000 -alias dictionary

# 2. Apuntar local.properties a ella (este archivo NO se commitea):
#      dictionary.keystore=/Users/<vos>/claves/wearos-dictionary.jks
#      dictionary.storePassword=...
#      dictionary.keyAlias=dictionary
#      dictionary.keyPassword=...

# 3. Construir y comprobar que quedó firmado:
./gradlew :app:assembleRelease
ls app/build/outputs/apk/release/          # tiene que decir app-release.apk, NO -unsigned
```

### 2. Lo que queda por verificar antes de publicar

| Qué | Estado | Por qué importa |
|---|---|---|
| **Gate completo** | ✅ 21 checks, verde | |
| **Tests instrumentados en API 37** | ✅ 34 tests, 0 fallas | |
| **Tests instrumentados en API 33** (el `minSdk`) | ❌ **sin correr** | El AVD `wear_api33` existe. **El propósito de esos tests es que el ICU difiere entre versiones**, así que correr uno solo no prueba lo que intentan probar |
| **Cualquier cosa vista en el reloj real** | ⚠️ **parcial** | La app, los dos packs y siete comprobaciones se vieron el 2026-09-20; los tiles dibujando y los tests instrumentados, no. Ver §Lo que YA se vio en el reloj |
| **R8** | ❌ apagado | **32,9 de los 33 MB son dex.** Es la palanca más grande que queda. Encenderlo reintroduce la clase de bug que sólo aparece en release, así que va atado a la verificación en dispositivo. Ver O-2 |
| **ABIs** | ✅ sólo `arm64-v8a` y `armeabi-v7a` en release | ⚠️ **El APK de release ya no se instala en un emulador x86**; el de debug sigue trayendo las cuatro |
| **Instalador de packs** | ❌ no existe | Los packs se copian a mano con `devpack.py`. Para publicar hace falta, y está bloqueado por el `sha256` del pack entero |

### Pendiente de subir al reloj

⚠️ **Hay un APK nuevo listo y SIN subir: `versionCode 5` / `0.5.0`, debug, 111,0 MB**
(2026-09-23). El reloj sigue en `versionCode 4`. Lo que trae y por qué hay que subirlo:

- **Los dos núcleos viajan adentro por primera vez que funciona.** `bundlePacks` buscaba en
  `../wearos-dictionary-data/` y el rebuild del 22 los movió a `dist/`: el APK que se armó desde
  entonces **no llevaba ningún diccionario**, en silencio (D-228). Verificado en el emulador: los
  dos núcleos se extraen y abren, `48.292` y `75.734` entradas, 0 rechazados.
- **Instalarlo caduca el memo de packs verificados** (D-225), así que el primer arranque en el
  reloj re-verifica **todos** los packs que tenga — los dos núcleos nuevos y los cinco completos
  que ya están. Es lo esperado y conviene saberlo antes de leer el arranque como una regresión.
- **`en-core` vuelve a aparecer en el selector** aunque haya un completo de español instalado
  (D-227). Ése es el cambio que hay que mirar en el reloj, porque el reloj **sí** tiene completos
  instalados y el emulador no.

```sh
./gradlew :app:installDebug          # 111 MB por adb; ver la advertencia de abajo
```

⚠️ **Y no se pudo verificar en el emulador, por falta de herramienta**: que buscar en un núcleo
devuelva resultados **por la vía del usuario**. El campo de búsqueda no toma foco con un tap
sintético — ver §Sembrar la consulta desde `adb`. Se contestó leyendo el pack con `sqlite3`
(`cas` → `casa, caso, casi`; `hous` → `house, household, housing`), que comprueba el dato y no la
pantalla.

⚠️ **Tampoco se verificó `extractIfNewer` en un dispositivo** (D-226): necesita un núcleo bajado
del catálogo cuyo `data_version` difiera del que trae el APK, y hoy los dos salen del mismo
`dist/`, así que son iguales y no hay nada que comparar. El gate cubre el plan; el cableado no.

<details>
<summary>Lo anterior, del 2026-09-21, conservado</summary>

✅ **Subido y verificado el 2026-09-21.** El reloj tiene `versionCode 4` / `0.4.0`, el build
**benchmark con R8** (5,48 MB), y los packs **no se tocaron porque son los mismos bytes** que los
locales —mismo `pack_id`, mismo tamaño— y lo único que cambiaría al reconstruirlos es metadata
(`data_version`, `source_date`). Empujar 372 MB por adb inalámbrico para cambiar un campo, en una
conexión que ya se cortó una vez a los 75 MB, no se paga.

⚠️ **Y eso deja algo sin verificar a propósito**: D-170 (`data_version` de 12 dígitos) sólo se
comprobó en los datos, con los packs viejos de 8 dígitos parseando bien como `Long`. La primera
reconstrucción de packs lo cierra.

```sh
# 1. Con el reloj conectado (adb pair / adb connect, o por cable):
./gradlew :app:installDebug

# 2. Los packs YA están al día. Si hiciera falta reinstalarlos:
python3 tools/devpack.py list                      # qué hay
python3 tools/devpack.py install ../wearos-dictionary-data/es-def-wikc.db
python3 tools/devpack.py install ../wearos-dictionary-data/en-def-wikt.db
python3 tools/devpack.py rm <pack viejo>.db        # los anteriores NO se reemplazan solos
```

Lo que hay que mirar ahí, y que no se pudo verificar de otra forma:

- **Las previews de los tiles al agregarlos** (D-149) y **que los tiles se dibujen con R8**
  (D-163). El package manager resuelve los dos `TileService` por su nombre original, así que R8 no
  los borró; que RENDERICEN es lo que falta, y agregar un tile es un gesto del usuario.
- ~~⚠️ **El respaldo entre idiomas (D-168) y los sinónimos tocables (D-169)**~~ — **both entries
  are stale, for different reasons, and that is worth keeping visible.** D-168 **was reverted** by
  D-189: the fallback is off, so there is nothing on a watch to verify. D-169 **was verified** on
  the emulator on 2026-09-23 (P-2): the links exist and they hit. And the premise both shared —
  *"they cannot be driven by `adb`"* — stopped being true the same day: D-232 seeds the query with
  a broadcast, so the text field never needs focus from a synthetic tap. Three claims in one
  bullet, all three overtaken within two days of being written.
- El inicio con **tres recientes y el botón** (D-148) sobre un historial real.
- **El orden de los nombres propios sobre el pack real** (D-154): escribir *ital* y *medel*. Se
  simuló contra el pack antes de escribirlo, pero simular no es la lista dibujada.
- **Mantener apretada una guardada** (D-155). El gesto largo se verificó en Robolectric; que en un
  reloj puesto el umbral de tiempo se sienta bien es otra cosa.
- **Cambiar el idioma de la interfaz** (D-158). `setApplicationLocales` **recrea la Activity**: lo
  que hay que mirar es que el cambio no deje la app en la pantalla equivocada ni pierda la
  búsqueda a medio escribir.
- **El diagnóstico al fondo de Ajustes** (D-158) diciendo el `versionName` correcto — es la
  primera vez que ese número sale del APK y no de un documento.

⚠️ **Y una advertencia operativa**: la subida de 315 MB por adb inalámbrico se cortó una vez a los
75 MB. Reintentar alcanza —`devpack.py` limpia el `.part` antes de escribir— pero por cable no
debería pasar.

</details>

⚠️ **Esa advertencia ahora también vale para el APK.** Pasó de 5,48 MB a **111,0 MB** al meterle
los núcleos, o sea **por encima de los 75 MB en que la conexión inalámbrica ya se cortó una vez**.
A diferencia de un pack, un `install` cortado no deja nada a medias —el package manager es
atómico— así que el peor caso es reintentar. **Por cable no debería pasar.**

### 3. El orden que recomiendo

1. Generar la keystore y comprobar que `assembleRelease` produce `app-release.apk`.
2. Correr los instrumentados en **API 33**, que es gratis y cierra una brecha real.
3. Instalar en el reloj y mirar las 31 decisiones que nunca se vieron ahí.
4. Recién entonces R8, con los instrumentados corriendo **contra el release**.

## Optimización

La app se usa en ráfagas cortas en una muñeca. Eso fija las prioridades: **lo que más gasta
batería no es la búsqueda, es la red y la pantalla encendida.** La guía oficial de Wear OS
clasifica el acceso a red como *very high impact* y encender la pantalla como *high impact*;
mantener la CPU ocupada también es *high*, pero nuestro trabajo de CPU dura milisegundos.

Las fases van en este orden por una razón: **no se optimiza lo que no se mide**, y hoy no hay
una sola medición real. Los presupuestos de `docs/formato-pack.md` son objetivos escritos a
priori.

### O-1. Hacerlo medible (antes de tocar nada)

**Estado.** **Desbloqueada y con los primeros números** (2026-09-21). El reloj se conectó y se
midió: arranque **500 ms en frío / 278 ms tibio** con 372,6 MB de packs abiertos, y el desglose de
energía de `dumpsys batterystats`. Ver `docs/bateria.md` §What the watch actually said.

⚠️ **Segunda sesión de reloj, la misma tarde: los números son de un build que nadie usa.** El APK
sideloadeado es `DEBUGGABLE` y **ART no lo compila AOT** (`status=run-from-apk`), así que el mismo
arranque dio **2203 ms**, y **1401 ms** tras forzar la verificación del dex. Los 500 ms son de un
build **con R8** y dos packs. Lo que sí quedó medido limpio: **la app es el 17,8 % del consumo del
reloj**, y dentro de eso **la pantalla es el 85 % y toda la CPU el 15 %** — lo que **desclasifica**
el redibujo en reposo como prioridad de batería, aunque quede **confirmado en 4,0 fps**. O-1 no se
cierra hasta medir sobre **release**. Ver `docs/bateria.md` §What the watch said on the second
session.

La reabre conseguir el reloj.

Macrobenchmark sobre el emulador para correctitud y sobre el reloj para números. Baseline de:
cold start, `suggest()` p50/p95 con prefijos de 1 a 5 letras, tiempo de abrir una entrada
(incluye descomprimir el payload), y tamaño del pack.

**Con qué choca.** Con la costumbre de optimizar por intuición. Cada fase siguiente necesita el
número de antes para justificarse.

**Qué hay que decidir antes.** Nada. Es el prerrequisito de todo lo demás.

> **El emulador no sirve para esto.** La documentación oficial es explícita: *"Run all final
> performance tests on a suite of physical Wear OS devices"*. El emulador cierra la brecha de
> **correctitud** (Unicode, FTS5, planes de consulta), no la de **rendimiento**: sus números de
> CPU y batería no representan nada.

### O-2. R8 y baseline profiles

**Estado.** ✅ **R8 ENCENDIDO** (2026-09-20, D-163). Queda pendiente sólo la verificación en
dispositivo, que es lo que O-2 siempre pidió, y los baseline profiles, que necesitan el reloj.

| | APK | dex | libs nativas |
|---|---|---|---|
| Antes | **33,0 MB** | **29,5 MB** | 2,4 MB |
| Ahora | **5,47 MB** | **2,70 MB** | 2,44 MB |

**El dex baja un 91 %**, y compila **sin una sola regla de keep**. Se comprobó además que
sobreviven las tres clases nombradas en `AndroidManifest.xml` —las dos de tiles y `MainActivity`—
y el driver JNI de SQLite, leyendo las cadenas del dex encogido.

⚠️ **Eso es un hecho sobre un archivo, no sobre una app que funcione.** Se comprobó con
`aapt2 dump xmltree` que el manifest del release conserva los tres nombres de componente y que el
dex encogido los contiene, pero leer nombres de clase es evidencia más débil que lanzarlo. **El
riesgo que O-2 siempre nombró no cambió**, y los dos `TileService` son el borde filoso porque
`app/CLAUDE.md` ya tiene escrito que romperlos no da error de compilación ni test.

**Lo que falta, y necesita el reloj conectado:**

1. **Instalar el release con R8 y ejercitar todas las superficies** — búsqueda, ficha, los dos
   tiles, ajustes, borrado de packs. Es el gate que esta sección siempre tuvo.
2. **Generar los baseline profiles.** Requieren Macrobenchmark, que a su vez requiere un build
   type minificado y no-debuggable; AGP 9 expone además `optimization { baselineProfile { } }`.
   Números oficiales: ~30 % de arranque, y entre 15 y 30 % más con startup profiles.

⚠️ **Y hace falta una keystore para que el release se pueda instalar** (D-086). La genera el
humano, nunca el agente: `./gradlew :app:releasePrecheck` imprime el `keytool` exacto.

**Por qué importa para batería y no sólo para tamaño**: menos dex es menos carga de clases, menos
memoria y menos JIT en **cada arranque del proceso**, y en esta app cada arranque es alguien
mirando la pantalla. Ver `docs/bateria.md` §The action plan.

La guía oficial de rendimiento de Wear OS dice, literal: *"Start with the most effective
performance tool types: baseline profiles (including startup profiles) and the R8 code
optimizer."*

**Con qué choca.** `app/build.gradle.kts` tiene hoy `release { optimization { enable = false } }`
— R8 desactivado. **Eso no fue una decisión, viene del template**, y deja el release sin
optimizar ni encoger.

Activarlo reintroduce la clase de bug que solo aparece en release: código o recursos que R8 quita
y que en debug estaban. Por eso esta fase va **atada** a la comprobación pre-entrega en
dispositivo, no antes.

**Qué hay que decidir antes.** Si se agrega un *startup profile*: la documentación advierte que
aumenta el tamaño del APK, y ya estamos sumando ~1–1,5 MB por ABI de SQLite nativo. Es un
trade-off que necesita el número de O-1.

### O-3. Tamaño del pack

**Estado.** ⚠️ **REENCUADRADO el 2026-09-21 (D-207): el presupuesto de 50 MB se retiró.**

Se rompió desde el primer build —72,2 MB contra 50— y en nueve citas **nunca hizo cambiar una
decisión**: sólo se usó para anotar que se incumplía. Y medía lo que no duele: el reloj reporta
**9,0 GiB libres** y los cinco packs juntos son 478 MB, el **5 %**.

**Lo que lo reemplaza**, atado a mediciones que ya existen:
1. **El arranque en frío** — 500 ms con 372,6 MB abiertos. Un pack nuevo no puede empeorarlo sin
   medirlo: eso sí lo siente quien levanta la muñeca. ⚠️ **Ese número es de un build de release con
   R8 y dos packs.** El 2026-09-21 (tarde), con un APK de debug y 444 MB en tres packs, el mismo
   arranque dio **2203 ms** (`status=run-from-apk`) y **1401 ms** tras forzar la verificación. No
   es una regresión: son dos mediciones distintas. **El límite sigue apoyado en el número de
   release, y re-medirlo sobre release es un ítem abierto.**
2. **El núcleo dentro del APK** — 17,1 MiB de los 66. Límite **duro**: lo que viaja en el APK se
   descarga aunque no se use.

**Lo que ya no se limita**: los bytes de un pack completo. Llegan por descarga diferida a carga y
Wi-Fi (D-029) y el usuario elige instalarlos. Así se resuelve la tensión que esta sección
arrastraba: *«que vayan completas»* gana, y lo que se vigila es **lo que se paga sin pedirlo**.

**Cómo se movió el tamaño, para tenerlo escrito.**

| | antes | 2026-09-20 | **tras el rebuild del 2026-09-21** |
|---|---|---|---|
| español | 68,3 MB | 73,6 MB | **77,2 MB** (73,6 MiB) |
| inglés | 272,4 MB | 315,9 MB | **321,7 MB** (306,8 MiB) |
| bilingüe | — | 57,9 MB | **66,5 MB** (63,4 MiB), y ganó un idioma entero |

⚠️ **Siguió moviéndose el 2026-09-21** y conviene que esté escrito: el español subió por las
frecuencias y el bilingüe **+8,2 MB** por volverse bidireccional (D-196) — 85.505 entradas
inglesas nuevas contra los 13,3 MiB que se ahorran vaciando `trans`. Eran costes conocidos y
aceptados; lo que no estaba era el número.

Contra un presupuesto blando de **50 MB** (D-028). Lo que lo movió: **D-141** (ninguna fuente
pierde palabras: el inglés pasó de 794.355 a 956.150 entradas al dejar entrar los nombres
propios) y las tres fuentes nuevas, que son baratas por separado —Tatoeba +368 KB, WordNet
+~100 KB— pero se suman.

⚠️ **La tensión es real y no se resuelve midiendo**: *«que vayan completas»* y *«50 MB»* apuntan
en direcciones opuestas. Las palancas existen y están medidas —`--nombres lexical-only` devuelve
31.549 entradas y 5 MB en español— así que es una decisión de producto, no de mecanismo. Sigue esperando el número de latencia de O-1 para saber qué
se puede sacrificar sin romper la búsqueda.

**Dónde está el peso, medido** (desglose completo en `docs/formato-pack.md` §Presupuestos):

| Objeto | MB | Parte | ¿Se puede recortar? |
|---|---|---|---|
| `form` | 33,4 | 46,3 % | **Es el 93,5 % conjugaciones de verbos.** Recortar acá es *falta una palabra* |
| `entry` | 17,8 | 24,6 % | Los ejemplos de uso son el 17,9 % del texto y promedian 152 bytes. Bajar a 0 ejemplos es el único recorte grande que no pierde ninguna palabra — a cambio de perder el desambiguador de la acepción |
| `fts_def_data` + `docsize` | 10,0 | 13,8 % | Acá viven `detail=none` y `columnsize=0` |
| `idx_entry_norm` + `idx_entry_fuzzy` | 9,2 | 12,9 % | `idx_entry_fuzzy` es el precio del nivel tolerante |

⚠️ **La tabla de arriba es del pack ESPAÑOL, y generalizarla fue un error que duró dos días.**
Medido con `dbstat` sobre los packs reales el 2026-09-23, el inglés tiene otra forma por completo:

| | español (77,6 MB) | **inglés (329,6 MB)** |
|---|---|---|
| `entry` | 20,0 MB — 26 % | **150,6 MB — 48 %** |
| `fts_def_data` | 9,1 MB — 12 % | **69,2 MB — 22 %** |
| `idx_entry_norm` | 5,8 MB — 7,5 % | **38,0 MB — 12 %** |
| `idx_entry_fuzzy` | 4,1 MB — 5,3 % | **27,7 MB — 8,8 %** |
| `form` | **32,9 MB — 42 %** | 19,1 MB — **6 %** |
| `fts_def_docsize` | 1,4 MB | 9,2 MB — 2,9 % |

O sea: *«el recorte obvio no existe porque el 46 % está en `form`»* **vale para el español y es
falso para el inglés**, donde `form` es el 6 % y el índice FTS solo es el 25 %.

**Y las dos opciones abiertas no valen lo mismo, medido** (D-241). Reindexando **texto real del
pack inglés**, 60.000 entradas, de las cuatro formas:

```
detail=full (hoy)     15,50 MB
detail=none            6,93 MB   →  ahorra 55,3 %   ≈ 38 MB del pack inglés (12 %)
columnsize=0          14,95 MB   →  ahorra  3,6 %   ≈  9 MB
```

⚠️ **La compatibilidad se verificó ejecutando la expresión MATCH real de la app, no razonándola**,
y eso corrigió una suposición: `columnsize=0` **no** rompe `ORDER BY rank`. Lo que `detail=none`
rompe son las **frases de varias palabras**, que hoy no se usan (`toMatchExpression` entrecomilla
token por token) — así que su costo real es **cerrar la búsqueda por frase exacta para siempre**.
Y el costo de `columnsize=0` es de este repo: borra `fts_def_docsize`, una de las siete
invariantes que D-217 le agregó a `PackFile.open`.

**Ninguna se aplicó**, por decisión explícita: primero el número de arranque de un reloj (P-4).
Aplicar un recorte sin el número que debería proteger es exactamente lo que esta sección prohíbe.

**La palanca de producto ya se tiró, y está decidida (D-116).** Lo que decía esta fila —*"las
32.305 de `pos = name` son el primer candidato y nadie decidió si un diccionario de muñeca las
quiere"*— quedó cerrado: **no las quiere**, salvo las que tienen vida léxica. Medido sobre los
packs reconstruidos:

| | Antes | Ahora | Δ |
|---|---|---|---|
| Español | 146.194 entradas · 72,2 MB | **114.619 · 68,1 MB** | −21,6 % entradas, −5,7 % bytes |
| Inglés | 956.150 entradas · 295,1 MiB | **794.355 · 255,7 MiB** | −16,9 % entradas, **−13,4 % bytes** |

**Y la lección es que en español la ganancia NO fue de bytes.** Los 32.305 nombres propios eran
sólo 0,63 MB de payload: lo que se ganó fue dejar de devolver *"Apellido."* en el 22,1 % de los
`norm`. En inglés sí fue de bytes además de ruido. El español subió 0,89 MB por los sinónimos
(D-117), así que el neto es −4,1 MB.

**Sigue sobre el presupuesto de 50 MB (D-028)** y el recorte grande que queda es el mismo de
antes: `form`, que no se toca sin romper la búsqueda por flexión.

**Con qué choca.** Con D-028 (50 MB blandos) y con las tres decisiones abiertas de
`docs/decisions.md`. Ninguna se puede cerrar sin el número de latencia de O-1.

**Por qué importa para la batería y no solo para el disco.** El pack se descarga por red, que es
lo que más gasta. Cada MB que se ahorra es tiempo de radio que no se paga.

### O-4. Batería

**Estado.** **Una sección que creció se volvió un documento: vive en `docs/bateria.md`**
(2026-09-20). Acá queda el puntero y lo que falta para cerrarla.

**Lo que cambió.** Apareció el primer dato real de batería que este proyecto tuvo: el reloj
atribuyó **9,4 %** al diccionario tras un rato largo con la app abierta. Lo que esta sección decía
antes —*"los tres consumidores reales, en orden"*— era razonamiento, no medición, y el orden ya no
se sostiene solo.

**Lo que se midió** (`tools/measure_query_cost.py`, sobre los packs reales): una búsqueda de
palabra completa cuesta **3,0 peldaños y ~1 ms de SQL**; abrir una palabra cuesta **una sola
consulta de 0,18 ms** —no una por palabra tocable, que era la sospecha—; y **6,3× más entradas
cuestan casi lo mismo**, porque el índice es logarítmico. Con eso, una sesión pesada entera suma
**segundos de CPU** contra **decenas de minutos de pantalla**.

✅ **CORRIDO el 2026-09-21, y la respuesta no era la esperada.** El sistema atribuye **5,98 mAh a
la pantalla y 6,08 a la CPU** —casi iguales— y, peor, **la app redibuja ~5 veces por segundo con la
pantalla estática y nadie tocándola**, quemando **3,6 % de un núcleo mientras esté abierta**.

⚠️ **Eso mató la conclusión central de `docs/bateria.md`**, que decía que en esta app la CPU nunca
podía ser la batería. Era cierto sobre el SQL —~1 ms por búsqueda, medido— y el documento mismo
avisaba que no pesaba el dibujado. Ahí estaba todo.

**El ítem más grande del plan pasó a ser otro**: encontrar qué invalida la composición cuando nada
cambia. Necesita una traza de Perfetto con las categorías `view` y `graphics`.

**Investigado contra fuentes primarias el 2026-09-20**, y el modelo de energía de Android zanja la
discusión: `screen.on` son **~200 mA** sólo por estar encendida y `screen.full` suma **100–300 mA**
más, contra `cpu.idle` en **~3 mA**. Una CPU ocupada cuesta 100–200 mA **durante los milisegundos
que está ocupada**. De ahí sale el principio que ordena el plan de acción: **en esta app la moneda
son segundos de pantalla, no milisegundos de CPU**, y cualquier idea que se mida en los segundos es
grande aunque no toque una consulta.

⚠️ **El famoso 3,2 % por hora NO aplica acá**: es una métrica de *watch faces*, medida *«cuando los
dispositivos no están cargando y no hay apps en uso»*. Citarlo contra nuestro 9,4 % sería comparar
dos cosas distintas.

⚠️ **Y una creencia que la medición mató**: el comentario de `SqlitePackSource` dice que el
prefijo es *"el 95% del uso"*. Es cierto mientras se escribe y **falso para la búsqueda que de
verdad corre**: D-128 no busca con el teclado abierto, busca una vez al cerrarlo y sobre la
palabra **completa**, que es justo el caso donde el prefijo devuelve poco y la cascada sigue.

### O-5. Animaciones y trabajo en el hilo de UI

**Estado.** ✅ **Cumplido por omisión, y verificado** (2026-09-20). Esta sección decía *«depende
de que exista una interfaz; hoy no existe»*, lo cual dejó de ser cierto hace sesiones.

La guía oficial pide minimizar animaciones y, si hay un loop, dejar una pausa al menos tan larga
como la animación. **Medido buscándolas: el código no tiene ni una** — ni `animate*`, ni
`Animatable`, ni `rememberInfiniteTransition`, ni `AnimatedVisibility`. Las únicas que corren son
las que traen los componentes de Wear Material3, que es su responsabilidad y no la nuestra.

**Lo que queda es no romperlo.** Cada animación que alguien agregue despierta la CPU, y
`docs/bateria.md` es claro en que la moneda cara son segundos de pantalla: una animación que
retrasa la respuesta cuesta dos veces. No hay enforcer — sería un chequeo sobre una lista de
nombres de API, y eso envejece mal.

---

## Comprobación que falta y bloquea el ship

### Lo que YA se vio en el reloj, y lo que no

**Estado.** **Primera verificación en hardware el 2026-09-20.** El reloj es un **SM-L715F, Wear OS
sobre Android 17**, y confirma la configuración que D-131 y D-133 asumen:
`sw234dp w234dp h234dp 340dpi`, pantalla física **498×498**, 40 GB libres.

Instalado y funcionando: app **0.3.0 (versionCode 3)**, `es-def-wikc-tat-wn-wd` (75,2 MB) y
`en-def-wikt-wn` (315,5 MB). Los packs anteriores se borraron — si no, convivirían bajo otro
`pack_id` y el reloj mostraría dos diccionarios por idioma.

**Lo verificado en hardware, con lo que cierra cada cosa:**

| Qué se vio | Cierra |
|---|---|
| La app abre los dos packs sin rechazarlos | **D-142**: la verificación de `norm()`/`fuzzy()` sobre 64 entradas corre al abrir un pack de **315 MB** en un reloj real, sin coste perceptible |
| Arranque en frío **2.216 ms** (`am start -W`) | El primer número de rendimiento real que tiene el proyecto. Ver O-1 |
| El campo de búsqueda **despejado del reloj y con su forma entera** | **D-133**, y es el arreglo que el usuario reportó dos veces |
| Un `ES` y un `EN` en el selector; **dos palabras del día, una por idioma** | **D-145** |
| `definitions · 315,5 MB · EN` en la pantalla de diccionarios | **D-125** y **D-138**: el manifiesto llega a la UI |
| Una entrada inglesa con glosa y cita (`jimpy`) | El pack de 956.150 entradas se lee bien |
| **Los dos tiles registrados** y reconocidos por el sistema | La mitad de §Ver los dos tiles |
| Logcat sin un solo error de la app | |

**Lo que sigue sin verse en hardware:**

- **Los tiles DIBUJANDO.** Están registrados, pero agregarlos al carrusel es un gesto del usuario
  y no se puede hacer por `adb`. Es lo único que falta de ese ítem.
- **Los tests instrumentados en el reloj.** Los 34 corrieron en el emulador (API 37). Correrlos en
  hardware es lo que cierra de verdad las asunciones sobre ICU y SQLite del dispositivo.
- **Rendimiento y batería medidos.** Hay un número de arranque; no hay latencia de búsqueda ni
  consumo. Ver O-1 y O-4.

⚠️ **Operativo, para no perder media hora la próxima vez:** el reloj está por **adb inalámbrico**,
y una subida de **315 MB se cortó a los 75** con `BrokenPipeError`. **Reintentar alcanza** —
`devpack.py` borra el `.part` huérfano antes de escribir, así que la operación es idempotente— pero
conviene saberlo antes de asumir que el pack está roto. Por cable no debería pasar.

### Los vectores de normalización, corriendo en un reloj

**Estado.** **A medias**, y las dos mitades se cierran distinto. La de **correctitud está
hecha**: `NormalizationOnDeviceTest` pasa en API 33 y API 37.0 (2026-09-17). La de
**rendimiento está bloqueada afuera**: falta un reloj físico, y la reabre conseguir uno. Lo que
falta es **mecanismo**, no contenido: hay hardware que no está.

**Es la clase de bug que este repo no puede observar**: una divergencia entre las claves
precalculadas del pack y las que el reloj calcula, en un dispositivo cuya versión de Unicode
difiere de la máquina de build. Síntoma: falta una palabra, en un modelo de reloj y no en otro,
sin error.

El repertorio fijado (D-003) mata la mayor parte del riesgo, pero su residuo es real: NFD y
`lowercase()` siguen delegando en la plataforma, y esa verificación se hizo **entre Java 26 y
Python 3.9**, nunca sobre Android.

**El test ya existe: `NormalizationOnDeviceTest`, y ya corrió** (2026-09-17): pasa en API 33 y
en API 37.0. El invariante central deja de ser ASSUMPTION en Android en ese rango. Repetirlo al
agregar un nivel de API soportado, o al tocar la normalización:

```sh
./gradlew :dict-data:connectedDebugAndroidTest
```

Correrlo en **cada nivel de API soportado**, no en uno solo: el punto es justamente que las
versiones de ICU difieren entre versiones de Android.

**Ahora es posible.** Hay Android Studio con emulador, y acceso a un reloj físico. Eso parte la
clase de bug en dos, y las dos mitades se cierran distinto:

| Qué | Dónde se cierra | Por qué |
|---|---|---|
| **Correctitud**: normalización, FTS5, planes de consulta, codec del payload | **Emulador** | Depende de la imagen del sistema, no del silicio. Un emulador de API 33 tiene el ICU de API 33 |
| **Rendimiento y batería**: latencia, consumo, arranque | **Reloj físico, sin excepción** | La documentación oficial es explícita: *"Run all final performance tests on a suite of physical Wear OS devices"* |

La mitad de **correctitud está cerrada** en API 33 y 37.0. La de **rendimiento sigue abierta**:
falta el reloj físico, y sin él no hay ni un número de latencia ni de batería.

---

## Proceso y herramientas

### ✅ El triage del método — v8→v16 **CERRADO el 2026-09-23**, v17→v24 **CERRADO el 2026-09-24**

**Estado.** ✅ **Los seis bloques aplicados** (D-233 a D-239), contra la recomendación de partirlos
en dos sesiones — ver ⚠️ Desviación abajo. Lo que entró, por nombre:

| Bloque | Dónde aterrizó | Decisión |
|---|---|---|
| Principio 7, segunda mitad — observar después de shippear | `BuildConfig.BUILD_COMMIT`/`BUILD_TIME` generados, leídos en Ajustes y en `DEBUG_DUMP`, más el brief permanente `docs/preguntas-del-reloj.md` | D-238 |
| Principio 15 — el contenido del dueño no es del agente para completar | `CLAUDE.md` §Working style | D-239 |
| La regla de la escalera | `CLAUDE.md` §Guardrails | D-234 |
| Session loop 3 — las sondas las encuentra el audit | `check_no_probes_left_behind` | D-236 |
| Session loop 4 — lo que no debe cambiar se prueba por su invariante | La skill `verify` | D-237 |
| Session loop 7 — cada commit se ofrece cuando su pieza pasa | La skill `commit`, con el procedimiento de partición | D-235 |
| Estándares de ingeniería — el comentario describe el código; un documento durable no cita artefactos de sesión | `CLAUDE.md` §Working style | D-239 |

**El presupuesto, que era la restricción real.** `CLAUDE.md` estaba en **198 de 200** y hicieron
falta **10 líneas**. ⚠️ **Nueve salieron de dos secciones que ya estaban duplicadas palabra por
palabra** —§Verification en la skill `verify`, §Logging obligation en la cabecera del propio
changelog— así que desalojarlas borró una segunda fuente de verdad en vez de retirar una regla
(D-233). La décima salió de fundir dos filas de Wear Widgets que decían lo mismo desde dos lados.
El archivo quedó en **200 exactas**.

⚠️ **Principio 17 tiene un delta SIN SUJETO acá, y eso no es lo mismo que rechazado.** Su ejemplo
de v13 especifica un formatter que sólo escribe copias que conservaron cada comentario y cada
token no-layout. **Este repo no tiene formatter**, así que no hay nada que aplicar ni nada que
declinar. Lo que sí entró de ese principio es su disciplina 2 —una mejora de proceso no viaja
dentro de una feature— que faltaba. La falta de un veredicto para *«aplicado: no hay sujeto»* se
reportó como candidato.

✅ **v17→v24 triaged on 2026-09-24, and the blockage this entry declared had already lifted.**

⚠️ **What this entry asserted, and it was false at the moment of reading it.** It said v17–v21 was
still blocked because *«el Method changelog del bundle llega hasta la 16: cinco versiones sin una
línea que diga qué cambia para quien lee»*. **Rows 17–21 were written in v22**, and that row says
so in its own words: *"Rows 17–21 below were missing from this changelog while the header declared
21 … They are written now, from the bundle roadmap's Done entries"*. The blockage lifted two
versions before anybody looked again and nobody wrote it down — which is exactly why a reversed
entry has to say so **in its own place** and not only where it was found (the `verify` skill,
§retraction). The method changelog now reaches **24**.

**What the triage found, and what landed the same day:**

| Delta | What was missing here | Where it landed |
|---|---|---|
| v23 — *bootstrap wires the knowledge base in* | `CLAUDE.md` did not name `.agents/knowledge/` **once**, with 43 active notes on the other side | D-268: the two moments —before a design decision, before claiming done— and the row in the document map |
| — | `knowledge/INDEX.md` claimed *"checked by the repository's audit"* and nothing checked it | D-267 wrote a check by hand and **d-a2f271-969225 retired it the next day**: `bundle.py digest --check` already did it, along with four more |
| v22 — *a retraction reaches the row of the decision it reverses* | The `verify` skill carried the findings half only | The second half, with the reason it stays at rung 2 |
| v22 — `layout.md`: one skills folder, `AGENTS.md` only without a `CLAUDE.md` | Already true: there is one `.claude/skills/` and a `CLAUDE.md` exists | nothing to do |
| v18, v19, v20, v21 — workspace scope, the sync cycle, the tool refusing to infer | Release mechanics, not the carrier's | nothing to do |
| v23 — record ids by hash · v24 — privacy, carrier id | Already applied by the sessions of the 22nd, 23rd and 24th | — |

⚠️ **The 30 candidates in `.agents/tracking/candidates.md` are not ours.** They come from other
carriers —an interactive client, a data-analysis repository, a transactional service— and a
release admits them, never a carrier (`prompt-harvest.md` §*What the local step must never do*).

✅ **`r-a2f271` quedó registrado en `carriers.md` el 2026-09-23**, y el bloqueo que se había
declarado era **medio falso**. Eran dos supuestos y sólo uno se sostuvo:

- ⚠️ **«ese archivo no es de los que un carrier puede escribir» — FALSO.** `prompt-harvest.md`
  §*What the local step must never do* dice *«never write **outside** `tracking/`»*, y
  `carriers.md` está **dentro**. Lo que prohíbe es subir una versión o recalcular un digest, y una
  fila no hace ninguna de las dos. El supuesto venía de una paráfrasis, no del texto.
- ✅ **«`bundle.py register` no corre acá» — cierto**: necesita `tomllib`, o sea Python 3.11+, y
  esta máquina corre 3.9.6. Pero eso impide usar *la herramienta*, no *escribir la fila*.

El id no se adivinó: se derivó **reproduciendo la receta de `bundle.py carrier_id`** —`r-` más
seis hex del sha256 del remoto normalizado— en Python 3.9, y da `r-a2f271`, el mismo que la
sesión del 22 había anotado. Verificado que los tres digests siguen cuadrando después.

### ~~`set -- $x` en fish no separa campos~~ ✅ **CERRADO el 2026-09-23**

**Estado.** **Hecho** (D-247): la regla vive en la skill `verify`, con el reemplazo en fish
escrito. No entró a `CLAUDE.md` porque está en 200 de 200; el puntero se metió dentro de una
línea que ya existía, sin sumar ninguna.

El shell de las sesiones es fish, no bash. `set -- $pair` no hace *positional splitting*: en fish
`set` asigna a una variable y `--` es el fin de opciones, así que `$1` y `$2` quedan vacíos y el
comando sigue con argumentos faltantes. Falla **sin error**: el bucle corre y produce basura.

**Las dos veces.** Una el 2026-09-22 derivando presupuestos —las tres derivaciones salieron con
`--rango-mb 30 --tier` y reventaron con un `ValueError` crudo— y otra el mismo día comparando dos
versiones del método, donde `ugrep` avisó *«No such file or directory: docs/... v7»* porque el par
entero llegó como un solo argumento.

**Lo que costaría cerrarlo.** Nada de código: es una regla de una línea —en fish se itera con
`for x in ...; set -l a (string split ' ' $x)` o se escribe el bucle en Python— y el lugar es
`CLAUDE.md` §Commands o la skill `verify`. Se propone, no se ejecuta.

### ~~Sembrar la consulta desde `adb`~~ ✅ **CONSTRUIDO el 2026-09-23** (D-232)

**Estado.** **Hecho**, y usado el mismo día para cerrar lo que bloqueaba. La entrada se deja
entera porque el patrón es lo que importa: una fricción que aparece **dos veces** deja de ser
anécdota y pasa a ser trabajo. ⚠️ **Lo que esta entrada daba por abierto ya no lo está**: D-168
fue revertida por D-189 — no hay respaldo entre idiomas que verificar — y D-169 quedó contestada
en emulador el 2026-09-23 (P-2). Lo que la herramienta sí desbloqueó sigue en pie: P-11 y P-12 se
manejan enteras por `adb`.

**Las dos veces, con el costo.** La primera el 2026-09-21 **en el reloj**: dejó D-168 (el respaldo
entre idiomas) y D-169 (los sinónimos tocables) sin verificar *teniendo el dispositivo en la mano*,
y es la misma forma del problema que obligó a fijar espresso 3.7.0 (D-093). La segunda el
2026-09-23 **en el emulador `wear_sm_l715f`**: `adb shell input tap` sobre el campo no le da el
foco, el `input text "hous"` se fue a otro control, y la app terminó en **Ajustes** — o sea que no
se pudo comprobar de punta a punta que los núcleos del APK devuelvan resultados por la vía del
usuario. Hubo que contestarlo **leyendo el pack con `sqlite3`**, que comprueba el dato pero no la
app.

⚠️ **Lo que esto vuelve inverificable crece con cada sesión**: hoy ya son el respaldo entre
idiomas, los sinónimos tocables, el orden de los nombres propios sobre el pack real, y ahora
**buscar en un núcleo**. Ninguna de esas cosas la cubre el gate, porque todas terminan en la
pantalla.

**Lo que costó cerrarlo.** Un `BroadcastReceiver` registrado en runtime detrás de
`BuildConfig.DEBUG_INTENTS` — `true` en `debug` y en `benchmark`, **`false` en `release`**, donde
R8 pliega el `if` y se lleva la clase entera. La restricción que ordenaba el diseño —un receiver
exportado en producción es superficie de ataque y batería— se cumple **por construcción y no por
disciplina**: en release el código no existe. Ver D-232.

### ~~Un `Write` fresco sobre los archivos generados no lo bloquea nada~~ ✅ **CERRADO el 2026-09-23**

**Estado.** **Hecho** (D-246): un hook `PreToolUse` con matcher `Write` sale 2 sobre los cuatro,
con un mensaje que dice qué herramienta regenera cada uno. Verificado por sonda, los cuatro y el
control. Se preguntó antes de construirlo, porque es una mejora de proceso.

Las reglas `Write(ruta)` de `permissions.deny` se aceptan pero **nunca se consultan**, así que se
sacaron. Los cuatro archivos generados siguen protegidos contra `Edit`, que es el caso real —un
agente los modifica, no los recrea—, pero crear uno de cero desde vacío no lo para nada.

**Lo que costaría cerrarlo.** Un hook `PreToolUse` con matcher `Write` que compare el `file_path`
contra las cuatro rutas y salga **2**. Son ~10 líneas y el repo ya tiene el patrón: el hook
`PostToolUse` de los vectores. ⚠️ **No se construyó**: las mejoras de proceso se proponen.

### ~~El `--` dentro de un comentario XML rompe el build~~ ✅ **CERRADO el 2026-09-23**

**Estado.** **Hecho** (D-247), junto con la de fish y en el mismo lugar. Entró aunque falla
**ruidosamente** —el mensaje de AGP nombra el problema— porque es el mismo reflejo: `--` es como
este repo escribe un inciso en todos lados, incluido este párrafo.

Un comentario XML no puede contener `--`: es el cierre del delimitador. AGP lo reporta como

```
ERROR: .../strings.xml:24:28: Resource and asset merger: The string "--" is not permitted within comments.
```

seguido de treinta líneas de stack trace de Xerces que no nombran el archivo hasta la primera.
El síntoma es `mergeDebugResources FAILED`, que se lee como un problema de recursos y no de
puntuación.

**Las tres veces.** Dos el 2026-09-22 en `AndroidManifest.xml` —ya están en el changelog de esa
sesión— y una en `values/strings.xml` el mismo día. En este repo el riesgo es estructural y no
casual: **el estilo de comentario que la casa usa lleva `--` todo el tiempo** («el nombre mentía
--y lo decidió una medición--»), porque es el guión de inciso que se escribe sin tecla de raya.
En `.kt` y en `.py` es correcto; en XML rompe el build.

**Lo que costaría cerrarlo.** Un chequeo en `tools/audit_dictionary.py`: leer los `.xml` de
`app/src/main/res/` y de `AndroidManifest.xml`, y fallar si algún comentario contiene `--`. Son
~10 líneas y entra al gate, que es donde el error cuesta segundos en vez de una corrida de
Gradle. La alternativa —acordarse de escribir `—`— ya falló tres veces.

⚠️ **No se construyó**: las mejoras de proceso se proponen, no se ejecutan (CLAUDE.md).

La forma de trabajar está bajo las mismas reglas que el código: tiene fricción, la fricción se
mide, y casi siempre es lo más barato de arreglar del proyecto. **Se rankea acá, contra las
features, por la misma persona y en la misma sentada** — que es el único lugar donde la
comparación es honesta. Un log de fricción aparte es un archivo que nadie abre.

El umbral es el **segundo golpe**: la primera vez va al changelog de la sesión, la segunda sube
acá con la aritmética. Una molestia sola es ruido; la segunda es un dato.

### A check whose subject is prose goes vacuous on a legitimate edit — four hits, 2026-09-21 to 23

**Status.** **Friction, past the threshold.** It is here rather than only in a changelog because
the rule is *first hit in the session log, second hit up to this section with the arithmetic*, and
this one has **four**, in three days, from two different checks.

**The four, with what each cost.**

| # | Date | The check | What defeated it |
|---|---|---|---|
| 1 | 2026-09-21 | `check_spanish_prose_budget` | Its own author's decision rows, within the hour of the check landing (D-250) |
| 2 | 2026-09-23 | `check_mirror_declarations` | Translating the eight `ESTE ARCHIVO TIENE UN ESPEJO` markers to `THIS FILE HAS A MIRROR` — **0 matches** (D-252) |
| 3 | 2026-09-23 | `check_spanish_prose_budget` | A **file path**, `preguntas-del-reloj.md`, and a verbatim Spanish quote of the request, in a changelog entry: 4,182 against 4,180 |
| 4 | 2026-09-23 | `check_spanish_prose_budget` | New roadmap prose written in Spanish **because its neighbours were Spanish**: 2,317 against 2,313 |

⚠️ **Hits 2 and 3–4 are different failures and the distinction is the whole point.** Hit 2 is a
check going **silently vacuous**: nothing was wrong, the count just went to zero, and the only
reason the gate went red is that somebody had written `if found == 0: failure(...)`. Hits 3 and 4
are a check **firing correctly** on something it cannot be expected to classify — a per-line
detector cannot tell a Spanish path or a quoted request from Spanish prose.

**What it costs today.** About ten minutes a session, and it is charged to whoever is doing the
translating, which is the person least able to argue with it. Four occurrences, zero escapes:
**the checks are working.** What is missing is not strictness.

**What would close it, and neither is a relaxation.**

- **For the vacuity class**: an audit-wide rule that any check counting occurrences of a fixed
  phrase asserts a **non-zero subject count**. Three checks qualify today
  (`check_mirror_declarations` already has it; the other two need auditing). ~15 lines.
- **For the misclassification class**: exempt lines that are **a bare path or fully inside a
  quotation** from the Spanish detector. ⚠️ This is the dangerous one — an exemption is how a
  ratchet loosens, and D-250 exists because the tempting fix was to raise the ceiling. It should
  only be built with a mutation probe showing that real Spanish prose in a quoted line **still**
  fails.

**It is proposed, not executed**, and it does not ride inside a feature: a revert of the feature
would take the check with it.

### The repo is supposed to be in English and 3,850 lines are not

**Status.** ⚠️ **Measured 2026-09-21, after the rule was pointed out.** `CLAUDE.md` says it
plainly — *«Spanish in the conversation, English in the repo. Identifiers, comments, documents and
commit messages are English»* — with one documented exception, the UI strings. The repo does not
match:

| | Spanish lines |
|---|---|
| `tools/**` comments and docstrings | ~859 |
| `app/src/main` KDoc and comments | ~402 |
| `dict-core/src/main` | ~294 |
| `dict-data/src/main` | ~127 |
| `docs/roadmap.md` | ~962 |
| `docs/decisions.md` | ~187 |
| `docs/fuentes.md`, `README.md`, `tools/CLAUDE.md` | ~141 |
| | **≈ 3,000** |

⚠️ **This is not a formatting preference and that is why it is here rather than in a backlog.**
The prose in this repo *is* the design record — `docs/decisions.md` exists so a person can ask
"why is this decided this way" — and a record half the readers cannot use is worth less than its
length suggests.

**Why it has to be staged rather than done in one pass.** A mechanical translation of 3,000 lines
would destroy the thing that makes this prose useful: it is specific, it carries measurements, and
it names what went wrong. Translating it well is re-writing it. Translating it badly and calling
the rule satisfied is worse than the current state.

**The order, by how many readers each unit has:**

1. The three `CLAUDE.md` files under `app/`, `tools/`, `dict-*` — read by every session.
   `tools/CLAUDE.md` is **done**.
2. `docs/decisions.md` — the most-consulted document.
3. Source comments, module by module, smallest first: `dict-data` → `dict-core` → `app` → `tools`.
4. `docs/roadmap.md` last, because it is the biggest and the one that changes most — translating a
   moving document twice is the likely outcome of doing it first.

⚠️ **And the rule that prevents this from growing again**: everything written from 2026-09-21
onward is English. ~~No enforcer~~ — **there is one since 2026-09-23**: `check_spanish_prose_budget`,
a per-area ratchet that only moves down. The objection that killed the first version —false
positives on the terms this repo leaves untranslated (gate, covering index, payload, rung)— is
handled by requiring a Spanish marker **and** no English one on the same line, which measured ~170
false positives away.

⚠️ **The ratchet works and the habit does not, and that is the friction worth writing down.** New
prose came out in Spanish **five times across two sessions** on 2026-09-24 —in `docs/roadmap.md`
twice, `docs/decisions.md` twice and a Kotlin test file— in documents the rule covers, written by
sessions that had the rule loaded. **The arithmetic**: five interruptions of the gate, each paying
a full rewrite of the paragraph or row, and two of those rewrites moved the line count and failed
again for a second reason.

**The trigger is not forgetting the rule; it is where the edit happens.** All five were written
**inside a section that was already Spanish**, or reflowing a quote from the owner — the
surrounding language reads as the file's language, the conversation is Spanish too, and the
default wins with nothing questioning it. The ratchet only fires afterwards, when the paragraph is
already written.

⚠️ **This is the case D-234 names**: an instruction the agent had loaded and broke anyway is the
signal to raise its rung, not to repeat it louder. The rung above a post-hoc ratchet would be
something that answers *before* the first word — which is what this row is for, and it is not
built. Cheapest candidate: the ratchet's failure message naming the two legitimate exceptions (UI
strings, and a verbatim quote from the owner, which must be wrapped so the line also carries an
English word) instead of only the ceiling.

⚠️ **That rule is NOT holding, and it is now measured.** Running one ruler against the tree of
2026-09-21 and against today's: **5,281 → 6,966 lines, +32 % in two days.** (That ruler is looser
than the one behind the ≈3,000 above — same tree, two different heuristics — so the two totals are
not comparable; **the delta between two runs of the same ruler is.**) A large share of the growth
was written by the sessions of the 22nd and the 23rd, which is to say by an agent that had the
rule loaded and broke it anyway — which by D-234 is the signal to raise its rung rather than
repeat it.

✅ **Stage 3 is FINISHED, 2026-09-23.** Every code area the gate watches is at **zero**:
`dict-data/src/main` (D-249), `dict-core/src/main` 587 → 530 → **0** (D-251, D-254),
`app/src/main` 1,293 → **0** and `tools/**` 1,778 → **0** (D-254). Each ceiling dropped in the
same commit as its translation, so the ratchet cannot loosen. The four rows are **left at 0 rather
than deleted**: a removed row is a ceiling nobody watches.

**The measured total: 11,928 → 8,030 lines**, of which 4,180 are the changelog and deliberately
out of scope — a session log is a record of what was said at the time, and rewriting it makes it a
worse record. Excluding it, **3,850 lines remain**, none of them in a `src/main` tree:

| | Spanish lines | Stage |
|---|---|---|
| `docs/roadmap.md` | 2,314 | 4, last |
| `docs/decisions.md` | 262 | 2 |
| `docs/fuentes.md`, `formato-pack.md`, `contratos-cruzados.md`, `bateria.md`, `README.md` | 318 | 2 |
| test sources (`app` 335, `dict-core` 232, `dict-data` 111) | 678 | 3, tail |
| the three `build.gradle.kts` | 256 | 3, tail |
| | **3,850** | |

⚠️ **The generated tables were reached through their generators, never by hand.** 34 of
`dict-core`'s lines lived in `UnicodeRepertoire.kt` and `CaseFolding.kt`, which are pinned to
Unicode 13.0.0 and listed in `CLAUDE.md` §*Files that are never edited by hand*. The validity
criterion was written **before** reading any result: run both generators against the unedited tree
and require an **empty diff**. It was empty — this machine's Python 3.9.6 ships exactly Unicode
13.0.0 — so every line the second run changed is provably prose. Had that first diff been
non-empty, the translation would have silently re-pinned the tables and invalidated every
`sense_code` already written. D-254.

⚠️ **Translating a marker broke the check that reads it.** `check_mirror_declarations` looked
for the Spanish `ESTE ARCHIVO TIENE UN ESPEJO`; rewriting those eight declarations as `THIS FILE
HAS A MIRROR` took its match count to zero. It failed **loudly**, through its own `found == 0`
guard — which exists precisely so a check cannot pass by seeing nothing. **That guard is the whole
lesson**: a check whose subject is a string of prose is one translation away from being vacuous,
and the only thing separating "it is vacuous" from "it is green" is a guard somebody wrote on
purpose. D-252.

⚠️ **The budget check bit its own author for the third time**, and the first time over
something that is not prose: a **file path**, `preguntas-del-reloj.md`, is Spanish and deliberately
so, and a per-line detector cannot tell a path from a sentence. The fix is D-250's and does not
change: rewrite so the English sits on the same line, **never raise the ceiling**.

**What is left is prose, not code, and it splits by document rather than by module.** The order
stands — `docs/decisions.md` first (the most-consulted), the smaller documents next, the tests and
build files with whatever module session touches them, and `docs/roadmap.md` **last**, because it
is the one that changes most and translating it twice is the likely outcome of doing it first.

### Documentation drifts behind the code and only the closing sweep catches it · i-a2f271-6aab76

**Status.** **Planned**, with the arithmetic done and the improvement proposed, not executed.

**Nine stale claims in one day, across three sessions, and the gate was green for every one of
them.** None was found by a test; all three times it was a sweep at the end of the session —
reading the documents that own what had just been touched.

| Session | What it over-claimed | Found by |
|---|---|---|
| `s-a2f271-713ed7` | **4**: `CLAUDE.md` and `app/CLAUDE.md` both said downloads wait for a charger after D-263 withdrew that half; the roadmap's installer table said the same; and `app/CLAUDE.md` still called *"the right degradation"* a screen D-264 had stopped being a dead end | reading the documents |
| `s-a2f271-0da358` | **3**: three roadmap entries described built work as missing, one of them the coverage probe, which came one `cat` away from being built a second time | reading the documents |
| `s-a2f271-4612e2` | **2**: `docs/formato-pack.md`, **which owns the format**, had no section for the new tag; `tools/CLAUDE.md` counted three mirrors where there were four | reading the documents |

⚠️ **Why no check catches it, and that is already measured.** §*A check whose subject is prose goes
vacuous* records four cases of prose checks going vacuous. What goes stale here is not a path or a
count — `check_doc_paths` and `check_test_counts` already cover those — but an **assertion about
behaviour**, and no regex separates a stale one from a correct one.

**The improvement, proposed and not executed.** Not a semantic check but a mechanical helper for
the sweep that is already done by hand: given the session's diff, **list which durable documents
name the files it touched**. It is a grep of the diff's paths against `docs/`, `CLAUDE.md` and the
`*/CLAUDE.md` files, and it turns *"did I miss a document?"* — answered from memory today — into a
list. It does not decide whether the text went stale; that is still reading it. It removes the step
that actually fails, which is **not opening the document**.

⚠️ **And what it does NOT solve**: a document describing something without naming the file.
`CLAUDE.md` said *"downloads are deferred to charging"* without citing `DownloadPackWorker.kt`, so
that grep would have missed it. It works against forgetting, not against prose that speaks without
citing.

### Los conteos de tests en los documentos se rompen en cada commit

**Estado.** ✅ **RESUELTO el 2026-09-21 con `--fix`.** `python3 tools/audit_dictionary.py --fix`
reescribe los conteos que encuentra mal y los reporta como aviso; sin el flag falla igual que
siempre. Verificado en las dos direcciones, rompiendo un número a propósito.

⚠️ **Sólo corrige el NÚMERO, y esa frontera es la decisión.** Que un documento haya *dejado de
afirmar* un conteo sigue siendo falla humana: ahí la frase se reescribió o el dato se borró, y
arreglarlo automáticamente inventaría una frase o **borraría una vigilancia sin que nadie se
entere**.

**La fricción que lo justificaba.**

`check_doc_paths` y el chequeo de conteos son útiles —atrapan documentación que miente— pero en
una sola sesión el de conteos **falló cinco veces**, siempre por lo mismo: agregar tests mueve
cuatro o cinco números repartidos en `README.md`, `app/CLAUDE.md`, `tools/CLAUDE.md` y
`docs/roadmap.md`, y ninguno se puede deducir sin correr la suite.

**El costo real medido en esta sesión**: cinco interrupciones del gate, y **dos commits que
salieron con el gate en rojo** porque el `&&` protege del build roto pero no de no leer la salida.

**La mejora, propuesta y no ejecutada** (D-046 y la regla de procesos): la auditoría ya imprime
`<archivo> dice N donde hay M ('<regex>')`, o sea **tiene todo lo necesario para arreglarse
sola**. Un `--fix` que reescriba sólo el grupo numérico del regex que ella misma reporta cierra la
fricción sin tocar los chequeos. Se hizo a mano en esta sesión, leyendo esa salida, y funcionó a
la primera sobre los cinco.

⚠️ **Lo que NO hay que hacer es relajar el chequeo**: la documentación que miente sobre cuántos
tests hay es exactamente lo que este chequeo existe para impedir.

### Verificar a ojo en el emulador cuesta más que el cambio que se verifica — **mostly closed by D-232**

⚠️ **This entry never mentioned D-232, and D-232 is what closes most of it.** Since 2026-09-23 a
query is seeded over `adb` on any build that is not `release`, which is exactly the complaint
below: `input text` leaves the text as the Wear IME's composing text and the app receives an empty
query. `docs/preguntas-del-reloj.md` §*What can already be asked without a finger* is the current
procedure. **What is still true** is everything that is not typing — navigating to a screen,
scrolling, a long press — and that is what this entry is now about.

**Qué pasaba, y en parte sigue pasando.** No hay forma fiable de llevar la app a un estado concreto sin un humano
tocando la pantalla. `adb shell input swipe` se sale de la app, `input tap` con coordenadas
calculadas de una captura cae en el botón de al lado, `input keyevent 4` cierra la app si el
teclado no llegó a abrirse, y **`input text` deja el texto como composing del IME de Wear sin
confirmarlo al campo**, así que la app recibe la query vacía y parece rota cuando no lo está.

**Costo.** La sesión del 2026-09-18 perdió ~8 intentos —cada uno con install, force-stop, launch,
sleep y captura— para terminar sin ver la pantalla que quería ver, y encima estuvo a punto de
diagnosticar como bug de la app lo que era del método. Contra eso, los tests instrumentados
corren solos y son deterministas.

**El arreglo.** Un test instrumentado que **guarde capturas** de los estados que interesan
(`SemanticsNodeInteraction.captureToImage()` ya existe en el harness que se usa) en vez de
manejar el emulador desde afuera. Lleva la pantalla al estado por composición, no por gestos, y
deja el PNG donde se pueda mirar. No necesita dependencias nuevas.

**Visto en.** 2026-09-17 (capturas del emulador, `keyevent 4` cerraba la app), 2026-09-17 otra vez
(*"ya había pasado la sesión anterior y volvió a pasar"*), 2026-09-18 (swipe, taps y `input
text`) y **2026-09-20** (mirar la línea `rel.` de D-132 en pantalla: cuatro intentos, ninguno
llegó a la entrada). El del 2026-09-18 fue el primero donde el costo no fue tiempo sino casi un
diagnóstico equivocado.

⚠️ **Y el cuarto golpe cierra la lista de atajos, que es lo único nuevo que aporta.** El IME de
Wear abre en **modo extract a pantalla completa**: el texto vive en el campo del teclado, no en el
de la app. Probados y fallidos los cuatro: `input text` + la lupa del IME, `input text` +
`keyevent 66`, `keyevent 111` (ESC) para cerrar el teclado, y **`input keyboard text`**, que
debería entrar como teclado físico y saltearse el IME — tampoco. En las cuatro el campo vuelve a
mostrar el placeholder. **No queda workaround por probar desde afuera**, así que el arreglo de
arriba —llevar la pantalla al estado por composición y guardar el PNG— deja de ser la opción
cómoda y pasa a ser la única.

Lo que sí se pudo mirar esa sesión, y sirve de referencia de hasta dónde llega el método: que la
app arranca con los dos packs nuevos instalados, que el espacio bajo el reloj queda limpio, y
—primera confirmación visual de D-127— que **un emulador en inglés muestra la UI en inglés**
(*type…*, *Say a word*, *Word of the day*).

### ✅ No hay forma repetible de preguntarle al pack si su CONTENIDO es bueno — **BUILT 2026-09-22, and this entry did not say so**

✅ **It is built, it runs, and it bites — measured 2026-09-24 against the real packs.**
`vectors/cobertura-es.txt` (123 words) and `cobertura-en.txt` (120) exist, and
`verify_pack.py._verify_vocabulary` consults them. Read off `dist/`:

| pack | required | result |
|---|---|---|
| `es-full`, `es-core` | 123 | ✅ all present |
| `en-full` | 120 | ✅ all present |
| `en-core` | **110** | ✅ — the other 10 carry `#! solo el pack completo`, so a frequency cut is not asked for a word with no frequency |
| `es-en` | 123 (es) | ✅ as a dictionary **of** Spanish; its English side reports `tuesday` and `workaround` as a note, not a failure (D-196, and §*La traducción glosada no produce entrada inversa*) |

**Proved by mutation**: an impossible word added to the Spanish list makes `es-full` report
`FALLA … (faltan 1: …)` and exit **1**. That had never been verified — the check had only ever
been seen green.

⚠️ **Why this entry is worth keeping rather than deleting.** It described the fix as *the*
*arreglo* —a `cobertura-*.txt` plus a mode in `verify_pack.py`— in the future tense, while both
had existed since **2026-09-22**. A session reading it starts building what is already there; this
one nearly did, and stopped only because it opened the file before writing. **The state goes in
the same change as the work.**

**Qué pasaba antes.** `verify_pack.py` comprobaba **invariantes**: que los índices existan, que
`fts_def.rowid == entry.id`, que `norm` coincida. El `pack-workflow` skill ya advierte que eso
*"no dice que el contenido sea bueno"* y manda a leer entradas a mano. Pero leer a mano no es
repetible: cada sesión escribe su propia consulta, mira lo que se le ocurre mirar, y lo que no
se le ocurrió no aparece.

**Costo, con la aritmética de esta sesión.** La sonda de vocabulario —una lista de palabras y un
`SELECT` por cada una— encontró **dos cosas que el gate no puede ver**:

1. que el 39,6 % de las entradas del pack español no definía nada, con `verify_pack.py` en verde
   y 119 tests pasando;
2. que la poda por `pos = name` **borraba 6 de los 12 meses en inglés**. Eso estuvo a punto de
   quedar commiteado: el gate daba verde, `verify_pack.py` daba verde, y los tests también,
   porque ninguno de los tres sabe qué palabras *debería* tener un diccionario.

⚠️ **Los dos golpes son de la misma sesión, no de dos**, así que por la regla del segundo golpe
esto entra acá **a préstamo**: si la próxima sesión que toque un pack no la necesita, se baja.
Lo que lo justifica igual es el tipo de falla — no costó tiempo, casi costó un defecto en el
producto, que es el mismo salto que hizo subir la fricción del emulador.

**El arreglo.** Un `vectors/cobertura-es.txt` y `vectors/cobertura-en.txt` con listas de palabras
que **tienen** que estar —vocabulario común, chilenismos, meses, días, países, términos
técnicos— y un modo de `verify_pack.py` que las consulte y reporte las faltantes. Es el mismo
patrón que `normalization-vectors.tsv`: el archivo de expectativas es el mecanismo, y el
valor está en que **el que agrega una palabra a la lista documenta una decisión de producto**.
No necesita dependencias nuevas y reusa el `--sample` que ya existe para correr barato.

**Lo que NO resuelve, y hay que decirlo**: una lista escrita a mano tiene el mismo sesgo que
mirar a mano. No sabe lo que nadie pensó en poner. Sirve contra regresiones, no contra huecos
desconocidos.

**Visto en.** 2026-09-19, dos veces en la misma sesión (el 39,6 % de entradas vacías; los meses
en inglés). **Primer golpe de changelog.**

### ✅ La auditoría dice que `CLAUDE.md` se pasó, y ahora también qué sección creció — CERRADO 2026-09-24

✅ **Closed by d-a2f271-c8d129.** The message now names the sections — `crecieron: Commits +15
(24 lineas)` — diffed against the last commit, falling back to the longest ones when there is no
`.git`. The advisory carries it as well as the failure, because by the time the budget is blown
the choice is already forced. Verified by mutation on all three branches.

**Qué pasaba antes.** `check_root_budget` fallaba con *"¿Qué sección creció?"* y no lo respondía. Hay
que contar líneas por sección a mano, elegir qué relocalizar y volver a correr. Pasó **dos veces
en la sesión del 2026-09-17** (204 → 201 → 199 líneas), y el archivo quedó en 199 de 200: la
próxima regla que se agregue vuelve a chocar.

**Costo.** ~3 minutos por golpe × cada sesión que agrega una regla al archivo raíz × la vida del
repo. El aviso de "cerca del límite" (>175) ya existe, así que el mecanismo está: lo que falta es
que diga **cuál** sección.

**El arreglo.** Que el check imprima el conteo por `##` cuando falla o avisa. Es una función
corta dentro de `check_root_budget`, sin dependencias nuevas.

**Visto en.** 2026-09-17 (bootstrap, el archivo nació en 158), 2026-09-17 (update a método v7,
dos relocalizaciones seguidas) y 2026-09-17 (el pack real: **`build_es.py` no se pudo agregar a
§Comandos** y terminó solo en el `pack-workflow` skill). El tercer golpe es el primero donde el
costo no es tiempo sino documentación que no se escribió: el archivo raíz ya no puede nombrar un
comando nuevo.

**Cuarto golpe, 2026-09-17**: `tools/devpack.py` —el comando con el que entra un diccionario al
reloj— tampoco pudo entrar a §Comandos, y vive sólo en `tools/CLAUDE.md` y `app/CLAUDE.md`. Van
**dos comandos seguidos** que el archivo raíz no puede nombrar: ya no es un costo de minutos, es
que §Comandos dejó de ser la lista de comandos del repo.

---

## Cerrado por medición

Retirado con el número, para que siga retirado.

| Idea | El número que la cerró |
|---|---|
| zstd para el payload | Costaba una librería nativa además de la de SQLite, por unos puntos de compresión (D-037) |
| Canario comprimido para el diccionario | Un diccionario **truncado seguía validando**: se derivaba de sí mismo (D-036) |
| Play Asset Delivery | Sin soporte documentado en Wear OS (D-038) |
| Room para leer packs | `createFromFile()` copia el archivo: decenas de MB duplicados (D-039) |
| OkHttp para descargas | `HttpURLConnection` hace `Range` y progreso con cero bytes extra (D-040) |
| KMP | Wear Compose es solo Android: la UI no se comparte con ningún segundo destino (D-018) |
| **PanLex** como fuente de traducciones | **CC BY-NC-SA 4.0**, no CC0 como la resume el buscador: `NonCommercial` la bloquea para un pack que se distribuye. Leída en la fuente primaria (2026-09-21) |
| **FreeDict `eng-spa`** (64.258 lemas EN→ES) | **GPL**, viral sobre el dato y en conflicto con el CC BY-SA del pack. Duele: es justo la dirección débil. Apertium, igual |
| **DBnary** para el par ES↔EN | **Pierde contra su propia fuente**: 30.723 pares y sólo **9.169 (29,8 %) ligados a acepción**, contra 34.710 / 18.817 leyendo `es.jsonl` directo. Su valor son las otras 25 ediciones |
| **WordNet como puente de traducción** | Sólo **435 de 78.417** synsets españoles (0,6 %) existen en OEWN 2024, y **342 de esos 435 son colisiones**: `soñador ↔ diner`, `hedonista ↔ groundskeeper`. Cuatro de cada cinco mal y ninguno se ve mal |
| **Wikidata `P5137`** como puente | Real y CC0 sobre el 30,3 % de las acepciones españolas, pero **las etiquetas viven en el dump de ítems de +100 GB**, no en el de lexemas que ya tenemos |
| **Un pack autorado EN→ES** | Sobre los 3,2 GB del dump inglés completo: **10.438 pares**, contra las 206.727 filas ya derivadas de las glosas. Veinte veces más chico que lo que vendría a reemplazar |
| **Plegar más que minúsculas/espacios/puntuación** en el código de acepción | Cada carácter extra que se pliega funde acepciones distintas en silencio. El plegado ligero ya da 42,21 % entre diccionarios, y el 57,79 % restante **no lo arregla ningún hash**: pide declarar equivalencias (D-181) |

---

## Qué le cuesta cada idea al invariante central

> *"`norm(x)` del builder == `norm(x)` de la app, en toda plataforma y para siempre."*

| Idea | ¿Lo rompe? |
|---|---|
| Pack real de español | **No.** Lo somete a volumen real por primera vez, que es distinto |
| Composición entre packs | **No**, si el join key es `(norm, pos)` o un hash de `norm`: los dos se apoyan en `norm`, así que *dependen* del invariante en lugar de amenazarlo |
| `trans` opcional | **No.** Es una tabla, no una clave |
| `:dict-data` | **No**, pero es el primer lugar donde el invariante se ejerce de verdad: hasta ahora solo lo probaron los tests |
| Diseño de la interfaz | **No** |
| Instalador de packs | **No**, pero es quien debe rechazar un pack con `NORM_VERSION` distinta. Sin eso, el invariante se viola en silencio (D-006) |
| Vectores en un reloj | **Al revés:** es lo único que lo verifica donde importa. Hoy el invariante está probado en escritorio y **asumido** en el reloj |
| Subir la versión de Unicode | **Sí.** Cambia el repertorio y por lo tanto `norm()`. Exige subir `NORM_VERSION` y reconstruir todos los packs |
| `detail=none` en FTS | **No.** Toca la búsqueda de texto libre, no las claves |
