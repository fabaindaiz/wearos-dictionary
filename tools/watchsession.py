"""Keeping a wireless adb session to a watch alive, for the length of a session and no longer.

    python3 tools/watchsession.py status    [-s SERIAL]
    python3 tools/watchsession.py install   [-s SERIAL]      # una vez, o tras recompilar
    python3 tools/watchsession.py start     [-s SERIAL]
    python3 tools/watchsession.py stop      [-s SERIAL]
    python3 tools/watchsession.py probe     [-s SERIAL] [--seconds N] [--out FILE]
    python3 tools/watchsession.py uninstall [-s SERIAL]

WHY THIS EXISTS

A physical watch answers what an emulator cannot (D-043), and the only way to reach one is
wireless adb: the Galaxy Watch charges over pogo pins and has no USB data path. The session dies
on its own, which turns every on-device run into a race against the screen.

WHAT KILLS IT, AND WHAT DOES NOT

**Measured 2026-09-25** on a SM-L715F (Android 17 / SDK 37), off the charger.

The watch suspends its SoC when the screen goes off, and forty seconds later the host declares
the device gone:

    13:42:31 PowerManagerService: Going to sleep due to timeout (screenOffTimeout=30000)
    13:43:11 (adb unreachable)

Two settings were tried first and **both were measured useless**, which is why this command drives
a wake lock instead of writing settings:

- `wifi_always_requested=1` kept the radio up. The mediator decided `toggleRadioState: true` even
  on the `SCREEN_OFF`, and the session died anyway: the radio was never what failed.
- `screen_off_timeout=1800000` did not even keep the screen on. `wakefulness` reached `Dozing`
  after **68 seconds**. On Wear OS ambient is the normal state and does not consult that timeout,
  which is a phone knob; a palm gesture beats it regardless.

Wireless debugging was never switched off either: `adb_wifi_enabled` stayed at 1 throughout. What
does happen is that the daemon later restarts and re-registers on a **different TLS port**
(33017 -> 41093 in that session), which is why a reconnect can look like a new device.

WHAT WORKS

A `PARTIAL_WAKE_LOCK` held by a process on the watch. That is the only primitive that keeps the
CPU running with the screen off, and holding one needs a process, so there is a tiny APK for it:
`:watch-keepalive`, which declares no launcher activity and therefore never shows up among the
watch's apps.

It is **not unkillable**, and nothing on Android is. A foreground service survives doze and
ordinary memory pressure; `force-stop`, a reboot and extreme pressure still end it.

The wake lock expires on its own after an hour (`KeepAliveService.LIMITE_MS`). A debugging session
can simply stop existing -- the laptop closes, the terminal dies -- and a wake lock nobody
releases flattens a watch overnight.

NO IP ADDRESSES BY HAND

The mDNS name (`adb-XXXX._adb-tls-connect._tcp`) survives the daemon restart: the service is
re-registered under the same name on the new port and the host picks it up on its own. When no
device is present at all, this command asks `adb mdns services` and connects to what it finds, so
a port never has to be typed.

READING THE RESULT, NOT THE EXIT CODE

`start` does not trust `am`: it asks `dumpsys power` whether the wake lock is actually held, which
is the thing that matters and the thing a successful-looking `am` does not prove.

`probe` samples reachability, the screen state and `mNumWifiRequests` from
`dumpsys WearConnectivityService`. A run is only valid if the watch is left alone and the charger
state does not change, so both are recorded next to every sample and a disturbed run can be thrown
out rather than believed.
"""

import argparse
import json
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from devpack import SinDispositivo, buscar_adb, elegir_dispositivo  # noqa: E402

RAIZ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PAQUETE = "cl.fadiaz.watchkeepalive"
COMPONENTE = "%s/.KeepAliveService" % PAQUETE
TAG_WAKELOCK = "watchkeepalive:adb"
APK = os.path.join(
    RAIZ, "watch-keepalive", "build", "outputs", "apk", "debug", "watch-keepalive-debug.apk"
)

DIR_ESTADO = os.path.expanduser("~/.cache/wearos-dictionary")

# Settings this command used to write, kept for two reasons: `status` reports them, and a session
# that died mid-flight may have left one set. Nothing here writes them any more -- both were
# measured useless (see the module docstring).
AJUSTES_HISTORICOS = [
    ("system", "screen_off_timeout"),
    ("global", "wifi_always_requested"),
    ("global", "stay_on_while_plugged_in"),
]


def _path_estado(serial):
    # The serial is in the file name, not in the repo: two watches can be held at once, and
    # neither identifier belongs in version control.
    seguro = "".join(c if c.isalnum() or c in "-_" else "_" for c in serial)
    return os.path.join(DIR_ESTADO, "watchsession-%s.json" % seguro)


def _adb(serial, *args, silencioso=False):
    cmd = [buscar_adb(), "-s", serial] + list(args)
    salida = subprocess.run(cmd, capture_output=True)
    if salida.returncode != 0 and not silencioso:
        raise SinDispositivo(salida.stderr.decode("utf-8", "replace").strip())
    return salida.stdout.decode("utf-8", "replace").strip()


def reconectar_por_mdns():
    """Connect to whatever watch mDNS is advertising, so no port is ever typed by hand.

    The daemon comes back on a new TLS port after a restart, so the port from the last session is
    worthless; the service name is not. Returns the endpoint connected to, or None.
    """
    adb = buscar_adb()
    salida = subprocess.run([adb, "mdns", "services"], capture_output=True).stdout.decode(
        "utf-8", "replace"
    )
    for linea in salida.splitlines():
        m = re.search(r"_adb-tls-connect\._tcp\s+(\S+:\d+)", linea)
        if m:
            subprocess.run([adb, "connect", m.group(1)], capture_output=True)
            return m.group(1)
    return None


def elegir_serial(pedido=None):
    adb = buscar_adb()

    def listar():
        return subprocess.run([adb, "devices", "-l"], capture_output=True).stdout.decode(
            "utf-8", "replace"
        )

    try:
        return elegir_dispositivo(listar(), pedido)
    except SinDispositivo:
        if reconectar_por_mdns() is None:
            raise
        return elegir_dispositivo(listar(), pedido)


def instalado(serial):
    salida = _adb(serial, "shell", "pm", "list", "packages", PAQUETE, silencioso=True)
    return PAQUETE in salida


def wakelock_tomado(serial):
    """Whether the lock is actually held, which is not the same as the service having started.

    `am start-foreground-service` reports success for merely delivering the intent. This asks the
    power manager, which is the only place the answer is real.
    """
    salida = _adb(serial, "shell", "dumpsys", "power", silencioso=True)
    return TAG_WAKELOCK in salida


def muestrear(serial):
    """One sample of what decides whether the session survives.

    Everything is read in a single `adb shell` so the values describe the same instant; separate
    round trips would let the screen turn off in between and mix two states into one row.
    """
    consulta = (
        'dumpsys WearConnectivityService | grep -o "mNumWifiRequests=[0-9]*"; '
        'dumpsys power | grep -o "mWakefulness=[A-Za-z]*" | head -1; '
        'dumpsys power | grep -c "%s"; '
        'dumpsys battery | grep -E "AC powered|USB powered|Wireless powered"' % TAG_WAKELOCK
    )
    try:
        salida = _adb(serial, "shell", consulta, silencioso=True)
    except SinDispositivo:
        return None

    muestra = {"wifi_reqs": None, "wakefulness": None, "cargando": False, "lock": False}
    for linea in salida.splitlines():
        linea = linea.strip()
        if linea.startswith("mNumWifiRequests="):
            muestra["wifi_reqs"] = int(linea.split("=", 1)[1])
        elif linea.startswith("mWakefulness="):
            muestra["wakefulness"] = linea.split("=", 1)[1]
        elif linea.isdigit():
            muestra["lock"] = int(linea) > 0
        elif "powered:" in linea and linea.endswith("true"):
            muestra["cargando"] = True
    return muestra if muestra["wifi_reqs"] is not None else None


def _revertir_ajustes(serial):
    """Put back any setting an older version of this command left behind."""
    path = _path_estado(serial)
    if not os.path.exists(path):
        return 0
    with open(path) as f:
        original = json.load(f)
    fallo = False
    for ref, valor in original.items():
        espacio, clave = ref.split("/", 1)
        try:
            if valor is None:
                _adb(serial, "shell", "settings", "delete", espacio, clave, silencioso=True)
            else:
                _adb(serial, "shell", "settings", "put", espacio, clave, valor)
            print("  ajuste %-22s -> %s" % (clave, valor))
        except SinDispositivo:
            fallo = True
            print("  ajuste %-22s NO se pudo revertir" % clave)
    if fallo:
        # The file survives on purpose: the originals are the only copy, and a watch that walked
        # out of range will come back.
        print("\nEl reloj no respondio. El estado sigue en %s." % path)
        return 1
    os.remove(path)
    return 0


def cmd_status(args):
    serial = elegir_serial(args.device)
    print("dispositivo: %s" % serial)
    print("  %-24s = %s" % ("keep-alive instalado", "si" if instalado(serial) else "no"))

    muestra = muestrear(serial)
    if muestra:
        print("  %-24s = %s" % ("wake lock tomado", "si" if muestra["lock"] else "no"))
        print(
            "  %-24s = %s  (0 = arranca el linger y se apaga la radio)"
            % ("mNumWifiRequests", muestra["wifi_reqs"])
        )
        print("  %-24s = %s" % ("pantalla", muestra["wakefulness"]))
        print("  %-24s = %s" % ("cargando", "si" if muestra["cargando"] else "no"))

    for espacio, clave in AJUSTES_HISTORICOS:
        valor = _adb(serial, "shell", "settings", "get", espacio, clave, silencioso=True)
        print("  %-24s = %s" % (clave, valor))

    if os.path.exists(_path_estado(serial)):
        print("\nHay ajustes de una sesion anterior sin revertir; `stop` los devuelve.")
    return 0


def cmd_install(args):
    serial = elegir_serial(args.device)
    if not os.path.exists(APK):
        print(
            "No esta construido el APK. Corre:\n"
            "  ./gradlew :watch-keepalive:assembleDebug",
            file=sys.stderr,
        )
        return 2
    print("dispositivo: %s" % serial)
    print(_adb(serial, "install", "-r", APK))
    return 0


def cmd_start(args):
    serial = elegir_serial(args.device)
    print("dispositivo: %s" % serial)
    if not instalado(serial):
        print(
            "El keep-alive no esta instalado. Corre:\n"
            "  ./gradlew :watch-keepalive:assembleDebug\n"
            "  python3 tools/watchsession.py install",
            file=sys.stderr,
        )
        return 2

    _adb(serial, "shell", "am", "start-foreground-service", "-n", COMPONENTE)
    # `am` reports success for delivering the intent, which is not the same as the lock being
    # held. The service has to reach startForeground first, so give it a moment and then ask the
    # power manager.
    for _ in range(10):
        if wakelock_tomado(serial):
            print("  wake lock tomado (%s)" % TAG_WAKELOCK)
            print("\nParalo con:  python3 tools/watchsession.py stop")
            return 0
        time.sleep(0.5)
    print("El service arranco pero el wake lock NO figura en dumpsys power.", file=sys.stderr)
    return 1


def cmd_stop(args):
    serial = elegir_serial(args.device)
    print("dispositivo: %s" % serial)
    if instalado(serial):
        _adb(serial, "shell", "am", "stopservice", "-n", COMPONENTE, silencioso=True)
        for _ in range(10):
            if not wakelock_tomado(serial):
                print("  wake lock soltado")
                break
            time.sleep(0.5)
        else:
            print("El wake lock SIGUE tomado despues del stop.", file=sys.stderr)
            return 1
    return _revertir_ajustes(serial)


def cmd_uninstall(args):
    serial = elegir_serial(args.device)
    print("dispositivo: %s" % serial)
    print(_adb(serial, "uninstall", PAQUETE, silencioso=True) or "no estaba instalado")
    return 0


def cmd_probe(args):
    """Sample what decides the session's survival, and say whether it held.

    This is the measurement, not the fix: run it once with the keep-alive off and once with it on,
    or a green reading proves nothing about what did the work.
    """
    serial = elegir_serial(args.device)
    destino = args.out or os.path.join(
        DIR_ESTADO, "probe-%s.tsv" % time.strftime("%Y%m%d-%H%M%S")
    )
    os.makedirs(os.path.dirname(os.path.abspath(destino)), exist_ok=True)

    print("keep-alive instalado: %s" % ("si" if instalado(serial) else "no"))
    print("wake lock tomado:     %s" % ("si" if wakelock_tomado(serial) else "no"))
    print("\nNo toques el reloj: despertar la pantalla es justo lo que se esta midiendo.")
    print("Muestreando %d s -> %s\n" % (args.seconds, destino))

    inicio = time.time()
    caidas, filas = 0, []
    with open(destino, "w") as f:
        f.write("ts\tt_s\talcanzable\tlock\twifi_reqs\twakefulness\tcargando\n")
        while True:
            t = int(time.time() - inicio)
            if t >= args.seconds:
                break
            muestra = muestrear(serial)
            if muestra is None:
                caidas += 1
                fila = (time.strftime("%H:%M:%S"), t, "NO", "-", "-", "-", "-")
            else:
                fila = (
                    time.strftime("%H:%M:%S"),
                    t,
                    "SI",
                    "si" if muestra["lock"] else "no",
                    muestra["wifi_reqs"],
                    muestra["wakefulness"],
                    "si" if muestra["cargando"] else "no",
                )
            filas.append(fila)
            f.write("\t".join(str(c) for c in fila) + "\n")
            f.flush()
            print("  %s  t=%-5s alcanzable=%s lock=%s %s" % fila[:5])
            time.sleep(args.interval)

    print("\n%d muestras, %d sin respuesta" % (len(filas), caidas))
    if caidas == 0:
        print("La sesion se mantuvo durante toda la ventana.")
    else:
        print("La sesion NO se mantuvo. Mira la columna lock antes de concluir: si decia")
        print("'no', el keep-alive no estaba sosteniendo nada y la corrida no prueba su falla.")
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Manten viva la sesion adb con el reloj mientras dure la depuracion."
    )
    parser.add_argument("-s", "--device", help="serial, si hay mas de un dispositivo")
    parser.add_argument(
        "--opcional",
        action="store_true",
        help="si no hay reloj, salir en silencio con exito (para hooks)",
    )
    sub = parser.add_subparsers(dest="cmd", required=True)

    sub.add_parser("status", help="que hay instalado y si el lock esta tomado")
    sub.add_parser("install", help="instala el APK del keep-alive")
    sub.add_parser("start", help="toma el wake lock")
    sub.add_parser("stop", help="lo suelta, y revierte ajustes viejos si quedaron")
    sub.add_parser("uninstall", help="saca el APK del reloj")

    p_probe = sub.add_parser("probe", help="mide si la sesion se sostiene")
    p_probe.add_argument("--seconds", type=int, default=300)
    p_probe.add_argument("--interval", type=int, default=15)
    p_probe.add_argument("--out", help="TSV de salida")

    args = parser.parse_args(argv)
    handlers = {
        "status": cmd_status,
        "install": cmd_install,
        "start": cmd_start,
        "stop": cmd_stop,
        "uninstall": cmd_uninstall,
        "probe": cmd_probe,
    }
    try:
        return handlers[args.cmd](args)
    except SinDispositivo as e:
        # A hook runs on every session, and most of them have no watch connected. Failing loudly
        # there would train the user to ignore the message that matters -- the one where the watch
        # IS connected and the lock could not be released.
        if args.opcional:
            return 0
        print(e, file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
