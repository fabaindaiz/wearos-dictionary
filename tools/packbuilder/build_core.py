#!/usr/bin/env python3
"""Derives a CORE pack from an already built full pack.

    python3 tools/packbuilder/build_core.py <full.db> <core.db> <corpus.tsv> [--top N]

## Why it is derived and not built in parallel

The roadmap had proposed a filter inside `build_pack`, that is, rebuilding from the dumps with less
vocabulary. Deriving from the full pack is better for two reasons, and the first is correctness:

1. ⚠️ **The core declares `subset_of`, and by deriving it that is true BY CONSTRUCTION.** Built in
   parallel it would be true only while both runs used the same sources, the same filters and the
   same pruning -- a promise nothing checks and that breaks in silence the first time somebody adds
   an option to only one of them.
2. It takes seconds instead of an hour, and it does not need the 4.5 GB of dumps.

## The trap this avoids, measured

**It is not chosen by `rank`.** `rank` is richness of the dictionary's page, not frequency of use:
a 14,388-entry core chosen by `rank` takes **91 % of the Spanish pack's inflections table**,
because the richest pages are the verbs and a verb has 33 forms. Chosen by frequency of use, the
top 8,000's 7,349 entries take **5.5 %**. Sixteen times less.

The signal is `sources/tatoeba.frequencies`, which counts over the corpus with the same tokenizer
and the same `norm()` that indexes the pack.
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

# How many words get in by default.
#
# ⚠️ **Measured over both real corpora, and English sets the ceiling.** At position 8,000 a Spanish
# word appears in 21 sentences and an English one in 5; at position 15,000, in 9 and in 2. Beyond
# ~8,000 the English corpus --41,512 sentences against Spanish's 442,135-- stops being evidence and
# becomes noise. The same N is used for both so "core" means the same thing in both languages.
TOP_POR_DEFECTO = 8000

# The keys the builder writes and that cannot be inherited (see PackBuilder.__init__).
DERIVADAS = {
    "schema_version", "norm_version", "payload_codec", "payload_dict", "payload_dict_sha256",
    "entry_count", "built_at", "uid_recipe", "data_version", "trans_dropped",
}


def vocabulario_del_corpus(corpus, lang, top=TOP_POR_DEFECTO):
    """The corpus's `top` most used words, as `norm()` keys."""
    from sources import tatoeba

    frecuencias = tatoeba.frequencies(corpus, lang=lang)
    orden = sorted(frecuencias.items(), key=lambda par: (-par[1], par[0]))
    return {palabra for palabra, _veces in orden[:top]}


#: The three sizes, and what separates each. See D-215.
#:
#: ⚠️ **`full` is not a derived tier**: it is the pack that was built, unfiltered and completely
#: correct. It is here only so the name marks it like the other two.
NIVELES = ("core", "main", "full")


def frecuencias_por_norm(lista):
    """The frequency list, with the keys the pack uses. See `frequency.por_norm`."""
    return _frequency.por_norm(_frequency.load(lista), normalize.norm)


def vocabulario_por_presupuesto(completo, presupuesto_mb, frecuencias=None):
    """The lemmas that fit in `presupuesto_mb`, **the most used first**.

    ## Which metric decides, and why that one

    The question a tier has to answer is not *"how many words fit?"* but *"what fraction of what
    somebody will look up is inside?"*. That gets measured: **corpus token coverage** -- the sum of
    the frequencies of the lemmas that got in, over the total. It is verifiable over the finished
    artifact, not an intention, which is why it is written to `meta.corpus_coverage` and
    `verify_pack.py` recomputes it.

    ## The order: frequency first, `rank` behind, and that is MEASURED

    ⚠️ **Ordering by `rank` is worse, even though `rank` already comes from frequency.** Two
    reasons, and both show in the number:

    1. `rank` is **bucketed** --`int(round(zipf * ESCALA_ZIPF))`, 500 buckets-- so thousands of
       words share a number and the tie-break is **alphabetical**. A fat word starting with `a`
       displaces a more used and thinner one.
    2. Past 500, `rank` **stops being frequency**: it is page richness, which correlates **-0.250**
       with real usage (D-185). A budget that reaches there spends on long pages.

    Token coverage measured over the real packs:

    | | by `rank` | by frequency | delta |
    |---|---|---|---|
    | English 25 MB | 93.46 % | **94.43 %** | +0.97 |
    | English 40 MB | 94.75 % | **96.03 %** | +1.28 |
    | English 130 MB | 95.10 % | **96.63 %** *(= the full pack)* | +1.53 |
    | Spanish 25 MB | 77.59 % | **78.87 %** *(= the full pack)* | +1.28 |
    | Spanish 40 MB | 77.62 % | **78.87 %** | +1.25 |

    And **more** lemmas get in, not fewer. Two readings worth having: a **25 MB** Spanish core
    **covers as much as the full 74 MB pack**, and a 130 MB English `main` reaches the coverage of
    the 307 MB one. The ceiling is the list: beyond the ~50,000 words it attests, adding lemmas
    does not raise the coverage -- what rises is what gets found when searching for something rare,
    which is another metric and is not here.

    ⚠️ **`frecuencias` is optional and without it the cut is by `rank`, as before.** That is not an
    oversight: `build_core` derives from an already built pack and can be run over any one without
    having to hand the corpus it was made with. Degrading to the old criterion beats failing.

    ⚠️ **What has NO signal goes behind everything that does**, ordered by `rank` among themselves.
    Over the English pack that is **95.5 %** of the lemmas: the list covers 38,067 of 842,026.

    ⚠️ **A set of `norm` is returned and not of ids, on purpose.** That way a homograph gets in
    whole or not at all: keeping half of `banco` would lose a sense with no warning.

    ⚠️ **The budget is estimated, not measured.** The file weighs more than its payloads --indexes,
    FTS and the forms table-- so it is scaled by the ratio the source pack has. Measured: Spanish
    weighs 7.2x its payloads (`form` dominates it, 44.6 % of the file) and English 3.8x (`entry`
    dominates). They are two different shapes, which is why the factor comes from the pack and not
    from a constant.
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

        # ⚠️ **By LEMMA and not by row**, and that is the difference from the listless mode. A
        # homograph gets in whole (`banco` the bench and `banco` the bank), so what it costs is the
        # sum of its payloads; charging row by row mixed the order of two different lemmas.
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
    """A derived tier's meta: the full one's, plus what declares it a subset."""
    if tier not in NIVELES:
        raise ValueError("nivel desconocido %r; los que hay son %s" % (tier, ", ".join(NIVELES)))
    salida = {k: v for k, v in meta.items() if k not in DERIVADAS}
    completo = salida["pack_id"]
    # ⚠️ **The identity is LANGUAGE + TIER, not the sources.** It used to be
    # `es-def-wikc-tat-freq-wn-wd`, that is, the list of what had been merged, and that makes
    # **adding a source change the identity**: the pack looks like another one and the app does not
    # recognize it as the one already installed. The sources are still declared in `sources`, which
    # is where they get consulted. Asked for: *"packs are by language and in versions"*.
    idioma = (salida.get("langs") or salida.get("lang_src") or "").split(",")[0].strip()
    salida["pack_id"] = "%s-%s" % (idioma, tier) if idioma else completo + "-" + tier
    # ⚠️ **The key that lets both be installed with no extra work** (D-171): with the full one
    # present, the app does not ask the core. It is a claim about CONTENT, and by deriving the core
    # from the full one it is true by construction.
    salida["subset_of"] = completo
    if cobertura is not None:
        # The metric that justifies the cut, in the artifact and not in a changelog. Two decimals
        # because the difference between two strategies plays out in the first.
        salida["corpus_coverage"] = "%.2f" % cobertura
    if lemas is not None:
        # La segunda metrica. Ver [_fraccion_de_lemas]: la primera satura y no puede justificar
        # un nivel grande.
        salida["lemma_coverage"] = "%.2f" % lemas
    # ⚠️ **And `tier`, which says the same thing without naming anybody.** `subset_of` claims *"I
    # am part of THAT pack"* and serves when the full one is installed; `tier` claims *"I am a
    # core"*, which is what is needed to decide without knowing the other. Both are declared
    # because they answer different questions, and both are true by construction when deriving.
    salida["tier"] = tier
    # ⚠️ **The tier goes in the NAME**, an explicit request: *"do have this marked in the name, for
    # instance español (core) or english (main)"*. And with the token in English --`core`, `main`,
    # `full`-- and not translated, because it is the tier's identifier and has to read the same in
    # any interface language.
    # ⚠️ **Through `name_with_tier` and not by concatenation**: the pack it derives from ALREADY
    # carries its tier in the name --`build_pack` closes it as `Español (full)`-- so sticking
    # `(core)` on top gave `Español (full) (core)`, which is what was visible on the watch.
    salida["name"] = build.name_with_tier(salida.get("name", ""), tier)
    # The credit travels with the content (D-138): the core distributes the same definitions, so it
    # inherits the same sources. And the corpus that CHOSE the words is declared too, because
    # `sources` answers how the pack was assembled -- even though not one of its sentences is
    # distributed.
    salida["sources"] = (salida.get("sources", "") +
                         "vocabulary\tTatoeba\thttps://tatoeba.org/\tCC BY 2.0 FR\t"
                         "https://creativecommons.org/licenses/by/2.0/fr/\n")
    return salida


def _agrupar(origen, tabla):
    """`entry_id -> [norm, ...]` for the chosen entries, in a single pass over the table."""
    salida = {}
    for entry_id, norm in origen.execute(
        "SELECT entry_id, norm FROM %s WHERE entry_id IN "
        "(SELECT id FROM entry WHERE norm IN (SELECT norm FROM vocab))" % tabla
    ):
        salida.setdefault(entry_id, []).append(norm)
    return salida


def _tags(texto):
    """The set of payload tag letters the text carries."""
    return {linea[0] for linea in texto.split("\n") if len(linea) >= 2 and linea[1] == "\t"}


def _ningun_canal_se_perdio(salida, tags_origen):
    """Fails if the derived pack carries fewer entries of some channel than the source did.

    ⚠️ **It counts TAG LETTERS and not named fields, and that is the whole point.** The named
    version of this check is the list of arguments in `derive`, and that list is what already went
    wrong: `W` was dropped there and shipped for weeks in every core -- 21.9 % of the Spanish
    entries against 0 % in the derived one -- because a pack that loses a channel opens with no
    error and passes every invariant. Counting letters catches **the next** channel too, the one
    nobody has written yet, which a per-field check by construction cannot.

    The comparison is `>=` and not equality: `merge_duplicate_senses` can fuse two senses on the
    way through and legitimately leave one fewer `S`.

    It re-decompresses the whole output, which sounds expensive next to `derivar_en_rango` calling
    `derive` up to four times. Measured on `es-core` (47,952 entries, same vocabulary in both
    runs): **19.9 s without it against 20.8 s with it, +5 %**. Writing and indexing dominate.
    """
    db = sqlite3.connect("file:%s?mode=ro" % salida, uri=True)
    try:
        diccionario = binascii.unhexlify(
            dict(db.execute("SELECT key, value FROM meta"))["payload_dict"])
        tags_salida = {}
        for (blob,) in db.execute("SELECT payload FROM entry"):
            for tag in _tags(payload_codec.decompress(blob, diccionario)):
                tags_salida[tag] = tags_salida.get(tag, 0) + 1
    finally:
        db.close()
    perdidos = sorted(
        (tag, cuenta, tags_salida.get(tag, 0))
        for tag, cuenta in tags_origen.items()
        if tags_salida.get(tag, 0) < cuenta
    )
    if perdidos:
        raise ValueError(
            "la derivacion perdio canales del payload: %s. Cada tag es un dato que el pack "
            "completo trae y el derivado no, y eso no falla en ninguna invariante: se ve como "
            "un diccionario que muestra menos" % ", ".join(
                "%s %d -> %d" % (tag, antes, ahora) for tag, antes, ahora in perdidos))


def derive(completo, salida, vocabulario, tier="core", cobertura=None, lemas=None):
    """Writes into `salida` the entries of `completo` whose lemma is in `vocabulario`.

    `cobertura` is the percentage of the corpus's tokens the vocabulary covers, and **it is written
    into the artifact**: a tier that does not declare its coverage forces recomputing it to know
    whether the cut was good, and nobody does. `verify_pack.py` recomputes it and fails on a
    mismatch.
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

    # ⚠️ **The forms are read in ONE pass and not one query per entry, and the difference is
    # between seconds and never finishing.** `form` has PRIMARY KEY (norm, entry_id), so filtering
    # by `entry_id` alone **uses no index**: it is a full scan. With 986,000 forms and 16,652
    # selected entries that is 16 billion rows visited. It was discovered waiting for the English
    # pack to finish, and it did not.
    formas_por_entrada = _agrupar(origen, "form")
    traducciones_por_entrada = _agrupar(origen, "trans")

    escritos = 0
    tags_origen = {}
    with build.PackBuilder(salida, _meta_del_nivel(meta, tier, cobertura, lemas)) as constructor:
        for entry_id, uid, headword, pos, rank, blob in filas:
            # ⚠️ **Everything the payload says ABOUT THE WORD has to be read back and handed
            # over, and forgetting one is invisible.** The entry is rebuilt from its text, so a
            # channel that is not named here is not written: the derived pack opens with no error,
            # passes every invariant and shows less. Measured on the packs published 2026-09-23:
            # `es-full` carried `W` in 21.9 % of a sample and the `es-core` derived from it in 0 %.
            texto = payload_codec.decompress(blob, diccionario)
            _pos_payload, senses, traducciones_de_palabra = payload_codec.parse(texto)
            formas = formas_por_entrada.get(entry_id, ())
            traducciones = traducciones_por_entrada.get(entry_id, ())
            constructor.add(build.Record(
                headword=headword,
                senses=senses,
                part_of_speech=pos,
                rank=rank,
                forms=formas,
                translations=traducciones,
                word_translations=traducciones_de_palabra,
                display_forms=payload_codec.parse_forms(texto),
                pronunciation=payload_codec.parse_pronunciation(texto),
                # The tier filter needs no reapplying: this pack's vocabulary is a subset of the
                # full one's, which already carries the origin only where it should.
                etymology=payload_codec.parse_etymology(texto),
                # ⚠️ **The uid is COPIED and not recomputed.** It is the logical identity and the
                # join key across packs (D-055): if the core recomputed its `sense_key` counting
                # ITS homographs, an entry with a twin in the full pack could lose it and end up
                # with another identity -- which is exactly the failure D-145 found when merging.
                uid=uid,
            ))
            escritos += 1
            for tag in _tags(texto):
                tags_origen[tag] = tags_origen.get(tag, 0) + 1
    origen.close()
    _ningun_canal_se_perdio(salida, tags_origen)
    return escritos


#: How many times it re-derives searching for the range. Each round is a whole pack written, so the
#: cap exists: it converges in two or three because the real factor stabilizes as soon as it is
#: measured once, and going on trying costs more than it refines.
MAX_VUELTAS = 4

#: Where in the range it aims: **the middle**.
#:
#: ⚠️ **The file/budget ratio is NOT monotonic, which is why no constant compensates for it.**
#: Measured over the Spanish pack deriving at six budgets:
#:
#:     budget      file      ratio
#:      10 MB     6.0 MB      0.60
#:      20 MB    13.4 MB      0.67
#:      30 MB    27.4 MB      0.91
#:    37.5 MB    47.3 MB      1.26   <- the peak
#:      45 MB    51.2 MB      1.14
#:      60 MB    60.4 MB      1.01
#:
#: It rises to 1.26 and comes back down: the `form` table grows faster than the payloads --12.98
#: inflections per lemma in Spanish-- until the vocabulary starts running out and the proportion
#: normalizes. With that behaviour, **the only guarantee of the range is measuring the file and
#: going back**, which is what [derivar_en_rango] does. Aiming at the middle is what leaves it room
#: for error in both directions.
OBJETIVO_DEL_RANGO = 0.5


def derivar_en_rango(completo, salida, minimo_mb, maximo_mb, tier="core", frecuencias=None):
    """Derives a tier whose FILE falls within `[minimo_mb, maximo_mb]`.

    ## Why it converges instead of estimating once

    ⚠️ **The source pack's factor is NOT the derived one's, and that made the range go unmet.**
    `vocabulario_por_presupuesto` scales the payloads by the **full** pack's `size /
    sum(payloads)`; the derived one has another ratio --it takes its own lemmas' forms but not the
    others', and the indexes grow differently-- so asking for 25 MB gave **17.7**, below the
    range's minimum.

    A short tier is not wrong because of its size: it is wrong because **the range is the
    requirement** and the artifact does not meet it. And the real factor is only known by
    **measuring the file**, so it derives, measures, corrects and goes round again.

    ## What it returns

    A report with `en_rango`, `mb`, `vueltas` and, if it could not be done, `motivo`. ⚠️ **Not
    being able to is not an error**: a `full` smaller than the tier's minimum means that tier makes
    no sense for that language, and that gets said rather than swallowed -- if it came out silent,
    the range would stop meaning anything.
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
                       # ⚠️ **`palabras` and not `entradas`, and the label matters.** This is the
                       # size of the chosen VOCABULARY --`count(DISTINCT norm)` in the pack that
                       # comes out-- and not `entry`'s rows, which are more because a lemma with
                       # two categories is two entries. Measured over `es-core`: 39,021 words,
                       # 41,219 distinct lemmas and **48,292 rows**, which is what
                       # `meta.entry_count` declares. Calling the first "entries" made the
                       # changelog's number look as if it contradicted the artifact's, and it cost
                       # a session to leave it written as unexplained.
                       palabras=len(vocabulario))
        if minimo_mb <= mb <= maximo_mb:
            informe["en_rango"] = True
            return informe
        if mb < minimo_mb and _es_el_pack_entero(completo, vocabulario):
            # There is no more vocabulary to put in: the `full` does not reach this tier's minimum.
            informe["motivo"] = ("todo el pack entra y pesa %.1f MB, por debajo del minimo de "
                                 "%.1f MB" % (mb, minimo_mb))
            return informe
        # The real factor is measured from the file that has just come out. It is the only way to
        # know it.
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
    """What percentage of the full pack's lemmas this tier takes.

    ⚠️ **It is the SECOND metric, and it is needed because the first saturates.**
    `corpus_coverage` stops moving past the ~50,000 words the list attests: measured, English
    reaches its ceiling of 96.63 % at **57.6 MB**, so `main`'s 130 MB buy **zero** coverage by that
    yardstick. What they buy is finding the rare -- the word that is in no subtitle corpus and that
    somebody will look up anyway -- and that is precisely what this one measures.

    It does not claim to be a probability: it is a fraction of the dictionary, monotonic and
    verifiable. A metric weighted by "how likely somebody is to look it up" would need a corpus
    that does not exist today, and saying so beats inventing a number.
    """
    origen = sqlite3.connect("file:%s?mode=ro" % completo, uri=True)
    try:
        total = origen.execute("SELECT COUNT(DISTINCT norm) FROM entry").fetchone()[0]
    finally:
        origen.close()
    return 100.0 * len(vocabulario) / total if total else 0.0


def _frecuencias_de(argv):
    """The frequency list, or `None` warning what that costs.

    ⚠️ **Without the list the tier comes out measurable but WORSE**, which is why it warns rather
    than swallowing it. Measured: cutting by `rank` loses between 0.97 and 1.53 points of coverage
    against cutting by frequency, and it also puts in fewer lemmas. See
    [vocabulario_por_presupuesto].
    """
    if "--frecuencias" in argv:
        return frecuencias_por_norm(argv[argv.index("--frecuencias") + 1])
    print("  ⚠️  sin --frecuencias: se corta por rank, que cubre ~1,3 puntos menos",
          file=sys.stderr)
    return None


def main(argv):
    """Three modes, and the RANGE one is what D-215's tiers use.

        build_core.py <full.db> <out.db> --rango-mb 25 50 --tier core   # the pipeline's
        build_core.py <full.db> <out.db> --budget-mb 50 --tier core     # a ceiling, no range
        build_core.py <full.db> <out.db> <corpus.tsv> [--top N]         # the original mode

    ⚠️ **`--budget-mb` still exists to explore the curve, not to publish.** It estimates once,
    scaling the payloads by the SOURCE pack's ratio, which is not the derived one's: asking for
    25 MB gave **17.7**. Measuring six budgets with it is cheap and that is how the ranges were
    validated; building a publishable tier with it guarantees nothing.
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
        print("%s: %d palabras, %.1f MB (rango %g-%g MB, nivel %s, %d vuelta%s)%s"
              % (os.path.basename(salida), informe["palabras"], informe["mb"], minimo, maximo,
                 tier, informe["vueltas"], "" if informe["vueltas"] == 1 else "s",
                 "" if informe.get("cobertura") is None
                 else ", cubre %.2f %% del corpus y %.2f %% de los lemas"
                      % (informe["cobertura"], informe["lemas"])))
        if not informe["en_rango"]:
            # ⚠️ **Exit 1 and not a warning.** The pipeline chains onto what the previous step
            # left, and exiting 0 with an out-of-range artifact says *this complies* about a pack
            # that does not -- which is exactly the class of failure this repo cannot see.
            print("  ✗  fuera de rango: %s" % informe["motivo"], file=sys.stderr)
            return 1
        return 0

    if "--budget-mb" in argv:
        presupuesto = float(argv[argv.index("--budget-mb") + 1])
        # ⚠️ **Without the list the tier comes out measurable but WORSE**, which is why it warns.
        # Measured: cutting by `rank` loses between 0.97 and 1.53 points of coverage against
        # cutting by frequency, and it also puts in fewer lemmas. See
        # `vocabulario_por_presupuesto`.
        frecuencias = _frecuencias_de(argv)
        vocabulario = vocabulario_por_presupuesto(completo, presupuesto, frecuencias)
        cobertura = _frequency.cobertura(vocabulario, frecuencias) if frecuencias else None
        escritos = derive(completo, salida, vocabulario, tier=tier, cobertura=cobertura)
        real = os.path.getsize(salida) / 1048576
        print("%s: %d entradas, %.1f MB (presupuesto %.0f MB, nivel %s)%s"
              % (os.path.basename(salida), escritos, real, presupuesto, tier,
                 "" if cobertura is None else ", cubre %.2f %% del corpus" % cobertura))
        # ⚠️ The budget is ESTIMATED by scaling the payloads, so the real file can overshoot. It
        # warns rather than swallowing it: whoever runs it decides whether to lower the number.
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
