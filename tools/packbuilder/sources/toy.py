"""A toy source: a small, deterministic pack for the tests.

It comes from no real source. It exists so :dict-data's tests run against a real pack (with its
indexes, its FTS and its compressed payloads) without downloading gigabytes or depending on the
network. The cases are chosen to exercise the five search paths:

  - prefix:          "corr" -> correr, corriente
  - inflected form:  "corriendo" -> correr
  - translation:     "run" -> correr
  - tolerant:        "korer" / "coreer" -> correr
  - free text:       "rapidamente" appears in correr's definition

It also includes traps on purpose: accents, an eszett, homograph lemmas with different pos, and a
lemma that normalizes the same as another.
"""

import sys

sys.path.insert(0, __import__("os").path.dirname(__import__("os").path.dirname(
    __import__("os").path.abspath(__file__))))

from build import Record  # noqa: E402

# (headword, pos, rank, [(gloss, [ejemplos], [traducciones])], [formas])
_DATA = [
    ("correr", "verb", 10, [
        # ⚠️ The ONLY example in the toy pack that carries a citation, and it goes with two
        # siblings without one on purpose: the common case is the example with no source --24.5 %
        # of the English dump declares one-- and a fixture where all of them had it would not tell
        # a reader that invents the attribution from one that reads it.
        ("moverse rapidamente de un lugar a otro",
         [{"text": "corrio hasta la esquina", "ref": "1897, Richard Marsh"}], ["to run"]),
        ("dicho del tiempo: transcurrir", [], ["to pass", "to elapse"]),
    ], ["corriendo", "corri", "corre", "corremos", "corrio"], None, (),
        # WARNING: the only entry with PRINCIPAL PARTS (tag `F`), and deliberately so, for the
        # same reason the one below is the only one with `W`: a channel with no fixture breaks
        # with nothing to warn. And they carry an ACCENT, which is the property this channel
        # exists to hold up -- the `form` table stores `corrio`, not `corrió`.
        (("ger", "corriendo"), ("part", "corrído"))),
    # ⚠️ **The only entry with ENTRY-LEVEL translations (tag `W`), and it is there on purpose.**
    # Without it the toy does not exercise the second channel (D-179) --the one that exists so
    # smearing unattributable data stops being free-- and the instrumented tests would never see a
    # `W`. It is the same reasoning by which a bilingual toy has to be kept: a channel with no
    # fixture breaks with nothing to warn.
    ("corriente", "noun", 40, [
        ("movimiento de un fluido en una direccion", [], ["current", "stream"]),
        ("que es habitual o comun", [], ["ordinary", "common"]),
    ], ["corrientes"], None, ["draught"]),
    ("corregir", "verb", 120, [
        ("enmendar lo que esta equivocado", [], ["to correct", "to fix"]),
    ], ["corrige", "corrigiendo"]),
    ("corto", "adjective", 60, [
        ("de poca longitud o duracion", [], ["short", "brief"]),
    ], ["corta", "cortos", "cortas"]),
    # ⚠️ **A LANGUAGE trap, and it is the one that was missing.** "sol" exists as a Spanish lemma
    # (the star) and "sole" in English; worse: "pie" is Spanish (the body part) and English (the
    # pastry). Without a cross-language homograph, resolving a translation without looking at the
    # language passes the tests -- and over the real pack it fails 8.30 % of the time, sending
    # `foot -> pie` to the English `pie`.
    ("pie", "noun", 25, [
        ("parte del cuerpo sobre la que se apoya", [], ["foot"]),
    ], ["pies"]),
    # And its companion: "pastel" translates to "pie", so "pie" exists ALSO as an English lemma.
    # At rank 26 --worse than the Spanish "pie"'s 25-- resolving by best rank gets it right by
    # accident; what makes it right for real is looking at the language.
    ("pastel", "noun", 26, [
        ("masa horneada con relleno dulce", [], ["pie", "cake"]),
    ], []),
    ("cosa", "noun", 30, [
        ("todo lo que tiene existencia", [], ["thing"]),
    ], ["cosas"]),
    # WARNING: the only entry with a PRONUNCIATION (tag `I`), for the same reason `correr` is the
    # only one with `F`: a channel with no fixture breaks with nothing to warn. It carries an IPA
    # stress mark, which is the property that makes this datum non-ASCII by construction.
    ("casa", "noun", 15, [
        ("edificio para habitar", ["la casa de la esquina"], ["house", "home"]),
    # ⚠️ The only entry with an ORIGIN (tag `M`), and the only one with TWO word-level channels:
    # `I` and `M` land one after the other before the senses, and their order is what the shared
    # fixture pins. It carries an accent and an asterisk for the same reason the IPA carries a
    # stress mark -- the datum has to be non-ASCII by construction.
    ], ["casas"], None, (), (), "\u02c8kasa", "Del latín *casa*, 'choza'."),
    ("cazar", "verb", 200, [
        ("perseguir animales para capturarlos", [], ["to hunt"]),
    ], ["caza", "cazando"]),
    # Homographs: same lemma, different pos. The list has to be able to tell them apart.
    ("bajo", "adjective", 80, [
        ("de poca altura", [], ["low", "short"]),
    ], []),
    ("bajo", "preposition", 85, [
        ("debajo de", [], ["under", "beneath"]),
    ], []),
    # Accents and ñ: norm folds them, headword keeps them for display.
    ("niño", "noun", 25, [
        ("persona de pocos anos", [], ["child", "boy"]),
    ], ["ninos", "niños"]),
    ("acción", "noun", 50, [
        ("ejercicio de una potencia", [], ["action"]),
        ("titulo que representa parte del capital", [], ["share", "stock"]),
    ], ["acciones"]),
    ("árbol", "noun", 45, [
        ("planta perenne de tronco lenoso", [], ["tree"]),
    ], ["arboles", "árboles"]),
    # It normalizes the same as "arbol": it exercises norm colliding without losing entries.
    ("arbol", None, 900, [
        ("variante sin tilde, registrada aparte", [], ["tree"]),
    ], []),
    ("llave", "noun", 70, [
        ("instrumento para abrir cerraduras", [], ["key"]),
    ], ["llaves"]),
    ("hola", "interjection", 20, [
        ("saludo familiar", [], ["hello", "hi"]),
    ], []),
    ("zapato", "noun", 90, [
        ("calzado que cubre el pie", [], ["shoe"]),
    ], ["zapatos"]),
    ("vaca", "noun", 100, [
        ("hembra del toro", [], ["cow"]),
    ], ["vacas"]),
    ("queso", "noun", 110, [
        ("alimento hecho de leche cuajada", [], ["cheese"]),
    ], ["quesos"]),
    ("guerra", "noun", 55, [
        ("conflicto armado entre naciones", [], ["war"]),
    ], ["guerras"]),
    ("México", "proper noun", 300, [
        ("pais de America del Norte", [], ["Mexico"]),
    ], ["Mejico"]),
    # A two-word lemma: norm leaves it as "de repente", with the space inside.
    ("de repente", "adverb", 400, [
        ("de manera subita", [], ["suddenly", "all of a sudden"]),
    ], []),
    # With a hyphen: norm turns it into a space, it does not delete it.
    ("self-made", "adjective", 800, [
        ("prestamo del ingles: hecho por si mismo", [], ["self-made"]),
    ], []),
    # An ordering trap: "sol" is the EXACT match and ranks worse than "soler", which only has it as
    # a prefix. Without the exact-first rule, typing "sol" does not return "sol". The real pack has
    # this case at scale: "per" did not return "perro" until position 619.
    ("sol", "noun", 500, [
        ("estrella del sistema solar", [], ["sun"]),
    ], ["soles"]),
    ("soler", "verb", 50, [
        ("tener costumbre de hacer algo", [], ["to use to"]),
    ], ["suele", "solia"]),
    # A duplicates trap: same headword AND same pos, separated only by etymology. In Wiktionary
    # this is common --"hacer" appears five times-- and a list that shows them all repeats the same
    # word. sense_key is what gives them distinct uids (D-058).
    ("vela", "noun", 300, [
        ("cilindro de cera con mecha", [], ["candle"]),
    ], ["velas"], "cera"),
    ("vela", "noun", 310, [
        ("lona que impulsa una embarcacion", [], ["sail"]),
    ], ["velas"], "nautica"),
    # An FTS ORDERING trap: both glosses speak of "mineral", but bm25 rewards the short one that
    # repeats it and penalizes the long one that mentions it once. "cantera" goes FIRST in this
    # list, so it takes the lower rowid -- and the best match goes to "cuarzo", the higher rowid.
    #
    # Without these two, searchDefinitions throwing away FTS's ranking and returning by rowid gave
    # the same result, and the bug was invisible. See test_el_mejor_match_de_fts_no_es_el_de_
    # rowid_mas_bajo, which runs in the gate.
    ("cantera", "noun", 600, [
        ("sitio de donde se saca piedra para obras, y tambien todo lo que de ahi se extrae "
         "incluido algun mineral de poco valor comercial", [], ["quarry"]),
    ], []),
    ("cuarzo", "noun", 610, [
        ("mineral de silice; un mineral muy comun", [], ["quartz"]),
    ], []),
]

METADATA = {
    "pack_id": "es-tr-toy",
    "kind": "bilingual",
    "name": "Juguete Español ↔ English",
    "langs": "es,en",
    # The capability, apart from `kind` (D-183). A bilingual pack declares it just the same: `kind`
    # says which language the definitions are in, this says which the translations are in.
    "translations_to": "en",
    # ⚠️ **One per language, and the toy declares them DIFFERENT on purpose.** If both were "es"
    # the fixture would not exercise the branch that matters: an English lemma folded with
    # Spanish's rules --`ce`→`se`, `v`→`b`-- gives a key the English query never computes, and the
    # tolerant rung would stop finding anything precisely in the pack's English half.
    "fuzzy_profiles": "es,en",
    "fuzzy_profile": "es",
    # It comes from no dump: the 28 entries are written in this file. It is declared all the same
    # because `source_date` says where the content comes from, and "from nowhere" is a valid answer
    # that is worth having written down.
    "source_date": "n/a",
    "license": "CC0-1.0",
    "attribution": "Datos de prueba escritos a mano para los tests; no es un diccionario real.",
    # The manifest here too (D-138): if the toy pack could skip it, the only end-to-end check that
    # runs in the gate would stop exercising it.
    "sources": ("definitions\tDatos de prueba del repo\thttps://example.invalid/toy\t"
                "CC0 1.0\thttps://creativecommons.org/publicdomain/zero/1.0/\n"),
    "source_url": "https://example.invalid/toy",
    # The toy KEEPS proper nouns on purpose, unlike the real packs (D-116): it is that branch's
    # only live fixture, and what holds up the test that both `pos` vocabularies --kaikki's "name"
    # and this file's "proper noun"-- get filtered.
    "proper_nouns": "included",
}


def records():
    """The 28 Spanish entries, and behind them the English ones their translations give.

    ⚠️ **The toy is bidirectional because the real pack is**, and because it is that branch's only
    fixture that runs on a device. A channel with no fixture breaks with nothing to warn: that
    already happened with the `W` tag, which was being emitted without any test looking at it.
    """
    from . import bilingual
    yield from bilingual.bidireccional(_propias(), "es", "en")


def _propias():
    for item in _DATA:
        # The sixth position is optional: only the homographs sharing a headword AND a pos carry
        # it, which without a sense_key would fail the build on repeated identity.
        headword, pos, rank, senses, forms = item[:5]
        sense_key = item[5] if len(item) > 5 else None
        # Seventh position, optional: the WORD's translations, which the source attributed to no
        # sense (tag `W`, D-179).
        word_translations = tuple(item[6]) if len(item) > 6 else ()
        # Eighth, optional: the principal parts the card SHOWS (tag `F`). Keyed by convention,
        # because the toy has no grammatical tags -- what this fixture has to exercise is the
        # channel, not the selection, which lives in `kaikki._display_forms` with its own tests.
        display_forms = tuple(item[7]) if len(item) > 7 else ()
        # Ninth, optional: the word's IPA (tag `I`), without the source's delimiters.
        pronunciation = item[8] if len(item) > 8 else None
        # Tenth, optional: where the word comes from (tag `M`). Uncapped, because the pack decides
        # by WORD and not by length -- a cut origin reads worse than none.
        etymology = item[9] if len(item) > 9 else None
        translations = []
        rendered_senses = []
        for gloss, examples, sense_translations in senses:
            rendered_senses.append(
                {
                    "gloss": gloss,
                    "examples": list(examples),
                    "translations": list(sense_translations),
                }
            )
            translations.extend(sense_translations)
        yield Record(
            headword=headword,
            senses=rendered_senses,
            part_of_speech=pos,
            rank=rank,
            forms=forms,
            display_forms=display_forms,
            pronunciation=pronunciation,
            etymology=etymology,
            # The search channel carries both, just as in a real pack, **and an infinitive's bare
            # form**: the dump writes "to run" and whoever searches types "run". Until here
            # `trans`'s tokenization resolved that (D-014); with that table emptied in a
            # bidirectional pack, it has to be an entry of its own or `run` stops finding `correr`
            # -- which is exactly the path this fixture exists to test.
            translations=_con_forma_desnuda(translations + list(word_translations)),
            word_translations=word_translations,
            sense_key=sense_key,
        )


def _con_forma_desnuda(claves):
    """`["to run"]` -> `["to run", "run"]`, igual que `bilingual.translation_keys`."""
    salida = []
    for clave in claves:
        for candidata in (clave, clave[3:] if clave.startswith("to ") else None):
            if candidata and candidata not in salida:
                salida.append(candidata)
    return salida
