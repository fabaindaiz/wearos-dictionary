"""Getting dictionary packs onto a device over adb, for development.

    python3 tools/devpack.py upload   <app.apk> <pack-id>... [-s SERIAL] [--solo-apk]
    python3 tools/devpack.py download <pack-id>... [-s SERIAL] [--espera S]
    python3 tools/devpack.py list     [-s SERIAL]
    python3 tools/devpack.py rm       <pack-id> [-s SERIAL]
    python3 tools/devpack.py install  <pack.db> [-s SERIAL] [--no-restart] [--verify] [--dry-run]
    python3 tools/devpack.py devices

THE WATCH GETS ITS PACKS BY DOWNLOAD (d-a2f271-d49033)

The watch runs the `benchmark` build: not debuggable, so ART compiles it and it measures what a
user would run, and so `run-as` cannot enter it. `upload`, `download`, `list` and `rm` therefore
go through the app's debug intents (`DebugIntents.kt`) and read its `Dict` log: `packserver.py`
serves the packs on this computer, `adb reverse` puts it at 127.0.0.1 on the device, and the app
downloads them through WorkManager as a manual download -- the path a user takes. Start the server
first: `python3 tools/packserver.py ../wearos-dictionary-data/dist --no-compress`.

`install` is the other road, kept for a debug build on an emulator: it writes the file with
`run-as`, needs no server, and is what the rest of this header is about.

WHY IT IS NOT JUST AN `adb push`

Three reasons, and none of them is convenience:

1. **Atomicity.** It writes to `<pack>.db.part` and only renames at the end. `packsInstalados`
   filters by the `.db` extension, so a `.part` is invisible to the app -- the same convention
   `PackStore.instalarAtomico` already uses. A push cut off half way straight onto the `.db`
   leaves a truncated pack, **which opens with no error and returns fewer words than it holds**.
   That is the symptom this repo cannot observe.

2. **Disk peak.** The `/data/local/tmp` + `cp` route duplicates the pack on the watch: 590.2 MiB
   transient for English (295.1 MiB x 2). By default the file is sent over stdin straight to the
   destination, with a 1x peak.

   **Measured on 2026-09-17** (wear_api33 emulator, adb 1.0.41 / 37.0.1): `adb shell` is
   binary-clean over stdin -- 1 MiB of random data gives the same sha256 on both sides. And the
   two routes take the same time over the 68.9 MiB Spanish pack: **0.73-0.88 s through the pipe
   against 0.80-0.92 s through tmp**. Which means **time decides nothing and the disk peak decides
   everything**. The fallback (`--tmp`) stays in case a device behaves differently; that it is not
   needed here says nothing about a physical watch (D-043).

3. **It checks that it arrived whole.** sha256 on both sides before renaming. If the device does
   not ship `sha256sum`, the size is compared and **it says so**, because they are not the same.

`--verify` runs `verify_pack.py` before sending anything, and **it is not the default**: it is
3.42 s over the Spanish pack (146,194 entries, measured on 2026-09-17), and that check belongs to
the pack's build --the `pack-workflow` skill already requires it-- not to every installation. What
this command promises is that the bytes arrive intact, not that the pack is well built.

The app has no rescan: the scan is one-shot in the ViewModel's init. Hence the force-stop before
and the relaunch after. That is also the right order: a `.db` the app has open is never
overwritten.

argparse is used, unlike the rest of tools/'s executables, because these are subcommands with
flags and doing it by hand comes out unreadable. It is stdlib: D-045 holds.
"""

import argparse
import hashlib
import os
import json
import re
import shutil
import sqlite3
import subprocess
import sys
import time
import urllib.request
from collections import namedtuple

PAQUETE = "cl.fadiaz.dictionary"
ACTIVITY = PAQUETE + "/.presentation.MainActivity"

# Relative to the app's data directory: `run-as` chdirs there. It has to match
# PackStore.packsDir (filesDir/packs).
DIR_PACKS = "files/packs"
TMP_REMOTO = "/data/local/tmp"

BLOQUE = 1024 * 1024
UMBRAL_PROGRESO = 4 * 1024 * 1024
RAIZ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# A pack_id ends up being a file name. Without this, one with "/" or ".." writes outside
# files/packs.
PACK_ID_VALIDO = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")

#: One step of the plan. `argv` None means the step is local (it does not talk to the device), and
#: `stdin` is the path of the file sent to it over standard input, when that applies.
Paso = namedtuple("Paso", "nombre argv stdin")


class PackInvalido(Exception):
    """El .db local no se puede leer, o su meta no sirve para instalarlo."""


class SinDispositivo(Exception):
    """No hay un dispositivo utilizable, o hay mas de uno y no se dijo cual."""


class FalloRemoto(Exception):
    """Un comando en el device fallo."""


# --------------------------------------------------------------------------- logica pura


def destino(meta):
    """What the file will be called on the watch: it comes from `meta.pack_id`, not the local name.

    That the file name and the pack_id matched was an unverified convention. Deriving it turns it
    into a property: two builds of the same pack overwrite the same file instead of leaving two
    copies the app opens as two dictionaries.
    """
    pack_id = (meta.get("pack_id") or "").strip()
    if not pack_id:
        raise PackInvalido("el pack no declara meta.pack_id")
    if not PACK_ID_VALIDO.match(pack_id):
        raise PackInvalido("pack_id no utilizable como nombre de archivo: %r" % pack_id)
    return pack_id + ".db"


def plan_install(adb, pack_local, meta, pipe=True, relanzar=True):
    """The complete sequence, as an inspectable value.

    Pure on purpose: assembling the plan enters the gate, running it needs a device and does not.
    The order is what has to be preserved -- the `mv` to the final `.db` goes **after** comparing
    the hashes, and nothing before that touches that name.
    """
    nombre = destino(meta)
    final = "%s/%s" % (DIR_PACKS, nombre)
    parcial = final + ".part"
    tmp = "%s/%s.part" % (TMP_REMOTO, nombre)

    def remoto(nombre_paso, comando, stdin=None):
        # A single argument after "shell": adb passes it verbatim to the device's shell. Splitting
        # it lets the LOCAL shell eat the redirect, and `cat > files/packs/x` ends up writing into
        # the shell user's cwd, which cannot write into the app's directory.
        return Paso(nombre_paso, list(adb) + ["shell", comando], stdin)

    def como_app(nombre_paso, comando, stdin=None):
        return remoto(nombre_paso, "run-as %s %s" % (PAQUETE, comando), stdin)

    pasos = [
        remoto("force-stop", "am force-stop %s" % PAQUETE),
        como_app("mkdir", "mkdir -p %s" % DIR_PACKS),
        # A `.part` orphaned by an earlier attempt starts with garbage and takes disk on top.
        como_app("limpiar", "rm -f %s" % parcial),
    ]

    if pipe:
        pasos.append(como_app("escribir", "sh -c 'cat > %s'" % parcial, stdin=pack_local))
    else:
        pasos.append(Paso("push", list(adb) + ["push", pack_local, tmp], None))
        pasos.append(como_app("escribir", "cp %s %s" % (tmp, parcial)))
        # Without this the 2x disk peak becomes permanent.
        pasos.append(remoto("rm-tmp", "rm -f %s" % tmp))

    pasos.append(como_app("sha256-device", "sha256sum %s" % parcial))
    pasos.append(Paso("comparar", None, None))
    # A pack extracted from the APK ends up 0600; `cat >` creates it with the shell's umask
    # (0666). The directory is private either way, but both routes have to leave the same file:
    # that the app cannot tell them apart is precisely the design.
    pasos.append(como_app("chmod", "chmod 600 %s" % parcial))
    pasos.append(como_app("mv", "mv %s %s" % (parcial, final)))
    if relanzar:
        pasos.append(remoto("relanzar", "am start -n %s" % ACTIVITY))
    return pasos


def elegir_dispositivo(salida, pedido=None):
    """Which of `adb devices -l`'s devices.

    This repo asks for **one emulator per API level** (33 and 37) because the ICU versions differ.
    With two running, a bare `adb` fails with a message that does not say which to pick.
    """
    listos, otros = [], []
    for linea in salida.splitlines()[1:]:
        linea = linea.strip()
        if not linea or linea.startswith("*"):
            continue
        campos = linea.split()
        if len(campos) < 2:
            continue
        (listos if campos[1] == "device" else otros).append((campos[0], campos[1]))

    seriales = [serial for serial, _ in listos]
    if pedido:
        if pedido in seriales:
            return pedido
        raise SinDispositivo(
            "el dispositivo %s no esta listo. Disponibles: %s"
            % (pedido, ", ".join(seriales) or "ninguno")
        )
    if len(seriales) == 1:
        return seriales[0]
    if not seriales:
        detalle = "".join("\n  %s\t%s" % par for par in otros)
        raise SinDispositivo(
            "No hay ningun dispositivo listo.%s\n\n"
            "Comproba que haya uno con:\n  ./gradlew :dict-data:devicePrecheck" % detalle
        )
    raise SinDispositivo(
        "Hay %d dispositivos conectados: %s\n\nElegi uno con -s <serial>."
        % (len(seriales), ", ".join(seriales))
    )


def decidir(meta_local, remotos):
    """What to do, given what is already on the watch.

    `remotos` is {file_name: meta_or_None}. The None is real and not a defensive case: with no
    `sqlite3` on the device there is no way to know which pack_id each file has, and that gets
    reported rather than guessed.
    """
    nombre = destino(meta_local)
    pack_id = meta_local.get("pack_id")

    for otro, meta in sorted(remotos.items()):
        if meta and otro != nombre and meta.get("pack_id") == pack_id:
            return "colision", (
                "%s ya contiene el pack_id %s. La app abriria los dos y el selector mostraria "
                "el idioma repetido. Borralo primero:\n  python3 tools/devpack.py rm %s"
                % (otro, pack_id, otro[:-3] if otro.endswith(".db") else otro)
            )

    actual = remotos.get(nombre)
    if actual:
        remota, local = _version(actual), _version(meta_local)
        if remota is not None and local is not None and remota > local:
            return "downgrade", (
                "en el reloj hay una version mas nueva: %s contra %s que estas instalando"
                % (actual.get("data_version"), meta_local.get("data_version"))
            )
        return "reinstalar", "%s ya esta instalado; se reemplaza" % nombre

    ciegos = sorted(n for n, m in remotos.items() if m is None)
    if ciegos:
        return "instalar", (
            "no se pudo leer el pack_id de: %s (no hay sqlite3 en el device). Si alguno de esos "
            "es el mismo pack, van a quedar duplicados." % ", ".join(ciegos)
        )
    return "instalar", ""


def comparar_hashes(esperado, salida_sha256):
    """What the device's `sha256sum` output says about what was sent.

    It is the only check that it arrived whole, so it lives apart and with a test of its own:
    "ok" renames the .part to the final .db, anything else does not.
    """
    campos = (salida_sha256 or "").split()
    if campos and re.match(r"^[0-9a-f]{64}$", campos[0]):
        return "ok" if campos[0] == esperado else "distinto"
    return "sin-sha256"


def _version(meta):
    valor = str(meta.get("data_version") or "").strip()
    return int(valor) if valor.isdigit() else None


# ------------------------------------------------------------------ what touches the outside world


def buscar_adb():
    """The same order as dict-data/build.gradle.kts: env, local.properties, PATH."""
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        sdk = os.environ.get(var)
        if sdk:
            candidato = os.path.join(sdk, "platform-tools", "adb")
            if os.access(candidato, os.X_OK):
                return candidato
    propiedades = os.path.join(RAIZ, "local.properties")
    if os.path.isfile(propiedades):
        with open(propiedades) as fuente:
            for linea in fuente:
                if linea.startswith("sdk.dir="):
                    candidato = os.path.join(
                        linea.split("=", 1)[1].strip(), "platform-tools", "adb"
                    )
                    if os.access(candidato, os.X_OK):
                        return candidato
    encontrado = shutil.which("adb")
    if encontrado:
        return encontrado
    raise SinDispositivo(
        "No se encontro adb. Defini ANDROID_HOME, o sdk.dir en local.properties:\n"
        '  echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties'
    )


def leer_meta(ruta):
    """The local pack's meta table. Read-only, the way the app and verify_pack.py open it."""
    if not os.path.isfile(ruta):
        raise PackInvalido("no existe: %s" % ruta)
    try:
        con = sqlite3.connect("file:%s?mode=ro" % ruta, uri=True)
    except sqlite3.Error as error:
        raise PackInvalido("no se pudo abrir %s: %s" % (ruta, error)) from error
    try:
        return dict(con.execute("SELECT key, value FROM meta").fetchall())
    except sqlite3.Error as error:
        raise PackInvalido("%s no parece un pack (sin tabla meta): %s" % (ruta, error)) from error
    finally:
        con.close()


def sha256_local(ruta):
    digest = hashlib.sha256()
    with open(ruta, "rb") as fuente:
        for bloque in iter(lambda: fuente.read(BLOQUE), b""):
            digest.update(bloque)
    return digest.hexdigest()


def correr(paso, silencioso=False, con_errores=False):
    """Runs a remote step and returns its stdout.

    With `con_errores=True` it returns `(stdout, stderr)`. That is needed because **adb sends
    `run-as`'s failures to stderr**, and whoever wants to detect them does not see them in stdout.
    """
    if paso.argv is None:
        return (None, "") if con_errores else None
    if paso.stdin is None:
        proceso = subprocess.run(paso.argv, capture_output=True)
        salida = proceso.stdout.decode("utf-8", "replace")
        errores = proceso.stderr.decode("utf-8", "replace")
    else:
        salida, errores = _enviar(paso, silencioso)
    if errores.strip() and not silencioso:
        print("  ! %s" % errores.strip())
    return (salida, errores) if con_errores else salida


def _enviar(paso, silencioso):
    """Sends the file over stdin, with progress.

    `adb push` shows progress on its own; through the pipe it has to be added, and with 295 MiB
    that is not decoration.
    """
    total = os.path.getsize(paso.stdin)
    # At 53 KB the progress is noise; at 295 MiB it is the only thing saying it has not hung.
    silencioso = silencioso or total < UMBRAL_PROGRESO
    proceso = subprocess.Popen(
        paso.argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE
    )
    enviado = 0
    with open(paso.stdin, "rb") as fuente:
        for bloque in iter(lambda: fuente.read(BLOQUE), b""):
            proceso.stdin.write(bloque)
            enviado += len(bloque)
            if not silencioso and total:
                sys.stdout.write("\r  enviando  %5.1f %%" % (100.0 * enviado / total))
                sys.stdout.flush()
    proceso.stdin.close()
    # `communicate()` after closing stdin by hand tries to flush it and blows up with "flush of
    # closed file". Both pipes are read by hand: the output of `cat > file` is either one error
    # line or nothing, so there is no risk of filling the buffer.
    salida, errores = proceso.stdout.read(), proceso.stderr.read()
    proceso.wait()
    if not silencioso and total:
        sys.stdout.write("\r  enviando  %5.1f %%\n" % 100.0)
    return salida.decode("utf-8", "replace"), errores.decode("utf-8", "replace")


def meta_remota(adb, nombre):
    """Reads an already installed pack's meta, if the device ships sqlite3. Otherwise, None.

    It is not a detail: without this "the same pack again" cannot be told from "another pack under
    another name", and the duplicates warning degrades to a generic one.
    """
    claves = "'pack_id','data_version','entry_count','name'"
    consulta = (
        "run-as %s sqlite3 -separator '=' %s/%s "
        '"select key,value from meta where key in (%s)"' % (PAQUETE, DIR_PACKS, nombre, claves)
    )
    salida = correr(Paso("meta", list(adb) + ["shell", consulta], None), silencioso=True)
    valores = {}
    for linea in (salida or "").splitlines():
        if "=" in linea:
            clave, valor = linea.split("=", 1)
            valores[clave.strip()] = valor.strip()
    return valores if "pack_id" in valores else None


def packs_remotos(adb):
    """{name: meta_or_None} of what is in files/packs."""
    # Both stdout AND stderr are looked at: adb sends `run-as`'s failures to stderr, so looking at
    # stdout alone let the commonest case through --the app is not installed-- and the install
    # carried on until dying with a BrokenPipeError 295 MB in, without saying what to do.
    #
    # The app not being there is NORMAL, not an oddity: `connectedAndroidTest` uninstalls it when
    # it finishes, so running the tests and then installing a pack is an everyday sequence.
    salida, errores = correr(
        Paso("ls", list(adb) + ["shell", "run-as %s ls %s" % (PAQUETE, DIR_PACKS)], None),
        silencioso=True,
        con_errores=True,
    )
    diagnostico = "%s\n%s" % (salida or "", errores or "")
    if "run-as:" in diagnostico or "Permission denied" in diagnostico:
        raise FalloRemoto(
            "run-as fallo: la app no esta instalada, o es un build release.\n"
            "  Instalala con: ./gradlew :app:installDebug\n"
            "  (connectedAndroidTest la desinstala al terminar.)\n  %s"
            % diagnostico.strip()
        )
    nombres = [n.strip() for n in (salida or "").split() if n.strip().endswith(".db")]
    return {nombre: meta_remota(adb, nombre) for nombre in nombres}


# --------------------------------------------------------------------------- subcomandos


def _adb_base(args):
    adb = buscar_adb()
    salida = subprocess.run([adb, "devices", "-l"], capture_output=True).stdout.decode(
        "utf-8", "replace"
    )
    return [adb, "-s", elegir_dispositivo(salida, args.device)]


def cmd_devices(args):
    adb = buscar_adb()
    salida = subprocess.run([adb, "devices", "-l"], capture_output=True).stdout.decode(
        "utf-8", "replace"
    )
    print("adb: %s" % adb)
    print(salida.strip())
    try:
        print("\nElegido: %s" % elegir_dispositivo(salida, args.device))
    except SinDispositivo as error:
        print("\n%s" % error)
        return 1
    return 0


def cmd_list(args):
    adb = _adb_base(args)
    _app_escuchando(adb)
    for linea in _volcado(adb):
        print(linea)
    return 0


def cmd_rm(args):
    pack_id = _id_de_pack(args.pack_id)
    adb = _adb_base(args)
    _app_escuchando(adb)
    _difundir(adb, "DELETE", "-e packs %s" % pack_id)
    # The deletion closes and reopens every pack on its own coroutine: the dump is asked again
    # until it stops listing the pack, rather than trusting the first one.
    for _ in range(10):
        if pack_id not in abiertos(_volcado(adb)):
            print("borrado: %s" % pack_id)
            return 0
        time.sleep(1)
    print("%s sigue abierto: no estaba instalado, o viene en el APK y no se borra" % pack_id)
    return 1


def cmd_install(args):
    meta = leer_meta(args.pack)
    nombre = destino(meta)
    tamano = os.path.getsize(args.pack)
    print(
        "%s -> %s  (%s, v%s, %s entradas, %.1f MiB)"
        % (
            os.path.basename(args.pack),
            nombre,
            meta.get("pack_id"),
            meta.get("data_version"),
            meta.get("entry_count", "?"),
            tamano / 1048576.0,
        )
    )

    if args.verify:
        verify = os.path.join(RAIZ, "tools", "packbuilder", "verify_pack.py")
        print("  verify_pack.py ...")
        if subprocess.run([sys.executable, verify, args.pack]).returncode != 0:
            print("verify_pack.py fallo: no se instala un pack que no cumple sus invariantes.")
            return 1

    if args.dry_run:
        adb = ["adb", "-s", args.device or "<serial>"]
        for paso in plan_install(adb, args.pack, meta, not args.tmp, not args.no_restart):
            print("  %-14s %s" % (paso.nombre, " ".join(paso.argv) if paso.argv else "(local)"))
        return 0

    adb = _adb_base(args)
    accion, mensaje = decidir(meta, packs_remotos(adb))
    if mensaje:
        print("  %s" % mensaje)
    if accion == "colision":
        return 1
    if accion == "downgrade" and not args.force:
        print("  Usa --force si es a proposito.")
        return 1

    esperado = sha256_local(args.pack)
    remoto_sha = None
    for paso in plan_install(adb, args.pack, meta, not args.tmp, not args.no_restart):
        if paso.nombre == "comparar":
            if not _comprobar(adb, meta, esperado, tamano, remoto_sha):
                return 1
            continue
        salida = correr(paso)
        if paso.nombre == "sha256-device":
            remoto_sha = salida
    print("instalado: %s" % nombre)
    return 0


def _comprobar(adb, meta, esperado, tamano, salida_sha):
    """That what ended up on the watch is byte for byte what was sent."""
    parcial = "%s/%s.part" % (DIR_PACKS, destino(meta))
    veredicto = comparar_hashes(esperado, salida_sha)
    if veredicto == "ok":
        print("  sha256    ok  %s" % esperado[:16])
        return True
    if veredicto == "distinto":
        print(
            "  sha256    NO COINCIDE: local %s, device %s"
            % (esperado[:16], salida_sha.split()[0][:16])
        )
        return False
    # With no sha256sum on the device the size is what is left, which catches truncation but not
    # corruption.
    salida = correr(
        Paso("stat", adb + ["shell", "run-as %s stat -c %%s %s" % (PAQUETE, parcial)], None),
        silencioso=True,
    )
    digitos = (salida or "").strip()
    if digitos.isdigit() and int(digitos) == tamano:
        print(
            "  AVISO: el device no trae sha256sum. Solo se comparo el TAMANO (%d bytes):\n"
            "  eso detecta un pack truncado, NO uno corrupto." % tamano
        )
        return True
    print("  el tamaño no coincide: local %d, device %r" % (tamano, digitos))
    return False


#: One step of `upload`. `siempre` runs even after an earlier step failed: the session's stop.
PasoDeSubida = namedtuple("PasoDeSubida", "nombre argv siempre")

#: The session tool, cloned beside this repository (d-a2f271-ab8cd7).
SESION = os.path.join(os.path.dirname(RAIZ), "wearos-debug-tool", "watchsession.py")


#: Where `packserver.py` listens on this computer, and where the device reaches it through
#: `adb reverse`: the same address on an emulator and on a watch over Wi-Fi adb, and the only host
#: the `benchmark` build talks to in clear text (`network_security_config.xml`).
PUERTO = 8765
CATALOGO_LOCAL = "http://127.0.0.1:%d" % PUERTO

#: The app's debug intents (`DebugIntents.kt`), which read and change its packs without `run-as`.
ACCION = PAQUETE + ".DEBUG_"


def _id_de_pack(arg):
    """`es-full`, `es-full.db` or a path to it: the pack id a catalog knows."""
    nombre = os.path.basename(arg)
    for sufijo in (".db.gz", ".db"):
        if nombre.endswith(sufijo):
            nombre = nombre[: -len(sufijo)]
    if not PACK_ID_VALIDO.match(nombre):
        raise PackInvalido("pack id invalido: %r" % arg)
    return nombre


def _exige_packs(packs, solo_apk):
    if not packs and not solo_apk:
        raise PackInvalido("una subida sin packs completos deja vacio lo que leen de ellos; "
                           "pasa los pack ids o --solo-apk si es a proposito")


def plan_upload(serial, apk, packs, sesion=SESION, solo_apk=False, adb="adb"):
    """The upload: the APK, then its packs by download, and on a watch the session stopped whatever fails.

    The APK goes first because asking for a download needs its receiver, and the app reloads on each
    finished one. An APK without packs is refused unless asked for: the list of *see more words*
    reads the full pack, so a new APK over old full packs shows a tier with no words
    (`LOCAL/todas es-full=0`). An emulator needs no session: nothing there turns its radio off.
    """
    _exige_packs(packs, solo_apk)
    ids = [_id_de_pack(p) for p in packs]
    yo = [sys.executable, os.path.abspath(__file__), "-s", serial]
    bajar = [PasoDeSubida("packs", yo + ["download"] + ids, False)] if ids else []
    if serial.startswith("emulator-"):
        return [
            PasoDeSubida("app", [adb, "-s", serial, "install", "-r", apk], False),
            PasoDeSubida("arranque", [adb, "-s", serial, "shell", "am start -W -n " + ACTIVITY], False),
        ] + bajar
    tool = [sys.executable, sesion, "-s", serial]
    pasos = [PasoDeSubida("sesion", tool + ["start"], False)]
    pasos.append(PasoDeSubida("app", tool + ["deploy", apk, "--actividad", ACTIVITY, "--tag", "Dict",
                                             "--listo", "arranque", "--espera", "240"], False))
    pasos += bajar
    pasos.append(PasoDeSubida("cierre", tool + ["stop"], True))
    return pasos


def ejecutar_subida(pasos, correr):
    """Runs the plan with `correr(argv) -> exit code`; returns the first failed step's name, or None.

    After a failure only the steps marked `siempre` run.
    """
    fallo = None
    for paso in pasos:
        if fallo is not None and not paso.siempre:
            continue
        print("== %s" % paso.nombre)
        if correr(paso.argv) != 0 and fallo is None:
            fallo = paso.nombre
    return fallo


_INSTALADO = re.compile(r"worker: (\S+) instalado$")
_FALLO = re.compile(r"worker: (\S+) (fallo .*)$")
_FUERA = re.compile(r"debug: (\S+) no esta en el catalogo$")
_SIN_CATALOGO = "debug: descarga sin catalogo"
#: The states that end the wait. A failure does not: the worker retries it.
FINALES = ("instalado", "no esta en el catalogo", "sin catalogo")


def estado_descargas(lineas, ids):
    """What the `Dict` log says of each pack id asked for; an id it has not named is absent."""
    estado = {}
    for linea in lineas:
        linea = linea.strip()
        if _SIN_CATALOGO in linea:
            estado.update({i: "sin catalogo" for i in ids if estado.get(i) != "instalado"})
            continue
        m = _INSTALADO.search(linea)
        if m and m.group(1) in ids:
            estado[m.group(1)] = "instalado"
            continue
        m = _FALLO.search(linea)
        if m and m.group(1) in ids:
            estado[m.group(1)] = m.group(2)
            continue
        m = _FUERA.search(linea)
        if m and m.group(1) in ids:
            estado[m.group(1)] = "no esta en el catalogo"
    return estado


def leer_volcado(lineas):
    """The lines of the LAST dump in the log, or None when it is cut before its end."""
    lineas = [l.strip() for l in lineas]
    inicios = [i for i, l in enumerate(lineas) if l.endswith("--- volcado ---")]
    if not inicios:
        return None
    resto = lineas[inicios[-1] + 1:]
    fin = next((i for i, l in enumerate(resto) if l.endswith("--- fin ---")), None)
    return None if fin is None else resto[:fin]


def abiertos(volcado):
    """The pack ids a dump says are open: `abiertos=2: es-core@1, en-full@20260910`."""
    for linea in volcado:
        if linea.startswith("abiertos="):
            _, _, lista = linea.partition(": ")
            return {p.split("@")[0].strip() for p in lista.split(",") if p.strip()}
    return set()


def _difundir(adb, accion, extras=""):
    return correr(Paso("broadcast " + accion, adb + [
        "shell", ("am broadcast -p %s -a %s%s %s" % (PAQUETE, ACCION, accion, extras)).strip()], None))


def _hora_del_device(adb):
    return correr(Paso("date", adb + ["shell", "date +'%m-%d %H:%M:%S.000'"], None), silencioso=True).strip()


def _leer_log(adb, desde, basta, espera):
    """Reads `Dict` from `desde` until `basta(lineas)` or `espera` seconds; returns (lineas, basto).

    It polls `logcat -d` once a second rather than streaming: a streaming logcat into a pipe holds
    a few short lines back until a block fills, so a dump of ten lines never arrived while a
    download's hundreds did. `adb logcat` takes its arguments without a shell, unlike
    `adb shell`, so the time goes unquoted.
    """
    lineas = []
    fin = time.time() + espera
    while True:
        r = subprocess.run(adb + ["logcat", "-d", "-v", "raw", "-s", "Dict:V", "-T", desde],
                           capture_output=True)
        if r.returncode != 0:
            raise FalloRemoto("logcat fallo: %s" % r.stderr.decode("utf-8", "replace").strip())
        lineas = [l.rstrip() for l in r.stdout.decode("utf-8", "replace").splitlines()]
        if basta(lineas):
            return lineas, True
        if time.time() >= fin:
            return lineas, False
        time.sleep(1)


def receiver_recien_creado(salida_am_start):
    """Whether `am start -W` created the Activity, and with it the debug receiver: COLD or WARM.

    The receiver is registered by the Activity, not the process: a process a tile woke up answers
    `pidof` and listens to nothing, so a broadcast sent to it is lost without a word.
    """
    m = re.search(r"LaunchState: (\w+)", salida_am_start)
    return bool(m) and m.group(1) in ("COLD", "WARM")


def _app_escuchando(adb):
    """Brings the Activity up and, when that created it, waits for its receiver to register."""
    desde = _hora_del_device(adb)
    salida = correr(Paso("arrancar", adb + ["shell", "am start -W -n %s" % ACTIVITY], None), silencioso=True)
    if not receiver_recien_creado(salida):
        return
    _, listo = _leer_log(adb, desde, lambda ls: any("debug intents ACTIVE" in l for l in ls), 60)
    if not listo:
        raise FalloRemoto("la app no registro los debug intents en 60 s: es un build release?")


def _volcado(adb, espera=20):
    desde = _hora_del_device(adb)
    _difundir(adb, "DUMP")
    lineas, _ = _leer_log(adb, desde, lambda ls: leer_volcado(ls) is not None, espera)
    volcado = leer_volcado(lineas)
    if volcado is None:
        raise FalloRemoto("la app no volco su estado en %d s" % espera)
    return volcado


def _indice_local():
    try:
        with urllib.request.urlopen(CATALOGO_LOCAL + "/index.json", timeout=5) as r:
            return json.load(r)
    except OSError as error:
        raise FalloRemoto("packserver no responde en %s (%s). Levantalo:\n"
                          "  python3 tools/packserver.py <dist> --no-compress" % (CATALOGO_LOCAL, error))


def cmd_download(args):
    ids = [_id_de_pack(p) for p in args.packs]
    servidos = {p.get("pack_id") for p in _indice_local().get("packs", [])}
    faltan = [i for i in ids if i not in servidos]
    if faltan:
        raise PackInvalido("packserver no sirve %s; sirve: %s"
                           % (", ".join(faltan), ", ".join(sorted(s for s in servidos if s))))
    adb = _adb_base(args)
    correr(Paso("reverse", adb + ["reverse", "tcp:%d" % PUERTO, "tcp:%d" % PUERTO], None), silencioso=True)
    _app_escuchando(adb)
    desde = _hora_del_device(adb)
    _difundir(adb, "SET", "-e catalog %s" % CATALOGO_LOCAL)
    _difundir(adb, "DOWNLOAD", "-e packs %s" % ",".join(ids))
    lineas, _ = _leer_log(
        adb, desde,
        lambda ls: all(estado_descargas(ls, ids).get(i, "").startswith(FINALES) for i in ids),
        args.espera)
    estado = estado_descargas(lineas, ids)
    for i in ids:
        print("  %-20s %s" % (i, estado.get(i, "sin noticias en %d s" % args.espera)))
    return 0 if all(estado.get(i) == "instalado" for i in ids) else 1


def cmd_upload(args):
    _exige_packs(args.packs, args.solo_apk)
    adb = buscar_adb()
    serial = elegir_dispositivo(
        subprocess.run([adb, "devices", "-l"], capture_output=True).stdout.decode("utf-8", "replace"),
        args.device)
    pasos = plan_upload(serial, args.apk, args.packs, args.sesion, args.solo_apk, adb)
    fallo = ejecutar_subida(pasos, lambda argv: subprocess.run(argv).returncode)
    if fallo is not None:
        print("fallo en: %s%s" % (fallo, "" if serial.startswith("emulator-") else " (la sesion se cerro igual)"))
        return 1
    return 0


def main(argv):
    parser = argparse.ArgumentParser(
        prog="devpack", description="Instala packs de diccionario en un reloj por adb."
    )
    parser.add_argument("-s", "--device", help="serial del dispositivo (adb devices)")
    subs = parser.add_subparsers(dest="comando")

    p_install = subs.add_parser("install", help="instala o actualiza un pack")
    p_install.add_argument("pack", help="el .db construido por build_pack.py")
    p_install.add_argument("--no-restart", action="store_true", help="no relanzar la app")
    p_install.add_argument("--verify", action="store_true", help="correr verify_pack.py antes")
    p_install.add_argument("--tmp", action="store_true", help="usar /data/local/tmp (pico 2x)")
    p_install.add_argument("--force", action="store_true", help="instalar aunque sea downgrade")
    p_install.add_argument("--dry-run", action="store_true", help="imprimir el plan y salir")
    p_install.set_defaults(func=cmd_install)

    p_list = subs.add_parser("list", help="que packs tiene abiertos la app (su volcado)")
    p_list.set_defaults(func=cmd_list)

    p_rm = subs.add_parser("rm", help="borra un pack, por la misma puerta que Ajustes")
    p_rm.add_argument("pack_id")
    p_rm.set_defaults(func=cmd_rm)

    p_download = subs.add_parser("download", help="la app baja packs de packserver, como un usuario")
    p_download.add_argument("packs", nargs="+", help="pack ids que sirve packserver")
    p_download.add_argument("--espera", type=int, default=900, help="segundos para que terminen")
    p_download.set_defaults(func=cmd_download)

    p_upload = subs.add_parser("upload", help="el APK, luego sus packs por descarga, y la sesion cerrada")
    p_upload.add_argument("apk")
    p_upload.add_argument("packs", nargs="*", help="pack ids que van con este APK (packserver los sirve)")
    p_upload.add_argument("--solo-apk", action="store_true", help="subir el APK sin packs")
    p_upload.add_argument("--sesion", default=SESION, help="watchsession.py")
    p_upload.set_defaults(func=cmd_upload)

    p_devices = subs.add_parser("devices", help="que dispositivos ve adb")
    p_devices.set_defaults(func=cmd_devices)

    args = parser.parse_args(argv[1:])
    if not getattr(args, "func", None):
        parser.print_help()
        return 2
    try:
        return args.func(args)
    except (PackInvalido, SinDispositivo, FalloRemoto) as error:
        print("%s" % error)
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
