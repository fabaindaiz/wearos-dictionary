"""Tests of tools/devpack.py: sideloading packs over adb in development.

What gets tested here is **the plan's shape**, not its execution: assembling the list of commands
is pure and enters the gate, running them needs a device and does not. That split is deliberate and
the limit is measured -- what is left out gets named in the changelog, not disguised as coverage.

There is a single assertion that pays for this file: **`<pack>.db` is never written before the
hashes are compared**. A half-copied pack opens with no error and returns fewer words than it
holds, and that is the symptom this repo cannot observe (PackStore.kt:132-141).
"""

import os
import sys
import unittest

# tests/ -> packbuilder/ -> tools/, which is where devpack.py lives. Same pattern as the rest of
# this directory's tests, exempt from E402 in pyproject.toml.
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
    """The file's name comes from meta.pack_id, not from what the local file is called."""

    def test_el_destino_sale_del_pack_id(self):
        self.assertEqual("es-def-wikc.db", devpack.destino(META))

    def test_un_pack_id_con_separador_se_rechaza(self):
        # Without this, a malicious or badly built pack_id writes outside files/packs.
        for malo in ("../fuera", "sub/dir", "", "   "):
            with self.assertRaises(devpack.PackInvalido):
                devpack.destino(dict(META, pack_id=malo))

    def test_sin_pack_id_se_rechaza(self):
        with self.assertRaises(devpack.PackInvalido):
            devpack.destino({"data_version": "20260915"})


class PlanInstallTest(unittest.TestCase):
    """The sequence. What gets frozen is the ORDER, which is where the atomicity lives."""

    def plan(self, **kw):
        return devpack.plan_install(ADB, "/tmp/es.db", META, **kw)

    def nombres(self, plan):
        return [paso.nombre for paso in plan]

    def test_lo_primero_es_matar_la_app(self):
        # Overwriting a .db the app has open is the other way of breaking this.
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
        # This route's disk peak is 2x the pack: 590.2 MiB for English. If the temporary is not
        # deleted, the peak becomes permanent.
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
        # A pack extracted from the APK ends up 0600; `cat >` creates it 0666. That both routes
        # leave the same file is the design: the app must not be able to tell them apart.
        nombres = self.nombres(self.plan())
        self.assertLess(nombres.index("comparar"), nombres.index("chmod"))
        self.assertLess(nombres.index("chmod"), nombres.index("mv"))

    def test_la_comparacion_es_local(self):
        comparar = next(p for p in self.plan() if p.nombre == "comparar")
        self.assertIsNone(comparar.argv)


class ElegirDispositivoTest(unittest.TestCase):
    """With one emulator per API level --which is what this repo asks for-- a bare adb fails."""

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
    """The only check that the pack arrived whole. If this is relaxed, the `.part` gets renamed all
    the same and a mutilated dictionary is left that opens with no error."""

    SHA = "1f318b89ca208e01" + "0" * 48

    def test_coinciden(self):
        salida = "%s  files/packs/toy-es-en.db.part\n" % self.SHA
        self.assertEqual("ok", devpack.comparar_hashes(self.SHA, salida))

    def test_no_coinciden(self):
        salida = "%s  files/packs/toy-es-en.db.part\n" % ("a" * 64)
        self.assertEqual("distinto", devpack.comparar_hashes(self.SHA, salida))

    def test_sin_sha256sum_en_el_device(self):
        # toybox with no sha256sum: the output is an error, not a hash. It cannot be read as "ok".
        for salida in ("", None, "sh: sha256sum: not found\n", "\n"):
            self.assertEqual("sin-sha256", devpack.comparar_hashes(self.SHA, salida))

    def test_una_salida_que_no_es_un_hash_no_pasa_por_ok(self):
        self.assertEqual("sin-sha256", devpack.comparar_hashes(self.SHA, "ZZZ  archivo\n"))


class DecidirTest(unittest.TestCase):
    """Install, reinstall, or warn. What cannot be known gets said."""

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
        # The real case: the APK's demo pack is called demo-es-en.db but its pack_id is toy-es-en.
        # Installing the toy pack would leave two files with the same id, and the app would open
        # both: the selector shows the language repeated.
        accion, mensaje = devpack.decidir(
            dict(META, pack_id="toy-es-en"),
            {"demo-es-en.db": {"pack_id": "toy-es-en", "data_version": "20260101"}},
        )
        self.assertEqual("colision", accion)
        self.assertIn("demo-es-en.db", mensaje)

    def test_sin_poder_leer_el_remoto_se_instala_y_se_dice(self):
        # With no sqlite3 on the device there is no way to know which pack_id each file has.
        accion, mensaje = devpack.decidir(META, {"otro.db": None})
        self.assertEqual("instalar", accion)
        self.assertIn("otro.db", mensaje)


class PaqueteAusenteTest(unittest.TestCase):
    """`run-as` failing because the app is not installed has to be said, not blow up.

    It is a NORMAL case, not an oddity: `connectedAndroidTest` uninstalls the app when it finishes,
    so running the tests and then installing a pack is a sequence anybody does. What was happening
    is that `run-as`'s message goes to **stderr** and `packs_remotos` only looked at stdout, so the
    guard did not fire: the install carried on and died with a BrokenPipeError 295 MB in, which
    says nothing about what to do.
    """

    def test_run_as_fallando_por_stderr_se_detecta(self):
        original = devpack.correr
        devpack.correr = lambda paso, silencioso=False, con_errores=False: (
            ("", "run-as: unknown package: cl.fadiaz.dictionary") if con_errores else ""
        )
        try:
            with self.assertRaises(devpack.FalloRemoto) as capturado:
                devpack.packs_remotos(["adb"])
        finally:
            devpack.correr = original
        self.assertIn("installDebug", str(capturado.exception))



if __name__ == "__main__":
    unittest.main()


class PlanUploadTest(unittest.TestCase):
    """`upload`: the APK first, then its packs by download, and the session always stopped.

    The packs arrive the way a user's do (d-a2f271-d49033): the watch runs a build `run-as` cannot
    enter, and asking for them needs the APK's receiver, so the APK goes first and the app reloads
    on each finished download. An APK sent without the full packs it was built against leaves
    *Local* empty on the watch (`LOCAL/todas es-full=0`), and a session nobody stops keeps the
    watch's radio awake.
    """

    SESION = "/x/watchsession.py"
    RELOJ = "192.0.2.1:5555"

    def _plan(self, packs=("es-full", "en-full"), apk="app-benchmark.apk", serial=RELOJ, **kw):
        return devpack.plan_upload(serial, apk, list(packs), self.SESION, **kw)

    def test_the_apk_goes_before_the_packs(self):
        pasos = self._plan()
        deploy = [i for i, p in enumerate(pasos) if "deploy" in p.argv]
        bajar = [i for i, p in enumerate(pasos) if "download" in p.argv]
        self.assertEqual(1, len(deploy))
        self.assertEqual(1, len(bajar))
        self.assertLess(deploy[0], bajar[0])

    def test_one_download_step_asks_for_every_pack_by_id(self):
        bajar = [p for p in self._plan(packs=("es-full.db", "en-full")) if "download" in p.argv][0]
        self.assertEqual(["es-full", "en-full"], bajar.argv[-2:])

    def test_the_session_opens_first_and_stops_last(self):
        pasos = self._plan()
        self.assertIn("start", pasos[0].argv)
        self.assertIn("stop", pasos[-1].argv)
        self.assertTrue(pasos[-1].siempre)

    def test_an_emulator_needs_no_session(self):
        pasos = self._plan(serial="emulator-5554")
        self.assertFalse(any(self.SESION in p.argv for p in pasos))
        self.assertIn("install", pasos[0].argv)
        self.assertIn("download", pasos[-1].argv)

    def test_every_step_names_the_device(self):
        for serial in (self.RELOJ, "emulator-5554"):
            for paso in self._plan(serial=serial):
                self.assertIn(serial, paso.argv)

    def test_an_apk_without_packs_is_refused_unless_asked_for(self):
        with self.assertRaises(devpack.PackInvalido):
            self._plan(packs=())
        pasos = self._plan(packs=(), solo_apk=True)
        self.assertIn("deploy", pasos[1].argv)
        self.assertFalse(any("download" in p.argv for p in pasos))


class EjecutarSubidaTest(unittest.TestCase):

    def test_a_failed_apk_skips_the_packs_and_still_stops_the_session(self):
        pasos = devpack.plan_upload("192.0.2.1:5555", "app.apk", ["es-full", "en-full"], "/x/w.py")
        corridos = []

        def correr(argv):
            corridos.append(argv)
            return 1 if "deploy" in argv else 0

        self.assertEqual("app", devpack.ejecutar_subida(pasos, correr))
        self.assertFalse(any("download" in a for a in corridos))
        self.assertIn("stop", corridos[-1])

    def test_a_clean_upload_reports_nothing(self):
        pasos = devpack.plan_upload("192.0.2.1:5555", "app.apk", ["es-full"], "/x/w.py")
        self.assertIsNone(devpack.ejecutar_subida(pasos, lambda argv: 0))


class EstadoDescargasTest(unittest.TestCase):
    """What the `Dict` log says about each pack asked for: the readout that replaced `run-as`."""

    def test_each_pack_is_done_when_its_worker_says_installed(self):
        lineas = ["debug: descargar es-full, en-full", "worker: empieza es-full",
                  "worker: es-full instalado"]
        self.assertEqual({"es-full": "instalado"},
                         devpack.estado_descargas(lineas, ["es-full", "en-full"]))

    def test_an_id_outside_the_catalog_is_named(self):
        estado = devpack.estado_descargas(["debug: xx no esta en el catalogo"], ["xx"])
        self.assertEqual({"xx": "no esta en el catalogo"}, estado)

    def test_a_worker_failure_is_kept_until_it_installs(self):
        lineas = ["worker: es-full fallo (HTTP 404), se reintentara"]
        self.assertEqual({"es-full": "fallo (HTTP 404), se reintentara"},
                         devpack.estado_descargas(lineas, ["es-full"]))
        lineas.append("worker: es-full instalado")
        self.assertEqual({"es-full": "instalado"}, devpack.estado_descargas(lineas, ["es-full"]))

    def test_a_missing_catalog_fails_every_pack(self):
        estado = devpack.estado_descargas(["debug: descarga sin catalogo (timeout)"], ["a", "b"])
        self.assertEqual({"a": "sin catalogo", "b": "sin catalogo"}, estado)

    def test_a_prefix_of_another_id_is_not_that_id(self):
        self.assertEqual({}, devpack.estado_descargas(["worker: es-full instalado"], ["es"]))


class VolcadoTest(unittest.TestCase):
    """`list` and `rm` read the app's dump instead of the pack directory."""

    LOG = ["otra cosa", "--- volcado ---", "abiertos=1: es-core@1", "--- fin ---",
           "--- volcado ---", "app versionCode=21", "abiertos=2: es-core@1, en-full@20260910",
           "rechazados=0", "--- fin ---", "despues"]

    def test_the_last_whole_dump_is_read(self):
        volcado = devpack.leer_volcado(self.LOG)
        self.assertEqual("app versionCode=21", volcado[0])
        self.assertEqual("rechazados=0", volcado[-1])

    def test_a_dump_cut_before_its_end_is_not_read_nor_an_older_one(self):
        # An older whole dump describes a state the app already left.
        self.assertIsNone(devpack.leer_volcado(self.LOG[:4] + ["--- volcado ---", "abiertos=0"]))

    def test_the_open_packs_are_the_ids_before_the_version(self):
        self.assertEqual({"es-core", "en-full"}, devpack.abiertos(devpack.leer_volcado(self.LOG)))

    def test_no_open_pack_is_an_empty_set(self):
        self.assertEqual(set(), devpack.abiertos(["abiertos=0"]))


class ArranqueTest(unittest.TestCase):
    """The receiver lives in the Activity: a process a tile woke up has none."""

    def test_a_cold_or_warm_launch_registers_the_receiver_anew(self):
        for estado in ("COLD", "WARM"):
            salida = "Status: ok\nLaunchState: %s\nTotalTime: 812\n" % estado
            self.assertTrue(devpack.receiver_recien_creado(salida))

    def test_an_activity_already_alive_already_listens(self):
        self.assertFalse(devpack.receiver_recien_creado("Status: ok\nLaunchState: UNKNOWN (0)\n"))
        self.assertFalse(devpack.receiver_recien_creado("Status: ok\nLaunchState: HOT\n"))
