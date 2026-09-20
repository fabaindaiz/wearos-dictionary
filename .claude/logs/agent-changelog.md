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
**Qué salió mal.** Qué erró el primer intento y qué lo agarró. Se omite solo si no erró nada.
**Qué quedó sin hacer.** La deuda que este cambio creó o esquivó, nombrada.
```

**Los tres últimos campos son los que pagan el archivo.** Un log de éxitos es contabilidad; uno
que dice *"el primer intento dejó `CLAUDE.md` en 204 líneas y lo agarró el propio check"* es el
único mecanismo por el que una sesión le avisa a otra. Los errores se escriben con la misma voz
que los aciertos: una entrada que esconde un desvío manda a la sesión siguiente por ese desvío.

---

## 2026-09-20 — Un solo diccionario por idioma, y el release cotizado

**Qué.** D-145: Wikidata se funde en el pack español en vez de ser un pack aparte. D-146: el APK
de release lleva sólo las ABIs de reloj. Y el checklist completo de lo que falta para publicar.

**Áreas.** `build_pack.py` (`--sumar` y el recálculo de `sense_key`) · `tests/test_segunda_fuente`
· `app/build.gradle.kts` · `docs/roadmap.md` (§Publicar, nuevo) · `docs/decisions.md`.

**Por qué.** Reportado desde el reloj: con dos diccionarios de español el inicio mostraba **dos
chips «ES» y dos palabras del día**.

**Medido.**

- **6.092 lemas exclusivos** de Wikidata deduplicando por lema exacto (23 más que por `norm`, y
  son legítimos: `Dr.`, `km²`, `c/`). Pack final: **152.281 entradas, 75,2 MB**.
- Inicio verificado en el emulador: **un «ES», un «EN», dos palabras del día** —una por idioma—.
- APK de release: **35 → 33 MB** al sacar `x86` y `x86_64`. ⚠️ De los 33, **32,9 son dex**: R8
  apagado sigue siendo la palanca grande.

**Arquitectura.** ✅ Cumple. La fusión es una **unión de filas**, así que no necesita composición:
los lemas compartidos se quedan con la definición de la fuente base y no hay arbitraje.

**Qué salió mal.**

- **La mitad del bug reportado es mía.** D-136 dejó escrito que *el selector pasa a elegir un
  idioma, no un archivo* y **no lo llevé a la pantalla**. Fundir Wikidata lo tapa; la incoherencia
  sigue ahí para el día que convivan dos diccionarios de un idioma de verdad.
- **`verify_pack.py` agarró un fallo que sólo existe al fusionar.** Cada fuente decide si una
  entrada necesita `sense_key` mirando **sus** homógrafos; al fusionar, un lexema que tenía gemelo
  en Wikidata puede perderlo y quedarse con una clave que ya no corresponde. Eso rompe `uid`, que
  es la llave del join entre packs. Es el mismo error que D-139, entrando por otra puerta — la
  segunda vez que la convención de `sense_key` muerde.
- Dejé un emulador de API 33 encendido y el siguiente `adb` falló con *more than one device*.

**Qué quedó sin hacer.**

- **La keystore es del humano** y sin ella el APK sale sin firmar. Está el procedimiento escrito.
- **Los instrumentados en API 33 no se corrieron** (el AVD existe). El propósito de esos tests es
  que el ICU difiere entre versiones, así que correr uno solo no prueba lo que intentan probar.
- **R8 sigue apagado** y es el 99 % del APK.
- **La incoherencia del selector** (lista packs, decide idiomas) queda para cuando haga falta.

## 2026-09-20 — Se midió cómo dividir los packs, y no se construyó nada

**Qué.** Diseño y mediciones para dividir los packs grandes en vez de achicarlos, tres decisiones
de producto tomadas por el usuario, y el ítem del margen lateral cerrado. **Sin código**: el
pedido fue guardarlo en el roadmap.

**Áreas.** `docs/roadmap.md` solamente.

**Por qué.** *«Que los packs muy grandes, en lugar de reducirse, se pueda evaluar dividirlos
funcionalmente»*, más cómo conviven varios packs del mismo idioma en la interfaz.

**Medido.** Todo lo que sostiene el diseño, y sin esto no había conversación posible:

- **Dónde está el peso**: español `form` 45 %, inglés `entry` 46 %, y **FTS + índices son el 27 %
  y el 46 %** respectivamente.
- **Dentro del payload**: glosas 70,6 % / ejemplos 17,0 % / **tesauro 5,9 %** en español; glosas
  49,5 % / **ejemplos 41,2 %** / tesauro 3,0 % en inglés. ⚠️ **Un «módulo de tesauro» ahorraría
  ~1 MB**: la idea intuitiva era la peor de la lista.
- **La señal de frecuencia que faltaba existe y ya estaba descargada**: contar palabras en las
  442.135 oraciones de Tatoeba. Se comparó contra contar palabras en las glosas del propio
  diccionario, que resultó sesgada —su top trae *apellido*, *gerundio*, *participio*—.
- **La curva del pack núcleo**: top 20.000 palabras → 14.388 entradas → **7,5 MB contra 73,6**.

**Arquitectura.** ✅ Cumple. La distinción que ordenó todo el diseño: **dividir por FILAS funciona
hoy** (un pack núcleo es un diccionario completo y autosuficiente que convive vía D-136);
**dividir por CAMPOS necesita composición**, que sigue bloqueada por la granularidad de `uid`.

**Qué salió mal.** Empecé a construir el pack núcleo —`tatoeba.frequencies()` y dos suites de
tests— antes de que el usuario dijera que sólo quería el diseño. Se revirtió el código y se
conservó sólo el roadmap; el árbol quedó verde. **La lección es de proceso**: el pedido decía
*«estas decisiones consúltamelas antes de tomarlas»*, y yo pregunté las decisiones pero asumí que
la respuesta autorizaba a implementar.

**Qué quedó sin hacer.** Todo, a propósito. Y dos cosas sin decidir que quedan anotadas: el `N`
del núcleo —20.000 es donde la curva se aplana, no una medición de qué necesita un usuario— y el
corpus inglés de Tatoeba, que no está descargado.

⚠️ **Una consecuencia de la decisión «un módulo sólo enriquece» que conviene no redescubrir**: lo
que tiene que ser **buscable** no puede salir del pack base. Los sinónimos entran a `fts_def`
(D-118) y los ejemplos también, así que moverlos a un módulo rompería las dos búsquedas. Las
relacionadas sí podrían: nunca entraron al índice (D-132).

## 2026-09-20 — El tesauro deja de depender de que alguien se acordara

**Qué.** D-144: sinónimos y antónimos desde WordNet, en los dos idiomas.

**Áreas.** `sources/wordnet.py` nuevo con sus tests · `build.py` (el merge y los dos filtros) ·
`build_pack.py` (`--tesauro` y dos fuentes más en el catálogo) · `tests/test_tesauro.py` nuevo ·
`docs/decisions.md`, `docs/fuentes.md`, `docs/roadmap.md`.

**Por qué.** Los sinónimos del wiki se escriben a mano, así que la cobertura depende de quién
editó qué: 18,4 % en español, 15,4 % en inglés. WordNet está construido al revés — un *synset*
**es** un conjunto de sinónimos.

**Medido.**

- **Español: +3.801** entradas con sinónimos (26.829 → 30.630). **Inglés: +30.423** con sinónimos
  y **+2.376** con antónimos.
- Fuentes: **Open English WordNet 2024**, CC BY 4.0, 120.630 synsets y 7.996 relaciones de
  antonimia · **MCR vía OMW**, CC BY 3.0, 78.417 synsets con lemas españoles.
- El filtro de variantes morfológicas saca **6.318 de 99.292** candidatos (6,4 %).
- Tamaños: español **73,6 MB**, inglés **315,5 MB**. Los dos pasan `verify_pack.py`.

**Arquitectura.** ✅ Cumple. El merge vive en `finish()` porque el filtro de flexiones necesita la
tabla `form` completa, igual que las frases de D-137.

**Qué salió mal.**

- **Los dos filtros aparecieron leyendo el pack, no testeando.** Primero `coreano → coreana ·
  coreanos · coreanas` (flexiones dentro del synset); después `decolorarse → decolorar`,
  `organismos → organismo`, `básicamente → basicamente`. El MCR se construyó automáticamente y eso
  se nota; el inglés no tiene ese problema.
- **Sospeché un bug que no existía.** `domingo → pollerudo · mandarina · calzonazos` me pareció
  ruido de WordNet; al verificar, `domingo` **sí** estaba correctamente excluido (está en dos
  synsets) y esos sinónimos venían del wiki. La comprobación costó cinco minutos y evitó
  "arreglar" algo que funcionaba.
- El primer intento de falsificar el filtro de flexiones falló por cómo parcheé el módulo
  (`KeyError: 'build'`); el segundo, con `dict(build.__dict__)`, funcionó y el test falló con
  `['coreana', 'coreanos', 'surcoreano']`, que es exactamente el ruido.

**Dos cosas más que sólo aparecieron mirando la pantalla.**

- **El separador de listas salía pegado**: `marine·freshwater·limnic`. Android **recorta los
  espacios de un `<string>`** salvo que el valor esté entre comillas dobles. Invisible en el XML
  —se ve bien— y ningún test lo veía porque todos afirmaban un solo término. Arreglado, con un
  test que lo fija y que se comprobó que falla sin las comillas.
- **El pack viejo no se reemplaza: se queda al lado.** Buscar *aquatic* devolvía la entrada del
  pack anterior, **sin los antónimos de WordNet**, porque `--tesauro` sufija el `pack_id` (D-138)
  y los dos conviven. Es la convivencia funcionando como se diseñó y un problema de producto al
  mismo tiempo; escrito en el roadmap junto a `data_version`.

**Qué quedó sin hacer.**

- **El ruido que queda no tiene filtro estructural**: algún synset mal mapeado del MCR (`uno` con
  `dos`) y alguna palabra inglesa colada (`meadero → jakes`).
- **La antonimia en español sigue viniendo sólo del wiki** (2,3 %). No se puede transferir del
  inglés por el synset: es una relación entre acepciones, no entre synsets.
- **O-3 empeoró**: 73,6 y 315,5 MB contra un presupuesto de 50. La tensión entre *«completas»* y
  el tamaño es una decisión de producto y está escrita en el roadmap.

## 2026-09-20 — Tres arreglos de interfaz, verificados en el emulador

**Qué.** D-143: la lupa vuelve a una barra **vacía**; atrás con texto vuelve al inicio en vez de
salir de la app; y cada resultado dice **de qué idioma viene** (`perron · noun · EN`).

**Áreas.** `SearchViewModel.kt` (`clearQuery`), `MainActivity.kt` (el `BackHandler` y la lupa),
`SearchScreen.kt` (`ResultRow` con el mapa de idiomas), `SearchViewModelTest`, `ScreensTest`.

**Por qué.** Tres pedidos, y los tres cierran huecos que la convivencia de packs (D-136) abrió o
agrandó. El del idioma se pagó solo: al probarlo, la etiqueta **delató que el pack activo era el
inglés**, que era exactamente la ambigüedad que venía a resolver.

**Medido.** Verificado a mano en el emulador, los tres: la lupa deja `type…`; el primer atrás
limpia y **el segundo sí sale** (la app no queda atrapada); los resultados muestran `noun · EN`.
Gate: **21 checks**, 77 `:dict-core`, 205 JVM de `:app`, 221 Python. Instrumentados: 34, 0 fallas.

**Qué salió mal.** Nada que rehacer, pero **por fin se pudo escribir en el emulador**: el truco
que faltaba era **tocar el candidato del IME antes que la lupa**, que es lo que confirma el texto
al campo. Los cuatro intentos anteriores (`input text` + lupa, + `keyevent 66`, ESC, `input
keyboard text`) fallaban porque el IME de Wear abre en modo extract y el texto vive en SU campo.
Eso desbloquea el ítem de proceso que llevaba cinco golpes.

**Qué quedó sin hacer.** **La condición del `BackHandler` no está en el gate**: `clearQuery` sí,
pero `createComposeRule()` no trae Activity y sin Activity no hay despachador de atrás. Es
cableado de dos líneas y se verificó a mano; si crece, hay que pasar a `createAndroidComposeRule`.

## 2026-09-20 — Ningún pack decide el orden, y ninguno pierde palabras

**Qué.** Dos cambios de política y una defensa nueva, a partir de una pregunta: *«si hay un pack
de la comunidad que no hace una buena normalización podría matar la lógica de ordenado»*.

1. **D-141** — ninguna fuente pierde palabras por defecto. La poda se pide por nombre.
2. **D-142** — el orden deja de confiar en la calibración de ningún pack, y las claves se
   recalculan sobre una muestra al abrir.

**Áreas.** `sources/kaikki.py`, `sources/wikidata.py`, `build_pack.py` y sus tests ·
`SearchRepository.kt` y su test · `PackFile.kt` y el `PackKeySampleTest` nuevo (instrumentado) ·
`PlatformAssumptionsTest.kt` · `compare_calibration.py` nuevo, con tests · `docs/decisions.md`,
`docs/fuentes.md`, `docs/roadmap.md`.

**Por qué.** La preocupación son **dos fallas distintas** y las estaba mezclando: normalización
mala hace **desaparecer palabras**; calibración mala **envenena el orden**. La primera es mucho
peor y no estaba cubierta del lado de la app.

**Medido.**

- **`score` no era el `rank`**, es la posición dentro de la lista de su propio pack. O sea que la
  mezcla ya era **ordinal** e inmune a que un pack calibre en otra escala. D-136 lo decía peor de
  lo que era; corregido.
- **La banda de cobertura, sobre los dos packs reales**: *cas* devolvía `castigar, castreño,
  cascar` — **sin `casa`** — y ahora devuelve `casa, casar, cascar`. *per*: de `percibir, perder`
  a `perro, persa`. *arb*: de `árbitro` a `árbol`.
- **Acuerdo entre packs: medido y rechazado.** Mejoraba *cas*, empeoraba *tomat*, y hacía que el
  orden dependiera de qué otros packs estuvieran instalados.
- **Compatibilidad de calibraciones**: entre `es-def-wikc` y `es-def-wd`, **ρ de Spearman =
  +0,388** sobre 8.595 entradas comunes, **control barajado +0,001**, y comparten **110 de las
  200 más comunes**.
- **No filtrar cuesta +7,6 %**: 114.619 → 146.193 entradas, 68,3 → 73,5 MB.
- Instrumentados en emulador: **0 fallas**, incluidos los 3 nuevos.

**Arquitectura.** ✅ Cumple. La banda de cobertura vive en `:dict-core` y se calcula del texto
escrito y del lema; la muestra de claves vive en `:dict-data`, que es el módulo al que le
corresponde tocar SQLite.

**Qué salió mal.**

- **Medí sobre un pack que se estaba reconstruyendo en background** y saqué conclusiones de
  resultados a medio escribir: «llov» y «guan» devolvían vacío. Lo noté porque el vacío era
  absurdo, no porque el número se viera mal. Repetí la medición con el build terminado.
- **Un test instrumentado viejo afirmaba el `pack_id` anterior** al cambio de gramática de D-138.
  El gate no lo ve: los instrumentados no corren ahí. Sólo apareció al correrlos a mano.
- Al escribir la banda pensé primero en el ratio crudo y la muestra lo desmintió: *iqui* ponía
  `iquide` —corta y oscura— delante de `iquiteño`. Bandas gruesas lo arreglan porque dentro de
  una banda vuelve a mandar el pack.

**Qué quedó sin hacer.**

- **La muestra de 64 claves acota el daño, no lo elimina.** Un pack que difiera en un solo
  carácter raro pasa. El que lo elimina es `verify_pack.py`, que recalcula todas las filas y
  corre al construir — un pack ajeno nunca pasó por ahí.
- **Falta una señal de frecuencia real.** La banda de cobertura tapa el síntoma más visible de
  D-067, pero `rank` sigue siendo riqueza de página y no frecuencia de uso.
- **El umbral de ρ no existe**: la herramienta informa, no decide. Con una sola pareja medida,
  inventar un corte sería un número sin medición detrás.

## 2026-09-20 — Tres fuentes nuevas, un manifiesto, y dos bugs que sólo aparecieron mirando

**Qué.** El pack español pasó de una fuente a tres, apareció un segundo diccionario de español
completo, y los packs pasaron a **declarar por escrito qué son y bajo qué licencia**.

1. **D-134** — tres políticas de nombres propios, y el que entra **pierde prioridad** en vez de
   desaparecer. Con la comparación de tamaño que se pidió.
2. **D-135** — segunda fuente (enwiktionary §Spanish) construida, medida y **dejada tras una
   opción**: 307 entradas.
3. **D-136** — `SearchRepository`: varios packs del mismo idioma se consultan juntos.
4. **D-137** — tercera fuente, Tatoeba: **6.499 entradas** ganan una frase de uso.
5. **D-138** — `meta.sources`: **una licencia por fuente**, y `pack_id` pasa a ser un código con
   gramática verificada.
6. **D-139** — `es-def-wd`: segundo pack base de español desde Wikidata Lexemes, **CC0**.
7. **D-140** — un texto traducido escrito a mano en el código pasa a ser un fallo del gate.

**Áreas.** Fuentes nuevas en `tools/packbuilder/sources/`: `tatoeba.py`, `wikidata.py`,
`enwikt_examples.py`. Tocados `build.py`, `build_pack.py`, `verify_pack.py`, `sources/kaikki.py` y
sus tests. En el núcleo, `SearchRepository.kt` y `PackSource.kt` nuevos, más `Model.kt`. En la app,
`SearchViewModel.kt`, `AttributionScreen.kt`, `SearchScreen.kt`, `EntryScreen.kt`,
`SettingsScreen.kt` y las dos tablas de strings. En herramientas, `audit_dictionary.py`. Documentos:
`docs/fuentes.md` nuevo, más `formato-pack.md`, `decisions.md`, `roadmap.md` y `CLAUDE.md`.

**Por qué.** Tres pedidos encadenados: aplicar los ítems 1 y 3 del roadmap; buscar otra fuente para
el español; y que las atribuciones viajen **en** los packs y se muestren desde ahí.

**Medido.**

- **Por qué el pack inglés es 4× más grande, que era la pregunta**: no está más cargado por
  entrada — tiene **6,9× más entradas**. Por entrada el **español es más denso**: 596 bytes contra
  343, 1,49 acepciones contra 1,29, y **12,98 formas flexionadas contra 1,14**, que es lo que hace
  que `form` se lleve 33 de sus 68 MB. El inglés gana en una sola cosa: ejemplos, 33,3 % contra
  11,4 %.
- **Nombres propios, la comparación que se pidió**: `lexical-only` 114.619 entradas / 68,3 MB ·
  `definitions-only` 117.648 / 69,1 MB (+1,2 %) · `included` 146.193 / 73,3 MB (+7,3 %). La del
  medio es la barata porque **28.314 de las 31.549 podadas sólo dicen su categoría**.
- **Ejemplos**: enwiktionary 307 (+8 KB) contra Tatoeba **6.499** (+368 KB). 21×.
- **Wikidata**: 15.269 entradas, 4,4 MB, **5.092 (33,3 %) exclusivas**, y **8.595 `uid` que unen**
  con el otro pack de español.
- Gate: **21 checks**, 0 fallas.

**Arquitectura.** ✅ Cumple. `SearchRepository` vive en `:dict-core` sin dependencias de producción
(fan-out secuencial) y **deliberadamente no implementa `DictionarySource`**: la mitad de esa
interfaz se direcciona por `entryId`, que es un rowid local a un pack.

**Qué salió mal.** Cuatro cosas, y **tres las encontró mirar, no un test**:

- **`nadal` recibió una frase sobre el tenista.** `norm()` baja a minúsculas y el filtro de
  ambigüedad **no podía verlo**: D-116 poda los nombres propios, así que no quedaba entrada con la
  que empatar — la clave parecía inequívoca *porque su competidor fue podado*. El primer arreglo
  fue una heurística de posición y **falló** con *«Nadal, mejor deportista español…»*. Lo correcto
  era un hecho del corpus, no una posición.
- **`abbacy → "more at abbot § Related terms"`.** Estas listas son las únicas del payload que la
  fuente no limpia. El gate estaba verde con la basura adentro.
- **La app mostraba «Guardadas» y «Ajustes» en español en un reloj en inglés.** Los recursos
  existían en los dos idiomas y `check_locale_parity` no podía verlo. Al escribir el chequeo que
  sí lo ve **aparecieron cinco más**.
- **El pack de Wikidata usaba el id del lexema como `sense_key` siempre.** Es una identidad mejor
  que la de kaikki, y por eso **los `uid` no unían con nada**. Lo agarró `verify_pack.py`. Con la
  convención correcta unen 8.595.

Además, dos veces escribí test e implementación en la misma pasada y tuve que **forzar el fallo
después** parchando la función; y un test de orden **pasaba por la razón equivocada** —`sortedWith`
de Kotlin ya es estable— así que la propiedad real había que escribirla de otra forma.

**Qué quedó sin hacer.**

- **La composición sigue sin construirse.** Existe la capa y existe el número (8.595 uid), falta la
  decisión de granularidad: `uid` es por entrada, un sinónimo es por acepción.
- **No se pudo escribir en el teclado del emulador** (quinto golpe del mismo ítem). Lo que sí se
  vio: la app con tres packs, el manifiesto por fuente renderizando, y el bug de idioma corregido.
- **`data_version` sigue sin resolverse** y ahora pesa más: hay cinco packs de español posibles.
- **Los packs de `--ejemplos` y `--frases` cambian el `pack_id`**, así que adoptar Tatoeba en el
  pack principal mueve el historial del reloj. Es el mismo problema de `data_version`.

## 2026-09-20 — El último campo que la fuente traía y el builder tiraba

**Qué.** Tres cosas, de un mismo pedido: *«que el código sea genérico pero se adapte de otras
formas a la resolución. Además, implementa lo que quedó pendiente… incluyendo mejorar o completar
el pack de español… recordá dar los créditos y considerar licencias»*.

1. **D-132, palabras relacionadas.** Hiperónimos, hipónimos y `related` entran al payload con el
   tag `R`, aditivo (no sube `CODEC_ID`) y fuera de `fts_def`. Dos reglas de atribución según
   cómo las sirva el dump. Se reconstruyeron los packs real de español y de inglés.
2. **D-133, el espacio bajo el reloj es una fracción de la pantalla**, no 20 dp. Segundo eje
   adaptable después de `rowsThatFit`.
3. Se filtró el **markup del wiki** que venía colado en esas listas.

**Áreas.** En el builder, `tools/packbuilder/sources/kaikki.py` con `payload.py`, `build.py`,
`build_pack.py` y `gen_payload_fixture.py`, más sus tests. En el núcleo, `Model.kt` y
`PayloadCodec.kt` con `PayloadCodecTest`. En la app, `Components.kt`, `EntryScreen.kt`,
`SearchScreen.kt`, `ScreensTest`, el `ClockGapTest` nuevo y las dos tablas de strings. En
documentos, `docs/decisions.md`, `docs/roadmap.md` y `docs/formato-pack.md`, más
`.claude/skills/verify/SKILL.md`, cuyos conteos estaban viejos.

⚠️ *(Las llaves de shell no son un path y el audit las rechaza — es la segunda vez que caigo en lo
mismo, después de `values{,-es}/strings.xml`.)*

**Por qué.** *«Mejorar o completar el pack de español»*, y con la instrucción de **no olvidar los
créditos ni las licencias**. Eso terminó decidiendo el alcance: la fuente elegida es **la misma
que ya estaba** (Wikcionario vía kaikki.org, CC BY-SA 4.0), así que `license`, `attribution` y
`source_url` **no cambian** y la atribución sigue siendo exacta. Traer una segunda fuente habría
obligado a nombrar a las dos, y eso es el ítem de los ejemplos, que sigue abierto.

**Medido.** Lo primero fue barrer qué quedaba sin usar, antes de escribir nada:

- 174.395 registros vivos del dump español. Las entradas **flacas** —una acepción, sin ejemplo—
  son 29.817. El **25,4 %** traía algo aprovechable, pero **el 20,3 % eran sinónimos que ya
  entraban** por D-117/D-124. Lo genuinamente nuevo: `related` 4,8 %, `hypernyms` 1,7 %,
  `hyponyms` 0,8 %.
- Las dos formas del dump, ~185.000 registros vivos de cada uno: **anidadas** en la acepción
  es 0,0 % / en 13,8 %; **a nivel de entrada** es 5,0 % / en 9,6 %. El mismo espejo que los
  sinónimos, y la razón de que hagan falta dos reglas.
- En los packs terminados: **5.395 entradas en español (4,7 %)** y **90.310 en inglés (11,4 %)**,
  por **+112 KB (0,17 %)** y **+1,4 MB (0,51 %)**. Más que los antónimos de D-126, que se
  aceptaron con 2,9 %.
- Markup colado: **1.072 de 267.721 items (0,40 %)** no son palabras. Tras el filtro, **0** en los
  dos packs.
- `PaddingDefaults` leído del `.aar`, no de memoria: vertical **10 %**, horizontal **5,2 %**,
  `edgePadding = 2.dp`.
- Gate: **57 `:dict-core` · 196 JVM `:app` · 161 Python · 20 checks**, verde.

**Arquitectura.** ✅ Cumple. Tag aditivo, `CODEC_ID` quieto, tags desconocidos ignorados: un pack
de usuario sin `R` sigue siendo válido y la pantalla no dibuja esa línea — que es exactamente lo
que D-130 prometió sobre la modularidad, ahora ejercitado por tercera vez.

**Qué salió mal.**

- **Leí el pack y encontré basura que los tests no podían ver.** En una muestra de seis entradas
  flacas del pack inglés salió `abbacy → abbé, more at abbot § Related terms`. Estas listas son
  las únicas del payload que la fuente **no limpia**: vienen como enlaces crudos. Lo agarró leer
  entradas de verdad, no contar filas — el gate estaba verde con la basura adentro.
- **Escribí el test del filtro junto con el filtro**, así que no lo vi fallar. Lo forcé después
  parchando `_es_markup` a `False`: falla con los tres items de más. Corregido el método, no sólo
  el resultado.
- **Una expectativa mía estaba mal, no el código**: el separador de listas es ` · `, no `, `.
- **Casi prometo en la descripción del pack algo que el inglés no tenía.** Antes de tocar
  `description` medí el dump inglés y ahí apareció que sirve las relacionadas **anidadas**, que
  era la forma que mi primera implementación ignoraba. Medir para no mentir en un metadato
  terminó **duplicando la ganancia**.
- **No pude mirar la línea `rel.` en pantalla.** Cuarto golpe del ítem de proceso: el IME de Wear
  abre en modo extract y el texto no llega al campo. Probados y fallidos `input text` + la lupa,
  `input text` + `keyevent 66`, ESC, y `input keyboard text`. **Se acabaron los workarounds desde
  afuera**, y eso está escrito en el roadmap: ahora el test instrumentado con captura es la única
  opción, no la cómoda.

**Qué quedó sin hacer.**

- **La línea `rel.` no se vio en un reloj ni en el emulador.** Está verificada leyendo los
  payloads descomprimidos y por `ScreensTest` bajo Robolectric. No es lo mismo y no se presenta
  como si lo fuera.
- **El margen lateral no se tocó, a propósito.** A diferencia del espacio bajo el reloj —donde los
  20 dp resultaron ser **exactamente** el 10 % de 192 dp, así que la fracción revelaba la
  intención en vez de cambiarla— los 16 dp no son el 5,2 % de nada. Pasarlos a la fracción achica
  el margen en los dos relojes que existen, en 12 lugares, sin poder mirarlo. Queda en el roadmap
  con la medición que necesita. *(La alternativa `max(16.dp, 5,2 %)` es código muerto: sólo
  actuaría arriba de 308 dp.)*
- **Decisión que no me corresponde, escrita en el roadmap**: los packs se reconstruyeron **sin
  cambiar el dump**, así que `data_version` —que es la fecha del dump— declara lo mismo que los
  viejos y un reloj no puede saber que hay uno mejor. Hoy no rompe nada porque los packs se
  copian a mano; es requisito del instalador. Tres caminos, ninguno obvio.
- **Los duplicados por mayúscula siguen** (`abecedary → Abecedarian · abecedarian`). Ruido, no
  error, y filtrarlos sin mirar rompería pares legítimos tipo *Polish* / *polish*. Sin medir.

## 2026-09-20 — El teclado no se cerraba por el campo, se cerraba por la lista

**Qué.** Cuatro bugs reportados desde el reloj y la app bilingüe. El teclado ya no se cierra al
escribir ni al borrar (D-128), el historial anota por los cuatro caminos y la etiqueta de guardada
recompone (D-129), la voz entra por el input **nativo del reloj**, y la interfaz es
**inglés/español por recursos** (D-127). Más el espacio bajo el reloj, rehecho.

**Áreas.** `app/src/main/res/values/strings.xml` y `app/src/main/res/values-es/strings.xml`
(nuevos), `presentation/*` entero,
`data/{PackSet,PackStore}.kt`, `tile/WordOfTheDayTileService.kt`,
`tools/audit_dictionary.py`, `docs/decisions.md` (D-127 a D-130).

**Por qué.** Reporte directo: *"escribir en el teclado hace que se cierre… también se sale cada
vez que uno borra una letra"*, *"el historial a veces no se actualiza"*, *"integrémonos más a la
búsqueda por voz nativa"*, *"quiero que la app sea multiidiomas"*.

**Arquitectura.** ✅ Cumple. D-072 se respetó empujando trabajo hacia afuera: `packTypeLabel` sale
de `PackSet.kt` y `SearchViewModel` deja de fabricar texto para emitir un estado.

**Medido.**
- **192 dp compone 2 filas de resultado; 234 dp compone 3.** La relación —22 % más pantalla, una
  fila más— es lo único comparable; los absolutos difieren del emulador porque `h192dp` es alto
  *disponible*.
- Sinónimos/antónimos: **1,05 %** y **0,06 %** del pack. Es el número que contesta si ensucian.
- El espaciador de 20 dp bajo el reloj **no** cuesta una fila: con `CLOCK_GAP = 0` el resultado es
  idéntico.

**Qué salió mal.**
- **Diagnostiqué el teclado en el lugar equivocado al principio.** La causa no es el campo de
  texto: es que **la lista se reestructura**. Con la query vacía el inicio muestra encabezado, voz,
  palabra del día e historial; con una letra todo eso desaparece. Eso destruye el campo y se lleva
  el foco. **D-089 no alcanzaba**: puso `key` para cuando un ítem cambia de *posición*, no para
  cuando cambia *qué ítems existen*.
- **El test de densidad pasaba por el motivo equivocado, y nadie lo había notado.**
  `entranTresResultadosSinScrollear` corría con el dispositivo **por defecto** de Robolectric, que
  no es un reloj: componía las cuatro sugerencias. La afirmación central de densidad del repo no
  estaba verificada.
- **El primer arreglo del espacio cortaba la forma del campo**, y el usuario lo dijo antes que yo
  lo viera: como `contentPadding` la barra bajaba pero seguía empezando dentro del transform de
  borde del `TransformingLazyColumn`. Un ítem espaciador se dibuja como cualquier otro.
- **No pude verificar el teclado por `adb`.** `input keyevent` no llega al campo, `input text` deja
  texto a medias y `mInputShown` va y viene entre comandos. Es la fricción que §Proceso ya nombra.
  Lo que **sí** se verificó en pantalla es lo que importa: con "pe" escrito, *"Decir una palabra"*
  y *"Palabra del día"* siguen ahí — **la lista no se reestructuró**.
- **Casi invento una fórmula de filas para el tile** sin poder medirla en el reloj. Se frenó: una
  claim necesita una medición, y el reloj estaba desconectado.

**Qué quedó sin hacer.** El **cableado** del bilingüe está completo, pero tres decisiones quedaron
escritas en el roadmap en vez de tomadas: si la voz nativa dictando en el idioma **del reloj** (y
no del pack) es aceptable, cómo revisar las cinco decisiones cotizadas contra 192 dp, y la
granularidad de un pack auxiliar de sinónimos (`uid` es por entrada, un sinónimo es por acepción).
Sigue sin verse **ningún tile en un reloj**, y `MAX_HISTORY = 3` sigue atado a la aritmética vieja.

## 2026-09-19 — El inglés sí tenía sinónimos: estaban en la otra forma

**Qué.** Los packs vuelven a abrir (estaban en `deflate-v1` y el código exige `deflate-v2`), el
inglés gana sinónimos, los dos ganan antónimos, el nombre del pack deja de cortarse, el historial
sobrevive a reconstruir un pack, **`:app` pasa a inglés** —identificadores, archivos y
comentarios— junto con los `CLAUDE.md`, los seis skills y cuatro documentos de `docs/`. Al final:
**el inicio deja libre la hora** y los cuatro textos en voseo pasan a español neutro.

**Áreas.** `tools/packbuilder/{sources/kaikki,payload,build,build_pack,verify_pack,
gen_payload_fixture}.py`, `dict-core/{Model,PayloadCodec}.kt`, `dict-data/PackFile.kt`,
`app/{data/Visit,data/PackSet,presentation/{MainActivity,SearchScreen,PacksScreen,
AttributionScreen,EntryScreen}}.kt`, `docs/decisions.md` (D-123 a D-126).

**Por qué.** Pedido: *"revisa posibles mejoras… el nombre del pack sigue viéndose cortado como
Español - definic…"*, más la deuda de que los `.db` en disco y en el reloj no abrían.

**Arquitectura.** ✅ Cumple. El tag `A` **no** sube `CODEC_ID` porque es aditivo, que es
exactamente lo que D-119 dejó escrito. La app sigue sin calcular `uid` (D-057) y sin importar
`android.*` en su lógica (D-072).

**Revisión del roadmap contra el código (pedida al cierre).** **Cinco entradas que el roadmap
declara abiertas ya no lo están**, y ninguna se actualizó: *el historial apunta a otra palabra*
(cerrada hoy por D-123, **con una tercera opción que la entrada no listaba** — validar contra el
lema en vez de excluir del backup o migrar a `uid`), *los packs del reloj son incompatibles*
(reconstruidos, aunque sólo vistos en emulador), *las acciones de una palabra* (existen), *el Tile
sigue siendo el del template* (cerrado por D-106) y *`docs/agents/` pendiente de traducir* (ya
estaba en inglés). Los conteos del §Dónde estamos están viejos en los tres módulos: son **54 /
178 / 147**, no 50 / 166 / 129.

⚠️ **No se actualizó el roadmap a propósito**: la otra sesión tiene cambios sin commitear en dos
de las tres secciones que habría que tocar, y arrastrarlos rompe la regla del repo. Queda para
quien los commitee.

**Medido.** Construir: **63,6 s** el español, **2 min 45 s** el inglés — el número que D-119
citaba, 3 min 38 s, era el de **copiar**, no el de construir. Sinónimos ingleses: de 0 a
**122.454 entradas (15,4 %)** y 240.195 items, **+2,82 MB sobre 268,1 (+1,05 %)**; se había
estimado 0,6 %. Antónimos: 3.317 entradas españolas (2,9 %) y 9.095 inglesas (1,1 %), **+0,06 % y
+0,04 %** — por eso van en el mismo pack y no en uno aparte. Formas de la fuente, sobre 120.000
registros vivos: `synonyms` arriba 16,5 % ES / 5,6 % EN, **dentro de `senses[]` 0,0 % ES / 25,8 %
EN**, y **ninguna entrada usa las dos**.

**Qué salió mal.**
- **D-117 estaba mal por medir de menos.** Decía que el inglés no podía dar sinónimos porque *"0
  de 43.679 traen `sense_index`"*. El número es correcto y la conclusión no: sólo se había mirado
  `raw["synonyms"]`. El inglés los sirve anidados en cada acepción, donde la atribución es
  estructural. Lo agarró medir la **otra** forma antes de creerle a la fila.
- **Una hipótesis mía murió medida.** El 74,5 % de los items ingleses vienen de páginas
  `Thesaurus:*` y van primero, así que el tope de 4 parecía quedarse con lo oscuro. Reordenar
  cambia **91 de 4.872** acepciones mezcladas (1,9 %) y en la muestra **empeora**: `craft` pasa de
  `ability, aptitude` a `craftiness, foxiness`. Se respeta el orden del dump.
- **Afirmé un defecto que no observé.** Comparé los 7 `entryId` cacheados en el emulador contra el
  pack reconstruido esperando verlos apuntar mal: **los 7 seguían bien**. Lo que prueba la premisa
  es `LogicalIdentityTest`, que ya existía. Queda escrito así en el commit.
- **Leí un pack con el diccionario de payload mal decodificado** (está en hex, va
  `bytes.fromhex`) y salió texto corrupto con dígitos intercalados — el síntoma exacto de D-008.
  Era mi script, no el pack.
- **Un `sed` global pisó una firma**: `_senses(raw, con_sinonimos)` quedó como
  `def _senses(raw, True)`. Lo agarró el propio test al no importar el módulo.
- **El shell de este entorno es zsh, no fish.** Una lista de archivos en una variable **no** hace
  word-splitting: el primer `sed` recibió los 12 paths como un solo nombre y no tocó nada. Pasar
  los archivos literalmente.
- **El `description` de los skills NO se traduce, y casi lo traduzco.** Es el campo contra el
  que se matchea el skill, y esas frases son las que el **usuario dice**: *"verificá"*, *"corré el
  gate"*, *"medí"*, *"falta una palabra"*. En inglés el skill deja de dispararse. Misma lógica que
  el brief: lo que se le **habla** al usuario sigue en español; lo que se **escribe** en el repo,
  no.
- **`pack-workflow` ya advertía lo que me costó un rato.** Dice, con ejemplo, que
  `meta.payload_dict` está en hex y que pasarlo como string devuelve texto que *parece* corrupto.
  Lo leí después de cazar el bug. La advertencia ahora dice que ya le pasó a alguien.
- **Casi reporto un bug de UI que no existía.** El detector de textos en español marcó
  `"Show more"` como literal de interfaz en dos pantallas. Estaba **dentro de comentarios** —el
  detector excluye regiones de código, y un comentario no lo es—. El texto real sigue siendo
  `Ver más` y hay un test que lo fija. Mirar el contexto antes de creerle a un grep propio.
- **La traducción de comentarios se hizo con un verificador, y se lo ganó.** Cada archivo se
  compara contra `HEAD` quitando **todos** los comentarios de las dos versiones: si el código
  restante no es idéntico, se rechaza. Sin eso, `Visit.kt` define el separador del historial como
  el literal **U+001F, que es invisible**, y reescribir el archivo a mano lo habría convertido en
  cadena vacía —rompiendo el formato del historial en silencio—. El verificador además cazó un
  bloque que aparecía **dos veces idéntico**, donde un reemplazo a ciegas habría dejado uno en
  español.
- **El renombrado masivo tuvo dos trampas que ningún test veía.** La interpolación **sin llaves**
  (`"$CLAVE_ESCALA=…"`) no es string, es código: el renombrador la dejó con el nombre viejo y lo
  agarró el compilador. Y **`AndroidManifest.xml` seguía declarando `.tile.HistorialTileService` y
  `.tile.PalabraTileService`** — los dos tiles habrían dejado de cargar en el reloj **sin error de
  compilación y sin test**. Lo agarró mirar el manifest a mano; verificado después en dispositivo,
  donde el sistema les pide el preview con el nombre nuevo. Los **valores** de las constantes de
  `SharedPreferences` no se tocaron a propósito: `KEY_PACK` sigue valiendo `"pack_activo"`, y
  renombrarlo habría borrado los ajustes y el historial de quien ya tiene la app.

**Subido al reloj, y ahí aparecieron dos cosas.** APK 0.3.0 instalado (28 s), pack español
(23 s) e inglés (**91,6 s para 258,5 MiB ≈ 2,8 MB/s**, contra los 3 min 38 s medidos antes sobre
309 MB). Los dos con sha256 verificado de los dos lados. Sin un solo rechazo ni crash, y los
**sinónimos se ven en el reloj**: *cooptar* muestra `sin. elegir · seleccionar · nominar · votar`.

✅ **Los 234 dp quedan CONFIRMADOS, y sin escribir una línea de código.** Faltaba verificarlo
*dentro* de la app porque `wm density` es la densidad física; se resolvió preguntándole al
sistema qué configuración entrega: **`sw234dp w234dp h234dp 340dpi`**, con los bounds de la
Activity en los 498×498 completos. Eso es lo que devuelve `LocalConfiguration.screenWidthDp`.
Cierra el 🔴 que pesaba sobre **cinco decisiones** (D-073, D-075, D-078, D-084, D-085): hay
**22 % más pantalla**, y a 48 dp eso es una **cuarta fila, +33 % de resultados**. La medición está;
el rediseño no.

⚠️ **Y una lectura mía que estuvo mal.** En la primera captura del reloj el subtítulo de la
palabra del día se veía `pañol · definicion` y lo di por un recorte que mi propio cambio (D-125)
habría reintroducido. **No lo era**: es el efecto de transformación del `TransformingLazyColumn`
en el borde de la pantalla. Quieto entra completo. Mirar una captura en movimiento y concluir es
el mismo error que este repo persigue en otros lados.

**Listo para el reloj, y sin subir.** `versionCode` pasó a **3** y `versionName` a **0.3.0**
—estaba en 2, y el instalador de Android **rechaza un versionCode menor al instalado** (D-095)—.
El APK de debug está construido (**52.202.566 B**) y los dos packs pasan `verify_pack.py`.
⚠️ **El reloj no estuvo conectado en toda la sesión**: lo que hay en el emulador no es lo que hay
en el reloj, y ahí siguen **los packs anteriores a D-116, que la app rechaza**. Subirlo es
`:app:installDebug` más `devpack.py install` de los dos packs.

**Qué quedó sin hacer.** De la **Fase C** se hicieron los pasos 1, 2 y 4, y la mitad del 3:
**`app/src` no tiene un solo identificador ni un solo comentario en español**, y tampoco lo tienen
`CLAUDE.md`, los tres `CLAUDE.md` de área, los seis skills ni `architecture`, `contratos-cruzados`,
`formato-pack` y `references`. Quedan **`docs/decisions.md`** (81 KB, la fila por decisión es
prosa densa), **`docs/agents/`** (~200 KB, meta-documentos) y **el changelog** (120 KB, que el
plan deja explícitamente para el final y en un commit aparte). ⚠️ **`docs/roadmap.md` y
`tools/CLAUDE.md` están bloqueados**: siguen modificados sin commitear por otra sesión, así que
traducirlos enredaría su trabajo con el mío. Y del producto, lo barato y sin hacer: **revisar las cinco decisiones que se cotizaron contra
192 dp**, ahora que los 234 están confirmados —empezando por si entra una cuarta fila— y
**ajustar los umbrales fuzzy** contra el pack real, que D-052 fijó a priori
sobre 22 entradas. Y la **Fase D** (varios diccionarios activos, descubrir palabras, ajustes ampliados, ver los tiles
dibujados). La etiqueta de tipo se muestra **en español al lado de un pack inglés**
(*"English · definiciones"*): lo cierra la localización de la Fase C. El reloj físico **no estuvo
conectado**: todo lo de dispositivo se verificó en el emulador, así que el tamaño real en 234 dp
sigue sin confirmarse. `docs/roadmap.md` y `tools/CLAUDE.md` quedan **modificados y sin commitear
por otra sesión** — no son míos y no los arrastré; `tools/CLAUDE.md` dice 129 tests de Python y hoy
son 147.

## 2026-09-19 — La fuente no era el problema: 26.265 entradas decían "Apellido."

**Qué.** Se evaluaron las fuentes alternativas de diccionario para los dos idiomas y **ninguna
reemplaza a la actual**. El trabajo terminó siendo de poda y de recuperar contenido que la
fuente ya traía: los nombres propios salen de los packs, los sinónimos del Wikcionario entran, y
Open English WordNet queda medido pero no adoptado. Después, a pedido, se revisó cobertura y
calidad del español: se limpian las etiquetas de mantenimiento del wiki y queda medido --y
pendiente-- el aporte de enwiktionary §Spanish. **D-116 a D-122.**

**Áreas.** `tools/packbuilder/` (`sources/kaikki.py`, `sources/oewn.py` **nuevo**,
`sources/toy.py`, `build.py`, `build_pack.py`, `payload.py`, `verify_pack.py`,
`gen_payload_fixture.py`, `vectors/payload-fixture.tsv` regenerado, 4 archivos de tests),
`dict-core/src/main/kotlin/cl/fadiaz/dictionary/core/PayloadCodec.kt` y `Model.kt`,
`dict-core/src/test/kotlin/cl/fadiaz/dictionary/core/PayloadCodecTest.kt`,
`app/src/main/java/cl/fadiaz/dictionary/presentation/EntryScreen.kt`,
`app/src/main/java/cl/fadiaz/dictionary/data/WordOfTheDay.kt` (sólo KDoc),
`app/src/test/java/cl/fadiaz/dictionary/presentation/ScreensTest.kt`,
`dict-data/src/androidTest/assets/toy-es-en.db`,
más `docs/decisions.md`, `docs/references.md`, `docs/roadmap.md`, `docs/formato-pack.md`.

**Por qué.** Pedido: comparar fuentes de definiciones en inglés y español y evaluar si vale la
pena cambiar, con dos síntomas — el español se siente incompleto, el inglés trae nombres propios
de más.

**Arquitectura.** ✅ Cumple. La poda sigue siendo **estructural**: se apoya en tags de
wiktextract, no en texto de ningún idioma (D-076). El cambio de payload viaja en los dos
lenguajes y la auditoría compara las constantes.

**Medido.**
- **Los dos síntomas eran el mismo bug, y no era de fuente.** **26.265 entradas del pack español
  tenían como definición completa la palabra "Apellido."** — el 18 % del pack. El 39,6 % de las
  entradas no definía nada en menos de 25 caracteres.
- **En 4.267 `norm` del inglés el nombre propio le ganaba en rank a la palabra común.** Buscar
  *freedom* devolvía primero *Freedom*, un *census-designated place* del condado de Santa Cruz.
- **La cobertura del español nunca fue el problema**: 70/70 en una sonda con chilenismos
  (*pololear*, *cachai*, *flaite*, *marraqueta*). El inglés, 57/57.
- **enwiktionary §Spanish, el candidato obvio, no da nada**: 118.458 lemas contra las 113.889
  entradas no-propias que ya había, y con glosas en inglés.
- **Packs reconstruidos:** español 146.194 → **114.620 entradas**, 72,2 → **68,1 MB**. Inglés
  956.150 → **794.355**, 295,1 → **255,7 MiB**. De los 29.599 `norm` que el español pierde, el
  **90,4 % no definía nada**.
- **Sinónimos:** 26.369 entradas (23,2 %) ganaron 71.609 sinónimos **por acepción**, por
  **+0,89 MB** — tres veces mi estimación de 0,30, porque `fts_def` los indexa además del payload.
- **Markup editorial fuera:** **665 → 0 acepciones** con `^([cita requerida])` /
  `^([definición imprecisa])`. El pack pierde **exactamente una entrada**, `arterializar`, cuya
  definición completa en el Wikcionario *es* la etiqueta — o sea que no tenía definición.
- **La cobertura del español SÍ se puede ampliar, y me había equivocado al decir que no**: el
  solapamiento con enwiktionary §Spanish es **sólo del 44 %** (46.326 lemas), y allá hay
  **56.741 que acá no están** — unión de **160.919, +54 %**. No se fusiona igual, pero por otro
  motivo: sus glosas son **traducciones al inglés**, no definiciones (*entretecho* → "loft;
  attic; garret"). Eso es un pack bilingüe, no una mejora del monolingüe.
- **Lo aprovechable de esa fuente son los ejemplos, y están en español**: 5.307 entradas que hoy
  no tienen ejemplo lo tendrían (17,3 % → 28,8 % sobre los lemas compartidos).
- **Spike OEWN:** 135.969 entradas en **38,4 MiB** (6,7× menos que Wiktionary podado), build en
  17,9 s, **70,8 % de entradas con sinónimos**. Pero **64 % en una sonda dura de 39 palabras**
  contra 100 %: no tiene *selfie*, *blockchain*, *deepfake*, *ghosting*, *burnout*,
  *mitochondria*. Por eso no reemplaza.

**Qué salió mal.**
- **La poda por `pos = name` a secas se llevaba puesto "January".** Los meses en inglés son
  nombres propios y **6 de los 12 desaparecieron** — lo agarró la sonda de vocabulario, no el
  gate ni `verify_pack.py`, que daban verde sobre un pack sin *january*. Hubo que volver a
  preguntar y agregar una excepción por señal léxica (`translations + descendants + derived`).
  **La lección: la sonda de vocabulario encontró lo que 119 tests no vieron.**
- **Afirmé en el plan que "buscar *bobo* encontraría *chulengo*" y era inventado.** El registro
  real de `chulengo [adj] "Tonto."` **no tiene sinónimos**. El mecanismo funciona —lo prueban
  *domingo*, *tonto*, *casa*, *pololear*— pero el ejemplo estaba sacado de la nada. De rebote
  apareció algo mejor: *chulengo* **se lista a sí mismo** como sinónimo, que es justo lo que el
  guard del lema ataja.
- **Estimé el costo de los sinónimos en 0,30 MB y fueron 0,89.** Me olvidé de que entran a
  `fts_def` además del payload, que es media razón del cambio.
- **Dije que no había que subir `CODEC_ID`** para no forzar a redescargar 364 MiB. Falso en el
  sentido que importa: no hay instalador, los packs se sideloadean, y el costo real son 3 min
  38 s de `adb`. Se subió a `deflate-v2`, y lo que quedó escrito es la contracara (D-119).
- **El primer `sed` que usé para comprobar que un test fallaba fue un no-op** y el test "pasó"
  con la clave borrada. Lo rehíce en Python y ahí sí falló. Es exactamente por qué hay que
  **mirar fallar** en vez de asumir que falló.
- **El spike de OEWN hizo saltar el guard de colisión de `uid` dos veces**: `pate` (dos
  `LexicalEntry` sin nada que las separe) y `green` (porque `a` y `s` —adjetivo satélite—
  mapean los dos a `adj`, y yo contaba duplicados por el `pos` crudo). Que abortara fue correcto
  las dos veces.
- **Afirmé "cero ganancia de cobertura" comparando totales, y es una falacia.** Dije que
  enwiktionary §Spanish no aportaba nada porque tiene 118.458 lemas contra nuestros 113.889 —
  pero **dos conjuntos del mismo tamaño pueden no solaparse**. Bajando el GB y cruzando de
  verdad, el solapamiento es del 44 % y hay 56.741 lemas nuevos. La conclusión final no cambió,
  pero **el razonamiento que la sostenía estaba mal**, y quedó corregido en `docs/references.md`
  con la tabla del cruce.
- **El primer filtro de markup que se me ocurrió habría destruido contenido.** `^(...)` a secas
  parece markup, pero en inglés es **superíndice matemático**: `10^(100)`, `e^(iπ)`. Lo agarró
  mirar las 775 ocurrencias del dump en vez de las 6 de la muestra. El filtro exige corchetes.
- **Armando los commits, un `git add -A` se llevó el spike de OEWN adentro del commit de la
  poda.** Los dos archivos de `sources/oewn.py` terminaron en un commit que no los nombra. Lo
  agarró revisar `git show --stat` antes de seguir, no el gate — un commit mal partido compila
  igual. Se rehizo la historia con `reset --hard` + `checkout <sha> -- .` y `git add` explícito
  por archivo. **Lección: `git add -A` no sirve cuando el trabajo se parte en commits**, porque
  barre lo que todavía no le toca.
- **La primera verificación de los cinco commits dio un falso rojo.** El loop contaba líneas con
  `grep -c FAILED` en vez de mirar el exit code, y el commit de documentación salió "ROJO"
  siendo verde. Se rehízo con `exit=$?`: los cinco dan 0 en worktree limpio.
- **El máximo de decisiones era D-115, no D-110.** Dos commits de otra sesión habían entrado
  mientras planificaba; el brief del arranque ya estaba vencido.

**La barrida de cierre, y lo que destapó.**
- **`docs/contratos-cruzados.md` —el documento que responde "falta una palabra"— no decía que
  ahora hay palabras ausentes a propósito.** Es el hueco más peligroso que dejó este trabajo:
  el síntoma de una decisión de producto y el de un contrato roto **son idénticos**, así que
  alguien iba a debuggear `norm()` por un no-bug. Ahora abre con la pregunta y el
  `SELECT value FROM meta WHERE key='proper_nouns'` que la responde en un segundo. Lo mismo en
  el skill `troubleshoot-diccionario`, donde la causa nueva va **primera** porque es la más
  probable y la más barata de descartar.
- **Ocho lugares afirmaban los números viejos** (`146.194`, `72,2 MB`, `956.150`, `295,1 MiB`):
  `docs/formato-pack.md`, `docs/roadmap.md`, `docs/decisions.md` (D-028), `app/CLAUDE.md` y el
  skill `pack-workflow`. Se distinguió el **registro histórico** —"HECHO 2026-09-17", "En qué
  quedó"— que se deja tal cual, de las **afirmaciones de estado actual**, que se corrigieron.
  Es la cuarta sesión seguida en que la documentación se queda atrás del código.
- **Se propuso, sin ejecutar, una sonda de contenido** en el roadmap §Proceso y herramientas.
  Marcada explícitamente como **primer golpe de changelog**, con los dos golpes dentro de esta
  misma sesión declarados como tales para que se pueda descontar.

**Qué quedó sin hacer.**
- **Los packs reales no se instalaron en el reloj.** Están construidos y verificados en el
  scratchpad, no en `wearos-dictionary-data/`. Los tres del reloj tienen `deflate-v1` y la app
  nueva **los va a rechazar** con `IncompatibleException`: hay que reconstruir y re-sideloadear
  **antes de probar nada en el dispositivo**.
- **El orden de resultados sigue sin arreglar.** Buscar un sinónimo **encuentra** la entrada
  pero la ordena por el proxy de `rank` (D-067): *bobo* devuelve 42 entradas y las útiles no
  están arriba. La poda sacó 22 % de ruido pero no recalibró nada, y `cas` sigue devolviendo
  *castigar* antes que *casa*.
- **Los ejemplos de uso del español siguen en 8,7 %** contra 29,6 % del inglés. La única fuente
  sería enwiktionary §Spanish —cuyos ejemplos **sí están en español**— y es una segunda fuente y
  una segunda licencia.
- **El 28,0 % del pack español sigue siendo entradas de una sola palabra**, y los sinónimos sólo
  alcanzaron al 6,8 % de ellas.
- **Los 2.204 subíndices de referencia cruzada siguen** (*"semejanza a un guanaco₁"*): son
  válidos como texto pero en un reloj no hay ningún *guanaco₁* al que ir.
- **Los ejemplos de enwiktionary §Spanish no se integraron** (D-122). El dataset quedó bajado en
  `wearos-dictionary-data/es-en-wikt.jsonl`, 1,04 GB, para que la próxima sesión no lo repita.
  Lo que falta diseñar es **a qué acepción se pega cada ejemplo**: por lema es contenido
  incorrecto que parece correcto.
- **La excepción léxica cuela ~350 nombres de pila españoles** (*Jorge*, *María*): 0,3 % del
  pack, aceptado a cambio de recuperar *España*, *Chile*, *México*.
- **El pack `en-core` de OEWN no está decidido.** El spike entregó el número; si prospera, lo
  bloquea "dos packs del mismo idioma se pisan", que necesita `SearchRepository`.

## 2026-09-18 — Los 364 MiB entran, y el inglés llegó al reloj a la tercera

**Qué.** Se subieron los tres packs y la app al reloj físico (SM-L715F, API 37).

**Áreas.** Ninguna de código: es una sesión de despliegue y verificación.

**Por qué.** Cerrar el desarrollo por ahora dejando el reloj con lo último.

**Medido.**
- **El pack de inglés está en el reloj por primera vez.** 309.452.800 B, sha256 verificado a los
  dos lados, **3 min 38 s** por la ruta por defecto de `devpack`. Los tres intentos anteriores
  --en dos sesiones-- habían muerto.
- **364 MiB de diccionarios entran en hardware real**, que era una de las cuatro preguntas
  abiertas desde que existe el reloj: 53 kB + 72,2 MB + 295,1 MB, y quedan **40 GB libres**.
- **El versionado hizo lo suyo sin que nadie lo notara**: el APK con `versionCode 2` se instaló
  sobre el `1` que había. Al revés lo habría rechazado el instalador (D-095).
- **Dos palabras del día en el reloj, una por diccionario**: *earsplittingly* (English) y
  *contento* (Español), cada una con su idioma debajo. EN quedó activo porque el reloj está en
  `en-US` y el pack activo lo decide el idioma del sistema (D-079).
- **Cero crashes de `cl.fadiaz.dictionary` en el buffer.** Los únicos que hay son de
  `io.homeassistant.companion.android`.

**Qué salió mal.**
- **El primer intento del inglés murió al 84 %** (260.046.848 de 309.452.800 B) con *"device not
  found"*: el reloj se cayó de ADB a mitad de la transferencia. La latencia estaba en 150 ms
  contra los 40 ms de cuando el español entró en 20 s. Otra vez el diseño atómico dejó un
  `.part` y no un pack corrupto (D-082), y el reintento funcionó.
- **Volví a manejar el reloj a ciegas con `input tap` y volvió a fallar**: un swipe me sacó a los
  ajustes del sistema y un tap cayó en el campo de texto en vez de en la palabra. Es la misma
  fricción ya anotada en §Proceso y herramientas del roadmap, y la ignoré.

**Qué quedó sin hacer.**
- **El crash de *Ver más* sigue sin reproducirse y ahora hay un dato nuevo**: no dejó rastro en
  el buffer de crashes. O se limpió, o **no era un crash de proceso** sino un ANR o un congelado
  visual — que es una hipótesis distinta y cambia dónde buscar.
- **La corona sigue sin moverse** y los **234 dp sin confirmar** dentro de la app. Las dos
  necesitan a alguien tocando el reloj, no `adb`.

## 2026-09-18 — Los documentos dejan de mentir, y el check que pedían por escrito

**Qué.** Barrida de documentación y un check nuevo en el audit (18 ahora).

**Áreas.** `README.md`, `docs/architecture.md`, `docs/roadmap.md`, `docs/formato-pack.md`,
`app/CLAUDE.md`, `dict-data/CLAUDE.md`, `tools/CLAUDE.md`,
`.claude/skills/verify/SKILL.md`, `tools/audit_dictionary.py`.

**Por qué.** Pedido de cerrar la sesión actualizando todos los documentos.

**Arquitectura.** ✅ Cumple.

**Medido.** Los conteos reales, que estaban mal en **ocho lugares**: `:dict-core` 47,
`:app` 85 JVM y 47 de pantalla, `:dict-data` 31, Python 101, audit 18 checks, 105 decisiones.

**Qué salió mal.**
- **Cuatro documentos afirmaban que `:app` seguía siendo el template de Android Studio** —
  `README.md` dos veces, `docs/architecture.md` dos veces— cuando hace sesiones que no lo es.
  El changelog lo venía señalando desde hace tres y nadie lo arreglaba, incluido yo.
- **`docs/formato-pack.md` anunciaba `schema_version = 2` en su título** mientras su propio
  cuerpo hablaba de la 3. Cuarta sesión que se señala.
- **`docs/architecture.md` describía un check que no existía**: decía que comprobar la dirección
  de dependencias *"es lo primero que la auditoría tiene que agregar"*. Ahora existe
  (`check_module_direction`) y se comprobó invirtiendo la dependencia a propósito: falla y dice
  cuál. Mira el build file y no los imports, porque `:app` y `:dict-data` **comparten el nombre
  de paquete** `cl.fadiaz.dictionary.data` y un import no dice de qué módulo viene.
- **La entrada de este changelog no se escribió en el primer intento** y el commit salió sin
  ella: el script falló buscando un ancla que no existía y sólo se vio en el traceback.

**Qué quedó sin hacer.**
- El presupuesto de **192 dp sigue escrito en cinco decisiones** y el reloj mide 234. Queda
  marcado en rojo en `app/CLAUDE.md`, pero confirmarlo dentro de la app sigue pendiente.
- El catálogo de descarga de packs sigue siendo un WIP en pantalla.

## 2026-09-19 — Las pantallas entran al gate, y el inicio se reordena

**Qué.** Robolectric mete las pantallas al gate (D-110) y con eso los siete arreglos de
usabilidad se verifican en segundos: ícono propio, margen final, Guardadas siempre visible,
borrar historial con doble toque, diccionarios legibles, el inicio en secciones y *Ver
traducción* escondida. D-110 a D-115.

**Áreas.** `app/build.gradle.kts`, `gradle/libs.versions.toml`,
`app/src/test/resources/robolectric.properties`, las seis pantallas de
`app/src/main/java/cl/fadiaz/dictionary/presentation/`, el `AccionesDeLaPalabra.kt` nuevo, los
tres drawables del ícono y `AndroidManifest.xml`.

**Por qué.** Catorce observaciones de usar la app en el reloj.

**Arquitectura.** ✅ Cumple. La decisión de qué acciones ofrece una palabra sale de
`MainActivity` a un archivo sin Android, para que el gate la vea (D-072).

**Medido.**
- **Los minutos de los tests no estaban en los tests.** Los 31 de `:dict-data` ejecutan en
  **3,3 s** y los 6 de tiles en **0,05 s**: el tiempo se iba en compilar e **instalar dos APK de
  50 MB**. Con Robolectric, **46 de los 47** de pantalla corren en **21 s** y el gate en frío
  queda en **1m07s** — contra 1m26s cuando *no* incluía las pantallas. `:app` pasa de 108 a
  **154 tests JVM**.
- **Robolectric 4.16.1 llega hasta SDK 36** y el proyecto targetea 37: sin
  `robolectric.properties` todo falla con *"Package targetSdkVersion=37 > maxSdkVersion=36"*. O
  sea que **la suite rápida no corre en el nivel del reloj**.
- **El único test que no sobrevive** es tocar una palabra dentro de una glosa: el nodo del enlace
  se encuentra y el click se despacha, pero el callback no se dispara. Es hit-testing sobre el
  rectángulo de una palabra dentro de un párrafo, y eso necesita layout de texto real.
- **El nombre de un diccionario no entra en una línea**: después del check reservado, los
  paddings y el botón de borrar de 48 dp le quedan ~140 dp, y *"Español — definiciones"* son 22
  caracteres.

**Qué salió mal.**
- **`allWarningsAsErrors` me corrigió el primer test de Robolectric**: usé `createComposeRule` en
  vez del `v2`, que es el que ya usaba el resto del proyecto.
- **Puse un comentario XML entre los atributos de `<application>`** y rompí el manifest. El error
  era *"Error parsing AndroidManifest.xml"*, sin línea.
- **El fixture de los tests declaraba los dos packs como español**, así que al agregar el idioma
  al subtítulo *"ES"* aparecía dos veces y el test falló por el fixture, no por el código.
- **`SettingsScreen` recibía un parámetro `activo` que nunca usaba**, y lo descubrí al tener que
  pasarle un valor de mentira desde un test.

**Qué quedó sin hacer.**
- **Las fases 2 a 4 enteras**: idiomas es/en, varios diccionarios activos, descubrir palabras,
  ajustes ampliados, y ver los tiles dibujados.
- **La palabra del día ya no se ve sin scrollear**, que es el costo directo de poner la barra
  primero. Está aceptado y escrito, pero nadie lo miró con las dos palabras y el historial llenos.
- **El subtítulo del diccionario se corta** en la palabra del día (*"Español — definicio…"*).
- Sigue abierto el defecto heredado de que las preferencias se respaldan con `entryId`, que no
  sobrevive a reconstruir un pack.

## 2026-09-19 — La superficie glanceable deja de ser el template, y ningún tile abre un pack

**Qué.** Dos tiles de diccionario —últimas palabras y palabra del día— reemplazan al *"Hello,
Tile!"* del template, y la complication del día de la semana **en inglés** se apaga. Cierra la
mitad glanceable de D-087 y la fila abierta del período de refresco, que llevaba abierta desde
que nació. D-106 a D-109.

**Áreas.** `app/src/main/java/cl/fadiaz/dictionary/tile/` (cuatro archivos: `TileContenido.kt`
puro, `TileRender.kt`, y los dos servicios; borrado `MainTileService.kt`),
el paquete `complication` entero (borrado), `data/PackStore.kt`,
`presentation/SearchViewModel.kt` y `MainActivity.kt`, `AndroidManifest.xml`, `strings.xml`,
`tools/audit_dictionary.py`, `app/src/test/java/cl/fadiaz/dictionary/tile/TileContentTest.kt` y
`app/src/androidTest/java/cl/fadiaz/dictionary/tile/TilesTest.kt` (nuevos), más `docs/` y el `verify` skill.

**Por qué.** Pedido: planificar e implementar los dos tiles. La deuda estaba escrita **idéntica
en cinco entradas de este changelog** sin avanzar nunca, lo que decía que faltaba la decisión de
producto y no el trabajo.

**Arquitectura.** ✅ Cumple. `TileContenido.kt` no toca Android y entra a la lista que vigila
`check_app_logic_is_jvm_testable`, que ahora son **siete** archivos (D-072). Reusa `Visita` y su
codec para las tres superficies, como ya hacían las guardadas (D-102).

**Medido.**

- **Ningún tile abre un pack, y no es una opinión de rendimiento.** Verificado en el
  `tiles-1.6.2-sources.jar`: `onTileRequest` está anotado `@MainThread` y *"must complete after
  at most 10 seconds"*. Afirmar que abrir 69 MB ahí es lento habría estado prohibido sin medir
  (D-042); decir que está fuera del contrato, no.
- **`setFreshnessIntervalMillis` es tiempo TRANSCURRIDO, no reloj de pared** — *"elapsed time
  (not wall clock time)"*, además *"inexact"* y con throttling. Pedirle 24 h habría hecho que la
  palabra del día se corriera unos minutos cada día. `TimeInterval` sí es epoch, así que la
  palabra va en un `Timeline` de siete ventanas y el renderer cambia solo: **cero despertares**
  contra los 24 diarios de la complication (D-107).
- **La app y el tile eligen la misma palabra.** Con el pack real de 146.194 entradas, la caché
  quedó escrita con siete lemas distintos y el día 0 es `sonarse`, que es exactamente lo que
  muestra el inicio de la app en la misma captura. Es la comprobación que importaba.
- **`tiles-testing:1.6.2` existe y arrastra Robolectric 4.16.1.** Se resolvió de verdad antes de
  decidir. Se descartó: meter un runner nuevo en un gate de segundos cuesta más de lo que compra.
  La cobertura quedó en `TileContenidoTest` (15, en el gate) y `TilesTest` (6, en dispositivo,
  con un Context real y sin dependencia nueva).
- **El gate pasa de 18 a 19 checks** y `:app` de **85 a 108 tests JVM**. Los dos enforcers nuevos
  se probaron **fallando** —un tile mencionando `PackFile.`, y la política importando
  `android.content.Context`— y después restaurados.
- **Los 6 tests de layout corren en API 37**, el nivel del reloj del proyecto, que es donde
  aplica `METADATA_GROUP_KEY`.

**Qué salió mal.**

- **Planifiqué media sesión contra una foto vieja del repo.** Mientras planeaba entraron 13
  commits, y dos tiraban abajo la premisa: la palabra del día **ya existía** (D-097) y había
  aparecido un reloj físico. Iba a construir un pool por `rank` guardado en `meta`, con un método
  nuevo en `DictionarySource` — todo innecesario.
- **Medí un sesgo que el repo ya había medido y corregido.** Encontré que el top-366 por `rank`
  es 86,6 % verbos y lo presenté como hallazgo; D-097 ya lo tenía como *"28 días seguidos daban
  28 verbos"*, resuelto rotando la categoría. Correcto y redundante.
- **Dije que `ORDER BY rank LIMIT N` era un full scan de tabla; es del índice**, con temp
  B-tree. Un subagente me corrigió a medias —dijo que era barato— y al medirlo resultó que la
  parte mía que estaba bien era el costo: 8–16 ms en español y 40–148 ms en inglés, en
  escritorio. Terminó sin importar, porque el algoritmo que ya existe no usa esa consulta.
- **Escribí `sumarDias` sin test y lo cubrí después.** Es exactamente lo que este repo prohíbe.
  El resto del archivo sí fue test primero, visto fallar.
- **Un `report.ok()` que no existe** rompió el audit en la primera corrida del check nuevo.
- **Cuarto golpe de la fricción de verificar a ojo en el emulador.** Gasté cinco intentos
  buscando el carrusel de tiles —tap que no abre el picker, scroll que se pasa, long-press que
  entra, y terminé en los ajustes rápidos— y **no llegué a ver un tile dibujado**. Paré ahí en
  vez de seguir, que es lo que la fricción ya registrada recomienda.

**Qué quedó sin hacer.**

- **Ningún tile se vio nunca dibujado.** Está verificado que los dos quedan registrados
  (`dumpsys`), que sus layouts se construyen sin tirar (6 tests en API 37) y que los datos llegan
  —la caché escrita coincide con la app—, pero **nadie vio un tile en pantalla**. Es lo primero a
  mirar cuando el reloj vuelva a la red. Queda escrito en `docs/roadmap.md` §Ver los dos tiles
  funcionando en un reloj, junto con las otras tres cosas que cuelgan de eso.
- **El `Timeline` de siete entradas es ASSUMPTION**: el javadoc no documenta un límite de
  entradas, y que el renderer las honre sin truncar no se comprobó.
- **Que el click abra la entrada correcta tampoco se comprobó en pantalla.** El extra se valida
  contra los packs abiertos —caer al activo sería D-080 otra vez— pero eso está probado por
  lectura, no por uso. Ojo además con `launchMode`: si la app está en background, el sistema
  puede reusar la tarea y `onCreate` no volver a correr, y el extra se perdería. No se probó.
- **Cuánto ahorra en batería no se midió**, y no se puede sin profiler en el reloj (O-4).
- **Los 234 dp siguen sin confirmar dentro de la app**, cuarta sesión. El tile usa el tamaño que
  le pasa `deviceConfiguration`, así que no depende del literal, pero `TilesTest` lo fija en 234
  a mano.
- **Un defecto que encontré y no toqué**, porque el arreglo es una decisión y no un parche: las
  preferencias **se respaldan y se transfieren** —`data_extraction_rules.xml` sólo excluye
  `packs/`— y guardan `entryId`, que según `schema.sql` *"NO sobrevive a reconstruir el pack"*.
  Restaurar un backup, o simplemente reconstruir un pack, deja el historial y las hasta 100
  guardadas apuntando a **otras palabras**, en silencio. Y `pack_activo` viaja a un reloj donde
  ese `.db` no existe. **Los tiles no lo crean: lo ponen en la carátula del reloj.** Documentado
  en `docs/roadmap.md` §El historial y las guardadas sobreviven a un backup, y abierto en
  §Decisiones abiertas con las dos salidas y lo que cuesta cada una.

---

## 2026-09-18 — El inicio, la palabra del día, y el primer APK que se distingue del anterior

**Qué.** El APK deja de declarar `versionCode 1` del template (D-095). El inicio gana palabra del
día —**una por diccionario cargado**— y ajustes, sin ser una pantalla nueva (D-096, D-097). La píldora deja de estar copiada seis
veces (D-098). Ajustes trae idioma, tamaño de texto y borrar el historial (D-099). La entrada gana
dos botones en una fila y un menú de opciones con guardar, copiar y ver en el otro idioma (D-100,
D-101, D-102), más una pantalla de palabras guardadas.

**Áreas.** `gradle.properties`, `app/build.gradle.kts`, `tools/audit_dictionary.py`, los archivos
nuevos `app/src/main/java/cl/fadiaz/dictionary/presentation/Components.kt`,
`app/src/main/java/cl/fadiaz/dictionary/presentation/SettingsScreen.kt`,
`app/src/main/java/cl/fadiaz/dictionary/data/WordOfTheDay.kt` y
`app/src/main/java/cl/fadiaz/dictionary/data/Settings.kt`; más `EntrySummary` en
`dict-core/src/main/kotlin/cl/fadiaz/dictionary/core/Model.kt` y su `summary()` en
`dict-data/src/main/kotlin/cl/fadiaz/dictionary/data/SqlitePackSource.kt`.

**Por qué.** Pedido: inicio con voz, texto, palabra del día, historial, ajustes y créditos; la
entrada con dos botones y menú; versionado y buenas prácticas de plataforma.

**Arquitectura.** ✅ Cumple. `PalabraDelDia` y `Ajustes` no tocan Android --la fecha entra por
parámetro-- y se sumaron a los archivos que vigila `check_app_logic_is_jvm_testable`, que ahora
son seis (D-072).

**Medido.**
- **Un inicio como ruta propia va contra la guía oficial.** Wear OS pide *"shallow and linear:
  avoid hierarchies deeper than two levels"* y elevar la acción primaria. Un menú que enruta a la
  búsqueda la hunde un toque. Por eso el inicio quedó siendo el estado vacío de la búsqueda.
- **`rank` está aplastado**: mediana **992** sobre un máximo de 997, el 100 % de las entradas bajo
  3000. Elegir una entrada al azar da *Eyaralar, piscigranja, Ynda, Voorschoten, nonparaxiality*.
- **Un umbral de rank funcionaba pero era por idioma**: 912 en español, 978 en inglés. Se
  reemplazó por el mejor de 32 candidatos, que no necesita constante. Resultado: *permanecer,
  errar, despedazar* y *swell, relieve, grove, stop, twinge*.
- **Sesgo medido y CORREGIDO en la misma sesión**: en español, **28 de 28 días daban verbos**.
  No era prevalencia —el pack es 29,2 % sustantivos contra 28,5 % verbos, casi empatados— sino
  que las páginas de verbos traen las conjugaciones y `rank` premia riqueza (D-067). Rotando la
  categoría objetivo por día, la misma muestra da **sustantivo 12, verbo 8, adjetivo 7,
  adverbio 1**, y salen *sorpresivo, gurú, mentira, arrollador* en vez de catorce infinitivos.
- **Los packs no usan el mismo vocabulario de `pos`**: los de kaikki dicen `name`, el de juguete
  dice `proper noun`. La exclusión cubría sólo uno, así que en el pack de demostración la palabra
  del día podía ser un nombre propio. Un fixture de un solo vocabulario no lo muestra.
- **`ButtonGroup`, `AlertDialog`, `ConfirmationDialog`, `SwitchButton` y `RadioButton` existen y
  son estables en Wear Material3 1.6.2**, verificado abriendo el `.aar`. **No existe** menú
  desplegable ni overflow.
- El APK declara ahora `versionCode 2`, `versionName 0.2.0`, comprobado en `output-metadata.json`.

**Qué salió mal.**
- **Dos tests nuevos pasaron por casualidad.** Con todos los `rank` iguales en el fixture, gana
  el primer candidato que aparece, así que "no elige un nombre propio" y "la categoría rota"
  pasaban sin que el código hiciera ninguna de las dos cosas. Se rehicieron dándole a lo que
  tenía que perder el **mejor** rank, y ahí sí fallaron.
- **Un slice con índices mal calculados se llevó cuatro funciones del ViewModel por delante**
  —`esFavorita`, `alternarFavorita`, `onEscalaDeTextoChange`, `limpiarHistorial`—. Lo agarró el
  compilador al instante; es el mismo error de recorte que ya había cometido en otra sesión.
- **`FakeDictionary` declaraba `entryCount = 1` fijo**, así que la palabra del día elegía siempre
  el id 1 y el test de "cada pack elige distinto" fallaba por el fake, no por el código.
- **La palabra del día no se veía, y el test del gate pasaba.** La lógica y el wiring estaban
  bien: el ítem llega **asincrónico** --son 32 lecturas-- cuando la lista ya se asentó, y como
  los ítems tienen `key`, la lista conserva su posición y el nuevo se insertaba **fuera de
  pantalla, arriba de todo**. Se arregló poniéndolo debajo del encabezado, donde insertar empuja
  hacia abajo. **Lo destapó una captura, no un test** — y el test que ahora lo fija usa
  `assertIsDisplayed`, no `assertExists`.
- **Escribí el primer `Cargando` de memoria en vez de copiarlo**, con otro `Arrangement` y otro
  padding. Un refactor que cambia comportamiento no es un refactor; se corrigió al original antes
  de correr nada.
- `Ajustes` y `EscalaDeTexto` nacieron `internal` y se exponían en firmas públicas: no compilaba.
- **La caché de configuración de Gradle mintió sobre por qué fallaban los tests.**
  `connectedDebugAndroidTest` reportó *"No compatible devices connected: found 1 device(s), 0 of
  which were compatible"* **tres veces seguidas**, con el emulador booteado y en estado `device`.
  Reinicié el emulador dos veces y limpié un lock huérfano del AVD persiguiéndolo. Con
  `--no-configuration-cache` el mensaje real apareció al instante: *"There were failing tests"*, y
  era **un solo test viejo** que buscaba el texto "Buscar" donde ahora hay un icono. Costo: ~40
  minutos.

**Qué quedó sin hacer.**
- **Gestionar packs: ver y borrar** entró después, con su propia pantalla (D-103, D-104, D-105).
- **Borrar de verdad libera el disco, medido en el emulador**: libre 9.802.568 kB → con el pack
  de 72,2 MB, 9.732.048 kB → tras borrar, **9.802.568 kB otra vez**, el valor exacto. Es la
  comprobación de que cerrar las conexiones **antes** de tocar el disco funciona: en Unix un
  archivo borrado con un descriptor abierto sigue ocupando espacio, y el usuario habría visto
  "borrado" con cero liberado.
- **El formateador de tamaños redondeaba con dos criterios distintos**: kB hacia arriba y MB al
  más cercano, así que 53.248 bytes se veían como *54 kB*. Lo agarró un test escrito con los
  tamaños reales de los tres packs del proyecto, no con números redondos inventados.
- **El catálogo de descarga de packs**: la mitad de abajo de la pantalla de gestión es un WIP
  explícito. Sigue bloqueado por dónde se hostea el catálogo, igual que el instalador.
- **El test de densidad parametrizado por escala de texto**, que es lo que cerraría WO-V1 de
  verdad. Hoy el ajuste existe y nadie comprobó que con `GRANDE` no se corte nada.
- **Nada se verificó en el reloj**: sigue fuera de la red. La corona sigue sin moverse, los 234 dp
  sin confirmar dentro de la app, el pack de inglés sin instalar y el crash de *Ver más* sin causa.
- Sin verificar de la lista de calidad: **WO-V13** (fondo negro), **WO-V14** (12sp/10sp mínimos) y
  **WO-V16** (que nada se corte en el círculo). `app_name` sigue diciendo "Dictionary" en inglés.

## 2026-09-18 — El reloj de verdad: tres bugs, una capa de tests rota, y un supuesto de 192 dp que no era

**Qué.** Instalado el MVP en un Galaxy Watch (SM-L715F, Android 17 / API 37). Usarlo destapó tres
defectos en minutos. Arreglados dos con test: el teclado que se cerraba a la primera letra
(D-089) y la tecla *Aceptar* que no hacía nada (D-090). Agregado: palabras tocables dentro de una
glosa que abren su entrada (D-094), atajo a la búsqueda arriba del scroll (D-091), y la barra de
búsqueda con borde para que se distinga (D-092). `espresso-core` fijada en 3.7.0 (D-093).

**Áreas.** Las cuatro pantallas y el ViewModel en
`app/src/main/java/cl/fadiaz/dictionary/presentation/`; el tokenizador nuevo
`dict-core/src/main/kotlin/cl/fadiaz/dictionary/core/GlossTokenizer.kt` y la interfaz
`dict-core/src/main/kotlin/cl/fadiaz/dictionary/core/DictionarySource.kt`;
`dict-data/src/main/kotlin/cl/fadiaz/dictionary/data/SqlitePackSource.kt`;
`gradle/libs.versions.toml` y los build de `:app` y `:dict-data`; y los tres archivos de test.

**Por qué.** Pedido tras usar la app en el reloj: arreglar los bugs, poder tocar las palabras de
una definición para saltar a su entrada, volver rápido al inicio, y resaltar la barra. Las tres
secciones por palabra (definiciones / traducciones / sinónimos) quedaron planeadas y **no
construidas**: exigen reconstruir los dos packs.

**Arquitectura.** ✅ Cumple. `GlossTokenizer` vive en `:dict-core` sin una sola API de la JVM
—el primer intento usó `Character.charCount` y lo hubiera cazado `ArchitectureTest` (D-017)—, la
lógica nueva de `:app` no importa `android.*` (D-072), y el link palabra-a-palabra lleva `packId`
(D-080).

**Medido.**
- **La pantalla del reloj son 498×498 px a 340 dpi = 234 dp**, no los 192 dp sobre los que están
  construidos D-073, D-075, D-078, D-084 y D-085. **22 % más pantalla.** Falta confirmarlo dentro
  de la app con `LocalConfiguration.screenWidthDp`: `wm density` da la densidad física y no
  necesariamente la que ve Compose.
- **41 GB libres en `/data`.** Los 364 MiB de los dos packs nunca fueron un problema.
- **Transporte por ADB Wi-Fi: `devpack install` por defecto da 3,4 MB/s; `--tmp` (`adb push`) da
  0,05 MB/s.** El español entró en 20 s; el inglés por `--tmp` tardó **98 minutos** y aun así
  falló al final. **60× más lento.** No usar `--tmp` sobre Wi-Fi.
- **Costo en el pack de las tres secciones futuras**, guardando sólo la palabra: español +0,76 MB
  crudo (**0,3 %**), inglés +30,7 MB crudo (**~3,5 %** comprimido). Y **no exige subir
  `schema_version`**: son tags de payload, que ambos lados ignoran si no los conocen.
- **El Wikcionario español identifica los idiomas destino por nombre en español** (`"Inglés"`),
  no por código ISO — filtrar por `lang_code` da 0 resultados. El inglés trae `links` de sense
  (59,4 B/entrada) y el español **no trae ninguno** (0 B), que es por qué las palabras tocables
  se resuelven en runtime contra `entry.norm` y no con los links de la fuente.

**Qué salió mal.**
- **El primer test de gestos no falló por el bug sino por Espresso.** `NoSuchMethodException:
  InputManager.getInstance`: la 3.5.0 que arrastra `ui-test-junit4` no funciona en API 37. O sea
  que la capa de tests de gestos estaba rota justo en el nivel del reloj, y se descubrió sólo
  porque este fue el primer test que hizo `performClick` sobre un campo.
- **Escribí `GlossTokenizer` antes que su test**, que es exactamente lo que este repo prohíbe. Lo
  rehice: stub → test → rojo → implementación. Y el primer intento usaba `Character.`, prohibido
  en `:dict-core`.
- **Afirmé que el atajo a la búsqueda costaba cero dp y era falso.** Lo desmintieron dos tests
  que se pusieron rojos: con tres acepciones cortas *Ver más* dejó de entrar en pantalla. Está
  corregido en D-091 con el costo real.
- **Mi hipótesis sobre el crash de *Ver más* era la equivocada.** El test con las 47 acepciones y
  el ejemplo de 917 caracteres —los dos máximos medidos— **pasa**: no reproduce el crash.
- **El emulador de API 37 se colgó dos veces** (`hanging thread 'QEMU2 main loop'`). Arranca
  estable con `-gpu swiftshader_indirect`.
- **Manejar el emulador por `adb shell input` para verificar a ojo es poco fiable**: un `swipe`
  salió de la app, unos taps cayeron en el micrófono, y `input text` deja el texto como composing
  del IME de Wear **sin confirmarlo al campo**, así que la query llegaba vacía. Perdí varios
  intentos antes de abandonarlo. Es el tercer golpe de esta clase (ver `keyevent 4`).

**Qué quedó sin hacer.**
- **El crash de *Ver más* sigue vivo y sin causa conocida.** Falta el stack trace del reloj, que
  no se pudo sacar porque el reloj se cayó de la red. Lo que sí se hizo es endurecer los tres
  caminos sospechosos —`key` en los ítems, índice con `getOrNull`, `runCatching` alrededor de
  `cargar`—, pero eso es blindaje, **no el arreglo**, y decir lo contrario mandaría a la próxima
  sesión a dar el bug por cerrado.
- **El pack de inglés no está instalado** en el reloj: los dos intentos de push murieron.
- **La entrada con enlaces no se vio nunca en pantalla.** La cubren 34 tests instrumentados, pero
  nadie la miró funcionando.
- **Los 234 dp no se confirmaron dentro de la app**, y de eso depende si D-073 sigue diciendo
  "tres filas".
- La corona sigue sin moverse nunca.
- `docs/formato-pack.md` sigue anunciando `schema_version = 2` en su título cuando el código
  dice 3, y `docs/architecture.md` sigue describiendo `:app` como el template. Van tres sesiones.

## 2026-09-17 — Dejar todo listo para el reloj, y tres bugs que sólo aparecieron al usarlo

**Qué.** Se prepararon los artefactos para instalar en un reloj físico: APK debug, pack de
español y pack de inglés, los tres verificados end-to-end en el emulador. En el camino
aparecieron **tres defectos que ningún test tenía**, porque los tres se ven mirando la pantalla
o corriendo el comando, no leyendo el código.

**Áreas.** `app/src/main/java/cl/fadiaz/dictionary/data/PackSet.kt`, `PackStore.kt`,
`app/src/main/java/cl/fadiaz/dictionary/presentation/SearchViewModel.kt`,
`app/src/test/java/cl/fadiaz/dictionary/presentation/SearchViewModelTest.kt`,
`tools/devpack.py`, `tools/packbuilder/tests/test_devpack.py`, `docs/decisions.md` (D-088).

**Por qué.** Pedido: dejarlo todo listo para instalarlo en el reloj.

**Arquitectura.** ✅ Cumple.

**Medido.**

- **Los dos packs entran: 364 MiB** en `files/packs` (72.212.480 + 309.452.800 + el demo de
  53 KB). Es la primera vez que ese número existe, aunque sea en emulador.
- **`devpack.py install` del inglés: 3,55 s** para 295,1 MiB, con sha256 verificado de los dos
  lados (`aa53e30e89ec3d7a`). El español: 0,85 s y `258ccdb62d5ff940`.
- **`posEnEspanol` no traduce nueve `pos`** (`character`, `contraction`, `article`, `unknown`,
  `participle`, `symbol`, `syllable`, `particle`, `infix`): son **106 entradas de 146.194,
  0,07 %**. Medido y **no corregido**: el número dice que no vale el cambio ahora.

**Los tres bugs, y por qué ninguno era visible desde el código.**

1. **El pack de demostración tapaba al diccionario real.** Con los dos instalados, la app abría
   las 28 entradas de juguete: buscar "p" devolvía *"correr — traducción"*, que sólo existe en el
   toy. Ni la preferencia ni el idioma desempataban —los dos packs son `es`— así que caía al
   último escalón de `elegirActivo`, que seguía siendo **el orden alfabético**, y `demo-` gana a
   `es-`. Es exactamente la clase que mató D-079, sobrevivida en el último recurso; y con un pack
   de demo dentro del APK ese recurso pasa de raro a normal. Arreglado por **origen** (vino de
   `assets/`), no por nombre — el nombre es justo lo que fallaba (D-088).
2. **El selector mostraba "ES" y "ES".** Los dos packs son español y la etiqueta es el idioma:
   no había forma de saber cuál era cuál. Un placeholder no es una opción, así que el demo ya ni
   se ofrece cuando hay un diccionario de verdad.
3. **`devpack.py` tiraba un traceback** cuando la app no estaba instalada: murió con un
   `BrokenPipeError` con 295 MB adentro. La guarda existía y **miraba el lugar equivocado** —
   `adb` manda los fallos de `run-as` a stderr y `correr` devolvía sólo stdout, así que comparaba
   contra una cadena vacía. Y que la app no esté es **normal**: `connectedAndroidTest` la
   desinstala al terminar.

**Qué salió mal.**

- **Los tres bugs los encontré usando la app, no razonando sobre ella.** Los 48 tests JVM y los
  27 de pantalla estaban en verde mientras la app abría el diccionario equivocado. Es el
  argumento de *"mirá el output, no sólo los números"* en su forma más literal.
- **Corrí `connectedAndroidTest` y me llevé puestos los packs**, sin darme cuenta de que
  desinstala la app. Diagnostiqué el `BrokenPipeError` como un problema del pipe antes de mirar
  si el paquete estaba.
- **Los packs y los dumps se habían borrado** al reiniciarse la sesión, así que hubo que volver a
  bajar 4,66 GB. Reconstruirlos: ~2 min el español, ~3 el inglés.

**Qué quedó sin hacer.**

- **Nada de esto se probó en un reloj físico**, que es el punto. Falta parear el reloj: ahí se
  cierra si la corona funciona (cableada y nunca movida), si los 364 MiB entran en hardware real,
  y si `NormalizationOnDeviceTest` pasa con su ICU.
- Los nueve `pos` sin traducir (0,07 % de las entradas).
- Todo lo de la lista de distribución: instalador de packs, el Tile del template, `app_name` en
  inglés, iconos, R8.

---

## 2026-09-17 — Las tres funciones que faltaban para el MVP, y un ranking que se tiraba

**Qué.** Se cerró el alcance del MVP —todo menos descarga de packs— y se construyeron las tres
funciones que faltaban: **buscar por definición**, **historial de entradas abiertas** y
**release firmable**. De paso apareció un defecto que ninguna de las tres pedía.

**Áreas.** `dict-data/src/main/kotlin/cl/fadiaz/dictionary/data/SqlitePackSource.kt`,
`tools/packbuilder/sources/toy.py`,
`tools/packbuilder/tests/test_build.py`, `dict-data/src/androidTest/`,
`app/src/main/java/cl/fadiaz/dictionary/data/Visit.kt` (nuevo), `PackStore.kt`,
las cuatro pantallas de `app/src/main/java/cl/fadiaz/dictionary/presentation/`,
`app/src/test/` y `app/src/androidTest/`,
`app/build.gradle.kts`, `tools/audit_dictionary.py`, `.gitignore`, `app/CLAUDE.md`,
`docs/decisions.md` (D-083 a D-087), `docs/roadmap.md`, `.claude/skills/verify/SKILL.md`.

**Por qué.** Pedido: verificar qué falta para un MVP con todas las características buscadas
menos descarga de packs, decidir qué entra ahora y qué después, y ejecutarlo.

**Arquitectura.** ✅ Cumple. D-072 (`Visita.kt` entra a la lista que vigila el audit), D-073
(ninguna de las dos funciones nuevas cuesta una fila con resultados en pantalla), D-012 (el
orden de FTS se reimpone en memoria, no con un `JOIN`, porque el `JOIN` cambia el plan).

**Medido.**

- **`searchDefinitions` tiraba el ranking de FTS5.** Pedía `ORDER BY rank` (bm25) y resolvía con
  `WHERE id IN (...)`, que SQLite devuelve en orden de **rowid**. La definición que mejor
  coincide no encabezaba. Es la misma clase de bug que hacía que `per` no devolviera `perro`: un
  orden que existe, se computa, y se pierde en el camino.
- **El fixture no permitía verlo**: con el toy pack, el mejor match de bm25 tenía siempre el
  rowid más bajo, así que tirar el ranking daba el mismo resultado. Ahora hay una trampa
  —`cantera` menciona "mineral" una vez en glosa larga y tiene rowid menor; `cuarzo` lo repite en
  una corta y tiene el mayor— con su guardián **en el gate**, porque el test que comprueba a la
  app es instrumentado y sin la guarda reordenar `_DATA` lo rompería en silencio.
- **Primer release de la historia del repo: 35 MB sin firmar**, contra 50 del debug.
  `lintVitalRelease`, que nunca había corrido, pasa.
- **El gate pasa de 15 a 16 checks** y de 29 a **46 tests JVM**; los de pantalla, de 19 a 27; los
  de `:dict-data`, de 25 a 26.
- **Los dos enforcers nuevos se probaron fallando**, no pasando: se firmó el release con la
  config de debug y el audit rompió; y la trampa del fixture falló antes de existir.

**Qué salió mal.**

- **Un `str.replace` con escapes Unicode volvió a no aplicar y no avisar** — el mismo patrón que
  ya me había fallado en la sesión del pack de inglés. El texto "Sin resultados" nunca cambió, y
  lo agarró un test de pantalla. La segunda vez lo hice por número de línea en vez de por texto.
- **Dupliqué un callback** (`onOpenVisita`) agregándolo dos veces a `MainActivity`; lo agarró el
  compilador con *"argument already passed"*.
- **Confundí el orden de argumentos de `assertEquals` entre los dos source sets**: en
  `src/test` es el de `kotlin.test` (mensaje al final) y en `src/androidTest` el de JUnit
  (mensaje al principio). El test falló comparando el mensaje contra el valor.
- **Perdí tres intentos capturando pantallas del emulador**, otra vez, porque `keyevent 4` cierra
  la app si el teclado no llegó a abrirse. Ya pasó en dos sesiones anteriores y volvió a pasar.
  Lo que terminó funcionando fue no depender del teclado: abrir una entrada, reiniciar la app y
  mirar el estado vacío — que además probó la persistencia.

**Qué quedó sin hacer.**

- **Nada corrió en un reloj físico todavía**, que era el destino elegido. Falta: generar la
  keystore (es del humano, `local.properties` está denegado para el agente), instalar el release
  firmado, comprobar que **364 MiB de diccionarios entran**, correr los 26+27 instrumentados
  sobre el hardware real —`NormalizationOnDeviceTest` es el que importa, porque el reloj puede
  traer otro ICU— y **mover la corona**, que sigue cableada y sin haberse movido nunca.
- **No hay forma de buscar por definición sin fallar antes**: si ya sabés que querés buscar por
  significado, tenés que escribir algo inexistente primero.
- **El historial no se puede borrar** desde la app.
- **El orden de resultados del pack inglés sigue sin evaluar** (`forms_cap = 12` es a ojo).
- **`docs/architecture.md` sigue diciendo que `:app` es el template y que no depende de nada**, y
  el check de dirección de dependencias que ese documento pide por escrito sigue sin existir.
- **`DictionarySource` está implementado dos veces en los tests**, uno por source set.

**Costos aceptados, no olvidos** (D-087). El Tile y la Complication del template **quedan
exportados y visibles**: instalado el APK, el reloj ofrece *"Example tile"* que dice "Hello,
Tile!" y una complication con el día de la semana en inglés, y `UPDATE_PERIOD_SECONDS = 3600`
despierta la app cada hora para recalcularlo. R8 queda apagado. Los dos se plantearon con su
costo y se eligieron así.

---

## 2026-09-17 — Un pack entra al reloj con un comando, y es atómico porque un push no lo es

**Qué.** `tools/devpack.py`: sideload de packs por adb para desarrollo (`install`, `list`, `rm`,
`devices`). Reemplaza los cuatro comandos copiados a mano que vivían duplicados en dos archivos.
No es el instalador —ese sigue bloqueado en dónde se hostea el catálogo— es la capa de
desarrollo, igual que Hatch lo es del gate. **Cero código en `:app`.** Nueva D-082.

**Áreas.** `tools/devpack.py` (nuevo), `tools/packbuilder/tests/test_devpack.py` (nuevo),
`pyproject.toml`, `tools/CLAUDE.md`, `app/CLAUDE.md`, `app/src/main/assets/.gitkeep`,
`docs/decisions.md` (D-082), `docs/architecture.md`, `docs/roadmap.md`, `.gitignore`,
`.claude/skills/pack-workflow/SKILL.md`.

**Por qué.** Pedido: formas fáciles de pasar y actualizar packs por adb en modo desarrollador.
Al mirarlo apareció que la receta documentada no era sólo incómoda: **copiaba directo sobre el
`.db`**, así que un push cortado dejaba un pack truncado — que se abre sin error y devuelve menos
palabras de las que tiene, el síntoma que este repo no puede observar.

**Arquitectura.** ✅ Cumple. Reusa la convención `.part` + `mv` de `PackStore.instalarAtomico` en
vez de inventar otra; stdlib pura (D-045); el gate no necesita Hatch (D-046). Una desviación de
convención local, no de decisión: usa `argparse` —son subcomandos con flags— mientras el resto
de `tools/` parsea `sys.argv` a mano. Es stdlib, así que D-045 se sostiene.

**Medido** (emulador `wear_api33`, API 33, adb 1.0.41 / 37.0.1):

- **`adb shell` es binary-clean por stdin.** 1 MiB aleatorio ida y vuelta: sha256 idéntico. Era
  la ASSUMPTION que decidía el mecanismo y ahora no lo es.
- **Las dos rutas tardan lo mismo** sobre el pack real de español (68,9 MiB): **0,73–0,88 s** por
  el pipe contra **0,80–0,92 s** por `/data/local/tmp` + `cp`. O sea: **el tiempo no decide nada;
  el pico de disco decide todo** — 1× contra 2× (590,2 MiB transitorios para el inglés).
- **`verify_pack.py` cuesta 3,42 s** sobre el pack de español, no minutos. **Mató mi propia
  justificación**: había escrito en el plan que era caro y por eso iba detrás de un flag. Sigue
  detrás del flag, pero por la razón correcta —pertenece al build del pack, no a la instalación—
  y el docstring ahora lleva el número en vez del adjetivo.
- **99 tests de Python en el gate**, contra 71. Los 28 nuevos son todos de lógica pura.
- **End-to-end con el pack real**: `install --verify` → sha256 ok → la app abre y buscar `cor`
  lleva a **correr** (verbo) y **corriente** (sust.). Miré la pantalla, no el exit code.

**Qué salió mal.**

- **`communicate()` después de cerrar `stdin` a mano revienta** con *"flush of closed file"*, y
  reventó **a mitad de una instalación real**. No lo agarró ningún test: la capa que ejecuta adb
  no tiene cobertura y no la puede tener en el gate. Lo agarró correrlo.
- **Ese crash fue la mejor evidencia de la sesión.** Dejó `toy-es-en.db.part` y **ningún `.db`**:
  la invariante de atomicidad demostrada por accidente, que es la forma en que de verdad se
  comprueba.
- **Escribí el paso `chmod` y su test en ese orden**, que es justo lo que este repo evita. Lo
  nombro como lo que es. Salió de mirar los permisos reales: el pipe deja 0666 y la extracción
  del APK deja 0600, y los dos caminos tienen que dejar el mismo archivo.
- **Tres chips "ES" en pantalla.** La app **re-extrae la demo en cada arranque**, así que su
  `pack_id` (`toy-es-en`) colisiona para siempre con el toy pack, y el selector muestra sólo
  `langSource`: N packs de español son N chips idénticos. El check de colisión lo detecta al
  instalar, pero **no puede evitar lo que la app se re-extrae sola**.
- **`hatch run lint:check` ya estaba rojo** antes de esta sesión: 6 hallazgos en `test_build.py`
  y `test_source_kaikki.py`. **El lint no está en el gate**, así que nadie lo vio. No los toqué.

**Lo que corrige al changelog anterior.** *"Los packs y los dumps ya no están en disco"* es falso
para los packs: **siguen en el emulador**, en `/data/local/tmp` — `en-def-wikt.db` (309.424.128 B)
y `es-def-wikc.db` (72.212.480 B), 367 MiB en total. De ahí salió el pack real con el que se
verificó todo esto, sin volver a bajar el dump. **Le ahorra ~7 minutos a la próxima sesión.**
Y de paso: esos 367 MiB colgados **son** el pico de disco de 2× volviéndose permanente, que es
exactamente el fallo que el paso `rm-tmp` previene.

**Qué quedó sin hacer.**

- **Nada corrió en un reloj físico**, y el pico de disco —el número que eligió el mecanismo—
  es precisamente lo único que sólo importa ahí. El emulador no puede cerrarlo (D-043).
- **El pack de inglés (295,1 MiB) nunca se transfirió.** Throughput y pico a ese tamaño siguen
  sin medir; lo de acá es una extrapolación desde 68,9 MiB y está dicho como tal.
- **La rama de sha256 que no coincide nunca se ejercitó en device**, sólo por test puro. No
  encontré forma honesta de inyectar corrupción sin trucar el propio código.
- **La capa que ejecuta adb no tiene ni un test** y no lo va a tener en el gate. Lo puro entra,
  lo demás se comprueba corriéndolo contra un emulador y mirando.
- **El `pack_id` de la demo sigue siendo `toy-es-en`** con nombre `demo-es-en.db`. Arreglarlo
  toca la decisión abierta de qué contenido tiene la demo (roadmap), así que no lo toqué.
- **`docs/architecture.md` sigue desactualizado** en lo demás (dice que `:app` es el template);
  sólo le agregué la fila que mi cambio necesitaba.

---

## 2026-09-17 — El pack de inglés pesa 295 MiB, y por eso el APK dejó de llevar diccionarios

**Qué.** Se agregó el diccionario de inglés y la app pasó a soportar dos packs con selector de
idioma. Medir el inglés cambió la arquitectura de distribución: **el APK ya no lleva
diccionarios reales**, sólo un pack de demostración de 53 KB. Eso cierra D-071 antes de tiempo
y abre D-081.

**Áreas.** `tools/packbuilder/sources/kaikki.py` y `build_pack.py` (renombrados y
parametrizados), `tools/packbuilder/verify_pack.py`, `tools/packbuilder/tests/`,
`app/src/main/java/cl/fadiaz/dictionary/data/` (`PackSet.kt` nuevo, `PackStore.kt`),
`app/src/main/java/cl/fadiaz/dictionary/presentation/` (las cuatro),
`app/src/test/` y `app/src/androidTest/`, `tools/audit_dictionary.py`,
`docs/decisions.md` (D-071 revertida, D-076 a D-080), `docs/formato-pack.md`,
`docs/roadmap.md`, `app/CLAUDE.md`.

**Por qué.** Pedido: bajar el diccionario de inglés, incluirlo en la build, y **evaluar cuánto
pesa y qué complejidad suma** para decidir. La decisión se tomó con los números en la mano y
cambió dos veces en el camino, que es para lo que servía medir.

**Arquitectura.** ✅ Cumple, y **retira una desviación**: D-071 pasa a revertida.

**Medido.**

- **Inglés: 956.150 entradas, 309.424.128 bytes (295,1 MiB)**, build de 180,6 s y 290 MB de RSS,
  desde un dump de 3.244.676.342 B. Comprimido: 184,7 MiB (sólo 37 %, contra 50 % del español,
  porque casi todo su peso son payloads ya comprimidos).
- **Sin nombres propios: 792.680 entradas, 266.711.040 bytes.** Los 163.470 topónimos y
  apellidos cuestan **40,7 MB, 13,8 %**.
- **Mató la creencia que yo mismo había afirmado horas antes.** Dije que un pack pesa porque el
  47 % son conjugaciones de verbos. **Eso es una verdad del español, no una general**: en inglés
  `form` es el **7 %** y `entry` el 48 %. El inglés pesa porque tiene **6,5× más entradas**
  (956.150 contra 146.194), no por morfología.
- **Busqué redundancia lossless en `form` y no existe**: sólo el 0,7 % (10.940 filas, 0,2 MB) es
  prefijo de su lema y por lo tanto redundante con la búsqueda por prefijo. Podar por
  divergencia ≥4 ahorraría 13,6 MB a cambio de que **602.681 formas dejen de resolver**.
- **La poda resultó estructural, no del idioma**: se apoya en los tags `form-of` de wiktextract,
  iguales en todos los dumps. No había una sola heurística comparando contra texto español, así
  que agregar inglés no necesitó una fuente nueva — sólo un `Perfil` de calibración por idioma.
- **El pack español reconstruido con el pipeline generalizado sale idéntico**: 146.194 entradas,
  9.372.800 bytes de payload, 72.212.480 en disco.
- **APK: 84 MB con el pack adentro, 50 MB sin él.**
- **25 tests JVM** en el gate (7 de `PackStore`, 18 del ViewModel) y **19 de pantalla** en
  dispositivo. Los checks del audit siguen en 15.
- **Verificado en el emulador**: el APK sin packs dice *"No hay ningún diccionario instalado."*;
  con los dos packs empujados por `adb`, el selector muestra **EN / ES** y arranca en **EN**
  porque el emulador está en inglés — el fallback al idioma del reloj funcionando, no el
  alfabeto.

**Qué salió mal.**

- **Saqué la extracción desde assets y la volví a poner en la misma sesión.** No fue indecisión
  mía: el pedido cambió a la mitad —primero "ningún pack en el APK", después "un mini-pack de
  ejemplo"— y la segunda vez el código volvió **más simple**, sin extracción perezosa, porque
  con 53 KB diferir no compra nada. Lo que sí fue error: al borrarla dejé un `assetsDePack`
  huérfano que reapareció como *conflicting overloads* al restaurar.
- **Un `str.replace` sin `assert` no aplicó y no avisó.** La escotilla de escape del selector
  —la fila *"Buscar en \<idioma\>"*— nunca se insertó, y el archivo compiló igual. Lo agarró el
  test de pantalla. Todos los demás reemplazos de la sesión llevaban `assert`; ese no.
- **Corté un archivo de tests con índices invertidos y dupliqué medio archivo.** El compilador
  dijo "conflicting overloads" y hubo que reconstruirlo a mano.
- **`verify_pack.py` falló contra un pack correcto.** La comprobación de FTS exigía que la
  entrada estuviera en el **top 30** por bm25, y en inglés la entrada de mejor rank es "you", su
  glosa empieza con "The people spoken…", y "people" aparece en 890 definiciones: estaba en la
  posición 721. **Confundía indexado con rankeado.** Se corrigió a comprobar pertenencia sin
  `LIMIT`, y el modo de falla real (D-011, `fts_def.rowid` desalineado) sigue cubierto.
- **Juzgué el orden de resultados del inglés sobre un piloto donde las palabras de prueba no
  estaban.** `work`, `time`, `people` y `run` no cayeron en la muestra 1/20, así que el
  "desorden" que reporté era en su mayoría artefacto del muestreo. Lo detecté antes de escribirlo
  en un documento, pero se lo había dicho al usuario primero.
- **Propuse un mockup de densidad sin hacer la aritmética** (sesión anterior, mismo patrón):
  acá el equivalente fue estimar el peso del inglés por regla de tres sobre el tamaño del dump.
  Me negué a dar el número antes de medir, y menos mal: la extrapolación ingenua daba ~165 MB y
  el real es 295.

**Qué quedó sin hacer.**

- **El orden de resultados en inglés no está evaluado.** El perfil `en` baja `forms_cap` de 80 a
  12 porque un verbo inglés trae cuatro formas y no 137, pero **ese 12 es a ojo, no medido**. La
  verificación que falta es la que en español destapó `perro` en la posición 619: que `hous`,
  `wor`, `tim` devuelvan `house`, `work`, `time`. **Es la deuda más importante que deja esta
  sesión.**
- **La búsqueda en inglés nunca se vio en la app.** El selector sí se verificó con los dos packs
  reales; las capturas de la búsqueda se perdieron al limpiarse el scratchpad antes de mirarlas.
- **Los packs y los dumps ya no están en disco** (4,7 GB de dumps, 380 MB de packs). Reconstruir
  el inglés cuesta ~4 min de descarga y ~3 min de build.
- **El instalador de packs quedó planificado, no construido.** Se extendió su entrada del
  roadmap con lo que la sesión hizo decidible: los números de compresión (el español baja al
  49,9 %, el inglés sólo al 62,6 %, porque su peso ya está deflateado), que el 45 % del pack
  inglés es derivable y podría no viajar, y que un diff binario entre versiones no va a ser chico
  porque los rowids se corren. Y un hueco que manda sobre el diseño: **no existe ningún hash del
  archivo entero**, así que una descarga truncada abriría y devolvería menos palabras.
- **Qué contenido debería tener el pack de demo está sin decidir.** Hoy lo genera
  `build_toy.py` —el fixture de los tests— y eso acopla lo que ve un usuario recién instalado a
  un archivo que se cambia por razones de test: pasó en esta misma sesión, agregándole `sol` y
  `soler`. Queda en el roadmap con el candidato obvio: las N entradas de mejor rank del pack
  español.
- **`docs/architecture.md` sigue desactualizado** —dice que `:app` es el template y que no
  depende de nada— y **el check de dirección de dependencias entre módulos que ese mismo
  documento pide sigue sin existir**. Estaba en el plan de esta sesión y no se hizo.
- **Nada corrió en un reloj físico**, y menos con 295 MiB.

---

## 2026-09-17 — Las pantallas: 13 tests primero, y el diseño salió de medir la pantalla

**Qué.** Las tres pantallas se rediseñaron y ganaron 13 tests instrumentados, escritos **antes**
del rediseño. D-031 pasa de no tener enforcer a tener tres. La corona rotatoria queda cableada.

**Áreas.** `app/src/androidTest/` (nuevo), `app/src/main/java/cl/fadiaz/dictionary/presentation/`
(las tres pantallas), `app/build.gradle.kts`, `tools/audit_dictionary.py`, `app/CLAUDE.md`,
`docs/decisions.md` (D-073 a D-075, y D-031 cerrado), `docs/roadmap.md`.

**Por qué.** Pedido: diseñar las interfaces y testear las pantallas para cerrar el MVP que va al
reloj.

**Arquitectura.** ✅ Cumple. D-026, D-072 (las pantallas están exentas de la regla y el test lo
aprovecha: no arman un `DictionarySource`), y `app/CLAUDE.md` sobre voz primero.

**Medido, y el diseño salió de ahí.**

- **El presupuesto es de 192×192 dp** (384 px a 320 dpi). Con los 48 dp mínimos de área tocable
  que pide Wear OS, **entran tres filas**. El diseño anterior, de dos líneas y ~74 dp de paso,
  daba dos.
- **Un lema puede tener 96 caracteres**: los refranes son entradas del Wikcionario. De ahí la
  fila de una línea con elipsis (D-073).
- **Las acepciones: mediana 3, p90 7, máximo 47** sobre las 3.000 entradas de mejor rank. El
  tope de 3 con `Ver más (N)` deja intacta la mitad de las entradas y evita el muro: `poner`
  tiene 24 y en pantalla dice *Ver más (21)* (D-074).
- **Los ejemplos: mediana 64 caracteres, p90 228, máximo 917.** Por eso van en secundario y más
  chicos: a igual peso que la glosa, uno solo entierra la acepción siguiente.
- **13 tests de pantalla, 7 en rojo antes del rediseño.** Al terminar, 13 verdes, más los 17 JVM
  y los 25 de `:dict-data`.
- **El check nuevo del audit se probó fallando**: se renombró `license` en la pantalla de
  atribución y el audit rompió. Las decisiones sin enforcer bajan de 13/71 a **12/72**.

**Qué salió mal.**

- **El mockup que acordamos prometía cinco resultados por pantalla y no entran.** Ni cinco ni
  cuatro: la aritmética de 192 dp con 48 dp de área tocable da tres. Lo descubrí cuando el test
  de densidad falló **después** del rediseño, no al diseñarlo — es decir, dibujé el mockup sin
  hacer la cuenta. La ganancia real es de ~40 % más filas por pantalla, no del doble. El test
  quedó con el número medido y el comentario explica por qué no puede subir.
- **Tres de las siete fallas iniciales eran bugs míos en los tests**, no huecos de diseño:
  esperaba "verbo" para un sustantivo, un matcher ambiguo que también agarraba el campo de
  texto, y un `performClick` sobre un nodo que estaba fuera de una lista perezosa. Los corregí
  como bugs de test y lo dije, porque corregir un test para que pase es exactamente lo que no
  hay que hacer sin nombrarlo.
- **`allWarningsAsErrors` —agregado la sesión anterior— pagó dos veces el mismo día**: agarró
  `createComposeRule` deprecado (hay que usar la v2, que corre con `StandardTestDispatcher`) y
  `rememberActiveFocusRequester` deprecado al cablear la corona.
- **Casi duplico `FakeDictionary`** copiándolo a `androidTest`. No hacía falta: las pantallas son
  funciones del estado. Borré la copia antes de escribir el primer test.
- **Perdí tres intentos capturando pantallas**: `input keyevent 4` cierra la app si el teclado no
  llegó a abrirse, y el tap cambia de coordenada según el estado. Ya había pasado la sesión
  anterior y volvió a pasar.

**Qué quedó sin hacer.**

- **La corona rotatoria está cableada y nunca se movió.** El emulador no acepta input de corona
  por `adb` (`Unknown command: rotaryencoder`), así que lo único verificado es que compila contra
  la API documentada. En un reloj puede estar invertida, ser demasiado sensible o no tener foco.
  **Es lo primero a mirar cuando el MVP llegue al reloj.**
- **El ejemplo de 917 caracteres sigue siendo un muro** con las acepciones desplegadas. La
  opción que lo resolvía —ejemplos detrás de un toque— se evaluó y no se tomó.
- **No hay paleta propia**: se usan los defaults de Wear Material3. Elegir colores sin un reloj
  delante es decidir a ciegas sobre contraste y consumo.
- **Los 13 tests de pantalla no corren en el gate.** El gate ve la lógica de `:app` y que la
  pantalla de atribución exista, no los pixeles.
- El Tile y la Complication siguen siendo los del template.

---

## 2026-09-17 — El gate empieza a ver `:app`, y el primer test encontró un bug real

**Qué.** `:app` pasó de cero tests a **17 en el gate**, y para eso hubo que volverlo testeable:
`SearchViewModel` recibe `abrirPack` en vez de construirlo desde un `Context`, y el resultado de
abrir un pack es `PackLoad`, un tipo sin Android. El gate gana además `allWarningsAsErrors` en
`:app` y un enforcer nuevo (D-072) que impide que la regresión vuelva.

**Áreas.** `app/src/test/java/` (3 archivos nuevos),
`app/src/main/java/cl/fadiaz/dictionary/presentation/SearchViewModel.kt`,
`app/src/main/java/cl/fadiaz/dictionary/presentation/MainActivity.kt`,
`app/src/main/java/cl/fadiaz/dictionary/data/PackStore.kt`,
`app/src/main/java/cl/fadiaz/dictionary/data/PackLoad.kt` (nuevo), `app/build.gradle.kts`,
`gradle/libs.versions.toml`, `tools/audit_dictionary.py`, `app/CLAUDE.md`,
`docs/decisions.md` (D-072, y D-031 gana enforcer parcial), `docs/roadmap.md`.

**Por qué.** Pedido explícito, con la razón adelante: enfoque test-driven, porque agregar tests
después sale caro. Esta sesión pagó esa factura y la deja documentada.

**Arquitectura.** ✅ Cumple. D-072 es la misma forma que D-017 usa para `:dict-core`, por un
motivo distinto: allá es portabilidad, acá es poder correr el test en el gate.

**Medido.**

- **17 tests JVM, en milisegundos** (10 del ViewModel, 7 de la instalación del pack), dentro de
  `./gradlew check`. Antes el gate no ejecutaba una sola línea de `:app`.
- **El primer test escrito encontró un bug de verdad, y se lo vio fallar antes del arreglo:**
  escribir mientras el pack carga dejaba la búsqueda **muerta**. `source` era un `var`, así que
  la consulta salía contra `null`, devolvía vacío y **nada la volvía a intentar**: el usuario veía
  "Sin resultados" hasta borrar una letra. El test falló con `expected:<[per]> but was:<[]>`.
  Arreglado haciendo del pack un flow y combinándolo con la query. **Verificado también en el
  emulador**: con los datos borrados, escribí "per" durante la extracción y los resultados
  aparecieron solos al terminar.
- **Los otros 9 tests pasaron contra el código viejo**, que es exactamente lo que los vuelve
  tests de **caracterización** y no TDD. Están nombrados así, uno por uno.
- **Los enforcers nuevos se probaron fallando**, no pasando: se rompió a propósito la limpieza
  del temporal y `unaCopiaQueSeCortaNoDejaUnPackAMedioEscribir` cayó; se metió un
  `import android.content.Context` en el ViewModel y `check_app_logic_is_jvm_testable` rompió el
  audit. El audit pasa de 13 a **14 checks**.
- **`allWarningsAsErrors` en `:app` está verificado activo**, no solo escrito: mi primera sonda
  —una función privada sin usar— **no emitió warning y el build pasó**, lo que casi me deja
  afirmar que la bandera funcionaba sin evidencia. Con una llamada deprecada de verdad
  (`String.capitalize()`) el compilador responde `e: warnings found and -Werror specified`.

**Qué salió mal.**

- **Escribí el test y el arreglo en la misma edición**, que es justo lo que TDD evita. Lo
  deshice: revertí el arreglo a mano, corrí los tests, vi al de la carrera fallar solo, y recién
  ahí lo restauré. El resultado es el mismo; la evidencia de que el test tiene dientes, no.
- **Casi doy por buena una bandera que no había comprobado.** Ver arriba: la primera sonda no
  generaba warning y el `BUILD SUCCESSFUL` se lee idéntico a "la bandera no está puesta".
- **`assertTrue(x is T)` no hace smart cast en Kotlin**; `assertIs<T>(x)` sí, porque tiene
  contract. El primer intento no compilaba por eso.
- **`kotlin-test` solo no alcanza** en un módulo Android: hace falta `kotlin-test-junit`, porque
  los unit tests de AGP corren sobre JUnit 4.

**Qué quedó sin hacer.**

- **Las tres pantallas siguen sin un solo test.** Nada comprueba que dibujen lo que el estado
  dice, y eso incluye la atribución: el enforcer de D-031 quedó **parcial** —fija que el estado
  lleva la licencia del pack, no que la pantalla la muestre—. Entrada nueva en el roadmap.
- **Los tests de UI no entran al gate** cuando existan: necesitan dispositivo, como los 25 de
  `:dict-data`. El gate seguirá sin ver la capa visible de la app.
- **`:dict-data` sigue sin tests JVM.** Es deliberado —necesita SQLite y FTS5 reales— pero
  significa que la deduplicación de la cascada, escrita ayer, solo la cubre un test instrumentado.
- **Nada corrió todavía en un reloj físico.**

## 2026-09-17 — El MVP corre en el emulador, y el orden de la lista dejó de ser inusable

**Qué.** `:app` dejó de ser el template: tres pantallas —búsqueda, entrada, atribución— sobre el
pack real de 146.194 entradas, empaquetado en el APK. Antes hubo que arreglar lo que el pack real
había dejado a la vista: el orden de los resultados, que hacía inusable cualquier búsqueda.

**Áreas.** `dict-data/src/main/kotlin/cl/fadiaz/dictionary/data/SqlitePackSource.kt`,
`dict-data/src/androidTest/` (3 tests nuevos),
`tools/packbuilder/sources/toy.py`, `tools/packbuilder/verify_pack.py`,
`tools/packbuilder/build_pack.py` *(entonces `build_es.py`)*, `tools/packbuilder/tests/test_build.py`,
`app/src/main/java/cl/fadiaz/dictionary/` (5 archivos nuevos), `app/build.gradle.kts`,
`app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/data_extraction_rules.xml`,
`gradle/libs.versions.toml`, `app/CLAUDE.md`, `docs/decisions.md` (D-068 a D-071),
`docs/roadmap.md`.

**Por qué.** Pedido: probar todo en el emulador y encaminar el MVP para subirlo al reloj.

**Arquitectura.** ✅ Cumple, con **una ⚠️ Desviación registrada** (D-071, abajo). D-026 (la
búsqueda vive en la app), D-031 (la atribución sale de `meta` y se muestra), D-043 (nada de
rendimiento se midió en el emulador), y la regla de `app/CLAUDE.md` de voz-primero.

**Medido.**

- **El orden viejo era inusable, y el número lo dice mejor que yo**: con el orden alfabético,
  `per` ponía `perro` en la **posición 619 de 782**; `sal` → `salir` en la 206; `dec` → `decir`
  en la 154. La lista muestra 30.
- **El orden nuevo** `(exacta, rank, norm)` con deduplicación: `per` devuelve *perder, permitir,
  perseguir, permanecer, perro*. **Verificado en el emulador contra el pack real**, no solo en
  escritorio: la captura muestra exactamente esos cinco.
- **Lo que cuesta:** el covering index sigue sirviendo el rango pero ya no el orden, así que
  SQLite agrega `USE TEMP B-TREE`. **1,8 ms p95** en el peor caso (prefijo de una letra, 22.358
  filas) contra 0,01 ms del orden viejo, presupuesto 20 ms. **Escritorio. El número de reloj no
  existe** (D-043) y es el que puede revertir esta decisión.
- **La deduplicación NO se hizo con `GROUP BY`**, y es medición: `GROUP BY headword, pos` cuesta
  **7,9 ms p95** con una letra —4× más— y encima **no saca los duplicados que se ven**, que
  difieren en `pos`. Over-fetch ×3 y `distinctBy` en Kotlin cuesta 1,8 ms y sí los saca.
- **El APK debug pesa 84 MB.** El asset comprime bien —72.212.480 → 36.004.318 B, 50 %— así que
  los otros ~48 MB son tooling de debug. **El release no se midió** y además tiene R8 apagado.
- **25/25 instrumentados** en API 33 y API 37.0, con el toy pack en 26 entradas.

**Qué salió mal.**

- **El pack real que construí ayer no se podía abrir en Android.** Le puse
  `data_version = "2026-09-15"` y `PackFile.parseMetadata` le hace `.toInt()`. `verify_pack.py`
  dio **verde**: solo comprobaba que la clave existiera. Es la falla exacta que este repo
  intenta no tener —el builder lo escribe, el validador lo aprueba, revienta en el reloj— y el
  hueco era del enforcer, no solo mío. `verify_pack.py` ahora verifica que las tres claves
  enteras lo sean (D-070). Lo encontré leyendo `Model.kt` para escribir el ViewModel, **no** por
  un test, y eso es suerte, no método.
- **Deduplicar solo en `byPrefix` no alcanzaba**: el nivel tolerante volvía a meter la entrada
  que el prefijo había fundido. Lo agarró el test instrumentado, que es donde tenía que
  agarrarlo (D-069).
- **Los comentarios de bloque de Kotlin anidan**, cosa que yo no tenía presente: un `/*` dentro
  de un KDoc —escribí `filesDir/packs/` seguido de un comodín— abre un comentario nuevo y el
  cierre del KDoc cierra ese, dejando el archivo entero comentado. El error que sale es
  *"Unclosed comment"* en la última línea, que no apunta a nada.
- **Tres intentos para escribir texto en el emulador.** `input keyevent 111` no cierra el IME de
  Wear, lo escribe: la query terminó siendo "per by". El que sirve es `keyevent 4`.
- **Escribí "Extraccion" sin tilde** en la atribución del pack, y es texto que el usuario ve en
  la pantalla de licencia. Se vio en la captura, no en un test; corregido y pack reconstruido.
- **Volví a abreviar una ruta con puntos suspensivos** en §Áreas y el check de punteros muertos
  volvió a rechazarla, igual que en la entrada anterior. Segunda vez en el día. Y la tercera fue
  escribiendo *esta misma línea*: puse la ruta abreviada como ejemplo, entre backticks, y el
  check la contó como puntero. El enforcer no distingue una mención de un enlace, y tiene razón
  en no intentarlo.
- **Un commit no era verde por si solo, y lo agarro `git worktree`, no yo.** El commit del MVP
  pasaba el gate en mi arbol y fallaba en un checkout limpio: `app/CLAUDE.md` apunta a
  `app/src/main/assets/`, que esta gitignoreado y **solo existe si ya copiaste el pack**. En mi
  maquina el directorio estaba; en un clone, no. Se arreglo con un `.gitkeep` que ademas explica
  como generar el pack, y hubo que rehacer los dos ultimos commits. Es exactamente el caso por
  el que `CLAUDE.md` pide verificar con worktree en vez de asumirlo.
- **Los commits de la tanda anterior:** intenté partir el arreglo de orden y el de `norm`/`fuzzy`
  en dos commits y tuve que desandarlo. Comparten dos archivos y sus filas de `decisions.md` se
  intercalan; separarlos obligaba a partir documentos por bloque para un corte que no era una
  dependencia real. Quedaron en uno, que es lo que eran.

**Qué quedó sin hacer.**

- **`:app` no tiene un solo test.** Ni unitario ni instrumentado. Todo el MVP se verificó
  mirando la pantalla y sacando capturas. Es la deuda más grande que deja esta sesión, y la que
  vuelve frágil todo lo de arriba.
- **Nada corrió en un reloj físico.** Sin eso no hay arranque, ni latencia, ni batería, y la
  decisión de orden queda apoyada en un número de escritorio.
- **Los instrumentados siguen corriendo contra el toy pack de 53 KB.** Un plan de consulta que
  se degrada a 146.194 entradas no lo ve ningún test; lo vi yo, a mano, una vez.
- **El proxy de `rank` favorece a los verbos** —las formas pesan y un verbo trae hasta 222— así
  que `cas` devuelve *castigar, cascar, casar* antes que `casa`. Bajar ese peso cuesta un rebuild
  de 54 s y no se probó.
- **El prefijo de una letra sigue siendo malo**: `a` devuelve *a, A, -a, a-, á*.
- **D-031 sigue sin enforcer.** La pantalla de atribución existe, pero nada impide que alguien la
  borre y el gate siga verde.
- El Tile y la Complication siguen siendo los del template, y el `UPDATE_PERIOD_SECONDS = 3600`
  heredado sigue por debajo de lo que pide la guía oficial.

**⚠️ Desviación (D-071).** El pack viaja como asset del APK y se extrae a `filesDir/packs/` en el
primer arranque. Eso lo duplica en disco —36,0 MB comprimidos en el APK más 69 MB extraídos— que
es **el mismo costo por el que se descartó Room** (D-039). No hay alternativa técnica:
`BundledSQLiteDriver.open()` recibe un *path* y un asset vive dentro del zip. Se planteó el costo
antes de construir y se eligió igual, para que instalar la app deje un diccionario funcionando
sin `adb`. Se revierte cuando exista el instalador; mientras tanto `PackStore` prefiere siempre
lo que haya en `filesDir/packs/`, así que un `adb push` gana y permite iterar sin rearmar el APK.

---

## 2026-09-17 — El pack real de español: 72,2 MB, y leerlo destapó que la lista no sirve

**Qué.** Se construyó el **primer pack real** del proyecto, que era el item 3 del roadmap y el
último de los tres desbloqueantes. Fuente nueva `sources/kaikki.py` (entonces `kaikki_es.py`; la poda,
dos pasadas, streaming) y `build_pack.py` con `--sample` para pilotos. **146.194 entradas,
72.212.480 bytes.** De paso se corrigió un bug de orden que solo era visible con un pack real, y
el pack sube a `schema_version = 3`.

**Áreas.** `tools/packbuilder/sources/kaikki.py` y `build_pack.py` (nuevos ese día, con los
nombres `kaikki_es.py` y `build_es.py`; se generalizaron al agregar inglés),
`tests/test_source_kaikki.py` (nuevo), `tests/test_build.py`, `indexes.sql`, `schema.sql`,
`build.py`, `verify_pack.py`,
`dict-data/src/main/kotlin/cl/fadiaz/dictionary/data/SqlitePackSource.kt`, `PackFile.kt`,
`PlatformAssumptionsTest.kt`, `docs/formato-pack.md`, `docs/decisions.md` (D-063 a D-067),
`docs/roadmap.md`, `.claude/skills/pack-workflow/SKILL.md`.

**Por qué.** Pedido: seguir con lo siguiente pendiente del roadmap. Era el item 3, y su razón de
ser era que D-028 (50 MB blandos) **no tenía ninguna medición detrás**.

**Arquitectura.** ✅ Cumple. D-015 (la app nunca parsea fuentes crudas), D-034 (monolingüe: el
pack no lleva `trans`), D-031 (`license` y `attribution` en `meta`). El cambio de índice toca
D-012 y por eso sube `schema_version`, que es el mecanismo que D-001 prevé para esto.

**Medido.** Cinco mediciones, y **tres mataron una creencia**:

- **El pack: 146.194 entradas, 72.212.480 bytes (68,9 MiB)** — 44 % por encima de D-028. Build
  53,9 s, 214 MB RSS. Dump de kaikki.org del 2026-09-15 (1.423.631.693 B, 1.036.458 senses en
  854.460 registros). **El 46,3 % del pack es `form`**: 33,4 MB, 1.487.695 filas, 93,5 %
  conjugaciones de verbos. Un verbo trae hasta 222 formas.
- **La poda descarta el 82,33 %** de los registros (703.506 páginas de forma flexionada).
- **Mató la creencia nº1: una pasada alcanzaba.** El lema trae su conjugación en `forms`
  (`amigar`: 137 formas, `amigo` entre ellas), así que recolectar las páginas form-of parecía
  redundante. Medido: cubren el **92,31 %** de las palabras-forma y **7,66 % —53.708 palabras—
  se perdían**, entre ellas "palpitaciones", "curvilínea", "animalito". Dos pasadas (D-065).
- **Mató la creencia nº2: el 19,35 % de entradas-basura se podía descartar.** Hay páginas de
  forma sin el tag `form-of` ("Participio de escribir"), y existe una señal buena para
  agarrarlas: `tags: [form-of]` a nivel de registro, 28.414 casos. Medido: **1.341 de esas
  palabras (4,72 %) no llegan a ningún lema por ningún otro camino**. Se quedan (D-066).
- **Mató la creencia nº3, y es la importante: que la lista de resultados estaba resuelta.**
  Se leyó el pack, no se contaron filas, y escribir `per` no muestra `perro`: sale en la
  **posición 619 de 782**. `salir` en la 206, `decir` en la 154, `comer` en la 131. La lista
  muestra 30. El orden es `(norm, rank)` —alfabético primero— y con 22 entradas de juguete eso
  era invisible. **Ordenando por `(rank, norm)` con el proxy de D-067, `perro` sube a la
  posición 5** y `hac`/`com` encabezan con `hacer` y `comer`. Está en el roadmap como entrada
  nueva; **no se cambió**, porque choca con D-012 y la decisión es de producto.
- **El bug de orden que sí se corrigió** (D-063): el prefijo ordenaba `rank DESC` con `rank`
  definido como "menor es más común". En el pack real, `escrit` devolvía *escrito / Participio
  de escribir* (rank 994) **antes** que el sustantivo (988). Afecta a 375 de 7.265 norms en el
  piloto. Y corregir solo la consulta no alcanzaba: el test nuevo del plan mostró que dejaba
  `USE TEMP B-TREE FOR LAST TERM OF ORDER BY`, o sea que dejaba de ser consulta de cobertura.
  Índice y consulta se dieron vuelta juntos.
- **22/22 instrumentados en API 33 y API 37.0**, con el toy pack reconstruido a
  `schema_version = 3`. Es lo que cierra el cambio de índice: `PlatformAssumptionsTest` pinea el
  `EXPLAIN QUERY PLAN` **en el dispositivo**, así que la consulta nueva se verificó donde el
  plan lo decide el SQLite de cada imagen, no el de mi máquina.

**Qué salió mal.**

- **Escribí un número antes de medirlo, en un comentario de test**: "99,4 % de cobertura" del
  `forms` de los lemas. El valor real era 85,86 % por pares y **92,31 % por palabra distinta**,
  y la primera cuenta que hice también estaba mal (contaba pares, no palabras, y daba un
  catastrófico 7,66 % → 4,72 % mal atribuido). Lo agarré releyendo mi propia salida, no un test.
- **Otro número mal extrapolado llegó a estar escrito en tres archivos**: "77,2 % de páginas de
  forma", medido sobre los primeros 400.000 registros y presentado como si fuera del dump
  entero. El valor real es **82,33 %**. Corregido en los cuatro lugares antes de commitear.
- **El primer diseño de la fuente era de una pasada y el test lo codificaba.** El test falló
  —correctamente— y la respuesta no fue borrarlo sino medir la premisa. Ese es el caso donde
  test-first pagó: si hubiera escrito el código primero, el test habría ratificado la pérdida
  de 53.708 palabras como comportamiento esperado.
- **Perdí dos intentos leyendo el pack** porque `meta.payload_dict` está guardado en hex:
  `decompress()` no falla, devuelve texto que parece corrupto y manda a cazar un bug del codec
  que no existe. Es exactamente el síntoma de D-008 pero con causa distinta. Quedó documentado
  en el `pack-workflow` skill, que es donde se busca.
- **`verify_pack.py` falló contra el pack real** por dos entradas legítimas: "h" y "H", la letra.
  `fuzzy("h")` es vacío porque la hache es muda, y el check trataba `norm` vacío y `fuzzy` vacío
  como el mismo problema. Separados (D-064). El test que lo acompaña es de **caracterización**:
  el builder ya se comportaba bien, lo que estaba mal era el check.

**Qué quedó sin hacer.**

- **El pack real no se abrió nunca en un emulador ni en un reloj.** Los 22 instrumentados siguen
  corriendo contra el toy de 53 KB. Un pack de 146.194 entradas es donde un plan de consulta se
  degrada, y eso hoy no lo ve nadie. Es lo más barato que queda y lo más cerca de un bug real.
- **El orden de la lista queda roto a propósito**, con la aritmética en el roadmap. Cambiarlo
  toca D-012 y necesita decidir qué pasa con los 3.137 headwords repetidos (`hacer` sale cinco
  veces, por etimología).
- **D-052 sigue sin ajustar.** Los umbrales del nivel tolerante ya se pueden medir contra este
  pack; no se hizo. El vecindario tolerante de "aser" trae 200 candidatos, que es mucho.
- **El `sense_key` usa un ordinal posicional**, no el `id` de kaikki. Se verificó que kaikki
  **sí** trae `id` por acepción —cerrando una ASSUMPTION de `decisions.md`— pero parece derivado
  del contenido, así que editar una glosa probablemente lo cambia. Falta medirlo contra dos
  dumps de fechas distintas.
- **El pack real no está commiteado ni publicado** (72 MB): vive en el scratchpad de la sesión.
  Dónde se hostea sigue siendo la decisión de producto que bloquea el instalador.

**Fricción, tercer golpe del mismo item.** `build_pack.py` es un comando nuevo y **no se pudo
agregar a `CLAUDE.md` §Comandos**: el archivo está en 199 de 200 líneas. Fue a
`pack-workflow/SKILL.md`, que es un hogar defendible, pero la decisión la tomó el presupuesto y
no el criterio. El item de §Proceso y herramientas —que `check_root_budget` diga *cuál* sección
creció— ya estaba propuesto por los dos golpes del 2026-09-17; este es el tercero, y el primero
donde la consecuencia no es tiempo perdido sino **una línea de documentación que no se escribió**.

## 2026-09-17 — El método salta de v0 a v7: header, loop de sesión y el digest como enforcer

**Qué.** Se actualizó el método de trabajo con agentes de la versión **0** a la **7** (lineage
`m-7c41a9`, digest `dee484b4cc29`). El archivo único que vivía en `docs/agents/`,
`bootstrap-prompt.md`, quedó reemplazado por el set de cuatro documentos `prompt-{context,evaluate,bootstrap,update}.md`, y
—la parte que importa— **nuestro header se llevó adelante**: `adopted: 2026-09-17`, siete
entradas en `adapted` y dos en `declined`, que antes no existían en ninguna parte porque v0 no
tenía header donde escribirlas.

De los 33 deltas entre v0 y v7: **22 aplicados, 5 ya los teníamos, 2 adaptados, 1 aplazado,
3 declinados.** Lo aplicado, como ediciones reales:

- **`CLAUDE.md`** — §*Cómo corre una sesión* nueva: el brief de apertura, el trabajo ajeno que no
  se arrastra, las preguntas juntas y cotizadas en unidades de este repo, mirar el output, y el
  cierre con captura incondicional. Regla **test-first** en §Verificación. Los dos documentos
  nuevos del método en el mapa.
- **`docs/roadmap.md`** — los **cinco estados** del ledger, con leyenda, y `**Estado.**` en las
  doce entradas. La comprobación en reloj pasa a *A medias* con sus dos mitades separadas.
  Área nueva **§Proceso y herramientas**, con su primer item.
- **`docs/architecture.md`** — la tabla **qué cambiaste → qué se mueve**, en los sustantivos de
  este repo: once filas, de `norm()` al changelog.
- **`docs/decisions.md`** — sección nueva *El método de trabajo con agentes*, D-059 a D-062.
- **`tools/audit_dictionary.py`** — `check_method_digest`, el enforcer de D-059.
- **`pack-workflow`** — paso nuevo: abrir el pack y leerlo, con las tres consultas y los dos
  silencios que hay que distinguir.
- **`state-review`** — preguntas 7 (¿el método sigue siendo el que decimos seguir?) y 8 (los
  smells, chequeables en un minuto).
- **El formato del changelog** gana *Qué salió mal* y *Qué quedó sin hacer*.

**Áreas.** `docs/agents/` (los cuatro archivos del set), `CLAUDE.md`, `docs/roadmap.md`,
`docs/architecture.md`, `docs/decisions.md`, `tools/audit_dictionary.py`,
`.claude/skills/pack-workflow/SKILL.md`, `.claude/skills/state-review/SKILL.md`,
`.claude/logs/agent-changelog.md`.

**Por qué.** Pedido explícito: actualizar el repo con los cambios del método. La copia nueva ya
estaba en el árbol, staged, encima de la vieja — así que el prune había corrido antes que el
triage, que es exactamente el orden que `prompt-update.md` intenta evitar.

**Arquitectura.** ✅ Cumple. Principio 19 gobernó la adopción: **seis archivos extendidos, cero
creados.** Ninguna guarantee del método se quedó sin casa, y ninguna forma de este repo se
renombró para parecerse al método — por eso `adapted` tiene siete filas.

**Medido.**

- **El digest del set: `dee484b4cc29`**, recalculado a mano con el procedimiento que el propio
  método publica (concatenar `prompt-*.md` en orden de nombre, sacar los bloques `yaml` del
  header, sha256, 12 hex). Coincide con el declarado ⟹ el header es confiable y el set está
  completo. Se verificó **de nuevo** después de escribir nuestro header, porque el header se
  excluye del cálculo: sigue dando `dee484b4cc29`.
- **`check_method_digest` se hizo fallar a propósito** antes de darlo por bueno: con una línea
  de más en `prompt-evaluate.md` reporta `declara dee484b4cc29 y el contenido da e13af87e3ab7`.
  Restaurado el archivo, vuelve a silencio. Un check que solo se vio pasar no se midió.
- **Los principios 1–13 son textualmente idénticos** entre v0 y v7 salvo anonimización: 51
  líneas de diff sobre 250, todas reemplazo de sustantivos propios. Es lo que sostiene tratar
  nuestra copia sin header como versión 0 de esta lineage y no como un documento distinto.
- **El gate estaba rojo al empezar**, y no por el código:

  ```
  FALLA  documento apunta a un archivo inexistente:
         .claude/logs/agent-changelog.md -> docs/agents/bootstrap-prompt.md
  ```

  Lo detectó el enforcer que este repo ya tenía, y es el breakage que §*The prune* llama el más
  común que causa un update: el puntero muerto.
- **`CLAUDE.md`: 158 → 199 de 200 líneas.** Queda **1 línea** de margen y el aviso de cercanía
  al límite ahora salta.

**Qué salió mal.** El primer intento dejó `CLAUDE.md` en **204 líneas** y lo agarró
`check_root_budget`, no yo. Al comprimir quedó en 201 — todavía roto — y recién el tercer intento
entró. Lo que finalmente dio margen no fue recortar prosa nueva sino **borrar una duplicación
vieja**: las tres líneas de Hatch en §Comandos ya estaban, mejor explicadas, en `tools/CLAUDE.md`.
Estaban duplicadas desde el bootstrap y nadie las había visto. La lección quedó como el primer
item de §Proceso y herramientas, porque la fricción se repitió dos veces en la misma sesión.

Segundo error, más silencioso: el header se escribió primero con las entradas de `adapted` sin
comillas, y una decía `troubleshooting layer is split: skill ...`. Un `: ` adentro de un escalar
plano de YAML lo convierte en **mapping**, no en string: el header habría parseado distinto de
como se lee. No había PyYAML para detectarlo, así que se citaron las catorce entradas a mano.

**Qué quedó sin hacer.**

- **`CLAUDE.md` queda en 199 de 200.** La próxima regla que se agregue choca. Hay margen real
  —§Comandos y §Verificación tienen más duplicación con los skills— pero buscarla es una revisión
  aparte, no parte de un update.
- **El item de §Proceso y herramientas está propuesto, no ejecutado**, por la disciplina 3 del
  principio 17: que `check_root_budget` diga *cuál* sección creció es una mejora de proceso y le
  toca al humano agendarla.
- **`upstream` quedó vacío** en el header: no se dijo de dónde vino esta copia. Si vuelve a
  llegar una versión nueva por el mismo camino, conviene anotarlo.
- **Nada se le mandó todavía a la lineage de arriba.** Cuatro candidatos pasaron el generality
  test y viven solo en el reporte de esta sesión: el veredicto arquitectónico por entrada de
  changelog; la tabla de vectores compartida como enforcer de paridad entre dos
  implementaciones; `—` distinguido de *(medición, no mecanismo)* en la columna de enforcer; y
  que un check de punteros muertos tiene que eximir al roadmap. No hay adónde mandarlos hasta
  que exista un canal hacia `m-7c41a9`.

**Qué se declinó, y por qué.** §*Three agents, one source* (v7): este repo es single-agent a
propósito y su asimetría ya es la política de acá — el invariante lo sostienen el hook, los
vectores y el gate, no la prosa. Se reabre si aparece el archivo de reglas de un segundo agente
(D-060). §*Workspaces* (v3): un solo repositorio; se reabre con el segundo. §*Models, reasoning
levels and cost* y la anonimización del worked example son internos del método y no implican
edición acá.

**El prune.** Un solo candidato: `bootstrap-prompt.md`, leído **entero** (986 líneas) antes de
dejar que la borradura quedara. Sus ocho secciones están cubiertas por el set nuevo, siete de
ellas como superset; **no contenía una sola línea sobre este repositorio**, así que no hubo nada
que mover afuera. Un enlace entrante, en la entrada del bootstrap de este mismo changelog: la
frase se conservó porque es cierta —esa sesión siguió ese archivo— y se le agregó qué pasó a ser.

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
