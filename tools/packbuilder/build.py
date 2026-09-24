"""Builds a dictionary pack (.db) from a stream of records.

Use as a library:

    from build import PackBuilder, Record
    with PackBuilder("es-en.db", metadata) as builder:
        for record in mi_fuente():
            builder.add(record)

The builder works in two passes over a staging table inside the same file, never in memory: the
real sources are gigabytes (kaikki.org's English JSONL is 2.9 GB) and do not fit. Pass 1 writes the
bodies uncompressed and takes a sample; the shared compression dictionary is built from that
sample; pass 2 compresses and fills `entry` and `fts_def`.

The indexes are created at the end, over the already populated tables.
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

# The recipe entry.uid, an entry's LOGICAL identity, is computed with (D-055).
#
# entry.id is PHYSICAL identity: the local rowid, which fts_def shares and which form and trans
# point at. It is cheap precisely for being sequential, and **it does not survive rebuilding the
# pack**: one new word in the middle shifts every following id.
#
# entry.uid is logical identity: it survives the rebuild, and it is how an auxiliary pack
# (synonyms, translations) adds information to this pack's same entry.
#
# ONLY the builder computes it. The app never recomputes it: it reads it from the row and uses it
# as a lookup key in the auxiliary pack. So, unlike norm()/fuzzy(), it is NOT a contract mirrored
# between two languages and cannot diverge. If it ever had to be computed in Kotlin, that stops
# being true and the class of bug D-005 exists to avoid comes back.
#
# If the recipe changes, this identifier changes: the auxiliary packs built with the previous one
# are orphaned and have to be able to detect it.
UID_RECIPE = "uid-v1"

# Size of the sample the compression dictionary is built from. More sample does not improve much
# because the dictionary caps at 32 KB anyway.
DICTIONARY_SAMPLE_SIZE = 4000

# Cap on entries per reverse translation key.
#
# Translations are phrases ("to run", "all of a sudden") and each word is indexed separately, or
# searching "run" would find nothing. The side effect is that function words ("to", "of", "a") end
# up pointing at tens of thousands of entries: it inflates the index and helps nobody.
#
# It is capped rather than discarding the key: searching "to" still returns something useful (the
# most frequent verbs) instead of nothing. The best ranked ones are kept.
TRANS_MAX_PER_KEY = 50


def stable_uid(lang, headword, pos, sense_key=None):
    """An entry's logical identity: stable across rebuilds and across packs. See UID_RECIPE.

    It is computed over the **raw** headword (not over `norm`) on purpose: that way it does not
    depend on NORM_VERSION, and raising the normalization rules does not invalidate the auxiliary
    packs. It also distinguishes "arbol" from "árbol", which are two different entries even though
    they normalize the same.

    `sense_key` disambiguates homographs sharing a headword AND a pos (different etymology). The
    source supplies it if it has it; if two entries end up with the same identity, the build fails
    rather than fusing them.

    It returns 63 unsigned bits: it fits in a SQLite INTEGER and is never negative.
    """
    material = "\x1f".join(
        (
            lang,
            # NFC pins the composition form: two sources can deliver "á" precomposed or
            # decomposed, and those would be different bytes for the same word.
            unicodedata.normalize("NFC", headword),
            (pos or "").strip().lower(),
            sense_key or "",
        )
    )
    return int.from_bytes(hashlib.sha256(material.encode("utf-8")).digest()[:8], "big") >> 1


# ⚠️ **Separator `,` and not `+`**: `meta` is TEXT and these two keys are lists. The comma is what
# `meta.sources` already uses, so the watch's reader learns no new convention.
_SEPARADOR_DE_LISTA = ","


def _parse_langs(crudo):
    """The pack's languages, in declaration order. `"es,en"` -> `["es", "en"]`."""
    if not crudo:
        return []
    vistos = []
    for parte in crudo.split(_SEPARADOR_DE_LISTA):
        lang = parte.strip()
        # Repeating a language is not a user error but a bug in whoever assembles the metadata:
        # it would duplicate the fuzzy profile and the default language would still be the first.
        if lang and lang not in vistos:
            vistos.append(lang)
    return vistos


def _parse_profiles(crudo, langs, unico=None):
    """`{language: fuzzy profile}`.

    It accepts three shapes, from the most explicit to the most convenient:

    - `"es,en"` in `meta.fuzzy_profiles`, **positional against `langs`** -- it is what a
      bidirectional pack writes;
    - `fuzzy_profile=` or `meta.fuzzy_profile`, one profile for every language;
    - nothing, and it falls back to `generic`.

    ⚠️ **Positional and not an `es=es` map**: a language's profile is not always named after the
    language --there is `generic`-- and a map would force repeating the key. That both lists have
    the same length is checked by this function, which is where a mismatch is still cheap.
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
    """An entry ready to index, as a source delivers it.

    `forms` are the inflected forms that must lead to this lemma (not including the lemma).
    `translations` are the target language's words by which it must be reachable. Both are
    normalized here: the source delivers raw text.

    `sense_key` is only needed when the source brings two entries with the same headword and the
    same pos (typically, a different etymology): it is what separates them in entry.uid. See
    stable_uid().
    """

    __slots__ = (
        "headword",
        "part_of_speech",
        "rank",
        "senses",
        "forms",
        "display_forms",
        "pronunciation",
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
        display_forms=(),
        pronunciation=None,
        translations=(),
        word_translations=(),
        sense_key=None,
        uid=None,
        lang=None,
    ):
        self.headword = headword
        self.senses = senses
        # None = the pack's primary language. Only a bidirectional source fills it.
        self.lang = lang
        self.part_of_speech = part_of_speech
        self.rank = rank
        self.forms = forms
        #: The principal parts the card SHOWS. See `kaikki._display_forms`: not the same as
        #: `forms`, which feeds the normalized search.
        self.display_forms = display_forms
        #: The word's IPA, or None when the source carried none. See `kaikki._pronunciation`.
        self.pronunciation = pronunciation
        self.translations = translations
        # Translations of the WORD, with no sense. They go to the payload (tag `W`) and NOT to
        # `trans` on their own: `translations` is the search channel and carries the union of both.
        self.word_translations = word_translations
        self.sense_key = sense_key
        self.uid = uid


#: The tiers a pack can declare. In English and untranslated: it is the tier's identifier and has
#: to read the same in any interface language (D-215).
TIERS = ("core", "main", "full")


def name_with_tier(name, tier):
    """The pack's name with **exactly one** tier token at the end.

    ⚠️ **It exists because the tier was stamped in two places and the second did not look at the
    first.** `build_pack` closes the full pack with `"%s (full)" % name` and `build_core` derives
    from that pack and sticks `(core)` on top, so the core came out called **`Español (full)
    (core)`** --seen on the watch's dictionaries screen, and both halves are true: it derived from
    a `full` one and it is a `core`--. What the user reads has to say **one** thing.

    The rule: any tier the end of the name already carries is removed and the right one is put on.
    That way it does not matter whether the name comes in clean or inherited, which is exactly what
    could not be assumed.

    >>> name_with_tier("Espanol", "full")
    'Espanol (full)'
    >>> name_with_tier("Espanol (full)", "core")
    'Espanol (core)'
    >>> name_with_tier("Espanol (core)", "core")
    'Espanol (core)'
    """
    base = (name or "").strip()
    # Only the LAST token, and only if it is a known tier: a pack called "Griego (koine)" cannot
    # lose its parenthesis for resembling this.
    for conocido in TIERS:
        sufijo = " (%s)" % conocido
        if base.endswith(sufijo):
            base = base[: -len(sufijo)].rstrip()
            break
    return "%s (%s)" % (base, tier) if base else "(%s)" % tier


def data_version(ahora=None):
    """The pack's version: YYYYMMDDHHMM, as an integer in a string.

    Three things at once, and none can be sacrificed:

    - **It orders.** An installer has to be able to say which of two packs is newer by comparing
      numbers, without parsing dates.
    - **It reads.** `202609211432` is "21 September 2026, 14:32" with no converter. An epoch would
      order too and nobody could read it at a glance, which was exactly what was asked for.
    - **It distinguishes two builds of the same dump.** To the minute, which is ample: a build
      takes minutes, so two never land on the same one.

    ⚠️ **It does not fit in a 32-bit Int** (202609211432 > 2,147,483,647). The app parses it as a
    `Long`; if somebody turns it into an `Int`, the pack blows up on OPENING on the watch with a
    NumberFormatException that does not name the key. A test pins that.
    """
    if ahora is None:
        t = time.gmtime()
        ahora = (t.tm_year, t.tm_mon, t.tm_mday, t.tm_hour, t.tm_min)
    return "%04d%02d%02d%02d%02d" % ahora


class PackBuilder:
    def __init__(self, path, metadata, fuzzy_profile=None, sentences=None,
                 thesaurus=None):
        """`metadata` are the meta table keys the source contributes.

        The builder adds its own (versions, count, date) and fails if the source tries to declare
        them: a hand-written schema version would be a silent way of breaking validation on the
        watch's side.

        `sentences` is a corpus's `norm -> sentence` map (D-137). It is resolved in `finish()` and
        not here **because the condition that makes it safe can only be evaluated at the end**:
        that the word leads to a single entry of the pack. See `_frases_por_entrada`.

        `thesaurus` is WordNet's `(lemma, pos) -> {"synonyms": [...], "antonyms": [...]}` map
        (D-144). It is resolved in `finish()` too, and for a similar reason: the filter that
        removes inflections disguised as synonyms needs the complete `form` table.
        """
        reserved = {
            "schema_version",
            "norm_version",
            "payload_codec",
            "payload_dict",
            "entry_count",
            "built_at",
            "uid_recipe",
            # ⚠️ **Derived, and therefore reserved.** It used to be the dump's date written by
            # hand, so rebuilding the SAME dump with another builder gave the same number and
            # `devpack.py` --and the installer-- read it as "it is the same pack": a better pack
            # did not propagate. What the written value meant is now declared in `source_date`.
            "data_version",
        }
        conflicts = reserved & set(metadata)
        if conflicts:
            raise ValueError("estas claves de meta las escribe el builder: %s" % sorted(conflicts))

        # ⚠️ **`langs` and not `lang_src`: a pack's languages are PEERS.** A bidirectional pack
        # has entries of both and neither is the main one; a monolingual one declares a single
        # language and nothing changes. The list's order is declaration order, not hierarchy --
        # the only thing it does is fix the default language of a `Record` that declares none.
        self.langs = _parse_langs(metadata.get("langs"))
        if not self.langs:
            # It goes into entry.uid: without it, the logical identity of two packs in different
            # languages could collide.
            raise ValueError("falta meta.langs, que forma parte de entry.uid")

        self.path = path
        self.metadata = dict(metadata)
        # One profile per language. `fuzzy_profile=` is still accepted for the single-language
        # case, which is 99 % of the calls and of the tests.
        self.fuzzy_profiles = _parse_profiles(
            metadata.get("fuzzy_profiles"), self.langs,
            fuzzy_profile or metadata.get("fuzzy_profile"),
        )
        for perfil in self.fuzzy_profiles.values():
            if perfil not in normalize.FUZZY_PROFILES:
                raise ValueError("perfil fuzzy desconocido: %r" % (perfil,))
        # The primary language's, for whatever still speaks of "the" pack's profile.
        self.fuzzy_profile = self.fuzzy_profiles[self.langs[0]]

        if os.path.exists(path):
            os.remove(path)
        self.connection = sqlite3.connect(path)
        # indexes.sql is run at the end, over the already populated tables.
        self.connection.executescript(_read_sql("schema.sql"))
        self.connection.executescript(
            """
            -- Only while building: the file is discarded if anything fails, so durability does
            -- not matter and this speeds up ingesting millions of rows.
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
            -- The translations go through staging because the per-key cap (TRANS_MAX_PER_KEY)
            -- needs the global counts, which are only known once ingestion has finished.
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
        # Deterministic: two builds of the same input give the same pack.
        self._random = random.Random(0)

    def add(self, record):
        norm_key = normalize.norm(record.headword)
        if not norm_key:
            # A lemma that normalizes to empty (punctuation only) cannot be searched.
            return

        body = payload_codec.render(
            record.part_of_speech, record.senses, record.word_translations,
            record.display_forms, record.pronunciation)
        if not body:
            # With no usable sense the entry has nothing to show.
            return

        # THIS entry's language decides its fuzzy profile and goes into its uid. A `Record` that
        # does not declare one belongs to the primary language, which is every monolingual pack's
        # case.
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
                # ⚠️ **An already computed `uid` is COPIED, not recomputed**, and only
                # `build_core` uses that. Deriving one pack from another has to preserve the
                # logical identity: `sense_key` is decided by counting the FINAL pack's homographs
                # (D-145), and a subset has fewer homographs, so recomputing it would give the
                # same word another identity and break the join across packs (D-055).
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

        # OR IGNORE because the sources bring duplicates and the composite PK already rejects them.
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
        """The keys by which the entry must be reachable from the target language.

        The whole phrase AND each separate word are indexed: "to run" has to be findable by typing
        "run", which is what a user types on a watch, and also by typing the whole "to run".
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
        """Reservoir sampling: a uniform sample without knowing the total or storing it whole."""
        self._sampled += 1
        if len(self._sample) < DICTIONARY_SAMPLE_SIZE:
            self._sample.append(body)
        else:
            index = self._random.randrange(self._sampled)
            if index < DICTIONARY_SAMPLE_SIZE:
                self._sample[index] = body

    def _frases_por_entrada(self):
        """An `entry_id -> sentence` map, resolved with the index ALREADY populated (D-137).

        ⚠️ **The condition is not "the word appears", it is "the word leads to ONE entry".** A
        corpus does not say which sense --nor which lemma-- each sentence belongs to. "vino" is a
        lemma (the drink) and also a form of "venir": hanging "Ella vino ayer" off the drink throws
        nothing, logs nothing, is not caught by `verify_pack.py`, and comes out of the pack as a
        definition with an example that contradicts it. It is the most expensive failure mode this
        repo has.

        It is evaluated here and not in `add()` because **the ambiguity is global**: when "vino"
        comes in, "venir"'s entry may not exist yet. Only with staging and `form` complete can you
        ask how many entries a key reaches.

        It costs half the yield: of 14,023 reachable entries, 7,019 remain. It is paid.

        It is also only glued to an entry with **one sense and no example of its own**. With
        several it is unknown which it illustrates (D-132's and D-135's rule); and if it already
        has the example the source ATTRIBUTED, that one wins: it comes with a sense, the corpus one
        merely contains the word.
        """
        if not self._sentences:
            return {}
        cur = self.connection
        cur.execute("CREATE TEMP TABLE frase (norm TEXT PRIMARY KEY, texto TEXT) WITHOUT ROWID")
        cur.executemany("INSERT OR IGNORE INTO frase (norm, texto) VALUES (?, ?)",
                        self._sentences.items())
        # `HAVING COUNT(DISTINCT id) = 1` is the whole rule. The union looks at lemma and form,
        # which are the two paths by which a typed word reaches an entry.
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
        """Adds WordNet's synonyms and antonyms to the body. See `sources/wordnet` (D-144).

        ⚠️ **Only for entries with ONE sense.** A synset is *one* sense; with several it is unknown
        which they belong to and hanging them off the first is D-117's mistake.

        ⚠️ **And an inflection of the lemma itself is NOT a synonym.** The Spanish MCR was built
        automatically and puts "coreana, coreanos, coreanas" in "coreano"'s synset; emitting them
        would fill the watch's line with the same word declined. It is detected against `form`,
        which is a datum we already have -- which is why this runs in `finish()` and not in
        `add()`.

        The synonyms also go to `fts_def` and the antonyms do **not**, which is D-118 and D-126: a
        synonym is another way of naming what you are looking for and an antonym is what you are
        NOT looking for.
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
        """Removes the repeated, the lemma itself, and this same entry's inflections."""
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

        # Pass 2: compress and populate entry + fts_def. It iterates with a separate cursor so as
        # not to load the whole staging into memory.
        # Before opening the read cursor: `_frases_por_entrada`'s temp table cannot be created or
        # dropped with a live cursor over staging on the same connection -- SQLite returns
        # "database table is locked".
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
                # The E tag hangs off the LAST open sense, and `_admite_frase_de_corpus` has
                # already checked there is exactly one. It is appended to the already rendered
                # text rather than re-rendering: the body is the only copy and reassembling it
                # would be a second implementation of the format.
                body = (body + payload_codec.TAG_EXAMPLE + "\t"
                        + payload_codec.sanitize(frase) + "\n")
                # And to the free-text index, or searching by definition would see a different
                # pack from the one displayed. The examples already went in (D-118).
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
            # An explicit rowid: it is what ties fts_def to entry.id, and in a contentless table it
            # is the only datum the query can return.
            write.execute("INSERT INTO fts_def (rowid, body) VALUES (?, ?)", (entry_id, fts_body))

        dropped = self._materialize_translations()
        self._write_metadata(dictionary, dropped)

        self.connection.executescript("DROP TABLE staging; DROP TABLE staging_trans;")
        # The indexes are created now, over the already populated tables.
        self.connection.executescript(_read_sql("indexes.sql"))

        self.connection.commit()
        self.connection.executescript("PRAGMA optimize;")
        # VACUUM reclaims the pages the deleted staging freed and leaves the file compact and with
        # its pages in order, which is how it will be read on the watch.
        self.connection.execute("VACUUM")
        self.connection.close()
        return self.count

    def _reject_uid_collisions(self):
        """Two entries with the same logical identity: the build fails, they are not fused.

        Resolving a collision here would be worse than failing: any tie-break that depends on
        insertion order breaks precisely the stability across rebuilds entry.uid exists to give.
        If one appears, the source has to supply a `sense_key`.

        On the hash: with 63 bits and a million entries the probability of an accidental collision
        is ~3e-8. In practice this catches homographs with the same headword and the same pos,
        which are a collision of the *identity*, not of the hash.
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
        """Copies staging_trans to trans applying the per-key cap.

        It is resolved with a single statement and a window function so as not to bring the keys
        into memory: in a real pack this is millions of rows.
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
                # ⚠️ **`fuzzy_profiles` in the plural and positional against `langs`.** The
                # singular is still written --it is the primary language's-- because
                # `verify_pack.py` and the single-language packs read it, and because an old reader
                # that only understands the singular fails at `schema_version` already, not here.
                "fuzzy_profiles": _SEPARADOR_DE_LISTA.join(
                    self.fuzzy_profiles[lang] for lang in self.langs),
                "fuzzy_profile": self.fuzzy_profile,
                "payload_codec": payload_codec.CODEC_ID,
                # The compression dictionary goes in hex: the meta table is TEXT, and a BLOB would
                # force the reader to treat this key differently from the rest.
                "payload_dict": dictionary.hex(),
                # The dictionary's integrity: see dictionary_digest() in payload.py.
                "payload_dict_sha256": payload_codec.dictionary_digest(dictionary),
                # Which recipe entry.uid was computed with: an auxiliary pack built with another
                # points at the wrong entries, and without this there would be no way to notice.
                "uid_recipe": UID_RECIPE,
                # ⚠️ **`full` unless whoever builds says otherwise**, which is why the default
                # lives here and not in each caller. `build_core.py` writes it as `core`.
                #
                # It exists because today a core steps aside through `subset_of`, which somebody
                # else's pack can declare wrongly or not declare at all; `tier` says **what** the
                # pack is, just as `pack_id` says who it is (D-138). The roadmap already
                # recommended it over inferring it from the name ending in `-core`, which is
                # guessing from the name what D-138 decided gets declared.
                "tier": values.get("tier", "full"),
                "entry_count": str(self.count),
                "built_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                "data_version": data_version(),
                # To see, on real data, how much TRANS_MAX_PER_KEY is trimming.
                "trans_dropped": str(dropped_translations),
            }
        )
        self.connection.executemany(
            "INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)", sorted(values.items())
        )

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        # A half-built pack is worse than none: it would open with no error and return incomplete
        # results. It applies to finish()'s validation failures too --a uid collision, for
        # instance-- which happen with the file already nearly assembled.
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


# Combined cap of synonyms plus antonyms per sense. It is the same watch line as
# `MAX_SYNONYMS_PER_SENSE` in the source; it is repeated here because this merge does not go
# through there.
MAX_TESAURO_POR_ACEPCION = 4


def _tiene_una_acepcion(body):
    return [linea[0] for linea in body.split("\n")
            if len(linea) > 1 and linea[1] == "\t"].count(
        payload_codec.TAG_SENSE) == 1


def _terminos_ya_presentes(body):
    """The normalized keys of the synonyms and antonyms the body already carries.

    Normalized and not literal: the wiki can bring "gelido" and WordNet "gélido", and repeating
    them would spend two lines to say the same thing.
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
    """The rendered body has ONE sense and no example of its own.

    It is read from the payload's text and not from the original `senses` because in pass 2 the
    body is all that is left: staging stores the text, not the structure. It is two line counts.
    """
    lineas = [linea[0] for linea in body.split("\n")
              if len(linea) > 1 and linea[1] == "\t"]
    return lineas.count(payload_codec.TAG_SENSE) == 1 and payload_codec.TAG_EXAMPLE not in lineas


def _fts_body(senses):
    """The text indexed for free-text search.

    Glosses, examples, translations and synonyms go in without the format's tags: the tags are not
    words anybody will search for and they only dirty the index.

    The synonyms go in because of D-118: if they only went to the payload, they would be visible
    only on OPENING an entry you had already found, which is when they are no longer needed.
    Searching "bobo" has to find "chulengo".

    **The antonyms do NOT go in, and that is a decision, not an oversight** (D-126). Searching
    "frio" to find "caliente" is not something anybody does: putting them in the index would add
    noise to a search whose ORDER is already open debt (D-067), and the wrong entry it would return
    would moreover be the one that means the opposite of what was sought. A test pins that.

    **The related words do not go in either** (D-132), by the same criterion: nobody types
    "camelido" expecting "guanaco". They go only to the payload, where they are read on opening the
    entry, which is when they help. A test pins that.

    **And the example's CITATION does not either**, although the example does. A citation is
    provenance and not meaning: searching "Richard Marsh" must not return `Thomas`. It would also
    be the third case of the mistake D-117 measured --the synonyms cost three times the estimate
    because `fts_def` indexes them on top of the payload-- and over the English pack the citations
    weigh nearly as much. A test pins that, for the same reason the antonyms have one.
    """
    parts = []
    for sense in senses:
        parts.append(sense.get("gloss", ""))
        parts.extend(
            payload_codec.example_text(item) for item in sense.get("examples", ())
        )
        parts.extend(sense.get("translations", ()))
        parts.extend(sense.get("synonyms", ()))
    return " ".join(part for part in parts if part)


def _read_sql(filename):
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), filename)
    with open(path, encoding="utf-8") as handle:
        return handle.read()
