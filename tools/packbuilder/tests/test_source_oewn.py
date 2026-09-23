"""Tests of the Open English WordNet source.

**They are CHARACTERIZATION tests, and that is a declared exception to "the test comes first"**
(see CLAUDE.md). `sources/oewn.py` is a SPIKE: it exists to produce a number --how much an English
pack built from OEWN weighs-- and not to go into production. These tests express no product
expectation: they pin what the parser does today, so the spike does not rot in silence if somebody
touches the shared pipeline.

The fixture is inline WN-LMF and not a cut of the release, because what has to be checked fits in
twenty lines: that the gloss come from the Synset --which appears AFTER the LexicalEntry elements
in document order-- and that the one-letter `pos` be translated into the vocabulary the rest of the
repo uses.
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
        # The Synset appears AFTER the LexicalEntry elements in document order, which is why the
        # source makes two passes. A single pass leaves every gloss empty.
        self.assertEqual(
            "a domesticated carnivorous mammal", self.got["dog"].senses[0]["gloss"]
        )

    def test_el_pos_se_traduce_al_vocabulario_del_repo(self):
        # OEWN uses a single letter; PalabraDelDia, the UI and kaikki's pack use "noun"/"verb".
        # Untranslated, the two English packs would have different vocabularies.
        self.assertEqual("noun", self.got["dog"].part_of_speech)
        self.assertEqual("verb", self.got["run"].part_of_speech)

    def test_los_miembros_del_synset_son_los_sinonimos(self):
        # What OEWN gives for free and kaikki's English dump cannot (D-117): per-sense synonyms,
        # structural, and without the lemma repeating itself.
        self.assertEqual(["hound"], self.got["dog"].senses[0]["synonyms"])
        self.assertEqual(["dog"], self.got["hound"].senses[0]["synonyms"])

    def test_se_respeta_el_tope_de_un_ejemplo(self):
        self.assertEqual(["the dog barked all night"], self.got["dog"].senses[0]["examples"])

    def test_las_formas_flexionadas_llegan_al_lema(self):
        self.assertIn("dogs", self.got["dog"].forms)

    def test_dos_homografos_del_mismo_pos_reciben_sense_key(self):
        """OEWN has homographs nothing but the id distinguishes: "pate" twice, as a noun.

        Without a `sense_key` the two collapse onto the same `entry.uid` and the build **aborts**
        (D-058). Aborting is the right thing --fusing them would lose a sense-- so the source has
        to supply the key, and only when it is needed.
        """
        pates = [r for r in self.todos if r.headword == "pate"]
        self.assertEqual(2, len(pates))
        self.assertEqual({"oewn-pate-n-1", "oewn-pate-n-2"}, {r.sense_key for r in pates})
        self.assertIsNone(self.got["dog"].sense_key, "un lema sin homografo no lleva sense_key")


if __name__ == "__main__":
    unittest.main()
