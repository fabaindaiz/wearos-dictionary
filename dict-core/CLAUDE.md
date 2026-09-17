# dict-core

Kotlin/JVM puro: normalización, claves de búsqueda, distancia de edición y el codec del
payload. **Sin Android y sin SQLite.** Los tests corren en milisegundos, sin emulador.

## La regla local

Toda API de plataforma vive en `PlatformJvm.kt`. Ningún otro archivo importa `java.*`/`javax.*`
ni usa `Character.`, `.codePoints()` o `.format()`.

`ArchitectureTest` rompe el build si eso deja de ser cierto, y está comprobado que detecta
violaciones reales. La conversión a KMP —si algún día hay una app companion— sería mover ese
archivo a `jvmMain/` y declarar sus cuatro funciones como `expect`.

## El archivo a copiar

`PrefixRange.kt`. Módulo pequeño, `object`, KDoc que explica **por qué** y no qué, iteración por
code point escrita a mano, cero dependencias.

Para un test: `PrefixRangeTest.kt` — verifica la propiedad ("el rango contiene exactamente las
palabras con ese prefijo"), no solo casos sueltos.

## La trampa que ya nos mordió

**Una extensión de Kotlin nunca gana sobre un miembro nativo de la JVM.** `appendUtf16` se llama
así y no `appendCodePoint` porque con ese nombre habría ganado
`StringBuilder.appendCodePoint` de la JVM y el código portable nunca se habría ejecutado:
funcionando bien hoy, fallando recién al compilar para otro target.

Si escribís un reemplazo portable de algo de la JVM, **dale un nombre distinto**.

## Agregar un `FuzzyProfile`

Es un cambio de dos lenguajes, siempre:

1. `FuzzyProfile.kt` — la entrada del enum, con las reglas **en orden**. El orden es parte del
   contrato: `"ce" → "se"` antes que `"c" → "k"`, si no "cerrar" deja de colisionar con "serrar".
2. `tools/packbuilder/normalize.py` — las mismas reglas en `FUZZY_PROFILES`.
3. `vectors/normalization-vectors.tsv` — casos para el perfil nuevo. `NormalizationVectorsTest`
   falla si un perfil declarado no tiene vectores.
4. Subir `NORM_VERSION` en los dos lados.

**Nada de expresiones regulares.** Solo reemplazo literal de strings, que tiene semántica
idéntica en Kotlin y en Python. Dos regex "equivalentes" divergen en un caso borde que nadie
nota.

## Qué NO va acá

Nada que sepa de SQLite, de Android, de rutas de archivo o de red. `DictionarySource` es una
interfaz a propósito: la implementación vive en el módulo que sí puede tocar SQLite.
