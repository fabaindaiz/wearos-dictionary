"""Which English glosses are usable as translation keys."""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import json  # noqa: E402
import tempfile  # noqa: E402

from sources import bilingual  # noqa: E402


def _bilingue(word, senses):
    """Un registro bilingue real, pasando por `bilingual.records` y su JSONL."""
    handle = tempfile.NamedTemporaryFile(
        mode="w", suffix=".jsonl", encoding="utf-8", delete=False)
    with handle:
        handle.write(json.dumps({
            "word": word, "pos": "verb", "lang_code": "es", "lang": "Spanish",
            "pos_title": "Verb", "senses": senses}, ensure_ascii=False) + "\n")
    try:
        return next(iter(bilingual.records(handle.name, lang="es")))
    finally:
        os.unlink(handle.name)


class TranslationKeysTest(unittest.TestCase):
    """The reverse index of a bilingual pack, derived from glosses.

    ⚠️ **The source has no `translations` field at all.** The English Wiktionary's Spanish section
    gives Spanish words with English *glosses*, so the only way to answer "what is `dog` in
    Spanish" is to decide which glosses are translations rather than descriptions. Measured over
    141,166 senses: **11.7 % are a translation and 88.3 % are a paraphrase.**

    That is why this is conservative. A key that is wrong sends the reader to another word, which
    is worse than not finding anything -- the same reasoning as D-126 for synonyms.
    """

    def test_a_bare_term_is_a_translation(self):
        self.assertEqual(["dog"], bilingual.translation_keys("dog"))

    def test_a_comma_list_gives_every_term(self):
        # "free, without charge" is the real shape of a Wiktionary gloss: several ways to say the
        # same thing, all of them valid to search by.
        self.assertEqual(["free", "without charge"],
                         bilingual.translation_keys("free, without charge"))

    def test_a_parenthetical_is_dropped_and_the_term_survives(self):
        # "foot (a part of the body)" -- the gloss is a translation with a disambiguation glued
        # on. Keeping the parenthetical would index a phrase nobody types.
        self.assertEqual(["foot"], bilingual.translation_keys("foot (a part of the body)"))

    def test_a_verb_is_indexed_with_and_without_its_to(self):
        # English infinitives arrive as "to run". Somebody searching the translation types "run".
        self.assertEqual(["to run", "run"], bilingual.translation_keys("to run"))

    def test_a_description_is_NOT_a_translation(self):
        # ⚠️ The half that matters. These are 88 % of the dump, and indexing them would make
        # `MatchKind.TRANSLATION` mean "some definition mentions this", which is what FTS is for.
        for glosa in (
            "English or American foot, a unit of length equal to twelve inches",
            "a person who works with wood",
            "the act of running quickly",
            "used to express surprise",
        ):
            self.assertEqual([], bilingual.translation_keys(glosa), glosa)

    def test_an_inflection_note_is_not_a_translation(self):
        # `kaikki._is_form_of` already prunes most of these, but the wording also appears inside
        # ordinary senses.
        self.assertEqual([], bilingual.translation_keys("plural of pie"))
        self.assertEqual([], bilingual.translation_keys("feminine singular of bonito"))

    def test_an_empty_or_junk_gloss_gives_nothing(self):
        self.assertEqual([], bilingual.translation_keys(""))
        self.assertEqual([], bilingual.translation_keys("   "))
        self.assertEqual([], bilingual.translation_keys("(obsolete)"))

    def test_the_result_has_no_duplicates_and_keeps_its_order(self):
        # Order matters for nothing functional, but a stable result is what makes two builds of
        # the same dump produce the same pack.
        self.assertEqual(["run", "to run"],
                         bilingual.translation_keys("run, to run, run"))

    def test_an_unbalanced_parenthesis_does_not_leak_into_a_key(self):
        # ⚠️ Found by reading the real output, not by reasoning: the dump contains glosses with
        # unbalanced parentheses and stray quotes, and they came through as `CAT scan")` and
        # `or cultures)`. A key nobody can type is dead weight in the index.
        #
        # ⚠️ And the two are caught by DIFFERENT rules, which is the part worth knowing. The
        # first is a good term with junk glued on, so stripping the borders is enough. The
        # second survives stripping -- what gives it away is that it starts with a conjunction,
        # meaning the separator cut it out of the middle of a phrase.
        self.assertEqual(["CAT scan"], bilingual.translation_keys('CAT scan")'))
        self.assertEqual([], bilingual.translation_keys("or cultures)"))

    def test_surrounding_quotes_are_not_part_of_the_word(self):
        self.assertEqual(["pepper"], bilingual.translation_keys('"pepper"'))
        self.assertEqual(["pepper"], bilingual.translation_keys("\u201cpepper\u201d"))

    def test_a_term_that_is_too_long_is_dropped(self):
        # A four-word "term" is a description with commas, not a translation.
        self.assertEqual([], bilingual.translation_keys("a thing that goes fast"))


class CanalDeLecturaTest(unittest.TestCase):
    """El pack bilingue tambien LLENA el payload, no solo el indice de busqueda.

    ⚠️ **Hasta hoy calculaba las claves limpias y las tiraba**: `record.translations` alimenta la
    tabla `trans` --que `PackBuilder` normaliza y D-014 tokeniza-- asi que lo que quedaba en el
    pack servia para buscar y no para leer. Medido: **206.727 filas de `trans` y CERO en `T`/`W`**,
    o sea el pack con mas traducciones del catalogo era el unico que no podia mostrarlas.

    ⚠️ **La atribucion aca es ESTRUCTURAL, como los sinonimos anidados de D-124**: cada termino
    sale de la glosa de **esa** acepcion, asi que no hay nada que adivinar y nada cae en el canal
    de nivel de entrada.
    """

    def test_cada_acepcion_se_queda_con_los_terminos_de_SU_glosa(self):
        record = _bilingue("correr", [
            {"glosses": ["to run, to jog"], "sense_index": "1"},
            {"glosses": ["to flow"], "sense_index": "2"},
        ])
        # ⚠️ La ficha muestra la forma que la fuente escribio --`to run`-- y NO la derivada.
        # Las dos juntas ("to run, run, to jog, jog") son ruido en 234 dp; el canal de busqueda
        # si lleva las dos, porque nadie teclea la preposicion.
        self.assertEqual(["to run", "to jog"], record.senses[0]["translations"])
        self.assertEqual(["to flow"], record.senses[1]["translations"])

    def test_el_canal_de_busqueda_sigue_llevando_todo(self):
        record = _bilingue("correr", [
            {"glosses": ["to run, to jog"], "sense_index": "1"},
            {"glosses": ["to flow"], "sense_index": "2"},
        ])
        for clave in ("to run", "run", "to jog", "jog", "to flow", "flow"):
            self.assertIn(clave, record.translations)

    def test_una_glosa_que_DESCRIBE_no_deja_termino_en_la_ficha(self):
        """La misma regla conservadora del modulo: una descripcion no es una traduccion."""
        record = _bilingue("abada", [
            {"glosses": ["a large mammal of the family Rhinocerotidae"], "sense_index": "1"},
        ])
        self.assertEqual([], record.senses[0]["translations"])

    def test_nada_cae_en_el_canal_de_nivel_de_entrada(self):
        """Todo termino viene de una acepcion concreta, asi que `W` queda vacio por construccion."""
        record = _bilingue("casa", [{"glosses": ["house"], "sense_index": "1"}])
        self.assertEqual((), record.word_translations)
