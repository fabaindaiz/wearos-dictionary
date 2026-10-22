import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from sources import discover  # noqa: E402

# The band with frequency is `rank < 500` and `rank = 499 - round(zipf * 70)`: these three are
# Zipf 4.3, 2.6 and "no frequency at all".
COMMON_RANK = 200
LOW_RANK = 320
NO_FREQUENCY = 990


def _raw(word="fragor", pos="noun", senses=None, translations=0, synonyms=0, **extra):
    record = {
        "word": word,
        "pos": pos,
        "senses": senses if senses is not None else [{"glosses": ["Ruido intenso y prolongado."]}],
        "translations": [{"word": "t%d" % i} for i in range(translations)],
        "synonyms": [{"word": "s%d" % i} for i in range(synonyms)],
    }
    record.update(extra)
    return record


def _tier(raw, rank, lang="es", etymology=False, gloss=None):
    first = gloss if gloss is not None else (raw["senses"][0].get("glosses") or [""])[0]
    return discover.tier(raw, first_gloss=first, rank=rank, lang=lang, has_etymology=etymology)


class TierTest(unittest.TestCase):
    """The three tiers of *see more words*: what each admits, and the noise each one keeps out.

    Every case is a word the probe of 2026-10-21 drew from the real dumps
    (`docs/roadmap.md` i-a2f271-ec624b): the ones that made a tier useless before the rule existed.
    """

    def test_common_is_the_band_with_frequency_above_zipf_three(self):
        self.assertEqual(discover.COMMON, _tier(_raw("tertulia"), COMMON_RANK))

    def test_function_words_below_the_floor_are_no_tier(self):
        # `a, de, el`: the word of the day's floor, for the same reason (RANK_FLOOR = 150).
        self.assertIsNone(_tier(_raw("de"), 20))

    def test_rare_needs_a_low_frequency_and_notability(self):
        # `fragor`: Zipf < 3 and translated widely. `airbag` is Zipf < 3 too, and nobody would
        # call it recherché -- what separates them is that the dictionary says more about one.
        self.assertEqual(discover.RARE, _tier(_raw("fragor", translations=9), LOW_RANK))
        self.assertIsNone(_tier(_raw("airbag", translations=2), LOW_RANK))

    def test_rare_reaches_into_the_band_with_no_frequency(self):
        # `pusilánime` has no frequency at all, and it is the archetype of the tier.
        raw = _raw("pusilánime", pos="adj", translations=10,
                   senses=[{"glosses": ["Falto de ánimo y valor para tolerar las desgracias."]}])
        self.assertEqual(discover.RARE, _tier(raw, NO_FREQUENCY))

    def test_rare_notability_threshold_is_per_language(self):
        # English Wiktionary translates far more: 9 translations is notable in Spanish only.
        raw = _raw("pistachio", translations=9, senses=[{"glosses": ["A tree of the cashew family."]}])
        self.assertIsNone(_tier(raw, NO_FREQUENCY, lang="en"))
        raw = _raw("fanfaronade", translations=25, senses=[{"glosses": ["Empty boasting; bluster."]}])
        self.assertEqual(discover.RARE, _tier(raw, NO_FREQUENCY, lang="en"))

    def test_a_literary_label_is_notability_on_its_own(self):
        raw = _raw("tenebrous", pos="adj", senses=[
            {"glosses": ["Dark and gloomy; obscure."], "tags": ["literary"]}])
        self.assertEqual(discover.RARE, _tier(raw, NO_FREQUENCY, lang="en"))

    def test_synonyms_and_an_etymology_are_notability(self):
        raw = _raw("tráfago", synonyms=3)
        self.assertEqual(discover.RARE, _tier(raw, NO_FREQUENCY, etymology=True))
        self.assertIsNone(_tier(raw, NO_FREQUENCY, etymology=False))

    def test_curious_is_a_majority_of_labelled_senses_with_a_real_definition(self):
        raw = _raw("trasmañanar", pos="verb", translations=1, senses=[
            {"glosses": ["Dejar algo para el día siguiente."], "tags": ["outdated"]}])
        self.assertEqual(discover.CURIOUS, _tier(raw, NO_FREQUENCY))

    def test_a_region_counts_as_curious(self):
        raw = _raw("cuatrerismo", synonyms=1, senses=[
            {"glosses": ["Robo de ganado, especialmente de caballos."], "tags": ["Argentina"]}])
        self.assertEqual(discover.CURIOUS, _tier(raw, NO_FREQUENCY))

    def test_one_old_sense_among_many_does_not_make_a_word_curious(self):
        # `impresora` and `apoderarse` filled the first draw: one obsolete sense among current ones.
        raw = _raw("apoderarse", pos="verb", translations=5, senses=[
            {"glosses": ["Hacerse dueño de algo, por lo común de manera violenta."]},
            {"glosses": ["Dar poder una persona a otra para que la represente."],
             "tags": ["obsolete"]},
            {"glosses": ["Tomar posesión de algo o alguien un sentimiento."]},
        ])
        self.assertNotEqual(discover.CURIOUS, _tier(raw, NO_FREQUENCY))

    def test_an_old_spelling_is_not_curious(self):
        # `asconder`: obsolete, and its whole definition is the word it became.
        raw = _raw("asconder", pos="verb", translations=1, senses=[
            {"glosses": ["Esconder."], "tags": ["obsolete"]}])
        self.assertIsNone(_tier(raw, NO_FREQUENCY))

    def test_science_keeps_a_regional_word_out_of_curious(self):
        raw = _raw("chahuar", translations=1, senses=[
            {"glosses": ["Planta de la familia de las bromeliáceas, sin tallo."], "tags": ["Peru"]}])
        self.assertIsNone(_tier(raw, NO_FREQUENCY))

    def test_a_labelled_word_too_common_to_be_curious_is_not_common_either(self):
        # Frequent but regional: on a reader from elsewhere *common* would be the wrong promise.
        raw = _raw("chulo", pos="adj", translations=3, senses=[
            {"glosses": ["Que es bonito o vistoso a la vista."], "tags": ["Mexico"]}])
        self.assertIsNone(_tier(raw, COMMON_RANK))

    def test_curious_needs_the_dictionary_to_say_something_more(self):
        raw = _raw("thrauna", senses=[
            {"glosses": ["Cantidad de objetos que caben en el hueco de las manos."],
             "tags": ["Chile"]}])
        self.assertIsNone(_tier(raw, NO_FREQUENCY))


class LemmaFilterTest(unittest.TestCase):
    """What is not a word to learn, whatever its tier would be."""

    def test_only_nouns_verbs_and_adjectives(self):
        for pos in ("adv", "intj", "name", "phrase", "prefix", "abbrev", "num"):
            with self.subTest(pos=pos):
                self.assertIsNone(_tier(_raw("algo", pos=pos), COMMON_RANK))

    def test_pages_that_are_mostly_form_of_are_not_lemmas(self):
        raw = _raw("girando", pos="verb", senses=[
            {"glosses": ["Gerundio de girar."], "form_of": [{"word": "girar"}]}])
        self.assertIsNone(_tier(raw, COMMON_RANK))

    def test_a_capital_a_digit_or_a_space_is_not_a_lemma_to_learn(self):
        for word in ("Asian", "mp3", "piedra preciosa"):
            with self.subTest(word=word):
                self.assertIsNone(_tier(_raw(word), COMMON_RANK))

    def test_vulgar_and_misspelt_pages_are_out(self):
        raw = _raw("certainity", senses=[{"glosses": ["Misspelling of certainty."],
                                           "tags": ["misspelling"]}])
        self.assertIsNone(_tier(raw, NO_FREQUENCY, lang="en"))
        raw = _raw("cojudo", pos="adj", senses=[{"glosses": ["Persona tonta o de pocas luces."],
                                                  "tags": ["vulgar"]}])
        self.assertIsNone(_tier(raw, COMMON_RANK))

    def test_a_page_whose_every_topic_is_technical_is_out(self):
        raw = _raw("adyacencia", translations=12, senses=[
            {"glosses": ["Relación entre dos vértices unidos por una arista."],
             "topics": ["mathematics"]}])
        self.assertIsNone(_tier(raw, NO_FREQUENCY))

    def test_science_named_in_the_gloss_is_out_of_rare_and_curious(self):
        # `deuterium` has no topic tag: the gloss is what gives it away.
        raw = _raw("deuterium", translations=40, senses=[
            {"glosses": ["An isotope of hydrogen with one proton and one neutron."]}])
        self.assertIsNone(_tier(raw, NO_FREQUENCY, lang="en"))

    def test_transparent_derivatives_and_variants_are_out(self):
        for gloss in ("Que achica.", "Acción o efecto de divulgar.", "Variante poco usada de finés.",
                      "Persona originaria de Nepal.", "Synonym of adapter.",
                      "The state or condition of being swollen.", "One who incites."):
            with self.subTest(gloss=gloss):
                raw = _raw("x", translations=30, senses=[{"glosses": [gloss]}])
                self.assertIsNone(_tier(raw, NO_FREQUENCY, lang="en"))

    def test_prefixed_and_hyphenated_coinages_are_not_rare(self):
        for word in ("nonharmful", "reorderer", "point-free"):
            with self.subTest(word=word):
                raw = _raw(word, pos="adj", translations=30,
                           senses=[{"glosses": ["Not causing harm to anyone at all."]}])
                self.assertNotEqual(discover.RARE, _tier(raw, NO_FREQUENCY, lang="en"))


class PartOfSpeechCodeTest(unittest.TestCase):
    """The codes the table stores and the app reads: part of the pack's contract."""

    def test_codes_are_fixed(self):
        self.assertEqual({"noun": 0, "verb": 1, "adj": 2}, discover.POS_CODES)
        self.assertEqual((0, 1, 2), (discover.COMMON, discover.RARE, discover.CURIOUS))


if __name__ == "__main__":
    unittest.main()
