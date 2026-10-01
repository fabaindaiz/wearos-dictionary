"""Which English glosses are usable as translation keys."""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import json  # noqa: E402
import tempfile  # noqa: E402

from sources import bilingual  # noqa: E402


def _bilingue(word, senses):
    """A real bilingual record, going through `bilingual.records` and its JSONL."""
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

    def test_a_demonym_gloss_yields_NOTHING_not_its_country(self):
        """⚠️ **`Spain`, `Mexico` and `etc` were English entries pointing at nonsense.**

        `canario` reads *"of, from or relating to the Canary Islands, Spain"* -- no parenthesis
        anywhere, which is why the explanation given before the 2026-09-25 rebuild was wrong. Split
        on commas, `of` is one word and does not start with a describing word, and `Spain` is one
        word too, so both became translation keys. Searching `Spain` in the bilingual pack
        returned `canario`.

        **The fix is a whole-gloss judgement, and it had to be: `_DESCRIBES` only ever guarded a
        fragment's first word**, so it threw away the fragment it matched and let the rest of the
        same sentence through.
        """
        self.assertEqual(
            [], bilingual.translation_keys("of, from or relating to the Canary Islands, Spain"))
        self.assertEqual(
            [], bilingual.translation_keys("of, from or relating to the state of Chiapas, Mexico"))

    def test_the_NOUN_demonym_template_yields_nothing_either(self):
        """The adjective template has a twin that the `of` rule cannot reach.

        `banfileño` reads *"native or inhabitant of the city of Banfield, Buenos Aires Province,
        Argentina"*: the first comma-fragment is eight words long, so nothing about it is bare and
        the opening-`of` rule never fires -- yet `Argentina` still becomes a key. Measured over the
        dump this template produces **623 keys from 464 glosses**, and the 14 read at random are
        place names without exception.
        """
        self.assertEqual([], bilingual.translation_keys(
            "native or inhabitant of the city of Banfield, Buenos Aires Province, Argentina"))
        self.assertEqual([], bilingual.translation_keys(
            "native or inhabitant of the province of Álava, Basque Country, Spain"))

    def test_native_on_its_own_is_still_a_translation(self):
        """⚠️ **The same trap as `from`, and it is why the pattern names the whole phrase.**
        `native` alone glosses 5 senses and `native american` 3; matching the single word would
        delete the translation of `nativo`.
        """
        self.assertEqual(["native"], bilingual.translation_keys("native"))
        self.assertEqual(["native", "indigenous"],
                         bilingual.translation_keys("native; indigenous"))

    def test_etc_closes_a_list_and_is_never_a_term(self):
        """⚠️ **`etc` was an English entry of the bilingual pack**, reached from `tanto`, `caber`,
        `pala` and 87 other senses whose gloss ends *"..., etc."*. It is what marks a list as
        unfinished; nothing is ever translated as `etc`, in either direction.
        """
        self.assertEqual(["so much", "long", "hard", "often"],
                         bilingual.translation_keys("so much, long, hard, often, etc."))
        # ⚠️ What this does NOT fix, said here so nobody reads the test as wider than it is:
        # `museum` still comes through, a fragment of "guided visit to a country, museum". That is
        # the comma-split description class, and the whole-gloss rule for it was measured and
        # rejected -- it takes `reply` out of `contestacion`.
        self.assertNotIn("etc", bilingual.translation_keys(
            "tour, guided visit to a country, museum, etc."))
        # ⚠️ **Exact match and not a prefix**, which a mutation probe demanded and the dump
        # settles: `aguafuerte` translates to `etching` and `aguafortista` to `etcher`.
        self.assertEqual(["etching"], bilingual.translation_keys("etching"))
        self.assertEqual(["etcher"], bilingual.translation_keys("etcher"))

    def test_a_fragment_that_describes_what_the_WORD_DOES_is_not_a_term(self):
        """`a-` glosses as *"forms words, especially verbs, that denote entering a state"*, and the
        comma-split handed `forms words` to the index as the English for `a-`. 31 keys over the
        dump: `forms nouns`, `expresses surprise`, `indicates reason`.

        ⚠️ **It names the verb AND requires a second word, and both halves were earned.** Matching
        the verb alone deletes `mean` --`ruin` and `cruel` translate to it, 37 senses-- and `mark`,
        which is `marca`. The description is always two words; the translation is one.
        """
        self.assertEqual([], bilingual.translation_keys("forms nouns"))
        self.assertEqual([], bilingual.translation_keys("expresses surprise"))
        self.assertEqual(["mean"], bilingual.translation_keys("mean"))
        self.assertEqual(["mean", "stingy"], bilingual.translation_keys("mean, stingy"))
        self.assertEqual(["mark"], bilingual.translation_keys("mark"))
        # ⚠️ **A bare verb from the list survives, and this case is synthetic ON PURPOSE.** None of
        # the seven appears alone as a key in today's dump --measured, zero-- so a mutation that
        # drops the second-word requirement changes nothing real and would have survived. The
        # guard stays because `formas` glossing as `forms` is a plausible next dump, and here the
        # test IS the specification rather than a sample of the data.
        self.assertEqual(["forms"], bilingual.translation_keys("forms"))
        self.assertEqual(["expresses"], bilingual.translation_keys("expresses"))

    def test_a_preposition_and_a_determiner_are_not_a_translation(self):
        """`of the`, `from that`, `of those`: 12 keys that no word is ever translated as. They are
        the tail of a description the separator cut.

        ⚠️ **Both lists are closed, and that is what keeps `to be` and `to you`.** A rule over
        "two function words" deletes `estar` -> `to be` (15 senses) and `te` -> `to you`, which are
        right. `be` is not a determiner and `you` is not one either.
        """
        self.assertEqual([], bilingual.translation_keys("of the"))
        self.assertEqual([], bilingual.translation_keys("from those"))
        # `be` and `you` come along because the search channel indexes the bare form of an
        # infinitive too; what matters here is that the PHRASE survives.
        self.assertEqual(["to be", "be"], bilingual.translation_keys("to be"))
        self.assertEqual(["to you", "you", "for you"],
                         bilingual.translation_keys("to you, for you"))
        self.assertEqual(["so that"], bilingual.translation_keys("so that"))

    def test_a_preposition_whose_translation_IS_the_relational_word_keeps_it(self):
        """⚠️ **The rule nearly shipped deleting the translation of `de` and `desde`.**

        `from`, `relating to` and `pertaining to` were in the set, on the reasoning that they open
        the same template. Reading which headwords they hit showed they open 8, 6 and 2 glosses in
        the whole dump, and those are `de`, `desde`, `de parte de`, `a partir de`, `atinente` and
        `para con` -- words whose translation **is** the phrase being thrown away. The count had
        said zero good keys lost, because the list of good keys was written by hand.

        `of` survives alone, and even then only when the gloss goes on: `de` is glossed
        *"of (possession)"*, which is a single piece once the parenthetical is gone.
        """
        self.assertEqual(["from"], bilingual.translation_keys("from (a location)"))
        self.assertEqual(["from"], bilingual.translation_keys("from, on behalf of"))
        self.assertEqual(["relating to"], bilingual.translation_keys("relating to"))
        self.assertEqual(["of"], bilingual.translation_keys("of (possession)"))

    def test_a_list_that_merely_starts_with_an_article_keeps_its_terms(self):
        """⚠️ **The wider rule was measured and REJECTED, and this test is what holds the line.**

        *"If the first fragment is a description, the whole gloss is"* looks like the same idea and
        is not. Measured over the dump it removes 2,804 keys, and reading them shows it takes
        `reply` out of `contestacion` --*"an answer, reply"*-- `loads` out of `multitud`, and
        `thump, thwack, whack, bash` out of `cabronazo`. An article in front of the first
        alternative does not make the rest prose.
        """
        self.assertEqual(["reply"], bilingual.translation_keys("an answer, reply"))
        self.assertEqual(["loads"], bilingual.translation_keys("a lot, loads"))

    def test_of_course_still_survives_because_the_fragment_is_not_bare(self):
        """`of` is excluded from `_DESCRIBES` on purpose: *"of course"* translates `por supuesto`.
        What this rule rejects is `of` **alone** as the opening fragment, which is never a term.
        """
        self.assertEqual(["of course"], bilingual.translation_keys("of course"))

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
    """The bilingual pack also FILLS the payload, not only the search index.

    ⚠️ **Until today it computed the clean keys and threw them away**: `record.translations` feeds
    the `trans` table --which `PackBuilder` normalizes and D-014 tokenizes-- so what stayed in the
    pack served for searching and not for reading. Measured: **206,727 `trans` rows and ZERO in
    `T`/`W`**, meaning the catalog's pack with the most translations was the only one that could
    not show them.

    ⚠️ **The attribution here is STRUCTURAL, like D-124's nested synonyms**: each term comes out of
    **that** sense's gloss, so there is nothing to guess and nothing falls into the entry-level
    channel.
    """

    def test_cada_acepcion_se_queda_con_los_terminos_de_SU_glosa(self):
        record = _bilingue("correr", [
            {"glosses": ["to run, to jog"], "sense_index": "1"},
            {"glosses": ["to flow"], "sense_index": "2"},
        ])
        # ⚠️ The card shows the form the source wrote --`to run`-- and NOT the derived one. Both
        # together ("to run, run, to jog, jog") are noise at 234 dp; the search channel does carry
        # both, because nobody types the preposition.
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
        """The module's same conservative rule: a description is not a translation."""
        record = _bilingue("abada", [
            {"glosses": ["a large mammal of the family Rhinocerotidae"], "sense_index": "1"},
        ])
        self.assertEqual([], record.senses[0]["translations"])

    def test_el_prior_de_frecuencia_TAMBIEN_llega_al_bilingue(self):
        """⚠️ It is the pack where the ordering defect was WORST and it nearly went without the fix.

        `orderFor` applies D-142's coverage band only to `MatchKind.PREFIX`; the `TRANSLATION` rung
        orders by raw `rank`. Measured over the real pack: `house` returned `solar, alojar,
        albergar` and never `casa`. If `bilingual.records` did not chain the frequencies, that rung
        would still be broken with everything else fixed.
        """
        import tempfile, json as _json
        handle = tempfile.NamedTemporaryFile(
            mode="w", suffix=".jsonl", encoding="utf-8", delete=False)
        with handle:
            for palabra in ("casa", "solar"):
                handle.write(_json.dumps({
                    "word": palabra, "pos": "noun", "lang_code": "es", "lang": "Spanish",
                    "pos_title": "Noun",
                    "senses": [{"glosses": ["house"], "sense_index": "1"}]}) + "\n")
        try:
            got = {r.headword: r.rank for r in bilingual.records(
                handle.name, lang="es", frequencies={"casa": 5.5, "solar": 2.0})}
        finally:
            os.unlink(handle.name)
        self.assertLess(got["casa"], got["solar"], "la palabra comun tiene que ganar")

    def test_nada_cae_en_el_canal_de_nivel_de_entrada(self):
        """Every term comes from a concrete sense, so `W` stays empty by construction."""
        record = _bilingue("casa", [{"glosses": ["house"], "sense_index": "1"}])
        self.assertEqual((), record.word_translations)


def _todos_con_flexiones(entradas, flexiones):
    handle = tempfile.NamedTemporaryFile(
        mode="w", suffix=".jsonl", encoding="utf-8", delete=False)
    with handle:
        for e in entradas:
            handle.write(json.dumps(e, ensure_ascii=False) + "\n")
    try:
        return list(bilingual.records(handle.name, lang="es", lang_dst="en",
                                      flexiones=flexiones))
    finally:
        os.unlink(handle.name)


def _todos(entradas):
    """Every record of a bilingual JSONL, in order."""
    handle = tempfile.NamedTemporaryFile(
        mode="w", suffix=".jsonl", encoding="utf-8", delete=False)
    with handle:
        for e in entradas:
            handle.write(json.dumps(e, ensure_ascii=False) + "\n")
    try:
        return list(bilingual.records(handle.name, lang="es", lang_dst="en"))
    finally:
        os.unlink(handle.name)


class LadoInversoTest(unittest.TestCase):
    """The OTHER language's entries, which make the pack bidirectional by construction.

    ⚠️ **Until here the pack was bidirectional for SEARCHING and not for READING.** The English
    words lived only in `trans` --an index from `norm` to a Spanish entry-- so `dog` found `perro`
    but `dog` was not a lemma: there was no card to open and no way to know the pack knew it. Asked
    for: *"redefine the translations as bidirectional by construction and declare both languages as
    peers"*.

    ⚠️ **They are DERIVED from the same dump already read, not from a new source**, which is
    D-175's same reasoning for the core: deriving makes the claim true by construction. If `dog`
    leads to `perro`, it is because `perro`'s gloss said `dog` -- not because two sources agreed.
    """

    def test_una_palabra_inglesa_se_vuelve_ENTRADA_con_su_idioma(self):
        registros = _todos([{
            "word": "perro", "pos": "noun", "lang_code": "es", "lang": "Spanish",
            "pos_title": "Noun", "senses": [{"glosses": ["dog"]}],
        }])
        por_lema = {r.headword: r for r in registros}
        self.assertIn("perro", por_lema)
        self.assertIn("dog", por_lema, "la palabra inglesa tiene que ser un lema")
        self.assertEqual("es", por_lema["perro"].lang)
        self.assertEqual("en", por_lema["dog"].lang)

    def test_la_entrada_inglesa_lleva_sus_equivalentes_españoles(self):
        registros = _todos([
            {"word": "perro", "pos": "noun", "lang_code": "es", "lang": "Spanish",
             "pos_title": "Noun", "senses": [{"glosses": ["dog"]}]},
            {"word": "can", "pos": "noun", "lang_code": "es", "lang": "Spanish",
             "pos_title": "Noun", "senses": [{"glosses": ["dog"]}]},
        ])
        dog = next(r for r in registros if r.headword == "dog")
        self.assertEqual({"perro", "can"}, set(dog.word_translations))

    def test_el_lado_inverso_NO_llena_trans(self):
        # ⚠️ It would be a second copy of the same index: searching "dog" already works through
        # `entry.norm`. Measured over the real pack, `trans` weighed 474,849 rows and 13.3 MiB.
        registros = _todos([{
            "word": "perro", "pos": "noun", "lang_code": "es", "lang": "Spanish",
            "pos_title": "Noun", "senses": [{"glosses": ["dog"]}],
        }])
        for r in registros:
            self.assertEqual((), tuple(r.translations),
                             "en un pack bidireccional `trans` sobra: %r" % r.headword)

    def test_la_entrada_inglesa_recibe_SUS_FLEXIONES(self):
        # ⚠️ **A measurement found the regression this closes, not a test.** On turning the English
        # words into entries and emptying `trans`, the reverse direction's coverage fell from
        # **98.4 % to 89.8 %** over the 1,000 most frequent English words.
        #
        # The cause: `trans` was TOKENIZED (D-014), and `--flexiones` put the English inflections in
        # there --`got`, `been`, `were`, `could`-- which thereby reached the Spanish lemma. With
        # `trans` empty that route disappeared, and the answers being lost were **correct**:
        # `been -> ser, estar, tener`, `could -> poder`.
        #
        # The right place in the new model is the ENGLISH entry's `form`: `got` is an inflection of
        # `get`, and `get` is now a lemma. It comes out symmetric with the Spanish side, which is
        # exactly what the bidirectional pack asserts.
        registros = _todos_con_flexiones(
            [{"word": "conseguir", "pos": "verb", "lang_code": "es", "lang": "Spanish",
              "pos_title": "Verb", "senses": [{"glosses": ["to get"]}]}],
            {"get": ("got", "gets", "getting")},
        )
        get = next(r for r in registros if r.headword == "get")
        self.assertEqual({"got", "gets", "getting"}, set(get.forms))


class ParentesisConComaTest(unittest.TestCase):
    """`Tuesday` was not an entry of the bilingual pack, and it is an order-of-operations bug.

    The gloss of `martes` is *"Tuesday (the third day of the week in many religious traditions,
    and the second day of the week in systems that…)"*. The separator split runs BEFORE the
    parenthetical is removed, so a parenthesis containing a comma is cut in half: the first piece
    keeps an unbalanced `(` and is rejected as a fragment, the second starts with `and` and is
    rejected as a description. The term in front of it never gets a chance.

    ⚠️ **This is not the prose heuristic the roadmap feared.** Nothing here guesses at meaning:
    the parenthetical was already being removed, just too late. `_PARENTHETICAL` still requires a
    balanced pair, so a genuinely unbalanced gloss keeps its `(` and is still rejected.

    ⚠️ `tuesday` appears **14,074** times in `freq-en-opensubs.txt`, so it is not a tail case.
    """

    def test_el_parentesis_con_coma_ya_no_se_come_el_termino(self):
        gloss = ("Tuesday (the third day of the week in many religious traditions, and the "
                 "second day of the week in systems that make Monday the first)")
        self.assertEqual(["Tuesday"], bilingual.translation_keys(gloss))

    def test_el_parentesis_SIN_coma_sigue_funcionando_igual(self):
        # The case that already worked, pinned so the reorder cannot quietly change it.
        self.assertEqual(["foot"], bilingual.translation_keys("foot (a part of the body)"))

    def test_un_parentesis_DESBALANCEADO_sigue_rechazandose(self):
        # The protection the order change must not remove: an unbalanced gloss is a fragment, and
        # `CAT scan")` is a key nobody can type.
        self.assertEqual([], bilingual.translation_keys("CAT scan\") (an imaging"))

    def test_varios_terminos_separados_por_coma_siguen_saliendo_todos(self):
        # The reorder must not turn the separator off: a real list of alternatives still splits.
        self.assertEqual(["dog", "hound"], bilingual.translation_keys("dog, hound"))

    def test_un_termino_con_parentesis_y_una_alternativa_despues(self):
        # Both behaviours at once, which is what the order change is really claiming.
        self.assertEqual(["Tuesday", "martes"],
                         bilingual.translation_keys("Tuesday (the day, after Monday), martes"))
