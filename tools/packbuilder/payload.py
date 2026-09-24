"""The format of an entry's body (the entry.payload column).

THIS FILE HAS A MIRROR:
    dict-core/src/main/kotlin/cl/fadiaz/dictionary/core/PayloadCodec.kt

The payload is compressed with raw deflate and a preloaded dictionary shared by the whole pack,
stored in meta.payload_dict. Entries are a few hundred bytes, far too short for deflate to find
redundancy on its own; the dictionary hands it a window already primed with the corpus's frequent
fragments.

deflate was chosen over zstd even though it compresses less, because deflate is in java.util.zip
(the Android platform, no extra .so) and in Python's stdlib zlib. zstd would force a native
library on the watch ON TOP of SQLite's, and a pip dependency here, to gain a few points of
compression.

The format once decompressed: UTF-8 text, one line per field, a one-character tag + TAB. See the
Kotlin mirror's documentation for the detail.

`CODEC_ID` is bumped when the TEXT format changes, not only when the compression does, because
`PackFile.open` compares it with `!=` and rejects the pack. Today that costs rebuilding and
re-sideloading the packs, and nothing more. **Once the installer exists, an ADDITIVE tag does not
bump it**: that is what tolerance of unknown tags is for, and forcing a 300 MB re-download over a
new field the old reader ignores would throw that property away (D-119).
"""

import hashlib
import os
import re
import sys
import unicodedata

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import casefold as _casefold  # noqa: E402
import zlib

# Bumped when the format changes. Written to meta.payload_codec.
PAYLOAD_VERSION = 2
CODEC_ID = "deflate-v2"

TAG_PART_OF_SPEECH = "P"
TAG_SENSE = "S"
TAG_EXAMPLE = "E"
TAG_TRANSLATION = "T"
TAG_SYNONYM = "Y"

# An antonym of THIS sense (D-126). **It does not bump CODEC_ID and that is deliberate**: it is a
# purely additive tag, and D-119 put it in writing that forcing a re-download over one of those
# would throw away the tolerance the format has. An old reader ignores it and shows the entry
# without antonyms, which is the right degradation.
TAG_ANTONYM = "A"

# A RELATED word of this sense: a hypernym, a hyponym or a morphological relative (D-132). An
# additive tag like the previous one, so it does not bump CODEC_ID either.
#
# **It is not a synonym and the separate tag is the whole difference.** "frances" carries `galo` as
# related; emitted as a synonym it would assert an equivalence the source does not give. It is
# emitted only for single-sense entries -- see `sources/kaikki._relacionadas`.
TAG_RELATED = "R"

# Where the example above it was quoted from: year and author, already trimmed by the source
# reader. Additive like [TAG_ANTONYM] and [TAG_RELATED], so it **does not bump [CODEC_ID]**
# either -- an old reader ignores it and shows the example with no attribution, which is correct
# degradation.
#
# ⚠️ **A tag and not a [REF_SEPARATOR] suffix on `E`, and the difference is not cosmetic.** The
# suffix mechanism is right there and translations use it, but it is only additive on a tag that
# is *born* with it: bolting it onto `E` would make an old reader paint the raw `\x1f` byte in
# the middle of the example. A new tag degrades to nothing; a new suffix degrades to garbage.
#
# ⚠️ **It only ever names the example written immediately above it.** Measured on the English
# dump, 86,5 % of the examples are `type: quotation` -- lines lifted from a published text -- so
# an example with no provenance is the common case, not the exception: without the citation the
# reader sees a sentence from an 1897 novel and cannot tell it from a definition. See the
# strictness in [parse]: a `C` that does not directly follow its `E` is DROPPED, because
# choosing an example for it would be the invented attribution of D-179.
TAG_CITATION = "C"

# Translations of the WORD, with no sense attributed.
#
# ⚠️ **It is the second channel, and it exists so the dishonest option stops being the cheap one.**
# With `T` alone --which lives inside a sense-- a builder holding unattributable data could either
# drop it or smear it across every sense, and smearing is free, invisible and passes
# `verify_pack`: D-117's mistake. Measured over the Spanish dump, **37.7 %** of the translations
# carry no `sense_index`, and in the sample pack that was **34.8 % of the data thrown away**.
#
# ⚠️ **It is written before the first `S` and does NOT bump [CODEC_ID]** (D-119): an old reader
# drops it through its `if senses:` guard and shows the entry without the list. But the position is
# a writing convention and **not** the semantics: `parse` takes it as belonging to the entry
# wherever it appears, because if the position decided, a misplaced `W` would become a sense
# translation -- the invented attribution this channel exists to prevent.
TAG_WORD_TRANSLATION = "W"

#: A **principal part** of the word: gerund, participle, plural or feminine.
#:
#: WARNING: the value is `key:form`, and the key is deliberately neutral. Storing the label
#: already translated --"gerundio"-- would put the interface's language INSIDE the pack, and the
#: same file is shared by a user running the app in Spanish and one running it in English. The app
#: maps the key to its localized string, which is where translation belongs (CLAUDE.md, Working
#: style).
#:
#: WARNING: these are FEW and chosen, not the whole conjugation. `correr` carries 137 forms in the
#: source and 202 rows in `form`; dumping those into the payload would be unreadable on a watch
#: and expensive in bytes -- `form` is already 42 % of the Spanish pack. What travels here are the
#: parts the rest derive from, which is what a printed dictionary puts beside the headword.
TAG_FORM = "F"

#: The word's pronunciation, in IPA. One per entry, describing the WORD and not a sense.
#:
#: Additive like [TAG_ANTONYM] and [TAG_FORM], so **it does not bump the codec id**: a reader that
#: does not know it skips the line, which is what makes a pack built before this channel keep
#: opening. That property was measured when `F` was added (D-242).
#:
#: WARNING: it is stored **without the slashes or brackets** the source wraps it in. Wiktionary
#: writes `/ˈkasa/` and `[ˈka.sa]`, which are different notations --phonemic and phonetic-- and
#: keeping either delimiter would put a typographic choice inside the pack where the card cannot
#: undo it. The app adds the slashes it wants to show.
#:
#: WARNING: **the first one, not all of them.** A Spanish entry averages more than one `sounds`
#: item --regional variants, rhymes, audio-- and a watch shows one line. Measured over the Spanish
#: dump: 99.8 % of entries carry at least one, median length 11 characters, so one per entry is
#: ~1.7 MB over 152,281 entries against the 150.6 MB `entry` already weighs.
TAG_PRONUNCIATION = "I"

#: Separates the key from the form inside a [TAG_FORM]. A colon and not a tab: `sanitize` strips
#: tabs because they would split the line, and this value has to survive it.
FORM_SEPARATOR = ":"

# Raw deflate: no zlib header. The header carries a DICTID that forces the reader to wait for
# needsDictionary(); with no header both sides pin the input dictionary.
_RAW_DEFLATE = -15

# Separates the term from the sense it points at, INSIDE an item's value.
#
# ⚠️ **The split of a `(pack, word, sense)` reference's three parts is the whole design, and each
# lives where it costs least:**
#
#     pack   -> NOT named. The target is declared by LANGUAGE in `meta.translations_to`, once per
#               pack. Naming a concrete pack killed the link for the user who has the core
#               installed and not the full one (D-180).
#     word   -> the item's value. It was already there: it is the term being shown.
#     sense  -> this suffix, OPTIONAL, because it only exists when the source knew it.
#
# From that split comes the property that matters: **a translation with no sense is already a link
# to the word and costs not one extra byte**. The common case is the free one.
#
# `\x1f` is chosen because it is the same joiner `stable_uid()` uses and because it **is not
# whitespace for `str.split()`**, so it survives `sanitize`. For that very reason it goes into the
# forbidden set: if the source could write it, it could FORGE a reference to another sense.
REF_SEPARATOR = "\x1f"

# Characters that would break the delimited format. They are sanitized on building, not on
# reading: the watch should not spend cycles defending itself from data we generate ourselves.
_FORBIDDEN = str.maketrans({"\t": " ", "\n": " ", "\r": " ", REF_SEPARATOR: ""})


def sanitize(value):
    """Deja un valor apto para el formato delimitado, o None si queda vacio."""
    cleaned = " ".join(value.translate(_FORBIDDEN).split())
    return cleaned or None


# How many hex characters of the sha256 name a sense.
#
# 12 hex is 48 bits. With the Spanish pack's 210,249 senses the probability of two distinct ones
# colliding is ~4e-7: negligible against the **22 real collisions (0.0105 %)** the data already has
# from glosses the wiki defines twice. Lengthening it would buy nothing and every character is paid
# in every reference.
SENSE_CODE_LENGTH = 12


# The punctuation one source puts at the end of a gloss and another does not.
_CIERRE = " .;:,"

# ⚠️ **Whitespace is enumerated by hand and `\s` is NOT used, and that is a cross-language trap.**
# In Python `\s` over `str` is **Unicode** and in Java/Kotlin it is **ASCII**: a hard space
# (U+00A0) would collapse on one side and not the other, and the two codes for the same sense would
# come out different **with no error and no log**. Folding the hard space --which appears in the odd
# gloss-- is lost, in exchange for both languages doing exactly the same thing, which is the deal
# this repo already chose for `norm()`.
_ESPACIO = re.compile("[ \t\n\r\f\v]+")


def fold_gloss(gloss):
    """Folds a gloss to decide whether two sources wrote **the same** sense.

    ⚠️ **The case folding FOLLOWS THE STANDARD**: `toCaseFold()`, rule R4 of section 3.13 of the
    Unicode Standard, which is the operation UAX #31 defines for *caseless matching*. The standard
    separates the two explicitly: `toLowerCase()` is **case mapping**, to DISPLAY text;
    `toCaseFold()` is **case folding**, to COMPARE it. The first version used `lower()`, which is
    the wrong one -- measured, **242 of 133,730** code points of the pinned repertoire differ
    (`ß`→`ss`, `ſ`→`s`, `ς`→`σ`), and over the real glosses 8 of 97,337 in Spanish.

    It comes from [casefold]'s pinned table and not from `str.casefold()`, because Java **does not
    have** `toCaseFold()` and the only thing that offers it is ICU, which D-003 forbids.

    ⚠️ **What IS a rule of OURS, and versioned, is stripping trailing punctuation**: no standard
    does it. It is a CONTENT decision --Wiktionary writes "Casa." and Wikidata "casa"-- and changing
    it invalidates every link already written, so it is a deliberate act and a vector pins it in
    both languages.

    Decided with the number on the table: between Wiktionary and Wikidata it raises agreement from
    **34.40 % to 42.21 % (+1,531 senses)**. The failures it recovers are visible by reading:

        wiktionary: "Condición o carácter de torpe."
        wikidata  : "condición o carácter de torpe"

    **Light on purpose**: lowercase, spaces collapsed and trailing punctuation removed. It does
    **NOT** strip accents -- `publico` and `público` are different words, and two glosses differing
    only in that are not the same sense. The more it folded, the more distinct senses it would fuse
    in silence.

    ⚠️ **`sense_code` AND `merge_duplicate_senses` use it, and it has to be that way**: if only the
    code folded, two senses differing by a full stop would share a code without being merged and
    one would be **unreachable** -- exactly the exception the invariant closes.
    """
    plegada = _casefold.fold(unicodedata.normalize("NFC", gloss).strip())
    return _ESPACIO.sub(" ", plegada).strip(_CIERRE)


def sense_code(uid, gloss):
    """Names a sense **without naming a pack**: unique for `(language, word, sense)`.

    ⚠️ **The language and the word are already inside `uid`** --`stable_uid(lang, headword, pos,
    sense_key)`-- so combining it with the gloss is enough. From there come the three properties
    that were asked for:

    1. **It does not name a pack.** Any installed pack of that language can resolve it, so the link
       does not die because the user has the core instead of the full one.
    2. **The core and the full one share it.** Verified over the real packs: the Spanish core's
       **21,534** codes are **identical** in the full one, because `build_core.py` **copies** the
       uid instead of recomputing it (D-175) and keeps the gloss.
    3. **It degrades to the word.** If no pack has that sense but some pack has the word, the term
       is still a useful link: the code is a *suffix* on the term, it does not replace it.

    ⚠️ **It is computed over the gloss folded by [fold_gloss], NOT over `norm()`, and that
    distinction is D-055's precedent applied as it stands.** The folding is light and our own;
    `norm()` is the central invariant's function and is tied to `NORM_VERSION`. `stable_uid`
    already decided the same and left written why: *"so it does not depend on NORM_VERSION, and
    raising the normalization rules does not invalidate the auxiliary packs"*. Here it bites harder
    still -- a `NORM_VERSION` bump, which D-005 allows at any time, would change **every** code and
    leave every link of every already built pack pointing at nothing, with no error and no log.

    NFC and not the raw bytes because two sources can deliver "á" precomposed or decomposed for the
    same gloss, and those would be different codes for the same sense.

    ⚠️ **THIS FILE HAS A MIRROR**: `PayloadCodec.senseCode` in Kotlin. If the two compute
    differently, the links point at nothing **with no error and no log**, which is this repo's
    central failure mode. A vector in `test_payload.py` and its twin in Kotlin pin it.
    """
    material = "%d\x1f%s" % (uid, fold_gloss(gloss))
    return hashlib.sha256(material.encode("utf-8")).hexdigest()[:SENSE_CODE_LENGTH]


def make_ref(term, sense_ref=None):
    """A translation item, as a TUPLE `(term, sense_or_None)`.

    ⚠️ **It returns a tuple and not a string on purpose, and this is not style: it is the
    defence.** The first version returned the string already joined and `render` had to guess
    whether a value carried a reference by splitting it on the separator. With that, a term from
    the source that **contained** the separator --`ho\x1fuse`-- read as the term `ho` pointing at
    `use`: the source could FORGE a reference to another sense. Its own test caught it.

    With the tuple there is nothing to guess: a string is always a term and is cleaned whole, and a
    reference can only be built by whoever calls this.
    """
    return (term, sense_ref) if sense_ref else term


def split_ref(value):
    """`(term, sense_or_None)`. What carries no suffix points at the whole word."""
    termino, _, destino = value.partition(REF_SEPARATOR)
    return termino, destino or None


def _sanitize_item(value):
    """Serializes a translation item: a string is a term, a tuple is a reference.

    Both parts are cleaned **separately** and only then joined, so the format's separator can only
    come from us. See [make_ref].
    """
    termino, destino = value if isinstance(value, tuple) else (value, None)
    limpio = sanitize(termino)
    if not limpio:
        return None
    apunta = sanitize(destino) if destino else None
    return limpio + REF_SEPARATOR + apunta if apunta else limpio


def _example_parts(item):
    """`(text, ref)` of one example. A bare string is an example with no known source.

    ⚠️ **Both shapes are accepted on purpose, and the bare string is the canonical one.** Five
    sources emit examples today -- `oewn`, `wikidata`, `bilingual`, `toy`, `enwikt_examples` --
    plus the Tatoeba sentence that `build.py` appends to an already rendered body, and none of
    them has a citation to give: Tatoeba is credited once per pack in `meta.sources`, not per
    sentence. Widening the type instead of migrating them is what keeps that true without
    touching any of the five.

    [parse] returns the same two shapes, so a round trip is stable in both directions: an
    example with no citation goes out a string and comes back a string.
    """
    if isinstance(item, dict):
        return item.get("text", ""), item.get("ref")
    return item, None


def example_text(item):
    """Only an example's text, whatever its shape. See [_example_parts].

    It exists for `build._fts_body`, which indexes the example and **not** its citation:
    publishing it as a function instead of letting every caller write its own `isinstance` is what
    stops there being two different ideas of what an example is a month from now.
    """
    return _example_parts(item)[0]


# A sense's fields that are lists, in the order they are written.
_LISTAS = ("examples", "translations", "synonyms", "antonyms", "related")


def merge_duplicate_senses(senses):
    """Fuses the senses that share a gloss, keeping the order and every one's attachments.

    ⚠️ **It is what makes the property "every sense is reachable by `(language, word, sense)`"
    true.** A sense's code comes from `(uid, gloss)`, so two senses of the same entry with an
    identical gloss **share a code** and one of the two becomes unreachable. Measured over the six
    real packs: **350 senses of 1.7 million** fell into that case -- all of them glosses the source
    writes twice (`y` → *and*, five times).

    ⚠️ **They are fused and not discarded, and a measurement decided that**: of 12 duplicate groups
    inspected, **5 carried different attachments** -- `them` repeats *"Used as the direct object of
    a verb"* with **different examples**. Discarding the copy would have lost that data in silence,
    which is exactly the failure mode this repo does not accept.

    The attachments are not re-capped. The overflow is bounded and measured: it is ~5 senses in the
    whole corpus that end up with one example too many, against 1.7 million.

    It lives here and not in each source because `render` is the **only** step every pack goes
    through: put in `kaikki` it would have to be repeated in `oewn`, `wikidata` and `bilingual`,
    and the property would be true only in the packs whose author remembered.
    """
    salida = []
    por_glosa = {}
    for sense in senses:
        # ⚠️ The SAME key `sense_code` uses: if they diverged, two senses would share a code
        # without being fused and one would be unreachable.
        gloss = fold_gloss(sense.get("gloss", ""))
        previa = por_glosa.get(gloss)
        if previa is None:
            copia = dict(sense)
            for campo in _LISTAS:
                copia[campo] = list(sense.get(campo, ()))
            por_glosa[gloss] = copia
            salida.append(copia)
            continue
        for campo in _LISTAS:
            for valor in sense.get(campo, ()):
                if valor not in previa[campo]:
                    previa[campo].append(valor)
    return salida


def _sin_repetir(valores):
    """The items, without the ones already seen, **keeping the order of first appearance**.

    ⚠️ **The order is information, which is why nothing is sorted and no set is used**: the source
    puts what is most used first, and with caps of 4 and 8 the order decides WHAT GETS SEEN.

    ⚠️ **Sweeping the built packs found it, not a test.** Measured over the real ones: **3.1 %** of
    the bilingual pack's entries with word translations repeated a term --`where` carried `donde,
    donde` and `do, do`; `Brazil` carried `carioca` twice-- and in the Spanish pack it was **88 of
    407** of those carrying per-sense translations. In a watch row that is the same word twice,
    taking a width that is already being clipped.

    The item is compared **whole and exact**: `donde` and `dónde` are different words and both
    stay.
    """
    vistos = set()
    salida = []
    for valor in valores:
        # The key is the item as it is emitted -- a translation tuple with its sense is different
        # from the same word with no sense, and both make sense.
        clave = valor if isinstance(valor, (str, tuple)) else repr(valor)
        if clave in vistos:
            continue
        vistos.add(clave)
        salida.append(valor)
    return salida


def render(part_of_speech, senses, word_translations=(), forms=(), pronunciation=None):
    """Serializes to text. `senses` is a list of dicts with gloss/examples/translations.

    The values are sanitized here: a stray tab in a Wiktionary gloss would corrupt the whole entry
    and the symptom would only appear on the watch.

    ⚠️ **And the lists are deduplicated, here and not in each source.** Same argument as
    `merge_duplicate_senses`: `render` is the **only** step every pack goes through, so put in
    `kaikki` it would have to be repeated in `oewn`, `wikidata` and `bilingual` and the property
    would be true only in the packs whose author remembered. See [_sin_repetir].
    """
    lines = []
    if part_of_speech:
        pos = sanitize(part_of_speech)
        if pos:
            lines.append(TAG_PART_OF_SPEECH + "\t" + pos)
    senses = merge_duplicate_senses(senses)
    for translation in _sin_repetir(word_translations):
        value = _sanitize_item(translation)
        if value:
            lines.append(TAG_WORD_TRANSLATION + "\t" + value)
    # Before the senses, like `W` and `F`: it describes the WORD, not one of its senses.
    ipa = sanitize(pronunciation or "")
    if ipa:
        lines.append(TAG_PRONUNCIATION + "\t" + ipa)
    # Before the senses, like `W`: they describe the WORD, not one of its senses.
    for key, form in forms:
        clave = sanitize(key).replace(FORM_SEPARATOR, "")
        valor = sanitize(form)
        if clave and valor:
            lines.append(TAG_FORM + "\t" + clave + FORM_SEPARATOR + valor)
    for sense in senses:
        gloss = sanitize(sense.get("gloss", ""))
        if not gloss:
            # A sense with no gloss contributes nothing and would unhook its examples.
            continue
        lines.append(TAG_SENSE + "\t" + gloss)
        for example in _sin_repetir(sense.get("examples", ())):
            texto, cita = _example_parts(example)
            value = sanitize(texto)
            if not value:
                # With no example there is nothing to hang the citation off, and a loose citation
                # would name whichever example came next. Both fall together.
                continue
            lines.append(TAG_EXAMPLE + "\t" + value)
            atribucion = sanitize(cita) if cita else None
            if atribucion:
                lines.append(TAG_CITATION + "\t" + atribucion)
        for translation in _sin_repetir(sense.get("translations", ())):
            value = _sanitize_item(translation)
            if value:
                lines.append(TAG_TRANSLATION + "\t" + value)
        for synonym in _sin_repetir(sense.get("synonyms", ())):
            value = sanitize(synonym)
            if value:
                lines.append(TAG_SYNONYM + "\t" + value)
        for antonym in _sin_repetir(sense.get("antonyms", ())):
            value = sanitize(antonym)
            if value:
                lines.append(TAG_ANTONYM + "\t" + value)
        for related in _sin_repetir(sense.get("related", ())):
            value = sanitize(related)
            if value:
                lines.append(TAG_RELATED + "\t" + value)
    return "".join(line + "\n" for line in lines)


def parse_forms(text):
    """A payload's principal parts, as `[(key, form), ...]`.

    Separate from [parse] because almost no caller wants them, and changing the tuple parse
    returns would mean touching `verify_pack.py` and all its tests over a datum they do not read.

    A line with no separator is ignored: the cost is losing that form, and throwing over one bad
    line would lose the whole entry.
    """
    salida = []
    for line in text.split("\n"):
        if len(line) < 3 or line[0] != TAG_FORM or line[1] != "\t":
            continue
        valor = line[2:]
        if FORM_SEPARATOR not in valor:
            continue
        clave, forma = valor.split(FORM_SEPARATOR, 1)
        if clave and forma:
            salida.append((clave, forma))
    return salida


def parse_pronunciation(text):
    """The entry's IPA, or None.

    Separate from [parse] for the same reason [parse_forms] is: changing the tuple `parse` returns
    would mean touching `verify_pack.py` and every test of it over a datum they do not read.

    The FIRST one wins if a payload somehow carries two. Being lenient here rather than raising is
    the same choice [parse_forms] makes: the cost is showing one of two pronunciations, and the
    cost of throwing is losing the whole entry.
    """
    for line in text.split("\n"):
        if len(line) >= 3 and line[0] == TAG_PRONUNCIATION and line[1] == "\t":
            return line[2:]
    return None


def parse(text):
    """The inverse of render(). It exists for verify_pack.py and the tests, not the normal path.

    Returns `(pos, senses, word_translations)`. Forms are read with [parse_forms]: `parse` keeps
    its signature because `verify_pack.py` and the tests use it, and changing it would mean
    touching both over a list almost no caller wants.
    """
    part_of_speech = None
    senses = []
    word_translations = []
    # The sense whose LAST example can still receive a citation, or None. It is set on emitting an
    # `E` and cleared by any other line: a `C` that does not come right after its `E` is discarded
    # rather than having an example picked for it. See [TAG_CITATION].
    citable = None
    for line in text.split("\n"):
        if not line or len(line) < 2 or line[1] != "\t":
            continue
        tag, value = line[0], line[2:]
        if not value:
            continue
        if tag == TAG_CITATION:
            if citable is not None:
                texto = citable["examples"][-1]
                citable["examples"][-1] = {"text": texto, "ref": value}
            citable = None
            continue
        citable = None
        if tag == TAG_PART_OF_SPEECH:
            if part_of_speech is None:
                part_of_speech = value
        elif tag == TAG_SENSE:
            senses.append(
                {
                    "gloss": value,
                    "examples": [],
                    "translations": [],
                    "synonyms": [],
                    "antonyms": [],
                    "related": [],
                }
            )
        elif tag == TAG_WORD_TRANSLATION:
            word_translations.append(value)
        elif tag == TAG_EXAMPLE:
            if senses:
                senses[-1]["examples"].append(value)
                citable = senses[-1]
        # noqa of SIM102 on purpose: the three guarded branches (P, E, T) have the same shape.
        # Flattening only this one would make it asymmetric against the other two, which ruff does
        # not flag, and the parallelism is what makes the chain readable.
        elif tag == TAG_TRANSLATION:  # noqa: SIM102
            if senses:
                senses[-1]["translations"].append(value)
        elif tag == TAG_SYNONYM:  # noqa: SIM102
            if senses:
                senses[-1]["synonyms"].append(value)
        elif tag == TAG_ANTONYM:  # noqa: SIM102
            if senses:
                senses[-1]["antonyms"].append(value)
        elif tag == TAG_RELATED:  # noqa: SIM102
            if senses:
                senses[-1]["related"].append(value)
        # Unknown tags are ignored on purpose: a newer builder can add fields without breaking an
        # old reader.
    return part_of_speech, senses, word_translations


def compress(text, dictionary):
    """Compresses to the bytes that go in entry.payload."""
    compressor = zlib.compressobj(9, zlib.DEFLATED, _RAW_DEFLATE, zdict=dictionary)
    return compressor.compress(text.encode("utf-8")) + compressor.flush()


def decompress(blob, dictionary):
    """The inverse of compress(). It has to give exactly what PayloadCodec.decode gives."""
    decompressor = zlib.decompressobj(_RAW_DEFLATE, zdict=dictionary)
    return (decompressor.decompress(blob) + decompressor.flush()).decode("utf-8")


def dictionary_digest(dictionary):
    """Hash of the preloaded dictionary, to store in meta.payload_dict_sha256.

    The reason: deflate does NOT detect a wrong preloaded dictionary. Given enough length it
    decompresses without error and returns corrupt text -- it was verified that "moverse
    rapidamente" comes out as " nadrse rapidamente", with no exception at all. A pack with the
    wrong dictionary would fill the screen with garbage without a single clue why.

    The reader checks this hash ONCE on opening the pack, not per entry. A compressed canary
    derived from the dictionary itself was tried first and discarded: since the expected value was
    computed from the same dictionary, a truncated dictionary went on validating.
    """
    return hashlib.sha256(dictionary).hexdigest()


def build_dictionary(samples, max_bytes=32 * 1024):
    """Builds the preloaded dictionary from sample payloads.

    zlib does not train dictionaries the way zstd does: the dictionary is simply text, and what
    helps is that it contain the corpus's frequent substrings, with the most frequent at the END
    (deflate prefers the matches closest to the start of the data, which correspond to the end of
    the dictionary's window).

    The strategy: count whole-word n-grams and keep the most repeated until the 32 KB budget --the
    maximum deflate uses-- is full.
    """
    from collections import Counter

    counts = Counter()
    for sample in samples:
        tokens = sample.replace("\t", " \t ").replace("\n", " \n ").split(" ")
        tokens = [t for t in tokens if t]
        for size in (2, 3, 4):
            for i in range(len(tokens) - size + 1):
                counts[" ".join(tokens[i : i + size])] += 1

    # Sorted from least to most frequent so the most frequent end up last.
    ranked = sorted(
        (item for item in counts.items() if item[1] > 1),
        key=lambda item: (item[1], len(item[0])),
    )

    chosen = []
    total = 0
    for phrase, _count in reversed(ranked):
        encoded = (phrase + " ").encode("utf-8")
        if total + len(encoded) > max_bytes:
            continue
        chosen.append(encoded)
        total += len(encoded)

    # `chosen` runs most to least frequent; it is reversed so the most frequent ends up last.
    return b"".join(reversed(chosen))
