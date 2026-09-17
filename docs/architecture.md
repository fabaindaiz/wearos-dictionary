# Arquitectura

## Las restricciones que mandan

| Restricción | Consecuencia de diseño |
|---|---|
| El disco del reloj es escaso | Packs por idioma, payloads comprimidos, presupuesto blando de 50 MB (D-028) |
| Pantalla de ~1.2", búsqueda mientras se escribe | Covering index: la lista sale del índice sin tocar la tabla (D-012) |
| Input por voz o teclado minúsculo | La búsqueda tolera errores en vez de exigir exactitud (D-027) |
| RAM y batería limitadas | Nada se carga en memoria; SQLite read-only con mmap |

> **ASSUMPTION sobre el espacio en disco.** Los principios oficiales de Wear OS dicen que el
> almacenamiento es escaso pero **no dan ningún número**. Cualquier cifra concreta sobre GB
> libres en relojes está sin fuente. Lo que sí es medible es el tamaño del pack, y eso es lo
> que se persigue.

## Módulos

```
:app            UI Wear Compose, Tiles, Complications          (hoy: template)
:dict-data      abre packs, implementa las consultas           (NO EXISTE todavía)
:dict-core      Kotlin puro: normalización, claves, payload    ✔
tools/          builder Python + repertorio Unicode            ✔  (fuera de Gradle)
```

**Dirección permitida:** `:app` → `:dict-data` → `:dict-core`. Nunca al revés.

Hoy esa dirección se respeta trivialmente porque `:app` no depende de ningún módulo del repo y
`:dict-data` no existe. Cuando exista, es lo primero que la auditoría tiene que empezar a
comprobar.

`:dict-core` no depende de Android ni de SQLite, y eso no es organización: es lo que debe estar
sincronizado con el builder y lo que más se testea. Los tests corren en milisegundos sin
emulador.

## Dónde va un archivo nuevo

| Si el archivo… | Va en | Y además |
|---|---|---|
| No sabe de Android, SQLite, red ni rutas | `dict-core/` | Si toca una API de JVM, va en `PlatformJvm.kt` o no va |
| Abre packs o ejecuta SQL | `:dict-data` | Hay que crear el módulo primero (ver roadmap) |
| Es una pantalla, un Tile o una Complication | `app/` | Leé `app/CLAUDE.md` antes: hay tres trampas conocidas |
| Construye o valida packs | `tools/packbuilder/` | Solo stdlib de Python. Una fuente nueva va en `sources/` |
| Genera datos que consumen los dos lenguajes | `tools/unicode/` | Tiene que emitir **ambas** copias y atarlas por sha256 |

## El flujo de un pack

```
fuente léxica (Wikcionario, JSONL)
        │  tools/packbuilder/sources/*.py   ← poda: acá se decide el tamaño
        ▼
   Record en streaming
        │  PackBuilder, dos pasadas sobre staging
        ▼
   pack.db  ──► verify_pack.py ──► catálogo + sha256 ──► descarga al reloj
        │                                                  (cargando + Wi-Fi)
        ▼
   filesDir/packs/<id>.db   ← read-only, una conexión por pack,
                              confinada a un dispatcher de un solo hilo
```

Ese último detalle no es opcional: el SQLite empacado reporta `THREADSAFE=2`, que es
multi-thread y **no** serialized.

## Las capas de la búsqueda

`DictionarySource` es la única superficie por la que la app llega a los datos. Ni la UI ni los
ViewModels ven SQL. Esa interfaz vive en `:dict-core` y no menciona SQLite a propósito: permite
cambiar el almacenamiento sin tocar nada arriba, y testear con implementaciones en memoria.

La búsqueda no es una consulta sino cinco en cascada, cada una más cara y menos confiable que la
anterior. El detalle está en `docs/formato-pack.md`.

## Desviaciones deliberadas de lo que recomienda el framework

| Recomendación habitual | Qué hacemos | Por qué |
|---|---|---|
| Room para todo lo que sea SQLite | Room **solo** para datos de la app; los packs con `BundledSQLiteDriver` directo | `createFromFile()` copia el archivo y duplica decenas de MB (D-039) |
| El SQLite del sistema alcanza | Se empaca SQLite propio, ~1–1,5 MB por ABI | FTS5 no está garantizado en Android (D-002) |
| Hilt/Dagger para inyección | Contenedor manual | A esta escala Hilt agrega KSP y tiempo de build sin beneficio |
| JSON o Protobuf para datos estructurados | Texto delimitado en el payload | Se parsea sin dependencias en los dos lenguajes; comprimido, la diferencia es ruido (D-009) |
| Kotlin al día | Kotlin 2.2.10, no 2.4.20 | `allWarningsAsErrors` convierte un bump de compilador en build roto (D-033) |
