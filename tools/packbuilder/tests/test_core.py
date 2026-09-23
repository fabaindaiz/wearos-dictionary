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
    """El nivel se estampa UNA vez, venga el nombre limpio o ya con uno."""

    def test_un_nombre_limpio_recibe_su_nivel(self):
        self.assertEqual("Español (full)", build.name_with_tier("Español", "full"))

    def test_un_nombre_que_YA_trae_nivel_no_acumula(self):
        # ⚠️ **El defecto que esto cierra, visto en la pantalla del reloj**: `build_pack` cierra
        # el completo como `Español (full)` y `build_core` derivaba de ESE pack, pegandole
        # `(core)` encima. El nucleo salia llamandose `Español (full) (core)`: las dos mitades
        # ciertas y la frase, para quien la lee, sin sentido.
        self.assertEqual("Español (core)", build.name_with_tier("Español (full)", "core"))
        self.assertEqual("English (main)", build.name_with_tier("English (full)", "main"))

    def test_volver_a_estampar_el_mismo_nivel_es_idempotente(self):
        self.assertEqual("Español (core)", build.name_with_tier("Español (core)", "core"))

    def test_un_parentesis_que_NO_es_un_nivel_se_respeta(self):
        # Un pack ajeno puede llamarse como quiera. Sacar cualquier parentesis final le borraria
        # parte del nombre a quien no tiene nada que ver con los niveles.
        self.assertEqual(
            "Griego (koiné) (core)", build.name_with_tier("Griego (koiné)", "core"))

    def test_solo_se_mira_el_ULTIMO_token(self):
        # "full" en el medio del nombre no es el sufijo de nivel y no se toca.
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

    # --- Los tres tamanos: core, main, full (D-215) ------------------------------------

    def test_el_presupuesto_toma_las_entradas_en_orden_de_RANK(self):
        """`rank` ya es frecuencia de uso, asi que es el orden de importancia.

        ⚠️ Medido sobre los packs reales: un presupuesto de 50 MB en ingles toma 59.503 entradas,
        y las que tienen senal de frecuencia --`rank < 500`-- son 55.903. O sea que *"las palabras
        importantes y de uso general"* y *"las que algun corpus atestigua"* son el mismo conjunto.
        No hace falta inventar un criterio.
        """
        # ⚠️ El pack de `setUp` NO sirve para esto y una mutacion lo demostro: ahi el orden por
        # rank y el alfabetico casi coinciden --`agua` es la primera por las dos vias-- asi que
        # ordenar por `headword` pasaba el test igual. Hace falta un pack donde se CONTRADIGAN.
        # ⚠️ Sin guiones bajos: `norm()` los convierte en espacio, y el primer intento de este
        # test comparaba contra el lema sin normalizar.
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
        """⚠️ **El corte por `rank` es peor que el corte por frecuencia, y esta medido.**

        `rank` ya sale de la frecuencia, pero **bucketizado**: `int(round(zipf * 70))` mete miles
        de palabras en el mismo numero y el desempate es alfabetico, asi que una palabra gorda que
        empieza con `a` desplaza a una mas usada y mas flaca. Y pasado el 500 deja de ser
        frecuencia: es riqueza de pagina, que correlaciona **-0,250** con el uso real.

        Medido sobre los packs reales, cobertura de tokens del corpus:

        | | por `rank` | por frecuencia | delta |
        |---|---|---|---|
        | ingles 25 MB | 93,46 % | **94,43 %** | +0,97 |
        | ingles 40 MB | 94,75 % | **96,03 %** | +1,28 |
        | ingles 130 MB | 95,10 % | **96,63 %** *(= el pack completo)* | +1,53 |
        | español 25 MB | 77,59 % | **78,87 %** *(= el pack completo)* | +1,28 |

        Y ademas entran **mas** lemas, no menos: el corte por rank gasta el presupuesto en las
        paginas gordas.
        """
        contrario = os.path.join(self.dir, "empate.db")
        with build.PackBuilder(contrario, dict(BASE_META)) as b:
            # Mismo rank: por rank desempata el alfabeto y entra `alfa`. Por frecuencia, `zulu`.
            b.add(rec("alfa", rank=100))
            b.add(rec("zulu", rank=100))
        vocab = build_core.vocabulario_por_presupuesto(
            contrario, presupuesto_mb=0.02, frecuencias={"zulu": 999, "alfa": 1})
        self.assertEqual({"zulu"}, vocab)

    def test_sin_lista_de_frecuencias_sigue_cortando_por_rank(self):
        """La lista es opcional: sin ella el comportamiento es el de antes, no un error.

        Importa porque `build_core` deriva de un pack ya construido y puede correrse a mano sobre
        uno cualquiera, sin tener a mano el corpus con el que se construyo.
        """
        contrario = os.path.join(self.dir, "sin-lista.db")
        with build.PackBuilder(contrario, dict(BASE_META)) as b:
            b.add(rec("abeja", rank=999))
            b.add(rec("zebra", rank=1))
        vocab = build_core.vocabulario_por_presupuesto(contrario, presupuesto_mb=0.02)
        self.assertEqual({"zebra"}, vocab)

    def test_una_palabra_SIN_frecuencia_va_detras_de_las_que_tienen(self):
        # La lista cubre ~50.000 palabras y el pack ingles tiene 842.026 lemas: el 95,5 % no
        # tiene senal. Esas se ordenan entre si por `rank`, detras de todas las atestiguadas.
        contrario = os.path.join(self.dir, "mixto.db")
        with build.PackBuilder(contrario, dict(BASE_META)) as b:
            b.add(rec("rara", rank=1))       # mejor rank, pero el corpus no la vio
            b.add(rec("comun", rank=400))    # peor rank, pero atestiguada
        vocab = build_core.vocabulario_por_presupuesto(
            contrario, presupuesto_mb=0.02, frecuencias={"comun": 500})
        self.assertEqual({"comun"}, vocab)

    def test_el_artefacto_cae_DENTRO_del_rango_y_no_solo_debajo(self):
        """⚠️ **El presupuesto es un RANGO que el archivo tiene que cumplir, no un techo.**

        Antes se estimaba una sola vez, escalando los payloads por la proporcion que tenia el
        pack de ORIGEN, y el derivado tiene otra: pedir 25 MB daba **17,7**, o sea por debajo del
        minimo del rango. Un nivel que se queda corto no esta mal por el tamaño -- esta mal
        porque el rango es el requisito de producto y el artefacto no lo cumple.

        Converge: deriva, mide el archivo de verdad, corrige el factor y vuelve. El factor real
        solo se conoce midiendo, asi que estimarlo una vez no alcanza.
        """
        salida = os.path.join(self.dir, "en-rango.db")
        informe = build_core.derivar_en_rango(
            self.completo, salida, minimo_mb=0.02, maximo_mb=0.06, tier="core")
        real = os.path.getsize(salida) / 1048576
        self.assertGreaterEqual(real, 0.02, informe)
        self.assertLessEqual(real, 0.06, informe)

    def test_si_el_pack_entero_no_llega_al_minimo_se_DICE(self):
        """No se puede inventar contenido para llenar un rango.

        Un `full` mas chico que el minimo del nivel no es un error: es que ese nivel no tiene
        sentido para ese idioma. Lo que no puede pasar es que salga callado, porque entonces el
        rango deja de significar algo.
        """
        salida = os.path.join(self.dir, "imposible.db")
        informe = build_core.derivar_en_rango(
            self.completo, salida, minimo_mb=500, maximo_mb=900, tier="core")
        self.assertFalse(informe["en_rango"])
        self.assertIn("todo el pack", informe["motivo"])

    def test_declara_las_DOS_metricas(self):
        """⚠️ Dos, porque una sola no puede justificar los dos niveles.

        `corpus_coverage` **satura**: pasadas las ~50.000 palabras que la lista atestigua, sumar
        lemas no la mueve. Medido, el ingles llega a su techo de 96,63 % en **57,6 MB**, asi que
        los 130 MB de `main` compran **cero** cobertura por esa vara. Lo que compran es encontrar
        lo raro, y eso lo mide `lemma_coverage`: que fraccion del diccionario completo se lleva.
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
        """⚠️ **La funcion existia y el pipeline seguia llamando al modo viejo.**

        Es la forma de deuda que no se ve: `derivar_en_rango` con sus tests en verde, y los packs
        publicados derivados igual con `--budget-mb`, o sea sin garantia de rango. El modo entra
        por la CLI o no entra.
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
        """Salir 0 con un artefacto fuera de rango seria peor que no tener el modo.

        El pipeline encadena sobre lo que el paso anterior dejo, y un exit 0 dice *esto cumple*.
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
        """Pedido: *«3 tamanos, core, main y full, y que esto vaya marcado en el nombre»*."""
        salida = os.path.join(self.dir, "main.db")
        build_core.derive(self.completo, salida, {"agua", "correr"}, tier="main")
        meta = dict(self._filas(salida, "select key, value from meta"))
        self.assertEqual("main", meta["tier"])
        self.assertEqual("Español (main)", meta["name"])

    def test_la_identidad_es_IDIOMA_mas_nivel_y_no_las_fuentes(self):
        """Pedido: *«los packs son por idioma y en versiones»*.

        ⚠️ Antes el `pack_id` era `es-def-wikc-tat-freq-wn-wd`, o sea la lista de fuentes. Eso hace
        que **anadir una fuente cambie la identidad** y el pack parezca otro: la app no lo
        reconoceria como el que ya tiene instalado. Las fuentes siguen declaradas en `meta.sources`,
        que es donde se consultan.
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
        # ⚠️ **La invariante que hace que esto sea un subconjunto y no otro pack.** `uid` es la
        # identidad logica y la llave de join entre packs (D-055). Si el nucleo recalculara su
        # `sense_key` contando SUS homografos, "banco" tendria otro uid que en el completo -- que
        # es exactamente el fallo que D-145 encontro al fusionar. El uid se COPIA, no se recalcula.
        core = self._core({"agua", "banco"})
        antes = dict(self._filas(self.completo, "SELECT headword || ':' || id, uid FROM entry"))
        despues = self._filas(core, "SELECT headword, uid FROM entry")
        uids_completo = {u for k, u in antes.items() if k.split(":")[0] in ("agua", "banco")}
        self.assertEqual(uids_completo, {u for _h, u in despues})

    def test_un_homografo_entra_ENTERO_o_no_entra(self):
        # Las dos acepciones de "banco" son dos entradas con el mismo lema: el vocabulario habla
        # de palabras, no de entradas, asi que entran las dos.
        core = self._core({"banco"})
        self.assertEqual(2, len(self._filas(core, "SELECT id FROM entry")))

    def test_declara_de_quien_es_subconjunto(self):
        core = self._core({"agua"})
        meta = dict(self._filas(core, "SELECT key, value FROM meta"))
        self.assertEqual("es-def-wikc", meta["subset_of"])
        # ⚠️ Era `es-def-wikc-core`, o sea las fuentes + el nivel. Desde D-215 la identidad es
        # IDIOMA + NIVEL: anadir una fuente ya no cambia el pack_id ni hace que parezca otro pack.
        self.assertEqual("es-core", meta["pack_id"])

    def test_las_formas_flexionadas_del_lema_viajan_con_el(self):
        # Sin esto, buscar "corriendo" en el nucleo no encontraria "correr", que es el peldano 2
        # de la cascada.
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
        # ⚠️ El credito se mueve con el contenido (D-138): el nucleo distribuye las mismas
        # definiciones, asi que lleva las mismas fuentes; y el corpus que ELIGIO las palabras
        # tambien se declara, porque `sources` contesta como se armo el pack.
        core = self._core({"agua"})
        meta = dict(self._filas(core, "SELECT key, value FROM meta"))
        self.assertIn("Wikcionario", meta["sources"])
        self.assertIn("vocabulary", meta["sources"])

    def test_un_vocabulario_que_no_matchea_nada_falla_en_vez_de_dar_un_pack_vacio(self):
        # Un pack de cero entradas se abre sin error y no encuentra nada: es la falla silenciosa
        # que este repo existe para no tener.
        with self.assertRaises(ValueError):
            self._core({"palabraqueno existe"})
