"""The Spanish prose ceiling's failure names the way out a writer most often needs.

The ceiling counts a line as Spanish when it carries a Spanish marker (an accent, a function word)
and no English one. English prose that wraps a quoted Spanish word onto a line of its own trips it,
and the failure used to name only the ceiling: on 2026-11-06 it fired on four such lines in one
session, each found at the commit.
"""

import os
import sys
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

import audit_dictionary  # noqa: E402


class PorEncimaDelTechoTest(unittest.TestCase):

    def _falla(self):
        report = audit_dictionary.Report()
        with mock.patch.object(audit_dictionary, "TECHO_ESPANOL", (("docs/", 0),)), \
                mock.patch.object(audit_dictionary, "MARKDOWN_REL", ["docs/a.md"]), \
                mock.patch.object(audit_dictionary, "FUENTES", []), \
                mock.patch.object(audit_dictionary, "lineas_en_espanol", lambda ruta: 1):
            audit_dictionary.check_spanish_prose_budget(report)
        self.assertEqual(1, len(report.failures))
        return report.failures[0][1]

    def test_names_the_english_word_a_quoting_line_needs(self):
        detalle = self._falla()
        self.assertIn("English word", detalle)

    def test_names_the_ui_strings_exception(self):
        self.assertIn("UI string", self._falla())


if __name__ == "__main__":
    unittest.main()
