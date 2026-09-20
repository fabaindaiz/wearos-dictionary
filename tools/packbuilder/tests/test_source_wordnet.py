"""El tesauro de WordNet: sinonimos y antonimos agrupados por SIGNIFICADO (D-144).

Los del wiki se escriben a mano y por eso son desiguales; un synset **es** un conjunto de
sinonimos, asi que la cobertura no depende de que alguien se acordara.

Lo que se fija aca es la regla de atribucion --un lema en varios synsets no sabe de cual acepcion
son sus sinonimos-- y la limpieza del ruido que trae el MCR español.
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
        """La regla de D-117, del lado de la fuente.

        Si "banco" esta en el synset del asiento y en el de la entidad financiera, sus sinonimos
        son de acepciones distintas y no hay forma de saber a cual de las nuestras pegarlos.
        """
        got = self.mapa(("00001-n", "banco"), ("00001-n", "asiento"),
                        ("00002-n", "banco"), ("00002-n", "entidad financiera"))
        self.assertNotIn(("banco", "noun"), got)
        self.assertIn(("asiento", "noun"), got, "los demas del synset siguen sirviendo")

    def test_la_categoria_sale_del_sufijo_del_synset(self):
        got = self.mapa(("00001-a", "apto"), ("00001-a", "capaz"))
        self.assertIn(("apto", "adj"), got)

    def test_el_satelite_es_un_adjetivo(self):
        # "s" es un matiz de adjetivo en WordNet, y en un reloj no es una distincion util.
        got = self.mapa(("00009-s", "diestro"), ("00009-s", "hábil"))
        self.assertIn(("diestro", "adj"), got)

    def test_los_digitos_no_son_terminos(self):
        """El MCR mete "1" y "2" en el synset de los numerales. Medido: 22 candidatos."""
        got = self.mapa(("00003-n", "uno"), ("00003-n", "1"), ("00003-n", "unidad"))
        self.assertEqual(["unidad"], got[("uno", "noun")]["synonyms"])

    def test_el_espanol_no_trae_antonimos(self):
        """No es un olvido: en WordNet la antonimia es una relacion entre ACEPCIONES.

        El `.tab` solo da lemas por synset, que no alcanza para saber que acepcion española es el
        opuesto de cual. Transferirla por el synset compartido seria inventarla, y un antonimo
        mal atribuido se lee como lo contrario de otra cosa (D-126).
        """
        got = self.mapa(("00001-n", "frío"), ("00001-n", "gelidez"))
        self.assertNotIn("antonyms", got[("frío", "noun")])


    def test_UNA_VARIANTE_MORFOLOGICA_NO_ES_UN_SINONIMO(self):
        """El ruido sistematico del MCR, encontrado leyendo el pack construido.

        Salian `decolorarse → decolorar`, `alistarse → alistar`, `organismos → organismo`,
        `basicamente → básicamente`: el mismo lema con otra terminacion. No es informacion, es la
        palabra otra vez, y en un reloj gasta el unico renglon que hay.

        La regla es **estructural y no sabe español**: uno es prefijo del otro y solo cambia una
        terminacion corta. Medido sobre el tesauro español: saca 6.318 de 99.292 candidatos
        (6,4 %) y deja 1.792 entradas sin nada -- entradas cuyo unico aporte era `X → X-se`.
        """
        got = self.mapa(("00010-v", "descamar"), ("00010-v", "descamarse"))
        self.assertNotIn(("descamar", "verb"), got)
        got = self.mapa(("00011-n", "organismo"), ("00011-n", "organismos"))
        self.assertNotIn(("organismo", "noun"), got)

    def test_una_raiz_CORTA_no_dispara_la_regla(self):
        """"Oct" es una abreviatura legitima de "October", no una variante morfologica.

        Sin el piso de largo, cualquier par que comparta tres letras se perderia.
        """
        got = self.mapa(("00012-n", "octubre"), ("00012-n", "oct"))
        self.assertEqual(["oct"], got[("octubre", "noun")]["synonyms"])

    def test_dos_palabras_de_la_misma_familia_SI_entran(self):
        # "ente" y "entidad" comparten raiz pero difieren en mas que una terminacion corta: son
        # dos palabras, no la misma dos veces.
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
        """⚠️ La diferencia que justifica tratar los dos campos distinto.

        La antonimia en WordNet es una relacion entre **acepciones concretas**, no entre synsets:
        la atribucion ya viene dada por la fuente, asi que la restriccion de "un solo synset" no
        corresponde -- seria tirar informacion bien atribuida.
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
