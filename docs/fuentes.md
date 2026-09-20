# Fuentes de datos, rankeadas

**Para qué es este documento.** Para decidir **qué pack vale la pena construir** sin volver a
medir. Cada fila dice qué trae la fuente, en qué calidad, bajo qué licencia, y si hoy se usa en
algún pack de la app. Las rechazadas se quedan acá con el número que las rechazó: son las filas
más útiles, porque evitan volver a intentarlo.

> **Una fila sin medición no entra.** Si dice un número, al lado dice cómo se obtuvo. Una fuente
> que nadie midió va en §Sin evaluar, no en la tabla.

*Última medición: 2026-09-20.*

---

## El ranking

| # | Fuente | Licencia | Qué aporta | Medido | Estado |
|---|---|---|---|---|---|
| 1 | **Wikcionario español** vía kaikki.org | CC BY-SA 4.0 | Definiciones en español, formas flexionadas, sinónimos, antónimos, relacionadas | **114.619 entradas, 68,3 MB.** 1,49 acepciones y 12,98 formas por entrada | ✅ **En uso** — `es-def-wikc` |
| 2 | **Wiktionary inglés** vía kaikki.org | CC BY-SA 4.0 | Lo mismo, en inglés | **794.355 entradas, 272,4 MB.** 1,29 acepciones y 1,14 formas por entrada | ✅ **En uso** — `en-def-wikt` |
| 3 | **Tatoeba** (corpus de oraciones) | CC BY 2.0 FR | Frases de uso reales. No define nada | **6.499 entradas ganan ejemplo, +368 KB.** 442.135 oraciones miradas | ⚙️ **Construible hoy** — `--frases` (D-137) |
| 4 | **Wikidata Lexemes** | **CC0** (sin atribución obligatoria) | Definiciones cortas, gentilicios regionales, locuciones | **15.269 entradas, 4,4 MB; 5.092 (33,3 %) NO están en el pack 1** | ✅ **Construido** — `es-def-wd` (D-139) |
| 5 | **Wiktionary inglés, sección Spanish** | CC BY-SA 4.0 | Ejemplos de uso en español | **307 entradas, +8 KB** | ⚙️ Construible (`--ejemplos`), **rinde 21× menos que Tatoeba** (D-135) |
| 6 | **Open English WordNet 2025** | CC BY 4.0 | Definiciones en inglés, estructura de synsets | Spike medido, no es pack de producción (D-120) | 🟡 Spike, congelado |

---

## Qué trae cada una, y qué le falta

### 1. Wikcionario español — la base
Es el pack. **Su límite está medido y es la conclusión más importante de este documento**: de las
80.744 entradas *flacas* (una acepción, sin ejemplo — el **70,4 %** del pack), sólo el **25,4 %**
traía algún campo sin usar, y **el 20,3 % eran sinónimos que ya entraban**. Lo nuevo que quedaba
—`related`, `hypernyms`, `hyponyms`— sumó 4,7 % de las entradas (D-132).

**No queda nada más que sacarle.** Cualquier mejora al pack español pasa por otra fuente o por el
orden de resultados.

### 2. Wiktionary inglés — por qué es 4× más grande
La pregunta *"¿por qué el inglés está tan cargado?"* tiene una respuesta contraintuitiva:

| | español | inglés |
|---|---|---|
| entradas | 114.619 | **794.355** |
| **bytes por entrada** | **596** | 343 |
| acepciones por entrada | **1,49** | 1,29 |
| formas flexionadas por entrada | **12,98** | 1,14 |
| % con sinónimos | **23,1 %** | 15,4 % |
| % con ejemplo | 11,4 % | **33,3 %** |

**No está más cargado por entrada: tiene 6,9× más entradas.** Por entrada el español es *más*
denso —casi el doble de bytes— porque es una lengua flexiva: 12,98 formas por lema contra 1,14, y
la tabla `form` se lleva 33 de sus 68 MB. El inglés gana en una sola cosa, los ejemplos, y ese es
exactamente el hueco que las fuentes 3 y 5 vienen a tapar.

### 3. Tatoeba — el mejor rendimiento medido
**6.499 entradas** ganan una frase por **+368 KB**. Rinde 21× más que la fuente 5 porque un
ejemplo de corpus **no necesita que dos fuentes coincidan en cómo numeran las acepciones**: sólo
necesita contener la palabra sin ambigüedad.

⚠️ **Dos filtros que costaron caro y no se pueden relajar** (D-137): la palabra tiene que llevar a
**una sola** entrada del pack (cuesta la mitad del alcance: 14.023 → 7.019), y tiene que aparecer
en minúscula alguna vez en el corpus (cuesta 518 más, y es lo que impidió que *nadal* recibiera
una frase sobre el tenista).

⚠️ **CC BY 2.0 FR, no CC0.** El export `sentences_CC0` trae **37 frases en español** de 562.186.

### 4. Wikidata Lexemes — construido (D-139)
**El único CC0 de la lista.** De 66.935 lexemas españoles, 15.814 traen glosa en español; el pack
resultante tiene **15.269 entradas en 4,4 MB**, y **5.092 (33,3 %) no están en el pack 1**.

Lo que aporta es **complementario, no redundante**: gentilicios regionales (*iquiteño*,
*huantino*, *ucayalino*, *abiyanés*) y locuciones (*a su vez*, *entre tanto*, *así como así*) —
justo lo que un wiki editado desde España cubre peor.

**Por qué sería un pack aparte y no un merge.** Es un *segundo pack base* del mismo idioma, que es
exactamente lo que `SearchRepository` habilitó (D-136): los dos se instalan, los dos se consultan,
y la ganancia es la unión. Fusionarlo dentro del pack 1 obligaría a decidir cuál definición gana
cuando las dos tienen la palabra, y eso no hay medición que lo resuelva.

**Construido y verificado.** `sources/wikidata.py`, dump de 450 MB, **4,4 MB** de pack. CC0 no suma
obligación de atribución a nadie, y se declara igual en el manifiesto.

⚠️ **Medido después de construirlo: 8.595 `uid` coinciden con el pack 1.** Esa es la llave de la
composición funcionando — y habrían sido **cero** si el pack usara el id del lexema como
`sense_key` siempre, que fue el primer intento. Ver D-139.

### 5. Wiktionary inglés §Spanish — construido y casi vacío
307 entradas. El roadmap lo estimaba en 5.307, pero ese número **cuenta los casos donde habría
que inventar la atribución**. Ver D-135 para el embudo completo. Está implementado y funciona; la
recomendación es usar la fuente 3 en su lugar.

### 6. Open English WordNet — spike
Medido en D-120. Estructura de synsets muy buena, pero es otro modelo de datos y no se integró.

---

## Sin evaluar

Nadie las midió, así que no tienen fila en el ranking. Si alguna se evalúa, **el número va acá
antes que el código**.

| Candidata | Por qué podría servir | Lo que hay que averiguar primero |
|---|---|---|
| **DBnary** | Extracción RDF de varios Wikcionarios | ¿Aporta algo sobre kaikki, o es la misma fuente con otro formato? |
| **Wikcionario de otros idiomas, sección español** | Cada wiki define distinto | Las glosas están en el idioma del wiki: sería un pack **bilingüe**, no monolingüe (D-034) |
| **FreeDict / Apertium** | Pares bilingües ya hechos | Licencia (Apertium es GPL, y eso es viral sobre el dato) |
| **Spanish WordNet (MCR)** | Estructura de synsets en español | La licencia del MCR no es abierta sin acuerdo; hay que leerla antes de bajar nada |

---

## Cómo se agrega una fuente

1. **Medirla contra el pack que ya existe**, no en abstracto: lo que importa es *cuántas entradas
   que hoy están flacas dejarían de estarlo*, o *cuántos lemas nuevos aporta*. Una fuente enorme
   que repite lo que ya hay vale cero.
2. **Leer la licencia en la fuente primaria** y anotarla acá antes de escribir código.
3. **Declararla en `build_pack.FUENTES`**. De esa tabla salen la prosa de `meta.attribution` y la
   lista estructurada de `meta.sources`, así que no hay forma de sumar contenido sin sumar el
   crédito (D-138).
4. **Mirar el pack construido, no el conteo.** Las dos fallas de contenido de esta sesión
   —`abbacy → "more at abbot § Related terms"` y `nadal → "Alonso, Nadal y Pau Gasol…"`— pasaron
   los tests y el gate. Las encontró leer entradas.
