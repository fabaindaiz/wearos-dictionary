"""Tests of tools/build_packs.py: the complete pipeline, in order.

**The plan's shape and not its execution** is tested, the same split as `test_devpack.py`: running
the pipeline needs 4.4 GB of dumps and an hour, and that does not enter the gate. What does enter,
and is what matters, is **the order** -- because skipping it raises no error.
"""

import io
import os
import shutil
import sys
import tempfile
import unittest

sys.path.insert(
    0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
)

import build_packs  # noqa: E402
sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(
    os.path.dirname(os.path.abspath(__file__)))), "tools", "packbuilder"))
import build_pack  # noqa: E402

BUILD_PACK_NOMBRE = "build_pack.py"

BUILD, DIST = build_packs.BUILD, build_packs.DIST

RAIZ = "/datos"


def _pasos(**kw):
    return build_packs.plan(RAIZ, **kw)


def _nombres(pasos):
    return [p["nombre"] for p in pasos]


class RestriccionesDeclaradasTest(unittest.TestCase):
    """⚠️ **The flags that change a pack are DECLARED, and the pipeline is checked against them.**

    A pack built without its flags comes out well formed, passes `verify_pack.py` and is quietly
    worse, so the list used to live in prose -- `tools/CLAUDE.md` §*the flags that are not
    optional*. Prose does not hold: on 2026-10-01 the decision to stop passing
    `--etimologia-hasta` for English was written into that table **and the pipeline went on passing
    it**, because the two live in different files. That is the defect this class exists to make
    impossible.
    """

    def _comando(self, nombre):
        for paso in _pasos():
            if paso["nombre"].startswith(nombre):
                return paso["comando"]
        self.fail("no hay paso %r en el plan" % nombre)

    def test_el_plan_cumple_lo_que_cada_pack_declara(self):
        """The mirror check: every restriction in `PACKS`, verified against the real plan."""
        revisados = 0
        for paso in _pasos():
            comando = paso["comando"]
            if BUILD_PACK_NOMBRE not in comando[1]:
                continue
            clave = comando[2]
            for bandera, (estado, razon) in build_pack.PACKS[clave].get(
                    "restricciones", {}).items():
                revisados += 1
                if estado == build_pack.REQUERIDA:
                    self.assertIn(bandera, comando,
                                  "%s necesita %s: %s" % (clave, bandera, razon))
                else:
                    self.assertNotIn(bandera, comando,
                                     "%s no debe pasar %s: %s" % (clave, bandera, razon))
        # ⚠️ Without this the test passes when NOBODY declares anything, which is how it goes vacuous.
        self.assertGreaterEqual(revisados, 6, "se revisaron %d restricciones" % revisados)

    def _argv(self, clave, *extra):
        return ["build_pack.py", clave, "dump.jsonl", "salida.db"] + list(extra)

    def test_falta_una_requerida_y_el_build_no_arranca(self):
        """⚠️ **It refuses instead of warning, and that is the point.** A pack built without its
        flags is well formed and passes `verify_pack.py`; a warning in a log nobody reads is the
        same as nothing.
        """
        with self.assertRaises(SystemExit) as caja:
            build_pack.verificar_restricciones("en", self._argv("en"))
        self.assertIn("--tesauro", str(caja.exception))
        self.assertIn("30,423", str(caja.exception), "la razon viaja con la queja")

    def test_sobra_una_prohibida_y_el_build_no_arranca(self):
        with self.assertRaises(SystemExit) as caja:
            build_pack.verificar_restricciones(
                "en", self._argv("en", "--tesauro", "x", "--etimologia-hasta", "y"))
        self.assertIn("--etimologia-hasta", str(caja.exception))

    def test_un_piloto_se_salta_las_requeridas_y_NO_las_prohibidas(self):
        """`--sample` exists to look at content before spending the full build. Demanding a
        thesaurus for a 1-in-200 pack would teach the habit of reaching for the escape hatch.
        """
        build_pack.verificar_restricciones("en", self._argv("en", "--sample", "200"))
        with self.assertRaises(SystemExit):
            build_pack.verificar_restricciones(
                "en", self._argv("en", "--sample", "200", "--etimologia-hasta", "y"))

    def test_a_proposito_convierte_la_negativa_en_una_desviacion_QUE_IMPRIME(self):
        """A deviation that leaves no line in the log is indistinguishable from a mistake."""
        salida = io.StringIO()
        desviaciones = build_pack.verificar_restricciones(
            "en", self._argv("en", "--a-proposito", "--tesauro"), salida=salida)
        self.assertEqual(1, len(desviaciones))
        self.assertIn("DELIBERATE DEVIATION", salida.getvalue())
        self.assertIn("--tesauro", salida.getvalue())

    def test_main_LLAMA_a_la_verificacion_y_no_solo_la_define(self):
        """⚠️ **A mutation probe demanded this one: deleting the call from `main` survived.**

        It is the same defect as a flag parsed and thrown away -- the function is covered, the
        wiring is not. The build has to stop on the declaration **before** touching the dump, so
        the failure here is the restriction and not a missing file.
        """
        with self.assertRaises(SystemExit) as caja:
            build_pack.main(["build_pack.py", "en", "no-existe.jsonl", "salida.db"])
        self.assertIn("--tesauro", str(caja.exception))

    def test_lo_que_describe_el_BUILD_no_llega_al_artefacto(self):
        """⚠️ **Adding `restricciones` to `PACKS` leaked it into the pack's `meta`.**

        `metadata = dict(PACKS[lang])` copies the whole declaration, so a dict value reached
        `_write_metadata` and SQLite answered `Error binding parameter 1 - probably unsupported
        type`: no field name, three layers from the cause, and 15 tests red at once. `PACKS` says
        two different things -- what the pack declares about itself, and how it is built -- and
        only the first is the artifact's.
        """
        self.assertIn("restricciones", build_pack.PACKS["en"])
        for clave, valor in build_pack.PACKS["en"].items():
            if clave == "restricciones":
                continue
            self.assertIsInstance(
                valor, str, "meta.%s llegaria al pack siendo %s" % (clave, type(valor).__name__))

    def test_un_pack_sin_restricciones_declaradas_no_estorba(self):
        self.assertEqual([], build_pack.verificar_restricciones("en-core", self._argv("en-core")))

    def test_el_espanol_sigue_llevando_sus_tres(self):
        comando = self._comando("es-full")
        for bandera in ("--frases", "--tesauro", "--sumar"):
            self.assertIn(bandera, comando)


class PlanTest(unittest.TestCase):

    def test_el_INGLES_va_antes_que_el_bilingue(self):
        """⚠️ The assertion that pays for the file.

        `--flexiones` reads an ALREADY BUILT pack of the target language, so the bilingual one has
        to come after English. Skipping it **raises no error**: the pack comes out well formed,
        passes `verify_pack.py`, and is worse in silence -- the reverse coverage drops 8.6 points,
        measured.
        """
        nombres = _nombres(_pasos())
        self.assertLess(
            nombres.index("en-full"), nombres.index("es-en (bilingue)"),
            "el bilingue necesita el ingles ya construido: %s" % nombres,
        )

    def test_el_bilingue_toma_las_flexiones_DEL_INGLES_QUE_ESTE_PLAN_CONSTRUYE(self):
        pasos = _pasos()
        ingles = next(p for p in pasos if p["nombre"] == "en-full")["salida"]
        biling = next(p for p in pasos if p["nombre"].startswith("es-en"))
        self.assertIn("--flexiones", biling["comando"])
        self.assertEqual(
            ingles, biling["comando"][biling["comando"].index("--flexiones") + 1],
            "tiene que apuntar al pack que este mismo plan acaba de construir",
        )

    def test_cada_paso_escribe_DONDE_le_toca(self):
        """The publishable to `dist/`, the intermediate to `build/`, and nothing the other way.

        ⚠️ It is what was seen on the emulator: with everything in one flat directory, `es-def-wd`
        appeared as a **downloadable** pack. It was not. Today the plan has no intermediates --the
        only one there was, nobody consumed, see
        [test_nada_se_construye_para_que_NADIE_lo_consuma]-- so the `build/` half is a guard for
        when there is one again.
        """
        for paso in _pasos():
            destino = BUILD if "intermedio" in paso["nombre"] else DIST
            self.assertIn(os.sep + destino + os.sep, paso["salida"], paso["nombre"])
            if destino == BUILD:
                self.assertNotIn(os.sep + DIST + os.sep, paso["salida"], paso["nombre"])

    def test_un_idioma_cuyo_FULL_ya_cabe_no_genera_main(self):
        """Full Spanish is 73.6 MB, below `main`'s budget (D-215).

        Generating it would give a second pack with the same content.
        """
        nombres = _nombres(_pasos(tamanos={"es": 73.6, "en": 306.8}))
        self.assertNotIn("es-main", nombres, nombres)
        self.assertIn("es-core", nombres)
        self.assertIn("en-main", nombres, "el ingles son 306,8 MB: ahi si hace falta")

    def test_sin_saber_el_tamano_se_planean_LOS_DOS_niveles(self):
        # In a dry run there is no `full` to measure, and coming up short would be worse than
        # planning too much.
        self.assertIn("es-main", _nombres(_pasos()))

    def test_cada_pack_publicable_se_VERIFICA(self):
        """A half-built pack opens with no error and returns fewer words than it holds."""
        for paso in _pasos():
            if "intermedio" in paso["nombre"]:
                continue
            self.assertTrue(paso["verifica"], "%s tendria que verificarse" % paso["nombre"])

    def test_los_niveles_piden_un_RANGO_medido_y_no_un_numero_suelto(self):
        """⚠️ **`--budget-mb` is an estimated ceiling; D-215's requirement is a range.**

        The estimate scales the payloads by the SOURCE pack's ratio, which is not the derived
        one's: asking for 25 MB gave 17.7. The pipeline has to ask for the range, which is the only
        thing met by measuring the file.
        """
        for paso in _pasos():
            if not paso["nombre"].endswith(("-core", "-main")):
                continue
            cmd = paso["comando"]
            self.assertIn("--rango-mb", cmd, paso["nombre"])
            self.assertNotIn("--budget-mb", cmd, paso["nombre"])
            nivel = paso["nombre"].rsplit("-", 1)[1]
            minimo, maximo = build_packs.RANGO[nivel]
            i = cmd.index("--rango-mb")
            self.assertEqual([str(minimo), str(maximo)], cmd[i + 1:i + 3], paso["nombre"])

    def test_los_niveles_derivan_del_FULL_y_no_de_otro_nivel(self):
        """Deriving a `core` from a `main` would make `subset_of` point at the intermediate."""
        for paso in _pasos():
            if not paso["nombre"].endswith(("-core", "-main")):
                continue
            origen = paso["comando"][2]
            self.assertTrue(origen.endswith("-full.db"), "%s deriva de %s" % (paso["nombre"], origen))

    # ---------------------------------------------------------------------------------------
    # The DUMPS: that each step hand its reader a file that reader knows how to read.
    # ---------------------------------------------------------------------------------------
    #
    # ⚠️ **This was missing, and the pipeline was wrong from the day it was written.** The tests
    # above pin the ORDER, which is what "raises no error" when skipped; but the right order over
    # the wrong files builds nothing. Measured on 2026-09-22: `es-wd` was receiving
    # `es_dbnary_ontolex.ttl.bz2` --and `sources/wikidata` does a `json.loads` per line-- and
    # Spanish's `--tesauro` was receiving `es_dbnary_enhancement.ttl.bz2` --and `wordnet.spanish`
    # opens the file as TEXT and splits on tabs--. Both blow up on the first line.
    #
    # That they blow up is good fortune: the failure mode this repo fears is the other one. But the
    # pipeline **was never run end to end** --its own docstring says so-- so nothing had noticed.

    #: Which file each step expects, and why that one and not another.
    DUMPS_ESPERADOS = {
        # kaikki: line-based JSONL, one per wiki page.
        "en-full": ["en.jsonl"],
        "es-full": ["es.jsonl"],
        "es-en (bilingue)": ["es-en-wikt.jsonl"],
    }

    #: Which file each flag expects. The reader is in parentheses.
    DUMPS_POR_BANDERA = {
        # wordnet.english: WN-LMF comprimido. wordnet.spanish: OMW `.tab`, texto plano.
        "--tesauro": ["oewn-2024.xml.gz", "wn-data-spa.tab"],
        # tatoeba: TSV de oraciones.
        "--frases": ["tatoeba-spa.tsv"],
        # frequency: lista `palabra<espacio>cuenta`.
        "--frecuencias": ["freq-en-opensubs.txt", "freq-es-opensubs.txt"],
        # build_core lee un pack ya construido, no un dump.
        "--flexiones": ["en-full.db"],
        # The tier filter reads a built pack too, and it has to be `main`'s: the rule is that
        # `full` carries the origin up to `main`'s vocabulary. A `core` here would take the datum
        # away from two thirds of `main`, and nothing would fail.
        "--etimologia-hasta": ["en-main.db"],
        # ⚠️ `--sumar <pack> <dump>` takes TWO values, and the one that is a path is the second.
        # That is how it escaped the old check, which always looked at index+1 and read `es-wd`.
        "--sumar": ["wikidata-lexemes.json.bz2"],
    }

    #: Which of the flag's values is the path. The default is 1 (the next one).
    RUTA_EN = {"--sumar": 2}

    def test_cada_paso_recibe_el_dump_que_su_lector_sabe_leer(self):
        for paso in _pasos():
            esperados = self.DUMPS_ESPERADOS.get(paso["nombre"])
            if not esperados:
                continue
            # El dump es el segundo argumento posicional de build_pack.py: script, lang, dump.
            posicionales = [a for a in paso["comando"][1:] if not a.startswith("--")]
            dump = os.path.basename(posicionales[2])
            self.assertIn(
                dump, esperados,
                "%s recibe %r; su lector espera %s" % (paso["nombre"], dump, esperados),
            )

    def test_cada_bandera_recibe_el_dump_que_su_lector_sabe_leer(self):
        for paso in _pasos():
            comando = paso["comando"]
            for bandera, esperados in self.DUMPS_POR_BANDERA.items():
                if bandera not in comando:
                    continue
                valor = os.path.basename(
                    comando[comando.index(bandera) + self.RUTA_EN.get(bandera, 1)])
                self.assertIn(
                    valor, esperados,
                    "%s pasa %s %r; ese lector espera %s"
                    % (paso["nombre"], bandera, valor, esperados),
                )

    def test_ninguna_bandera_con_RUTA_queda_fuera_de_la_tabla(self):
        """⚠️ **A table-driven check works while the table is complete, and it was not.**

        It is the third time a step hands a reader a file it cannot read: first `es-wd` with a
        Turtle, then `--tesauro` with another, and now `--sumar` with a built `.db` when its reader
        opens the raw dump. The first two were pinned by a table; the third escaped because **the
        flag was not in the table**.

        So exhaustiveness gets pinned too: any flag that receives a path has to be declared. A new
        flag with no row makes this fail, not the rebuild.
        """
        for paso in _pasos():
            comando = paso["comando"]
            for i, argumento in enumerate(comando):
                if not argumento.startswith("--"):
                    continue
                siguientes = comando[i + 1:i + 3]
                if not any(os.sep in v for v in siguientes):
                    continue
                self.assertIn(
                    argumento, self.DUMPS_POR_BANDERA,
                    "%s pasa %s con una ruta y esa bandera no esta en DUMPS_POR_BANDERA"
                    % (paso["nombre"], argumento),
                )

    def test_nada_se_construye_para_que_NADIE_lo_consuma(self):
        """An intermediate nobody reads is build time and a file that confuses.

        ⚠️ **The rebuild found it, not the gate**: `build/es-def-wd.db` was being built and the step
        supposedly consuming it (`--sumar`) reads the **dump**, not the pack. Thirty seconds and
        4.5 MB for nothing, and the documentation said it was *an input to the merge*.
        """
        pasos = _pasos()
        for i, paso in enumerate(pasos):
            if os.sep + "build" + os.sep not in paso["salida"]:
                continue
            consumido = any(paso["salida"] in p["comando"] for p in pasos[i + 1:])
            self.assertTrue(
                consumido,
                "%s escribe %s y ningun paso posterior lo lee"
                % (paso["nombre"], os.path.basename(paso["salida"])),
            )

    def test_el_plan_no_usa_ninguna_fuente_RECHAZADA(self):
        """⚠️ DBnary is in `docs/fuentes.md` as rejected **and measured**: the same source as
        Wiktionary, half the yield. A `.ttl` in the plan is not a change of source --which would be
        a decision with its row in `decisions.md`-- it is an oversight.
        """
        for paso in _pasos():
            for argumento in paso["comando"]:
                self.assertNotIn(
                    "dbnary", os.path.basename(argumento).lower(),
                    "%s usa una fuente rechazada: %s" % (paso["nombre"], argumento),
                )
                self.assertFalse(
                    os.path.basename(argumento).endswith((".ttl", ".ttl.bz2")),
                    "%s pasa un Turtle, que ningun lector de este repo parsea: %s"
                    % (paso["nombre"], argumento),
                )

    def test_solo_un_idioma_no_arrastra_al_otro_ni_al_bilingue(self):
        nombres = _nombres(_pasos(solo="es"))
        self.assertTrue(all(not n.startswith("en") for n in nombres), nombres)
        self.assertTrue(all("bilingue" not in n for n in nombres), nombres)


class FiltroDeEtimologiaTest(unittest.TestCase):
    """That NOBODY carries `--etimologia-hasta`, and that this is checked with the file present.

    ⚠️ **This class used to pin the opposite**, and its own docstring carried the number that
    justified it: *"weighs +179 MB instead of +13"*. Measured on 2026-10-01 by recompressing the
    **362,921** affected entries against the pack's own dictionary, dropping the filter costs
    **8.11 MB** -- 2.60 % of a 312 MB pack -- and buys the origin for **351,401** words that had
    none. The flag is gone for every pack here (`d-a2f271-94e801`).

    ⚠️ **The previous `main` is CREATED in each test, and without that they are vacuous.** The flag
    used to be added only `if os.path.exists(vocabulario)`, so an assertion over a plan whose path
    is absent holds whatever the code does.
    """

    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        os.makedirs(os.path.join(self.tmp, DIST))

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _previo(self, nombre):
        ruta = os.path.join(self.tmp, DIST, nombre)
        open(ruta, "wb").close()
        return ruta

    def _paso(self, nombre, **kw):
        return next(p for p in build_packs.plan(self.tmp, **kw) if p["nombre"] == nombre)

    def test_el_ingles_no_lo_lleva_NI_con_el_main_anterior_presente(self):
        previo = self._previo("en-main.db")
        self.assertTrue(os.path.exists(previo), "el fixture tiene que existir o el test es vacuo")
        self.assertNotIn("--etimologia-hasta", self._paso("en-full", solo="en")["comando"])

    def test_el_espanol_tampoco_porque_su_full_ES_el_main(self):
        # D-220: `es-full` falls below `main`'s size range and no `es-main` is built, so it carries
        # the origin for all of its own vocabulary. A filter here would take the datum away from
        # words its own reader can look up -- and nothing would fail.
        self._previo("en-main.db")
        self._previo("es-main.db")
        self.assertNotIn("--etimologia-hasta", self._paso("es-full", solo="es")["comando"])


if __name__ == "__main__":
    unittest.main()
