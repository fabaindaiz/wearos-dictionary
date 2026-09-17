# tools

Dos áreas de Python, sin dependencias de terceros (solo stdlib). Corren fuera de Gradle salvo
por la tarea `:tools:pythonTest`, que las mete en el gate.

- `packbuilder/` — construye los packs `.db` a partir de fuentes lexicográficas.
- `unicode/` — genera el repertorio Unicode fijado que comparten el builder y la app.

## El archivo a copiar

Un módulo: `payload.py` — docstring que explica la decisión y su alternativa descartada,
funciones cortas, cero estado global.

Un test: `tests/test_build.py` — construye packs de verdad en un directorio temporal y los
inspecciona con SQL. No mockea SQLite.

## El builder es de dos pasadas, y no por gusto

Las fuentes reales son de gigabytes (el JSONL del Wikcionario son 1,1 GB) y no entran en
memoria. Por eso `PackBuilder` escribe a una tabla de staging dentro del propio archivo, arma el
diccionario de compresión con una muestra por reservorio, y recién en la segunda pasada
comprime y llena `entry` y `fts_def`.

Los índices se crean **al final**, sobre las tablas ya pobladas. Mantenerlos durante la ingesta
es mucho más lento.

Si algo falla a mitad, el pack se borra. Un pack a medio construir es peor que ninguno: se abre
sin error y devuelve menos resultados de los que debería.

## Agregar una fuente

Va en `sources/`, y entrega `Record` — el builder no sabe de formatos. Lo que la fuente debe
resolver:

- **Poda.** Es donde se decide el tamaño del pack. Conservar `word`, `pos`, glosas, formas y
  traducciones; descartar etimologías, pronunciaciones, categorías y citas.
- **Streaming.** Nunca cargar el archivo entero.
- **De qué edición y sección viene.** El Wikcionario y el Wiktionary inglés son datasets
  distintos: el primero da glosas en español, el segundo glosas en inglés sobre palabras
  españolas.

## Regenerar el repertorio Unicode es un acto deliberado

`gen_repertoire.py` aborta si el Python que lo corre no trae exactamente Unicode 13.0.0. Eso no
es un bug: fija el repertorio al **piso** común entre el builder y el Android más viejo que
soportamos.

Subir de versión exige comprobar que el piso nuevo lo soporten todas las plataformas, revisar el
diff de `repertoire.txt` y subir `NORM_VERSION`. Leé el encabezado del generador antes.

## La obligación de espejo

`normalize.py` es el espejo escrito a mano de `TextNormalizer.kt`. Cualquier archivo con espejo
lo declara en su encabezado:

```
ESTE ARCHIVO TIENE UN ESPEJO: <ruta>
```

`tools/audit_dictionary.py` comprueba que la ruta declarada exista. Que el **contenido**
coincida lo comprueban los vectores compartidos, no la auditoría.
