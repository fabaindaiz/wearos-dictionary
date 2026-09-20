"""La SEGUNDA fuente: ejemplos de uso del Wiktionary ingles, seccion Spanish.

No define nada -- sus glosas son traducciones al ingles y eso seria un pack bilingue (D-034).
Lo unico que se le toma son los **ejemplos en español**, para las entradas flacas del pack.

⚠️ **Cuatro filtros, y cada uno sale de una medicion sobre el dump, no de una preferencia.**
Sin ellos el pack sale con notacion de ajedrez y con metatexto en ingles; con ellos entran
"Hablo frances e ingles" y "-¿Iras a la fiesta? -Nel, estara muy aburrida".
"""

import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from sources import enwikt_examples as fuente  # noqa: E402


def _jsonl(*records):
    handle = tempfile.NamedTemporaryFile(
        mode="w", suffix=".jsonl", encoding="utf-8", delete=False
    )
    with handle:
        for record in records:
            handle.write(json.dumps(record, ensure_ascii=False) + "\n")
    return handle.name


def _raw(word, pos, ejemplos, n_acepciones=1, lang_code="es"):
    senses = [{"glosses": ["gloss %d" % i]} for i in range(n_acepciones)]
    senses[0]["examples"] = ejemplos
    return {"word": word, "pos": pos, "lang_code": lang_code, "senses": senses}


def _ej(text, tipo="example", english="an english gloss"):
    item = {"text": text, "type": tipo}
    if english:
        item["english"] = english
    return item


class EjemplosTest(unittest.TestCase):

    def setUp(self):
        self.paths = []

    def tearDown(self):
        for path in self.paths:
            os.unlink(path)

    def mapa(self, *raw):
        path = _jsonl(*raw)
        self.paths.append(path)
        return fuente.examples_by_entry(path)

    def test_un_ejemplo_bueno_entra_con_su_lema_y_su_pos(self):
        got = self.mapa(_raw("gripe", "noun", [_ej("Tengo la gripe.")]))
        self.assertEqual({("gripe", "noun"): ["Tengo la gripe."]}, got)

    def test_con_VARIAS_acepciones_no_entra_nada(self):
        """La regla que impide inventar la atribucion, igual que en D-132.

        Si alla hay cinco acepciones y en nuestro pack hay una, el ejemplo puede estar
        ilustrando una acepcion **que nosotros no tenemos**. Medido: le pasa a "y", cuyo
        ejemplo es "jamon y queso" y alla tiene cinco acepciones.
        """
        got = self.mapa(_raw("y", "conj", [_ej("jamón y queso")], n_acepciones=5))
        self.assertEqual({}, got)

    def test_el_metatexto_en_ingles_no_es_un_ejemplo(self):
        """Los items SIN `type` son notas, no ejemplos. Medido: 191 en el dump.

        "Near-synonym: pedazo", "Coordinate term: ovarios", y un enlace a Wikipedia. Van en
        `examples` igual que los demas y el unico campo que los separa es `type`.
        """
        got = self.mapa(_raw("so", "adv", [{"text": "Near-synonym: pedazo"}]))
        self.assertEqual({}, got)

    def test_una_cita_sin_traduccion_tampoco(self):
        """`english` es lo que confirma que `text` es el español (D-135).

        Sin ese campo `text` puede ser cualquier cosa, y lo es: `A` trae "19. Ac4xd5, Ab7xd5",
        que es notacion de ajedrez de una partida citada.
        """
        got = self.mapa(_raw("A", "noun", [_ej("19. Ac4xd5, Ab7xd5", tipo="quotation",
                                               english=None)]))
        self.assertEqual({}, got)

    def test_una_cita_CON_traduccion_si_entra(self):
        # Una quotation es uso real de un texto publicado: es contenido de diccionario, no ruido.
        got = self.mapa(_raw("Jamaica", "noun",
                             [_ej("Michael Wallace, deportado a Jamaica.", tipo="quotation")]))
        self.assertEqual({("Jamaica", "noun"): ["Michael Wallace, deportado a Jamaica."]}, got)

    def test_un_ejemplo_que_no_entra_en_un_reloj_se_descarta(self):
        largo = "Un permiso para escalar el monte Everest de 8.849 metros cuesta once mil " \
                "dolares y hay que reservarlo con mucha antelacion."
        self.assertGreater(len(largo), fuente.MAX_LARGO)
        got = self.mapa(_raw("permiso", "noun", [_ej(largo), _ej("Pidió permiso.")]))
        self.assertEqual({("permiso", "noun"): ["Pidió permiso."]}, got)

    def test_otro_idioma_de_la_misma_pagina_no_cuenta(self):
        # El dump trae la pagina entera: "gripe" existe tambien en ingles y en frances.
        got = self.mapa(_raw("gripe", "verb", [_ej("He griped about it.")], lang_code="en"))
        self.assertEqual({}, got)

    def test_se_topea_para_no_pagar_una_pantalla_por_entrada(self):
        muchos = [_ej("Ejemplo número %d." % i) for i in range(6)]
        got = self.mapa(_raw("x", "noun", muchos))
        self.assertEqual(fuente.MAX_POR_ENTRADA, len(got[("x", "noun")]))
