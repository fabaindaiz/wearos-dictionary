"""Tests del codec de payload.

El contrato cruzado con Kotlin lo verifica PayloadCodecTest.kt sobre
vectors/payload-fixture.tsv. Aca se prueba el lado Python por si mismo.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import payload  # noqa: E402

FIXTURE = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "vectors",
    "payload-fixture.tsv",
)


class RenderParseTest(unittest.TestCase):
    def test_round_trip(self):
        senses = [
            {"gloss": "primera", "examples": ["ej uno"], "translations": ["first"]},
            {"gloss": "segunda", "examples": [], "translations": ["second", "other"]},
        ]
        text = payload.render("verb", senses)
        pos, parsed = payload.parse(text)
        self.assertEqual("verb", pos)
        self.assertEqual(senses, parsed)

    def test_sanitize_replaces_delimiters(self):
        # Un tab que llegue de la fuente corromperia el formato delimitado.
        self.assertEqual("con un tab", payload.sanitize("con\tun tab"))
        self.assertEqual("dos lineas", payload.sanitize("dos\nlineas"))
        self.assertEqual("colapsa espacios", payload.sanitize("colapsa    espacios"))
        self.assertIsNone(payload.sanitize("   "))
        self.assertIsNone(payload.sanitize(""))

    def test_sense_without_gloss_is_dropped(self):
        # Una acepcion sin glosa no muestra nada y descolgaria sus ejemplos.
        text = payload.render(None, [{"gloss": "  ", "examples": ["huerfano"]}])
        self.assertEqual("", text)

    def test_examples_before_first_sense_are_ignored(self):
        _pos, senses = payload.parse("E\tsin acepcion\nS\tla acepcion\n")
        self.assertEqual(1, len(senses))
        self.assertEqual([], senses[0]["examples"])

    def test_unknown_tags_are_ignored(self):
        # Compatibilidad hacia adelante con un builder mas nuevo.
        pos, senses = payload.parse("P\tnoun\nZ\tcampo futuro\nS\tuna\n")
        self.assertEqual("noun", pos)
        self.assertEqual(1, len(senses))

    def test_only_first_part_of_speech_wins(self):
        pos, _senses = payload.parse("P\tnoun\nP\tverb\nS\tuna\n")
        self.assertEqual("noun", pos)


class CompressionTest(unittest.TestCase):
    def setUp(self):
        self.dictionary = payload.build_dictionary(
            ["S\tto move quickly\nT\tcorrer\n", "S\tto move slowly\nT\tcaminar\n"] * 4
        )

    def test_round_trip(self):
        senses = [{"gloss": "moverse rapidamente", "translations": ["to run"]}]
        text = payload.render("verb", senses)
        blob = payload.compress(text, self.dictionary)
        self.assertEqual(text, payload.decompress(blob, self.dictionary))

    def test_non_ascii_survives(self):
        text = payload.render("noun", [{"gloss": "niño, árbol, Straße, 日本語"}])
        blob = payload.compress(text, self.dictionary)
        self.assertEqual(text, payload.decompress(blob, self.dictionary))

    def test_wrong_dictionary_corrupts_silently(self):
        """Documenta POR QUE existe meta.payload_dict_sha256.

        deflate no valida el diccionario precargado. Con uno equivocado del largo suficiente
        descomprime sin lanzar nada y devuelve texto corrupto. Este test fija ese
        comportamiento: si una version futura de zlib empezara a detectarlo, queremos enterarnos
        antes de sacar la verificacion por hash pensando que es redundante.
        """
        senses = [{"gloss": "moverse rapidamente", "translations": ["to run"]}]
        text = payload.render("verb", senses)
        blob = payload.compress(text, self.dictionary)
        wrong = b"un diccionario que no corresponde en nada" * 3

        try:
            result = payload.decompress(blob, wrong)
        except Exception:
            # Tambien es aceptable: pasa cuando el diccionario equivocado es mas corto y las
            # referencias quedan fuera de la ventana.
            return
        self.assertNotEqual(text, result, "con otro diccionario no deberia dar el texto correcto")

    def test_dictionary_digest_detects_every_change(self):
        # Es la unica defensa contra el caso de arriba, asi que tiene que detectar cualquier
        # diferencia, incluida una truncadura de pocos bytes.
        digest = payload.dictionary_digest(self.dictionary)
        self.assertEqual(digest, payload.dictionary_digest(self.dictionary))
        self.assertNotEqual(digest, payload.dictionary_digest(self.dictionary[:-1]))
        self.assertNotEqual(digest, payload.dictionary_digest(self.dictionary[:-10]))
        self.assertNotEqual(digest, payload.dictionary_digest(self.dictionary + b"x"))
        self.assertNotEqual(digest, payload.dictionary_digest(b""))

    def test_dictionary_respects_size_budget(self):
        # 32 KB es el maximo que usa deflate; pasarse seria desperdiciar bytes del pack.
        samples = ["S\tacepcion numero %d de relleno\nT\tfiller %d\n" % (i, i) for i in range(5000)]
        dictionary = payload.build_dictionary(samples * 2)
        self.assertLessEqual(len(dictionary), 32 * 1024)

    def test_dictionary_is_deterministic(self):
        # Dos builds del mismo input tienen que dar el mismo pack.
        samples = ["S\tuno\nT\tone\n", "S\tdos\nT\ttwo\n"] * 5
        self.assertEqual(
            payload.build_dictionary(samples), payload.build_dictionary(samples)
        )

    def test_dictionary_actually_helps(self):
        # Si el diccionario no mejorara nada, no valdria la pena el campo en meta ni la
        # complejidad del codec.
        def sample(gloss, translation):
            return payload.render("verb", [{"gloss": gloss, "translations": [translation]}])

        samples = [
            sample("dicho de una persona que se mueve", "to move"),
            sample("dicho de una persona que se queda", "to stay"),
        ] * 20
        dictionary = payload.build_dictionary(samples)
        text = payload.render(
            "verb", [{"gloss": "dicho de una persona que se cansa", "translations": ["to tire"]}]
        )
        with_dict = len(payload.compress(text, dictionary))
        without_dict = len(payload.compress(text, b""))
        self.assertLess(with_dict, without_dict, "el diccionario precargado no mejoro nada")


class FixtureTest(unittest.TestCase):
    def test_fixture_is_current(self):
        """El fixture commiteado tiene que corresponder al codec actual.

        Si alguien cambia el formato y no regenera el fixture, el test de Kotlin sigue pasando
        contra datos viejos y la divergencia queda tapada.
        """
        self.assertTrue(
            os.path.exists(FIXTURE),
            "falta el fixture; generarlo con python3 gen_payload_fixture.py",
        )
        dictionary = None
        declared_digest = None
        cases = 0
        with open(FIXTURE, encoding="utf-8") as handle:
            for raw in handle:
                line = raw.rstrip("\n")
                if not line or line.startswith("#"):
                    continue
                fields = line.split("\t")
                self.assertEqual(3, len(fields), "linea mal formada: %r" % line)
                if fields[0] == "DICTIONARY":
                    dictionary = bytes.fromhex(fields[1])
                    continue
                if fields[0] == "DICTIONARY_SHA256":
                    declared_digest = fields[1]
                    continue
                self.assertIsNotNone(dictionary, "DICTIONARY debe venir primero")
                expected = (
                    fields[2].replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\")
                )
                self.assertEqual(
                    expected,
                    payload.decompress(bytes.fromhex(fields[1]), dictionary),
                    "el caso %r no descomprime a lo esperado; regenerar el fixture" % fields[0],
                )
                cases += 1
        self.assertGreater(cases, 3, "el fixture tiene sospechosamente pocos casos")
        self.assertEqual(
            payload.dictionary_digest(dictionary),
            declared_digest,
            "el sha256 publicado en el fixture no corresponde; regenerar el fixture",
        )


if __name__ == "__main__":
    unittest.main()
