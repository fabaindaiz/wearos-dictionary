# CLAUDE.md

Diccionario **100% offline** para Wear OS. Los idiomas se instalan como *packs*: archivos
SQLite de solo lectura, descargados por separado y consultados en el reloj sin red. Lo inusual:
**las claves de búsqueda se calculan dos veces, en dos lenguajes distintos, y tienen que dar el
mismo string** — el builder en Python las escribe en el pack, el reloj las recalcula en Kotlin
sobre lo que escribe el usuario.

## El invariante central

> `norm(x)` calculado por el builder == `norm(x)` calculado por la app, para todo `x`, en toda
> plataforma y para siempre.

Si se rompe, no hay excepción ni log: **falta una palabra en los resultados**, y el reporte
llega meses después sin nada en el stack trace. Todo lo demás —la collation BINARY, el covering
index, el repertorio Unicode fijado, `NORM_VERSION`— existe para sostenerlo.

Enforcer: `vectors/normalization-vectors.tsv`, ejecutado por los tests de **ambos** lenguajes.

## Restricciones no negociables

Decisiones tomadas, no preferencias. No propongas alternativas salvo que se pidan revisar.
Cada una tiene su fila en `docs/decisions.md`.

**Normalización y claves**
- La clasificación de code points sale de `UnicodeRepertoire` / `repertoire.py`, **nunca** de
  `Character.getType` ni `unicodedata.category` — cada plataforma trae su propia versión de
  Unicode y 14.773 code points se clasificaban distinto. Enforcer: `ArchitectureTest` compara
  el sha256 de las dos copias. (D-003)
- NFD y `lowercase()` **sí** se delegan en la plataforma: 0 diferencias medidas sobre los
  133.730 code points del repertorio fijado. (D-004)
- Un cambio en `norm()` o `fuzzy()` toca los dos lenguajes en el mismo commit y sube
  `NORM_VERSION`. Enforcer: `tools/audit_dictionary.py` compara las constantes. (D-005, D-006)

**Formato de pack**
- El pack es inmutable y se abre read-only. No hay migraciones: `schema_version` distinta se
  rechaza y se descarga de nuevo. (D-001)
- `fts_def` es contentless y su `rowid` **es** `entry.id`. Si se desalinean, la búsqueda de
  texto libre apunta a entradas equivocadas. Enforcer: `verify_pack.py`. (D-011)
- `meta.payload_dict_sha256` se verifica al abrir. deflate **no** detecta un diccionario
  precargado equivocado: descomprime sin error y devuelve texto corrupto. (D-008)
- Los packs se construyen con `tools/packbuilder`. La app nunca parsea fuentes crudas. (D-015)

**Portabilidad de `:dict-core`**
- Toda API de JVM vive en `PlatformJvm.kt`. Ningún otro archivo del módulo importa `java.*` ni
  usa `Character.`, `.codePoints()` o `.format()`. Enforcer: `ArchitectureTest`. (D-017)
- KMP se evaluó y **no se adopta**: Wear Compose es solo Android. (D-018)

**Superficie Wear OS**
- Tiles y widgets no aceptan text input: la búsqueda vive dentro de la app. La superficie
  glanceable sirve para word of the day, últimas búsquedas o shortcut. (D-026)
- **Nunca** `androidx.glance:glance-wear-tiles` — está deprecado y será removido. El naming
  confunde: no es la librería de Wear Widgets. (D-025)
- Wear Widgets (Glance + RemoteCompose) está pospuesto, no descartado: los paquetes están en
  alpha y solo existen en Wear OS 7. (D-024)
- Las descargas se difieren a **cargando y con Wi-Fi**, según la guía oficial de Wear OS. (D-029)

## Guardrails que no se relajan

- **Los vectores compartidos.** Parecen un test más; son el único mecanismo que detecta que las
  dos implementaciones de `norm()` se separaron.
- **El hash del payload dict.** Parece redundante porque "deflate ya falla si algo está mal".
  No falla: está medido que devuelve texto corrupto en silencio.
- **`verify_pack.py` antes de publicar un pack.** Un pack a medio construir se abre sin error y
  devuelve menos resultados de los que tiene.
- **La comprobación en device.** Todavía no existe y es la clase de bug que este repo no puede
  ver: ver `docs/contratos-cruzados.md`.

## Archivos que no se editan a mano

Generados. Editarlos crea dos fuentes de verdad que divergen en silencio.

- `dict-core/src/main/kotlin/cl/fadiaz/dictionary/core/UnicodeRepertoire.kt`
- `tools/unicode/repertoire.txt`
- `tools/packbuilder/vectors/payload-fixture.tsv`
- `local.properties`

Respaldado en `.claude/settings.json` → `permissions.deny`.

## Comandos

```sh
./gradlew check                                                 # EL GATE. Compila, lint y :dict-core:test
./gradlew :dict-core:test                                       # solo el núcleo (rápido, sin emulador)
cd tools/packbuilder && python3 -m unittest discover -s tests    # el builder
python3 tools/packbuilder/build_toy.py                          # regenera el pack de juguete
python3 tools/packbuilder/verify_pack.py <pack.db>              # invariantes de un pack real
python3 tools/unicode/gen_repertoire.py                         # regenera el repertorio (acto deliberado)
python3 tools/packbuilder/gen_payload_fixture.py                # regenera el fixture del codec
./gradlew :dict-data:devicePrecheck                             # hay emulador o reloj? dice que falta
./gradlew :dict-data:connectedDebugAndroidTest                  # LOS TESTS EN DISPOSITIVO (ver abajo)
```

El entorno Hatch para el pipeline Python es opcional y **el gate no lo necesita** (D-046):
vive en `tools/CLAUDE.md`, que es el documento que lo posee.

## Verificación

`./gradlew check` corre el gate completo, incluidos los tests del núcleo y la auditoría
estructural. **Los tests de Python corren dentro del gate** vía `:tools:pythonTest`.

Después de tocar `norm()`, `fuzzy()` o el formato del pack, además:
`python3 tools/packbuilder/build_toy.py && python3 tools/packbuilder/verify_pack.py dict-data/src/androidTest/assets/toy-es-en.db`

**Una claim necesita una medición.** No escribas un número en un documento sin decir cómo se
obtuvo, y si la medición mata una creencia, esa es la entrada más valiosa del changelog.

**El test va primero.** Un agente que escribe el código y después el test escribe **el test que
el código pasa**, y el bug queda ratificado como comportamiento esperado. Escribí antes la
expectativa —una fila en `normalization-vectors.tsv`, un caso en `test_build.py`— y **miralo
fallar por la razón que esperabas**. Las excepciones (un spike, un test de caracterización) se
nombran como tales.

**Emulador y reloj no miden lo mismo** (D-043): el emulador cierra correctitud —normalización,
FTS5, planes de consulta—, porque trae el ICU y el SQLite de su nivel de API. Rendimiento y
batería solo valen medidos en **reloj físico**. Ver el `benchmark` skill.

**Los tests de `:dict-data` son instrumentados y el gate NO los corre** (necesitan dispositivo).
Son los únicos que cierran las asunciones sobre Android. Corrélos en cada nivel de API
soportado, no en uno solo: el punto es que las versiones de ICU difieren.

## Cómo corre una sesión

**Abrí con el brief**, antes de contestar o planear. Una línea sin nada dice `nada`: omitirla no
distingue *miré y está limpio* de *no miré*.

```
En movimiento   qué quedó a medias, según el roadmap y el changelog
En el árbol     trabajo sin commitear, en qué branch, y de quién es
Restringe       las decisiones y los números que pesan sobre lo que se pidió
Obsoleto        qué hay que re-chequear antes de creerle
Lo cambia       cómo lo de arriba altera el pedido — una frase
Fricción        items de proceso abiertos que este trabajo va a tocar
```

**El trabajo sin commitear no es tuyo.** Nombralo en el reporte y no lo arrastres al tuyo.

**Las preguntas van juntas y antes de escribir**, con tope de tres y una recomendación adelante.
Cada opción se cotiza **en las unidades de este repo** —MB por millón de entradas, ms a p99,
bytes por fila— nunca en "más complejo", y dice **qué cierra**: eso es lo que nadie reconstruye
del código un año después. Lo reversible en diez minutos se decide solo y se avisa en una línea.

**Mirá el output, no solo los números.** Un gate verde dice que el código hizo lo que se le
mandó, no que lo que se le mandó estuviera bien. Acá eso es abrir el pack y **leer entradas de
verdad**, no contar filas. Ver el `pack-workflow` skill.

**Cerrá devolviendo lo que la sesión aprendió.** Capturar es incondicional; proponer tiene
umbral: una fricción va al changelog la primera vez y **sube al roadmap §Proceso y herramientas
la segunda**, con la aritmética. Las mejoras de proceso **se proponen, no se ejecutan**, salvo la
de una línea y reversible. La pregunta de cierre: *si la próxima sesión es otro agente sin
memoria de esta, ¿qué tendría que re-derivar?*

## Commits

Se ofrecen cuando el trabajo está terminado, nunca por iniciativa propia a mitad de tarea.
Se parten **por dependencia, no por tamaño**: cada commit tiene que quedar verde por sí solo,
así el historial es bisecable. Verificalo con `git worktree` antes de dar por hecho que lo está.

El mensaje dice **por qué**, no qué archivos cambiaron — eso ya lo dice el diff.

## Obligación de logging

Cada sesión escribe su entrada en `.claude/logs/agent-changelog.md`, arriba de todo.
Existe porque **dos sesiones en paralelo no se ven entre sí** y el conflicto aparece al
compilar, o peor, al revisar.

La entrada dice además **qué salió mal en el camino** y **qué quedó sin hacer**. Un log de
éxitos es contabilidad: lo único que avisa a la sesión siguiente son los errores y la deuda.

## Estilo de trabajo

- **Español** en la conversación. Los technical terms van **en inglés sin traducir**: gate,
  covering index, prefix, payload, rung.
- **Trade-offs y conceptos antes que code dumps.** Explicá qué se gana y qué se pierde.
- **Preguntas clarificadoras antes de soluciones detalladas.** Una suposición equivocada cuesta
  más que una pregunta.
- **Fuentes primarias.** Documentación oficial o el repositorio de artefactos, no un resumen.
  Marcá **ASSUMPTION** lo que venga de memoria o de un resumen.
- **Extender antes de crear.** Un segundo archivo haciendo el trabajo de uno que ya existe es
  cómo un codebase olvida lo que decidió.
- **La integridad arquitectónica gana sobre el pedido.** Si algo rompe una restricción de acá,
  decí el costo y proponé el camino correcto; desviate solo con confirmación explícita, y
  registralo como ⚠️ Desviación.

## Los documentos, y cuál responde qué

| Pregunta | Documento |
|---|---|
| ¿Por qué esto está decidido así? ¿Puedo cambiarlo? | `docs/decisions.md` |
| Falta una palabra / salen repetidos / el pack no abre | `docs/contratos-cruzados.md` |
| ¿Cómo es el `.db` por dentro? ¿Qué consulta uso? | `docs/formato-pack.md` |
| ¿Dónde va un archivo nuevo? ¿Cuáles son las capas? | `docs/architecture.md` |
| ¿Qué sigue? ¿Con qué choca lo que quiero hacer? | `docs/roadmap.md` |
| ¿Cómo mido esto? ¿Está lento? ¿Cuánto gasta? | `benchmark` skill, y `docs/roadmap.md` §Optimización |
| ¿Esto ya lo investigamos? ¿Qué dice la fuente oficial? | `docs/references.md` |
| ¿Qué cambió y por qué, en las últimas sesiones? | `.claude/logs/agent-changelog.md` |
| ¿Qué es este proyecto? (para alguien de afuera) | `README.md` |
| ¿Cómo se trabaja este repo con un agente? ¿De dónde salen estas reglas? | `docs/agents/prompt-context.md` |
| ¿Está sano el sistema de instrucciones? ¿Hay que actualizar el método? | `docs/agents/prompt-evaluate.md`, `prompt-update.md` |
