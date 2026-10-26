# Fuentes de datos, rankeadas

**Para qué es este documento.** Para decidir **qué pack vale la pena construir** sin volver a
medir. Cada fila dice qué trae la fuente, en qué calidad, bajo qué licencia, y si hoy se usa en
algún pack de la app. Las rechazadas se quedan acá con el número que las rechazó: son las filas
más útiles, porque evitan volver a intentarlo.

> **Una fila sin medición no entra.** Si dice un número, al lado dice cómo se obtuvo. Una fuente
> que nadie midió va en §Sin evaluar, no en la tabla.

*Last measured: 2026-10-26 (sources, copyleft and register, see §Sweep of 2026-10-26). Before that: 2026-09-21, the TRANSLATION sweep (§Traducciones ES↔EN).*

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

## Sweep of 2026-10-26: the RAE, English sources, copyleft, and the register we already have

Asked for: alternative sources including the RAE, extra things a pack could carry, how to tell
frequent and colloquial words apart for the filters, and then whether copyleft sources open up
**given that the app is GPL-3.0 and the packs are downloaded separately**. Licenses were read
through a fetch tool that summarises, so a quote below is close to verbatim but not guaranteed;
what was not read at all says **UNVERIFIED** or **ASSUMPTION**.

### The license question, settled first

- **The repository is GPL-3.0** (`LICENSE`). The packs already carry Wiktionary text, which the
  [Wikimedia Terms of Use](https://foundation.wikimedia.org/wiki/Policy:Terms_of_Use) §7 license
  as CC BY-SA 4.0 (and GFDL).
- **Creative Commons declared GPLv3 a BY-SA-compatible license for 4.0 on 2015-10-08, one way
  only** ([compatible licenses](https://creativecommons.org/share-your-work/licensing-considerations/compatible-licenses/),
  read whole): BY-SA 4.0 material may go into a GPLv3 adaptation, never the reverse. The page
  names 4.0 only, so BY-SA 3.0 (DBnary, the MCR) is not covered. Its "special considerations"
  page was not read: **UNVERIFIED**.
- **What follows for us.** A pack that *merges* GPL data with Wiktionary data must be released
  as GPLv3 as a whole, which is allowed. A **separate pack per license** needs no such question: the
  architecture already installs several packs of one language and queries them together (D-136),
  and each declares its sources and licenses in `meta` (D-138). The separate pack is the
  recommendation. It keeps every existing pack's license as it is today, and a GPL source can be
  dropped by not installing it.
- **GPL-2.0-only is the line.** A source that says "version 2" without "or later" cannot go into a
  GPLv3 work at all. The old verdict on this page, "GPL is viral on the data and clashes with CC
  BY-SA", was wrong in its reason. The real problems are the version and, below, the provenance.

| Source | License, as read | Or later? | GPLv3 | What it adds |
|---|---|---|---|---|
| **RAE** (DLE, DPD, DAMER, CORPES) | All rights reserved; reproduction and extraction prohibited (search snippet: the legal page answered 403) | — | ❌ | Nothing usable but facts. Never a definition or an example |
| **FreeDict `spa-eng` 0.3.1** | "GNU General Public License ver. 2.0 and any later version" (TEI header) | Yes | ✅ | Only **4,502** headwords (2019, from Ergane; FreeDict itself says "too small"). **Measured: +604 headwords**, with broken spacing (`nowandthen`) and wrong pairs (`papel pintado → tapestry`) |
| **FreeDict `eng-spa` 2025.11.23** | "Creative Commons Attribution-ShareAlike 3.0 Unported" (TEI header, read). **Not GPL at all** | — | n/a | 64,258 headwords, generated by WikDict "from Wiktionary.org via DBnary". **Measured: +353 headwords and +9,476 pairs** over what the packs and dumps already hold; a sample of 25 added pairs had 16 correct, 6 odd and 3 wrong (`free → obstáculo`) |
| **Apertium `eng-spa`** (renamed from `en-es`) | `COPYING` is the GPL **Version 2** text; the `.dix` carries no notice; no "or later" grant found | **No** (as far as read) | ❌ | 37,769 pairs, 10,250 of them proper names. **Measured: about 3,700 real headwords per direction** beyond ours once names and identical pairs are dropped; clean, but one machine-translation style term per sense (`vendedor de globos → balloons seller`) |
| **GCIDE** (Webster 1913 + additions) | "version 3 of the License, or (at your option) any later version" | Yes | ✅ | Old English definitions, etymology, quotations. Dated vocabulary |
| **RLA-ES** (Spanish hunspell) | Tri-license: GPL 3+, LGPL 3+ or MPL 1.1+ | Yes | ✅ (as GPL 3+) | Spelling forms and affix rules. Our dump's `forms` already covers inflections |
| **`th_es`** (Spanish thesaurus) | LGPL **v2.1**, no "or later" wording | No | **UNVERIFIED** | Synonym groups |
| **`hyph_es`** | GPL 3+ / LGPL 3+ / MPL 1.1+; the TeX original is MIT | Yes | ✅ | Hyphenation patterns. **Our dump already has syllables for 90.4 %** (below) |
| **CMUdict** | "completely unrestricted", BSD-style | — | ✅ | ARPAbet for ~130k English words (**ASSUMPTION** on the count) |
| **hyph-en-us** (TeX) | Free copying "with or without modification", notice kept; hyph-utf8 is MIT on CTAN | — | ✅ | English hyphenation patterns |
| **Moby** (thesaurus, pronunciator, hyphenator) | "Public domain in the USA" (Gutenberg) | — | ✅ | 30,260 thesaurus roots; noisy associations |
| **Roget 1911** | "Public domain in the USA" (Gutenberg) | — | ✅ | Category index, not per sense; dated |
| **ConceptNet 5** | CC BY-SA 4.0 | — | ✅ | Relations (UsedFor, IsA…); much of it is Wiktionary and WordNet we already have |
| **NGSL 1.2**, **Octanove C1/C2** | CC BY-SA 4.0 | — | ✅ | A core-word list (2,809) and CEFR C1/C2 levels |
| **CEFR-J 1.5** | Custom: research and commercial use "provided that you cite" | — | ? | CEFR A1–B2 per word and part of speech; redistribution inside a database is not addressed |
| Wordnik, Merriam-Webster, Oxford APIs | The terms forbid caching and local copies (Wordnik read; the others **ASSUMPTION**) | — | ❌ | — |
| Free Dictionary API (dictionaryapi.dev) | The code is GPL-3.0; the data's provenance is a scraper | — | ❌ | — |
| Wordset | The `LICENSE` returned 404 | — | **UNVERIFIED** | WordNet ancestry plus volunteer edits |
| Leipzig corpora, UD treebanks (es) | Contradictory terms; per treebank (AnCora is NC) | — | **UNVERIFIED** | Frequency and syntax |

**The conclusion is the same as on 2026-09-21, now for a different reason.** Copyleft does open
the door, but what walks through it is small. The one large bilingual source (FreeDict `eng-spa`)
is Wiktionary under another name, and is CC BY-SA 3.0 rather than GPL, and the one machine-translation one (Apertium) is GPL-2.0-only.
What GPL sources would really add is **hyphenation patterns and old English definitions** (GCIDE),
and the first is already in our dump.

### What our own dumps carry and the packs do not read

Measured 2026-10-26 by streaming both kaikki dumps, counting non-empty fields per live entry (no
`form-of`, no proper names): 90,248 entries in `es`, 1,292,211 in `en`.

| Field | es | en | Read today? | What it would give |
|---|---|---|---|---|
| `hyphenations` (syllables) | **90.4 %** | 1.8 % | No | Syllable division on the card: `ja·po·nés` |
| `sounds[].other`: stress type and syllable count | ~99 % (`aguda`, `trisílaba`) | — | No | "aguda, 3 sílabas", a Spanish-school staple |
| `sounds[].rhymes` | present (`ayer → eɾ`) | in IPA only | No | Rhymes, as a lookup by rhyme key |
| `sounds[].audio` (Commons files) | some (`casa`, `ayer`) | some | No | Recorded pronunciation. Each Commons file has **its own** license, and it costs bytes a watch pays |
| `idioms` / `proverbs` | 8.3 % / 0.5 % | — | No | Set phrases under the entry (`arce japonés`) |
| `sense.attestations` (first dated use) | — | 12,322 senses | No | "since c. 1480" |
| `sense.tags` / `raw_tags` (register, region) | 42,958 / 19,147 senses | 1,150,815 senses | **Only by the word-of-the-day tiers** (`discover.py`) | Register chips and a register filter, below |
| `sense.qualifier` | — | 21,452 | No | "preceded by *the*" |

Synonyms, antonyms, related terms, hypernyms and hyponyms are already read (D-124, D-126, D-132),
and so are IPA and etymology.

### Frequent and colloquial: what the data supports

**Frequency is already in the packs.** `sources/frequency.py` turns subtitle frequency into the
prior that orders results and feeds the *Común* tier. SPALEX's file also carries a `zipf` column
for its ~45,000 words, but it misses exactly the colloquial American vocabulary: `chévere`,
`boludo`, `fome` and `cachai` are absent. wordfreq (CC BY-SA 4.0 data, probed in a throwaway
venv) has them all: `boludo` 4.03, `chévere` 2.93, `fome` 2.94, `cachai` 2.31.

**Register is per sense, and a word-level filter must read the majority of its senses.**
Wiktionary marks "colloquial" on one sense of `domingo` or `amigo`. Counting a word as colloquial
only when at least half of its senses carry the tag:

| Group (tags folded in) | es: any sense | es: majority | of those in SPALEX: median known | en: any sense | en: majority |
|---|---|---|---|---|---|
| Colloquial (`colloquial`, `informal`, `slang`, `jocular`, `Lunfardo`…) | 6,434 | **4,780** | 82 % (934 words) | 39,884 | **33,369** |
| Vulgar/offensive (`vulgar`, `derogatory`, `offensive`…) | 1,849 | **1,303** | 86 % (309) | 10,698 | **9,097** |
| Formal/literary (`formal`, `literary`, `learned`, `poetic`) | 459 | **345** | 64 % (210) | 2,486 | **1,907** |
| Outdated (`outdated`, `obsolete`, `archaic`, `dated`) | 4,440 | **3,141** | 72 % (625) | 63,603 | **54,002** |
| Childish | 60 | 42 | — | 543 | 412 |

Out of 78,055 Spanish and 732,865 English words with a gloss. Region is the largest Spanish tag
family (Chile 3,540 senses, Argentina 2,916, Mexico 2,276…) and was not folded into these groups.

**What the numbers say.**
- *Colloquial* is **not** *frequent*. The colloquial majority's median Zipf in SPALEX is 2.2,
  below the 2.4 median of the whole list. Most are regional words and phrases (`la chancha y los
  veinte`, `chocolear`). A filter has to offer them as separate axes: *Común* (frequency) and
  *Coloquial* (register).
- The groups are big enough for a filter in both languages and small enough to mean something:
  6 % of Spanish words are colloquial by majority, 1.7 % vulgar.
- *Formal* in Spanish is thin (345). It is better merged with the existing *Rara* tier, which
  already reads the literary label, than offered on its own.

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
| **FreeDict `eng-spa`** | ⚠️ **GPL** | **64.258 lemas EN→ES** — justo la dirección débil | — | ❌ ~~GPL es viral sobre el dato~~ — corrected 2026-10-26: GPLv3 can take BY-SA 4.0, but this one is actually **CC BY-SA 3.0, built from Wiktionary** via WikDict: +353 headwords over our data. See §Sweep of 2026-10-26 |
| **Apertium `en-es`** | ⚠️ **GPL** | Diccionario bilingüe de MT | — | ❌ **GPL-2.0-only** (`COPYING`, no "or later" found): it cannot enter a GPL-3.0 work. See §Sweep of 2026-10-26 |

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

## La cita del ejemplo: cada dump la sirve con otra forma

Medido el 2026-09-22 sobre los dos dumps, muestreando 1 de cada 10 registros. Importa porque el
recorte que produce `kaikki._recorte_de_cita` **se calibra por idioma** (D-216): el separador vive
en `Perfil`, junto a los pesos del rank y por la misma razón que ellos.

| | Wiktionary inglés | Wikcionario español |
|---|---|---|
| Ejemplos con `type` | **100 %** (`quotation` 86,5 %, `example` 13,5 %) | **0 %** — el campo no existe |
| Ejemplos con `ref` | **75,5 %** de los que el builder guarda | **68,4 %** |
| Forma del `ref` | año primero, campos por **coma**: `1897, Richard Marsh, The Beetle:` | autor primero, año último, campos por **punto**: `Miguel Nicolau. Iniciación a la Teología. Página 85. 1984.` |
| `ref` completo | 119 B promedio | 92 B |
| Recortado a dos campos | **31 B** (p50 24 · p90 50 · p99 147 · máx 352) | **50 B** |
| Construido | ✅ desde D-216 | ⚙️ medido, **no construido**: falta declarar el separador en su `Perfil` |

⚠️ **`type` es un campo sólo del Wiktionary inglés, y es el discriminador que uno querría.** Dice
si la frase fue *redactada para ilustrar* (`example`) o *citada de un texto publicado*
(`quotation`). En español no está, así que ahí lo único que distingue una cita es que traiga
`ref`.

⚠️ **Reordenar para preferir el ejemplo redactado rinde poco**: sólo el **7,3 %** de las
acepciones con ejemplo tiene los dos tipos. El 86,5 % de citas no es un defecto de la extracción,
es lo que el Wiktionary inglés tiene.

⚠️ **Dos defectos del recorte los encontró leer el pack construido, no los tests** — que es el
paso 4 de esta misma página. `captive` salía como `1850, [Alfred`, porque el Wiktionary encierra
el nombre editorial del autor entre corchetes (`[Alfred, Lord Tennyson]`) y esa coma es interna:
**369 citas (1,3 %)**, todas con el corchete sin cerrar. Y otras **184 (0,64 %)** traen el `ref`
entero envuelto, donde el par no cierra nunca. Con los dos arreglos, las citas desbalanceadas
pasan de 184 a **0 de 28.744**.

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
