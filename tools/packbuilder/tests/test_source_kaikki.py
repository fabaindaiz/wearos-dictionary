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

    def test_la_politica_lexical_only_poda_los_nombres_propios(self):
        # D-116 sigue disponible y sigue haciendo lo que hacia; lo que cambio es que **ya no es
        # el default** (D-141). Se pide por nombre, y es la que produjo los numeros de D-116.
        path = _jsonl(
            _raw("London", "name", [_sense("The capital of England.")]),
            _raw("run", "verb", [_sense("To move at a fast pace.")]),
        )
        self.paths.append(path)
        got = [r.headword for r in kaikki.records(path, lang="en", politica="lexical-only")]
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
        # ⚠️ **Ya no es el default** (D-141). Se pidio explicitamente que ninguna fuente pierda
        # palabras: "quiero que vayan completas antes que tener que decidir que eliminar y que no
        # y hacerlo erroneamente". La poda sigue existiendo y se pide por nombre.
        self.assertEqual(
            [], [r.headword for r in kaikki.records(path, lang="en", politica="lexical-only")])

    def test_por_DEFECTO_no_se_pierde_ninguna_palabra(self):
        """El default es `included`: ninguna fuente pierde entradas (D-141).

        ⚠️ **Lo que vuelve seguro este default es el castigo de rank**, no la esperanza de que no
        molesten. D-116 midio 4.267 casos en ingles donde el toponimo le gana en rank a la palabra
        comun; con `CASTIGO_NOMBRE_PROPIO` el mejor nombre propio queda debajo de la peor palabra
        comun, asi que entran **sin desplazar nada**.
        """
        path = _jsonl(
            _raw("Ivanivka", "name", [_sense("A village in Cherkasy Oblast, Ukraine.")]),
            _raw("run", "verb", [_sense("To move at a fast pace.")]),
        )
        self.paths.append(path)
        got = {r.headword: r.rank for r in kaikki.records(path, lang="en")}
        self.assertEqual({"Ivanivka", "run"}, set(got))
        self.assertGreater(got["Ivanivka"], got["run"],
                           "el nombre propio tiene que entrar DEBAJO de la palabra comun")

    def test_la_politica_included_los_trae_de_vuelta_a_todos(self):
        # La medicion sigue siendo posible: es lo que produjo el numero de D-116.
        path = _jsonl(
            _raw("London", "name", [_sense("The capital of England.")]),
            _raw("run", "verb", [_sense("To move at a fast pace.")]),
        )
        self.paths.append(path)
        got = [r.headword for r in kaikki.records(path, lang="en", politica="included")]
        self.assertEqual(["London", "run"], sorted(got))


class PoliticaDefinitionsOnlyTest(unittest.TestCase):
    """La tercera politica: entra el nombre propio que DEFINE, no el que solo se registra.

    `lexical-only` poda por señal lexica, y eso se lleva puesto a "Fez" y a "Puruándiro" junto con
    los 26.708 apellidos. Medido sobre el pack español con `--con-nombres`: de las 31.549 entradas
    que hoy se descartan, **28.314 solo dicen su categoria** ("Apellido.", "Nombre de pila de
    mujer.") y **3.235 traen una definicion de verdad** -- ciudades, generos taxonomicos, grafias
    anticuadas, el caballo del Cid.

    ⚠️ **El marcador es `categories`, y eso NO es una heuristica sobre el texto.** Un filtro por
    la prosa de la glosa seria un patron en español que no sirve en ingles, justo lo que el punto
    4 del docstring del modulo dice que no se hace. `categories` lo emite wiktextract desde la
    categorizacion del propio wiki: 26.708 acepciones en `ES:Apellidos` y 2.398 en los tres
    `ES:Antropónimos`. La lista vive en el `Perfil`, que ya es la pieza que se calibra por idioma.
    """

    def setUp(self):
        self.paths = []

    def tearDown(self):
        for path in self.paths:
            os.unlink(path)

    def _cats(self, *nombres):
        return [{"name": n} for n in nombres]

    def test_un_apellido_no_entra_aunque_la_politica_sea_permisiva(self):
        path = _jsonl(_raw("Hopewell", "name",
                           [_sense("Apellido.", categories=self._cats("ES:Apellidos"))]))
        self.paths.append(path)
        got = [r.headword for r in kaikki.records(path, lang="es",
                                                  politica="definitions-only")]
        self.assertEqual([], got)

    def test_una_ciudad_QUE_DEFINE_si_entra(self):
        """El caso que la politica existe para rescatar, y que `lexical-only` tira."""
        path = _jsonl(_raw("Puruándiro", "name",
                           [_sense("Ciudad del estado de Michoacán en México.")]))
        self.paths.append(path)
        self.assertEqual(
            [], [r.headword for r in kaikki.records(path, lang="es", politica="lexical-only")],
            "la politica podadora si la tira: no tiene señal lexica")
        self.assertEqual(["Puruándiro"],
                         [r.headword for r in kaikki.records(path, lang="es",
                                                             politica="definitions-only")])

    def test_con_una_acepcion_que_define_alcanza(self):
        # "Estrella" es nombre de pila Y estrella. Podarla por la primera acepcion perderia la
        # segunda, que es vocabulario.
        path = _jsonl(_raw("Estrella", "name", [
            _sense("Nombre de pila de mujer.", categories=self._cats("ES:Antropónimos femeninos")),
            _sense("Cuerpo celeste que brilla con luz propia."),
        ]))
        self.paths.append(path)
        got = [r.headword for r in kaikki.records(path, lang="es", politica="definitions-only")]
        self.assertEqual(["Estrella"], got)

    def test_el_nombre_propio_que_entra_PIERDE_prioridad(self):
        """Pedido asi: no borrar, bajar de prioridad.

        Sin esto la politica empeora la busqueda en vez de mejorarla: es exactamente el efecto
        que D-116 midio en ingles --4.267 casos donde el toponimo le gana en rank a la palabra
        comun-- y volveria por la puerta de atras.
        """
        path = _jsonl(
            _raw("Fez", "name", [_sense("Una de las principales ciudades de Marruecos.")]),
            _raw("fez", "noun", [_sense("Gorro de fieltro rojo, tronco-conico.")]),
        )
        self.paths.append(path)
        por_lema = {r.headword: r.rank
                    for r in kaikki.records(path, lang="es", politica="definitions-only")}
        self.assertGreater(por_lema["Fez"], por_lema["fez"],
                           "el nombre propio tiene que quedar DEBAJO (rank mayor = menos comun)")

    def test_una_politica_desconocida_no_se_traga_en_silencio(self):
        # Un typo en la CLI no puede construir un pack con la politica por defecto y no decirlo:
        # el pack saldria bien y con otro contenido del pedido.
        path = _jsonl(_raw("x", "noun", [_sense("una glosa")]))
        self.paths.append(path)
        with self.assertRaises(ValueError):
            list(kaikki.records(path, lang="es", politica="lo-que-sea"))


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
    """Los sinonimos van a SU acepcion. Esta clase cubre la forma de ARRIBA (D-117).

    Es la que usa el dump español: `raw["synonyms"]` con un `sense_index` declarado. La forma
    anidada, que es la que usa el ingles, vive en `SinonimosAnidadosTest`.

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

    def test_la_forma_de_arriba_sin_sense_index_no_se_cuelga_de_nada(self):
        """Lo que protegia la lista de idiomas, ahora sin la lista.

        Antes habia una puerta por idioma --`IDIOMAS_CON_SINONIMOS = {"es"}`-- y este test
        afirmaba que el ingles no traia ningun sinonimo. **Esa afirmacion era incorrecta**: se
        habia medido solo la forma de arriba. El ingles sirve 338.200 items anidados dentro de
        cada acepcion, que es donde la atribucion es estructural (ver `SinonimosAnidadosTest`).

        Lo que si sigue valiendo es la regla, y no necesita saber de que idioma es el dump: en
        la forma de ARRIBA, un item sin `sense_index` no se puede atribuir a ninguna acepcion y
        se descarta. Medido: 0 de los 43.679 items de arriba del dump ingles lo traen --traen
        `_dis1`, un vector de pesos-- asi que se caen solos, sin puerta.
        """
        path = _jsonl(_raw("cat", "noun", [_sense("a small feline", sense_index="1")],
                           synonyms=[{"word": "feline", "_dis1": "50 50"}]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="en")))
        self.assertEqual([], got.senses[0]["synonyms"])




class SinonimosAnidadosTest(unittest.TestCase):
    """La OTRA forma en que la fuente sirve sinonimos, que es la unica que usa el ingles.

    Medido sobre 120.000 registros de cada dump, ya sin `pos = name`:

        | forma                        | español | ingles |
        |------------------------------|---------|--------|
        | `synonyms` arriba            |  16,5 % |  5,6 % |
        | `synonyms` dentro de `senses`|   0,0 % | 25,8 % |
        | las dos a la vez             |   0,0 % |  0,0 % |

    Los dos dumps usan **una sola forma cada uno y no la misma**, asi que no hay precedencia que
    decidir. Y la anidada **no necesita `sense_index`**: viene dentro de la acepcion, que es
    exactamente la atribucion que D-117 exige. Medido: 0 de 338.200 traen `sense_index`, y no
    hace falta.
    """

    def setUp(self):
        self.paths = []

    def tearDown(self):
        for path in self.paths:
            os.unlink(path)

    def test_el_ingles_saca_sus_sinonimos_de_la_forma_anidada(self):
        path = _jsonl(_raw("dictionary", "noun", [
            _sense("a reference work", sense_index="1",
                   synonyms=[{"word": "lexicon"}, {"word": "wordbook"}]),
            _sense("the vocabulary of a language", sense_index="2",
                   synonyms=[{"word": "vocabulary"}]),
        ]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="en")))
        self.assertEqual(["lexicon", "wordbook"], got.senses[0]["synonyms"])
        self.assertEqual(["vocabulary"], got.senses[1]["synonyms"])

    def test_la_forma_anidada_no_necesita_sense_index(self):
        # La diferencia de fondo con la forma española: aca la atribucion es estructural, no
        # declarada. Exigir `sense_index` tiraria los 338.200 items del dump ingles.
        path = _jsonl(_raw("free", "adj", [
            _sense("unconstrained", synonyms=[{"word": "unfettered"}]),
        ]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="en")))
        self.assertEqual(["unfettered"], got.senses[0]["synonyms"])

    def test_una_acepcion_form_of_se_lleva_sus_anidados(self):
        # Gratis, y es la ventaja estructural sobre la forma española: la acepcion podada se va
        # ENTERA, asi que sus sinonimos no pueden colgarse de otra. No hay ordinal que se corra.
        path = _jsonl(_raw("dogs", "noun", [
            _sense("", sense_index="1", tags=["form-of"], form_of=[{"word": "dog"}],
                   synonyms=[{"word": "NO-DEBE-APARECER"}]),
            _sense("plural of the animal", sense_index="2", synonyms=[{"word": "hounds"}]),
        ]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="en")))
        self.assertEqual(1, len(got.senses))
        self.assertEqual(["hounds"], got.senses[0]["synonyms"])

    def test_el_tope_de_cuatro_y_la_deduplicacion_valen_igual(self):
        path = _jsonl(_raw("big", "adj", [
            _sense("of great size", synonyms=(
                [{"word": "large"}, {"word": "large"}] + [{"word": "s%d" % i} for i in range(9)]
            )),
        ]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="en")))
        self.assertEqual(4, len(got.senses[0]["synonyms"]))
        self.assertEqual(["large", "s0", "s1", "s2"], got.senses[0]["synonyms"])

    def test_un_sinonimo_anidado_igual_al_lema_no_se_emite(self):
        # Medido: 1,9 % de los 338.200 items ingleses. "cat" se lista como sinonimo de "cat".
        path = _jsonl(_raw("cat", "noun", [
            _sense("a small feline", synonyms=[{"word": "cat"}, {"word": "feline"}]),
        ]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="en")))
        self.assertEqual(["feline"], got.senses[0]["synonyms"])

    def test_el_orden_del_dump_se_respeta(self):
        """No se reordena por fuente, y eso se midio antes de decidirlo.

        El 74,5 % de los items ingleses traen `source: "Thesaurus:*"` y aparecen primero, asi que
        parecia que el tope de 4 se quedaria con lo oscuro. **Medido: cambia 91 acepciones de
        4.872 mezcladas (1,9 %)**, y en la muestra el resultado reordenado es PEOR --`craft`
        pasa de `ability, aptitude` a `craftiness, foxiness`--. La hipotesis no sobrevivio.
        """
        path = _jsonl(_raw("craft", "noun", [
            _sense("skill", synonyms=[
                {"word": "technique", "source": "Thesaurus:skill"},
                {"word": "ability"},
            ]),
        ]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="en")))
        self.assertEqual(["technique", "ability"], got.senses[0]["synonyms"])



class AntonimosTest(unittest.TestCase):
    """Los antonimos, en las MISMAS dos formas que los sinonimos (D-126).

    Medido sobre 120.000 registros vivos de cada dump, y el espejo es exacto:

        | forma                         | español | ingles |
        |-------------------------------|---------|--------|
        | `antonyms` arriba con index   |   2,1 % |  0,0 % |
        | `antonyms` dentro de `senses` |   0,0 % |  3,2 % |

    Cobertura mucho menor que los sinonimos --3,2 % contra 25,8 % en ingles-- y por eso el costo
    tambien: 0,80 B por entrada viva.

    **Atribuir mal un antonimo es peor que atribuir mal un sinonimo**: un sinonimo en la acepcion
    equivocada se lee como una eleccion rara, un antonimo se lee como lo contrario de otra cosa.
    """

    def setUp(self):
        self.paths = []

    def tearDown(self):
        for path in self.paths:
            os.unlink(path)

    def test_la_forma_de_arriba_va_a_su_acepcion(self):
        path = _jsonl(_raw("caliente", "adj", [
            _sense("de temperatura alta", sense_index="1"),
            _sense("enojado", sense_index="2"),
        ], antonyms=[
            {"word": "frio", "sense_index": "1"},
            {"word": "calmado", "sense_index": "2"},
        ]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="es")))
        self.assertEqual(["frio"], got.senses[0]["antonyms"])
        self.assertEqual(["calmado"], got.senses[1]["antonyms"])

    def test_la_forma_anidada_va_a_su_acepcion(self):
        path = _jsonl(_raw("hot", "adj", [
            _sense("of high temperature", antonyms=[{"word": "cold"}]),
            _sense("spicy", antonyms=[{"word": "mild"}]),
        ]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="en")))
        self.assertEqual(["cold"], got.senses[0]["antonyms"])
        self.assertEqual(["mild"], got.senses[1]["antonyms"])

    def test_un_antonimo_de_arriba_sin_sense_index_se_descarta(self):
        # Misma regla que los sinonimos: colgarlo de la primera acepcion seria inventar la
        # atribucion, y aca inventarla significa afirmar un opuesto que la fuente no afirmo.
        path = _jsonl(_raw("caliente", "adj", [_sense("de temperatura alta", sense_index="1")],
                           antonyms=[{"word": "frio"}]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="es")))
        self.assertEqual([], got.senses[0]["antonyms"])

    def test_un_antonimo_igual_al_lema_no_se_emite(self):
        path = _jsonl(_raw("fast", "adj", [
            _sense("quick", antonyms=[{"word": "fast"}, {"word": "slow"}]),
        ]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="en")))
        self.assertEqual(["slow"], got.senses[0]["antonyms"])

    def test_el_tope_de_cuatro_vale_igual(self):
        path = _jsonl(_raw("big", "adj", [
            _sense("large", antonyms=[{"word": "a%d" % i} for i in range(9)]),
        ]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="en")))
        self.assertEqual(4, len(got.senses[0]["antonyms"]))

    def test_sinonimos_y_antonimos_conviven_sin_mezclarse(self):
        path = _jsonl(_raw("hot", "adj", [
            _sense("of high temperature",
                   synonyms=[{"word": "warm"}], antonyms=[{"word": "cold"}]),
        ]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="en")))
        self.assertEqual(["warm"], got.senses[0]["synonyms"])
        self.assertEqual(["cold"], got.senses[0]["antonyms"])



class RelacionadasTest(unittest.TestCase):
    """Palabras relacionadas para las entradas FLACAS, y solo donde no hay nada que inventar.

    El 70,4 % del pack español son entradas de **una sola acepcion y sin ejemplo**: 80.744. Son
    las que se sienten vacias, y la fuente tiene algo para ellas que el builder tiraba --
    `hypernyms`, `hyponyms` y `related`.

    ⚠️ **Solo entran si la entrada tiene UNA acepcion, y esa es toda la regla.** La fuente los
    trae a nivel de ENTRADA, no de acepcion; colgarlos de la primera acepcion de una entrada con
    varias seria inventar la atribucion, que es exactamente el error que D-117 existe para
    impedir. Con una sola acepcion no hay a que otra cosa pertenecer.

    Medido sobre 174.395 registros vivos: de las 29.817 flacas, 2.142 ganan algo por esta via
    (7,2 %). Los sinonimos alcanzan a mas --20,3 %-- pero esos ya entraban por D-117.
    """

    def setUp(self):
        self.paths = []

    def tearDown(self):
        for path in self.paths:
            os.unlink(path)

    def test_una_entrada_flaca_gana_sus_relacionadas(self):
        path = _jsonl(_raw("katakana", "noun", [_sense("silabario japones", sense_index="1")],
                           related=[{"word": "hiragana"}]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="es")))
        self.assertEqual(["hiragana"], got.senses[0]["related"])

    def test_hiperonimos_e_hiponimos_entran_por_el_mismo_lado(self):
        path = _jsonl(_raw("catalan", "noun", [_sense("lengua romance", sense_index="1")],
                           hypernyms=[{"word": "lengua"}], hyponyms=[{"word": "valenciano"}]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="es")))
        self.assertEqual(["lengua", "valenciano"], got.senses[0]["related"])

    def test_con_VARIAS_acepciones_no_entra_ninguna(self):
        """EL TEST QUE PAGA LA REGLA.

        La fuente los trae a nivel de entrada. Con dos acepciones no se sabe de cual son, y
        colgarlos de la primera seria contenido incorrecto que parece correcto.
        """
        path = _jsonl(_raw("frances", "noun", [
            _sense("originario de Francia", sense_index="1"),
            _sense("idioma romance", sense_index="2"),
        ], related=[{"word": "galo"}]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="es")))
        self.assertEqual([[], []], [s["related"] for s in got.senses])

    def test_una_relacionada_igual_al_lema_no_se_emite(self):
        path = _jsonl(_raw("be", "noun", [_sense("nombre de la letra b", sense_index="1")],
                           related=[{"word": "be"}, {"word": "be alta"}]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="es")))
        self.assertEqual(["be alta"], got.senses[0]["related"])

    def test_el_tope_de_cuatro_vale_igual(self):
        path = _jsonl(_raw("x", "noun", [_sense("una glosa", sense_index="1")],
                           related=[{"word": "r%d" % i} for i in range(9)]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="es")))
        self.assertEqual(4, len(got.senses[0]["related"]))

    def test_no_duplica_lo_que_ya_es_sinonimo(self):
        """Un sinonimo ya se muestra en su linea; repetirlo abajo gasta una pantalla de reloj."""
        path = _jsonl(_raw("domingo", "noun", [_sense("marido dominado", sense_index="1")],
                           synonyms=[{"word": "pollerudo", "sense_index": "1"}],
                           related=[{"word": "pollerudo"}, {"word": "calzonazos"}]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="es")))
        self.assertEqual(["pollerudo"], got.senses[0]["synonyms"])
        self.assertEqual(["calzonazos"], got.senses[0]["related"])

    def test_las_ANIDADAS_entran_aunque_haya_varias_acepciones(self):
        """La forma del dump INGLES, y la razon por la que la regla de arriba no las alcanza.

        Medido sobre 185.972 registros vivos del dump ingles: 13,8 % traen relacionadas ANIDADAS
        dentro de la acepcion contra 9,6 % a nivel de entrada. Misma asimetria que los sinonimos
        (D-124). Anidadas la atribucion es **estructural** --el item ya vive en su acepcion-- asi
        que exigir una sola acepcion tiraria justamente la forma mas frecuente.
        """
        path = _jsonl(_raw("bank", "noun", [
            dict(_sense("financial institution", sense_index="1"),
                 hypernyms=[{"word": "institution"}]),
            dict(_sense("edge of a river", sense_index="2"),
                 related=[{"word": "riverbank"}]),
        ]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="es")))
        self.assertEqual(["institution"], got.senses[0]["related"])
        self.assertEqual(["riverbank"], got.senses[1]["related"])

    def test_con_una_acepcion_se_suman_las_anidadas_y_las_de_la_entrada(self):
        """Union, no precedencia: el mismo criterio que `_senses` ya aplica a los sinonimos."""
        path = _jsonl(_raw("guanaco", "noun", [
            dict(_sense("mamifero sudamericano", sense_index="1"),
                 related=[{"word": "chulengo"}]),
        ], hypernyms=[{"word": "camelido"}]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="es")))
        self.assertEqual(["chulengo", "camelido"], got.senses[0]["related"])


    def test_el_markup_del_wikcionario_no_es_una_palabra(self):
        """Medido: 1.072 de 267.721 items (0,40 %) no son palabras sino referencias internas.

        Poco en el total y **mucho donde importa**: en una muestra de seis entradas flacas del
        pack ingles salio `abbacy -> abbe, more at abbot § Related terms`, y en una entrada flaca
        esa linea es lo unico que hay debajo de la glosa. Las tres formas medidas:

            "abbot § Related terms"        145 items   una referencia a una seccion
            "Appendix:Months", "mul:12"    927 items   un namespace del wiki, o un codigo
            "more at ..."                    4 items   una frase, no un lema

        Ninguna se puede mostrar ni se puede abrir como entrada: `norm()` no las encuentra.
        """
        path = _jsonl(_raw("abbacy", "noun", [_sense("dignidad de un abad", sense_index="1")],
                           related=[{"word": "abbé"},
                                    {"word": "more at abbot § Related terms"},
                                    {"word": "Appendix:Months"},
                                    {"word": "mul:12"}]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="es")))
        self.assertEqual(["abbé"], got.senses[0]["related"])

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
