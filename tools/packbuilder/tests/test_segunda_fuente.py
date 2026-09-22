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


def _dump_wikidata(path):
    """Un dump de lexemas minimo: uno que el Wikcionario ya tiene y uno que no."""
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
    """Una segunda fuente que aporta LEMAS NUEVOS se funde en el pack (D-146).

    ⚠️ **Es una union de FILAS, no de campos, y por eso no necesita composicion.** Wikidata
    aporta 6.092 lemas que el Wikcionario no tiene --gentilicios regionales, locuciones--; los
    8.595 que comparten se quedan con la definicion del Wikcionario, asi que no hay nada que
    arbitrar. Eso es lo que hace barato fusionar y caro separar.

    Lo que se evita fusionando: dos packs del mismo idioma producian **dos chips "ES" y dos
    palabras del dia** en el inicio, porque la pantalla lista packs y desde D-136 el selector
    elige idioma.
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
        """EL TEST QUE PAGA ESTE ARCHIVO.

        "banco" existe en las dos fuentes. Dejar entrar las dos daria **dos filas para la misma
        palabra** en la lista de resultados, y ademas dos entradas con el mismo `uid`, que
        `_reject_uid_collisions` rechaza al cerrar el pack.
        """
        filas, _ = self._construir("--sumar", "es-wd", self.wd)
        self.assertEqual(1, [f[0] for f in filas].count("banco"))

    def test_la_definicion_que_gana_es_la_de_la_fuente_BASE(self):
        # No hay arbitraje: el que llega primero se queda. Asi la fusion no necesita decidir
        # cual definicion es mejor, que es lo que la haria cara.
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
        """EL TEST QUE ESTE MERGE HIZO NECESARIO, y lo agarro `verify_pack.py` primero.

        Cada fuente decide si una entrada necesita `sense_key` mirando SUS homografos. Al
        fusionar, un lexema que tenia gemelo en Wikidata puede perderlo --porque el gemelo ya
        estaba en el Wikcionario y se descarto-- y queda con una clave que ya no corresponde.

        ⚠️ Eso rompe `uid`, que es la identidad logica y **la llave del join entre packs**
        (D-055): un `uid` calculado con `sense_key` no coincide con el mismo lema en otro pack
        que lo calculo sin ella. Es el mismo error que D-139 documenta, ahora por otra puerta.

        La regla que vale es la del pack FINAL: lleva clave el que tiene homografo **ahi**.
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
        """⚠️ Antes exigia que el `pack_id` terminara en `-wd`. Ver D-215: la identidad es idioma
        + nivel, y de donde viene el contenido se contesta en `sources`, que es donde se mira."""
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
        """EL TEST QUE PAGA ESTE ARCHIVO.

        Si el merge se hace y el credito no se mueve, el pack sale bien construido y **mal
        licenciado**, y no hay forma de notarlo mirando el contenido.
        """
        meta = self._construir(con_ejemplos=True)
        self.assertIn("es.wiktionary.org", meta["attribution"])
        self.assertIn("en.wiktionary.org", meta["attribution"])
        self.assertEqual("CC-BY-SA-4.0", meta["license"],
                         "las dos fuentes son CC BY-SA 4.0: la licencia del pack no cambia")

    def test_el_manifiesto_declara_CADA_fuente_con_SU_licencia(self):
        """`meta.sources`: el manifiesto estructurado (D-138).

        La prosa de `attribution` sirve para leerla; esto sirve para **mostrarla por fuente**. Un
        pack puede mezclar contenido bajo licencias distintas --el español con `--frases` junta
        CC BY-SA 4.0 y CC BY 2.0 FR-- y un solo nombre para todo el pack o reclama de mas o
        acredita de menos.
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
        """⚠️ **Esto afirma lo contrario de lo que afirmaba hasta D-215**, a proposito.

        Antes el `pack_id` acumulaba un sufijo por fuente, *"para que los dos puedan convivir"*:
        dos builds del espanol con distintas fuentes eran dos packs instalables a la vez. Bajo
        *«los packs son por idioma y en versiones»* eso es justo lo que no se quiere -- son **el
        mismo diccionario**, y lo que los distingue es `data_version`, no la identidad.

        Lo que se pierde: dos variantes del mismo idioma ya no conviven. Lo que se gana: anadir
        una fuente deja de hacer que el pack parezca otro que la app nunca vio.
        """
        sin_ = self._construir(con_ejemplos=False)
        con = self._construir(con_ejemplos=True)
        self.assertEqual(sin_["pack_id"], con["pack_id"])
        self.assertEqual("es-full", con["pack_id"])
        # Y la proteccion de fondo sigue en pie por otra via: los dos builds se distinguen.
        self.assertNotEqual(
            sin_["sources"], con["sources"],
            "la diferencia real entre los dos sigue declarada, en sources",
        )

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
            _pos, ss, _palabra = payload.parse(payload.decompress(blob, dic))
            por_lema[hw] = ss
        db.close()
        self.assertEqual(["El acomodador nos tendía los abrigos."],
                         por_lema["acomodador"][0]["examples"])
        self.assertEqual([[], []], [s["examples"] for s in por_lema["banco"]])
