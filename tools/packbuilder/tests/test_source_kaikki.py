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
        self.assertEqual("Lamer con la boca.",
                         self._gloss("Lamer con la boca.^([cita requerida])."))

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




class UltimaPalabraDelDumpTest(unittest.TestCase):
    """La ultima palabra del archivo recibe las mismas opciones que todas las demas.

    ⚠️ **El bug que esto fija ya ocurrio y ningun test lo agarraba.** Los registros se agrupan por
    `word` y el grupo se vacia al ver uno distinto; el **ultimo grupo** sale por una llamada a
    `_emit` **fuera del bucle**. Mientras las opciones se encadenaban a mano, olvidar esa segunda
    llamada hacia que la ultima palabra del dump perdiera ese dato **en silencio** -- sin error,
    sin log, y con el pack entero pasando `verify_pack.py`.

    Ningun test existente podia verlo porque ninguno tenia dos palabras donde la segunda fuera la
    ultima. `Opciones` cerro la puerta; esto fija que siga cerrada.
    """

    def setUp(self):
        self.paths = []

    def tearDown(self):
        for path in self.paths:
            os.unlink(path)

    def test_la_ultima_palabra_recibe_traducciones_igual_que_la_primera(self):
        path = _jsonl(
            _raw("casa", "noun", [_sense("edificacion", sense_index="1")],
                 translations=[{"word": "house", "code": "en", "sense_index": "1"}]),
            _raw("zumo", "noun", [_sense("liquido de una fruta", sense_index="1")],
                 translations=[{"word": "juice", "code": "en", "sense_index": "1"}]),
        )
        self.paths.append(path)
        got = {r.headword: r for r in kaikki.records(path, lang="es", translations_to="en")}
        self.assertEqual(["house"], got["casa"].senses[0]["translations"])
        self.assertEqual(["juice"], got["zumo"].senses[0]["translations"],
                         "la ULTIMA palabra del archivo perdio sus traducciones")

    def test_la_ultima_palabra_recibe_el_prior_de_frecuencia_igual(self):
        path = _jsonl(
            _raw("casa", "noun", [_sense("edificacion")]),
            _raw("zumo", "noun", [_sense("liquido de una fruta")]),
        )
        self.paths.append(path)
        got = {r.headword: r.rank
               for r in kaikki.records(path, lang="es", frequencies={"casa": 5.5, "zumo": 5.5})}
        self.assertEqual(got["casa"], got["zumo"],
                         "la ULTIMA palabra del archivo no uso la señal de frecuencia")
        self.assertLess(got["zumo"], kaikki.FRONTERA_CON_SENAL)


class RankPorFrecuenciaTest(unittest.TestCase):
    """El prior de orden sale de la frecuencia de uso, y la riqueza queda de respaldo.

    ⚠️ **El defecto que cierra, medido**: `rank` correlacionaba **-0,250** con la frecuencia real
    --se esperaria -1-- porque contaba formas flexionadas y un verbo español trae hasta 222. En los
    peldaños sin banda de cobertura (D-142 solo defiende `PREFIX`) eso se veia crudo:
    `house` devolvia `solar, alojar, albergar` y nunca `casa`.

    ⚠️ **Dos bandas disjuntas y no una escala mezclada.** Solo el **17,4 %** de los lemas tiene
    señal de frecuencia; mezclar riqueza y frecuencia en un mismo numero exigiria calibrar cuanta
    riqueza *vale* un punto de Zipf, que es una decision que nadie midio. Con bandas, quien tiene
    señal se ordena por ella y quien no queda debajo **en bloque**, conservando entre pares el
    orden de riqueza de siempre.
    """

    def setUp(self):
        self.paths = []

    def tearDown(self):
        for path in self.paths:
            os.unlink(path)

    def _rank_de(self, palabra, frecuencias=None, formas=None):
        path = _jsonl(_raw(palabra, "noun", [_sense("una glosa cualquiera")],
                           forms=[{"form": f} for f in (formas or [])]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="es", frequencies=frecuencias)))
        return got.rank

    def test_mas_frecuente_rankea_mejor(self):
        comun = self._rank_de("casa", {"casa": 5.5})
        raro = self._rank_de("casa", {"casa": 2.0})
        self.assertLess(comun, raro, "mayor Zipf tiene que dar menor rank")

    def test_lo_que_tiene_señal_le_gana_a_CUALQUIER_cosa_sin_señal(self):
        """La afirmacion central de las dos bandas.

        ⚠️ Sin esto, una entrada riquisima sin señal --un verbo con 80 formas-- seguiria ganandole
        a una palabra comun, que es exactamente el defecto que esto viene a cerrar.
        """
        apenas_comun = self._rank_de("casa", {"casa": 1.0})
        riquisima_sin_señal = self._rank_de("zurriagazo", {}, formas=["z%d" % i for i in range(80)])
        self.assertLess(apenas_comun, riquisima_sin_señal)

    def test_sin_señal_se_conserva_el_orden_de_riqueza_entre_pares(self):
        """No aparecer en 50.000 palabras de subtitulos es evidencia de rareza, pero entre raras
        la riqueza sigue siendo la mejor pista que hay."""
        rica = self._rank_de("zzz", {}, formas=["a", "b", "c", "d"])
        pobre = self._rank_de("zzz", {})
        self.assertLess(rica, pobre)

    def test_sin_mapa_de_frecuencias_nada_cambia(self):
        """Un pack construido sin la lista tiene que salir igual que antes: `oewn` y `wikidata`
        tienen su propia formula y no pasan por aca."""
        self.assertEqual(self._rank_de("zzz", None), self._rank_de("zzz", {}))

    def test_el_castigo_de_nombre_propio_se_suma_ENCIMA(self):
        """⚠️ `verify_pack.py` exige `rank >= 1000` para nombres propios bajo la politica estricta.
        Si la frecuencia se aplicara despues del castigo, `Madrid` --que es frecuente-- entraria
        por debajo de ese piso y el pack fallaria la verificacion."""
        path = _jsonl(_raw("Madrid", "name", [_sense("capital de España")]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="es", politica="included",
                                       frequencies={"madrid": 5.0})))
        self.assertGreaterEqual(got.rank, kaikki.CASTIGO_NOMBRE_PROPIO)

    def test_el_rank_nunca_es_negativo_con_una_frecuencia_enorme(self):
        self.assertGreaterEqual(self._rank_de("de", {"de": 99.0}), 0)


class TraduccionesTest(unittest.TestCase):
    """Las traducciones van a SU acepcion, y lo que no se puede atribuir NO se cuelga de la 1.

    Misma forma que los sinonimos de D-117 --`raw["translations"]` con `sense_index` declarado--
    con dos diferencias que estos tests fijan:

    1. **Hay que filtrar por idioma.** El dump trae la tabla entera: medido sobre el dump español,
       `en` son 34.710 de 281.022 items; el resto es frances, aleman, italiano, neerlandes...
       Sin filtro, una entrada española mostraria su traduccion al polaco.
    2. **El indice puede ser un rango.** Medido: 53,6 % simple, 8,6 % compuesto (`1-2`, `1, 4`) y
       37,7 % sin indice. Los sinonimos son 100 % simples, asi que expandir rangos no los toca.

    El modo de falla que existen para impedir es el de D-117: una traduccion colgada de la
    acepcion equivocada se lee perfectamente plausible y no la agarra `verify_pack.py`.
    """

    def setUp(self):
        self.paths = []

    def tearDown(self):
        for path in self.paths:
            os.unlink(path)

    def test_las_traducciones_van_a_su_acepcion(self):
        path = _jsonl(_raw("vela", "noun", [
            _sense("cilindro de cera que da luz al arder", sense_index="1"),
            _sense("tela que impulsa una embarcacion", sense_index="2"),
        ], translations=[
            {"word": "candle", "code": "en", "sense_index": "1"},
            {"word": "sail", "code": "en", "sense_index": "2"},
        ]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="es", translations_to="en")))
        self.assertEqual(["candle"], got.senses[0]["translations"])
        self.assertEqual(["sail"], got.senses[1]["translations"])

    def test_un_rango_alcanza_las_dos_acepciones(self):
        path = _jsonl(_raw("amante", "noun", [
            _sense("persona que ama", sense_index="1"),
            _sense("companero sexual", sense_index="2"),
        ], translations=[{"word": "lover", "code": "en", "sense_index": "1-2"}]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="es", translations_to="en")))
        self.assertEqual(["lover"], got.senses[0]["translations"])
        self.assertEqual(["lover"], got.senses[1]["translations"])

    def test_una_traduccion_a_otro_idioma_no_entra(self):
        path = _jsonl(_raw("casa", "noun", [_sense("edificacion para vivir", sense_index="1")],
                           translations=[
                               {"word": "house", "code": "en", "sense_index": "1"},
                               {"word": "Haus", "code": "de", "sense_index": "1"},
                               {"word": "maison", "code": "fr", "sense_index": "1"},
                           ]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="es", translations_to="en")))
        self.assertEqual(["house"], got.senses[0]["translations"])

    def test_una_traduccion_sin_indice_no_se_cuelga_de_la_primera(self):
        """La regla de D-117, y la razon por la que existe el modo lista.

        Colgarla de la acepcion 1 acierta a veces y falla otras **sin dejar rastro**. Se descarta
        aca; el lugar honesto para este dato es el canal de nivel de entrada, que todavia no
        existe (roadmap §Naming a sense from another pack).
        """
        path = _jsonl(_raw("banco", "noun", [
            _sense("asiento para varias personas", sense_index="1"),
            _sense("entidad financiera", sense_index="2"),
        ], translations=[{"word": "bank", "code": "en"}]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="es", translations_to="en")))
        self.assertEqual([], got.senses[0]["translations"])
        self.assertEqual([], got.senses[1]["translations"])

    def test_el_dump_ingles_traduce_por_el_canal_de_la_palabra(self):
        """⚠️ Una conclusion anterior era demasiado fuerte y este test la corrige.

        **De caracterizacion**: pasa sin codigo nuevo, porque el canal `W` ya lo resolvia. Se
        escribe igual porque lo que fija --que el ingles SI puede traducir-- contradice lo que
        el changelog de esta misma sesion habia dejado escrito, y sin el la proxima sesion
        volveria a creerle al numero viejo.

        Se habia medido que el dump ingles trae **0 `sense_index` de 9.987** traducciones al
        español y de ahi se concluyo que *«el pack ingles no puede tener traducciones»*. Eso
        valia solo para el canal `T`, que exige atribucion. Con el canal `W` --que existe
        justamente para lo no atribuible-- esas 9.987 si tienen donde vivir, y ademas llenan
        `trans`, que es lo que hace que `perro` encuentre `dog`.
        """
        path = _jsonl({
            "word": "dog", "pos": "noun", "lang_code": "en", "lang": "English",
            "pos_title": "Noun",
            "senses": [{"glosses": ["a domesticated carnivorous mammal"], "sense_index": "1"}],
            "translations": [
                {"word": "perro", "code": "es", "sense": "domesticated animal"},
                {"word": "Hund", "code": "de"},
            ],
        })
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="en", translations_to="es")))
        # Sin indice: no se cuelga de la acepcion, va al canal de la palabra.
        self.assertEqual([], got.senses[0]["translations"])
        self.assertEqual(("perro",), got.word_translations)
        # Y entra al canal de busqueda, que es lo que cierra la direccion inversa.
        self.assertEqual(("perro",), got.translations)

    def test_las_no_atribuidas_van_al_canal_de_la_palabra(self):
        """El 37,7 % del dato, que antes se tiraba por no tener donde vivir."""
        path = _jsonl(_raw("banco", "noun", [
            _sense("asiento para varias personas", sense_index="1"),
            _sense("entidad financiera", sense_index="2"),
        ], translations=[
            {"word": "bench", "code": "en", "sense_index": "1"},
            {"word": "bank", "code": "en"},
        ]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="es", translations_to="en")))
        self.assertEqual(["bench"], got.senses[0]["translations"])
        self.assertEqual([], got.senses[1]["translations"])
        # ⚠️ NO se cuelga de la acepcion 1: vive en el canal de la palabra.
        self.assertEqual(("bank",), got.word_translations)

    def test_el_canal_de_busqueda_lleva_las_dos(self):
        """`translations` alimenta la tabla `trans`, y para buscar da igual la atribucion.

        Esto es lo que hace que un pack MONOLINGUE se pueda buscar en el otro idioma: escribir
        `bank` encuentra `banco` sin que haya un pack bilingue instalado.
        """
        path = _jsonl(_raw("banco", "noun", [_sense("entidad financiera", sense_index="1")],
                           translations=[
                               {"word": "bank", "code": "en", "sense_index": "1"},
                               {"word": "bench", "code": "en"},
                           ]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="es", translations_to="en")))
        self.assertEqual(("bank", "bench"), got.translations)

    def test_lo_que_ya_salio_por_acepcion_no_se_repite_abajo(self):
        """Repetirlo diria que la palabra significa eso *ademas*, y es lo mismo mejor atribuido."""
        path = _jsonl(_raw("casa", "noun", [_sense("edificacion para vivir", sense_index="1")],
                           translations=[
                               {"word": "house", "code": "en", "sense_index": "1"},
                               {"word": "house", "code": "en"},
                           ]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="es", translations_to="en")))
        self.assertEqual(["house"], got.senses[0]["translations"])
        self.assertEqual((), got.word_translations)

    def test_sin_idioma_destino_no_se_emite_ninguna(self):
        """El pack ingles no declara destino, y no tiene que ganar traducciones por accidente."""
        path = _jsonl(_raw("casa", "noun", [_sense("edificacion", sense_index="1")],
                           translations=[{"word": "house", "code": "en", "sense_index": "1"}]))
        self.paths.append(path)
        got = next(iter(kaikki.records(path, lang="es")))
        self.assertEqual([], got.senses[0]["translations"])


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


class CitaEnEspanolTest(unittest.TestCase):
    """El Wikcionario sirve el `ref` con OTRA forma, y por eso el separador vive en el `Perfil`.

    | | ingles | español |
    |---|---|---|
    | forma | `1897, Richard Marsh, The Beetle:` | `Miguel Nicolau. Iniciacion a la Teologia. Pagina 85. 1984.` |
    | orden | año primero | **autor primero, año ultimo** |
    | separador | coma | **punto** |
    | ejemplos con `ref` | 75,5 % | **68,4 %** |
    | `ref` completo | 119 B | **92 B** |

    ⚠️ **Y trae un problema que el ingles no tiene: las INICIALES.** `J. R. R. Tolkien` son cuatro
    campos si se parte por punto a secas, y los dos primeros serian `J` y `R`. Por eso el perfil
    declara **dos** cosas: con que se parte, y si hay que fusionar iniciales.
    """

    def setUp(self):
        self.paths = []

    def tearDown(self):
        for path in self.paths:
            os.unlink(path)

    def _cita(self, ref):
        path = _jsonl(_raw("casa", "noun", [
            _sense("Edificio.", examples=[{"text": "la casa de la esquina", "ref": ref}]),
        ]))
        self.paths.append(path)
        got = list(kaikki.records(path, lang="es"))
        return got[0].senses[0]["examples"][0]

    def test_se_recorta_a_autor_y_obra(self):
        # El `ref` real del dump, medido.
        self.assertEqual(
            {"text": "la casa de la esquina", "ref": "Miguel Nicolau. Iniciación a la Teología"},
            self._cita("Miguel Nicolau. Iniciación a la Teología. Página 85. "
                       "Editorial: I.T. San Ildefonso. 1984. ISBN: 9788439818250."),
        )

    def test_las_INICIALES_no_cuentan_como_campo(self):
        # ⚠️ El caso que obliga a la segunda regla. Sin fusionar, los dos primeros campos de
        # `J. R. R. Tolkien. El Señor de los Anillos.` son `J` y `R`.
        self.assertEqual(
            "J. R. R. Tolkien. El Señor de los Anillos",
            self._cita("J. R. R. Tolkien. El Señor de los Anillos. Página 12. 1954.")["ref"],
        )

    def test_un_ref_que_empieza_con_puntuacion_no_la_arrastra(self):
        # Visto en el dump: hay `ref` con el campo de autor vacio, que empiezan con `. `.
        self.assertEqual(
            "Anónimo. Ordinación dada a la ciudad de Zaragoza",
            self._cita(". Anónimo. Ordinación dada a la ciudad de Zaragoza. Página 3. 1414.")["ref"],
        )

    def test_un_ejemplo_sin_ref_sigue_siendo_una_cadena_pelada(self):
        path = _jsonl(_raw("casa", "noun", [
            _sense("Edificio.", examples=[{"text": "la casa"}]),
        ]))
        self.paths.append(path)
        got = list(kaikki.records(path, lang="es"))
        self.assertEqual(["la casa"], got[0].senses[0]["examples"])

    def test_el_separador_INGLES_no_parte_una_cita_espanola(self):
        # La cita española no tiene comas de nivel superior: si el perfil se equivocara de
        # separador, saldria entera en vez de recortada. Es la degradacion segura, y este caso
        # fija que el perfil español declara el punto.
        cita = self._cita("Emilio Castelar. Discursos politicos y literarios. Página 372. 1861.")
        self.assertEqual("Emilio Castelar. Discursos politicos y literarios", cita["ref"])


class SubindicesDeReferenciaTest(unittest.TestCase):
    """Los subindices de referencia cruzada se sacan de la glosa **solo en español**.

    ⚠️ **La medicion que define el alcance, y sin ella el arreglo "barato" rompe contenido.**
    El roadmap lo describia como *"un `str.translate` en `_gloss()`"*, que es compartido por los
    dos idiomas. Medido sobre los packs de hoy:

    | | con subindice | que son |
    |---|---|---|
    | español | ~1.890 | **referencias cruzadas**: `mudanza₁`, `ejercito₂`, `abdicar₁` (62 de 69) |
    | ingles | ~4.584 | **formulas quimicas**: `C₇H₅NO₃S`, `FeO₂²⁻`, `MnO₂` (23 de 26, y las otras 3 tambien) |

    Aplicarlo a los dos convierte la sacarina en algo que no es una formula, en un lugar donde
    nadie mira. Es la misma leccion de D-121, donde un patron mas ancho habria destruido el
    superindice matematico ingles.

    ⚠️ **Y pierde informacion aun en español**: el subindice dice **que acepcion** de la palabra
    referida. Se acepta a sabiendas -- en un reloj, `ejercito₂` se lee como un error de
    codificacion, y la acepcion exacta no es recuperable desde la ficha de todas formas.
    """

    def setUp(self):
        self.paths = []

    def tearDown(self):
        for path in self.paths:
            os.unlink(path)

    def _glosa(self, texto, lang="es"):
        path = _jsonl(_raw("x", "noun", [_sense(texto)]))
        self.paths.append(path)
        return list(kaikki.records(path, lang=lang))[0].senses[0]["gloss"]

    def test_el_subindice_se_saca_de_la_glosa_espanola(self):
        self.assertEqual("En particular, ejército terrestre.",
                         self._glosa("En particular, ejército₂ terrestre."))

    def test_el_INGLES_conserva_sus_formulas(self):
        # ⚠️ El contra-caso, y es la mitad que define el alcance.
        formula = "A white powder, C₇H₅NO₃S, used as a sweetener."
        self.assertEqual(formula, self._glosa(formula, lang="en"))

    def test_una_glosa_sin_subindices_no_se_toca(self):
        self.assertEqual("Edificio para habitar.", self._glosa("Edificio para habitar."))


class LavadoDeFrecuenciaTest(unittest.TestCase):
    """Una palabra con mayuscula no cobra la frecuencia de su homografo en minuscula.

    ⚠️ **El problema, medido sobre `en-def-wikt.db`:** 6.462 entradas con mayuscula y
    `pos != name` estaban en la banda de frecuencia real `[0,500)`, que tiene 55.903 -- el
    **11,6 %** de la banda "mas frecuente". La causa es que `frequency.key()` baja a minusculas
    --correcto, D-186: en español el acento distingue palabras-- y la lista de OpenSubtitles
    **ya viene toda en minusculas**, asi que `TO` cobra las apariciones de `to`.

    ⚠️ **Se arregla SOLO la clase que tiene regla, y eso fue la decision.** Las 4.246 con
    homografo en minuscula son siglas y formas honorificas --`TO`, `OF`, `IS`, `WE`, `ME`, `HE`,
    `NO`, `ARE`, `BE`, `CAN`-- y ahi la regla es inequivoca: **la frecuencia es del lema en
    minuscula, que ya tiene su propia entrada**. Las otras dos clases --1.333 con hermano
    `pos=name` y 883 sin ninguna de las dos senales-- mezclan `Thomas` con `Christmas`, y no hay
    dato que las separe: el truco de D-137 se midio y **no transfiere al ingles**.
    """

    def setUp(self):
        self.paths = []

    def tearDown(self):
        for path in self.paths:
            os.unlink(path)

    def records(self, *raw, **kw):
        path = _jsonl(*raw)
        self.paths.append(path)
        return {r.headword: r for r in kaikki.records(path, lang="en", **kw)}

    def test_la_sigla_no_cobra_la_frecuencia_de_la_palabra(self):
        got = self.records(
            _raw("to", "prep", [_sense("Indicating direction.")], lang_code="en"),
            _raw("TO", "noun", [_sense("Initialism of time-out.")], lang_code="en"),
            frequencies={"to": 6.0},
        )
        self.assertLess(got["to"].rank, kaikki.FRONTERA_CON_SENAL,
                        "la palabra en minuscula SI tiene senal de frecuencia")
        self.assertGreaterEqual(
            got["TO"].rank, kaikki.FRONTERA_CON_SENAL,
            "la sigla cobro la frecuencia de `to`: sale en la banda de los mas frecuentes")

    def test_sin_homografo_en_minuscula_la_frecuencia_SI_se_cobra(self):
        # ⚠️ El contra-caso, y es la mitad que define el alcance. `Christmas` y `American` son
        # vocabulario ingles frecuente y no tienen homografo en minuscula: tienen que conservar
        # su frecuencia. Negarsela por llevar mayuscula seria el falso positivo que hizo descartar
        # la regla mas amplia.
        got = self.records(
            _raw("Christmas", "noun", [_sense("The feast.")], lang_code="en"),
            frequencies={"christmas": 5.0},
        )
        self.assertLess(got["Christmas"].rank, kaikki.FRONTERA_CON_SENAL)

    def test_el_homografo_tiene_que_ser_una_ENTRADA_y_no_una_pagina_de_forma(self):
        # Una pagina `form-of` no es una entrada del pack: se invierte como forma de su lema
        # (D-065). Si contara, cualquier mayuscula cuya minuscula sea una flexion perderia su
        # frecuencia sin que exista ninguna entrada que la reclame.
        got = self.records(
            _raw("ran", "verb", [
                _sense("simple past of run", tags=["form-of"], form_of=[{"word": "run"}]),
            ], lang_code="en"),
            _raw("RAN", "noun", [_sense("Initialism of regional area network.")], lang_code="en"),
            frequencies={"ran": 5.0},
        )
        self.assertLess(got["RAN"].rank, kaikki.FRONTERA_CON_SENAL)

    def test_una_minuscula_nunca_pierde_su_propia_frecuencia(self):
        got = self.records(
            _raw("run", "verb", [_sense("To move quickly.")], lang_code="en"),
            frequencies={"run": 5.5},
        )
        self.assertLess(got["run"].rank, kaikki.FRONTERA_CON_SENAL)


class CitaDelEjemploTest(unittest.TestCase):
    """Where the quoted example came from, trimmed to what fits on a watch.

    ⚠️ **Why this is worth reading before changing.** Measured over the English dump, **86,5 %**
    of the examples are `type: quotation` -- lines lifted from a published text -- and the pack
    was keeping only `text`. The result on screen is a sentence out of an 1897 novel with nothing
    saying so, which is what sent a real user to Wiktionary by hand to find out.

    The `ref` the source gives averages **119 bytes** and carries publisher, city, OCLC and page.
    Trimmed to its first two top-level fields it averages **31** -- p50 24, p90 50 -- which is
    +1,6 % of the English pack instead of +6,3 %.
    """

    def setUp(self):
        self.paths = []

    def tearDown(self):
        for path in self.paths:
            os.unlink(path)

    def english(self, *raw):
        path = _jsonl(*raw)
        self.paths.append(path)
        return list(kaikki.records(path, lang="en"))

    def _example(self, ref):
        got = self.english(_raw("thomas", "noun", [
            _sense("An infidel or doubter.", examples=[{"text": "prove them Thomases", "ref": ref}]),
        ]))
        return got[0].senses[0]["examples"][0]

    def test_la_cita_se_recorta_a_ano_y_autor(self):
        # El `ref` real de la entrada que disparo todo esto.
        self.assertEqual(
            {"text": "prove them Thomases", "ref": "1897, Richard Marsh"},
            self._example("1897, Richard Marsh, The Beetle:"),
        )

    def test_una_coma_dentro_de_un_titulo_entrecomillado_no_corta(self):
        # ⚠️ El caso que mato la regla ingenua: partir por coma a secas dejaba
        # «2019 June 6, “A gaggle» -- un titular cortado al medio que se lee como un error de
        # datos. Medido, le pasa a 320 citas (1,1 %) y arreglarlo cuesta 1 byte de promedio.
        ref = ("2019 June 6, “A gaggle, a confusion and a conspiracy - bizarre animal "
               "collective group names”, in BBC:")
        self.assertEqual(
            "2019 June 6, “A gaggle, a confusion and a conspiracy - bizarre animal "
            "collective group names”",
            self._example(ref)["ref"],
        )

    def test_los_parentesis_tampoco_se_parten(self):
        ref = "1611, The Holy Bible, […] (King James Version), London: […] Robert Barker:"
        self.assertEqual("1611, The Holy Bible", self._example(ref)["ref"])

    def test_los_corchetes_tampoco_se_parten(self):
        # ⚠️ **Este caso lo encontro LEER el pack construido, no un test.** El `ref` real de
        # `captive` salia como `1850, [Alfred` -- un corchete abierto que nunca cierra, que se
        # lee como dato roto. El Wiktionary usa corchetes para el nombre editorial del autor
        # (`[Alfred, Lord Tennyson]`, `[William Tyndale, transl.]`, `[i.e., Ben Jonson]`) y esa
        # coma es interna. Medido sobre el dump: **369 citas (1,3 %)**, y TODAS salian con el
        # corchete desbalanceado. Cerrarlo cuesta 1 byte de promedio (31 -> 32).
        ref = "1850, [Alfred, Lord Tennyson], In Memoriam A. H. H., London: Edward Moxon:"
        self.assertEqual("1850, [Alfred, Lord Tennyson]", self._example(ref)["ref"])

    def test_un_ref_envuelto_entero_en_corchetes_se_desenvuelve(self):
        # La otra forma que el Wiktionary usa: encerrar la cita ENTERA entre corchetes cuando la
        # fuente es indirecta. El corchete no cierra dentro de los dos primeros campos, asi que
        # la profundidad nunca vuelve a cero y **no se corta nada**: salia el `ref` completo,
        # con el corchete abierto. Medido: 184 citas (0,64 %).
        ref = "[1755 April 15, Samuel Johnson, “Lexico′grapher”, in A Dictionary of the English Language:"
        self.assertEqual("1755 April 15, Samuel Johnson", self._example(ref)["ref"])

    def test_el_desenvoltorio_NO_se_aplica_cuando_el_par_si_cierra(self):
        # ⚠️ **La version ingenua de la regla de arriba --sacar el delimitador inicial siempre--
        # ROMPE estos dos**, y se vio midiendo: `[1877], Anna Sewell` quedaba `1877], Anna
        # Sewell`. Por eso se desenvuelve solo si el corte quedo desbalanceado.
        self.assertEqual("[1877], Anna Sewell",
                         self._example("[1877], Anna Sewell, “A Strike for Liberty”:")["ref"])
        self.assertEqual("(Can we date this quote?), Sir T. Browne",
                         self._example("(Can we date this quote?), Sir T. Browne, (Please provide):")["ref"])

    def test_ninguna_cita_sale_con_un_par_sin_cerrar(self):
        """La propiedad, no el caso: lo que se lee como roto es el par desbalanceado.

        Medido sobre el dump entero con esta regla: **0 de 28.744**.
        """
        for ref in (
            "1850, [Alfred, Lord Tennyson], In Memoriam:",
            "1526, [William Tyndale, transl.], The Newe Testament:",
            "1600 (first performance), Beniamin Ionson [i.e., Ben Jonson], “Cynthias Reuels”:",
            "[1898], J[ohn] Meade Falkner, Moonfleet:",
            "[2018, David Correia, Tyler Wall, Police: A Field Guide, page 263:",
            "[1827, [Richard Cook], “RUMFUSTIAN”, in Oxford Night Caps:",
        ):
            cita = self._example(ref)["ref"]
            for abre, cierra in (("[", "]"), ("(", ")"), ("“", "”")):
                self.assertEqual(cita.count(abre), cita.count(cierra),
                                 "par %s%s desbalanceado en %r" % (abre, cierra, cita))

    def test_los_dos_puntos_del_final_se_caen(self):
        self.assertEqual("2009, Linda D. Wilson", self._example("2009, Linda D. Wilson, “Thomas”:")["ref"])

    def test_un_ref_de_un_solo_campo_entra_entero(self):
        self.assertEqual("BBC News", self._example("BBC News:")["ref"])

    def test_un_ejemplo_sin_ref_queda_como_cadena_pelada(self):
        # La forma canonica: el 24,5 % de los ejemplos del dump no declara fuente, y devolver un
        # dict con `ref: None` obligaria a cada consumidor a distinguir dos formas del mismo
        # caso. Ver `payload._example_parts`.
        got = self.english(_raw("dog", "noun", [
            _sense("A mammal.", examples=[{"text": "the dog barks"}]),
        ]))
        self.assertEqual(["the dog barks"], got[0].senses[0]["examples"])

    def test_un_ref_vacio_es_lo_mismo_que_ninguno(self):
        got = self.english(_raw("dog", "noun", [
            _sense("A mammal.", examples=[{"text": "the dog barks", "ref": "   "}]),
        ]))
        self.assertEqual(["the dog barks"], got[0].senses[0]["examples"])

    def test_cada_idioma_recorta_con_SU_separador(self):
        """⚠️ Lo que este caso protege es que el separador NO sea global.

        Los dos dumps sirven el `ref` con formas distintas --el ingles pone el año primero y
        separa por coma; el Wikcionario pone el autor primero y separa por punto-- asi que un
        separador compartido produce basura en uno de los dos. Vive en el `Perfil`, junto a los
        pesos del rank y por la misma razon que ellos (D-076).

        El español entro despues que el ingles, y este caso es el que fija que los dos convivan.
        """
        path = _jsonl(_raw("casa", "noun", [
            _sense("Edificio.", examples=[{"text": "la casa",
                                           "ref": "Miguel Nicolau. Obra citada. 1984."}]),
        ]))
        self.paths.append(path)
        got = list(kaikki.records(path, lang="es"))
        self.assertEqual(
            [{"text": "la casa", "ref": "Miguel Nicolau. Obra citada"}],
            got[0].senses[0]["examples"],
        )


if __name__ == "__main__":
    unittest.main()


class PartesPrincipalesTest(unittest.TestCase):
    """Que formas llegan a la FICHA. Distinto de `forms`, que alimenta la busqueda."""

    def _forms(self, *items):
        return kaikki._display_forms({"forms": list(items)}, "correr")

    def test_elige_la_simple_y_descarta_la_compuesta(self):
        # ⚠️ **El caso que una sonda descubrio sin cubrir.** La fuente etiqueta `corriendo` y
        # `habiendo corrido` EXACTAMENTE igual --las dos llevan `['impersonal', 'gerund']`-- asi
        # que ninguna etiqueta las separa: lo unico que las distingue es el espacio. Con la
        # compuesta primero en la lista, un selector sin ese filtro se la lleva.
        self.assertEqual(
            (("ger", "corriendo"),),
            self._forms({"form": "habiendo corrido", "tags": ["impersonal", "gerund"]},
                        {"form": "corriendo", "tags": ["impersonal", "gerund"]}),
        )

    def test_impersonal_NO_descalifica(self):
        # ⚠️ Creerlo dejo a `correr` sin ninguna parte principal en la primera version: el
        # español marca asi TODAS sus formas no personales.
        self.assertEqual(
            (("part", "corrido"),),
            self._forms({"form": "corrido", "tags": ["impersonal", "participle"]}),
        )

    def test_el_plural_no_se_lleva_una_forma_verbal(self):
        # `corremos` es `['first-person', 'plural', ...]`: plural, y no el plural de un lema.
        self.assertEqual(
            (("pl", "casas"),),
            self._forms({"form": "corremos", "tags": ["first-person", "plural", "present"]},
                        {"form": "casas", "tags": ["plural"]}),
        )

    def test_el_femenino_singular_no_se_lleva_el_femenino_plural(self):
        self.assertEqual(
            (("fem", "alta"),),
            self._forms({"form": "altas", "tags": ["feminine", "plural"]},
                        {"form": "alta", "tags": ["feminine"]}),
        )

    def test_el_lema_no_es_una_forma_suya(self):
        self.assertEqual((), self._forms({"form": "correr", "tags": ["gerund"]}))

    def test_sin_etiquetas_no_se_adivina_nada(self):
        # Una fuente sin `tags` no permite decir que es cada forma, y una etiqueta inventada es
        # peor que ninguna forma.
        self.assertEqual((), self._forms({"form": "corriendo"}))

    def test_un_femenino_plural_no_es_ninguna_de_las_dos(self):
        # `altas` es plural Y femenino, asi que no es el plural llano (`altos`) ni el femenino
        # singular (`alta`): las dos filas lo prohiben y no sale ninguna. Mostrarlo bajo
        # cualquiera de las dos claves seria una etiqueta equivocada, que es peor que ninguna.
        self.assertEqual((), self._forms({"form": "altas", "tags": ["plural", "feminine"]}))

    def test_una_forma_no_se_repite_bajo_dos_claves(self):
        # `corriendo` califica como gerundio y nada mas; si una forma calificara dos veces, la
        # ficha la mostraria dos veces con etiquetas distintas.
        salida = self._forms({"form": "corriendo", "tags": ["gerund"]},
                             {"form": "corrido", "tags": ["participle"]})
        self.assertEqual(len({f for _, f in salida}), len(salida))

