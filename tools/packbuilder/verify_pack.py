"""Verifica las invariantes de un pack construido.

    python3 verify_pack.py ruta/al/pack.db

Corre sobre el pack final, no sobre el builder: chequea el artefacto que realmente se va a
descargar al reloj. Un pack a medio construir o con la normalizacion desfasada se abre sin
ningun error y devuelve menos resultados de los que corresponde, asi que estas comprobaciones
son el unico lugar donde ese problema se vuelve visible.

Sale con codigo 1 si algo falla.
"""

import os
import sqlite3
import sys

import normalize
import payload as payload_codec

import build

# Techo de la parte de nombres propios en un pack 'lexical-only'. Ver el check de estructura.
PROPER_NOUN_SHARE_MAX = 0.05

REQUIRED_META = (
    "attribution",
    "built_at",
    "data_version",
    "entry_count",
    "fuzzy_profile",
    "kind",
    "lang_src",
    "license",
    "name",
    "norm_version",
    "pack_id",
    "payload_codec",
    "payload_dict",
    "payload_dict_sha256",
    # Que politica de contenido se aplico (D-111). Va en REQUIRED_META y no solo en el codigo
    # porque el pack tiene que poder explicarse solo: sin esta clave nadie sabe si a un pack le
    # faltan los nombres propios porque se decidio, o porque la fuente venia rota.
    "proper_nouns",
    "schema_version",
    # Estaba documentada en docs/formato-pack.md y NO estaba exigida: un pack sin source_url
    # pasaba el validador y despues no habia como saber de que dump salio.
    "source_url",
    "uid_recipe",
)

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
    if meta.get("kind") == "bilingual":
        report.check(meta.get("lang_dst"), "un pack bilingue declara lang_dst")

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
    politica = meta.get("proper_nouns")
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
    profile = meta.get("fuzzy_profile", "generic")
    mismatched_norm = 0
    mismatched_fuzzy = 0
    for row in db.execute("SELECT headword, norm, fuzzy FROM entry"):
        if normalize.norm(row["headword"]) != row["norm"]:
            mismatched_norm += 1
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
    lang = meta.get("lang_src", "")
    ambiguous = {
        row[0]
        for row in db.execute(
            "SELECT headword || '\x1f' || COALESCE(pos, '') FROM entry"
            " GROUP BY headword, pos HAVING COUNT(*) > 1"
        )
    }
    mismatched_uid = 0
    checked_uid = 0
    for row in db.execute("SELECT headword, pos, uid FROM entry"):
        if (row["headword"] + "\x1f" + (row["pos"] or "")) in ambiguous:
            continue
        checked_uid += 1
        if build.stable_uid(lang, row["headword"], row["pos"]) != row["uid"]:
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
        "SELECT id, headword, payload FROM entry ORDER BY id LIMIT ?", (PAYLOAD_SAMPLE,)
    ):
        try:
            text = payload_codec.decompress(row["payload"], dictionary)
            _pos, senses = payload_codec.parse(text)
            if not senses:
                report.check(False, "la entrada %s quedo sin acepciones" % row["headword"])
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
    _pos, senses = payload_codec.parse(payload_codec.decompress(sample["payload"], dictionary))
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
