# Fuentes de datos, rankeadas

**Para qué es este documento.** Para decidir **qué pack vale la pena construir** sin volver a
medir. Cada fila dice qué trae la fuente, en qué calidad, bajo qué licencia, y si hoy se usa en
algún pack de la app. Las rechazadas se quedan acá con el número que las rechazó: son las filas
más útiles, porque evitan volver a intentarlo.

> **Una fila sin medición no entra.** Si dice un número, al lado dice cómo se obtuvo. Una fuente
> que nadie midió va en §Sin evaluar, no en la tabla.

*Última medición: 2026-09-21 (barrido de fuentes de TRADUCCIÓN, ver §Traducciones ES↔EN).*

---

## El ranking

| # | Fuente | Licencia | Qué aporta | Medido | Estado |
|---|---|---|---|---|---|
| 1 | **Wikcionario español** vía kaikki.org | CC BY-SA 4.0 | Definiciones en español, formas flexionadas, sinónimos, antónimos, relacionadas | **114.619 entradas, 68,3 MB.** 1,49 acepciones y 12,98 formas por entrada | ✅ **En uso** — `es-def-wikc` |
| 2 | **Wiktionary inglés** vía kaikki.org | CC BY-SA 4.0 | Lo mismo, en inglés | **794.355 entradas, 272,4 MB.** 1,29 acepciones y 1,14 formas por entrada | ✅ **En uso** — `en-def-wikt` |
| 3 | **Tatoeba** (corpus de oraciones) | CC BY 2.0 FR | Frases de uso reales. No define nada | **6.499 entradas ganan ejemplo, +368 KB.** 442.135 oraciones miradas | ⚙️ **Construible hoy** — `--frases` (D-137) |
| 4 | **Wikidata Lexemes** | **CC0** (sin atribución obligatoria) | Definiciones cortas, gentilicios regionales, locuciones | **15.269 entradas, 4,4 MB; 5.092 (33,3 %) NO están en el pack 1** | ✅ **Construido** — `es-def-wd` (D-139) |
| 5 | **Open English WordNet 2024** | CC BY 4.0 | Sinónimos y antónimos agrupados por significado | **+30.423 entradas con sinónimos, +2.376 con antónimos** | ✅ **En uso** — `--tesauro` (D-144) |
| 6 | **Multilingual Central Repository** (vía OMW) | CC BY 3.0 | Sinónimos en español | **+3.801 entradas**; trae ruido que hay que filtrar | ✅ **En uso** — `--tesauro` (D-144) |
| 7 | **Wiktionary inglés, sección Spanish** | CC BY-SA 4.0 | Ejemplos de uso en español | **307 entradas, +8 KB** | ⚙️ Construible (`--ejemplos`), **rinde 21× menos que Tatoeba** (D-135) |
| 8 | **Open English WordNet como pack completo** | CC BY 4.0 | Definiciones en inglés | Spike medido, no es pack de producción (D-120). Su **tesauro** sí se usa, ver fila 5 | 🟡 Spike, congelado |

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
| **Wikcionario de otros idiomas, sección español** | Cada wiki define distinto | Las glosas están en el idioma del wiki: sería un pack **bilingüe**, no monolingüe (D-034) |
| **Spanish WordNet (MCR)** | Estructura de synsets en español | **Ya se usa** como tesauro (D-144), con licencia CC BY 3.0 vía OMW. ⚠️ **Como puente de traducción está medido y descartado**: sólo el 0,6 % de sus synsets existe en OEWN 2024 y 342 de esas 435 coincidencias son colisiones de offset (`soñador ↔ diner`). Ver roadmap §Completing the translations |

---

## Traducciones ES↔EN: el barrido completo

**Medido el 2026-09-21**, y hecho porque faltaba: hasta ese día se habían medido las fuentes que
ya estaban en disco o ya listadas acá, nunca *qué existe en el mundo* para traducir entre español
e inglés. El resultado no cambia la recomendación, pero ahora la respalda un barrido en vez de la
casualidad de qué se había descargado.

| Fuente | Licencia | Qué da para ES↔EN | Medido | Veredicto |
|---|---|---|---|---|
| **Wikcionario `es.jsonl`** *(ya en disco)* | CC BY-SA 4.0 | Tabla de traducciones **con `sense_index` declarado** | **34.710 pares; 18.817 acepciones alcanzadas** expandiendo rangos | 🏆 **La mejor, y el pipeline no la lee** |
| **Wiktionary `en.jsonl`** *(ya en disco)* | CC BY-SA 4.0 | Pares EN→ES con el **texto** de la acepción inglesa | **10.410 pares, 100 % con texto de acepción** | ✅ Complemento para la dirección inversa |
| **DBnary** | CC BY-SA 3.0 | Traducciones desambiguadas por acepción, 27 ediciones | **30.723 ES→EN, sólo 9.169 (29,8 %) ligadas a acepción**, sobre 5.661 lemas | ❌ **Misma fuente, la mitad del rendimiento** |
| **Wikidata Lexemes** *(ya en disco)* | CC0 | `P5137` = ítem del concepto, y alguna glosa inglesa | 20.872 acepciones; **6.324 (30,3 %) con `P5137`**, 1.629 (7,8 %) con glosa EN | ❌ Chica, y **le falta la otra orilla** |
| **PanLex** | ⚠️ **CC BY-NC-SA 4.0** | Base panlingüe enorme | — | ❌ **`NonCommercial` la bloquea** |
| **FreeDict `eng-spa`** | ⚠️ **GPL** | **64.258 lemas EN→ES** — justo la dirección débil | — | ❌ GPL es viral sobre el dato y choca con CC BY-SA |
| **Apertium `en-es`** | ⚠️ **GPL** | Diccionario bilingüe de MT | — | ❌ Lo mismo (ya estaba anotado) |

⚠️ **La licencia de PanLex es el ejemplo de por qué este repo exige fuente primaria.** El buscador
la resume como **CC0**; su propia página de licencia dice *«Creative Commons
Attribution-NonCommercial-ShareAlike 4.0 International License»* y exige permiso escrito para uso
comercial. Creerle al resumen habría metido una cláusula NC dentro de un pack que se distribuye.

**Por qué DBnary pierde contra leer el dump directo**, que es contraintuitivo porque DBnary
*existe* para esto: desambigua sólo cuando puede casar la glosa de la tabla de traducción con una
acepción, mientras que `sense_index` **viene declarado por el wiki** y no hay nada que casar. Su
valor real son las **otras 25 ediciones**, no el par que ya tenemos. Su calidad donde sí liga es
buena —`francés__adjetivo__1 → French`, `francés__sustantivo_masculino__2 → blowjob`, con las
acepciones bien separadas— así que la fila la rechaza el rendimiento, no el dato.

**Por qué a Wikidata le falta la otra orilla**: `P5137` apunta a un ítem de Wikidata —`berilio →
Q569`, `vino → Q282`—, que es el concepto y **tiene etiqueta en todo idioma**. Sería un puente
alineado por acepción y CC0. Pero las etiquetas viven en el dump de **ítems**, que son más de
100 GB, no en el de lexemas que ya tenemos. Y sus glosas inglesas directas (7,8 %) son
**definiciones en inglés**, no términos — el mismo problema de forma que el pack bilingüe:
`parecer → "to seem to be a certain way"`.

**Conclusión, y es la misma de antes pero ahora con el barrido detrás:** de todo lo que existe con
licencia usable, **lo mejor ya está en disco y sin leer**. Las dos fuentes grandes que podrían
haber ayudado —PanLex y FreeDict, ésta última justo en la dirección débil— están bloqueadas por
licencia, no por calidad.

---

## Un pack que no construimos nosotros

Desde D-136 conviven varios packs del mismo idioma, así que uno puede venir de la comunidad. Las
dos formas en que un pack ajeno puede hacer daño, y qué lo frena:

| Riesgo | Síntoma | Qué lo frena |
|---|---|---|
| **Claves mal normalizadas** | **Faltan palabras**, sin error ni log | `PackFile.open` recalcula `norm()` y `fuzzy()` sobre **64 entradas repartidas** y rechaza el pack (D-142). Convierte `norm_version` de declaración en prueba |
| **`rank` mal calibrado** | Su basura sale primera | El orden usa una **banda de cobertura** calculada del texto escrito y del lema, **sin mirar ningún dato del pack** (D-142). El `rank` sólo decide *dentro* de una banda |
| **Manifiesto incompleto** | No se sabe si se puede redistribuir | `verify_pack.py` exige `sources` con **una licencia por fuente** (D-138) |
| **`pack_id` genérico** | Pisa a otro al instalarse | Gramática verificada `<idioma>-<tipo>-<fuente>` (D-138) |
| ⚠️ **Atribución inventada** | Un sinónimo o una traducción colgada de la acepción equivocada — **se lee plausible** | **Nada, hoy.** Es la única fila sin freno: el payload tiene un solo canal, así que embadurnar dato de entrada por todas las acepciones es gratis e invisible. Ver roadmap §Enforcing the contract |

**Para saber cuánto se parecen dos calibraciones**, hay una herramienta y no una intuición:

```sh
python3 tools/packbuilder/compare_calibration.py pack_a.db pack_b.db
```

Calcula **Spearman sobre las entradas que comparten `uid`** — correlación de *órdenes*, así que
no le importa que un pack use 0..1000 y otro 0..100. Medido entre los dos packs de español:

```
entradas en comun (por uid): 8.595
rho de Spearman   : +0.388
control (barajado): +0.001   <- 'sin relacion' para este n
top 200 compartido: 110 de 200
```

Dos fuentes honestas que comparten señal sin ser intercambiables. **Un ρ bajo no condena al
pack**: dice cuánto se está apoyando la mezcla en una calibración ajena.

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
