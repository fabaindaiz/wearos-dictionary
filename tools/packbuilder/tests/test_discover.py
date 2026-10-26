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


def _tier(raw, rank, lang="es", etymology=None, gloss=None, known=None):
    first = gloss if gloss is not None else (raw["senses"][0].get("glosses") or [""])[0]
    # `True` stands for an etymology that names no learned origin; a string is the text itself.
    texto = "Del catalán." if etymology is True else etymology or None
    return discover.tier(raw, first_gloss=first, rank=rank, etymology=texto, known=known)


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

    def test_rare_needs_a_low_frequency_and_a_learned_origin(self):
        # `fragor`: Zipf < 3 and from Latin. `airbag` is Zipf < 3 too, and nobody would call it
        # recherché -- what separates them is where the word comes from.
        self.assertEqual(discover.RARE, _tier(_raw("fragor", translations=9), LOW_RANK,
                                              etymology="Del latín fragor."))
        self.assertIsNone(_tier(_raw("airbag", translations=2), LOW_RANK,
                                etymology="Del inglés airbag."))

    def test_rare_reaches_into_the_band_with_no_frequency(self):
        # `pusilánime` has no frequency at all, and it is the archetype of the tier.
        raw = _raw("pusilánime", pos="adj", translations=10,
                   senses=[{"glosses": ["Falto de ánimo y valor para tolerar las desgracias."]}])
        self.assertEqual(discover.RARE, _tier(raw, NO_FREQUENCY,
                                              etymology="Del latín pusillanĭmis."))

    def test_many_translations_mark_a_well_known_word_not_a_rare_one(self):
        # Version 1 read them as notability. Against English prevalence they are the opposite.
        raw = _raw("ingest", pos="verb", translations=25,
                   senses=[{"glosses": ["To take food or drink into the body."]}])
        self.assertIsNone(_tier(raw, NO_FREQUENCY, lang="en", etymology="From Latin ingestus."))
        raw = _raw("jactance", translations=2, senses=[{"glosses": ["Boasting; bragging."]}])
        self.assertEqual(discover.RARE, _tier(raw, NO_FREQUENCY, lang="en",
                                              etymology="From Latin iactantia."))

    def test_a_literary_label_is_a_learned_origin_on_its_own(self):
        raw = _raw("tenebrous", pos="adj", senses=[
            {"glosses": ["Dark and gloomy; obscure."], "tags": ["literary"]}])
        self.assertEqual(discover.RARE, _tier(raw, NO_FREQUENCY, lang="en"))

    def test_without_prevalence_a_synonym_is_not_enough(self):
        # `tráfago` comes from Catalan: with synonyms but no learned origin it gets no tier unless
        # prevalence covers it -- and SPALEX does, which is where a word like it gets its chance.
        raw = _raw("tráfago", synonyms=3)
        self.assertIsNone(_tier(raw, NO_FREQUENCY, etymology=True))
        self.assertEqual(discover.RARE, _tier(raw, NO_FREQUENCY, etymology=True, known=40.0))

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


class PrevalenceTest(unittest.TestCase):
    """Version 2: where the share of people who know a word is known, *rare* is that share.

    Measured on 2026-10-26 with SPALEX (Spanish, CC BY 4.0): our version-1 *rare* had a median of
    **86 % of people knowing the word**, while the published lists of recherché words sit at
    35-63 %. Frequency cannot tell *pelícano* (infrequent, known by all) from *oclocracia*;
    prevalence can. `docs/roadmap.md` i-a2f271-ec624b.
    """

    def test_a_word_known_by_few_is_rare_even_with_a_frequency(self):
        # `inefable`: frequent enough to be *common* by Zipf, known by 63 %.
        raw = _raw("inefable", pos="adj", synonyms=2,
                   senses=[{"glosses": ["Que no se puede explicar con palabras."]}])
        self.assertEqual(discover.RARE, _tier(raw, 251, known=63.0))

    def test_a_word_everybody_knows_is_not_rare_however_infrequent(self):
        raw = _raw("archipiélago", translations=40, synonyms=1,
                   senses=[{"glosses": ["Conjunto de islas cercanas entre sí."]}])
        self.assertIsNone(_tier(raw, NO_FREQUENCY, etymology="Del griego.", known=98.0))

    def test_a_word_almost_nobody_knows_is_not_rare_either(self):
        raw = _raw("acrotera", synonyms=1, senses=[{"glosses": ["Pedestal sobre un frontón."]}])
        self.assertIsNone(_tier(raw, NO_FREQUENCY, etymology="Del griego.", known=6.0))
        # Nor common when it happens to be frequent: frequency there is a homograph or a name.
        self.assertIsNone(_tier(raw, COMMON_RANK, etymology="Del griego.", known=6.0))

    def test_a_rare_word_still_needs_a_learned_origin_a_label_or_a_synonym(self):
        raw = _raw("pizarrero", senses=[{"glosses": ["Persona que trabaja la pizarra."]}])
        self.assertIsNone(_tier(raw, NO_FREQUENCY, etymology=True, known=60.0))

    def test_species_and_trades_are_not_rare_words(self):
        for gloss in ("Planta herbácea de flores amarillas.", "Ave rapaz de gran tamaño.",
                      "Persona que fabrica o vende sombreros."):
            with self.subTest(gloss=gloss):
                raw = _raw("x", synonyms=2, senses=[{"glosses": [gloss]}])
                self.assertIsNone(_tier(raw, NO_FREQUENCY, etymology="Del latín.", known=40.0))

    def test_a_curious_word_everybody_knows_is_not_curious(self):
        raw = _raw("lesera", synonyms=1, senses=[
            {"glosses": ["Cosa sin importancia, tontería."], "tags": ["Chile"]}])
        self.assertEqual(discover.CURIOUS, _tier(raw, NO_FREQUENCY, known=60.0))
        self.assertIsNone(_tier(raw, NO_FREQUENCY, known=96.0))

    def test_without_prevalence_rare_needs_a_learned_origin(self):
        # English has no open prevalence norms: there the Wiktionary signals carry it, and a
        # learned origin is the strongest of them -- x7 in the published lists, measured.
        raw = _raw("vilipend", pos="verb", translations=3,
                   senses=[{"glosses": ["To treat with contempt; to belittle."]}])
        self.assertIsNone(_tier(raw, NO_FREQUENCY, lang="en", etymology="From Old French."))
        self.assertEqual(discover.RARE, _tier(raw, NO_FREQUENCY, lang="en",
                                              etymology="From Latin vilipendere."))

    def test_the_version_moved(self):
        self.assertEqual(3, discover.VERSION)


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

    def test_misspelt_pages_are_out_and_vulgar_ones_have_their_own_tier(self):
        raw = _raw("certainity", senses=[{"glosses": ["Misspelling of certainty."],
                                           "tags": ["misspelling"]}])
        self.assertIsNone(_tier(raw, NO_FREQUENCY, lang="en"))
        # Out of every other tier, as before d-a2f271-136e08; that decision gave them *Vulgar*.
        raw = _raw("cojudo", pos="adj", senses=[{"glosses": ["Persona tonta o de pocas luces."],
                                                  "tags": ["vulgar"]}])
        self.assertEqual(discover.VULGAR, _tier(raw, COMMON_RANK))

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
        self.assertEqual((0, 1, 2, 3, 4), (discover.COMMON, discover.RARE, discover.CURIOUS,
                                            discover.COLLOQUIAL, discover.VULGAR))


if __name__ == "__main__":
    unittest.main()


class TechnicalWithoutPrevalenceTest(unittest.TestCase):
    """English *rare* without prevalence: the jargon a learned etymology lets in.

    Measured on 2026-10-26 over the English rare tier (13,859 words): by eye, 21 of 60 were
    technical (*juliform*, *lymphangion*, *anecic*); the four signals below cut that to 15 of 60
    with none of 14 literary control words lost, at the price of about 500 literary words
    (*diapason*, *cicatrix*). Prevalence cannot judge this: it does not tell jargon from literature.
    """

    def _en(self, word, gloss, topics=None, pos="noun"):
        sense = {"glosses": [gloss]}
        if topics:
            sense["topics"] = topics
        return _tier(_raw(word, pos=pos, senses=[sense]), NO_FREQUENCY, lang="en",
                     etymology="From Latin.")

    def test_a_first_sense_in_a_field_is_jargon(self):
        self.assertIsNone(self._en("taffrail", "The upper part of the stern of a ship.",
                                   topics=["nautical"]))
        # The humanities are not a field to drop: history, literature, religion stay.
        self.assertEqual(discover.RARE, self._en("aedile", "An elected magistrate of ancient Rome.",
                                                 topics=["history"]))

    def test_science_vocabulary_in_the_gloss_is_jargon(self):
        self.assertIsNone(self._en("anecic", "Of an earthworm: that burrows deep into the soil.",
                                   pos="adj"))

    def test_a_scientific_affix_on_the_headword_is_jargon(self):
        self.assertIsNone(self._en("lymphangion", "A vessel that carries lymph.",
                                   topics=None))
        self.assertIsNone(self._en("juliform", "Shaped like a catkin.", pos="adj"))
        # The affix alone, with a gloss no other rule reads as science.
        self.assertIsNone(self._en("glycolatry", "Excessive fondness for sweet things."))

    def test_a_taxon_in_the_gloss_is_jargon(self):
        self.assertIsNone(self._en("bonassus", "Any of several large wild oxen."))

    def test_the_literary_controls_stay(self):
        for word, gloss, pos in (("adroit", "Skillful, dexterous; clever.", "adj"),
                                 ("despond", "To give up hope; to become despondent.", "verb"),
                                 ("jactance", "Boasting; bragging.", "noun"),
                                 ("investiture", "The act of investing someone with an office.",
                                  "noun")):
            with self.subTest(word=word):
                self.assertEqual(discover.RARE, self._en(word, gloss, pos=pos))


class RegisterTest(unittest.TestCase):
    """*Coloquial* and *Vulgar* (d-a2f271-136e08): a word whose senses are MOSTLY so labelled.

    Wiktionary labels one sense of *amigo* colloquial; a tier read from any sense would hold the
    whole language. The cases are words the measurement of 2026-10-26 drew.
    """

    def _senses(self, *tagged):
        return [{"glosses": [gloss], "tags": list(tags)} for gloss, tags in tagged]

    def test_a_word_mostly_colloquial_is_colloquial(self):
        raw = _raw("fome", pos="adj", senses=self._senses(
            ("Aburrido, sin gracia.", ["colloquial", "Chile"])))
        self.assertEqual(discover.COLLOQUIAL, _tier(raw, LOW_RANK))

    def test_one_colloquial_sense_among_many_is_not(self):
        raw = _raw("amigo", senses=self._senses(
            ("Persona con quien se tiene amistad.", []),
            ("Persona que gusta mucho de algo.", []),
            ("Tratamiento afectuoso entre desconocidos.", ["colloquial"])))
        self.assertNotEqual(discover.COLLOQUIAL, _tier(raw, COMMON_RANK))

    def test_half_the_senses_is_a_majority(self):
        raw = _raw("pololo", senses=self._senses(
            ("Novio, enamorado.", ["colloquial", "Chile"]),
            ("Insecto coleóptero de Chile.", [])))
        self.assertEqual(discover.COLLOQUIAL, _tier(raw, LOW_RANK))

    def test_vulgar_wins_over_colloquial_and_is_no_longer_dropped(self):
        # Before this tier `_DROP` threw these away; now they have a tier of their own, and
        # nowhere else.
        raw = _raw("boludo", pos="adj", senses=self._senses(
            ("Tonto, estúpido.", ["vulgar", "colloquial", "Argentina"])))
        self.assertEqual(discover.VULGAR, _tier(raw, COMMON_RANK))

    def test_slang_is_colloquial_not_dropped(self):
        raw = _raw("bae", senses=self._senses(("Darling, sweetheart.", ["slang"])))
        self.assertEqual(discover.COLLOQUIAL, _tier(raw, LOW_RANK))

    def test_the_other_drop_labels_still_drop(self):
        # A colloquial abbreviation (`cel`) is still not a word to learn.
        raw = _raw("cel", senses=self._senses(
            ("Teléfono celular.", ["colloquial", "abbreviation"])))
        self.assertIsNone(_tier(raw, LOW_RANK))

    def test_the_lemma_filters_still_apply(self):
        # A phrase, a name, a function word: the same doors as every other tier.
        phrase = _raw("la chancha y los veinte", senses=self._senses(
            ("Pedir demasiado.", ["colloquial"])))
        self.assertIsNone(_tier(phrase, LOW_RANK))
        frequent = _raw("tío", senses=self._senses(("Persona, tipo.", ["colloquial"])))
        self.assertIsNone(_tier(frequent, 100))

    def test_the_first_sense_must_carry_the_register(self):
        # `cacatúa`: half its senses are derogatory, and the card opens on the bird. Drawn under
        # *Vulgar*, the list would show a parrot.
        raw = _raw("cacatúa", senses=self._senses(
            ("Ave trepadora de Oceanía de plumaje blanco.", []),
            ("Mujer fea y extravagante.", ["derogatory"])))
        self.assertNotIn(_tier(raw, LOW_RANK), (discover.VULGAR, discover.COLLOQUIAL))

    def test_a_diminutive_or_a_clipping_is_not_a_word_to_learn(self):
        for word, gloss in (("caquis", "Diminutivo de caca."), ("cole", "Apócope de colegio.")):
            with self.subTest(word=word):
                raw = _raw(word, senses=self._senses((gloss, ["colloquial"])))
                self.assertIsNone(_tier(raw, LOW_RANK))
