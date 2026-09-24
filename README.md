# wearos-dictionary

Diccionario para Wear OS donde **toda consulta funciona sin red, siempre**. Los idiomas se
instalan como *packs*: archivos SQLite de solo lectura, y **descargar un diccionario es lo unico
que usa la red** --nada de lo que hace la app despues la vuelve a tocar--.

> **Estado:** la app funciona y corre en un reloj físico. Busca por voz y por teclado, muestra
> la entrada con sus acepciones, deja saltar de una palabra a otra tocándola, guarda favoritas,
> trae una palabra del día por idioma y permite gestionar los diccionarios instalados.
> **Lo que falta para llamarlo terminado** es descargar packs desde el reloj —hoy entran por
> cable— y cerrar lo que sólo se comprueba con el reloj puesto.
> Ver [docs/roadmap.md](docs/roadmap.md).

## Lo que hace distinto a este proyecto

Las claves de búsqueda se calculan **dos veces, en dos lenguajes distintos**: el builder en
Python las escribe dentro del pack, y el reloj las recalcula en Kotlin sobre lo que escribe el
usuario. Tienen que dar exactamente el mismo string.

Si se separan no hay excepción ni log — simplemente **falta una palabra en los resultados**. Casi
todo el diseño existe para que eso no pueda pasar en silencio.

## Empezar

Requiere JDK 17+ y Python 3.9. Android Studio genera `local.properties` con la ruta al SDK;
desde la terminal, creala a mano:

```sh
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
```

```sh
./gradlew check                                   # el gate: compila, lint, 1061 tests y la auditoría
./gradlew :dict-core:test                         # solo el núcleo de búsqueda (rápido)
python3 tools/packbuilder/build_toy.py            # pack de juguete para los tests instrumentados
python3 tools/packbuilder/verify_pack.py <pack>   # invariantes de un pack real
```

El pack de juguete no está en el repo: es determinista y se regenera con el comando de arriba.

**El gate no necesita nada instalado más allá de `python3`** — a propósito, para que un clone
funcione solo. Para trabajar en el pipeline de packs hay un entorno Hatch opcional
(`pyproject.toml`) que agrega linter y, sobre todo, la matriz de versiones de Python:

```sh
hatch run test           # los tests del builder
hatch run matrix:test    # los mismos, bajo cada versión de Python soportada
hatch run lint:check     # ruff
```

Está documentado en [tools/CLAUDE.md](tools/CLAUDE.md).

## Trabajar desde Android Studio

El IDE de pruebas del proyecto. Dos cosas a instalar una sola vez:

**El plugin de Claude Code.** `Settings → Plugins → "Claude Code" → instalar → reiniciar`.
Requiere el CLI instalado aparte. Con `/config → Diff tool: auto` los diffs salen en el visor
del IDE en vez del terminal.

**Un emulador de Wear OS**, que es lo que falta para correr los tests instrumentados:

```
Tools → Device Manager → Add a new device → Wear OS → imagen de API 33 o superior
```

```sh
./gradlew :dict-data:devicePrecheck             # ¿hay con qué correrlos? Dice qué falta
./gradlew :dict-data:connectedDebugAndroidTest  # los 39 tests en dispositivo
```

**Creá un AVD por cada nivel de API que soportes, no uno solo.** El punto de
`NormalizationOnDeviceTest` es justamente que las versiones de ICU difieren entre versiones de
Android; correr en una sola no prueba lo que el test intenta probar.

Esos tests son los únicos que cierran las asunciones sobre Android — que el SQLite empacado
traiga FTS5, que el prefijo use el covering index en ese dispositivo, y que `norm()` dé lo mismo
en el reloj que en el builder. Hasta que corran, todo lo que este repo afirma sobre Android es
una suposición. Detalle en [dict-data/CLAUDE.md](dict-data/CLAUDE.md).

## Estructura

```
dict-core/    Kotlin/JVM puro: normalización, claves de búsqueda, codec del payload
tools/        pipeline Python que construye los packs, y el repertorio Unicode fijado
app/          app Wear OS: las pantallas, el ViewModel y de dónde salen los packs
dict-data/    abre los packs y ejecuta las consultas contra SQLite
docs/         formato de pack, decisiones, contratos, roadmap
```

## Dónde seguir

| Si querés… | Leé |
|---|---|
| Entender cómo está organizado y dónde va un archivo nuevo | [docs/architecture.md](docs/architecture.md) |
| Saber cómo es un pack por dentro | [docs/formato-pack.md](docs/formato-pack.md) |
| Saber por qué algo está decidido así | [docs/decisions.md](docs/decisions.md) |
| **Tocar la normalización o el formato del pack** | [docs/contratos-cruzados.md](docs/contratos-cruzados.md) primero |
| Ver qué sigue y con qué choca | [docs/roadmap.md](docs/roadmap.md) |

Si vas a trabajar con Claude Code, [CLAUDE.md](CLAUDE.md) es el punto de entrada.

## Licencia

El código está bajo [GPL-3.0](LICENSE).

Los packs de idioma son obra derivada de sus fuentes y llevan su propia licencia, declarada en
`meta.license` de cada pack. El contenido viene de Wiktionary, que es **CC BY-SA**: obliga a
atribución visible en la app y *share-alike* sobre los datos derivados. La pantalla de licencias
no es opcional.

El DLE de la Real Academia **no** es una fuente utilizable: no tiene licencia abierta ni API
pública.
