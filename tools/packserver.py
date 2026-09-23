#!/usr/bin/env python3
"""A DEVELOPMENT file server for exercising pack downloads.

It is neither the installer nor the production catalog: it is what is missing in order to exercise
the download path without having decided where anything is hosted (roadmap, Pack installer). It
serves a directory of packs and **generates the index by reading the packs themselves**.

⚠️ **The index is generated, not hand-written, and that is correctness and not convenience.** Every
field comes out of the `.db`'s `meta` table or from measuring the file, so it cannot diverge from
what is served. A hand-written catalog saying `data_version` 202609211937 for a file that is
already another one is exactly the failure this repo cannot observe: the app believes the version
and never downloads again.

## What the index carries, and why each thing

- `schema_version` and `norm_version` **before the url**: they are the two that get a pack
  rejected whole on opening (D-001, D-006). Publishing them lets the app discard an incompatible
  pack **without downloading 192 MB to throw them away**.
- `data_version`: the monotonic integer the builder already writes. It is what decides "update"
  against "download".
- **Two hashes**: `sha256` is of the `.db.gz` that travels, `db_sha256` of the `.db` that stays on
  disk. Both are needed because verification happens at two moments -- when the transfer finishes
  and after decompressing -- and `installAtomically` compares against the second (D-165).
- `name`, `description`, `entry_count`, `license`: what the download screen shows. It comes from
  the pack so nobody rewrites it differently.

⚠️ **A pack with an old schema is published all the same, on purpose.** `es-def-wd` is
`schema_version` 3 and therefore **carries no `langs`** --that field was born with 4-- but it
appears in the index with its version. The app discards it without downloading, which is exactly
what those two versions are published for, and in development it serves to exercise that path.

## Why `.db.gz` is served as an opaque file and not with `Content-Encoding: gzip`

Measured: the Spanish pack goes from 73.6 to ~37 MB and English from 306.8 to ~192. But with
`Content-Encoding` the decompression is transparent, and then `Range` runs over the
**decompressed** stream: you cannot resume, and `Content-Length` lies. On a watch that downloads
192 MB only while charging, losing resumption is worse than the saving. So the `.gz` travels as
one more file and the app decompresses it by hand.

⚠️ **And the raw `.db` is served TOO, on purpose.** A block-level delta update (zsync) needs
`Range` over bytes that resemble the previous version, and a normal `.gz` resembles nothing after
the first byte that changes. Leaving the raw one available costs nothing on a development machine
and is what keeps that door open. See `docs/roadmap.md`.

## Usage

    python3 tools/packserver.py ../wearos-dictionary-data --port 8765
    # and on the watch / emulator:  http://<this-machine-ip>:8765/index.json
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

# The `meta` fields that go into the index, with the type they are converted to. What is not here
# is not published: `payload_dict` is 32 KB of binary and has no business in a catalog.
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
# ⚠️ `attribution` is NOT published, on purpose: it is ~600 characters per pack --the Spanish core
# cites five sources-- and **the pack already carries it inside**, so the app reads it after
# installing. In the index it would only duplicate the datum and fatten what the watch downloads
# on pressing the button. `license` does go, because it is short and the screen shows it before
# downloading.


def sha256_of(path: str) -> str:
    """A file's sha256, read in chunks: the packs do not fit in memory."""
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(BUFFER), b""):
            digest.update(chunk)
    return digest.hexdigest()


def pack_metadata(db_path: str) -> dict:
    """A pack's `meta` table, only the publishable fields.

    It is opened **read-only by URI**: the pack is immutable (D-001) and opening it any other way
    could leave a `-wal` beside it, which in a served directory would be published garbage.
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
    """Compresses the pack if the `.gz` is missing or older. Returns whether it did work.

    Compressing English is several minutes, so it is not redone on every startup. The criterion is
    the modification date, not the hash: computing the hash of 306 MB to decide whether to
    compress 306 MB costs nearly as much as compressing them.
    """
    if os.path.exists(gz_path) and os.path.getmtime(gz_path) >= os.path.getmtime(db_path):
        return False
    partial = gz_path + ".part"
    with open(db_path, "rb") as src, gzip.open(partial, "wb", compresslevel=6) as dst:
        shutil.copyfileobj(src, dst, BUFFER)
    os.replace(partial, gz_path)
    return True


def es_publicable(meta):
    """Is this pack a final product, or an intermediate step of the merge?

    ⚠️ **The filter is SEMANTIC and not by name**, and that matters: `es-def-wd` was seen in the
    emulator's catalog, and it is not a downloadable pack -- it is an **input** to the merge, which
    the Spanish pack carries fused inside. Publishing it offers a single-source dictionary, which
    is exactly the model that was discarded.

    A publishable pack **declares its tier** (`tier`, D-215). An intermediate declares none, so it
    falls out on its own and **renaming the file does not sneak it through**.

    ⚠️ **The bilingual one is the exception, and it is written rather than guessed**: it has no
    tiers because its purpose is not a size of the same dictionary. It is recognized by `kind`,
    which the pack declares.
    """
    return bool(meta.get("tier")) or meta.get("kind") == "bilingual"


def catalog_entry(db_path: str, gz_path: str) -> dict:
    """One index row: what the pack says, plus what the two files measure."""
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
    """A `.db` that is not a backup.

    ⚠️ The data directory holds `en-def-wikt.OLD.db` and `es-tr-enwikt.OLD2.db` beside the good
    ones. Publishing them would serve old packs as if they were the catalog.
    """
    return name.endswith(".db") and ".OLD" not in name


def build_catalog(directory: str, compress: bool = True) -> dict:
    """The directory's complete index, ordered by `pack_id` so it is diffable."""
    packs = []
    for name in sorted(os.listdir(directory)):
        if not is_pack(name):
            continue
        db_path = os.path.join(directory, name)
        if not es_publicable(pack_metadata(db_path)):
            print("  %s: intermedio, no se publica (no declara tier)" % name, file=sys.stderr)
            continue
        gz_path = db_path + ".gz"
        if compress:
            gzip_if_stale(db_path, gz_path)
        if not os.path.exists(gz_path):
            continue
        packs.append(catalog_entry(db_path, gz_path))
    packs.sort(key=lambda p: p.get("pack_id", ""))
    return {"catalog_version": 1, "packs": packs}


def etag_for(payload: bytes) -> str:
    """A strong ETag of the body.

    It serves to make pressing the button twice cost a bodyless 304 instead of the whole index. It
    is the only thing needed for that: the index is small and gets regenerated anyway.
    """
    return '"' + hashlib.sha256(payload).hexdigest()[:32] + '"'


RANGE_RE = re.compile(r"^bytes=(\d*)-(\d*)$")


def parse_range(header: str | None, size: int) -> tuple[int, int] | None:
    """Translates a `Range: bytes=...` into an inclusive `(first, last)`, or `None`.

    ⚠️ **`SimpleHTTPRequestHandler` does not implement `Range`**, and without this a Wi-Fi drop at
    90 % of 192 MB forces starting from zero. D-040 chose `HttpURLConnection` precisely because it
    does `Range`; a server that does not support it makes that decision unverifiable.

    RFC 9110 §14.1.1's three forms, and a `None` for everything else:

    - `bytes=0-99`   the first 100
    - `bytes=100-`   from 100 to the end  (the resume case)
    - `bytes=-100`   the last 100

    It returns `None` when there is no header or it is not understood --a full 200 is answered--
    and `(-1, -1)` when it is understood but **cannot be satisfied**, which is a different 416.
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
    """The IPs by which the watch can reach this machine.

    `localhost` is no use: the watch is at the other end of the Wi-Fi.
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
    """Serves the directory with `Range` and `ETag`, which is what the stdlib's lacks."""

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
