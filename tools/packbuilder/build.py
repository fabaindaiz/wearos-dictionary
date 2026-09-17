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

SCHEMA_VERSION = 2

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
        "sense_key",
    )

    def __init__(
        self,
        headword,
        senses,
        part_of_speech=None,
        rank=0,
        forms=(),
        translations=(),
        sense_key=None,
    ):
        self.headword = headword
        self.senses = senses
        self.part_of_speech = part_of_speech
        self.rank = rank
        self.forms = forms
        self.translations = translations
        self.sense_key = sense_key


class PackBuilder:
    def __init__(self, path, metadata, fuzzy_profile=None):
        """`metadata` son las claves de la tabla meta que aporta la fuente.

        El builder agrega por su cuenta las que son suyas (versiones, conteo, fecha) y falla si
        la fuente intenta declararlas: una version de esquema escrita a mano seria una forma
        silenciosa de romper la validacion del lado del reloj.
        """
        reserved = {
            "schema_version",
            "norm_version",
            "payload_codec",
            "payload_dict",
            "entry_count",
            "built_at",
            "uid_recipe",
        }
        conflicts = reserved & set(metadata)
        if conflicts:
            raise ValueError("estas claves de meta las escribe el builder: %s" % sorted(conflicts))

        if not metadata.get("lang_src"):
            # Entra en entry.uid: sin el, la identidad logica de dos packs de idiomas distintos
            # podria colisionar.
            raise ValueError("falta meta.lang_src, que forma parte de entry.uid")

        self.path = path
        self.metadata = dict(metadata)
        self.fuzzy_profile = fuzzy_profile or metadata.get("fuzzy_profile", "generic")
        if self.fuzzy_profile not in normalize.FUZZY_PROFILES:
            raise ValueError("perfil fuzzy desconocido: %r" % (self.fuzzy_profile,))

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
        self._sample = []
        self._sampled = 0
        # Determinista: dos builds del mismo input dan el mismo pack.
        self._random = random.Random(0)

    def add(self, record):
        norm_key = normalize.norm(record.headword)
        if not norm_key:
            # Un lema que se normaliza a vacio (solo puntuacion) no se puede buscar.
            return

        body = payload_codec.render(record.part_of_speech, record.senses)
        if not body:
            # Sin ninguna acepcion utilizable la entrada no tiene nada que mostrar.
            return

        fuzzy_key = normalize.fuzzy(record.headword, self.fuzzy_profile)
        fts_body = _fts_body(record.senses)

        cursor = self.connection.execute(
            "INSERT INTO staging (uid, headword, norm, fuzzy, pos, rank, body, fts_body)"
            " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            (
                stable_uid(
                    self.metadata["lang_src"],
                    record.headword,
                    record.part_of_speech,
                    record.sense_key,
                ),
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

    def finish(self):
        if self.count == 0:
            raise ValueError("el pack quedo sin entradas")

        self._reject_uid_collisions()

        dictionary = payload_codec.build_dictionary(self._sample)

        # Pasada 2: comprimir y poblar entry + fts_def. Se itera con un cursor separado para no
        # cargar el staging completo en memoria.
        read = self.connection.cursor()
        write = self.connection.cursor()
        read.execute(
            "SELECT id, uid, headword, norm, fuzzy, pos, rank, body, fts_body"
            " FROM staging ORDER BY id"
        )
        for row in read:
            entry_id, uid, headword, norm_key, fuzzy_key, pos, rank, body, fts_body = row
            write.execute(
                "INSERT INTO entry (id, uid, headword, norm, fuzzy, pos, rank, payload)"
                " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                (
                    entry_id,
                    uid,
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
                "entry_count": str(self.count),
                "built_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
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


def _fts_body(senses):
    """Texto que se indexa para la busqueda de texto libre.

    Van glosas, ejemplos y traducciones sin los tags del formato: los tags no son palabras que
    alguien vaya a buscar y solo ensucian el indice.
    """
    parts = []
    for sense in senses:
        parts.append(sense.get("gloss", ""))
        parts.extend(sense.get("examples", ()))
        parts.extend(sense.get("translations", ()))
    return " ".join(part for part in parts if part)


def _read_sql(filename):
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), filename)
    with open(path, encoding="utf-8") as handle:
        return handle.read()
