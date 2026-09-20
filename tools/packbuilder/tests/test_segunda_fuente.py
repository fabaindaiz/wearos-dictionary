"""Usar dos fuentes obliga a nombrar a las dos, y eso no puede depender de que alguien se acuerde.

La atribucion no es una formalidad del README: es **la condicion de la licencia** con la que se
distribuye el pack. Las dos fuentes son CC BY-SA 4.0, asi que no hay incompatibilidad -- hay una
obligacion, y el modo de falla es silencioso: el pack sale entero, abre, funciona, y esta mal
licenciado. Ningun otro check lo veria.
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
    """Un dump minimo del Wikcionario español: una entrada flaca y una con dos acepciones."""
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
        """EL TEST QUE PAGA ESTE ARCHIVO.

        Si el merge se hace y el credito no se mueve, el pack sale bien construido y **mal
        licenciado**, y no hay forma de notarlo mirando el contenido.
        """
        meta = self._construir(con_ejemplos=True)
        self.assertIn("es.wiktionary.org", meta["attribution"])
        self.assertIn("en.wiktionary.org", meta["attribution"])
        self.assertEqual("CC-BY-SA-4.0", meta["license"],
                         "las dos fuentes son CC BY-SA 4.0: la licencia del pack no cambia")

    def test_el_pack_id_cambia_para_que_los_dos_PUEDAN_convivir(self):
        # Si compartieran pack_id, instalar uno pisaria al otro y el historial del reloj
        # apuntaria a entradas de un pack que ya no esta.
        sin_ = self._construir(con_ejemplos=False)["pack_id"]
        con = self._construir(con_ejemplos=True)["pack_id"]
        self.assertNotEqual(sin_, con)
        self.assertTrue(con.startswith(sin_), "el sufijo tiene que dejar ver de cual deriva")

    def test_la_entrada_de_DOS_acepciones_no_recibe_el_ejemplo(self):
        """"banco" tiene ejemplo en la segunda fuente, pero dos acepciones en la nuestra.

        Pegarlo a la primera seria inventar la atribucion. Es la misma regla de D-132, aplicada
        del otro lado del merge.
        """
        import payload
        self._construir(con_ejemplos=True)
        db = sqlite3.connect(self.salida)
        dic = bytes.fromhex(
            db.execute("SELECT value FROM meta WHERE key='payload_dict'").fetchone()[0])
        por_lema = {}
        for hw, blob in db.execute("SELECT headword, payload FROM entry"):
            _pos, ss = payload.parse(payload.decompress(blob, dic))
            por_lema[hw] = ss
        db.close()
        self.assertEqual(["El acomodador nos tendía los abrigos."],
                         por_lema["acomodador"][0]["examples"])
        self.assertEqual([[], []], [s["examples"] for s in por_lema["banco"]])
