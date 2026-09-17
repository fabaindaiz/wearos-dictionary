# app

App Wear OS. **Hoy es todavía el template de Android Studio**: `MainActivity`, un Tile y una
Complication de ejemplo, sin lógica de diccionario. El diseño de la interfaz es trabajo
pendiente (ver `docs/roadmap.md`).

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

## Descargas

Se difieren a **cargando y con Wi-Fi**, con WorkManager. Es la guía oficial de Wear OS, y con
packs de decenas de MB no es opcional.

Excluir los packs del backup con `android:dataExtractionRules`. Con `minSdk 33`,
`fullBackupContent` **no aplica**: es el mecanismo para Android 11 e inferiores.
