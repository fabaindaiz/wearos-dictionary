"""Normalizacion de texto para construir los packs.

ESTE ARCHIVO TIENE UN ESPEJO:
    dict-core/src/main/kotlin/cl/fadiaz/dictionary/core/TextNormalizer.kt
    dict-core/src/main/kotlin/cl/fadiaz/dictionary/core/FuzzyProfile.kt

Las claves `norm` y `fuzzy` se calculan aca al construir el pack y se vuelven a calcular en
el reloj sobre lo que escribe el usuario. Si las dos implementaciones divergen aunque sea en
un caracter, la consulta deja de matchear y el sintoma es "falta esa palabra": sin error, sin
crash, sin log. Por eso cualquier cambio aca se replica en Kotlin en el mismo commit, se
agregan casos a vectors/normalization-vectors.tsv (que testea ambos lados) y se sube
NORM_VERSION.

Se usa solo str.replace() y nunca expresiones regulares: el reemplazo literal tiene semantica
identica en Python y en Kotlin (global, izquierda a derecha, sin solapamiento), mientras que
dos regex "equivalentes" son justo el tipo de cosa que diverge en silencio.
"""

import unicodedata

import repertoire

# Sube cuando cambia el resultado de norm() o fuzzy(). Se escribe en meta.norm_version.
#
# 1 -> 2: la clasificacion de code points paso de unicodedata.category a repertoire.py.
NORM_VERSION = 2

# Letras que NFD no descompone y que igual queremos plegar, para que "Straße" y "strasse"
# caigan en la misma clave.
EXPANSIONS = {
    "ß": "ss",
    "æ": "ae",
    "œ": "oe",
    "ø": "o",
    "đ": "d",
    "ð": "d",
    "þ": "th",
    "ł": "l",
    "ı": "i",
    "ŋ": "ng",
    "ſ": "s",
    # Ligaduras latinas: NFD no las descompone (eso es NFKD, que traeria otros efectos
    # menos predecibles como "½" -> "1/2").
    "ﬁ": "fi",
    "ﬂ": "fl",
    "ﬀ": "ff",
}

# La clasificacion de code points NO sale de unicodedata: sale de repertoire.py, que trae sus
# propios datos fijados en Unicode 13.
#
# Motivo: cada plataforma trae su propia version de Unicode (este Python -> 13.0, Java 26 -> 16,
# y Android una distinta por cada release del sistema). Medido sobre el repertorio completo,
# 14.773 code points se clasificaban distinto entre Python y Java, y eso hacia que el mismo pack
# se comportara distinto en dos relojes con distinta version de Wear OS.
#
# unicodedata se sigue usando SOLO para NFD, y eso es seguro: sobre los 133.730 code points del
# repertorio fijado, la NFD de Java 26 y la de Python 3.9 dan cero diferencias.

# Reglas de plegado fonetico por idioma, en orden. El orden es parte del contrato:
# "ce" -> "se" tiene que correr antes que "c" -> "k", si no "cerrar" termina en "kerar" y
# deja de colisionar con "serrar".
FUZZY_PROFILES = {
    "generic": (),
    # Espanol: seseo (c/z/s), b/v, y/ll, h muda, u muda de que/qui/gue/gui.
    # "ch" se protege con un marcador numerico antes de borrar la h y se restaura al final.
    # Limitacion conocida: "mexico" -> "mesiko" y "mejico" -> "mejiko" no colisionan; tratar
    # la x como j romperia "examen" -> "esamen", que es el caso mas frecuente.
    "es": (
        ("ch", "8"),
        ("qu", "k"),
        ("gue", "ge"),
        ("gui", "gi"),
        ("h", ""),
        ("ll", "y"),
        ("v", "b"),
        ("z", "s"),
        ("ce", "se"),
        ("ci", "si"),
        ("c", "k"),
        ("y", "i"),
        ("x", "s"),
        ("w", "b"),
        ("8", "ch"),
    ),
    # Ingles: digrafos mudos (kn, wr, gh), ph/f, c dura y blanda, x/ks, y/i.
    "en": (
        ("ck", "k"),
        ("ph", "f"),
        ("wh", "w"),
        ("kn", "n"),
        ("wr", "r"),
        ("gh", ""),
        ("qu", "kw"),
        ("ce", "se"),
        ("ci", "si"),
        ("cy", "si"),
        ("c", "k"),
        ("x", "ks"),
        ("y", "i"),
    ),
    # Aleman: sch/s, v/f, w/v, z/ts, digrafos con h. La ss de la eszett ya la produjo norm().
    "de": (
        ("sch", "s"),
        ("ck", "k"),
        ("ph", "f"),
        ("th", "t"),
        ("dt", "t"),
        ("v", "f"),
        ("w", "v"),
        ("z", "ts"),
        ("y", "i"),
    ),
}


def norm(text):
    """Clave de indexado: minusculas, sin diacriticos, solo letras/digitos, espacios colapsados.

    El resultado se compara con collation BINARY, asi que no hace falta ICU en el reloj y el
    comportamiento es identico en todos los dispositivos.
    """
    lowered = text.lower()
    expanded = "".join(EXPANSIONS.get(ch, ch) for ch in lowered)
    decomposed = unicodedata.normalize("NFD", expanded)

    kept = []
    for ch in decomposed:
        klass = repertoire.classify(ord(ch))
        if klass == repertoire.CLASS_COMBINING_MARK:
            # Las marcas combinantes son los diacriticos que dejo NFD.
            continue
        if klass in (repertoire.CLASS_LETTER, repertoire.CLASS_DIGIT):
            kept.append(ch)
        else:
            # Puntuacion, simbolos y todo lo ajeno al repertorio fijado pasan a ser
            # separadores, no desaparecen: "self-made" debe quedar "self made".
            kept.append(" ")
    return " ".join("".join(kept).split())


def fuzzy(text, profile):
    """Clave tolerante a errores: norm() mas plegado fonetico y colapso de letras repetidas.

    Es deliberadamente agresiva porque solo se usa como ultimo recurso, cuando la busqueda por
    prefijo no dio resultados, y despues se reordena por distancia de edicion. Falsos positivos
    aca son baratos; falsos negativos no.
    """
    if profile not in FUZZY_PROFILES:
        raise ValueError("perfil fuzzy desconocido: %r" % (profile,))
    result = norm(text)
    for source, replacement in FUZZY_PROFILES[profile]:
        result = result.replace(source, replacement)
    return " ".join(_collapse_doubled_letters(result).split())


def _collapse_doubled_letters(text):
    """Colapsa letras repetidas adyacentes: "correr" -> "corer".

    Solo letras: colapsar digitos convertiria "1000" en "10", que es el motivo de que el
    repertorio separe las dos clases.
    """
    out = []
    previous = None
    for ch in text:
        if ch == previous and repertoire.classify(ord(ch)) == repertoire.CLASS_LETTER:
            continue
        out.append(ch)
        previous = ch
    return "".join(out)
