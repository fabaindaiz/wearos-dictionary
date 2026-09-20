"""Pegar una frase de corpus a una entrada, sin colgarsela a la palabra equivocada.

Este archivo existe por **un** modo de falla, y es el peor que tiene este repo: contenido
incorrecto que parece correcto. "vino" es un lema (la bebida) y tambien una forma de "venir".
Una frase que dice "Ella vino ayer" colgada de la bebida no lanza, no loguea, no lo agarra
`verify_pack.py` y sale del pack como una definicion con su ejemplo.

Por eso la regla no es "buscar la palabra": es **que esa palabra lleve a una sola entrada en
todo el pack**, lema o forma flexionada. Cuesta la mitad del rendimiento (14.023 entradas
alcanzables -> 7.019) y se paga entero.
"""

import os
import sqlite3
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import build  # noqa: E402
import payload  # noqa: E402

META = {
    "pack_id": "test-frases",
    "kind": "monolingual",
    "name": "Test",
    "lang_src": "es",
    "fuzzy_profile": "es",
    "data_version": "20260101",
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
            _pos, ss = payload.parse(payload.decompress(blob, dic))
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
        """EL TEST QUE PAGA ESTE ARCHIVO.

        "vino" es lema (la bebida) y forma de "venir". La frase "Ella vino ayer." ilustra el
        verbo; colgada de la bebida seria un ejemplo que contradice su propia definicion.
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
        # Es la mitad del rendimiento: 790.611 de las 867.826 claves del pack son formas.
        got = self.construir([rec("lixiviar", "Extraer partes solubles.", pos="verb",
                                  forms=("lixiviaba",))],
                             {"lixiviaba": "El agua lixiviaba el mineral."})
        self.assertEqual(["El agua lixiviaba el mineral."],
                         got["lixiviar"][0][0]["examples"])

    def test_una_entrada_de_VARIAS_acepciones_no_recibe_nada(self):
        # Misma regla que D-132 y D-135: no se sabe cual de las acepciones ilustra.
        got = self.construir([rec("banco", "Asiento largo.", mas_acepciones=["Entidad financiera."])],
                             {"banco": "Me senté en el banco."})
        self.assertEqual([[], []], [s["examples"] for s in got["banco"][0]])

    def test_una_entrada_que_YA_tiene_ejemplo_no_se_toca(self):
        # El ejemplo del Wikcionario es de la acepcion; el de corpus solo la contiene. Ante la
        # duda gana el que la fuente atribuyo.
        got = self.construir([rec("correr", "Moverse rápido.", pos="verb",
                                  examples=["Corrió hasta la esquina."])],
                             {"correr": "Me gusta correr por la mañana."})
        self.assertEqual(["Corrió hasta la esquina."], got["correr"][0][0]["examples"])

    def test_la_frase_tambien_se_puede_buscar_por_texto_libre(self):
        # Los ejemplos ya entraban a `fts_def` (D-118 los incluye); una frase pegada despues
        # tiene que entrar igual, o la busqueda por definicion veria un pack distinto al que se
        # muestra. Es la clase de desalineacion que D-011 existe para impedir.
        self.construir([rec("hiragana", "Silabario japonés.")],
                       {"hiragana": "El hiragana es un silabario."})
        filas = self.db.execute(
            "SELECT COUNT(*) FROM fts_def WHERE fts_def MATCH 'silabario'").fetchone()[0]
        self.assertEqual(1, filas)

    def test_sin_frases_el_pack_sale_igual_que_siempre(self):
        got = self.construir([rec("hiragana", "Silabario japonés.")], None)
        self.assertEqual([], got["hiragana"][0][0]["examples"])
