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

## Dónde estamos

*Actualizado: 2026-09-20.*

**Hecho y verificado en escritorio.** El motor de búsqueda (`:dict-core`, **81 tests**) y el
pipeline de packs (`tools/`, **250 tests**) están completos y en el gate, junto con los **259 JVM
de `:app`** y **24 checks** de auditoría estructural — **614 tests en total**. Los **43
instrumentados** (34 de `:dict-data` y 7 de `:app`) el gate no los corre: necesitan dispositivo, y
son los únicos que cierran las asunciones sobre Android. El pack de juguete pasa todas las
invariantes de `verify_pack.py`, incluido que el prefijo use `COVERING INDEX`.

⚠️ **Los 41 instrumentados corrieron el 2026-09-20 a 234 dp y en pantalla redonda**, sobre el
emulador de D-150: **0 fallas**. Es la primera vez que corren en la geometría del reloj — hasta
entonces el AVD por defecto los corría a 192 dp y en una pantalla cuadrada.

**Los packs, al cerrar el 2026-09-20.** De una fuente por idioma se pasó a cuatro en español:

| | entradas | tamaño | fuentes |
|---|---|---|---|
| `es-def-wikc-tat-wn-wd` | **152.281** | 75,2 MB | Wikcionario · Tatoeba · MCR/WordNet · Wikidata |
| `en-def-wikt-wn` | **956.150** | 315,5 MB | Wiktionary · Open English WordNet |

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

En orden. Cada una es barata y habilita varias de las de abajo.

| # | Qué | Por qué primero | Bloquea a |
|---|---|---|---|
| 1 | ~~**Correr los tests instrumentados** en el emulador~~ **HECHO 2026-09-17** | Convirtió el comportamiento en Android de ASSUMPTION a verificado: 20/20 en API 33 y API 37.0 | ~~Todo lo que toque el reloj~~ desbloqueado |
| 2 | ~~**Decidir el join key entre packs**~~ **HECHO 2026-09-17** | Se midió y se decidió: `entry.uid`, columna aparte sin índice (D-055 a D-058). Ya está en el formato | ~~Composición, y el pack real~~ desbloqueado |
| 3 | ~~**Construir el pack real y pesarlo**~~ **HECHO 2026-09-17** | Se midió: **146.194 entradas, 72,2 MB**, un 44 % por encima del presupuesto blando de D-028. Y leerlo destapó el problema de orden de abajo | ~~O-3 y el alcance del producto~~ desbloqueados; O-2 y O-4 siguen esperando el reloj |

---

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

### La calidad del contenido del pack español

**Estado.** **En gran parte hecho** (2026-09-20). De una sola fuente se pasó a **cuatro**, y lo
que queda ya no es contenido sino tamaño.

| Fuente | Qué aportó | Estado |
|---|---|---|
| Wikcionario (base) | definiciones, formas, sinónimos, antónimos, relacionadas | ✅ y **agotada**, medido |
| Tatoeba | **6.499** entradas ganan frase de uso | ✅ `--frases` (D-137) |
| WordNet / MCR | **+3.801** entradas ganan sinónimos | ✅ `--tesauro` (D-144) |
| enwiktionary §Spanish | 307 entradas ganan ejemplo | ✅ `--ejemplos` (D-135), rinde 21× menos |

El pack quedó en **146.193 entradas y 73,6 MB**, con **21,0 %** de entradas con sinónimos. ⚠️ **Y
ahí está la tensión nueva**: el presupuesto blando de D-028 son 50 MB. No filtrar (D-141) y sumar
fuentes empujan en la dirección contraria. Ver §O-3.

**Lo que ya se hizo, para no repetirlo.** Salieron los nombres propios (22,1 % de las entradas,
26.265 de ellas definiendo sólo *"Apellido."*), entraron 71.609 sinónimos por acepción en
26.369 entradas, después los antónimos (D-126), y se limpiaron las 665 etiquetas de mantenimiento
del wiki. En 2026-09-20 entraron las **palabras relacionadas** (D-132): hiperónimos, hipónimos y
`related`, que eran el último campo aprovechable que la fuente traía y el builder tiraba —
**5.395 entradas en español (4,7 %) y 90.310 en inglés (11,4 %)**, por +112 KB y +1,4 MB. El pack
quedó en **114.619 entradas y 68,3 MB**.

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

**Estado.** **Medido, sin decidir** (2026-09-20). Es el mismo problema con tres caras distintas, y
por eso conviene tenerlo en un solo lugar.

**El problema.** Una fuente externa da sinónimos, antónimos o ejemplos **por acepción** — pero por
**su** acepción, no por la nuestra. Nada en el dato dice cuál de nuestras acepciones corresponde.

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

> **El selector de idioma NO es composición**, y conviene no confundirlos. El selector elige
> **un** pack y busca en él (D-078); la composición hace que un pack auxiliar le **sume**
> información a la misma entrada de otro. `SearchRepository` sigue sin existir.

Que un pack de sinónimos y uno de traducciones puedan sumar información **a la misma entrada**
del pack de definiciones.

**El join key ya está decidido y construido** (D-055, 2026-09-17): `entry.uid`, una columna
aparte, hash de `(lang_src, NFC(headword), pos, sense_key)`. `entry.id` sigue siendo el rowid
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

### Reorientar el esquema a monolingüe

**Estado.** Planificado. Lo que falta es **contenido de decisión**, no mecanismo: qué pasa con `trans`.

D-034 fijó que el primer pack es monolingüe con definiciones. Falta que el código lo refleje.

**Con qué choca.** Con `trans`, que en un pack monolingüe se definió como "las palabras que
aparecen en la glosa" — que es exactamente lo que `fts_def` ya indexa, mejor. En monolingüe esa
tabla es espacio gastado dos veces.

**Qué hay que decidir antes.** Si `trans` se vuelve opcional (solo bilingüe) o desaparece del
todo. Y si el toy pack pasa a ser monolingüe, hay que **conservar uno bilingüe mínimo**: sin él,
la búsqueda inversa y el tope `TRANS_MAX_PER_KEY` quedan sin test.

---

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

**Estado.** **Hecho**, y creció con cada pantalla: **47 tests instrumentados** de Compose en
`:app`.

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

**Qué sigue faltando, y es lo que más importa.**
- **El orden de resultados en inglés no se evaluó.** El proxy de `rank` está calibrado para
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

### Qué contenido tiene el pack de demostración

**Estado.** Planificado, y **es una decisión de producto, no de mecanismo**. El mecanismo está
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

**Estado.** **A medias** (2026-09-18). Hecho: el inicio con palabra del día y ajustes, el
versionado del APK y el refactor de componentes. Falta: las acciones de una palabra y la búsqueda
con opciones.

**El inicio NO es una pantalla propia, y esa fue la decisión que reorientó todo** (D-096). La guía
de Wear OS pide *"shallow and linear: avoid hierarchies deeper than two levels"* y elevar la
acción primaria; un menú que enruta a la búsqueda la hunde un toque. El inicio quedó siendo el
estado vacío de la búsqueda, que es lo que ya era, con palabra del día y ajustes agregados. Eso
además cerró D-091: el botón de volver de una entrada tiene un solo destino posible.

**Lo que falta, en orden de costo.**

- **Las acciones de una palabra**: los dos botones de arriba en un `ButtonGroup` —lado a lado
  cuestan 48 dp, igual que uno; apilados costarían 96— y un menú con *ver en el otro idioma*,
  *favoritos* y *copiar*. Wear Material3 **no tiene menú desplegable ni overflow**, verificado
  contra la referencia de API: las dos formas soportadas son `AlertDialog` (el overload sobre
  `TransformingLazyColumn` que trajo 1.6) o empujar una pantalla de lista.
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

**Estado.** **Medición cerrada, rediseño sin empezar** (2026-09-19).

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

**Estado.** **Diseñado y medido, sin construir** (2026-09-20). Decisión del usuario: *«que los
packs muy grandes, en lugar de reducirse, se pueda evaluar dividirlos funcionalmente para poder
instalar las partes que uno quiere»*. Lo que sigue es el diseño con precio; **nada de esto está
implementado**.

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

**Estado.** **Encontrado construyendo, sin decidir** (2026-09-20).

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

**Estado.** Planificado. Lo que falta es **mecanismo**, salvo una cosa que es de producto y la
bloquea: dónde se hostea el catálogo.

**No hay "importar a la base de datos", y conviene decirlo primero** porque es la confusión
natural. El pack **es** la base de datos: un SQLite inmutable que se abre read-only (D-001).
Instalar no es un ETL — es *verificar y renombrar*. Room quedó descartado justamente porque
`createFromFile()` **copia** el archivo (D-039), y `instalarAtomico` ya existe con esa forma:
se escribe un `.part` y recién al final se renombra, porque **un pack a medio escribir se abre
sin error y devuelve menos palabras de las que tiene**.

#### El hueco que manda sobre todo lo demás

**Hoy no existe ningún hash del archivo entero.** `meta.payload_dict_sha256` cubre sólo el
diccionario de compresión de 32 KB (D-008); `PackFile.open` valida `schema_version`,
`norm_version` y `payload_codec`, todos en las primeras páginas del archivo. Una descarga
truncada o corrompida más allá de esa zona **abre igual y devuelve menos resultados**, que es
exactamente el bug que este repo no puede observar.

Así que el catálogo tiene que declarar `sha256` y `bytes` de cada `.db`, y la instalación no
puede terminar sin comprobarlos. Es el requisito número uno, antes que cualquier optimización.

#### Lo que ya está decidido y no se rediscute

| | |
|---|---|
| Transporte | `HttpURLConnection`, que hace `Range` y progreso sin sumar un byte al APK (D-040) |
| Cuándo | Diferido a **cargando + Wi-Fi**, con WorkManager (D-029) |
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

El reloj se desconectó después de la primera subida, así que **lo que se le instaló es de antes de
D-147**. Los packs están al día; **la app está 13 decisiones atrás (D-147 a D-159)**.

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

- **Las previews de los tiles al agregarlos** (D-149). Agregar un tile es un gesto del usuario y
  no se hace por `adb`; es lo único de esa decisión que queda sin ver.
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

**Estado.** Planificado. Su mitad de rendimiento está **bloqueada afuera**: necesita un reloj físico (D-043).

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

**Estado.** Planificado, y **es el ítem que más se movió hoy, en la dirección mala**.

| | antes | hoy |
|---|---|---|
| español | 68,3 MB | **73,6 MB** |
| inglés | 272,4 MB | **315,5 MB** |

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

**El recorte obvio no existe.** Las tres opciones abiertas de `docs/decisions.md`
(`detail=none`, `columnsize=0`, bloques vs fila) juntas atacan el 13,8 % del pack; el 46,3 %
está en una tabla que no se puede tocar sin romper la búsqueda por forma flexionada.

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

**Lo que falta, y es lo único que decide el resto.** Correr `dumpsys batterystats` en el reloj para
saber si ese 9,4 % es pantalla o CPU. Todo el árbol de estrategias cuelga de esa respuesta y está
escrito en el documento, con el protocolo.

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

**Estado.** Planificado. Depende de que exista una interfaz; hoy no existe.

La guía oficial pide minimizar animaciones y, si hay un loop, dejar una pausa al menos tan larga
como la animación.

**Con qué choca.** Con nada todavía: la UI no existe. Esta fase entra junto con el diseño de la
interfaz, no después — rehacer animaciones ya escritas es más caro que no escribirlas mal.

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

La forma de trabajar está bajo las mismas reglas que el código: tiene fricción, la fricción se
mide, y casi siempre es lo más barato de arreglar del proyecto. **Se rankea acá, contra las
features, por la misma persona y en la misma sentada** — que es el único lugar donde la
comparación es honesta. Un log de fricción aparte es un archivo que nadie abre.

El umbral es el **segundo golpe**: la primera vez va al changelog de la sesión, la segunda sube
acá con la aritmética. Una molestia sola es ruido; la segunda es un dato.

### Verificar a ojo en el emulador cuesta más que el cambio que se verifica

**Qué pasa ahora.** No hay forma fiable de llevar la app a un estado concreto sin un humano
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

### No hay forma repetible de preguntarle al pack si su CONTENIDO es bueno

**Qué pasa ahora.** `verify_pack.py` comprueba **invariantes**: que los índices existan, que
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

### La auditoría dice que `CLAUDE.md` se pasó, pero no qué sección creció

**Qué pasa ahora.** `check_root_budget` falla con *"¿Qué sección creció?"* y no lo responde. Hay
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
