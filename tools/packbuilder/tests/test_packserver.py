"""Tests de tools/packserver.py: el servidor de archivos de desarrollo.

Dos clases de aserto, y la division es deliberada:

- **Las funciones puras** --parsear `Range`, filtrar respaldos, leer `meta`-- se prueban solas.
- **`Range` y `ETag` son protocolo**, y una funcion que devuelve la tupla correcta no prueba que
  el servidor emita un 206 con el `Content-Range` correcto. Eso se prueba levantando el servidor
  en un puerto efimero y hablandole por HTTP. Es barato y es lo unico que cierra D-040: esa
  decision eligio `HttpURLConnection` **porque hace `Range`**, y sin un servidor que lo soporte
  la reanudacion es inverificable.

El aserto que paga el archivo: **reanudar devuelve exactamente los bytes que faltaban**. Una
descarga de 192 MB que se corta al 90 % y se reanuda mal produce un `.db` que se abre sin error y
devuelve menos palabras de las que tiene.
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

# tests/ -> packbuilder/ -> tools/, donde vive packserver.py. Mismo patron que test_devpack.py.
sys.path.insert(
    0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
)

import packserver  # noqa: E402


def un_pack(path, **meta):
    """Un `.db` minimo con tabla `meta`. No es un pack valido: aca solo se lee `meta`."""
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
        # No publicable: 32 KB de binario que no tienen nada que hacer en un catalogo.
        "payload_dict": "deadbeef",
    }
    base.update({k: str(v) for k, v in meta.items()})
    conn = sqlite3.connect(path)
    conn.execute("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT)")
    conn.executemany("INSERT INTO meta VALUES (?, ?)", base.items())
    conn.commit()
    conn.close()


class TestParseRange(unittest.TestCase):
    """RFC 9110 §14.1.1. Las tres formas, y todo lo demas tratado como ausente."""

    def test_sin_cabecera_es_None(self):
        self.assertIsNone(packserver.parse_range(None, 1000))
        self.assertIsNone(packserver.parse_range("", 1000))

    def test_rango_cerrado(self):
        self.assertEqual((0, 99), packserver.parse_range("bytes=0-99", 1000))
        self.assertEqual((10, 20), packserver.parse_range("bytes=10-20", 1000))

    def test_desde_un_punto_hasta_el_final_es_el_caso_de_REANUDAR(self):
        # El que importa: el cliente ya tiene 900 bytes y pide el resto.
        self.assertEqual((900, 999), packserver.parse_range("bytes=900-", 1000))

    def test_sufijo(self):
        self.assertEqual((900, 999), packserver.parse_range("bytes=-100", 1000))

    def test_el_final_se_recorta_al_tamano(self):
        self.assertEqual((0, 999), packserver.parse_range("bytes=0-99999", 1000))

    def test_insatisfacible_es_distinto_de_no_entendido(self):
        # 416 y no 200: pedir desde mas alla del final es un error del cliente, y tragarselo
        # devolviendo el archivo entero haria que una reanudacion mal calculada se vea como exito.
        self.assertEqual((-1, -1), packserver.parse_range("bytes=1000-", 1000))
        self.assertEqual((-1, -1), packserver.parse_range("bytes=5000-6000", 1000))
        self.assertEqual((-1, -1), packserver.parse_range("bytes=-0", 1000))

    def test_basura_se_ignora(self):
        for malo in ("bytes=abc", "items=0-9", "bytes=", "bytes=-", "0-99"):
            self.assertIsNone(packserver.parse_range(malo, 1000), malo)


class TestCatalogo(unittest.TestCase):
    def test_los_respaldos_OLD_no_se_publican(self):
        # ⚠️ El directorio real tiene en-def-wikt.OLD.db y es-tr-enwikt.OLD2.db al lado de los
        # buenos. Publicarlos serviria un pack viejo como si fuera el catalogo.
        self.assertTrue(packserver.is_pack("es-def-wikc.db"))
        self.assertFalse(packserver.is_pack("en-def-wikt.OLD.db"))
        self.assertFalse(packserver.is_pack("es-tr-enwikt.OLD2.db"))
        self.assertFalse(packserver.is_pack("es-def-wikc.db.gz"))
        self.assertFalse(packserver.is_pack("notas.txt"))

    def test_un_INTERMEDIO_del_merge_no_se_publica(self):
        """⚠️ Visto en el catalogo del emulador: `es-def-wd` aparecia como un pack descargable.

        No lo es. Es una **entrada** del merge --el pack espanol lo lleva fundido dentro-- y
        publicarlo ofrece un diccionario de una sola fuente, que es justo el modelo que se
        descarto. El filtro es **semantico y no por nombre**: un pack publicable DECLARA su nivel
        (D-215), y un intermedio no declara ninguno. Renombrar el archivo no lo cuela.
        """
        with tempfile.TemporaryDirectory() as d:
            un_pack(os.path.join(d, "final.db"), tier="full", kind="monolingual")
            intermedio = os.path.join(d, "intermedio.db")
            # ⚠️ `kind` explicito: el fixture por defecto es bilingue, que es JUSTO la excepcion
            # del filtro -- y con el por defecto este test pasaba sin probar nada.
            un_pack(intermedio, kind="monolingual")
            conn = sqlite3.connect(intermedio)
            conn.execute("DELETE FROM meta WHERE key = 'tier'")
            conn.commit()
            conn.close()
            ids = [p["pack_id"] for p in packserver.build_catalog(d)["packs"]]
            self.assertEqual(1, len(ids), "solo el que declara nivel: %s" % ids)

    def test_el_BILINGUE_si_se_publica_aunque_no_tenga_nivel(self):
        """Su proposito no es un tamano del mismo diccionario, asi que no lleva `tier` (D-215)."""
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
        # Uno del .gz que viaja y otro del .db que queda. Se verifica en dos momentos.
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
        # ~600 caracteres por pack que el pack ya lleva dentro. Ver META_FIELDS.
        with tempfile.TemporaryDirectory() as d:
            un_pack(os.path.join(d, "es-test.db"))
            pack = packserver.build_catalog(d)["packs"][0]
            self.assertNotIn("attribution", pack)
            self.assertIn("license", pack, "la licencia SI, que es corta y se muestra antes")

    def test_un_pack_de_esquema_VIEJO_se_publica_con_su_version(self):
        # Es lo que deja que la app lo descarte sin bajar 192 MB. Un pack de esquema 3 no tiene
        # `langs` --ese campo nacio con el 4-- y aun asi tiene que aparecer.
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
    """Levanta el servidor en un puerto efimero y le habla por HTTP."""

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
        # Es lo que hace que apretar el boton de consultar no cueste el indice entero cada vez.
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
        # El aserto que paga el archivo.
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
        # A proposito: una actualizacion delta por bloques necesita los bytes del .db.
        with urllib.request.urlopen(self.base + "/packs/es-test.db") as r:
            self.assertEqual(200, r.status)
            self.assertEqual(os.path.getsize(self.db), len(r.read()))


if __name__ == "__main__":
    unittest.main()
