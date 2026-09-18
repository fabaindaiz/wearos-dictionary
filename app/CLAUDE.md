# app

App Wear OS. **El MVP existe y corre**: buscar, abrir una entrada y la pantalla de atribución,
sobre el pack real de 146.194 entradas. El Tile y la Complication **siguen siendo los del
template** y no hacen nada de diccionario: qué muestra la superficie glanceable es una decisión
de producto abierta (D-026, `docs/roadmap.md`).

Las tres pantallas viven en `presentation/`; `data/PackStore.kt` es lo único que sabe de dónde
sale el pack.

Las reglas de acá son preventivas: son caras de descubrir tarde.

## El test va primero, y acá eso tiene una condición técnica

`:app` nació sin tests y retrofitearlos costó un refactor. **La causa no fue pereza**:
`SearchViewModel` extendía `AndroidViewModel` y construía el pack desde un `Context`, así que
no había forma de correrlo en la JVM. Un test que necesita dispositivo no entra al gate, y uno
que no entra al gate no se corre.

De ahí la regla, que es D-072 y la enforcea el audit: **la lógica de `:app` no importa
`android.*`**. Lo que necesita Android entra por parámetro —`SearchViewModel` recibe `abrirPack`,
no un `Context`— y la frontera es `PackLoad`, un tipo sin Android por el que pasa un fake.

Las **pantallas están fuera de la regla**: un Composable es Android por definición. Se prueban
en dispositivo, con `PantallasTest`, y **no entran al gate**.

Esos tests no arman un `DictionarySource`: las pantallas son funciones del estado, así que el
estado se construye a mano. Si alguna vez una pantalla necesita un fake, es señal de que se le
metió lógica que debería estar en el ViewModel.

## El presupuesto es de 192 dp

La pantalla son 384×384 px a 320 dpi, o sea **192×192 dp**, y la guía de Wear OS pide 48 dp
mínimos de área tocable. Eso da **tres filas y nada más**, medido. Cada dp que gasta el chrome
es un resultado que el usuario no ve, y de ahí salen D-073 (lista de una línea, 48 dp) y D-075
(la entrada de texto se colapsa cuando hay resultados).

Si alguien baja de 48 dp para meter una cuarta fila, el test de densidad **sigue pasando** y lo
que se rompe es el área tocable. Por eso el mínimo vive en una constante con nombre.

```sh
./gradlew :app:testDebugUnitTest         # 17 tests JVM, milisegundos, dentro del gate
./gradlew :app:connectedDebugAndroidTest # 13 tests de pantalla, necesitan emulador
```

Lo que cubren es lo que **no da error**: resultados de una consulta vieja pisando a la actual,
una consulta por pulsación drenando la batería, la búsqueda muerta mientras el pack carga, y un
pack a medio copiar —que se abre sin quejarse y devuelve menos palabras de las que tiene.

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

## Dos cosas del template que hay que resolver antes de publicar

Ninguna se decidió; se heredaron:

- **R8 está desactivado** (`optimization { enable = false }`). La guía oficial de rendimiento de
  Wear OS lo nombra como una de las dos palancas principales. Activarlo reintroduce la clase de
  bug que solo aparece en release, así que va atado a probar en dispositivo. Roadmap O-2.
- **La complication refresca cada hora** (`UPDATE_PERIOD_SECONDS = 3600`). La guía oficial pide
  *"2 hours or longer"*, o desactivar el refresco. Se decide junto con qué muestra el Tile: si es
  "últimas búsquedas", no necesita refresco programado en absoluto.

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
