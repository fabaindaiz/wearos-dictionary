"""Tests de tools/devpack.py: el sideload de packs por adb en desarrollo.

Lo que se prueba aca es **la forma del plan**, no su ejecucion: armar la lista de comandos es
puro y entra al gate, correrlos necesita un dispositivo y no entra. Esa division es deliberada
y el limite esta medido -- lo que queda afuera se nombra en el changelog, no se disfraza de
cobertura.

El aserto que paga este archivo es uno solo: **nunca se escribe `<pack>.db` antes de comparar
los hashes**. Un pack a medio copiar se abre sin error y devuelve menos palabras de las que
tiene, y ese es el sintoma que este repo no puede observar (PackStore.kt:132-141).
"""

import os
import sys
import unittest

# tests/ -> packbuilder/ -> tools/, que es donde vive devpack.py. Mismo patron que el resto
# de los tests de este directorio, exento de E402 en pyproject.toml.
sys.path.insert(
    0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
)

import devpack  # noqa: E402

META = {
    "pack_id": "es-def-wikc",
    "data_version": "20260915",
    "schema_version": "3",
    "norm_version": "1",
    "name": "Español — definiciones",
    "entry_count": "146194",
}

ADB = ["adb", "-s", "emulator-5554"]


class DestinoTest(unittest.TestCase):
    """El nombre del archivo sale de meta.pack_id, no de como se llame el archivo local."""

    def test_el_destino_sale_del_pack_id(self):
        self.assertEqual("es-def-wikc.db", devpack.destino(META))

    def test_un_pack_id_con_separador_se_rechaza(self):
        # Sin esto, un pack_id malicioso o mal construido escribe fuera de files/packs.
        for malo in ("../fuera", "sub/dir", "", "   "):
            with self.assertRaises(devpack.PackInvalido):
                devpack.destino(dict(META, pack_id=malo))

    def test_sin_pack_id_se_rechaza(self):
        with self.assertRaises(devpack.PackInvalido):
            devpack.destino({"data_version": "20260915"})


class PlanInstallTest(unittest.TestCase):
    """La secuencia. Lo que se congela es el ORDEN, que es donde vive la atomicidad."""

    def plan(self, **kw):
        return devpack.plan_install(ADB, "/tmp/es.db", META, **kw)

    def nombres(self, plan):
        return [paso.nombre for paso in plan]

    def test_lo_primero_es_matar_la_app(self):
        # Pisar un .db que la app tiene abierto es la otra forma de romper esto.
        self.assertEqual("force-stop", self.nombres(self.plan())[0])

    def test_nada_escribe_el_db_final_antes_de_comparar(self):
        plan = self.plan()
        comparar = self.nombres(plan).index("comparar")
        for paso in plan[:comparar]:
            texto = " ".join(paso.argv or [])
            self.assertNotIn(
                "packs/es-def-wikc.db ", texto + " ",
                "un paso previo a la comparacion toca el .db final: %s" % paso.nombre,
            )

    def test_el_mv_viene_despues_de_comparar(self):
        nombres = self.nombres(self.plan())
        self.assertLess(nombres.index("comparar"), nombres.index("mv"))

    def test_se_escribe_a_part_y_el_mv_lo_renombra(self):
        plan = self.plan()
        escribir = next(p for p in plan if p.nombre == "escribir")
        self.assertIn("files/packs/es-def-wikc.db.part", " ".join(escribir.argv))
        mv = next(p for p in plan if p.nombre == "mv")
        self.assertIn(
            "files/packs/es-def-wikc.db.part files/packs/es-def-wikc.db", " ".join(mv.argv)
        )

    def test_por_pipe_el_archivo_local_entra_por_stdin(self):
        escribir = next(p for p in self.plan(pipe=True) if p.nombre == "escribir")
        self.assertEqual("/tmp/es.db", escribir.stdin)
        self.assertNotIn("/data/local/tmp", " ".join(escribir.argv))

    def test_el_fallback_pasa_por_tmp_y_lo_borra(self):
        # El pico de disco de esta ruta es 2x el pack: 590,2 MiB para el ingles. Si el temporal
        # no se borra, el pico se vuelve permanente.
        plan = self.plan(pipe=False)
        nombres = self.nombres(plan)
        self.assertIn("push", nombres)
        self.assertIn("rm-tmp", nombres)
        rm = next(p for p in plan if p.nombre == "rm-tmp")
        self.assertIn("/data/local/tmp", " ".join(rm.argv))
        self.assertIsNone(next(p for p in plan if p.nombre == "escribir").stdin)

    def test_todos_los_pasos_remotos_llevan_el_serial(self):
        for paso in self.plan():
            if paso.argv is not None:
                self.assertEqual(["adb", "-s", "emulator-5554"], paso.argv[:3])

    def test_sin_relanzar_no_hay_paso_de_relanzar(self):
        self.assertIn("relanzar", self.nombres(self.plan(relanzar=True)))
        self.assertNotIn("relanzar", self.nombres(self.plan(relanzar=False)))

    def test_el_chmod_deja_el_mismo_archivo_que_la_extraccion_del_apk(self):
        # Un pack extraido del APK queda 0600; `cat >` lo crea 0666. Que los dos caminos dejen
        # el mismo archivo es el diseño: la app no tiene que poder distinguirlos.
        nombres = self.nombres(self.plan())
        self.assertLess(nombres.index("comparar"), nombres.index("chmod"))
        self.assertLess(nombres.index("chmod"), nombres.index("mv"))

    def test_la_comparacion_es_local(self):
        comparar = next(p for p in self.plan() if p.nombre == "comparar")
        self.assertIsNone(comparar.argv)


class ElegirDispositivoTest(unittest.TestCase):
    """Con un emulador por nivel de API --que es lo que este repo pide-- adb a secas falla."""

    UNO = "List of devices attached\nemulator-5554\tdevice product:sdk_gwear_arm64\n"
    DOS = (
        "List of devices attached\n"
        "emulator-5554\tdevice product:sdk_gwear_arm64\n"
        "emulator-5556\tdevice product:sdk_gwear64_arm64\n"
    )
    RUIDO = (
        "List of devices attached\n"
        "emulator-5554\toffline\n"
        "0a1b2c3d\tunauthorized\n"
        "emulator-5556\tdevice product:sdk_gwear64_arm64\n"
        "\n"
    )

    def test_uno_solo_no_necesita_serial(self):
        self.assertEqual("emulator-5554", devpack.elegir_dispositivo(self.UNO, None))

    def test_dos_sin_serial_falla_nombrando_los_dos(self):
        with self.assertRaises(devpack.SinDispositivo) as cm:
            devpack.elegir_dispositivo(self.DOS, None)
        self.assertIn("emulator-5554", str(cm.exception))
        self.assertIn("emulator-5556", str(cm.exception))

    def test_dos_con_serial_elige_ese(self):
        self.assertEqual("emulator-5556", devpack.elegir_dispositivo(self.DOS, "emulator-5556"))

    def test_un_serial_que_no_esta_falla(self):
        with self.assertRaises(devpack.SinDispositivo):
            devpack.elegir_dispositivo(self.DOS, "emulator-9999")

    def test_ninguno_falla(self):
        with self.assertRaises(devpack.SinDispositivo):
            devpack.elegir_dispositivo("List of devices attached\n\n", None)

    def test_offline_y_unauthorized_no_cuentan(self):
        self.assertEqual("emulator-5556", devpack.elegir_dispositivo(self.RUIDO, None))


class CompararHashesTest(unittest.TestCase):
    """La unica comprobacion de que el pack llego entero. Si esto se afloja, el `.part` se
    renombra igual y queda un diccionario mutilado que se abre sin error."""

    SHA = "1f318b89ca208e01" + "0" * 48

    def test_coinciden(self):
        salida = "%s  files/packs/toy-es-en.db.part\n" % self.SHA
        self.assertEqual("ok", devpack.comparar_hashes(self.SHA, salida))

    def test_no_coinciden(self):
        salida = "%s  files/packs/toy-es-en.db.part\n" % ("a" * 64)
        self.assertEqual("distinto", devpack.comparar_hashes(self.SHA, salida))

    def test_sin_sha256sum_en_el_device(self):
        # toybox sin sha256sum: la salida es un error, no un hash. No se puede leer como "ok".
        for salida in ("", None, "sh: sha256sum: not found\n", "\n"):
            self.assertEqual("sin-sha256", devpack.comparar_hashes(self.SHA, salida))

    def test_una_salida_que_no_es_un_hash_no_pasa_por_ok(self):
        self.assertEqual("sin-sha256", devpack.comparar_hashes(self.SHA, "ZZZ  archivo\n"))


class DecidirTest(unittest.TestCase):
    """Instalar, reinstalar, o avisar. Lo que no se puede saber, se dice."""

    def test_sin_nada_instalado_se_instala(self):
        accion, _ = devpack.decidir(META, {})
        self.assertEqual("instalar", accion)

    def test_la_misma_version_es_reinstalar(self):
        accion, _ = devpack.decidir(
            META, {"es-def-wikc.db": {"pack_id": "es-def-wikc", "data_version": "20260915"}}
        )
        self.assertEqual("reinstalar", accion)

    def test_un_remoto_mas_nuevo_avisa_downgrade(self):
        accion, mensaje = devpack.decidir(
            META, {"es-def-wikc.db": {"pack_id": "es-def-wikc", "data_version": "20261231"}}
        )
        self.assertEqual("downgrade", accion)
        self.assertIn("20261231", mensaje)

    def test_otro_archivo_con_el_mismo_pack_id_es_colision(self):
        # El caso real: el pack de demo del APK se llama demo-es-en.db pero su pack_id es
        # toy-es-en. Instalar el toy pack dejaria dos archivos con el mismo id, y la app
        # abriria los dos: el selector muestra el idioma repetido.
        accion, mensaje = devpack.decidir(
            dict(META, pack_id="toy-es-en"),
            {"demo-es-en.db": {"pack_id": "toy-es-en", "data_version": "20260101"}},
        )
        self.assertEqual("colision", accion)
        self.assertIn("demo-es-en.db", mensaje)

    def test_sin_poder_leer_el_remoto_se_instala_y_se_dice(self):
        # Sin sqlite3 en el device no hay forma de saber que pack_id tiene cada archivo.
        accion, mensaje = devpack.decidir(META, {"otro.db": None})
        self.assertEqual("instalar", accion)
        self.assertIn("otro.db", mensaje)


if __name__ == "__main__":
    unittest.main()
