import os
import sqlite3
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from sources import inflections  # noqa: E402


def _pack(filas):
    """A minimal `.db` with `entry` and `form`, which is all this module reads."""
    ruta = tempfile.NamedTemporaryFile(suffix=".db", delete=False).name
    con = sqlite3.connect(ruta)
    con.execute("CREATE TABLE entry (id INTEGER PRIMARY KEY, norm TEXT NOT NULL)")
    con.execute("CREATE TABLE form (norm TEXT NOT NULL, entry_id INTEGER NOT NULL)")
    for i, (lema, formas) in enumerate(filas.items(), 1):
        con.execute("INSERT INTO entry VALUES (?,?)", (i, lema))
        for f in formas:
            con.execute("INSERT INTO form VALUES (?,?)", (f, i))
    con.commit()
    con.close()
    return ruta


class FlexionesDelDestinoTest(unittest.TestCase):
    """The target language's inflections, read from an already built pack.

    The failure mode they close: the reverse direction finds `dogs` only if some gloss writes
    `dogs`. Measured, the English top 8,000's coverage goes from **78.1 % to 98.9 %**.
    """

    def setUp(self):
        self.rutas = []

    def tearDown(self):
        for r in self.rutas:
            os.unlink(r)

    def test_trae_las_flexiones_de_las_claves_que_son_lema(self):
        ruta = _pack({"dog": ["dogs"], "run": ["ran", "running"], "cat": ["cats"]})
        self.rutas.append(ruta)
        got = inflections.por_lema(ruta, {"dog", "run"})
        self.assertEqual(["dogs"], got["dog"])
        self.assertEqual(["ran", "running"], got["run"])
        self.assertNotIn("cat", got, "no se piden flexiones de lo que el pack no traduce")

    def test_descarta_los_artefactos_del_parser(self):
        """⚠️ `no table tags` y `glossary` aparecen 577 y 575 veces en el pack ingles real."""
        ruta = _pack({"dog": ["dogs", "no table tags", "glossary"]})
        self.rutas.append(ruta)
        self.assertEqual(["dogs"], inflections.por_lema(ruta, {"dog"})["dog"])

    def test_descarta_las_frases(self):
        """38.7 % of the English pack's `form` rows contain a space."""
        ruta = _pack({"eat": ["ate", "ate breathed and slept"]})
        self.rutas.append(ruta)
        self.assertEqual(["ate"], inflections.por_lema(ruta, {"eat"})["eat"])

    def test_descarta_lo_que_no_es_palabra(self):
        ruta = _pack({"ten": ["10s", "tens"]})
        self.rutas.append(ruta)
        self.assertEqual(["tens"], inflections.por_lema(ruta, {"ten"})["ten"])

    def test_no_repite_una_clave_que_ya_existe(self):
        """Si `run` ya es clave, agregarla como flexion de otra cosa no aporta nada."""
        ruta = _pack({"jog": ["run", "jogs"]})
        self.rutas.append(ruta)
        self.assertEqual(["jogs"], inflections.por_lema(ruta, {"jog", "run"})["jog"])


if __name__ == "__main__":
    unittest.main()
