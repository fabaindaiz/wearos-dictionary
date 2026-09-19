---
name: troubleshoot-diccionario
description: Diagnostica fallas del diccionario a partir del síntoma. Usar cuando se reporte "falta una palabra", "no encuentra X", "no aparece en la búsqueda", "salen resultados repetidos", "el pack no abre", "se ve texto corrupto o con símbolos raros", o cualquier resultado incorrecto de búsqueda.
allowed-tools: Bash, Read, Grep
---

# Diagnosticar el diccionario

**El conocimiento de fallas de este repo no está en el historial de git.** Está en
`docs/contratos-cruzados.md` y en los encabezados de los archivos con espejo. Empezá por ahí, no
por `git log`.

Casi todas las fallas de este proyecto comparten un síntoma —*falta una palabra*— y ninguna
produce excepción. Diagnosticá por síntoma.

## "Falta una palabra" / "no encuentra X"

El más común y el más peligroso. En orden de probabilidad:

0. **¿Es un nombre propio?** Desde D-116 los packs **no traen apellidos, topónimos ni nombres
   de pila**, salvo los que tienen vida léxica. *Ivanivka*, *Troya*, *Etchechury* y *Hopewell*
   **no están, y no es un bug**: son 22,1 % de las entradas en español y 17,1 % en inglés, y el
   90,4 % de lo que se sacó no definía nada. Comprobalo en un segundo, antes de tocar nada:
   ```bash
   sqlite3 <pack.db> "SELECT value FROM meta WHERE key='proper_nouns'"   # lexical-only
   ```
   Si el lema es un nombre propio y el pack dice `lexical-only`, **la respuesta es "así se
   diseñó"**. Los que sí quedaron son los que tienen `translations + descendants + derived >= 5`
   en el dump: los meses, los países, los idiomas — *January*, *Paris*, *España*, *Chile*.
   Para medir cuánto cambia eso, se reconstruye con `--con-nombres`.

1. **Las dos implementaciones de `norm()` divergieron.** Comprobalo directo:
   ```bash
   cd tools/packbuilder && python3 -c "import normalize; print(repr(normalize.norm('LA PALABRA')))"
   ```
   y compará con lo que guardó el pack:
   ```bash
   sqlite3 <pack.db> "SELECT headword, norm, fuzzy FROM entry WHERE headword LIKE 'LA PALABRA%'"
   ```
   Si difieren, alguien tocó un solo lenguaje. Ver `contratos-cruzados.md` §1.

2. **`norm_version` del pack ≠ `NORM_VERSION` de la app.** El pack está indexado con otras
   reglas. `SELECT value FROM meta WHERE key='norm_version'`.

3. **La palabra tiene un carácter fuera del repertorio fijado.** Los code points asignados
   después de Unicode 13 se tratan como separador, así que "abXcd" se indexa como dos palabras.
   Ver `contratos-cruzados.md` §2.

4. **La palabra no está en la fuente.** Comprobalo antes de asumir un bug:
   `SELECT COUNT(*) FROM entry WHERE norm = '<la clave normalizada>'`.

## "Salen resultados repetidos"

Casi seguro la búsqueda inversa sin deduplicar. El rango de prefijo matchea varias claves de
`trans` de la misma entrada ("to", "to run", "to pass") y sin `DISTINCT` la entrada sale una vez
por clave. Está documentado en `docs/formato-pack.md`, consulta #3.

## "Texto corrupto" / símbolos raros en una entrada

**El diccionario de compresión no corresponde.** deflate no lo detecta: descomprime sin lanzar
nada y devuelve basura. Medido: "moverse rapidamente" → " nadrse rapidamente".

```bash
python3 tools/packbuilder/verify_pack.py <pack.db>   # comprueba payload_dict_sha256
```

Si el hash no corresponde, el pack está mal construido, no mal leído.

## "El pack no abre"

En orden: `schema_version` incompatible → `norm_version` incompatible → descarga truncada
(verificar sha256 del archivo) → FTS5 ausente en el SQLite que se está usando (si no es el
empacado, no hay FTS5).

## "La búsqueda se puso lenta"

```bash
sqlite3 <pack.db> "EXPLAIN QUERY PLAN SELECT id, headword, pos FROM entry WHERE norm >= 'cor' AND norm < 'cos' ORDER BY norm, rank DESC LIMIT 30"
```

Tiene que decir `COVERING INDEX idx_entry_norm`. Si dice `SCAN entry`, se perdió el índice o la
consulta dejó de encajar con él.

## Antes de dar por sentado que es un bug

**Preguntá primero si falta por decisión o por bug.** Desde D-116 hay palabras ausentes a
propósito, y desde D-121 hay una entrada menos porque su definición era una etiqueta de
mantenimiento del wiki. Ninguna de las dos es un contrato roto.

Después de descartarlo: este repo tiene **cuatro modos de falla conocidos y documentados**, y
tres de ellos no producen error. Leé `docs/contratos-cruzados.md` entero antes de escribir código nuevo para arreglar algo:
es probable que el mecanismo que falta ya esté descrito ahí.
