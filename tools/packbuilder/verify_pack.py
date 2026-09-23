"""Verifies a built pack's invariants.

    python3 verify_pack.py path/to/pack.db
    python3 verify_pack.py --como-la-app pack.db [...]   # what the APP checks, and nothing more
    python3 verify_pack.py pack.db --frecuencias <list>  # plus, recompute meta.corpus_coverage

It runs over the final pack, not over the builder: it checks the artifact that will really be
downloaded to the watch. A half-built pack, or one whose normalization has drifted, opens with no
error at all and returns fewer results than it should, so these checks are the only place where
that problem becomes visible.

`--como-la-app` answers another question, the one before putting a pack on the watch: *if I
install it, does it appear?*. It runs **exactly** the checks `PackFile.open` would reject the pack
by, in the same order, and prints the `PackRejection` the user would see. It accepts several packs
because the natural question is "do all the ones I am about to upload pass?".

⚠️ **It is a mirror, this repo's fourth, and it is born with an enforcer**: `audit_dictionary.py`
compares its reasons against Kotlin's `PackRejection` enum. See [MOTIVOS_DE_LA_APP].

It exits with code 1 if anything fails.
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

# The lowest rank (= most common) a proper noun can have in a 'definitions-only' pack.
#
# It mirrors `CASTIGO_NOMBRE_PROPIO` from sources/kaikki.py, and mirroring it is the point: the
# builder applies the penalty and this checks the ARTIFACT, which is the only thing published. See
# the check.
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
    # Which content policy was applied (D-116). It goes in REQUIRED_META and not only in the code
    # because the pack has to be able to explain itself: without this key nobody knows whether a
    # pack is missing its proper nouns because that was decided, or because the source came broken.
    "proper_nouns",
    "schema_version",
    # It was documented in docs/formato-pack.md and was NOT required: a pack with no source_url
    # passed the validator and afterwards there was no way to know which dump it came from.
    "source_url",
    "uid_recipe",
)

# The three values `meta.proper_nouns` can take. They are listed here --and not inferred from the
# `if`-- because the structural check is OPTIONAL by design: "included" checks nothing, and without
# this list a typo like "lexical_only" is indistinguishable from "included". That is, the pack
# declares itself pruned, the validator does not count a single proper noun, and it comes out
# green.
# The `pack_id`'s grammar, which is the pack's CODE (D-138).
#
#     <language>-<type>-<source>[-<variant>]*
#     es-def-wikc            Spanish, definitions, from Wiktionary
#     es-def-wikc-tat        the same, with Tatoeba sentences
#     en-def-wikt            English, definitions, from Wiktionary
#
# ⚠️ **It exists because of the collision, not out of tidiness.** Since D-136 two packs of the same
# language and different sources are installed and queried together: they share a language and a
# type, and **the only thing that separates them is the source code**. With a generic `pack_id`
# --"espanol", "dict"-- one overwrites the other on installation, and the watch's history and saved
# words end up pointing at entries of a pack that is no longer there. It is a failure that does not
# throw: the pack that remained opens and works.
#
# The language is two letters (ISO 639-1); the type is `def` or `tr`; the source and the variants
# come from `build_pack.FUENTES`'s catalog and from the CLI's options.
#: The two shapes a `pack_id` can have, and they are two for a reason.
#:
#: ⚠️ **`<language>-<tier>` is the NEW shape (D-215)**, and it exists because the old one tied the
#: identity to the sources: `es-def-wikc-tat-freq-wn-wd` changes its id **on adding a source**, and
#: then the pack looks like another one and the app does not recognize it as the one already
#: installed. The sources are still declared in `meta.sources`, which is where they get consulted.
#:
#: The old shape `<language>-<type>-<source>` is still accepted because the **bilingual** pack has
#: no tiers --its purpose is another-- and because the already built packs use it.
NIVELES_DE_PACK = ("core", "main", "full")
GRAMATICA_DE_PACK_ID = re.compile(
    r"^[a-z]{2}-(?:(?:core|main|full)|(?:def|tr)-[a-z0-9]{2,8}(?:-[a-z0-9]{1,16})*)$"
)

# How many fields a `meta.sources` row has. It mirrors PackSource.CAMPOS in Kotlin.
CAMPOS_DE_FUENTE = 5

POLITICAS_DE_NOMBRES_PROPIOS = ("excluded", "lexical-only", "definitions-only",
                                "included")

REQUIRED_INDEXES = ("idx_entry_norm", "idx_entry_fuzzy")

# How many entries get decompressed to check the payloads. Decompressing the whole pack on a real
# dictionary would take minutes; a random sample detects the same thing.
PAYLOAD_SAMPLE = 200

#: The list to check `meta.corpus_coverage` against, or None. `main` sets it from the CLI.
FRECUENCIAS_PARA_VERIFICAR = None

# The tags the format defines today. They are listed and not derived from `dir(payload_codec)` so
# adding one is an explicit act: a new tag has to go in here **and** in the Kotlin mirror.
TAGS_CONOCIDOS = frozenset((
    payload_codec.TAG_PART_OF_SPEECH,
    payload_codec.TAG_SENSE,
    payload_codec.TAG_EXAMPLE,
    payload_codec.TAG_CITATION,
    payload_codec.TAG_TRANSLATION,
    payload_codec.TAG_SYNONYM,
    payload_codec.TAG_FORM,
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
    # The app parses these three as integers (`PackFile.parseMetadata`). A value that is not one
    # does NOT fail here or while building: it fails on OPENING the pack, on the watch, with a
    # NumberFormatException that does not name the key. It really happened: the first real pack was
    # built with data_version = "2026-09-15" and this file came out green.
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
    # ⚠️ **No `entry.lang` may fall outside what is declared.** It is the invariant that makes the
    # column useful: the app filters by language with it, so an entry with a language the pack does
    # not declare **never appears** -- with no error, no log, and the pack passing everything else.
    # It is the same class of failure as `norm()`.
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
        # ⚠️ **And it has entries of both, which is what makes it bidirectional BY CONSTRUCTION.**
        # Without this a pack can declare itself bilingual with the other language empty, which is
        # exactly what it was before: `dog` found `perro` through `trans` but `dog` was not a
        # lemma, and nothing in the artifact said so.
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

    # The content policy is checked against the ARTIFACT, not against the flag that asked for it. A
    # miswired flag passes the source's tests and leaves the pack with the proper nouns inside all
    # the same; the only thing that catches it is counting rows in the finished pack.
    #
    # BOTH `pos` vocabularies: kaikki emits "name", sources/toy.py emits "proper noun". Excluding
    # only one lets the other through, and that already happened once.
    pack_id = meta.get("pack_id") or ""
    report.check(
        bool(GRAMATICA_DE_PACK_ID.match(pack_id)),
        "meta.pack_id es un codigo y no un nombre generico (%r; forma <idioma>-<nivel> "
        "para los packs por idioma, o <idioma>-<tipo>-<fuente> para el bilingue)" % pack_id,
    )

    # The manifest: what each source contributed and under which licence. Without this the pack
    # opens, searches and works, and **there is no way to know whether it can be redistributed** --
    # which is exactly what somebody receiving a 68 MB .db needs to answer without asking anybody
    # (D-138).
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
    # Declaring the source and staying silent about the licence is WORSE than declaring nothing: it
    # looks complete.
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
        # This policy lets proper nouns in on purpose, so the proportion ceiling does not apply.
        # What makes it safe is something else, and that is what gets checked: **that none of them
        # can beat a common word on rank**. If the penalty is miswired the pack comes out whole,
        # opens with no error and returns the toponym at the top -- the failure mode D-116 measured
        # 4,267 times in English. There is nothing else that sees it.
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
            # 'lexical-only' lets through the ones with lexical life: the months, the countries,
            # the languages. That signal cannot be recomputed here --we do not have the dump-- so
            # the only thing visible from the pack is checked: that they be a MINORITY. Measured:
            # 0.2 % in English and 0.6 % in Spanish, against 17-22 % in an unpruned pack. The
            # margin is so wide that the threshold needs no fine calibration; what it catches is
            # the pruning not having run at all.
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

    # ⚠️ **The declared coverage gets RECOMPUTED, or it is worth nothing.** A tier writes into
    # `meta.corpus_coverage` what fraction of the corpus's tokens it holds inside, and that number
    # is the only thing justifying its cut (D-219). Declaring it without checking it turns it into
    # an intention: the artifact gets trimmed badly and goes on saying it covers 96 %.
    #
    # Without the list it CANNOT be checked, and then that is said rather than swallowed.
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
    # SQLite does not enforce foreign keys here (the tables do not declare them, so as not to pay
    # the check on every builder insert), so it is verified explicitly.
    for table in ("form", "trans"):
        orphans = db.execute(
            "SELECT COUNT(*) FROM %s t WHERE NOT EXISTS"
            " (SELECT 1 FROM entry e WHERE e.id = t.entry_id)" % table
        ).fetchone()[0]
        report.check(orphans == 0, "%s no tiene entry_id huerfanos" % table)

    # ⚠️ **NOBODY was looking at `form`'s and `trans`'s keys, and they are the way in for 1,499,895
    # words in the Spanish pack.** D-142 recomputes a sample of `entry`, but the `form` table is the
    # one that resolves an inflection --`palpitaciones` -> `palpitacion`-- and one of its keys built
    # under other rules is exactly the repo's central failure mode: the word is in the file and no
    # search reaches it.
    #
    # It is checked by **idempotence** (`norm(k) == k`) and not by recomputing from the original
    # form, because the original form is not stored: the table is `(norm, entry_id)` and nothing
    # more. That detects a key folded with another Unicode, with another casefold or without NFD;
    # it does not detect a key that is the correct `norm()` of ANOTHER word. It bounds, it does not
    # eliminate -- the same deal D-142 made with the sample.
    #
    # Measured: **7.6 s** over the Spanish pack's 1,309,880 distinct keys and **4.3 s** over
    # English's 801,758. Expensive for a watch and cheap for an hour-long build, which is precisely
    # why it lives here and not in `PackFile`.
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
    # An empty `norm` and an empty `fuzzy` are NOT the same problem, and treating them alike made
    # the first real pack fail over two legitimate entries: "h" and "H", the letter. With no `norm`
    # the entry is unreachable by any path. With no `fuzzy` it merely falls outside the tolerant
    # rung, which is exactly right for a one-silent-letter lemma.
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
    # The most important check in the file. If the pack was built with another version of
    # normalize.py, the stored keys are not the ones the app will compute and words would simply be
    # missing, with no error at all.
    # One profile per language: in a bidirectional pack the English entries fold with the English
    # profile and the Spanish ones with Spanish's, in the same file.
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
    # entry.uid is the key an auxiliary pack adds information to these entries by (D-055). If it
    # repeats, the auxiliary points at two entries at once; if it does not match the recipe, it
    # points at the wrong one. Neither of those produces an error on the watch.
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

    # The recipe is recomputed only where it can be: the sense_key that disambiguates homographs is
    # not stored in the pack, so the entries sharing a (headword, pos) are skipped. In a real pack
    # they are a minority and the rest is covered.
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
    # ⚠️ **With `entry.lang` and not with the pack's language.** In a bidirectional pack `casa` and
    # `house` live in the same file and their uid carries different languages -- which is exactly
    # what stops them colliding across packs (D-055).
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
    # deflate does not detect a wrong dictionary: it decompresses with no error and returns corrupt
    # text. This hash is the only defence, and the app checks it when opening the pack.
    report.check(
        payload_codec.dictionary_digest(dictionary) == meta.get("payload_dict_sha256"),
        "meta.payload_dict_sha256 corresponde al diccionario guardado",
    )
    decoded = 0
    senses_total = 0
    failures_before = len(report.failures)
    # ⚠️ **Spread along the table, not the first 200.** It was `ORDER BY id LIMIT 200`, which is
    # exactly what D-142 argues is no use: a pack correct only in its first rows --which happens if
    # somebody built half with one version and half with another-- passed whole. And in a
    # BIDIRECTIONAL pack it is worse still: the reverse entries live in the table's second half
    # (D-196), so the old sample did not look at a single one.
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
            # ⚠️ **With no senses it is accepted ONLY if it carries word translations**, and that
            # exception is a bidirectional pack's reverse side: `dog` answers *"how is it said"*
            # --`perro`, `can`-- and not *"what does it mean"*, which is the English monolingual
            # pack's job. What is still forbidden is an EMPTY entry: it takes a row, appears in the
            # list, and on opening it there is nothing.
            if not senses and not palabra:
                report.check(
                    False,
                    "la entrada %s no tiene ni acepciones ni traducciones" % row["headword"])
            # ⚠️ **Every sense has to be reachable by `(language, word, sense)`.** The code comes
            # from `(uid, gloss)`, so two senses of the same entry with an identical gloss share a
            # code and one becomes **unreachable** -- a link written against it leads to the other,
            # with no error and no log. `payload.merge_duplicate_senses` prevents it while
            # building; this checks it over the bytes, which is the only thing that counts for a
            # pack we did not build.
            # ⚠️ **Over the BYTES and not over `senses`, and that is where the value is.**
            # `payload.parse` already discards the orphaned citation --the right degradation for
            # the reader-- so looking at the parsed structure can never see the problem. A pack
            # built by somebody else with the citation displaced would show an example without the
            # attribution the pack says it carries, or worse, would hang it off the wrong example
            # if somebody relaxed the rule. It is the first payload CONTENT check this validator
            # has.
            huerfanas = _citas_huerfanas(text)
            if huerfanas:
                report.check(False,
                             "la entrada %s tiene %d cita(s) que no siguen a un ejemplo"
                             % (row["headword"], huerfanas))
            # ⚠️ **No list repeats an item** (D-218). Sweeping the built packs found it: 3.1 % of
            # the bilingual's entries with word translations repeated a term --`where` carried
            # `donde, donde`-- and in a watch row that is the same word twice, in a width that is
            # already being clipped. `payload.render` deduplicates it while building; this checks it
            # over the BYTES, which is the only thing that counts for a pack we did not build.
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

            # ⚠️ **No unknown tag.** The reader ignores them on purpose --it is what allows adding a
            # field without breaking an old app (D-119)-- and for that very reason a tag the builder
            # wrote wrongly is invisible: it throws nothing, logs nothing, and its content is never
            # seen. Here is the only place it can be noticed.
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
    """How many of the body's `C` lines do not come immediately after their `E`.

    An exact mirror of `payload.parse`'s and `PayloadCodec.parse`'s rule. If the three drifted
    apart, the same pack would show different attributions depending on who read it, and this
    validator would say it is fine.
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



#: Where the lists of words a pack MUST find live.
VECTORES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "vectors")


#: The directive a list group can carry in its header.
#:
#: ⚠️ **It exists because not every word binds every tier, and confusing that breaks the check in
#: both directions.** Measured over `freq-en-opensubs.txt`: `blockchain`, `deepfake` and
#: `workaround` have **zero** occurrences, so a frequency cut **cannot** bring them -- and the group
#: containing them does not defend the cut: it defends that the SOURCE carries today's vocabulary
#: (D-120), which is a property of the full pack. Requiring them of a `core` turns the check into
#: noise somebody ends up switching off; requiring them of nobody loses the signal.
#:
#: What carries NO directive binds every tier, and that is the right default: the months, the verbs
#: and everyday vocabulary are frequent, so if they are missing **the cut is broken**.
DIRECTIVA_SOLO_COMPLETO = "#! solo el pack completo"


def lista_de_cobertura(lang):
    """What `lang` requires, as `(word, binds_every_tier)`; None if it declares no list."""
    ruta = os.path.join(VECTORES, "cobertura-%s.txt" % lang)
    if not os.path.exists(ruta):
        return None
    palabras = []
    todo_nivel = True
    with open(ruta, encoding="utf-8") as handle:
        for linea in handle:
            linea = linea.strip()
            if not linea:
                # The blank line closes the group, so the directive does not leak into the next
                # one. It is the same separation the file already uses to group.
                todo_nivel = True
                continue
            if linea.startswith("#"):
                if linea == DIRECTIVA_SOLO_COMPLETO:
                    todo_nivel = False
                continue
            palabras.append((linea, todo_nivel))
    return palabras


def _verify_vocabulary(db, meta, report):
    """That the words the language requires can be found.

    ⚠️ **It is the only thing that asks whether the CONTENT is any good**, and that is why it
    exists. Everything else in this file checks invariants: that the indexes are there, that
    `fts_def.rowid` is `entry.id`, that `norm` matches. None of that knows **which words a
    dictionary ought to have**, which is why two real failures got through with the gate green,
    `verify_pack.py` green and the tests green: 39.6 % of the Spanish pack's entries defined
    nothing, and the `pos = name` pruning deleted **6 of the 12 months in English**.

    ⚠️ **It only runs if the language's list exists, and that is on purpose**: a flag gets
    forgotten exactly the time it matters. A language with no list is reported as such rather than
    passing in silence, because "there is no list" and "the list passes" are not the same thing.

    ⚠️ **A word counts if it is a lemma OR an inflected form.** `fui` reaches `ir` through the
    `form` table, and that is exactly what the user experiences when searching for it: requiring it
    to be a lemma would turn the check into one about the source's lemmatization, which is
    something else.
    """
    idiomas = [x.strip() for x in (meta.get("langs") or "").split(",") if x.strip()]
    # ⚠️ **A bilingual is required to satisfy the language it is a dictionary OF, which is the
    # SOURCE one.** Its target side exists for the reverse direction --`went` reaches `go` through
    # the `form` table (D-196)-- not to be a dictionary of that language: its entries are the ones
    # some source word translated to. Requiring the full list of it measures a promise that pack
    # never made. The rebuild found it: `es-en` failed on `tuesday` because `martes` carries its
    # translation **glossed inside the sense** rather than as a clean term.
    #
    # ⚠️ **But it is not silenced, it is reported.** That signal is real --searching `tuesday` in
    # the bilingual returns nothing-- and silencing it would lose exactly what this check exists to
    # see.
    exigidos = idiomas[:1] if meta.get("kind") == "bilingual" else idiomas
    for lang in idiomas:
        palabras = lista_de_cobertura(lang)
        if palabras is None:
            report.note("no hay vectors/cobertura-%s.txt: el contenido de ese idioma no se "
                        "comprueba" % lang)
            continue
        # ⚠️ **A pack with fewer entries than the list has words is not a dictionary**, it is a
        # fixture --the toy one has 82 entries-- and over it this check would measure size and not
        # coverage. The rule scales itself with the list instead of pinning a number.
        entradas = db.execute("SELECT COUNT(*) FROM entry").fetchone()[0]
        if entradas < len(palabras):
            report.note("%s: %d entradas para una lista de %d palabras; es un fixture, no se "
                        "comprueba la cobertura" % (lang, entradas, len(palabras)))
            continue
        # ⚠️ A derived tier only answers for what binds every tier. See
        # [DIRECTIVA_SOLO_COMPLETO]: asking a frequency cut for a word with no frequency is asking
        # it for something its metric cannot deliver.
        es_completo = meta.get("tier", "full") == "full"
        exigidas = [p for p, todo_nivel in palabras if todo_nivel or es_completo]
        faltan = []
        for palabra in exigidas:
            clave = normalize.norm(palabra)
            hay = db.execute(
                "SELECT 1 FROM entry WHERE norm = ? UNION ALL"
                " SELECT 1 FROM form WHERE norm = ? LIMIT 1", (clave, clave)
            ).fetchone()
            if not hay:
                faltan.append(palabra)
        detalle = "las %d palabras que exige %s estan en el pack%s" % (
            len(exigidas), lang,
            "" if not faltan else " (faltan %d: %s)" % (
                len(faltan), ", ".join(faltan[:12]) + (" ..." if len(faltan) > 12 else "")))
        if lang in exigidos:
            report.check(not faltan, detalle)
        elif faltan:
            report.note("%s es el idioma DESTINO de un bilingue, asi que esto no reprueba: %s"
                        % (lang, detalle))


def _verify_query_plans(db, report):
    """Confirms that the prefix search uses the covering index.

    It is the design's central claim: if the plan changes to a table scan, the incremental search
    stops meeting the latency budget and nothing else would notice.
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
    """Exercises the five search paths over the real pack.

    These are the reference queries :dict-data implements. Two details that were discovered here
    and are easy to write wrongly:

      - The reverse one needs DISTINCT per entry: the prefix range matches several keys of the
        same entry ("to", "to run", "to pass") and without deduplicating it comes out repeated.
      - The tolerant rung queries by a PREFIX of the fuzzy key, not by the whole key, so as to
        bring a neighbourhood and not only the exact collisions.
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

    # A word that exists in the indexed text, taken from the pack itself.
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
        # NO LIMIT, and that is the point of the check. The invariant is that FTS **find** the
        # entry, not that it rank it high: bm25 penalizes long glosses, so a correct entry with an
        # extensive definition and a very frequent term falls outside the top 30. It happened with
        # the English pack --"you" for "people", position 721 of 890-- and the check was failing
        # over a healthy pack.
        #
        # The real failure mode is still covered: if `fts_def.rowid` drifted from `entry.id`
        # (D-011), the entry would appear at NO position.
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
    """A mirror of PrefixRange.upperBound for the verification queries."""
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
    """Bytes per table and index, to see where the pack's size goes."""
    try:
        rows = db.execute(
            "SELECT name, SUM(pgsize) FROM dbstat GROUP BY name ORDER BY 2 DESC"
        ).fetchall()
        return [(row[0], row[1]) for row in rows]
    except sqlite3.OperationalError:
        # dbstat is an optional module; if the local sqlite does not ship it that is not the pack's
        # failure.
        return [("(dbstat no disponible en este sqlite)", 0)]


# ---------------------------------------------------------------------------------------------
# The mirror mode: "would this app reject this pack, and with what reason?"
# ---------------------------------------------------------------------------------------------
#
# THIS BLOCK HAS A MIRROR:
#     dict-core/src/main/kotlin/cl/fadiaz/dictionary/core/Model.kt -> PackRejection
#     dict-core/src/main/kotlin/cl/fadiaz/dictionary/core/PackIntegrity.kt -> checkMeta
#     dict-data/src/main/kotlin/cl/fadiaz/dictionary/data/PackFile.kt -> open
#
# ⚠️ **It is this repo's FOURTH cross-language contract, and it is born with an enforcer because
# the other three taught that without one they drift apart.** `tools/audit_dictionary.py` compares
# [MOTIVOS_DE_LA_APP]'s ids against `PackRejection`'s, in the same order: adding a reason in Kotlin
# without adding it here --or changing the order on only one of the two sides-- breaks the gate.
#
# ⚠️ **And the ORDER is part of the contract, not a coincidence.** Every check rejects (D-217), so
# the order does not decide whether a pack gets in: it decides **which reason gets reported**,
# which is the only line the user reads. The data directory's seven `schema_version` 3 packs came
# out as "incomplete metadata" instead of "another version of the format" precisely because the
# order was the wrong way round.
#
# What this mode is NOT: a replacement for `verify()`. That one looks at far more --it recomputes
# ALL the keys, cross-checks `uid`, checks query plans-- because it runs at build time and can
# spend seconds. This one answers a single question, the one that matters before putting a pack on
# the watch: *if I install it, does it appear?*

# Cuantas filas de `form`/`trans` mira el modo espejo buscando huerfanas.
# Espeja ORPHAN_SAMPLE_SIZE de PackFile.kt.
APP_ORPHAN_SAMPLE = 64

# Cuantas entradas recalcula el modo espejo. Espeja KEY_SAMPLE_SIZE de PackFile.kt.
APP_KEY_SAMPLE = 64

# The keys `PackIntegrity.REQUIRED_META` requires. **It is not [REQUIRED_META]**, which is what
# this verifier asks for on top: the app needs neither `built_at` nor `proper_nouns` to open.
APP_REQUIRED_META = (
    "pack_id", "schema_version", "norm_version", "kind", "name", "langs",
    "fuzzy_profiles", "entry_count", "data_version", "license", "attribution",
    "payload_dict", "payload_dict_sha256", "payload_codec",
)


def _app_metadata(meta, db):
    """`METADATA`: without these keys you cannot even say what file this is."""
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
    """`SCHEMA_VERSION`: looked at BEFORE the keys. See the ⚠️ in the header."""
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
    """`LICENSE`: D-031 says attribution is not optional, so being uncreditable rejects."""
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
    """`KEYS`: D-142's spread sample of 64, recomputed with the builder's code."""
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
    """`DAMAGED` is not checked: it is what is left when opening the file throws."""
    return None


# ⚠️ **The order is `PackFile.open`'s, and `PackRejection`'s.** The audit compares this table's ids
# against the enum, in order. See the ⚠️ in the block's header.
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

# EVALUATION order is not declaration order: `PackIntegrity.checkMeta` looks at the schema before
# the required keys --it is the key that says which other keys have to exist-- and the enum is
# declared in the order the reasons are read, not the order they are evaluated.
ORDEN_DE_EVALUACION = (
    "schema", "metadata", "norm", "codec", "license", "index", "staging",
    "count", "fts", "emptykey", "orphan", "keys", "dict",
)


def como_la_app(path):
    """Runs over the pack what the app runs when opening it. 0 if it would accept it, 1 if not."""
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
        # Several packs at once: the natural question is "do ALL the ones I am about to upload
        # pass?".
        return max(como_la_app(p) for p in argumentos)
    if len(argumentos) != 1:
        print(__doc__)
        return 2
    return verify(argumentos[0])


if __name__ == "__main__":
    sys.exit(main(sys.argv))
