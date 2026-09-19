# app

App Wear OS, **corriendo en un reloj físico** sobre el pack real de 146.194 entradas.

Las pantallas viven en `presentation/`: búsqueda (que además **es el inicio**), entrada,
atribución, ajustes, gestión de diccionarios y guardadas. `data/PackStore.kt` es lo único que
sabe de dónde sale un pack.

La superficie glanceable son **dos tiles** —últimas palabras y palabra del día— y **ninguno abre
un pack**: los dos leen `SharedPreferences` (D-106). La complication del template se apagó.

Las reglas de acá son preventivas: son caras de descubrir tarde.

## El test va primero, y acá eso tiene una condición técnica

`:app` nació sin tests y retrofitearlos costó un refactor. **La causa no fue pereza**:
`SearchViewModel` extendía `AndroidViewModel` y construía el pack desde un `Context`, así que
no había forma de correrlo en la JVM. Un test que necesita dispositivo no entra al gate, y uno
que no entra al gate no se corre.

De ahí la regla, que es D-072 y la enforcea el audit: **la lógica de `:app` no importa
`android.*`**. Lo que necesita Android entra por parámetro —`SearchViewModel` recibe `abrirPack`,
no un `Context`— y la frontera es `PackLoad`, un tipo sin Android por el que pasa un fake.

Las **pantallas están fuera de la regla** como código —un Composable es Android por definición—
pero **ya no como tests**: corren con Robolectric en la JVM y **sí entran al gate** (D-110). De
los 47, **46 corren así en 21 s**; el único que no es tocar una palabra dentro de una glosa, que
depende del layout de texto real.

⚠️ **Robolectric corre en SDK 36, no en 37**, que es el nivel del reloj: llega hasta ahí
(`app/src/test/resources/robolectric.properties`). Lo que dependa de API 37 sigue necesitando
dispositivo — y este proyecto ya tuvo un caso, la inyección de input que obligó a fijar espresso
3.7.0 (D-093).

Esos tests no arman un `DictionarySource`: las pantallas son funciones del estado, así que el
estado se construye a mano. Si alguna vez una pantalla necesita un fake, es señal de que se le
metió lógica que debería estar en el ViewModel.

## El presupuesto es de 192 dp — y puede estar equivocado

La pantalla son 384×384 px a 320 dpi, o sea **192×192 dp**, y la guía de Wear OS pide 48 dp
mínimos de área tocable. Eso da **tres filas y nada más**, medido. Cada dp que gasta el chrome
es un resultado que el usuario no ve, y de ahí salen D-073 (lista de una línea, 48 dp) y D-075
(la entrada de texto se colapsa cuando hay resultados).

🔴 **Pero el reloj del proyecto mide 234 dp**, no 192: `wm size` da 498×498 px y `wm density` da
340. Son **22 % más pantalla** y los 192 dp son la moneda con la que se justificaron cinco
decisiones. Falta confirmarlo **dentro de la app** con `LocalConfiguration.screenWidthDp`, porque
`wm density` es la densidad física y Compose puede ver otra. Hasta entonces, cualquier
arquitectura que gaste dp se cotiza contra 192 y se anota la duda.

Si alguien baja de 48 dp para meter una cuarta fila, el test de densidad **sigue pasando** y lo
que se rompe es el área tocable. Por eso el mínimo vive en una constante con nombre.

```sh
./gradlew :app:testDebugUnitTest         # 154 tests JVM, las pantallas incluidas
./gradlew :app:connectedDebugAndroidTest # 7 tests que sí necesitan dispositivo
./gradlew :app:releasePrecheck           # hay keystore para firmar? dice que falta
./gradlew :app:assembleRelease           # 35 MB; sin keystore sale SIN FIRMAR, no rompe
```

Lo que cubren es lo que **no da error**: resultados de una consulta vieja pisando a la actual,
una consulta por pulsación drenando la batería, la búsqueda muerta mientras el pack carga, y un
pack a medio copiar —que se abre sin quejarse y devuelve menos palabras de las que tiene.

## El inicio es la búsqueda, y eso es deliberado

No hay pantalla de menú. La guía de Wear OS pide jerarquías de **como mucho dos niveles** y
elevar la acción primaria; un inicio que enruta a la búsqueda la hunde un toque. Así que el
estado vacío de `SearchScreen` **es** el inicio: palabra del día, voz, texto, historial,
guardadas y ajustes (D-096). Ajustes es el único segundo nivel, y de ahí cuelga la gestión de
diccionarios.

Tres reglas que salieron de mirarlo en pantalla, no de razonarlo:

- **Un ítem que llega asincrónico no se inserta arriba de todo.** La palabra del día tarda 32
  lecturas; para cuando llega, la lista ya se asentó, y como los ítems tienen `key` conserva su
  posición — insertada en el índice 0 aparecía **fuera de pantalla**. Va debajo del encabezado.
- **Todo `item` lleva `key`.** Sin identidad estable, un ítem que cambia de posición se destruye
  y se recompone, y eso se llevaba el foco del campo de texto y el teclado con él (D-089).
- **Dos botones van en un `Row`, no apilados**: lado a lado cuestan 48 dp, apilados 96 (D-100).

## Borrar un diccionario: el orden es el contrato

Se cierran **todas** las conexiones antes de tocar el disco, y recién después se recarga el set
(D-104). No es precaución teórica: en Unix un archivo borrado con un descriptor abierto sigue
ocupando el disco, así que el usuario vería *"borrado"* y cero espacio liberado. **Medido**:
libre 9.802.568 kB → con el pack de 72,2 MB, 9.732.048 kB → tras borrar, 9.802.568 kB otra vez.

El pack de demostración **no se puede borrar**: viene en el APK y `PackStore.open` lo re-extrae
al reabrir, así que el botón no haría nada.

## Un tile no abre un pack, y no es una opinión de rendimiento

`onTileRequest` está anotado **`@MainThread`** y *"must complete after at most 10 seconds"*. Está
en el javadoc de `tiles 1.6.2`, así que abrir ahí un `.db` de 69 o 295 MB está descartado **por
escrito**, sin necesidad de medir nada. Lo enforcea `check_tiles_dont_open_packs`.

Lo que un tile necesita **lo deja escrito la app** en `SharedPreferences`: el historial ya guarda
`headword` y `pos` desnormalizados, y la palabra del día se adelanta una semana porque es
determinista por (fecha, pack) (D-097). Los dos tiles son adaptadores sobre `TileContenido.kt`,
que es puro y se prueba en el gate.

**El freshness interval no es reloj de pared.** Verbatim: *"elapsed time (not wall clock time)"*,
e *"inexact"*. Para que la palabra cambie a medianoche se usa un `Timeline` con ventanas de
`TimeInterval`, que sí son epoch (D-107). Y `freshnessIntervalMillis = 0` significa que el sistema
**no** vuelve a llamar al tile: el de historial depende de que la app lo empuje con
`requestUpdate`.

**No declares `androidx.wear.tiles.GROUP`** en ninguno de los dos: *"tile providers in the same
group represent the same tile on the device"*, y los fundirías en uno.

## Tiles y widgets no aceptan text input

La búsqueda vive **obligatoriamente dentro de la app**. La superficie glanceable sirve para word
of the day, últimas búsquedas o un shortcut al search — no para buscar.

## `androidx.glance:glance-wear-tiles` está prohibido

Deprecado y será removido. El naming confunde: **no** es la librería de Wear Widgets. Si
buscás cómo hacer un Tile con Glance, ese es el resultado que vas a encontrar y es el
equivocado.

Lo que se usa hoy: `androidx.wear.tiles` + `androidx.wear.protolayout`.

## Wear Widgets está pospuesto, no descartado

Wear OS 7 trae Wear Widgets (Glance + RemoteCompose) como evolución de los full-screen Tiles.
No se usan todavía porque `androidx.glance.wear:*` y `androidx.compose.remote:*` están en alpha
con packages moviéndose entre releases, y solo existen en Wear OS 7.

La migración futura es adaptar el `mainSlot` del tile a un widget 2x2, diseñada para ser directa.

## Input

Voz vía `RecognizerIntent` como camino principal, teclado como fallback. Eso no es una
preferencia de producto: es la razón por la que la búsqueda tiene un nivel tolerante a errores —
el dictado por voz produce entradas que no coinciden exactamente con ningún lema.

Implementado así en `SearchScreen`. El teclado es `BasicTextField` de compose foundation y no un
componente de Wear Compose **porque Wear Compose no trae campo de texto**: la librería asume que
el input entra por voz o por el activity del sistema. Es el teclado, además, el que ejercita la
búsqueda incremental — el `debounce` de 120 ms y el `mapLatest` de `SearchViewModel` no hacen
nada con la voz, que entrega la frase entera de una vez.

El `RecognizerIntent` pide `EXTRA_LANGUAGE = "es"` explícitamente. Sin eso el reconocedor usa el
idioma del sistema, y un reloj en inglés dictando "perro" devuelve cualquier cosa.

## Costos aceptados del MVP (D-087)

Ya no son herencia del template: se decidieron, con el costo sobre la mesa.

- **R8 está desactivado.** La guía oficial de Wear OS lo nombra como una de las dos palancas
  principales, pero activarlo reintroduce la clase de bug que sólo aparece en release y va atado
  a una comprobación en dispositivo que todavía no se hizo. Roadmap O-2.
- ~~El Tile y la Complication del template~~ **Cerrado el 2026-09-19** (D-106 a D-109): hay dos
  tiles de diccionario y la complication se apagó, con ella los 24 despertares diarios.
- Restos menores sin tocar: `app_name` = "Dictionary" en inglés con la UI en español; el permiso
  `WAKE_LOCK` declarado y nunca usado; `ic_launcher_round` presente pero sin `android:roundIcon`,
  en un reloj redondo.

## Firmar el release

La keystore **vive fuera del repo** y `local.properties` guarda sólo su ruta (D-086). Sin
keystore configurada el release sale **sin firmar en vez de romper**, porque un clone limpio
tiene que seguir compilando.

```sh
./gradlew :app:releasePrecheck   # dice qué falta y el keytool para generarlo
```

## Rendimiento

Muchos relojes tienen CPU y GPU bastante más limitadas que un teléfono. Minimizá animaciones, y
si hay un loop dejá una pausa al menos tan larga como la animación.

Medí en **reloj físico**, nunca en el emulador: el emulador sirve para correctitud, no para
rendimiento. Ver el `benchmark` skill.

## Descargas

Se difieren a **cargando y con Wi-Fi**, con WorkManager. Es la guía oficial de Wear OS, y con
packs de decenas de MB no es opcional.

Excluir los packs del backup con `android:dataExtractionRules`. Con `minSdk 33`,
`fullBackupContent` **no aplica**: es el mecanismo para Android 11 e inferiores. Hecho:
`res/xml/data_extraction_rules.xml` excluye `packs/` de cloud-backup y de device-transfer.

## Los packs no viajan en el APK

El APK lleva sólo un **pack de demostración** de 53 KB (D-081). Los diccionarios de verdad viven
en `filesDir/packs/` y entran por `tools/devpack.py`; cuando exista el instalador, escribirá en
ese mismo directorio y la app no va a notar la diferencia.

```sh
python3 tools/packbuilder/build_pack.py es <kaikki-es.jsonl> es-def-wikc.db
python3 tools/devpack.py install es-def-wikc.db   # o: hatch run push es-def-wikc.db
python3 tools/devpack.py list                     # qué quedó en el reloj
python3 tools/devpack.py rm es-def-wikc           # para probar la degradación
```

**No lo hagas con `adb push` a mano.** Copiar directo sobre el `.db` no es atómico: si el push se
corta queda un pack truncado, y un pack truncado **se abre sin error y devuelve menos palabras de
las que tiene**. `devpack.py` escribe a `.part`, compara sha256 de los dos lados y recién ahí
renombra — lo mismo que hace `instalarAtomico` para los packs del APK (D-082).

Hace además `force-stop` antes y relanza después, porque **la app no tiene rescan**: el escaneo es
one-shot en el `init` del ViewModel y un pack copiado no aparece hasta reiniciar el proceso.

Sin packs la app arranca y dice *"No hay ningún diccionario instalado."*, que es la degradación
correcta.

**Esto cerró D-071**, que era una desviación consciente: el pack viajaba como asset y se extraía
al primer arranque, duplicándolo en disco. La decisión decía que se revertía al existir el
instalador; lo que la adelantó fue medir el inglés — **295,1 MiB en disco, 184,7 MiB
comprimido**, que con los dos idiomas dejaba el APK en ~270 MB. Sin packs pesa 50 MB.

## Dos packs, un idioma activo

Se elige uno y se busca en él; **no se fusionan resultados** — eso es composición y necesita
`SearchRepository`, que no existe (D-078).

Tres cosas que no son preferencia sino defensas contra bugs que ya existían:

- **El pack activo lo decide el idioma del reloj, nunca el orden alfabético** (D-079). Antes se
  abría el primer `.db` alfabético, así que instalar inglés habría escondido el español en
  silencio: `en-` ordena antes que `es-`.
- **La navegación lleva `packId`** además de `entryId` (D-080). Sin él, tocar un resultado de
  inglés lo resolvía contra el pack activo y mostraba otra palabra.
- **La voz sale de `metadata.langSource` del pack activo.** Un reloj dictando "perro" contra el
  reconocedor en inglés devuelve cualquier cosa.

El selector **no cuesta una fila de resultados** —con 192 dp serían un tercio de la lista—:
reemplaza al título cuando la búsqueda está vacía, y con query sin resultados aparece como
*Buscar en \<idioma\>*, que es exactamente cuando sirve.
