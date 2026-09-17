"""Verifica normalize.py contra los vectores compartidos con :dict-core.

El mismo archivo de vectores lo corre el test de Kotlin
(dict-core/src/test/kotlin/.../NormalizationVectorsTest.kt). Es el unico mecanismo que detecta
que las dos implementaciones se separaron.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import normalize  # noqa: E402

VECTORS = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "vectors",
    "normalization-vectors.tsv",
)


def load_vectors(path):
    """Devuelve (numero_de_linea, funcion, perfil, entrada, esperado).

    No se hace strip de los campos, solo del salto de linea: los espacios dentro de un campo
    son parte del caso.
    """
    cases = []
    with open(path, encoding="utf-8") as handle:
        for lineno, raw in enumerate(handle, start=1):
            line = raw.rstrip("\n").rstrip("\r")
            if not line or line.startswith("#"):
                continue
            fields = line.split("\t")
            if len(fields) != 4:
                raise AssertionError(
                    "linea %d: se esperaban 4 campos separados por tab, hay %d: %r"
                    % (lineno, len(fields), line)
                )
            cases.append((lineno,) + tuple(fields))
    return cases


class NormalizationVectorsTest(unittest.TestCase):
    def test_vectors_file_is_not_empty(self):
        # Un archivo de vectores vacio o no encontrado haria pasar todo lo demas en silencio.
        self.assertGreater(len(load_vectors(VECTORS)), 40)

    def test_all_vectors(self):
        for lineno, func, profile, text, expected in load_vectors(VECTORS):
            with self.subTest(line=lineno, func=func, input=text):
                if func == "norm":
                    self.assertEqual(profile, "-", "norm no usa perfil")
                    actual = normalize.norm(text)
                elif func == "fuzzy":
                    actual = normalize.fuzzy(text, profile)
                else:
                    self.fail("funcion desconocida en linea %d: %r" % (lineno, func))
                self.assertEqual(
                    expected,
                    actual,
                    "linea %d: %s(%r) dio %r, se esperaba %r"
                    % (lineno, func, text, actual, expected),
                )


class NormEdgeCasesTest(unittest.TestCase):
    """Casos que no se pueden expresar en el TSV sin que un editor los arruine."""

    def test_empty_string(self):
        self.assertEqual("", normalize.norm(""))
        self.assertEqual("", normalize.fuzzy("", "es"))

    def test_leading_and_trailing_whitespace_is_dropped(self):
        self.assertEqual("hola mundo", normalize.norm("  hola   mundo  "))

    def test_tabs_and_newlines_are_separators(self):
        self.assertEqual("a b", normalize.norm("a\t\nb"))

    def test_norm_is_idempotent(self):
        # Importante porque fuzzy() llama a norm() y el builder normaliza en varios pasos.
        for text in ["Straße", "İstanbul", "self-made", "Łódź", "COVID-19"]:
            once = normalize.norm(text)
            self.assertEqual(once, normalize.norm(once), "norm no es idempotente en %r" % text)

    def test_unknown_profile_is_rejected(self):
        # El builder debe fallar ruidosamente: un perfil mal escrito produciria un pack
        # con claves fuzzy que el reloj nunca va a generar.
        with self.assertRaises(ValueError):
            normalize.fuzzy("hola", "klingon")


if __name__ == "__main__":
    unittest.main()
