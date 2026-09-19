"""Genera el fixture del payload que consume el test de Kotlin.

    python3 gen_payload_fixture.py

Escribe vectors/payload-fixture.tsv con casos comprimidos por Python. El test de Kotlin
(PayloadCodecTest) los descomprime con java.util.zip y compara. Es el equivalente, para el
codec, de lo que normalization-vectors.tsv es para las claves de busqueda: sin esto, que los
dos lados usen deflate "igual" es un supuesto sin verificar.

El fixture se regenera solo cuando cambia el formato; se commitea junto al cambio.
"""

import os

import payload as payload_codec

HERE = os.path.dirname(os.path.abspath(__file__))
OUTPUT = os.path.join(HERE, "vectors", "payload-fixture.tsv")

# Texto de muestra para el diccionario precargado, con la redundancia tipica de un diccionario.
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
    # Los sinonimos van POR ACEPCION (D-112). El caso trae dos acepciones con sinonimos
    # distintos a proposito: es lo unico que detecta un parser que los atribuye mal.
    ("sinonimos por acepcion", "noun", [
        {"gloss": "paga semanal que recibe un menor",
         "synonyms": ["mesada", "paga"]},
        {"gloss": "hombre dominado por su pareja",
         "examples": ["es un pollerudo"],
         "synonyms": ["pollerudo", "calzonazos"]},
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

    for description, part_of_speech, senses in CASES:
        text = payload_codec.render(part_of_speech, senses)
        blob = payload_codec.compress(text, dictionary)
        # Comprobacion propia antes de escribir: si Python no puede leer lo que escribio, el
        # fixture esta mal y no tiene sentido pedirle a Kotlin que lo lea.
        assert payload_codec.decompress(blob, dictionary) == text, description
        escaped = text.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t")
        lines.append("%s\t%s\t%s" % (description, blob.hex(), escaped))

    with open(OUTPUT, "w", encoding="utf-8") as handle:
        handle.write("\n".join(lines) + "\n")

    print("%s: %d casos, diccionario de %d bytes" % (OUTPUT, len(CASES), len(dictionary)))


if __name__ == "__main__":
    main()
