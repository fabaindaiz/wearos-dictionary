"""Construye un pack real monolingue desde un dump de kaikki.org.

    python3 build_pack.py <lang> <kaikki.jsonl> <salida.db> [--sample N] [--nombres POLITICA]
                          [--ejemplos <es-en-wikt.jsonl>] [--frases <tatoeba-spa.tsv>]
                          [--tesauro <wordnet>] [--sumar <pack> <dump>]

`--sample N` construye un pack piloto con 1 de cada N lemas, elegidos por hash del headword:
determinista y **sin sesgo posicional**, a diferencia de cortar por las primeras N lineas. Sirve
para mirar el contenido y estimar el tamano antes de gastar el build completo.

Los dumps se bajan de kaikki.org (paginas procesadas por idioma; el formato crudo esta
deprecado). **Cual es cual es el error facil**, porque los tres existen y son datasets distintos:

    es -> https://kaikki.org/eswiktionary/Español/...   definiciones EN ESPAÑOL de palabras
                                                        españolas. 1,42 GB.
    en -> https://kaikki.org/dictionary/English/...     definiciones EN INGLES de palabras
                                                        inglesas. 3,24 GB.

    enwiktionary seccion Spanish da palabras españolas con glosa **en ingles**: eso es un pack
    BILINGUE y no lo construye este script.

**Los nombres propios no entran** (D-116): apellidos, toponimos y nombres de pila se descartan
por defecto. Lo que se saca: 32.305 entradas en español (22,1 %, de las cuales 26.265 definen
solamente "Apellido.") y 163.470 en ingles (17,1 %, 40,7 MB).

`--nombres POLITICA` cambia eso. Son tres y estan medidas en español (D-134):

    lexical-only       el default. 114.619 entradas, 68,3 MB
    definitions-only   entra el que DEFINE y no el que solo se registra, con el rank
                       castigado. Rescata ciudades, generos taxonomicos, grafias anticuadas
    included           entran todos. 146.193 entradas, 73,3 MB. Existe para MEDIR

Las dos que no son el default **sufijan el `pack_id`**, asi que se pueden instalar al lado del
pack normal y compararse en el reloj. `--con-nombres` sigue funcionando como alias de
`--nombres included`.

`--ejemplos` suma una **segunda fuente**: los ejemplos de uso en español del Wiktionary ingles,
seccion Spanish (D-135). Solo llena entradas flacas y solo donde no hay atribucion que inventar,
asi que el numero es chico -- **326 entradas, 0,28 %**. ⚠️ **Cambia la atribucion del pack**,
porque usar dos fuentes obliga a nombrar a las dos: por eso es una opcion y no un default. Las
dos son CC BY-SA 4.0.

`--frases` suma una **tercera fuente**: el corpus Tatoeba (D-137). Rinde **23 veces mas** que
`--ejemplos` --7.019 entradas contra 307-- porque un ejemplo de corpus no necesita que las dos
fuentes coincidan en como numeran las acepciones: solo necesita contener la palabra **sin
ambiguedad**, y eso lo comprueba el builder contra su propio indice. ⚠️ Tatoeba es **CC BY 2.0
FR** y tambien cambia la atribucion. Las dos opciones se pueden combinar.

`--sumar <pack> <dump>` funde el **vocabulario** de otro pack del catalogo dentro de este: entran
los lemas que la fuente base no tiene y se descartan los repetidos (D-146). `--sumar es-wd
<lexemas>` aporta **6.092 lemas** al pack español --gentilicios regionales, locuciones-- por
~1,5 MB. ⚠️ Es una union de FILAS: no parte ninguna entrada, asi que no necesita composicion.

`--tesauro` suma **sinonimos y antonimos de WordNet** (D-144), que estan agrupados por
SIGNIFICADO y por lo tanto no dependen de que alguien los escribiera a mano. El formato lo elige
el idioma del pack: **WN-LMF** (`english-wordnet-*.xml.gz`, CC BY 4.0) para el ingles y el
**`.tab` de OMW** (`wn-data-spa.tab`, CC BY 3.0) para el español. Rinde **21.143 entradas** en
ingles --mas 2.486 con antonimos-- y **5.504** en español. ⚠️ Tambien cambia la atribucion.
"""

import hashlib
import os
import sys

from sources import bilingual, enwikt_examples, kaikki, oewn, tatoeba, wikidata, wordnet

from build import PackBuilder

# El CATALOGO de fuentes, y la razon de que sea una tabla y no texto suelto (D-138).
#
# Cada fila es una declaracion: quien aporto que, desde donde, y bajo que licencia. De aca salen
# **las dos** cosas que el pack lleva -- la prosa de `meta.attribution` y la lista estructurada de
# `meta.sources` -- asi que **no hay forma de sumar contenido sin sumar el credito**: es un dato,
# no un parrafo que alguien tiene que acordarse de editar.
#
# ⚠️ **Una licencia por fuente, no una por pack.** El pack español con `--frases` mezcla
# definiciones CC BY-SA 4.0 con frases CC BY 2.0 FR. Un solo nombre para todo el pack o reclama de
# mas o acredita de menos, y la atribucion es la CONDICION de uso del dato (D-031).
#
# `codigo` es tambien el que aparece en el `pack_id` (ver GRAMATICA_DE_PACK_ID), para que dos
# packs del mismo idioma y distinta fuente no colisionen.
FUENTES = {
    "wikc": {
        "codigo": "wikc",
        "rol": "definitions",
        "nombre": "Wikcionario (es.wiktionary.org)",
        "url": "https://kaikki.org/eswiktionary/Espa%C3%B1ol/",
        "licencia": "CC BY-SA 4.0",
        "licencia_url": "https://creativecommons.org/licenses/by-sa/4.0/",
        "prosa": ("Definiciones del Wikcionario (es.wiktionary.org), licencia CC BY-SA 4.0. "
                  "Extracción: kaikki.org / wiktextract (Tatu Ylonen)."),
    },
    "wikt": {
        "codigo": "wikt",
        "rol": "definitions",
        "nombre": "Wiktionary (en.wiktionary.org)",
        "url": "https://kaikki.org/dictionary/English/",
        "licencia": "CC BY-SA 4.0",
        "licencia_url": "https://creativecommons.org/licenses/by-sa/4.0/",
        "prosa": ("Definitions from Wiktionary (en.wiktionary.org), CC BY-SA 4.0. "
                  "Extraction: kaikki.org / wiktextract (Tatu Ylonen)."),
    },
    "enwikt-ej": {
        "codigo": "ej",
        "rol": "examples",
        "nombre": "Wiktionary en inglés, sección Spanish",
        "url": "https://kaikki.org/dictionary/Spanish/",
        "licencia": "CC BY-SA 4.0",
        "licencia_url": "https://creativecommons.org/licenses/by-sa/4.0/",
        "prosa": ("Ejemplos de uso del Wiktionary en inglés (en.wiktionary.org), sección "
                  "Spanish, licencia CC BY-SA 4.0."),
    },
    # El MISMO volcado que "enwikt-ej", con otro rol: alla aporta ejemplos a un pack espanol,
    # aca aporta las definiciones enteras -- y en ingles, que es lo que lo vuelve bilingue.
    "enwikt": {
        "codigo": "enwikt",
        "rol": "definitions",
        "nombre": "Wiktionary en inglés, sección Spanish",
        "url": "https://kaikki.org/dictionary/Spanish/",
        "licencia": "CC BY-SA 4.0",
        "licencia_url": "https://creativecommons.org/licenses/by-sa/4.0/",
        "prosa": ("Definiciones en inglés de palabras españolas, del Wiktionary en inglés "
                  "(en.wiktionary.org), sección Spanish, licencia CC BY-SA 4.0."),
    },
    "tatoeba": {
        "codigo": "tat",
        "rol": "sentences",
        "nombre": "Tatoeba",
        "url": "https://tatoeba.org/",
        "licencia": "CC BY 2.0 FR",
        "licencia_url": "https://creativecommons.org/licenses/by/2.0/fr/",
        "prosa": "Frases de ejemplo del corpus Tatoeba (tatoeba.org), licencia CC BY 2.0 FR.",
    },
    "wd": {
        "codigo": "wd",
        "rol": "definitions",
        "nombre": "Wikidata Lexemes",
        "url": "https://www.wikidata.org/wiki/Wikidata:Lexicographical_data",
        "licencia": "CC0 1.0",
        "licencia_url": "https://creativecommons.org/publicdomain/zero/1.0/",
        # Se declara igual aunque CC0 no lo exija: de donde viene un dato es util saberlo
        # aunque no sea obligatorio decirlo.
        "prosa": ("Definiciones de Wikidata Lexemes (wikidata.org), dedicadas al dominio "
                  "público bajo CC0 1.0."),
    },
    "mcr": {
        "codigo": "wn",
        "rol": "relations",
        "nombre": "Multilingual Central Repository, vía Open Multilingual Wordnet",
        "url": "https://adimen.si.ehu.es/web/MCR/",
        "licencia": "CC BY 3.0",
        "licencia_url": "https://creativecommons.org/licenses/by/3.0/",
        "prosa": ("Sinónimos del Multilingual Central Repository (adimen.si.ehu.es/web/MCR/), "
                  "distribuido por Open Multilingual Wordnet, licencia CC BY 3.0."),
    },
    "oewn-tesauro": {
        "codigo": "wn",
        "rol": "relations",
        "nombre": "Open English WordNet",
        "url": "https://en-word.net/",
        "licencia": "CC BY 4.0",
        "licencia_url": "https://creativecommons.org/licenses/by/4.0/",
        "prosa": ("Synonyms and antonyms from Open English WordNet (en-word.net), CC BY 4.0."),
    },
    "oewn": {
        "codigo": "oewn",
        "rol": "definitions",
        "nombre": "Open English WordNet",
        "url": "https://en-word.net/",
        "licencia": "CC BY 4.0",
        "licencia_url": "https://creativecommons.org/licenses/by/4.0/",
        "prosa": "Open English WordNet 2025, CC BY 4.0.",
    },
}


def _declarar(metadata, clave):
    """Suma una fuente al manifiesto del pack: la prosa y la fila estructurada, juntas.

    Es una sola funcion para que **no exista** una forma de agregar contenido y olvidar el
    credito. El modo de falla que evita es silencioso: el pack sale entero, abre, funciona, y
    esta mal licenciado -- nada en el contenido lo delata.
    """
    fuente = FUENTES[clave]
    fila = "\t".join((fuente["rol"], fuente["nombre"], fuente["url"],
                       fuente["licencia"], fuente["licencia_url"]))
    metadata["sources"] = (metadata.get("sources", "") + fila + "\n")
    prosa = metadata.get("attribution", "")
    metadata["attribution"] = (prosa + " " + fuente["prosa"]).strip()
    return fuente

# D-031: el contenido es CC BY-SA y la pantalla de atribucion no es opcional. Estas dos claves
# son lo que la app tiene que mostrar; sin ellas el pack no cumple la licencia de los datos.
#
# `name` es CORTO y `description` lleva el texto largo (D-125). El nombre se muestra en una
# fila de reloj --en el selector del inicio, en la pantalla de diccionarios, en el dialogo de
# borrar y en la atribucion-- y "Español - definiciones" son 22 caracteres: se cortaba en los
# cuatro. Lo que el nombre largo decia --que trae definiciones-- sale ahora de `kind`, que es un
# dato y no una cadena que alguien tiene que leer.
#
# `proper_nouns` declara la politica de contenido del pack (D-116). Se escribe en `meta` el
# valor EFECTIVO, no el declarado: meta tiene que decir que paso, no que se pretendia.
#
# "lexical-only" y no "excluded" porque la poda tiene una excepcion medida: el nombre propio con
# vida lexica --los meses, los paises, los idiomas-- se conserva. Ver SENAL_LEXICA_MINIMA en
# sources/kaikki.py.
#
# `source_date` es la fecha del DUMP en AAAAMMDD, y es informativa: dice de que volcado sale el
# contenido. **No es la version del pack.** Esa la deriva el builder del reloj del build
# (`build.data_version`), porque reconstruir el mismo dump con otro builder tiene que dar un
# numero distinto -- si no, `devpack.py` y el instalador leen "es el mismo pack" y un pack mejor
# no se propaga nunca.
PACKS = {
    "es": {
        "pack_id": "es-def-wikc",
        "kind": "monolingual",
        "name": "Español",
        "description": (
            "Definiciones en español del Wikcionario, sin nombres propios. "
            "Incluye sinónimos, antónimos y palabras relacionadas por acepción."
        ),
        "lang_src": "es",
        # ⚠️ **El idioma en que estan las traducciones del payload, y es una DECLARACION que el
        # lector necesita**: sin ella la ficha muestra una lista de palabras inglesas sin decir
        # que son inglesas. No convierte el pack en bilingue --`kind` sigue siendo monolingual y
        # `lang_dst` sigue vacio-- porque no se puede BUSCAR por ellas: son contenido de lectura.
        # El canal de busqueda es la tabla `trans`, que en este pack sigue vacia.
        # ⚠️ **El IDIOMA al que apuntan las traducciones, y a proposito no un pack.**
        #
        # La primera version declaraba tambien `translations_pack = "en-def-wikt"`. Se saco: si
        # el usuario tiene instalado el **nucleo** ingles y no el completo, el enlace moria
        # aunque hubiera un diccionario ingles perfectamente capaz de resolverlo. Nombrar el
        # idioma deja que lo resuelva cualquier pack instalado de ese idioma.
        #
        # La acepcion viaja aparte, como sufijo del item, con un codigo que **tampoco** nombra un
        # pack (ver `payload.sense_code`): es unico para `(idioma, palabra, acepcion)` y el
        # nucleo y el completo lo comparten por construccion.
        "translations_to": "en",
        "fuzzy_profile": "es",
        "source_date": "20260915",
        "license": "CC-BY-SA-4.0",
        # La atribucion NO se escribe aca: se deriva de FUENTES[fuente_base] (D-138), para
        # que sumar contenido y sumar credito sean el mismo acto.
        "fuente_base": "wikc",
        "source_url": "https://kaikki.org/eswiktionary/Espa%C3%B1ol/",
        "proper_nouns": "included",
    },
    # El SEGUNDO pack base de español (D-139). No reemplaza al del Wikcionario: se instala al
    # lado y se consulta junto con el (D-136), y la ganancia es la union de lemas -- 5.283 que el
    # otro no tiene, medidos. Es ademas el unico CC0 del catalogo.
    "es-wd": {
        "pack_id": "es-def-wd",
        "kind": "monolingual",
        "name": "Español (Wikidata)",
        "description": (
            "Definiciones en español de Wikidata Lexemes. Segundo diccionario de español: "
            "aporta gentilicios regionales y locuciones que el Wikcionario cubre peor."
        ),
        "lang_src": "es",
        "fuzzy_profile": "es",
        "source_date": "20260920",
        "license": "CC0-1.0",
        "fuente_base": "wd",
        "source_url": "https://dumps.wikimedia.org/wikidatawiki/entities/",
        "proper_nouns": "included",
    },
    # El tercer pack: BILINGUE, y el unico que llena `trans`.
    #
    # ⚠️ **Un solo pack sirve para las dos direcciones, y la clave es `trans`**: las entradas son
    # palabras espanolas con glosas en ingles, asi que buscar "perro" la encuentra por prefijo y
    # buscar "dog" la encuentra por el indice inverso (peldano 3 de la cascada). Lo que NO da es
    # la calidad de un pack escrito en la otra direccion: `trans` se deriva de las glosas, no
    # viene en el volcado. Ver sources/bilingual.py.
    "es-en": {
        "pack_id": "es-tr-enwikt",
        "kind": "bilingual",
        "name": "Español → English",
        "description": (
            "Palabras en español definidas en inglés, del Wiktionary en inglés. "
            "Se puede buscar en los dos idiomas."
        ),
        "lang_src": "es",
        "lang_dst": "en",
        "fuzzy_profile": "es",
        "source_date": "20260915",
        "license": "CC-BY-SA-4.0",
        "fuente_base": "enwikt",
        "source_url": "https://kaikki.org/dictionary/Spanish/",
        "proper_nouns": "included",
    },
    "en": {
        "pack_id": "en-def-wikt",
        "kind": "monolingual",
        "name": "English",
        "description": (
            "English definitions from Wiktionary, proper nouns pruned. "
            "Includes synonyms, antonyms and related words per sense."
        ),
        # ⚠️ **El ingles SI traduce, por el canal de la palabra.** Se habia concluido que no
        # podia, midiendo que sus 9.987 traducciones al español traen **0 `sense_index`** -- pero
        # eso solo cierra el canal `T`, que exige atribucion por acepcion. El canal `W` existe
        # justamente para lo no atribuible, asi que esas 9.987 entran, y de paso llenan `trans`:
        # es lo que hace que buscar `perro` encuentre `dog` en el pack ingles.
        "translations_to": "es",
        "lang_src": "en",
        "fuzzy_profile": "en",
        "source_date": "20260909",
        "license": "CC-BY-SA-4.0",
        "fuente_base": "wikt",
        "source_url": "https://kaikki.org/dictionary/English/",
        "proper_nouns": "included",
    },
    # SPIKE (D-120). Existe para medir, no es un pack de produccion: no esta en el catalogo y
    # no se sube al reloj. Ver sources/oewn.py.
    "en-core": {
        "pack_id": "en-core-oewn",
        "kind": "monolingual",
        "name": "English core",
        "description": "Spike: Open English WordNet 2025. Not a production pack (D-120).",
        # `lang_src` se queda en "en" y NO en "en-core": entra en stable_uid(), y mantenerlo
        # igual al pack de kaikki es lo unico que deja comparable la identidad logica de las
        # dos fuentes si algun dia se quieren cruzar.
        "lang_src": "en",
        "fuzzy_profile": "en",
        "source_date": "20251231",
        "license": "CC-BY-4.0",
        "fuente_base": "oewn",
        "source_url": "https://en-word.net/",
        # La edicion estandar de OEWN 2025 no trae nombres propios: estan en Open English
        # Namenet / la edicion 2025+. La fuente de referencia del dominio llego a D-116 sola.
        "proper_nouns": "excluded",
    },
}

# De que modulo sale cada pack. Dos lineas en vez de un build_spike_oewn.py aparte, que
# duplicaria el manejo de --sample, de la metadata y de PackBuilder.
READERS = {"es": kaikki, "en": kaikki, "en-core": oewn, "es-wd": wikidata,
           "es-en": bilingual}


def _keep(headword, sample):
    if sample <= 1:
        return True
    digest = hashlib.sha256(headword.encode("utf-8")).digest()
    return int.from_bytes(digest[:4], "big") % sample == 0


def _con_sense_key_del_pack_final(nuevos):
    """Recalcula `sense_key` mirando los homografos del pack FUSIONADO, no los de la fuente.

    ⚠️ **Lo agarro `verify_pack.py` y es un fallo silencioso de los caros.** Cada fuente decide
    si una entrada necesita `sense_key` mirando SUS propios homografos. Al fusionar, un lexema que
    tenia gemelo en Wikidata puede perderlo --porque el gemelo ya estaba en la fuente base y se
    descarto-- y se queda con una clave que ya no corresponde a nada.

    Eso rompe `uid`, que es la identidad logica y **la llave del join entre packs** (D-055): el
    mismo lema calculado con clave en un pack y sin clave en otro **deja de unir**. Es el mismo
    error que D-139 documenta, entrando por otra puerta.

    La regla que vale es la del pack final: lleva clave el que tiene homografo **ahi**. Por eso
    los registros se bufferean --son miles, no millones-- en vez de emitirse en streaming.
    """
    cuenta = {}
    for record in nuevos:
        clave = (record.headword, record.part_of_speech)
        cuenta[clave] = cuenta.get(clave, 0) + 1
    for record in nuevos:
        if cuenta[(record.headword, record.part_of_speech)] == 1:
            record.sense_key = None
        yield record


def _pegar_ejemplo(record, ejemplos):
    """Le pega a una entrada FLACA el ejemplo de la segunda fuente. Ver `sources/enwikt_examples`.

    ⚠️ **Solo si la entrada tiene una acepcion y ninguna todavia**, que es la mitad de la regla
    que impide inventar la atribucion -- la otra mitad la aplica la fuente, exigiendo que alla
    tambien haya una sola. Con varias acepciones nuestras no se sabe a cual pegarlo, y pegarlo a
    la primera es contenido incorrecto que parece correcto: el lector no tiene como sospecharlo.
    """
    if not ejemplos or len(record.senses) != 1 or record.senses[0]["examples"]:
        return
    traidos = ejemplos.get((record.headword, record.part_of_speech))
    if traidos:
        record.senses[0]["examples"] = list(traidos)


def main(argv):
    if len(argv) < 4 or argv[1] not in PACKS:
        sys.stderr.write(__doc__)
        sys.stderr.write("\nIdiomas: %s\n" % ", ".join(sorted(PACKS)))
        return 2
    lang, source, output = argv[1], argv[2], argv[3]
    sample = 1
    if "--sample" in argv:
        sample = int(argv[argv.index("--sample") + 1])
    politica = kaikki.POLITICA_POR_DEFECTO
    if "--con-nombres" in argv:
        politica = "included"
    if "--nombres" in argv:
        politica = argv[argv.index("--nombres") + 1]
    dump_ejemplos = None
    if "--ejemplos" in argv:
        dump_ejemplos = argv[argv.index("--ejemplos") + 1]
    dump_frases = None
    if "--frases" in argv:
        dump_frases = argv[argv.index("--frases") + 1]
    dump_tesauro = None
    if "--tesauro" in argv:
        dump_tesauro = argv[argv.index("--tesauro") + 1]
    sumar = None
    if "--sumar" in argv:
        i = argv.index("--sumar")
        sumar = (argv[i + 1], argv[i + 2])

    metadata = dict(PACKS[lang])
    # El manifiesto se arma antes que nada: la fuente base primero, para que quede arriba en la
    # lista, y cada opcion agrega la suya donde mezcla su contenido.
    _declarar(metadata, metadata.pop("fuente_base"))
    if sample > 1:
        metadata["pack_id"] += "-sample%d" % sample
        metadata["name"] += " (piloto 1/%d)" % sample
    if politica != kaikki.POLITICA_POR_DEFECTO:
        # El pack por defecto conserva el pack_id pelado: si cambiara, el `pack_activo`, el
        # historial y los favoritos del reloj quedarian apuntando a un pack que ya no existe.
        # Los otros lo sufijan para que dos politicas puedan convivir instaladas y compararse.
        metadata["pack_id"] += "-" + politica.replace("-", "")
        metadata["proper_nouns"] = politica
    ejemplos = {}
    if dump_ejemplos:
        # ⚠️ Dos fuentes obligan a nombrar a las DOS, y eso es lo caro de esta opcion (D-135).
        # No es una formalidad: la atribucion es la condicion de la licencia con la que se
        # distribuye el pack, y las dos fuentes son CC BY-SA 4.0. Se escribe aca, junto al
        # merge, para que no exista manera de mezclar el contenido sin mover el credito.
        ejemplos = enwikt_examples.examples_by_entry(dump_ejemplos)
        metadata["pack_id"] += "-" + _declarar(metadata, "enwikt-ej")["codigo"]
        metadata["description"] += " Con ejemplos de uso de una segunda fuente."
    frases = None
    if dump_frases:
        # Mismo criterio que arriba y misma razon: el credito se mueve JUNTO con el contenido,
        # aca, para que no exista una forma de mezclar sin atribuir. Tatoeba es CC BY 2.0 FR
        # --el export CC0 trae 37 frases en español de 562.186-- asi que la atribucion es
        # obligatoria, no cortesia.
        frases = tatoeba.shortest_by_norm(dump_frases)
        metadata["pack_id"] += "-" + _declarar(metadata, "tatoeba")["codigo"]
        metadata["description"] += " Con frases de uso del corpus Tatoeba."
    tesauro = None
    if dump_tesauro:
        # El formato lo decide el IDIOMA del pack, no una opcion mas: el ingles tiene su propio
        # WordNet en WN-LMF y el español llega por el .tab de OMW. Son la misma idea servida
        # distinto, igual que las dos formas de los sinonimos del wiki (D-124).
        ingles = metadata["lang_src"] == "en"
        tesauro = wordnet.english(dump_tesauro) if ingles else wordnet.spanish(dump_tesauro)
        metadata["pack_id"] += "-" + _declarar(
            metadata, "oewn-tesauro" if ingles else "mcr")["codigo"]
        metadata["description"] += (
            " With WordNet synonyms and antonyms." if ingles
            else " Con sinónimos de WordNet."
        )

    if sumar:
        # ⚠️ **Una union de FILAS, no de campos.** La fuente sumada aporta LEMAS que la base no
        # tiene; los que comparten se quedan con la definicion de la base y no hay nada que
        # arbitrar. Eso es lo que la vuelve barata -- y lo que distingue esto de la composicion,
        # que parte una entrada en dos y sigue bloqueada por la granularidad de `uid` (D-146).
        metadata["pack_id"] += "-" + _declarar(
            metadata, PACKS[sumar[0]]["fuente_base"])["codigo"]
        metadata["description"] += " Con vocabulario de una fuente adicional."

    if os.path.dirname(output):
        os.makedirs(os.path.dirname(output), exist_ok=True)

    with PackBuilder(output, metadata, sentences=frases, thesaurus=tesauro) as builder:
        reader = READERS[lang]
        # El lector bilingue recibe el idioma de ORIGEN y no la clave del CLI: "es-en"
        # nombra al pack, pero el perfil de normalizacion y los `PERFILES` de kaikki son los
        # del espanol.
        argumentos = (
            (source, lang) if reader is oewn
            else (source, PACKS[lang]["lang_src"], politica) if reader is bilingual
            # `translations_to` sale de la misma tabla que lo declara en `meta`, para que la
            # promesa del pack y lo que el lector emite no puedan separarse.
            else (source, lang, politica, PACKS[lang].get("translations_to"))
            if reader is kaikki
            else (source, lang, politica)
        )
        vistos = set()
        for record in reader.records(*argumentos):
            if not _keep(record.headword, sample):
                continue
            _pegar_ejemplo(record, ejemplos)
            vistos.add((record.headword, record.part_of_speech))
            builder.add(record)
        if sumar:
            # Despues de la base y no mezclado: el orden **es** la regla de arbitraje. El primero
            # que llega se queda con el lema, asi que la definicion de la fuente base gana sin
            # que nadie tenga que compararlas.
            #
            # La clave es el lema EXACTO y no `norm()`: "papa" y "papá" comparten norm y son dos
            # palabras, y "Dr." o "km²" se perderian contra la entrada que normaliza igual.
            extra = READERS[sumar[0]]
            nuevos = []
            for record in extra.records(sumar[1], PACKS[sumar[0]]["lang_src"], politica):
                clave = (record.headword, record.part_of_speech)
                if clave in vistos or not _keep(record.headword, sample):
                    continue
                vistos.add(clave)
                nuevos.append(record)
            for record in _con_sense_key_del_pack_final(nuevos):
                builder.add(record)

    print("%s: %d entradas, %d bytes" % (output, builder.count, os.path.getsize(output)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
