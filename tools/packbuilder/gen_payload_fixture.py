"""Generates the payload fixture the Kotlin test consumes.

    python3 gen_payload_fixture.py

It writes vectors/payload-fixture.tsv with cases compressed by Python. The Kotlin test
(PayloadCodecTest) decompresses them with java.util.zip and compares. It is, for the codec, the
equivalent of what normalization-vectors.tsv is for the search keys: without it, that both sides
use deflate "the same way" is an unverified assumption.

The fixture is regenerated only when the format changes; it is committed alongside the change.
"""

import os

import payload as payload_codec

HERE = os.path.dirname(os.path.abspath(__file__))
OUTPUT = os.path.join(HERE, "vectors", "payload-fixture.tsv")

# Sample text for the preloaded dictionary, with a dictionary's typical redundancy.
SAMPLES = [
    payload_codec.render("verb", [{"gloss": "to move quickly", "translations": ["correr"]}]),
    payload_codec.render("verb", [{"gloss": "to move slowly", "translations": ["caminar"]}]),
    payload_codec.render("noun", [{"gloss": "dicho de una persona", "translations": ["person"]}]),
    payload_codec.render("noun", [{"gloss": "dicho de una cosa", "translations": ["thing"]}]),
] * 3

CASES = [
    # (descripcion, part_of_speech, senses)
    ("entrada tipica con varias acepciones", "verb", [
        {"gloss": "moverse rapidamente de un lugar a otro",
         "examples": ["corrio hasta la esquina"],
         "translations": ["to run"]},
        {"gloss": "dicho del tiempo: transcurrir", "translations": ["to pass", "to elapse"]},
    ]),
    ("sin part of speech", None, [{"gloss": "una sola acepcion pelada"}]),
    ("sin ejemplos ni traducciones", "noun", [{"gloss": "edificio para habitar"}]),
    ("acentos y caracteres no ASCII", "noun", [
        {"gloss": "niño, árbol, acción, Straße, 日本語",
         "examples": ["el niño está en el árbol"],
         "translations": ["child"]},
    ]),
    ("muchas acepciones", "adjective", [
        {"gloss": "acepcion numero %d" % i, "translations": ["sense %d" % i]} for i in range(1, 9)
    ]),
    ("tabs y saltos en la fuente se sanean a espacios", "verb", [
        {"gloss": "con\tun tab\ny un salto", "examples": ["dos\t\tseguidos"]},
    ]),
    # Synonyms go PER SENSE (D-117). The case carries two senses with different synonyms on
    # purpose: it is the only thing that detects a parser attributing them wrongly.
    # Antonyms share the synonyms' mould but NOT the tag (D-126). The case carries them TOGETHER
    # and crossed on purpose: it is the only thing that detects a parser confusing "Y" with "A",
    # and confusing them gives not an odd result but an inverted one.
    ("sinonimos y antonimos en la misma acepcion", "adjective", [
        {"gloss": "de temperatura elevada",
         "examples": ["el agua está caliente"],
         "synonyms": ["ardiente", "tórrido"],
         "antonyms": ["frío", "gélido"]},
        {"gloss": "enojado",
         "synonyms": ["furioso"],
         "antonyms": ["calmado"]},
    ]),
    # A THIN entry with related words (D-132), which is the case they exist for: a single sense,
    # no example. It carries all THREE list tags at once -- Y, A and R -- because the only thing
    # that detects a parser confusing related with synonym is seeing them together and distinct.
    ("una entrada flaca con relacionadas", "noun", [
        {"gloss": "mamífero camélido sudamericano",
         "synonyms": ["huanaco"],
         "antonyms": [],
         "related": ["camélido", "vicuña", "llama"]},
    ]),
    ("sinonimos por acepcion", "noun", [
        {"gloss": "paga semanal que recibe un menor",
         "synonyms": ["mesada", "paga"]},
        {"gloss": "hombre dominado por su pareja",
         "examples": ["es un pollerudo"],
         "synonyms": ["pollerudo", "calzonazos"]},
    ]),
    # The example's citation, tag `C`. BOTH senses are there on purpose: one with a citation and
    # one without, because the common case --measured, 24.5 % of the English dump's examples-- is
    # the example with no source, and a reader returning an empty citation instead of none would
    # pass a fixture that only carried the cited case.
    #
    # The third sense carries a `Y` between the example and what follows: it pins that the
    # citation is written RIGHT AFTER its example and not at the end of the block.
    # The IPA is non-ASCII by construction and carries combining marks, so it also exercises what
    # the accent case does -- but through a channel the sense text never touches.
    ("pronunciacion en IPA", "noun", [{"gloss": "edificacion para vivir"}], "\u02c8ka.sa"),
    # A fifth element is the etymology. Two cases: one with both channels, because a payload that
    # carries `I` and `M` puts two word-level lines before the senses and their ORDER is part of
    # the contract; and one with etymology alone, which is what a pack whose tier grants it but
    # whose source has no pronunciation looks like.
    ("etimologia y pronunciacion", "noun", [{"gloss": "edificacion para vivir"}],
     "\u02c8ka.sa", "Del latin casa, cabana."),
    ("solo etimologia", "verb", [{"gloss": "desplazarse deprisa"}], None,
     "Del latin currere, con perdida de la geminada."),
    ("pronunciacion sin acepciones utiles", None, [{"gloss": "x"}], "\u02c8\u03b8i\u027eko"),
    ("ejemplo con cita", "noun", [
        {"gloss": "moverse rapidamente de un lugar a otro",
         "examples": [{"text": "corrio hasta la esquina", "ref": "1897, Richard Marsh"}]},
        {"gloss": "dicho del tiempo: transcurrir",
         "examples": [{"text": "sin fuente conocida"}]},
        {"gloss": "fluir un liquido",
         "examples": [{"text": "el agua corre", "ref": "1876, Mark Twain"}],
         "synonyms": ["fluir"]},
    ]),
]


def main():
    dictionary = payload_codec.build_dictionary(SAMPLES)
    lines = [
        "# Fixture del codec de payload, generado por gen_payload_fixture.py.",
        "#",
        "# Lo consume dict-core/src/test/kotlin/.../PayloadCodecTest.kt para comprobar que",
        "# java.util.zip descomprime exactamente lo que zlib comprimio, con el mismo diccionario",
        "# precargado. Sin esto, que los dos lados hagan 'deflate' es un supuesto sin verificar.",
        "#",
        "# Formato:  descripcion <TAB> hex_comprimido <TAB> texto_esperado_con_\\n_escapado",
        "# Las dos primeras lineas de datos son el diccionario precargado (DICTIONARY) y su",
        "# sha256 (DICTIONARY_SHA256), para comprobar que los dos lados lo hashean igual.",
        "",
        "DICTIONARY\t%s\t" % dictionary.hex(),
        "DICTIONARY_SHA256\t%s\t" % payload_codec.dictionary_digest(dictionary),
    ]

    for case in CASES:
        # A fourth element is the pronunciation, optional so the ten cases written before this
        # channel existed stay exactly as they are -- and so the fixture keeps covering the shape
        # of a payload that carries none, which is every pack built so far.
        description, part_of_speech, senses = case[:3]
        text = payload_codec.render(
            part_of_speech, senses, pronunciation=case[3] if len(case) > 3 else None,
            etymology=case[4] if len(case) > 4 else None,
        )
        blob = payload_codec.compress(text, dictionary)
        # A check of its own before writing: if Python cannot read what it wrote, the fixture is
        # wrong and there is no sense asking Kotlin to read it.
        assert payload_codec.decompress(blob, dictionary) == text, description
        escaped = text.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t")
        lines.append("%s\t%s\t%s" % (description, blob.hex(), escaped))

    with open(OUTPUT, "w", encoding="utf-8") as handle:
        handle.write("\n".join(lines) + "\n")

    print("%s: %d casos, diccionario de %d bytes" % (OUTPUT, len(CASES), len(dictionary)))


if __name__ == "__main__":
    main()
