"""Verifica las invariantes de un pack construido.

    python3 verify_pack.py ruta/al/pack.db

Corre sobre el pack final, no sobre el builder: chequea el artefacto que realmente se va a
descargar al reloj. Un pack a medio construir o con la normalizacion desfasada se abre sin
ningun error y devuelve menos resultados de los que corresponde, asi que estas comprobaciones
son el unico lugar donde ese problema se vuelve visible.

Sale con codigo 1 si algo falla.
"""

import os
import re
import sqlite3
import sys

import normalize
import payload as payload_codec

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

    print("\n[integridad referencial]")
    # SQLite no aplica claves foraneas aca (las tablas no las declaran, para no pagar el
    # chequeo en cada insert del builder), asi que se verifica explicitamente.
    for table in ("form", "trans"):
        orphans = db.execute(
            "SELECT COUNT(*) FROM %s t WHERE NOT EXISTS"
            " (SELECT 1 FROM entry e WHERE e.id = t.entry_id)" % table
        ).fetchone()[0]
        report.check(orphans == 0, "%s no tiene entry_id huerfanos" % table)
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
    for row in db.execute(
        "SELECT id, uid, headword, payload FROM entry ORDER BY id LIMIT ?", (PAYLOAD_SAMPLE,)
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


def main(argv):
    if len(argv) != 2:
        print(__doc__)
        return 2
    return verify(argv[1])


if __name__ == "__main__":
    sys.exit(main(sys.argv))
