# wearos-dictionary

Diccionario **100% offline** para Wear OS. Los idiomas se instalan como *packs*: archivos
SQLite de solo lectura que se descargan por separado y se consultan en el reloj sin red.

> **Estado:** en desarrollo temprano. El motor de búsqueda y el pipeline de construcción de
> packs están hechos y testeados; la app todavía es el template de Android Studio. Ver
> [Estado y siguientes pasos](#estado-y-siguientes-pasos).

## Por qué está diseñado así

Cuatro restricciones del reloj mandan sobre todo lo demás:

| Restricción | Consecuencia de diseño |
|---|---|
| El disco es el recurso escaso (8–32 GB, poco libre real) | Packs por idioma, payloads comprimidos, presupuesto de ≤25 MB por pack |
| Pantalla de ~1.2", búsqueda mientras se escribe | Índice de cobertura: la lista de resultados sale del índice sin tocar la tabla |
| Entrada de texto pobre (voz, escritura a mano, teclado minúsculo) | La búsqueda tolera errores en vez de exigir exactitud |
| RAM y batería limitadas | Nada se carga en memoria; SQLite read-only con mmap |

## Estructura

```
dict-core/          Kotlin/JVM puro: normalización, búsqueda, codec del payload
  PlatformJvm.kt      el ÚNICO archivo con APIs de JVM (ver Portabilidad)
tools/
  unicode/          repertorio Unicode fijado; genera las tablas de ambos lados
  packbuilder/      pipeline Python que construye los packs .db
app/                app Wear OS (todavía el template de Android Studio)
```

`:dict-core` no depende de Android ni de SQLite. Eso no es organización: es lo que debe estar
sincronizado con el builder y lo que más se testea, y así los tests corren en milisegundos sin
emulador.

## Empezar

Requiere JDK 17+ y Python 3.9+. Android Studio genera `local.properties` con la ruta al SDK;
si trabajás desde la terminal, creala a mano:

```sh
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
```

```sh
# Tests del núcleo de búsqueda (40 tests, sin emulador)
./gradlew :dict-core:test

# Tests del pipeline de packs (35 tests)
cd tools/packbuilder && python3 -m unittest discover -s tests

# Construir el pack de juguete que usan los tests instrumentados
python3 tools/packbuilder/build_toy.py

# Verificar las invariantes de un pack construido
python3 tools/packbuilder/verify_pack.py ruta/al/pack.db
```

El pack de juguete no está en el repo: es determinista y se regenera con el comando de arriba.

## Cómo funciona la búsqueda

No es una consulta sino cinco, en cascada, y el orden importa porque cada nivel es más caro y
menos confiable que el anterior:

1. **Prefijo del lema** — el 95% del uso. Un range scan sobre un índice de cobertura, así que
   responde sin leer un solo payload.
2. **Forma flexionada** — se escribió "corriendo" y el lema es "correr".
3. **Traducción inversa** — se escribió "run" en un pack es→en.
4. **Tolerante a errores** — solo si lo anterior devolvió casi nada. Trae un vecindario por
   clave fonética y lo reordena por distancia de edición.
5. **Texto libre en definiciones** (FTS5) — solo por acción explícita del usuario, nunca
   mientras escribe: recorre un índice mucho más grande y no cumple el presupuesto de latencia.

El detalle del esquema y de las consultas está en [docs/formato-pack.md](docs/formato-pack.md).

## Portabilidad

Se evaluó Kotlin Multiplatform y **no se adopta**: Wear Compose es solo Android y Compose
Multiplatform no apunta a watchOS, así que la UI —que es la mayor parte del trabajo restante—
no se puede compartir con ningún segundo destino.

Pero `:dict-core` se mantiene portable, que sale casi gratis y deja la puerta abierta a una app
companion de teléfono, que es el segundo destino realista. Toda API de JVM vive en
`PlatformJvm.kt`, y `ArchitectureTest` rompe el build si eso deja de ser cierto. La conversión
a KMP serían tres pasos mecánicos, descritos en el encabezado de ese archivo.

## Antes de tocar nada

Este proyecto tiene **contratos que se rompen en silencio**: no producen un error, una excepción
ni una línea de log, solo hacen que falten palabras en los resultados. Están todos descritos en
[docs/contratos-cruzados.md](docs/contratos-cruzados.md) — vale la pena leerlo antes del primer
cambio a la normalización o al formato del pack.

El resumen: `norm()` y `fuzzy()` existen dos veces, en Kotlin y en Python, y deben dar el mismo
resultado bit a bit. Los vectores compartidos que corren de los dos lados son lo único que
detecta que se separaron.

## Estado y siguientes pasos

Hecho:

- [x] Núcleo de búsqueda portable, con tests en JVM
- [x] Pipeline de construcción de packs y verificador de invariantes
- [x] Formato de pack v1: esquema, índices, FTS5 contentless, payload comprimido

Siguiente:

- [ ] `:dict-data` — abrir packs con SQLite empacado e implementar las cinco consultas
- [ ] Pack real es↔en desde wiktextract, y medirlo contra los presupuestos
- [ ] Instalador de packs: catálogo, descarga verificada, WorkManager
- [ ] **Diseño de la interfaz** — entrada por voz, lista, corona rotatoria, Tile, Complication

## Licencia

El código está bajo la licencia de [LICENSE](LICENSE).

Los packs de idioma son obra derivada de sus fuentes y llevan su propia licencia, declarada en
`meta.license` de cada pack. Los derivados de Wiktionary son CC BY-SA, lo que obliga a
**atribución visible en la app** y *share-alike* sobre los datos. La pantalla de licencias no es
opcional.
