"""Tests de la fuente Open English WordNet.

**Son tests de CARACTERIZACION, y es una excepcion declarada a "el test va primero"**
(ver CLAUDE.md). `sources/oewn.py` es un SPIKE: existe para producir un numero --cuanto pesa un
pack de ingles construido desde OEWN-- y no para entrar en produccion. Estos tests no expresan
una expectativa de producto: fijan lo que el parser hace hoy, para que el spike no se pudra en
silencio si alguien toca el pipeline compartido.

El fixture es WN-LMF inline y no un recorte del release, porque lo que hay que comprobar cabe en
veinte lineas: que la glosa salga del Synset --que aparece DESPUES de las LexicalEntry en orden
de documento-- y que el `pos` de una letra se traduzca al vocabulario que usa el resto del repo.
"""

import gzip
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from sources import oewn  # noqa: E402

LEXICON = """<?xml version="1.0" encoding="UTF-8"?>
<LexicalResource>
  <Lexicon id="oewn" language="en" version="2025">
    <LexicalEntry id="oewn-dog-n">
      <Lemma writtenForm="dog" partOfSpeech="n"/>
      <Form writtenForm="dogs"/>
      <Sense id="oewn-dog__1" synset="oewn-01-n"/>
    </LexicalEntry>
    <LexicalEntry id="oewn-hound-n">
      <Lemma writtenForm="hound" partOfSpeech="n"/>
      <Sense id="oewn-hound__1" synset="oewn-01-n"/>
    </LexicalEntry>
    <LexicalEntry id="oewn-run-v">
      <Lemma writtenForm="run" partOfSpeech="v"/>
      <Sense id="oewn-run__1" synset="oewn-02-v"/>
    </LexicalEntry>
    <LexicalEntry id="oewn-pate-n-1">
      <Lemma writtenForm="pate" partOfSpeech="n"/>
      <Sense id="oewn-pate__1" synset="oewn-02-v"/>
    </LexicalEntry>
    <LexicalEntry id="oewn-pate-n-2">
      <Lemma writtenForm="pate" partOfSpeech="n"/>
      <Sense id="oewn-pate__2" synset="oewn-02-v"/>
    </LexicalEntry>
    <Synset id="oewn-01-n" partOfSpeech="n" members="oewn-dog-n oewn-hound-n">
      <Definition>a domesticated carnivorous mammal</Definition>
      <Example>the dog barked all night</Example>
      <Example>a second example that no entra</Example>
    </Synset>
    <Synset id="oewn-02-v" partOfSpeech="v" members="oewn-run-v">
      <Definition>to move fast on foot</Definition>
    </Synset>
  </Lexicon>
</LexicalResource>
"""


class OewnTest(unittest.TestCase):
    def setUp(self):
        handle = tempfile.NamedTemporaryFile(suffix=".xml.gz", delete=False)
        with handle:
            handle.write(gzip.compress(LEXICON.encode("utf-8")))
        self.path = handle.name
        self.todos = list(oewn.records(self.path))
        self.got = {r.headword: r for r in self.todos}

    def tearDown(self):
        os.unlink(self.path)

    def test_la_glosa_sale_del_synset_y_no_del_sense(self):
        # El Synset aparece DESPUES de las LexicalEntry en orden de documento, y por eso la
        # fuente hace dos pasadas. Una sola pasada deja todas las glosas vacias.
        self.assertEqual(
            "a domesticated carnivorous mammal", self.got["dog"].senses[0]["gloss"]
        )

    def test_el_pos_se_traduce_al_vocabulario_del_repo(self):
        # OEWN usa una letra; PalabraDelDia, la UI y el pack de kaikki usan "noun"/"verb".
        # Sin traducir, los dos packs de ingles tendrian vocabularios distintos.
        self.assertEqual("noun", self.got["dog"].part_of_speech)
        self.assertEqual("verb", self.got["run"].part_of_speech)

    def test_los_miembros_del_synset_son_los_sinonimos(self):
        # Lo que OEWN da gratis y el dump ingles de kaikki no puede dar (D-117): sinonimos
        # por acepcion, estructurales, y sin el lema repitiendose a si mismo.
        self.assertEqual(["hound"], self.got["dog"].senses[0]["synonyms"])
        self.assertEqual(["dog"], self.got["hound"].senses[0]["synonyms"])

    def test_se_respeta_el_tope_de_un_ejemplo(self):
        self.assertEqual(["the dog barked all night"], self.got["dog"].senses[0]["examples"])

    def test_las_formas_flexionadas_llegan_al_lema(self):
        self.assertIn("dogs", self.got["dog"].forms)

    def test_dos_homografos_del_mismo_pos_reciben_sense_key(self):
        """OEWN tiene homografos que no distingue mas que el id: "pate" dos veces, sustantivo.

        Sin `sense_key` los dos colapsan al mismo `entry.uid` y el build **aborta** (D-058).
        Que aborte es lo correcto --fundirlos perderia una acepcion-- asi que la fuente tiene
        que entregar la clave, y solo cuando hace falta.
        """
        pates = [r for r in self.todos if r.headword == "pate"]
        self.assertEqual(2, len(pates))
        self.assertEqual({"oewn-pate-n-1", "oewn-pate-n-2"}, {r.sense_key for r in pates})
        self.assertIsNone(self.got["dog"].sense_key, "un lema sin homografo no lleva sense_key")


if __name__ == "__main__":
    unittest.main()
