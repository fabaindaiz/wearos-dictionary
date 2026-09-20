"""Frases de uso en español, del corpus Tatoeba.

**No es un diccionario y no define nada.** Es un corpus de oraciones sueltas, contribuidas por
gente, y lo unico que aporta es lo que al pack le falta: **uso real**. El 70,4 % del pack español
son entradas de una acepcion sin ningun ejemplo, y D-132 dejo medido que el dump del Wikcionario
ya no tiene nada mas que darles.

**Cuanto rinde, contra la otra fuente de ejemplos.** Medido sobre el mismo pack:

    enwiktionary §Spanish (D-135)      307 entradas
    Tatoeba                          7.019 entradas      23 veces mas

La diferencia no es que Tatoeba tenga mas texto: es que **un ejemplo no necesita que las dos
fuentes coincidan en como numeran las acepciones**. Solo necesita contener la palabra. Por eso
la regla de atribucion de D-132 --que mato el 94 % del rendimiento de enwiktionary-- aca no
muerde: no hay dos listas de acepciones que conciliar.

⚠️ **La ambiguedad se resuelve del lado del pack, no de aca.** Este modulo entrega
`norm(palabra) -> frase` sin saber nada del diccionario; es `build.py` el que exige que esa
`norm` lleve a **una sola** entrada (lema o forma flexionada) antes de pegar nada. Es la
condicion que importa: "vino" es lema y tambien forma de "venir", y colgarle una frase a la
equivocada es contenido incorrecto que parece correcto. Ese filtro cuesta caro --de 14.023
entradas alcanzables se baja a 7.019-- y se paga entero.

⚠️ **Licencia.** Las frases son **CC BY 2.0 FR**, no CC0. El export `sentences_CC0` existe y es
la primera cosa que se probo: trae **37 frases en español** de 562.186, asi que no sirve. Hay
atribucion obligatoria, igual que en D-135, y por eso esto tambien es una opcion y no un default.

Fuente: https://downloads.tatoeba.org/exports/per_language/spa/spa_sentences.tsv.bz2
"""

import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import normalize  # noqa: E402

# El codigo ISO 639-3 del español en Tatoeba. La columna 2 del TSV.
LANG = "spa"

# La ventana de largo, en caracteres.
#
# El piso saca las frases que no ilustran nada ("Si.", "Ya."). El techo es lo que entra en dos
# renglones a 234 dp sin empujar la definicion fuera de pantalla -- el mismo numero que
# `enwikt_examples.MAX_LARGO`, porque el renglon del reloj no cambia segun de donde salga el texto.
MIN_LARGO = 15
MAX_LARGO = 80

# Que cuenta como palabra: letras, ningun digito. `\w` traeria "25" y "covid19".
_PALABRA = re.compile(r"[^\W\d_]+", re.UNICODE)


def shortest_by_norm(path, lang=LANG):
    """Mapa `norm(palabra) -> la frase mas corta que la contiene`.

    **La mas corta y no la primera**: en un reloj el renglon es el recurso escaso y las dos son
    igual de validas como ejemplo. Ademas vuelve el resultado **independiente del orden del
    archivo**, que es lo que hace que dos builds del mismo dump den el mismo pack.

    La clave es `norm()`, el mismo que indexa el pack: cualquier otra cosa --minusculas a mano,
    quitar tildes con una tabla-- seria una segunda definicion de la igualdad de palabras, que es
    exactamente lo que el invariante central de este repo existe para impedir.
    """
    comunes = _vistas_en_minuscula(path, lang)
    out = {}
    for texto in _frases(path, lang):
        for palabra in _PALABRA.findall(texto):
            clave = normalize.norm(palabra)
            # ⚠️ El filtro que salvo a "nadal". Ver `_vistas_en_minuscula`.
            if not clave or clave not in comunes:
                continue
            previa = out.get(clave)
            # Empate por largo: gana la menor alfabeticamente, para que el mapa no dependa del
            # orden en que el archivo sirvio las frases.
            if previa is None or (len(texto), texto) < (len(previa), previa):
                out[clave] = texto
    return out


def _frases(path, lang):
    """Las frases del idioma pedido que entran en una pantalla. Una pasada, sin cargar nada."""
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            partes = line.rstrip("\n").split("\t")
            if len(partes) < 3 or partes[1] != lang:
                continue
            texto = partes[2].strip()
            if MIN_LARGO <= len(texto) <= MAX_LARGO:
                yield texto


def _vistas_en_minuscula(path, lang):
    """Las claves que el corpus escribe en minuscula **alguna vez**: las palabras comunes.

    ⚠️ **Existe por un error que ningun test veia y que aparecio leyendo el pack construido.**
    La palabra dialectal "nadal" (Navidad) habia recibido *"Alonso, Nadal y Pau Gasol, entre los
    mejor pagados del mundo."*: `norm()` baja a minusculas, asi que el apellido del tenista
    colisiona con el sustantivo comun.

    Y el filtro de ambiguedad del builder **no puede verlo**: D-116 poda los nombres propios del
    pack, asi que no existe una segunda entrada con la que "nadal" empate. La clave parece
    inequivoca precisamente porque su competidor fue podado.

    ⚠️ **El primer intento fue descartar las mayusculas a mitad de frase, y NO alcanzo**:
    *"Nadal, mejor deportista español de la historia..."* empieza con el apellido y pasaba por la
    excepcion de la primera palabra -- toda frase empieza en mayuscula, asi que esa excepcion no
    se puede quitar. Lo que separa de verdad no es una posicion sino **un hecho del corpus**: una
    palabra escrita en minuscula alguna vez es comun; una escrita siempre en mayuscula, en
    442.135 oraciones, es un nombre propio.

    Cuesta una segunda pasada sobre el archivo --40 MB, sin descomprimir-- y nada de memoria
    extra que no fuera a hacer falta igual.
    """
    vistas = set()
    for texto in _frases(path, lang):
        for palabra in _PALABRA.findall(texto):
            if palabra[:1] == palabra[:1].lower():
                clave = normalize.norm(palabra)
                if clave:
                    vistas.add(clave)
    return vistas
