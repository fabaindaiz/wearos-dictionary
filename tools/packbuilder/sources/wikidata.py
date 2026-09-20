"""Lexemas de Wikidata: el **segundo pack base** de español, y el unico CC0.

No es una mejora del pack del Wikcionario: es **otro diccionario**, de otra comunidad, que se
instala al lado y se consulta junto con el (D-136). La ganancia es la union de lemas.

**Por que este y no un merge.** Fusionarlo dentro del pack 1 obligaria a decidir cual definicion
gana cuando las dos tienen la palabra, y no hay medicion que resuelva eso. Como pack aparte no
hay nada que decidir: los dos estan, y el que tenga la palabra la contesta.

**Lo que aporta, medido** sobre el dump del 2026-09-20 (450 MB comprimidos):

    lexemas en español                     66.935
    de esos, con glosa EN ESPAÑOL          15.814   (23,6 %)
    de esos, que NO estan en es-def-wikc    5.283   (33,4 %)

Y lo que aporta es **complementario, no redundante**: gentilicios regionales --"iquiteño",
"huantino", "ucayalino", "abiyanés"-- y locuciones --"a su vez", "entre tanto", "así como así"--,
justo lo que un wiki editado mayormente desde España cubre peor.

⚠️ **Licencia CC0**, que es la diferencia practica mas grande con las otras fuentes: no suma
obligacion de atribucion a nadie. Se declara igual en el manifiesto (D-138), porque declarar de
donde viene un dato es util aunque no sea obligatorio.

Fuente: https://dumps.wikimedia.org/wikidatawiki/entities/latest-lexemes.json.bz2
"""

import bz2
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from build import Record  # noqa: E402
from sources import kaikki  # noqa: E402

# El item de Wikidata que representa al idioma español.
IDIOMA_ES = "Q1321"

# Las categorias lexicas, **identificadas mirando los lemas de cada una en el dump** y no de
# memoria: `Q1084` sale con berilio/vino/viernes/maiz, `Q147276` con Miguel/Portugal/Turquia.
#
# Las locuciones se mapean a su nucleo ("año nuevo" -> noun, "hacer coro" -> verb): en el reloj
# la etiqueta dice que clase de palabra es, y "locucion nominal" no cabe ni ayuda.
#
# ⚠️ `Q147276` se mapea a **"name"** a proposito: es el vocabulario que usa la poda de nombres
# propios (D-116, D-134), asi que las tres politicas funcionan igual en este pack sin tocar nada.
CATEGORIAS = {
    "Q1084": "noun",          # sustantivo
    "Q34698": "adj",          # adjetivo
    "Q24905": "verb",         # verbo
    "Q29888377": "noun",      # locucion nominal: "año nuevo", "reloj de pulsera"
    "Q380057": "adv",         # adverbio
    "Q10976085": "verb",      # locucion verbal: "hacer coro", "rezar a coros"
    "Q5978303": "adv",        # locucion adverbial: "por el contrario", "entre tanto"
    "Q147276": "name",        # nombre propio -- lo poda la politica, igual que en kaikki
    "Q12734432": "adj",       # locucion adjetiva: "de buenas", "de pocas palabras"
    "Q102047": "suffix",      # sufijo: "-miento", "-al"
    "Q134830": "prefix",      # prefijo: "a-", "anti-"
    "Q83034": "intj",         # interjeccion
    "Q187931": "phrase",      # refran o formula: "albarda sobre aparejo"
    "Q102786": "abbrev",      # abreviatura: "n.º", "JJ. OO."
    "Q1298743": "conj",       # conjuncion
    "Q4833830": "prep",       # preposicion
    "Q36224": "pron",         # pronombre
    "Q103184": "num",         # numeral
    "Q161873": "det",         # determinante
}

# Techo del rank, igual que en kaikki: "menor es mas comun", asi que se calcula restando.
RANK_BASE = 1000

# ⚠️ **El proxy de rank es OTRO y no se puede comparar con el de kaikki.** Wikidata no trae
# etimologia ni traducciones, que son dos de los cinco terminos del perfil de `sources/kaikki`.
# Aca solo hay acepciones y formas. D-136 ya deja escrito que `score` no es comparable entre packs
# de fuentes distintas; esto es la razon concreta.
PESO_ACEPCION = 3
PESO_FORMA = 1
TOPE_DE_FORMAS = 80

# Mismo tope que el resto del pipeline: es el ancho de un renglon de reloj, no un dato de fuente.
MAX_ACEPCIONES = 8


def _valor(mapa, idioma="es"):
    entrada = (mapa or {}).get(idioma)
    return (entrada or {}).get("value", "").strip()


def _lexemas(path):
    """Itera el dump. Es un array JSON gigante servido de a una entidad por linea."""
    abrir = bz2.open if path.endswith(".bz2") else open
    with abrir(path, "rt", encoding="utf-8") as handle:
        for line in handle:
            line = line.strip().rstrip(",")
            if not line or line in ("[", "]"):
                continue
            try:
                yield json.loads(line)
            except ValueError:
                # Una linea rota no puede tirar un build de 450 MB. No se silencia un error de
                # logica: se salta una linea que el dump sirvio mal.
                continue


def _contar_homografos(path, politica):
    """Cuantas veces aparece cada `(lema, pos)`. Primera de las dos pasadas. Ver `records`.

    Cuesta ~2 minutos sobre el dump comprimido y evita que la identidad logica de una entrada
    dependa del orden en que el archivo la sirvio.
    """
    cuenta = {}
    for lexema in _lexemas(path):
        if lexema.get("language") != IDIOMA_ES:
            continue
        lema = _valor(lexema.get("lemmas"))
        if not lema:
            continue
        pos = CATEGORIAS.get(lexema.get("lexicalCategory"))
        if pos == "name" and politica != "included":
            continue
        if not any(_valor(s.get("glosses")) for s in (lexema.get("senses") or [])):
            continue
        clave = (lema, pos)
        cuenta[clave] = cuenta.get(clave, 0) + 1
    return cuenta


def records(path, lang="es", politica=kaikki.POLITICA_POR_DEFECTO):
    """Entrega Records desde el dump de lexemas. Ver el docstring del modulo.

    `politica` existe para que la firma sea la misma que la de `sources/kaikki` --`build_pack`
    llama a las dos igual-- y aca solo decide si entran los `Q147276`. No hay señal lexica que
    medir, asi que "lexical-only" y "definitions-only" se comportan como excluirlos: la unica
    diferencia real es `included`, **que es el default** (D-141).

    ⚠️ **Lo que si se descarta y no es una poda: los lexemas SIN glosa en español** (76,4 % del
    dump). No es una decision de contenido -- es que la fuente **no tiene definicion que dar**.
    Un lexema asi trae categoria y formas y nada mas; emitirlo seria un lema que al abrirlo esta
    vacio, y `build.add()` lo rechaza igual. Si algun dia Wikidata los completa, entran solos.
    """
    repetidos = _contar_homografos(path, politica)
    for lexema in _lexemas(path):
        if lexema.get("language") != IDIOMA_ES:
            continue
        lema = _valor(lexema.get("lemmas"))
        if not lema:
            continue
        pos = CATEGORIAS.get(lexema.get("lexicalCategory"))
        if pos == "name" and politica != "included":
            continue
        glosas = [
            _valor(s.get("glosses"))
            for s in (lexema.get("senses") or [])
        ]
        senses = [
            {"gloss": g, "examples": [], "translations": [], "synonyms": [], "antonyms": [],
             "related": []}
            for g in glosas if g
        ][:MAX_ACEPCIONES]
        # Sin glosa en español no es una entrada de un diccionario español: el lexema existe
        # igual --tiene formas y categoria-- pero no dice nada. Son el 76,4 % del dump.
        if not senses:
            continue
        formas = tuple(
            f for f in (_valor(x.get("representations")) for x in (lexema.get("forms") or []))
            if f and f != lema
        )
        score = PESO_ACEPCION * len(senses) + PESO_FORMA * min(len(formas), TOPE_DE_FORMAS)
        yield Record(
            headword=lema,
            senses=senses,
            part_of_speech=pos,
            rank=max(0, RANK_BASE - score),
            forms=formas,
            translations=(),
            # ⚠️ **Solo cuando hay homografo, y esto costo una pasada extra.**
            #
            # El id del lexema (`L12345`) es una identidad estable declarada por la fuente, que
            # es mas de lo que kaikki tiene, y la tentacion es usarlo siempre. **No se puede**:
            # `uid` existe para unir la MISMA entrada entre packs distintos (D-055), y si este
            # pack mete `L12345` en el hash y el del Wikcionario no mete nada, la entrada de
            # "vino" de los dos packs deja de unir. La convencion tiene que ser la misma en todos.
            #
            # Y como el dump NO viene agrupado por lema, saber si hay homografo obliga a contar
            # primero: ponerselo solo al segundo haria que el uid del primero dependiera del
            # orden del archivo. `verify_pack.py` agarro exactamente eso.
            sense_key=lexema.get("id") if repetidos.get((lema, pos), 0) > 1 else None,
        )
