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

    def test_el_INTERMEDIO_no_va_a_dist(self):
        """⚠️ Es lo que se vio en el emulador: `es-def-wd` aparecio como pack descargable.

        Es una ENTRADA del merge espanol, no un diccionario para nadie. Un pack de una sola fuente
        es justo el modelo que se descarto.
        """
        intermedio = next(p for p in _pasos() if "intermedio" in p["nombre"])
        self.assertIn(os.sep + "build" + os.sep, intermedio["salida"])
        self.assertNotIn(os.sep + "dist" + os.sep, intermedio["salida"])

    def test_todo_lo_demas_SI_va_a_dist(self):
        for paso in _pasos():
            if "intermedio" in paso["nombre"]:
                continue
            self.assertIn(os.sep + "dist" + os.sep, paso["salida"], paso["nombre"])

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

    def test_los_niveles_derivan_del_FULL_y_no_de_otro_nivel(self):
        """Derivar un `core` de un `main` haria que `subset_of` apunte al intermedio."""
        for paso in _pasos():
            if not paso["nombre"].endswith(("-core", "-main")):
                continue
            origen = paso["comando"][2]
            self.assertTrue(origen.endswith("-full.db"), "%s deriva de %s" % (paso["nombre"], origen))

    def test_solo_un_idioma_no_arrastra_al_otro_ni_al_bilingue(self):
        nombres = _nombres(_pasos(solo="es"))
        self.assertTrue(all(not n.startswith("en") for n in nombres), nombres)
        self.assertTrue(all("bilingue" not in n for n in nombres), nombres)


if __name__ == "__main__":
    unittest.main()
