"""Tests de tools/build_packs.py: el pipeline completo, en orden.

Se prueba **la forma del plan y no su ejecucion**, el mismo reparto que `test_devpack.py`: correr
el pipeline necesita 4,4 GB de dumps y una hora, y eso no entra al gate. Lo que si entra, y es lo
que importa, es **el orden** -- porque saltarselo no da error.
"""

import os
import sys
import unittest

sys.path.insert(
    0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
)

import build_packs  # noqa: E402

BUILD, DIST = build_packs.BUILD, build_packs.DIST

RAIZ = "/datos"


def _pasos(**kw):
    return build_packs.plan(RAIZ, **kw)


def _nombres(pasos):
    return [p["nombre"] for p in pasos]


class PlanTest(unittest.TestCase):

    def test_el_INGLES_va_antes_que_el_bilingue(self):
        """⚠️ El aserto que paga el archivo.

        `--flexiones` lee un pack YA CONSTRUIDO del idioma destino, asi que el bilingue tiene que
        ir despues del ingles. Saltarselo **no da error**: el pack sale bien formado, pasa
        `verify_pack.py`, y es peor en silencio -- la cobertura inversa cae 8,6 puntos, medidos.
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
        """Lo publicable a `dist/`, lo intermedio a `build/`, y nada al reves.

        ⚠️ Es lo que se vio en el emulador: con todo en un directorio plano, `es-def-wd` aparecio
        como pack **descargable**. No lo era. Hoy el plan no tiene intermedios --el unico que
        habia no lo consumia nadie, ver [test_nada_se_construye_para_que_NADIE_lo_consuma]-- asi
        que la mitad de `build/` es una guarda para cuando vuelva a haber uno.
        """
        for paso in _pasos():
            destino = BUILD if "intermedio" in paso["nombre"] else DIST
            self.assertIn(os.sep + destino + os.sep, paso["salida"], paso["nombre"])
            if destino == BUILD:
                self.assertNotIn(os.sep + DIST + os.sep, paso["salida"], paso["nombre"])

    def test_un_idioma_cuyo_FULL_ya_cabe_no_genera_main(self):
        """El espanol completo son 73,6 MB, por debajo del presupuesto de `main` (D-215).

        Generarlo daria un segundo pack con el mismo contenido.
        """
        nombres = _nombres(_pasos(tamanos={"es": 73.6, "en": 306.8}))
        self.assertNotIn("es-main", nombres, nombres)
        self.assertIn("es-core", nombres)
        self.assertIn("en-main", nombres, "el ingles son 306,8 MB: ahi si hace falta")

    def test_sin_saber_el_tamano_se_planean_LOS_DOS_niveles(self):
        # En seco no hay `full` que medir, y quedarse corto seria peor que planear de mas.
        self.assertIn("es-main", _nombres(_pasos()))

    def test_cada_pack_publicable_se_VERIFICA(self):
        """Un pack a medias se abre sin error y devuelve menos palabras de las que tiene."""
        for paso in _pasos():
            if "intermedio" in paso["nombre"]:
                continue
            self.assertTrue(paso["verifica"], "%s tendria que verificarse" % paso["nombre"])

    def test_los_niveles_piden_un_RANGO_medido_y_no_un_numero_suelto(self):
        """⚠️ **`--budget-mb` es un techo estimado; el requisito de D-215 es un rango.**

        La estimacion escala los payloads por la proporcion del pack de ORIGEN, que no es la del
        derivado: pedir 25 MB daba 17,7. El pipeline tiene que pedir el rango, que es lo unico
        que se cumple midiendo el archivo.
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
        """Derivar un `core` de un `main` haria que `subset_of` apunte al intermedio."""
        for paso in _pasos():
            if not paso["nombre"].endswith(("-core", "-main")):
                continue
            origen = paso["comando"][2]
            self.assertTrue(origen.endswith("-full.db"), "%s deriva de %s" % (paso["nombre"], origen))

    # ---------------------------------------------------------------------------------------
    # Los DUMPS: que cada paso le pase a su lector un archivo que ese lector sepa leer.
    # ---------------------------------------------------------------------------------------
    #
    # ⚠️ **Esto faltaba, y el pipeline estaba mal desde que se escribio.** Los tests de arriba
    # fijan el ORDEN, que es lo que "no da error" al saltarselo; pero el orden correcto sobre los
    # archivos equivocados no construye nada. Medido el 2026-09-22: `es-wd` recibia
    # `es_dbnary_ontolex.ttl.bz2` --y `sources/wikidata` hace `json.loads` por linea-- y
    # `--tesauro` del español recibia `es_dbnary_enhancement.ttl.bz2` --y `wordnet.spanish` abre
    # el archivo como TEXTO y parte por tabs--. Los dos revientan en la primera linea.
    #
    # Que reviente es una suerte: el modo de falla que este repo teme es el otro. Pero el
    # pipeline **nunca se corrio de punta a punta** --su propio docstring lo dice-- asi que nada
    # lo habia notado.

    #: Que archivo espera cada paso, y por que ese y no otro.
    DUMPS_ESPERADOS = {
        # kaikki: JSONL de lineas, una por pagina del wiki.
        "en-full": ["en.jsonl"],
        "es-full": ["es.jsonl"],
        "es-en (bilingue)": ["es-en-wikt.jsonl"],
    }

    #: Que archivo espera cada bandera. El lector esta entre parentesis.
    DUMPS_POR_BANDERA = {
        # wordnet.english: WN-LMF comprimido. wordnet.spanish: OMW `.tab`, texto plano.
        "--tesauro": ["oewn-2024.xml.gz", "wn-data-spa.tab"],
        # tatoeba: TSV de oraciones.
        "--frases": ["tatoeba-spa.tsv"],
        # frequency: lista `palabra<espacio>cuenta`.
        "--frecuencias": ["freq-en-opensubs.txt", "freq-es-opensubs.txt"],
        # build_core lee un pack ya construido, no un dump.
        "--flexiones": ["en-full.db"],
        # ⚠️ `--sumar <pack> <dump>` toma DOS valores, y el que es ruta es el segundo. Por eso
        # se le escapo al chequeo viejo, que miraba siempre indice+1 y leia `es-wd`.
        "--sumar": ["wikidata-lexemes.json.bz2"],
    }

    #: Cual de los valores de la bandera es la ruta. El default es 1 (el que sigue).
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
        """⚠️ **El chequeo por tabla sirve mientras la tabla este completa, y no lo estaba.**

        Es la tercera vez que un paso le pasa a un lector un archivo que no sabe leer: primero
        `es-wd` con un Turtle, despues `--tesauro` con otro, y ahora `--sumar` con un `.db`
        construido cuando su lector abre el dump crudo. Las dos primeras las fijo una tabla; la
        tercera se escapo porque **la bandera no estaba en la tabla**.

        Asi que la exhaustividad tambien se fija: cualquier bandera que reciba una ruta tiene que
        estar declarada. Una bandera nueva sin fila hace fallar esto, no al rebuild.
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
        """Un intermedio que nadie lee es tiempo de build y un archivo que confunde.

        ⚠️ **Lo encontro el rebuild, no el gate**: `build/es-def-wd.db` se construia y el paso que
        supuestamente lo consumia (`--sumar`) lee el **dump**, no el pack. Treinta segundos y 4,5
        MB para nada, y la documentacion decia que era *una entrada del merge*.
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
        """⚠️ DBnary esta en `docs/fuentes.md` como rechazada **y medida**: misma fuente que el
        Wikcionario, la mitad del rendimiento. Un `.ttl` en el plan no es un cambio de fuente
        --que seria una decision con su fila en `decisions.md`-- es un descuido.
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


if __name__ == "__main__":
    unittest.main()
