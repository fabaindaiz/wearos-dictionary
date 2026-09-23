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
    """The signal that replaces page richness as the ordering prior.

    The defect it closes, measured over the real Spanish pack: `rank` correlates **-0.250** with
    real usage frequency, where -1 would be expected, because it counts inflected forms and a verb
    carries up to 222. The symptom shows on the rungs that do **not** have D-142's coverage band:
    `house` returned `solar, alojar, albergar` and never `casa`.
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
        """⚠️ **The defect this closes was seen in the built pack, not by reasoning.**

        The first version used `norm()` as the key, which folds accents. In Spanish the accent
        DISTINGUISHES words, so an obscure one inherited its common homograph's frequency:

            háber  (an obscure unit)  ->  rank  97   <- it took `haber`'s, the verb
            hábil                     ->  rank 237       (229,602 occurrences, position 210)
            líbero                    ->  rank 240   <- it summed `libero` + `liberó`
            liberal                   ->  rank 248

        The key keeps the accent and only folds capitals. A word with no entry of its own in the
        list falls into the band with no signal, which is right: nobody measured its frequency.
        """
        ruta = _lista("haber 229602\nhábil 2264\nCASA 120000\n")
        self.rutas.append(ruta)
        mapa = frequency.load(ruta)
        self.assertEqual(229602, mapa["haber"])
        self.assertEqual(2264, mapa["hábil"])
        self.assertNotIn("habil", mapa, "plegar el acento le daria a `háber` la de `haber`")
        self.assertEqual(120000, mapa["casa"], "las mayusculas si se pliegan")

    def test_una_linea_rota_se_ignora_en_vez_de_romper(self):
        """A 50,000-line file downloaded from the internet: one bad line does not fell the build."""
        ruta = _lista("casa 120000\nsin-cuenta\n\nperro noesunnumero\nsol 90000\n")
        self.rutas.append(ruta)
        self.assertEqual({"casa": 120000, "sol": 90000}, frequency.load(ruta))

    def test_zipf_es_logaritmico(self):
        """⚠️ Without a logarithm, `de` (14,459,520) crushes everything: the distribution is a
        power law and the rest of the vocabulary would be indistinguishable."""
        z = frequency.to_zipf({"comun": 1_000_000, "rara": 1_000})
        self.assertGreater(z["comun"], z["rara"])
        # tres ordenes de magnitud son tres puntos de Zipf, no un factor 1000
        self.assertAlmostEqual(3.0, z["comun"] - z["rara"], places=6)

    def test_zipf_de_una_palabra_por_millon_es_tres(self):
        """The pinned scale: Zipf 3 == once per million. It is `wordfreq`'s convention."""
        z = frequency.to_zipf({"x": 1, "resto": 999_999})
        self.assertAlmostEqual(3.0, z["x"], places=6)

    def test_la_principal_gana_y_la_otra_rellena(self):
        """Each lemma takes its value from ONE source. Averaging two different scales --occurrences
        against sentences-that-contain-it-- would be a calibration nobody measured."""
        principal = {"casa": 5.0, "sol": 4.0}
        relleno = {"casa": 1.0, "guanaco": 2.5}
        self.assertEqual({"casa": 5.0, "sol": 4.0, "guanaco": 2.5},
                         frequency.combined(principal, relleno))

    def test_sin_relleno_devuelve_la_principal(self):
        self.assertEqual({"casa": 5.0}, frequency.combined({"casa": 5.0}, None))


if __name__ == "__main__":
    unittest.main()


class PorNormTest(unittest.TestCase):
    """⚠️ It exists because of a measurement that came out wrong, not out of completeness.

    Written as a dict comprehension, the last word sharing a key **overwrites** the earlier ones:
    in the English list `a` ended up with 3,942 occurrences instead of 14,484,562, and the symptom
    was not an error but an absurd order -- `didn` heading English's most frequent words.
    """

    def test_las_que_comparten_clave_se_SUMAN(self):
        def norm(p):
            return p.lower().replace("\u00e1", "a")
        self.assertEqual(
            {"a": 111},
            frequency.por_norm({"A": 100, "\u00e1": 10, "a": 1}, norm),
        )

    def test_una_clave_vacia_no_entra(self):
        self.assertEqual({}, frequency.por_norm({"  ": 5}, lambda p: p.strip()))


class CoberturaTest(unittest.TestCase):
    """A tier's metric: what fraction of the corpus's TOKENS it holds inside."""

    FREC = {"de": 100, "casa": 10, "ornitorrinco": 1}

    def test_cubrir_la_palabra_mas_usada_vale_mas_que_cubrir_dos_raras(self):
        # It is the point of measuring tokens and not types: two of three words is 66 % of the
        # TYPES and 9.9 % of the TOKENS. The second is what the user feels.
        self.assertAlmostEqual(90.09, frequency.cobertura({"de"}, self.FREC), places=2)
        self.assertAlmostEqual(9.91, frequency.cobertura({"casa", "ornitorrinco"}, self.FREC), places=2)

    def test_todo_o_nada(self):
        self.assertEqual(100.0, frequency.cobertura(set(self.FREC), self.FREC))
        self.assertEqual(0.0, frequency.cobertura(set(), self.FREC))

    def test_un_corpus_vacio_no_divide_por_cero(self):
        self.assertEqual(0.0, frequency.cobertura({"de"}, {}))
