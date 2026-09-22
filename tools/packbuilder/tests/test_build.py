"""Tests del builder de packs.

Construyen packs de verdad en un directorio temporal y los inspeccionan con SQL. Lo que se
verifica aca es sobre todo lo que NO falla ruidosamente: un pack a medio construir, o con el
tope de traducciones mal aplicado, se abre sin error y devuelve resultados incompletos.
"""

import contextlib
import io
import os
import shutil
import sqlite3
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import normalize  # noqa: E402
import verify_pack  # noqa: E402
from sources import toy  # noqa: E402

import build  # noqa: E402
import payload as payload_codec  # noqa: E402

BASE_META = {
    # Un `pack_id` con la forma que D-138 exige: <idioma>-<tipo>-<fuente>. "test" a secas ya no
    # sirve, y el test que lo rechaza vive en ManifiestoTest.
    "pack_id": "es-def-test",
    "kind": "monolingual",
    "name": "Test",
    "langs": "es",
    "fuzzy_profile": "es",
    "source_date": "1",
    "license": "CC0-1.0",
    "attribution": "test",
    "sources": ("definitions\tFuente de prueba\thttps://example.invalid/test\t"
                "CC0 1.0\thttps://creativecommons.org/publicdomain/zero/1.0/\n"),
    "source_url": "https://example.invalid/test",
    "proper_nouns": "excluded",
}


def record(headword, gloss="una glosa", **kwargs):
    return build.Record(headword=headword, senses=[{"gloss": gloss}], **kwargs)


class BuilderTestCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.path = os.path.join(self.tmp, "pack.db")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def build(self, records, metadata=None):
        with build.PackBuilder(self.path, dict(metadata or BASE_META)) as builder:
            for item in records:
                builder.add(item)
        return sqlite3.connect(self.path)


class ToyPackTest(BuilderTestCase):
    def test_toy_pack_passes_every_invariant(self):
        # verify_pack.py es lo que se corre sobre los packs reales; si el de juguete no pasa,
        # el de verdad tampoco va a pasar.
        with build.PackBuilder(self.path, dict(toy.METADATA)) as builder:
            for item in toy.records():
                builder.add(item)
        self.assertEqual(0, verify_pack.verify(self.path), "verify_pack encontro fallas")


class ToyPackFixtureTest(BuilderTestCase):
    """El pack de juguete tiene que seguir ejercitando los cinco caminos de busqueda.

    Los tests instrumentados de :dict-data (SqlitePackSourceTest) dependen del CONTENIDO de
    este pack: que "coreer" no lo encuentre el prefijo, que "c" de mas resultados que el umbral
    del nivel tolerante, que "bajo" tenga dos homografos. Nada de eso es obvio al editar
    sources/toy.py.

    Sin estos tests, romper una de esas suposiciones no se notaria hasta conectar un emulador,
    y el fallo se leeria como un bug del codigo y no del fixture.
    """

    # Espejo de SqlitePackSource.FUZZY_TRIGGER. Si cambia alla, cambia aca.
    FUZZY_TRIGGER = 5

    def _payload(self, entry_id):
        """El cuerpo descomprimido de una entrada, para mirar lo que la ficha mostraria."""
        diccionario = bytes.fromhex(self.db.execute(
            "SELECT value FROM meta WHERE key='payload_dict'").fetchone()[0])
        blob = self.db.execute(
            "SELECT payload FROM entry WHERE id=?", (entry_id,)).fetchone()[0]
        return payload_codec.decompress(blob, diccionario)

    def setUp(self):
        super().setUp()
        with build.PackBuilder(self.path, dict(toy.METADATA)) as builder:
            for item in toy.records():
                builder.add(item)
        self.db = sqlite3.connect(self.path)

    def prefijo(self, texto):
        clave = normalize.norm(texto)
        upper = clave[:-1] + chr(ord(clave[-1]) + 1)
        return [
            row[0]
            for row in self.db.execute(
                "SELECT headword FROM entry WHERE norm >= ? AND norm < ?"
                " ORDER BY CASE WHEN norm = ? THEN 0 ELSE 1 END, rank, norm LIMIT 30",
                (clave, upper, clave),
            )
        ]

    def test_el_mejor_match_de_fts_no_es_el_de_rowid_mas_bajo(self):
        """La trampa que hace visible que FTS5 ordena por relevancia y `entry.id` no.

        `searchDefinitions` consulta `fts_def MATCH ... ORDER BY rank` --bm25-- y despues resuelve
        los rowids con `WHERE id IN (...)`, que sale en orden de rowid. Si el mejor match tuviera
        siempre el rowid mas bajo, tirar el ranking no se notaria y el bug viviria para siempre.

        Dos entradas comparten el termino: una con glosa LARGA que lo menciona una vez, y otra
        con glosa CORTA que lo repite --bm25 premia la corta y castiga la larga--. La larga va
        primero en `_DATA`, asi que se lleva el rowid menor.

        Este test corre EN EL GATE. El que comprueba que la app respete ese orden es instrumentado
        y necesita dispositivo: sin esta guarda, reordenar `_DATA` romperia aquel en silencio.
        """
        termino = normalize.norm("mineral")
        por_relevancia = [
            row[0] for row in self.db.execute(
                "SELECT rowid FROM fts_def WHERE fts_def MATCH ? ORDER BY rank", (termino,))
        ]
        por_rowid = sorted(por_relevancia)
        self.assertGreaterEqual(len(por_relevancia), 2, "la trampa necesita dos entradas")
        self.assertNotEqual(
            por_relevancia[0], por_rowid[0],
            "el mejor match de bm25 tiene el rowid mas bajo: la trampa dejo de ser una trampa",
        )

    def test_un_prefijo_productivo_supera_el_umbral_del_nivel_tolerante(self):
        # Si esto baja del umbral, el test que comprueba que el nivel tolerante NO se dispara
        # pasaria por el motivo equivocado.
        self.assertGreaterEqual(len(self.prefijo("c")), self.FUZZY_TRIGGER)

    def test_hay_un_tipeo_que_solo_alcanza_el_nivel_tolerante(self):
        # "coreer" no debe ser alcanzable por prefijo ni por forma flexionada: si lo fuera, el
        # test del nivel tolerante no probaria el nivel tolerante.
        self.assertEqual([], self.prefijo("coreer"))
        formas = self.db.execute(
            "SELECT COUNT(*) FROM form WHERE norm = ?", (normalize.norm("coreer"),)
        ).fetchone()[0]
        self.assertEqual(0, formas)
        # Pero si tiene que caer en el vecindario fuzzy.
        clave = normalize.fuzzy("coreer", "es")[:4]
        upper = clave[:-1] + chr(ord(clave[-1]) + 1)
        vecinos = self.db.execute(
            "SELECT COUNT(*) FROM entry WHERE fuzzy >= ? AND fuzzy < ?", (clave, upper)
        ).fetchone()[0]
        self.assertGreater(vecinos, 0)

    def test_hay_homografos_con_pos_distinto(self):
        filas = self.db.execute(
            "SELECT pos FROM entry WHERE headword = 'bajo' ORDER BY pos"
        ).fetchall()
        self.assertEqual([("adjective",), ("preposition",)], filas)

    def test_hay_una_forma_flexionada_y_una_traduccion_conocidas(self):
        self.assertGreater(
            self.db.execute(
                "SELECT COUNT(*) FROM form f JOIN entry e ON e.id = f.entry_id"
                " WHERE f.norm = 'corriendo' AND e.headword = 'correr'"
            ).fetchone()[0],
            0,
        )
        # ⚠️ **`run` ya no vive en `trans` sino que ES una entrada**, y ese cambio es el pack
        # bidireccional: `trans` se vacia porque seria una segunda copia del mismo indice.
        # Lo que se comprueba es lo mismo de siempre --que escribiendo `run` se llegue a
        # `correr`-- por el camino nuevo.
        fila = self.db.execute(
            "SELECT id, lang FROM entry WHERE norm = 'run'").fetchone()
        self.assertIsNotNone(fila, "la palabra inglesa tiene que ser un lema")
        self.assertEqual("en", fila[1], "y declarar su idioma")
        cuerpo = self._payload(fila[0])
        self.assertIn("correr", cuerpo, "y llevar a su equivalente español")
        self.assertEqual(
            0,
            self.db.execute("SELECT COUNT(*) FROM trans").fetchone()[0],
            "en un pack bidireccional `trans` sobra: 474.849 filas y 13,3 MiB en el pack real",
        )

    def test_hay_un_lema_exacto_que_rankea_peor_que_uno_que_lo_extiende(self):
        """Sin esta trampa, la regla de exacta-primero no tiene nada que probar.

        "sol" es la coincidencia exacta y rankea 500; "soler" solo lo tiene de prefijo y rankea
        50. Ordenando solo por rank, escribir "sol" no devuelve "sol".
        """
        filas = dict(self.db.execute(
            "SELECT headword, rank FROM entry WHERE headword IN ('sol', 'soler')"))
        self.assertEqual({"sol": 500, "soler": 50}, filas)
        self.assertEqual("sol", self.prefijo("sol")[0])

    def test_hay_dos_entradas_con_el_mismo_headword_y_el_mismo_pos(self):
        """Es el caso "hacer" del Wikcionario, que aparece cinco veces por etimologia.

        Son entradas distintas y legitimas --uid las separa-- pero una lista que las muestra
        todas repite la misma palabra. La deduplicacion vive en SqlitePackSource, no aca; esto
        solo garantiza que el fixture siga teniendo el caso.
        """
        velas = self.db.execute(
            "SELECT pos, COUNT(*), COUNT(DISTINCT uid) FROM entry WHERE headword = 'vela'"
            " GROUP BY pos").fetchall()
        self.assertEqual([("noun", 2, 2)], velas)

    def test_hay_una_palabra_buscable_solo_por_su_definicion(self):
        filas = self.db.execute(
            "SELECT e.headword FROM fts_def f JOIN entry e ON e.id = f.rowid"
            " WHERE fts_def MATCH ?",
            ('"rapidamente"',),
        ).fetchall()
        self.assertIn(("correr",), filas)


class IngestTest(BuilderTestCase):
    def test_headword_that_normalizes_to_empty_is_skipped(self):
        # "!!!" no se puede buscar por ningun camino; entrar al pack solo ocuparia lugar.
        db = self.build([record("correr"), record("!!!"), record("¿?")])
        self.assertEqual([("correr",)], list(db.execute("SELECT headword FROM entry")))

    def test_entry_without_usable_senses_is_skipped(self):
        db = self.build([
            record("correr"),
            build.Record(headword="vacio", senses=[{"gloss": "   "}]),
            build.Record(headword="sin", senses=[]),
        ])
        self.assertEqual([("correr",)], list(db.execute("SELECT headword FROM entry")))

    def test_forms_are_deduplicated_and_exclude_the_headword(self):
        db = self.build([
            record("correr", forms=["corriendo", "corriendo", "Corriendo", "correr"])
        ])
        forms = [row[0] for row in db.execute("SELECT norm FROM form ORDER BY norm")]
        self.assertEqual(["corriendo"], forms, "el lema o un duplicado entro en form")

    def test_translation_indexes_phrase_and_each_word(self):
        # Sin esto, buscar "run" no encuentra "to run", que es lo que un usuario escribe.
        db = self.build([record("correr", translations=["to run"])])
        keys = sorted(row[0] for row in db.execute("SELECT norm FROM trans"))
        self.assertEqual(["run", "to", "to run"], keys)

    def test_translation_cap_keeps_the_best_ranked(self):
        # "to" apuntaria a decenas de miles de entradas en un pack real.
        limit = build.TRANS_MAX_PER_KEY
        records = [
            record("verbo%03d" % i, rank=i, translations=["to word%03d" % i])
            for i in range(limit + 25)
        ]
        db = self.build(records)

        kept = [row[0] for row in db.execute(
            "SELECT e.rank FROM trans t JOIN entry e ON e.id = t.entry_id"
            " WHERE t.norm = 'to' ORDER BY e.rank")]
        self.assertEqual(limit, len(kept), "no se aplico el tope por clave")
        # Se conservan los de mejor rank (menor es mas comun), no los primeros que llegaron.
        self.assertEqual(list(range(limit)), kept)

        dropped = db.execute("SELECT value FROM meta WHERE key='trans_dropped'").fetchone()[0]
        self.assertEqual(25, int(dropped), "meta.trans_dropped no refleja lo recortado")

    def test_un_lema_cuyo_fuzzy_es_vacio_sigue_siendo_entrada(self):
        """CARACTERIZACION: el builder ya se comportaba asi; este test fija la conducta.

        `fuzzy("h")` es vacio: la hache es muda en el perfil español. La entrada igual existe.

        `norm` vacio y `fuzzy` vacio no son el mismo problema. Sin `norm` la entrada es
        inalcanzable y no tiene sentido guardarla. Sin `fuzzy` solo queda fuera del nivel
        tolerante: se sigue encontrando por prefijo y exacta, que es como se busca una letra.

        Lo encontro el primer pack real: "h" y "H" son entradas del Wikcionario (la letra) y
        hacian fallar la invariante de verify_pack.py, que trataba los dos casos igual.
        """
        db = self.build([record("h", gloss="octava letra del abecedario español")])
        row = db.execute("SELECT norm, fuzzy FROM entry WHERE headword='h'").fetchone()
        self.assertEqual(("h", ""), row)
        encontrada = db.execute(
            "SELECT headword FROM entry WHERE norm >= ? AND norm < ? ORDER BY norm, rank",
            ("h", "i")).fetchall()
        self.assertIn(("h",), encontrada)

    def test_normalization_columns_match_normalize_module(self):
        db = self.build([record("Ärztin"), record("acción"), record("Straße")])
        for headword, norm_key, fuzzy_key in db.execute(
            "SELECT headword, norm, fuzzy FROM entry"
        ):
            self.assertEqual(normalize.norm(headword), norm_key)
            self.assertEqual(normalize.fuzzy(headword, "es"), fuzzy_key)


class PacksDeclaradosTest(unittest.TestCase):
    """Todo pack declara su politica de contenido, y el validador la comprueba (D-116).

    La regla se enforcea desde Python y no desde `audit_dictionary.py` porque aca se puede
    **importar** `PACKS`; alla habria que leerlo con una regex sobre un dict, que se rompe sola
    en cuanto alguien reordena el archivo.
    """

    def test_todo_pack_declara_su_politica_de_nombres_propios(self):
        sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
        import build_pack

        for lang, metadata in build_pack.PACKS.items():
            self.assertIn(
                "proper_nouns", metadata,
                "el pack %r no dice si trae nombres propios: meta tiene que decir que paso, "
                "y un pack sin la clave no pasa verify_pack" % lang,
            )
            self.assertIn(metadata["proper_nouns"], ("excluded", "lexical-only", "included"))


class SinonimosEnElIndiceTest(BuilderTestCase):
    """Buscar un sinonimo tiene que encontrar la entrada (D-118).

    Es media razon del cambio: sin esto los sinonimos solo se VEN al abrir una entrada que ya
    encontraste, que es justo cuando ya no los necesitas.
    """

    def test_los_sinonimos_entran_al_indice_de_texto_libre(self):
        # La glosa NO contiene "bobo": si el match aparece, vino del sinonimo.
        registro = build.Record(
            headword="chulengo",
            senses=[{"gloss": "persona de poco entendimiento", "synonyms": ["bobo", "zonzo"]}],
        )
        db = self.build([registro])
        filas = db.execute("SELECT rowid FROM fts_def WHERE fts_def MATCH 'bobo'").fetchall()
        self.assertEqual(1, len(filas), "buscar 'bobo' tiene que encontrar 'chulengo'")
        entry_id = db.execute("SELECT id FROM entry WHERE headword='chulengo'").fetchone()[0]
        self.assertEqual(entry_id, filas[0][0], "fts_def.rowid tiene que ser entry.id (D-011)")


class CitaHuerfanaTest(BuilderTestCase):
    """`verify_pack.py` rechaza una cita que no cuelgue de un ejemplo.

    ⚠️ **Se comprueba sobre los BYTES y no sobre la estructura parseada, y esa es la diferencia
    que hace util al chequeo.** `payload.parse` ya descarta la cita huerfana en silencio, que es
    la degradacion correcta para el lector; pero un pack construido por otro --o por una version
    futura del builder con un bug-- la llevaria adentro, y el usuario veria una entrada a la que
    le falta la atribucion que el pack decia traer. Es, ademas, el unico chequeo de CONTENIDO
    del payload que este validador tiene: hasta ahora solo miraba invariantes estructurales.
    """

    def _pack_con_cuerpo(self, cuerpo):
        with build.PackBuilder(self.path, dict(BASE_META)) as builder:
            builder.add(record("correr"))
        db = sqlite3.connect(self.path)
        diccionario = bytes.fromhex(db.execute(
            "SELECT value FROM meta WHERE key='payload_dict'").fetchone()[0])
        db.execute("UPDATE entry SET payload = ?",
                   (payload_codec.compress(cuerpo, diccionario),))
        db.commit()
        db.close()
        salida = io.StringIO()
        with contextlib.redirect_stdout(salida):
            codigo = verify_pack.verify(self.path)
        return codigo, salida.getvalue()

    def test_una_cita_sin_ejemplo_hace_fallar_la_verificacion(self):
        codigo, salida = self._pack_con_cuerpo("P\tverb\nS\tuna glosa\nC\t1897, Richard Marsh\n")
        self.assertEqual(1, codigo, salida)
        self.assertIn("cita", salida)

    def test_una_cita_separada_de_su_ejemplo_tambien_falla(self):
        # El caso peligroso de verdad: hay un ejemplo, asi que la cita "parece" tener de que
        # colgar -- pero el tag del medio la desplaza y quien la lea le asignaria un ejemplo que
        # la fuente nunca le atribuyo.
        codigo, salida = self._pack_con_cuerpo(
            "P\tverb\nS\tuna glosa\nE\tun ejemplo\nY\tsinonimo\nC\t1897, Richard Marsh\n")
        self.assertEqual(1, codigo, salida)

    def test_la_cita_pegada_a_su_ejemplo_pasa(self):
        codigo, salida = self._pack_con_cuerpo(
            "P\tverb\nS\tuna glosa\nE\tun ejemplo\nC\t1897, Richard Marsh\n")
        self.assertEqual(0, codigo, salida)


class ComoLaAppTest(BuilderTestCase):
    """El modo espejo: `verify_pack.py --como-la-app` contesta lo que la app contestaria.

    ⚠️ **Es el cuarto contrato cruzado del repo** (D-217), y lo que lo sostiene es doble: la
    auditoria compara los ids de `MOTIVOS_DE_LA_APP` contra el enum `PackRejection`, y estos
    casos comprueban que cada motivo **se dispare de verdad**. Sin lo segundo, una tabla con los
    ids correctos y las comprobaciones rotas pasaria la auditoria y mentiria en cada respuesta.
    """

    def _pack(self, **meta_extra):
        metadata = dict(BASE_META)
        metadata.update(meta_extra)
        with build.PackBuilder(self.path, metadata) as builder:
            builder.add(record("correr"))
        salida = io.StringIO()
        with contextlib.redirect_stdout(salida):
            codigo = verify_pack.como_la_app(self.path)
        return codigo, salida.getvalue()

    def _con_meta_crudo(self, clave, valor):
        """Escribe en `meta` DESPUES de construir: el builder no deja poner un valor invalido."""
        with build.PackBuilder(self.path, dict(BASE_META)) as builder:
            builder.add(record("correr"))
        db = sqlite3.connect(self.path)
        if valor is None:
            db.execute("DELETE FROM meta WHERE key = ?", (clave,))
        else:
            db.execute("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)", (clave, valor))
        db.commit()
        db.close()
        salida = io.StringIO()
        with contextlib.redirect_stdout(salida):
            codigo = verify_pack.como_la_app(self.path)
        return codigo, salida.getvalue()

    def test_un_pack_recien_construido_lo_abriria(self):
        codigo, salida = self._pack()
        self.assertEqual(0, codigo, salida)
        self.assertIn("la app lo abriria", salida)

    def test_otro_esquema_se_reporta_como_esquema_y_no_como_metadata(self):
        """⚠️ El caso que encontraron los packs reales de `schema_version` 3.

        Un pack de otro esquema **tambien** puede no traer claves que nacieron despues, asi que
        los dos motivos aplican. El que se reporta tiene que ser el esquema: es el unico que le
        dice al usuario que hacer.
        """
        with build.PackBuilder(self.path, dict(BASE_META)) as builder:
            builder.add(record("correr"))
        db = sqlite3.connect(self.path)
        db.execute("UPDATE meta SET value = '3' WHERE key = 'schema_version'")
        db.execute("DELETE FROM meta WHERE key IN ('langs', 'fuzzy_profiles')")
        db.commit()
        db.close()
        salida = io.StringIO()
        with contextlib.redirect_stdout(salida):
            codigo = verify_pack.como_la_app(self.path)
        salida = salida.getvalue()
        self.assertEqual(1, codigo, salida)
        self.assertIn("RECHAZADO schema", salida)
        self.assertNotIn("RECHAZADO metadata", salida)

    def test_una_clave_obligatoria_que_falta_es_metadata(self):
        codigo, salida = self._con_meta_crudo("attribution", None)
        self.assertEqual(1, codigo, salida)
        self.assertIn("RECHAZADO metadata", salida)

    def test_otra_norm_version_se_rechaza_por_la_normalizacion(self):
        codigo, salida = self._con_meta_crudo("norm_version", "99")
        self.assertEqual(1, codigo, salida)
        self.assertIn("RECHAZADO norm", salida)

    def test_otro_codec_se_rechaza_por_el_codec(self):
        codigo, salida = self._con_meta_crudo("payload_codec", "zstd-v1")
        self.assertEqual(1, codigo, salida)
        self.assertIn("RECHAZADO codec", salida)

    def test_sin_fuentes_declaradas_no_se_puede_acreditar(self):
        # D-031: la pantalla de atribucion no es opcional.
        codigo, salida = self._con_meta_crudo("sources", "")
        self.assertEqual(1, codigo, salida)
        self.assertIn("RECHAZADO license", salida)

    def test_entry_count_que_no_cuadra_es_un_archivo_truncado(self):
        codigo, salida = self._con_meta_crudo("entry_count", "999")
        self.assertEqual(1, codigo, salida)
        self.assertIn("RECHAZADO count", salida)

    def test_sin_un_indice_la_busqueda_escanearia(self):
        with build.PackBuilder(self.path, dict(BASE_META)) as builder:
            builder.add(record("correr"))
        db = sqlite3.connect(self.path)
        db.execute("DROP INDEX idx_entry_fuzzy")
        db.commit()
        db.close()
        salida = io.StringIO()
        with contextlib.redirect_stdout(salida):
            codigo = verify_pack.como_la_app(self.path)
        salida = salida.getvalue()
        self.assertEqual(1, codigo, salida)
        self.assertIn("RECHAZADO index", salida)

    def test_una_clave_mal_calculada_se_agarra_con_la_muestra(self):
        # Es el modo de falla central del repo: la palabra esta y ninguna busqueda la alcanza.
        with build.PackBuilder(self.path, dict(BASE_META)) as builder:
            builder.add(record("correr"))
        db = sqlite3.connect(self.path)
        db.execute("UPDATE entry SET norm = 'otracosa' WHERE id = 1")
        db.commit()
        db.close()
        salida = io.StringIO()
        with contextlib.redirect_stdout(salida):
            codigo = verify_pack.como_la_app(self.path)
        salida = salida.getvalue()
        self.assertEqual(1, codigo, salida)
        self.assertIn("RECHAZADO keys", salida)

    def test_un_archivo_que_no_es_un_pack_es_damaged(self):
        with open(self.path, "wb") as handle:
            handle.write(b"esto no es sqlite")
        salida = io.StringIO()
        with contextlib.redirect_stdout(salida):
            codigo = verify_pack.como_la_app(self.path)
        salida = salida.getvalue()
        self.assertEqual(1, codigo, salida)
        self.assertIn("RECHAZADO damaged", salida)


class InvariantesExhaustivasTest(BuilderTestCase):
    """Lo que `verify()` mira de mas que la app, porque corre al construir y puede gastar."""

    def test_una_lista_con_items_repetidos_hace_fallar(self):
        # D-218. `render` lo deduplica al construir; esto lo comprueba sobre los BYTES, que es lo
        # unico que vale para un pack que no construimos nosotros.
        with build.PackBuilder(self.path, dict(BASE_META)) as builder:
            builder.add(record("correr"))
        db = sqlite3.connect(self.path)
        diccionario = bytes.fromhex(db.execute(
            "SELECT value FROM meta WHERE key='payload_dict'").fetchone()[0])
        cuerpo = "P\tverb\nS\tuna glosa\nT\tto run\nT\tto run\n"
        db.execute("UPDATE entry SET payload = ?",
                   (payload_codec.compress(cuerpo, diccionario),))
        db.commit()
        db.close()
        salida = io.StringIO()
        with contextlib.redirect_stdout(salida):
            codigo = verify_pack.verify(self.path)
        self.assertEqual(1, codigo)
        self.assertIn("repite items", salida.getvalue())

    def test_un_tag_desconocido_hace_fallar(self):
        # El lector los ignora a proposito (D-119), asi que este es el unico lugar donde un tag
        # que el builder escribio mal se puede notar.
        with build.PackBuilder(self.path, dict(BASE_META)) as builder:
            builder.add(record("correr"))
        db = sqlite3.connect(self.path)
        diccionario = bytes.fromhex(db.execute(
            "SELECT value FROM meta WHERE key='payload_dict'").fetchone()[0])
        db.execute("UPDATE entry SET payload = ?",
                   (payload_codec.compress("P\tverb\nS\tuna glosa\nZ\tdel futuro\n",
                                           diccionario),))
        db.commit()
        db.close()
        salida = io.StringIO()
        with contextlib.redirect_stdout(salida):
            codigo = verify_pack.verify(self.path)
        self.assertEqual(1, codigo)
        self.assertIn("tag desconocido", salida.getvalue())

    def test_una_clave_de_form_que_no_es_norm_valida_hace_fallar(self):
        """⚠️ La tabla `form` son 1,5 millones de filas que NADIE miraba.

        D-142 recalcula una muestra de `entry`; `form` es la que resuelve una flexion, y una
        clave suya construida con otras reglas es la palabra que esta en el archivo y ninguna
        busqueda alcanza.
        """
        with build.PackBuilder(self.path, dict(BASE_META)) as builder:
            builder.add(record("correr"))
        db = sqlite3.connect(self.path)
        db.execute("INSERT OR REPLACE INTO form (norm, entry_id) VALUES ('MAYUSCULA', 1)")
        db.commit()
        db.close()
        salida = io.StringIO()
        with contextlib.redirect_stdout(salida):
            codigo = verify_pack.verify(self.path)
        self.assertEqual(1, codigo)
        self.assertIn("claves de norm() validas", salida.getvalue())


class AntonimosFueraDelIndiceTest(BuilderTestCase):
    """Los antonimos van al payload y NO a `fts_def` (D-126).

    Es lo contrario de lo que se decidio para los sinonimos (D-118), y el motivo es que la
    pregunta que cada uno responde es distinta: un sinonimo es otra forma de nombrar lo que
    buscas, un antonimo es lo que NO buscas. Indexarlo haria que escribir "frio" devuelva
    "caliente", con el orden de resultados --que ya es deuda (D-067)-- decidiendo que tan
    arriba aparece esa respuesta invertida.

    Sin este test, alguien que agregue un campo al payload lo suma a `_fts_body` por simetria
    y nada falla: el pack sale mas grande y la busqueda mas ruidosa, en silencio.
    """

    def test_un_antonimo_no_se_puede_buscar_por_texto_libre(self):
        with build.PackBuilder(self.path, dict(BASE_META)) as builder:
            entrada = record("caliente")
            entrada.senses[0].update({"synonyms": ["ardiente"], "antonyms": ["gelido"]})
            builder.add(entrada)
        db = sqlite3.connect(self.path)
        sinonimo = db.execute(
            "SELECT COUNT(*) FROM fts_def WHERE fts_def MATCH 'ardiente'").fetchone()[0]
        antonimo = db.execute(
            "SELECT COUNT(*) FROM fts_def WHERE fts_def MATCH 'gelido'").fetchone()[0]
        db.close()
        self.assertEqual(1, sinonimo, "el sinonimo SI tiene que estar en el indice (D-118)")
        self.assertEqual(0, antonimo, "el antonimo NO tiene que estar en el indice (D-126)")

    def test_la_cita_del_ejemplo_tampoco_entra_al_indice(self):
        """El ejemplo SI se indexa (D-118) y su cita NO, y la asimetria es el punto.

        Una cita es procedencia, no significado: buscar "Richard Marsh" tiene que devolver nada,
        no la entrada `Thomas`. Ademas seria el tercer caso del mismo error -- D-117 midio que
        los sinonimos costaron **tres veces** lo estimado justamente porque `fts_def` los indexa
        ademas del payload, y las citas del pack ingles pesan casi tanto como ellos.
        """
        with build.PackBuilder(self.path, dict(BASE_META)) as builder:
            entrada = record("thomas")
            entrada.senses[0]["examples"] = [
                {"text": "prove them Thomases", "ref": "1897, Richard Marsh"},
            ]
            builder.add(entrada)
        db = sqlite3.connect(self.path)
        ejemplo = db.execute(
            "SELECT COUNT(*) FROM fts_def WHERE fts_def MATCH 'Thomases'").fetchone()[0]
        cita = db.execute(
            "SELECT COUNT(*) FROM fts_def WHERE fts_def MATCH 'Marsh'").fetchone()[0]
        cuerpo = payload_codec.decompress(
            db.execute("SELECT payload FROM entry").fetchone()[0],
            bytes.fromhex(db.execute(
                "SELECT value FROM meta WHERE key = 'payload_dict'").fetchone()[0]),
        )
        db.close()
        self.assertEqual(1, ejemplo, "el ejemplo SI tiene que estar en el indice (D-118)")
        self.assertEqual(0, cita, "la cita NO tiene que estar en el indice")
        self.assertIn("C\t1897, Richard Marsh", cuerpo, "pero si tiene que estar en el payload")

    def test_una_relacionada_tampoco_se_puede_buscar_por_texto_libre(self):
        """Mismo criterio que el antonimo, y es exactamente el descuido que el docstring anuncia.

        `related` es la tercera lista de palabras del payload (D-132) y la tentacion de sumarla
        a `_fts_body` "por simetria" con los sinonimos es la misma. No corresponde: nadie escribe
        "camelido" esperando "guanaco", y la entrada que devolveria compite por el orden con la
        que el usuario si buscaba.
        """
        with build.PackBuilder(self.path, dict(BASE_META)) as builder:
            entrada = record("guanaco")
            entrada.senses[0].update({"synonyms": ["huanaco"], "related": ["camelido"]})
            builder.add(entrada)
        db = sqlite3.connect(self.path)
        sinonimo = db.execute(
            "SELECT COUNT(*) FROM fts_def WHERE fts_def MATCH 'huanaco'").fetchone()[0]
        relacionada = db.execute(
            "SELECT COUNT(*) FROM fts_def WHERE fts_def MATCH 'camelido'").fetchone()[0]
        payload_crudo = db.execute("SELECT payload FROM entry").fetchone()[0]
        db.close()
        self.assertEqual(1, sinonimo, "el sinonimo SI tiene que estar en el indice (D-118)")
        self.assertEqual(0, relacionada, "la relacionada NO tiene que estar en el indice (D-132)")
        self.assertTrue(payload_crudo, "pero si tiene que haber llegado al payload")


_FUENTE = ("definitions\tWikcionario\thttps://es.wiktionary.org/\t"
           "CC BY-SA 4.0\thttps://creativecommons.org/licenses/by-sa/4.0/\n")


class ManifiestoTest(BuilderTestCase):
    """El pack declara QUE es, DE DONDE viene y COMO se puede usar, y el validador lo exige.

    Son las tres preguntas que alguien que recibe un `.db` de 68 MB tiene que poder contestar sin
    preguntarle a nadie. El modo de falla es silencioso en las tres: un pack sin manifiesto abre,
    busca y funciona -- y no se puede saber si se puede redistribuir.
    """

    def _con_meta(self, **cambios):
        metadata = dict(BASE_META)
        metadata.update(cambios)
        with build.PackBuilder(self.path, metadata) as builder:
            builder.add(record("correr"))
        salida = io.StringIO()
        with contextlib.redirect_stdout(salida):
            codigo = verify_pack.verify(self.path)
        return codigo, salida.getvalue()

    def test_un_pack_id_demasiado_generico_se_rechaza(self):
        """El codigo evita la colision, que es lo que un nombre generico no puede evitar.

        Dos packs de español de fuentes distintas instalados a la vez (D-136) comparten idioma y
        tipo: lo unico que los separa es el codigo de fuente. Con `pack_id = "espanol"` los dos
        son "espanol", uno pisa al otro al instalar, y el historial del reloj queda apuntando a
        entradas de un pack que ya no esta.
        """
        codigo, salida = self._con_meta(pack_id="espanol")
        self.assertNotEqual(0, codigo)
        self.assertIn("pack_id", salida)

    def test_el_pack_id_con_la_forma_correcta_pasa(self):
        codigo, salida = self._con_meta(pack_id="es-def-wikc", sources=_FUENTE)
        self.assertEqual(0, codigo, salida)

    def test_las_variantes_son_parte_de_la_forma(self):
        # "es-def-wikc-tat" y "es-def-wikc-sample10" son packs legitimos que tienen que convivir
        # con el pelado. Si la gramatica no las admite, el builder no puede construirlos.
        for pack_id in ("es-def-wikc-tat", "es-def-wikc-ej-tat", "es-def-wikc-sample10",
                        "en-def-wikt", "es-tr-wikc"):
            codigo, salida = self._con_meta(pack_id=pack_id, sources=_FUENTE)
            self.assertEqual(0, codigo, "%s deberia ser valido:\n%s" % (pack_id, salida))

    def test_un_pack_SIN_manifiesto_de_fuentes_se_rechaza(self):
        """Sin `meta.sources` no se sabe bajo que terminos se puede redistribuir el contenido."""
        codigo, salida = self._con_meta(pack_id="es-def-wikc", sources="")
        self.assertNotEqual(0, codigo)
        self.assertIn("sources", salida)

    def test_una_fuente_SIN_licencia_se_rechaza(self):
        """Declarar la fuente y callar la licencia es peor que no declarar nada: parece completo.

        Es la comprobacion que paga esta clase. La atribucion es la CONDICION de uso del dato
        (D-031), y un pack que nombra a Tatoeba sin decir CC BY 2.0 FR no dice como usarse.
        """
        codigo, salida = self._con_meta(
            pack_id="es-def-wikc",
            sources="definitions\tWikcionario\thttps://es.wiktionary.org/\t\t\n")
        self.assertNotEqual(0, codigo)
        self.assertIn("licencia", salida.lower() + salida)


class PoliticaDeContenidoTest(BuilderTestCase):
    """El validador comprueba el ARTEFACTO, no el builder.

    Un flag mal cableado pasa los tests de la fuente --que le pasan el valor a mano-- y deja el
    pack con los nombres propios adentro igual. Lo unico que lo agarra es contar filas en el
    pack terminado.
    """

    def test_un_pack_que_dice_excluded_y_trae_nombres_propios_se_rechaza(self):
        metadata = dict(BASE_META)
        metadata["proper_nouns"] = "excluded"
        with build.PackBuilder(self.path, metadata) as builder:
            builder.add(record("correr"))
            builder.add(record("Troya", gloss="Apellido.", part_of_speech="name"))
        salida = io.StringIO()
        with contextlib.redirect_stdout(salida):
            codigo = verify_pack.verify(self.path)
        self.assertNotEqual(0, codigo, "verify_pack tiene que cazar un pack que se contradice")
        self.assertIn("proper_nouns", salida.getvalue())

    def test_definitions_only_exige_que_el_nombre_propio_este_CASTIGADO(self):
        """La invariante que 'definitions-only' trae consigo (D-134).

        La politica deja entrar nombres propios a proposito, asi que el techo de proporcion que
        cuida a 'lexical-only' no aplica. Lo que si tiene que cumplirse es lo que hace que la
        politica sea segura: **que ninguno de ellos pueda ganarle en rank a una palabra comun**.
        Si alguien cablea mal el castigo, el pack sale entero, abre sin error y devuelve el
        toponimo arriba -- que es exactamente el modo de falla que D-116 midio en ingles, 4.267
        veces. Sin este check nada lo veria.
        """
        metadata = dict(BASE_META)
        metadata["proper_nouns"] = "definitions-only"
        with build.PackBuilder(self.path, metadata) as builder:
            builder.add(record("fez", gloss="Gorro de fieltro rojo.", rank=120))
            builder.add(record("Fez", gloss="Ciudad de Marruecos.",
                               part_of_speech="name", rank=120))
        salida = io.StringIO()
        with contextlib.redirect_stdout(salida):
            codigo = verify_pack.verify(self.path)
        self.assertNotEqual(0, codigo, "un nombre propio sin castigar tiene que rechazarse")
        self.assertIn("rank", salida.getvalue())

    def test_definitions_only_acepta_el_pack_bien_construido(self):
        metadata = dict(BASE_META)
        metadata["proper_nouns"] = "definitions-only"
        with build.PackBuilder(self.path, metadata) as builder:
            builder.add(record("fez", gloss="Gorro de fieltro rojo.", rank=120))
            builder.add(record("Fez", gloss="Ciudad de Marruecos.",
                               part_of_speech="name", rank=1120))
        salida = io.StringIO()
        with contextlib.redirect_stdout(salida):
            codigo = verify_pack.verify(self.path)
        self.assertEqual(0, codigo, salida.getvalue())

    def test_los_dos_vocabularios_de_pos_cuentan(self):
        """kaikki dice "name", el toy dice "proper noun". Excluir uno solo deja pasar el otro."""
        metadata = dict(BASE_META)
        metadata["proper_nouns"] = "excluded"
        with build.PackBuilder(self.path, metadata) as builder:
            builder.add(record("correr"))
            builder.add(record("Mexico", part_of_speech="proper noun"))
        salida = io.StringIO()
        with contextlib.redirect_stdout(salida):
            codigo = verify_pack.verify(self.path)
        self.assertNotEqual(0, codigo, "'proper noun' tambien es un nombre propio")

    def test_lexical_only_acepta_unos_pocos_pero_no_un_pack_sin_podar(self):
        """La excepcion de la señal lexica deja pasar 1.675 nombres propios en ingles (0,2 %).

        El validador no puede recalcular la señal --no tiene el dump-- asi que comprueba lo que
        si puede ver: que sean una minoria. Un pack sin podar tiene 17-22 %, asi que el margen
        es enorme y el check igual caza el caso que importa (que la poda no corrio).
        """
        metadata = dict(BASE_META)
        metadata["proper_nouns"] = "lexical-only"
        with build.PackBuilder(self.path, metadata) as builder:
            for i in range(50):
                builder.add(record("comun%03d" % i))
            builder.add(record("January", part_of_speech="name"))
        salida = io.StringIO()
        with contextlib.redirect_stdout(salida):
            self.assertEqual(0, verify_pack.verify(self.path), salida.getvalue())

    def test_lexical_only_rechaza_un_pack_donde_la_poda_no_corrio(self):
        metadata = dict(BASE_META)
        metadata["proper_nouns"] = "lexical-only"
        with build.PackBuilder(self.path, metadata) as builder:
            for i in range(10):
                builder.add(record("comun%03d" % i))
            for i in range(10):
                builder.add(record("Apellido%03d" % i, part_of_speech="name"))
        salida = io.StringIO()
        with contextlib.redirect_stdout(salida):
            codigo = verify_pack.verify(self.path)
        self.assertNotEqual(0, codigo, "50 % de nombres propios no es 'lexical-only'")
        self.assertIn("proper_nouns", salida.getvalue())

    def test_una_politica_desconocida_se_rechaza(self):
        """Un typo en el valor no puede SALTEAR el check estructural en silencio.

        El check se dispara con `politica in ("excluded", "lexical-only")`, asi que
        `"lexical_only"` --guion bajo en vez de guion-- cae al mismo lado que `"included"`:
        el pack pasa entero sin que nadie cuente un solo nombre propio. Es el peor modo de
        falla del validador, porque el pack se declara podado y nadie lo comprueba.
        """
        metadata = dict(BASE_META)
        metadata["proper_nouns"] = "lexical_only"
        with build.PackBuilder(self.path, metadata) as builder:
            for i in range(10):
                builder.add(record("comun%03d" % i))
            for i in range(10):
                builder.add(record("Apellido%03d" % i, part_of_speech="name"))
        salida = io.StringIO()
        with contextlib.redirect_stdout(salida):
            codigo = verify_pack.verify(self.path)
        self.assertNotEqual(0, codigo, "un proper_nouns desconocido tiene que fallar")
        self.assertIn("proper_nouns", salida.getvalue())

    def test_un_pack_que_los_declara_no_se_rechaza(self):
        metadata = dict(BASE_META)
        metadata["proper_nouns"] = "included"
        with build.PackBuilder(self.path, metadata) as builder:
            builder.add(record("Troya", gloss="Apellido.", part_of_speech="name"))
        salida = io.StringIO()
        with contextlib.redirect_stdout(salida):
            codigo = verify_pack.verify(self.path)
        self.assertEqual(0, codigo, salida.getvalue())


class StructureTest(BuilderTestCase):
    def test_indexes_exist_and_staging_is_gone(self):
        db = self.build([record("correr")])
        names = {row[0] for row in db.execute("SELECT name FROM sqlite_master")}
        self.assertIn("idx_entry_norm", names)
        self.assertIn("idx_entry_fuzzy", names)
        self.assertNotIn("staging", names)
        self.assertNotIn("staging_trans", names)

    def test_el_prefijo_devuelve_primero_la_entrada_mas_comun(self):
        """rank es "menor es mas comun" (schema.sql) y el prefijo tiene que respetarlo.

        No es teorico: en el primer pack real, buscar "escrit" devolvia
        `escrito|verb` ("Participio de escribir", rank 994) **antes** que `escrito|noun`
        (rank 988), porque la consulta ordenaba por `rank DESC`. Con 22 entradas de juguete
        no se ve: rank solo desempata dentro de un mismo `norm`, y el toy pack casi no tiene.
        En el pack real, 375 de 7.265 norms tienen mas de una entrada.
        """
        db = self.build([
            record("escrito", gloss="participio de escribir", rank=994),
            record("escrito", gloss="documento", rank=988, part_of_speech="noun"),
        ])
        rows = [row[0] for row in db.execute(
            "SELECT rank FROM entry WHERE norm >= ? AND norm < ? ORDER BY norm, rank LIMIT 10",
            ("escrit", "escriu"))]
        self.assertEqual([988, 994], rows)

    def test_el_indice_satisface_el_orden_del_prefijo_sin_ordenar(self):
        """La consulta sale integra del covering index (D-012). Si el indice y el ORDER BY no
        coinciden en la direccion de `rank`, SQLite agrega un sort: sigue siendo correcto, pero
        deja de ser el plan que el diseno afirma, y en un pack de 150.000 entradas eso se paga.
        """
        db = self.build([record("escrito", rank=1), record("casa", rank=2)])
        plan = " ".join(str(row) for row in db.execute(
            "EXPLAIN QUERY PLAN SELECT id, headword, pos FROM entry"
            " WHERE norm >= ? AND norm < ? ORDER BY norm, rank LIMIT 10", ("a", "b")))
        self.assertIn("COVERING INDEX idx_entry_norm", plan)
        self.assertNotIn("TEMP B-TREE", plan)

    def test_fts_rowid_matches_entry_id(self):
        # fts_def es contentless: el rowid es lo unico que devuelve, asi que si no coincide con
        # entry.id la busqueda de texto libre apunta a entradas equivocadas.
        db = self.build(
            [record("correr", gloss="moverse rapidamente"), record("casa", gloss="edificio")]
        )
        entry_id = db.execute("SELECT id FROM entry WHERE headword='correr'").fetchone()[0]
        rows = [row[0] for row in db.execute(
            "SELECT rowid FROM fts_def WHERE fts_def MATCH ?", ('"rapidamente"',))]
        self.assertEqual([entry_id], rows)


class FailureModeTest(BuilderTestCase):
    def test_reserved_meta_keys_are_rejected(self):
        # Una schema_version escrita a mano seria una forma silenciosa de romper la validacion
        # que hace el reloj al abrir el pack.
        metadata = dict(BASE_META)
        metadata["schema_version"] = "99"
        with self.assertRaises(ValueError):
            build.PackBuilder(self.path, metadata)

    def test_una_palabra_muy_comun_en_las_glosas_no_hace_fallar_la_comprobacion_de_fts(self):
        """La invariante es que FTS **encuentre** la entrada, no que la rankee alto.

        Lo destapo el pack de ingles: la entrada de mejor rank es "you", su glosa empieza con
        "The people spoken...", y "people" aparece en 890 de 47.718 definiciones. La entrada
        estaba --posicion 721 de 890-- pero fuera del top 30, y la comprobacion fallaba por un
        pack correcto. Confundir indexado con rankeado es un falso negativo que manda a buscar
        un bug que no existe.

        El modo de falla real que esto cuida sigue cubierto: si `fts_def.rowid` se desalineara de
        `entry.id` (D-011), la entrada no apareceria en NINGUNA posicion.
        """
        # La entrada de mejor rank tiene la glosa LARGA --bm25 castiga la longitud-- y otras
        # cincuenta cortas comparten el termino. Asi la entrada correcta cae fuera del top 30,
        # que es exactamente lo que paso con "you" y "people" en el pack de ingles.
        larga = "personas " + " ".join("relleno%d" % i for i in range(40))
        registros = [build.Record(headword="aaa", senses=[{"gloss": larga}], rank=0)]
        registros += [
            build.Record(headword="bbb%03d" % i, senses=[{"gloss": "personas"}], rank=500)
            for i in range(50)
        ]
        with build.PackBuilder(self.path, dict(BASE_META)) as builder:
            for item in registros:
                builder.add(item)
        salida = io.StringIO()
        with contextlib.redirect_stdout(salida):
            codigo = verify_pack.verify(self.path)
        self.assertEqual(0, codigo, "un pack correcto no puede fallar por una glosa repetida:\n%s"
                         % salida.getvalue())

    def test_verify_pack_rechaza_un_entero_de_meta_que_no_lo_es(self):
        """`PackFile.parseMetadata` parsea tres claves de meta como numeros: un string revienta
        al ABRIR, en el reloj, con un NumberFormatException que no nombra la clave.

        Es la clase de bug que este repo existe para no tener: el builder lo escribe, el
        validador lo deja pasar y el error aparece recien en el dispositivo. Paso de verdad --el
        primer pack real se construyo con `data_version = "2026-09-15"` y `verify_pack.py` dio
        verde--, y **esa causa concreta ya no existe**: desde que `data_version` lo deriva el
        builder no hay forma de escribirlo mal. Lo que se fija aca es que el validador siga
        mirando, porque las otras dos claves se escriben igual.
        """
        with build.PackBuilder(self.path, dict(BASE_META)) as builder:
            builder.add(record("correr"))
        with sqlite3.connect(self.path) as db:
            db.execute("UPDATE meta SET value = '2026-09-15' WHERE key = 'data_version'")
        salida = io.StringIO()
        with contextlib.redirect_stdout(salida):
            codigo = verify_pack.verify(self.path)
        self.assertNotEqual(0, codigo, "verify_pack deberia fallar con un data_version no entero")
        self.assertIn("data_version", salida.getvalue())

    def test_unknown_fuzzy_profile_is_rejected(self):
        metadata = dict(BASE_META)
        metadata["fuzzy_profile"] = "klingon"
        with self.assertRaises(ValueError):
            build.PackBuilder(self.path, metadata)

    def test_empty_pack_is_rejected(self):
        with self.assertRaises(ValueError), build.PackBuilder(self.path, dict(BASE_META)):
            pass

    def test_failure_leaves_no_half_built_pack(self):
        # Un pack a medias es peor que ninguno: se abriria sin error y devolveria resultados
        # incompletos, sin nada que indique que le faltan entradas.
        class Boom(Exception):
            pass

        # noqa de SIM117 a proposito: assertRaises no es un peer del otro context manager,
        # afirma SOBRE el. Combinarlos en un solo `with` los mostraria como iguales.
        with self.assertRaises(Boom):  # noqa: SIM117
            with build.PackBuilder(self.path, dict(BASE_META)) as builder:
                builder.add(record("correr"))
                raise Boom()
        self.assertFalse(os.path.exists(self.path), "quedo un pack a medio construir")


class LogicalIdentityTest(BuilderTestCase):
    """entry.uid: la identidad que sobrevive a reconstruir el pack (D-055).

    Es lo que hace posible que un pack auxiliar le sume informacion a una entrada de este. Si se
    rompe, el auxiliar apunta a la entrada equivocada y no hay ningun error: se muestran los
    sinonimos de otra palabra.
    """

    def _uids(self, records):
        db = self.build(records)
        filas = db.execute("SELECT headword, uid, id FROM entry")
        out = {row[0]: (row[1], row[2]) for row in filas}
        db.close()
        return out

    def test_el_uid_sobrevive_a_que_la_fuente_agregue_una_palabra_en_el_medio(self):
        # El caso que motiva toda la decision: entry.id se corre, entry.uid no.
        antes = self._uids([record("alfa"), record("gamma")])
        self.setUp()
        despues = self._uids([record("alfa"), record("beta"), record("gamma")])

        self.assertNotEqual(
            antes["gamma"][1], despues["gamma"][1], "entry.id deberia haberse corrido"
        )
        self.assertEqual(antes["alfa"][0], despues["alfa"][0])
        self.assertEqual(
            antes["gamma"][0], despues["gamma"][0], "entry.uid cambio al reconstruir el pack"
        )

    def test_el_uid_no_depende_de_la_normalizacion(self):
        # Va sobre el headword crudo: subir NORM_VERSION no puede invalidar los packs auxiliares.
        # Efecto colateral buscado: "arbol" y "árbol" normalizan igual y son entradas distintas.
        uids = self._uids([record("arbol"), record("árbol")])
        self.assertNotEqual(uids["arbol"][0], uids["árbol"][0])

    def test_los_homografos_con_pos_distinto_tienen_uid_distinto(self):
        uids = self._uids(
            [record("bajo", part_of_speech="adjective"),
             record("bajo", part_of_speech="preposition")]
        )
        db = self.build(
            [record("bajo", part_of_speech="adjective"),
             record("bajo", part_of_speech="preposition")]
        )
        distintos = db.execute("SELECT COUNT(DISTINCT uid) FROM entry").fetchone()[0]
        db.close()
        self.assertEqual(distintos, 2)
        self.assertEqual(len(uids), 1)  # el dict los pisa: comparten headword, no uid

    def test_dos_entradas_con_la_misma_identidad_hacen_fallar_el_build(self):
        # Fundirlas seria peor: cualquier desempate por orden de insercion rompe justo la
        # estabilidad entre rebuilds que el uid existe para dar.
        with self.assertRaises(ValueError) as caught:
            self.build([record("banco", part_of_speech="noun"),
                        record("banco", part_of_speech="noun")])
        self.assertIn("sense_key", str(caught.exception))
        self.assertFalse(os.path.exists(self.path), "quedo un pack a medio construir")

    def test_sense_key_separa_dos_entradas_que_de_otro_modo_colisionarian(self):
        db = self.build(
            [
                record("banco", part_of_speech="noun", sense_key="et1"),
                record("banco", part_of_speech="noun", sense_key="et2"),
            ]
        )
        self.assertEqual(db.execute("SELECT COUNT(DISTINCT uid) FROM entry").fetchone()[0], 2)
        db.close()

    def test_el_uid_no_depende_del_pack_que_lo_escribe(self):
        # Dos packs distintos del mismo idioma tienen que darle el mismo uid a la misma palabra:
        # si dependiera del pack_id, ninguna composicion seria posible.
        otro = dict(BASE_META, pack_id="otro", name="Otro")
        primero = self.build([record("correr")])
        uid_primero = primero.execute("SELECT uid FROM entry").fetchone()[0]
        primero.close()
        self.setUp()
        segundo = self.build([record("correr")], metadata=otro)
        uid_segundo = segundo.execute("SELECT uid FROM entry").fetchone()[0]
        segundo.close()
        self.assertEqual(uid_primero, uid_segundo)

    def test_un_pack_sin_langs_se_rechaza(self):
        sin_idioma = {k: v for k, v in BASE_META.items() if k != "langs"}
        with self.assertRaises(ValueError):
            self.build([record("correr")], metadata=sin_idioma)


class DeterminismTest(BuilderTestCase):
    def test_two_builds_produce_the_same_data(self):
        # Determinista para que reconstruir un pack sin cambios no genere una descarga nueva.
        def contents(path):
            with build.PackBuilder(path, dict(toy.METADATA)) as builder:
                for item in toy.records():
                    builder.add(item)
            db = sqlite3.connect(path)
            entries = list(
                db.execute(
                    "SELECT id, uid, headword, norm, fuzzy, pos, rank, payload"
                    " FROM entry ORDER BY id"
                )
            )
            meta = dict(db.execute("SELECT key, value FROM meta"))
            meta.pop("built_at")  # unico campo que cambia entre corridas, a proposito
            db.close()
            return entries, meta

        first = contents(os.path.join(self.tmp, "a.db"))
        second = contents(os.path.join(self.tmp, "b.db"))
        self.assertEqual(first[1], second[1], "la metadata cambio entre dos builds iguales")
        self.assertEqual(first[0], second[0], "los payloads cambiaron entre dos builds iguales")


if __name__ == "__main__":
    unittest.main()



class DataVersionTest(unittest.TestCase):
    """`data_version` distingue dos builds del MISMO dump.

    ⚠️ **El bug que esto cierra**: era la fecha del dump escrita a mano, asi que reconstruir el
    mismo dump con otro builder --otra poda, otra fuente sumada, otro `rank`-- daba **el mismo
    numero**, y `devpack.py` y el instalador lo leian como "es el mismo pack". Un pack mejor no
    se propagaba nunca.
    """

    def test_es_un_entero_de_doce_digitos_legible_como_fecha(self):
        # AAAAMMDDHHMM: un humano lo lee sin convertidor, que era la mitad del pedido. La otra
        # mitad es que ordene, y un numero con esta forma ordena igual que el tiempo.
        valor = build.data_version((2026, 9, 21, 14, 32))
        self.assertEqual("202609211432", valor)
        self.assertRegex(build.data_version(), r"^20\d{10}$")

    def test_dos_builds_del_mismo_dump_dan_numeros_distintos_y_ordenados(self):
        uno = build.data_version((2026, 9, 21, 14, 32))
        dos = build.data_version((2026, 9, 21, 14, 33))
        self.assertLess(int(uno), int(dos),
                        "el mas nuevo tiene que ser el mayor: de eso vive el instalador")

    def test_entra_en_un_Long_y_NO_en_un_Int(self):
        # ⚠️ La app lo parsea, y por eso este test existe: 202609211432 **no entra en un Int de
        # 32 bits**. Si alguien vuelve `dataVersion` a Int, el pack revienta al abrir en el reloj
        # con un NumberFormatException que no nombra la clave (D-070).
        valor = int(build.data_version((2026, 9, 21, 14, 32)))
        self.assertGreater(valor, 2 ** 31 - 1)
        self.assertLess(valor, 2 ** 63 - 1)

    def test_escribirlo_a_mano_es_un_error(self):
        # Si se puede escribir a mano, alguien se va a olvidar de subirlo: es exactamente lo que
        # paso durante meses.
        with self.assertRaises(ValueError):
            build.PackBuilder(self.path, dict(BASE_META, data_version="20260915"))

    def test_la_fecha_del_dump_no_se_pierde(self):
        # Lo que el valor escrito a mano SIGNIFICABA --de que volcado sale el contenido-- sigue
        # siendo informacion util, asi que se declara aparte en vez de desaparecer.
        self.path = os.path.join(self.dir, "fecha.db")
        constructor = build.PackBuilder(self.path, dict(BASE_META, source_date="20260915"))
        constructor.add(record("casa"))
        constructor.finish()
        with sqlite3.connect(self.path) as db:
            meta = dict(db.execute("SELECT key, value FROM meta"))
        self.assertEqual("20260915", meta["source_date"])

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.path = os.path.join(self.dir, "dv.db")

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)


class ListaDeCoberturaTest(unittest.TestCase):
    """A quien le exige la lista de cobertura las palabras de un idioma.

    ⚠️ **Lo trajo el rebuild, no un test.** El pack bilingue declara `langs = es,en` y reprobo
    por `tuesday`: `martes` trae su traduccion **glosada dentro de la acepcion** --*«Tuesday (the
    third day of the week...)»*-- en vez de un termino limpio, asi que nunca salio la entrada
    inglesa. Es un hueco real **y una promesa que ese pack no hizo**: su lado ingles existe para
    la direccion inversa (D-196), no para ser un diccionario de ingles.

    La regla que queda: **la lista le exige a un pack el idioma del que es diccionario**. En un
    monolingue, todos los que declara; en un bilingue, el de ORIGEN. Lo demas se informa, porque
    callarlo seria perder la señal que encontro esto.
    """

    def _pack(self, langs, kind, palabras_presentes):
        path = os.path.join(self.dir, "%s-%s.db" % (kind, langs.replace(",", "")))
        db = sqlite3.connect(path)
        db.execute("CREATE TABLE meta (key TEXT, value TEXT)")
        db.execute("CREATE TABLE entry (id INTEGER, norm TEXT)")
        db.execute("CREATE TABLE form (entry_id INTEGER, norm TEXT)")
        db.executemany("INSERT INTO meta VALUES (?, ?)",
                       [("langs", langs), ("kind", kind)])
        # ⚠️ Con menos entradas que palabras tiene la lista, el chequeo se salta por "es un
        # fixture". El relleno existe para que la regla que se prueba sea la del idioma.
        filas = list(palabras_presentes) + ["relleno%d" % i for i in range(8)]
        db.executemany("INSERT INTO entry VALUES (?, ?)", list(enumerate(filas)))
        db.commit()
        return db, dict(db.execute("SELECT key, value FROM meta"))

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.original = verify_pack.lista_de_cobertura
        verify_pack.lista_de_cobertura = lambda lang: {
            "es": [("martes", True), ("casa", True)],
            "en": [("tuesday", True), ("house", True), ("blockchain", False)]}.get(lang)

    def tearDown(self):
        verify_pack.lista_de_cobertura = self.original
        shutil.rmtree(self.dir, ignore_errors=True)

    def test_a_un_MONOLINGUE_se_le_exige_su_idioma(self):
        db, meta = self._pack("en", "monolingual", ["house"])
        report = verify_pack.Report()
        verify_pack._verify_vocabulary(db, meta, report)
        self.assertTrue(any("tuesday" in f for f in report.failures), report.failures)

    def test_a_un_BILINGUE_se_le_exige_el_idioma_de_ORIGEN(self):
        db, meta = self._pack("es,en", "bilingual", ["casa"])
        report = verify_pack.Report()
        verify_pack._verify_vocabulary(db, meta, report)
        self.assertTrue(any("martes" in f for f in report.failures), report.failures)

    def test_lo_que_solo_obliga_al_COMPLETO_no_reprueba_a_un_nivel(self):
        """⚠️ **Un corte por frecuencia no puede traer una palabra que no tiene frecuencia.**

        Medido sobre `freq-en-opensubs.txt`: `blockchain`, `deepfake` y `workaround` tienen
        **cero** apariciones. Exigirselas a un `core` es pedirle al corte algo que su propia
        metrica no puede entregar -- y el grupo que las contiene defiende otra cosa: que la
        FUENTE traiga vocabulario de hoy (D-120), que es una propiedad del pack completo.

        `tuesday`, en cambio, tiene 14.074: si falta en un nivel, el corte esta roto.
        """
        db, meta = self._pack("en", "monolingual", ["tuesday", "house"])
        meta["tier"] = "core"
        report = verify_pack.Report()
        verify_pack._verify_vocabulary(db, meta, report)
        self.assertEqual([], report.failures)

    def test_y_al_pack_COMPLETO_si_se_le_exige(self):
        db, meta = self._pack("en", "monolingual", ["tuesday", "house"])
        meta["tier"] = "full"
        report = verify_pack.Report()
        verify_pack._verify_vocabulary(db, meta, report)
        self.assertTrue(any("blockchain" in f for f in report.failures), report.failures)

    def test_al_BILINGUE_el_idioma_DESTINO_se_le_informa_y_no_reprueba(self):
        """El lado ingles de `es-en` es la direccion inversa, no un diccionario de ingles."""
        db, meta = self._pack("es,en", "bilingual", ["casa", "martes", "house"])
        report = verify_pack.Report()
        verify_pack._verify_vocabulary(db, meta, report)
        self.assertEqual([], report.failures)
