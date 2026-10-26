import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from sources import prevalence  # noqa: E402

CABECERA = ("spelling,count_total,percent_total,prevalence_total,count_nts,percent_nts,"
            "prevalence_nts,count_ntl,percent_ntl,prevalence_ntl,freq,zipf\n")


class SpalexTest(unittest.TestCase):
    """SPALEX's `word_info.csv`: the share of speakers who know each word (Aguasvivas et al. 2018).

    The file is not in the repo: it lives with the other dumps and is passed with `--prevalencia`.
    """

    def _csv(self, filas):
        f = tempfile.NamedTemporaryFile("w", suffix=".csv", encoding="utf-8", delete=False)
        with f:
            f.write(CABECERA + filas)
        self.addCleanup(os.unlink, f.name)
        return f.name

    def test_reads_the_percent_known_over_both_samples(self):
        ruta = self._csv("ababol,285,17.89,-0.9,144,22.2,-0.75,141,13.4,-1.08,0.03,1.51\n"
                         "inefable,300,63.0,0.3,150,70,0.5,150,56,0.1,0.5,2.7\n")
        self.assertEqual({"ababol": 17.89, "inefable": 63.0}, prevalence.load(ruta))

    def test_a_row_without_a_number_is_skipped_not_guessed(self):
        # Absence is not 0 % known: a word with no reading has no tier from prevalence at all.
        ruta = self._csv("raro,0,,,0,,,0,,,0,0\n")
        self.assertEqual({}, prevalence.load(ruta))

    def test_the_key_is_the_lowercase_spelling(self):
        ruta = self._csv("Ñandú,300,61.0,0.2,150,60,0.1,150,62,0.3,0.5,2.7\n")
        self.assertEqual({"ñandú": 61.0}, prevalence.load(ruta))


if __name__ == "__main__":
    unittest.main()
