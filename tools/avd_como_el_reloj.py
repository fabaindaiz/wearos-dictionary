"""Crea un emulador que reporta lo MISMO que el reloj del proyecto (D-150).

    python3 tools/avd_como_el_reloj.py [--nombre wear_sm_l715f]

**Por que hace falta.** El AVD que trae Android Studio para Wear es `wearos_small_round`:
**384x384 a 320 dpi**, que da `sw192dp`, y ademas `hw.lcd.circular=false`. El reloj del proyecto
--un SM-L715F-- es **498x498 a 340 dpi**, que da `sw234dp`, y es redondo.

O sea que el emulador por defecto **miente en las dos cosas que este repo mas pelea**: el ancho
en dp, contra el que se cotizaron cinco decisiones (D-073, D-075, D-078, D-084, D-085) antes de
medir el reloj de verdad, y la forma redonda, de la que dependen el edge transform y el recorte
de los bordes. Un layout que se ve bien en el emulador por defecto puede estar roto en la muñeca,
y al reves.

**Lo que este AVD reporta, verificado contra el reloj:**

    reloj      ...-sw234dp-w234dp-h234dp-small-notlong-round-...-340dpi-...
    este AVD   ...-sw234dp-w234dp-h234dp-small-notlong-round-...-340dpi-...

Coinciden en todo lo que decide un layout. Difieren en `highdr`/`lowdr` --el rango dinamico de la
pantalla, que no participa de ninguna medida-- y en que el emulador simula una SIM.

⚠️ **Lo que un emulador sigue sin poder decir** (D-043): rendimiento y bateria. El AVD iguala la
GEOMETRIA, no el hardware. Para eso sigue haciendo falta el reloj.
"""

import argparse
import os
import subprocess
import sys

# Lo que hay que imitar, medido con `am get-config` en el reloj el 2026-09-20.
RELOJ = {
    "hw.lcd.width": "498",
    "hw.lcd.height": "498",
    # 340 no es un bucket estandar de Android y no pasa nada: 498 / (340/160) = 234,35 -> 234 dp.
    "hw.lcd.density": "340",
    # El default de los perfiles de Wear es `false`, lo cual es sorprendente en un reloj redondo.
    "hw.lcd.circular": "yes",
    # El pack ingles son 315 MB y la app los abre al arrancar; con el default se nota.
    "hw.ramSize": "1536",
}

# El perfil base. Da 454x454, que se sobreescribe -- pero trae el resto de la definicion de un
# reloj (sin telefonia real, sin camara, los sensores que corresponden).
PERFIL = "wearos_large_round"


def _sdk():
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(var):
            return os.environ[var]
    return os.path.expanduser("~/Library/Android/sdk")


def _imagen(sdk):
    """La system image de Wear mas nueva que este instalada."""
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
    """Reescribe las claves de `config.ini`, agregando las que falten."""
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
