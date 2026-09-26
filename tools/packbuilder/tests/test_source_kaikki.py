"""Tests of the kaikki source: the pruning, which is where the pack's size gets decided.

The builder already has its tests. Here the other thing gets verified: that what comes out of a
kaikki.org record is what we want and **nothing more**. The three things no pack invariant catches:

  - an entry that is really an inflected form ("amigo" as the present of "amigar") is not an
    entry: it is a form that has to lead to its lemma;
  - an empty gloss is not a sense, and a record with no usable senses is not an entry;
  - two homographs sharing word AND pos AND pos_title genuinely exist (leonino) and without a
    sense_key they make the build fail.

The fixtures are real records from the Wiktionary dump, trimmed to the fields the pruning looks
at. See docs/formato-pack.md.
"""

import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from sources import kaikki  # noqa: E402


def _jsonl(*records):
    handle = tempfile.NamedTemporaryFile(
        mode="w", suffix=".jsonl", encoding="utf-8", delete=False
    )
    with handle:
        for record in records:
            handle.write(json.dumps(record, ensure_ascii=False) + "\n")
    return handle.name


def _raw(word, pos, senses, **extra):
    record = {"word": word, "pos": pos, "lang_code": "es", "lang": "Español",
              "pos_title": extra.pop("pos_title", pos.title()), "senses": senses}
    record.update(extra)
    return record


def _sense(gloss, **extra):
    sense = {"glosses": [gloss] if gloss else [], "sense_index": "1"}
    sense.update(extra)
    return sense


class PodaTest(unittest.TestCase):
    def setUp(self):
        self.paths = []

    def tearDown(self):
        for path in self.paths:
            os.unlink(path)

    def records(self, *raw):
        path = _jsonl(*raw)
        self.paths.append(path)
        return list(kaikki.records(path))

    def test_la_etimologia_se_lee_de_LOS_DOS_nombres_de_campo(self):
        """⚠️ The trap that would have cost a rebuild, and it is silent.

        Spanish writes `etymology_texts`, a LIST; English writes `etymology_text`, a STRING.
        Reading only one produces the other pack with **no etymology at all** -- no error, no log,
        and `verify_pack.py` passes because every invariant still holds. It is the same shape as a
        missing `norm()`: the defect is an absence.
        """
        self.assertEqual(
            "Del latín casa.",
            kaikki._etymology({"etymology_texts": ["Del latín casa."]}),
            "Spanish: a list",
        )
        self.assertEqual(
            "From Old English hus.",
            kaikki._etymology({"etymology_text": "From Old English hus."}),
            "English: a string",
        )

    def test_sin_etimologia_devuelve_None_y_no_cadena_vacia(self):
        """`render` skips a falsy value, so both work -- but `None` is what "absent" means."""
        self.assertIsNone(kaikki._etymology({}))
        self.assertIsNone(kaikki._etymology({"etymology_texts": []}))
        self.assertIsNone(kaikki._etymology({"etymology_text": "   "}))

    def test_una_etimologia_con_VARIOS_textos_se_queda_con_el_primero(self):
        """A watch card shows one. The rest are alternative accounts of the same word."""
        self.assertEqual(
            "Primera.",
            kaikki._etymology({"etymology_texts": ["Primera.", "Segunda."]}),
        )

    def test_el_ARBOL_de_etimologia_no_llega_a_la_tarjeta(self):
        """⚠️ **English renders the `{{etymon}}` template INTO the field, and it is not prose.**

        The dump writes a literal `Etymology tree` followed by one line per proto-form, and only
        then the sentence a reader wants. Carried whole, the card shows *"Etymology tree
        Proto-Indo-European *(s)kewH-der.? Proto-Germanic *hūsą ..."* -- the newlines collapse into
        spaces and the result is template noise. Measured over the whole English dump: **9.9 %** of
        the entries with an etymology carry the tree.

        The tree closes with the page's OWN entry --`English house`-- and the prose follows it.
        Cutting there finds it in **99.95 %** of the 52,925 trees.
        """
        crudo = ("Etymology tree\nProto-Germanic *hūsą\nOld English hūs\nEnglish house\n"
                 "From Middle English hous, from Old English hūs.")
        self.assertEqual(
            "From Middle English hous, from Old English hūs.",
            kaikki._etymology({"word": "house", "etymology_text": crudo}),
        )

    def test_una_prosa_CORTA_detras_del_arbol_tambien_se_rescata(self):
        # ⚠️ The first rule tried counted words and lost these: `From folk + -ie.` is four words,
        # and **87 % of the trees** end in a sentence that short. What decides is the marker line,
        # not the length.
        crudo = "Etymology tree\nEnglish folk\nEnglish -ie\nEnglish folkie\nFrom folk + -ie."
        self.assertEqual(
            "From folk + -ie.",
            kaikki._etymology({"word": "folkie", "etymology_text": crudo}),
        )

    def test_un_arbol_SIN_prosa_detras_no_deja_el_arbol(self):
        # 26 of the 52,925 trees end with nothing usable behind them. Absence reads clean on the
        # card; the tree does not.
        crudo = "Etymology tree\nProto-Germanic *fulką\nEnglish folk"
        self.assertIsNone(kaikki._etymology({"word": "folk", "etymology_text": crudo}))
        sin_marca = "Etymology tree\nProto-Germanic *fulką\nOld English folc"
        self.assertIsNone(kaikki._etymology({"word": "folkie", "etymology_text": sin_marca}))

    def test_una_etimologia_SIN_arbol_pasa_intacta(self):
        # The 90 % case, and the whole Spanish dump: nothing to cut. A rule that trimmed here would
        # eat the first sentence of every entry, which is the one that matters.
        self.assertEqual(
            "From Middle English rennen, from Old English rinnan.",
            kaikki._etymology({"word": "run",
                               "etymology_text": "From Middle English rennen, from Old English "
                                                 "rinnan."}),
        )

    def test_el_arbol_se_corta_en_la_ULTIMA_marca_y_no_en_la_primera(self):
        # A tree can name the page's own word in the middle of a branch. Cutting at the first
        # occurrence would leave half a tree glued in front of the prose.
        crudo = ("Etymology tree\nEnglish dog\nEnglish -gy\nEnglish doggy\nEnglish dog\n"
                 "From dog + -y.")
        self.assertEqual(
            "From dog + -y.",
            kaikki._etymology({"word": "dog", "etymology_text": crudo}),
        )

    def test_la_glosa_y_un_ejemplo_sobreviven(self):
        got = self.records(_raw("casa", "noun", [
            _sense("Edificio para habitar.", examples=[{"text": "La casa de la esquina."}]),
        ]))
        self.assertEqual(len(got), 1)
        self.assertEqual(got[0].headword, "casa")
        self.assertEqual(got[0].senses[0]["gloss"], "Edificio para habitar.")
        self.assertEqual(got[0].senses[0]["examples"], ["La casa de la esquina."])

    def test_lo_que_no_es_definicion_se_descarta(self):
        """Etymology, sounds and categories are half the dump's weight and are not displayed."""
        got = self.records(_raw(
            "casa", "noun", [_sense("Edificio para habitar.")],
            etymology_texts=["Del latín casa."],
            sounds=[{"ipa": "[ˈka.sa]"}],
            categories=[{"name": "ES:Sustantivos"}],
            hyphenations=[{"parts": ["ca", "sa"]}],
        ))
        rendered = json.dumps(got[0].senses, ensure_ascii=False)
        for veneno in ("Del latín casa.", "[ˈka.sa]", "ES:Sustantivos"):
            self.assertNotIn(veneno, rendered)

    def test_el_pack_monolingue_no_lleva_traducciones(self):
        """D-034: in a monolingual pack `trans` duplicates what fts_def already indexes better."""
        got = self.records(_raw(
            "casa", "noun", [_sense("Edificio para habitar.")],
            translations=[{"word": "house", "lang_code": "en"},
                          {"word": "Haus", "lang_code": "de"}],
        ))
        self.assertEqual(tuple(got[0].translations), ())
        self.assertEqual(tuple(got[0].senses[0].get("translations", ())), ())

    def test_una_pagina_de_forma_flexionada_no_es_una_entrada(self):
        """82.33 % of the dump is these pages. They are not shown: they are searched and land on the lemma."""
        got = self.records(
            _raw("amigar", "verb", [_sense("Hacer amigos a quienes estaban reñidos.")],
                 forms=[{"form": "amigo"}, {"form": "amigas"}]),
            _raw("amigo", "verb", [
                _sense("Primera persona del singular del presente de amigar.",
                       tags=["form-of"], form_of=[{"word": "amigar"}]),
            ], pos_title="Forma verbal"),
        )
        self.assertEqual([r.headword for r in got], ["amigar"])
        self.assertIn("amigo", got[0].forms)

    def test_la_forma_llega_al_lema_aunque_el_lema_no_la_declare(self):
        """It is the reason the source has two passes, and it is measured: the lemma's `forms`
        leaves **7.66 % of the form-words uncovered** (53,708 of 700,959). Each is a search that
        finds nothing. "palpitaciones" -> "palpitacion" is one of them."""
        got = self.records(
            _raw("palpitación", "noun", [_sense("Latido del corazón.")]),
            _raw("palpitaciones", "noun", [
                _sense("Forma del plural de palpitación.",
                       tags=["form-of"], form_of=[{"word": "palpitación"}]),
            ], pos_title="Forma sustantiva"),
        )
        self.assertEqual([r.headword for r in got], ["palpitación"])
        self.assertIn("palpitaciones", got[0].forms)

    def test_una_forma_de_un_lema_que_no_existe_no_inventa_una_entrada(self):
        got = self.records(_raw("huis", "verb", [
            _sense("Segunda persona del plural de huir.",
                   tags=["form-of"], form_of=[{"word": "huir"}]),
        ], pos_title="Forma verbal"))
        self.assertEqual(got, [])

    def test_un_lema_que_ademas_tiene_acepcion_propia_sigue_siendo_entrada(self):
        """"amigo" tambien es sustantivo: la forma verbal no puede borrar el sustantivo."""
        got = self.records(
            _raw("amigar", "verb", [_sense("Hacer amigos a quienes estaban reñidos.")]),
            _raw("amigo", "noun", [_sense("Persona con quien se tiene amistad.")]),
            _raw("amigo", "verb", [
                _sense("Presente de amigar.", tags=["form-of"], form_of=[{"word": "amigar"}]),
            ], pos_title="Forma verbal"),
        )
        self.assertEqual(sorted(r.headword for r in got), ["amigar", "amigo"])

    def test_una_glosa_vacia_no_es_una_acepcion(self):
        got = self.records(_raw("casa", "noun", [
            _sense(""), _sense("Edificio para habitar."),
        ]))
        self.assertEqual([s["gloss"] for s in got[0].senses], ["Edificio para habitar."])

    def test_un_registro_sin_acepciones_usables_no_se_emite(self):
        got = self.records(_raw("casa", "noun", [_sense("")]))
        self.assertEqual(got, [])

    def test_las_formas_se_deduplican_y_excluyen_al_lema(self):
        got = self.records(_raw(
            "japonés", "adj", [_sense("Propio de Japón.")],
            forms=[{"form": "japoneses"}, {"form": "japonesas"},
                   {"form": "japoneses"}, {"form": "japonés"}],
        ))
        self.assertEqual(sorted(got[0].forms), ["japonesas", "japoneses"])

    def test_los_homografos_con_el_mismo_pos_title_se_separan_con_sense_key(self):
        """leonino/adj appears three times, distinguished only by etymology. Without a sense_key,
        stable_uid() collides and the build fails."""
        got = self.records(
            _raw("leonino", "adj", [_sense("Que concierne al león.")],
                 etymology_texts=["Del latín leoninus."]),
            _raw("leonino", "adj", [_sense("Que concierne a los papas llamados León.")],
                 etymology_texts=["Epónimo: los papas llamados León."]),
            _raw("leonino", "adj", [_sense("Que concierne al poeta Leonius.")],
                 etymology_texts=["Epónimo: el poeta Leonius."]),
        )
        self.assertEqual(len(got), 3)
        self.assertEqual(len({r.sense_key for r in got}), 3)

    def test_un_registro_unico_no_lleva_sense_key(self):
        """sense_key goes into the uid: setting it when it is not needed makes it unstable."""
        got = self.records(_raw("casa", "noun", [_sense("Edificio para habitar.")]))
        self.assertIsNone(got[0].sense_key)


class IdiomaTest(unittest.TestCase):
    """The pruning is the same for every language; what changes is the rank's calibration.

    These tests exist so that does not get forgotten: the day somebody puts in a heuristic that
    depends on Spanish, the English case catches it.
    """

    def setUp(self):
        self.paths = []

    def tearDown(self):
        for path in self.paths:
            os.unlink(path)

    def test_la_poda_funciona_igual_sobre_un_dump_de_ingles(self):
        # wiktextract's tags are in English and are the same in every dump: the inflected-form
        # detection does not depend on the content's language.
        path = _jsonl(
            _raw("run", "verb", [_sense("To move at a fast pace.")],
                 pos_title="Verb", forms=[{"form": "running"}, {"form": "ran"}]),
            _raw("ran", "verb", [
                _sense("simple past of run", tags=["form-of"], form_of=[{"word": "run"}]),
            ], pos_title="Verb"),
        )
        self.paths.append(path)
        got = list(kaikki.records(path, lang="en"))
        self.assertEqual(["run"], [r.headword for r in got])
        self.assertIn("ran", got[0].forms)

    def test_un_idioma_sin_perfil_falla_ruidosamente(self):
        # Silence here would mean building a pack with another language's rank.
        path = _jsonl(_raw("run", "verb", [_sense("To move fast.")]))
        self.paths.append(path)
        with self.assertRaises(KeyError):
            list(kaikki.records(path, lang="klingon"))

    def test_la_politica_lexical_only_poda_los_nombres_propios(self):
        # D-116 is still available and still does what it did; what changed is that **it is no
        # longer the default** (D-141). It is asked for by name, and it is the one that produced
        # D-116's numbers.
        path = _jsonl(
            _raw("London", "name", [_sense("The capital of England.")]),
            _raw("run", "verb", [_sense("To move at a fast pace.")]),
        )
        self.paths.append(path)
        got = [r.headword for r in kaikki.records(path, lang="en", politica="lexical-only")]
        self.assertEqual(["run"], got)

    def test_un_nombre_propio_con_senal_lexica_se_conserva(self):
        """"January" is not "Ivanivka", and the source can tell them apart without reading the text.

        Measured: January has 69 across translations, descendants and derivatives; February 50;
        Paris 172; Moscow 330. A surname (Hopewell) and a village (Ivanivka) have 0. The signal is
        structural --they are wiktextract fields-- so the pruning still does not depend on the
        language (D-076).
        """
        path = _jsonl(_raw("January", "name", [_sense("The first month of the year.")],
                           descendants=[{"word": "w%d" % i} for i in range(6)]))
        self.paths.append(path)
        got = [r.headword for r in kaikki.records(path, lang="en")]
        self.assertEqual(["January"], got)

    def test_un_nombre_propio_sin_senal_lexica_se_va_igual(self):
        # 163,470 of these in English, 32,305 in Spanish. They are 99 % of the proper nouns.
        path = _jsonl(
            _raw("Ivanivka", "name", [_sense("A village in Cherkasy Oblast, Ukraine.")]),
            _raw("Hopewell", "name", [_sense("A surname.")], derived=[{"word": "uno"}]),
        )
        self.paths.append(path)
        # ⚠️ **It is no longer the default** (D-141). It was explicitly asked that no source lose
        # words: "I want them to go in complete rather than having to decide what to remove and
        # what not and getting it wrong". The pruning still exists and is asked for by name.
        self.assertEqual(
            [], [r.headword for r in kaikki.records(path, lang="en", politica="lexical-only")])

    def test_por_DEFECTO_no_se_pierde_ninguna_palabra(self):
        """The default is `included`: no source loses entries (D-141).

        ⚠️ **What makes this default safe is the rank penalty**, not the hope that they will not
        get in the way. D-116 measured 4,267 cases in English where the toponym beats the common
        word on rank; with `CASTIGO_NOMBRE_PROPIO` the best proper noun sits below the worst common
        word, so they get in **without displacing anything**.
        """
        path = _jsonl(
            _raw("Ivanivka", "name", [_sense("A village in Cherkasy Oblast, Ukraine.")]),
            _raw("run", "verb", [_sense("To move at a fast pace.")]),
        )
        self.paths.append(path)
        got = {r.headword: r.rank for r in kaikki.records(path, lang="en")}
        self.assertEqual({"Ivanivka", "run"}, set(got))
        self.assertGreater(got["Ivanivka"], got["run"],
                           "el nombre propio tiene que entrar DEBAJO de la palabra comun")

    def test_la_politica_included_los_trae_de_vuelta_a_todos(self):
        # The measurement is still possible: it is what produced D-116's number.
        path = _jsonl(
            _raw("London", "name", [_sense("The capital of England.")]),
            _raw("run", "verb", [_sense("To move at a fast pace.")]),
        )
        self.paths.append(path)
        got = [r.headword for r in kaikki.records(path, lang="en", politica="included")]
        self.assertEqual(["London", "run"], sorted(got))


class PoliticaDefinitionsOnlyTest(unittest.TestCase):
    """The third policy: the proper noun that DEFINES gets in, not the one that merely registers.

    `lexical-only` prunes by lexical signal, and that takes "Fez" and "Puruándiro" along with the
    26,708 surnames. Measured over the Spanish pack with `--con-nombres`: of the 31,549 entries
    discarded today, **28,314 say nothing but their category** ("Apellido.", "Nombre de pila de
    mujer.") and **3,235 carry a real definition** -- cities, taxonomic genera, archaic spellings,
    El Cid's horse.

    ⚠️ **The marker is `categories`, and that is NOT a heuristic over the text.** A filter over the
    gloss's prose would be a pattern in Spanish that is no use in English, exactly what point 4 of
    the module's docstring says is not done. `categories` is emitted by wiktextract from the wiki's
    own categorization: 26,708 senses in `ES:Apellidos` and 2,398 in the three `ES:Antropónimos`.
    The list lives in the `Perfil`, which is already the piece calibrated per language.
    """

    def setUp(self):
        self.paths = []

    def tearDown(self):
        for path in self.paths:
            os.unlink(path)

    def _cats(self, *nombres):
        return [{"name": n} for n in nombres]

    def test_un_apellido_no_entra_aunque_la_politica_sea_permisiva(self):
        path = _jsonl(_raw("Hopewell", "name",
                           [_sense("Apellido.", categories=self._cats("ES:Apellidos"))]))
        self.paths.append(path)
        got = [r.headword for r in kaikki.records(path, lang="es",
                                                  politica="definitions-only")]
        self.assertEqual([], got)

    def test_una_ciudad_QUE_DEFINE_si_entra(self):
        """The case the policy exists to rescue, and that `lexical-only` throws away."""
        path = _jsonl(_raw("Puruándiro", "name",
                           [_sense("Ciudad del estado de Michoacán en México.")]))
        self.paths.append(path)
        self.assertEqual(
            [], [r.headword for r in kaikki.records(path, lang="es", politica="lexical-only")],
            "la politica podadora si la tira: no tiene señal lexica")
        self.assertEqual(["Puruándiro"],
                         [r.headword for r in kaikki.records(path, lang="es",
                                                             politica="definitions-only")])

    def test_con_una_acepcion_que_define_alcanza(self):
        # "Estrella" is a given name AND a star. Pruning it by the first sense would lose the
        # second, which is vocabulary.
        path = _jsonl(_raw("Estrella", "name", [
            _sense("Nombre de pila de mujer.", categories=self._cats("ES:Antropónimos femeninos")),
            _sense("Cuerpo celeste que brilla con luz propia."),
        ]))
        self.paths.append(path)
        got = [r.headword for r in kaikki.records(path, lang="es", politica="definitions-only")]
        self.assertEqual(["Estrella"], got)

    def test_el_nombre_propio_que_entra_PIERDE_prioridad(self):
        """Asked for that way: do not delete, demote.

        Without this the policy makes the search worse instead of better: it is exactly the effect
        D-116 measured in English --4,267 cases where the toponym beats the common word on rank--
        and it would come back through the back door.
        """
        path = _jsonl(
            _raw("Fez", "name", [_sense("Una de las principales ciudades de Marruecos.")]),
            _raw("fez", "noun", [_sense("Gorro de fieltro rojo, tronco-conico.")]),
        )
        self.paths.append(path)
        por_lema = {r.headword: r.rank
                    for r in kaikki.records(path, lang="es", politica="definitions-only")}
        self.assertGreater(por_lema["Fez"], por_lema["fez"],
                           "el nombre propio tiene que quedar DEBAJO (rank mayor = menos comun)")

    def test_una_politica_desconocida_no_se_traga_en_silencio(self):
        # A typo in the CLI cannot build a pack with the default policy and not say so: the pack
        # would come out fine and with different content from the one asked for.
        path = _jsonl(_raw("x", "noun", [_sense("una glosa")]))
        self.paths.append(path)
        with self.assertRaises(ValueError):
            list(kaikki.records(path, lang="es", politica="lo-que-sea"))


class MarkupEditorialTest(unittest.TestCase):
    """The wiki's maintenance tags are not part of the definition (D-121).

    wiktextract leaves them embedded in `glosses` and there is no clean version: `raw_glosses` is
    None in every measured case. On a watch, "Pene.^([cita requerida])" spends half the screen
    telling the reader that a Wiktionary editor wanted a source.
    """

    def setUp(self):
        self.paths = []

    def tearDown(self):
        for path in self.paths:
            os.unlink(path)

    def _gloss(self, texto, lang="es"):
        path = _jsonl(_raw("x", "noun", [_sense(texto)]))
        self.paths.append(path)
        return next(iter(kaikki.records(path, lang=lang))).senses[0]["gloss"]

    def test_se_saca_la_etiqueta_de_cita_requerida(self):
        # 671 cases in the Spanish dump.
        self.assertEqual("Pene.", self._gloss("Pene.^([cita requerida])"))

    def test_se_saca_la_de_definicion_imprecisa(self):
        # 103 casos.
        self.assertEqual(
            "Cierta tela usada antiguamente.",
            self._gloss("Cierta tela usada antiguamente.^([definición imprecisa])"),
        )

    def test_el_punto_que_queda_colgando_no_duplica(self):
        # "...los labios.^([cita requerida])." ends in TWO full stops if only the tag is deleted.
        self.assertEqual("Lamer con la boca.",
                         self._gloss("Lamer con la boca.^([cita requerida])."))

    def test_la_notacion_matematica_NO_se_toca(self):
        """The filter is the form WITH SQUARE BRACKETS, and this is why.

        In English `^(...)` is a mathematical superscript: 10^(100), 2^(2/r), e^(iπ). A filter over
        a bare `^(...)` would destroy real content instead of cleaning it.
        """
        self.assertEqual("A number, 10^(100).", self._gloss("A number, 10^(100).", lang="en"))
        self.assertEqual("Equal to e^(iπ).", self._gloss("Equal to e^(iπ).", lang="en"))


class SinonimosTest(unittest.TestCase):
    """Synonyms go to THEIR sense. This class covers the TOP-LEVEL shape (D-117).

    It is the one the Spanish dump uses: `raw["synonyms"]` with a declared `sense_index`. The
    nested shape, the one English uses, lives in `SinonimosAnidadosTest`.

    The failure mode these tests exist to prevent: a synonym attributed to the wrong sense. It
    throws nothing, logs nothing, is not caught by `verify_pack.py` -- it comes out of the pack as
    correct content and a reader discovers it a year later.
    """

    def setUp(self):
        self.paths = []

    def tearDown(self):
        for path in self.paths:
            os.unlink(path)

    def test_los_sinonimos_van_a_su_acepcion(self):
        path = _jsonl(_raw("domingo", "noun", [
            _sense("hombre dominado por su pareja", sense_index="1"),
            _sense("paga semanal de un menor", sense_index="2"),
        ], synonyms=[
            {"word": "pollerudo", "sense_index": "1"},
            {"word": "mesada", "sense_index": "2"},
            {"word": "paga", "sense_index": "2"},
        ]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="es")))
        self.assertEqual(["pollerudo"], got.senses[0]["synonyms"])
        self.assertEqual(["mesada", "paga"], got.senses[1]["synonyms"])

    def test_un_sinonimo_de_una_acepcion_podada_no_se_cuelga_de_otra(self):
        """The change's most important test.

        `_senses()` discards the form-of sense BEFORE emitting, so the ordinals shift. An
        implementation by position (`enumerate`) hangs "corrido" off the sense that survives, and
        the pack comes out with a synonym that is not one.
        """
        path = _jsonl(_raw("corrido", "noun", [
            _sense("", sense_index="1", tags=["form-of"], form_of=[{"word": "correr"}]),
            _sense("romance popular mexicano", sense_index="2"),
        ], synonyms=[
            {"word": "NO-DEBE-APARECER", "sense_index": "1"},
            {"word": "balada", "sense_index": "2"},
        ]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="es")))
        self.assertEqual(1, len(got.senses))
        self.assertEqual(["balada"], got.senses[0]["synonyms"])

    def test_un_sinonimo_sin_sense_index_se_descarta(self):
        # Medido: 5 casos en todo el dump. Colgarlo de la primera acepcion seria inventar.
        path = _jsonl(_raw("casa", "noun", [_sense("edificio para habitar", sense_index="1")],
                           synonyms=[{"word": "vivienda"}]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="es")))
        self.assertEqual([], got.senses[0]["synonyms"])

    def test_el_tope_es_cuatro_por_acepcion(self):
        path = _jsonl(_raw("tonto", "adj", [_sense("de poco entendimiento", sense_index="1")],
                           synonyms=[{"word": "s%d" % i, "sense_index": "1"} for i in range(9)]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="es")))
        self.assertEqual(4, len(got.senses[0]["synonyms"]))

    def test_un_sinonimo_igual_al_lema_no_se_emite(self):
        # Same criterion as _forms(). In English it really happens: "cat" is listed as a synonym of
        # "cat".
        path = _jsonl(_raw("casa", "noun", [_sense("edificio para habitar", sense_index="1")],
                           synonyms=[{"word": "casa", "sense_index": "1"},
                                     {"word": "vivienda", "sense_index": "1"}]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="es")))
        self.assertEqual(["vivienda"], got.senses[0]["synonyms"])

    def test_la_forma_de_arriba_sin_sense_index_no_se_cuelga_de_nada(self):
        """What the language list protected, now without the list.

        There used to be a per-language gate --`IDIOMAS_CON_SINONIMOS = {"es"}`-- and this test
        asserted that English brought no synonyms at all. **That assertion was incorrect**: only
        the top-level shape had been measured. English serves 338,200 items nested inside each
        sense, which is where the attribution is structural (see `SinonimosAnidadosTest`).

        What does still hold is the rule, and it needs to know nothing about the dump's language:
        in the TOP-LEVEL shape, an item with no `sense_index` cannot be attributed to any sense and
        is discarded. Measured: 0 of the English dump's 43,679 top-level items carry one --they
        carry `_dis1`, a weight vector-- so they fall out on their own, with no gate.
        """
        path = _jsonl(_raw("cat", "noun", [_sense("a small feline", sense_index="1")],
                           synonyms=[{"word": "feline", "_dis1": "50 50"}]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="en")))
        self.assertEqual([], got.senses[0]["synonyms"])




class UltimaPalabraDelDumpTest(unittest.TestCase):
    """The file's last word receives the same options as every other.

    ⚠️ **The bug this pins already happened and no test caught it.** The records are grouped by
    `word` and the group is flushed on seeing a different one; the **last group** comes out through
    an `_emit` call **outside the loop**. While the options were threaded by hand, forgetting that
    second call made the dump's last word lose that datum **in silence** -- no error, no log, and
    with the whole pack passing `verify_pack.py`.

    No existing test could see it because none had two words where the second was the last.
    `Opciones` closed the door; this pins that it stays closed.
    """

    def setUp(self):
        self.paths = []

    def tearDown(self):
        for path in self.paths:
            os.unlink(path)

    def test_la_ultima_palabra_recibe_traducciones_igual_que_la_primera(self):
        path = _jsonl(
            _raw("casa", "noun", [_sense("edificacion", sense_index="1")],
                 translations=[{"word": "house", "code": "en", "sense_index": "1"}]),
            _raw("zumo", "noun", [_sense("liquido de una fruta", sense_index="1")],
                 translations=[{"word": "juice", "code": "en", "sense_index": "1"}]),
        )
        self.paths.append(path)
        got = {r.headword: r for r in kaikki.records(path, lang="es", translations_to="en")}
        self.assertEqual(["house"], got["casa"].senses[0]["translations"])
        self.assertEqual(["juice"], got["zumo"].senses[0]["translations"],
                         "la ULTIMA palabra del archivo perdio sus traducciones")

    def test_la_ultima_palabra_recibe_el_prior_de_frecuencia_igual(self):
        path = _jsonl(
            _raw("casa", "noun", [_sense("edificacion")]),
            _raw("zumo", "noun", [_sense("liquido de una fruta")]),
        )
        self.paths.append(path)
        got = {r.headword: r.rank
               for r in kaikki.records(path, lang="es", frequencies={"casa": 5.5, "zumo": 5.5})}
        self.assertEqual(got["casa"], got["zumo"],
                         "la ULTIMA palabra del archivo no uso la señal de frecuencia")
        self.assertLess(got["zumo"], kaikki.FRONTERA_CON_SENAL)


class RankPorFrecuenciaTest(unittest.TestCase):
    """The ordering prior comes from usage frequency, and richness stays as the fallback.

    ⚠️ **The defect it closes, measured**: `rank` correlated **-0.250** with the real frequency
    --where -1 would be expected-- because it counted inflected forms and a Spanish verb carries up
    to 222. On the rungs with no coverage band (D-142 only defends `PREFIX`) that showed raw:
    `house` returned `solar, alojar, albergar` and never `casa`.

    ⚠️ **Two disjoint bands and not one mixed scale.** Only **17.4 %** of the lemmas have a
    frequency signal; mixing richness and frequency into one number would require calibrating how
    much richness a Zipf point *is worth*, which is a decision nobody measured. With bands, whoever
    has a signal is ordered by it and whoever does not sits below **as a block**, keeping the usual
    richness order among peers.
    """

    def setUp(self):
        self.paths = []

    def tearDown(self):
        for path in self.paths:
            os.unlink(path)

    def _rank_de(self, palabra, frecuencias=None, formas=None):
        path = _jsonl(_raw(palabra, "noun", [_sense("una glosa cualquiera")],
                           forms=[{"form": f} for f in (formas or [])]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="es", frequencies=frecuencias)))
        return got.rank

    def test_mas_frecuente_rankea_mejor(self):
        comun = self._rank_de("casa", {"casa": 5.5})
        raro = self._rank_de("casa", {"casa": 2.0})
        self.assertLess(comun, raro, "mayor Zipf tiene que dar menor rank")

    def test_lo_que_tiene_señal_le_gana_a_CUALQUIER_cosa_sin_señal(self):
        """The two bands' central claim.

        ⚠️ Without this, an extremely rich entry with no signal --a verb with 80 forms-- would go
        on beating a common word, which is exactly the defect this comes to close.
        """
        apenas_comun = self._rank_de("casa", {"casa": 1.0})
        riquisima_sin_señal = self._rank_de("zurriagazo", {}, formas=["z%d" % i for i in range(80)])
        self.assertLess(apenas_comun, riquisima_sin_señal)

    def test_sin_señal_se_conserva_el_orden_de_riqueza_entre_pares(self):
        """Not appearing in 50,000 words of subtitles is evidence of rarity, but among rare words
        richness is still the best clue there is."""
        rica = self._rank_de("zzz", {}, formas=["a", "b", "c", "d"])
        pobre = self._rank_de("zzz", {})
        self.assertLess(rica, pobre)

    def test_sin_mapa_de_frecuencias_nada_cambia(self):
        """A pack built without the list has to come out as before: `oewn` and `wikidata` have
        their own formula and do not come through here."""
        self.assertEqual(self._rank_de("zzz", None), self._rank_de("zzz", {}))

    def test_el_castigo_de_nombre_propio_se_suma_ENCIMA(self):
        """⚠️ `verify_pack.py` requires `rank >= 1000` for proper nouns under the strict policy. If
        the frequency were applied after the penalty, `Madrid` --which is frequent-- would come in
        below that floor and the pack would fail verification."""
        path = _jsonl(_raw("Madrid", "name", [_sense("capital de España")]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="es", politica="included",
                                       frequencies={"madrid": 5.0})))
        self.assertGreaterEqual(got.rank, kaikki.CASTIGO_NOMBRE_PROPIO)

    def test_el_rank_nunca_es_negativo_con_una_frecuencia_enorme(self):
        self.assertGreaterEqual(self._rank_de("de", {"de": 99.0}), 0)


class TraduccionesTest(unittest.TestCase):
    """Translations go to THEIR sense, and what cannot be attributed is NOT hung off sense 1.

    The same shape as D-117's synonyms --`raw["translations"]` with a declared `sense_index`-- with
    two differences these tests pin:

    1. **They have to be filtered by language.** The dump carries the whole table: measured over
       the Spanish dump, `en` is 34,710 of 281,022 items; the rest is French, German, Italian,
       Dutch... Without the filter, a Spanish entry would show its Polish translation.
    2. **The index can be a range.** Measured: 53.6 % simple, 8.6 % compound (`1-2`, `1, 4`) and
       37.7 % with no index. The synonyms are 100 % simple, so expanding ranges does not touch
       them.

    The failure mode they exist to prevent is D-117's: a translation hung off the wrong sense reads
    perfectly plausible and `verify_pack.py` does not catch it.
    """

    def setUp(self):
        self.paths = []

    def tearDown(self):
        for path in self.paths:
            os.unlink(path)

    def test_las_traducciones_van_a_su_acepcion(self):
        path = _jsonl(_raw("vela", "noun", [
            _sense("cilindro de cera que da luz al arder", sense_index="1"),
            _sense("tela que impulsa una embarcacion", sense_index="2"),
        ], translations=[
            {"word": "candle", "code": "en", "sense_index": "1"},
            {"word": "sail", "code": "en", "sense_index": "2"},
        ]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="es", translations_to="en")))
        self.assertEqual(["candle"], got.senses[0]["translations"])
        self.assertEqual(["sail"], got.senses[1]["translations"])

    def test_un_rango_alcanza_las_dos_acepciones(self):
        path = _jsonl(_raw("amante", "noun", [
            _sense("persona que ama", sense_index="1"),
            _sense("companero sexual", sense_index="2"),
        ], translations=[{"word": "lover", "code": "en", "sense_index": "1-2"}]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="es", translations_to="en")))
        self.assertEqual(["lover"], got.senses[0]["translations"])
        self.assertEqual(["lover"], got.senses[1]["translations"])

    def test_una_traduccion_a_otro_idioma_no_entra(self):
        path = _jsonl(_raw("casa", "noun", [_sense("edificacion para vivir", sense_index="1")],
                           translations=[
                               {"word": "house", "code": "en", "sense_index": "1"},
                               {"word": "Haus", "code": "de", "sense_index": "1"},
                               {"word": "maison", "code": "fr", "sense_index": "1"},
                           ]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="es", translations_to="en")))
        self.assertEqual(["house"], got.senses[0]["translations"])

    def test_una_traduccion_sin_indice_no_se_cuelga_de_la_primera(self):
        """D-117's rule, and the reason the list mode exists.

        Hanging it off sense 1 gets it right sometimes and wrong other times **with no trace**. It
        is discarded here; the honest place for this datum is the entry-level channel, which does
        not yet exist (roadmap §Naming a sense from another pack).
        """
        path = _jsonl(_raw("banco", "noun", [
            _sense("asiento para varias personas", sense_index="1"),
            _sense("entidad financiera", sense_index="2"),
        ], translations=[{"word": "bank", "code": "en"}]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="es", translations_to="en")))
        self.assertEqual([], got.senses[0]["translations"])
        self.assertEqual([], got.senses[1]["translations"])

    def test_el_dump_ingles_traduce_por_el_canal_de_la_palabra(self):
        """⚠️ An earlier conclusion was too strong and this test corrects it.

        **Characterization**: it passes with no new code, because the `W` channel already resolved
        it. It is written all the same because what it pins --that English CAN translate--
        contradicts what this same session's changelog had left written, and without it the next
        session would believe the old number again.

        It had been measured that the English dump carries **0 `sense_index` of 9,987**
        translations into Spanish, and from there it was concluded that *"the English pack cannot
        have translations"*. That held only for the `T` channel, which demands attribution. With
        the `W` channel --which exists precisely for the unattributable-- those 9,987 do have
        somewhere to live, and they fill `trans` as well, which is what makes `perro` find `dog`.
        """
        path = _jsonl({
            "word": "dog", "pos": "noun", "lang_code": "en", "lang": "English",
            "pos_title": "Noun",
            "senses": [{"glosses": ["a domesticated carnivorous mammal"], "sense_index": "1"}],
            "translations": [
                {"word": "perro", "code": "es", "sense": "domesticated animal"},
                {"word": "Hund", "code": "de"},
            ],
        })
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="en", translations_to="es")))
        # With no index: it is not hung off the sense, it goes to the word channel.
        self.assertEqual([], got.senses[0]["translations"])
        self.assertEqual(("perro",), got.word_translations)
        # And it enters the search channel, which is what closes the reverse direction.
        self.assertEqual(("perro",), got.translations)

    def test_las_no_atribuidas_van_al_canal_de_la_palabra(self):
        """37.7 % of the data, which used to be thrown away for having nowhere to live."""
        path = _jsonl(_raw("banco", "noun", [
            _sense("asiento para varias personas", sense_index="1"),
            _sense("entidad financiera", sense_index="2"),
        ], translations=[
            {"word": "bench", "code": "en", "sense_index": "1"},
            {"word": "bank", "code": "en"},
        ]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="es", translations_to="en")))
        self.assertEqual(["bench"], got.senses[0]["translations"])
        self.assertEqual([], got.senses[1]["translations"])
        # ⚠️ NO se cuelga de la acepcion 1: vive en el canal de la palabra.
        self.assertEqual(("bank",), got.word_translations)

    def test_el_canal_de_busqueda_lleva_las_dos(self):
        """`translations` feeds the `trans` table, and for searching the attribution is irrelevant.

        This is what makes a MONOLINGUAL pack searchable in the other language: typing `bank` finds
        `banco` with no bilingual pack installed.
        """
        path = _jsonl(_raw("banco", "noun", [_sense("entidad financiera", sense_index="1")],
                           translations=[
                               {"word": "bank", "code": "en", "sense_index": "1"},
                               {"word": "bench", "code": "en"},
                           ]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="es", translations_to="en")))
        self.assertEqual(("bank", "bench"), got.translations)

    def test_lo_que_ya_salio_por_acepcion_no_se_repite_abajo(self):
        """Repeating it would say the word means that *as well*, and it is the same thing better attributed."""
        path = _jsonl(_raw("casa", "noun", [_sense("edificacion para vivir", sense_index="1")],
                           translations=[
                               {"word": "house", "code": "en", "sense_index": "1"},
                               {"word": "house", "code": "en"},
                           ]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="es", translations_to="en")))
        self.assertEqual(["house"], got.senses[0]["translations"])
        self.assertEqual((), got.word_translations)

    def test_sin_idioma_destino_no_se_emite_ninguna(self):
        """The English pack declares no target, and must not gain translations by accident."""
        path = _jsonl(_raw("casa", "noun", [_sense("edificacion", sense_index="1")],
                           translations=[{"word": "house", "code": "en", "sense_index": "1"}]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="es")))
        self.assertEqual([], got.senses[0]["translations"])


class SinonimosAnidadosTest(unittest.TestCase):
    """The OTHER shape in which the source serves synonyms, the only one English uses.

    Measured over 120,000 records of each dump, already without `pos = name`:

        | shape                         | Spanish | English |
        |-------------------------------|---------|---------|
        | top-level `synonyms`          |  16.5 % |   5.6 % |
        | `synonyms` inside `senses`    |   0.0 % |  25.8 % |
        | both at once                  |   0.0 % |   0.0 % |

    The two dumps use **one shape each and not the same one**, so there is no precedence to decide.
    And the nested one **needs no `sense_index`**: it comes inside the sense, which is exactly the
    attribution D-117 requires. Measured: 0 of 338,200 carry a `sense_index`, and none is needed.
    """

    def setUp(self):
        self.paths = []

    def tearDown(self):
        for path in self.paths:
            os.unlink(path)

    def test_el_ingles_saca_sus_sinonimos_de_la_forma_anidada(self):
        path = _jsonl(_raw("dictionary", "noun", [
            _sense("a reference work", sense_index="1",
                   synonyms=[{"word": "lexicon"}, {"word": "wordbook"}]),
            _sense("the vocabulary of a language", sense_index="2",
                   synonyms=[{"word": "vocabulary"}]),
        ]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="en")))
        self.assertEqual(["lexicon", "wordbook"], got.senses[0]["synonyms"])
        self.assertEqual(["vocabulary"], got.senses[1]["synonyms"])

    def test_la_forma_anidada_no_necesita_sense_index(self):
        # The fundamental difference from the Spanish shape: here the attribution is structural,
        # not declared. Requiring a `sense_index` would throw away the English dump's 338,200 items.
        path = _jsonl(_raw("free", "adj", [
            _sense("unconstrained", synonyms=[{"word": "unfettered"}]),
        ]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="en")))
        self.assertEqual(["unfettered"], got.senses[0]["synonyms"])

    def test_una_acepcion_form_of_se_lleva_sus_anidados(self):
        # Free, and it is the structural advantage over the Spanish shape: the pruned sense goes
        # WHOLE, so its synonyms cannot hang off another. There is no ordinal to shift.
        path = _jsonl(_raw("dogs", "noun", [
            _sense("", sense_index="1", tags=["form-of"], form_of=[{"word": "dog"}],
                   synonyms=[{"word": "NO-DEBE-APARECER"}]),
            _sense("plural of the animal", sense_index="2", synonyms=[{"word": "hounds"}]),
        ]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="en")))
        self.assertEqual(1, len(got.senses))
        self.assertEqual(["hounds"], got.senses[0]["synonyms"])

    def test_el_tope_de_cuatro_y_la_deduplicacion_valen_igual(self):
        path = _jsonl(_raw("big", "adj", [
            _sense("of great size", synonyms=(
                [{"word": "large"}, {"word": "large"}] + [{"word": "s%d" % i} for i in range(9)]
            )),
        ]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="en")))
        self.assertEqual(4, len(got.senses[0]["synonyms"]))
        self.assertEqual(["large", "s0", "s1", "s2"], got.senses[0]["synonyms"])

    def test_un_sinonimo_anidado_igual_al_lema_no_se_emite(self):
        # Measured: 1.9 % of the 338,200 English items. "cat" is listed as a synonym of "cat".
        path = _jsonl(_raw("cat", "noun", [
            _sense("a small feline", synonyms=[{"word": "cat"}, {"word": "feline"}]),
        ]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="en")))
        self.assertEqual(["feline"], got.senses[0]["synonyms"])

    def test_el_orden_del_dump_se_respeta(self):
        """No reordering by source, and that was measured before it was decided.

        74.5 % of the English items carry `source: "Thesaurus:*"` and appear first, so it looked as
        though the cap of 4 would keep the obscure ones. **Measured: it changes 91 senses of 4,872
        mixed ones (1.9 %)**, and in the sample the reordered result is WORSE --`craft` goes from
        `ability, aptitude` to `craftiness, foxiness`--. The hypothesis did not survive.
        """
        path = _jsonl(_raw("craft", "noun", [
            _sense("skill", synonyms=[
                {"word": "technique", "source": "Thesaurus:skill"},
                {"word": "ability"},
            ]),
        ]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="en")))
        self.assertEqual(["technique", "ability"], got.senses[0]["synonyms"])



class AntonimosTest(unittest.TestCase):
    """The antonyms, in the SAME two shapes as the synonyms (D-126).

    Measured over 120,000 live records of each dump, and the mirror is exact:

        | shape                          | Spanish | English |
        |--------------------------------|---------|---------|
        | top-level `antonyms` with index|   2.1 % |   0.0 % |
        | `antonyms` inside `senses`     |   0.0 % |   3.2 % |

    Far lower coverage than the synonyms --3.2 % against 25.8 % in English-- and so is the cost:
    0.80 B per live entry.

    **Misattributing an antonym is worse than misattributing a synonym**: a synonym under the wrong
    sense reads as an odd choice, an antonym reads as the opposite of something else.
    """

    def setUp(self):
        self.paths = []

    def tearDown(self):
        for path in self.paths:
            os.unlink(path)

    def test_la_forma_de_arriba_va_a_su_acepcion(self):
        path = _jsonl(_raw("caliente", "adj", [
            _sense("de temperatura alta", sense_index="1"),
            _sense("enojado", sense_index="2"),
        ], antonyms=[
            {"word": "frio", "sense_index": "1"},
            {"word": "calmado", "sense_index": "2"},
        ]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="es")))
        self.assertEqual(["frio"], got.senses[0]["antonyms"])
        self.assertEqual(["calmado"], got.senses[1]["antonyms"])

    def test_la_forma_anidada_va_a_su_acepcion(self):
        path = _jsonl(_raw("hot", "adj", [
            _sense("of high temperature", antonyms=[{"word": "cold"}]),
            _sense("spicy", antonyms=[{"word": "mild"}]),
        ]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="en")))
        self.assertEqual(["cold"], got.senses[0]["antonyms"])
        self.assertEqual(["mild"], got.senses[1]["antonyms"])

    def test_un_antonimo_de_arriba_sin_sense_index_se_descarta(self):
        # Same rule as the synonyms: hanging it off the first sense would be inventing the
        # attribution, and here inventing it means asserting an opposite the source did not assert.
        path = _jsonl(_raw("caliente", "adj", [_sense("de temperatura alta", sense_index="1")],
                           antonyms=[{"word": "frio"}]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="es")))
        self.assertEqual([], got.senses[0]["antonyms"])

    def test_un_antonimo_igual_al_lema_no_se_emite(self):
        path = _jsonl(_raw("fast", "adj", [
            _sense("quick", antonyms=[{"word": "fast"}, {"word": "slow"}]),
        ]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="en")))
        self.assertEqual(["slow"], got.senses[0]["antonyms"])

    def test_el_tope_de_cuatro_vale_igual(self):
        path = _jsonl(_raw("big", "adj", [
            _sense("large", antonyms=[{"word": "a%d" % i} for i in range(9)]),
        ]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="en")))
        self.assertEqual(4, len(got.senses[0]["antonyms"]))

    def test_sinonimos_y_antonimos_conviven_sin_mezclarse(self):
        path = _jsonl(_raw("hot", "adj", [
            _sense("of high temperature",
                   synonyms=[{"word": "warm"}], antonyms=[{"word": "cold"}]),
        ]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="en")))
        self.assertEqual(["warm"], got.senses[0]["synonyms"])
        self.assertEqual(["cold"], got.senses[0]["antonyms"])



class RelacionadasTest(unittest.TestCase):
    """Related words for the THIN entries, and only where there is nothing to invent.

    70.4 % of the Spanish pack is entries with **a single sense and no example**: 80,744. They are
    the ones that feel empty, and the source has something for them the builder was throwing away
    -- `hypernyms`, `hyponyms` and `related`.

    ⚠️ **They only get in if the entry has ONE sense, and that is the whole rule.** The source
    brings them at ENTRY level, not at sense level; hanging them off the first sense of an entry
    with several would be inventing the attribution, which is exactly the error D-117 exists to
    prevent. With a single sense there is nothing else for them to belong to.

    Measured over 174,395 live records: of the 29,817 thin ones, 2,142 gain something this way
    (7.2 %). The synonyms reach more --20.3 %-- but those already came in through D-117.
    """

    def setUp(self):
        self.paths = []

    def tearDown(self):
        for path in self.paths:
            os.unlink(path)

    def test_una_entrada_flaca_gana_sus_relacionadas(self):
        path = _jsonl(_raw("katakana", "noun", [_sense("silabario japones", sense_index="1")],
                           related=[{"word": "hiragana"}]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="es")))
        self.assertEqual(["hiragana"], got.senses[0]["related"])

    def test_hiperonimos_e_hiponimos_entran_por_el_mismo_lado(self):
        path = _jsonl(_raw("catalan", "noun", [_sense("lengua romance", sense_index="1")],
                           hypernyms=[{"word": "lengua"}], hyponyms=[{"word": "valenciano"}]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="es")))
        self.assertEqual(["lengua", "valenciano"], got.senses[0]["related"])

    def test_con_VARIAS_acepciones_no_entra_ninguna(self):
        """THE TEST THAT PAYS FOR THE RULE.

        The source brings them at entry level. With two senses it is unknown which they belong to,
        and hanging them off the first would be incorrect content that looks correct.
        """
        path = _jsonl(_raw("frances", "noun", [
            _sense("originario de Francia", sense_index="1"),
            _sense("idioma romance", sense_index="2"),
        ], related=[{"word": "galo"}]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="es")))
        self.assertEqual([[], []], [s["related"] for s in got.senses])

    def test_una_relacionada_igual_al_lema_no_se_emite(self):
        path = _jsonl(_raw("be", "noun", [_sense("nombre de la letra b", sense_index="1")],
                           related=[{"word": "be"}, {"word": "be alta"}]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="es")))
        self.assertEqual(["be alta"], got.senses[0]["related"])

    def test_el_tope_de_cuatro_vale_igual(self):
        path = _jsonl(_raw("x", "noun", [_sense("una glosa", sense_index="1")],
                           related=[{"word": "r%d" % i} for i in range(9)]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="es")))
        self.assertEqual(4, len(got.senses[0]["related"]))

    def test_no_duplica_lo_que_ya_es_sinonimo(self):
        """A synonym is already shown on its line; repeating it below spends a watch screen."""
        path = _jsonl(_raw("domingo", "noun", [_sense("marido dominado", sense_index="1")],
                           synonyms=[{"word": "pollerudo", "sense_index": "1"}],
                           related=[{"word": "pollerudo"}, {"word": "calzonazos"}]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="es")))
        self.assertEqual(["pollerudo"], got.senses[0]["synonyms"])
        self.assertEqual(["calzonazos"], got.senses[0]["related"])

    def test_las_ANIDADAS_entran_aunque_haya_varias_acepciones(self):
        """The ENGLISH dump's shape, and the reason the rule above does not reach them.

        Measured over 185,972 live records of the English dump: 13.8 % carry related words NESTED
        inside the sense against 9.6 % at entry level. The same asymmetry as the synonyms (D-124).
        Nested, the attribution is **structural** --the item already lives in its sense-- so
        requiring a single sense would throw away precisely the more frequent shape.
        """
        path = _jsonl(_raw("bank", "noun", [
            dict(_sense("financial institution", sense_index="1"),
                 hypernyms=[{"word": "institution"}]),
            dict(_sense("edge of a river", sense_index="2"),
                 related=[{"word": "riverbank"}]),
        ]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="es")))
        self.assertEqual(["institution"], got.senses[0]["related"])
        self.assertEqual(["riverbank"], got.senses[1]["related"])

    def test_con_una_acepcion_se_suman_las_anidadas_y_las_de_la_entrada(self):
        """A union, not a precedence: the same criterion `_senses` already applies to the synonyms."""
        path = _jsonl(_raw("guanaco", "noun", [
            dict(_sense("mamifero sudamericano", sense_index="1"),
                 related=[{"word": "chulengo"}]),
        ], hypernyms=[{"word": "camelido"}]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="es")))
        self.assertEqual(["chulengo", "camelido"], got.senses[0]["related"])


    def test_el_markup_del_wikcionario_no_es_una_palabra(self):
        """Measured: 1,072 of 267,721 items (0.40 %) are not words but internal references.

        Little in the total and **a lot where it matters**: in a sample of six thin entries from
        the English pack, `abbacy -> abbe, more at abbot § Related terms` came out, and in a thin
        entry that line is the only thing under the gloss. The three measured shapes:

            "abbot § Related terms"        145 items   a reference to a section
            "Appendix:Months", "mul:12"    927 items   a wiki namespace, or a code
            "more at ..."                    4 items   a phrase, not a lemma

        None can be displayed and none can be opened as an entry: `norm()` does not find them.
        """
        path = _jsonl(_raw("abbacy", "noun", [_sense("dignidad de un abad", sense_index="1")],
                           related=[{"word": "abbé"},
                                    {"word": "more at abbot § Related terms"},
                                    {"word": "Appendix:Months"},
                                    {"word": "mul:12"}]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="es")))
        self.assertEqual(["abbé"], got.senses[0]["related"])

class RankTest(unittest.TestCase):
    """rank es un PROXY: el Wikcionario no trae frecuencia de uso. Menor es mas comun."""

    def setUp(self):
        self.paths = []

    def tearDown(self):
        for path in self.paths:
            os.unlink(path)

    def records(self, *raw):
        path = _jsonl(*raw)
        self.paths.append(path)
        return {r.headword: r for r in kaikki.records(path)}

    def test_una_entrada_rica_rankea_mejor_que_una_pobre(self):
        got = self.records(
            _raw("hacer", "verb", [
                _sense("Producir algo.", examples=[{"text": "hizo una casa"}]),
                _sense("Fabricar.", examples=[{"text": "hacer pan"}]),
                _sense("Causar."),
            ], forms=[{"form": "hago"}, {"form": "hizo"}, {"form": "haremos"}]),
            _raw("zurriagazo", "noun", [_sense("Golpe dado con el zurriago.")]),
        )
        self.assertLess(got["hacer"].rank, got["zurriagazo"].rank)

    def test_el_rank_nunca_es_negativo(self):
        """La columna es INTEGER NOT NULL y el indice ordena por ella: un negativo la rompe."""
        got = self.records(_raw("zzz", "noun", [_sense("x")]))
        self.assertGreaterEqual(got["zzz"].rank, 0)


class CitaEnEspanolTest(unittest.TestCase):
    """Wiktionary serves the `ref` in ANOTHER shape, which is why the separator lives in the `Perfil`.

    | | English | Spanish |
    |---|---|---|
    | shape | `1897, Richard Marsh, The Beetle:` | `Miguel Nicolau. Iniciacion a la Teologia. Pagina 85. 1984.` |
    | order | year first | **author first, year last** |
    | separator | comma | **full stop** |
    | examples with a `ref` | 75.5 % | **68.4 %** |
    | complete `ref` | 119 B | **92 B** |

    ⚠️ **And it brings a problem English does not have: the INITIALS.** `J. R. R. Tolkien` is four
    fields if split naively on the full stop, and the first two would be `J` and `R`. That is why
    the profile declares **two** things: what to split on, and whether initials have to be fused.
    """

    def setUp(self):
        self.paths = []

    def tearDown(self):
        for path in self.paths:
            os.unlink(path)

    def _cita(self, ref):
        path = _jsonl(_raw("casa", "noun", [
            _sense("Edificio.", examples=[{"text": "la casa de la esquina", "ref": ref}]),
        ]))
        self.paths.append(path)
        got = list(kaikki.records(path, lang="es"))
        return got[0].senses[0]["examples"][0]

    def test_se_recorta_a_autor_y_obra(self):
        # The dump's real `ref`, measured.
        self.assertEqual(
            {"text": "la casa de la esquina", "ref": "Miguel Nicolau. Iniciación a la Teología"},
            self._cita("Miguel Nicolau. Iniciación a la Teología. Página 85. "
                       "Editorial: I.T. San Ildefonso. 1984. ISBN: 9788439818250."),
        )

    def test_las_INICIALES_no_cuentan_como_campo(self):
        # ⚠️ The case that forces the second rule. Without fusing, the first two fields of
        # `J. R. R. Tolkien. El Señor de los Anillos.` are `J` and `R`.
        self.assertEqual(
            "J. R. R. Tolkien. El Señor de los Anillos",
            self._cita("J. R. R. Tolkien. El Señor de los Anillos. Página 12. 1954.")["ref"],
        )

    def test_un_ref_que_empieza_con_puntuacion_no_la_arrastra(self):
        # Seen in the dump: there are `ref` values with an empty author field, starting with `. `.
        self.assertEqual(
            "Anónimo. Ordinación dada a la ciudad de Zaragoza",
            self._cita(". Anónimo. Ordinación dada a la ciudad de Zaragoza. Página 3. 1414.")["ref"],
        )

    def test_un_ejemplo_sin_ref_sigue_siendo_una_cadena_pelada(self):
        path = _jsonl(_raw("casa", "noun", [
            _sense("Edificio.", examples=[{"text": "la casa"}]),
        ]))
        self.paths.append(path)
        got = list(kaikki.records(path, lang="es"))
        self.assertEqual(["la casa"], got[0].senses[0]["examples"])

    def test_el_separador_INGLES_no_parte_una_cita_espanola(self):
        # The Spanish citation has no top-level commas: if the profile got the separator wrong, it
        # would come out whole instead of trimmed. That is the safe degradation, and this case pins
        # that the Spanish profile declares the full stop.
        cita = self._cita("Emilio Castelar. Discursos politicos y literarios. Página 372. 1861.")
        self.assertEqual("Emilio Castelar. Discursos politicos y literarios", cita["ref"])


class SubindicesDeReferenciaTest(unittest.TestCase):
    """The cross-reference subscripts are stripped from the gloss **only in Spanish**.

    ⚠️ **The measurement that defines the scope, and without it the "cheap" fix breaks content.**
    The roadmap described it as *"a `str.translate` in `_gloss()`"*, which is shared by both
    languages. Measured over today's packs:

    | | with a subscript | what they are |
    |---|---|---|
    | Spanish | ~1,890 | **cross-references**: `mudanza₁`, `ejercito₂`, `abdicar₁` (62 of 69) |
    | English | ~4,584 | **chemical formulas**: `C₇H₅NO₃S`, `FeO₂²⁻`, `MnO₂` (23 of 26, and the other 3 too) |

    Applying it to both turns saccharin into something that is not a formula, in a place nobody
    looks. It is D-121's same lesson, where a wider pattern would have destroyed the English
    mathematical superscript.

    ⚠️ **And it loses information even in Spanish**: the subscript says **which sense** of the
    referred word. That is accepted knowingly -- on a watch, `ejercito₂` reads as an encoding
    error, and the exact sense is not recoverable from the card anyway.
    """

    def setUp(self):
        self.paths = []

    def tearDown(self):
        for path in self.paths:
            os.unlink(path)

    def _glosa(self, texto, lang="es"):
        path = _jsonl(_raw("x", "noun", [_sense(texto)]))
        self.paths.append(path)
        return list(kaikki.records(path, lang=lang))[0].senses[0]["gloss"]

    def test_el_subindice_se_saca_de_la_glosa_espanola(self):
        self.assertEqual("En particular, ejército terrestre.",
                         self._glosa("En particular, ejército₂ terrestre."))

    def test_el_INGLES_conserva_sus_formulas(self):
        # ⚠️ The counter-case, and it is the half that defines the scope.
        formula = "A white powder, C₇H₅NO₃S, used as a sweetener."
        self.assertEqual(formula, self._glosa(formula, lang="en"))

    def test_una_glosa_sin_subindices_no_se_toca(self):
        self.assertEqual("Edificio para habitar.", self._glosa("Edificio para habitar."))


class LavadoDeFrecuenciaTest(unittest.TestCase):
    """A capitalized word does not collect its lowercase homograph's frequency.

    ⚠️ **The problem, measured over `en-def-wikt.db`:** 6,462 entries with a capital and
    `pos != name` were in the real frequency band `[0,500)`, which has 55,903 -- **11.6 %** of the
    "most frequent" band. The cause is that `frequency.key()` lowercases --correctly, D-186: in
    Spanish the accent distinguishes words-- and the OpenSubtitles list **already comes entirely in
    lowercase**, so `TO` collects `to`'s occurrences.

    ⚠️ **ONLY the class that has a rule gets fixed, and that was the decision.** The 4,246 with a
    lowercase homograph are initialisms and honorific forms --`TO`, `OF`, `IS`, `WE`, `ME`, `HE`,
    `NO`, `ARE`, `BE`, `CAN`-- and there the rule is unambiguous: **the frequency belongs to the
    lowercase lemma, which already has its own entry**. The other two classes --1,333 with a
    `pos=name` sibling and 883 with neither signal-- mix `Thomas` in with `Christmas`, and there is
    no datum that separates them: D-137's trick was measured and **does not transfer to English**.
    """

    def setUp(self):
        self.paths = []

    def tearDown(self):
        for path in self.paths:
            os.unlink(path)

    def records(self, *raw, **kw):
        path = _jsonl(*raw)
        self.paths.append(path)
        return {r.headword: r for r in kaikki.records(path, lang="en", **kw)}

    def test_la_sigla_no_cobra_la_frecuencia_de_la_palabra(self):
        got = self.records(
            _raw("to", "prep", [_sense("Indicating direction.")], lang_code="en"),
            _raw("TO", "noun", [_sense("Initialism of time-out.")], lang_code="en"),
            frequencies={"to": 6.0},
        )
        self.assertLess(got["to"].rank, kaikki.FRONTERA_CON_SENAL,
                        "la palabra en minuscula SI tiene senal de frecuencia")
        self.assertGreaterEqual(
            got["TO"].rank, kaikki.FRONTERA_CON_SENAL,
            "la sigla cobro la frecuencia de `to`: sale en la banda de los mas frecuentes")

    def test_sin_homografo_en_minuscula_la_frecuencia_SI_se_cobra(self):
        # ⚠️ The counter-case, and it is the half that defines the scope. `Christmas` and
        # `American` are frequent English vocabulary and have no lowercase homograph: they have to
        # keep their frequency. Denying it to them for carrying a capital would be the false
        # positive that got the wider rule discarded.
        got = self.records(
            _raw("Christmas", "noun", [_sense("The feast.")], lang_code="en"),
            frequencies={"christmas": 5.0},
        )
        self.assertLess(got["Christmas"].rank, kaikki.FRONTERA_CON_SENAL)

    def test_el_homografo_tiene_que_ser_una_ENTRADA_y_no_una_pagina_de_forma(self):
        # A `form-of` page is not an entry of the pack: it is inverted as a form of its lemma
        # (D-065). If it counted, any capitalized word whose lowercase is an inflection would lose
        # its frequency with no entry existing to claim it.
        got = self.records(
            _raw("ran", "verb", [
                _sense("simple past of run", tags=["form-of"], form_of=[{"word": "run"}]),
            ], lang_code="en"),
            _raw("RAN", "noun", [_sense("Initialism of regional area network.")], lang_code="en"),
            frequencies={"ran": 5.0},
        )
        self.assertLess(got["RAN"].rank, kaikki.FRONTERA_CON_SENAL)

    def test_una_minuscula_nunca_pierde_su_propia_frecuencia(self):
        got = self.records(
            _raw("run", "verb", [_sense("To move quickly.")], lang_code="en"),
            frequencies={"run": 5.5},
        )
        self.assertLess(got["run"].rank, kaikki.FRONTERA_CON_SENAL)


class CitaDelEjemploTest(unittest.TestCase):
    """Where the quoted example came from, trimmed to what fits on a watch.

    ⚠️ **Why this is worth reading before changing.** Measured over the English dump, **86,5 %**
    of the examples are `type: quotation` -- lines lifted from a published text -- and the pack
    was keeping only `text`. The result on screen is a sentence out of an 1897 novel with nothing
    saying so, which is what sent a real user to Wiktionary by hand to find out.

    The `ref` the source gives averages **119 bytes** and carries publisher, city, OCLC and page.
    Trimmed to its first two top-level fields it averages **31** -- p50 24, p90 50 -- which is
    +1,6 % of the English pack instead of +6,3 %.
    """

    def setUp(self):
        self.paths = []

    def tearDown(self):
        for path in self.paths:
            os.unlink(path)

    def english(self, *raw):
        path = _jsonl(*raw)
        self.paths.append(path)
        return list(kaikki.records(path, lang="en"))

    def _example(self, ref):
        got = self.english(_raw("thomas", "noun", [
            _sense("An infidel or doubter.", examples=[{"text": "prove them Thomases", "ref": ref}]),
        ]))
        return got[0].senses[0]["examples"][0]

    def test_la_cita_se_recorta_a_ano_y_autor(self):
        # The real `ref` of the entry that set all this off.
        self.assertEqual(
            {"text": "prove them Thomases", "ref": "1897, Richard Marsh"},
            self._example("1897, Richard Marsh, The Beetle:"),
        )

    def test_una_coma_dentro_de_un_titulo_entrecomillado_no_corta(self):
        # ⚠️ The case that killed the naive rule: splitting naively on the comma left
        # "2019 June 6, “A gaggle" -- a headline cut in half that reads as a data error. Measured,
        # it happens to 320 citations (1.1 %) and fixing it costs 1 byte on average.
        ref = ("2019 June 6, “A gaggle, a confusion and a conspiracy - bizarre animal "
               "collective group names”, in BBC:")
        self.assertEqual(
            "2019 June 6, “A gaggle, a confusion and a conspiracy - bizarre animal "
            "collective group names”",
            self._example(ref)["ref"],
        )

    def test_los_parentesis_tampoco_se_parten(self):
        ref = "1611, The Holy Bible, […] (King James Version), London: […] Robert Barker:"
        self.assertEqual("1611, The Holy Bible", self._example(ref)["ref"])

    def test_los_corchetes_tampoco_se_parten(self):
        # ⚠️ **This case was found by READING the built pack, not by a test.** `captive`'s real
        # `ref` came out as `1850, [Alfred` -- an opened bracket that never closes, which reads as
        # broken data. Wiktionary uses brackets for the author's editorial name (`[Alfred, Lord
        # Tennyson]`, `[William Tyndale, transl.]`, `[i.e., Ben Jonson]`) and that comma is
        # internal. Measured over the dump: **369 citations (1.3 %)**, and ALL of them came out
        # with the bracket unbalanced. Closing it costs 1 byte on average (31 -> 32).
        ref = "1850, [Alfred, Lord Tennyson], In Memoriam A. H. H., London: Edward Moxon:"
        self.assertEqual("1850, [Alfred, Lord Tennyson]", self._example(ref)["ref"])

    def test_un_ref_envuelto_entero_en_corchetes_se_desenvuelve(self):
        # The other shape Wiktionary uses: enclosing the WHOLE citation in brackets when the source
        # is indirect. The bracket does not close within the first two fields, so the depth never
        # returns to zero and **nothing is cut**: the complete `ref` came out, with the bracket
        # open. Measured: 184 citations (0.64 %).
        ref = "[1755 April 15, Samuel Johnson, “Lexico′grapher”, in A Dictionary of the English Language:"
        self.assertEqual("1755 April 15, Samuel Johnson", self._example(ref)["ref"])

    def test_el_desenvoltorio_NO_se_aplica_cuando_el_par_si_cierra(self):
        # ⚠️ **The naive version of the rule above --always removing the leading delimiter--
        # BREAKS these two**, and that was seen by measuring: `[1877], Anna Sewell` became `1877],
        # Anna Sewell`. That is why it only unwraps if the cut came out unbalanced.
        self.assertEqual("[1877], Anna Sewell",
                         self._example("[1877], Anna Sewell, “A Strike for Liberty”:")["ref"])
        self.assertEqual("(Can we date this quote?), Sir T. Browne",
                         self._example("(Can we date this quote?), Sir T. Browne, (Please provide):")["ref"])

    def test_ninguna_cita_sale_con_un_par_sin_cerrar(self):
        """The property, not the case: what reads as broken is the unbalanced pair.

        Measured over the whole dump with this rule: **0 of 28,744**.
        """
        for ref in (
            "1850, [Alfred, Lord Tennyson], In Memoriam:",
            "1526, [William Tyndale, transl.], The Newe Testament:",
            "1600 (first performance), Beniamin Ionson [i.e., Ben Jonson], “Cynthias Reuels”:",
            "[1898], J[ohn] Meade Falkner, Moonfleet:",
            "[2018, David Correia, Tyler Wall, Police: A Field Guide, page 263:",
            "[1827, [Richard Cook], “RUMFUSTIAN”, in Oxford Night Caps:",
        ):
            cita = self._example(ref)["ref"]
            for abre, cierra in (("[", "]"), ("(", ")"), ("“", "”")):
                self.assertEqual(cita.count(abre), cita.count(cierra),
                                 "par %s%s desbalanceado en %r" % (abre, cierra, cita))

    def test_los_dos_puntos_del_final_se_caen(self):
        self.assertEqual("2009, Linda D. Wilson", self._example("2009, Linda D. Wilson, “Thomas”:")["ref"])

    def test_un_ref_de_un_solo_campo_entra_entero(self):
        self.assertEqual("BBC News", self._example("BBC News:")["ref"])

    def test_un_ejemplo_sin_ref_queda_como_cadena_pelada(self):
        # The canonical shape: 24.5 % of the dump's examples declare no source, and returning a
        # dict with `ref: None` would force every consumer to distinguish two shapes of the same
        # case. See `payload._example_parts`.
        got = self.english(_raw("dog", "noun", [
            _sense("A mammal.", examples=[{"text": "the dog barks"}]),
        ]))
        self.assertEqual(["the dog barks"], got[0].senses[0]["examples"])

    def test_un_ref_vacio_es_lo_mismo_que_ninguno(self):
        got = self.english(_raw("dog", "noun", [
            _sense("A mammal.", examples=[{"text": "the dog barks", "ref": "   "}]),
        ]))
        self.assertEqual(["the dog barks"], got[0].senses[0]["examples"])

    def test_cada_idioma_recorta_con_SU_separador(self):
        """⚠️ What this case protects is that the separator NOT be global.

        The two dumps serve the `ref` in different shapes --English puts the year first and splits
        on the comma; Wiktionary puts the author first and splits on the full stop-- so a shared
        separator produces garbage in one of the two. It lives in the `Perfil`, next to the rank
        weights and for the same reason as them (D-076).

        Spanish came in after English, and this case is the one that pins that both coexist.
        """
        path = _jsonl(_raw("casa", "noun", [
            _sense("Edificio.", examples=[{"text": "la casa",
                                           "ref": "Miguel Nicolau. Obra citada. 1984."}]),
        ]))
        self.paths.append(path)
        got = list(kaikki.records(path, lang="es"))
        self.assertEqual(
            [{"text": "la casa", "ref": "Miguel Nicolau. Obra citada"}],
            got[0].senses[0]["examples"],
        )


if __name__ == "__main__":
    unittest.main()


class PartesPrincipalesTest(unittest.TestCase):
    """Which forms reach the CARD. Different from `forms`, which feeds the search."""

    def _forms(self, *items):
        return kaikki._display_forms({"forms": list(items)}, "correr")

    def test_elige_la_simple_y_descarta_la_compuesta(self):
        # ⚠️ **The case a probe discovered uncovered.** The source tags `corriendo` and `habiendo
        # corrido` EXACTLY alike --both carry `['impersonal', 'gerund']`-- so no tag separates
        # them: the only thing that distinguishes them is the space. With the compound one first in
        # the list, a selector without that filter takes it.
        self.assertEqual(
            (("ger", "corriendo"),),
            self._forms({"form": "habiendo corrido", "tags": ["impersonal", "gerund"]},
                        {"form": "corriendo", "tags": ["impersonal", "gerund"]}),
        )

    def test_impersonal_NO_descalifica(self):
        # ⚠️ Believing it left `correr` with no principal part at all in the first version: Spanish
        # marks ALL its non-finite forms that way.
        self.assertEqual(
            (("part", "corrido"),),
            self._forms({"form": "corrido", "tags": ["impersonal", "participle"]}),
        )

    def test_el_plural_no_se_lleva_una_forma_verbal(self):
        # `corremos` es `['first-person', 'plural', ...]`: plural, y no el plural de un lema.
        self.assertEqual(
            (("pl", "casas"),),
            self._forms({"form": "corremos", "tags": ["first-person", "plural", "present"]},
                        {"form": "casas", "tags": ["plural"]}),
        )

    def test_el_femenino_singular_no_se_lleva_el_femenino_plural(self):
        self.assertEqual(
            (("fem", "alta"),),
            self._forms({"form": "altas", "tags": ["feminine", "plural"]},
                        {"form": "alta", "tags": ["feminine"]}),
        )

    def test_el_lema_no_es_una_forma_suya(self):
        self.assertEqual((), self._forms({"form": "correr", "tags": ["gerund"]}))

    def test_sin_etiquetas_no_se_adivina_nada(self):
        # A source with no `tags` gives no way to say what each form is, and an invented label is
        # worse than no form at all.
        self.assertEqual((), self._forms({"form": "corriendo"}))

    def test_un_femenino_plural_no_es_ninguna_de_las_dos(self):
        # `altas` is plural AND feminine, so it is neither the plain plural (`altos`) nor the
        # feminine singular (`alta`): both rows forbid it and neither comes out. Showing it under
        # either key would be a wrong label, which is worse than none.
        self.assertEqual((), self._forms({"form": "altas", "tags": ["plural", "feminine"]}))

    def test_una_forma_no_se_repite_bajo_dos_claves(self):
        # `corriendo` qualifies as a gerund and nothing else; if a form qualified twice, the card
        # would show it twice with different labels.
        salida = self._forms({"form": "corriendo", "tags": ["gerund"]},
                             {"form": "corrido", "tags": ["participle"]})
        self.assertEqual(len({f for _, f in salida}), len(salida))


class PronunciacionTest(unittest.TestCase):
    """Reading `sounds[].ipa` out of the source. See `kaikki._pronunciation`."""

    def test_saca_los_corchetes_de_la_transcripcion_fonetica(self):
        # 854,071 of the Spanish dump's 854,460 lines come wrapped like this.
        self.assertEqual("xapo\u02c8nes", kaikki._pronunciation({"sounds": [{"ipa": "[xapo\u02c8nes]"}]}))

    def test_saca_las_barras_de_la_transcripcion_fonemica(self):
        # Three lines of the Spanish dump and most of the English one.
        self.assertEqual("\u02c8kasa", kaikki._pronunciation({"sounds": [{"ipa": "/\u02c8kasa/"}]}))

    def test_salta_los_sonidos_que_no_traen_ipa(self):
        # ⚠️ The case that makes `sounds[0]` wrong: the list mixes transcriptions with
        # `acentuación`, `longitud silábica` and audio files, and only some carry the key.
        raw = {"sounds": [{"raw_tags": ["acentuaci\u00f3n"], "other": "aguda"},
                          {"ipa": "[\u02c8kasa]"}]}
        self.assertEqual("\u02c8kasa", kaikki._pronunciation(raw))

    def test_un_delimitador_desparejo_se_deja_entero(self):
        # ⚠️ **Measured: 10 lines of the dump are wrong this way.** Stripping one side would leave
        # a stray `)` that reads like part of the transcription; left whole it is visibly the
        # source's problem, which is the only honest option when the source is malformed.
        self.assertEqual("[\u02c8xa.po)", kaikki._pronunciation({"sounds": [{"ipa": "[\u02c8xa.po)"}]}))

    def test_sin_sonidos_no_hay_pronunciacion(self):
        self.assertIsNone(kaikki._pronunciation({}))
        self.assertIsNone(kaikki._pronunciation({"sounds": []}))
        self.assertIsNone(kaikki._pronunciation({"sounds": [{"ipa": "   "}]}))

    def test_unos_delimitadores_solos_no_dejan_una_cadena_vacia(self):
        # `[]` would strip to "" and an empty value must be None, not a blank row on the card.
        self.assertIsNone(kaikki._pronunciation({"sounds": [{"ipa": "[]"}]}))
