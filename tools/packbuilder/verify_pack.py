"""Verifica las invariantes de un pack construido.

    python3 verify_pack.py ruta/al/pack.db
    python3 verify_pack.py --como-la-app pack.db [...]   # lo que la APP comprueba, y nada mas
    python3 verify_pack.py pack.db --frecuencias <lista>  # ademas, recalcula meta.corpus_coverage

Corre sobre el pack final, no sobre el builder: chequea el artefacto que realmente se va a
descargar al reloj. Un pack a medio construir o con la normalizacion desfasada se abre sin
ningun error y devuelve menos resultados de los que corresponde, asi que estas comprobaciones
son el unico lugar donde ese problema se vuelve visible.

`--como-la-app` contesta otra pregunta, y es la de antes de subir un pack al reloj: *si lo
instalo, ¿aparece?*. Corre **exactamente** las comprobaciones por las que `PackFile.open`
rechazaria el pack, en el mismo orden, e imprime el `PackRejection` que el usuario veria. Acepta
varios packs porque la pregunta natural es "¿pasan todos los que voy a subir?".

⚠️ **Es un espejo, el cuarto de este repo, y nace con enforcer**: `audit_dictionary.py` compara
sus motivos contra el enum `PackRejection` de Kotlin. Ver [MOTIVOS_DE_LA_APP].

Sale con codigo 1 si algo falla.
"""

import os
import re
import sqlite3
import sys

import normalize
import payload as payload_codec
from sources import frequency as _frequency

import build

# Techo de la parte de nombres propios en un pack 'lexical-only'. Ver el check de estructura.
PROPER_NOUN_SHARE_MAX = 0.05

# El rank mas bajo (= mas comun) que un nombre propio puede tener en un pack 'definitions-only'.
#
# Espeja `CASTIGO_NOMBRE_PROPIO` de sources/kaikki.py, y espejarlo es el punto: el builder aplica
# el castigo y esto comprueba el ARTEFACTO, que es lo unico que se publica. Ver el check.
RANK_MINIMO_NOMBRE_PROPIO = 1000

REQUIRED_META = (
    "attribution",
    "sources",
    "built_at",
    "data_version",
    "entry_count",
    "fuzzy_profile",
    "kind",
    "langs",
    "license",
    "name",
    "norm_version",
    "pack_id",
    "payload_codec",
    "payload_dict",
    "payload_dict_sha256",
    # Que politica de contenido se aplico (D-116). Va en REQUIRED_META y no solo en el codigo
    # porque el pack tiene que poder explicarse solo: sin esta clave nadie sabe si a un pack le
    # faltan los nombres propios porque se decidio, o porque la fuente venia rota.
    "proper_nouns",
    "schema_version",
    # Estaba documentada en docs/formato-pack.md y NO estaba exigida: un pack sin source_url
    # pasaba el validador y despues no habia como saber de que dump salio.
    "source_url",
    "uid_recipe",
)

# Los tres valores que `meta.proper_nouns` puede tomar. Se listan aca --y no se infieren del
# `if`-- porque el check estructural es OPCIONAL por diseño: "included" no comprueba nada, y sin
# esta lista un typo como "lexical_only" es indistinguible de "included". O sea que el pack se
# declara podado, el validador no cuenta un solo nombre propio, y sale verde.
# La gramatica del `pack_id`, que es el CODIGO del pack (D-138).
#
#     <idioma>-<tipo>-<fuente>[-<variante>]*
#     es-def-wikc            español, definiciones, del Wikcionario
#     es-def-wikc-tat        el mismo, con frases de Tatoeba
#     en-def-wikt            ingles, definiciones, del Wiktionary
#
# ⚠️ **Existe por la colision, no por prolijidad.** Desde D-136 dos packs del mismo idioma y de
# fuentes distintas se instalan y se consultan juntos: comparten idioma y tipo, y **lo unico que
# los separa es el codigo de fuente**. Con un `pack_id` generico --"espanol", "dict"-- uno pisa
# al otro al instalarse, y el historial y las guardadas del reloj quedan apuntando a entradas de
# un pack que ya no esta. Es un fallo que no lanza: el pack que quedo abre y funciona.
#
# El idioma son dos letras (ISO 639-1); el tipo es `def` o `tr`; la fuente y las variantes salen
# del catalogo de `build_pack.FUENTES` y de las opciones de la CLI.
#: Las dos formas que puede tener un `pack_id`, y son dos por una razon.
#:
#: ⚠️ **`<idioma>-<nivel>` es la forma NUEVA (D-215)**, y existe porque la vieja ataba la identidad
#: a las fuentes: `es-def-wikc-tat-freq-wn-wd` cambia de id **al anadir una fuente**, y entonces el
#: pack parece otro y la app no lo reconoce como el que ya esta instalado. Las fuentes siguen
#: declaradas en `meta.sources`, que es donde se consultan.
#:
#: La forma vieja `<idioma>-<tipo>-<fuente>` se sigue aceptando porque el pack **bilingue** no
#: tiene niveles --su proposito es otro-- y porque los packs ya construidos la usan.
NIVELES_DE_PACK = ("core", "main", "full")
GRAMATICA_DE_PACK_ID = re.compile(
    r"^[a-z]{2}-(?:(?:core|main|full)|(?:def|tr)-[a-z0-9]{2,8}(?:-[a-z0-9]{1,16})*)$"
)

# Cuantos campos tiene una fila de `meta.sources`. Espeja PackSource.CAMPOS en Kotlin.
CAMPOS_DE_FUENTE = 5

POLITICAS_DE_NOMBRES_PROPIOS = ("excluded", "lexical-only", "definitions-only",
                                "included")

REQUIRED_INDEXES = ("idx_entry_norm", "idx_entry_fuzzy")

# Cuantas entradas se descomprimen para comprobar los payloads. Descomprimir el pack completo
# en un diccionario real tomaria minutos; una muestra al azar detecta lo mismo.
PAYLOAD_SAMPLE = 200

#: La lista con la que comprobar `meta.corpus_coverage`, o None. La pone `main` desde la CLI.
FRECUENCIAS_PARA_VERIFICAR = None

# Los tags que el formato define hoy. Se listan y no se derivan de `dir(payload_codec)` para que
# agregar uno sea un acto explicito: un tag nuevo tiene que entrar aca **y** en el espejo Kotlin.
TAGS_CONOCIDOS = frozenset((
    payload_codec.TAG_PART_OF_SPEECH,
    payload_codec.TAG_SENSE,
    payload_codec.TAG_EXAMPLE,
    payload_codec.TAG_CITATION,
    payload_codec.TAG_TRANSLATION,
    payload_codec.TAG_SYNONYM,
    payload_codec.TAG_ANTONYM,
    payload_codec.TAG_RELATED,
    payload_codec.TAG_WORD_TRANSLATION,
))


class Report:
    def __init__(self):
        self.failures = []
        self.notes = []

    def check(self, condition, message):
        if condition:
            print("  ok    %s" % message)
        else:
            print("  FALLA %s" % message)
            self.failures.append(message)

    def note(self, message):
        print("  --    %s" % message)
        self.notes.append(message)


def verify(path):
    report = Report()
    db = sqlite3.connect("file:%s?mode=ro" % path, uri=True)
    db.row_factory = sqlite3.Row

    print("pack: %s (%.1f KB)" % (path, os.path.getsize(path) / 1024.0))

    meta = {row["key"]: row["value"] for row in db.execute("SELECT key, value FROM meta")}

    print("\n[meta]")
    missing = [key for key in REQUIRED_META if key not in meta]
    report.check(not missing, "estan todas las claves obligatorias%s" % (
        "" if not missing else " (faltan: %s)" % ", ".join(missing)))
    # La app parsea estas tres como enteros (`PackFile.parseMetadata`). Un valor que no lo sea
    # NO falla aca ni al construir: falla al ABRIR el pack, en el reloj, con un
    # NumberFormatException que no nombra la clave. Paso de verdad: el primer pack real se
    # construyo con data_version = "2026-09-15" y este archivo dio verde.
    for key in ("schema_version", "norm_version", "data_version"):
        valor = meta.get(key, "")
        report.check(
            valor.lstrip("-").isdigit(),
            "meta.%s es un entero (la app le hace toInt()): %r" % (key, valor),
        )
    report.check(
        meta.get("schema_version") == str(build.SCHEMA_VERSION),
        "schema_version es %d" % build.SCHEMA_VERSION,
    )
    report.check(
        meta.get("norm_version") == str(normalize.NORM_VERSION),
        "norm_version coincide con normalize.py (%d)" % normalize.NORM_VERSION,
    )
    report.check(
        meta.get("payload_codec") == payload_codec.CODEC_ID,
        "payload_codec es %s" % payload_codec.CODEC_ID,
    )
    report.check(
        meta.get("fuzzy_profile") in normalize.FUZZY_PROFILES,
        "fuzzy_profile es un perfil conocido (%s)" % meta.get("fuzzy_profile"),
    )
    declarados = [x.strip() for x in meta.get("langs", "").split(",") if x.strip()]
    perfiles = [x.strip() for x in meta.get("fuzzy_profiles", "").split(",") if x.strip()]
    report.check(
        len(perfiles) == len(declarados) and all(p in normalize.FUZZY_PROFILES for p in perfiles),
        "meta.fuzzy_profiles trae un perfil conocido por cada idioma (%r para %r)"
        % (perfiles, declarados),
    )
    report.check(
        meta.get("tier") in NIVELES_DE_PACK,
        "meta.tier declara que clase de pack es (%r)" % meta.get("tier"),
    )
    # ⚠️ **Ningun `entry.lang` puede quedar fuera de lo declarado.** Es la invariante que hace
    # util la columna: la app filtra por idioma con ella, asi que una entrada con un idioma que
    # el pack no declara **no aparece nunca** -- sin error, sin log, y con el pack pasando todo
    # lo demas. Es la misma clase de falla que `norm()`.
    usados = {row[0] for row in db.execute("SELECT DISTINCT lang FROM entry")}
    report.check(
        usados <= set(declarados),
        "todo entry.lang esta en meta.langs (usa %r, declara %r)"
        % (sorted(usados), declarados),
    )
    if meta.get("kind") == "bilingual":
        # ⚠️ Un pack bilingue declara DOS idiomas en `meta.langs`, como pares. Ya no hay
        # `lang_dst`: no hay un idioma principal y otro destino, hay dos.
        report.check(
            len(declarados) == 2,
            "un pack bilingue declara sus DOS idiomas en meta.langs (%r)" % declarados,
        )
        # ⚠️ **Y tiene entradas de los dos, que es lo que lo vuelve bidireccional POR
        # CONSTRUCCION.** Sin esto un pack puede declararse bilingue con el otro idioma vacio,
        # que es exactamente lo que era antes: `dog` encontraba `perro` por `trans` pero `dog`
        # no era un lema, y nada en el artefacto lo decia.
        report.check(
            usados == set(declarados),
            "un pack bilingue tiene ENTRADAS de sus dos idiomas (tiene %r de %r)"
            % (sorted(usados), declarados),
        )

    print("\n[estructura]")
    indexes = {row["name"] for row in db.execute(
        "SELECT name FROM sqlite_master WHERE type='index' AND name NOT LIKE 'sqlite_%'")}
    for name in REQUIRED_INDEXES:
        report.check(name in indexes, "existe el indice %s" % name)
    report.check(
        not db.execute("SELECT 1 FROM sqlite_master WHERE name='staging'").fetchone(),
        "no quedo la tabla de staging",
    )

    # La politica de contenido se comprueba contra el ARTEFACTO, no contra el flag que la pidio.
    # Un flag mal cableado pasa los tests de la fuente y deja el pack con los nombres propios
    # adentro igual; lo unico que lo agarra es contar filas en el pack terminado.
    #
    # Los DOS vocabularios de `pos`: kaikki emite "name", sources/toy.py emite "proper noun".
    # Excluir uno solo deja pasar el otro, y ya paso una vez.
    pack_id = meta.get("pack_id") or ""
    report.check(
        bool(GRAMATICA_DE_PACK_ID.match(pack_id)),
        "meta.pack_id es un codigo y no un nombre generico (%r; forma <idioma>-<nivel> "
        "para los packs por idioma, o <idioma>-<tipo>-<fuente> para el bilingue)" % pack_id,
    )

    # El manifiesto: que aporto cada fuente y bajo que licencia. Sin esto el pack abre, busca y
    # funciona, y **no se puede saber si se puede redistribuir** -- que es justo lo que alguien
    # que recibe un .db de 68 MB necesita contestar sin preguntarle a nadie (D-138).
    filas = [f.split("\t") for f in (meta.get("sources") or "").strip().split("\n") if f.strip()]
    report.check(bool(filas), "meta.sources declara al menos una fuente")
    bien_formadas = [f for f in filas if len(f) == CAMPOS_DE_FUENTE]
    report.check(
        len(bien_formadas) == len(filas),
        "cada fuente de meta.sources trae sus %d campos (%d de %d mal formadas)"
        % (CAMPOS_DE_FUENTE, len(filas) - len(bien_formadas), len(filas)),
    )
    sin_nombre = [f for f in bien_formadas if not f[1].strip()]
    sin_licencia = [f for f in bien_formadas if not f[3].strip()]
    report.check(not sin_nombre, "cada fuente declara un nombre (%d sin nombre)" % len(sin_nombre))
    # Declarar la fuente y callar la licencia es PEOR que no declarar nada: parece completo.
    report.check(
        not sin_licencia,
        "cada fuente declara su licencia (%d sin licencia: %s)"
        % (len(sin_licencia), ", ".join(f[1] for f in sin_licencia) or "-"),
    )

    politica = meta.get("proper_nouns")
    report.check(
        politica in POLITICAS_DE_NOMBRES_PROPIOS,
        "meta.proper_nouns declara una politica conocida (%r)" % politica,
    )
    if politica == "definitions-only":
        # Esta politica deja entrar nombres propios a proposito, asi que el techo de proporcion
        # no aplica. Lo que la vuelve segura es otra cosa, y es lo que se comprueba: **que
        # ninguno pueda ganarle en rank a una palabra comun**. Si el castigo se cablea mal el
        # pack sale entero, abre sin error y devuelve el toponimo arriba -- el modo de falla que
        # D-116 midio 4.267 veces en ingles. No hay otra cosa que lo vea.
        sin_castigar = db.execute(
            "SELECT COUNT(*) FROM entry WHERE pos IN ('name', 'proper noun') AND rank < ?",
            (RANK_MINIMO_NOMBRE_PROPIO,),
        ).fetchone()[0]
        report.check(
            sin_castigar == 0,
            "meta.proper_nouns dice 'definitions-only' y todos los nombres propios tienen el "
            "rank castigado (%d sin castigar)" % sin_castigar,
        )
    if politica in ("excluded", "lexical-only"):
        propios = db.execute(
            "SELECT COUNT(*) FROM entry WHERE pos IN ('name', 'proper noun')"
        ).fetchone()[0]
        filas = db.execute("SELECT COUNT(*) FROM entry").fetchone()[0]
        if politica == "excluded":
            report.check(
                propios == 0,
                "meta.proper_nouns dice 'excluded' y no hay nombres propios (%d encontrados)"
                % propios,
            )
        else:
            # 'lexical-only' deja pasar los que tienen vida lexica: los meses, los paises, los
            # idiomas. Aca no se puede recalcular esa señal --no tenemos el dump-- asi que se
            # comprueba lo unico visible desde el pack: que sean una MINORIA. Medido: 0,2 % en
            # ingles y 0,6 % en español, contra 17-22 % en un pack sin podar. El margen es tan
            # ancho que el umbral no necesita calibracion fina; lo que caza es que la poda no
            # haya corrido en absoluto.
            share = propios / filas if filas else 0
            report.check(
                share < PROPER_NOUN_SHARE_MAX,
                "meta.proper_nouns dice 'lexical-only' y son una minoria "
                "(%d de %d, %.1f %%)" % (propios, filas, 100 * share),
            )

    entry_count = db.execute("SELECT COUNT(*) FROM entry").fetchone()[0]
    report.check(entry_count > 0, "hay entradas (%d)" % entry_count)
    report.check(
        meta.get("entry_count") == str(entry_count),
        "meta.entry_count coincide con las filas reales",
    )
    report.check(
        db.execute("SELECT COUNT(*) FROM fts_def").fetchone()[0] == entry_count,
        "fts_def tiene una fila por entrada",
    )

    # ⚠️ **La cobertura declarada se RECALCULA, o no vale nada.** Un nivel escribe en
    # `meta.corpus_coverage` que fraccion de los tokens del corpus tiene adentro, y ese numero es
    # lo unico que justifica su corte (D-219). Declararlo sin comprobarlo lo vuelve una intencion:
    # el artefacto se recorta mal y sigue diciendo que cubre el 96 %.
    #
    # Sin la lista NO se puede comprobar, y entonces se dice que no se comprobo en vez de callarlo.
    if "corpus_coverage" in meta:
        declarada = float(meta["corpus_coverage"])
        if FRECUENCIAS_PARA_VERIFICAR:
            crudas = _frequency.load(FRECUENCIAS_PARA_VERIFICAR)
            frec = _frequency.por_norm(crudas, normalize.norm)
            vocabulario = {row["norm"] for row in db.execute("SELECT DISTINCT norm FROM entry")}
            real = _frequency.cobertura(vocabulario, frec)
            report.check(
                abs(real - declarada) < 0.05,
                "meta.corpus_coverage dice %.2f %% y el pack cubre %.2f %%" % (declarada, real),
            )
        else:
            report.note("meta.corpus_coverage = %.2f %% (declarado; sin --frecuencias no se "
                        "comprueba)" % declarada)

    print("\n[cobertura de vocabulario]")
    _verify_vocabulary(db, meta, report)

    print("\n[integridad referencial]")
    # SQLite no aplica claves foraneas aca (las tablas no las declaran, para no pagar el
    # chequeo en cada insert del builder), asi que se verifica explicitamente.
    for table in ("form", "trans"):
        orphans = db.execute(
            "SELECT COUNT(*) FROM %s t WHERE NOT EXISTS"
            " (SELECT 1 FROM entry e WHERE e.id = t.entry_id)" % table
        ).fetchone()[0]
        report.check(orphans == 0, "%s no tiene entry_id huerfanos" % table)

    # ⚠️ **Las claves de `form` y `trans` no las miraba NADIE, y son el camino de entrada de
    # 1.499.895 palabras en el pack español.** D-142 recalcula una muestra de `entry`, pero la
    # tabla `form` es la que resuelve una flexion --`palpitaciones` -> `palpitacion`-- y una
    # clave suya construida con otras reglas es exactamente el modo de falla central del repo:
    # la palabra esta en el archivo y ninguna busqueda la alcanza.
    #
    # Se comprueba por **idempotencia** (`norm(k) == k`) y no recalculando desde la forma
    # original, porque la forma original no se guarda: la tabla es `(norm, entry_id)` y nada mas.
    # Eso detecta una clave plegada con otro Unicode, con otro casefold o sin NFD; no detecta una
    # clave que sea el `norm()` correcto de OTRA palabra. Acota, no elimina -- el mismo trato que
    # D-142 hizo con la muestra.
    #
    # Medido: **7,6 s** sobre las 1.309.880 claves distintas del pack español y **4,3 s** sobre
    # las 801.758 del ingles. Caro para un reloj y barato para un build de una hora, que es
    # justamente por que vive aca y no en `PackFile`.
    for table in ("form", "trans"):
        malas = []
        total_claves = 0
        for (clave,) in db.execute("SELECT DISTINCT norm FROM %s" % table):
            total_claves += 1
            if normalize.norm(clave) != clave:
                if len(malas) < 5:
                    malas.append(clave)
        report.check(
            not malas,
            "las %d claves distintas de %s son claves de norm() validas%s"
            % (total_claves, table,
               "" if not malas else " (mal: %s)" % ", ".join(repr(x) for x in malas)),
        )
    # `norm` vacio y `fuzzy` vacio NO son el mismo problema, y tratarlos igual hacia fallar el
    # primer pack real por dos entradas legitimas: "h" y "H", la letra. Sin `norm` la entrada es
    # inalcanzable por cualquier camino. Sin `fuzzy` solo queda fuera del nivel tolerante, que es
    # exactamente lo que corresponde a un lema de una letra muda.
    report.check(
        db.execute("SELECT COUNT(*) FROM entry WHERE norm = ''").fetchone()[0] == 0,
        "ninguna entrada tiene norm vacio",
    )
    sin_fuzzy = db.execute("SELECT COUNT(*) FROM entry WHERE fuzzy = ''").fetchone()[0]
    if sin_fuzzy:
        report.note(
            "%d entradas sin fuzzy: quedan fuera del nivel tolerante, se buscan por prefijo"
            % sin_fuzzy
        )

    print("\n[normalizacion: las columnas coinciden con normalize.py]")
    # La comprobacion mas importante del archivo. Si el pack se construyo con otra version de
    # normalize.py, las claves guardadas no son las que la app va a calcular y simplemente
    # faltarian palabras, sin ningun error.
    # Un perfil por idioma: en un pack bidireccional las entradas inglesas se pliegan con el
    # perfil ingles y las españolas con el español, en el mismo archivo.
    por_idioma = dict(zip(
        [x.strip() for x in meta.get("langs", "").split(",") if x.strip()],
        [x.strip() for x in meta.get("fuzzy_profiles", "").split(",") if x.strip()],
    ))
    mismatched_norm = 0
    mismatched_fuzzy = 0
    for row in db.execute("SELECT headword, norm, fuzzy, lang FROM entry"):
        if normalize.norm(row["headword"]) != row["norm"]:
            mismatched_norm += 1
        profile = por_idioma.get(row["lang"], "generic")
        if normalize.fuzzy(row["headword"], profile) != row["fuzzy"]:
            mismatched_fuzzy += 1
    report.check(mismatched_norm == 0, "entry.norm == norm(headword) en todas las filas")
    report.check(mismatched_fuzzy == 0, "entry.fuzzy == fuzzy(headword) en todas las filas")

    print("\n[identidad logica: entry.uid]")
    # entry.uid es la clave con la que un pack auxiliar le suma informacion a estas entradas
    # (D-055). Si se repite, el auxiliar apunta a dos entradas a la vez; si no coincide con la
    # receta, apunta a la equivocada. Ninguna de las dos cosas produce un error en el reloj.
    report.check(
        meta.get("uid_recipe") == build.UID_RECIPE,
        "meta.uid_recipe es %s" % build.UID_RECIPE,
    )
    report.check(
        db.execute("SELECT COUNT(*) FROM entry WHERE uid IS NULL OR uid <= 0").fetchone()[0] == 0,
        "ninguna entrada tiene uid nulo o no positivo",
    )
    distinct_uid = db.execute("SELECT COUNT(DISTINCT uid) FROM entry").fetchone()[0]
    report.check(distinct_uid == entry_count, "entry.uid es unico en las %d entradas" % entry_count)

    # Se recalcula la receta solo donde se puede: el sense_key que desambigua homografos no se
    # guarda en el pack, asi que las entradas que comparten (headword, pos) se saltean. En un
    # pack real son una minoria y el resto queda cubierto.
    lang = meta.get("langs", "").split(",")[0].strip()
    ambiguous = {
        row[0]
        for row in db.execute(
            "SELECT headword || '\x1f' || COALESCE(pos, '') FROM entry"
            " GROUP BY headword, pos HAVING COUNT(*) > 1"
        )
    }
    mismatched_uid = 0
    checked_uid = 0
    # ⚠️ **Con `entry.lang` y no con el idioma del pack.** En un pack bidireccional `casa` y
    # `house` viven en el mismo archivo y su uid lleva idiomas distintos -- que es justo lo que
    # hace que no puedan colisionar entre packs (D-055).
    for row in db.execute("SELECT headword, pos, uid, lang FROM entry"):
        if (row["headword"] + "\x1f" + (row["pos"] or "")) in ambiguous:
            continue
        checked_uid += 1
        if build.stable_uid(row["lang"], row["headword"], row["pos"]) != row["uid"]:
            mismatched_uid += 1
    report.check(
        mismatched_uid == 0,
        "entry.uid == stable_uid(headword, pos) en las %d filas sin homografo exacto" % checked_uid,
    )

    print("\n[payloads]")
    dictionary = bytes.fromhex(meta.get("payload_dict", ""))
    report.note("diccionario de compresion: %d bytes" % len(dictionary))
    # deflate no detecta un diccionario equivocado: descomprime sin error y devuelve texto
    # corrupto. Este hash es la unica defensa, y la app lo comprueba al abrir el pack.
    report.check(
        payload_codec.dictionary_digest(dictionary) == meta.get("payload_dict_sha256"),
        "meta.payload_dict_sha256 corresponde al diccionario guardado",
    )
    decoded = 0
    senses_total = 0
    failures_before = len(report.failures)
    # ⚠️ **Repartida a lo largo de la tabla, no las primeras 200.** Era `ORDER BY id LIMIT 200`,
    # que es exactamente lo que D-142 argumenta que no sirve: un pack correcto solo en sus
    # primeras filas --lo que pasa si alguien construyo la mitad con una version y la mitad con
    # otra-- pasaba entero. Y en un pack BIDIRECCIONAL es peor todavia: las entradas inversas
    # viven en la segunda mitad de la tabla (D-196), asi que la muestra vieja no miraba ni una.
    total_entradas = db.execute("SELECT COUNT(*) FROM entry").fetchone()[0]
    paso_muestra = max(1, total_entradas // PAYLOAD_SAMPLE)
    for row in db.execute(
        "SELECT id, uid, headword, payload FROM entry"
        " WHERE (id - 1) - ((id - 1) / ?) * ? = 0 ORDER BY id LIMIT ?",
        (paso_muestra, paso_muestra, PAYLOAD_SAMPLE),
    ):
        try:
            text = payload_codec.decompress(row["payload"], dictionary)
            _pos, senses, palabra = payload_codec.parse(text)
            # ⚠️ **Sin acepciones se acepta SOLO si trae traducciones de palabra**, y esa
            # excepcion es el lado inverso de un pack bidireccional: `dog` contesta *«como se
            # dice»* --`perro`, `can`-- y no *«que significa»*, que es trabajo del pack
            # monolingue ingles. Lo que sigue prohibido es una entrada VACIA: ocupa una fila,
            # aparece en la lista y al abrirla no hay nada.
            if not senses and not palabra:
                report.check(
                    False,
                    "la entrada %s no tiene ni acepciones ni traducciones" % row["headword"])
            # ⚠️ **Toda acepcion tiene que ser alcanzable por `(idioma, palabra, acepcion)`.**
            # El codigo sale de `(uid, glosa)`, asi que dos acepciones de la misma entrada con la
            # glosa identica comparten codigo y una queda **inalcanzable** -- un enlace escrito
            # contra ella lleva a la otra, sin error y sin log. `payload.merge_duplicate_senses`
            # lo impide al construir; esto lo comprueba sobre los bytes, que es lo unico que vale
            # para un pack que no construimos nosotros.
            # ⚠️ **Sobre los BYTES y no sobre `senses`, y ahi esta el valor.** `payload.parse`
            # ya descarta la cita huerfana --degradacion correcta para el lector-- asi que
            # mirar la estructura parseada no puede ver el problema nunca. Un pack construido
            # por otro con la cita desplazada mostraria un ejemplo sin la atribucion que el pack
            # dice traer, o peor, se la colgaria al ejemplo equivocado si alguien relaja la
            # regla. Es el primer chequeo de CONTENIDO del payload que este validador tiene.
            huerfanas = _citas_huerfanas(text)
            if huerfanas:
                report.check(False,
                             "la entrada %s tiene %d cita(s) que no siguen a un ejemplo"
                             % (row["headword"], huerfanas))
            # ⚠️ **Ninguna lista repite un item** (D-218). Lo encontro barrer los packs
            # construidos: el 3,1 % de las entradas con traducciones de palabra del bilingue
            # repetian un termino --`where` traia `donde, donde`-- y en una fila de reloj eso es
            # la misma palabra dos veces, en un ancho que ya se corta. `payload.render` lo
            # deduplica al construir; esto lo comprueba sobre los BYTES, que es lo unico que
            # vale para un pack que no construimos nosotros.
            repetidas = []
            for sense in senses:
                for campo in ("examples", "translations", "synonyms", "antonyms", "related"):
                    items = [payload_codec.example_text(x) if campo == "examples" else x
                             for x in sense[campo]]
                    if len(items) != len(set(items)):
                        repetidas.append((row["headword"], campo))
            palabras = [x for x in palabra]
            if len(palabras) != len(set(palabras)):
                repetidas.append((row["headword"], "word_translations"))
            if repetidas:
                report.check(False,
                             "la entrada %s repite items en %s"
                             % (repetidas[0][0], ", ".join(c for _, c in repetidas[:4])))

            # ⚠️ **Ningun tag desconocido.** El lector los ignora a proposito --es lo que deja
            # agregar un campo sin romper una app vieja (D-119)-- y por eso mismo un tag que el
            # builder escribio mal es invisible: no lanza, no loguea, y su contenido no se ve
            # nunca. Aca es el unico lugar donde se puede notar.
            for linea in text.split("\n"):
                if len(linea) >= 2 and linea[1] == "\t" and linea[0] not in TAGS_CONOCIDOS:
                    report.check(False,
                                 "la entrada %s trae un tag desconocido: %r"
                                 % (row["headword"], linea[0]))
                    break

            codigos = {payload_codec.sense_code(row["uid"], s["gloss"]) for s in senses}
            if len(codigos) != len(senses):
                report.check(False,
                             "la entrada %s tiene acepciones que comparten codigo: %d acepciones, "
                             "%d codigos" % (row["headword"], len(senses), len(codigos)))
            senses_total += len(senses)
            decoded += 1
        except Exception as error:  # noqa: BLE001 - se reporta, no se propaga
            report.check(False, "no se pudo decodificar %s: %r" % (row["headword"], error))
    report.check(
        len(report.failures) == failures_before,
        "se decodificaron %d payloads (%.1f acepciones por entrada)"
        % (decoded, senses_total / max(decoded, 1)),
    )

    print("\n[planes de consulta]")
    _verify_query_plans(db, report)

    print("\n[caminos de busqueda]")
    _verify_search_paths(db, report, profile)

    print("\n[tamanos]")
    for name, size in _section_sizes(db):
        report.note("%-16s %8.1f KB" % (name, size / 1024.0))

    db.close()

    print("")
    if report.failures:
        print("FALLARON %d comprobaciones" % len(report.failures))
        return 1
    print("todas las comprobaciones pasaron")
    return 0


def _citas_huerfanas(text):
    """Cuantas lineas `C` del cuerpo no vienen inmediatamente despues de su `E`.

    Espejo exacto de la regla de `payload.parse` y de `PayloadCodec.parse`. Si los tres se
    separaran, el mismo pack mostraria atribuciones distintas segun quien lo lea, y este
    validador diria que esta bien.
    """
    huerfanas = 0
    anterior = None
    for line in text.split("\n"):
        if len(line) < 2 or line[1] != "\t":
            continue
        if line[0] == payload_codec.TAG_CITATION and anterior != payload_codec.TAG_EXAMPLE:
            huerfanas += 1
        anterior = line[0]
    return huerfanas



#: Donde viven las listas de palabras que un pack TIENE que encontrar.
VECTORES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "vectors")


def lista_de_cobertura(lang):
    """Las palabras que `lang` exige, o None si ese idioma no declara ninguna."""
    ruta = os.path.join(VECTORES, "cobertura-%s.txt" % lang)
    if not os.path.exists(ruta):
        return None
    palabras = []
    with open(ruta, encoding="utf-8") as handle:
        for linea in handle:
            linea = linea.strip()
            if linea and not linea.startswith("#"):
                palabras.append(linea)
    return palabras


def _verify_vocabulary(db, meta, report):
    """Que las palabras que el idioma exige se puedan encontrar.

    ⚠️ **Es lo unico que pregunta si el CONTENIDO sirve**, y por eso existe. Todo lo demas de
    este archivo comprueba invariantes: que los indices esten, que `fts_def.rowid` sea
    `entry.id`, que `norm` coincida. Nada de eso sabe **que palabras deberia tener un
    diccionario**, y por eso dos fallas reales pasaron con el gate en verde, `verify_pack.py` en
    verde y los tests en verde: el 39,6 % de las entradas del pack español no definia nada, y la
    poda por `pos = name` borraba **6 de los 12 meses en ingles**.

    ⚠️ **Se corre solo si existe la lista del idioma, y eso es a proposito**: una bandera se
    olvida justo la vez que importa. Un idioma sin lista se reporta como tal en vez de pasar en
    silencio, porque "no hay lista" y "la lista pasa" no son lo mismo.

    ⚠️ **Una palabra cuenta si es lema O forma flexionada.** `fui` llega a `ir` por la tabla
    `form`, y eso es exactamente lo que el usuario experimenta al buscarla: exigir que sea lema
    convertiria el chequeo en uno sobre la lematizacion de la fuente, que es otra cosa.
    """
    idiomas = [x.strip() for x in (meta.get("langs") or "").split(",") if x.strip()]
    for lang in idiomas:
        palabras = lista_de_cobertura(lang)
        if palabras is None:
            report.note("no hay vectors/cobertura-%s.txt: el contenido de ese idioma no se "
                        "comprueba" % lang)
            continue
        # ⚠️ **Un pack con menos entradas que palabras tiene la lista no es un diccionario**, es
        # un fixture -- el de juguete tiene 82 entradas--, y sobre el este chequeo mediria tamaño
        # y no cobertura. La regla se escala sola con la lista en vez de fijar un numero.
        entradas = db.execute("SELECT COUNT(*) FROM entry").fetchone()[0]
        if entradas < len(palabras):
            report.note("%s: %d entradas para una lista de %d palabras; es un fixture, no se "
                        "comprueba la cobertura" % (lang, entradas, len(palabras)))
            continue
        faltan = []
        for palabra in palabras:
            clave = normalize.norm(palabra)
            hay = db.execute(
                "SELECT 1 FROM entry WHERE norm = ? UNION ALL"
                " SELECT 1 FROM form WHERE norm = ? LIMIT 1", (clave, clave)
            ).fetchone()
            if not hay:
                faltan.append(palabra)
        report.check(
            not faltan,
            "las %d palabras que exige %s estan en el pack%s" % (
                len(palabras), lang,
                "" if not faltan else " (faltan %d: %s)" % (
                    len(faltan), ", ".join(faltan[:12]) + (" ..." if len(faltan) > 12 else ""))),
        )


def _verify_query_plans(db, report):
    """Confirma que la busqueda por prefijo usa el indice de cobertura.

    Es la afirmacion central del diseno: si el plan cambia a un scan de tabla, la busqueda
    incremental deja de cumplir el presupuesto de latencia y nada mas lo notaria.
    """
    plan = " ".join(
        row[-1]
        for row in db.execute(
            "EXPLAIN QUERY PLAN SELECT id, headword, pos FROM entry"
            " WHERE norm >= ? AND norm < ? ORDER BY norm, rank LIMIT 30",
            ("cor", "cos"),
        )
    )
    report.check(
        "COVERING INDEX idx_entry_norm" in plan,
        "el prefijo usa el indice de cobertura (plan: %s)" % plan,
    )
    report.check("SCAN entry" not in plan, "el prefijo no escanea la tabla entry")

    plan = " ".join(
        row[-1]
        for row in db.execute(
            "EXPLAIN QUERY PLAN SELECT id, norm FROM entry"
            " WHERE fuzzy >= ? AND fuzzy < ? LIMIT 200",
            ("kor", "kos"),
        )
    )
    report.check(
        "idx_entry_fuzzy" in plan, "el nivel tolerante usa idx_entry_fuzzy (plan: %s)" % plan
    )


def _verify_search_paths(db, report, profile):
    """Ejercita los cinco caminos de busqueda sobre el pack real.

    Estas son las consultas de referencia que implementa :dict-data. Dos detalles que se
    descubrieron aca y que son faciles de escribir mal:

      - La inversa necesita DISTINCT por entrada: el rango de prefijo matchea varias claves de
        la misma entrada ("to", "to run", "to pass") y sin deduplicar sale repetida.
      - El nivel tolerante consulta por un PREFIJO de la clave fuzzy, no por la clave completa,
        para traer un vecindario y no solo las colisiones exactas.
    """
    first = db.execute("SELECT headword FROM entry ORDER BY rank LIMIT 1").fetchone()
    if first is None:
        report.check(False, "el pack esta vacio")
        return
    headword = first["headword"]
    key = normalize.norm(headword)
    prefix = key[:3] if len(key) >= 3 else key

    found = db.execute(
        "SELECT COUNT(*) FROM (SELECT id FROM entry WHERE norm >= ? AND norm < ?"
        " ORDER BY norm, rank LIMIT 30)",
        (prefix, _upper_bound(prefix)),
    ).fetchone()[0]
    report.check(found > 0, "prefijo %r encuentra resultados (%d)" % (prefix, found))

    form = db.execute("SELECT norm FROM form LIMIT 1").fetchone()
    if form is None:
        report.note("el pack no trae formas flexionadas")
    else:
        found = db.execute(
            "SELECT COUNT(*) FROM form f JOIN entry e ON e.id = f.entry_id WHERE f.norm = ?",
            (form["norm"],),
        ).fetchone()[0]
        report.check(found > 0, "la forma flexionada %r llega a su lema" % form["norm"])

    translation = db.execute("SELECT norm FROM trans LIMIT 1").fetchone()
    if translation is None:
        report.note("el pack no trae traducciones (monolingue sin indice inverso)")
    else:
        word = translation["norm"]
        rows = db.execute(
            "SELECT e.id FROM entry e WHERE e.id IN"
            " (SELECT entry_id FROM trans WHERE norm >= ? AND norm < ?)"
            " ORDER BY e.rank LIMIT 20",
            (word, _upper_bound(word)),
        ).fetchall()
        ids = [row["id"] for row in rows]
        report.check(len(ids) > 0, "la traduccion %r llega a alguna entrada" % word)
        report.check(len(ids) == len(set(ids)), "la inversa no devuelve entradas repetidas")

    fuzzy_key = normalize.fuzzy(headword, profile)
    fuzzy_prefix = fuzzy_key[:4] if len(fuzzy_key) >= 4 else fuzzy_key
    found = db.execute(
        "SELECT COUNT(*) FROM (SELECT id FROM entry WHERE fuzzy >= ? AND fuzzy < ? LIMIT 200)",
        (fuzzy_prefix, _upper_bound(fuzzy_prefix)),
    ).fetchone()[0]
    report.check(
        found > 0,
        "el vecindario tolerante de %r trae candidatos (%d)" % (fuzzy_prefix, found),
    )

    # Una palabra que exista en el texto indexado, tomada del propio pack.
    sample = db.execute("SELECT id, payload FROM entry ORDER BY rank LIMIT 1").fetchone()
    dictionary = bytes.fromhex(
        db.execute("SELECT value FROM meta WHERE key='payload_dict'").fetchone()[0]
    )
    _pos, senses, _palabra = payload_codec.parse(payload_codec.decompress(sample["payload"], dictionary))
    words = [w for w in normalize.norm(senses[0]["gloss"]).split(" ") if len(w) > 3]
    if not words:
        report.note("no se encontro una palabra utilizable para probar FTS")
    else:
        term = words[0]
        # SIN LIMIT, y eso es el punto de la comprobacion. La invariante es que FTS **encuentre**
        # la entrada, no que la rankee alto: bm25 castiga las glosas largas, asi que una entrada
        # correcta con una definicion extensa y un termino muy frecuente queda fuera del top 30.
        # Paso con el pack de ingles --"you" por "people", posicion 721 de 890-- y la
        # comprobacion fallaba por un pack sano.
        #
        # El modo de falla real sigue cubierto: si `fts_def.rowid` se desalineara de `entry.id`
        # (D-011), la entrada no apareceria en NINGUNA posicion.
        rows = db.execute(
            "SELECT rowid FROM fts_def WHERE fts_def MATCH ?",
            ('"%s"' % term,),
        ).fetchall()
        report.check(
            sample["id"] in [row["rowid"] for row in rows],
            "FTS encuentra la entrada por %r de su definicion (%d entradas la contienen)"
            % (term, len(rows)),
        )


def _upper_bound(prefix):
    """Espejo de PrefixRange.upperBound para las consultas de verificacion."""
    if not prefix:
        return None
    codepoints = [ord(ch) for ch in prefix]
    while codepoints:
        nxt = codepoints[-1] + 1
        if 0xD800 <= nxt <= 0xDFFF:
            nxt = 0xE000
        if nxt <= 0x10FFFF:
            return "".join(chr(cp) for cp in codepoints[:-1]) + chr(nxt)
        codepoints.pop()
    return None


def _section_sizes(db):
    """Bytes por tabla e indice, para ver donde se va el tamano del pack."""
    try:
        rows = db.execute(
            "SELECT name, SUM(pgsize) FROM dbstat GROUP BY name ORDER BY 2 DESC"
        ).fetchall()
        return [(row[0], row[1]) for row in rows]
    except sqlite3.OperationalError:
        # dbstat es un modulo opcional; si el sqlite local no lo trae no es un fallo del pack.
        return [("(dbstat no disponible en este sqlite)", 0)]


# ---------------------------------------------------------------------------------------------
# El modo espejo: "¿esta app rechazaria este pack, y con que motivo?"
# ---------------------------------------------------------------------------------------------
#
# ESTE BLOQUE TIENE UN ESPEJO:
#     dict-core/src/main/kotlin/cl/fadiaz/dictionary/core/Model.kt -> PackRejection
#     dict-core/src/main/kotlin/cl/fadiaz/dictionary/core/PackIntegrity.kt -> checkMeta
#     dict-data/src/main/kotlin/cl/fadiaz/dictionary/data/PackFile.kt -> open
#
# ⚠️ **Es el CUARTO contrato cruzado de este repo, y nace con enforcer porque los otros tres
# ensenaron que sin el se separan.** `tools/audit_dictionary.py` compara los ids de
# [MOTIVOS_DE_LA_APP] contra los de `PackRejection`, en el mismo orden: agregar un motivo en
# Kotlin sin agregarlo aca --o cambiar el orden de uno solo de los dos lados-- rompe el gate.
#
# ⚠️ **Y el ORDEN es parte del contrato, no una casualidad.** Todas las comprobaciones rechazan
# (D-217), asi que el orden no decide si un pack entra: decide **que motivo se reporta**, que es
# la unica linea que el usuario lee. Los siete packs de `schema_version` 3 del directorio de
# datos salian como "metadatos incompletos" en vez de "otra version del formato" justamente por
# tener el orden al reves.
#
# Lo que este modo NO es: un reemplazo de `verify()`. Aquel mira mucho mas --recalcula TODAS las
# claves, cruza `uid`, comprueba planes de consulta-- porque corre al construir y puede gastar
# segundos. Este contesta una sola pregunta, la que importa antes de subir un pack al reloj:
# *si lo instalo, ¿aparece?*

# Cuantas filas de `form`/`trans` mira el modo espejo buscando huerfanas.
# Espeja ORPHAN_SAMPLE_SIZE de PackFile.kt.
APP_ORPHAN_SAMPLE = 64

# Cuantas entradas recalcula el modo espejo. Espeja KEY_SAMPLE_SIZE de PackFile.kt.
APP_KEY_SAMPLE = 64

# Las claves que `PackIntegrity.REQUIRED_META` exige. **No es [REQUIRED_META]**, que es lo que
# este verificador pide de mas: la app no necesita `built_at` ni `proper_nouns` para abrir.
APP_REQUIRED_META = (
    "pack_id", "schema_version", "norm_version", "kind", "name", "langs",
    "fuzzy_profiles", "entry_count", "data_version", "license", "attribution",
    "payload_dict", "payload_dict_sha256", "payload_codec",
)


def _app_metadata(meta, db):
    """`METADATA`: sin estas claves no se puede ni decir que archivo es esto."""
    faltan = [k for k in APP_REQUIRED_META if k not in meta]
    if faltan:
        return "a meta le faltan claves obligatorias: %s" % ", ".join(faltan)
    for clave in ("norm_version", "entry_count"):
        if not meta[clave].strip().lstrip("-").isdigit():
            return "%s no es un numero: %r" % (clave, meta[clave])
    if not meta["data_version"].strip().lstrip("-").isdigit():
        return "data_version no es un numero: %r" % meta["data_version"]
    idiomas = [x.strip() for x in meta["langs"].split(",") if x.strip()]
    if not idiomas:
        return "meta.langs no declara ningun idioma"
    perfiles = [x.strip() for x in meta["fuzzy_profiles"].split(",") if x.strip()]
    if len(perfiles) != len(idiomas):
        return "meta.fuzzy_profiles trae %d perfiles para %d idiomas" % (
            len(perfiles), len(idiomas))
    return None


def _app_schema(meta, db):
    """`SCHEMA_VERSION`: se mira ANTES que las claves. Ver el ⚠️ del encabezado."""
    crudo = meta.get("schema_version", "").strip()
    if not crudo.lstrip("-").isdigit():
        return "schema_version ausente o ilegible: %r" % meta.get("schema_version")
    if int(crudo) != build.SCHEMA_VERSION:
        return "schema_version %s, esta app entiende %d" % (crudo, build.SCHEMA_VERSION)
    return None


def _app_norm(meta, db):
    if int(meta["norm_version"]) != normalize.NORM_VERSION:
        return "norm_version %s != %d: el pack esta indexado con otras reglas" % (
            meta["norm_version"], normalize.NORM_VERSION)
    return None


def _app_codec(meta, db):
    if meta.get("payload_codec") != payload_codec.CODEC_ID:
        return "payload_codec %r, esta app lee %r" % (
            meta.get("payload_codec"), payload_codec.CODEC_ID)
    return None


def _app_license(meta, db):
    """`LICENSE`: D-031 dice que la atribucion no es opcional, asi que no poder acreditar rechaza."""
    fuentes = [l for l in (meta.get("sources") or "").split("\n") if l.strip()]
    if not fuentes:
        return "meta.sources vacio: el pack no declara de donde sale su contenido (D-138)"
    sin = []
    for linea in fuentes:
        campos = linea.split("\t")
        if len(campos) < 4 or not campos[3].strip():
            sin.append(campos[1] if len(campos) > 1 and campos[1] else "(sin nombre)")
    if sin:
        return "fuentes sin licencia declarada: %s" % ", ".join(sin)
    return None


def _objetos(db):
    return {row["name"] for row in db.execute(
        "SELECT name FROM sqlite_master WHERE name IN "
        "('idx_entry_norm', 'idx_entry_fuzzy', 'staging')")}


def _app_index(meta, db):
    faltan = [n for n in ("idx_entry_norm", "idx_entry_fuzzy") if n not in _objetos(db)]
    if faltan:
        return "faltan indices: %s; la busqueda escanearia la tabla" % ", ".join(faltan)
    return None


def _app_staging(meta, db):
    if "staging" in _objetos(db):
        return "quedo la tabla de staging: el pack se construyo a medias"
    return None


def _app_count(meta, db):
    filas = db.execute("SELECT COUNT(*) FROM entry").fetchone()[0]
    if filas != int(meta["entry_count"]):
        return "meta.entry_count dice %s y hay %d filas: el archivo esta truncado" % (
            meta["entry_count"], filas)
    return None


def _app_fts(meta, db):
    filas = db.execute("SELECT COUNT(*) FROM entry").fetchone()[0]
    indexadas = db.execute("SELECT COUNT(*) FROM fts_def_docsize").fetchone()[0]
    if indexadas != filas:
        return "fts_def tiene %d filas para %d entradas (D-011)" % (indexadas, filas)
    return None


def _app_emptykey(meta, db):
    vacias = db.execute("SELECT COUNT(*) FROM entry WHERE norm = ''").fetchone()[0]
    if vacias:
        return "%d entradas con norm vacio: estan en el archivo y no se alcanzan" % vacias
    return None


def _app_orphan(meta, db):
    for tabla in ("form", "trans"):
        huerfanas = db.execute(
            "SELECT COUNT(*) FROM (SELECT entry_id FROM %s LIMIT ?) t "
            "WHERE NOT EXISTS (SELECT 1 FROM entry e WHERE e.id = t.entry_id)" % tabla,
            (APP_ORPHAN_SAMPLE,)).fetchone()[0]
        if huerfanas:
            return "%d filas de %s apuntan a entradas que no existen" % (huerfanas, tabla)
    return None


def _app_keys(meta, db):
    """`KEYS`: la muestra de 64 repartida de D-142, recalculada con el codigo del builder."""
    total = int(meta["entry_count"])
    if total <= 0:
        return None
    perfiles = [x.strip() for x in meta["fuzzy_profiles"].split(",") if x.strip()]
    idiomas = [x.strip() for x in meta["langs"].split(",") if x.strip()]
    por_idioma = dict(zip(idiomas, perfiles))
    paso = max(1, total // APP_KEY_SAMPLE)
    for i in range(APP_KEY_SAMPLE):
        fila = db.execute(
            "SELECT headword, norm, fuzzy, lang FROM entry WHERE id = ?", (1 + i * paso,)
        ).fetchone()
        if fila is None:
            continue
        esperado = normalize.norm(fila["headword"])
        if fila["norm"] != esperado:
            return "entry.norm no coincide en %r: el pack dice %r y se calcula %r" % (
                fila["headword"], fila["norm"], esperado)
        if fila["fuzzy"] is not None:
            perfil = por_idioma.get(fila["lang"], perfiles[0] if perfiles else "generic")
            espera_f = normalize.fuzzy(fila["headword"], perfil)
            if fila["fuzzy"] != espera_f:
                return "entry.fuzzy no coincide en %r: el pack dice %r y se calcula %r" % (
                    fila["headword"], fila["fuzzy"], espera_f)
    return None


def _app_dict(meta, db):
    try:
        diccionario = bytes.fromhex(meta["payload_dict"])
    except ValueError:
        return "payload_dict no es hexadecimal"
    if payload_codec.dictionary_digest(diccionario) != meta["payload_dict_sha256"]:
        return "payload_dict_sha256 no corresponde al diccionario guardado"
    return None


def _app_damaged(meta, db):
    """`DAMAGED` no se comprueba: es lo que queda cuando abrir el archivo lanza."""
    return None


# ⚠️ **El orden es el de `PackFile.open`, y el de `PackRejection`.** La auditoria compara los
# ids de esta tabla contra el enum, en orden. Ver el ⚠️ del encabezado del bloque.
MOTIVOS_DE_LA_APP = (
    ("metadata", _app_metadata),
    ("schema", _app_schema),
    ("norm", _app_norm),
    ("codec", _app_codec),
    ("license", _app_license),
    ("index", _app_index),
    ("staging", _app_staging),
    ("count", _app_count),
    ("fts", _app_fts),
    ("emptykey", _app_emptykey),
    ("orphan", _app_orphan),
    ("keys", _app_keys),
    ("dict", _app_dict),
    ("damaged", _app_damaged),
)

# El orden de EVALUACION no es el de declaracion: `PackIntegrity.checkMeta` mira el esquema antes
# que las claves obligatorias --es la clave que dice que otras claves tienen que existir-- y el
# enum se declara en el orden en que se leen los motivos, no en el que se evaluan.
ORDEN_DE_EVALUACION = (
    "schema", "metadata", "norm", "codec", "license", "index", "staging",
    "count", "fts", "emptykey", "orphan", "keys", "dict",
)


def como_la_app(path):
    """Corre sobre el pack lo que la app corre al abrirlo. 0 si lo aceptaria, 1 si no."""
    checkers = dict(MOTIVOS_DE_LA_APP)
    try:
        db = sqlite3.connect("file:%s?mode=ro" % path, uri=True)
        db.row_factory = sqlite3.Row
        meta = {row["key"]: row["value"] for row in db.execute("SELECT key, value FROM meta")}
    except Exception as error:  # noqa: BLE001 - se reporta, no se propaga
        print("%s: RECHAZADO damaged -- %r" % (os.path.basename(path), error))
        return 1
    for motivo in ORDEN_DE_EVALUACION:
        detalle = checkers[motivo](meta, db)
        if detalle:
            db.close()
            print("%s: RECHAZADO %s -- %s" % (os.path.basename(path), motivo, detalle))
            return 1
    entradas = db.execute("SELECT COUNT(*) FROM entry").fetchone()[0]
    db.close()
    print("%s: la app lo abriria (%d entradas)" % (os.path.basename(path), entradas))
    return 0


def main(argv):
    global FRECUENCIAS_PARA_VERIFICAR
    resto = list(argv[1:])
    if "--frecuencias" in resto:
        i = resto.index("--frecuencias")
        FRECUENCIAS_PARA_VERIFICAR = resto[i + 1]
        del resto[i:i + 2]
    argumentos = [a for a in resto if not a.startswith("--")]
    banderas = {a for a in resto if a.startswith("--")}
    desconocidas = banderas - {"--como-la-app"}
    if not argumentos or desconocidas:
        print(__doc__)
        return 2
    if "--como-la-app" in banderas:
        # Varios packs de una: la pregunta natural es "¿pasan TODOS los que voy a subir?".
        return max(como_la_app(p) for p in argumentos)
    if len(argumentos) != 1:
        print(__doc__)
        return 2
    return verify(argumentos[0])


if __name__ == "__main__":
    sys.exit(main(sys.argv))
