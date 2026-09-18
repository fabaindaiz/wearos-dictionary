"""Sideload de packs de diccionario por adb, para desarrollo.

El instalador de packs esta bloqueado en una decision de producto --donde se hostea el catalogo
(docs/roadmap.md)-- asi que el unico camino real para meter un diccionario en un reloj sigue
siendo adb, y va a seguir siendolo un rato. Esto es esa capa de desarrollo, y NO es el
instalador: no descarga, no verifica catalogos, no sabe de WorkManager ni de D-029.

    python3 tools/devpack.py install <pack.db> [-s SERIAL] [--no-restart] [--verify] [--dry-run]
    python3 tools/devpack.py list   [-s SERIAL]
    python3 tools/devpack.py rm     <pack-id> [-s SERIAL]
    python3 tools/devpack.py devices

POR QUE NO ES UN `adb push` Y YA

Tres razones, y ninguna es comodidad:

1. **Atomicidad.** Se escribe a `<pack>.db.part` y recien al final se renombra. `packsInstalados`
   filtra por extension `.db`, asi que un `.part` es invisible para la app -- la misma convencion
   que ya usa `PackStore.instalarAtomico`. Un push cortado a la mitad directo sobre el `.db`
   deja un pack truncado, **que se abre sin error y devuelve menos palabras de las que tiene**.
   Ese es el sintoma que este repo no puede observar.

2. **Pico de disco.** La ruta `/data/local/tmp` + `cp` duplica el pack en el reloj: 590,2 MiB
   transitorios para el ingles (295,1 MiB x 2). Por defecto se manda el archivo por stdin
   directo al destino, con pico 1x.

   **Medido el 2026-09-17** (emulador wear_api33, adb 1.0.41 / 37.0.1): `adb shell` es
   binary-clean por stdin -- 1 MiB aleatorio da el mismo sha256 de los dos lados. Y las dos
   rutas tardan lo mismo sobre el pack de español de 68,9 MiB: **0,73-0,88 s por el pipe contra
   0,80-0,92 s por tmp**. O sea que **el tiempo no decide nada y el pico de disco decide todo**.
   El fallback (`--tmp`) queda por si un device se porta distinto; que aca no haga falta no dice
   nada de un reloj fisico (D-043).

3. **Se comprueba que llego entero.** sha256 de los dos lados antes de renombrar. Si el device
   no trae `sha256sum`, se compara el tamaño y **se dice**, porque no es lo mismo.

`--verify` corre `verify_pack.py` antes de mandar nada, y **no es el default**: son 3,42 s sobre
el pack de español (146.194 entradas, medido el 2026-09-17), y esa comprobacion pertenece al
build del pack --el `pack-workflow` skill ya la manda-- no a cada instalacion. Lo que este
comando promete es que los bytes llegan intactos, no que el pack este bien construido.

La app no tiene rescan: el escaneo es one-shot en el init del ViewModel. Por eso se hace
force-stop antes y se relanza despues. Eso ademas es el orden correcto: nunca se pisa un `.db`
que la app tiene abierto.

Se usa argparse, a diferencia del resto de los ejecutables de tools/, porque son subcomandos con
flags y a mano queda ilegible. Es stdlib: D-045 se sostiene.
"""

import argparse
import hashlib
import os
import re
import shutil
import sqlite3
import subprocess
import sys
from collections import namedtuple

PAQUETE = "cl.fadiaz.dictionary"
ACTIVITY = PAQUETE + "/.presentation.MainActivity"

# Relativo al directorio de datos de la app: `run-as` hace chdir ahi. Tiene que coincidir con
# PackStore.packsDir (filesDir/packs).
DIR_PACKS = "files/packs"
TMP_REMOTO = "/data/local/tmp"

BLOQUE = 1024 * 1024
UMBRAL_PROGRESO = 4 * 1024 * 1024
RAIZ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# Un pack_id llega a ser un nombre de archivo. Sin esto, uno con "/" o ".." escribe fuera de
# files/packs.
PACK_ID_VALIDO = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")

#: Un paso del plan. `argv` None significa que el paso es local (no habla con el device), y
#: `stdin` es la ruta del archivo que se le manda por entrada estandar, si corresponde.
Paso = namedtuple("Paso", "nombre argv stdin")


class PackInvalido(Exception):
    """El .db local no se puede leer, o su meta no sirve para instalarlo."""


class SinDispositivo(Exception):
    """No hay un dispositivo utilizable, o hay mas de uno y no se dijo cual."""


class FalloRemoto(Exception):
    """Un comando en el device fallo."""


# --------------------------------------------------------------------------- logica pura


def destino(meta):
    """Como se va a llamar el archivo en el reloj: sale de `meta.pack_id`, no del nombre local.

    Que el nombre del archivo y el pack_id coincidan era una convencion no verificada. Derivarlo
    la convierte en una propiedad: dos builds del mismo pack pisan el mismo archivo en vez de
    dejar dos copias que la app abre como dos diccionarios.
    """
    pack_id = (meta.get("pack_id") or "").strip()
    if not pack_id:
        raise PackInvalido("el pack no declara meta.pack_id")
    if not PACK_ID_VALIDO.match(pack_id):
        raise PackInvalido("pack_id no utilizable como nombre de archivo: %r" % pack_id)
    return pack_id + ".db"


def plan_install(adb, pack_local, meta, pipe=True, relanzar=True):
    """La secuencia completa, como valor inspeccionable.

    Es puro a proposito: armar el plan entra al gate, ejecutarlo necesita un dispositivo y no.
    El orden es lo que hay que conservar -- el `mv` al `.db` definitivo va **despues** de
    comparar los hashes, y nada antes toca ese nombre.
    """
    nombre = destino(meta)
    final = "%s/%s" % (DIR_PACKS, nombre)
    parcial = final + ".part"
    tmp = "%s/%s.part" % (TMP_REMOTO, nombre)

    def remoto(nombre_paso, comando, stdin=None):
        # Un solo argumento despues de "shell": adb se lo pasa verbatim a la shell del device.
        # Partirlo deja que la shell LOCAL se coma el redirect, y `cat > files/packs/x` termina
        # escribiendo en el cwd del usuario shell, que no puede escribir en el dir de la app.
        return Paso(nombre_paso, list(adb) + ["shell", comando], stdin)

    def como_app(nombre_paso, comando, stdin=None):
        return remoto(nombre_paso, "run-as %s %s" % (PAQUETE, comando), stdin)

    pasos = [
        remoto("force-stop", "am force-stop %s" % PAQUETE),
        como_app("mkdir", "mkdir -p %s" % DIR_PACKS),
        # Un .part huerfano de un intento anterior arranca con basura y encima ocupa disco.
        como_app("limpiar", "rm -f %s" % parcial),
    ]

    if pipe:
        pasos.append(como_app("escribir", "sh -c 'cat > %s'" % parcial, stdin=pack_local))
    else:
        pasos.append(Paso("push", list(adb) + ["push", pack_local, tmp], None))
        pasos.append(como_app("escribir", "cp %s %s" % (tmp, parcial)))
        # Sin esto el pico de disco de 2x se vuelve permanente.
        pasos.append(remoto("rm-tmp", "rm -f %s" % tmp))

    pasos.append(como_app("sha256-device", "sha256sum %s" % parcial))
    pasos.append(Paso("comparar", None, None))
    # Un pack extraido del APK queda 0600; `cat >` lo crea con el umask de la shell (0666).
    # El directorio es privado igual, pero los dos caminos tienen que dejar el mismo archivo:
    # que la app no los distinga es justamente el diseño.
    pasos.append(como_app("chmod", "chmod 600 %s" % parcial))
    pasos.append(como_app("mv", "mv %s %s" % (parcial, final)))
    if relanzar:
        pasos.append(remoto("relanzar", "am start -n %s" % ACTIVITY))
    return pasos


def elegir_dispositivo(salida, pedido=None):
    """Cual de los dispositivos de `adb devices -l`.

    Este repo pide **un emulador por nivel de API** (33 y 37) porque las versiones de ICU
    difieren. Con dos levantados, `adb` a secas falla con un mensaje que no dice cual elegir.
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
    """Que hacer, dado lo que ya hay en el reloj.

    `remotos` es {nombre_archivo: meta_o_None}. El None es real y no un caso defensivo: sin
    `sqlite3` en el device no hay forma de saber que pack_id tiene cada archivo, y eso se
    informa en vez de adivinarse.
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
    """Que dice la salida de `sha256sum` del device sobre lo que se mando.

    Es la unica comprobacion de que llego entero, asi que vive separada y con test propio:
    "ok" renombra el .part al .db definitivo, cualquier otra cosa no.
    """
    campos = (salida_sha256 or "").split()
    if campos and re.match(r"^[0-9a-f]{64}$", campos[0]):
        return "ok" if campos[0] == esperado else "distinto"
    return "sin-sha256"


def _version(meta):
    valor = str(meta.get("data_version") or "").strip()
    return int(valor) if valor.isdigit() else None


# --------------------------------------------------------------------------- lo que toca el mundo


def buscar_adb():
    """Mismo orden que dict-data/build.gradle.kts: env, local.properties, PATH."""
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
    """La tabla meta del pack local. Read-only, igual que la abre la app y verify_pack.py."""
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
    """Ejecuta un paso remoto y devuelve su stdout.

    Con `con_errores=True` devuelve `(stdout, stderr)`. Hace falta porque **adb manda los
    fallos de `run-as` a stderr**, y quien los quiera detectar no los ve en el stdout.
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
    """Manda el archivo por stdin, con progreso.

    `adb push` muestra progreso solo; por el pipe hay que ponerlo, y con 295 MiB no es adorno.
    """
    total = os.path.getsize(paso.stdin)
    # Con 53 KB el progreso es ruido; con 295 MiB es lo unico que dice que no se colgo.
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
    # `communicate()` despues de cerrar stdin a mano intenta flushearlo y revienta con
    # "flush of closed file". Se leen las dos tuberias a mano: la salida de `cat > archivo` es
    # una linea de error o nada, asi que no hay riesgo de llenar el buffer.
    salida, errores = proceso.stdout.read(), proceso.stderr.read()
    proceso.wait()
    if not silencioso and total:
        sys.stdout.write("\r  enviando  %5.1f %%\n" % 100.0)
    return salida.decode("utf-8", "replace"), errores.decode("utf-8", "replace")


def meta_remota(adb, nombre):
    """Lee la meta de un pack ya instalado, si el device trae sqlite3. Si no, None.

    No es un detalle: sin esto no se puede distinguir "el mismo pack de nuevo" de "otro pack con
    otro nombre", y el aviso de duplicados se degrada a una advertencia generica.
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
    """{nombre: meta_o_None} de lo que hay en files/packs."""
    # Se miran stdout Y stderr: adb manda los fallos de `run-as` a stderr, asi que mirar solo
    # stdout dejaba pasar el caso mas comun --la app no esta instalada-- y el install seguia
    # hasta morir con un BrokenPipeError con 295 MB adentro, sin decir que hacer.
    #
    # Que la app no este es NORMAL, no una rareza: `connectedAndroidTest` la desinstala al
    # terminar, asi que correr los tests y despues instalar un pack es una secuencia de todos
    # los dias.
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
    remotos = packs_remotos(adb)
    if not remotos:
        print("No hay ningun pack en %s/%s" % (PAQUETE, DIR_PACKS))
        return 0
    tamanos = correr(
        Paso("ls", adb + ["shell", "run-as %s ls -l %s" % (PAQUETE, DIR_PACKS)], None),
        silencioso=True,
    )
    print(tamanos.strip())
    print()
    for nombre in sorted(remotos):
        meta = remotos[nombre]
        if meta is None:
            print("  %-24s (sin sqlite3 en el device: no se puede leer su meta)" % nombre)
        else:
            print(
                "  %-24s %s  v%s  %s entradas"
                % (
                    nombre,
                    meta.get("pack_id", "?"),
                    meta.get("data_version", "?"),
                    meta.get("entry_count", "?"),
                )
            )
    return 0


def cmd_rm(args):
    adb = _adb_base(args)
    nombre = args.pack_id if args.pack_id.endswith(".db") else args.pack_id + ".db"
    correr(Paso("force-stop", adb + ["shell", "am force-stop %s" % PAQUETE], None))
    correr(
        Paso("rm", adb + ["shell", "run-as %s rm -f %s/%s" % (PAQUETE, DIR_PACKS, nombre)], None)
    )
    print("borrado: %s" % nombre)
    if not args.no_restart:
        correr(Paso("relanzar", adb + ["shell", "am start -n %s" % ACTIVITY], None))
    return 0


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
    """Que lo que quedo en el reloj sea byte a byte lo que se mando."""
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
    # Sin sha256sum en el device queda el tamaño, que agarra el truncado pero no la corrupcion.
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

    p_list = subs.add_parser("list", help="que packs hay instalados")
    p_list.set_defaults(func=cmd_list)

    p_rm = subs.add_parser("rm", help="borra un pack del reloj")
    p_rm.add_argument("pack_id")
    p_rm.add_argument("--no-restart", action="store_true")
    p_rm.set_defaults(func=cmd_rm)

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
