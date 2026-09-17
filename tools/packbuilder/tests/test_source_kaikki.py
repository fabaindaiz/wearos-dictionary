"""Tests de la fuente kaikki: la poda, que es donde se decide el tamano del pack.

El builder ya tiene sus tests. Aca se verifica lo otro: que de un registro de kaikki.org salga
lo que queremos y **nada mas**. Las tres cosas que ninguna invariante del pack agarra:

  - una entrada que en realidad es una forma flexionada ("amigo" como presente de "amigar")
    no es una entrada: es una forma que tiene que llevar a su lema;
  - una glosa vacia no es una acepcion, y un registro sin acepciones usables no es una entrada;
  - dos homografos que comparten word Y pos Y pos_title existen de verdad (leonino) y sin
    sense_key hacen fallar el build.

Los fixtures son registros reales del dump del Wikcionario, recortados a los campos que la
poda mira. Ver docs/formato-pack.md.
"""

import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from sources import kaikki  # noqa: E402


def _jsonl(*records):
    handle = tempfile.NamedTemporaryFile(
        mode="w", suffix=".jsonl", encoding="utf-8", delete=False
    )
    with handle:
        for record in records:
            handle.write(json.dumps(record, ensure_ascii=False) + "\n")
    return handle.name


def _raw(word, pos, senses, **extra):
    record = {"word": word, "pos": pos, "lang_code": "es", "lang": "Español",
              "pos_title": extra.pop("pos_title", pos.title()), "senses": senses}
    record.update(extra)
    return record


def _sense(gloss, **extra):
    sense = {"glosses": [gloss] if gloss else [], "sense_index": "1"}
    sense.update(extra)
    return sense


class PodaTest(unittest.TestCase):
    def setUp(self):
        self.paths = []

    def tearDown(self):
        for path in self.paths:
            os.unlink(path)

    def records(self, *raw):
        path = _jsonl(*raw)
        self.paths.append(path)
        return list(kaikki.records(path))

    def test_la_glosa_y_un_ejemplo_sobreviven(self):
        got = self.records(_raw("casa", "noun", [
            _sense("Edificio para habitar.", examples=[{"text": "La casa de la esquina."}]),
        ]))
        self.assertEqual(len(got), 1)
        self.assertEqual(got[0].headword, "casa")
        self.assertEqual(got[0].senses[0]["gloss"], "Edificio para habitar.")
        self.assertEqual(got[0].senses[0]["examples"], ["La casa de la esquina."])

    def test_lo_que_no_es_definicion_se_descarta(self):
        """Etimologia, sonidos y categorias son la mitad del peso del dump y no se muestran."""
        got = self.records(_raw(
            "casa", "noun", [_sense("Edificio para habitar.")],
            etymology_texts=["Del latín casa."],
            sounds=[{"ipa": "[ˈka.sa]"}],
            categories=[{"name": "ES:Sustantivos"}],
            hyphenations=[{"parts": ["ca", "sa"]}],
        ))
        rendered = json.dumps(got[0].senses, ensure_ascii=False)
        for veneno in ("Del latín casa.", "[ˈka.sa]", "ES:Sustantivos"):
            self.assertNotIn(veneno, rendered)

    def test_el_pack_monolingue_no_lleva_traducciones(self):
        """D-034: en monolingue `trans` duplica lo que fts_def ya indexa mejor."""
        got = self.records(_raw(
            "casa", "noun", [_sense("Edificio para habitar.")],
            translations=[{"word": "house", "lang_code": "en"},
                          {"word": "Haus", "lang_code": "de"}],
        ))
        self.assertEqual(tuple(got[0].translations), ())
        self.assertEqual(tuple(got[0].senses[0].get("translations", ())), ())

    def test_una_pagina_de_forma_flexionada_no_es_una_entrada(self):
        """El 82,33 % del dump son estas paginas. No se muestran: se buscan y caen en el lema."""
        got = self.records(
            _raw("amigar", "verb", [_sense("Hacer amigos a quienes estaban reñidos.")],
                 forms=[{"form": "amigo"}, {"form": "amigas"}]),
            _raw("amigo", "verb", [
                _sense("Primera persona del singular del presente de amigar.",
                       tags=["form-of"], form_of=[{"word": "amigar"}]),
            ], pos_title="Forma verbal"),
        )
        self.assertEqual([r.headword for r in got], ["amigar"])
        self.assertIn("amigo", got[0].forms)

    def test_la_forma_llega_al_lema_aunque_el_lema_no_la_declare(self):
        """Es la razon de que la fuente sea de dos pasadas, y esta medida: el `forms` del lema
        deja **7,66 % de las palabras-forma sin cubrir** (53.708 de 700.959). Cada una es una
        busqueda que no encuentra nada. "palpitaciones" -> "palpitacion" es una de ellas."""
        got = self.records(
            _raw("palpitación", "noun", [_sense("Latido del corazón.")]),
            _raw("palpitaciones", "noun", [
                _sense("Forma del plural de palpitación.",
                       tags=["form-of"], form_of=[{"word": "palpitación"}]),
            ], pos_title="Forma sustantiva"),
        )
        self.assertEqual([r.headword for r in got], ["palpitación"])
        self.assertIn("palpitaciones", got[0].forms)

    def test_una_forma_de_un_lema_que_no_existe_no_inventa_una_entrada(self):
        got = self.records(_raw("huis", "verb", [
            _sense("Segunda persona del plural de huir.",
                   tags=["form-of"], form_of=[{"word": "huir"}]),
        ], pos_title="Forma verbal"))
        self.assertEqual(got, [])

    def test_un_lema_que_ademas_tiene_acepcion_propia_sigue_siendo_entrada(self):
        """"amigo" tambien es sustantivo: la forma verbal no puede borrar el sustantivo."""
        got = self.records(
            _raw("amigar", "verb", [_sense("Hacer amigos a quienes estaban reñidos.")]),
            _raw("amigo", "noun", [_sense("Persona con quien se tiene amistad.")]),
            _raw("amigo", "verb", [
                _sense("Presente de amigar.", tags=["form-of"], form_of=[{"word": "amigar"}]),
            ], pos_title="Forma verbal"),
        )
        self.assertEqual(sorted(r.headword for r in got), ["amigar", "amigo"])

    def test_una_glosa_vacia_no_es_una_acepcion(self):
        got = self.records(_raw("casa", "noun", [
            _sense(""), _sense("Edificio para habitar."),
        ]))
        self.assertEqual([s["gloss"] for s in got[0].senses], ["Edificio para habitar."])

    def test_un_registro_sin_acepciones_usables_no_se_emite(self):
        got = self.records(_raw("casa", "noun", [_sense("")]))
        self.assertEqual(got, [])

    def test_las_formas_se_deduplican_y_excluyen_al_lema(self):
        got = self.records(_raw(
            "japonés", "adj", [_sense("Propio de Japón.")],
            forms=[{"form": "japoneses"}, {"form": "japonesas"},
                   {"form": "japoneses"}, {"form": "japonés"}],
        ))
        self.assertEqual(sorted(got[0].forms), ["japonesas", "japoneses"])

    def test_los_homografos_con_el_mismo_pos_title_se_separan_con_sense_key(self):
        """leonino/adj aparece tres veces, distinguido solo por etimologia. Sin sense_key,
        stable_uid() colisiona y el build falla."""
        got = self.records(
            _raw("leonino", "adj", [_sense("Que concierne al león.")],
                 etymology_texts=["Del latín leoninus."]),
            _raw("leonino", "adj", [_sense("Que concierne a los papas llamados León.")],
                 etymology_texts=["Epónimo: los papas llamados León."]),
            _raw("leonino", "adj", [_sense("Que concierne al poeta Leonius.")],
                 etymology_texts=["Epónimo: el poeta Leonius."]),
        )
        self.assertEqual(len(got), 3)
        self.assertEqual(len({r.sense_key for r in got}), 3)

    def test_un_registro_unico_no_lleva_sense_key(self):
        """sense_key entra en el uid: ponerlo cuando no hace falta lo vuelve inestable."""
        got = self.records(_raw("casa", "noun", [_sense("Edificio para habitar.")]))
        self.assertIsNone(got[0].sense_key)


class IdiomaTest(unittest.TestCase):
    """La poda es la misma para todos los idiomas; lo que cambia es la calibracion del rank.

    Estos tests existen para que eso no se olvide: el dia que alguien meta una heuristica que
    dependa del español, el caso de ingles lo agarra.
    """

    def setUp(self):
        self.paths = []

    def tearDown(self):
        for path in self.paths:
            os.unlink(path)

    def test_la_poda_funciona_igual_sobre_un_dump_de_ingles(self):
        # Los tags de wiktextract estan en ingles y son los mismos en todos los dumps: la
        # deteccion de forma flexionada no depende del idioma del contenido.
        path = _jsonl(
            _raw("run", "verb", [_sense("To move at a fast pace.")],
                 pos_title="Verb", forms=[{"form": "running"}, {"form": "ran"}]),
            _raw("ran", "verb", [
                _sense("simple past of run", tags=["form-of"], form_of=[{"word": "run"}]),
            ], pos_title="Verb"),
        )
        self.paths.append(path)
        got = list(kaikki.records(path, lang="en"))
        self.assertEqual(["run"], [r.headword for r in got])
        self.assertIn("ran", got[0].forms)

    def test_un_idioma_sin_perfil_falla_ruidosamente(self):
        # Silencio aca seria construir un pack con el rank de otro idioma.
        path = _jsonl(_raw("run", "verb", [_sense("To move fast.")]))
        self.paths.append(path)
        with self.assertRaises(KeyError):
            list(kaikki.records(path, lang="klingon"))

    def test_sin_nombres_descarta_los_nombres_propios(self):
        # No es una opcion de producto: existe para poder medir cuanto pesan.
        path = _jsonl(
            _raw("London", "name", [_sense("The capital of England.")]),
            _raw("run", "verb", [_sense("To move at a fast pace.")]),
        )
        self.paths.append(path)
        con = [r.headword for r in kaikki.records(path, lang="en")]
        sin = [r.headword for r in kaikki.records(path, lang="en", sin_nombres=True)]
        self.assertEqual(["London", "run"], sorted(con))
        self.assertEqual(["run"], sin)


class RankTest(unittest.TestCase):
    """rank es un PROXY: el Wikcionario no trae frecuencia de uso. Menor es mas comun."""

    def setUp(self):
        self.paths = []

    def tearDown(self):
        for path in self.paths:
            os.unlink(path)

    def records(self, *raw):
        path = _jsonl(*raw)
        self.paths.append(path)
        return {r.headword: r for r in kaikki.records(path)}

    def test_una_entrada_rica_rankea_mejor_que_una_pobre(self):
        got = self.records(
            _raw("hacer", "verb", [
                _sense("Producir algo.", examples=[{"text": "hizo una casa"}]),
                _sense("Fabricar.", examples=[{"text": "hacer pan"}]),
                _sense("Causar."),
            ], forms=[{"form": "hago"}, {"form": "hizo"}, {"form": "haremos"}]),
            _raw("zurriagazo", "noun", [_sense("Golpe dado con el zurriago.")]),
        )
        self.assertLess(got["hacer"].rank, got["zurriagazo"].rank)

    def test_el_rank_nunca_es_negativo(self):
        """La columna es INTEGER NOT NULL y el indice ordena por ella: un negativo la rompe."""
        got = self.records(_raw("zzz", "noun", [_sense("x")]))
        self.assertGreaterEqual(got["zzz"].rank, 0)


if __name__ == "__main__":
    unittest.main()
