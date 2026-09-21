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


def frequencies(path, lang=LANG):
    """Mapa `norm(palabra) -> cuantas frases la usan`. La senal de **uso** de un pack nucleo.

    ⚠️ **Existe porque `rank` no sirve para elegir vocabulario, y eso esta medido.** `rank` es
    riqueza de pagina del diccionario, no frecuencia de habla. Un nucleo de 14.388 entradas
    elegido por `rank` se lleva **1.366.667 de las 1.499.895 formas flexionadas** del pack
    espanol --el 91 % de la tabla, que es la mas grande del archivo-- porque las paginas mas ricas
    son los verbos y un verbo espanol tiene 33 formas. Elegido por uso, no.

    ⚠️ **Cuenta sobre TODAS las frases del idioma, no sobre la ventana de [shortest_by_norm].**
    Ese filtro de largo existe porque un ejemplo tiene que entrar en dos renglones de reloj;
    aplicarlo aca sesgaria la cuenta hacia las frases de largo medio, que no tienen por que usar
    las palabras mas comunes.

    ⚠️ **Y descarta lo que el corpus nunca escribe en minuscula**, por el mismo hecho que salvo a
    "nadal" (ver [_vistas_en_minuscula]) y con un motivo medido propio: **"Tom" aparece en 36.694
    de las 442.135 frases espanolas, el 8,3 %**. Sin el filtro seria una de las palabras mas
    frecuentes del idioma y entraria al nucleo antes que "agua".

    Cuenta **frases que la contienen** y no apariciones: una frase que repite una palabra no la
    hace mas comun, la hace mas enfatica.
    """
    # ⚠️ **Sobre TODAS las frases tambien para este filtro, y no sobre la ventana de ejemplos.**
    # "una palabra escrita en minuscula alguna vez" es un hecho del CORPUS; restringir la
    # evidencia a las frases de 15 a 80 caracteres lo vuelve menos cierto, y con un corpus chico
    # --el ingles tiene 41.512 frases contra 442.135 del espanol-- deja fuera palabras comunes de
    # verdad. `shortest_by_norm` se queda con la ventana porque su trabajo SI es elegir ejemplos.
    comunes = _mayormente_en_minuscula(path, lang)
    out = {}
    for texto in _todas_las_frases(path, lang):
        for clave in {normalize.norm(p) for p in _PALABRA.findall(texto)}:
            if not clave or clave not in comunes:
                continue
            out[clave] = out.get(clave, 0) + 1
    return out


# Que proporcion de sus apariciones tiene que ir en minuscula para contar como palabra comun.
#
# ⚠️ **Medido, y la separacion es enorme**: `tom` 0,0 % (1 de 36.749), `maria` 0,3 %, `juan` y
# `john` 0,0 %, contra `agua` 99,4 %, `water` 98,1 % y `enero` 89,2 %. Cualquier numero entre 5 y
# 85 separa igual de bien; 50 % se lee como "el corpus la escribe en minuscula mas veces que no".
UMBRAL_MINUSCULA = 0.5


def _mayormente_en_minuscula(path, lang):
    """Las claves que el corpus escribe en minuscula **la mayoria de las veces**.

    ⚠️ **"Alguna vez" no alcanza para elegir vocabulario, y eso se midio.** Es la regla que usa
    [_vistas_en_minuscula] y sirve para lo suyo --elegir un ejemplo-- pero en 442.135 frases casi
    cualquier palabra aparece en minuscula una vez, y **"tom" pasaba con 1 de 36.749 apariciones**,
    quedando en el puesto 11 de las palabras mas frecuentes del espanol.

    El costo de equivocarse es distinto en cada caso y por eso son dos reglas: un ejemplo mal
    elegido es una frase rara en una ficha; un vocabulario mal elegido es un pack nucleo lleno de
    nombres propios.

    ⚠️ **Y tiene una consecuencia conocida en ingles**: los dias y los meses se escriben siempre
    en mayuscula --`monday` 0,0 %-- asi que quedan fuera del nucleo. En espanol no pasa, porque
    `enero` va en minuscula el 89,2 % de las veces. El pack completo los tiene igual.
    """
    minusculas, total = {}, {}
    for texto in _todas_las_frases(path, lang):
        for palabra in _PALABRA.findall(texto):
            clave = normalize.norm(palabra)
            if not clave:
                continue
            total[clave] = total.get(clave, 0) + 1
            if palabra[:1] == palabra[:1].lower():
                minusculas[clave] = minusculas.get(clave, 0) + 1
    return {c for c, n in total.items() if minusculas.get(c, 0) / n >= UMBRAL_MINUSCULA}


def _todas_las_frases(path, lang):
    """Todas las frases del idioma, sin la ventana de largo. Una pasada, sin cargar nada."""
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            partes = line.rstrip("\n").split("\t")
            if len(partes) < 3 or partes[1] != lang:
                continue
            texto = partes[2].strip()
            if texto:
                yield texto


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


def _vistas_en_minuscula(path, lang, frases=None):
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
    for texto in (frases or _frases)(path, lang):
        for palabra in _PALABRA.findall(texto):
            if palabra[:1] == palabra[:1].lower():
                clave = normalize.norm(palabra)
                if clave:
                    vistas.add(clave)
    return vistas
