"""Fuente de juguete: un pack chico y determinista para los tests.

No sale de ninguna fuente real. Existe para que los tests de :dict-data corran contra un pack
de verdad (con sus indices, su FTS y sus payloads comprimidos) sin descargar gigabytes ni
depender de la red. Los casos estan elegidos para ejercitar los cinco caminos de busqueda:

  - prefijo:          "corr" -> correr, corriente
  - forma flexionada: "corriendo" -> correr
  - traduccion:       "run" -> correr
  - tolerante:        "korer" / "coreer" -> correr
  - texto libre:      "rapidamente" aparece en la definicion de correr

Ademas incluye trampas a proposito: acentos, eszett, lemas homografos con distinto pos, y un
lema que se normaliza igual que otro.
"""

import sys

sys.path.insert(0, __import__("os").path.dirname(__import__("os").path.dirname(
    __import__("os").path.abspath(__file__))))

from build import Record  # noqa: E402

# (headword, pos, rank, [(gloss, [ejemplos], [traducciones])], [formas])
_DATA = [
    ("correr", "verb", 10, [
        ("moverse rapidamente de un lugar a otro", ["corrio hasta la esquina"], ["to run"]),
        ("dicho del tiempo: transcurrir", [], ["to pass", "to elapse"]),
    ], ["corriendo", "corri", "corre", "corremos", "corrio"]),
    ("corriente", "noun", 40, [
        ("movimiento de un fluido en una direccion", [], ["current", "stream"]),
        ("que es habitual o comun", [], ["ordinary", "common"]),
    ], ["corrientes"]),
    ("corregir", "verb", 120, [
        ("enmendar lo que esta equivocado", [], ["to correct", "to fix"]),
    ], ["corrige", "corrigiendo"]),
    ("corto", "adjective", 60, [
        ("de poca longitud o duracion", [], ["short", "brief"]),
    ], ["corta", "cortos", "cortas"]),
    ("cosa", "noun", 30, [
        ("todo lo que tiene existencia", [], ["thing"]),
    ], ["cosas"]),
    ("casa", "noun", 15, [
        ("edificio para habitar", ["la casa de la esquina"], ["house", "home"]),
    ], ["casas"]),
    ("cazar", "verb", 200, [
        ("perseguir animales para capturarlos", [], ["to hunt"]),
    ], ["caza", "cazando"]),
    # Homografos: mismo lema, distinto pos. La lista tiene que poder distinguirlos.
    ("bajo", "adjective", 80, [
        ("de poca altura", [], ["low", "short"]),
    ], []),
    ("bajo", "preposition", 85, [
        ("debajo de", [], ["under", "beneath"]),
    ], []),
    # Acentos y ene: norm los pliega, headword los conserva para mostrar.
    ("niño", "noun", 25, [
        ("persona de pocos anos", [], ["child", "boy"]),
    ], ["ninos", "niños"]),
    ("acción", "noun", 50, [
        ("ejercicio de una potencia", [], ["action"]),
        ("titulo que representa parte del capital", [], ["share", "stock"]),
    ], ["acciones"]),
    ("árbol", "noun", 45, [
        ("planta perenne de tronco lenoso", [], ["tree"]),
    ], ["arboles", "árboles"]),
    # Se normaliza igual que "arbol": ejercita que norm colisione sin perder entradas.
    ("arbol", None, 900, [
        ("variante sin tilde, registrada aparte", [], ["tree"]),
    ], []),
    ("llave", "noun", 70, [
        ("instrumento para abrir cerraduras", [], ["key"]),
    ], ["llaves"]),
    ("hola", "interjection", 20, [
        ("saludo familiar", [], ["hello", "hi"]),
    ], []),
    ("zapato", "noun", 90, [
        ("calzado que cubre el pie", [], ["shoe"]),
    ], ["zapatos"]),
    ("vaca", "noun", 100, [
        ("hembra del toro", [], ["cow"]),
    ], ["vacas"]),
    ("queso", "noun", 110, [
        ("alimento hecho de leche cuajada", [], ["cheese"]),
    ], ["quesos"]),
    ("guerra", "noun", 55, [
        ("conflicto armado entre naciones", [], ["war"]),
    ], ["guerras"]),
    ("México", "proper noun", 300, [
        ("pais de America del Norte", [], ["Mexico"]),
    ], ["Mejico"]),
    # Lema de dos palabras: norm lo deja como "de repente", con el espacio adentro.
    ("de repente", "adverb", 400, [
        ("de manera subita", [], ["suddenly", "all of a sudden"]),
    ], []),
    # Con guion: norm lo convierte en espacio, no lo borra.
    ("self-made", "adjective", 800, [
        ("prestamo del ingles: hecho por si mismo", [], ["self-made"]),
    ], []),
]

METADATA = {
    "pack_id": "toy-es-en",
    "kind": "bilingual",
    "name": "Juguete Español → English",
    "lang_src": "es",
    "lang_dst": "en",
    "fuzzy_profile": "es",
    "data_version": "1",
    "license": "CC0-1.0",
    "attribution": "Datos de prueba escritos a mano para los tests; no es un diccionario real.",
    "source_url": "https://example.invalid/toy",
}


def records():
    for headword, pos, rank, senses, forms in _DATA:
        translations = []
        rendered_senses = []
        for gloss, examples, sense_translations in senses:
            rendered_senses.append(
                {
                    "gloss": gloss,
                    "examples": list(examples),
                    "translations": list(sense_translations),
                }
            )
            translations.extend(sense_translations)
        yield Record(
            headword=headword,
            senses=rendered_senses,
            part_of_speech=pos,
            rank=rank,
            forms=forms,
            translations=translations,
        )
