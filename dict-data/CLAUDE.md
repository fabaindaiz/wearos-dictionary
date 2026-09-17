# dict-data

Abre los packs y ejecuta las consultas. Aisla todo el SQL: `:app` no ve una query.

## Sus tests son instrumentados, y el gate no los corre

Son los **únicos** que cierran las asunciones sobre Android: que el SQLite empacado traiga FTS5,
que el prefijo use el covering index en ese dispositivo, y sobre todo que `norm()` dé lo mismo
en el reloj que en el builder.

```sh
./gradlew :dict-data:devicePrecheck            # ¿hay con qué correrlos? Dice qué falta
./gradlew :dict-data:connectedDebugAndroidTest # los 22 tests
```

Corrélos en **cada nivel de API soportado**, no en uno solo. El punto de
`NormalizationOnDeviceTest` es justamente que las versiones de ICU difieren entre versiones de
Android; correr uno solo no prueba lo que el test intenta probar.

Los assets (el pack de juguete y una copia de los vectores) los genera
`prepareAndroidTestAssets`, que corre solo antes del build. No hay paso manual.

## Una conexión por pack, en un dispatcher de un solo hilo

No es precaución: el SQLite empacado reporta `THREADSAFE=2`, que es multi-thread y **no**
serialized. Una conexión no se puede usar desde dos hilos a la vez (D-050).

El efecto secundario es útil: las consultas de un mismo pack se serializan solas, así que una
búsqueda vieja que no terminó no compite con la nueva.

## El archivo a copiar

`SqlitePackSource.kt` para una consulta nueva: cada una documenta **por qué** tiene la forma que
tiene, que es donde vive el conocimiento que se pierde. `PackFile.kt` para algo que valide un
pack: sus cuatro chequeos cubren fallas que de otro modo serían silenciosas.

## Dos cosas que AGP 9 y Gradle 9 no dejan hacer

Las dos costaron una iteración cada una y están comentadas en `build.gradle.kts`:

- **No se le puede pasar un `Provider` a la SourceSet API.** Por eso los assets generados van al
  directorio estático `src/androidTest/assets/`, que está gitignorado entero.
- **Referenciar algo declarado a nivel de build script desde un `doLast` rompe el configuration
  cache.** Pasó con un `copy {}` y después con una función auxiliar. La solución es una lambda
  local o una tarea aparte.

## Los umbrales del nivel tolerante son provisorios

`FUZZY_TRIGGER`, `FUZZY_PREFIX_LENGTH` y `MAX_EDIT_DISTANCE` se eligieron a priori y **no tienen
ninguna medición detrás** (D-052). Con un pack de 22 entradas no significan nada: se ajustan con
el pack real.

Si vas a tocarlos, mirá antes `ToyPackFixtureTest` en el builder — los tests instrumentados
dependen del contenido del pack de juguete, y ese test protege esa suposición.
