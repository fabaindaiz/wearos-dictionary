"""Tests del builder de packs.

Construyen packs de verdad en un directorio temporal y los inspeccionan con SQL. Lo que se
verifica aca es sobre todo lo que NO falla ruidosamente: un pack a medio construir, o con el
tope de traducciones mal aplicado, se abre sin error y devuelve resultados incompletos.
"""

import os
import shutil
import sqlite3
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import normalize  # noqa: E402
import verify_pack  # noqa: E402
from sources import toy  # noqa: E402

import build  # noqa: E402

BASE_META = {
    "pack_id": "test",
    "kind": "bilingual",
    "name": "Test",
    "lang_src": "es",
    "lang_dst": "en",
    "fuzzy_profile": "es",
    "data_version": "1",
    "license": "CC0-1.0",
    "attribution": "test",
}


def record(headword, gloss="una glosa", **kwargs):
    return build.Record(headword=headword, senses=[{"gloss": gloss}], **kwargs)


class BuilderTestCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.path = os.path.join(self.tmp, "pack.db")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def build(self, records, metadata=None):
        with build.PackBuilder(self.path, dict(metadata or BASE_META)) as builder:
            for item in records:
                builder.add(item)
        return sqlite3.connect(self.path)


class ToyPackTest(BuilderTestCase):
    def test_toy_pack_passes_every_invariant(self):
        # verify_pack.py es lo que se corre sobre los packs reales; si el de juguete no pasa,
        # el de verdad tampoco va a pasar.
        with build.PackBuilder(self.path, dict(toy.METADATA)) as builder:
            for item in toy.records():
                builder.add(item)
        self.assertEqual(0, verify_pack.verify(self.path), "verify_pack encontro fallas")


class ToyPackFixtureTest(BuilderTestCase):
    """El pack de juguete tiene que seguir ejercitando los cinco caminos de busqueda.

    Los tests instrumentados de :dict-data (SqlitePackSourceTest) dependen del CONTENIDO de
    este pack: que "coreer" no lo encuentre el prefijo, que "c" de mas resultados que el umbral
    del nivel tolerante, que "bajo" tenga dos homografos. Nada de eso es obvio al editar
    sources/toy.py.

    Sin estos tests, romper una de esas suposiciones no se notaria hasta conectar un emulador,
    y el fallo se leeria como un bug del codigo y no del fixture.
    """

    # Espejo de SqlitePackSource.FUZZY_TRIGGER. Si cambia alla, cambia aca.
    FUZZY_TRIGGER = 5

    def setUp(self):
        super().setUp()
        with build.PackBuilder(self.path, dict(toy.METADATA)) as builder:
            for item in toy.records():
                builder.add(item)
        self.db = sqlite3.connect(self.path)

    def prefijo(self, texto):
        clave = normalize.norm(texto)
        upper = clave[:-1] + chr(ord(clave[-1]) + 1)
        return [
            row[0]
            for row in self.db.execute(
                "SELECT headword FROM entry WHERE norm >= ? AND norm < ?"
                " ORDER BY norm, rank DESC LIMIT 30",
                (clave, upper),
            )
        ]

    def test_un_prefijo_productivo_supera_el_umbral_del_nivel_tolerante(self):
        # Si esto baja del umbral, el test que comprueba que el nivel tolerante NO se dispara
        # pasaria por el motivo equivocado.
        self.assertGreaterEqual(len(self.prefijo("c")), self.FUZZY_TRIGGER)

    def test_hay_un_tipeo_que_solo_alcanza_el_nivel_tolerante(self):
        # "coreer" no debe ser alcanzable por prefijo ni por forma flexionada: si lo fuera, el
        # test del nivel tolerante no probaria el nivel tolerante.
        self.assertEqual([], self.prefijo("coreer"))
        formas = self.db.execute(
            "SELECT COUNT(*) FROM form WHERE norm = ?", (normalize.norm("coreer"),)
        ).fetchone()[0]
        self.assertEqual(0, formas)
        # Pero si tiene que caer en el vecindario fuzzy.
        clave = normalize.fuzzy("coreer", "es")[:4]
        upper = clave[:-1] + chr(ord(clave[-1]) + 1)
        vecinos = self.db.execute(
            "SELECT COUNT(*) FROM entry WHERE fuzzy >= ? AND fuzzy < ?", (clave, upper)
        ).fetchone()[0]
        self.assertGreater(vecinos, 0)

    def test_hay_homografos_con_pos_distinto(self):
        filas = self.db.execute(
            "SELECT pos FROM entry WHERE headword = 'bajo' ORDER BY pos"
        ).fetchall()
        self.assertEqual([("adjective",), ("preposition",)], filas)

    def test_hay_una_forma_flexionada_y_una_traduccion_conocidas(self):
        self.assertGreater(
            self.db.execute(
                "SELECT COUNT(*) FROM form f JOIN entry e ON e.id = f.entry_id"
                " WHERE f.norm = 'corriendo' AND e.headword = 'correr'"
            ).fetchone()[0],
            0,
        )
        self.assertGreater(
            self.db.execute(
                "SELECT COUNT(*) FROM trans t JOIN entry e ON e.id = t.entry_id"
                " WHERE t.norm = 'run' AND e.headword = 'correr'"
            ).fetchone()[0],
            0,
        )

    def test_hay_una_palabra_buscable_solo_por_su_definicion(self):
        filas = self.db.execute(
            "SELECT e.headword FROM fts_def f JOIN entry e ON e.id = f.rowid"
            " WHERE fts_def MATCH ?",
            ('"rapidamente"',),
        ).fetchall()
        self.assertIn(("correr",), filas)


class IngestTest(BuilderTestCase):
    def test_headword_that_normalizes_to_empty_is_skipped(self):
        # "!!!" no se puede buscar por ningun camino; entrar al pack solo ocuparia lugar.
        db = self.build([record("correr"), record("!!!"), record("¿?")])
        self.assertEqual([("correr",)], list(db.execute("SELECT headword FROM entry")))

    def test_entry_without_usable_senses_is_skipped(self):
        db = self.build([
            record("correr"),
            build.Record(headword="vacio", senses=[{"gloss": "   "}]),
            build.Record(headword="sin", senses=[]),
        ])
        self.assertEqual([("correr",)], list(db.execute("SELECT headword FROM entry")))

    def test_forms_are_deduplicated_and_exclude_the_headword(self):
        db = self.build([
            record("correr", forms=["corriendo", "corriendo", "Corriendo", "correr"])
        ])
        forms = [row[0] for row in db.execute("SELECT norm FROM form ORDER BY norm")]
        self.assertEqual(["corriendo"], forms, "el lema o un duplicado entro en form")

    def test_translation_indexes_phrase_and_each_word(self):
        # Sin esto, buscar "run" no encuentra "to run", que es lo que un usuario escribe.
        db = self.build([record("correr", translations=["to run"])])
        keys = sorted(row[0] for row in db.execute("SELECT norm FROM trans"))
        self.assertEqual(["run", "to", "to run"], keys)

    def test_translation_cap_keeps_the_best_ranked(self):
        # "to" apuntaria a decenas de miles de entradas en un pack real.
        limit = build.TRANS_MAX_PER_KEY
        records = [
            record("verbo%03d" % i, rank=i, translations=["to word%03d" % i])
            for i in range(limit + 25)
        ]
        db = self.build(records)

        kept = [row[0] for row in db.execute(
            "SELECT e.rank FROM trans t JOIN entry e ON e.id = t.entry_id"
            " WHERE t.norm = 'to' ORDER BY e.rank")]
        self.assertEqual(limit, len(kept), "no se aplico el tope por clave")
        # Se conservan los de mejor rank (menor es mas comun), no los primeros que llegaron.
        self.assertEqual(list(range(limit)), kept)

        dropped = db.execute("SELECT value FROM meta WHERE key='trans_dropped'").fetchone()[0]
        self.assertEqual(25, int(dropped), "meta.trans_dropped no refleja lo recortado")

    def test_normalization_columns_match_normalize_module(self):
        db = self.build([record("Ärztin"), record("acción"), record("Straße")])
        for headword, norm_key, fuzzy_key in db.execute(
            "SELECT headword, norm, fuzzy FROM entry"
        ):
            self.assertEqual(normalize.norm(headword), norm_key)
            self.assertEqual(normalize.fuzzy(headword, "es"), fuzzy_key)


class StructureTest(BuilderTestCase):
    def test_indexes_exist_and_staging_is_gone(self):
        db = self.build([record("correr")])
        names = {row[0] for row in db.execute("SELECT name FROM sqlite_master")}
        self.assertIn("idx_entry_norm", names)
        self.assertIn("idx_entry_fuzzy", names)
        self.assertNotIn("staging", names)
        self.assertNotIn("staging_trans", names)

    def test_fts_rowid_matches_entry_id(self):
        # fts_def es contentless: el rowid es lo unico que devuelve, asi que si no coincide con
        # entry.id la busqueda de texto libre apunta a entradas equivocadas.
        db = self.build(
            [record("correr", gloss="moverse rapidamente"), record("casa", gloss="edificio")]
        )
        entry_id = db.execute("SELECT id FROM entry WHERE headword='correr'").fetchone()[0]
        rows = [row[0] for row in db.execute(
            "SELECT rowid FROM fts_def WHERE fts_def MATCH ?", ('"rapidamente"',))]
        self.assertEqual([entry_id], rows)


class FailureModeTest(BuilderTestCase):
    def test_reserved_meta_keys_are_rejected(self):
        # Una schema_version escrita a mano seria una forma silenciosa de romper la validacion
        # que hace el reloj al abrir el pack.
        metadata = dict(BASE_META)
        metadata["schema_version"] = "99"
        with self.assertRaises(ValueError):
            build.PackBuilder(self.path, metadata)

    def test_unknown_fuzzy_profile_is_rejected(self):
        metadata = dict(BASE_META)
        metadata["fuzzy_profile"] = "klingon"
        with self.assertRaises(ValueError):
            build.PackBuilder(self.path, metadata)

    def test_empty_pack_is_rejected(self):
        with self.assertRaises(ValueError), build.PackBuilder(self.path, dict(BASE_META)):
            pass

    def test_failure_leaves_no_half_built_pack(self):
        # Un pack a medias es peor que ninguno: se abriria sin error y devolveria resultados
        # incompletos, sin nada que indique que le faltan entradas.
        class Boom(Exception):
            pass

        # noqa de SIM117 a proposito: assertRaises no es un peer del otro context manager,
        # afirma SOBRE el. Combinarlos en un solo `with` los mostraria como iguales.
        with self.assertRaises(Boom):  # noqa: SIM117
            with build.PackBuilder(self.path, dict(BASE_META)) as builder:
                builder.add(record("correr"))
                raise Boom()
        self.assertFalse(os.path.exists(self.path), "quedo un pack a medio construir")


class DeterminismTest(BuilderTestCase):
    def test_two_builds_produce_the_same_data(self):
        # Determinista para que reconstruir un pack sin cambios no genere una descarga nueva.
        def contents(path):
            with build.PackBuilder(path, dict(toy.METADATA)) as builder:
                for item in toy.records():
                    builder.add(item)
            db = sqlite3.connect(path)
            entries = list(
                db.execute(
                    "SELECT id, headword, norm, fuzzy, pos, rank, payload FROM entry ORDER BY id"
                )
            )
            meta = dict(db.execute("SELECT key, value FROM meta"))
            meta.pop("built_at")  # unico campo que cambia entre corridas, a proposito
            db.close()
            return entries, meta

        first = contents(os.path.join(self.tmp, "a.db"))
        second = contents(os.path.join(self.tmp, "b.db"))
        self.assertEqual(first[1], second[1], "la metadata cambio entre dos builds iguales")
        self.assertEqual(first[0], second[0], "los payloads cambiaron entre dos builds iguales")


if __name__ == "__main__":
    unittest.main()
