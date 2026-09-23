"""The correlation that says whether two calibrations can be compared (D-142).

It is a numeric function, so it is pinned with cases whose result is known in advance: if somebody
"optimizes" it and it stops being Spearman, the number the tool prints stops meaning what its own
documentation says.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import compare_calibration as cc  # noqa: E402


class SpearmanTest(unittest.TestCase):

    def test_identicos_dan_uno(self):
        self.assertAlmostEqual(1.0, cc.spearman([1, 2, 3, 4], [1, 2, 3, 4]))

    def test_opuestos_dan_menos_uno(self):
        self.assertAlmostEqual(-1.0, cc.spearman([1, 2, 3, 4], [4, 3, 2, 1]))

    def test_es_de_ORDENES_y_no_de_valores(self):
        """What makes it useful here: two packs can use different scales and still agree.

        Ours runs 0..1000 and another might run 0..100, or the other way round. All that matters is
        whether they agree on which word comes first.
        """
        self.assertAlmostEqual(1.0, cc.spearman([1, 2, 3, 4], [10, 200, 3000, 40000]))

    def test_los_empates_no_la_rompen(self):
        # A pack can give thousands of entries the same rank; with unaveraged ranks this would give
        # a different result or a division by zero.
        self.assertIsInstance(cc.spearman([1, 1, 1, 2], [5, 5, 5, 9]), float)

    def test_con_menos_de_dos_puntos_no_lanza(self):
        self.assertEqual(0.0, cc.spearman([], []))
        self.assertEqual(0.0, cc.spearman([1], [1]))

    def test_sin_varianza_no_divide_por_cero(self):
        # Every entry with the same rank: there is no order to correlate.
        self.assertEqual(0.0, cc.spearman([1, 1, 1], [1, 2, 3]))
