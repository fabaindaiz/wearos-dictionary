"""Construye un pack de diccionario (.db) a partir de un flujo de registros.

Uso como libreria:

    from build import PackBuilder, Record
    with PackBuilder("es-en.db", metadata) as builder:
        for record in mi_fuente():
            builder.add(record)

El builder trabaja en dos pasadas sobre una tabla de staging dentro del mismo archivo, nunca
en memoria: las fuentes reales son de gigabytes (el JSONL de ingles de kaikki.org son 2.9 GB)
y no caben. La pasada 1 escribe los cuerpos sin comprimir y toma una muestra; con la muestra se
arma el diccionario de compresion compartido; la pasada 2 comprime y llena `entry` y `fts_def`.

Los indices se crean al final, sobre las tablas ya pobladas.
"""

import hashlib
import os
import random
import sqlite3
import time
import unicodedata

import normalize
import payload as payload_codec

SCHEMA_VERSION = 4

# Receta con la que se calcula entry.uid, la identidad LOGICA de una entrada (D-055).
#
# entry.id es identidad FISICA: el rowid local, que comparte fts_def y al que apuntan form y
# trans. Es barato justamente por ser secuencial, y **no sobrevive a reconstruir el pack**:
# una palabra nueva en el medio corre todos los ids siguientes.
#
# entry.uid es identidad logica: sobrevive al rebuild, y es por donde un pack auxiliar (sinonimos,
# traducciones) le suma informacion a la misma entrada de este pack.
#
# Lo calcula SOLO el builder. La app nunca lo recalcula: lo lee de la fila y lo usa como clave
# de lookup en el pack auxiliar. Por eso, a diferencia de norm()/fuzzy(), NO es un contrato
# espejado entre dos lenguajes y no puede divergir. Si alguna vez hiciera falta calcularlo en
# Kotlin, deja de ser cierto y vuelve la clase de bug que D-005 existe para evitar.
#
# Si la receta cambia, cambia este identificador: los packs auxiliares construidos con la
# anterior quedan huerfanos y tienen que poder detectarlo.
UID_RECIPE = "uid-v1"

# Tamano de la muestra con la que se arma el diccionario de compresion. Mas muestra no mejora
# mucho porque el diccionario tope es de 32 KB igual.
DICTIONARY_SAMPLE_SIZE = 4000

# Tope de entradas por clave de traduccion inversa.
#
# Las traducciones son frases ("to run", "all of a sudden") y se indexa cada palabra por
# separado, si no buscar "run" no encontraria nada. El efecto colateral es que las palabras
# funcionales ("to", "of", "a") terminan apuntando a decenas de miles de entradas: infla el
# indice y no le sirve a nadie.
#
# Se topea en vez de descartar la clave: buscar "to" sigue devolviendo algo util (los verbos
# mas frecuentes) en lugar de nada. Se conservan las de mejor rank.
TRANS_MAX_PER_KEY = 50


def stable_uid(lang, headword, pos, sense_key=None):
    """Identidad logica de una entrada: estable entre rebuilds y entre packs. Ver UID_RECIPE.

    Se calcula sobre el headword **crudo** (no sobre `norm`) a proposito: asi no depende de
    NORM_VERSION, y subir las reglas de normalizacion no invalida los packs auxiliares. Ademas
    distingue "arbol" de "árbol", que son dos entradas distintas aunque normalicen igual.

    `sense_key` desambigua homografos que comparten headword Y pos (distinta etimologia). La
    fuente lo entrega si lo tiene; si dos entradas quedan con la misma identidad, el build falla
    en vez de fundirlas.

    Devuelve 63 bits sin signo: entra en un INTEGER de SQLite y nunca es negativo.
    """
    material = "\x1f".join(
        (
            lang,
            # NFC fija la forma de composicion: dos fuentes pueden entregar "á" precompuesta o
            # descompuesta, y serian bytes distintos para la misma palabra.
            unicodedata.normalize("NFC", headword),
            (pos or "").strip().lower(),
            sense_key or "",
        )
    )
    return int.from_bytes(hashlib.sha256(material.encode("utf-8")).digest()[:8], "big") >> 1


# ⚠️ **Separador `,` y no `+`**: `meta` es TEXT y estas dos claves son listas. La coma es lo que
# ya usa `meta.sources`, asi que el lector del reloj no aprende una convencion nueva.
_SEPARADOR_DE_LISTA = ","


def _parse_langs(crudo):
    """Los idiomas del pack, en orden de declaracion. `"es,en"` -> `["es", "en"]`."""
    if not crudo:
        return []
    vistos = []
    for parte in crudo.split(_SEPARADOR_DE_LISTA):
        lang = parte.strip()
        # Repetir un idioma no es un error del usuario sino un bug del que arma la metadata:
        # duplicaria el perfil fuzzy y el idioma por defecto seguiria siendo el primero.
        if lang and lang not in vistos:
            vistos.append(lang)
    return vistos


def _parse_profiles(crudo, langs, unico=None):
    """`{idioma: perfil fuzzy}`.

    Acepta tres formas, de la mas explicita a la mas comoda:

    - `"es,en"` en `meta.fuzzy_profiles`, **posicional contra `langs`** -- es lo que escribe un
      pack bidireccional;
    - `fuzzy_profile=` o `meta.fuzzy_profile`, un solo perfil para todos los idiomas;
    - nada, y cae en `generic`.

    ⚠️ **Posicional y no un mapa `es=es`**: el perfil de un idioma no siempre se llama como el
    idioma --hay `generic`-- y un mapa obligaria a repetir la clave. Que las dos listas tengan
    el mismo largo lo comprueba esta funcion, que es donde un desajuste todavia es barato.
    """
    if crudo:
        perfiles = [p.strip() for p in crudo.split(_SEPARADOR_DE_LISTA)]
        if len(perfiles) != len(langs):
            raise ValueError(
                "meta.fuzzy_profiles tiene %d perfiles para %d idiomas: %r vs %r"
                % (len(perfiles), len(langs), perfiles, langs))
        return dict(zip(langs, perfiles))
    return {lang: (unico or "generic") for lang in langs}


class Record:
    """Una entrada lista para indexar, tal como la entrega una fuente.

    `forms` son las formas flexionadas que deben llevar a este lema (sin incluir el lema).
    `translations` son las palabras del idioma destino por las que se debe poder llegar.
    Ambas se normalizan aca: la fuente entrega texto crudo.

    `sense_key` solo hace falta cuando la fuente trae dos entradas con el mismo headword y el
    mismo pos (tipicamente, distinta etimologia): es lo que las separa en entry.uid. Ver
    stable_uid().
    """

    __slots__ = (
        "headword",
        "part_of_speech",
        "rank",
        "senses",
        "forms",
        "translations",
        "word_translations",
        "sense_key",
        "uid",
        "lang",
    )

    def __init__(
        self,
        headword,
        senses,
        part_of_speech=None,
        rank=0,
        forms=(),
        translations=(),
        word_translations=(),
        sense_key=None,
        uid=None,
        lang=None,
    ):
        self.headword = headword
        self.senses = senses
        # None = el idioma primario del pack. Solo una fuente bidireccional lo llena.
        self.lang = lang
        self.part_of_speech = part_of_speech
        self.rank = rank
        self.forms = forms
        self.translations = translations
        # Traducciones de la PALABRA, sin acepcion. Van al payload (tag `W`) y NO a `trans`
        # por si solas: `translations` es el canal de busqueda y lleva la union de las dos.
        self.word_translations = word_translations
        self.sense_key = sense_key
        self.uid = uid


def data_version(ahora=None):
    """La version del pack: AAAAMMDDHHMM, como entero en un string.

    Tres cosas a la vez, y ninguna se puede sacrificar:

    - **Ordena.** Un instalador tiene que poder decir cual de dos packs es mas nuevo comparando
      numeros, sin parsear fechas.
    - **Se lee.** `202609211432` es "21 de septiembre de 2026, 14:32" sin convertidor. Un epoch
      tambien ordenaria y nadie podria leerlo de un vistazo, que era justo lo pedido.
    - **Distingue dos builds del mismo dump.** Al minuto, que es de sobra: un build tarda
      minutos, asi que dos no caen nunca en el mismo.

    ⚠️ **No entra en un Int de 32 bits** (202609211432 > 2.147.483.647). La app lo parsea como
    `Long`; si alguien lo vuelve `Int`, el pack revienta al ABRIR en el reloj con un
    NumberFormatException que no nombra la clave. Hay un test que lo fija.
    """
    if ahora is None:
        t = time.gmtime()
        ahora = (t.tm_year, t.tm_mon, t.tm_mday, t.tm_hour, t.tm_min)
    return "%04d%02d%02d%02d%02d" % ahora


class PackBuilder:
    def __init__(self, path, metadata, fuzzy_profile=None, sentences=None,
                 thesaurus=None):
        """`metadata` son las claves de la tabla meta que aporta la fuente.

        El builder agrega por su cuenta las que son suyas (versiones, conteo, fecha) y falla si
        la fuente intenta declararlas: una version de esquema escrita a mano seria una forma
        silenciosa de romper la validacion del lado del reloj.

        `sentences` es el mapa `norm -> frase` de un corpus (D-137). Se resuelve en `finish()` y
        no aca **porque la condicion que lo vuelve seguro solo se puede evaluar al final**: que
        la palabra lleve a una sola entrada del pack. Ver `_frases_por_entrada`.

        `thesaurus` es el mapa `(lema, pos) -> {"synonyms": [...], "antonyms": [...]}` de WordNet
        (D-144). Tambien se resuelve en `finish()`, y por un motivo parecido: el filtro que saca
        las flexiones disfrazadas de sinonimo necesita la tabla `form` completa.
        """
        reserved = {
            "schema_version",
            "norm_version",
            "payload_codec",
            "payload_dict",
            "entry_count",
            "built_at",
            "uid_recipe",
            # ⚠️ **Derivado, y por eso reservado.** Era la fecha del dump escrita a mano, asi que
            # reconstruir el MISMO dump con otro builder daba el mismo numero y `devpack.py` --y
            # el instalador-- lo leian como "es el mismo pack": un pack mejor no se propagaba.
            # Lo que el valor escrito significaba se declara ahora en `source_date`.
            "data_version",
        }
        conflicts = reserved & set(metadata)
        if conflicts:
            raise ValueError("estas claves de meta las escribe el builder: %s" % sorted(conflicts))

        # ⚠️ **`langs` y no `lang_src`: los idiomas de un pack son PARES.** Un pack
        # bidireccional tiene entradas de los dos y ninguno es el principal; uno monolingue
        # declara una sola y nada cambia. El orden de la lista es orden de declaracion, no
        # jerarquia -- lo unico que hace es fijar el idioma por defecto de un `Record` que no
        # declare el suyo.
        self.langs = _parse_langs(metadata.get("langs"))
        if not self.langs:
            # Entra en entry.uid: sin el, la identidad logica de dos packs de idiomas distintos
            # podria colisionar.
            raise ValueError("falta meta.langs, que forma parte de entry.uid")

        self.path = path
        self.metadata = dict(metadata)
        # Un perfil por idioma. `fuzzy_profile=` sigue aceptandose para el caso de un solo
        # idioma, que es el 99 % de las llamadas y de los tests.
        self.fuzzy_profiles = _parse_profiles(
            metadata.get("fuzzy_profiles"), self.langs,
            fuzzy_profile or metadata.get("fuzzy_profile"),
        )
        for perfil in self.fuzzy_profiles.values():
            if perfil not in normalize.FUZZY_PROFILES:
                raise ValueError("perfil fuzzy desconocido: %r" % (perfil,))
        # El del idioma primario, para lo que todavia habla de "el" perfil del pack.
        self.fuzzy_profile = self.fuzzy_profiles[self.langs[0]]

        if os.path.exists(path):
            os.remove(path)
        self.connection = sqlite3.connect(path)
        # indexes.sql se corre al final, sobre las tablas ya pobladas.
        self.connection.executescript(_read_sql("schema.sql"))
        self.connection.executescript(
            """
            -- Solo durante la construccion: el archivo se descarta si algo falla, asi que la
            -- durabilidad no importa y esto acelera la ingesta de millones de filas.
            PRAGMA journal_mode = OFF;
            PRAGMA synchronous = OFF;
            CREATE TABLE staging (
                id       INTEGER PRIMARY KEY,
                uid      INTEGER NOT NULL,
                lang     TEXT NOT NULL,
                headword TEXT NOT NULL,
                norm     TEXT NOT NULL,
                fuzzy    TEXT NOT NULL,
                pos      TEXT,
                rank     INTEGER NOT NULL,
                body     TEXT NOT NULL,
                fts_body TEXT NOT NULL
            );
            -- Las traducciones pasan por staging porque el tope por clave
            -- (TRANS_MAX_PER_KEY) necesita los conteos globales, que solo se conocen cuando
            -- termino la ingesta.
            CREATE TABLE staging_trans (
                norm     TEXT NOT NULL,
                entry_id INTEGER NOT NULL,
                PRIMARY KEY (norm, entry_id)
            ) WITHOUT ROWID;
            """
        )
        self.count = 0
        self._sentences = sentences or {}
        self._thesaurus = thesaurus or {}
        self._sample = []
        self._sampled = 0
        # Determinista: dos builds del mismo input dan el mismo pack.
        self._random = random.Random(0)

    def add(self, record):
        norm_key = normalize.norm(record.headword)
        if not norm_key:
            # Un lema que se normaliza a vacio (solo puntuacion) no se puede buscar.
            return

        body = payload_codec.render(
            record.part_of_speech, record.senses, record.word_translations)
        if not body:
            # Sin ninguna acepcion utilizable la entrada no tiene nada que mostrar.
            return

        # El idioma de ESTA entrada decide su perfil fuzzy y entra en su uid. Un `Record` que no
        # lo declara es del idioma primario, que es el caso de todo pack monolingue.
        lang = record.lang or self.langs[0]
        if lang not in self.fuzzy_profiles:
            raise ValueError(
                "el record declara lang=%r, que no esta en meta.langs=%r" % (lang, self.langs))
        fuzzy_key = normalize.fuzzy(record.headword, self.fuzzy_profiles[lang])
        fts_body = _fts_body(record.senses)

        cursor = self.connection.execute(
            "INSERT INTO staging (uid, lang, headword, norm, fuzzy, pos, rank, body, fts_body)"
            " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (
                # ⚠️ **Un `uid` ya calculado se COPIA, no se recalcula**, y eso sólo lo usa
                # `build_core`. Derivar un pack de otro tiene que conservar la identidad logica:
                # `sense_key` se decide contando los homografos del pack FINAL (D-145), y un
                # subconjunto tiene menos homografos, asi que recalcularlo le daria otra
                # identidad a la misma palabra y rompería el join entre packs (D-055).
                record.uid if record.uid is not None else stable_uid(
                    lang,
                    record.headword,
                    record.part_of_speech,
                    record.sense_key,
                ),
                lang,
                record.headword,
                norm_key,
                fuzzy_key,
                record.part_of_speech,
                record.rank,
                body,
                fts_body,
            ),
        )
        entry_id = cursor.lastrowid
        self.count += 1
        self._collect_sample(body)

        # OR IGNORE porque las fuentes traen repetidos y la PK compuesta ya los rechaza.
        for form in record.forms:
            key = normalize.norm(form)
            if key and key != norm_key:
                self.connection.execute(
                    "INSERT OR IGNORE INTO form (norm, entry_id) VALUES (?, ?)", (key, entry_id)
                )

        for key in self._translation_keys(record.translations):
            self.connection.execute(
                "INSERT OR IGNORE INTO staging_trans (norm, entry_id) VALUES (?, ?)",
                (key, entry_id),
            )

    @staticmethod
    def _translation_keys(translations):
        """Claves por las que se debe poder llegar a la entrada desde el idioma destino.

        Se indexa la frase completa Y cada palabra suelta: "to run" tiene que encontrarse
        escribiendo "run", que es lo que un usuario escribe en un reloj, y tambien escribiendo
        "to run" completo.
        """
        keys = set()
        for translation in translations:
            normalized = normalize.norm(translation)
            if not normalized:
                continue
            keys.add(normalized)
            words = normalized.split(" ")
            if len(words) > 1:
                keys.update(words)
        return keys

    def _collect_sample(self, body):
        """Muestreo de reservorio: muestra uniforme sin conocer el total ni guardarlo entero."""
        self._sampled += 1
        if len(self._sample) < DICTIONARY_SAMPLE_SIZE:
            self._sample.append(body)
        else:
            index = self._random.randrange(self._sampled)
            if index < DICTIONARY_SAMPLE_SIZE:
                self._sample[index] = body

    def _frases_por_entrada(self):
        """Mapa `entry_id -> frase`, resuelto con el indice YA poblado (D-137).

        ⚠️ **La condicion no es "la palabra aparece", es "la palabra lleva a UNA entrada".** Un
        corpus no dice de que acepcion --ni de que lema-- es cada oracion. "vino" es un lema (la
        bebida) y tambien una forma de "venir": colgarle "Ella vino ayer" a la bebida no lanza,
        no loguea, no lo agarra `verify_pack.py`, y sale del pack como una definicion con un
        ejemplo que la contradice. Es el modo de falla mas caro que tiene este repo.

        Se evalua aca y no en `add()` porque **la ambiguedad es global**: cuando entra "vino" la
        entrada de "venir" puede no existir todavia. Recien con el staging y `form` completos se
        puede preguntar a cuantas entradas llega una clave.

        Cuesta la mitad del rendimiento: de 14.023 entradas alcanzables quedan 7.019. Se paga.

        Ademas solo se le pega a una entrada de **una acepcion y sin ejemplo propio**. Con varias
        no se sabe cual ilustra (la regla de D-132 y D-135); y si ya tiene el ejemplo que la
        fuente ATRIBUYO, ese gana: viene con acepcion, el de corpus solo contiene la palabra.
        """
        if not self._sentences:
            return {}
        cur = self.connection
        cur.execute("CREATE TEMP TABLE frase (norm TEXT PRIMARY KEY, texto TEXT) WITHOUT ROWID")
        cur.executemany("INSERT OR IGNORE INTO frase (norm, texto) VALUES (?, ?)",
                        self._sentences.items())
        # `HAVING COUNT(DISTINCT id) = 1` es la regla entera. La union mira lema y forma, que son
        # los dos caminos por los que una palabra escrita llega a una entrada.
        filas = cur.execute(
            """
            SELECT MIN(alcanza.id), frase.texto
              FROM frase
              JOIN (SELECT norm, id FROM staging
                    UNION ALL
                    SELECT norm, entry_id AS id FROM form) AS alcanza
                ON alcanza.norm = frase.norm
             GROUP BY frase.norm
            HAVING COUNT(DISTINCT alcanza.id) = 1
            """
        ).fetchall()
        cur.execute("DROP TABLE frase")
        return dict(filas)

    def _sumar_tesauro(self, entry_id, headword, pos, body, fts_body):
        """Le agrega al body los sinonimos y antonimos de WordNet. Ver `sources/wordnet` (D-144).

        ⚠️ **Solo para entradas de UNA acepcion.** Un synset es *una* acepcion; con varias no se
        sabe de cual son y colgarlos de la primera es el error de D-117.

        ⚠️ **Y una flexion del propio lema NO es un sinonimo.** El MCR español se construyo
        automaticamente y mete "coreana, coreanos, coreanas" en el synset de "coreano"; emitirlas
        llenaria la linea del reloj con la misma palabra declinada. Se detecta contra `form`, que
        es un dato que ya tenemos -- por eso esto corre en `finish()` y no en `add()`.

        Los sinonimos van tambien a `fts_def` y los antonimos **no**, que es D-118 y D-126: un
        sinonimo es otra forma de nombrar lo que buscas y un antonimo es lo que NO buscas.
        """
        aporte = self._thesaurus.get((headword, pos))
        if not aporte or not _tiene_una_acepcion(body):
            return body, fts_body
        ya = _terminos_ya_presentes(body)
        nuevos_sinonimos = self._filtrar(aporte.get("synonyms", ()), entry_id, headword, ya)
        nuevos_antonimos = self._filtrar(aporte.get("antonyms", ()), entry_id, headword, ya)
        if not nuevos_sinonimos and not nuevos_antonimos:
            return body, fts_body
        lineas = []
        for termino in nuevos_sinonimos:
            lineas.append(payload_codec.TAG_SYNONYM + "\t" + payload_codec.sanitize(termino))
        for termino in nuevos_antonimos:
            lineas.append(payload_codec.TAG_ANTONYM + "\t" + payload_codec.sanitize(termino))
        body = body + "".join(linea + "\n" for linea in lineas)
        if nuevos_sinonimos:
            fts_body = (fts_body + " " + " ".join(nuevos_sinonimos)).strip()
        return body, fts_body

    def _filtrar(self, terminos, entry_id, headword, ya):
        """Saca lo repetido, el propio lema, y las flexiones de esta misma entrada."""
        out = []
        cupo = MAX_TESAURO_POR_ACEPCION - len(ya)
        for termino in terminos:
            if cupo <= 0:
                break
            clave = normalize.norm(termino)
            if not clave or clave in ya or clave == normalize.norm(headword):
                continue
            fila = self.connection.execute(
                "SELECT 1 FROM form WHERE norm = ? AND entry_id = ?", (clave, entry_id)
            ).fetchone()
            if fila is not None:
                continue
            ya.add(clave)
            out.append(termino)
            cupo -= 1
        return out

    def finish(self):
        if self.count == 0:
            raise ValueError("el pack quedo sin entradas")

        self._reject_uid_collisions()

        dictionary = payload_codec.build_dictionary(self._sample)

        # Pasada 2: comprimir y poblar entry + fts_def. Se itera con un cursor separado para no
        # cargar el staging completo en memoria.
        # Antes de abrir el cursor de lectura: la temp table de `_frases_por_entrada` no se puede
        # crear ni borrar con un cursor vivo sobre staging en la misma conexion -- SQLite
        # devuelve "database table is locked".
        frases = self._frases_por_entrada()

        read = self.connection.cursor()
        write = self.connection.cursor()
        read.execute(
            "SELECT id, uid, lang, headword, norm, fuzzy, pos, rank, body, fts_body"
            " FROM staging ORDER BY id"
        )
        for row in read:
            entry_id, uid, lang, headword, norm_key, fuzzy_key, pos, rank, body, fts_body = row
            body, fts_body = self._sumar_tesauro(entry_id, headword, pos, body, fts_body)
            frase = frases.get(entry_id)
            if frase and _admite_frase_de_corpus(body):
                # El tag E se cuelga de la ULTIMA acepcion abierta, y `_admite_frase_de_corpus`
                # ya comprobo que hay exactamente una. Se agrega al texto ya renderizado en vez
                # de re-renderizar: el body es la unica copia y volver a armarlo seria una
                # segunda implementacion del formato.
                body = (body + payload_codec.TAG_EXAMPLE + "\t"
                        + payload_codec.sanitize(frase) + "\n")
                # Y al indice de texto libre, o la busqueda por definicion veria un pack distinto
                # del que se muestra. Los ejemplos ya entraban (D-118).
                fts_body = (fts_body + " " + frase).strip()
            write.execute(
                "INSERT INTO entry (id, uid, lang, headword, norm, fuzzy, pos, rank, payload)"
                " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (
                    entry_id,
                    uid,
                    lang,
                    headword,
                    norm_key,
                    fuzzy_key,
                    pos,
                    rank,
                    payload_codec.compress(body, dictionary),
                ),
            )
            # rowid explicito: es lo que ata fts_def a entry.id, y en una tabla contentless es
            # el unico dato que la consulta puede devolver.
            write.execute("INSERT INTO fts_def (rowid, body) VALUES (?, ?)", (entry_id, fts_body))

        dropped = self._materialize_translations()
        self._write_metadata(dictionary, dropped)

        self.connection.executescript("DROP TABLE staging; DROP TABLE staging_trans;")
        # Los indices se crean ahora, sobre las tablas ya pobladas.
        self.connection.executescript(_read_sql("indexes.sql"))

        self.connection.commit()
        self.connection.executescript("PRAGMA optimize;")
        # VACUUM recupera las paginas que dejo libres el staging borrado y deja el archivo
        # compacto y con las paginas en orden, que es como se va a leer en el reloj.
        self.connection.execute("VACUUM")
        self.connection.close()
        return self.count

    def _reject_uid_collisions(self):
        """Dos entradas con la misma identidad logica: el build falla, no se funden.

        Resolver una colision aca seria peor que fallar: cualquier criterio de desempate que
        dependa del orden de insercion rompe justo la estabilidad entre rebuilds que entry.uid
        existe para dar. Si aparece, la fuente tiene que entregar `sense_key`.

        Por hash: con 63 bits y un millon de entradas la probabilidad de una colision fortuita
        es ~3e-8. En la practica esto atrapa homografos con mismo headword y mismo pos, que son
        una colision de la *identidad*, no del hash.
        """
        collision = self.connection.execute(
            "SELECT uid, COUNT(*) AS n, group_concat(headword || ' [' || COALESCE(pos, '') || ']')"
            " FROM staging GROUP BY uid HAVING n > 1 LIMIT 1"
        ).fetchone()
        if collision:
            raise ValueError(
                "dos entradas comparten entry.uid (%d): %s. La fuente tiene que entregar un "
                "sense_key que las distinga; ver stable_uid()" % (collision[0], collision[2])
            )

    def _materialize_translations(self):
        """Copia staging_trans a trans aplicando el tope por clave.

        Se resuelve con una sola sentencia y una funcion de ventana para no traer las claves a
        memoria: en un pack real esto son millones de filas.
        """
        total = self.connection.execute("SELECT COUNT(*) FROM staging_trans").fetchone()[0]
        self.connection.execute(
            """
            INSERT INTO trans (norm, entry_id)
            SELECT norm, entry_id FROM (
                SELECT st.norm AS norm,
                       st.entry_id AS entry_id,
                       ROW_NUMBER() OVER (
                           PARTITION BY st.norm ORDER BY s.rank, st.entry_id
                       ) AS position
                FROM staging_trans st
                JOIN staging s ON s.id = st.entry_id
            )
            WHERE position <= ?
            """,
            (TRANS_MAX_PER_KEY,),
        )
        kept = self.connection.execute("SELECT COUNT(*) FROM trans").fetchone()[0]
        return total - kept

    def _write_metadata(self, dictionary, dropped_translations=0):
        values = dict(self.metadata)
        values.update(
            {
                "schema_version": str(SCHEMA_VERSION),
                "norm_version": str(normalize.NORM_VERSION),
                # ⚠️ **`fuzzy_profiles` en plural y posicional contra `langs`.** El singular se
                # sigue escribiendo --es el del idioma primario-- porque `verify_pack.py` y los
                # packs de un solo idioma lo leen, y porque un lector viejo que solo entienda el
                # singular falla ya en `schema_version`, no aca.
                "fuzzy_profiles": _SEPARADOR_DE_LISTA.join(
                    self.fuzzy_profiles[lang] for lang in self.langs),
                "fuzzy_profile": self.fuzzy_profile,
                "payload_codec": payload_codec.CODEC_ID,
                # El diccionario de compresion va en hex: la tabla meta es TEXT, y un BLOB
                # obligaria a tratar esta clave distinto de las demas en el lector.
                "payload_dict": dictionary.hex(),
                # Integridad del diccionario: ver dictionary_digest() en payload.py.
                "payload_dict_sha256": payload_codec.dictionary_digest(dictionary),
                # Con que receta se calculo entry.uid: un pack auxiliar construido con otra
                # apunta a entradas equivocadas, y sin esto no habria como notarlo.
                "uid_recipe": UID_RECIPE,
                # ⚠️ **`full` salvo que quien construya diga otra cosa**, y por eso el default
                # vive aca y no en cada llamador. Lo escribe `build_core.py` como `core`.
                #
                # Existe porque hoy un nucleo se hace a un lado por `subset_of`, que un pack
                # ajeno puede declarar mal o no declarar; `tier` dice **que es** el pack, igual
                # que `pack_id` dice quien es (D-138). El roadmap ya lo recomendaba sobre
                # inferirlo de que el nombre termine en `-core`, que es adivinar del nombre lo
                # que D-138 decidio que se declara.
                "tier": values.get("tier", "full"),
                "entry_count": str(self.count),
                "built_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                "data_version": data_version(),
                # Para ver, con datos reales, cuanto esta recortando TRANS_MAX_PER_KEY.
                "trans_dropped": str(dropped_translations),
            }
        )
        self.connection.executemany(
            "INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)", sorted(values.items())
        )

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        # Un pack a medio construir es peor que ninguno: se abriria sin error y devolveria
        # resultados incompletos. Aplica tambien a los fallos de validacion de finish() --
        # una colision de uid, por ejemplo--, que ocurren con el archivo ya casi armado.
        if exc_type is None:
            try:
                self.finish()
            except BaseException:
                self._discard()
                raise
        else:
            self._discard()
        return False

    def _discard(self):
        self.connection.close()
        if os.path.exists(self.path):
            os.remove(self.path)


# Tope combinado de sinonimos mas antonimos por acepcion. Es el mismo renglon de reloj que
# `MAX_SYNONYMS_PER_SENSE` en la fuente; aca se repite porque este merge no pasa por ahi.
MAX_TESAURO_POR_ACEPCION = 4


def _tiene_una_acepcion(body):
    return [linea[0] for linea in body.split("\n")
            if len(linea) > 1 and linea[1] == "\t"].count(
        payload_codec.TAG_SENSE) == 1


def _terminos_ya_presentes(body):
    """Las claves normalizadas de los sinonimos y antonimos que el body ya trae.

    Normalizadas y no literales: el wiki puede traer "gelido" y WordNet "gélido", y repetirlos
    gastaria dos renglones para decir lo mismo.
    """
    out = set()
    for linea in body.split("\n"):
        if len(linea) > 2 and linea[1] == "\t" and linea[0] in (
            payload_codec.TAG_SYNONYM, payload_codec.TAG_ANTONYM
        ):
            clave = normalize.norm(linea[2:])
            if clave:
                out.add(clave)
    return out


def _admite_frase_de_corpus(body):
    """El body renderizado tiene UNA acepcion y ninguna ejemplo propio.

    Se lee del texto del payload y no de los `senses` originales porque en la pasada 2 el body es
    lo unico que queda: el staging guarda el texto, no la estructura. Son dos conteos de lineas.
    """
    lineas = [linea[0] for linea in body.split("\n")
              if len(linea) > 1 and linea[1] == "\t"]
    return lineas.count(payload_codec.TAG_SENSE) == 1 and payload_codec.TAG_EXAMPLE not in lineas


def _fts_body(senses):
    """Texto que se indexa para la busqueda de texto libre.

    Van glosas, ejemplos, traducciones y sinonimos sin los tags del formato: los tags no son
    palabras que alguien vaya a buscar y solo ensucian el indice.

    Los sinonimos entran por D-118: si solo fueran al payload, se verian recien al ABRIR una
    entrada que ya encontraste, que es cuando ya no hacen falta. Buscar "bobo" tiene que
    encontrar "chulengo".

    **Los antonimos NO entran, y es una decision, no un olvido** (D-126). Buscar "frio" para
    encontrar "caliente" no es algo que nadie haga: meterlos al indice sumaria ruido a una
    busqueda cuyo ORDEN ya es deuda abierta (D-067), y la entrada equivocada que devolveria
    seria ademas la que significa lo contrario de lo buscado. Hay un test que lo fija.

    **Las relacionadas tampoco entran** (D-132), por el mismo criterio: nadie escribe "camelido"
    esperando "guanaco". Van solo al payload, donde se leen al abrir la entrada, que es cuando
    sirven. Hay un test que lo fija.
    """
    parts = []
    for sense in senses:
        parts.append(sense.get("gloss", ""))
        parts.extend(sense.get("examples", ()))
        parts.extend(sense.get("translations", ()))
        parts.extend(sense.get("synonyms", ()))
    return " ".join(part for part in parts if part)


def _read_sql(filename):
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), filename)
    with open(path, encoding="utf-8") as handle:
        return handle.read()
