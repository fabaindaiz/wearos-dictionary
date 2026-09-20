"""La correlacion que dice si dos calibraciones se pueden comparar (D-142).

Es una funcion numerica, asi que se fija con casos cuyo resultado se conoce de antemano: si
alguien la "optimiza" y deja de ser Spearman, el numero que la herramienta imprime deja de
significar lo que su propia documentacion dice.
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
        """Lo que la vuelve util aca: dos packs pueden usar escalas distintas y estar de acuerdo.

        El nuestro va 0..1000 y otro podria ir 0..100, o al reves. Lo unico que importa es si
        coinciden en cual palabra va antes.
        """
        self.assertAlmostEqual(1.0, cc.spearman([1, 2, 3, 4], [10, 200, 3000, 40000]))

    def test_los_empates_no_la_rompen(self):
        # Un pack puede darle el mismo rank a miles de entradas; con rangos sin promediar esto
        # daria un resultado distinto o una division por cero.
        self.assertIsInstance(cc.spearman([1, 1, 1, 2], [5, 5, 5, 9]), float)

    def test_con_menos_de_dos_puntos_no_lanza(self):
        self.assertEqual(0.0, cc.spearman([], []))
        self.assertEqual(0.0, cc.spearman([1], [1]))

    def test_sin_varianza_no_divide_por_cero(self):
        # Todas las entradas con el mismo rank: no hay orden que correlacionar.
        self.assertEqual(0.0, cc.spearman([1, 1, 1], [1, 2, 3]))
