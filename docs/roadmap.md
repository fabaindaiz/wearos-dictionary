# Trabajo planificado

Ideas aceptadas, todavía no construidas. **No es una promesa ni una orden de trabajo**: es
dónde va a chocar cada una, escrito ahora que está claro.

## Dónde estamos

El **motor de búsqueda y el pipeline de packs están hechos y testeados**: 40 tests en
`:dict-core`, 35 en el builder, un pack de juguete que pasa todas las invariantes de
`verify_pack.py`, incluido que el prefijo use `COVERING INDEX`.

La **app sigue siendo el template de Android Studio**. No hay nada de diccionario en `:app`, y
`:dict-data` no existe como módulo.

**Nada de esto corrió nunca en un reloj.** No hay dispositivo ni emulador conectado, `:app:test`
es `NO-SOURCE`, y no existe un solo test instrumentado. Toda afirmación sobre el comportamiento
en Android es ASSUMPTION.

El invariante central —que el builder y la app calculen la misma clave— está sostenido por los
vectores compartidos, y se verificó que detecta divergencia real: encontró y corrigió un desfase
de 14.773 code points entre Python y la JVM (D-003).

---

## Datos

### Construir el pack real de español monolingüe

Podar el Wikcionario a definiciones y pesarlo. **Es el primer item por una razón: es la medición
que decide si el formato aguanta.**

**Con qué choca.** Con D-028, el presupuesto blando de 50 MB, que hoy **no tiene ninguna
medición detrás**. La sección Español del Wikcionario son 1.036.458 senses; el pack de juguete
tiene 22 entradas. No hay nada entre esos dos puntos.

**Qué hay ya a favor.** `PackBuilder` es de dos pasadas y trabaja en streaming, así que el
tamaño de la fuente no es el problema. `verify_pack.py` valida el resultado. La compresión con
diccionario compartido ya funciona end-to-end.

**Qué hay que decidir antes.** Nada. Hay que medirlo. Todo lo demás de esta lista se decide
mejor con ese número.

### Composición entre packs

Que un pack de sinónimos y uno de traducciones puedan sumar información **a la misma entrada**
del pack de definiciones.

**Con qué choca.** Con el formato del pack base, que es el que estás por construir. Hoy
`entry.id` es el rowid local asignado por autoincrement en orden de inserción: dos packs no
comparten ninguna identidad de palabra, y **reconstruir el pack base reordena los ids**, así que
ni siquiera copiarlos funcionaría entre versiones.

**Qué hay ya a favor.** `Suggestion` ya lleva `packId` además de `entryId`, así que la capa de
resultados distingue el origen. `SearchRepository` ya fusiona varios sources.

**Qué hay que decidir antes.**
- ¿El join key es `(norm, pos)` —estable pero laxo, confunde homógrafos como *bajo* adjetivo y
  *bajo* preposición— o un `entry.id` que sea un **hash estable** de `(norm, pos, fuente)`?
- Si es hash: deja de ser posible que un rebuild rompa las referencias, en vez de validarlo
  después. Es un movimiento de rung 4. ¿Vale el cambio en `PackBuilder`?
- ¿Los packs auxiliares van a salir de las mismas fuentes que el base? Si sí, pueden compartir
  la normalización de `pos`; si no, hay que mapear vocabularios de part-of-speech distintos.

**Por qué es urgente y no puede esperar.** Es más barato decidirlo **antes** de construir el
pack base que después.

### Reorientar el esquema a monolingüe

D-034 fijó que el primer pack es monolingüe con definiciones. Falta que el código lo refleje.

**Con qué choca.** Con `trans`, que en un pack monolingüe se definió como "las palabras que
aparecen en la glosa" — que es exactamente lo que `fts_def` ya indexa, mejor. En monolingüe esa
tabla es espacio gastado dos veces.

**Qué hay que decidir antes.** Si `trans` se vuelve opcional (solo bilingüe) o desaparece del
todo. Y si el toy pack pasa a ser monolingüe, hay que **conservar uno bilingüe mínimo**: sin él,
la búsqueda inversa y el tope `TRANS_MAX_PER_KEY` quedan sin test.

---

## Aplicación

### `:dict-data`

Abrir packs con `BundledSQLiteDriver` e implementar las cinco consultas.

**Con qué choca.** Con nada estructural: es el camino planeado. Pero es el módulo donde
`THREADSAFE=2` deja de ser un dato y pasa a ser un requisito — una conexión por pack, confinada
a un dispatcher de un solo hilo.

**Qué hay ya a favor.** `DictionarySource` está definido, las cinco consultas están escritas y
probadas contra un pack real en `verify_pack.py`, y las versiones están pinneadas en el catálogo.

**Qué hay que decidir antes.** Si `trans` sobrevive (ver arriba). Y dónde vive la verificación
de `meta.payload_dict_sha256` al abrir el pack, que hoy **no existe en ningún lado** aunque D-008
la dé por hecha.

### Diseño de la interfaz

Voz, lista de resultados, corona rotatoria, Tile, Complication.

**Con qué choca.** Con D-026: la búsqueda vive dentro de la app porque los tiles no aceptan text
input, así que la superficie glanceable necesita un propósito propio, no ser un atajo a lo mismo.

**Qué hay que decidir antes.** Qué muestra el Tile: word of the day, últimas búsquedas, o
shortcut. Son productos distintos.

### Instalador de packs

Catálogo, descarga verificada, WorkManager.

**Con qué choca.** Con D-029: cargando **y** Wi-Fi. Con packs de decenas de MB eso significa que
la primera instalación puede tardar hasta la noche, y la UI tiene que explicarlo.

**Qué hay que decidir antes.** Dónde se hostea el catálogo, y si los packs se versionan
independientemente de la app.

---

## Comprobación que falta y bloquea el ship

### Los vectores de normalización, corriendo en un reloj

**Es la clase de bug que este repo no puede observar**: una divergencia entre las claves
precalculadas del pack y las que el reloj calcula, en un dispositivo cuya versión de Unicode
difiere de la máquina de build. Síntoma: falta una palabra, en un modelo de reloj y no en otro,
sin error.

El repertorio fijado (D-003) mata la mayor parte del riesgo, pero su residuo es real: NFD y
`lowercase()` siguen delegando en la plataforma, y esa verificación se hizo **entre Java 26 y
Python 3.9**, nunca sobre Android.

**Qué hace falta.** Un test instrumentado que corra `normalization-vectors.tsv` sobre imágenes
reales de Wear OS, una por nivel de API soportado. Necesita un reloj o un emulador; hoy no hay
ninguno conectado.

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
