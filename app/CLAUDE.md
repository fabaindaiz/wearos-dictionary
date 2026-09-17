# app

App Wear OS. **El MVP existe y corre**: buscar, abrir una entrada y la pantalla de atribución,
sobre el pack real de 146.194 entradas. El Tile y la Complication **siguen siendo los del
template** y no hacen nada de diccionario: qué muestra la superficie glanceable es una decisión
de producto abierta (D-026, `docs/roadmap.md`).

Las tres pantallas viven en `presentation/`; `data/PackStore.kt` es lo único que sabe de dónde
sale el pack.

Las reglas de acá son preventivas: son caras de descubrir tarde.

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

## El pack viaja dentro del APK, y es una ⚠️ desviación consciente

`PackStore` extrae el pack desde `assets/` a `filesDir/packs/` la primera vez. Eso **duplica el
pack en disco** —36 MB comprimidos dentro del APK más 69 MB extraídos— que es exactamente el
costo por el que se descartó Room en D-039. `BundledSQLiteDriver.open()` recibe un *path* y un
asset vive dentro del zip del APK: no hay forma de abrirlo en sitio.

Se aceptó para que instalar la app deje un diccionario funcionando sin `adb`. **Cuando exista el
instalador, el asset desaparece.** Mientras tanto `PackStore` prefiere siempre lo que ya haya en
`filesDir/packs/`, así que un `adb push` ahí gana y permite iterar sin reconstruir el APK.

El `.db` no está en el repo (`.gitignore`): se construye con `tools/packbuilder/build_es.py` y se
copia a `app/src/main/assets/`. Sin él la app compila igual y muestra "No hay ningún diccionario
instalado.", que es la degradación correcta.
