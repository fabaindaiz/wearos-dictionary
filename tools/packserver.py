#!/usr/bin/env python3
"""Servidor de archivos de DESARROLLO para probar la descarga de packs.

No es el instalador ni el catalogo de produccion: es lo que falta para poder ejercitar el
camino de descarga sin haber decidido donde se hostea nada (roadmap, Instalador de packs).
Sirve un directorio de packs y **genera el indice leyendo los packs mismos**.

⚠️ **El indice se genera, no se escribe a mano, y eso es correctitud y no comodidad.** Cada
campo sale de la tabla `meta` del `.db` o de medir el archivo, asi que no puede divergir de lo
que se sirve. Un catalogo a mano que dice `data_version` 202609211937 para un archivo que ya es
otro es exactamente el fallo que este repo no puede observar: la app se cree la version y no
vuelve a descargar.

## Lo que el indice lleva, y por que cada cosa

- `schema_version` y `norm_version` **antes de la url**: son las dos que hacen que un pack se
  rechace entero al abrirlo (D-001, D-006). Publicarlas deja que la app descarte un pack
  incompatible **sin descargar 192 MB para tirarlos**.
- `data_version`: el entero monotono que ya escribe el builder. Es con lo que se decide
  "actualizar" contra "descargar".
- **Dos hashes**: `sha256` es del `.db.gz` que viaja, `db_sha256` del `.db` que queda en disco.
  Hacen falta los dos porque se verifica en dos momentos -- al terminar la transferencia y
  despues de descomprimir -- y `installAtomically` compara contra el segundo (D-165).
- `name`, `description`, `entry_count`, `license`: lo que la pantalla de descarga muestra. Sale
  del pack para que nadie lo reescriba distinto.

⚠️ **Un pack de esquema viejo se publica igual, y es a proposito.** `es-def-wd` es
`schema_version` 3 y por eso **no trae `langs`** --ese campo nacio con el 4-- pero aparece en el
indice con su version. La app lo descarta sin descargar, que es exactamente para lo que se
publican esas dos versiones, y en desarrollo sirve para ejercitar ese camino.

## Por que se sirve `.db.gz` como archivo opaco y no con `Content-Encoding: gzip`

Medido: el pack espanol pasa de 73,6 a ~37 MB y el ingles de 306,8 a ~192. Pero con
`Content-Encoding` la descompresion es transparente, y entonces `Range` corre sobre el flujo
**descomprimido**: no se puede reanudar, y `Content-Length` miente. Sobre un reloj que descarga
192 MB solo mientras carga, perder la reanudacion es peor que el ahorro. Asi que el `.gz` viaja
como un archivo mas y la app lo descomprime a mano.

⚠️ **Y se sirve TAMBIEN el `.db` en crudo, a proposito.** Una actualizacion delta por bloques
(zsync) necesita `Range` sobre bytes que se parezcan a la version anterior, y un `.gz` normal no
se parece en nada tras el primer byte que cambia. Dejar el crudo disponible no cuesta nada en una
maquina de desarrollo y es lo que mantiene esa puerta abierta. Ver `docs/roadmap.md`.

## Uso

    python3 tools/packserver.py ../wearos-dictionary-data --port 8765
    # y en el reloj / emulador:  http://<ip-de-esta-maquina>:8765/index.json
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import os
import re
import shutil
import socket
import sqlite3
import sys
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

BUFFER = 1 << 20
CATALOG = "index.json"
PACKS_DIR = "packs"

# Los campos de `meta` que van al indice, con el tipo al que se convierten. Lo que no este aca
# no se publica: `payload_dict` son 32 KB de binario y no tiene nada que hacer en un catalogo.
META_FIELDS = {
    "pack_id": str,
    "name": str,
    "description": str,
    "langs": str,
    "kind": str,
    "tier": str,
    "entry_count": int,
    "data_version": int,
    "schema_version": int,
    "norm_version": int,
    "license": str,
}
# ⚠️ `attribution` NO se publica, a proposito: son ~600 caracteres por pack --el nucleo espanol
# cita cinco fuentes-- y **el pack ya lo lleva dentro**, asi que la app lo lee despues de
# instalar. En el indice solo duplicaria el dato y engordaria lo que el reloj baja al apretar el
# boton. `license` si va, porque es corto y la pantalla lo muestra antes de descargar.


def sha256_of(path: str) -> str:
    """El sha256 de un archivo, leido por trozos: los packs no caben en memoria."""
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(BUFFER), b""):
            digest.update(chunk)
    return digest.hexdigest()


def pack_metadata(db_path: str) -> dict:
    """La tabla `meta` de un pack, solo los campos publicables.

    Se abre **read-only por URI**: el pack es inmutable (D-001) y abrirlo de otra forma podria
    dejarle un `-wal` al lado, que en un directorio que se sirve seria basura publicada.
    """
    uri = f"file:{db_path}?mode=ro"
    with sqlite3.connect(uri, uri=True) as conn:
        rows = dict(conn.execute("SELECT key, value FROM meta").fetchall())
    out = {}
    for key, kind in META_FIELDS.items():
        if key not in rows or rows[key] is None:
            continue
        value = rows[key]
        out[key] = int(value) if kind is int else str(value)
    if "langs" in out:
        out["langs"] = [x for x in out["langs"].split(",") if x]
    return out


def gzip_if_stale(db_path: str, gz_path: str) -> bool:
    """Comprime el pack si el `.gz` falta o es mas viejo. Devuelve si hizo trabajo.

    Comprimir el ingles son varios minutos, asi que no se rehace en cada arranque. El criterio
    es la fecha de modificacion, no el hash: calcular el hash de 306 MB para decidir si hay que
    comprimir 306 MB cuesta casi lo mismo que comprimirlos.
    """
    if os.path.exists(gz_path) and os.path.getmtime(gz_path) >= os.path.getmtime(db_path):
        return False
    partial = gz_path + ".part"
    with open(db_path, "rb") as src, gzip.open(partial, "wb", compresslevel=6) as dst:
        shutil.copyfileobj(src, dst, BUFFER)
    os.replace(partial, gz_path)
    return True


def catalog_entry(db_path: str, gz_path: str) -> dict:
    """Una fila del indice: lo que dice el pack, mas lo que miden los dos archivos."""
    entry = pack_metadata(db_path)
    entry.update(
        url=f"{PACKS_DIR}/{os.path.basename(gz_path)}",
        bytes=os.path.getsize(gz_path),
        sha256=sha256_of(gz_path),
        db_url=f"{PACKS_DIR}/{os.path.basename(db_path)}",
        db_bytes=os.path.getsize(db_path),
        db_sha256=sha256_of(db_path),
    )
    return entry


def is_pack(name: str) -> bool:
    """Un `.db` que no sea un respaldo.

    ⚠️ El directorio de datos tiene `en-def-wikt.OLD.db` y `es-tr-enwikt.OLD2.db` al lado de los
    buenos. Publicarlos serviria packs viejos como si fueran el catalogo.
    """
    return name.endswith(".db") and ".OLD" not in name


def build_catalog(directory: str, compress: bool = True) -> dict:
    """El indice completo del directorio, ordenado por `pack_id` para que sea diffeable."""
    packs = []
    for name in sorted(os.listdir(directory)):
        if not is_pack(name):
            continue
        db_path = os.path.join(directory, name)
        gz_path = db_path + ".gz"
        if compress:
            gzip_if_stale(db_path, gz_path)
        if not os.path.exists(gz_path):
            continue
        packs.append(catalog_entry(db_path, gz_path))
    packs.sort(key=lambda p: p.get("pack_id", ""))
    return {"catalog_version": 1, "packs": packs}


def etag_for(payload: bytes) -> str:
    """Un ETag fuerte del cuerpo.

    Sirve para que apretar el boton dos veces cueste un 304 sin cuerpo en vez del indice
    entero. Es lo unico que hace falta para eso: el indice es chico y se regenera igual.
    """
    return '"' + hashlib.sha256(payload).hexdigest()[:32] + '"'


RANGE_RE = re.compile(r"^bytes=(\d*)-(\d*)$")


def parse_range(header: str | None, size: int) -> tuple[int, int] | None:
    """Traduce un `Range: bytes=...` a `(primero, ultimo)` inclusivo, o `None`.

    ⚠️ **`SimpleHTTPRequestHandler` no implementa `Range`**, y sin esto una caida de Wi-Fi al
    90 % de 192 MB obliga a empezar de cero. D-040 eligio `HttpURLConnection` justamente porque
    hace `Range`; un servidor que no lo soporta hace inverificable esa decision.

    Las tres formas de RFC 9110 §14.1.1, y un `None` para todo lo demas:

    - `bytes=0-99`   los primeros 100
    - `bytes=100-`   desde el 100 hasta el final  (el caso de reanudar)
    - `bytes=-100`   los ultimos 100

    Devuelve `None` cuando no hay cabecera o no se entiende --se responde 200 completo-- y
    `(-1, -1)` cuando se entiende pero **no se puede satisfacer**, que es un 416 distinto.
    """
    if not header:
        return None
    match = RANGE_RE.match(header.strip())
    if not match:
        return None
    first, last = match.group(1), match.group(2)
    if not first and not last:
        return None
    if not first:  # sufijo: los ultimos N bytes
        length = int(last)
        if length == 0:
            return (-1, -1)
        return (max(0, size - length), size - 1)
    start = int(first)
    if start >= size:
        return (-1, -1)
    end = size - 1 if not last else min(int(last), size - 1)
    if end < start:
        return (-1, -1)
    return (start, end)


def local_ips() -> list[str]:
    """Las IPs por las que el reloj puede llegar a esta maquina.

    `localhost` no sirve: el reloj esta en la otra punta del Wi-Fi.
    """
    out = []
    try:
        probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        probe.connect(("8.8.8.8", 80))  # no manda nada; solo elige la interfaz de salida
        out.append(probe.getsockname()[0])
        probe.close()
    except OSError:
        pass
    return out


class PackHandler(SimpleHTTPRequestHandler):
    """Sirve el directorio con `Range` y `ETag`, que es lo que le falta al de la stdlib."""

    catalog: dict = {}
    root: str = "."

    def log_message(self, fmt, *args):  # noqa: A003 - firma de la stdlib
        sys.stderr.write("  %s\n" % (fmt % args))

    def do_GET(self):  # noqa: N802 - firma de la stdlib
        if self.path.rstrip("/") in ("", "/index.json", "/" + CATALOG):
            return self._send_catalog()
        return self._send_file()

    def do_HEAD(self):  # noqa: N802
        if self.path.rstrip("/") in ("", "/index.json", "/" + CATALOG):
            return self._send_catalog(body=False)
        return self._send_file(body=False)

    def _send_catalog(self, body: bool = True):
        payload = json.dumps(self.catalog, ensure_ascii=False, indent=1).encode("utf-8")
        tag = etag_for(payload)
        if self.headers.get("If-None-Match") == tag:
            self.send_response(304)
            self.send_header("ETag", tag)
            self.end_headers()
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("ETag", tag)
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        if body:
            self.wfile.write(payload)

    def _resolve(self) -> str | None:
        """La ruta pedida dentro de la raiz, o `None` si se sale de ella."""
        rel = self.path.split("?", 1)[0].lstrip("/")
        if rel.startswith(PACKS_DIR + "/"):
            rel = rel[len(PACKS_DIR) + 1 :]
        target = os.path.realpath(os.path.join(self.root, rel))
        if os.path.commonpath([target, os.path.realpath(self.root)]) != os.path.realpath(self.root):
            return None
        return target if os.path.isfile(target) else None

    def _send_file(self, body: bool = True):
        target = self._resolve()
        if not target:
            self.send_error(404)
            return
        size = os.path.getsize(target)
        span = parse_range(self.headers.get("Range"), size)
        if span == (-1, -1):
            self.send_response(416)
            self.send_header("Content-Range", f"bytes */{size}")
            self.end_headers()
            return
        start, end = span if span else (0, size - 1)
        self.send_response(206 if span else 200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(end - start + 1))
        if span:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        if not body:
            return
        with open(target, "rb") as handle:
            handle.seek(start)
            left = end - start + 1
            while left > 0:
                chunk = handle.read(min(BUFFER, left))
                if not chunk:
                    break
                self.wfile.write(chunk)
                left -= len(chunk)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("directory", help="donde estan los .db")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument(
        "--no-compress",
        action="store_true",
        help="no generar los .db.gz que falten (sirve solo lo que ya este)",
    )
    parser.add_argument(
        "--index-only",
        action="store_true",
        help="imprime el indice y sale, sin levantar el servidor",
    )
    args = parser.parse_args(argv)

    directory = os.path.abspath(args.directory)
    if not os.path.isdir(directory):
        print(f"no existe el directorio {directory}", file=sys.stderr)
        return 1

    print(f"leyendo packs de {directory} ...", file=sys.stderr)
    catalog = build_catalog(directory, compress=not args.no_compress)
    if not catalog["packs"]:
        print("no se encontro ni un .db publicable", file=sys.stderr)
        return 1
    for pack in catalog["packs"]:
        print(
            f"  {pack['pack_id']:22} v{pack.get('data_version')} "
            f"{pack['bytes'] / 1048576:7.1f} MB gz  ({pack['db_bytes'] / 1048576:.1f} MB db)",
            file=sys.stderr,
        )

    if args.index_only:
        print(json.dumps(catalog, ensure_ascii=False, indent=1))
        return 0

    PackHandler.catalog = catalog
    PackHandler.root = directory
    server = ThreadingHTTPServer(("0.0.0.0", args.port), PackHandler)
    for ip in local_ips():
        print(
            f"\n  el indice, desde el reloj:  http://{ip}:{args.port}/index.json",
            file=sys.stderr,
        )
    print(f"  sirviendo {len(catalog['packs'])} packs. Ctrl-C para parar.\n", file=sys.stderr)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n  parado.", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
