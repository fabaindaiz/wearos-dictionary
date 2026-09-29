"""Source: a kaikki.org (wiktextract) dump, in any language.

It yields `Record` objects streaming: the JSONL files range from 1.4 GB (Spanish) to 3.2 GB
(English) and do not fit in memory (D-015).

**The pruning logic is the same for every language.** It leans on the structural tags wiktextract
emits --`form-of`, `form_of`, `glosses`, `forms`-- which are the same in every dump and are in
English. There is not one heuristic comparing against Spanish text.

What DOES change per language are the `rank` calibration constants, which is why they live in a
`Perfil`: they were measured over a concrete dump and are not inherited. See `PERFILES`.

**The pruning is the work**, not a detail. Three decisions govern it, and all three come from
measuring the dump rather than supposing:

1. **82.33 % of the records are inflected-form pages** (703,506 of 854,460): "amigo" as the
   present of "amigar". They are recognized because **all** their senses carry the `form-of` tag.
   They are not dictionary entries --showing them would be the same result repeated four times--
   but **they do have to be collected**, which is why this source makes **two passes**.

   (There are others that are an inflected form and do **not** carry the tag: "Participio de
   escribir". Those are kept as entries on purpose, and the reason is measured in D-066.)

   It looked unnecessary: the lemma carries its conjugation in `forms` (`amigar` carries 137
   forms, `amigo` among them). **Measured, that is not enough**: the lemmas' `forms` covers
   92.31 % of the form-words, and the **remaining 7.66 % --53,708 words-- would be lost**. They
   are not rare cases: "palpitaciones", "curvilinea", "animalito". Each is a search that finds
   nothing, with no error and no log. Pass 1 inverts the form-of pages into a lemma -> forms map;
   pass 2 emits the entries with both sources of forms joined.
2. **The pack is monolingual** (D-034): `translations` is not emitted. In a monolingual pack the
   `trans` table duplicates what `fts_def` already indexes better.
3. **Etymology, pronunciation, categories, syllabification and the lexical relations are
   discarded.** They are not shown on a watch and they are most of the dump's weight.
4. **Proper nouns do not get in** (D-116). It is not that they weigh --in Spanish they are 0.63 MB
   of payload-- it is that they **dilute**: 26,265 entries whose complete definition is
   "Apellido.", and in English 4,267 cases where the toponym beats the common word on rank.

   **With one exception, and it had to be measured to be found**: pruning by a bare `pos = "name"`
   took "January" with it, because the months in English are proper nouns. Six of the twelve
   disappeared (the other six survive by also being a verb, a modal or an adjective: "march",
   "may", "august"). What saves the month and not the village is `SENAL_LEXICA_MINIMA`. See there.

Records of the same `word` come contiguous (measured: 0 non-contiguous blocks in 400,000 records),
so the homographs are detected with a local buffer instead of a global map. If the source ever
stopped grouping them, what warns is `build.py`'s identity check, which fails loudly instead of
fusing two entries.
"""

import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from build import Record  # noqa: E402
from sources import frequency as _frequency  # noqa: E402

# How many usage examples are stored per sense.
#
# The examples go into the payload and also into fts_def, so they pay twice. One is enough to
# disambiguate a sense on a watch screen; the second is no longer visible without scrolling.
MAX_EXAMPLES_PER_SENSE = 1

# How many synonyms are stored per sense. Four fit on a watch screen on a single line; the fifth
# already forces scrolling to read something that is a help, not the definition.
MAX_SYNONYMS_PER_SENSE = 4

# Cap on translations per sense. Higher than the synonyms' because **it is the answer and not a
# complement**: somebody opening an entry looking for the translation wants to see it, not four of
# them. Measured over the Spanish dump, the median is 1 and the p90 is 2, so this cap almost never
# bites -- it exists for the maximum of 17.
MAX_TRADUCCIONES_POR_ACEPCION = 8

# The three fields the related words come from, **in this order**, which is the order of how much
# they say: a hypernym places the word ("guanaco -> camelido"), a hyponym gives a case, and
# `related` is a bag of morphological relatives ("frances -> galo, francofilo"). With a cap of 4
# the order decides what gets seen.
CAMPOS_RELACIONADAS = ("hypernyms", "hyponyms", "related")

# THE SOURCE DECIDES WHERE THE SYNONYMS COME FROM, NOT A LIST OF LANGUAGES.
#
# There used to be a list --`IDIOMAS_CON_SINONIMOS = {"es"}`-- and it was justified by an
# incomplete measurement: only the TOP-LEVEL shape, `raw["synonyms"]`, was looked at, where English
# brings 0 items with a `sense_index` and therefore nothing attributable. But English serves its
# own in another shape, **nested inside each sense**, where the attribution is structural and needs
# no declaring. Measured over 120,000 live records of each dump:
#
#     shape                            Spanish   English
#     top-level `synonyms`              16.5 %     5.6 %
#     `synonyms` inside `senses`         0.0 %    25.8 %
#     both at once                       0.0 %     0.0 %
#
# Each dump uses **one shape only, and not the same one**, so there is no precedence to decide. And
# the list is redundant: `_by_sense_index` already discards what carries no `sense_index`, so
# English's top-level shape falls out on its own. A default that holds because of what the source
# brings is harder to leave stale than one that holds because of a constant.

# Threshold of the exception to the proper-noun pruning (D-116).
#
# `pos = "name"` puts "January" and "Ivanivka" in the same bag, and the first is vocabulary while
# the second is a village in Cherkasy. What separates them **without looking at the text** is how
# much lexical life the word has: translations into other languages, descendants and derivatives.
#
# Measured over the dumps: January 69, February 50, Paris 172, Moscow 330, España 77, Chile 73 --
# against **0** for Hopewell (a surname) and Ivanivka (a village). With the threshold at 5, 1,675
# entries are kept in English (1.0 % of the proper nouns) and 731 in Spanish (2.3 %).
#
# It stays structural: they are fields wiktextract emits identically in every dump, not a heuristic
# against text in one language. See point 4 of the module's docstring.
SENAL_LEXICA_MINIMA = 5

# The three proper-noun policies, ordered from most pruning to least.
#
#   "lexical-only"      the default (D-116): the one with lexical life --translations,
#                       derivatives, descendants-- above SENAL_LEXICA_MINIMA gets in. It saves
#                       "January" and drops "Ivanivka".
#   "definitions-only"  the one that DEFINES gets in: only what the wiki's categorization marks
#                       as a name registry is pruned. It rescues 3,235 entries in Spanish
#                       --cities, taxonomic genera, archaic spellings-- for ~0.5 MB.
#   "included"          they all get in. It exists to MEASURE, not to publish.
#
# The value is written to `meta.proper_nouns` and `verify_pack.py` verifies it against the content.
POLITICAS_DE_NOMBRES = ("lexical-only", "definitions-only", "included")

# ⚠️ **The default is `included`: no source loses words** (D-141).
#
# Asked for that way, and the argument is good: *"I want them to go in complete rather than having
# to decide what to remove and what not and getting it wrong"*. Pruning is taking a content
# decision about somebody else's data, and getting that decision wrong **leaves no trace**: the
# word is simply not there.
#
# **What makes this default safe is not the hope that they will not get in the way, it is
# `CASTIGO_NOMBRE_PROPIO`.** D-116 measured the real problem --4,267 cases in English where the
# toponym beats the common word on rank-- and that problem is one of ORDER, not of presence. With
# the penalty they get in without displacing anything. Both pruning policies still exist and are
# asked for by name; they are the ones that produced D-116's and D-134's numbers.
POLITICA_POR_DEFECTO = "included"

# What gets added to the rank of a proper noun that came in through a permissive policy.
#
# **Do not delete, demote** -- asked for that way. Without this the policy makes the search worse
# instead of better: D-116 measured **4,267 cases in English where the toponym beats the common
# word on rank**, and they would come back through the back door. The column is "lower is more
# common", so adding is penalizing. The value is the whole RANK_BASE: a proper noun sits below ANY
# common word, not a little below. If it is ever to be nuanced, the number is here and the test
# that pins it is `test_el_nombre_propio_que_entra_PIERDE_prioridad`.
CASTIGO_NOMBRE_PROPIO = 1000

# The rank ceiling. The column is "lower is more common" (schema.sql), so the rank is computed by
# subtracting: a rich page lands near 0, a poor one near the ceiling.
RANK_BASE = 1000

# The boundary between the ordering prior's two bands.
#
# ⚠️ **They are two disjoint bands and not one mixed scale, and that is the design.** Only **17.4 %**
# of the pack's lemmas have a frequency signal (24,132 of 138,490): mixing richness and frequency
# into one number would require calibrating how much richness a Zipf point *is worth*, which is a
# decision nobody measured. With bands, whoever has a signal is ordered by it --the honest datum--
# and whoever does not sits below **as a block**, keeping the usual richness order among peers.
#
# Not appearing in 50,000 words of subtitles **is already evidence of rarity**, so the lower band
# is not an arbitrary penalty: it is what the source's silence means.
FRONTERA_CON_SENAL = 500

# How much a Zipf point is worth in the band with signal. With a Zipf of ~7.2 for Spanish's most
# common word, 70 spreads the frequent vocabulary over nearly the whole band without overflowing it.
ESCALA_ZIPF = 70

class Perfil:
    """The `rank` proxy's constants, which **are measured per language and not inherited**.

    The rank is not frequency of use --Wiktionary does not carry it-- but richness of the page,
    which correlates with the word being common because common words are the ones people edit
    (D-063).

    `forms_cap` is the most language-dependent: it exists so a verb does not win merely by having
    many forms. In Spanish a verb carries up to 222 and the cap bites; in English it carries four
    or five and **the cap never fires**, so `w_form` stops discriminating and the rank ends up
    dominated by senses and etymology. That is why the two profiles are not the same.
    """

    __slots__ = ("w_sense", "w_example", "w_form", "w_translation", "w_etymology", "forms_cap",
                 "categorias_de_registro", "separador_de_cita", "fusiona_iniciales",
                 "quita_subindices")

    def __init__(self, w_sense, w_example, w_form, w_translation, w_etymology, forms_cap,
                 categorias_de_registro=(), separador_de_cita=None, fusiona_iniciales=False,
                 quita_subindices=False):
        self.w_sense = w_sense
        self.w_example = w_example
        self.w_form = w_form
        self.w_translation = w_translation
        self.w_etymology = w_etymology
        self.forms_cap = forms_cap
        # The wiki categories that mean "this page REGISTERS a name, it does not define it".
        # Only the "definitions-only" policy uses them. See `_es_registro_de_nombres`.
        self.categorias_de_registro = tuple(categorias_de_registro)
        # Which character separates a `ref`'s fields in THIS dump, or None to carry no citation.
        #
        # ⚠️ **It is a per-language calibration and that is why it lives here**, next to the rank
        # weights and for the same reason as them (D-076): it was measured over a concrete dump and
        # is not inherited. English serves `"1897, Richard Marsh, The Beetle:"` and Wiktionary
        # `"Miguel Nicolau. Iniciacion a la Teologia. Pagina 85. 1984."` -- author first, year
        # last, full stops instead of commas. Applying the other's separator produces garbage.
        #
        # ⚠️ **Point 4 of the module's docstring forbids heuristics over the prose, and this comes
        # close.** It gets in all the same because it does not decide **presence**: its worst case
        # is an ugly cut in a secondary line, not a word that disappears without a trace, which is
        # the class of error that rule exists to prevent. It is written down so the next session
        # neither deletes it for the resemblance nor copies it where presence IS decided.
        self.separador_de_cita = separador_de_cita
        # Whether a field of one or two letters is an INITIAL and not a field. It only makes sense
        # when the separator is the full stop: `J. R. R. Tolkien` is four fields when split
        # naively, and the first two would be `J` and `R`. English splits on the comma and does not
        # need it.
        self.fusiona_iniciales = fusiona_iniciales
        # Whether the cross-reference subscripts are stripped from the gloss.
        #
        # ⚠️ **It is per language because the subscripts mean DIFFERENT THINGS in each dump**,
        # and applying it to both breaks content. Measured over today's packs: in Spanish ~1,890
        # glosses use them as a cross-reference --`mudanza\u2081`, `ejercito\u2082`, 62 of 69
        # sampled-- and in English ~4,584 are **chemical formulas** --`C\u2087H\u2085NO\u2083S`,
        # `MnO\u2082`, 23 of 26 and the other 3 too--. It is D-121's same trap.
        self.quita_subindices = quita_subindices


PERFILES = {
    # Medido sobre eswiktionary 2026-09-15: "per" devuelve perder/permitir/perseguir/permanecer/
    # perro, y "perro" subio de la posicion 619 a la 5 (D-067).
    "es": Perfil(w_sense=3, w_example=2, w_form=1, w_translation=0.5, w_etymology=5,
                 forms_cap=80,
                 # Measured over the 2026-09-15 dump: 68.4 % of the examples carry a `ref`, with
                 # the fields separated by a FULL STOP and the author first. Trimmed to author and
                 # work it goes from 92 to 50 bytes.
                 separador_de_cita=".", fusiona_iniciales=True,
                 # ~1,890 glosses with a cross-reference subscript. See [Perfil].
                 quita_subindices=True,
                 # Measured over the 2026-09-15 dump: 26,708 senses in the first and 2,398 spread
                 # over the other three. Between the four they cover the 28,314 proper nouns that
                 # say nothing but their category.
                 categorias_de_registro=("ES:Apellidos", "ES:Antropónimos",
                                         "ES:Antropónimos femeninos",
                                         "ES:Antropónimos masculinos")),
    # English: the forms cap drops because a verb carries 4-5 and not 137. The weights are tuned
    # against the dump by measuring that common prefixes return the common word at the top.
    # ⚠️ English does NOT declare `categorias_de_registro`, and that is a measurement, not an
    # oversight: there the signal is dirty. "Places in the United States" appears in 865 registry
    # senses and in **11,455** that define, so the same category is on both sides and separates
    # nothing. With the list empty, "definitions-only" lets every English proper noun in -- which
    # is exactly what makes the English pack the heaviest. There the useful policy is still
    # "lexical-only".
    "en": Perfil(w_sense=3, w_example=2, w_form=4, w_translation=0.5, w_etymology=5,
                 forms_cap=12,
                 # Measured over the 2026-09-09 dump: 75.5 % of the examples the builder stores
                 # carry a `ref`, and their fields are comma separated with the year first.
                 separador_de_cita=","),
}

# How many of the `ref`'s fields are kept.
#
# Two is "year, author" in English --`"1897, Richard Marsh, The Beetle:"` -> `"1897, Richard
# Marsh"`-- and it lowers the average from **119 to 31 bytes**, which over the English pack is
# +1.6 % instead of +6.3 %. What is thrown away is publisher, city, →OCLC and page: catalog data
# that does not fit on a watch screen and that nobody reads in a dictionary.
CAMPOS_DE_CITA = 2

# The pairs that must NOT be split when looking for the top-level separator.
#
# ⚠️ **Without the quotation marks, 320 citations (1.1 %) come out with the headline cut in half**:
# a news item's `ref` is `2019 June 6, “A gaggle, a confusion and a conspiracy…”, in BBC:` and
# splitting naively on the comma leaves `2019 June 6, “A gaggle`, which reads as broken data.
#
# ⚠️ **And the SQUARE BRACKETS were found by reading the built pack, not by a test.** Wiktionary
# uses them for the author's editorial name --`[Alfred, Lord Tennyson]`, `[William Tyndale,
# transl.]`, `Beniamin Ionson [i.e., Ben Jonson]`-- and that comma is internal: without them
# `captive` showed `1850, [Alfred`. Measured over the dump they are **369 citations (1.3 %)** and
# **every one** came out with the bracket opened and never closed. All three pairs together cost
# **1 byte** on average (31 -> 32).
#
# An unclosed pair in the source breaks nothing: the depth never returns to zero, nothing is cut,
# and the citation comes out whole. It is longer than ideal and never incorrect, which is the right
# side to fail on.
_PARES_DE_CITA = (("“", "”"), ("(", ")"), ("[", "]"))

# The characters that OPEN one of those pairs, for the unwrapping guard.
_APERTURAS_DE_CITA = frozenset(abre for abre, _ in _PARES_DE_CITA)

# What a source leaves stuck to the end of a citation field. **The full stop is NOT here**: an
# abbreviated surname ends in one ("Thos.", "Marsh Jr.") and removing it invents a form nobody
# wrote.
_CIERRE_DE_CITA = " :,;"


def _recorte_de_cita(ref, perfil):
    """A `ref`'s first two top-level fields, or None if there is nothing to say.

    "Top level" means outside [_PARES_DE_CITA]'s pairs: a separator falling inside a quoted title
    or a parenthesis **does not split**. See the measurement there.

    It returns None and not "" so the caller does not have to distinguish two shapes of the same
    case -- the example with no known source, which is 24.5 % of them.
    """
    separador = perfil.separador_de_cita if perfil else None
    if not separador:
        return None
    # ⚠️ A `ref` with an empty author field starts with the separator: seen in the Spanish dump
    # --`. Anonimo. Ordinacion dada a la ciudad...`--. Without this the first field comes out empty
    # and the trim takes one fewer than it should.
    texto = (ref or "").strip().lstrip(separador + " ").strip()
    if not texto:
        return None
    cortado = _corta_en_nivel_superior(texto, separador, perfil.fusiona_iniciales)
    # ⚠️ **Wiktionary also encloses the WHOLE citation in square brackets** when the source is
    # indirect: `[1755 April 15, Samuel Johnson, "Lexico'grapher", in A Dictionary…`. That bracket
    # does not close within the first two fields, so the depth never returns to zero, nothing is
    # cut and the whole `ref` comes out with the pair open. Measured: 184 citations (0.64 %).
    #
    # ⚠️ **It unwraps ONLY if the cut came out unbalanced, and the naive version was tried first:
    # it broke things.** Always removing the leading delimiter turned `[1877], Anna Sewell` into
    # `1877], Anna Sewell` and `(Can we date this quote?), Sir T. Browne` into something worse
    # still -- there the pair **does** close and the cut was already right. With the guard, the
    # citations with an unclosed pair go from 184 to **0 of 28,744**, and the average drops from
    # 31.8 to 30.8 bytes.
    if _desbalanceada(cortado) and texto[0] in _APERTURAS_DE_CITA:
        alternativa = _corta_en_nivel_superior(texto[1:].strip(), separador,
                                               perfil.fusiona_iniciales)
        if not _desbalanceada(alternativa):
            cortado = alternativa
    return cortado or None


def _corta_en_nivel_superior(texto, separador, fusiona_iniciales=False):
    """The first two fields, without splitting inside a [_PARES_DE_CITA] pair.

    ⚠️ **`fusiona_iniciales` exists because of Spanish and it is not cosmetic.** There the
    separator is the full stop, and `J. R. R. Tolkien. El Señor de los Anillos` has **five** fields
    when split naively: the first two would be `J` and `R`. A field of one or two letters is not a
    field, it is an initial, and it sticks to the next one.
    """
    campos = []
    actual = []
    profundidad = 0
    for ch in texto:
        for abre, cierra in _PARES_DE_CITA:
            if ch == abre:
                profundidad += 1
                break
            if ch == cierra:
                profundidad = max(0, profundidad - 1)
                break
        if ch == separador and profundidad == 0:
            pieza = "".join(actual)
            if fusiona_iniciales and _es_inicial(pieza):
                # ⚠️ **The initial does NOT close the field: it carries it along.** `J. R. R.
                # Tolkien` is ONE author, not four fields, and the surname comes afterwards -- so
                # what has to happen is to keep accumulating, not to stick to the previous field.
                actual.append(ch)
                continue
            campos.append(pieza)
            actual = []
            if len(campos) == CAMPOS_DE_CITA:
                break
            continue
        actual.append(ch)
    if len(campos) < CAMPOS_DE_CITA and actual:
        campos.append("".join(actual))
    juntos = (separador + " ").join(c.strip() for c in campos if c.strip())
    return juntos.rstrip(_CIERRE_DE_CITA)


def _es_inicial(pieza):
    """Whether what has accumulated ends in an initial --`J`, `R`, `Ch`-- and not a complete field.

    The LAST token is looked at and not the whole piece: on reaching the full stop of `J. R. R.
    Tolkien` what has accumulated is already `J. R. R`, and what decides whether the field closes
    is that the next word be a surname and not another initial.
    """
    tokens = pieza.strip().split()
    return bool(tokens) and len(tokens[-1].rstrip(".")) <= 2


def _desbalanceada(cita):
    """Whether the citation is missing the close of one of the pairs. That is what reads as broken data."""
    return any(cita.count(abre) != cita.count(cierra) for abre, cierra in _PARES_DE_CITA)


# Wiki maintenance tags embedded in the gloss (D-121).
#
# wiktextract leaves them inside `glosses` and **there is no clean version**: `raw_glosses` is None
# in every measured case. In Spanish they are 774 senses: 671 "[cita requerida]" and 103
# "[definición imprecisa]". On a watch, "Pene.^([cita requerida])" spends half the screen telling
# the reader that an editor wanted a source.
#
# **The pattern requires the SQUARE BRACKETS, and that is not a detail**: in English `^(...)`
# without brackets is a mathematical superscript --10^(100), e^(iπ), 2^(2/r)-- and a wider filter
# would destroy content instead of cleaning it. With brackets there is a single English case,
# "[sic]".
#
# The optional trailing full stop exists because the source writes "...los labios.^([cita
# requerida])." and removing only the tag leaves two full stops in a row.
_MARKUP_EDITORIAL = re.compile(r"\s*\^\(\[[^\]]*\]\)\.?")


# Wiktionary's cross-reference subscripts: `ejercito\u2082 terrestre`.
#
# ⚠️ **They are stripped ONLY in the languages that declare it, and that restriction is the
# decision.** Measured over today's packs: in Spanish ~1,890 glosses use them as a
# cross-reference (62 of 69 sampled) and in English ~4,584 are **chemical formulas**
# --`C\u2087H\u2085NO\u2083S`, `FeO\u2082\u00b2\u207b`, `MnO\u2082`; 23 of 26, and the other
# 3 are chemistry too--. A `str.translate` in this very place, applied to both, turns saccharin
# into something that is not a formula, in a place nobody looks. It is the same trap D-121
# documented with the mathematical superscript.
_SUBINDICES = str.maketrans("", "", "\u2080\u2081\u2082\u2083\u2084\u2085\u2086\u2087\u2088\u2089")


def _gloss(sense, perfil=None):
    """A sense's gloss.

    `glosses` can carry several levels (the general one first, the specific one after). **The
    last** is taken: it is the one that really defines. Joining them all would repeat the parent's
    text in every child, which is weight paid twice, in the payload and in the index.

    ⚠️ **Pierde informacion en español, y se acepta a sabiendas**: el subindice dice **que
    acepcion** de la palabra referida, y eso no es recuperable. Lo que se gana es que
    `ejercito\u2082` deje de leerse como un error de codificacion en una pantalla de reloj, donde
    la acepcion exacta no se puede consultar de todas formas. Ver [_SUBINDICES].
    """
    glosses = [g.strip() for g in (sense.get("glosses") or []) if g and g.strip()]
    if not glosses:
        return ""
    limpia = _MARKUP_EDITORIAL.sub("", glosses[-1]).strip()
    if perfil is not None and perfil.quita_subindices:
        limpia = limpia.translate(_SUBINDICES)
    return limpia


def _senal_lexica(raw):
    """Cuanta vida lexica tiene la palabra: traducciones + descendientes + derivados."""
    return (
        len(raw.get("translations") or [])
        + len(raw.get("descendants") or [])
        + len(raw.get("derived") or [])
    )


def _is_form_of(sense):
    return "form-of" in (sense.get("tags") or []) or bool(sense.get("form_of"))


def _by_sense_index(raw, headword, clave, idioma=None):
    """A `sense_index` -> items map, read from the raw record. The Spanish dump's shape.

    `clave` is "synonyms", "antonyms" or "translations": it is literally the same shape under
    another name, and two copies of this would diverge the day somebody fixes an edge in only one.

    `idioma` filters by `code`, and only the translations need it: the dump carries the whole
    table. Measured over Spanish, `en` is **34,710 of 281,022** items; without the filter a Spanish
    entry would show its Polish translation.

    **The key is the `sense_index` the source declares, NEVER the position.** `_senses()` prunes
    the form-of senses before emitting, so the ordinals shift: an `enumerate()` would hang the
    synonyms of the sense that left off the one that survives. That throws nothing, logs nothing
    and is not caught by `verify_pack.py` -- it comes out of the pack as correct content.

    A synonym with no `sense_index` is discarded (measured: 5 in the whole dump). Hanging it off
    the first sense would be inventing an attribution the source does not give.
    """
    out = {}
    for item in raw.get(clave) or []:
        index = (item.get("sense_index") or "").strip()
        word = (item.get("word") or "").strip()
        # A synonym identical to the lemma contributes nothing, same as in _forms().
        if not index or not word or word == headword:
            continue
        if idioma is not None and (item.get("code") or item.get("lang_code")) != idioma:
            continue
        for numero in _indices(index):
            out.setdefault(numero, []).append(word)
    return out


# A `sense_index` can name several senses: "1-2", "1, 4". Measured over the Spanish dump: the
# synonyms and antonyms are **100 % simple** --0 compound out of 86,418 and 7,542-- so expanding
# here does not touch them; the translations are 53.6 % simple and **8.6 % compound**, and without
# expanding them that 8.6 % finds no sense.
_RANGO = re.compile(r"(\d+)\s*[-\u2013\u2014]\s*(\d+)")
# The range's ceiling: "4-10" is real, but a huge number would be a wrong parse that would hang the
# item off senses that do not exist. It is discarded rather than guessed.
MAX_ACEPCIONES_POR_RANGO = 50


def _indices(index):
    """The sense numbers a `sense_index` names, as strings.

    What is not understood is **discarded**, just as D-117 discards an item with no index:
    measured, it is residue --`"1b"` 3 times, `"1 y 2"` 2, `"2 (en el aire)"` 1 in the whole dump--
    and guessing would be inventing the attribution.
    """
    salida = []
    for parte in re.split(r"[,;]", index):
        parte = parte.strip()
        rango = _RANGO.fullmatch(parte)
        if rango:
            desde, hasta = int(rango.group(1)), int(rango.group(2))
            if desde <= hasta and hasta - desde < MAX_ACEPCIONES_POR_RANGO:
                salida.extend(str(n) for n in range(desde, hasta + 1))
        elif parte.isdigit():
            salida.append(parte)
    return salida


def _nested(sense, headword, clave):
    """The items that come INSIDE the sense. The English dump's shape.

    `clave` is "synonyms" or "antonyms", same as in `_by_sense_index`.

    No `sense_index` is asked for and that is not an oversight: here the attribution is structural
    --the item already lives in its sense-- whereas in the shape above it is declared. Requiring it
    would throw away the English dump's 338,200 items for not carrying a datum they do not need.

    **The dump's order is respected.** 74.5 % carry `source: "Thesaurus:*"` and come first, so it
    looked as though the cap of 4 would keep the obscure ones; measured, reordering changes 91 of
    4,872 mixed senses (1.9 %) and in the sample the result is WORSE: `craft` goes from `ability,
    aptitude` to `craftiness, foxiness`.
    """
    out, vistos = [], set()
    for item in sense.get(clave) or []:
        word = (item.get("word") or "").strip()
        # Same as in _forms() and in the shape above: a synonym identical to the lemma contributes
        # nothing. Measured: 1.9 % of the English items.
        if not word or word == headword or word in vistos:
            continue
        vistos.add(word)
        out.append(word)
    return out


def _es_markup(word):
    """An internal wiki reference, not a word. Measured: 0.40 % of the items.

    These lists are the only ones in the payload the source **does not clean**: the synonyms come
    as lemmas and these come as raw links, so they carry namespaces (`Appendix:Months`, `mul:12`),
    section references (`abbot § Related terms`) and the odd loose phrase (`more at ...`).

    It matters more than the 0.40 % suggests: where they show is in the **thin** entries, which are
    the ones they exist for, and there that line is the only thing under the gloss. None of them
    can be opened either --`norm()` does not find them-- so they would be a dead link.
    """
    return ":" in word or "§" in word or word.lower().startswith("more at ")


def _relacionadas(fuente, headword, ya_mostrados):
    """Hypernyms, hyponyms and `related`, read from `fuente`: one sense or the whole record.

    They are the last usable field the source carried and the builder threw away. They matter
    because of the **thin entries**: 70.4 % of the Spanish pack is a single sense with no example,
    and those are the ones that feel empty on the watch. Measured over 174,395 live records: of the
    29,817 thin ones, 2,142 gain something here (7.2 %). The synonyms reach more --20.3 %-- but
    those already come in through `_by_sense_index`, so they are not new gain.

    **The dump's two shapes, and they are not interchangeable.** As with the synonyms (D-124), each
    language serves these lists in a different place -- measured over ~185,000 live records of each
    dump:

        related NESTED in the sense        es 0.0 %   en 13.8 %
        related at ENTRY level             es 5.0 %   en  9.6 %

    Nested, the attribution is **structural**: the item already lives in its sense, so they always
    get in. At entry level it is **non-existent** --there is no `sense_index`, unlike the
    synonyms-- and so `_senses` only asks for them **if the entry has a single sense**. With
    several, hanging them off the first would be inventing the attribution: the same error
    `_by_sense_index` documents and discards, which throws nothing, logs nothing and comes out of
    the pack as correct content. With a single one there is nothing to invent, because there is no
    other they would go to.

    `ya_mostrados` are the synonyms and antonyms that sense already emits. Repeating a word two
    lines below spends a watch screen, which is this pack's scarce resource.
    """
    out, vistos = [], set(ya_mostrados)
    for clave in CAMPOS_RELACIONADAS:
        for item in fuente.get(clave) or []:
            word = (item.get("word") or "").strip()
            # Same as in _forms(), _by_sense_index() and _nested(): a word identical to the lemma
            # contributes nothing. Here it really happens -- "be" is listed as related to "be".
            if not word or word == headword or word in vistos or _es_markup(word):
                continue
            vistos.add(word)
            out.append(word)
    return out


def _word_translations(raw, headword, idioma, ya_en_acepciones):
    """The translations the source did NOT attribute to any sense.

    ⚠️ **They are 37.7 % of the data and until today they were thrown away**, because the payload
    had a single channel: `T` lives inside a sense, so emitting this there would have meant hanging
    it off the first -- D-117's mistake, which reads plausible and nobody catches. Measured over
    the sample pack, they were **34.8 % of the available translations**: `construir` had 16 and
    showed zero.

    What already came out per sense is excluded: repeating it below would say the word means that
    "as well", when it is the same thing with better attribution.
    """
    if not idioma:
        return ()
    salida = []
    for item in raw.get("translations") or []:
        word = (item.get("word") or "").strip()
        if not word or word == headword:
            continue
        if (item.get("code") or item.get("lang_code")) != idioma:
            continue
        if (item.get("sense_index") or "").strip():
            continue
        if word not in salida and word not in ya_en_acepciones:
            salida.append(word)
    return tuple(salida[:MAX_TRADUCCIONES_POR_ACEPCION])


def _senses(raw, translations_to=None, perfil=None):
    """The senses that survive the pruning. Empty if the record is not an entry."""
    headword = raw.get("word", "")
    synonyms = _by_sense_index(raw, headword, "synonyms")
    antonyms = _by_sense_index(raw, headword, "antonyms")
    # With no declared target none is emitted: the English pack must not gain translations by
    # accident merely because its dump carries the table.
    traducciones = (
        _by_sense_index(raw, headword, "translations", idioma=translations_to)
        if translations_to else {}
    )
    out = []
    for sense in raw.get("senses") or []:
        if _is_form_of(sense):
            continue
        gloss = _gloss(sense, perfil)
        if not gloss:
            continue
        examples = []
        for example in (sense.get("examples") or [])[:MAX_EXAMPLES_PER_SENSE]:
            text = (example.get("text") or "").strip()
            if not text:
                continue
            # ⚠️ **The citation is discarded if there is no example, not the other way round.**
            # With no text there is nothing to hang it off, and keeping it anyway would leave it
            # naming the next sense's example. See `payload.parse`'s strict rule.
            cita = _recorte_de_cita(example.get("ref"), perfil)
            examples.append({"text": text, "ref": cita} if cita else text)
        index = (sense.get("sense_index") or "").strip()
        # The two shapes in which the source serves synonyms. No dump uses both, so this is not a
        # precedence but a union: whichever is empty contributes nothing.
        out.append({
            "gloss": gloss,
            "examples": examples,
            # ⚠️ **What carries no index does NOT get in**, which is D-117's rule and the reason
            # the list mode exists: hanging it off sense 1 gets it right sometimes and wrong other
            # times with no trace. Measured, 37.7 % of the dump's translations carry no index --
            # that data is real and its honest place is the entry-level channel, which does not yet
            # exist (roadmap §Naming a sense from another pack).
            "translations": traducciones.get(index, [])[:MAX_TRADUCCIONES_POR_ACEPCION],
            "synonyms": (
                synonyms.get(index, []) or _nested(sense, headword, "synonyms")
            )[:MAX_SYNONYMS_PER_SENSE],
            # The same mould and the same cap. Misattributing an antonym is worse than
            # misattributing a synonym: it reads as the opposite of something else, not as an odd
            # choice.
            "antonyms": (
                antonyms.get(index, []) or _nested(sense, headword, "antonyms")
            )[:MAX_SYNONYMS_PER_SENSE],
            # Only the NESTED shape here: it is the only one whose attribution is structural. The
            # entry-level one is added below, and only if there is a single sense.
            "related": _relacionadas(
                sense, headword, ya_mostrados=synonyms.get(index, []) + antonyms.get(index, [])
            )[:MAX_SYNONYMS_PER_SENSE],
        })
    if len(out) == 1:
        # A union with the nested, not a precedence -- same as the synonyms' two shapes: whichever
        # is empty contributes nothing, and no dump uses both at once.
        sola = out[0]
        ya = sola["synonyms"] + sola["antonyms"] + sola["related"]
        sola["related"] = (
            sola["related"] + _relacionadas(raw, headword, ya)
        )[:MAX_SYNONYMS_PER_SENSE]
    return out


#: Tags with which the source marks a row that describes the inflection TABLE, not an inflection.
#:
#: ⚠️ **They are the dump's own markers and not a list of words we dislike.** `glossary` arrives
#: as `{"form": "glossary", "source": "conjugation", "tags": ["inflection-template"]}` and
#: `no-table-tags` as `["table-tags"]`. Measured on the built `en-full` (2026-09-29), the two of
#: them sat in `form` as inflections of **575 and 577 English verbs** -- `try`, `pull`, `enter` --
#: and `glossary` is a real entry, so typing it answered with the noun plus 575 verbs. Recognising
#: them by spelling would leave the next artefact in; the Spanish dump carries **0** of either,
#: which is why this filter cannot move that pack.
#:
#: ⚠️ **`class` joined them after the first two were already filtered, and the repetition backstop
#: is what found it.** The bilingual carried `c z alternation` on **956** entries: the verb's
#: conjugation class, which the source labels `["class"]`. Counted across all three dumps before
#: adding it -- `es-en-wikt.jsonl` has **5,163** forms tagged `class` and every one is an
#: `X-Y alternation`, while `es.jsonl` and `en.jsonl` have **zero**, so it never marks a real
#: inflection. That two markers were not the whole set is why `verify_pack` now checks the
#: consequence and not only this list.
TAGS_DE_LA_TABLA = frozenset({"table-tags", "inflection-template", "class"})

#: Labels a rendered headword template leaves behind where a form should be.
#:
#: ⚠️ **The third layer the repetition backstop peeled, and the first with no usable tag.** `f same
#: meaning` sat on **107** entries of the bilingual -- `lente`, `color`, `mundial`, nouns whose
#: gender is ambiguous -- and it carries `canonical`, which is a REAL tag that the lemma's own
#: spelling also carries. So the marker is the rendered label itself, the same idiom as
#: [ARBOL_DE_ETIMOLOGIA]: a fixed string the dump writes, not a guess about the text.
#:
#: ⚠️ **Measured before pinning, in all three dumps.** Of the 267 multi-word `canonical` forms in
#: `es-en-wikt.jsonl`, **118** end in one of these and the other 149 are real: `el TLCAN`,
#: `El Cairo`, `o … o`, `tanto ... como`. English has **6,120** multi-word canonical forms and
#: every one is a `the X`, so no rule about word counts could have told them apart.
ANOTACIONES_RENDERIZADAS = ("same meaning", "gender-neutral")

#: Tags that mark a degree form, which in English is often built with a word in front.
#:
#: ⚠️ **What is dropped is the SHAPE, never the words `more` and `most`.** A degree form whose
#: tail is the lemma itself is periphrastic, and as a search key it is dead weight: nobody reaches
#: `fuchsialike` by typing `more fuchsialike`, and the lemma is its own entry. A SYNTHETIC one --
#: `bigger` -- is exactly what `form` exists for and is untouched. Keying on the two English words
#: would be a heuristic over prose, which point 4 of this module's docstring forbids.
#:
#: Measured on the built `en-full` (2026-09-29): **158,678** rows are `more`/`most` + the lemma's
#: own `norm`, **16.1 %** of the table and ~3.1 MiB of a 316.5 MiB pack. Only **28** rows starting
#: with those words are NOT that shape --`most favoured nations`, `more so`-- and the tail test is
#: what protects them.
TAGS_DE_GRADO = frozenset({"comparative", "superlative"})


def _es_perifrastico(forma, headword, tags):
    """A degree form that is another word plus the lemma, so it says nothing the lemma does not."""
    return bool(TAGS_DE_GRADO & tags) and forma.endswith(" " + headword)


def _forms(raw, headword, inbound):
    """The inflections that lead to this lemma, deduplicated and without the lemma.

    Two sources: the ones the lemma declares in `forms`, and the form-of pages that point at it.
    Neither is enough on its own. See point 1 of the module's docstring.
    """
    seen = {}
    for item in raw.get("forms") or []:
        form = (item.get("form") or "").strip()
        tags = set(item.get("tags") or ())
        if (TAGS_DE_LA_TABLA & tags
                or _es_perifrastico(form, headword, tags)
                or form.endswith(ANOTACIONES_RENDERIZADAS)):
            continue
        if form and form != headword:
            seen[form] = None
    for form in sorted(inbound):
        if form != headword:
            seen[form] = None
    return tuple(seen)


#: Which principal parts are kept, and under which neutral key. Order is the table's.
#:
#: WARNING: each row REQUIRES some tags and FORBIDS others, and the second half is what makes it
#: useful: without `forbidden`, `plural` takes any first-person-plural verb form.
#:
#: WARNING: `impersonal` does NOT disqualify, and believing it cost the first version. The source
#: puts it on EVERY non-personal Spanish form -- `corriendo`, `corrido` and `haber corrido` all
#: carry it -- so using it as a filter left `correr` with no principal parts at all. What
#: separates `corriendo` from `habiendo corrido` is not a tag but that the second is COMPOUND,
#: and that shows in the space. Measured against the real dump, not reasoned.
#:
#: WARNING: **the required tags of one row can be a SUBSET of another's**, and that is a trap this
#: table walked into. The preterite carries `{first-person, indicative, present, singular}` plus
#: `perfect`, so a present row asking only for the first four matches the preterite form as well
#: and the card shows `hice` labelled as a present. Every present row forbids `perfect` for that
#: reason, and it is the kind of thing a screenshot catches and a test does not.
#:
#: WARNING: `vos-form` is excluded everywhere. The app's Spanish is neutral Latin American
#: (app/CLAUDE.md), and `conocés` beside `conoces` is two answers to one question. `vosotros` is
#: absent for the same reason -- it is the second-person plural row that is not here.
#:
#: **Which ten, and why these ten.** Measured over 200,000 pages of the Spanish dump, ~4,400 verbs
#: per form: the fraction whose STEM is not the lemma's, which is the only part a reader cannot
#: derive. Ordered as the card shows them.
#:
#:     subjuntivo yo/el   27.6 %   conocer -> conozca
#:     preterito yo       18.6 %   hacer -> hice
#:     presente yo        11.1 %   conocer -> conozco
#:     presente tu/el/ellos 7.3 %  haber -> has, ha, han
#:     preterito el        3.9 %   hacer -> hizo
#:     gerundio            2.5 %   dormir -> durmiendo
#:     participio          1.4 %   hacer -> hecho
#:
#: Two sources agree on the core of it. Wiktionary's own `es-verb` headword names **first-person
#: singular present, first-person singular preterite and past participle** (en.wiktionary.org,
#: `hablar`), and traditional Spanish grammar's *tiempos primitivos* -- present indicative,
#: preterite, future indicative -- are the three whose irregularity propagates to the rest of
#: their group, so one form from each group covers the paradigm.
#:
#: ⚠️ **The future is deliberately NOT here even though it is a primitive**: measured, **0.2 %**
#: of verbs change its stem. Its dozen irregulars are famous --`haré`, `diré`, `pondré`-- but the
#: row would cost 4,398 verbs a line to help 8. The same applies to the conditional (0.2 %), the
#: imperfect (0.0 %) and `presente nosotros` (0.1 %) -- that last one is here anyway, because the
#: present paradigm read with a hole in it is worse than one derivable row.
PARTES_PRINCIPALES = (
    # The non-finite pair, first because they are what the compound and progressive tenses need.
    #
    # ⚠️ **Two rows for `ger` because the two dumps name it differently.** Spanish tags `corriendo`
    # as `gerund`; English has no gerund tag and calls `making` a `participle present`. One row
    # each, and neither can match the other's language, so the key comes out once.
    #
    # ⚠️ **`part` forbids `present`, and without it English shows the wrong word.** `making` is a
    # participle too, and it comes first in the source's list, so the card labelled `making` as THE
    # participle of `make` and the compound tenses were left with nothing.
    ("ger", frozenset({"gerund"}), frozenset({"vos-form", "archaic", "obsolete"})),
    ("ger", frozenset({"participle", "present"}),
     frozenset({"archaic", "obsolete", "dialectal", "nonstandard"})),
    # ⚠️ **The past goes BEFORE the participle, and in English that decides which label the word
    # gets.** `made` is both, and the same spelling is only ever shown once, so whichever row runs
    # first claims it. The past is the more informative label, and the participle row then earns
    # its row exactly where the two differ -- `go -> went, gone`, which is the case worth a line.
    ("past", frozenset({"past"}),
     frozenset({"participle", "plural", "first-person", "second-person", "third-person",
                "archaic", "obsolete", "dialectal", "nonstandard"})),
    ("part", frozenset({"participle"}),
     frozenset({"present", "feminine", "plural", "vos-form", "archaic", "obsolete",
                "dialectal", "nonstandard"})),
    # The present indicative, whole. The most read tense of the language.
    ("ind1s", frozenset({"first-person", "indicative", "present", "singular"}),
     frozenset({"perfect", "vos-form", "archaic", "obsolete", "combined"})),
    ("ind2s", frozenset({"second-person", "indicative", "present", "singular"}),
     frozenset({"perfect", "vos-form", "archaic", "obsolete", "combined"})),
    ("ind3s", frozenset({"third-person", "indicative", "present", "singular"}),
     frozenset({"perfect", "vos-form", "archaic", "obsolete", "combined"})),
    ("ind1p", frozenset({"first-person", "indicative", "present", "plural"}),
     frozenset({"perfect", "vos-form", "archaic", "obsolete", "combined"})),
    ("ind3p", frozenset({"third-person", "indicative", "present", "plural"}),
     frozenset({"perfect", "vos-form", "archaic", "obsolete", "combined"})),
    # The preterite: the second primitive, and where the stem breaks most visibly.
    ("pret1s", frozenset({"first-person", "indicative", "perfect", "present", "singular"}),
     frozenset({"vos-form", "archaic", "obsolete", "combined"})),
    ("pret3s", frozenset({"third-person", "indicative", "perfect", "present", "singular"}),
     frozenset({"vos-form", "archaic", "obsolete", "combined"})),
    # The single most irregular form in the language, at 27.6 %.
    ("sub1s", frozenset({"first-person", "present", "singular", "subjunctive"}),
     frozenset({"perfect", "vos-form", "archaic", "obsolete", "combined"})),
    # English does not tag `indicative` at all, so none of the ten rows above reach it. Its own
    # principal parts are the ones Wiktionary's headword names: third-person present, and the past.
    ("ind3s", frozenset({"present", "singular", "third-person"}),
     frozenset({"indicative", "subjunctive", "past", "perfect", "participle", "archaic",
                "obsolete", "dialectal", "nonstandard"})),
    # Nouns and adjectives: their only two inflections.
    #
    # ⚠️ **`pl` forbids every verb tag, and English is why.** Its conjugation table lists the past
    # by person, so `made` is tagged `past plural` -- and this row, meant for the plural of a NOUN,
    # matched it and labelled `made` as *the plural of make*. Seen by reading the built pack, not
    # by any test: a wrong label on a real word is worse than a missing form, and nothing fails.
    ("pl", frozenset({"plural"}),
     frozenset({"first-person", "second-person", "third-person", "feminine",
                "past", "present", "participle", "infinitive", "subjunctive"})),
    ("fem", frozenset({"feminine"}),
     frozenset({"plural", "first-person", "second-person", "third-person"})),
)


def _display_forms(raw, headword):
    """The principal parts the card shows, as `[(key, form), ...]`.

    WARNING: this is not `_forms`, and mixing them would be an expensive mistake. `_forms` feeds
    the SEARCH channel: it wants every inflection, normalized, so typing `corrais` finds
    `correr`. This feeds the SCREEN: it wants very few, with their spelling and their label. A
    Spanish verb carries 137 forms in the source; two come out here.

    WARNING: with the original spelling, which is exactly what `form` cannot give: that table
    stores `norm(form)` -- `corrais`, not `corráis` -- because its job is to be a search key.
    """
    salida = []
    vistas = set()
    for clave, exigidas, prohibidas in PARTES_PRINCIPALES:
        for item in raw.get("forms") or []:
            forma = (item.get("form") or "").strip()
            if not forma or forma == headword or forma in vistas:
                continue
            # WARNING: compounds out. `haber corrido` and `habiendo corrido` carry the same
            # tags as the simple ones, and a watch card has no room for a periphrasis.
            if " " in forma:
                continue
            tags = set(item.get("tags") or ())
            if exigidas <= tags and not (prohibidas & tags):
                salida.append((clave, forma))
                vistas.add(forma)
                break
    return tuple(salida)


def _rank(raw, senses, forms, perfil, es_nombre_propio=False, zipf=None):
    """The prior the results are ordered by. Lower is more common.

    ⚠️ **`zipf` is REAL usage frequency and it wins over page richness.** Richness was the only
    proxy there was and it turned out bad: measured over the Spanish pack, it correlates **-0.250**
    with real frequency where -1 would be expected, because it counts inflected forms and a verb
    carries up to 222. Where D-142's coverage band does not reach --the `INFLECTED_FORM` and
    `TRANSLATION` rungs, which order by raw `rank`-- that showed raw: `house` returned `solar,
    alojar, albergar` and never `casa`.

    Two disjoint bands, see [FRONTERA_CON_SENAL]:

    - **with signal** -> `[0, FRONTERA_CON_SENAL)`, from the Zipf;
    - **without signal** -> `[FRONTERA_CON_SENAL, RANK_BASE]`, from the usual richness, compressed
      into half the range. The order among peers is kept: among rare words, richness is still the
      best clue there is.

    `es_nombre_propio` applies [CASTIGO_NOMBRE_PROPIO] **on top of all the above**: whoever came in
    through a permissive policy sits below any common word, not a little below. It is added at the
    end on purpose -- if the frequency were applied afterwards, `Madrid`, which is frequent, would
    come in below the floor `verify_pack.py` requires and the pack would fail verification.
    """
    if zipf is not None:
        base = max(0, FRONTERA_CON_SENAL - 1 - int(round(zipf * ESCALA_ZIPF)))
    else:
        score = (
            perfil.w_sense * len(senses)
            + perfil.w_example * sum(len(s["examples"]) for s in senses)
            + perfil.w_form * min(len(forms), perfil.forms_cap)
            + perfil.w_translation * len(raw.get("translations") or [])
            + (perfil.w_etymology if raw.get("etymology_texts") else 0)
        )
        base = FRONTERA_CON_SENAL + max(0, RANK_BASE - int(score)) // 2
    return base + CASTIGO_NOMBRE_PROPIO if es_nombre_propio else base


def _sense_key(raw, index, needs_key):
    """What separates two homographs sharing word, pos AND pos_title.

    It genuinely happens: `leonino` as an adjective appears three times in Wiktionary,
    distinguished only by etymology. `pos_title` is used when it suffices, and an ordinal when it
    does not.

    The ordinal is the position within the group, not a hash of the content, **on purpose**:
    editing an etymology must not change the uid (D-055). The cost is the symmetric one --that
    inserting a new sense in the middle shifts the following ordinals-- and it is the lesser of the
    two, because insertions are rarer than edits.
    """
    if not needs_key:
        return None
    title = raw.get("pos_title") or ""
    return "%s#%d" % (title, index) if index else title or "#0"


def _es_registro_de_nombres(raw, perfil):
    """The page REGISTERS a name instead of defining it: a surname, a given name.

    ⚠️ **It looks at `categories`, not at the gloss, and that is the point.** A pattern over the
    prose ("^Apellido") would be a heuristic in one language, exactly what point 4 of the module's
    docstring says is not done; `categories` is emitted by wiktextract from the wiki's own
    categorization and travels in every dump.

    Measured in Spanish: 26,708 senses in `ES:Apellidos` and 2,398 in the three `ES:Antropónimos`,
    which between the four cover the 28,314 proper nouns that say nothing but their category.

    **ALL the senses have to be one.** "Estrella" is a given name and also the celestial body:
    pruning it by the first would lose the second, which is vocabulary.
    """
    if not perfil.categorias_de_registro:
        return False
    sentidos = raw.get("senses") or []
    if not sentidos:
        return False
    for sense in sentidos:
        nombres = set()
        for cat in sense.get("categories") or []:
            nombres.add(cat if isinstance(cat, str) else (cat.get("name") or ""))
        if not nombres.intersection(perfil.categorias_de_registro):
            return False
    return True


def _entra_el_nombre_propio(raw, perfil, politica):
    """Whether this `pos = "name"` survives the pruning, per the policy. See POLITICAS_DE_NOMBRES."""
    if politica == "included":
        return True
    if politica == "definitions-only":
        return not _es_registro_de_nombres(raw, perfil)
    return _senal_lexica(raw) >= SENAL_LEXICA_MINIMA


class Opciones:
    """What the build asks of the reader and that **does not change between records**.

    ⚠️ **It exists because threading the options one by one already cost a bug.** Every new option
    --`translations_to`, `frequencies`-- had to be added to `records`, to `_emit` and to **both**
    places `_emit` is called from, because the file's last group comes out through a separate call
    outside the loop. Forgetting that second call makes **the dump's last word lose that datum in
    silence**, and it happened: no test caught it, because none has two words where the second is
    the last.

    With this, adding an option is adding a field. The call sites are not touched.
    """

    __slots__ = ("perfil", "politica", "translations_to", "frequencies",
                 "lemas_en_minuscula")

    def __init__(self, perfil, politica, translations_to=None, frequencies=None,
                 lemas_en_minuscula=()):
        self.perfil = perfil
        self.politica = politica
        self.translations_to = translations_to
        self.frequencies = frequencies
        self.lemas_en_minuscula = frozenset(lemas_en_minuscula)

    def zipf(self, headword):
        """A lemma's frequency, or None if there is no signal.

        ⚠️ It uses `frequency.key` and **not** `norm()`: folding the accent gives `háber` the
        frequency of `haber` --the verb, position 210-- and sends it to rank 97 against `hábil`'s
        237.

        ⚠️ **And a capitalized word does NOT collect its lowercase homograph's frequency.**
        `frequency.key` lowercases --correctly, D-186-- and the OpenSubtitles list **already comes
        entirely in lowercase**, so the two share a key. Measured over the English pack: **6,462
        entries** with a capital and `pos != name` were in the real frequency band `[0,500)`, which
        has 55,903 -- **11.6 %** of the "most frequent" band was this.

        What gets corrected is only the class that has a rule: the **4,246** whose lowercase
        homograph **is also an entry of the pack**, which are initialisms and honorific forms
        --`TO`, `OF`, `IS`, `WE`, `ME`, `HE`, `NO`, `ARE`, `BE`, `CAN`--. There the argument is
        unambiguous: that frequency belongs to the lowercase lemma, which already has its own entry
        to claim it.

        ⚠️ **The other two classes are NOT touched, and that is deliberate.** 1,333 have a
        `pos=name` sibling and 883 have neither signal, and both mix the garbage in with legitimate
        vocabulary: `Thomas` 179 and `Richard` 168 live alongside `Christmas` 150, `American` 153
        and `British` 177. There is no datum on disk that separates them -- D-137's trick (*a word
        written in lowercase at least once in the corpus is common*) was measured against Tatoeba's
        41,512 English sentences and **does not transfer**: `american` appears 361 times and **0 in
        lowercase**, because English capitalizes demonyms by rule.

        What is left with no frequency falls into the page-richness band, which is the right
        answer: nobody measured THAT spelling's frequency.
        """
        if not self.frequencies:
            return None
        minuscula = headword.lower()
        if headword != minuscula and minuscula in self.lemas_en_minuscula:
            return None
        return self.frequencies.get(_frequency.key(headword))


def _emit(group, inbound, opciones):
    """Turns a group of records of the same `word` into Records."""
    perfil, politica = opciones.perfil, opciones.politica
    prepared = []
    for raw in group:
        if raw.get("pos") == "name" and not _entra_el_nombre_propio(raw, perfil, politica):
            continue
        senses = _senses(raw, opciones.translations_to, perfil)
        if not senses:
            continue
        prepared.append((raw, senses))

    # A sense_key is only needed when two entries of the group share a pos: that is the only thing
    # that collides in stable_uid(). Setting it when it is not needed makes the uid unstable for
    # free.
    by_pos = {}
    for raw, _ in prepared:
        by_pos[raw.get("pos")] = by_pos.get(raw.get("pos"), 0) + 1

    index_in_pos = {}
    for raw, senses in prepared:
        pos = raw.get("pos")
        index = index_in_pos.get(pos, 0)
        index_in_pos[pos] = index + 1
        headword = raw["word"]
        forms = _forms(raw, headword, inbound.get(headword, ()))
        por_acepcion = [t for s in senses for t in s["translations"]]
        sueltas = _word_translations(raw, headword, opciones.translations_to, set(por_acepcion))
        yield Record(
            headword=headword,
            senses=senses,
            part_of_speech=pos,
            rank=_rank(raw, senses, forms, perfil, es_nombre_propio=(pos == "name"),
                       zipf=opciones.zipf(headword)),
            forms=forms,
            display_forms=_display_forms(raw, headword),
            pronunciation=_pronunciation(raw),
            etymology=_etymology(raw),
            # ⚠️ **The SEARCH channel carries both**, attributed and loose: to find `casa` by
            # typing `house` it makes no difference whether the source knew which sense it belongs
            # to. This is what makes a monolingual pack searchable in the other language too.
            translations=tuple(dict.fromkeys(por_acepcion + list(sueltas))),
            word_translations=sueltas,
            sense_key=_sense_key(raw, index, by_pos[pos] > 1),
        )


#: The delimiter pairs Wiktionary wraps a transcription in: `[…]` is phonetic and `/…/` phonemic.
#:
#: WARNING: they are stripped as a PAIR and never one side at a time. Measured over the whole
#: Spanish dump (854,460 lines, 2026-09-24): 854,071 use `[…]`, 3 use `/…/` and **10 are
#: mismatched** --`[…)`-- which is the source being wrong. Stripping one side would turn those ten
#: into a value with a stray `)`, half-cleaned and impossible to tell from a real one; left whole,
#: they are visibly the source's problem.
_DELIMITADORES_IPA = (("[", "]"), ("/", "/"))


#: The first line English writes when the page uses the `{{etymon}}` template.
#:
#: ⚠️ **It is the literal the DUMP writes, not a heuristic over the prose.** Point 4 of this
#: module's docstring forbids guessing at the text, and this does not guess: it recognises a
#: rendered template by the fixed string that opens it and by the line that closes it.
ARBOL_DE_ETIMOLOGIA = "Etymology tree"

#: How many lines above the tree marker still belong to the tree rather than to the prose.
#:
#: ⚠️ **Measured, not chosen**: over the whole English dump the marker sits on line 0 for 99.81 %
#: of the 53,024 pages that carry a tree, line 1 for 22 and line 2 for 75. Three covers 99.996 %
#: of them, and each extra line is one more chance to swallow a real opening paragraph -- which is
#: why a tree found BEYOND this keeps what comes before it instead of widening the number.
GRACIA_DE_LA_ETIQUETA = 3


def _sin_arbol(texto, palabra):
    """The prose behind the rendered etymology tree, or `None` if there is none.

    ⚠️ **Carried whole, the tree is template noise on the card.** The newlines collapse into
    spaces and the reader gets *"Etymology tree Proto-Indo-European *(s)kewH-der.? Proto-Germanic
    *hūsą Old English hūs ..."* before the sentence they wanted. Measured over the whole English
    dump on 2026-09-25: **52,925 of 535,571** entries with an etymology (9.9 %) render one.

    ⚠️ **The cut is the MARKER line and not a length.** The tree closes with the page's own entry
    in its own language --`English house`-- and the prose follows. The first rule tried asked for
    a line of eight words or more and lost **87 %** of the cases, because the sentence behind the
    tree is usually as short as `From folk + -ie.`. Cutting at the marker finds the prose in
    **99.95 %** of the trees; 26 are left with nothing, and those carry no etymology rather than
    carrying the tree.

    ⚠️ **The LAST marker, not the first**: a branch can name the page's own word halfway up, and
    cutting at the first occurrence would glue half a tree in front of the prose.
    """
    lineas = texto.split("\n")
    # ⚠️ **Anywhere, not only at the top**, and what the position decides is what goes WITH it.
    # Counting the index of the marker over the whole English dump (2026-09-29): line 0 on 52,925
    # pages (99.81 %), line 1 on 22, line 2 on 75 and **line 4 on 2**.
    inicio = next(
        (i for i, l in enumerate(lineas) if l.strip() == ARBOL_DE_ETIMOLOGIA),
        None,
    )
    if inicio is None:
        return texto
    # The tree closes with the page's own entry; with no closing marker it runs to the end.
    marca = "English " + palabra
    fin = next(
        (i for i in range(len(lineas) - 1, inicio, -1) if lineas[i].strip() == marca),
        len(lineas) - 1,
    )
    # ⚠️ **A LEADING tree takes the lines above it and a TRAILING one does not**, and that is the
    # whole of this distinction. Above a leading tree sit its own label lines --`PIE word`, then
    # ` *bʰréh₂tēr` on the next-- which are the tree's and not prose; `brother`, `one` and
    # `September` shipped a whole tree while the grace was two, because the fixture that proved it
    # had written the label and its value on one line and so agreed with the code, not the dump.
    #
    # ⚠️ **Above a trailing tree sits the answer.** `creep` reads *"From Middle English crepen…"*
    # and then the tree; dropping what comes before would throw away the etymology to remove the
    # noise. The two pages this covers were found by `verify_pack`'s content check reading every
    # entry, not by any test -- the rule was believed complete and had a mutation probe.
    resto = lineas[fin + 1:] if inicio < GRACIA_DE_LA_ETIQUETA else lineas[:inicio] + lineas[fin + 1:]
    return "\n".join(resto).strip() or None


#: A cross-reference to a numbered section that does not exist outside the wiki page.
#:
#: WARNING: it is REMOVED and the sentence around it is kept. `swap` reads *"…from the verb (see
#: Etymology 1 above)"*: the clause before it is true and useful, and only the pointer is dangling
#: -- a card has no "above". Measured on the built pack: 0.25 % cite a numbered etymology and
#: 0.36 % say *see … above/below*.
REFERENCIA_COLGADA = re.compile(
    r"\s*[(\[]?\s*(?:see|cf\.?|compare)\b[^)\].]{0,40}?"
    r"(?:Etymology\s*\d|\babove\b|\bbelow\b)[^)\].]{0,20}[)\]]?",
    re.IGNORECASE,
)


def _sin_referencias(texto):
    """The origin with its dangling cross-references dropped, or `None` if nothing is left.

    ⚠️ **The pointer goes and the clause stays.** Cutting the whole sentence would lose *"from the
    verb"*, which is the part that answers the question; keeping the pointer leaves the card
    telling the reader to look at a section of a page they are not on.
    """
    limpio = REFERENCIA_COLGADA.sub("", texto)
    limpio = re.sub(r"\s{2,}", " ", limpio).strip()
    limpio = re.sub(r"\s+([.,;])", r"\1", limpio)
    return limpio or None


#: Lines that NAME a section instead of saying anything, pinned from what the dump writes.
#:
#: ⚠️ **Counted on the text that survives `_sin_arbol`, which is the only text that reaches a
#: card.** Measured on the whole English dump (2026-09-29): once the tree is gone, **324** distinct
#: bare heading lines remain over 535,529 origins, and these five are the ones that name a section
#: rather than being a leftover tree node -- `Cognates` (1,165), `More information` (7),
#: `Abbreviation` (7), `Details` (6), `Etymological notes` (5). Flattened into the payload's
#: single line, `Cognates` doubles the next word: `sword` read *"…(“sharp”). Cognates Cognate
#: with North Frisian Swērt…"*.
#:
#: ⚠️ **Whole line and exact, never a substring.** *"Cognates in Frisian are attested"* is a
#: sentence, and a rule that matched inside the prose would eat it.
#:
#: ⚠️ **`PIE word` is deliberately NOT here, and putting it here made `brother` worse.** It is the
#: tree's own label, not a section heading: removing it while the tree is still there deletes the
#: one word that made the tree recognisable and leaves the tree. It belongs to `_sin_arbol`, which
#: is what cuts the whole block.
CABECERAS_DE_SECCION = frozenset({
    "Cognates", "More information", "Abbreviation", "Details", "Etymological notes",
})

#: A wiki bullet: an asterisk and a SPACE.
#:
#: ⚠️ **The space is the whole rule and it is not cosmetic.** Etymology is written full of
#: reconstructed forms --`*hūsą`, `*bʰréh₂tēr`-- so keying on the asterisk alone would behead a
#: large share of the origins in the pack. Measured: **0.64 %** of the surviving origins carry a
#: real bullet.
VINETA = "* "

#: What joins the items of a bulleted list once their markers are gone.
#:
#: ⚠️ **They are JOINED and not cut down to the first, and that is a deliberate reversal.** The
#: first version kept only the first bullet, reasoning that `_etymology` already keeps the first of
#: several `etymology_texts`. Measured, that silently dropped the alternative accounts of **2,791**
#: of 535,529 origins: `bloke` carries two hypotheses about where it comes from and would have
#: shown one -- reading complete and being incomplete, which is the failure this repository treats
#: as the worst kind. The separator costs two characters and keeps the text honest.
SEPARADOR_DE_VINETAS = " · "


def _sin_cabeceras(texto):
    """The origin without the lines that only name a section."""
    return "\n".join(l for l in texto.split("\n") if l.strip() not in CABECERAS_DE_SECCION)


def _sin_vinetas(texto):
    """A bulleted list as one line: the markers go, the items are joined, nothing is lost.

    ⚠️ **The prose ABOVE the list stays.** `bloke` reads *"Origin unknown; the following
    borrowings have been hypothesized:"* and then the bullets -- dropping it would start the card
    mid-thought.

    ⚠️ **Every item survives**; see [SEPARADOR_DE_VINETAS] for why this is a join and not a cut.
    """
    salida = []
    lista = []
    for linea in texto.split("\n"):
        # ⚠️ **A line that is ONLY the marker is dropped, and it is not a hypothetical.** The dump
        # writes a bare `*` above the real bullets on 13 pages: `jimmy` reached the card as
        # *"* (chocolate sprinkles): Unknown…"*, which reads exactly like a bullet nobody stripped,
        # and `minneola`'s entire etymology is that one character. An empty bullet says nothing,
        # and an origin that is nothing at all comes out as `None` rather than as a heading over a
        # blank line.
        if linea.strip() == VINETA.strip():
            continue
        if linea.lstrip().startswith(VINETA):
            lista.append(linea.lstrip()[len(VINETA):].strip())
            continue
        if lista:
            salida.append(SEPARADOR_DE_VINETAS.join(lista))
            lista = []
        salida.append(linea)
    if lista:
        salida.append(SEPARADOR_DE_VINETAS.join(lista))
    return "\n".join(salida)


def _limpiar_origen(texto, palabra):
    """Everything that has to come off an origin before it reaches a card, in order."""
    sin_arbol = _sin_arbol(texto, palabra)
    if not sin_arbol:
        return None
    return _sin_referencias(_sin_vinetas(_sin_cabeceras(sin_arbol)))


def _etymology(raw):
    """Where the word comes from, or `None`.

    ⚠️ **The two dumps do not agree on the field name, and reading one only is silent.** Spanish
    writes `etymology_texts`, a LIST; English writes `etymology_text`, a STRING. Assuming either
    produces the other pack with no etymology at all -- no error, no log, and every invariant
    still holding. The defect would be an absence, which is the shape this repository cannot see.

    ⚠️ **The FIRST one, when there are several.** They are alternative accounts of the same word
    and a watch card shows one line.
    """
    palabra = raw.get("word") or ""
    textos = raw.get("etymology_texts")
    if isinstance(textos, (list, tuple)):
        for texto in textos:
            if texto and texto.strip():
                return _limpiar_origen(texto.strip(), palabra)
    texto = raw.get("etymology_text")
    if isinstance(texto, str) and texto.strip():
        return _limpiar_origen(texto.strip(), palabra)
    return None


def _pronunciation(raw):
    """The entry's IPA, without the source's delimiters, or None.

    ⚠️ **The first item that HAS an `ipa`, not `sounds[0]`.** That list mixes transcriptions with
    other phonetic notes --`acentuación: aguda`, `longitud silábica: trisílaba`, an audio file--
    and only some carry the key. Measured over the Spanish dump, `sounds[0]` happened to carry it
    every time; indexing it anyway would be a rule that holds by luck of one language's editors.

    ⚠️ **One per entry.** 100.0 % of the dump's lines carry at least one and a watch shows a single
    line; the rest are regional variants a 234 dp card has no room to qualify.
    """
    for sound in raw.get("sounds") or ():
        value = (sound.get("ipa") or "").strip()
        if not value:
            continue
        for abre, cierra in _DELIMITADORES_IPA:
            if len(value) > 1 and value.startswith(abre) and value.endswith(cierra):
                return value[1:-1].strip() or None
        return value
    return None


def _is_form_page(raw):
    """A page that is ONLY an inflected form: none of its senses defines anything."""
    senses = raw.get("senses") or []
    return bool(senses) and all(_is_form_of(sense) for sense in senses)


#: How a gloss names the lemma it points at, in either dump.
_LEMA_EN_LA_GLOSA = re.compile(r"\b(?:of|de)\s+(.+?)\s*$", re.IGNORECASE | re.DOTALL)


def _lemas_de(sense):
    """The lemmas a form page really leads to, with a comma-split one put back together.

    ⚠️ **wiktextract cuts a multi-word lemma on its own commas.** The page `ate, breathed, and
    slept` declares `form_of = ['eat', 'breathe', 'and sleep']`, so `eat` and `breathe` each
    receive an inflection they do not have and `eat, breathe, and sleep` receives none. Measured
    over the whole English dump (2026-09-29): **178 of 537,579** senses with a `form_of` name more
    than one target, and **161** of those are one lemma in pieces.

    ⚠️ **The gloss decides, and that is what keeps this from eating the real cases.** It names the
    lemma in prose, so rejoining the pieces with `", "` either reproduces it -- they were one
    lemma -- or it does not, and then they are several lemmas and every one of them stays.
    `postie` is the diminutive of `postman` OR `postwoman`, and both survive.
    """
    destinos = [t.get("word") for t in sense.get("form_of") or [] if t.get("word")]
    if len(destinos) < 2:
        return destinos
    glosa = (sense.get("glosses") or [""])[0] or ""
    cola = _LEMA_EN_LA_GLOSA.search(glosa)
    if cola:
        junto = ", ".join(destinos)
        if junto.casefold() == cola.group(1).strip().rstrip(".").casefold():
            return [junto]
    return destinos


def _inbound_forms(path):
    """Pass 1: inverts the form-of pages into a lemma -> forms-that-lead-to-it map.

    It is inverted here and not in pass 2 because pass 2 consumes by lemma: this way the map has
    one key per lemma (~150,000) instead of one per form (~700,000).
    """
    inbound = {}
    # The lemmas ALREADY written in lowercase that would be an entry of the pack. It comes free
    # here: this pass already walks the whole file. See `Opciones.zipf`.
    lemas_en_minuscula = set()
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            if not line.strip():
                continue
            raw = json.loads(line)
            word = raw.get("word")
            if not word:
                continue
            if not _is_form_page(raw):
                # ⚠️ **A `form-of` page does NOT count**, and the distinction matters: it is not an
                # entry, it is inverted as a form of its lemma (D-065). If it counted, `RAN` would
                # lose its frequency to `ran` --which is an inflection of `run`-- with no lowercase
                # entry existing to claim it.
                if word == word.lower():
                    lemas_en_minuscula.add(word)
                continue
            for sense in raw["senses"]:
                for lemma in _lemas_de(sense):
                    if lemma != word:
                        inbound.setdefault(lemma, set()).add(word)
    return inbound, lemas_en_minuscula


def records(path, lang="es", politica=POLITICA_POR_DEFECTO, translations_to=None,
            frequencies=None):
    """Iterates the JSONL and yields Records. Those of the same `word` are grouped for homographs.

    **Proper nouns do NOT come out by default** (`pos = "name"`: surnames, toponyms, given names).
    It is a product decision, D-116, and the default lives here --in the library-- and not in the
    CLI flag, so any new caller inherits it without having to ask.

    What gets removed, measured: in Spanish **32,305 entries, 22.1 %**, of which **26,265 have as
    their complete definition the word "Apellido."**. In English **163,470, 17.1 %**, which also
    weigh **40.7 MB (13.8 % of the pack)** and in **4,267 cases beat the common word on rank**:
    searching "freedom" returned a town in Santa Cruz County first.

    `politica` chooses among [POLITICAS_DE_NOMBRES]'s three. `"included"` still exists because it
    is what produced those numbers, and measuring them again against a new dump has to stay cheap.
    `"definitions-only"` is the middle one: it prunes the name registry and lets the one that
    defines in, with a penalized rank.

    An unknown policy **throws**: a typo in the CLI cannot build a pack with the default and not
    say so, because the pack would come out fine and with different content from the one asked for.
    """
    if politica not in POLITICAS_DE_NOMBRES:
        raise ValueError("politica de nombres propios desconocida: %r (son %s)"
                         % (politica, ", ".join(POLITICAS_DE_NOMBRES)))
    # Pass 1 FIRST: `lemas_en_minuscula`, which `Opciones` needs, comes out of it.
    inbound, lemas_en_minuscula = _inbound_forms(path)
    opciones = Opciones(PERFILES[lang], politica, translations_to, frequencies,
                        lemas_en_minuscula)
    group = []
    current = None
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            if not line.strip():
                continue
            raw = json.loads(line)
            word = raw.get("word")
            if not word:
                continue
            if word != current:
                for record in _emit(group, inbound, opciones):
                    yield record
                group = []
                current = word
            group.append(raw)
    # The file's last group comes out here and not through the loop. With [Opciones] there is
    # nothing left to forget, which is exactly what this refactor came to close.
    for record in _emit(group, inbound, opciones):
        yield record
