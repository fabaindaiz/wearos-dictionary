# Preguntas que sólo el reloj contesta

**Lo que este documento es.** La lista permanente de preguntas abiertas cuya respuesta **no está
en esta máquina**, cada una con el readout que la contesta y con qué desbloquea. No es una lista
de tareas: es lo que hay que llevarse encima la próxima vez que haya un reloj en la muñeca, para
que esa sesión vuelva con **decisiones** y no con impresiones.

**Por qué existe.** Es la segunda mitad del principio 7 del método. La primera —nombrar la clase
de bug que este repo no puede ver y darle un chequeo pre-ship— ya está: el invariante central, los
vectores compartidos, `verify_pack.py`. La segunda es **observar después de shippear**, y acá el
lugar donde el software corre es una muñeca y quien lo mira no está frente a una terminal.

> Sin este documento, una pasada con el reloj contesta lo que se le ocurra a quien lo tenga puesto.
> Con él, contesta lo que estaba bloqueando algo — y la respuesta entra a `docs/decisions.md`.

**Cómo se usa.**

1. Antes de una pasada: se lee entero y se eligen las preguntas que el tiempo alcanza.
2. Durante: se captura el readout **tal cual**, no la interpretación.
3. Después: cada pregunta contestada se tacha acá con la fecha y el número, y si decidió algo, su
   fila va a `docs/decisions.md`. Una pregunta que se contestó y no se tachó vuelve a preguntarse.

**Cómo entra una pregunta.** Una afirmación que una sesión **no pudo verificar** lo dice en su
entrada del changelog **y agrega su pregunta acá en el mismo cambio** (`CLAUDE.md` §Logging
obligation). Ése es el único camino: si entra por otro lado, nadie sabe qué sesión la dejó abierta.

---

## Lo que ya se puede preguntar sin un dedo

Desde D-232 la app se puede manejar por `adb` en cualquier build que no sea `release`. **Casi
todas las preguntas de abajo se contestan así**, y eso es nuevo: hasta el 2026-09-23 dependían de
escribir en un campo que no toma foco con un tap sintético.

```sh
adb shell am broadcast -p cl.fadiaz.dictionary -a cl.fadiaz.dictionary.DEBUG_SEARCH -e q "hous"
adb shell am broadcast -p cl.fadiaz.dictionary -a cl.fadiaz.dictionary.DEBUG_CLEAR
adb shell am broadcast -p cl.fadiaz.dictionary -a cl.fadiaz.dictionary.DEBUG_DUMP
adb logcat -s Dict:V
adb shell setprop log.tag.Dict DEBUG      # el detalle por consulta
```

⚠️ **`-p` va antes del extra.** Un extra vacío no sobrevive a `adb shell`: medido el 2026-09-23,
`-e q "" -p cl.fadiaz.dictionary` dejó la app buscando literalmente `-p`. Por eso vaciar tiene su
propia acción.

---

## Las preguntas abiertas

| # | La pregunta | El readout que la contesta | Qué desbloquea |
|---|---|---|---|
| P-1 | ¿El respaldo entre idiomas devuelve algo cuando el activo no tiene nada? | `DEBUG_SEARCH` con una palabra que sólo existe en el otro idioma, y `buscar '…' -> N (…) RESPALDO` en `logcat` | **D-168**, sin verificar desde el 2026-09-21 *teniendo el reloj en la mano* |
| P-2 | ¿Los sinónimos de una glosa son tocables y abren la entrada correcta? | `DEBUG_SEARCH`, abrir la ficha, tocar un sinónimo, y comparar el `packId`+`entryId` del log contra el de la glosa | **D-169**, mismo caso que P-1 |
| P-3 | ¿Los dos tiles **dibujan**, y con R8? | Agregarlos al carrusel a mano —es un gesto del usuario, no hay `adb` que lo haga— y `tile historial: pantalla=NNNdp filas=N` en `logcat` | **D-149** y **D-163**. El package manager resuelve los dos `TileService`, así que R8 no los borró; que **rendericen** es lo que falta |
| P-4 | ¿Cuánto tarda de verdad un arranque en frío, y cuánto una consulta a p99? | `am start -W` sobre el build **`benchmark`**, y `buscar … en N ms` con `log.tag.Dict DEBUG` | **O-1** y el presupuesto que reemplazó a D-207. ⚠️ Un número de emulador **no** sirve (D-043) |
| P-5 | ¿Cuánto gasta de batería una ráfaga de uso real? | `dumpsys batterystats` antes y después, sobre `benchmark` | **O-4** y `docs/bateria.md`, que hoy no tiene una sola cifra medida |
| P-6 | ¿Los 46 instrumentados pasan en **hardware**, no en emulador? | `./gradlew :dict-data:connectedDebugAndroidTest` con el reloj conectado. **Leer el conteo, nunca el color**: un dispositivo que se cae a mitad reporta `BUILD SUCCESSFUL` con cero tests | Las asunciones sobre ICU y SQLite del dispositivo. Corrieron en emulador API 33 y 37; en hardware, nunca |
| P-7 | ¿`extractIfNewer` reemplaza el núcleo cuando el APK trae uno más nuevo? | Instalar un núcleo viejo desde el catálogo, subir de `versionCode`, y `<asset>: el APK trae uno mas nuevo (…)` en `logcat` | **D-226/D-229**. El gate cubre el plan y el parser; el cableado necesita dos `data_version` distintos, y hoy los dos salen del mismo `dist/` |
| P-8 | ¿Instalar una versión nueva caduca el memo sobre los **cinco packs completos**? | `DEBUG_DUMP` antes y después de subir de `versionCode`: la huella termina en `.aN` | **D-225**. Verificado el 2026-09-23 **con dos núcleos en un emulador**; con 450 MB de packs reales el costo del primer arranque es otro número |
| P-9 | ¿Cuánto tarda realmente instalar un APK de 111 MB por adb inalámbrico? | El tiempo de `installDebug`, y si se corta, a cuántos MB | La subida de 315 MB **ya se cortó una vez a los 75**. El APK pasó de 5,48 a 111 MB: si esto no es viable, el núcleo inglés no puede viajar dentro |
| P-10 | ¿Una palabra del día de un pack **núcleo** se lee como algo que valga la pena aprender? | `DEBUG_DUMP` nombra el pack activo; el inicio muestra la palabra. Comparar contra la simulación: `acción`, `anillo`, `Christmas`, `afternoon` | **D-240**. El piso de rank se ajustó sobre tiradas simuladas contra los packs reales, nunca sobre la pantalla, y de él depende el primer arranque de cada instalación nueva |

---

## Contestadas

*Se tachan con su fecha y su número; no se borran, porque una pregunta borrada vuelve el trimestre
que viene sin memoria de qué la cerró.*

| # | La pregunta | Contestada | Con qué |
|---|---|---|---|
| — | ¿Un pack núcleo del APK devuelve resultados por la vía del usuario? | **2026-09-23**, emulador `wear_sm_l715f` | `DEBUG_SEARCH hous` → `house` primero en pantalla. Cerró la duda que había obligado a contestar leyendo el `.db` con `sqlite3` |
| — | ¿La pantalla son 192 dp o 234? | **2026-09-19**, SM-L715F | `sw234dp w234dp h234dp 340dpi`. Movió cinco decisiones de layout |
