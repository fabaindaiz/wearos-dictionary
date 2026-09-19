"""Fuente: un dump de kaikki.org (wiktextract), en cualquier idioma.

Entrega `Record` en streaming: los JSONL van de 1,4 GB (español) a 3,2 GB (ingles) y no entran
en memoria (D-015).

**La logica de poda es la misma para todos los idiomas.** Se apoya en los tags estructurales que
wiktextract emite --`form-of`, `form_of`, `glosses`, `forms`-- que son los mismos en todos los
dumps y estan en ingles. No hay una sola heuristica que compare contra texto en español.

Lo que SI cambia por idioma son las constantes de calibracion del `rank`, y por eso viven en un
`Perfil`: se midieron sobre un dump concreto y no se heredan. Ver `PERFILES`.

**La poda es el trabajo**, no un detalle. Tres decisiones la gobiernan, y las tres salen de
medir el dump, no de suponer:

1. **El 82,33 % de los registros son paginas de forma flexionada** (703.506 de 854.460):
   "amigo" como presente de "amigar". Se reconocen porque **todas** sus acepciones llevan el tag
   `form-of`. No son entradas de diccionario —mostrarlas seria el mismo resultado repetido cuatro
   veces— pero **si hay que recolectarlas**, y por eso esta fuente hace **dos pasadas**.

   (Hay otras que son forma flexionada y **no** llevan el tag: "Participio de escribir". Esas se
   conservan como entrada a proposito, y la razon esta medida en D-066.)

   Parecia que no hacia falta: el lema trae su conjugacion en `forms` (`amigar` trae 137 formas,
   `amigo` entre ellas). **Medido, no alcanza**: el `forms` de los lemas cubre el 92,31 % de las
   palabras-forma, y el **7,66 % restante —53.708 palabras— se perderia**. No son casos raros:
   "palpitaciones", "curvilinea", "animalito". Cada una es una busqueda que no encuentra nada,
   sin error y sin log. La pasada 1 invierte las paginas form-of en un mapa lema -> formas; la
   pasada 2 emite las entradas con las dos fuentes de formas unidas.
2. **El pack es monolingue** (D-034): `translations` no se emite. En monolingue la tabla `trans`
   duplica lo que `fts_def` ya indexa mejor.
3. **Etimologia, pronunciacion, categorias, silabeo y las relaciones lexicas se descartan.** No
   se muestran en un reloj y son la mayor parte del peso del dump.
4. **Los nombres propios no entran** (D-116). No es que pesen --en español son 0,63 MB de
   payload-- es que **diluyen**: 26.265 entradas cuya definicion completa es "Apellido.", y en
   ingles 4.267 casos donde el toponimo le gana en rank a la palabra comun.

   **Con una excepcion, y hubo que medirla para encontrarla**: podar por `pos = "name"` a secas
   se llevaba puesto "January", porque los meses en ingles son nombres propios. Seis de los doce
   desaparecian (los otros seis sobreviven por ser tambien verbo, modal o adjetivo: "march",
   "may", "august"). Lo que salva al mes y no a la aldea es `SENAL_LEXICA_MINIMA`. Ver ahi.

Los registros del mismo `word` vienen contiguos (medido: 0 bloques no contiguos en 400.000
registros), asi que los homografos se detectan con un buffer local en vez de un mapa global.
Si la fuente algun dia dejara de agruparlos, el que avisa es el chequeo de identidad de
`build.py`, que falla ruidosamente en vez de fundir dos entradas.
"""

import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from build import Record  # noqa: E402

# Cuantos ejemplos de uso se guardan por acepcion.
#
# Los ejemplos entran al payload y ademas a fts_def, asi que pagan dos veces. Uno alcanza para
# desambiguar una acepcion en una pantalla de reloj; el segundo ya no se ve sin scrollear.
MAX_EXAMPLES_PER_SENSE = 1

# Cuantos sinonimos se guardan por acepcion. Cuatro entran en una pantalla de reloj en una sola
# linea; el quinto ya obliga a scrollear para leer algo que es una ayuda, no la definicion.
MAX_SYNONYMS_PER_SENSE = 4

# LA FUENTE DECIDE DE DONDE SALEN LOS SINONIMOS, NO UNA LISTA DE IDIOMAS.
#
# Habia una lista --`IDIOMAS_CON_SINONIMOS = {"es"}`-- y estaba justificada por una medicion
# incompleta: se miro solo la forma de ARRIBA, `raw["synonyms"]`, donde el ingles trae 0 items
# con `sense_index` y por lo tanto nada atribuible. Pero el ingles sirve los suyos en otra
# forma, **anidados dentro de cada acepcion**, donde la atribucion es estructural y no hace
# falta declararla. Medido sobre 120.000 registros vivos de cada dump:
#
#     forma                          español   ingles
#     `synonyms` arriba               16,5 %    5,6 %
#     `synonyms` dentro de `senses`    0,0 %   25,8 %
#     las dos a la vez                 0,0 %    0,0 %
#
# Cada dump usa **una sola forma, y no la misma**, asi que no hay precedencia que decidir. Y la
# lista sobra: `_by_sense_index` ya descarta lo que no trae `sense_index`, asi que la forma
# de arriba del ingles se cae sola. Un default que se sostiene por lo que la fuente trae es mas
# dificil de dejar desactualizado que uno que se sostiene por una constante.

# Umbral de la excepcion a la poda de nombres propios (D-116).
#
# `pos = "name"` mete en la misma bolsa a "January" y a "Ivanivka", y la primera es vocabulario
# mientras la segunda es una aldea de Cherkasy. Lo que las separa **sin mirar el texto** es
# cuanta vida lexica tiene la palabra: traducciones a otros idiomas, descendientes y derivados.
#
# Medido sobre los dumps: January 69, February 50, Paris 172, Moscow 330, España 77, Chile 73 --
# contra **0** de Hopewell (apellido) e Ivanivka (aldea). Con el umbral en 5 se conservan 1.675
# entradas en ingles (1,0 % de los nombres propios) y 731 en español (2,3 %).
#
# Sigue siendo estructural: son campos que wiktextract emite igual en todos los dumps, no una
# heuristica contra texto en un idioma. Ver el punto 4 del docstring del modulo.
SENAL_LEXICA_MINIMA = 5

# Techo del rank. La columna es "menor es mas comun" (schema.sql), asi que el rank se calcula
# restando: una pagina rica queda cerca de 0, una pobre cerca del techo.
RANK_BASE = 1000

class Perfil:
    """Las constantes del proxy de `rank`, que **se miden por idioma y no se heredan**.

    El rank no es frecuencia de uso --el Wikcionario no la trae-- sino riqueza de la pagina, que
    correlaciona con que la palabra sea comun porque las palabras comunes son las que la gente
    edita (D-063).

    `forms_cap` es el que mas depende del idioma: existe para que un verbo no gane solo por
    tener muchas formas. En español un verbo trae hasta 222 y el tope muerde; en ingles trae
    cuatro o cinco y **el tope nunca se activa**, asi que `w_form` deja de discriminar y el rank
    queda dominado por acepciones y etimologia. Por eso los dos perfiles no son iguales.
    """

    __slots__ = ("w_sense", "w_example", "w_form", "w_translation", "w_etymology", "forms_cap")

    def __init__(self, w_sense, w_example, w_form, w_translation, w_etymology, forms_cap):
        self.w_sense = w_sense
        self.w_example = w_example
        self.w_form = w_form
        self.w_translation = w_translation
        self.w_etymology = w_etymology
        self.forms_cap = forms_cap


PERFILES = {
    # Medido sobre eswiktionary 2026-09-15: "per" devuelve perder/permitir/perseguir/permanecer/
    # perro, y "perro" subio de la posicion 619 a la 5 (D-067).
    "es": Perfil(w_sense=3, w_example=2, w_form=1, w_translation=0.5, w_etymology=5,
                 forms_cap=80),
    # Ingles: el tope de formas baja porque un verbo trae 4-5 y no 137. Los pesos se ajustan
    # contra el dump midiendo que prefijos comunes devuelvan la palabra comun arriba.
    "en": Perfil(w_sense=3, w_example=2, w_form=4, w_translation=0.5, w_etymology=5,
                 forms_cap=12),
}


# Etiquetas de mantenimiento del wiki incrustadas en la glosa (D-121).
#
# wiktextract las deja adentro de `glosses` y **no hay version limpia**: `raw_glosses` es None
# en todos los casos medidos. En español son 774 acepciones: 671 "[cita requerida]" y 103
# "[definición imprecisa]". En un reloj, "Pene.^([cita requerida])" gasta media pantalla en
# decirle al lector que un editor queria una fuente.
#
# **El patron exige los CORCHETES, y no es un detalle**: en ingles `^(...)` sin corchetes es
# superindice matematico --10^(100), e^(iπ), 2^(2/r)-- y un filtro mas ancho destruiria
# contenido en vez de limpiarlo. Con corchetes hay un solo caso en ingles, "[sic]".
#
# El punto opcional del final existe porque la fuente escribe "...los labios.^([cita
# requerida])." y sacar solo el tag deja dos puntos seguidos.
_MARKUP_EDITORIAL = re.compile(r"\s*\^\(\[[^\]]*\]\)\.?")


def _gloss(sense):
    """La glosa de una acepcion.

    `glosses` puede traer varios niveles (la general primero, la especifica despues). Se toma
    **la ultima**: es la que define de verdad. Unir todas repetiria el texto del padre en cada
    hija, que es peso pagado dos veces en el payload y en el indice.
    """
    glosses = [g.strip() for g in (sense.get("glosses") or []) if g and g.strip()]
    if not glosses:
        return ""
    return _MARKUP_EDITORIAL.sub("", glosses[-1]).strip()


def _senal_lexica(raw):
    """Cuanta vida lexica tiene la palabra: traducciones + descendientes + derivados."""
    return (
        len(raw.get("translations") or [])
        + len(raw.get("descendants") or [])
        + len(raw.get("derived") or [])
    )


def _is_form_of(sense):
    return "form-of" in (sense.get("tags") or []) or bool(sense.get("form_of"))


def _by_sense_index(raw, headword, clave):
    """Mapa `sense_index` -> items, leido del registro crudo. La forma del dump español.

    `clave` es "synonyms" o "antonyms": es literalmente la misma forma con otro nombre, y dos
    copias de esto divergirian el dia que alguien arregle un borde en una sola.

    **La clave es el `sense_index` que declara la fuente, NUNCA la posicion.** `_senses()` poda
    las acepciones form-of antes de emitir, asi que los ordinales se corren: un `enumerate()`
    le colgaria a la acepcion que sobrevive los sinonimos de la que se fue. Eso no lanza, no
    loguea y no lo agarra `verify_pack.py` -- sale del pack como contenido correcto.

    Un sinonimo sin `sense_index` se descarta (medido: 5 en todo el dump). Colgarlo de la
    primera acepcion seria inventar una atribucion que la fuente no da.
    """
    out = {}
    for item in raw.get(clave) or []:
        index = (item.get("sense_index") or "").strip()
        word = (item.get("word") or "").strip()
        # El sinonimo igual al lema no aporta nada, igual que en _forms().
        if not index or not word or word == headword:
            continue
        out.setdefault(index, []).append(word)
    return out


def _nested(sense, headword, clave):
    """Los items que vienen DENTRO de la acepcion. La forma del dump ingles.

    `clave` es "synonyms" o "antonyms", igual que en `_by_sense_index`.

    No se pide `sense_index` y no es un descuido: aca la atribucion es estructural --el item ya
    vive en su acepcion-- mientras que en la forma de arriba es declarada. Exigirlo tiraria los
    338.200 items del dump ingles por no traer un dato que no necesitan.

    **El orden del dump se respeta.** El 74,5 % traen `source: "Thesaurus:*"` y van primero, asi
    que parecia que el tope de 4 se quedaria con lo oscuro; medido, reordenar cambia 91 de 4.872
    acepciones mezcladas (1,9 %) y en la muestra el resultado es PEOR: `craft` pasa de
    `ability, aptitude` a `craftiness, foxiness`.
    """
    out, vistos = [], set()
    for item in sense.get(clave) or []:
        word = (item.get("word") or "").strip()
        # Igual que en _forms() y en la forma de arriba: el sinonimo igual al lema no aporta
        # nada. Medido: 1,9 % de los items ingleses.
        if not word or word == headword or word in vistos:
            continue
        vistos.add(word)
        out.append(word)
    return out


def _senses(raw):
    """Las acepciones que sobreviven la poda. Vacia si el registro no es una entrada."""
    headword = raw.get("word", "")
    synonyms = _by_sense_index(raw, headword, "synonyms")
    antonyms = _by_sense_index(raw, headword, "antonyms")
    out = []
    for sense in raw.get("senses") or []:
        if _is_form_of(sense):
            continue
        gloss = _gloss(sense)
        if not gloss:
            continue
        examples = []
        for example in (sense.get("examples") or [])[:MAX_EXAMPLES_PER_SENSE]:
            text = (example.get("text") or "").strip()
            if text:
                examples.append(text)
        index = (sense.get("sense_index") or "").strip()
        # Las dos formas en que la fuente sirve sinonimos. Ningun dump usa las dos, asi que esto
        # no es una precedencia sino una union: la que este vacia no aporta nada.
        out.append({
            "gloss": gloss,
            "examples": examples,
            "synonyms": (
                synonyms.get(index, []) or _nested(sense, headword, "synonyms")
            )[:MAX_SYNONYMS_PER_SENSE],
            # Mismo molde y mismo tope. Atribuir mal un antonimo es peor que atribuir mal un
            # sinonimo: se lee como lo contrario de otra cosa, no como una eleccion rara.
            "antonyms": (
                antonyms.get(index, []) or _nested(sense, headword, "antonyms")
            )[:MAX_SYNONYMS_PER_SENSE],
        })
    return out


def _forms(raw, headword, inbound):
    """Las flexiones que llevan a este lema, deduplicadas y sin el lema.

    Dos fuentes: las que el lema declara en `forms`, y las paginas form-of que lo apuntan.
    Ninguna de las dos alcanza sola. Ver el punto 1 del docstring del modulo.
    """
    seen = {}
    for item in raw.get("forms") or []:
        form = (item.get("form") or "").strip()
        if form and form != headword:
            seen[form] = None
    for form in sorted(inbound):
        if form != headword:
            seen[form] = None
    return tuple(seen)


def _rank(raw, senses, forms, perfil):
    """Proxy de frecuencia. Menor es mas comun. Ver `Perfil` y D-063."""
    score = (
        perfil.w_sense * len(senses)
        + perfil.w_example * sum(len(s["examples"]) for s in senses)
        + perfil.w_form * min(len(forms), perfil.forms_cap)
        + perfil.w_translation * len(raw.get("translations") or [])
        + (perfil.w_etymology if raw.get("etymology_texts") else 0)
    )
    return max(0, RANK_BASE - int(score))


def _sense_key(raw, index, needs_key):
    """Que separa dos homografos que comparten word, pos Y pos_title.

    Existe de verdad: `leonino` adjetivo aparece tres veces en el Wikcionario, distinguido solo
    por etimologia. Se usa `pos_title` cuando alcanza, y un ordinal cuando no.

    El ordinal es la posicion dentro del grupo, no un hash del contenido, **a proposito**:
    editar una etimologia no tiene que cambiar el uid (D-055). El costo es el simetrico — que
    insertar una acepcion nueva en el medio corra los ordinales siguientes— y es el menos malo
    de los dos, porque las inserciones son mas raras que las ediciones.
    """
    if not needs_key:
        return None
    title = raw.get("pos_title") or ""
    return "%s#%d" % (title, index) if index else title or "#0"


def _emit(group, inbound, perfil, con_nombres):
    """Convierte un grupo de registros del mismo `word` en Records."""
    prepared = []
    for raw in group:
        if (
            not con_nombres
            and raw.get("pos") == "name"
            and _senal_lexica(raw) < SENAL_LEXICA_MINIMA
        ):
            continue
        senses = _senses(raw)
        if not senses:
            continue
        prepared.append((raw, senses))

    # Un sense_key hace falta solo cuando dos entradas del grupo comparten pos: es lo unico que
    # colisiona en stable_uid(). Ponerlo cuando no hace falta vuelve el uid inestable de gratis.
    by_pos = {}
    for raw, _ in prepared:
        by_pos[raw.get("pos")] = by_pos.get(raw.get("pos"), 0) + 1

    index_in_pos = {}
    for raw, senses in prepared:
        pos = raw.get("pos")
        index = index_in_pos.get(pos, 0)
        index_in_pos[pos] = index + 1
        headword = raw["word"]
        forms = _forms(raw, headword, inbound.get(headword, ()))
        yield Record(
            headword=headword,
            senses=senses,
            part_of_speech=pos,
            rank=_rank(raw, senses, forms, perfil),
            forms=forms,
            translations=(),
            sense_key=_sense_key(raw, index, by_pos[pos] > 1),
        )


def _is_form_page(raw):
    """Una pagina que es SOLO forma flexionada: ninguna de sus acepciones define nada."""
    senses = raw.get("senses") or []
    return bool(senses) and all(_is_form_of(sense) for sense in senses)


def _inbound_forms(path):
    """Pasada 1: invierte las paginas form-of en un mapa lema -> formas que llevan a el.

    Se invierte aca y no en la pasada 2 porque la pasada 2 consume por lema: asi el mapa tiene
    una clave por lema (~150.000) en vez de una por forma (~700.000).
    """
    inbound = {}
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            if not line.strip():
                continue
            raw = json.loads(line)
            word = raw.get("word")
            if not word or not _is_form_page(raw):
                continue
            for sense in raw["senses"]:
                for target in sense.get("form_of") or []:
                    lemma = target.get("word")
                    if lemma and lemma != word:
                        inbound.setdefault(lemma, set()).add(word)
    return inbound


def records(path, lang="es", con_nombres=False):
    """Itera el JSONL y entrega Records. Los del mismo `word` se agrupan para los homografos.

    **Los nombres propios NO salen por defecto** (`pos = "name"`: apellidos, toponimos, nombres
    de pila). Es una decision de producto, D-116, y el default vive aca --en la libreria-- y no
    en el flag de la CLI, para que cualquier llamador nuevo la herede sin tener que pedirla.

    Lo que se saca, medido: en español **32.305 entradas, el 22,1 %**, de las cuales **26.265
    tienen como definicion completa la palabra "Apellido."**. En ingles **163.470, el 17,1 %**,
    que ademas pesan **40,7 MB (13,8 % del pack)** y en **4.267 casos le ganan en rank a la
    palabra comun**: buscar "freedom" devolvia primero un pueblo del condado de Santa Cruz.

    `con_nombres=True` los trae de vuelta. Sigue existiendo porque es lo que produjo esos
    numeros, y porque volver a medirlos contra un dump nuevo tiene que seguir siendo barato.
    """
    perfil = PERFILES[lang]
    inbound = _inbound_forms(path)
    group = []
    current = None
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            if not line.strip():
                continue
            raw = json.loads(line)
            word = raw.get("word")
            if not word:
                continue
            if word != current:
                for record in _emit(group, inbound, perfil, con_nombres):
                    yield record
                group = []
                current = word
            group.append(raw)
    for record in _emit(group, inbound, perfil, con_nombres):
        yield record
