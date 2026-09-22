#!/usr/bin/env python3
"""Deriva un pack NUCLEO de un pack completo ya construido.

    python3 tools/packbuilder/build_core.py <completo.db> <nucleo.db> <corpus.tsv> [--top N]

## Por que se deriva y no se construye en paralelo

El roadmap habia planteado un filtro dentro de `build_pack`, o sea reconstruir desde los dumps con
menos vocabulario. Derivar del pack completo es mejor por dos razones, y la primera es de
correctitud:

1. ⚠️ **El nucleo declara `subset_of`, y derivandolo eso es cierto POR CONSTRUCCION.** Construido
   en paralelo seria cierto sólo mientras las dos corridas usaran las mismas fuentes, los mismos
   filtros y la misma poda -- una promesa que nada comprueba y que se rompe en silencio la primera
   vez que alguien agrega una opcion a una sola de las dos.
2. Tarda segundos en vez de una hora, y no necesita los 4,5 GB de dumps.

## La trampa que esto evita, medida

**No se elige por `rank`.** `rank` es riqueza de pagina del diccionario, no frecuencia de uso: un
nucleo de 14.388 entradas elegido por `rank` se lleva **el 91 % de la tabla de flexiones** del pack
español, porque las paginas mas ricas son los verbos y un verbo tiene 33 formas. Elegido por
frecuencia de uso, las 7.349 entradas del top 8.000 se llevan **el 5,5 %**. Dieciseis veces menos.

La señal es `sources/tatoeba.frequencies`, que cuenta sobre el corpus con el mismo tokenizador y el
mismo `norm()` que indexa el pack.
"""

import binascii
import os
import sqlite3
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import build  # noqa: E402
import normalize  # noqa: E402
from sources import frequency as _frequency  # noqa: E402
import payload as payload_codec  # noqa: E402

# Cuantas palabras entran por defecto.
#
# ⚠️ **Medido sobre los dos corpus reales, y el techo lo pone el ingles.** En el puesto 8.000 una
# palabra española aparece en 21 frases y una inglesa en 5; en el puesto 15.000, en 9 y en 2. Mas
# alla de ~8.000 el corpus ingles --41.512 frases contra 442.135 del español-- deja de ser
# evidencia y pasa a ser ruido. Se usa el mismo N para los dos para que "nucleo" signifique lo
# mismo en los dos idiomas.
TOP_POR_DEFECTO = 8000

# Las claves que escribe el builder y que no se pueden heredar (ver PackBuilder.__init__).
DERIVADAS = {
    "schema_version", "norm_version", "payload_codec", "payload_dict", "payload_dict_sha256",
    "entry_count", "built_at", "uid_recipe", "data_version", "trans_dropped",
}


def vocabulario_del_corpus(corpus, lang, top=TOP_POR_DEFECTO):
    """Las `top` palabras mas usadas del corpus, como claves `norm()`."""
    from sources import tatoeba

    frecuencias = tatoeba.frequencies(corpus, lang=lang)
    orden = sorted(frecuencias.items(), key=lambda par: (-par[1], par[0]))
    return {palabra for palabra, _veces in orden[:top]}


#: Los tres tamanos, y lo que separa a cada uno. Ver D-215.
#:
#: ⚠️ **`full` no es un nivel derivado**: es el pack construido, sin filtrar y completamente
#: correcto. Aqui esta solo para que el nombre lo marque como los otros dos.
NIVELES = ("core", "main", "full")


def frecuencias_por_norm(lista):
    """La lista de frecuencias, con las claves que el pack usa. Ver `frequency.por_norm`."""
    return _frequency.por_norm(_frequency.load(lista), normalize.norm)


def vocabulario_por_presupuesto(completo, presupuesto_mb, frecuencias=None):
    """Los lemas que caben en `presupuesto_mb`, **los mas usados primero**.

    ## Que metrica decide, y por que esa

    La pregunta que un nivel tiene que contestar no es *"¿cuantas palabras entran?"* sino *"¿que
    fraccion de lo que alguien va a buscar esta adentro?"*. Eso se mide: **cobertura de tokens
    del corpus** -- la suma de las frecuencias de los lemas que entraron, sobre el total. Es
    verificable sobre el artefacto terminado, no una intencion, y por eso se escribe en
    `meta.corpus_coverage` y `verify_pack.py` la recalcula.

    ## El orden: frecuencia primero, `rank` detras, y eso esta MEDIDO

    ⚠️ **Ordenar por `rank` es peor, aunque `rank` ya salga de la frecuencia.** Dos razones, y
    las dos se ven en el numero:

    1. `rank` esta **bucketizado** --`int(round(zipf * ESCALA_ZIPF))`, 500 cubetas-- asi que
       miles de palabras comparten numero y el desempate es **alfabetico**. Una palabra gorda que
       empieza con `a` desplaza a una mas usada y mas flaca.
    2. Pasado el 500 `rank` **deja de ser frecuencia**: es riqueza de pagina, que correlaciona
       **-0,250** con el uso real (D-185). Un presupuesto que llega ahi gasta en paginas largas.

    Cobertura de tokens medida sobre los packs reales:

    | | por `rank` | por frecuencia | delta |
    |---|---|---|---|
    | ingles 25 MB | 93,46 % | **94,43 %** | +0,97 |
    | ingles 40 MB | 94,75 % | **96,03 %** | +1,28 |
    | ingles 130 MB | 95,10 % | **96,63 %** *(= el pack completo)* | +1,53 |
    | español 25 MB | 77,59 % | **78,87 %** *(= el pack completo)* | +1,28 |
    | español 40 MB | 77,62 % | **78,87 %** | +1,25 |

    Y entran **mas** lemas, no menos. Dos lecturas que valen: un nucleo español de **25 MB cubre
    tanto como el pack completo de 74**, y un `main` ingles de 130 MB alcanza la cobertura del de
    307. El techo es la lista: mas alla de las ~50.000 palabras que atestigua, sumar lemas no
    sube la cobertura -- lo que sube es lo que se encuentra al buscar algo raro, que es otra
    metrica y no esta.

    ⚠️ **`frecuencias` es opcional y sin ella se corta por `rank`, como antes.** No es un
    descuido: `build_core` deriva de un pack ya construido y puede correrse sobre uno cualquiera
    sin tener a mano el corpus con el que se hizo. Degradar al criterio viejo es mejor que fallar.

    ⚠️ **Lo que NO tiene senal va detras de todo lo que si la tiene**, ordenado por `rank` entre
    si. Sobre el pack ingles son el **95,5 %** de los lemas: la lista cubre 38.067 de 842.026.

    ⚠️ **Se devuelve un conjunto de `norm` y no de ids, a proposito.** Asi un homografo entra
    entero o no entra: quedarse con la mitad de `banco` seria perder una acepcion sin ningun aviso.

    ⚠️ **El presupuesto se estima, no se mide.** El archivo pesa mas que sus payloads --indices,
    FTS y la tabla de formas-- asi que se escala por la proporcion que tiene el pack de origen.
    Medido: el espanol pesa 7,2x sus payloads (lo domina `form`, 44,6 % del archivo) y el ingles
    3,8x (lo domina `entry`). Son dos formas distintas y por eso el factor sale del pack y no de
    una constante.
    """
    origen = sqlite3.connect("file:%s?mode=ro" % completo, uri=True)
    try:
        total_payload = origen.execute("SELECT sum(length(payload)) FROM entry").fetchone()[0] or 0
        if not total_payload:
            raise ValueError("%s no tiene payloads" % os.path.basename(completo))
        factor = os.path.getsize(completo) / total_payload
        tope = presupuesto_mb * 1048576 / factor
        filas = origen.execute(
            "SELECT norm, length(payload), rank FROM entry ORDER BY rank ASC, headword ASC"
        ).fetchall()
        if not frecuencias:
            vocabulario = set()
            acumulado = 0
            for norm, plen, _rank in filas:
                if acumulado + plen > tope and vocabulario:
                    break
                acumulado += plen
                vocabulario.add(norm)
            return vocabulario

        # ⚠️ **Por LEMA y no por fila**, y esa es la diferencia con el modo sin lista. Un
        # homografo entra entero (`banco` asiento y `banco` entidad), asi que lo que cuesta es la
        # suma de sus payloads; cobrar fila por fila mezclaba el orden de dos lemas distintos.
        coste = {}
        mejor_rank = {}
        for norm, plen, rank in filas:
            coste[norm] = coste.get(norm, 0) + plen
            if norm not in mejor_rank or rank < mejor_rank[norm]:
                mejor_rank[norm] = rank
        orden = sorted(
            coste,
            key=lambda n: (0, -frecuencias[n], n) if n in frecuencias else (1, mejor_rank[n], n),
        )
        vocabulario = set()
        acumulado = 0
        for norm in orden:
            if acumulado + coste[norm] > tope and vocabulario:
                break
            acumulado += coste[norm]
            vocabulario.add(norm)
        return vocabulario
    finally:
        origen.close()


def _meta_del_nivel(meta, tier, cobertura=None, lemas=None):
    """La meta de un nivel derivado: la del completo, mas lo que lo declara subconjunto."""
    if tier not in NIVELES:
        raise ValueError("nivel desconocido %r; los que hay son %s" % (tier, ", ".join(NIVELES)))
    salida = {k: v for k, v in meta.items() if k not in DERIVADAS}
    completo = salida["pack_id"]
    # ⚠️ **La identidad es IDIOMA + NIVEL, no las fuentes.** Antes era
    # `es-def-wikc-tat-freq-wn-wd`, o sea la lista de lo que se habia fusionado, y eso hace que
    # **anadir una fuente cambie la identidad**: el pack parece otro y la app no lo reconoce como
    # el que ya esta instalado. Las fuentes siguen declaradas en `sources`, que es donde se
    # consultan. Pedido: *«los packs son por idioma y en versiones»*.
    idioma = (salida.get("langs") or salida.get("lang_src") or "").split(",")[0].strip()
    salida["pack_id"] = "%s-%s" % (idioma, tier) if idioma else completo + "-" + tier
    # ⚠️ **La clave que hace que los dos puedan estar instalados sin trabajo de mas** (D-171): con
    # el completo presente, la app no le pregunta al nucleo. Es una afirmacion de CONTENIDO, y
    # derivando el nucleo del completo es verdadera por construccion.
    salida["subset_of"] = completo
    if cobertura is not None:
        # La metrica que justifica el corte, en el artefacto y no en un changelog. Dos decimales
        # porque la diferencia entre dos estrategias se juega en el primero.
        salida["corpus_coverage"] = "%.2f" % cobertura
    if lemas is not None:
        # La segunda metrica. Ver [_fraccion_de_lemas]: la primera satura y no puede justificar
        # un nivel grande.
        salida["lemma_coverage"] = "%.2f" % lemas
    # ⚠️ **Y `tier`, que dice lo mismo sin nombrar a nadie.** `subset_of` afirma *«soy parte de
    # ESE pack»* y sirve cuando el completo esta instalado; `tier` afirma *«soy un nucleo»*, que
    # es lo que hace falta para decidir sin conocer al otro. Se declaran los dos porque contestan
    # preguntas distintas, y los dos son ciertos por construccion al derivar.
    salida["tier"] = tier
    # ⚠️ **El nivel va en el NOMBRE**, pedido explicito: *«que esto si vaya marcado en el nombre,
    # por ejemplo español (core) o english (main)»*. Y con el token en ingles --`core`, `main`,
    # `full`-- y no traducido, porque es el identificador del nivel y tiene que leerse igual en
    # cualquier idioma de la interfaz.
    salida["name"] = "%s (%s)" % (salida.get("name", ""), tier)
    # El credito se mueve con el contenido (D-138): el nucleo distribuye las mismas definiciones,
    # asi que hereda las mismas fuentes. Y el corpus que ELIGIO las palabras se declara tambien,
    # porque `sources` contesta como se armo el pack -- aunque no se distribuya una sola frase suya.
    salida["sources"] = (salida.get("sources", "") +
                         "vocabulary\tTatoeba\thttps://tatoeba.org/\tCC BY 2.0 FR\t"
                         "https://creativecommons.org/licenses/by/2.0/fr/\n")
    return salida


def _agrupar(origen, tabla):
    """`entry_id -> [norm, ...]` para las entradas elegidas, en una sola pasada sobre la tabla."""
    salida = {}
    for entry_id, norm in origen.execute(
        "SELECT entry_id, norm FROM %s WHERE entry_id IN "
        "(SELECT id FROM entry WHERE norm IN (SELECT norm FROM vocab))" % tabla
    ):
        salida.setdefault(entry_id, []).append(norm)
    return salida


def derive(completo, salida, vocabulario, tier="core", cobertura=None, lemas=None):
    """Escribe en `salida` las entradas de `completo` cuyo lema este en `vocabulario`.

    `cobertura` es el porcentaje de tokens del corpus que el vocabulario cubre, y **se escribe en
    el artefacto**: un nivel que no declara su cobertura obliga a recalcularla para saber si el
    corte fue bueno, y nadie lo hace. `verify_pack.py` la recalcula y falla si no coincide.
    """
    origen = sqlite3.connect("file:%s?mode=ro" % completo, uri=True)
    meta = dict(origen.execute("SELECT key, value FROM meta"))
    diccionario = binascii.unhexlify(meta["payload_dict"])

    origen.execute("CREATE TEMP TABLE vocab(norm TEXT PRIMARY KEY)")
    origen.executemany("INSERT OR IGNORE INTO vocab VALUES (?)", ((p,) for p in vocabulario))

    filas = origen.execute(
        "SELECT id, uid, headword, pos, rank, payload FROM entry "
        "WHERE norm IN (SELECT norm FROM vocab) ORDER BY id"
    ).fetchall()
    if not filas:
        raise ValueError(
            "el vocabulario no coincide con una sola entrada de %s: un pack de cero entradas se "
            "abre sin error y no encuentra nada" % os.path.basename(completo)
        )

    # ⚠️ **Las formas se leen en UNA pasada y no una consulta por entrada, y la diferencia es
    # entre segundos y no terminar nunca.** `form` tiene PRIMARY KEY (norm, entry_id), asi que
    # filtrar por `entry_id` solo **no usa indice**: es un scan completo. Con 986.000 formas y
    # 16.652 entradas seleccionadas eso son 16 mil millones de filas visitadas. Se descubrio
    # esperando a que el pack ingles terminara, y no termino.
    formas_por_entrada = _agrupar(origen, "form")
    traducciones_por_entrada = _agrupar(origen, "trans")

    escritos = 0
    with build.PackBuilder(salida, _meta_del_nivel(meta, tier, cobertura, lemas)) as constructor:
        for entry_id, uid, headword, pos, rank, blob in filas:
            _pos_payload, senses, _palabra = payload_codec.parse(
                payload_codec.decompress(blob, diccionario))
            formas = formas_por_entrada.get(entry_id, ())
            traducciones = traducciones_por_entrada.get(entry_id, ())
            constructor.add(build.Record(
                headword=headword,
                senses=senses,
                part_of_speech=pos,
                rank=rank,
                forms=formas,
                translations=traducciones,
                # ⚠️ **El uid se COPIA y no se recalcula.** Es la identidad logica y la llave de
                # join entre packs (D-055): si el nucleo recalculara su `sense_key` contando SUS
                # homografos, una entrada con gemelo en el completo podria perderlo y quedarse con
                # otra identidad -- que es exactamente el fallo que D-145 encontro al fusionar.
                uid=uid,
            ))
            escritos += 1
    origen.close()
    return escritos


#: Cuantas veces se re-deriva buscando el rango. Cada vuelta es un pack escrito entero, asi que
#: el tope existe: converge en dos o tres porque el factor real se estabiliza en cuanto se mide
#: una vez, y seguir intentando cuesta mas de lo que afina.
MAX_VUELTAS = 4

#: A que altura del rango se apunta: **el medio**.
#:
#: ⚠️ **La razon archivo/presupuesto NO es monotona, y por eso ninguna constante la compensa.**
#: Medido sobre el pack español derivando a seis presupuestos:
#:
#:     presupuesto   archivo   razon
#:          10 MB     6,0 MB    0,60
#:          20 MB    13,4 MB    0,67
#:          30 MB    27,4 MB    0,91
#:        37,5 MB    47,3 MB    1,26   <- el pico
#:          45 MB    51,2 MB    1,14
#:          60 MB    60,4 MB    1,01
#:
#: Sube hasta 1,26 y vuelve a bajar: la tabla `form` crece mas rapido que los payloads --12,98
#: flexiones por lema en español-- hasta que el vocabulario empieza a agotarse y la proporcion se
#: normaliza. Con ese comportamiento, **la unica garantia del rango es medir el archivo y volver**,
#: que es lo que hace [derivar_en_rango]. Apuntar al medio es lo que le deja margen de error en
#: las dos direcciones.
OBJETIVO_DEL_RANGO = 0.5


def derivar_en_rango(completo, salida, minimo_mb, maximo_mb, tier="core", frecuencias=None):
    """Deriva un nivel cuyo ARCHIVO cae dentro de `[minimo_mb, maximo_mb]`.

    ## Por que converge en vez de estimar una vez

    ⚠️ **El factor del pack de origen NO es el del derivado, y eso hacia que el rango no se
    cumpliera.** `vocabulario_por_presupuesto` escala los payloads por `tamaño / suma(payloads)`
    del **completo**; el derivado tiene otra proporcion --se lleva las formas de sus lemas pero
    no las de los demas, y los indices crecen distinto-- asi que pedir 25 MB daba **17,7**, por
    debajo del minimo del rango.

    Un nivel corto no esta mal por el tamaño: esta mal porque **el rango es el requisito** y el
    artefacto no lo cumple. Y el factor real solo se conoce **midiendo el archivo**, asi que se
    deriva, se mide, se corrige y se vuelve.

    ## Que devuelve

    Un informe con `en_rango`, `mb`, `vueltas` y, si no se pudo, `motivo`. ⚠️ **No poder no es un
    error**: un `full` mas chico que el minimo del nivel significa que ese nivel no tiene sentido
    para ese idioma, y eso se dice en vez de callarse -- si saliera callado, el rango dejaria de
    significar algo.
    """
    objetivo_mb = minimo_mb + (maximo_mb - minimo_mb) * OBJETIVO_DEL_RANGO
    presupuesto = objetivo_mb
    informe = {"en_rango": False, "mb": 0.0, "vueltas": 0, "motivo": ""}
    for vuelta in range(1, MAX_VUELTAS + 1):
        vocabulario = vocabulario_por_presupuesto(completo, presupuesto, frecuencias)
        cobertura = _frequency.cobertura(vocabulario, frecuencias) if frecuencias else None
        lemas = _fraccion_de_lemas(completo, vocabulario)
        derive(completo, salida, vocabulario, tier=tier, cobertura=cobertura, lemas=lemas)
        mb = os.path.getsize(salida) / 1048576
        informe.update(mb=mb, vueltas=vuelta, cobertura=cobertura, lemas=lemas,
                       entradas=len(vocabulario))
        if minimo_mb <= mb <= maximo_mb:
            informe["en_rango"] = True
            return informe
        if mb < minimo_mb and _es_el_pack_entero(completo, vocabulario):
            # No hay mas vocabulario que meter: el `full` no llega al minimo de este nivel.
            informe["motivo"] = ("todo el pack entra y pesa %.1f MB, por debajo del minimo de "
                                 "%.1f MB" % (mb, minimo_mb))
            return informe
        # El factor real se mide del archivo que acaba de salir. Es la unica forma de saberlo.
        presupuesto *= objetivo_mb / mb if mb else 2.0
    informe["motivo"] = "no convergio en %d vueltas (ultimo: %.1f MB)" % (MAX_VUELTAS, informe["mb"])
    return informe


def _es_el_pack_entero(completo, vocabulario):
    origen = sqlite3.connect("file:%s?mode=ro" % completo, uri=True)
    try:
        total = origen.execute("SELECT COUNT(DISTINCT norm) FROM entry").fetchone()[0]
    finally:
        origen.close()
    return len(vocabulario) >= total


def _fraccion_de_lemas(completo, vocabulario):
    """Que porcentaje de los lemas del pack completo se lleva este nivel.

    ⚠️ **Es la SEGUNDA metrica, y hace falta porque la primera satura.** `corpus_coverage` deja
    de moverse pasadas las ~50.000 palabras que la lista atestigua: medido, el ingles llega a su
    techo de 96,63 % en **57,6 MB**, asi que los 130 MB de `main` compran **cero** cobertura por
    esa vara. Lo que compran es encontrar lo raro -- la palabra que no esta en ningun corpus de
    subtitulos y que alguien igual va a buscar-- y eso es justamente lo que esta mide.

    No pretende ser una probabilidad: es una fraccion del diccionario, monotona y verificable.
    Una metrica ponderada por "que tan probable es que alguien la busque" necesitaria un corpus
    que hoy no existe, y decirlo es mejor que inventar un numero.
    """
    origen = sqlite3.connect("file:%s?mode=ro" % completo, uri=True)
    try:
        total = origen.execute("SELECT COUNT(DISTINCT norm) FROM entry").fetchone()[0]
    finally:
        origen.close()
    return 100.0 * len(vocabulario) / total if total else 0.0


def _frecuencias_de(argv):
    """La lista de frecuencias, o `None` avisando lo que eso cuesta.

    ⚠️ **Sin la lista el nivel sale medible pero PEOR**, y por eso se avisa en vez de callarlo.
    Medido: el corte por `rank` pierde entre 0,97 y 1,53 puntos de cobertura contra el corte por
    frecuencia, y ademas mete menos lemas. Ver [vocabulario_por_presupuesto].
    """
    if "--frecuencias" in argv:
        return frecuencias_por_norm(argv[argv.index("--frecuencias") + 1])
    print("  ⚠️  sin --frecuencias: se corta por rank, que cubre ~1,3 puntos menos",
          file=sys.stderr)
    return None


def main(argv):
    """Tres modos, y el de RANGO es el que usan los niveles de D-215.

        build_core.py <completo.db> <salida.db> --rango-mb 25 50 --tier core   # el del pipeline
        build_core.py <completo.db> <salida.db> --budget-mb 50 --tier core     # un techo, sin rango
        build_core.py <completo.db> <salida.db> <corpus.tsv> [--top N]         # el modo original

    ⚠️ **`--budget-mb` sigue existiendo para explorar la curva, no para publicar.** Estima una
    sola vez escalando los payloads por la proporcion del pack de ORIGEN, que no es la del
    derivado: pedir 25 MB dio **17,7**. Medir seis presupuestos con el sale barato y es asi como
    se validaron los rangos; construir un nivel publicable con el no garantiza nada.
    """
    if len(argv) < 3:
        sys.stderr.write(__doc__)
        return 2
    completo, salida = argv[1], argv[2]
    tier = argv[argv.index("--tier") + 1] if "--tier" in argv else "core"

    if "--rango-mb" in argv:
        i = argv.index("--rango-mb")
        minimo, maximo = float(argv[i + 1]), float(argv[i + 2])
        informe = derivar_en_rango(completo, salida, minimo, maximo, tier=tier,
                                   frecuencias=_frecuencias_de(argv))
        print("%s: %d entradas, %.1f MB (rango %g-%g MB, nivel %s, %d vuelta%s)%s"
              % (os.path.basename(salida), informe["entradas"], informe["mb"], minimo, maximo,
                 tier, informe["vueltas"], "" if informe["vueltas"] == 1 else "s",
                 "" if informe.get("cobertura") is None
                 else ", cubre %.2f %% del corpus y %.2f %% de los lemas"
                      % (informe["cobertura"], informe["lemas"])))
        if not informe["en_rango"]:
            # ⚠️ **Exit 1 y no un aviso.** El pipeline encadena sobre lo que el paso anterior
            # dejo, y salir 0 con un artefacto fuera de rango dice *esto cumple* de un pack que
            # no cumple -- que es exactamente la clase de fallo que este repo no puede ver.
            print("  ✗  fuera de rango: %s" % informe["motivo"], file=sys.stderr)
            return 1
        return 0

    if "--budget-mb" in argv:
        presupuesto = float(argv[argv.index("--budget-mb") + 1])
        # ⚠️ **Sin la lista el nivel sale medible pero PEOR**, y por eso se avisa. Medido: el
        # corte por `rank` pierde entre 0,97 y 1,53 puntos de cobertura contra el corte por
        # frecuencia, y ademas mete menos lemas. Ver `vocabulario_por_presupuesto`.
        frecuencias = _frecuencias_de(argv)
        vocabulario = vocabulario_por_presupuesto(completo, presupuesto, frecuencias)
        cobertura = _frequency.cobertura(vocabulario, frecuencias) if frecuencias else None
        escritos = derive(completo, salida, vocabulario, tier=tier, cobertura=cobertura)
        real = os.path.getsize(salida) / 1048576
        print("%s: %d entradas, %.1f MB (presupuesto %.0f MB, nivel %s)%s"
              % (os.path.basename(salida), escritos, real, presupuesto, tier,
                 "" if cobertura is None else ", cubre %.2f %% del corpus" % cobertura))
        # ⚠️ El presupuesto se ESTIMA escalando los payloads, asi que el archivo real puede
        # pasarse. Se avisa en vez de callarlo: quien lo corre decide si baja el numero.
        if real > presupuesto * 1.1:
            print("  ⚠️  se paso un %.0f %% del presupuesto" % (100 * real / presupuesto - 100),
                  file=sys.stderr)
        return 0

    if len(argv) < 4:
        sys.stderr.write(__doc__)
        return 2
    corpus = argv[3]
    top = TOP_POR_DEFECTO
    if "--top" in argv:
        top = int(argv[argv.index("--top") + 1])

    con = sqlite3.connect("file:%s?mode=ro" % completo, uri=True)
    lang = con.execute("SELECT value FROM meta WHERE key='langs'").fetchone()[0].split(",")[0].strip()
    con.close()
    # Tatoeba usa ISO 639-3 en su columna de idioma; el pack usa 639-1.
    lang_corpus = {"es": "spa", "en": "eng"}.get(lang, lang)

    vocabulario = vocabulario_del_corpus(corpus, lang_corpus, top)
    escritos = derive(completo, salida, vocabulario, tier=tier)
    print("%s: %d entradas de las %d palabras mas usadas (%.1f MB)"
          % (os.path.basename(salida), escritos, top,
             os.path.getsize(salida) / 1048576))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
