"""Tests of the pack builder.

They build real packs in a temporary directory and inspect them with SQL. What gets verified here
is above all what does NOT fail loudly: a half-built pack, or one with the translations cap
misapplied, opens with no error and returns incomplete results.
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
    # A `pack_id` in the shape D-138 requires: <language>-<type>-<source>. A bare "test" no longer
    # works, and the test that rejects it lives in ManifiestoTest.
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
        # verify_pack.py is what gets run over the real packs; if the toy one does not pass, the
        # real one will not pass either.
        with build.PackBuilder(self.path, dict(toy.METADATA)) as builder:
            for item in toy.records():
                builder.add(item)
        self.assertEqual(0, verify_pack.verify(self.path), "verify_pack encontro fallas")


class ToyPackFixtureTest(BuilderTestCase):
    """The toy pack has to keep exercising the five search paths.

    :dict-data's instrumented tests (SqlitePackSourceTest) depend on this pack's CONTENT: that
    "coreer" is not found by the prefix, that "c" gives more results than the tolerant rung's
    threshold, that "bajo" has two homographs. None of that is obvious when editing
    sources/toy.py.

    Without these tests, breaking one of those assumptions would not be noticed until an emulator
    was connected, and the failure would read as a bug in the code and not in the fixture.
    """

    # Espejo de SqlitePackSource.FUZZY_TRIGGER. Si cambia alla, cambia aca.
    FUZZY_TRIGGER = 5

    def _payload(self, entry_id):
        """An entry's decompressed body, to look at what the card would show."""
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
        """The trap that makes visible that FTS5 orders by relevance and `entry.id` does not.

        `searchDefinitions` queries `fts_def MATCH ... ORDER BY rank` --bm25-- and then resolves
        the rowids with `WHERE id IN (...)`, which comes out in rowid order. If the best match
        always had the lowest rowid, throwing away the ranking would not be noticed and the bug
        would live forever.

        Two entries share the term: one with a LONG gloss that mentions it once, and one with a
        SHORT gloss that repeats it --bm25 rewards the short one and penalizes the long one--. The
        long one goes first in `_DATA`, so it takes the lower rowid.

        This test runs IN THE GATE. The one that checks the app respects that order is instrumented
        and needs a device: without this guard, reordering `_DATA` would break that one in silence.
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
        # If this drops below the threshold, the test that checks the tolerant rung does NOT fire
        # would pass for the wrong reason.
        self.assertGreaterEqual(len(self.prefijo("c")), self.FUZZY_TRIGGER)

    def test_hay_un_tipeo_que_solo_alcanza_el_nivel_tolerante(self):
        # "coreer" must not be reachable by prefix or by inflected form: if it were, the tolerant
        # rung's test would not be testing the tolerant rung.
        self.assertEqual([], self.prefijo("coreer"))
        formas = self.db.execute(
            "SELECT COUNT(*) FROM form WHERE norm = ?", (normalize.norm("coreer"),)
        ).fetchone()[0]
        self.assertEqual(0, formas)
        # But it does have to land in the fuzzy neighbourhood.
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
        # ⚠️ **`run` no longer lives in `trans` but IS an entry**, and that change is the
        # bidirectional pack: `trans` is emptied because it would be a second copy of the same
        # index. What gets checked is the same as always --that typing `run` reaches `correr`--
        # through the new path.
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
        """Without this trap, the exact-first rule has nothing to prove.

        "sol" is the exact match and ranks 500; "soler" only has it as a prefix and ranks 50.
        Ordering by rank alone, typing "sol" does not return "sol".
        """
        filas = dict(self.db.execute(
            "SELECT headword, rank FROM entry WHERE headword IN ('sol', 'soler')"))
        self.assertEqual({"sol": 500, "soler": 50}, filas)
        self.assertEqual("sol", self.prefijo("sol")[0])

    def test_hay_dos_entradas_con_el_mismo_headword_y_el_mismo_pos(self):
        """It is Wiktionary's "hacer" case, which appears five times by etymology.

        They are distinct and legitimate entries --uid separates them-- but a list that shows them
        all repeats the same word. The deduplication lives in SqlitePackSource, not here; this only
        guarantees the fixture keeps the case.
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
        # "!!!" cannot be searched by any path; getting into the pack would only take space.
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
        # The best ranked (lower is more common) are kept, not the first to arrive.
        self.assertEqual(list(range(limit)), kept)

        dropped = db.execute("SELECT value FROM meta WHERE key='trans_dropped'").fetchone()[0]
        self.assertEqual(25, int(dropped), "meta.trans_dropped no refleja lo recortado")

    def test_un_lema_cuyo_fuzzy_es_vacio_sigue_siendo_entrada(self):
        """CHARACTERIZATION: the builder already behaved this way; this test pins the behaviour.

        `fuzzy("h")` is empty: the h is silent in the Spanish profile. The entry exists all the
        same.

        An empty `norm` and an empty `fuzzy` are not the same problem. With no `norm` the entry is
        unreachable and there is no sense storing it. With no `fuzzy` it merely falls outside the
        tolerant rung: it is still found by prefix and exact match, which is how a letter gets
        searched for.

        The first real pack found it: "h" and "H" are Wiktionary entries (the letter) and they made
        verify_pack.py's invariant fail, because it treated both cases alike.
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
    """Every pack declares its content policy, and the validator checks it (D-116).

    The rule is enforced from Python and not from `audit_dictionary.py` because here `PACKS` can be
    **imported**; there it would have to be read with a regex over a dict, which breaks on its own
    the moment somebody reorders the file.
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
    """Searching a synonym has to find the entry (D-118).

    It is half the reason for the change: without this the synonyms are only SEEN on opening an
    entry you have already found, which is exactly when you no longer need them.
    """

    def test_los_sinonimos_entran_al_indice_de_texto_libre(self):
        # The gloss does NOT contain "bobo": if the match appears, it came from the synonym.
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
    """`verify_pack.py` rejects a citation that does not hang off an example.

    ⚠️ **It is checked over the BYTES and not over the parsed structure, and that is the difference
    that makes the check useful.** `payload.parse` already discards the orphaned citation in
    silence, which is the right degradation for the reader; but a pack built by somebody else --or
    by a future version of the builder with a bug-- would carry it inside, and the user would see
    an entry missing the attribution the pack said it carried. It is also the only payload CONTENT
    check this validator has: until now it looked only at structural invariants.
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
        # The genuinely dangerous case: there is an example, so the citation "looks" as though it
        # has something to hang off -- but the tag in between displaces it and whoever reads it
        # would assign it an example the source never attributed to it.
        codigo, salida = self._pack_con_cuerpo(
            "P\tverb\nS\tuna glosa\nE\tun ejemplo\nY\tsinonimo\nC\t1897, Richard Marsh\n")
        self.assertEqual(1, codigo, salida)

    def test_la_cita_pegada_a_su_ejemplo_pasa(self):
        codigo, salida = self._pack_con_cuerpo(
            "P\tverb\nS\tuna glosa\nE\tun ejemplo\nC\t1897, Richard Marsh\n")
        self.assertEqual(0, codigo, salida)


class ComoLaAppTest(BuilderTestCase):
    """The mirror mode: `verify_pack.py --como-la-app` answers what the app would answer.

    ⚠️ **It is the repo's fourth cross-language contract** (D-217), and what holds it up is
    twofold: the audit compares `MOTIVOS_DE_LA_APP`'s ids against the `PackRejection` enum, and
    these cases check that each reason **actually fires**. Without the second, a table with the
    right ids and broken checks would pass the audit and lie in every answer.
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
        """⚠️ The case the real `schema_version` 3 packs found.

        A pack of another schema can **also** be missing keys that were born later, so both reasons
        apply. The one reported has to be the schema: it is the only one that tells the user what to
        do.
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
        # It is the repo's central failure mode: the word is there and no search reaches it.
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
    """What `verify()` looks at beyond the app, because it runs at build time and can spend."""

    def test_una_lista_con_items_repetidos_hace_fallar(self):
        # D-218. `render` deduplicates it while building; this checks it over the BYTES, which is
        # the only thing that counts for a pack we did not build.
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
        # The reader ignores them on purpose (D-119), so this is the only place a tag the builder
        # wrote wrongly can be noticed.
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
        """⚠️ The `form` table is 1.5 million rows NOBODY was looking at.

        D-142 recomputes a sample of `entry`; `form` is the one that resolves an inflection, and one
        of its keys built under other rules is the word that is in the file and no search reaches.
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
    """The antonyms go to the payload and NOT to `fts_def` (D-126).

    It is the opposite of what was decided for the synonyms (D-118), and the reason is that the
    question each answers is different: a synonym is another way of naming what you are looking
    for, an antonym is what you are NOT looking for. Indexing it would make typing "frio" return
    "caliente", with the result ordering --already debt (D-067)-- deciding how high that inverted
    answer appears.

    Without this test, somebody adding a field to the payload adds it to `_fts_body` out of symmetry
    and nothing fails: the pack comes out larger and the search noisier, in silence.
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
        """The example IS indexed (D-118) and its citation is NOT, and the asymmetry is the point.

        A citation is provenance, not meaning: searching "Richard Marsh" has to return nothing, not
        the `Thomas` entry. It would also be the third case of the same mistake -- D-117 measured
        that the synonyms cost **three times** the estimate precisely because `fts_def` indexes them
        on top of the payload, and the English pack's citations weigh nearly as much as they do.
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
        """The same criterion as the antonym, and it is exactly the slip the docstring announces.

        `related` is the payload's third word list (D-132) and the temptation to add it to
        `_fts_body` "out of symmetry" with the synonyms is the same. It does not belong: nobody
        types "camelido" expecting "guanaco", and the entry it would return competes for the order
        with the one the user was actually looking for.
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
    """The pack declares WHAT it is, WHERE it comes from and HOW it can be used, and the validator
    requires it.

    They are the three questions somebody receiving a 68 MB `.db` has to be able to answer without
    asking anybody. The failure mode is silent in all three: a pack with no manifest opens, searches
    and works -- and there is no way to know whether it can be redistributed.
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
        """The code avoids the collision, which is what a generic name cannot avoid.

        Two Spanish packs from different sources installed at once (D-136) share a language and a
        type: the only thing that separates them is the source code. With `pack_id = "espanol"`
        both are "espanol", one overwrites the other on installing, and the watch's history ends up
        pointing at entries of a pack that is no longer there.
        """
        codigo, salida = self._con_meta(pack_id="espanol")
        self.assertNotEqual(0, codigo)
        self.assertIn("pack_id", salida)

    def test_el_pack_id_con_la_forma_correcta_pasa(self):
        codigo, salida = self._con_meta(pack_id="es-def-wikc", sources=_FUENTE)
        self.assertEqual(0, codigo, salida)

    def test_las_variantes_son_parte_de_la_forma(self):
        # "es-def-wikc-tat" and "es-def-wikc-sample10" are legitimate packs that have to coexist
        # with the bare one. If the grammar does not admit them, the builder cannot build them.
        for pack_id in ("es-def-wikc-tat", "es-def-wikc-ej-tat", "es-def-wikc-sample10",
                        "en-def-wikt", "es-tr-wikc"):
            codigo, salida = self._con_meta(pack_id=pack_id, sources=_FUENTE)
            self.assertEqual(0, codigo, "%s deberia ser valido:\n%s" % (pack_id, salida))

    def test_un_pack_SIN_manifiesto_de_fuentes_se_rechaza(self):
        """Without `meta.sources` there is no way to know under what terms the content can be redistributed."""
        codigo, salida = self._con_meta(pack_id="es-def-wikc", sources="")
        self.assertNotEqual(0, codigo)
        self.assertIn("sources", salida)

    def test_una_fuente_SIN_licencia_se_rechaza(self):
        """Declaring the source and staying silent about the licence is worse than declaring
        nothing: it looks complete.

        It is the check that pays for this class. The attribution is the CONDITION of using the
        data (D-031), and a pack that names Tatoeba without saying CC BY 2.0 FR does not say how to
        be used.
        """
        codigo, salida = self._con_meta(
            pack_id="es-def-wikc",
            sources="definitions\tWikcionario\thttps://es.wiktionary.org/\t\t\n")
        self.assertNotEqual(0, codigo)
        self.assertIn("licencia", salida.lower() + salida)


class PoliticaDeContenidoTest(BuilderTestCase):
    """The validator checks the ARTIFACT, not the builder.

    A miswired flag passes the source's tests --which hand it the value directly-- and leaves the
    pack with the proper nouns inside all the same. The only thing that catches it is counting rows
    in the finished pack.
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
        """The invariant 'definitions-only' brings with it (D-134).

        The policy lets proper nouns in on purpose, so the proportion ceiling that guards
        'lexical-only' does not apply. What does have to hold is what makes the policy safe:
        **that none of them can beat a common word on rank**. If somebody miswires the penalty,
        the pack comes out whole, opens with no error and returns the toponym at the top -- which
        is exactly the failure mode D-116 measured in English, 4,267 times. Without this check
        nothing would see it.
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
        """The lexical-signal exception lets 1,675 English proper nouns through (0.2 %).

        The validator cannot recompute the signal --it does not have the dump-- so it checks what
        it can see: that they be a minority. An unpruned pack has 17-22 %, so the margin is enormous
        and the check still catches the case that matters (that the pruning did not run).
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
        """A typo in the value cannot SKIP the structural check in silence.

        The check fires with `politica in ("excluded", "lexical-only")`, so `"lexical_only"`
        --underscore instead of hyphen-- falls on the same side as `"included"`: the pack passes
        whole without anybody counting a single proper noun. It is the validator's worst failure
        mode, because the pack declares itself pruned and nobody checks it.
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
        """rank is "lower is more common" (schema.sql) and the prefix has to respect it.

        It is not theoretical: in the first real pack, searching "escrit" returned `escrito|verb`
        ("Participio de escribir", rank 994) **before** `escrito|noun` (rank 988), because the
        query ordered by `rank DESC`. With 22 toy entries it is invisible: rank only breaks ties
        within one `norm`, and the toy pack has almost none. In the real pack, 375 of 7,265 norms
        have more than one entry.
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
        """The query is served entirely from the covering index (D-012). If the index and the ORDER
        BY disagree on `rank`'s direction, SQLite adds a sort: it stays correct, but it stops being
        the plan the design claims, and in a 150,000-entry pack that is paid for.
        """
        db = self.build([record("escrito", rank=1), record("casa", rank=2)])
        plan = " ".join(str(row) for row in db.execute(
            "EXPLAIN QUERY PLAN SELECT id, headword, pos FROM entry"
            " WHERE norm >= ? AND norm < ? ORDER BY norm, rank LIMIT 10", ("a", "b")))
        self.assertIn("COVERING INDEX idx_entry_norm", plan)
        self.assertNotIn("TEMP B-TREE", plan)

    def test_fts_rowid_matches_entry_id(self):
        # fts_def is contentless: the rowid is all it returns, so if it does not match entry.id the
        # free-text search points at the wrong entries.
        db = self.build(
            [record("correr", gloss="moverse rapidamente"), record("casa", gloss="edificio")]
        )
        entry_id = db.execute("SELECT id FROM entry WHERE headword='correr'").fetchone()[0]
        rows = [row[0] for row in db.execute(
            "SELECT rowid FROM fts_def WHERE fts_def MATCH ?", ('"rapidamente"',))]
        self.assertEqual([entry_id], rows)


class FailureModeTest(BuilderTestCase):
    def test_reserved_meta_keys_are_rejected(self):
        # A hand-written schema_version would be a silent way of breaking the validation the watch
        # does when opening the pack.
        metadata = dict(BASE_META)
        metadata["schema_version"] = "99"
        with self.assertRaises(ValueError):
            build.PackBuilder(self.path, metadata)

    def test_una_palabra_muy_comun_en_las_glosas_no_hace_fallar_la_comprobacion_de_fts(self):
        """The invariant is that FTS **find** the entry, not that it rank it high.

        The English pack uncovered it: the best ranked entry is "you", its gloss starts with "The
        people spoken...", and "people" appears in 890 of 47,718 definitions. The entry was there
        --position 721 of 890-- but outside the top 30, and the check was failing over a correct
        pack. Confusing indexed with ranked is a false negative that sends you hunting a bug that
        does not exist.

        The real failure mode this guards is still covered: if `fts_def.rowid` drifted from
        `entry.id` (D-011), the entry would appear at NO position.
        """
        # The best ranked entry has the LONG gloss --bm25 penalizes length-- and fifty other short
        # ones share the term. That way the correct entry falls outside the top 30, which is exactly
        # what happened with "you" and "people" in the English pack.
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
        """`PackFile.parseMetadata` parses three meta keys as numbers: a string blows up on
        OPENING, on the watch, with a NumberFormatException that does not name the key.

        It is the class of bug this repo exists in order not to have: the builder writes it, the
        validator lets it through and the error appears only on the device. It really happened
        --the first real pack was built with `data_version = "2026-09-15"` and `verify_pack.py`
        came out green-- and **that concrete cause no longer exists**: since the builder derives
        `data_version` there is no way to write it wrongly. What is pinned here is that the
        validator keeps looking, because the other two keys are still written by hand.
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
        # A half-built pack is worse than none: it would open with no error and return incomplete
        # results, with nothing to indicate it is missing entries.
        class Boom(Exception):
            pass

        # noqa of SIM117 on purpose: assertRaises is not a peer of the other context manager, it
        # asserts ABOUT it. Combining them into a single `with` would show them as equals.
        with self.assertRaises(Boom):  # noqa: SIM117
            with build.PackBuilder(self.path, dict(BASE_META)) as builder:
                builder.add(record("correr"))
                raise Boom()
        self.assertFalse(os.path.exists(self.path), "quedo un pack a medio construir")


class LogicalIdentityTest(BuilderTestCase):
    """entry.uid: the identity that survives rebuilding the pack (D-055).

    It is what makes it possible for an auxiliary pack to add information to one of this pack's
    entries. If it breaks, the auxiliary points at the wrong entry and there is no error at all:
    another word's synonyms get shown.
    """

    def _uids(self, records):
        db = self.build(records)
        filas = db.execute("SELECT headword, uid, id FROM entry")
        out = {row[0]: (row[1], row[2]) for row in filas}
        db.close()
        return out

    def test_el_uid_sobrevive_a_que_la_fuente_agregue_una_palabra_en_el_medio(self):
        # The case that motivates the whole decision: entry.id shifts, entry.uid does not.
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
        # It goes over the raw headword: bumping NORM_VERSION must not invalidate the auxiliary
        # packs. An intended side effect: "arbol" and "árbol" normalize the same and are different
        # entries.
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
        # Fusing them would be worse: any tie-break by insertion order breaks precisely the
        # stability across rebuilds the uid exists to give.
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
        # Two different packs of the same language have to give the same word the same uid: if it
        # depended on the pack_id, no composition would be possible.
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
        # Deterministic so that rebuilding an unchanged pack does not generate a new download.
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
    """`data_version` distinguishes two builds of the SAME dump.

    ⚠️ **The bug this closes**: it used to be the dump's date written by hand, so rebuilding the
    same dump with another builder --another pruning, another source added, another `rank`-- gave
    **the same number**, and `devpack.py` and the installer read it as "it is the same pack". A
    better pack never propagated.
    """

    def test_es_un_entero_de_doce_digitos_legible_como_fecha(self):
        # YYYYMMDDHHMM: a human reads it with no converter, which was half the request. The other
        # half is that it order, and a number of this shape orders just like time.
        valor = build.data_version((2026, 9, 21, 14, 32))
        self.assertEqual("202609211432", valor)
        self.assertRegex(build.data_version(), r"^20\d{10}$")

    def test_dos_builds_del_mismo_dump_dan_numeros_distintos_y_ordenados(self):
        uno = build.data_version((2026, 9, 21, 14, 32))
        dos = build.data_version((2026, 9, 21, 14, 33))
        self.assertLess(int(uno), int(dos),
                        "el mas nuevo tiene que ser el mayor: de eso vive el instalador")

    def test_entra_en_un_Long_y_NO_en_un_Int(self):
        # ⚠️ The app parses it, which is why this test exists: 202609211432 **does not fit in a
        # 32-bit Int**. If somebody turns `dataVersion` back into an Int, the pack blows up on
        # opening on the watch with a NumberFormatException that does not name the key (D-070).
        valor = int(build.data_version((2026, 9, 21, 14, 32)))
        self.assertGreater(valor, 2 ** 31 - 1)
        self.assertLess(valor, 2 ** 63 - 1)

    def test_escribirlo_a_mano_es_un_error(self):
        # If it can be written by hand, somebody will forget to bump it: that is exactly what
        # happened for months.
        with self.assertRaises(ValueError):
            build.PackBuilder(self.path, dict(BASE_META, data_version="20260915"))

    def test_la_fecha_del_dump_no_se_pierde(self):
        # What the hand-written value MEANT --which dump the content comes from-- is still useful
        # information, so it is declared separately rather than disappearing.
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
    """Whom the coverage list requires a language's words of.

    ⚠️ **The rebuild brought it, not a test.** The bilingual pack declares `langs = es,en` and
    failed on `tuesday`: `martes` carries its translation **glossed inside the sense** --*"Tuesday
    (the third day of the week...)"*-- instead of a clean term, so the English entry never came
    out. It is a real gap **and a promise that pack never made**: its English side exists for the
    reverse direction (D-196), not to be an English dictionary.

    The rule that remains: **the list requires of a pack the language it is a dictionary of**. In a
    monolingual one, every language it declares; in a bilingual one, the SOURCE one. The rest gets
    reported, because silencing it would lose the signal that found this.
    """

    def _pack(self, langs, kind, palabras_presentes):
        path = os.path.join(self.dir, "%s-%s.db" % (kind, langs.replace(",", "")))
        db = sqlite3.connect(path)
        db.execute("CREATE TABLE meta (key TEXT, value TEXT)")
        db.execute("CREATE TABLE entry (id INTEGER, norm TEXT)")
        db.execute("CREATE TABLE form (entry_id INTEGER, norm TEXT)")
        db.executemany("INSERT INTO meta VALUES (?, ?)",
                       [("langs", langs), ("kind", kind)])
        # ⚠️ With fewer entries than the list has words, the check is skipped as "it is a fixture".
        # The filler exists so the rule being tested is the language one.
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
        """⚠️ **A frequency cut cannot bring a word that has no frequency.**

        Measured over `freq-en-opensubs.txt`: `blockchain`, `deepfake` and `workaround` have
        **zero** occurrences. Requiring them of a `core` is asking the cut for something its own
        metric cannot deliver -- and the group containing them defends something else: that the
        SOURCE carries today's vocabulary (D-120), which is a property of the full pack.

        `tuesday`, by contrast, has 14,074: if it is missing from a tier, the cut is broken.
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


class VocabularioDeEtimologiaTest(BuilderTestCase):
    """The tier filter: which words carry an etymology, and which only lose that line.

    ⚠️ **It lives in the builder and not at the call sites.** Every record reaches the pack through
    `add` -- the base source's, a second source's, a bidirectional reader's -- so a filter written
    beside one of those loops would be right the day it was written and partial the next.
    """

    def build_with_vocabulary(self, records, vocabulary):
        with build.PackBuilder(self.path, dict(BASE_META),
                               etymology_vocabulary=vocabulary) as builder:
            for item in records:
                builder.add(item)
        return sqlite3.connect(self.path)

    def etymologies(self, connection):
        dictionary = bytes.fromhex(connection.execute(
            "SELECT value FROM meta WHERE key = 'payload_dict'").fetchone()[0])
        salida = {}
        for headword, blob in connection.execute("SELECT headword, payload FROM entry"):
            texto = payload_codec.decompress(blob, dictionary)
            salida[headword] = payload_codec.parse_etymology(texto)
        return salida

    def test_a_word_outside_the_vocabulary_loses_the_line_and_keeps_the_entry(self):
        # The whole point: the filter costs one line, never the entry. A `cherenga` that vanished
        # would turn a size decision into missing vocabulary.
        conexion = self.build_with_vocabulary(
            [record("casa", etymology="Del latín casa."),
             record("cherenga", etymology="De origen incierto.")],
            {normalize.norm("casa")},
        )
        self.assertEqual({"casa": "Del latín casa.", "cherenga": None},
                         self.etymologies(conexion))

    def test_with_no_vocabulary_every_word_carries_it(self):
        # `core` and `main`, where every word qualifies and the filter is not passed at all.
        conexion = self.build_with_vocabulary(
            [record("casa", etymology="Del latín casa."),
             record("cherenga", etymology="De origen incierto.")],
            None,
        )
        self.assertEqual({"casa": "Del latín casa.", "cherenga": "De origen incierto."},
                         self.etymologies(conexion))

    def test_an_empty_vocabulary_is_not_the_same_as_none(self):
        # The distinction that makes the flag safe: `set()` is "nobody carries it", `None` is
        # "everybody does". Reading a list that turned out empty must not silently mean the second.
        conexion = self.build_with_vocabulary([record("casa", etymology="Del latín casa.")], set())
        self.assertEqual({"casa": None}, self.etymologies(conexion))

    def test_the_vocabulary_is_matched_on_norm_and_not_on_the_spelling(self):
        # `entry.norm` is what the reader's query hits, so the set is in the same units. Matching
        # spellings would drop `Álava` and every accented lemma with no error.
        conexion = self.build_with_vocabulary(
            [record("Álava", etymology="Del euskera.")], {normalize.norm("Álava")})
        self.assertEqual({"Álava": "Del euskera."}, self.etymologies(conexion))


class LeerVocabularioDeEtimologiaTest(BuilderTestCase):
    """`build_pack.vocabulario_de_etimologia`: a pack gives it, or a word list does."""

    def test_a_pack_gives_its_own_norms(self):
        import build_pack

        with build.PackBuilder(self.path, dict(BASE_META)) as builder:
            builder.add(record("casa"))
            builder.add(record("Álava"))
        self.assertEqual({normalize.norm("casa"), normalize.norm("Álava")},
                         build_pack.vocabulario_de_etimologia(self.path))

    def test_a_word_list_is_normalized_and_a_pack_is_not(self):
        # ⚠️ The asymmetry is deliberate: `entry.norm` was written by this same `norm()` at this
        # same NORM_VERSION, so running it again would be a second implementation of the key. A
        # `.txt` holds raw words and has to be normalized here.
        import build_pack

        lista = os.path.join(self.tmp, "palabras.txt")
        with open(lista, "w", encoding="utf-8") as handle:
            handle.write("Casa\n  ÁLAVA  \n\n")
        self.assertEqual({normalize.norm("casa"), normalize.norm("Álava")},
                         build_pack.vocabulario_de_etimologia(lista))


class LecturaDeEtimologiaTest(BuilderTestCase):
    """`verify_pack.py`'s readout: how much origin the pack carries, and up to which vocabulary.

    ⚠️ **A readout and never a check**, like the IPA one: coverage is a property of the source, so
    demanding a number would fail a pack whose language Wiktionary covers worse and demanding zero
    would forbid the channel. What it answers is what the card cannot -- an absent origin means
    *this pack has none*, *this word has none*, or *this tier does not reach this word*.
    """

    def _verificar(self, records, metadata=None):
        with build.PackBuilder(self.path, dict(metadata or BASE_META)) as builder:
            for item in records:
                builder.add(item)
        salida = io.StringIO()
        with contextlib.redirect_stdout(salida):
            verify_pack.verify(self.path)
        return salida.getvalue()

    def test_dice_que_fraccion_de_la_muestra_trae_origen(self):
        salida = self._verificar([
            record("casa", etymology="Del latín casa."),
            record("cherenga"),
            record("perro", etymology="De origen incierto."),
            record("zarigüeya"),
        ])
        self.assertIn("2 de 4 entradas de la muestra traen etimologia (50.0 %)", salida)

    def test_un_pack_sin_origen_lo_dice_en_vez_de_callarse(self):
        # The one that matters: every pack built before the channel reads 0.0 %, and that line is
        # how a rebuild's debt gets noticed. Silence would read the same as full coverage.
        salida = self._verificar([record("casa"), record("perro")])
        self.assertIn("0 de 2 entradas de la muestra traen etimologia (0.0 %)", salida)

    def test_declara_HASTA_DONDE_lo_lleva_cuando_el_pack_lo_dice(self):
        # ⚠️ Without the reach, a `full` pack reading 19.5 % looks like a bad source instead of a
        # tier filter doing its job. The two are printed together so they cannot be read apart.
        meta = dict(BASE_META)
        meta["etymology_vocabulary"] = "en-main.db (186543 words)"
        salida = self._verificar([record("casa", etymology="Del latín casa.")], meta)
        self.assertIn("el pack la lleva hasta en-main.db (186543 words)", salida)

    def test_sin_esa_clave_no_inventa_un_alcance(self):
        # `core` and `main` carry it for all of their vocabulary and declare no reach. Printing one
        # anyway would assert a filter that was never applied.
        salida = self._verificar([record("casa", etymology="Del latín casa.")])
        self.assertIn("traen etimologia", salida)
        self.assertNotIn("la lleva hasta", salida)

    def test_la_pronunciacion_tiene_la_misma_lectura_y_hasta_hoy_nadie_la_miraba(self):
        # ⚠️ **The `I` readout shipped with no test**, and it is the one that found the last
        # rebuild's debt: 0.0 % on `es-full` against 100 % available in the dump. A readout nobody
        # asserts can stop printing in a refactor and the only symptom is a number that never
        # appears again -- which reads like a pack that was fine.
        salida = self._verificar([
            record("casa", pronunciation="\u02c8kasa"),
            record("perro"),
        ])
        self.assertIn("1 de 2 entradas de la muestra traen pronunciacion (50.0 %)", salida)
