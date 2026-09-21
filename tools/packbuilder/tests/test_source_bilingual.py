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

    def test_el_prior_de_frecuencia_TAMBIEN_llega_al_bilingue(self):
        """⚠️ Es el pack donde el defecto de orden era PEOR y casi se queda sin el arreglo.

        `orderFor` aplica la banda de cobertura de D-142 solo a `MatchKind.PREFIX`; el peldaño
        `TRANSLATION` ordena por `rank` puro. Medido sobre el pack real: `house` devolvia
        `solar, alojar, albergar` y nunca `casa`. Si `bilingual.records` no encadenara las
        frecuencias, ese peldaño seguiria roto con todo lo demas arreglado.
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
        """Todo termino viene de una acepcion concreta, asi que `W` queda vacio por construccion."""
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
    """Todos los registros de un JSONL bilingue, en orden."""
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
    """Las entradas del OTRO idioma, que hacen el pack bidireccional por construccion.

    ⚠️ **Hasta aca el pack era bidireccional para BUSCAR y no para LEER.** Las palabras inglesas
    vivian solo en `trans` --un indice de `norm` a entrada española--, asi que `dog` encontraba
    `perro` pero `dog` no era un lema: no habia ficha que abrir ni forma de saber que el pack lo
    conocia. Pedido: *«redefinir las traducciones como bidireccionales por construccion y que
    declares a la par ambos idiomas»*.

    ⚠️ **Se DERIVAN del propio volcado que ya se leyo, no de una fuente nueva**, que es el mismo
    razonamiento de D-175 para el nucleo: derivar hace la afirmacion cierta por construccion. Si
    `dog` lleva a `perro`, es porque la glosa de `perro` decia `dog` -- no porque dos fuentes
    coincidieran.
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
        # ⚠️ Seria una segunda copia del mismo indice: buscar "dog" ya funciona por `entry.norm`.
        # Medido sobre el pack real, `trans` pesaba 474.849 filas y 13,3 MiB.
        registros = _todos([{
            "word": "perro", "pos": "noun", "lang_code": "es", "lang": "Spanish",
            "pos_title": "Noun", "senses": [{"glosses": ["dog"]}],
        }])
        for r in registros:
            self.assertEqual((), tuple(r.translations),
                             "en un pack bidireccional `trans` sobra: %r" % r.headword)

    def test_la_entrada_inglesa_recibe_SUS_FLEXIONES(self):
        # ⚠️ **La regresion que esto cierra la encontro una medicion, no un test.** Al volver
        # entradas las palabras inglesas y vaciar `trans`, la cobertura de la direccion inversa
        # cayo de **98,4 % a 89,8 %** en las 1.000 palabras inglesas mas frecuentes.
        #
        # La causa: `trans` estaba TOKENIZADA (D-014), y `--flexiones` metia ahi las flexiones
        # inglesas -- `got`, `been`, `were`, `could`-- que asi llegaban al lema español. Con
        # `trans` vacia esa ruta desaparecio, y las respuestas que se perdian eran **correctas**:
        # `been -> ser, estar, tener`, `could -> poder`.
        #
        # El lugar correcto en el modelo nuevo es `form` de la entrada INGLESA: `got` es una
        # flexion de `get`, y `get` ahora es un lema. Queda simetrico con el lado español, que
        # es justo lo que el pack bidireccional afirma.
        registros = _todos_con_flexiones(
            [{"word": "conseguir", "pos": "verb", "lang_code": "es", "lang": "Spanish",
              "pos_title": "Verb", "senses": [{"glosses": ["to get"]}]}],
            {"get": ("got", "gets", "getting")},
        )
        get = next(r for r in registros if r.headword == "get")
        self.assertEqual({"got", "gets", "getting"}, set(get.forms))
