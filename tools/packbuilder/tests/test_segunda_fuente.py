"""Using two sources forces naming both, and that cannot depend on somebody remembering.

The attribution is not a README formality: it is **the condition of the licence** the pack is
distributed under. Both sources are CC BY-SA 4.0, so there is no incompatibility -- there is an
obligation, and the failure mode is silent: the pack comes out whole, opens, works, and is badly
licensed. No other check would see it.
"""

import json
import os
import sqlite3
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import build_pack  # noqa: E402


def _dump_kaikki(path):
    """A minimal Spanish Wiktionary dump: one thin entry and one with two senses."""
    filas = [
        {"word": "acomodador", "pos": "noun", "lang_code": "es", "lang": "Español",
         "pos_title": "Sustantivo",
         "senses": [{"glosses": ["Persona que acomoda a los espectadores."],
                     "sense_index": "1"}]},
        {"word": "banco", "pos": "noun", "lang_code": "es", "lang": "Español",
         "pos_title": "Sustantivo",
         "senses": [{"glosses": ["Asiento largo."], "sense_index": "1"},
                    {"glosses": ["Entidad financiera."], "sense_index": "2"}]},
    ]
    with open(path, "w", encoding="utf-8") as h:
        for fila in filas:
            h.write(json.dumps(fila, ensure_ascii=False) + "\n")


def _dump_ejemplos(path):
    filas = [
        {"word": "acomodador", "pos": "noun", "lang_code": "es",
         "senses": [{"glosses": ["usher"], "examples": [
             {"text": "El acomodador nos tendía los abrigos.", "type": "example",
              "english": "The usher handed us our coats."}]}]},
        {"word": "banco", "pos": "noun", "lang_code": "es",
         "senses": [{"glosses": ["bench"], "examples": [
             {"text": "Me senté en el banco.", "type": "example",
              "english": "I sat on the bench."}]}]},
    ]
    with open(path, "w", encoding="utf-8") as h:
        for fila in filas:
            h.write(json.dumps(fila, ensure_ascii=False) + "\n")


def _dump_wikidata(path):
    """A minimal lexemes dump: one Wiktionary already has and one it does not."""
    filas = [
        {"id": "L1", "language": "Q1321", "lexicalCategory": "Q1084",
         "lemmas": {"es": {"language": "es", "value": "banco"}},
         "senses": [{"id": "L1-S1", "glosses": {"es": {"language": "es",
                                                       "value": "mueble para sentarse"}}}],
         "forms": []},
        {"id": "L2", "language": "Q1321", "lexicalCategory": "Q34698",
         "lemmas": {"es": {"language": "es", "value": "iquiteño"}},
         "senses": [{"id": "L2-S1", "glosses": {"es": {"language": "es",
                                                       "value": "perteneciente a Iquitos"}}}],
         "forms": []},
    ]
    with open(path, "w", encoding="utf-8") as h:
        h.write("[\n")
        for fila in filas:
            h.write(json.dumps(fila, ensure_ascii=False) + ",\n")
        h.write("]\n")
    return path


class SumarVocabularioTest(unittest.TestCase):
    """A second source contributing NEW LEMMAS is merged into the pack (D-146).

    ⚠️ **It is a union of ROWS, not of fields, which is why it needs no composition.** Wikidata
    contributes 6,092 lemmas Wiktionary does not have --regional demonyms, set phrases--; the 8,595
    they share keep Wiktionary's definition, so there is nothing to arbitrate. That is what makes
    merging cheap and separating expensive.

    What merging avoids: two packs of the same language produced **two "ES" chips and two words of
    the day** on the home, because the screen lists packs and since D-136 the selector chooses a
    language.
    """

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.kaikki = os.path.join(self.dir, "es.jsonl")
        self.wd = os.path.join(self.dir, "wd.json")
        self.salida = os.path.join(self.dir, "pack.db")
        _dump_kaikki(self.kaikki)
        _dump_wikidata(self.wd)

    def tearDown(self):
        import shutil
        shutil.rmtree(self.dir, ignore_errors=True)

    def _construir(self, *extra):
        import contextlib
        import io as _io
        argv = ["build_pack.py", "es", self.kaikki, self.salida] + list(extra)
        with contextlib.redirect_stdout(_io.StringIO()):
            build_pack.main(argv)
        db = sqlite3.connect(self.salida)
        filas = list(db.execute("SELECT headword, pos FROM entry ORDER BY headword"))
        meta = dict(db.execute("SELECT key, value FROM meta").fetchall())
        db.close()
        return filas, meta

    def test_sin_sumar_solo_esta_la_fuente_base(self):
        filas, _ = self._construir()
        self.assertEqual([("acomodador", "noun"), ("banco", "noun")], filas)

    def test_UN_LEMA_NUEVO_ENTRA(self):
        filas, _ = self._construir("--sumar", "es-wd", self.wd)
        self.assertIn(("iquiteño", "adj"), filas)

    def test_UN_LEMA_QUE_YA_ESTA_NO_SE_DUPLICA(self):
        """THE TEST THAT PAYS FOR THIS FILE.

        "banco" exists in both sources. Letting both in would give **two rows for the same word**
        in the results list, and also two entries with the same `uid`, which
        `_reject_uid_collisions` rejects when closing the pack.
        """
        filas, _ = self._construir("--sumar", "es-wd", self.wd)
        self.assertEqual(1, [f[0] for f in filas].count("banco"))

    def test_la_definicion_que_gana_es_la_de_la_fuente_BASE(self):
        # There is no arbitration: whoever arrives first stays. That way the merge does not have to
        # decide which definition is better, which is what would make it expensive.
        import payload
        self._construir("--sumar", "es-wd", self.wd)
        db = sqlite3.connect(self.salida)
        dic = bytes.fromhex(
            db.execute("SELECT value FROM meta WHERE key='payload_dict'").fetchone()[0])
        blob = db.execute("SELECT payload FROM entry WHERE headword='banco'").fetchone()[0]
        db.close()
        _pos, ss, _palabra = payload.parse(payload.decompress(blob, dic))
        self.assertIn("Asiento", ss[0]["gloss"], "gano la glosa del Wikcionario")

    def test_la_fuente_sumada_se_declara_en_el_manifiesto(self):
        _filas, meta = self._construir("--sumar", "es-wd", self.wd)
        self.assertIn("Wikidata", meta["sources"])
        self.assertIn("CC0", meta["sources"])

    def test_EL_SENSE_KEY_SE_RECALCULA_SOBRE_EL_PACK_FUSIONADO(self):
        """THE TEST THIS MERGE MADE NECESSARY, and `verify_pack.py` caught it first.

        Each source decides whether an entry needs a `sense_key` by looking at ITS homographs. On
        merging, a lexeme that had a twin in Wikidata can lose it --because the twin was already in
        Wiktionary and got discarded-- and is left with a key that no longer corresponds.

        ⚠️ That breaks `uid`, which is the logical identity and **the join key across packs**
        (D-055): a `uid` computed with a `sense_key` does not match the same lemma in another pack
        that computed it without one. It is the same error D-139 documents, now through another
        door.

        The rule that holds is the FINAL pack's: a key goes to whoever has a homograph **there**.
        """
        filas, _ = self._construir("--sumar", "es-wd", self.wd)
        db = sqlite3.connect(self.salida)
        import build
        for hw, pos, uid in db.execute("SELECT headword, pos, uid FROM entry"):
            cuantos = [f for f in filas if f == (hw, pos)]
            if len(cuantos) == 1:
                self.assertEqual(
                    build.stable_uid("es", hw, pos, None), uid,
                    "%r no tiene homografo en el pack, asi que su uid no puede llevar clave" % hw,
                )
        db.close()

    def test_la_fuente_sumada_se_declara_en_SOURCES_y_no_en_el_pack_id(self):
        """⚠️ It used to require the `pack_id` to end in `-wd`. See D-215: the identity is language
        + tier, and where the content comes from is answered in `sources`, which is where you look."""
        _filas, meta = self._construir("--sumar", "es-wd", self.wd)
        self.assertEqual("es-full", meta["pack_id"])
        self.assertIn("wikidata", meta["sources"].lower(), meta["sources"])


class SegundaFuenteTest(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.kaikki = os.path.join(self.dir, "es.jsonl")
        self.enwikt = os.path.join(self.dir, "es-en.jsonl")
        self.salida = os.path.join(self.dir, "pack.db")
        _dump_kaikki(self.kaikki)
        _dump_ejemplos(self.enwikt)

    def tearDown(self):
        import shutil
        shutil.rmtree(self.dir, ignore_errors=True)

    def _construir(self, con_ejemplos):
        argv = ["build_pack.py", "es", self.kaikki, self.salida]
        if con_ejemplos:
            argv += ["--ejemplos", self.enwikt]
        import contextlib
        import io as _io
        with contextlib.redirect_stdout(_io.StringIO()):
            build_pack.main(argv)
        db = sqlite3.connect(self.salida)
        meta = dict(db.execute("SELECT key, value FROM meta").fetchall())
        db.close()
        return meta

    def test_sin_la_segunda_fuente_la_atribucion_nombra_UNA(self):
        meta = self._construir(con_ejemplos=False)
        self.assertIn("es.wiktionary.org", meta["attribution"])
        self.assertNotIn("en.wiktionary.org", meta["attribution"])

    def test_con_la_segunda_fuente_la_atribucion_nombra_las_DOS(self):
        """THE TEST THAT PAYS FOR THIS FILE.

        If the merge happens and the credit does not move, the pack comes out well built and
        **badly licensed**, and there is no way to notice by looking at the content.
        """
        meta = self._construir(con_ejemplos=True)
        self.assertIn("es.wiktionary.org", meta["attribution"])
        self.assertIn("en.wiktionary.org", meta["attribution"])
        self.assertEqual("CC-BY-SA-4.0", meta["license"],
                         "las dos fuentes son CC BY-SA 4.0: la licencia del pack no cambia")

    def test_el_manifiesto_declara_CADA_fuente_con_SU_licencia(self):
        """`meta.sources`: the structured manifest (D-138).

        `attribution`'s prose serves to be read; this serves to **show it per source**. A pack can
        mix content under different licences --the Spanish one with `--frases` joins CC BY-SA 4.0
        and CC BY 2.0 FR-- and a single name for the whole pack either over-claims or
        under-credits.
        """
        meta = self._construir(con_ejemplos=True)
        filas = [linea.split("\t") for linea in meta["sources"].strip().split("\n")]
        self.assertEqual(2, len(filas), "tienen que estar las DOS fuentes")
        self.assertEqual(["definitions", "examples"], [f[0] for f in filas])
        for fila in filas:
            self.assertEqual(5, len(fila), "cinco campos por fuente: %r" % (fila,))
            self.assertTrue(fila[1], "una fuente sin nombre no acredita nada")
            self.assertTrue(fila[3], "una fuente sin licencia no declara como se puede usar")

    def test_sin_opciones_el_manifiesto_trae_UNA_fuente(self):
        meta = self._construir(con_ejemplos=False)
        self.assertEqual(1, len(meta["sources"].strip().split("\n")))
        self.assertIn("definitions", meta["sources"])

    def test_el_pack_id_NO_cambia_al_anadir_una_fuente(self):
        """⚠️ **This asserts the opposite of what it asserted until D-215**, on purpose.

        The `pack_id` used to accumulate a suffix per source, *"so both can coexist"*: two Spanish
        builds with different sources were two packs installable at once. Under *"packs are by
        language and in versions"* that is exactly what is not wanted -- they are **the same
        dictionary**, and what distinguishes them is `data_version`, not the identity.

        What is lost: two variants of the same language no longer coexist. What is gained: adding a
        source stops making the pack look like another one the app has never seen.
        """
        sin_ = self._construir(con_ejemplos=False)
        con = self._construir(con_ejemplos=True)
        self.assertEqual(sin_["pack_id"], con["pack_id"])
        self.assertEqual("es-full", con["pack_id"])
        # And the underlying protection still stands by another route: the two builds are
        # distinguishable.
        self.assertNotEqual(
            sin_["sources"], con["sources"],
            "la diferencia real entre los dos sigue declarada, en sources",
        )

    def test_la_entrada_de_DOS_acepciones_no_recibe_el_ejemplo(self):
        """"banco" has an example in the second source, but two senses in ours.

        Gluing it to the first would be inventing the attribution. It is D-132's same rule, applied
        on the other side of the merge.
        """
        import payload
        self._construir(con_ejemplos=True)
        db = sqlite3.connect(self.salida)
        dic = bytes.fromhex(
            db.execute("SELECT value FROM meta WHERE key='payload_dict'").fetchone()[0])
        por_lema = {}
        for hw, blob in db.execute("SELECT headword, payload FROM entry"):
            _pos, ss, _palabra = payload.parse(payload.decompress(blob, dic))
            por_lema[hw] = ss
        db.close()
        self.assertEqual(["El acomodador nos tendía los abrigos."],
                         por_lema["acomodador"][0]["examples"])
        self.assertEqual([[], []], [s["examples"] for s in por_lema["banco"]])
