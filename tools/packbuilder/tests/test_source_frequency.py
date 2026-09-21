import math
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from sources import frequency  # noqa: E402


def _lista(texto):
    handle = tempfile.NamedTemporaryFile(
        mode="w", suffix=".txt", encoding="utf-8", delete=False)
    with handle:
        handle.write(texto)
    return handle.name


class ListaDeFrecuenciasTest(unittest.TestCase):
    """La señal que reemplaza a la riqueza de pagina como prior de orden.

    El defecto que cierra, medido sobre el pack español real: `rank` correlaciona **-0,250** con la
    frecuencia real de uso, donde se esperaria -1, porque cuenta formas flexionadas y un verbo trae
    hasta 222. El sintoma se ve en los peldaños que **no** tienen la banda de cobertura de D-142:
    `house` devolvia `solar, alojar, albergar` y nunca `casa`.
    """

    def setUp(self):
        self.rutas = []

    def tearDown(self):
        for r in self.rutas:
            os.unlink(r)

    def test_lee_palabra_y_cuenta(self):
        ruta = _lista("de 14459520\nque 14421005\ncasa 120000\n")
        self.rutas.append(ruta)
        self.assertEqual({"de": 14459520, "que": 14421005, "casa": 120000},
                         frequency.load(ruta))

    def test_el_acento_NO_se_pliega(self):
        """⚠️ **El defecto que esto cierra se vio en el pack construido, no razonando.**

        La primera version usaba `norm()` como clave, que pliega acentos. En español el acento
        DISTINGUE palabras, asi que una oscura heredaba la frecuencia de su homografo comun:

            háber  (una unidad oscura)  ->  rank  97   <- se llevaba la de `haber`, el verbo
            hábil                       ->  rank 237       (229.602 ocurrencias, puesto 210)
            líbero                      ->  rank 240   <- sumaba `libero` + `liberó`
            liberal                     ->  rank 248

        La clave conserva el acento y sólo pliega mayusculas. Una palabra sin entrada propia en la
        lista cae a la banda sin señal, que es lo correcto: nadie midio su frecuencia.
        """
        ruta = _lista("haber 229602\nhábil 2264\nCASA 120000\n")
        self.rutas.append(ruta)
        mapa = frequency.load(ruta)
        self.assertEqual(229602, mapa["haber"])
        self.assertEqual(2264, mapa["hábil"])
        self.assertNotIn("habil", mapa, "plegar el acento le daria a `háber` la de `haber`")
        self.assertEqual(120000, mapa["casa"], "las mayusculas si se pliegan")

    def test_una_linea_rota_se_ignora_en_vez_de_romper(self):
        """Un archivo de 50.000 lineas bajado de internet: una linea mala no tira el build."""
        ruta = _lista("casa 120000\nsin-cuenta\n\nperro noesunnumero\nsol 90000\n")
        self.rutas.append(ruta)
        self.assertEqual({"casa": 120000, "sol": 90000}, frequency.load(ruta))

    def test_zipf_es_logaritmico(self):
        """⚠️ Sin log, `de` (14.459.520) aplasta todo: la distribucion es de ley de potencias y
        el resto del vocabulario quedaria indistinguible entre si."""
        z = frequency.to_zipf({"comun": 1_000_000, "rara": 1_000})
        self.assertGreater(z["comun"], z["rara"])
        # tres ordenes de magnitud son tres puntos de Zipf, no un factor 1000
        self.assertAlmostEqual(3.0, z["comun"] - z["rara"], places=6)

    def test_zipf_de_una_palabra_por_millon_es_tres(self):
        """La escala fijada: Zipf 3 == una vez por millon. Es la convencion de `wordfreq`."""
        z = frequency.to_zipf({"x": 1, "resto": 999_999})
        self.assertAlmostEqual(3.0, z["x"], places=6)

    def test_la_principal_gana_y_la_otra_rellena(self):
        """Cada lema toma su valor de UNA fuente. Promediar dos escalas distintas --ocurrencias
        contra frases-que-la-contienen-- seria una calibracion que nadie midio."""
        principal = {"casa": 5.0, "sol": 4.0}
        relleno = {"casa": 1.0, "guanaco": 2.5}
        self.assertEqual({"casa": 5.0, "sol": 4.0, "guanaco": 2.5},
                         frequency.combined(principal, relleno))

    def test_sin_relleno_devuelve_la_principal(self):
        self.assertEqual({"casa": 5.0}, frequency.combined({"casa": 5.0}, None))


if __name__ == "__main__":
    unittest.main()
