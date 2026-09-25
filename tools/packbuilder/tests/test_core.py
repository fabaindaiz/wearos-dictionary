"""Derivar un pack nucleo de un pack completo ya construido."""

import contextlib
import io
import os
import sqlite3
import sys
import tempfile
import shutil
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import build  # noqa: E402
import build_core  # noqa: E402

BASE_META = {
    "pack_id": "es-def-wikc",
    "kind": "monolingual",
    "name": "Español",
    "langs": "es",
    "fuzzy_profile": "es",
    "source_date": "20260915",
    "license": "CC-BY-SA-4.0",
    "attribution": "Wikcionario",
    "sources": ("definitions\tWikcionario\thttps://example.invalid/w\t"
                "CC BY-SA 4.0\thttps://creativecommons.org/licenses/by-sa/4.0/\n"),
    "source_url": "https://example.invalid/w",
    "proper_nouns": "included",
}


def _sin_ruido():
    """La CLI imprime su informe; el gate no es el lugar para leerlo."""
    return contextlib.redirect_stdout(io.StringIO())


def rec(headword, pos="noun", rank=0, forms=(), senses=None, sense_key=None):
    return build.Record(
        headword=headword,
        senses=senses or [{"gloss": "definición de " + headword}],
        part_of_speech=pos,
        rank=rank,
        forms=forms,
        sense_key=sense_key,
    )


class NombreConNivelTest(unittest.TestCase):
    """The tier is stamped ONCE, whether the name comes clean or already carrying one."""

    def test_un_nombre_limpio_recibe_su_nivel(self):
        self.assertEqual("Español (full)", build.name_with_tier("Español", "full"))

    def test_un_nombre_que_YA_trae_nivel_no_acumula(self):
        # ⚠️ **The defect this closes, seen on the watch's screen**: `build_pack` closes the full
        # one as `Español (full)` and `build_core` derived from THAT pack, sticking `(core)` on
        # top. The core came out called `Español (full) (core)`: both halves true and the phrase,
        # to whoever reads it, meaningless.
        self.assertEqual("Español (core)", build.name_with_tier("Español (full)", "core"))
        self.assertEqual("English (main)", build.name_with_tier("English (full)", "main"))

    def test_volver_a_estampar_el_mismo_nivel_es_idempotente(self):
        self.assertEqual("Español (core)", build.name_with_tier("Español (core)", "core"))

    def test_un_parentesis_que_NO_es_un_nivel_se_respeta(self):
        # Somebody else's pack can call itself anything. Removing any trailing parenthesis would
        # erase part of the name of one that has nothing to do with the tiers.
        self.assertEqual(
            "Griego (koiné) (core)", build.name_with_tier("Griego (koiné)", "core"))

    def test_solo_se_mira_el_ULTIMO_token(self):
        # "full" in the middle of the name is not the tier suffix and is not touched.
        self.assertEqual(
            "Diccionario (full) de bolsillo (core)",
            build.name_with_tier("Diccionario (full) de bolsillo", "core"),
        )


class BuildCoreTest(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.completo = os.path.join(self.dir, "completo.db")
        with build.PackBuilder(self.completo, dict(BASE_META)) as b:
            b.add(rec("agua", rank=1, forms=["aguas"]))
            b.add(rec("correr", pos="verb", rank=2, forms=["corro", "corriendo"]))
            b.add(rec("quilombo", rank=900))
            b.add(rec("ornitorrinco", rank=950))
            # Un homografo: mismo headword y pos, distinta etimologia. Su uid lleva sense_key.
            b.add(rec("banco", rank=10, sense_key="asiento"))
            b.add(rec("banco", rank=11, sense_key="entidad"))

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def _core(self, vocabulario, nombre="core.db"):
        salida = os.path.join(self.dir, nombre)
        build_core.derive(self.completo, salida, vocabulario)
        return salida

    def _filas(self, path, sql):
        with sqlite3.connect(path) as db:
            return db.execute(sql).fetchall()

    # --- The three sizes: core, main, full (D-215) -------------------------------------

    def test_el_presupuesto_toma_las_entradas_en_orden_de_RANK(self):
        """`rank` is already usage frequency, so it is the order of importance.

        ⚠️ Measured over the real packs: a 50 MB budget in English takes 59,503 entries, and the
        ones with a frequency signal --`rank < 500`-- are 55,903. That is, *"the important,
        general-use words"* and *"the ones some corpus attests"* are the same set. There is no need
        to invent a criterion.
        """
        # ⚠️ `setUp`'s pack is NO use for this and a mutation proved it: there the rank order and
        # the alphabetical one nearly coincide --`agua` is first by both routes-- so ordering by
        # `headword` passed the test just the same. A pack where they CONTRADICT each other is
        # needed.
        # ⚠️ No underscores: `norm()` turns them into spaces, and this test's first attempt
        # compared against the unnormalized lemma.
        contrario = os.path.join(self.dir, "contrario.db")
        with build.PackBuilder(contrario, dict(BASE_META)) as b:
            b.add(rec("abeja", rank=999))
            b.add(rec("zebra", rank=1))
        vocab = build_core.vocabulario_por_presupuesto(contrario, presupuesto_mb=0.02)
        self.assertEqual(
            {"zebra"}, vocab,
            "con presupuesto para UNA entrada entra la de rank 1, no la primera del alfabeto",
        )

    def test_con_la_lista_de_frecuencias_gana_la_MAS_USADA_y_no_la_de_mejor_rank(self):
        """⚠️ **Cutting by `rank` is worse than cutting by frequency, and it is measured.**

        `rank` already comes from the frequency, but **bucketed**: `int(round(zipf * 70))` puts
        thousands of words on the same number and the tie-break is alphabetical, so a fat word
        starting with `a` displaces a more used and thinner one. And past 500 it stops being
        frequency: it is page richness, which correlates **-0.250** with real usage.

        Measured over the real packs, corpus token coverage:

        | | by `rank` | by frequency | delta |
        |---|---|---|---|
        | English 25 MB | 93.46 % | **94.43 %** | +0.97 |
        | English 40 MB | 94.75 % | **96.03 %** | +1.28 |
        | English 130 MB | 95.10 % | **96.63 %** *(= the full pack)* | +1.53 |
        | Spanish 25 MB | 77.59 % | **78.87 %** *(= the full pack)* | +1.28 |

        And **more** lemmas get in, not fewer: the rank cut spends the budget on the fat pages.
        """
        contrario = os.path.join(self.dir, "empate.db")
        with build.PackBuilder(contrario, dict(BASE_META)) as b:
            # Same rank: by rank the alphabet breaks the tie and `alfa` gets in. By frequency,
            # `zulu`.
            b.add(rec("alfa", rank=100))
            b.add(rec("zulu", rank=100))
        vocab = build_core.vocabulario_por_presupuesto(
            contrario, presupuesto_mb=0.02, frecuencias={"zulu": 999, "alfa": 1})
        self.assertEqual({"zulu"}, vocab)

    def test_sin_lista_de_frecuencias_sigue_cortando_por_rank(self):
        """The list is optional: without it the behaviour is the old one, not an error.

        It matters because `build_core` derives from an already built pack and can be run by hand
        over any one, without having to hand the corpus it was built with.
        """
        contrario = os.path.join(self.dir, "sin-lista.db")
        with build.PackBuilder(contrario, dict(BASE_META)) as b:
            b.add(rec("abeja", rank=999))
            b.add(rec("zebra", rank=1))
        vocab = build_core.vocabulario_por_presupuesto(contrario, presupuesto_mb=0.02)
        self.assertEqual({"zebra"}, vocab)

    def test_una_palabra_SIN_frecuencia_va_detras_de_las_que_tienen(self):
        # The list covers ~50,000 words and the English pack has 842,026 lemmas: 95.5 % have no
        # signal. Those are ordered among themselves by `rank`, behind every attested one.
        contrario = os.path.join(self.dir, "mixto.db")
        with build.PackBuilder(contrario, dict(BASE_META)) as b:
            b.add(rec("rara", rank=1))       # mejor rank, pero el corpus no la vio
            b.add(rec("comun", rank=400))    # peor rank, pero atestiguada
        vocab = build_core.vocabulario_por_presupuesto(
            contrario, presupuesto_mb=0.02, frecuencias={"comun": 500})
        self.assertEqual({"comun"}, vocab)

    def test_el_artefacto_cae_DENTRO_del_rango_y_no_solo_debajo(self):
        """⚠️ **The budget is a RANGE the file has to meet, not a ceiling.**

        It used to be estimated once, scaling the payloads by the SOURCE pack's ratio, and the
        derived one has another: asking for 25 MB gave **17.7**, that is, below the range's
        minimum. A tier that comes up short is not wrong because of its size -- it is wrong because
        the range is the product requirement and the artifact does not meet it.

        It converges: it derives, measures the real file, corrects the factor and goes round again.
        The real factor is only known by measuring, so estimating it once is not enough.
        """
        salida = os.path.join(self.dir, "en-rango.db")
        informe = build_core.derivar_en_rango(
            self.completo, salida, minimo_mb=0.02, maximo_mb=0.06, tier="core")
        real = os.path.getsize(salida) / 1048576
        self.assertGreaterEqual(real, 0.02, informe)
        self.assertLessEqual(real, 0.06, informe)

    def test_si_el_pack_entero_no_llega_al_minimo_se_DICE(self):
        """Content cannot be invented to fill a range.

        A `full` smaller than the tier's minimum is not an error: it means that tier makes no sense
        for that language. What cannot happen is that it come out silent, because then the range
        stops meaning anything.
        """
        salida = os.path.join(self.dir, "imposible.db")
        informe = build_core.derivar_en_rango(
            self.completo, salida, minimo_mb=500, maximo_mb=900, tier="core")
        self.assertFalse(informe["en_rango"])
        self.assertIn("todo el pack", informe["motivo"])

    def test_declara_las_DOS_metricas(self):
        """⚠️ Two, because one alone cannot justify both tiers.

        `corpus_coverage` **saturates**: beyond the ~50,000 words the list attests, adding lemmas
        does not move it. Measured, English reaches its ceiling of 96.63 % at **57.6 MB**, so
        `main`'s 130 MB buy **zero** coverage by that yardstick. What they buy is finding the rare,
        and that is what `lemma_coverage` measures: what fraction of the full dictionary it takes.
        """
        salida = os.path.join(self.dir, "metricas.db")
        build_core.derivar_en_rango(self.completo, salida, minimo_mb=0.001, maximo_mb=9999,
                                    tier="core", frecuencias={"agua": 100})
        with sqlite3.connect(salida) as db:
            meta = dict(db.execute("SELECT key, value FROM meta"))
        self.assertIn("corpus_coverage", meta)
        self.assertIn("lemma_coverage", meta)
        self.assertEqual("100.00", meta["lemma_coverage"])

    def test_la_CLI_pide_el_RANGO_y_no_un_presupuesto_suelto(self):
        """⚠️ **The function existed and the pipeline went on calling the old mode.**

        It is the shape of debt that is invisible: `derivar_en_rango` with its tests green, and the
        published packs derived all the same with `--budget-mb`, that is, with no range guarantee.
        The mode gets in through the CLI or it does not get in.
        """
        salida = os.path.join(self.dir, "cli-rango.db")
        with _sin_ruido():
            codigo = build_core.main(["build_core.py", self.completo, salida,
                                      "--rango-mb", "0.02", "0.06", "--tier", "core"])
        self.assertEqual(0, codigo)
        real = os.path.getsize(salida) / 1048576
        self.assertGreaterEqual(real, 0.02)
        self.assertLessEqual(real, 0.06)

    def test_la_CLI_falla_si_el_rango_NO_se_cumple(self):
        """Exiting 0 with an out-of-range artifact would be worse than not having the mode.

        The pipeline chains onto what the previous step left, and an exit 0 says *this complies*.
        """
        salida = os.path.join(self.dir, "cli-imposible.db")
        with _sin_ruido():
            codigo = build_core.main(["build_core.py", self.completo, salida,
                                      "--rango-mb", "500", "900"])
        self.assertEqual(1, codigo)

    def test_un_presupuesto_enorme_se_lo_lleva_todo(self):
        vocab = build_core.vocabulario_por_presupuesto(self.completo, presupuesto_mb=9999)
        for lema in ("agua", "correr", "quilombo", "ornitorrinco", "banco"):
            self.assertIn(lema, vocab, lema)

    def test_el_nivel_va_en_el_NOMBRE_y_en_tier(self):
        """Asked for: *"3 sizes, core, main and full, and have this marked in the name"*."""
        salida = os.path.join(self.dir, "main.db")
        build_core.derive(self.completo, salida, {"agua", "correr"}, tier="main")
        meta = dict(self._filas(salida, "select key, value from meta"))
        self.assertEqual("main", meta["tier"])
        self.assertEqual("Español (main)", meta["name"])

    def test_la_identidad_es_IDIOMA_mas_nivel_y_no_las_fuentes(self):
        """Asked for: *"packs are by language and in versions"*.

        ⚠️ The `pack_id` used to be `es-def-wikc-tat-freq-wn-wd`, that is, the list of sources. That
        makes **adding a source change the identity** and the pack look like another one: the app
        would not recognize it as the one it already has installed. The sources are still declared
        in `meta.sources`, which is where they get consulted.
        """
        salida = os.path.join(self.dir, "core.db")
        build_core.derive(self.completo, salida, {"agua"}, tier="core")
        meta = dict(self._filas(salida, "select key, value from meta"))
        self.assertEqual("es-core", meta["pack_id"])
        self.assertEqual("es-def-wikc", meta["subset_of"], "sigue diciendo de quien es subconjunto")

    def test_solo_entran_los_lemas_del_vocabulario(self):
        core = self._core({"agua", "correr"})
        lemas = sorted(r[0] for r in self._filas(core, "SELECT headword FROM entry"))
        self.assertEqual(["agua", "correr"], lemas)

    def test_los_uid_son_LOS_MISMOS_que_en_el_pack_completo(self):
        # ⚠️ **The invariant that makes this a subset and not another pack.** `uid` is the logical
        # identity and the join key across packs (D-055). If the core recomputed its `sense_key`
        # counting ITS homographs, "banco" would have a different uid from the full one's -- which
        # is exactly the failure D-145 found when merging. The uid is COPIED, not recomputed.
        core = self._core({"agua", "banco"})
        antes = dict(self._filas(self.completo, "SELECT headword || ':' || id, uid FROM entry"))
        despues = self._filas(core, "SELECT headword, uid FROM entry")
        uids_completo = {u for k, u in antes.items() if k.split(":")[0] in ("agua", "banco")}
        self.assertEqual(uids_completo, {u for _h, u in despues})

    def test_un_homografo_entra_ENTERO_o_no_entra(self):
        # "banco"'s two senses are two entries with the same lemma: the vocabulary speaks of words,
        # not of entries, so both get in.
        core = self._core({"banco"})
        self.assertEqual(2, len(self._filas(core, "SELECT id FROM entry")))

    def test_declara_de_quien_es_subconjunto(self):
        core = self._core({"agua"})
        meta = dict(self._filas(core, "SELECT key, value FROM meta"))
        self.assertEqual("es-def-wikc", meta["subset_of"])
        # ⚠️ It used to be `es-def-wikc-core`, that is, the sources + the tier. Since D-215 the
        # identity is LANGUAGE + TIER: adding a source no longer changes the pack_id or makes it
        # look like another pack.
        self.assertEqual("es-core", meta["pack_id"])

    def test_las_formas_flexionadas_del_lema_viajan_con_el(self):
        # Without this, searching "corriendo" in the core would not find "correr", which is rung 2
        # of the cascade.
        core = self._core({"correr"})
        formas = sorted(r[0] for r in self._filas(core, "SELECT norm FROM form"))
        self.assertIn("corriendo", formas)

    def test_las_formas_de_lo_que_NO_entro_no_viajan(self):
        core = self._core({"agua"})
        formas = sorted(r[0] for r in self._filas(core, "SELECT norm FROM form"))
        self.assertNotIn("corriendo", formas)

    def test_se_conserva_el_contenido_de_la_acepcion(self):
        core = self._core({"agua"})
        with sqlite3.connect(core) as db:
            import binascii
            import payload
            dic = binascii.unhexlify(
                db.execute("SELECT value FROM meta WHERE key='payload_dict'").fetchone()[0])
            blob = db.execute("SELECT payload FROM entry").fetchone()[0]
        _pos, senses, _palabra = payload.parse(payload.decompress(blob, dic))
        self.assertEqual("definición de agua", senses[0]["gloss"])

    def test_hereda_la_atribucion_del_completo_y_suma_la_del_corpus(self):
        # ⚠️ The credit travels with the content (D-138): the core distributes the same
        # definitions, so it carries the same sources; and the corpus that CHOSE the words is
        # declared too, because `sources` answers how the pack was assembled.
        core = self._core({"agua"})
        meta = dict(self._filas(core, "SELECT key, value FROM meta"))
        self.assertIn("Wikcionario", meta["sources"])
        self.assertIn("vocabulary", meta["sources"])

    def test_un_vocabulario_que_no_matchea_nada_falla_en_vez_de_dar_un_pack_vacio(self):
        # A zero-entry pack opens with no error and finds nothing: it is the silent failure this
        # repo exists in order not to have.
        with self.assertRaises(ValueError):
            self._core({"palabraqueno existe"})


class DescriptionFollowsTheLanguage(unittest.TestCase):
    """Every sentence appended to a description is written in the pack's own language.

    WARNING: **this is a regression test for something that shipped.** All three English packs
    carried *"Resultados ordenados por frecuencia de uso real."* wedged into English prose, and
    `en-core` travels inside the APK, so it was on a watch. Four of the five append sites did not
    look at the language; the fifth did, which is why the defect looked deliberate.
    """

    def _meta(self, langs):
        return {"langs": langs, "description": "Base."}

    def test_english_pack_gets_the_english_sentence(self):
        import build_pack
        m = self._meta("en")
        build_pack._describir(m, "frecuencia")
        self.assertEqual("Base. " + build_pack.FRASES["frecuencia"][0], m["description"])

    def test_spanish_pack_gets_the_spanish_sentence(self):
        import build_pack
        m = self._meta("es")
        build_pack._describir(m, "frecuencia")
        self.assertEqual("Base. " + build_pack.FRASES["frecuencia"][1], m["description"])

    def test_the_bidirectional_pack_follows_its_first_language(self):
        # `es,en` is described in Spanish on purpose: it is a Spanish-first dictionary, and the
        # rest of this file already reads the FIRST of `langs` as the pack's language.
        import build_pack
        m = self._meta("es,en")
        build_pack._describir(m, "frecuencia")
        self.assertEqual("Base. " + build_pack.FRASES["frecuencia"][1], m["description"])
class AttributionFollowsTheLanguage(unittest.TestCase):
    """The ATTRIBUTION follows the pack's language too, which it did not.

    ⚠️ **It is the same bug `_describir` was written to fix, one function over.** The sentence
    table got the `(en, es)` pair; the SOURCE table kept one Spanish string, so `_declarar`
    appended it to every pack and the three English ones shipped an attribution reading
    *"...(Tatu Ylonen). Frecuencias de uso del corpus OpenSubtitles... CC BY-SA 4.0. Synonyms
    and antonyms from Open English WordNet..."* -- Spanish wedged into English prose.

    ⚠️ **And this field is the worse place for it.** `description` is a convenience;
    `attribution` is what D-031 makes non-optional, so the string somebody reads to know whose
    data this is was half in a language they may not have.
    """

    # Letters and words that only occur in the Spanish prose. Deliberately not a language
    # detector: it is a fixed list over strings this repo writes, so it cannot go vacuous on a
    # rewording it does not know about -- `todas` below is what catches that.
    MARCAS_ES = ("á", "é", "í", "ó", "ú", "ñ", "licencia", "Frecuencias", "Frases",
                 "Definiciones", "Sinónimos", "Ejemplos")

    def _meta(self, langs):
        return {"langs": langs}

    def test_an_english_pack_carries_no_spanish_in_its_attribution(self):
        import build_pack
        m = self._meta("en")
        # Every source an English pack can use today, plus the one that actually leaked.
        for clave in ("wikt", "opensubs", "oewn-tesauro"):
            build_pack._declarar(m, clave)
        encontradas = [x for x in self.MARCAS_ES if x in m["attribution"]]
        self.assertEqual([], encontradas, m["attribution"])

    def test_a_spanish_pack_keeps_its_spanish_attribution(self):
        import build_pack
        m = self._meta("es")
        build_pack._declarar(m, "opensubs")
        self.assertEqual(build_pack.FUENTES["opensubs"]["prosa"][1], m["attribution"])

    def test_the_bidirectional_pack_follows_its_first_language(self):
        # `es,en` is Spanish-first, the same rule the description follows.
        import build_pack
        m = self._meta("es,en")
        build_pack._declarar(m, "opensubs")
        self.assertEqual(build_pack.FUENTES["opensubs"]["prosa"][1], m["attribution"])

    def test_todas_las_fuentes_declaran_las_dos_prosas(self):
        # ⚠️ The guard that keeps the test above from going vacuous: a source added later with a
        # single string would make `en, es = fuente["prosa"]` unpack its characters, and the pack
        # would ship an attribution of two letters rather than failing.
        import build_pack
        for clave, fuente in build_pack.FUENTES.items():
            with self.subTest(fuente=clave):
                self.assertIsInstance(fuente["prosa"], tuple, clave)
                self.assertEqual(2, len(fuente["prosa"]), clave)
                for texto in fuente["prosa"]:
                    self.assertGreater(len(texto), 20, clave)


