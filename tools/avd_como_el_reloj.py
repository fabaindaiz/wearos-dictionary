"""Creates an emulator that reports the SAME thing as the project's watch (D-150).

    python3 tools/avd_como_el_reloj.py [--nombre wear_sm_l715f]

**Why it is needed.** The AVD Android Studio ships for Wear is `wearos_small_round`: **384x384 at
320 dpi**, which gives `sw192dp`, and on top of that `hw.lcd.circular=false`. The project's watch
--an SM-L715F-- is **498x498 at 340 dpi**, which gives `sw234dp`, and it is round.

So the default emulator **lies about the two things this repo fights hardest**: the width in dp,
against which five decisions were priced (D-073, D-075, D-078, D-084, D-085) before the real watch
was measured, and the round shape, which the edge transform and the border clipping depend on. A
layout that looks fine on the default emulator can be broken on the wrist, and the other way
round.

**What this AVD reports, verified against the watch:**

    watch      ...-sw234dp-w234dp-h234dp-small-notlong-round-...-340dpi-...
    this AVD   ...-sw234dp-w234dp-h234dp-small-notlong-round-...-340dpi-...

They agree on everything that decides a layout. They differ in `highdr`/`lowdr` --the screen's
dynamic range, which takes part in no measurement-- and in the emulator simulating a SIM.

⚠️ **What an emulator still cannot say** (D-043): performance and battery. The AVD matches the
GEOMETRY, not the hardware. For that the watch is still needed.
"""

import argparse
import os
import subprocess
import sys

# What has to be imitated, measured with `am get-config` on the watch on 2026-09-20.
RELOJ = {
    "hw.lcd.width": "498",
    "hw.lcd.height": "498",
    # 340 no es un bucket estandar de Android y no pasa nada: 498 / (340/160) = 234,35 -> 234 dp.
    "hw.lcd.density": "340",
    # The Wear profiles' default is `false`, which is surprising on a round watch.
    "hw.lcd.circular": "yes",
    # The English pack is 315 MB and the app opens it at startup; with the default it shows.
    "hw.ramSize": "1536",
}

# The base profile. It gives 454x454, which gets overwritten -- but it brings the rest of a
# watch's definition (no real telephony, no camera, the right sensors).
PERFIL = "wearos_large_round"


def _sdk():
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(var):
            return os.environ[var]
    return os.path.expanduser("~/Library/Android/sdk")


def _imagen(sdk):
    """The newest installed Wear system image."""
    base = os.path.join(sdk, "system-images")
    candidatas = []
    for api in sorted(os.listdir(base)) if os.path.isdir(base) else []:
        for tag in sorted(os.listdir(os.path.join(base, api))):
            if "wear" not in tag:
                continue
            for abi in sorted(os.listdir(os.path.join(base, api, tag))):
                candidatas.append("system-images;%s;%s;%s" % (api, tag, abi))
    if not candidatas:
        raise SystemExit(
            "no hay ninguna system image de Wear instalada.\n"
            "  Instalala con: sdkmanager 'system-images;android-37.0;android-wear-signed;arm64-v8a'"
        )
    return candidatas[-1]


def _parchar(config, valores):
    """Rewrites `config.ini`'s keys, adding whichever are missing."""
    with open(config, encoding="utf-8") as handle:
        lineas = handle.read().split("\n")
    puestas = set()
    salida = []
    for linea in lineas:
        clave = linea.split("=", 1)[0] if "=" in linea else None
        if clave in valores:
            salida.append("%s=%s" % (clave, valores[clave]))
            puestas.add(clave)
        else:
            salida.append(linea)
    for clave, valor in valores.items():
        if clave not in puestas:
            salida.append("%s=%s" % (clave, valor))
    with open(config, "w", encoding="utf-8") as handle:
        handle.write("\n".join(salida))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.strip().split("\n")[0])
    parser.add_argument("--nombre", default="wear_sm_l715f")
    args = parser.parse_args(argv)

    sdk = _sdk()
    avdmanager = os.path.join(sdk, "cmdline-tools", "latest", "bin", "avdmanager")
    if not os.path.exists(avdmanager):
        raise SystemExit("no encuentro avdmanager en %s" % avdmanager)

    imagen = _imagen(sdk)
    print("imagen : %s" % imagen)
    print("perfil : %s" % PERFIL)
    subprocess.run(
        [avdmanager, "create", "avd", "-n", args.nombre, "-k", imagen, "-d", PERFIL, "--force"],
        input="no\n", text=True, check=True,
        stdout=subprocess.DEVNULL,
    )
    config = os.path.expanduser("~/.android/avd/%s.avd/config.ini" % args.nombre)
    _parchar(config, RELOJ)
    print("creado : %s" % args.nombre)
    for clave, valor in RELOJ.items():
        print("   %-18s %s" % (clave, valor))
    print("\nArrancalo con:")
    print("  %s/emulator/emulator -avd %s" % (sdk, args.nombre))
    print("\nY comproba que reporta lo mismo que el reloj:")
    print("  adb shell am get-config     # tiene que decir sw234dp ... round ... 340dpi")
    return 0


if __name__ == "__main__":
    sys.exit(main())
