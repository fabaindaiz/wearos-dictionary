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

*Actualizado: 2026-09-17.*

**Hecho y verificado en escritorio.** El motor de búsqueda (`:dict-core`, **47 tests**) y el
pipeline de packs (`tools/`, **101 tests**) están completos y en el gate, junto con los **85 JVM
de `:app`** y **18 checks** de auditoría estructural. El pack de juguete pasa
todas las invariantes de `verify_pack.py`, incluido que el prefijo use `COVERING INDEX`.

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

**Hay dos diccionarios y el MVP los usa.** Español (146.194 entradas, 68,9 MiB) e inglés
(956.150 entradas, **295,1 MiB**), con selector de idioma. **El APK sólo lleva el pack de
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
  se distingan. Si eso se revisa, se revisa ese test primero.

### Composición entre packs

**Estado.** Planificado. El join key ya está decidido y medido (D-055 a D-058); falta construirlo.

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

**Estado.** **A medias**, y la mitad que falta cambió. Lo hecho (2026-09-17): densidad,
jerarquía tipográfica, los estados de carga/instalación/error, el tope de acepciones y la corona
rotatoria (D-073 a D-075). Lo que falta ya no es la base sino lo que sólo se decide con un reloj
puesto:

- **La corona está cableada pero nunca se movió.** El emulador no acepta input de corona por
  `adb` (`Unknown command: rotaryencoder`), así que lo único verificado es que compila contra la
  API documentada. En un reloj puede estar invertida, ser demasiado sensible, o no tener foco.
- **El ejemplo de 917 caracteres sigue siendo un muro.** Va en secundario y más chico, pero con
  las acepciones desplegadas un solo ejemplo largo todavía empuja la siguiente fuera de pantalla.
  La opción que lo resolvía —ejemplos detrás de un toque— se evaluó y no se tomó.
- **No hay paleta propia**: se usan los defaults de Wear Material3, que están pensados para OLED.
  Elegir colores sin un reloj delante es decidir a ciegas sobre contraste y consumo. La barra de
  búsqueda ya dejó de ser indistinguible de una fila (D-092), pero eso es un borde, no una paleta.
- 🔴 **El presupuesto de 192 dp puede estar equivocado, y es la moneda de cambio de cinco
  decisiones.** Medido en el reloj del proyecto (SM-L715F, 2026-09-18): `wm size` da **498×498 px**
  y `wm density` da **340**, o sea **234 dp** — **22 % más pantalla** que los 192 dp sobre los que
  se justificaron D-073, D-075, D-078, D-084 y D-085. A 48 dp de área tocable eso da margen para
  una **cuarta fila**, que son un 33 % más de resultados sin bajar del mínimo de Wear OS.
  **Falta confirmarlo dentro de la app** con `LocalConfiguration.screenWidthDp`: `wm density` es
  la densidad física y Compose puede ver otra. Hasta entonces los 192 dp siguen escritos en cinco
  lugares y `PantallasTest.entranTresResultadosSinScrollear` mide contra el dispositivo que haya.
  Este repo ya se equivocó una vez con esta aritmética: el mockup prometía cinco filas y entraban
  tres.

Voz, lista de resultados, corona rotatoria, Tile, Complication.

**Con qué choca.** Con D-026: la búsqueda vive dentro de la app porque los tiles no aceptan text
input, así que la superficie glanceable necesita un propósito propio, no ser un atajo a lo mismo.

**Decidido el 2026-09-19.** Los dos: un tile de últimas palabras y uno de palabra del día
(D-106). Ninguno abre un pack —`onTileRequest` es main thread con 10 s de tope— así que los dos
leen lo que la app deja escrito en `SharedPreferences`.

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

**Estado.** **Defecto abierto, encontrado el 2026-09-19** construyendo los tiles. No lo crean los
tiles: lo ponen en la carátula del reloj, que es donde se vería.

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

Lo que cuesta, y por qué no es obvio: construir FTS5 sobre 956.150 entradas y dos índices en una
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

**Estado.** Planificado, y ahora **es lo único que separa al release de estar optimizado**.
La firma ya está (D-086) y el release compila: **35 MB sin firmar**, contra 50 del debug — el
primer número de release que existe en este repo. R8 sigue apagado por decisión, no por herencia
(D-087): activarlo necesita la comprobación en dispositivo que O-2 siempre pidió.

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

**Estado.** Planificado, y **ya no espera al pack real: está pesado**. 72,2 MB contra un
presupuesto blando de 50 (D-028). Sigue esperando el número de latencia de O-1 para saber qué
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
está en una tabla que no se puede tocar sin romper la búsqueda por forma flexionada. Si 72 MB
resulta inaceptable, la palanca real es **de producto, no de formato**: cuántas de las 146.194
entradas se envían. Las 32.305 de `pos = name` (apellidos y topónimos, 22 % de las entradas) son
el primer candidato a mirar, y nadie decidió todavía si un diccionario de muñeca las quiere.

**Con qué choca.** Con D-028 (50 MB blandos) y con las tres decisiones abiertas de
`docs/decisions.md`. Ninguna se puede cerrar sin el número de latencia de O-1.

**Por qué importa para la batería y no solo para el disco.** El pack se descarga por red, que es
lo que más gasta. Cada MB que se ahorra es tiempo de radio que no se paga.

### O-4. Batería

**Estado.** **Bloqueado afuera.** No hay medición de batería que valga sin reloj físico (D-043). Lo reabre conseguirlo.

Medir con **el power metric de Macrobenchmark, Perfetto o el Power Profiler**. No con Battery
Historian: la documentación oficial dice que ya no se mantiene.

Los tres consumidores reales, en orden:

1. **La descarga del pack.** Mitigado por D-029 (cargando + Wi-Fi), pero sin medir.
2. ~~**La superficie glanceable.**~~ **Cerrado el 2026-09-19.** La complication se apagó —que es
   la primera opción que nombra la guía, *"disable automatic refresh"*— y con ella los 24
   despertares diarios. Los dos tiles nuevos no programan refrescos: el de historial va con
   `freshnessIntervalMillis = 0` (el sistema no lo llama) y se empuja desde la app; el de palabra
   del día emite un `Timeline` de siete ventanas de reloj de pared y el renderer cambia solo
   (D-107). **Lo que sigue sin medirse es cuánto ahorra eso en batería**, porque O-4 sigue sin
   una sola medición en el reloj.
3. **La pantalla durante la búsqueda.** El `debounce` de 120 ms y la cancelación con `mapLatest`
   ya están diseñados para no trabajar de más, pero nunca se midieron en un reloj.

**Qué hay que decidir antes.** Nada de la superficie glanceable: ya está decidida. Lo que falta
es el reloj y un profiler.

### O-5. Animaciones y trabajo en el hilo de UI

**Estado.** Planificado. Depende de que exista una interfaz; hoy no existe.

La guía oficial pide minimizar animaciones y, si hay un loop, dejar una pausa al menos tan larga
como la animación.

**Con qué choca.** Con nada todavía: la UI no existe. Esta fase entra junto con el diseño de la
interfaz, no después — rehacer animaciones ya escritas es más caro que no escribirlas mal.

---

## Comprobación que falta y bloquea el ship

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
(*"ya había pasado la sesión anterior y volvió a pasar"*), y 2026-09-18 (swipe, taps y `input
text`). **Tercer golpe**, y el primero donde el costo no fue tiempo sino casi un diagnóstico
equivocado.

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
