import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import casefold  # noqa: E402
import payload  # noqa: E402


class CaseFoldingTest(unittest.TestCase):
    """The pinned table that implements `toCaseFold()` in both languages.

    ⚠️ **The standard separates two operations that look alike**: `toLowerCase()` is *case
    mapping*, to DISPLAY text, and `toCaseFold()` --rule R4, section 3.13-- is *case folding*, to
    COMPARE it. The sense code compares, so the second is its business.

    `lower()` was being used. Measured over the pinned repertoire, **242 of 133,730** code points
    differ.
    """

    def test_el_ejemplo_del_propio_estandar(self):
        """"Μάϊος" and "ΜΆΪΟΣ" have to match. With `lower()` alone they do NOT: the final sigma
        comes out different, and it is the case Unicode's documentation uses as its example."""
        self.assertEqual(casefold.fold("Μάϊος"), casefold.fold("ΜΆΪΟΣ"))

    def test_la_ese_alemana(self):
        self.assertEqual(casefold.fold("ß"), casefold.fold("ss"))

    def test_lower_NO_alcanza_y_por_eso_existe_la_tabla(self):
        self.assertNotEqual("ß".lower(), "ss".lower())
        self.assertEqual(casefold.fold("ß"), casefold.fold("ss"))

    def test_los_acentos_se_conservan(self):
        """Folding case is not folding the accent: `publico` and `público` are different words."""
        self.assertNotEqual(casefold.fold("publico"), casefold.fold("público"))

    def test_viene_de_la_tabla_y_no_de_str_casefold(self):
        """⚠️ If this side called `str.casefold()` and the other read the table, they would drift
        apart the day Python's version changes **with no error and no log**. Java has no
        `toCaseFold()`, so the table is the only way for both to do the same thing."""
        self.assertGreater(casefold.PAIR_COUNT, 0)
        self.assertEqual("13.0.0", casefold.UNICODE_VERSION,
                         "la tabla tiene que estar fijada a la misma version que el repertorio")

    def test_el_codigo_de_acepcion_usa_el_plegado_estandar(self):
        self.assertEqual(payload.sense_code(1, "ß"), payload.sense_code(1, "ss"))

    def test_el_vector_compartido_con_Kotlin(self):
        """⚠️ The same number is pinned in `PayloadCodecTest.kt`. If the two sides fold
        differently, the links between packs point at nothing with no exception and no log."""
        self.assertEqual("8ec316909e48", payload.sense_code(1, "Casa."))


if __name__ == "__main__":
    unittest.main()
