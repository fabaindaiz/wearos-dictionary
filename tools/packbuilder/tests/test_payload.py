"""Tests of the payload codec.

The cross-language contract with Kotlin is verified by PayloadCodecTest.kt over
vectors/payload-fixture.tsv. Here the Python side is tested on its own.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import payload  # noqa: E402

FIXTURE = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "vectors",
    "payload-fixture.tsv",
)


class RenderParseTest(unittest.TestCase):
    def test_round_trip(self):
        senses = [
            {"gloss": "primera", "examples": ["ej uno"], "translations": ["first"],
             "synonyms": ["bobo", "zonzo"], "antonyms": ["listo"], "related": ["tonto"]},
            {"gloss": "segunda", "examples": [], "translations": ["second", "other"],
             "synonyms": [], "antonyms": [], "related": []},
        ]
        text = payload.render("verb", senses)
        pos, parsed, _palabra = payload.parse(text)
        self.assertEqual("verb", pos)
        self.assertEqual(senses, parsed)

    def test_sanitize_replaces_delimiters(self):
        # A tab arriving from the source would corrupt the delimited format.
        self.assertEqual("con un tab", payload.sanitize("con\tun tab"))
        self.assertEqual("dos lineas", payload.sanitize("dos\nlineas"))
        self.assertEqual("colapsa espacios", payload.sanitize("colapsa    espacios"))
        self.assertIsNone(payload.sanitize("   "))
        self.assertIsNone(payload.sanitize(""))

    def test_sense_without_gloss_is_dropped(self):
        # A sense with no gloss shows nothing and would unhook its examples.
        text = payload.render(None, [{"gloss": "  ", "examples": ["huerfano"]}])
        self.assertEqual("", text)

    def test_examples_before_first_sense_are_ignored(self):
        _pos, senses, _palabra = payload.parse("E\tsin acepcion\nS\tla acepcion\n")
        self.assertEqual(1, len(senses))
        self.assertEqual([], senses[0]["examples"])

    def test_los_sinonimos_antes_de_la_primera_acepcion_se_ignoran(self):
        # The same case as the orphaned example: a synonym with no open sense has nothing to hang
        # off, and hanging it off whichever comes first would be misattributing it.
        _pos, senses, _palabra = payload.parse("Y\tsin acepcion\nS\tla acepcion\n")
        self.assertEqual(1, len(senses))
        self.assertEqual([], senses[0]["synonyms"])

    def test_los_sinonimos_van_a_su_acepcion_y_no_a_la_siguiente(self):
        # What this test protects: that the order of the tags does not mix senses. A synonym
        # attributed to the wrong sense neither fails nor logs, it comes out as correct content.
        _pos, senses, _palabra = payload.parse("S\tuna\nY\tbobo\nS\totra\nY\tlisto\n")
        self.assertEqual(["bobo"], senses[0]["synonyms"])
        self.assertEqual(["listo"], senses[1]["synonyms"])

    def test_los_antonimos_van_a_su_acepcion_y_no_a_la_siguiente(self):
        # The same failure mode as the synonyms and a worse consequence: a misattributed antonym
        # does not read as "odd", it reads as the opposite of something else.
        _pos, senses, _palabra = payload.parse("S\tuna\nA\tfrio\nS\totra\nA\tlento\n")
        self.assertEqual(["frio"], senses[0]["antonyms"])
        self.assertEqual(["lento"], senses[1]["antonyms"])

    def test_los_antonimos_antes_de_la_primera_acepcion_se_ignoran(self):
        _pos, senses, _palabra = payload.parse("A\tsin acepcion\nS\tla acepcion\n")
        self.assertEqual(1, len(senses))
        self.assertEqual([], senses[0]["antonyms"])

    def test_sinonimos_y_antonimos_no_se_mezclan(self):
        # The tag is the only thing that separates them, and confusing them inverts the meaning.
        _pos, senses, _palabra = payload.parse("S\tcaliente\nY\tardiente\nA\tfrio\n")
        self.assertEqual(["ardiente"], senses[0]["synonyms"])
        self.assertEqual(["frio"], senses[0]["antonyms"])

    def test_render_y_parse_conservan_los_antonimos(self):
        texto = payload.render("adj", [
            {"gloss": "caliente", "synonyms": ["ardiente"], "antonyms": ["frio", "helado"]},
        ])
        _pos, senses, _palabra = payload.parse(texto)
        self.assertEqual(["frio", "helado"], senses[0]["antonyms"])

    def test_las_relacionadas_van_a_su_acepcion(self):
        _pos, senses, _palabra = payload.parse("S\tuna\nR\tprimera\nS\totra\nR\tsegunda\n")
        self.assertEqual(["primera"], senses[0]["related"])
        self.assertEqual(["segunda"], senses[1]["related"])

    def test_las_relacionadas_no_se_confunden_con_sinonimos_ni_antonimos(self):
        # All three are word lists and the tag is the only thing that separates them. A related
        # word read as a synonym asserts an equivalence the source does not give: "frances" carries
        # `galo` as related, and as a synonym it would be false.
        _pos, senses, _palabra = payload.parse("S\tcaliente\nY\tardiente\nA\tfrio\nR\tcalor\n")
        self.assertEqual(["ardiente"], senses[0]["synonyms"])
        self.assertEqual(["frio"], senses[0]["antonyms"])
        self.assertEqual(["calor"], senses[0]["related"])

    def test_render_y_parse_conservan_las_relacionadas(self):
        texto = payload.render("noun", [
            {"gloss": "silabario", "related": ["hiragana", "kanji"]},
        ])
        _pos, senses, _palabra = payload.parse(texto)
        self.assertEqual(["hiragana", "kanji"], senses[0]["related"])

    def test_una_relacionada_antes_de_la_primera_acepcion_se_ignora(self):
        _pos, senses, _palabra = payload.parse("R\thuerfana\nS\tla acepcion\n")
        self.assertEqual(1, len(senses))
        self.assertEqual([], senses[0]["related"])

    def test_unknown_tags_are_ignored(self):
        # Forward compatibility with a newer builder.
        pos, senses, _palabra = payload.parse("P\tnoun\nZ\tcampo futuro\nS\tuna\n")
        self.assertEqual("noun", pos)
        self.assertEqual(1, len(senses))

    def test_only_first_part_of_speech_wins(self):
        pos, _senses, _palabra = payload.parse("P\tnoun\nP\tverb\nS\tuna\n")
        self.assertEqual("noun", pos)


class CitationTest(unittest.TestCase):
    """The `C` tag: where the quoted example was taken from.

    The whole point of these tests is that a citation can only ever name the example it was
    written next to. Hanging it off the wrong one produces content that reads correct and is
    not -- the failure mode D-122 calls worse than a missing word.
    """

    def test_the_citation_hangs_off_its_example(self):
        _pos, senses, _word = payload.parse("S\ta gloss\nE\tan example\nC\t1897, Richard Marsh\n")
        self.assertEqual(
            [{"text": "an example", "ref": "1897, Richard Marsh"}], senses[0]["examples"]
        )

    def test_an_example_without_a_citation_stays_a_plain_string(self):
        # The canonical form: no citation means no wrapper. Every source that predates the tag
        # keeps emitting and reading plain strings, untouched.
        _pos, senses, _word = payload.parse("S\ta gloss\nE\tan example\n")
        self.assertEqual(["an example"], senses[0]["examples"])

    def test_a_citation_without_an_example_is_dropped(self):
        # Same rule as the orphan synonym: nothing to hang it on, and hanging it on whatever
        # comes next would be inventing the attribution.
        _pos, senses, _word = payload.parse("S\ta gloss\nC\t1897, Richard Marsh\n")
        self.assertEqual([], senses[0]["examples"])

    def test_a_citation_before_the_first_sense_is_dropped(self):
        _pos, senses, _word = payload.parse("C\torphan\nS\ta gloss\nE\tan example\n")
        self.assertEqual(1, len(senses))
        self.assertEqual(["an example"], senses[0]["examples"])

    def test_the_citation_does_not_cross_into_the_next_sense(self):
        _pos, senses, _word = payload.parse("S\tone\nE\te1\nC\tc1\nS\ttwo\nE\te2\nC\tc2\n")
        self.assertEqual([{"text": "e1", "ref": "c1"}], senses[0]["examples"])
        self.assertEqual([{"text": "e2", "ref": "c2"}], senses[1]["examples"])

    def test_a_citation_that_does_not_immediately_follow_its_example_is_dropped(self):
        # ⚠️ **The strict rule, and it is deliberate.** A `C` separated from its `E` by any other
        # tag is a guess about which example it belongs to, and D-179 exists precisely because a
        # guess of that shape is free, invisible and passes verification. `render` always writes
        # the pair adjacent, so the only way to reach this is a builder that got it wrong.
        _pos, senses, _word = payload.parse("S\tone\nE\te1\nY\tsyn\nC\tc1\n")
        self.assertEqual(["e1"], senses[0]["examples"])
        self.assertEqual(["syn"], senses[0]["synonyms"])

    def test_only_the_first_citation_of_an_example_counts(self):
        _pos, senses, _word = payload.parse("S\tone\nE\te1\nC\tfirst\nC\tsecond\n")
        self.assertEqual([{"text": "e1", "ref": "first"}], senses[0]["examples"])

    def test_render_writes_the_citation_right_after_its_example(self):
        text = payload.render("noun", [
            {"gloss": "a gloss",
             "examples": [{"text": "an example", "ref": "1897, Richard Marsh"}],
             "synonyms": ["other"]},
        ])
        self.assertEqual(
            "P\tnoun\nS\ta gloss\nE\tan example\nC\t1897, Richard Marsh\nY\tother\n", text
        )

    def test_an_example_dict_without_a_ref_renders_bare(self):
        text = payload.render(None, [{"gloss": "g", "examples": [{"text": "e", "ref": None}]}])
        self.assertEqual("S\tg\nE\te\n", text)

    def test_round_trip_keeps_both_shapes(self):
        senses = [
            {"gloss": "one",
             "examples": [{"text": "quoted", "ref": "1897, Richard Marsh"}],
             "translations": [], "synonyms": [], "antonyms": [], "related": []},
            {"gloss": "two", "examples": ["made up"],
             "translations": [], "synonyms": [], "antonyms": [], "related": []},
        ]
        _pos, parsed, _word = payload.parse(payload.render("noun", senses))
        self.assertEqual(senses, parsed)

    def test_the_citation_is_sanitized(self):
        # A tab reaching the pack from the source would corrupt the whole entry, and the symptom
        # would only show up on the watch.
        text = payload.render(None, [{"gloss": "g", "examples": [{"text": "e", "ref": "a\tb"}]}])
        self.assertEqual("S\tg\nE\te\nC\ta b\n", text)

    def test_an_example_whose_text_is_empty_takes_no_citation(self):
        text = payload.render(None, [{"gloss": "g", "examples": [{"text": "  ", "ref": "r"}]}])
        self.assertEqual("S\tg\n", text)

    def test_the_citation_does_not_bump_the_codec_id(self):
        # ⚠️ D-119: `C` is purely additive and an old reader ignores it, so forcing every user to
        # re-download 300 MB for a field they cannot see would throw that property away. This
        # assertion is what makes someone stop and read D-119 before changing the constant.
        self.assertEqual("deflate-v2", payload.CODEC_ID)
        self.assertEqual(2, payload.PAYLOAD_VERSION)


class CompressionTest(unittest.TestCase):
    def setUp(self):
        self.dictionary = payload.build_dictionary(
            ["S\tto move quickly\nT\tcorrer\n", "S\tto move slowly\nT\tcaminar\n"] * 4
        )

    def test_round_trip(self):
        senses = [{"gloss": "moverse rapidamente", "translations": ["to run"]}]
        text = payload.render("verb", senses)
        blob = payload.compress(text, self.dictionary)
        self.assertEqual(text, payload.decompress(blob, self.dictionary))

    def test_non_ascii_survives(self):
        text = payload.render("noun", [{"gloss": "niño, árbol, Straße, 日本語"}])
        blob = payload.compress(text, self.dictionary)
        self.assertEqual(text, payload.decompress(blob, self.dictionary))

    def test_wrong_dictionary_corrupts_silently(self):
        """Documents WHY meta.payload_dict_sha256 exists.

        deflate does not validate the preloaded dictionary. With a wrong one of sufficient length
        it decompresses without throwing anything and returns corrupt text. This test pins that
        behaviour: if a future version of zlib started detecting it, we want to find out before
        removing the hash verification on the assumption that it is redundant.
        """
        senses = [{"gloss": "moverse rapidamente", "translations": ["to run"]}]
        text = payload.render("verb", senses)
        blob = payload.compress(text, self.dictionary)
        wrong = b"un diccionario que no corresponde en nada" * 3

        try:
            result = payload.decompress(blob, wrong)
        except Exception:
            # Also acceptable: it happens when the wrong dictionary is shorter and the references
            # fall outside the window.
            return
        self.assertNotEqual(text, result, "con otro diccionario no deberia dar el texto correcto")

    def test_dictionary_digest_detects_every_change(self):
        # It is the only defence against the case above, so it has to detect any difference,
        # including a truncation of a few bytes.
        digest = payload.dictionary_digest(self.dictionary)
        self.assertEqual(digest, payload.dictionary_digest(self.dictionary))
        self.assertNotEqual(digest, payload.dictionary_digest(self.dictionary[:-1]))
        self.assertNotEqual(digest, payload.dictionary_digest(self.dictionary[:-10]))
        self.assertNotEqual(digest, payload.dictionary_digest(self.dictionary + b"x"))
        self.assertNotEqual(digest, payload.dictionary_digest(b""))

    def test_dictionary_respects_size_budget(self):
        # 32 KB is the maximum deflate uses; going over would waste the pack's bytes.
        samples = ["S\tacepcion numero %d de relleno\nT\tfiller %d\n" % (i, i) for i in range(5000)]
        dictionary = payload.build_dictionary(samples * 2)
        self.assertLessEqual(len(dictionary), 32 * 1024)

    def test_dictionary_is_deterministic(self):
        # Two builds of the same input have to give the same pack.
        samples = ["S\tuno\nT\tone\n", "S\tdos\nT\ttwo\n"] * 5
        self.assertEqual(
            payload.build_dictionary(samples), payload.build_dictionary(samples)
        )

    def test_dictionary_actually_helps(self):
        # Si el diccionario no mejorara nada, no valdria la pena el campo en meta ni la
        # complejidad del codec.
        def sample(gloss, translation):
            return payload.render("verb", [{"gloss": gloss, "translations": [translation]}])

        samples = [
            sample("dicho de una persona que se mueve", "to move"),
            sample("dicho de una persona que se queda", "to stay"),
        ] * 20
        dictionary = payload.build_dictionary(samples)
        text = payload.render(
            "verb", [{"gloss": "dicho de una persona que se cansa", "translations": ["to tire"]}]
        )
        with_dict = len(payload.compress(text, dictionary))
        without_dict = len(payload.compress(text, b""))
        self.assertLess(with_dict, without_dict, "el diccionario precargado no mejoro nada")


class FixtureTest(unittest.TestCase):
    def test_fixture_is_current(self):
        """The committed fixture has to correspond to the current codec.

        If somebody changes the format and does not regenerate the fixture, the Kotlin test goes on
        passing against old data and the divergence stays covered up.
        """
        self.assertTrue(
            os.path.exists(FIXTURE),
            "falta el fixture; generarlo con python3 gen_payload_fixture.py",
        )
        dictionary = None
        declared_digest = None
        cases = 0
        with open(FIXTURE, encoding="utf-8") as handle:
            for raw in handle:
                line = raw.rstrip("\n")
                if not line or line.startswith("#"):
                    continue
                fields = line.split("\t")
                self.assertEqual(3, len(fields), "linea mal formada: %r" % line)
                if fields[0] == "DICTIONARY":
                    dictionary = bytes.fromhex(fields[1])
                    continue
                if fields[0] == "DICTIONARY_SHA256":
                    declared_digest = fields[1]
                    continue
                self.assertIsNotNone(dictionary, "DICTIONARY debe venir primero")
                expected = (
                    fields[2].replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\")
                )
                self.assertEqual(
                    expected,
                    payload.decompress(bytes.fromhex(fields[1]), dictionary),
                    "el caso %r no descomprime a lo esperado; regenerar el fixture" % fields[0],
                )
                cases += 1
        self.assertGreater(cases, 3, "el fixture tiene sospechosamente pocos casos")
        self.assertEqual(
            payload.dictionary_digest(dictionary),
            declared_digest,
            "el sha256 publicado en el fixture no corresponde; regenerar el fixture",
        )



class TraduccionesDeNivelEntradaTest(unittest.TestCase):
    """The `W` tag: the translations the source could NOT attribute to a sense.

    ⚠️ **It exists so the dishonest option stops being the cheap one.** With a single channel, a
    builder holding unattributable data could only drop it or smear it across every sense -- and
    smearing is free, invisible and passes `verify_pack.py`, which is D-117's mistake. Measured:
    **37.7 %** of the Spanish dump's translations carry no `sense_index`, and over the sample pack
    that was **34.8 % of the data thrown away** (`construir` had 16 and showed 0).

    It goes **before the first `S`** on purpose: an old reader drops it through the `if senses:`
    guard and shows the entry without the list, which is the right degradation. That is why it
    **does not bump `CODEC_ID`** (D-119).
    """

    def test_las_de_nivel_entrada_no_se_cuelgan_de_ninguna_acepcion(self):
        pos, senses, palabra = payload.parse(
            "P\tnoun\nW\tbank\nS\tasiento para varias personas\nS\tentidad financiera\n")
        self.assertEqual("noun", pos)
        self.assertEqual(["bank"], palabra)
        self.assertEqual([], senses[0]["translations"])
        self.assertEqual([], senses[1]["translations"])

    def test_los_dos_modos_conviven_sin_mezclarse(self):
        _pos, senses, palabra = payload.parse(
            "W\tbank\nS\tasiento\nT\tbench\nS\tentidad financiera\n")
        self.assertEqual(["bank"], palabra)
        self.assertEqual(["bench"], senses[0]["translations"])
        self.assertEqual([], senses[1]["translations"])

    def test_render_las_emite_antes_de_la_primera_acepcion(self):
        texto = payload.render("noun", [{"gloss": "asiento"}], word_translations=["bank"])
        self.assertTrue(texto.index("W\tbank") < texto.index("S\tasiento"),
                        "va antes de la primera S para que un lector viejo la descarte")

    def test_un_W_despues_de_una_acepcion_igual_es_de_la_entrada(self):
        """The position is a writing convention, not the semantics.

        If it were the semantics, a misplaced `W` would become a sense translation -- which is
        exactly the invented attribution this channel exists to prevent.
        """
        _pos, senses, palabra = payload.parse("S\tuna\nW\ttarde\n")
        self.assertEqual(["tarde"], palabra)
        self.assertEqual([], senses[0]["translations"])


class ReferenciaDeTraduccionTest(unittest.TestCase):
    """Pointing at `(pack, word, sense)` without spending bytes on what is constant.

    ⚠️ **The three parts live in different places, and that split IS the design**:

        pack   -> `meta.translations_pack`, ONCE per pack. It is constant for every translation:
                  repeating it per item would cost ~280 KB of a single string.
        word   -> the item's value. It was already there: it is the term being shown.
        sense  -> an OPTIONAL suffix on the item, because it only exists when the source knew it.

    From that it follows that **a translation with no sense is already a link to the word** and
    costs not one extra byte: the common case is the cheap one. And with no `translations_to`
    declared there is nowhere to go, so the term is shown unpainted -- which is D-084's rule and
    what was asked for: *"shown but not linkable unless they have something to show"*.
    """

    def test_un_termino_pelado_es_la_palabra_sin_acepcion(self):
        self.assertEqual(("house", None), payload.split_ref("house"))

    def test_un_termino_con_sufijo_nombra_una_acepcion(self):
        texto = payload.render(None, [{"gloss": "g",
                                       "translations": [payload.make_ref("house", "a1b2c3")]}])
        self.assertEqual(("house", "a1b2c3"),
                         payload.split_ref(payload.parse(texto)[1][0]["translations"][0]))

    def test_el_separador_no_puede_venir_del_dato(self):
        """If the source could put it in, it could FORGE a reference to another sense.

        This test **really failed** against the first version, which joined into a string and let
        `render` guess: `ho\x1fuse` read as `ho` pointing at `use`.
        """
        texto = payload.render(None, [{"gloss": "g", "translations": ["ho\x1fuse"]}])
        self.assertIn("T\thouse", texto)
        _pos, senses, _palabra = payload.parse(texto)
        self.assertEqual(("house", None), payload.split_ref(senses[0]["translations"][0]))

    def test_la_referencia_sobrevive_el_ida_y_vuelta(self):
        texto = payload.render(
            "noun", [{"gloss": "asiento", "translations": [payload.make_ref("bench", "d4e5")]}],
            word_translations=[payload.make_ref("bank", "f6a7")])
        _pos, senses, palabra = payload.parse(texto)
        self.assertEqual(("bench", "d4e5"), payload.split_ref(senses[0]["translations"][0]))
        self.assertEqual(("bank", "f6a7"), payload.split_ref(palabra[0]))


class CodigoDeAcepcionTest(unittest.TestCase):
    """The code that names a sense without naming a pack.

    Asked for: *"instead of showing a pack, show a language, the word, and let the link to the
    sense be unambiguous and unique for that word, language and pack (core and full may repeat this
    code here). That way, if the exact sense is not there but the word is, it can be referenced."*

    ⚠️ **`entry.uid` already carries the language inside** --`stable_uid(lang, headword, pos,
    sense_key)`-- so the code comes from combining it with the gloss and it **names no pack**.
    Verified over the real packs: the Spanish core's **21,534** codes are **identical** in the full
    one, because `build_core.py` **copies** the uid instead of recomputing it.

    Collisions measured over the whole Spanish pack: **22 of 210,249 (0.0105 %)**, and they are
    glosses the wiki defines twice, so they point at two senses of identical text.
    """

    def test_el_codigo_no_depende_del_pack(self):
        """The same uid and the same gloss give the same code. That is what makes the core and the
        full one share it without coordinating."""
        self.assertEqual(payload.sense_code(123, "Edificación destinada a vivienda."),
                         payload.sense_code(123, "Edificación destinada a vivienda."))

    def test_dos_acepciones_de_la_misma_palabra_dan_codigos_distintos(self):
        self.assertNotEqual(payload.sense_code(123, "asiento para varias personas"),
                            payload.sense_code(123, "entidad financiera"))

    def test_la_misma_glosa_en_otra_palabra_da_otro_codigo(self):
        """Without the uid, two entries with the same short definition --and there are thousands--
        would be the same sense."""
        self.assertNotEqual(payload.sense_code(123, "Apellido."),
                            payload.sense_code(456, "Apellido."))

    def test_no_depende_de_NORM_VERSION(self):
        """⚠️ D-055's precedent, and here it bites harder.

        If the code went through `norm()`, a `NORM_VERSION` bump --which D-005 allows at any time--
        would change EVERY code and leave every link of every already built pack pointing at
        nothing, with no error and no log. It is computed over the raw gloss.
        """
        import normalize
        # ⚠️ The case has to be one where the FOLDING and `norm()` differ, and that is the accents:
        # `fold_gloss` keeps them and `norm()` strips them. With "Un  ASIENTO  largo" both give the
        # same thing, so that case proved nothing.
        glosa = "El público"
        self.assertNotEqual(payload.sense_code(7, glosa),
                            payload.sense_code(7, normalize.norm(glosa)),
                            "si estos coinciden es que el codigo esta pasando por norm()")

    def test_NFC_para_que_la_misma_glosa_no_de_dos_codigos(self):
        """Two sources can deliver "á" precomposed or decomposed for the same gloss."""
        import unicodedata
        g = "Sección"
        self.assertEqual(payload.sense_code(7, unicodedata.normalize("NFC", g)),
                         payload.sense_code(7, unicodedata.normalize("NFD", g)))

    def test_es_estable_y_esta_fijado_por_un_vector(self):
        """⚠️ It has a MIRROR in Kotlin: if this number changes, the links of every already built
        pack point at nothing. It is pinned here so changing it is a deliberate act."""
        self.assertEqual("8ec316909e48", payload.sense_code(1, "casa"))


class AcepcionDireccionableTest(unittest.TestCase):
    """**Every sense has to be reachable by `(language, word, sense)`, with no exceptions.**

    The user's literal request. It is a property of the PACK, not of the hash: the code comes from
    `(uid, gloss)`, so **two senses of the same entry with an identical gloss share a code** and one
    of the two becomes unreachable.

    Measured over the six real packs before fixing it: **350 senses** of 1.7 million fell into that
    case -- 14 in the Spanish one, 106 in the English one, 211 in the bilingual one, 0 in the
    Spanish core. All of them are glosses the source writes twice (`y` → *and* five times).

    ⚠️ **They are FUSED and not discarded, and a measurement decided that**: of 12 duplicate groups
    inspected, **5 carried different attachments** -- `them` repeats *"Used as the direct object of
    a verb"* with **different examples**. Discarding the copy would have lost that data in silence.
    """

    def test_dos_acepciones_con_la_misma_glosa_se_fusionan(self):
        texto = payload.render("noun", [
            {"gloss": "la misma", "examples": ["uno"]},
            {"gloss": "otra"},
            {"gloss": "la misma", "examples": ["dos"]},
        ])
        _pos, senses, _w = payload.parse(texto)
        self.assertEqual(["la misma", "otra"], [s["gloss"] for s in senses])

    def test_la_fusion_conserva_los_adjuntos_de_las_dos(self):
        """`them` repeats the gloss with different examples: discarding would lose one."""
        texto = payload.render(None, [
            {"gloss": "g", "examples": ["She treated them."], "synonyms": ["a"]},
            {"gloss": "g", "examples": ["Give it to them."], "synonyms": ["b"]},
        ])
        _pos, senses, _w = payload.parse(texto)
        self.assertEqual(1, len(senses))
        self.assertEqual(["She treated them.", "Give it to them."], senses[0]["examples"])
        self.assertEqual(["a", "b"], senses[0]["synonyms"])

    def test_la_fusion_no_duplica_valores_repetidos(self):
        texto = payload.render(None, [
            {"gloss": "g", "synonyms": ["a", "b"]},
            {"gloss": "g", "synonyms": ["b", "c"]},
        ])
        _pos, senses, _w = payload.parse(texto)
        self.assertEqual(["a", "b", "c"], senses[0]["synonyms"])

    def test_la_fusion_conserva_las_citas_de_las_dos(self):
        # `_LISTAS` dedupes with `==`, and an example is now a dict when it carries a citation.
        # Two senses that share a gloss and quote different works have to keep both: dropping
        # one is the silent loss the merge was written to avoid in the first place.
        texto = payload.render(None, [
            {"gloss": "g", "examples": [{"text": "uno", "ref": "1897, Richard Marsh"}]},
            {"gloss": "g", "examples": [{"text": "dos", "ref": "1876, Mark Twain"}]},
        ])
        _pos, senses, _w = payload.parse(texto)
        self.assertEqual(1, len(senses))
        self.assertEqual(
            [{"text": "uno", "ref": "1897, Richard Marsh"},
             {"text": "dos", "ref": "1876, Mark Twain"}],
            senses[0]["examples"],
        )

    def test_la_fusion_no_duplica_un_ejemplo_con_la_misma_cita(self):
        texto = payload.render(None, [
            {"gloss": "g", "examples": [{"text": "uno", "ref": "1897, Richard Marsh"}]},
            {"gloss": "g", "examples": [{"text": "uno", "ref": "1897, Richard Marsh"}]},
        ])
        _pos, senses, _w = payload.parse(texto)
        self.assertEqual([{"text": "uno", "ref": "1897, Richard Marsh"}], senses[0]["examples"])

    def test_una_lista_no_repite_el_mismo_item(self):
        """Sweeping the built packs found it, not a test.

        Measured over the real packs: **3.1 %** of the bilingual pack's entries with word
        translations repeated a term --`where` carried `donde, donde` and `do, do`, `Brazil`
        carried `carioca` twice-- and in the Spanish pack it was **88 of 407** with per-sense
        translations. On the watch that is the same word twice in a row that is already clipped.

        ⚠️ **It is deduplicated in `render` and not in each source**, for the same reason as
        `merge_duplicate_senses`: it is the only step EVERY pack goes through. Put in `kaikki` it
        would have to be repeated in `oewn`, `wikidata` and `bilingual`, and the property would be
        true only in the packs whose author remembered.
        """
        texto = payload.render("noun", [
            {"gloss": "g",
             "translations": ["donde", "donde", "do", "do"],
             "synonyms": ["a", "b", "a"],
             "antonyms": ["x", "x"],
             "related": ["r", "r"],
             "examples": ["uno", "uno"]},
        ], word_translations=["gratis", "gratis", "libre"])
        _pos, senses, palabra = payload.parse(texto)
        self.assertEqual(["donde", "do"], senses[0]["translations"])
        self.assertEqual(["a", "b"], senses[0]["synonyms"])
        self.assertEqual(["x"], senses[0]["antonyms"])
        self.assertEqual(["r"], senses[0]["related"])
        self.assertEqual(["uno"], senses[0]["examples"])
        self.assertEqual(["gratis", "libre"], palabra)

    def test_deduplicar_conserva_el_ORDEN_de_la_primera_aparicion(self):
        # The order is information: the source puts what is most used first, and with a cap of 4 or
        # 8 the order decides what gets seen.
        texto = payload.render(None, [{"gloss": "g", "synonyms": ["c", "a", "c", "b"]}])
        _pos, senses, _w = payload.parse(texto)
        self.assertEqual(["c", "a", "b"], senses[0]["synonyms"])

    def test_dos_ejemplos_distintos_no_se_pisan(self):
        # Deduplicating must not eat distinct content: whole items are compared.
        texto = payload.render(None, [{"gloss": "g", "examples": ["uno", "dos"]}])
        _pos, senses, _w = payload.parse(texto)
        self.assertEqual(["uno", "dos"], senses[0]["examples"])

    def test_la_fusion_conserva_el_ORDEN_de_la_primera(self):
        """The first sense is the one the source put first, and the order is information."""
        texto = payload.render(None, [
            {"gloss": "primera"}, {"gloss": "segunda"}, {"gloss": "primera"},
        ])
        _pos, senses, _w = payload.parse(texto)
        self.assertEqual(["primera", "segunda"], [s["gloss"] for s in senses])

    def test_glosas_distintas_no_se_tocan(self):
        texto = payload.render(None, [{"gloss": "una"}, {"gloss": "otra"}])
        _pos, senses, _w = payload.parse(texto)
        self.assertEqual(2, len(senses))


class PlegadoDeGlosaTest(unittest.TestCase):
    """The folding that makes two dictionaries recognize the same sense.

    Decided by the user with the number on the table: between Wiktionary and Wikidata it raises
    agreement from **34.40 % to 42.21 % (+1,531 senses)**. The failures it recovers are of this
    shape, and they are visible by reading:

        wiktionary: "Condición o carácter de torpe."
        wikidata  : "condición o carácter de torpe"

    ⚠️ **The folding has to be applied to the code AND to the fusion key, or the addressability
    exceptions come back**: two senses differing only by a full stop would have the same code
    without being fused, and one would be unreachable. That is why both call the same function.

    ⚠️ **It is a rule of OURS and versioned, unlike NFC which is a standard.** Changing it
    invalidates every link already written, so it is a deliberate act and a vector pins it in both
    languages.
    """

    def test_ignora_mayusculas_y_punto_final(self):
        self.assertEqual(payload.sense_code(7, "Condición o carácter de torpe."),
                         payload.sense_code(7, "condición o carácter de torpe"))

    def test_colapsa_espacios(self):
        self.assertEqual(payload.sense_code(7, "un  asiento\tlargo"),
                         payload.sense_code(7, "un asiento largo"))

    def test_el_espacio_duro_NO_se_colapsa(self):
        """⚠️ El guardrail de la trampa entre lenguajes.

        En Python `\\s` sobre `str` es **Unicode** y en Java/Kotlin es **ASCII**. Si alguno de
        los dos usara `\\s`, un espacio duro se colapsaria de un lado y del otro no, y los dos
        codigos de la misma acepcion quedarian distintos sin error y sin log. Se fija que
        **ninguno** lo colapse.
        """
        self.assertNotEqual(payload.sense_code(1, "una\u00a0casa"),
                            payload.sense_code(1, "una casa"))

    def test_NO_ignora_los_acentos(self):
        """LIGHT folding: `publico` and `público` are different words and so are the glosses."""
        self.assertNotEqual(payload.sense_code(7, "el publico"), payload.sense_code(7, "el público"))

    def test_NO_ignora_una_palabra_distinta(self):
        self.assertNotEqual(payload.sense_code(7, "asiento largo"),
                            payload.sense_code(7, "asiento corto"))

    def test_la_fusion_usa_el_MISMO_plegado_que_el_codigo(self):
        """Otherwise two senses share a code without being fused and one becomes unreachable."""
        texto = payload.render(None, [
            {"gloss": "Condición de torpe.", "examples": ["uno"]},
            {"gloss": "condición de torpe", "examples": ["dos"]},
        ])
        _pos, senses, _w = payload.parse(texto)
        self.assertEqual(1, len(senses), "quedaron dos acepciones con el mismo codigo")
        self.assertEqual(["uno", "dos"], senses[0]["examples"])

    def test_la_fusion_conserva_la_glosa_de_la_PRIMERA(self):
        """The folding decides what is the same; what is DISPLAYED is still the original text."""
        texto = payload.render(None, [{"gloss": "Condición de torpe."}, {"gloss": "condición de torpe"}])
        _pos, senses, _w = payload.parse(texto)
        self.assertEqual("Condición de torpe.", senses[0]["gloss"])

    def test_el_vector_esta_fijado_en_los_dos_lenguajes(self):
        """⚠️ If this number changes, the links of every already built pack die.

        That `"Casa."` and `"casa"` give **the same** number is the folding working, and that this
        number is the same as before the folding says that for a gloss already lowercase and with
        no trailing punctuation nothing changed -- meaning the folding only adds, it does not move
        what already worked.
        """
        self.assertEqual("8ec316909e48", payload.sense_code(1, "Casa."))
        self.assertEqual("8ec316909e48", payload.sense_code(1, "casa"))

if __name__ == "__main__":
    unittest.main()


class PartesPrincipalesTest(unittest.TestCase):
    """The channel the card shows beside the lemma."""

    def test_van_antes_de_las_acepciones(self):
        # They describe the WORD, not one of its senses, just like the loose translations.
        texto = payload.render("verb", [{"gloss": "g"}], forms=[("ger", "corriendo")])
        self.assertLess(texto.index("F\tger"), texto.index("S\tg"))

    def test_la_clave_viaja_neutra_y_la_forma_con_su_ortografia(self):
        # ⚠️ It is the whole point of this channel: `form` stores `corrais` because it is a search
        # key, and a card showing `corrais` is misspelled.
        texto = payload.render("verb", [{"gloss": "g"}], forms=[("part", "corrído")])
        self.assertIn("F\tpart:corrído\n", texto)

    def test_se_leen_de_vuelta(self):
        texto = payload.render("adj", [{"gloss": "g"}],
                               forms=[("pl", "altos"), ("fem", "alta")])
        self.assertEqual([("pl", "altos"), ("fem", "alta")], payload.parse_forms(texto))

    def test_una_linea_sin_separador_se_ignora_y_no_tira_la_entrada(self):
        # Breaking over a malformed form would lose the whole entry, which is far worse.
        self.assertEqual([("pl", "casas")],
                         payload.parse_forms("F\tsinsep\nF\tpl:casas\nS\tg\n"))

    def test_un_pack_sin_formas_no_emite_nada(self):
        # The degradation: a pack built before this channel simply does not carry it, and the card
        # does not show the section. No exception and no gap.
        self.assertEqual("", "".join(
            l for l in payload.render("noun", [{"gloss": "g"}]).splitlines(True)
            if l.startswith("F\t")))
        self.assertEqual([], payload.parse_forms("P\tnoun\nS\tg\n"))

    def test_un_tab_en_la_forma_no_parte_la_linea(self):
        # The same sanitizing as the rest of the payload: a stray tab would corrupt the entry.
        # `sanitize` turns it into a space rather than deleting it, which is what it does with the
        # rest of the payload: what matters is that the line does NOT split and the form survives.
        texto = payload.render("noun", [{"gloss": "g"}], forms=[("pl", "ca\tsas")])
        self.assertEqual([("pl", "ca sas")], payload.parse_forms(texto))
        self.assertEqual(1, texto.count("F\t"))


class PronunciacionTest(unittest.TestCase):
    """The `I` channel: the word's IPA, one per entry.

    Its own class and not inside `PartesPrincipalesTest`: a pronunciation is not a principal part,
    and a test filtered by class name is how a suite quietly runs none of what you meant --
    measured in this repo on 2026-09-24, in another file.
    """

    def test_la_pronunciacion_viaja_y_vuelve(self):
        texto = payload.render("noun", [{"gloss": "g"}], pronunciation="\u02c8ka.sa")
        self.assertIn("I\t\u02c8ka.sa\n", texto)
        self.assertEqual("\u02c8ka.sa", payload.parse_pronunciation(texto))

    def test_la_pronunciacion_va_antes_de_las_acepciones(self):
        # It describes the WORD, like `W` and `F`. If it landed after an `S`, a reader that hangs
        # items off the last sense would be one edit away from attributing it to that sense --
        # which is the invented attribution `W` exists to prevent.
        texto = payload.render("noun", [{"gloss": "g"}], pronunciation="\u02c8ka.sa")
        self.assertLess(texto.index("I\t"), texto.index("S\t"))

    def test_un_pack_sin_pronunciacion_no_emite_nada(self):
        # The degradation, and it is the reason this tag does not bump the codec id: a pack built
        # before the channel carries no `I`, and both sides simply have nothing to show.
        texto = payload.render("noun", [{"gloss": "g"}])
        self.assertNotIn("I\t", texto)
        self.assertIsNone(payload.parse_pronunciation(texto))

    def test_un_tab_en_la_pronunciacion_no_parte_la_linea(self):
        # Same sanitizing as every other value: a stray tab would split the record.
        texto = payload.render("noun", [{"gloss": "g"}], pronunciation="\u02c8ka\tsa")
        self.assertEqual("\u02c8ka sa", payload.parse_pronunciation(texto))
        self.assertEqual(1, texto.count("I\t"))
