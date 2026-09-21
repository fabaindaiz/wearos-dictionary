"""Derivar un pack nucleo de un pack completo ya construido."""

import os
import sqlite3
import sys
import tempfile
import shutil
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import build  # noqa: E402
import build_core  # noqa: E402

BASE_META = {
    "pack_id": "es-def-wikc",
    "kind": "monolingual",
    "name": "Español",
    "lang_src": "es",
    "fuzzy_profile": "es",
    "source_date": "20260915",
    "license": "CC-BY-SA-4.0",
    "attribution": "Wikcionario",
    "sources": ("definitions\tWikcionario\thttps://example.invalid/w\t"
                "CC BY-SA 4.0\thttps://creativecommons.org/licenses/by-sa/4.0/\n"),
    "source_url": "https://example.invalid/w",
    "proper_nouns": "included",
}


def rec(headword, pos="noun", rank=0, forms=(), senses=None, sense_key=None):
    return build.Record(
        headword=headword,
        senses=senses or [{"gloss": "definición de " + headword}],
        part_of_speech=pos,
        rank=rank,
        forms=forms,
        sense_key=sense_key,
    )


class BuildCoreTest(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.completo = os.path.join(self.dir, "completo.db")
        with build.PackBuilder(self.completo, dict(BASE_META)) as b:
            b.add(rec("agua", rank=1, forms=["aguas"]))
            b.add(rec("correr", pos="verb", rank=2, forms=["corro", "corriendo"]))
            b.add(rec("quilombo", rank=900))
            b.add(rec("ornitorrinco", rank=950))
            # Un homografo: mismo headword y pos, distinta etimologia. Su uid lleva sense_key.
            b.add(rec("banco", rank=10, sense_key="asiento"))
            b.add(rec("banco", rank=11, sense_key="entidad"))

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def _core(self, vocabulario, nombre="core.db"):
        salida = os.path.join(self.dir, nombre)
        build_core.derive(self.completo, salida, vocabulario)
        return salida

    def _filas(self, path, sql):
        with sqlite3.connect(path) as db:
            return db.execute(sql).fetchall()

    def test_solo_entran_los_lemas_del_vocabulario(self):
        core = self._core({"agua", "correr"})
        lemas = sorted(r[0] for r in self._filas(core, "SELECT headword FROM entry"))
        self.assertEqual(["agua", "correr"], lemas)

    def test_los_uid_son_LOS_MISMOS_que_en_el_pack_completo(self):
        # ⚠️ **La invariante que hace que esto sea un subconjunto y no otro pack.** `uid` es la
        # identidad logica y la llave de join entre packs (D-055). Si el nucleo recalculara su
        # `sense_key` contando SUS homografos, "banco" tendria otro uid que en el completo -- que
        # es exactamente el fallo que D-145 encontro al fusionar. El uid se COPIA, no se recalcula.
        core = self._core({"agua", "banco"})
        antes = dict(self._filas(self.completo, "SELECT headword || ':' || id, uid FROM entry"))
        despues = self._filas(core, "SELECT headword, uid FROM entry")
        uids_completo = {u for k, u in antes.items() if k.split(":")[0] in ("agua", "banco")}
        self.assertEqual(uids_completo, {u for _h, u in despues})

    def test_un_homografo_entra_ENTERO_o_no_entra(self):
        # Las dos acepciones de "banco" son dos entradas con el mismo lema: el vocabulario habla
        # de palabras, no de entradas, asi que entran las dos.
        core = self._core({"banco"})
        self.assertEqual(2, len(self._filas(core, "SELECT id FROM entry")))

    def test_declara_de_quien_es_subconjunto(self):
        core = self._core({"agua"})
        meta = dict(self._filas(core, "SELECT key, value FROM meta"))
        self.assertEqual("es-def-wikc", meta["subset_of"])
        self.assertEqual("es-def-wikc-core", meta["pack_id"])

    def test_las_formas_flexionadas_del_lema_viajan_con_el(self):
        # Sin esto, buscar "corriendo" en el nucleo no encontraria "correr", que es el peldano 2
        # de la cascada.
        core = self._core({"correr"})
        formas = sorted(r[0] for r in self._filas(core, "SELECT norm FROM form"))
        self.assertIn("corriendo", formas)

    def test_las_formas_de_lo_que_NO_entro_no_viajan(self):
        core = self._core({"agua"})
        formas = sorted(r[0] for r in self._filas(core, "SELECT norm FROM form"))
        self.assertNotIn("corriendo", formas)

    def test_se_conserva_el_contenido_de_la_acepcion(self):
        core = self._core({"agua"})
        with sqlite3.connect(core) as db:
            import binascii
            import payload
            dic = binascii.unhexlify(
                db.execute("SELECT value FROM meta WHERE key='payload_dict'").fetchone()[0])
            blob = db.execute("SELECT payload FROM entry").fetchone()[0]
        _pos, senses, _palabra = payload.parse(payload.decompress(blob, dic))
        self.assertEqual("definición de agua", senses[0]["gloss"])

    def test_hereda_la_atribucion_del_completo_y_suma_la_del_corpus(self):
        # ⚠️ El credito se mueve con el contenido (D-138): el nucleo distribuye las mismas
        # definiciones, asi que lleva las mismas fuentes; y el corpus que ELIGIO las palabras
        # tambien se declara, porque `sources` contesta como se armo el pack.
        core = self._core({"agua"})
        meta = dict(self._filas(core, "SELECT key, value FROM meta"))
        self.assertIn("Wikcionario", meta["sources"])
        self.assertIn("vocabulary", meta["sources"])

    def test_un_vocabulario_que_no_matchea_nada_falla_en_vez_de_dar_un_pack_vacio(self):
        # Un pack de cero entradas se abre sin error y no encuentra nada: es la falla silenciosa
        # que este repo existe para no tener.
        with self.assertRaises(ValueError):
            self._core({"palabraqueno existe"})
