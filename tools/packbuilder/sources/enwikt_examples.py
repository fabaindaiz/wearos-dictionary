"""Ejemplos de uso en español, tomados del Wiktionary INGLES, seccion Spanish.

Es la **segunda fuente** del pack español, y lo unico que se le toma son los ejemplos. Sus
glosas son traducciones al ingles: traerlas convertiria el pack en bilingue, que es otra cosa
(D-034). Aca no se define nada, se ilustra.

**Por que existe.** El 70,4 % del pack español son entradas de una acepcion sin ejemplo, y el
dump del Wikcionario español ya no tiene nada mas que darles: medido en D-132, de los campos
sin usar solo quedaba un 7 % aprovechable y ya se uso.

⚠️ **Y por que NO esta encendida por defecto** (D-135). El numero honesto es chico --**326
entradas, el 0,28 % del pack**-- y el precio no es el codigo sino la **atribucion**: usar dos
fuentes obliga a nombrar a las dos en cada pack, para siempre. Las dos son CC BY-SA 4.0, asi
que no hay incompatibilidad de licencia; hay una obligacion permanente por un 0,28 %. La
decision de pagarla es del usuario, y por eso esto es una opcion de la CLI y no un default.

**Como se llego a 326, que es la parte que no hay que redescubrir.** El roadmap estimaba 5.307
cruzando por lema. Ese numero cuenta casos donde **habria que inventar la atribucion**: entradas
nuestras con varias acepciones, donde no se sabe a cual pega el ejemplo. Los cuatro filtros de
abajo son lo que queda cuando no se inventa nada, y cada uno sale de mirar el dump:

    cruce por lema, sin filtros                     ~5.300
    + nuestra entrada tiene UNA acepcion             2.172
    + alla tambien tiene UNA, y el mismo `pos`         510
    + `english` presente (confirma que `text` es
      el español y no notacion ni metatexto)           326

Lo que cada filtro saca, con el caso que lo justifica:

    una acepcion alla    "y" tiene 5 acepciones alla y 1 aca: el ejemplo "jamon y queso"
                         puede estar ilustrando una acepcion que nuestro pack no tiene
    mismo `pos`          sin el entra "A" (noun) con el ejemplo de "A" como notacion
    `type`               191 items sin `type` son notas, no ejemplos: "Near-synonym: pedazo",
                         "Coordinate term: ovarios", un enlace a Wikipedia
    `english`            sin el entra "19. Ac4xd5, Ab7xd5" -- ajedrez de una partida citada
"""

import json

# Los dos tipos que son uso de la palabra. Medido en el dump: 3.729 `example` (redactados) y
# 1.950 `quotation` (citados de un texto publicado, con `ref`). Los dos son contenido de
# diccionario. Los 191 SIN `type` no lo son, y por eso el filtro lista en vez de excluir.
TIPOS_QUE_SON_USO = ("example", "quotation")

# Un ejemplo mas largo que esto no entra en una pantalla de reloj sin comerse la definicion.
# El numero es el ancho de dos renglones a 234 dp; los que caen son 7 de 250 (2,8 %).
MAX_LARGO = 90

# Un ejemplo alcanza para lo que esto resuelve --una entrada que se ve vacia-- y dos ya empujan
# la definicion fuera de pantalla. Mismo criterio que MAX_EXAMPLES_PER_SENSE en kaikki.py.
MAX_POR_ENTRADA = 1


def examples_by_entry(path):
    """Mapa `(headword, pos) -> [ejemplos]`, listo para que el builder lo consulte.

    La clave lleva el `pos` porque sin el "A" como sustantivo hereda el ejemplo de otra cosa.
    Cabe en memoria de sobra: son unos pocos miles de entradas, no el dump entero.
    """
    out = {}
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            if not line.strip():
                continue
            raw = json.loads(line)
            if raw.get("lang_code") != "es":
                continue
            senses = raw.get("senses") or []
            # Con mas de una acepcion no se sabe a cual pega el ejemplo. Ver el docstring.
            if len(senses) != 1:
                continue
            word, pos = raw.get("word"), raw.get("pos")
            if not word or not pos:
                continue
            buenos = []
            for item in senses[0].get("examples") or []:
                text = (item.get("text") or "").strip()
                if not text or len(text) > MAX_LARGO:
                    continue
                if item.get("type") not in TIPOS_QUE_SON_USO:
                    continue
                if not (item.get("english") or "").strip():
                    continue
                buenos.append(text)
                if len(buenos) == MAX_POR_ENTRADA:
                    break
            if buenos:
                out[(word, pos)] = buenos
    return out
