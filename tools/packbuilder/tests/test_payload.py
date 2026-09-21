"""Tests del codec de payload.

El contrato cruzado con Kotlin lo verifica PayloadCodecTest.kt sobre
vectors/payload-fixture.tsv. Aca se prueba el lado Python por si mismo.
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
        # Un tab que llegue de la fuente corromperia el formato delimitado.
        self.assertEqual("con un tab", payload.sanitize("con\tun tab"))
        self.assertEqual("dos lineas", payload.sanitize("dos\nlineas"))
        self.assertEqual("colapsa espacios", payload.sanitize("colapsa    espacios"))
        self.assertIsNone(payload.sanitize("   "))
        self.assertIsNone(payload.sanitize(""))

    def test_sense_without_gloss_is_dropped(self):
        # Una acepcion sin glosa no muestra nada y descolgaria sus ejemplos.
        text = payload.render(None, [{"gloss": "  ", "examples": ["huerfano"]}])
        self.assertEqual("", text)

    def test_examples_before_first_sense_are_ignored(self):
        _pos, senses, _palabra = payload.parse("E\tsin acepcion\nS\tla acepcion\n")
        self.assertEqual(1, len(senses))
        self.assertEqual([], senses[0]["examples"])

    def test_los_sinonimos_antes_de_la_primera_acepcion_se_ignoran(self):
        # Mismo caso que el ejemplo huerfano: un sinonimo sin acepcion abierta no tiene de que
        # colgarse, y colgarlo de la primera que venga seria atribuirlo mal.
        _pos, senses, _palabra = payload.parse("Y\tsin acepcion\nS\tla acepcion\n")
        self.assertEqual(1, len(senses))
        self.assertEqual([], senses[0]["synonyms"])

    def test_los_sinonimos_van_a_su_acepcion_y_no_a_la_siguiente(self):
        # Lo que este test protege: que el orden de los tags no mezcle acepciones. Un sinonimo
        # atribuido a la acepcion equivocada no falla ni loguea, sale como contenido correcto.
        _pos, senses, _palabra = payload.parse("S\tuna\nY\tbobo\nS\totra\nY\tlisto\n")
        self.assertEqual(["bobo"], senses[0]["synonyms"])
        self.assertEqual(["listo"], senses[1]["synonyms"])

    def test_los_antonimos_van_a_su_acepcion_y_no_a_la_siguiente(self):
        # Mismo modo de falla que los sinonimos y peor consecuencia: un antonimo mal atribuido
        # no se lee como "raro", se lee como lo contrario de otra cosa.
        _pos, senses, _palabra = payload.parse("S\tuna\nA\tfrio\nS\totra\nA\tlento\n")
        self.assertEqual(["frio"], senses[0]["antonyms"])
        self.assertEqual(["lento"], senses[1]["antonyms"])

    def test_los_antonimos_antes_de_la_primera_acepcion_se_ignoran(self):
        _pos, senses, _palabra = payload.parse("A\tsin acepcion\nS\tla acepcion\n")
        self.assertEqual(1, len(senses))
        self.assertEqual([], senses[0]["antonyms"])

    def test_sinonimos_y_antonimos_no_se_mezclan(self):
        # El tag es lo unico que los separa, y confundirlos invierte el significado.
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
        # Los tres son listas de palabras y el tag es lo unico que las separa. Una relacionada
        # leida como sinonimo afirma una equivalencia que la fuente no da: "frances" trae `galo`
        # como related, y como sinonimo seria falso.
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
        # Compatibilidad hacia adelante con un builder mas nuevo.
        pos, senses, _palabra = payload.parse("P\tnoun\nZ\tcampo futuro\nS\tuna\n")
        self.assertEqual("noun", pos)
        self.assertEqual(1, len(senses))

    def test_only_first_part_of_speech_wins(self):
        pos, _senses, _palabra = payload.parse("P\tnoun\nP\tverb\nS\tuna\n")
        self.assertEqual("noun", pos)


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
        """Documenta POR QUE existe meta.payload_dict_sha256.

        deflate no valida el diccionario precargado. Con uno equivocado del largo suficiente
        descomprime sin lanzar nada y devuelve texto corrupto. Este test fija ese
        comportamiento: si una version futura de zlib empezara a detectarlo, queremos enterarnos
        antes de sacar la verificacion por hash pensando que es redundante.
        """
        senses = [{"gloss": "moverse rapidamente", "translations": ["to run"]}]
        text = payload.render("verb", senses)
        blob = payload.compress(text, self.dictionary)
        wrong = b"un diccionario que no corresponde en nada" * 3

        try:
            result = payload.decompress(blob, wrong)
        except Exception:
            # Tambien es aceptable: pasa cuando el diccionario equivocado es mas corto y las
            # referencias quedan fuera de la ventana.
            return
        self.assertNotEqual(text, result, "con otro diccionario no deberia dar el texto correcto")

    def test_dictionary_digest_detects_every_change(self):
        # Es la unica defensa contra el caso de arriba, asi que tiene que detectar cualquier
        # diferencia, incluida una truncadura de pocos bytes.
        digest = payload.dictionary_digest(self.dictionary)
        self.assertEqual(digest, payload.dictionary_digest(self.dictionary))
        self.assertNotEqual(digest, payload.dictionary_digest(self.dictionary[:-1]))
        self.assertNotEqual(digest, payload.dictionary_digest(self.dictionary[:-10]))
        self.assertNotEqual(digest, payload.dictionary_digest(self.dictionary + b"x"))
        self.assertNotEqual(digest, payload.dictionary_digest(b""))

    def test_dictionary_respects_size_budget(self):
        # 32 KB es el maximo que usa deflate; pasarse seria desperdiciar bytes del pack.
        samples = ["S\tacepcion numero %d de relleno\nT\tfiller %d\n" % (i, i) for i in range(5000)]
        dictionary = payload.build_dictionary(samples * 2)
        self.assertLessEqual(len(dictionary), 32 * 1024)

    def test_dictionary_is_deterministic(self):
        # Dos builds del mismo input tienen que dar el mismo pack.
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
        """El fixture commiteado tiene que corresponder al codec actual.

        Si alguien cambia el formato y no regenera el fixture, el test de Kotlin sigue pasando
        contra datos viejos y la divergencia queda tapada.
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
    """El tag `W`: las traducciones que la fuente NO pudo atribuir a una acepcion.

    ⚠️ **Existe para que la opcion deshonesta deje de ser la barata.** Con un solo canal, un
    builder con dato no atribuible solo podia tirarlo o embadurnarlo por todas las acepciones --y
    embadurnar es gratis, invisible y pasa `verify_pack.py`, que es el error de D-117. Medido: el
    **37,7 %** de las traducciones del dump español no trae `sense_index`, y sobre el pack de
    muestra eso era el **34,8 % del dato tirado** (`construir` tenia 16 y mostraba 0).

    Va **antes de la primera `S`** a proposito: un lector viejo lo descarta por la guarda
    `if senses:` y muestra la entrada sin la lista, que es degradacion correcta. Por eso
    **no sube `CODEC_ID`** (D-119).
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
        """La posicion es una convencion de escritura, no la semantica.

        Si fuera la semantica, un `W` mal ubicado se volveria una traduccion de acepcion --que es
        exactamente la atribucion inventada que este canal existe para evitar.
        """
        _pos, senses, palabra = payload.parse("S\tuna\nW\ttarde\n")
        self.assertEqual(["tarde"], palabra)
        self.assertEqual([], senses[0]["translations"])


class ReferenciaDeTraduccionTest(unittest.TestCase):
    """Apuntar a `(pack, palabra, acepcion)` sin gastar bytes en lo que es constante.

    ⚠️ **Las tres partes viven en lugares distintos, y ese reparto ES el diseño**:

        pack      -> `meta.translations_pack`, UNA vez por pack. Es constante para todas las
                     traducciones: repetirlo por item costaria ~280 KB de una sola cadena.
        palabra   -> el valor del item. Ya estaba ahi: es el termino que se muestra.
        acepcion  -> sufijo OPCIONAL del item, porque solo existe cuando la fuente la supo.

    De ahi sale que **una traduccion sin acepcion ya es un link a la palabra** y no cuesta un
    solo byte extra: el caso comun es el barato. Y sin `translations_pack` declarado no hay a
    donde ir, asi que el termino se muestra sin pintar -- que es la regla de D-084 y lo que se
    pidio: *"mostrarse pero no ser linkeables a menos que tengan algo que mostrar"*.
    """

    def test_un_termino_pelado_es_la_palabra_sin_acepcion(self):
        self.assertEqual(("house", None), payload.split_ref("house"))

    def test_un_termino_con_sufijo_nombra_una_acepcion(self):
        texto = payload.render(None, [{"gloss": "g",
                                       "translations": [payload.make_ref("house", "a1b2c3")]}])
        self.assertEqual(("house", "a1b2c3"),
                         payload.split_ref(payload.parse(texto)[1][0]["translations"][0]))

    def test_el_separador_no_puede_venir_del_dato(self):
        """Si la fuente pudiera meterlo, podria FORJAR una referencia a otra acepcion.

        Este test **fallo de verdad** contra la primera version, que juntaba en una cadena y
        dejaba que `render` adivinara: `ho\x1fuse` se leia como `ho` apuntando a `use`.
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
    """El codigo que nombra una acepcion sin nombrar un pack.

    Pedido: *«en lugar de mostrar un pack, mostrar un idioma, la palabra, y que el link a la
    acepcion sea inequivoco y unico para esa palabra, idioma y pack (core y completo aqui pueden
    repetir este codigo). Asi si no esta la acepcion exacta pero si la palabra, se puede
    referenciar a esta.»*

    ⚠️ **`entry.uid` ya lleva el idioma adentro** --`stable_uid(lang, headword, pos, sense_key)`--
    asi que el codigo sale de combinarlo con la glosa y **no nombra ningun pack**. Verificado
    sobre los packs reales: los **21.534** codigos del nucleo español son **identicos** en el
    completo, porque `build_core.py` **copia** el uid en vez de recalcularlo.

    Colisiones medidas sobre el pack español entero: **22 de 210.249 (0,0105 %)**, y son glosas
    que el wiki define dos veces, asi que apuntan a dos acepciones de texto identico.
    """

    def test_el_codigo_no_depende_del_pack(self):
        """Mismo uid y misma glosa dan el mismo codigo. Eso es lo que hace que nucleo y completo
        lo compartan sin coordinarse."""
        self.assertEqual(payload.sense_code(123, "Edificación destinada a vivienda."),
                         payload.sense_code(123, "Edificación destinada a vivienda."))

    def test_dos_acepciones_de_la_misma_palabra_dan_codigos_distintos(self):
        self.assertNotEqual(payload.sense_code(123, "asiento para varias personas"),
                            payload.sense_code(123, "entidad financiera"))

    def test_la_misma_glosa_en_otra_palabra_da_otro_codigo(self):
        """Sin el uid, dos entradas con la misma definicion corta --y las hay a miles-- serian
        la misma acepcion."""
        self.assertNotEqual(payload.sense_code(123, "Apellido."),
                            payload.sense_code(456, "Apellido."))

    def test_no_depende_de_NORM_VERSION(self):
        """⚠️ El precedente de D-055, y aca muerde mas fuerte.

        Si el codigo pasara por `norm()`, un bump de `NORM_VERSION` --que D-005 permite en
        cualquier momento-- cambiaria TODOS los codigos y dejaria apuntando a la nada cada enlace
        de cada pack ya construido, sin error y sin log. Se calcula sobre la glosa cruda.
        """
        import normalize
        glosa = "Un  ASIENTO  largo"
        self.assertNotEqual(payload.sense_code(7, glosa),
                            payload.sense_code(7, normalize.norm(glosa)),
                            "si estos coinciden es que el codigo esta pasando por norm()")

    def test_NFC_para_que_la_misma_glosa_no_de_dos_codigos(self):
        """Dos fuentes pueden entregar "á" precompuesta o descompuesta para la misma glosa."""
        import unicodedata
        g = "Sección"
        self.assertEqual(payload.sense_code(7, unicodedata.normalize("NFC", g)),
                         payload.sense_code(7, unicodedata.normalize("NFD", g)))

    def test_es_estable_y_esta_fijado_por_un_vector(self):
        """⚠️ Tiene ESPEJO en Kotlin: si este numero cambia, los enlaces de todos los packs ya
        construidos apuntan a la nada. Se fija aqui para que cambiarlo sea un acto deliberado."""
        self.assertEqual("8ec316909e48", payload.sense_code(1, "casa"))


class AcepcionDireccionableTest(unittest.TestCase):
    """**Toda acepcion tiene que ser alcanzable por `(idioma, palabra, acepcion)`, sin excepciones.**

    Pedido literal del usuario. Es una propiedad del PACK, no del hash: el codigo sale de
    `(uid, glosa)`, asi que **dos acepciones de la misma entrada con la glosa identica comparten
    codigo** y una de las dos queda inalcanzable.

    Medido sobre los seis packs reales antes de arreglarlo: **350 acepciones** de 1,7 millones
    caian en ese caso -- 14 en el español, 106 en el ingles, 211 en el bilingue, 0 en el nucleo
    español. Todas son glosas que la fuente escribe dos veces (`y` → *and* cinco veces).

    ⚠️ **Se FUSIONAN y no se descartan, y eso lo decidio una medicion**: de 12 grupos duplicados
    inspeccionados, **5 traian adjuntos distintos** -- `them` repite *"Used as the direct object
    of a verb"* con **ejemplos diferentes**. Descartar la copia habria perdido ese dato en
    silencio.
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
        """`them` repite la glosa con ejemplos distintos: descartar perderia uno."""
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

    def test_la_fusion_conserva_el_ORDEN_de_la_primera(self):
        """La primera acepcion es la que la fuente puso primero, y el orden es informacion."""
        texto = payload.render(None, [
            {"gloss": "primera"}, {"gloss": "segunda"}, {"gloss": "primera"},
        ])
        _pos, senses, _w = payload.parse(texto)
        self.assertEqual(["primera", "segunda"], [s["gloss"] for s in senses])

    def test_glosas_distintas_no_se_tocan(self):
        texto = payload.render(None, [{"gloss": "una"}, {"gloss": "otra"}])
        _pos, senses, _w = payload.parse(texto)
        self.assertEqual(2, len(senses))

if __name__ == "__main__":
    unittest.main()
