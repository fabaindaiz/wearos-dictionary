# Contratos cruzados

Este proyecto tiene acuerdos entre piezas que se rompen **en silencio**: no producen una
excepción, ni un error, ni una línea de log. El síntoma es siempre el mismo y es difícil de
atribuir: *falta una palabra en los resultados*. Alguien lo reporta como "la app no encuentra
X", meses después, y no hay nada en el stack trace.

Este documento es la lista de esos acuerdos y del mecanismo que protege cada uno.

> **Antes de abrir este documento, descartá lo que no es un bug.** Desde D-116 el pack **no
> trae nombres propios** —apellidos, topónimos, nombres de pila—, salvo los que tienen vida
> léxica. Son el 22,1 % de las entradas en español y el 17,1 % en inglés: *Ivanivka*, *Troya*,
> *Etchechury* **faltan a propósito**, y *January*, *Paris*, *España* y *Chile* están por la
> excepción. Un `SELECT value FROM meta WHERE key='proper_nouns'` que diga `lexical-only`
> responde la pregunta sin leer nada más.
>
> El síntoma de una decisión de producto y el de un contrato roto **son idénticos**, y ésa es
> justamente la razón por la que la política se escribe en `meta` y no sólo en el código: un
> pack tiene que poder explicar por sí solo por qué le falta una palabra.

## 1. `norm()` y `fuzzy()` existen dos veces

| | |
|---|---|
| **Archivos** | `dict-core/src/main/kotlin/cl/fadiaz/dictionary/core/TextNormalizer.kt` ⟷ `tools/packbuilder/normalize.py` |
| **Acuerdo** | Deben dar el mismo resultado **bit a bit**, para toda entrada |
| **Protección** | `tools/packbuilder/vectors/normalization-vectors.tsv`, que corren los tests de **ambos** lados |

El builder calcula estas claves al construir el pack y las guarda en columnas indexadas. El
reloj las vuelve a calcular sobre lo que escribe el usuario y compara. Si las dos
implementaciones difieren aunque sea en un carácter, la comparación falla y la palabra
simplemente no aparece.

**Al cambiar la normalización:**

1. Cambiar los dos archivos en el mismo commit.
2. Agregar casos a `normalization-vectors.tsv`.
3. Subir `NORM_VERSION` en los dos lados. Los packs viejos se rechazan solos al abrirse.

**Por qué no hay expresiones regulares.** Los plegados fonéticos usan solo reemplazo literal de
strings, que tiene semántica idéntica en Kotlin y en Python: global, izquierda a derecha, sin
solapamiento. Dos regex "equivalentes" en dos lenguajes son justo el tipo de cosa que diverge
en un caso borde y nadie nota.

**El orden de las reglas es parte del contrato.** En el perfil español, `"ce" → "se"` tiene que
correr antes que `"c" → "k"`; si no, "cerrar" termina en "kerar" y deja de colisionar con
"serrar", que era el punto.

## 2. La versión de Unicode de la plataforma

| | |
|---|---|
| **Archivos** | `tools/unicode/repertoire.txt` → `repertoire.py` + `UnicodeRepertoire.kt` |
| **Acuerdo** | La clasificación de code points sale de nuestra tabla, nunca de la plataforma |
| **Protección** | Ambas copias se generan del mismo origen y están atadas por sha256 |

Este es un bug que ya ocurrió, y vale la pena entenderlo porque la intuición falla.

Cada plataforma trae su propia versión de Unicode:

```
Python 3.9  ->  Unicode 13.0
Java 26     ->  Unicode 16
Android     ->  una versión distinta POR CADA release del sistema
```

Mientras la clasificación se delegaba en `unicodedata.category` y `Character.getType`, el
builder y la app no coincidían: medido sobre el repertorio completo, **14.773 code points se
clasificaban distinto**, todos por estar asignados después de Unicode 13. Y lo peor no era la
diferencia builder/app, sino que **el mismo pack se habría comportado distinto en dos relojes
con distinta versión de Wear OS**.

La tabla está fijada en **Unicode 13.0** por ser el *piso*: lo que trae el Python del builder, y
por debajo de Android 13 (`minSdk 33`, Unicode 14). Toda plataforma por encima del piso conoce
el repertorio entero.

**Qué se sigue delegando en la plataforma, y por qué es seguro.** NFD y `lowercase()`. Se
verificó sobre los 133.730 code points de la tabla: **cero diferencias** en ambas operaciones
entre Java 26 y Python 3.9. La política de estabilidad de Unicode garantiza que la
descomposición canónica de un carácter no cambia una vez asignado.

**Subir de versión Unicode es un acto deliberado**, no un efecto secundario de actualizar
Python. El procedimiento está en el encabezado de `tools/unicode/gen_repertoire.py`.

## 3. deflate no valida su diccionario precargado

| | |
|---|---|
| **Archivos** | `dict-core/src/main/kotlin/cl/fadiaz/dictionary/core/PayloadCodec.kt` ⟷ `tools/packbuilder/payload.py` |
| **Acuerdo** | `meta.payload_dict_sha256` se verifica al abrir el pack |
| **Protección** | `vectors/payload-fixture.tsv`, comprimido por Python y descomprimido por Kotlin |

Los payloads se comprimen con deflate y un diccionario compartido guardado en el pack. Lo
peligroso es que **deflate no detecta un diccionario equivocado**: si tiene el largo suficiente,
descomprime sin lanzar nada y devuelve texto corrupto. Verificado:

```
correcto    ->  "moverse rapidamente"
equivocado  ->  " nadrse rapidamente"     sin ninguna excepción
```

Java lanzó excepción la primera vez que se probó, pero por suerte: el diccionario falso era más
corto y las referencias quedaban fuera de la ventana. No es una garantía.

Por eso el hash, que se comprueba **una vez al abrir el pack**, no por entrada.

> Se intentó antes un canario comprimido derivado del propio diccionario y se descartó: como el
> valor esperado se calculaba desde el mismo diccionario, uno truncado seguía validando. Un
> mecanismo de integridad que se valida a sí mismo no valida nada.

## 4. `fts_def.rowid` es `entry.id`

| | |
|---|---|
| **Acuerdo** | La tabla FTS5 es *contentless*; el rowid es lo único que devuelve |
| **Protección** | `verify_pack.py` comprueba que haya una fila de FTS por entrada |

`fts_def` se declara con `content=''` para no guardar una segunda copia del texto, que ya vive
comprimido en `entry.payload`. La consecuencia es que una búsqueda de texto libre **solo
devuelve rowids**: si el builder los desalinea, la búsqueda apunta a entradas equivocadas y
muestra resultados que no tienen nada que ver con lo buscado.

Consecuencia asumida: sin `snippet()` ni `highlight()`. El resaltado se hace en Kotlin sobre el
payload descomprimido de los pocos resultados que se muestran.

## 5. La identidad de una entrada entre packs

| | |
|---|---|
| **Archivos** | `tools/packbuilder/build.py` → `entry.uid` ⟷ el pack auxiliar que lo referencia |
| **Acuerdo** | `entry.uid` identifica la misma palabra en dos packs distintos, y sobrevive a reconstruir el base |
| **Protección** | `verify_pack.py` (unicidad + `meta.uid_recipe`) y `check_forbidden_mirror` en `audit_dictionary.py` |

Un pack auxiliar —sinónimos, traducciones— le suma información a una entrada del pack base
apuntándola por `uid`. Las dos formas de romperlo no producen error:

- **`uid` repetido**: el auxiliar le pega a dos entradas a la vez y una muestra contenido ajeno.
- **Otra receta de `uid`**: el auxiliar apunta a la entrada equivocada, o a ninguna. Por eso la
  receta viaja en `meta.uid_recipe` y se compara.

**Por qué esto no es un contrato entre dos lenguajes, y hay que mantenerlo así.** `uid` lo calcula
únicamente el builder; la app lo lee de la columna. Mientras haya una sola implementación no
puede divergir, y no hacen falta vectores compartidos. El día que alguien escriba un
`TextNormalizer.uid()` —por conveniencia, para no leer la fila— vuelve la clase de bug del §1
entera, y esta vez sin vectores que la atrapen. `audit_dictionary.py` rompe el build si aparece.

**Por qué el build falla ante una colisión en vez de resolverla.** Cualquier criterio de desempate
que dependa del orden de inserción rompe justo la estabilidad entre rebuilds que `uid` existe para
dar: el mismo diccionario, reconstruido, repartiría las identidades distinto. La fuente entrega
`sense_key` para separar homógrafos con mismo headword y mismo pos.

## 6. Portabilidad de `:dict-core`

| | |
|---|---|
| **Acuerdo** | Toda API de JVM vive en `PlatformJvm.kt` |
| **Protección** | `ArchitectureTest` rompe el build si otro archivo la toca |

"Es Kotlin puro" es una afirmación que se degrada sola: alcanza con que alguien use
`String.format`, `java.util.Locale` o `codePoints()` sin pensarlo. El test lo convierte en un
fallo inmediato en vez de una sorpresa el día de la conversión a KMP.

Un detalle que el test no atrapa y conviene tener presente: **una extensión de Kotlin nunca gana
sobre un miembro nativo de la JVM**. Por eso `appendUtf16` se llama así y no `appendCodePoint`
—con ese nombre, `StringBuilder.appendCodePoint` de la JVM habría ganado y el código portable
nunca se habría ejecutado, funcionando bien hoy y fallando recién al compilar para otro target.

## Cómo verificar todo de una vez

`./gradlew check` corre los seis mecanismos de arriba. Los comandos sueltos están en
[CLAUDE.md](../CLAUDE.md); no se repiten acá para que no haya dos listas que diverjan.
