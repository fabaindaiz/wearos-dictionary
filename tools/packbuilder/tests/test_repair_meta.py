"""Repairing a pack's metadata without re-exporting its content."""

import contextlib
import hashlib
import io
import os
import shutil
import sqlite3
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import build  # noqa: E402
import build_pack  # noqa: E402
import repair_meta  # noqa: E402

from test_core import BASE_META, rec  # noqa: E402


def _sin_ruido():
    return contextlib.redirect_stdout(io.StringIO())


def huella_del_contenido(path):
    """sha256 over every table that is NOT `meta`.

    This is the invariant the whole tool rests on: a metadata repair cannot change a search
    result. Comparing it before and after is what proves that mechanically instead of by reading
    the code and believing it.
    """
    con = sqlite3.connect(path)
    try:
        tablas = [r[0] for r in con.execute(
            "select name from sqlite_master where type in ('table','index') "
            "and name != 'meta' order by name")]
        h = hashlib.sha256()
        for t in tablas:
            h.update(t.encode())
            try:
                for fila in con.execute("select * from %s" % t):
                    h.update(repr(fila).encode())
            except sqlite3.DatabaseError:
                # A contentless FTS shadow table is not selectable; its name still counts.
                pass
        return h.hexdigest()
    finally:
        con.close()


class NameWithoutTiersTest(unittest.TestCase):
    """Repair strips EVERY tier token; the builder's helper strips one, on purpose."""

    def test_the_accumulated_damage_comes_off_whole(self):
        # ⚠️ This is the exact string that shipped in `en-main.db`, read off the pack on
        # 2026-09-23. Feeding it to `build.name_with_tier` returns it UNCHANGED --removing
        # `(main)` leaves `(full)` at the end-- which is why repair needs its own function.
        self.assertEqual("English", repair_meta.name_without_tiers("English (full) (main)"))
        self.assertEqual("English (full) (main)",
                         build.name_with_tier("English (full) (main)", "main"))

    def test_a_name_that_merely_ends_in_a_parenthesis_survives(self):
        # `Griego (koine)` must not lose its parenthesis for resembling a tier.
        self.assertEqual("Griego (koine)", repair_meta.name_without_tiers("Griego (koine)"))

    def test_a_clean_name_is_left_alone(self):
        self.assertEqual("English", repair_meta.name_without_tiers("English"))


class RepairsForTest(unittest.TestCase):

    def test_a_healthy_pack_needs_nothing(self):
        meta = {"pack_id": "en-full", "tier": "full", "name": "English (full)",
                "langs": "en", "description": "English definitions."}
        self.assertEqual([], repair_meta.repairs_for(meta))

    def test_the_double_tier_is_reported(self):
        meta = {"pack_id": "en-main", "tier": "main", "name": "English (full) (main)",
                "langs": "en", "description": "English definitions."}
        [(clave, viejo, nuevo, _)] = repair_meta.repairs_for(meta)
        self.assertEqual(("name", "English (full) (main)", "English (main)"),
                         (clave, viejo, nuevo))

    def test_a_spanish_sentence_in_an_english_pack_is_translated(self):
        en, es = build_pack.FRASES["frecuencia"]
        meta = {"pack_id": "en-full", "tier": "full", "name": "English (full)",
                "langs": "en", "description": "English definitions. " + es}
        [(clave, _viejo, nuevo, _)] = repair_meta.repairs_for(meta)
        self.assertEqual("description", clave)
        self.assertEqual("English definitions. " + en, nuevo)

    def test_a_spanish_pack_keeps_its_spanish(self):
        # The mirror case, and the one that would break every Spanish pack if the condition were
        # dropped: the same sentence is CORRECT here.
        _en, es = build_pack.FRASES["frecuencia"]
        meta = {"pack_id": "es-full", "tier": "full", "name": "Español (full)",
                "langs": "es", "description": "Definiciones. " + es}
        self.assertEqual([], repair_meta.repairs_for(meta))

    def test_the_bidirectional_pack_keeps_its_untiered_name(self):
        # ⚠️ **Caught by a dry run against the real packs, 2026-09-23.** `es-en` declares
        # `tier=full` and its name deliberately carries no tier: `build_pack` skips the stamping
        # for a bilingual pack, whose tiers are not sizes of one dictionary. The first version of
        # this rule wanted to rename it `Español ↔ English (full)` -- inventing a defect out of a
        # deliberate choice, over an artifact nobody would rebuild to check.
        meta = {"pack_id": "es-tr", "kind": "bilingual", "tier": "full",
                "name": "Español ↔ English", "langs": "es,en", "description": "Bilingüe."}
        self.assertEqual([], repair_meta.repairs_for(meta))

    def test_the_bidirectional_pack_keeps_its_spanish(self):
        # `es,en` is Spanish-first and described in Spanish on purpose.
        _en, es = build_pack.FRASES["frecuencia"]
        meta = {"pack_id": "es-tr", "name": "Español ↔ English",
                "langs": "es,en", "description": "Bilingüe. " + es}
        self.assertEqual([], repair_meta.repairs_for(meta))


class RepairOnARealPackTest(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.pack = os.path.join(self.dir, "en-main.db")
        meta = dict(BASE_META)
        meta.update({"pack_id": "en-main", "langs": "en", "tier": "main",
                     "name": "English (full) (main)",
                     "description": "English definitions. "
                                    + build_pack.FRASES["frecuencia"][1]})
        with build.PackBuilder(self.pack, meta) as b:
            b.add(rec("water", rank=1, forms=["waters"]))
            b.add(rec("run", pos="verb", rank=2, forms=["runs", "running"]))
            b.add(rec("platypus", rank=950))

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def _meta(self):
        con = sqlite3.connect(self.pack)
        try:
            return dict(con.execute("select key, value from meta").fetchall())
        finally:
            con.close()

    def test_it_repairs_both_fields_and_moves_the_version(self):
        antes = self._meta()
        # A minute later than the build: see `test_it_refuses_when_the_version_would_not_move`.
        with _sin_ruido():
            repair_meta.repair(self.pack, write=True, ahora=(2027, 1, 1, 12, 0))
        despues = self._meta()
        self.assertEqual("English (main)", despues["name"])
        self.assertEqual("English definitions. " + build_pack.FRASES["frecuencia"][0],
                         despues["description"])
        # ⚠️ The version has to MOVE, or an installer comparing versions keeps the broken copy
        # and the repair silently never arrives.
        self.assertNotEqual(antes["data_version"], despues["data_version"])

    def test_it_refuses_when_the_version_would_not_move(self):
        # ⚠️ `data_version` has MINUTE resolution, and its docstring justifies that with
        # *"a build takes minutes, so two never land on the same one"*. A repair takes under a
        # second, so it breaks that assumption -- and two files claiming one version is exactly
        # how an installer keeps the broken copy. It refuses out loud instead.
        antes = self._meta()
        with self.assertRaises(SystemExit):
            with _sin_ruido():
                repair_meta.repair(self.pack, write=True,
                                   ahora=tuple(int(x) for x in (
                                       antes["data_version"][0:4], antes["data_version"][4:6],
                                       antes["data_version"][6:8], antes["data_version"][8:10],
                                       antes["data_version"][10:12])))
        self.assertEqual(antes["name"], self._meta()["name"])

    def test_it_does_not_touch_a_single_row_of_content(self):
        # ⚠️ **The boundary the whole tool rests on.** If a repair could change `entry`,
        # `payload` or `fts_def`, it would be a second builder -- a worse one, running over an
        # artifact nobody would rebuild to check.
        antes = huella_del_contenido(self.pack)
        with _sin_ruido():
            repair_meta.repair(self.pack, write=True, ahora=(2027, 1, 1, 12, 0))
        self.assertEqual(antes, huella_del_contenido(self.pack))

    def test_without_write_it_changes_nothing(self):
        antes = self._meta()
        with _sin_ruido():
            arreglos = repair_meta.repair(self.pack, write=False)
        self.assertEqual(2, len(arreglos))
        self.assertEqual(antes, self._meta())

    def test_running_it_twice_is_a_no_op_the_second_time(self):
        with _sin_ruido():
            repair_meta.repair(self.pack, write=True, ahora=(2027, 1, 1, 12, 0))
            version = self._meta()["data_version"]
            arreglos = repair_meta.repair(self.pack, write=True, ahora=(2027, 1, 1, 12, 1))
        self.assertEqual([], arreglos)
        # And the version does NOT move on a no-op: a repaired pack re-checked is the same pack,
        # and bumping it would make an installer re-download bytes that did not change.
        self.assertEqual(version, self._meta()["data_version"])


if __name__ == "__main__":
    unittest.main()
