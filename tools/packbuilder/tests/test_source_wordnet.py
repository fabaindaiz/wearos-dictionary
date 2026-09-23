"""WordNet's thesaurus: synonyms and antonyms grouped by MEANING (D-144).

The wiki's are written by hand and are therefore uneven; a synset **is** a set of synonyms, so the
coverage does not depend on somebody having remembered.

What gets pinned here is the attribution rule --a lemma in several synsets does not know which
sense its synonyms belong to-- and the cleanup of the noise the Spanish MCR brings.
"""

import gzip
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from sources import wordnet  # noqa: E402


def _tab(*filas):
    h = tempfile.NamedTemporaryFile(mode="w", suffix=".tab", encoding="utf-8", delete=False)
    with h:
        h.write("# Multilingual Central Repository\tspa\thttp://x\tCC BY 3.0\n")
        for synset, termino in filas:
            h.write("%s\tspa:lemma\t%s\n" % (synset, termino))
    return h.name


def _lmf(*entradas):
    """Un WN-LMF minimo. `entradas` son (lema, pos, [(synset, [antonimo_id])]) ."""
    partes = ['<?xml version="1.0"?>', "<LexicalResource><Lexicon>"]
    for lema, pos, sentidos in entradas:
        partes.append('<LexicalEntry id="e-%s-%s">' % (lema, pos))
        partes.append('<Lemma writtenForm="%s" partOfSpeech="%s"/>' % (lema, pos))
        for synset, antonimos in sentidos:
            partes.append('<Sense id="s-%s-%s" synset="%s">' % (lema, synset, synset))
            for destino in antonimos:
                partes.append('<SenseRelation relType="antonym" target="%s"/>' % destino)
            partes.append("</Sense>")
        partes.append("</LexicalEntry>")
    partes.append("</Lexicon></LexicalResource>")
    h = tempfile.NamedTemporaryFile(suffix=".xml.gz", delete=False)
    with h:
        h.write(gzip.compress("".join(partes).encode("utf-8")))
    return h.name


class EspanolTest(unittest.TestCase):

    def setUp(self):
        self.paths = []

    def tearDown(self):
        for p in self.paths:
            os.unlink(p)

    def mapa(self, *filas):
        path = _tab(*filas)
        self.paths.append(path)
        return wordnet.spanish(path)

    def test_los_lemas_de_un_synset_son_sinonimos_entre_si(self):
        got = self.mapa(("00001-n", "carruaje"), ("00001-n", "coche"), ("00001-n", "vagón"))
        self.assertEqual(["coche", "vagón"], got[("carruaje", "noun")]["synonyms"])
        self.assertEqual(["carruaje", "vagón"], got[("coche", "noun")]["synonyms"])

    def test_un_lema_en_VARIOS_synsets_no_entra(self):
        """D-117's rule, on the source's side.

        If "banco" is in the bench's synset and in the financial institution's, its synonyms belong
        to different senses and there is no way to know which of ours to glue them to.
        """
        got = self.mapa(("00001-n", "banco"), ("00001-n", "asiento"),
                        ("00002-n", "banco"), ("00002-n", "entidad financiera"))
        self.assertNotIn(("banco", "noun"), got)
        self.assertIn(("asiento", "noun"), got, "los demas del synset siguen sirviendo")

    def test_la_categoria_sale_del_sufijo_del_synset(self):
        got = self.mapa(("00001-a", "apto"), ("00001-a", "capaz"))
        self.assertIn(("apto", "adj"), got)

    def test_el_satelite_es_un_adjetivo(self):
        # "s" is a shade of adjective in WordNet, and on a watch it is not a useful distinction.
        got = self.mapa(("00009-s", "diestro"), ("00009-s", "hábil"))
        self.assertIn(("diestro", "adj"), got)

    def test_los_digitos_no_son_terminos(self):
        """The MCR puts "1" and "2" in the numerals' synset. Measured: 22 candidates."""
        got = self.mapa(("00003-n", "uno"), ("00003-n", "1"), ("00003-n", "unidad"))
        self.assertEqual(["unidad"], got[("uno", "noun")]["synonyms"])

    def test_el_espanol_no_trae_antonimos(self):
        """It is not an oversight: in WordNet antonymy is a relation between SENSES.

        The `.tab` only gives lemmas per synset, which is not enough to know which Spanish sense is
        the opposite of which. Transferring it through the shared synset would be inventing it, and
        a misattributed antonym reads as the opposite of something else (D-126).
        """
        got = self.mapa(("00001-n", "frío"), ("00001-n", "gelidez"))
        self.assertNotIn("antonyms", got[("frío", "noun")])


    def test_UNA_VARIANTE_MORFOLOGICA_NO_ES_UN_SINONIMO(self):
        """The MCR's systematic noise, found by reading the built pack.

        Out came `decolorarse → decolorar`, `alistarse → alistar`, `organismos → organismo`,
        `basicamente → básicamente`: the same lemma with a different ending. That is not
        information, it is the word again, and on a watch it spends the only line there is.

        The rule is **structural and knows no Spanish**: one is a prefix of the other and only a
        short ending changes. Measured over the Spanish thesaurus: it removes 6,318 of 99,292
        candidates (6.4 %) and leaves 1,792 entries with nothing -- entries whose only contribution
        was `X → X-se`.
        """
        got = self.mapa(("00010-v", "descamar"), ("00010-v", "descamarse"))
        self.assertNotIn(("descamar", "verb"), got)
        got = self.mapa(("00011-n", "organismo"), ("00011-n", "organismos"))
        self.assertNotIn(("organismo", "noun"), got)

    def test_una_raiz_CORTA_no_dispara_la_regla(self):
        """"Oct" is a legitimate abbreviation of "October", not a morphological variant.

        Without the length floor, any pair sharing three letters would be lost.
        """
        got = self.mapa(("00012-n", "octubre"), ("00012-n", "oct"))
        self.assertEqual(["oct"], got[("octubre", "noun")]["synonyms"])

    def test_dos_palabras_de_la_misma_familia_SI_entran(self):
        # "ente" and "entidad" share a stem but differ by more than a short ending: they are two
        # words, not the same one twice.
        got = self.mapa(("00013-n", "ente"), ("00013-n", "entidad"))
        self.assertEqual(["entidad"], got[("ente", "noun")]["synonyms"])


class InglesTest(unittest.TestCase):

    def setUp(self):
        self.paths = []

    def tearDown(self):
        for p in self.paths:
            os.unlink(p)

    def mapa(self, *entradas):
        path = _lmf(*entradas)
        self.paths.append(path)
        return wordnet.english(path)

    def test_los_miembros_del_synset_son_sinonimos(self):
        got = self.mapa(("aberrance", "n", [("oewn-1-n", [])]),
                        ("aberrancy", "n", [("oewn-1-n", [])]),
                        ("deviance", "n", [("oewn-1-n", [])]))
        self.assertEqual(["aberrancy", "deviance"], got[("aberrance", "noun")]["synonyms"])

    def test_un_lema_en_varios_synsets_no_recibe_sinonimos(self):
        got = self.mapa(("bank", "n", [("oewn-1-n", []), ("oewn-2-n", [])]),
                        ("shore", "n", [("oewn-1-n", [])]))
        self.assertNotIn("synonyms", got.get(("bank", "noun"), {}))

    def test_el_ANTONIMO_si_entra_aunque_el_lema_sea_polisemico(self):
        """⚠️ The difference that justifies treating the two fields differently.

        Antonymy in WordNet is a relation between **concrete senses**, not between synsets: the
        attribution comes already given by the source, so the "one synset only" restriction does
        not apply -- it would be throwing away well attributed information.
        """
        got = self.mapa(
            ("die", "v", [("oewn-1-v", ["s-be born-oewn-2-v"]), ("oewn-3-v", [])]),
            ("be born", "v", [("oewn-2-v", [])]),
        )
        self.assertEqual(["be born"], got[("die", "verb")]["antonyms"])

    def test_un_antonimo_que_apunta_a_la_nada_no_rompe(self):
        got = self.mapa(("solo", "n", [("oewn-1-n", ["s-fantasma-oewn-9-n"])]))
        self.assertNotIn("antonyms", got.get(("solo", "noun"), {}))

    def test_el_tope_por_acepcion_vale(self):
        entradas = [("x", "n", [("oewn-1-n", [])])]
        entradas += [("h%d" % i, "n", [("oewn-1-n", [])]) for i in range(9)]
        got = self.mapa(*entradas)
        self.assertEqual(wordnet.MAX_POR_ACEPCION, len(got[("x", "noun")]["synonyms"]))
