"""Fuente: el Wikcionario español, procesado por kaikki.org (eswiktionary, seccion Español).

Entrega `Record` en streaming: el JSONL son 1,4 GB y no entra en memoria (D-015).

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

Los registros del mismo `word` vienen contiguos (medido: 0 bloques no contiguos en 400.000
registros), asi que los homografos se detectan con un buffer local en vez de un mapa global.
Si la fuente algun dia dejara de agruparlos, el que avisa es el chequeo de identidad de
`build.py`, que falla ruidosamente en vez de fundir dos entradas.
"""

import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from build import Record  # noqa: E402

# Cuantos ejemplos de uso se guardan por acepcion.
#
# Los ejemplos entran al payload y ademas a fts_def, asi que pagan dos veces. Uno alcanza para
# desambiguar una acepcion en una pantalla de reloj; el segundo ya no se ve sin scrollear.
MAX_EXAMPLES_PER_SENSE = 1

# Techo del rank. La columna es "menor es mas comun" (schema.sql), asi que el rank se calcula
# restando: una pagina rica queda cerca de 0, una pobre cerca del techo.
RANK_BASE = 1000

# Pesos del proxy de frecuencia. **No es frecuencia de uso**: el Wikcionario no la trae. Es la
# riqueza de la pagina, que correlaciona con que la palabra sea comun porque las palabras
# comunes son las que la gente edita. Ver docs/decisions.md, D-063.
_W_SENSE = 3
_W_EXAMPLE = 2
_W_FORM = 1
_W_TRANSLATION = 0.5
_W_ETYMOLOGY = 5

# Tope de formas que cuentan para el rank: un verbo trae 137 y con eso solo ganaria siempre.
_FORMS_SCORE_CAP = 80


def _gloss(sense):
    """La glosa de una acepcion.

    `glosses` puede traer varios niveles (la general primero, la especifica despues). Se toma
    **la ultima**: es la que define de verdad. Unir todas repetiria el texto del padre en cada
    hija, que es peso pagado dos veces en el payload y en el indice.
    """
    glosses = [g.strip() for g in (sense.get("glosses") or []) if g and g.strip()]
    return glosses[-1] if glosses else ""


def _is_form_of(sense):
    return "form-of" in (sense.get("tags") or []) or bool(sense.get("form_of"))


def _senses(raw):
    """Las acepciones que sobreviven la poda. Vacia si el registro no es una entrada."""
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
        out.append({"gloss": gloss, "examples": examples})
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


def _rank(raw, senses, forms):
    """Proxy de frecuencia. Menor es mas comun. Ver el bloque de pesos y D-063."""
    score = (
        _W_SENSE * len(senses)
        + _W_EXAMPLE * sum(len(s["examples"]) for s in senses)
        + _W_FORM * min(len(forms), _FORMS_SCORE_CAP)
        + _W_TRANSLATION * len(raw.get("translations") or [])
        + (_W_ETYMOLOGY if raw.get("etymology_texts") else 0)
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


def _emit(group, inbound):
    """Convierte un grupo de registros del mismo `word` en Records."""
    prepared = []
    for raw in group:
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
            rank=_rank(raw, senses, forms),
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


def records(path):
    """Itera el JSONL y entrega Records. Los del mismo `word` se agrupan para los homografos."""
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
                for record in _emit(group, inbound):
                    yield record
                group = []
                current = word
            group.append(raw)
    for record in _emit(group, inbound):
        yield record
