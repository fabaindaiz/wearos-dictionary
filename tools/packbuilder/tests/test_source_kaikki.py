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

    def test_por_defecto_no_salen_los_nombres_propios(self):
        # D-116: apellidos y toponimos no entran a un diccionario de muñeca. El default vive
        # ACA, en la libreria, no en el flag de la CLI: cualquier llamador nuevo lo hereda.
        path = _jsonl(
            _raw("London", "name", [_sense("The capital of England.")]),
            _raw("run", "verb", [_sense("To move at a fast pace.")]),
        )
        self.paths.append(path)
        got = [r.headword for r in kaikki.records(path, lang="en")]
        self.assertEqual(["run"], got)

    def test_un_nombre_propio_con_senal_lexica_se_conserva(self):
        """"January" no es "Ivanivka", y la fuente lo puede distinguir sin mirar el texto.

        Medido: January tiene 69 entre traducciones, descendientes y derivados; February 50;
        Paris 172; Moscow 330. Un apellido (Hopewell) y una aldea (Ivanivka) tienen 0. La
        señal es estructural --son campos de wiktextract-- asi que la poda sigue sin depender
        del idioma (D-076).
        """
        path = _jsonl(_raw("January", "name", [_sense("The first month of the year.")],
                           descendants=[{"word": "w%d" % i} for i in range(6)]))
        self.paths.append(path)
        got = [r.headword for r in kaikki.records(path, lang="en")]
        self.assertEqual(["January"], got)

    def test_un_nombre_propio_sin_senal_lexica_se_va_igual(self):
        # 163.470 de estos en ingles, 32.305 en español. Son el 99 % de los nombres propios.
        path = _jsonl(
            _raw("Ivanivka", "name", [_sense("A village in Cherkasy Oblast, Ukraine.")]),
            _raw("Hopewell", "name", [_sense("A surname.")], derived=[{"word": "uno"}]),
        )
        self.paths.append(path)
        self.assertEqual([], [r.headword for r in kaikki.records(path, lang="en")])

    def test_con_nombres_los_trae_de_vuelta(self):
        # La medicion sigue siendo posible: es lo que produjo el numero de D-116.
        path = _jsonl(
            _raw("London", "name", [_sense("The capital of England.")]),
            _raw("run", "verb", [_sense("To move at a fast pace.")]),
        )
        self.paths.append(path)
        got = [r.headword for r in kaikki.records(path, lang="en", con_nombres=True)]
        self.assertEqual(["London", "run"], sorted(got))


class MarkupEditorialTest(unittest.TestCase):
    """Las etiquetas de mantenimiento del wiki no son parte de la definicion (D-121).

    wiktextract las deja incrustadas en `glosses` y no hay version limpia: `raw_glosses` es
    None en todos los casos medidos. En un reloj, "Pene.^([cita requerida])" gasta media
    pantalla en decirle al lector que un editor del Wikcionario queria una fuente.
    """

    def setUp(self):
        self.paths = []

    def tearDown(self):
        for path in self.paths:
            os.unlink(path)

    def _gloss(self, texto, lang="es"):
        path = _jsonl(_raw("x", "noun", [_sense(texto)]))
        self.paths.append(path)
        return next(iter(kaikki.records(path, lang=lang))).senses[0]["gloss"]

    def test_se_saca_la_etiqueta_de_cita_requerida(self):
        # 671 casos en el dump español.
        self.assertEqual("Pene.", self._gloss("Pene.^([cita requerida])"))

    def test_se_saca_la_de_definicion_imprecisa(self):
        # 103 casos.
        self.assertEqual(
            "Cierta tela usada antiguamente.",
            self._gloss("Cierta tela usada antiguamente.^([definición imprecisa])"),
        )

    def test_el_punto_que_queda_colgando_no_duplica(self):
        # "...los labios.^([cita requerida])." termina en DOS puntos si solo se borra el tag.
        self.assertEqual("Lamer con la boca.", self._gloss("Lamer con la boca.^([cita requerida])."))

    def test_la_notacion_matematica_NO_se_toca(self):
        """El filtro es la forma con CORCHETES, y esto es por que.

        En ingles `^(...)` es superindice matematico: 10^(100), 2^(2/r), e^(iπ). Un filtro
        sobre `^(...)` a secas destruiria contenido real en vez de limpiarlo.
        """
        self.assertEqual("A number, 10^(100).", self._gloss("A number, 10^(100).", lang="en"))
        self.assertEqual("Equal to e^(iπ).", self._gloss("Equal to e^(iπ).", lang="en"))


class SinonimosTest(unittest.TestCase):
    """Los sinonimos van a SU acepcion, y solo en español (D-117).

    El modo de falla que estos tests existen para impedir: un sinonimo atribuido a la acepcion
    equivocada. No lanza, no loguea, no lo agarra `verify_pack.py` -- sale del pack como
    contenido correcto y lo descubre un lector dentro de un año.
    """

    def setUp(self):
        self.paths = []

    def tearDown(self):
        for path in self.paths:
            os.unlink(path)

    def test_los_sinonimos_van_a_su_acepcion(self):
        path = _jsonl(_raw("domingo", "noun", [
            _sense("hombre dominado por su pareja", sense_index="1"),
            _sense("paga semanal de un menor", sense_index="2"),
        ], synonyms=[
            {"word": "pollerudo", "sense_index": "1"},
            {"word": "mesada", "sense_index": "2"},
            {"word": "paga", "sense_index": "2"},
        ]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="es")))
        self.assertEqual(["pollerudo"], got.senses[0]["synonyms"])
        self.assertEqual(["mesada", "paga"], got.senses[1]["synonyms"])

    def test_un_sinonimo_de_una_acepcion_podada_no_se_cuelga_de_otra(self):
        """El test mas importante del cambio.

        `_senses()` descarta la acepcion form-of ANTES de emitir, asi que los ordinales se
        corren. Una implementacion por posicion (`enumerate`) le cuelga "corrido" a la acepcion
        que sobrevive, y el pack sale con un sinonimo que no lo es.
        """
        path = _jsonl(_raw("corrido", "noun", [
            _sense("", sense_index="1", tags=["form-of"], form_of=[{"word": "correr"}]),
            _sense("romance popular mexicano", sense_index="2"),
        ], synonyms=[
            {"word": "NO-DEBE-APARECER", "sense_index": "1"},
            {"word": "balada", "sense_index": "2"},
        ]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="es")))
        self.assertEqual(1, len(got.senses))
        self.assertEqual(["balada"], got.senses[0]["synonyms"])

    def test_un_sinonimo_sin_sense_index_se_descarta(self):
        # Medido: 5 casos en todo el dump. Colgarlo de la primera acepcion seria inventar.
        path = _jsonl(_raw("casa", "noun", [_sense("edificio para habitar", sense_index="1")],
                           synonyms=[{"word": "vivienda"}]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="es")))
        self.assertEqual([], got.senses[0]["synonyms"])

    def test_el_tope_es_cuatro_por_acepcion(self):
        path = _jsonl(_raw("tonto", "adj", [_sense("de poco entendimiento", sense_index="1")],
                           synonyms=[{"word": "s%d" % i, "sense_index": "1"} for i in range(9)]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="es")))
        self.assertEqual(4, len(got.senses[0]["synonyms"]))

    def test_un_sinonimo_igual_al_lema_no_se_emite(self):
        # Mismo criterio que _forms(). En ingles pasa de verdad: "cat" se lista como sinonimo
        # de "cat".
        path = _jsonl(_raw("casa", "noun", [_sense("edificio para habitar", sense_index="1")],
                           synonyms=[{"word": "casa", "sense_index": "1"},
                                     {"word": "vivienda", "sense_index": "1"}]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="es")))
        self.assertEqual(["vivienda"], got.senses[0]["synonyms"])

    def test_el_ingles_no_trae_sinonimos_al_payload(self):
        """Medido: 0 de 43.679 sinonimos del dump ingles traen `sense_index`.

        Traen `_dis1` --un vector de pesos-- y `source: "Thesaurus:*"`, y el ruido es
        estructural: "cat" figura como sinonimo de "cat". Sin esta puerta, la implementacion
        natural es agnostica del idioma y el ingles se lleva 43.679 items de basura.
        """
        path = _jsonl(_raw("cat", "noun", [_sense("a small feline", sense_index="1")],
                           synonyms=[{"word": "feline", "sense_index": "1"}]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="en")))
        self.assertEqual([], got.senses[0]["synonyms"])


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
