"""Gluing a corpus sentence to an entry, without hanging it off the wrong word.

This file exists because of **one** failure mode, and it is the worst this repo has: incorrect
content that looks correct. "vino" is a lemma (the drink) and also a form of "venir". A sentence
saying "Ella vino ayer" hung off the drink throws nothing, logs nothing, is not caught by
`verify_pack.py` and comes out of the pack as a definition with its example.

So the rule is not "find the word": it is **that that word lead to a single entry in the whole
pack**, lemma or inflected form. It costs half the yield (14,023 reachable entries -> 7,019) and
it is paid in full.
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
    "pack_id": "test-frases",
    "kind": "monolingual",
    "name": "Test",
    "langs": "es",
    "fuzzy_profile": "es",
    "source_date": "20260101",
    "license": "CC0-1.0",
    "attribution": "test",
    "source_url": "https://example.org/",
    "proper_nouns": "excluded",
}


def rec(headword, gloss, pos="noun", forms=(), examples=None, mas_acepciones=()):
    senses = [{"gloss": gloss, "examples": list(examples or []), "translations": [],
               "synonyms": [], "antonyms": [], "related": []}]
    for extra in mas_acepciones:
        senses.append({"gloss": extra, "examples": [], "translations": [],
                       "synonyms": [], "antonyms": [], "related": []})
    return build.Record(headword=headword, senses=senses, part_of_speech=pos,
                        rank=100, forms=tuple(forms), translations=(), sense_key=None)


class FrasesTest(unittest.TestCase):

    def setUp(self):
        self.path = os.path.join(tempfile.mkdtemp(), "pack.db")

    def construir(self, registros, frases):
        with build.PackBuilder(self.path, dict(META), sentences=frases) as builder:
            for r in registros:
                builder.add(r)
        db = sqlite3.connect(self.path)
        dic = bytes.fromhex(
            db.execute("SELECT value FROM meta WHERE key='payload_dict'").fetchone()[0])
        out = {}
        for hw, blob in db.execute("SELECT headword, payload FROM entry"):
            _pos, ss, _palabra = payload.parse(payload.decompress(blob, dic))
            out.setdefault(hw, []).append(ss)
        self.db = db
        return out

    def tearDown(self):
        if hasattr(self, "db"):
            self.db.close()

    def test_una_entrada_flaca_recibe_su_frase(self):
        got = self.construir([rec("hiragana", "Silabario japonés.")],
                             {"hiragana": "El hiragana es un silabario."})
        self.assertEqual(["El hiragana es un silabario."], got["hiragana"][0][0]["examples"])

    def test_EL_HOMOGRAFO_NO_RECIBE_NADA(self):
        """THE TEST THAT PAYS FOR THIS FILE.

        "vino" is a lemma (the drink) and a form of "venir". The sentence "Ella vino ayer."
        illustrates the verb; hung off the drink it would be an example that contradicts its own
        definition.
        """
        got = self.construir(
            [rec("vino", "Bebida alcohólica de uva."),
             rec("venir", "Desplazarse hacia aquí.", pos="verb", forms=("vino", "vine"))],
            {"vino": "Ella vino ayer temprano."},
        )
        self.assertEqual([], got["vino"][0][0]["examples"], "la bebida no puede recibirla")
        self.assertEqual([], got["venir"][0][0]["examples"],
                         "el verbo tampoco: la clave es ambigua y no se sabe cual es")

    def test_una_forma_flexionada_INEQUIVOCA_si_sirve(self):
        # It is half the yield: 790,611 of the pack's 867,826 keys are forms.
        got = self.construir([rec("lixiviar", "Extraer partes solubles.", pos="verb",
                                  forms=("lixiviaba",))],
                             {"lixiviaba": "El agua lixiviaba el mineral."})
        self.assertEqual(["El agua lixiviaba el mineral."],
                         got["lixiviar"][0][0]["examples"])

    def test_una_entrada_de_VARIAS_acepciones_no_recibe_nada(self):
        # Same rule as D-132 and D-135: it is unknown which of the senses it illustrates.
        got = self.construir([rec("banco", "Asiento largo.",
                                  mas_acepciones=["Entidad financiera."])],
                             {"banco": "Me senté en el banco."})
        self.assertEqual([[], []], [s["examples"] for s in got["banco"][0]])

    def test_una_entrada_que_YA_tiene_ejemplo_no_se_toca(self):
        # Wiktionary's example belongs to the sense; the corpus one merely contains it. In doubt,
        # the one the source attributed wins.
        got = self.construir([rec("correr", "Moverse rápido.", pos="verb",
                                  examples=["Corrió hasta la esquina."])],
                             {"correr": "Me gusta correr por la mañana."})
        self.assertEqual(["Corrió hasta la esquina."], got["correr"][0][0]["examples"])

    def test_la_frase_tambien_se_puede_buscar_por_texto_libre(self):
        # The examples already went into `fts_def` (D-118 includes them); a sentence glued later
        # has to go in just the same, or searching by definition would see a different pack from
        # the one displayed. It is the class of misalignment D-011 exists to prevent.
        self.construir([rec("hiragana", "Silabario japonés.")],
                       {"hiragana": "El hiragana es un silabario."})
        filas = self.db.execute(
            "SELECT COUNT(*) FROM fts_def WHERE fts_def MATCH 'silabario'").fetchone()[0]
        self.assertEqual(1, filas)

    def test_sin_frases_el_pack_sale_igual_que_siempre(self):
        got = self.construir([rec("hiragana", "Silabario japonés.")], None)
        self.assertEqual([], got["hiragana"][0][0]["examples"])
