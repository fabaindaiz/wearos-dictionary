"""Frecuencia de uso: el prior con el que se ordenan los resultados.

## Que problema cierra, medido

`kaikki._rank` calculaba el prior como **riqueza de pagina del dump** --acepciones, ejemplos,
formas flexionadas, traducciones, etimologia-- y eso premia verbos: un verbo español trae hasta 222
formas y el perfil `es` las cuenta hasta un tope de 80, que es **el termino dominante de los 1000
puntos**. Medido sobre el pack español real, la correlacion de Spearman entre `rank` y la
frecuencia real de uso era **-0,250**, donde se esperaria -1.

⚠️ **El sintoma no estaba donde parecia.** `coverageBand` (D-142) ya defiende el peldaño de
prefijo sin confiar en el pack, asi que ahi el daño estaba acotado. Los peldaños `INFLECTED_FORM`
y `TRANSLATION` **no tienen esa defensa** --`orderFor` aplica la banda solo a `PREFIX`-- y
ordenaban por `rank` puro:

    house  ->  solar, alojar, albergar, domiciliar    <- «casa» no aparecia
    water  ->  gastar, regar, resbalar                <- «agua» no aparecia
    book   ->  reservar, fichar, multar               <- «libro» no aparecia

## Por que subtitulos

La literatura de SUBTLEX es consistente en mas de seis idiomas: **las frecuencias de subtitulos
predicen el reconocimiento de palabras mejor que las de corpus de libros**, porque se parecen al
habla. Es exactamente el registro de alguien buscando una palabra en un reloj.

## Por que Zipf y no la cuenta cruda

La distribucion es de ley de potencias: `de` aparece 14.459.520 veces y la palabra numero 50.000
aparece 185. Sin logaritmo, la primera aplasta a todas y el resto del vocabulario queda
indistinguible. Zipf es `log10(frecuencia por mil millones)`, la normalizacion estandar --la misma
escala que usa `wordfreq`-- y tiene la propiedad que hace falta: **tres ordenes de magnitud son
tres puntos**, no un factor mil.

⚠️ **Se usa la idea, no el paquete**: `wordfreq` es una dependencia pip y `tools/CLAUDE.md` fija
*stdlib only* como propiedad deliberada.

## Por que la principal gana en vez de promediar

Las dos fuentes miden cosas distintas: OpenSubtitles cuenta **ocurrencias** y
`tatoeba.frequencies` cuenta **frases que contienen la palabra**. Promediarlas seria calibrar una
escala contra otra, que es una decision que nadie midio. Cada lema toma su valor de **una** fuente.
"""

import math
import unicodedata


def key(word):
    """La clave con la que se busca una frecuencia: minusculas en NFC, **con el acento**.

    ⚠️ **NO se usa `norm()`, y eso lo decidio un defecto visto en el pack construido.** `norm()`
    pliega acentos, y en español el acento **distingue palabras**: con la clave normalizada, una
    palabra oscura hereda la frecuencia de su homografo comun.

        háber  (una unidad oscura)  ->  rank  97   <- se llevaba la de `haber`, el verbo
        hábil                       ->  rank 237       (229.602 ocurrencias, puesto 210)
        líbero                      ->  rank 240   <- sumaba `libero` + `liberó`
        liberal                     ->  rank 248

    Lo que se pierde es cobertura --una palabra escrita con otro acento en la lista no matchea--
    y lo que se gana es que la señal no mienta. Una palabra sin entrada propia cae a la banda sin
    señal, que es la respuesta correcta: nadie midio su frecuencia.
    """
    return unicodedata.normalize("NFC", word).strip().lower()


def load(path):
    """`{key(palabra): cuenta}` de una lista `palabra<espacio>cuenta`. Ver [key].

    Una linea que no se entiende **se ignora en vez de romper**: son 50.000 lineas bajadas de
    internet, y una mala no puede tirar un build de una hora.
    """
    salida = {}
    with open(path, encoding="utf-8") as handle:
        for linea in handle:
            partes = linea.split()
            if len(partes) != 2:
                continue
            palabra, cuenta = partes
            if not cuenta.isdigit():
                continue
            clave = key(palabra)
            if clave:
                salida[clave] = salida.get(clave, 0) + int(cuenta)
    return salida


def to_zipf(counts):
    """`{norm: zipf}` donde Zipf 3 == una vez por millon, 6 == una vez por mil.

    Es la escala de `wordfreq`: `log10(frecuencia por mil millones)`. Lo que importa acá es que sea
    **logaritmica**, porque la distribucion no lo es.
    """
    total = sum(counts.values())
    if not total:
        return {}
    return {k: math.log10(v / total * 1e9) for k, v in counts.items()}


def combined(principal, relleno):
    """La principal manda; `relleno` aporta solo lo que aquella no tiene. Ver el docstring."""
    salida = dict(relleno or {})
    salida.update(principal)
    return salida
