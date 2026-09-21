import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import casefold  # noqa: E402
import payload  # noqa: E402


class CaseFoldingTest(unittest.TestCase):
    """La tabla fijada que implementa `toCaseFold()` en los dos lenguajes.

    ⚠️ **El estandar separa dos operaciones que se parecen**: `toLowerCase()` es *case mapping*,
    para MOSTRAR texto, y `toCaseFold()` --regla R4, seccion 3.13-- es *case folding*, para
    COMPARARLO. El codigo de acepcion compara, asi que le toca la segunda.

    Se usaba `lower()`. Medido sobre el repertorio fijado, **242 de 133.730** code points difieren.
    """

    def test_el_ejemplo_del_propio_estandar(self):
        """«Μάϊος» y «ΜΆΪΟΣ» tienen que casar. Con `lower()` solo NO casan: la sigma final queda
        distinta, y es el caso que la documentacion de Unicode usa de ejemplo."""
        self.assertEqual(casefold.fold("Μάϊος"), casefold.fold("ΜΆΪΟΣ"))

    def test_la_ese_alemana(self):
        self.assertEqual(casefold.fold("ß"), casefold.fold("ss"))

    def test_lower_NO_alcanza_y_por_eso_existe_la_tabla(self):
        self.assertNotEqual("ß".lower(), "ss".lower())
        self.assertEqual(casefold.fold("ß"), casefold.fold("ss"))

    def test_los_acentos_se_conservan(self):
        """Plegar la caja no es plegar el acento: `publico` y `público` son palabras distintas."""
        self.assertNotEqual(casefold.fold("publico"), casefold.fold("público"))

    def test_viene_de_la_tabla_y_no_de_str_casefold(self):
        """⚠️ Si este lado llamara a `str.casefold()` y el otro leyera la tabla, se separarian el
        dia que cambie la version de Python **sin error y sin log**. Java no tiene `toCaseFold()`,
        asi que la tabla es la unica forma de que los dos hagan lo mismo."""
        self.assertGreater(casefold.PAIR_COUNT, 0)
        self.assertEqual("13.0.0", casefold.UNICODE_VERSION,
                         "la tabla tiene que estar fijada a la misma version que el repertorio")

    def test_el_codigo_de_acepcion_usa_el_plegado_estandar(self):
        self.assertEqual(payload.sense_code(1, "ß"), payload.sense_code(1, "ss"))

    def test_el_vector_compartido_con_Kotlin(self):
        """⚠️ El mismo numero esta fijado en `PayloadCodecTest.kt`. Si los dos lados pliegan
        distinto, los enlaces entre packs apuntan a la nada sin excepcion y sin log."""
        self.assertEqual("8ec316909e48", payload.sense_code(1, "Casa."))


if __name__ == "__main__":
    unittest.main()
