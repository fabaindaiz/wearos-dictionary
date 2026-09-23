"""Spanish's second base pack: Wikidata lexemes (D-139).

It does not improve the Wiktionary pack: it is **another dictionary** installed alongside and
queried together with it (D-136). What gets checked here is the pruning --that with no Spanish
gloss there is no entry-- and that the identity come from the source and not from the file's order.
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
        """76.4 % of the dump. The lexeme exists --it has forms and a category-- but says nothing.

        It is the pruning that decides the pack's size: 66,935 Spanish lexemes, 15,814 usable.
        """
        got = self.records(_lex("L2", "algo", glosas=(None,)))
        self.assertEqual([], got)

    def test_otro_idioma_no_entra(self):
        got = self.records(_lex("L3", "water", idioma="Q1860"))
        self.assertEqual([], got)

    def test_las_locuciones_se_mapean_a_su_nucleo(self):
        # On a watch the label says what class of word it is; "locución nominal" does not fit.
        got = self.records(_lex("L4", "año nuevo", categoria="Q29888377"),
                           _lex("L5", "hacer coro", categoria="Q10976085"),
                           _lex("L6", "entre tanto", categoria="Q5978303"))
        self.assertEqual(["noun", "verb", "adv"], [r.part_of_speech for r in got])

    def test_un_nombre_propio_entra_por_defecto_y_se_poda_si_se_pide(self):
        # Q147276 maps to "name" precisely so D-116/D-134's policies catch it without this module
        # having to know anything about them. By default it **gets in** (D-141).
        propio = _lex("L7", "Portugal", categoria="Q147276")
        self.assertEqual(["Portugal"], [r.headword for r in self.records(propio)])
        self.assertEqual([], [r.headword for r in self.records(propio, politica="lexical-only")])

    def test_los_DOS_homografos_llevan_clave_no_solo_el_segundo(self):
        """If only the second carried it, the first's uid would depend on the dump's order.

        The dump does not come grouped by lemma, so knowing there is a homograph forces counting in
        a prior pass. `verify_pack.py` caught exactly this failure.
        """
        got = self.records(_lex("L10", "bajo"), _lex("L11", "bajo"))
        self.assertEqual(["L10", "L11"], [r.sense_key for r in got])

    def test_una_entrada_SIN_homografo_no_lleva_clave(self):
        """⚠️ The convention has to be the SAME in every pack or `uid` stops joining.

        The lexeme's id is a stable identity declared by the source --more than kaikki has-- and
        the temptation is to use it always. It cannot be done: `uid` exists to join the same entry
        across packs (D-055), and if this pack puts `L12345` into the hash and Wiktionary's puts
        nothing, the two packs' "vino" entries stop joining.
        """
        got = self.records(_lex("L20", "berilio"))
        self.assertIsNone(got[0].sense_key)

    def test_las_formas_llevan_al_lema_y_no_incluyen_al_lema(self):
        got = self.records(_lex("L12", "correr", formas=("correr", "corriendo", "corrió")))
        self.assertEqual(("corriendo", "corrió"), got[0].forms)

    def test_una_categoria_desconocida_no_tira_la_entrada(self):
        # Losing a definition for not recognizing a Q-id would be throwing away good content. What
        # is lost is the label, which is optional in the payload, not the entry.
        got = self.records(_lex("L13", "cosa", categoria="Q99999999"))
        self.assertEqual(1, len(got))
        self.assertIsNone(got[0].part_of_speech)

    def test_una_linea_rota_no_tira_el_build(self):
        path = _dump(_lex("L14", "uno"))
        self.paths.append(path)
        with open(path, "a", encoding="utf-8") as h:
            h.write("{esto no es json,\n")
        self.assertEqual(["uno"], [r.headword for r in wikidata.records(path)])
