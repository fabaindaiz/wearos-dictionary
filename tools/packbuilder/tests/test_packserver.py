"""Tests of tools/packserver.py: the development file server.

Two classes of assertion, and the split is deliberate:

- **The pure functions** --parsing `Range`, filtering backups, reading `meta`-- are tested on
  their own.
- **`Range` and `ETag` are protocol**, and a function returning the right tuple does not prove the
  server emits a 206 with the right `Content-Range`. That is tested by starting the server on an
  ephemeral port and talking to it over HTTP. It is cheap and it is the only thing that closes
  D-040: that decision chose `HttpURLConnection` **because it does `Range`**, and without a server
  that supports it, resumption is unverifiable.

The assertion that pays for the file: **resuming returns exactly the bytes that were missing**. A
192 MB download cut off at 90 % and resumed badly produces a `.db` that opens with no error and
returns fewer words than it holds.
"""

import gzip
import json
import os
import sqlite3
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

# tests/ -> packbuilder/ -> tools/, which is where packserver.py lives. Same pattern as
# test_devpack.py.
sys.path.insert(
    0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
)

import packserver  # noqa: E402


def un_pack(path, **meta):
    """A minimal `.db` with a `meta` table. It is not a valid pack: only `meta` is read here."""
    base = {
        "pack_id": "es-test",
        "name": "Test",
        "description": "un pack de prueba",
        "langs": "es,en",
        "kind": "bilingual",
        "tier": "full",
        "entry_count": "42",
        "data_version": "202609211937",
        "schema_version": "4",
        "norm_version": "2",
        "license": "CC-BY-SA-4.0",
        "attribution": "nadie",
        # Not publishable: 32 KB of binary that has no business in a catalog.
        "payload_dict": "deadbeef",
    }
    base.update({k: str(v) for k, v in meta.items()})
    conn = sqlite3.connect(path)
    conn.execute("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT)")
    conn.executemany("INSERT INTO meta VALUES (?, ?)", base.items())
    conn.commit()
    conn.close()


class TestParseRange(unittest.TestCase):
    """RFC 9110 §14.1.1. The three forms, and everything else treated as absent."""

    def test_sin_cabecera_es_None(self):
        self.assertIsNone(packserver.parse_range(None, 1000))
        self.assertIsNone(packserver.parse_range("", 1000))

    def test_rango_cerrado(self):
        self.assertEqual((0, 99), packserver.parse_range("bytes=0-99", 1000))
        self.assertEqual((10, 20), packserver.parse_range("bytes=10-20", 1000))

    def test_desde_un_punto_hasta_el_final_es_el_caso_de_REANUDAR(self):
        # The one that matters: the client already has 900 bytes and asks for the rest.
        self.assertEqual((900, 999), packserver.parse_range("bytes=900-", 1000))

    def test_sufijo(self):
        self.assertEqual((900, 999), packserver.parse_range("bytes=-100", 1000))

    def test_el_final_se_recorta_al_tamano(self):
        self.assertEqual((0, 999), packserver.parse_range("bytes=0-99999", 1000))

    def test_insatisfacible_es_distinto_de_no_entendido(self):
        # 416 and not 200: asking from beyond the end is a client error, and swallowing it by
        # returning the whole file would make a miscalculated resume look like a success.
        self.assertEqual((-1, -1), packserver.parse_range("bytes=1000-", 1000))
        self.assertEqual((-1, -1), packserver.parse_range("bytes=5000-6000", 1000))
        self.assertEqual((-1, -1), packserver.parse_range("bytes=-0", 1000))

    def test_basura_se_ignora(self):
        for malo in ("bytes=abc", "items=0-9", "bytes=", "bytes=-", "0-99"):
            self.assertIsNone(packserver.parse_range(malo, 1000), malo)


class TestCatalogo(unittest.TestCase):
    def test_los_respaldos_OLD_no_se_publican(self):
        # ⚠️ The real directory holds en-def-wikt.OLD.db and es-tr-enwikt.OLD2.db beside the good
        # ones. Publishing them would serve an old pack as if it were the catalog.
        self.assertTrue(packserver.is_pack("es-def-wikc.db"))
        self.assertFalse(packserver.is_pack("en-def-wikt.OLD.db"))
        self.assertFalse(packserver.is_pack("es-tr-enwikt.OLD2.db"))
        self.assertFalse(packserver.is_pack("es-def-wikc.db.gz"))
        self.assertFalse(packserver.is_pack("notas.txt"))

    def test_un_INTERMEDIO_del_merge_no_se_publica(self):
        """⚠️ Seen in the emulator's catalog: `es-def-wd` appeared as a downloadable pack.

        It is not. It is an **input** to the merge --the Spanish pack carries it fused inside-- and
        publishing it offers a single-source dictionary, which is exactly the model that was
        discarded. The filter is **semantic and not by name**: a publishable pack DECLARES its tier
        (D-215), and an intermediate declares none. Renaming the file does not sneak it through.
        """
        with tempfile.TemporaryDirectory() as d:
            un_pack(os.path.join(d, "final.db"), tier="full", kind="monolingual")
            intermedio = os.path.join(d, "intermedio.db")
            # ⚠️ An explicit `kind`: the default fixture is bilingual, which is PRECISELY the
            # filter's exception -- and with the default this test passed without testing anything.
            un_pack(intermedio, kind="monolingual")
            conn = sqlite3.connect(intermedio)
            conn.execute("DELETE FROM meta WHERE key = 'tier'")
            conn.commit()
            conn.close()
            ids = [p["pack_id"] for p in packserver.build_catalog(d)["packs"]]
            self.assertEqual(1, len(ids), "solo el que declara nivel: %s" % ids)

    def test_el_BILINGUE_si_se_publica_aunque_no_tenga_nivel(self):
        """Its purpose is not a size of the same dictionary, so it carries no `tier` (D-215)."""
        with tempfile.TemporaryDirectory() as d:
            biling = os.path.join(d, "es-en.db")
            un_pack(biling, kind="bilingual")  # explicito aunque sea el default del fixture
            conn = sqlite3.connect(biling)
            conn.execute("DELETE FROM meta WHERE key = 'tier'")
            conn.commit()
            conn.close()
            ids = [p["pack_id"] for p in packserver.build_catalog(d)["packs"]]
            self.assertEqual(1, len(ids), "el bilingue tiene que publicarse: %s" % ids)

    def test_meta_se_lee_con_los_tipos_y_sin_el_payload_dict(self):
        with tempfile.TemporaryDirectory() as d:
            db = os.path.join(d, "es-test.db")
            un_pack(db)
            meta = packserver.pack_metadata(db)
            self.assertEqual(42, meta["entry_count"])
            self.assertIsInstance(meta["entry_count"], int)
            self.assertEqual(202609211937, meta["data_version"])
            self.assertEqual(["es", "en"], meta["langs"])
            self.assertNotIn(
                "payload_dict", meta, "el diccionario de compresion no va al catalogo"
            )

    def test_el_indice_lleva_DOS_hashes_distintos(self):
        # One of the .gz that travels and one of the .db that stays. It is verified at two moments.
        with tempfile.TemporaryDirectory() as d:
            db = os.path.join(d, "es-test.db")
            un_pack(db)
            cat = packserver.build_catalog(d)
            pack = cat["packs"][0]
            self.assertEqual(64, len(pack["sha256"]))
            self.assertEqual(64, len(pack["db_sha256"]))
            self.assertNotEqual(pack["sha256"], pack["db_sha256"])
            self.assertEqual("packs/es-test.db.gz", pack["url"])
            self.assertEqual(os.path.getsize(db), pack["db_bytes"])

    def test_el_indice_publica_las_versiones_que_permiten_RECHAZAR_sin_descargar(self):
        with tempfile.TemporaryDirectory() as d:
            un_pack(os.path.join(d, "es-test.db"))
            pack = packserver.build_catalog(d)["packs"][0]
            self.assertEqual(4, pack["schema_version"])
            self.assertEqual(2, pack["norm_version"])

    def test_attribution_NO_se_publica(self):
        # ~600 characters per pack that the pack already carries inside. See META_FIELDS.
        with tempfile.TemporaryDirectory() as d:
            un_pack(os.path.join(d, "es-test.db"))
            pack = packserver.build_catalog(d)["packs"][0]
            self.assertNotIn("attribution", pack)
            self.assertIn("license", pack, "la licencia SI, que es corta y se muestra antes")

    def test_un_pack_de_esquema_VIEJO_se_publica_con_su_version(self):
        # It is what lets the app discard it without downloading 192 MB. A schema 3 pack has no
        # `langs` --that field was born with 4-- and still has to appear.
        with tempfile.TemporaryDirectory() as d:
            db = os.path.join(d, "viejo.db")
            un_pack(db, schema_version=3)
            conn = sqlite3.connect(db)
            conn.execute("DELETE FROM meta WHERE key = 'langs'")
            conn.commit()
            conn.close()
            pack = packserver.build_catalog(d)["packs"][0]
            self.assertEqual(3, pack["schema_version"])
            self.assertNotIn("langs", pack)

    def test_ordenado_por_pack_id(self):
        with tempfile.TemporaryDirectory() as d:
            un_pack(os.path.join(d, "z.db"), pack_id="aaa")
            un_pack(os.path.join(d, "a.db"), pack_id="zzz")
            ids = [p["pack_id"] for p in packserver.build_catalog(d)["packs"]]
            self.assertEqual(["aaa", "zzz"], ids)

    def test_no_recomprime_si_el_gz_esta_al_dia(self):
        with tempfile.TemporaryDirectory() as d:
            db = os.path.join(d, "es-test.db")
            gz = db + ".gz"
            un_pack(db)
            self.assertTrue(packserver.gzip_if_stale(db, gz), "la primera vez tiene que comprimir")
            self.assertFalse(packserver.gzip_if_stale(db, gz), "no deberia rehacerlo")
            # Y el .gz de verdad contiene el .db.
            with open(db, "rb") as f, gzip.open(gz, "rb") as g:
                self.assertEqual(f.read(), g.read())

    def test_etag_estable_y_sensible(self):
        self.assertEqual(packserver.etag_for(b"abc"), packserver.etag_for(b"abc"))
        self.assertNotEqual(packserver.etag_for(b"abc"), packserver.etag_for(b"abd"))


class TestServidorDeVerdad(unittest.TestCase):
    """Starts the server on an ephemeral port and talks to it over HTTP."""

    @classmethod
    def setUpClass(cls):
        cls.dir = tempfile.TemporaryDirectory()
        cls.db = os.path.join(cls.dir.name, "es-test.db")
        un_pack(cls.db)
        cls.catalog = packserver.build_catalog(cls.dir.name)
        packserver.PackHandler.catalog = cls.catalog
        packserver.PackHandler.root = cls.dir.name
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), packserver.PackHandler)
        cls.base = "http://127.0.0.1:%d" % cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.dir.cleanup()

    def test_el_indice_llega_como_json(self):
        with urllib.request.urlopen(self.base + "/index.json") as r:
            self.assertEqual(200, r.status)
            cat = json.loads(r.read())
        self.assertEqual("es-test", cat["packs"][0]["pack_id"])

    def test_pedirlo_dos_veces_cuesta_un_304_SIN_cuerpo(self):
        # It is what makes pressing the query button not cost the whole index every time.
        with urllib.request.urlopen(self.base + "/index.json") as r:
            tag = r.headers["ETag"]
        self.assertTrue(tag)
        req = urllib.request.Request(self.base + "/index.json", headers={"If-None-Match": tag})
        try:
            with urllib.request.urlopen(req) as r:
                self.fail("deberia haber sido 304, fue %s" % r.status)
        except urllib.error.HTTPError as e:
            self.assertEqual(304, e.code)
            self.assertEqual(b"", e.read())

    def test_un_archivo_completo_anuncia_que_acepta_rangos(self):
        with urllib.request.urlopen(self.base + "/packs/es-test.db.gz") as r:
            self.assertEqual(200, r.status)
            self.assertEqual("bytes", r.headers["Accept-Ranges"])
            self.assertEqual(len(r.read()), int(r.headers["Content-Length"]))

    def test_REANUDAR_devuelve_exactamente_los_bytes_que_faltaban(self):
        # The assertion that pays for the file.
        url = self.base + "/packs/es-test.db.gz"
        with urllib.request.urlopen(url) as r:
            entero = r.read()
        corte = len(entero) // 2
        req = urllib.request.Request(url, headers={"Range": "bytes=%d-" % corte})
        with urllib.request.urlopen(req) as r:
            self.assertEqual(206, r.status)
            resto = r.read()
            self.assertEqual(
                "bytes %d-%d/%d" % (corte, len(entero) - 1, len(entero)),
                r.headers["Content-Range"],
            )
        self.assertEqual(entero, entero[:corte] + resto, "la reanudacion no reconstruye el archivo")

    def test_un_rango_imposible_es_416_y_no_el_archivo_entero(self):
        url = self.base + "/packs/es-test.db.gz"
        req = urllib.request.Request(url, headers={"Range": "bytes=999999999-"})
        try:
            with urllib.request.urlopen(req) as r:
                self.fail("deberia haber sido 416, fue %s" % r.status)
        except urllib.error.HTTPError as e:
            self.assertEqual(416, e.code)

    def test_no_se_puede_salir_del_directorio(self):
        for intento in ("/../../etc/passwd", "/packs/../../../etc/passwd"):
            try:
                with urllib.request.urlopen(self.base + intento) as r:
                    self.fail("%s no deberia servirse (fue %s)" % (intento, r.status))
            except urllib.error.HTTPError as e:
                self.assertIn(e.code, (400, 403, 404), intento)

    def test_el_db_en_crudo_TAMBIEN_se_sirve(self):
        # On purpose: a block-level delta update needs the .db's bytes.
        with urllib.request.urlopen(self.base + "/packs/es-test.db") as r:
            self.assertEqual(200, r.status)
            self.assertEqual(os.path.getsize(self.db), len(r.read()))


if __name__ == "__main__":
    unittest.main()
