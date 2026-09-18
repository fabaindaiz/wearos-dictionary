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

BASE_META = {
    "pack_id": "test",
    "kind": "bilingual",
    "name": "Test",
    "lang_src": "es",
    "lang_dst": "en",
    "fuzzy_profile": "es",
    "data_version": "1",
    "license": "CC0-1.0",
    "attribution": "test",
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
        self.assertGreater(
            self.db.execute(
                "SELECT COUNT(*) FROM trans t JOIN entry e ON e.id = t.entry_id"
                " WHERE t.norm = 'run' AND e.headword = 'correr'"
            ).fetchone()[0],
            0,
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

    def test_un_data_version_no_entero_se_rechaza(self):
        """`PackFile.parseMetadata` hace `data_version.toInt()`: un string revienta al ABRIR.

        Es la clase de bug que este repo existe para no tener: el builder lo escribe, el
        validador lo deja pasar y el error aparece recien en el reloj. Paso de verdad -- el
        primer pack real se construyo con `data_version = "2026-09-15"` y `verify_pack.py` dio
        verde-- asi que la comprobacion vive ahora del lado que lo produce.
        """
        metadata = dict(BASE_META)
        metadata["data_version"] = "2026-09-15"
        with build.PackBuilder(self.path, metadata) as builder:
            builder.add(record("correr"))
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
        out = {row[0]: (row[1], row[2]) for row in db.execute("SELECT headword, uid, id FROM entry")}
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
            [record("bajo", part_of_speech="adjective"), record("bajo", part_of_speech="preposition")]
        )
        db = self.build(
            [record("bajo", part_of_speech="adjective"), record("bajo", part_of_speech="preposition")]
        )
        distintos = db.execute("SELECT COUNT(DISTINCT uid) FROM entry").fetchone()[0]
        db.close()
        self.assertEqual(distintos, 2)
        self.assertEqual(len(uids), 1)  # el dict los pisa: comparten headword, no uid

    def test_dos_entradas_con_la_misma_identidad_hacen_fallar_el_build(self):
        # Fundirlas seria peor: cualquier desempate por orden de insercion rompe justo la
        # estabilidad entre rebuilds que el uid existe para dar.
        with self.assertRaises(ValueError) as caught:
            self.build([record("banco", part_of_speech="noun"), record("banco", part_of_speech="noun")])
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
        base = dict(BASE_META)
        otro = dict(BASE_META, pack_id="otro", name="Otro")
        primero = self.build([record("correr")])
        uid_primero = primero.execute("SELECT uid FROM entry").fetchone()[0]
        primero.close()
        self.setUp()
        segundo = self.build([record("correr")], metadata=otro)
        uid_segundo = segundo.execute("SELECT uid FROM entry").fetchone()[0]
        segundo.close()
        self.assertEqual(uid_primero, uid_segundo)

    def test_un_pack_sin_lang_src_se_rechaza(self):
        sin_idioma = {k: v for k, v in BASE_META.items() if k != "lang_src"}
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
