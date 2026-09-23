"""Fuente: un dump de kaikki.org (wiktextract), en cualquier idioma.

Entrega `Record` en streaming: los JSONL van de 1,4 GB (español) a 3,2 GB (ingles) y no entran
en memoria (D-015).

**La logica de poda es la misma para todos los idiomas.** Se apoya en los tags estructurales que
wiktextract emite --`form-of`, `form_of`, `glosses`, `forms`-- que son los mismos en todos los
dumps y estan en ingles. No hay una sola heuristica que compare contra texto en español.

Lo que SI cambia por idioma son las constantes de calibracion del `rank`, y por eso viven en un
`Perfil`: se midieron sobre un dump concreto y no se heredan. Ver `PERFILES`.

**La poda es el trabajo**, no un detalle. Tres decisiones la gobiernan, y las tres salen de
medir el dump, no de suponer:

1. **El 82,33 % de los registros son paginas de forma flexionada** (703.506 de 854.460):
   "amigo" como presente de "amigar". Se reconocen porque **todas** sus acepciones llevan el tag
   `form-of`. No son entradas de diccionario —mostrarlas seria el mismo resultado repetido cuatro
   veces— pero **si hay que recolectarlas**, y por eso esta fuente hace **dos pasadas**.

   (Hay otras que son forma flexionada y **no** llevan el tag: "Participio de escribir". Esas se
   conservan como entrada a proposito, y la razon esta medida en D-066.)

   Parecia que no hacia falta: el lema trae su conjugacion en `forms` (`amigar` trae 137 formas,
   `amigo` entre ellas). **Medido, no alcanza**: el `forms` de los lemas cubre el 92,31 % de las
   palabras-forma, y el **7,66 % restante —53.708 palabras— se perderia**. No son casos raros:
   "palpitaciones", "curvilinea", "animalito". Cada una es una busqueda que no encuentra nada,
   sin error y sin log. La pasada 1 invierte las paginas form-of en un mapa lema -> formas; la
   pasada 2 emite las entradas con las dos fuentes de formas unidas.
2. **El pack es monolingue** (D-034): `translations` no se emite. En monolingue la tabla `trans`
   duplica lo que `fts_def` ya indexa mejor.
3. **Etimologia, pronunciacion, categorias, silabeo y las relaciones lexicas se descartan.** No
   se muestran en un reloj y son la mayor parte del peso del dump.
4. **Los nombres propios no entran** (D-116). No es que pesen --en español son 0,63 MB de
   payload-- es que **diluyen**: 26.265 entradas cuya definicion completa es "Apellido.", y en
   ingles 4.267 casos donde el toponimo le gana en rank a la palabra comun.

   **Con una excepcion, y hubo que medirla para encontrarla**: podar por `pos = "name"` a secas
   se llevaba puesto "January", porque los meses en ingles son nombres propios. Seis de los doce
   desaparecian (los otros seis sobreviven por ser tambien verbo, modal o adjetivo: "march",
   "may", "august"). Lo que salva al mes y no a la aldea es `SENAL_LEXICA_MINIMA`. Ver ahi.

Los registros del mismo `word` vienen contiguos (medido: 0 bloques no contiguos en 400.000
registros), asi que los homografos se detectan con un buffer local en vez de un mapa global.
Si la fuente algun dia dejara de agruparlos, el que avisa es el chequeo de identidad de
`build.py`, que falla ruidosamente en vez de fundir dos entradas.
"""

import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from build import Record  # noqa: E402
from sources import frequency as _frequency  # noqa: E402

# Cuantos ejemplos de uso se guardan por acepcion.
#
# Los ejemplos entran al payload y ademas a fts_def, asi que pagan dos veces. Uno alcanza para
# desambiguar una acepcion en una pantalla de reloj; el segundo ya no se ve sin scrollear.
MAX_EXAMPLES_PER_SENSE = 1

# Cuantos sinonimos se guardan por acepcion. Cuatro entran en una pantalla de reloj en una sola
# linea; el quinto ya obliga a scrollear para leer algo que es una ayuda, no la definicion.
MAX_SYNONYMS_PER_SENSE = 4

# Tope de traducciones por acepcion. Mas alto que el de sinonimos porque **es la respuesta y no
# un complemento**: quien abre una entrada buscando la traduccion quiere verla, no cuatro de
# ellas. Medido sobre el dump español, la mediana es 1 y el p90 es 2, asi que este tope casi
# nunca muerde -- existe para el maximo de 17.
MAX_TRADUCCIONES_POR_ACEPCION = 8

# Los tres campos de los que salen las palabras relacionadas, **en este orden**, que es el de
# cuanto dicen: un hiperonimo ubica la palabra ("guanaco -> camelido"), un hiponimo da un caso, y
# `related` es una bolsa de parientes morfologicos ("frances -> galo, francofilo"). Con tope 4 el
# orden decide que se ve.
CAMPOS_RELACIONADAS = ("hypernyms", "hyponyms", "related")

# LA FUENTE DECIDE DE DONDE SALEN LOS SINONIMOS, NO UNA LISTA DE IDIOMAS.
#
# Habia una lista --`IDIOMAS_CON_SINONIMOS = {"es"}`-- y estaba justificada por una medicion
# incompleta: se miro solo la forma de ARRIBA, `raw["synonyms"]`, donde el ingles trae 0 items
# con `sense_index` y por lo tanto nada atribuible. Pero el ingles sirve los suyos en otra
# forma, **anidados dentro de cada acepcion**, donde la atribucion es estructural y no hace
# falta declararla. Medido sobre 120.000 registros vivos de cada dump:
#
#     forma                          español   ingles
#     `synonyms` arriba               16,5 %    5,6 %
#     `synonyms` dentro de `senses`    0,0 %   25,8 %
#     las dos a la vez                 0,0 %    0,0 %
#
# Cada dump usa **una sola forma, y no la misma**, asi que no hay precedencia que decidir. Y la
# lista sobra: `_by_sense_index` ya descarta lo que no trae `sense_index`, asi que la forma
# de arriba del ingles se cae sola. Un default que se sostiene por lo que la fuente trae es mas
# dificil de dejar desactualizado que uno que se sostiene por una constante.

# Umbral de la excepcion a la poda de nombres propios (D-116).
#
# `pos = "name"` mete en la misma bolsa a "January" y a "Ivanivka", y la primera es vocabulario
# mientras la segunda es una aldea de Cherkasy. Lo que las separa **sin mirar el texto** es
# cuanta vida lexica tiene la palabra: traducciones a otros idiomas, descendientes y derivados.
#
# Medido sobre los dumps: January 69, February 50, Paris 172, Moscow 330, España 77, Chile 73 --
# contra **0** de Hopewell (apellido) e Ivanivka (aldea). Con el umbral en 5 se conservan 1.675
# entradas en ingles (1,0 % de los nombres propios) y 731 en español (2,3 %).
#
# Sigue siendo estructural: son campos que wiktextract emite igual en todos los dumps, no una
# heuristica contra texto en un idioma. Ver el punto 4 del docstring del modulo.
SENAL_LEXICA_MINIMA = 5

# Las tres politicas de nombres propios, y el orden es de mas podadora a menos.
#
#   "lexical-only"      el default (D-116): entra el que tiene vida lexica --traducciones,
#                       derivados, descendientes-- por encima de SENAL_LEXICA_MINIMA. Salva
#                       "January" y tira "Ivanivka".
#   "definitions-only"  entra el que DEFINE: se poda solo el que la categorizacion del wiki
#                       marca como registro de nombres. Rescata 3.235 entradas en español
#                       --ciudades, generos taxonomicos, grafias anticuadas-- por ~0,5 MB.
#   "included"          entran todos. Existe para MEDIR, no para publicar.
#
# El valor se escribe en `meta.proper_nouns` y `verify_pack.py` lo verifica contra el contenido.
POLITICAS_DE_NOMBRES = ("lexical-only", "definitions-only", "included")

# ⚠️ **El default es `included`: ninguna fuente pierde palabras** (D-141).
#
# Pedido asi, y el argumento es bueno: *"quiero que vayan completas antes que tener que decidir
# que eliminar y que no y hacerlo erroneamente"*. Podar es tomar una decision de contenido sobre
# datos ajenos, y equivocarse en esa decision **no deja rastro**: la palabra simplemente no esta.
#
# **Lo que vuelve seguro este default no es la esperanza de que no molesten, es
# `CASTIGO_NOMBRE_PROPIO`.** D-116 midio el problema real --4.267 casos en ingles donde el
# toponimo le gana en rank a la palabra comun-- y ese problema es de ORDEN, no de presencia. Con
# el castigo entran sin desplazar nada. Las dos politicas podadoras siguen existiendo y se piden
# por nombre; son las que produjeron los numeros de D-116 y D-134.
POLITICA_POR_DEFECTO = "included"

# Lo que se le suma al rank de un nombre propio que entro por una politica permisiva.
#
# **No borrar, bajar de prioridad** -- pedido asi. Sin esto la politica empeora la busqueda en
# lugar de mejorarla: D-116 midio en ingles **4.267 casos donde el toponimo le gana en rank a la
# palabra comun**, y volverian por la puerta de atras. La columna es "menor es mas comun", asi
# que sumar es castigar. El valor es RANK_BASE entero: un nombre propio queda por debajo de
# CUALQUIER palabra comun, no un poco mas abajo. Si algun dia se quiere matizar, el numero esta
# aca y el test que lo fija es `test_el_nombre_propio_que_entra_PIERDE_prioridad`.
CASTIGO_NOMBRE_PROPIO = 1000

# Techo del rank. La columna es "menor es mas comun" (schema.sql), asi que el rank se calcula
# restando: una pagina rica queda cerca de 0, una pobre cerca del techo.
RANK_BASE = 1000

# La frontera entre las dos bandas del prior de orden.
#
# ⚠️ **Son dos bandas disjuntas y no una escala mezclada, y eso es el diseño.** Solo el **17,4 %**
# de los lemas del pack tiene señal de frecuencia (24.132 de 138.490): mezclar riqueza y frecuencia
# en un mismo numero exigiria calibrar cuanta riqueza *vale* un punto de Zipf, que es una decision
# que nadie midio. Con bandas, quien tiene señal se ordena por ella --el dato honesto-- y quien no
# queda debajo **en bloque**, conservando entre pares el orden de riqueza de siempre.
#
# No aparecer en 50.000 palabras de subtitulos **ya es evidencia de rareza**, asi que la banda de
# abajo no es un castigo arbitrario: es lo que el silencio de la fuente significa.
FRONTERA_CON_SENAL = 500

# Cuanto vale un punto de Zipf en la banda con señal. Con Zipf ~7,2 para la palabra mas comun del
# español, 70 reparte el vocabulario frecuente sobre casi toda la banda sin desbordarla.
ESCALA_ZIPF = 70

class Perfil:
    """Las constantes del proxy de `rank`, que **se miden por idioma y no se heredan**.

    El rank no es frecuencia de uso --el Wikcionario no la trae-- sino riqueza de la pagina, que
    correlaciona con que la palabra sea comun porque las palabras comunes son las que la gente
    edita (D-063).

    `forms_cap` es el que mas depende del idioma: existe para que un verbo no gane solo por
    tener muchas formas. En español un verbo trae hasta 222 y el tope muerde; en ingles trae
    cuatro o cinco y **el tope nunca se activa**, asi que `w_form` deja de discriminar y el rank
    queda dominado por acepciones y etimologia. Por eso los dos perfiles no son iguales.
    """

    __slots__ = ("w_sense", "w_example", "w_form", "w_translation", "w_etymology", "forms_cap",
                 "categorias_de_registro", "separador_de_cita", "fusiona_iniciales",
                 "quita_subindices")

    def __init__(self, w_sense, w_example, w_form, w_translation, w_etymology, forms_cap,
                 categorias_de_registro=(), separador_de_cita=None, fusiona_iniciales=False,
                 quita_subindices=False):
        self.w_sense = w_sense
        self.w_example = w_example
        self.w_form = w_form
        self.w_translation = w_translation
        self.w_etymology = w_etymology
        self.forms_cap = forms_cap
        # Las categorias del wiki que significan "esta pagina REGISTRA un nombre, no lo define".
        # Solo las usa la politica "definitions-only". Ver `_es_registro_de_nombres`.
        self.categorias_de_registro = tuple(categorias_de_registro)
        # Con que caracter separa los campos de un `ref` ESTE dump, o None para no traer cita.
        #
        # ⚠️ **Es una calibracion por idioma y por eso vive aca**, junto a los pesos del rank y
        # por la misma razon que ellos (D-076): se midio sobre un dump concreto y no se hereda.
        # El ingles sirve `"1897, Richard Marsh, The Beetle:"` y el Wikcionario
        # `"Miguel Nicolau. Iniciacion a la Teologia. Pagina 85. 1984."` -- autor primero, año
        # ultimo, puntos en vez de comas. Aplicarle el separador del otro produce basura.
        #
        # ⚠️ **El punto 4 del docstring del modulo prohibe heuristicas sobre la prosa, y esto se
        # le acerca.** Entra igual porque no decide **presencia**: su peor caso es un corte feo
        # en una linea secundaria, no una palabra que desaparece sin dejar rastro, que es la
        # clase de error que aquella regla existe para impedir. Queda escrito para que la
        # proxima sesion no la borre por parecerse ni la copie a donde si decide presencia.
        self.separador_de_cita = separador_de_cita
        # Si un campo de una letra o dos es una INICIAL y no un campo. Solo tiene sentido cuando
        # el separador es el punto: `J. R. R. Tolkien` son cuatro campos partiendo a secas, y los
        # dos primeros serian `J` y `R`. El ingles parte por coma y no lo necesita.
        self.fusiona_iniciales = fusiona_iniciales
        # Si se sacan los subindices de referencia cruzada de la glosa.
        #
        # ⚠️ **Es por idioma porque los subindices significan COSAS DISTINTAS en cada dump**, y
        # aplicarlo a los dos rompe contenido. Medido sobre los packs de hoy: en español ~1.890
        # glosas los usan como referencia cruzada --`mudanza\u2081`, `ejercito\u2082`, 62 de 69
        # muestreadas-- y en ingles ~4.584 son **formulas quimicas** --`C\u2087H\u2085NO\u2083S`,
        # `MnO\u2082`, 23 de 26 y las otras 3 tambien--. Es la misma trampa de D-121.
        self.quita_subindices = quita_subindices


PERFILES = {
    # Medido sobre eswiktionary 2026-09-15: "per" devuelve perder/permitir/perseguir/permanecer/
    # perro, y "perro" subio de la posicion 619 a la 5 (D-067).
    "es": Perfil(w_sense=3, w_example=2, w_form=1, w_translation=0.5, w_etymology=5,
                 forms_cap=80,
                 # Medido sobre el dump del 2026-09-15: el 68,4 % de los ejemplos trae `ref`, con
                 # los campos separados por PUNTO y el autor primero. Recortado a autor y obra
                 # pasa de 92 a 50 bytes.
                 separador_de_cita=".", fusiona_iniciales=True,
                 # ~1.890 glosas con subindice de referencia cruzada. Ver [Perfil].
                 quita_subindices=True,
                 # Medido sobre el dump del 2026-09-15: 26.708 acepciones en la primera y 2.398
                 # repartidas en las otras tres. Entre las cuatro cubren los 28.314 nombres
                 # propios que solo dicen su categoria.
                 categorias_de_registro=("ES:Apellidos", "ES:Antropónimos",
                                         "ES:Antropónimos femeninos",
                                         "ES:Antropónimos masculinos")),
    # Ingles: el tope de formas baja porque un verbo trae 4-5 y no 137. Los pesos se ajustan
    # contra el dump midiendo que prefijos comunes devuelvan la palabra comun arriba.
    # ⚠️ El ingles NO declara `categorias_de_registro`, y es una medicion, no un olvido: ahi la
    # señal esta sucia. "Places in the United States" aparece en 865 acepciones de registro y en
    # **11.455** que definen, asi que la misma categoria esta en los dos lados y no separa nada.
    # Con la lista vacia, "definitions-only" deja entrar todos los nombres propios del ingles --
    # que es justo lo que hace al pack ingles el mas pesado. Ahi la politica util sigue siendo
    # "lexical-only".
    "en": Perfil(w_sense=3, w_example=2, w_form=4, w_translation=0.5, w_etymology=5,
                 forms_cap=12,
                 # Medido sobre el dump del 2026-09-09: el 75,5 % de los ejemplos que el builder
                 # guarda trae `ref`, y sus campos van separados por coma con el año primero.
                 separador_de_cita=","),
}

# Cuantos campos del `ref` se conservan.
#
# Dos es "año, autor" en el ingles --`"1897, Richard Marsh, The Beetle:"` -> `"1897, Richard
# Marsh"`-- y baja el promedio de **119 a 31 bytes**, que sobre el pack ingles es +1,6 % en vez
# de +6,3 %. Lo que se tira es editorial, ciudad, →OCLC y pagina: datos de catalogo que en una
# pantalla de reloj no entran y que nadie lee en un diccionario.
CAMPOS_DE_CITA = 2

# Los pares que NO se pueden partir al buscar el separador de nivel superior.
#
# ⚠️ **Sin las comillas, 320 citas (1,1 %) salen con el titular cortado al medio**: el `ref` de
# una nota periodistica es `2019 June 6, “A gaggle, a confusion and a conspiracy…”, in BBC:` y
# partir por coma a secas deja `2019 June 6, “A gaggle`, que se lee como un dato roto.
#
# ⚠️ **Y los CORCHETES los encontro leer el pack construido, no un test.** El Wiktionary los usa
# para el nombre editorial del autor --`[Alfred, Lord Tennyson]`, `[William Tyndale, transl.]`,
# `Beniamin Ionson [i.e., Ben Jonson]`-- y esa coma es interna: sin ellos `captive` mostraba
# `1850, [Alfred`. Medido sobre el dump son **369 citas (1,3 %)** y **todas** salian con el
# corchete abierto sin cerrar. Los tres pares juntos cuestan **1 byte** de promedio (31 -> 32).
#
# Un par sin cerrar en la fuente no rompe nada: la profundidad no vuelve a cero, no se corta, y
# la cita sale entera. Es mas larga de lo ideal y nunca incorrecta, que es el lado correcto para
# fallar.
_PARES_DE_CITA = (("“", "”"), ("(", ")"), ("[", "]"))

# Los caracteres que ABREN uno de esos pares, para la guarda del desenvoltorio.
_APERTURAS_DE_CITA = frozenset(abre for abre, _ in _PARES_DE_CITA)

# Lo que una fuente deja pegado al final de un campo de cita. **El punto NO esta**: un apellido
# abreviado termina en punto ("Thos.", "Marsh Jr.") y quitarselo inventa una forma que nadie
# escribio.
_CIERRE_DE_CITA = " :,;"


def _recorte_de_cita(ref, perfil):
    """Los dos primeros campos de nivel superior de un `ref`, o None si no hay nada que decir.

    "Nivel superior" quiere decir fuera de los pares de [_PARES_DE_CITA]: el separador que cae
    dentro de un titulo entrecomillado o de un parentesis **no parte**. Ver la medicion ahi.

    Devuelve None y no "" para que el llamador no tenga que distinguir dos formas del mismo
    caso -- el ejemplo sin fuente conocida, que es el 24,5 % de ellos.
    """
    separador = perfil.separador_de_cita if perfil else None
    if not separador:
        return None
    # ⚠️ Un `ref` con el campo de autor vacio empieza con el separador: visto en el dump español
    # --`. Anonimo. Ordinacion dada a la ciudad...`--. Sin esto el primer campo sale vacio y el
    # recorte se lleva uno menos de los que deberia.
    texto = (ref or "").strip().lstrip(separador + " ").strip()
    if not texto:
        return None
    cortado = _corta_en_nivel_superior(texto, separador, perfil.fusiona_iniciales)
    # ⚠️ **El Wiktionary tambien encierra la cita ENTERA entre corchetes** cuando la fuente es
    # indirecta: `[1755 April 15, Samuel Johnson, "Lexico'grapher", in A Dictionary…`. Ese
    # corchete no cierra dentro de los dos primeros campos, asi que la profundidad nunca vuelve
    # a cero, no se corta nada y sale el `ref` entero con el par abierto. Medido: 184 citas
    # (0,64 %).
    #
    # ⚠️ **Se desenvuelve SOLO si el corte quedo desbalanceado, y la version ingenua se probo
    # primero: rompia.** Sacar el delimitador inicial siempre convertia `[1877], Anna Sewell` en
    # `1877], Anna Sewell` y `(Can we date this quote?), Sir T. Browne` en algo peor todavia --
    # ahi el par **si** cierra y el corte ya era correcto. Con la guarda, las citas con un par
    # sin cerrar pasan de 184 a **0 de 28.744**, y el promedio baja de 31,8 a 30,8 bytes.
    if _desbalanceada(cortado) and texto[0] in _APERTURAS_DE_CITA:
        alternativa = _corta_en_nivel_superior(texto[1:].strip(), separador,
                                               perfil.fusiona_iniciales)
        if not _desbalanceada(alternativa):
            cortado = alternativa
    return cortado or None


def _corta_en_nivel_superior(texto, separador, fusiona_iniciales=False):
    """Los dos primeros campos, sin partir dentro de un par de [_PARES_DE_CITA].

    ⚠️ **`fusiona_iniciales` existe por el español y no es cosmetico.** Ahi el separador es el
    punto, y `J. R. R. Tolkien. El Señor de los Anillos` tiene **cinco** campos partiendo a
    secas: los dos primeros serian `J` y `R`. Un campo de una letra o dos no es un campo, es una
    inicial, y se pega al siguiente.
    """
    campos = []
    actual = []
    profundidad = 0
    for ch in texto:
        for abre, cierra in _PARES_DE_CITA:
            if ch == abre:
                profundidad += 1
                break
            if ch == cierra:
                profundidad = max(0, profundidad - 1)
                break
        if ch == separador and profundidad == 0:
            pieza = "".join(actual)
            if fusiona_iniciales and _es_inicial(pieza):
                # ⚠️ **La inicial NO cierra el campo: se lo lleva consigo.** `J. R. R. Tolkien`
                # es UN autor, no cuatro campos, y el apellido viene despues -- asi que lo que
                # hay que hacer es seguir acumulando, no pegarse al campo anterior.
                actual.append(ch)
                continue
            campos.append(pieza)
            actual = []
            if len(campos) == CAMPOS_DE_CITA:
                break
            continue
        actual.append(ch)
    if len(campos) < CAMPOS_DE_CITA and actual:
        campos.append("".join(actual))
    juntos = (separador + " ").join(c.strip() for c in campos if c.strip())
    return juntos.rstrip(_CIERRE_DE_CITA)


def _es_inicial(pieza):
    """Si lo acumulado termina en una inicial --`J`, `R`, `Ch`-- y no en un campo completo.

    Se mira el ULTIMO token y no la pieza entera: al llegar al punto de `J. R. R. Tolkien` lo
    acumulado ya es `J. R. R`, y lo que decide si el campo cierra es que la siguiente palabra
    sea un apellido y no otra inicial.
    """
    tokens = pieza.strip().split()
    return bool(tokens) and len(tokens[-1].rstrip(".")) <= 2


def _desbalanceada(cita):
    """Si a la cita le falta cerrar alguno de los pares. Es lo que se lee como dato roto."""
    return any(cita.count(abre) != cita.count(cierra) for abre, cierra in _PARES_DE_CITA)


# Etiquetas de mantenimiento del wiki incrustadas en la glosa (D-121).
#
# wiktextract las deja adentro de `glosses` y **no hay version limpia**: `raw_glosses` es None
# en todos los casos medidos. En español son 774 acepciones: 671 "[cita requerida]" y 103
# "[definición imprecisa]". En un reloj, "Pene.^([cita requerida])" gasta media pantalla en
# decirle al lector que un editor queria una fuente.
#
# **El patron exige los CORCHETES, y no es un detalle**: en ingles `^(...)` sin corchetes es
# superindice matematico --10^(100), e^(iπ), 2^(2/r)-- y un filtro mas ancho destruiria
# contenido en vez de limpiarlo. Con corchetes hay un solo caso en ingles, "[sic]".
#
# El punto opcional del final existe porque la fuente escribe "...los labios.^([cita
# requerida])." y sacar solo el tag deja dos puntos seguidos.
_MARKUP_EDITORIAL = re.compile(r"\s*\^\(\[[^\]]*\]\)\.?")


# Los subindices de referencia cruzada del Wikcionario: `ejercito\u2082 terrestre`.
#
# ⚠️ **Se quitan SOLO en los idiomas que lo declaran, y esa restriccion es la decision.** Medido
# sobre los packs de hoy: en español ~1.890 glosas los usan como referencia cruzada (62 de 69
# muestreadas) y en ingles ~4.584 son **formulas quimicas** --`C\u2087H\u2085NO\u2083S`,
# `FeO\u2082\u00b2\u207b`, `MnO\u2082`; 23 de 26, y las otras 3 tambien son quimica--. Un
# `str.translate` en este mismo lugar, aplicado a los dos, convierte la sacarina en algo que no
# es una formula, en un lugar donde nadie mira. Es la misma trampa que D-121 documento con el
# superindice matematico.
_SUBINDICES = str.maketrans("", "", "\u2080\u2081\u2082\u2083\u2084\u2085\u2086\u2087\u2088\u2089")


def _gloss(sense, perfil=None):
    """La glosa de una acepcion.

    `glosses` puede traer varios niveles (la general primero, la especifica despues). Se toma
    **la ultima**: es la que define de verdad. Unir todas repetiria el texto del padre en cada
    hija, que es peso pagado dos veces en el payload y en el indice.

    ⚠️ **Pierde informacion en español, y se acepta a sabiendas**: el subindice dice **que
    acepcion** de la palabra referida, y eso no es recuperable. Lo que se gana es que
    `ejercito\u2082` deje de leerse como un error de codificacion en una pantalla de reloj, donde
    la acepcion exacta no se puede consultar de todas formas. Ver [_SUBINDICES].
    """
    glosses = [g.strip() for g in (sense.get("glosses") or []) if g and g.strip()]
    if not glosses:
        return ""
    limpia = _MARKUP_EDITORIAL.sub("", glosses[-1]).strip()
    if perfil is not None and perfil.quita_subindices:
        limpia = limpia.translate(_SUBINDICES)
    return limpia


def _senal_lexica(raw):
    """Cuanta vida lexica tiene la palabra: traducciones + descendientes + derivados."""
    return (
        len(raw.get("translations") or [])
        + len(raw.get("descendants") or [])
        + len(raw.get("derived") or [])
    )


def _is_form_of(sense):
    return "form-of" in (sense.get("tags") or []) or bool(sense.get("form_of"))


def _by_sense_index(raw, headword, clave, idioma=None):
    """Mapa `sense_index` -> items, leido del registro crudo. La forma del dump español.

    `clave` es "synonyms", "antonyms" o "translations": es literalmente la misma forma con otro
    nombre, y dos copias de esto divergirian el dia que alguien arregle un borde en una sola.

    `idioma` filtra por `code`, y sólo las traducciones lo necesitan: el dump trae la tabla
    entera. Medido sobre el español, `en` son **34.710 de 281.022** items; sin filtro una entrada
    española mostraria su traduccion al polaco.

    **La clave es el `sense_index` que declara la fuente, NUNCA la posicion.** `_senses()` poda
    las acepciones form-of antes de emitir, asi que los ordinales se corren: un `enumerate()`
    le colgaria a la acepcion que sobrevive los sinonimos de la que se fue. Eso no lanza, no
    loguea y no lo agarra `verify_pack.py` -- sale del pack como contenido correcto.

    Un sinonimo sin `sense_index` se descarta (medido: 5 en todo el dump). Colgarlo de la
    primera acepcion seria inventar una atribucion que la fuente no da.
    """
    out = {}
    for item in raw.get(clave) or []:
        index = (item.get("sense_index") or "").strip()
        word = (item.get("word") or "").strip()
        # El sinonimo igual al lema no aporta nada, igual que en _forms().
        if not index or not word or word == headword:
            continue
        if idioma is not None and (item.get("code") or item.get("lang_code")) != idioma:
            continue
        for numero in _indices(index):
            out.setdefault(numero, []).append(word)
    return out


# Un `sense_index` puede nombrar varias acepciones: "1-2", "1, 4". Medido sobre el dump español:
# los sinonimos y antonimos son **100 % simples** --0 compuestos de 86.418 y 7.542-- asi que
# expandir aca no los toca; las traducciones son 53,6 % simples y **8,6 % compuestas**, y sin
# expandirlas ese 8,6 % no encuentra acepcion.
_RANGO = re.compile(r"(\d+)\s*[-\u2013\u2014]\s*(\d+)")
# Techo del rango: "4-10" es real, pero un numero enorme seria un parseo equivocado que colgaria
# el item de acepciones que no existen. Se descarta en vez de adivinar.
MAX_ACEPCIONES_POR_RANGO = 50


def _indices(index):
    """Los numeros de acepcion que nombra un `sense_index`, como strings.

    Lo que no se entiende se **descarta**, igual que D-117 descarta un item sin indice: medido,
    es residuo --`"1b"` 3 veces, `"1 y 2"` 2, `"2 (en el aire)"` 1 en todo el dump-- y adivinar
    seria inventar la atribucion.
    """
    salida = []
    for parte in re.split(r"[,;]", index):
        parte = parte.strip()
        rango = _RANGO.fullmatch(parte)
        if rango:
            desde, hasta = int(rango.group(1)), int(rango.group(2))
            if desde <= hasta and hasta - desde < MAX_ACEPCIONES_POR_RANGO:
                salida.extend(str(n) for n in range(desde, hasta + 1))
        elif parte.isdigit():
            salida.append(parte)
    return salida


def _nested(sense, headword, clave):
    """Los items que vienen DENTRO de la acepcion. La forma del dump ingles.

    `clave` es "synonyms" o "antonyms", igual que en `_by_sense_index`.

    No se pide `sense_index` y no es un descuido: aca la atribucion es estructural --el item ya
    vive en su acepcion-- mientras que en la forma de arriba es declarada. Exigirlo tiraria los
    338.200 items del dump ingles por no traer un dato que no necesitan.

    **El orden del dump se respeta.** El 74,5 % traen `source: "Thesaurus:*"` y van primero, asi
    que parecia que el tope de 4 se quedaria con lo oscuro; medido, reordenar cambia 91 de 4.872
    acepciones mezcladas (1,9 %) y en la muestra el resultado es PEOR: `craft` pasa de
    `ability, aptitude` a `craftiness, foxiness`.
    """
    out, vistos = [], set()
    for item in sense.get(clave) or []:
        word = (item.get("word") or "").strip()
        # Igual que en _forms() y en la forma de arriba: el sinonimo igual al lema no aporta
        # nada. Medido: 1,9 % de los items ingleses.
        if not word or word == headword or word in vistos:
            continue
        vistos.add(word)
        out.append(word)
    return out


def _es_markup(word):
    """Una referencia interna del wiki, no una palabra. Medido: 0,40 % de los items.

    Estas listas son las unicas del payload que la fuente **no limpia**: los sinonimos vienen
    como lemas y estas vienen como enlaces crudos, asi que traen namespaces (`Appendix:Months`,
    `mul:12`), referencias a secciones (`abbot § Related terms`) y alguna frase suelta
    (`more at ...`).

    Importa mas de lo que el 0,40 % sugiere: donde se ven es en las entradas **flacas**, que son
    para las que existen, y ahi esa linea es lo unico debajo de la glosa. Ademas ninguna se puede
    abrir --`norm()` no las encuentra-- asi que serian un enlace muerto.
    """
    return ":" in word or "§" in word or word.lower().startswith("more at ")


def _relacionadas(fuente, headword, ya_mostrados):
    """Hiperonimos, hiponimos y `related`, leidos de `fuente`: una acepcion o el registro entero.

    Son el ultimo campo aprovechable que la fuente traia y el builder tiraba. Importan por las
    **entradas flacas**: el 70,4 % del pack español es una acepcion sola sin ejemplo, y esas son
    las que se sienten vacias en el reloj. Medido sobre 174.395 registros vivos: de las 29.817
    flacas, 2.142 ganan algo por aca (7,2 %). Los sinonimos alcanzan a mas --20,3 %-- pero esos
    ya entran por `_by_sense_index`, asi que no son ganancia nueva.

    **Las dos formas del dump, y no son intercambiables.** Igual que con los sinonimos (D-124),
    cada idioma sirve estas listas en un lugar distinto -- medido sobre ~185.000 registros vivos
    de cada dump:

        relacionadas ANIDADAS en la acepcion     es 0,0 %   en 13,8 %
        relacionadas a nivel de ENTRADA          es 5,0 %   en  9,6 %

    Anidadas la atribucion es **estructural**: el item ya vive en su acepcion, asi que entran
    siempre. A nivel de entrada es **inexistente** --no hay `sense_index`, a diferencia de los
    sinonimos-- y entonces `_senses` solo las pide **si la entrada tiene una sola acepcion**. Con
    varias, colgarlas de la primera seria inventar la atribucion: el mismo error que
    `_by_sense_index` documenta y descarta, que no lanza, no loguea y sale del pack como
    contenido correcto. Con una sola no hay nada que inventar, porque no hay otra donde irian.

    `ya_mostrados` son los sinonimos y antonimos que esa acepcion ya emite. Repetir una palabra
    dos renglones mas abajo gasta una pantalla de reloj, que es el recurso escaso de este pack.
    """
    out, vistos = [], set(ya_mostrados)
    for clave in CAMPOS_RELACIONADAS:
        for item in fuente.get(clave) or []:
            word = (item.get("word") or "").strip()
            # Igual que en _forms(), _by_sense_index() y _nested(): la palabra igual al lema no
            # aporta nada. Aca pasa de verdad -- "be" se lista como related de "be".
            if not word or word == headword or word in vistos or _es_markup(word):
                continue
            vistos.add(word)
            out.append(word)
    return out


def _word_translations(raw, headword, idioma, ya_en_acepciones):
    """Las traducciones que la fuente NO atribuyo a ninguna acepcion.

    ⚠️ **Son el 37,7 % del dato y hasta hoy se tiraban**, porque el payload tenia un solo canal:
    `T` vive dentro de una acepcion, asi que emitir esto ahi habria sido colgarlo de la primera
    --el error de D-117, que se lee plausible y no lo agarra nadie. Medido sobre el pack de
    muestra, eran el **34,8 % de las traducciones disponibles**: `construir` tenia 16 y mostraba
    cero.

    Se excluye lo que ya salio por acepcion: repetirlo abajo diria que la palabra significa eso
    "ademas", cuando es lo mismo con mejor atribucion.
    """
    if not idioma:
        return ()
    salida = []
    for item in raw.get("translations") or []:
        word = (item.get("word") or "").strip()
        if not word or word == headword:
            continue
        if (item.get("code") or item.get("lang_code")) != idioma:
            continue
        if (item.get("sense_index") or "").strip():
            continue
        if word not in salida and word not in ya_en_acepciones:
            salida.append(word)
    return tuple(salida[:MAX_TRADUCCIONES_POR_ACEPCION])


def _senses(raw, translations_to=None, perfil=None):
    """Las acepciones que sobreviven la poda. Vacia si el registro no es una entrada."""
    headword = raw.get("word", "")
    synonyms = _by_sense_index(raw, headword, "synonyms")
    antonyms = _by_sense_index(raw, headword, "antonyms")
    # Sin destino declarado no se emite ninguna: el pack ingles no tiene que ganar traducciones
    # por accidente sólo porque su dump trae la tabla.
    traducciones = (
        _by_sense_index(raw, headword, "translations", idioma=translations_to)
        if translations_to else {}
    )
    out = []
    for sense in raw.get("senses") or []:
        if _is_form_of(sense):
            continue
        gloss = _gloss(sense, perfil)
        if not gloss:
            continue
        examples = []
        for example in (sense.get("examples") or [])[:MAX_EXAMPLES_PER_SENSE]:
            text = (example.get("text") or "").strip()
            if not text:
                continue
            # ⚠️ **La cita se descarta si no hay ejemplo, no al reves.** Sin texto no hay de que
            # colgarla, y guardarla igual la dejaria nombrando al ejemplo de la acepcion
            # siguiente. Ver la regla estricta de `payload.parse`.
            cita = _recorte_de_cita(example.get("ref"), perfil)
            examples.append({"text": text, "ref": cita} if cita else text)
        index = (sense.get("sense_index") or "").strip()
        # Las dos formas en que la fuente sirve sinonimos. Ningun dump usa las dos, asi que esto
        # no es una precedencia sino una union: la que este vacia no aporta nada.
        out.append({
            "gloss": gloss,
            "examples": examples,
            # ⚠️ **Lo que no trae indice NO entra**, que es la regla de D-117 y la razon por la
            # que el modo lista existe: colgarla de la acepcion 1 acierta a veces y falla otras
            # sin dejar rastro. Medido, el 37,7 % de las traducciones del dump no trae indice --
            # ese dato es real y su lugar honesto es el canal de nivel de entrada, que todavia
            # no existe (roadmap §Naming a sense from another pack).
            "translations": traducciones.get(index, [])[:MAX_TRADUCCIONES_POR_ACEPCION],
            "synonyms": (
                synonyms.get(index, []) or _nested(sense, headword, "synonyms")
            )[:MAX_SYNONYMS_PER_SENSE],
            # Mismo molde y mismo tope. Atribuir mal un antonimo es peor que atribuir mal un
            # sinonimo: se lee como lo contrario de otra cosa, no como una eleccion rara.
            "antonyms": (
                antonyms.get(index, []) or _nested(sense, headword, "antonyms")
            )[:MAX_SYNONYMS_PER_SENSE],
            # Solo la forma ANIDADA aca: es la unica cuya atribucion es estructural. La de
            # nivel de entrada se agrega abajo, y solo si hay una sola acepcion.
            "related": _relacionadas(
                sense, headword, ya_mostrados=synonyms.get(index, []) + antonyms.get(index, [])
            )[:MAX_SYNONYMS_PER_SENSE],
        })
    if len(out) == 1:
        # Union con lo anidado, no precedencia -- igual que las dos formas de los sinonimos: la
        # que este vacia no aporta nada, y ningun dump usa las dos a la vez.
        sola = out[0]
        ya = sola["synonyms"] + sola["antonyms"] + sola["related"]
        sola["related"] = (
            sola["related"] + _relacionadas(raw, headword, ya)
        )[:MAX_SYNONYMS_PER_SENSE]
    return out


def _forms(raw, headword, inbound):
    """Las flexiones que llevan a este lema, deduplicadas y sin el lema.

    Dos fuentes: las que el lema declara en `forms`, y las paginas form-of que lo apuntan.
    Ninguna de las dos alcanza sola. Ver el punto 1 del docstring del modulo.
    """
    seen = {}
    for item in raw.get("forms") or []:
        form = (item.get("form") or "").strip()
        if form and form != headword:
            seen[form] = None
    for form in sorted(inbound):
        if form != headword:
            seen[form] = None
    return tuple(seen)


#: Which principal parts are kept, and under which neutral key. Order is the table's.
#:
#: WARNING: each row REQUIRES some tags and FORBIDS others, and the second half is what makes it
#: useful: without `forbidden`, `plural` takes any first-person-plural verb form.
#:
#: WARNING: `impersonal` does NOT disqualify, and believing it cost the first version. The source
#: puts it on EVERY non-personal Spanish form -- `corriendo`, `corrido` and `haber corrido` all
#: carry it -- so using it as a filter left `correr` with no principal parts at all. What
#: separates `corriendo` from `habiendo corrido` is not a tag but that the second is COMPOUND,
#: and that shows in the space. Measured against the real dump, not reasoned.
PARTES_PRINCIPALES = (
    ("ger", frozenset({"gerund"}), frozenset()),
    ("part", frozenset({"participle"}), frozenset()),
    ("pl", frozenset({"plural"}),
     frozenset({"first-person", "second-person", "third-person", "feminine"})),
    ("fem", frozenset({"feminine"}),
     frozenset({"plural", "first-person", "second-person", "third-person"})),
)


def _display_forms(raw, headword):
    """The principal parts the card shows, as `[(key, form), ...]`.

    WARNING: this is not `_forms`, and mixing them would be an expensive mistake. `_forms` feeds
    the SEARCH channel: it wants every inflection, normalized, so typing `corrais` finds
    `correr`. This feeds the SCREEN: it wants very few, with their spelling and their label. A
    Spanish verb carries 137 forms in the source; two come out here.

    WARNING: with the original spelling, which is exactly what `form` cannot give: that table
    stores `norm(form)` -- `corrais`, not `corráis` -- because its job is to be a search key.
    """
    salida = []
    vistas = set()
    for clave, exigidas, prohibidas in PARTES_PRINCIPALES:
        for item in raw.get("forms") or []:
            forma = (item.get("form") or "").strip()
            if not forma or forma == headword or forma in vistas:
                continue
            # WARNING: compounds out. `haber corrido` and `habiendo corrido` carry the same
            # tags as the simple ones, and a watch card has no room for a periphrasis.
            if " " in forma:
                continue
            tags = set(item.get("tags") or ())
            if exigidas <= tags and not (prohibidas & tags):
                salida.append((clave, forma))
                vistas.add(forma)
                break
    return tuple(salida)


def _rank(raw, senses, forms, perfil, es_nombre_propio=False, zipf=None):
    """El prior con el que se ordenan los resultados. Menor es mas comun.

    ⚠️ **`zipf` es frecuencia de uso REAL y manda sobre la riqueza de pagina.** La riqueza era el
    unico proxy que habia y resulto malo: medido sobre el pack español, correlaciona **-0,250**
    con la frecuencia real donde se esperaria -1, porque cuenta formas flexionadas y un verbo trae
    hasta 222. Donde la banda de cobertura de D-142 no llega --los peldaños `INFLECTED_FORM` y
    `TRANSLATION`, que ordenan por `rank` puro-- eso se veia crudo: `house` devolvia
    `solar, alojar, albergar` y nunca `casa`.

    Dos bandas disjuntas, ver [FRONTERA_CON_SENAL]:

    - **con señal** -> `[0, FRONTERA_CON_SENAL)`, del Zipf;
    - **sin señal** -> `[FRONTERA_CON_SENAL, RANK_BASE]`, de la riqueza de siempre, comprimida a
      la mitad del rango. Se conserva el orden entre pares: entre palabras raras, la riqueza sigue
      siendo la mejor pista que hay.

    `es_nombre_propio` aplica [CASTIGO_NOMBRE_PROPIO] **encima de todo lo anterior**: el que entro
    por una politica permisiva queda debajo de cualquier palabra comun, no un poco mas abajo. Se
    suma al final a proposito -- si la frecuencia se aplicara despues, `Madrid`, que es frecuente,
    entraria por debajo del piso que `verify_pack.py` exige y el pack fallaria la verificacion.
    """
    if zipf is not None:
        base = max(0, FRONTERA_CON_SENAL - 1 - int(round(zipf * ESCALA_ZIPF)))
    else:
        score = (
            perfil.w_sense * len(senses)
            + perfil.w_example * sum(len(s["examples"]) for s in senses)
            + perfil.w_form * min(len(forms), perfil.forms_cap)
            + perfil.w_translation * len(raw.get("translations") or [])
            + (perfil.w_etymology if raw.get("etymology_texts") else 0)
        )
        base = FRONTERA_CON_SENAL + max(0, RANK_BASE - int(score)) // 2
    return base + CASTIGO_NOMBRE_PROPIO if es_nombre_propio else base


def _sense_key(raw, index, needs_key):
    """Que separa dos homografos que comparten word, pos Y pos_title.

    Existe de verdad: `leonino` adjetivo aparece tres veces en el Wikcionario, distinguido solo
    por etimologia. Se usa `pos_title` cuando alcanza, y un ordinal cuando no.

    El ordinal es la posicion dentro del grupo, no un hash del contenido, **a proposito**:
    editar una etimologia no tiene que cambiar el uid (D-055). El costo es el simetrico — que
    insertar una acepcion nueva en el medio corra los ordinales siguientes— y es el menos malo
    de los dos, porque las inserciones son mas raras que las ediciones.
    """
    if not needs_key:
        return None
    title = raw.get("pos_title") or ""
    return "%s#%d" % (title, index) if index else title or "#0"


def _es_registro_de_nombres(raw, perfil):
    """La pagina REGISTRA un nombre en vez de definirlo: un apellido, un nombre de pila.

    ⚠️ **Mira `categories`, no la glosa, y eso es el punto.** Un patron sobre la prosa
    ("^Apellido") seria una heuristica en un idioma, justo lo que el punto 4 del docstring del
    modulo dice que no se hace; `categories` lo emite wiktextract desde la categorizacion del
    propio wiki y viaja en todos los dumps.

    Medido en español: 26.708 acepciones en `ES:Apellidos` y 2.398 en los tres `ES:Antropónimos`,
    que entre las cuatro cubren los 28.314 nombres propios que solo dicen su categoria.

    **Hace falta que TODAS las acepciones lo sean.** "Estrella" es nombre de pila y tambien el
    cuerpo celeste: podarla por la primera perderia la segunda, que es vocabulario.
    """
    if not perfil.categorias_de_registro:
        return False
    sentidos = raw.get("senses") or []
    if not sentidos:
        return False
    for sense in sentidos:
        nombres = set()
        for cat in sense.get("categories") or []:
            nombres.add(cat if isinstance(cat, str) else (cat.get("name") or ""))
        if not nombres.intersection(perfil.categorias_de_registro):
            return False
    return True


def _entra_el_nombre_propio(raw, perfil, politica):
    """Si este `pos = "name"` sobrevive la poda, segun la politica. Ver POLITICAS_DE_NOMBRES."""
    if politica == "included":
        return True
    if politica == "definitions-only":
        return not _es_registro_de_nombres(raw, perfil)
    return _senal_lexica(raw) >= SENAL_LEXICA_MINIMA


class Opciones:
    """Lo que el build le pide al lector y que **no cambia entre registros**.

    ⚠️ **Existe porque encadenar las opciones una por una ya costo un bug.** Cada opcion nueva
    --`translations_to`, `frequencies`-- habia que sumarla a `records`, a `_emit` y a **los dos**
    sitios donde `_emit` se llama, porque el ultimo grupo del archivo sale por una llamada aparte
    fuera del bucle. Olvidar esa segunda llamada hace que **la ultima palabra del dump pierda ese
    dato en silencio**, y paso: ningun test lo agarro, porque ninguno tiene dos palabras donde la
    segunda sea la ultima.

    Con esto, agregar una opcion es agregar un campo. Los sitios de llamada no se tocan.
    """

    __slots__ = ("perfil", "politica", "translations_to", "frequencies",
                 "lemas_en_minuscula")

    def __init__(self, perfil, politica, translations_to=None, frequencies=None,
                 lemas_en_minuscula=()):
        self.perfil = perfil
        self.politica = politica
        self.translations_to = translations_to
        self.frequencies = frequencies
        self.lemas_en_minuscula = frozenset(lemas_en_minuscula)

    def zipf(self, headword):
        """La frecuencia de un lema, o None si no hay señal.

        ⚠️ Usa `frequency.key` y **no** `norm()`: plegar el acento le da a `háber` la frecuencia
        de `haber` --el verbo, puesto 210-- y lo manda a rank 97 contra 237 de `hábil`.

        ⚠️ **Y una palabra con mayuscula NO cobra la frecuencia de su homografo en minuscula.**
        `frequency.key` baja a minusculas --correcto, D-186-- y la lista de OpenSubtitles **ya
        viene toda en minusculas**, asi que las dos comparten clave. Medido sobre el pack ingles:
        **6.462 entradas** con mayuscula y `pos != name` estaban en la banda de frecuencia real
        `[0,500)`, que tiene 55.903 -- el **11,6 %** de la banda "mas frecuente" era esto.

        Lo que se corrige es solo la clase que tiene regla: las **4.246** cuyo homografo en
        minuscula **tambien es entrada del pack**, que son siglas y formas honorificas --`TO`,
        `OF`, `IS`, `WE`, `ME`, `HE`, `NO`, `ARE`, `BE`, `CAN`--. Ahi el argumento es inequivoco:
        esa frecuencia es del lema en minuscula, que ya tiene su propia entrada para reclamarla.

        ⚠️ **Las otras dos clases NO se tocan, y es deliberado.** 1.333 tienen hermano `pos=name`
        y 883 no tienen ninguna de las dos señales, y las dos mezclan la basura con el vocabulario
        legitimo: `Thomas` 179 y `Richard` 168 conviven con `Christmas` 150, `American` 153 y
        `British` 177. No hay dato en disco que las separe -- el truco de D-137 (*una palabra
        escrita en minuscula alguna vez en el corpus es comun*) se midio contra las 41.512 frases
        inglesas de Tatoeba y **no transfiere**: `american` aparece 361 veces y **0 en minuscula**,
        porque el ingles capitaliza gentilicios por regla.

        Lo que queda sin frecuencia cae a la banda de riqueza de pagina, que es la respuesta
        correcta: nadie midio la frecuencia de ESA grafia.
        """
        if not self.frequencies:
            return None
        minuscula = headword.lower()
        if headword != minuscula and minuscula in self.lemas_en_minuscula:
            return None
        return self.frequencies.get(_frequency.key(headword))


def _emit(group, inbound, opciones):
    """Convierte un grupo de registros del mismo `word` en Records."""
    perfil, politica = opciones.perfil, opciones.politica
    prepared = []
    for raw in group:
        if raw.get("pos") == "name" and not _entra_el_nombre_propio(raw, perfil, politica):
            continue
        senses = _senses(raw, opciones.translations_to, perfil)
        if not senses:
            continue
        prepared.append((raw, senses))

    # Un sense_key hace falta solo cuando dos entradas del grupo comparten pos: es lo unico que
    # colisiona en stable_uid(). Ponerlo cuando no hace falta vuelve el uid inestable de gratis.
    by_pos = {}
    for raw, _ in prepared:
        by_pos[raw.get("pos")] = by_pos.get(raw.get("pos"), 0) + 1

    index_in_pos = {}
    for raw, senses in prepared:
        pos = raw.get("pos")
        index = index_in_pos.get(pos, 0)
        index_in_pos[pos] = index + 1
        headword = raw["word"]
        forms = _forms(raw, headword, inbound.get(headword, ()))
        por_acepcion = [t for s in senses for t in s["translations"]]
        sueltas = _word_translations(raw, headword, opciones.translations_to, set(por_acepcion))
        yield Record(
            headword=headword,
            senses=senses,
            part_of_speech=pos,
            rank=_rank(raw, senses, forms, perfil, es_nombre_propio=(pos == "name"),
                       zipf=opciones.zipf(headword)),
            forms=forms,
            display_forms=_display_forms(raw, headword),
            # ⚠️ **El canal de BUSQUEDA lleva las dos**, atribuidas y sueltas: para encontrar
            # `casa` escribiendo `house` da igual si la fuente supo a que acepcion pertenece.
            # Esto es lo que hace que un pack monolingue se busque tambien en el otro idioma.
            translations=tuple(dict.fromkeys(por_acepcion + list(sueltas))),
            word_translations=sueltas,
            sense_key=_sense_key(raw, index, by_pos[pos] > 1),
        )


def _is_form_page(raw):
    """Una pagina que es SOLO forma flexionada: ninguna de sus acepciones define nada."""
    senses = raw.get("senses") or []
    return bool(senses) and all(_is_form_of(sense) for sense in senses)


def _inbound_forms(path):
    """Pasada 1: invierte las paginas form-of en un mapa lema -> formas que llevan a el.

    Se invierte aca y no en la pasada 2 porque la pasada 2 consume por lema: asi el mapa tiene
    una clave por lema (~150.000) en vez de una por forma (~700.000).
    """
    inbound = {}
    # Los lemas que YA estan escritos en minuscula y serian una entrada del pack. Sale gratis
    # aca: esta pasada ya recorre el archivo entero. Ver `Opciones.zipf`.
    lemas_en_minuscula = set()
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            if not line.strip():
                continue
            raw = json.loads(line)
            word = raw.get("word")
            if not word:
                continue
            if not _is_form_page(raw):
                # ⚠️ **Una pagina `form-of` NO cuenta**, y la distincion importa: no es una
                # entrada, se invierte como forma de su lema (D-065). Si contara, `RAN` perderia
                # su frecuencia por `ran` --que es flexion de `run`-- sin que exista ninguna
                # entrada en minuscula que la reclame.
                if word == word.lower():
                    lemas_en_minuscula.add(word)
                continue
            for sense in raw["senses"]:
                for target in sense.get("form_of") or []:
                    lemma = target.get("word")
                    if lemma and lemma != word:
                        inbound.setdefault(lemma, set()).add(word)
    return inbound, lemas_en_minuscula


def records(path, lang="es", politica=POLITICA_POR_DEFECTO, translations_to=None,
            frequencies=None):
    """Itera el JSONL y entrega Records. Los del mismo `word` se agrupan para los homografos.

    **Los nombres propios NO salen por defecto** (`pos = "name"`: apellidos, toponimos, nombres
    de pila). Es una decision de producto, D-116, y el default vive aca --en la libreria-- y no
    en el flag de la CLI, para que cualquier llamador nuevo la herede sin tener que pedirla.

    Lo que se saca, medido: en español **32.305 entradas, el 22,1 %**, de las cuales **26.265
    tienen como definicion completa la palabra "Apellido."**. En ingles **163.470, el 17,1 %**,
    que ademas pesan **40,7 MB (13,8 % del pack)** y en **4.267 casos le ganan en rank a la
    palabra comun**: buscar "freedom" devolvia primero un pueblo del condado de Santa Cruz.

    `politica` elige entre las tres de [POLITICAS_DE_NOMBRES]. `"included"` sigue existiendo
    porque es lo que produjo esos numeros, y volver a medirlos contra un dump nuevo tiene que
    seguir siendo barato. `"definitions-only"` es la intermedia: poda el registro de nombres y
    deja entrar al que define, con el rank castigado.

    Una politica desconocida **lanza**: un typo en la CLI no puede construir un pack con el
    default y no decirlo, porque el pack saldria bien y con otro contenido del que se pidio.
    """
    if politica not in POLITICAS_DE_NOMBRES:
        raise ValueError("politica de nombres propios desconocida: %r (son %s)"
                         % (politica, ", ".join(POLITICAS_DE_NOMBRES)))
    # La pasada 1 PRIMERO: de ahi sale `lemas_en_minuscula`, que `Opciones` necesita.
    inbound, lemas_en_minuscula = _inbound_forms(path)
    opciones = Opciones(PERFILES[lang], politica, translations_to, frequencies,
                        lemas_en_minuscula)
    group = []
    current = None
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            if not line.strip():
                continue
            raw = json.loads(line)
            word = raw.get("word")
            if not word:
                continue
            if word != current:
                for record in _emit(group, inbound, opciones):
                    yield record
                group = []
                current = word
            group.append(raw)
    # El ultimo grupo del archivo sale por aca y no por el bucle. Con [Opciones] ya no
    # hay nada que olvidar, que es justo lo que este refactor vino a cerrar.
    for record in _emit(group, inbound, opciones):
        yield record
