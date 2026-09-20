"""El segundo pack base de español: lexemas de Wikidata (D-139).

No mejora el pack del Wikcionario: es **otro diccionario** que se instala al lado y se consulta
junto con el (D-136). Lo que se comprueba aca es la poda --que sin glosa en español no hay
entrada-- y que la identidad venga de la fuente y no del orden del archivo.
"""

import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from sources import wikidata  # noqa: E402


def _dump(*lexemas):
    handle = tempfile.NamedTemporaryFile(
        mode="w", suffix=".json", encoding="utf-8", delete=False
    )
    with handle:
        handle.write("[\n")
        for lx in lexemas:
            handle.write(json.dumps(lx, ensure_ascii=False) + ",\n")
        handle.write("]\n")
    return handle.name


def _lex(lid, lema, categoria="Q1084", glosas=("una glosa",), formas=(), idioma="Q1321"):
    return {
        "id": lid,
        "language": idioma,
        "lexicalCategory": categoria,
        "lemmas": {"es": {"language": "es", "value": lema}},
        "senses": [{"id": "%s-S%d" % (lid, i),
                    "glosses": ({"es": {"language": "es", "value": g}} if g else {})}
                   for i, g in enumerate(glosas)],
        "forms": [{"id": "%s-F%d" % (lid, i),
                   "representations": {"es": {"language": "es", "value": f}}}
                  for i, f in enumerate(formas)],
    }


class WikidataTest(unittest.TestCase):

    def setUp(self):
        self.paths = []

    def tearDown(self):
        for path in self.paths:
            os.unlink(path)

    def records(self, *lexemas, **kw):
        path = _dump(*lexemas)
        self.paths.append(path)
        return list(wikidata.records(path, **kw))

    def test_un_lexema_con_glosa_en_español_es_una_entrada(self):
        got = self.records(_lex("L1", "berilio",
                                glosas=("elemento químico de número atómico 4",)))
        self.assertEqual(1, len(got))
        self.assertEqual("berilio", got[0].headword)
        self.assertEqual("noun", got[0].part_of_speech)
        self.assertEqual("elemento químico de número atómico 4", got[0].senses[0]["gloss"])

    def test_SIN_glosa_en_español_no_es_una_entrada(self):
        """El 76,4 % del dump. El lexema existe --tiene formas y categoria-- pero no dice nada.

        Es la poda que decide el tamaño del pack: 66.935 lexemas en español, 15.814 utiles.
        """
        got = self.records(_lex("L2", "algo", glosas=(None,)))
        self.assertEqual([], got)

    def test_otro_idioma_no_entra(self):
        got = self.records(_lex("L3", "water", idioma="Q1860"))
        self.assertEqual([], got)

    def test_las_locuciones_se_mapean_a_su_nucleo(self):
        # En un reloj la etiqueta dice que clase de palabra es; "locucion nominal" no cabe.
        got = self.records(_lex("L4", "año nuevo", categoria="Q29888377"),
                           _lex("L5", "hacer coro", categoria="Q10976085"),
                           _lex("L6", "entre tanto", categoria="Q5978303"))
        self.assertEqual(["noun", "verb", "adv"], [r.part_of_speech for r in got])

    def test_un_nombre_propio_se_poda_como_en_la_otra_fuente(self):
        # Q147276 se mapea a "name" justamente para que la politica de D-116/D-134 lo agarre sin
        # que este modulo tenga que saber nada de ella.
        propio = _lex("L7", "Portugal", categoria="Q147276")
        self.assertEqual([], [r.headword for r in self.records(propio)])
        self.assertEqual(["Portugal"],
                         [r.headword for r in self.records(propio, politica="included")])

    def test_los_DOS_homografos_llevan_clave_no_solo_el_segundo(self):
        """Si solo la llevara el segundo, el uid del primero dependeria del orden del dump.

        El dump no viene agrupado por lema, asi que saber que hay homografo obliga a contar en
        una pasada previa. `verify_pack.py` agarro exactamente esta falla.
        """
        got = self.records(_lex("L10", "bajo"), _lex("L11", "bajo"))
        self.assertEqual(["L10", "L11"], [r.sense_key for r in got])

    def test_una_entrada_SIN_homografo_no_lleva_clave(self):
        """⚠️ La convencion tiene que ser la MISMA en todos los packs o `uid` deja de unir.

        El id del lexema es una identidad estable declarada por la fuente --mas de lo que kaikki
        tiene-- y la tentacion es usarlo siempre. No se puede: `uid` existe para unir la misma
        entrada entre packs (D-055), y si este pack mete `L12345` en el hash y el del Wikcionario
        no mete nada, la entrada de "vino" de los dos deja de unir.
        """
        got = self.records(_lex("L20", "berilio"))
        self.assertIsNone(got[0].sense_key)

    def test_las_formas_llevan_al_lema_y_no_incluyen_al_lema(self):
        got = self.records(_lex("L12", "correr", formas=("correr", "corriendo", "corrió")))
        self.assertEqual(("corriendo", "corrió"), got[0].forms)

    def test_una_categoria_desconocida_no_tira_la_entrada(self):
        # Perder una definicion por no reconocer un Q-id seria tirar contenido bueno. Se pierde
        # la etiqueta, que es opcional en el payload, no la entrada.
        got = self.records(_lex("L13", "cosa", categoria="Q99999999"))
        self.assertEqual(1, len(got))
        self.assertIsNone(got[0].part_of_speech)

    def test_una_linea_rota_no_tira_el_build(self):
        path = _dump(_lex("L14", "uno"))
        self.paths.append(path)
        with open(path, "a", encoding="utf-8") as h:
            h.write("{esto no es json,\n")
        self.assertEqual(["uno"], [r.headword for r in wikidata.records(path)])
