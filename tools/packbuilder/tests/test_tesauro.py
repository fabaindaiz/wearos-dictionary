"""Gluing WordNet's synonyms and antonyms to the pack's entries (D-144).

Three rules, and each prevents a different shape of incorrect content that looks correct:

  - **one sense**, or it is unknown which they belong to (D-117);
  - **do not repeat** what the wiki already put there, or the watch's line is spent twice;
  - **do not accept an inflection of the lemma itself** as a synonym, which is the Spanish MCR's
    noise.
"""

import os
import sqlite3
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import payload  # noqa: E402

import build  # noqa: E402

META = {
    "pack_id": "es-def-test", "kind": "monolingual", "name": "Test", "langs": "es",
    "fuzzy_profile": "es", "source_date": "20260101", "license": "CC0-1.0",
    "attribution": "test", "proper_nouns": "included", "source_url": "https://example.invalid/",
    "sources": "definitions\tTest\thttps://example.invalid/\tCC0 1.0\t\n",
}


def rec(headword, gloss, pos="noun", forms=(), synonyms=(), antonyms=(), mas=()):
    senses = [{"gloss": gloss, "examples": [], "translations": [],
               "synonyms": list(synonyms), "antonyms": list(antonyms), "related": []}]
    for extra in mas:
        senses.append({"gloss": extra, "examples": [], "translations": [],
                       "synonyms": [], "antonyms": [], "related": []})
    return build.Record(headword=headword, senses=senses, part_of_speech=pos, rank=100,
                        forms=tuple(forms), translations=(), sense_key=None)


class TesauroTest(unittest.TestCase):

    def setUp(self):
        self.path = os.path.join(tempfile.mkdtemp(), "pack.db")

    def construir(self, registros, tesauro):
        with build.PackBuilder(self.path, dict(META), thesaurus=tesauro) as builder:
            for r in registros:
                builder.add(r)
        self.db = sqlite3.connect(self.path)
        dic = bytes.fromhex(
            self.db.execute("SELECT value FROM meta WHERE key='payload_dict'").fetchone()[0])
        out = {}
        for hw, blob in self.db.execute("SELECT headword, payload FROM entry"):
            _pos, ss, _palabra = payload.parse(payload.decompress(blob, dic))
            out[hw] = ss
        return out

    def tearDown(self):
        if hasattr(self, "db"):
            self.db.close()

    def test_una_entrada_flaca_recibe_sus_sinonimos(self):
        got = self.construir([rec("carruaje", "Vehículo de tracción animal.")],
                             {("carruaje", "noun"): {"synonyms": ["coche", "vagón"]}})
        self.assertEqual(["coche", "vagón"], got["carruaje"][0]["synonyms"])

    def test_una_entrada_de_VARIAS_acepciones_no_recibe_nada(self):
        got = self.construir([rec("banco", "Asiento.", mas=["Entidad financiera."])],
                             {("banco", "noun"): {"synonyms": ["asiento"]}})
        self.assertEqual([[], []], [s["synonyms"] for s in got["banco"]])

    def test_UNA_FLEXION_DEL_LEMA_NO_ES_UN_SINONIMO(self):
        """THE TEST THAT PAYS FOR THIS FILE.

        The Spanish MCR was built automatically and puts inflections inside the synset: "coreano"
        appears with "coreana", "coreanos", "coreanas". Emitting them as synonyms fills the watch's
        line with the same word declined.

        It is detected against the pack's own `form` table, which is a datum we already have.
        """
        got = self.construir(
            [rec("coreano", "Originario de Corea.", pos="adj",
                 forms=("coreana", "coreanos", "coreanas"))],
            {("coreano", "adj"): {"synonyms": ["coreana", "coreanos", "surcoreano"]}},
        )
        self.assertEqual(["surcoreano"], got["coreano"][0]["synonyms"])

    def test_no_se_repite_lo_que_el_wiki_ya_puso(self):
        got = self.construir(
            [rec("frío", "De baja temperatura.", pos="adj", synonyms=["gélido"])],
            {("frío", "adj"): {"synonyms": ["gélido", "helado"]}},
        )
        self.assertEqual(["gélido", "helado"], got["frío"][0]["synonyms"])

    def test_los_antonimos_entran_por_su_propio_tag(self):
        got = self.construir([rec("die", "To stop living.", pos="verb")],
                             {("die", "verb"): {"antonyms": ["be born"]}})
        self.assertEqual(["be born"], got["die"][0]["antonyms"])
        self.assertEqual([], got["die"][0]["synonyms"], "un antonimo no puede salir de sinonimo")

    def test_el_sinonimo_entra_al_INDICE_y_el_antonimo_NO(self):
        """D-118 and D-126 still hold for the ones arriving this way.

        Searching "coche" has to find "carruaje"; searching "be born" must NOT return "die".
        """
        self.construir([rec("carruaje", "Vehículo.", pos="noun"),
                        rec("die", "To stop living.", pos="verb")],
                       {("carruaje", "noun"): {"synonyms": ["coche"]},
                        ("die", "verb"): {"antonyms": ["be born"]}})
        sinonimo = self.db.execute(
            "SELECT COUNT(*) FROM fts_def WHERE fts_def MATCH 'coche'").fetchone()[0]
        antonimo = self.db.execute(
            "SELECT COUNT(*) FROM fts_def WHERE fts_def MATCH 'born'").fetchone()[0]
        self.assertEqual(1, sinonimo, "el sinonimo SI va al indice (D-118)")
        self.assertEqual(0, antonimo, "el antonimo NO va al indice (D-126)")

    def test_el_tope_por_acepcion_no_se_pasa(self):
        got = self.construir(
            [rec("x", "Una glosa.", synonyms=["a", "b"])],
            {("x", "noun"): {"synonyms": ["c", "d", "e", "f"]}},
        )
        self.assertLessEqual(len(got["x"][0]["synonyms"]), 4)

    def test_sin_tesauro_el_pack_sale_igual_que_siempre(self):
        got = self.construir([rec("carruaje", "Vehículo.")], None)
        self.assertEqual([], got["carruaje"][0]["synonyms"])
