"""Builds a real monolingual pack from a kaikki.org dump.

    python3 build_pack.py <lang> <kaikki.jsonl> <output.db> [--sample N] [--nombres POLICY]
                          [--ejemplos <es-en-wikt.jsonl>] [--frases <tatoeba-spa.tsv>]
                          [--tesauro <wordnet>] [--sumar <pack> <dump>]
                          [--flexiones <target-language-pack.db>]
                          [--frecuencias <opensubtitles-list.txt>]

`--sample N` builds a pilot pack with 1 in every N lemmas, chosen by hashing the headword:
deterministic and **with no positional bias**, unlike cutting the first N lines. It serves to look
at the content and estimate the size before spending the full build.

The dumps are downloaded from kaikki.org (per-language processed pages; the raw format is
deprecated). **Which is which is the easy mistake**, because all three exist and are different
datasets:

    es -> https://kaikki.org/eswiktionary/Español/...   definitions IN SPANISH of Spanish
                                                        words. 1.42 GB.
    en -> https://kaikki.org/dictionary/English/...     definitions IN ENGLISH of English
                                                        words. 3.24 GB.

    enwiktionary's Spanish section gives Spanish words with a gloss **in English**: that is a
    BILINGUAL pack and this script does not build it.

**Proper nouns do not get in** (D-116): surnames, toponyms and given names are discarded by
default. What that removes: 32,305 entries in Spanish (22.1 %, of which 26,265 define only
"Apellido.") and 163,470 in English (17.1 %, 40.7 MB).

`--nombres POLICY` changes that. There are three and they are measured in Spanish (D-134):

    lexical-only       the default. 114,619 entries, 68.3 MB
    definitions-only   the one that DEFINES gets in and the one that merely registers does
                       not, with a penalized rank. It rescues cities, taxonomic genera,
                       archaic spellings
    included           they all get in. 146,193 entries, 73.3 MB. It exists to MEASURE

The two that are not the default **suffix the `pack_id`**, so they can be installed beside the
normal pack and compared on the watch. `--con-nombres` still works as an alias for
`--nombres included`.

`--ejemplos` adds a **second source**: the Spanish usage examples of the English Wiktionary,
Spanish section (D-135). It only fills thin entries and only where there is no attribution to
invent, so the number is small -- **326 entries, 0.28 %**. ⚠️ **It changes the pack's
attribution**, because using two sources forces naming both: that is why it is an option and not a
default. Both are CC BY-SA 4.0.

`--frases` adds a **third source**: the Tatoeba corpus (D-137). It yields **23 times more** than
`--ejemplos` --7,019 entries against 307-- because a corpus example does not need the two sources
to agree on how they number the senses: it only needs to contain the word **unambiguously**, and
the builder checks that against its own index. ⚠️ Tatoeba is **CC BY 2.0 FR** and it too changes
the attribution. Both options can be combined.

`--sumar <pack> <dump>` merges another catalog pack's **vocabulary** into this one: the lemmas the
base source does not have get in and the duplicates are discarded (D-146). `--sumar es-wd
<lexemes>` contributes **6,092 lemmas** to the Spanish pack --regional demonyms, set phrases-- for
~1.5 MB. ⚠️ It is a union of ROWS: it splits no entry, so it needs no composition.

`--tesauro` adds **WordNet's synonyms and antonyms** (D-144), which are grouped by MEANING and
therefore do not depend on somebody having written them by hand. The pack's language picks the
format: **WN-LMF** (`english-wordnet-*.xml.gz`, CC BY 4.0) for English and **OMW's `.tab`**
(`wn-data-spa.tab`, CC BY 3.0) for Spanish. It yields **21,143 entries** in English --plus 2,486
with antonyms-- and **5,504** in Spanish. ⚠️ It changes the attribution too.
"""

import hashlib
import os
import sys

from sources import bilingual, enwikt_examples, kaikki, oewn, tatoeba, wikidata, wordnet

from build import PackBuilder, name_with_tier

# The CATALOG of sources, and the reason it is a table and not loose text (D-138).
#
# Each row is a declaration: who contributed what, from where, and under which licence. From here
# come **both** things the pack carries -- `meta.attribution`'s prose and `meta.sources`'s
# structured list -- so **there is no way to add content without adding the credit**: it is a
# datum, not a paragraph somebody has to remember to edit.
#
# ⚠️ **One licence per source, not one per pack.** The Spanish pack with `--frases` mixes CC BY-SA
# 4.0 definitions with CC BY 2.0 FR sentences. A single name for the whole pack either over-claims
# or under-credits, and the attribution is the CONDITION of using the data (D-031).
#
# `codigo` is also the one that appears in the `pack_id` (see GRAMATICA_DE_PACK_ID), so two packs
# of the same language from different sources do not collide.
FUENTES = {
    "wikc": {
        "codigo": "wikc",
        "rol": "definitions",
        "nombre": "Wikcionario (es.wiktionary.org)",
        "url": "https://kaikki.org/eswiktionary/Espa%C3%B1ol/",
        "licencia": "CC BY-SA 4.0",
        "licencia_url": "https://creativecommons.org/licenses/by-sa/4.0/",
        "prosa": ("Definitions from the Spanish Wiktionary (es.wiktionary.org), CC BY-SA 4.0. "
                  "Extraction: kaikki.org / wiktextract (Tatu Ylonen).",
                  "Definiciones del Wikcionario (es.wiktionary.org), licencia CC BY-SA 4.0. Extracción: "
                  "kaikki.org / wiktextract (Tatu Ylonen)."),
    },
    "wikt": {
        "codigo": "wikt",
        "rol": "definitions",
        "nombre": "Wiktionary (en.wiktionary.org)",
        "url": "https://kaikki.org/dictionary/English/",
        "licencia": "CC BY-SA 4.0",
        "licencia_url": "https://creativecommons.org/licenses/by-sa/4.0/",
        "prosa": ("Definitions from Wiktionary (en.wiktionary.org), CC BY-SA 4.0. Extraction: "
                  "kaikki.org / wiktextract (Tatu Ylonen).",
                  "Definiciones del Wiktionary (en.wiktionary.org), licencia CC BY-SA 4.0. Extracción: "
                  "kaikki.org / wiktextract (Tatu Ylonen)."),
    },
    "enwikt-ej": {
        "codigo": "ej",
        "rol": "examples",
        "nombre": "Wiktionary en inglés, sección Spanish",
        "url": "https://kaikki.org/dictionary/Spanish/",
        "licencia": "CC BY-SA 4.0",
        "licencia_url": "https://creativecommons.org/licenses/by-sa/4.0/",
        "prosa": ("Usage examples from the English Wiktionary (en.wiktionary.org), Spanish section, CC "
                  "BY-SA 4.0.",
                  "Ejemplos de uso del Wiktionary en inglés (en.wiktionary.org), sección Spanish, "
                  "licencia CC BY-SA 4.0."),
    },
    # The SAME dump as "enwikt-ej", in another role: there it contributes examples to a Spanish
    # pack, here it contributes the whole definitions -- and in English, which is what makes it
    # bilingual.
    "enwikt": {
        "codigo": "enwikt",
        "rol": "definitions",
        "nombre": "Wiktionary en inglés, sección Spanish",
        "url": "https://kaikki.org/dictionary/Spanish/",
        "licencia": "CC BY-SA 4.0",
        "licencia_url": "https://creativecommons.org/licenses/by-sa/4.0/",
        "prosa": ("English definitions of Spanish words, from the English Wiktionary "
                  "(en.wiktionary.org), Spanish section, CC BY-SA 4.0.",
                  "Definiciones en inglés de palabras españolas, del Wiktionary en inglés "
                  "(en.wiktionary.org), sección Spanish, licencia CC BY-SA 4.0."),
    },
    "opensubs": {
        "codigo": "freq",
        "rol": "frequency",
        "nombre": "OpenSubtitles (via FrequencyWords)",
        "url": "https://github.com/hermitdave/FrequencyWords",
        "licencia": "CC BY-SA 4.0",
        "licencia_url": "https://creativecommons.org/licenses/by-sa/4.0/",
        "prosa": ("Usage frequencies from the OpenSubtitles corpus, via FrequencyWords "
                  "(github.com/hermitdave/FrequencyWords), CC BY-SA 4.0.",
                  "Frecuencias de uso del corpus OpenSubtitles, via FrequencyWords "
                  "(github.com/hermitdave/FrequencyWords), licencia CC BY-SA 4.0."),
    },
    "tatoeba": {
        "codigo": "tat",
        "rol": "sentences",
        "nombre": "Tatoeba",
        "url": "https://tatoeba.org/",
        "licencia": "CC BY 2.0 FR",
        "licencia_url": "https://creativecommons.org/licenses/by/2.0/fr/",
        "prosa": ("Example sentences from the Tatoeba corpus (tatoeba.org), CC BY 2.0 FR.",
                  "Frases de ejemplo del corpus Tatoeba (tatoeba.org), licencia CC BY 2.0 FR."),
    },
    "wd": {
        "codigo": "wd",
        "rol": "definitions",
        "nombre": "Wikidata Lexemes",
        "url": "https://www.wikidata.org/wiki/Wikidata:Lexicographical_data",
        "licencia": "CC0 1.0",
        "licencia_url": "https://creativecommons.org/publicdomain/zero/1.0/",
        # It is declared all the same even though CC0 does not require it: where a datum comes from
        # is useful to know even when saying so is not compulsory.
        "prosa": ("Definitions from Wikidata Lexemes (wikidata.org), dedicated to the public domain "
                  "under CC0 1.0.",
                  "Definiciones de Wikidata Lexemes (wikidata.org), dedicadas al dominio público bajo "
                  "CC0 1.0."),
    },
    "mcr": {
        "codigo": "wn",
        "rol": "relations",
        "nombre": "Multilingual Central Repository, vía Open Multilingual Wordnet",
        "url": "https://adimen.si.ehu.es/web/MCR/",
        "licencia": "CC BY 3.0",
        "licencia_url": "https://creativecommons.org/licenses/by/3.0/",
        "prosa": ("Synonyms from the Multilingual Central Repository (adimen.si.ehu.es/web/MCR/), "
                  "distributed by Open Multilingual Wordnet, CC BY 3.0.",
                  "Sinónimos del Multilingual Central Repository (adimen.si.ehu.es/web/MCR/), "
                  "distribuido por Open Multilingual Wordnet, licencia CC BY 3.0."),
    },
    "oewn-tesauro": {
        "codigo": "wn",
        "rol": "relations",
        "nombre": "Open English WordNet",
        "url": "https://en-word.net/",
        "licencia": "CC BY 4.0",
        "licencia_url": "https://creativecommons.org/licenses/by/4.0/",
        "prosa": ("Synonyms and antonyms from Open English WordNet (en-word.net), CC BY 4.0.",
                  "Sinónimos y antónimos de Open English WordNet (en-word.net), licencia CC BY 4.0."),
    },
    "oewn": {
        "codigo": "oewn",
        "rol": "definitions",
        "nombre": "Open English WordNet",
        "url": "https://en-word.net/",
        "licencia": "CC BY 4.0",
        "licencia_url": "https://creativecommons.org/licenses/by/4.0/",
        "prosa": ("Open English WordNet 2025, CC BY 4.0.",
                  "Open English WordNet 2025, licencia CC BY 4.0."),
    },
}


# The sentences a source adds to a pack's description, **in both languages**.
#
# WARNING: **they live in one mapping because they are read twice.** `_describir` appends them
# when building; `repair_meta.py` translates them back when repairing a pack that shipped with
# the wrong one. Two copies of a sentence is how the repair tool silently stops recognising a
# sentence somebody reworded here.
FRASES = {
    "enwikt-ej": ("With usage examples from a second source.",
                  "Con ejemplos de uso de una segunda fuente."),
    "tatoeba": ("With usage sentences from the Tatoeba corpus.",
                "Con frases de uso del corpus Tatoeba."),
    "frecuencia": ("Results ordered by real usage frequency.",
                   "Resultados ordenados por frecuencia de uso real."),
    "wordnet": ("With WordNet synonyms and antonyms.",
                "Con sinónimos de WordNet."),
    "suma": ("With vocabulary from an additional source.",
             "Con vocabulario de una fuente adicional."),
}


def es_ingles(metadata):
    """Whether the pack is an English one, by the FIRST of `meta.langs`.

    The bidirectional `es,en` is Spanish-first and described in Spanish on purpose.
    """
    return metadata["langs"].split(",")[0].strip() == "en"


def _describir(metadata, clave):
    """Appends one sentence to the description, **in the pack's own language**.

    WARNING: **it exists because four of the five sentences were appended in Spanish to every
    pack.** The thesaurus block alone chose by language, so all three English packs shipped
    reading *"English definitions from Wiktionary. (...) Resultados ordenados por frecuencia de
    uso real. With WordNet synonyms and antonyms."* -- one Spanish sentence wedged into English
    prose. `en-core` travels inside the APK, so it was on a watch.

    It went unseen because nothing compares a description against the language it declares: each
    piece is correct on its own and only the assembly is wrong. Taking the choice away from the
    call site is the same move `_declarar` makes for attribution.
    """
    en, es = FRASES[clave]
    metadata["description"] += " " + (en if es_ingles(metadata) else es)


def _declarar(metadata, clave):
    """Adds a source to the pack's manifest: the prose and the structured row, together.

    It is a single function so that **there is no** way to add content and forget the credit. The
    failure mode it prevents is silent: the pack comes out whole, opens, works, and is badly
    licensed -- nothing in the content gives it away.

    ⚠️ **The prose is a `(en, es)` pair chosen by the pack's language, exactly like [FRASES].**
    It used to be one Spanish string appended to every pack, so the three English packs shipped an
    `attribution` reading *"...wiktextract (Tatu Ylonen). **Frecuencias de uso del corpus
    OpenSubtitles, via FrequencyWords (...), licencia CC BY-SA 4.0.** Synonyms and antonyms from
    Open English WordNet..."*.

    ⚠️ **It is the same bug `_describir` was written to fix, one function over, and it survived
    there because the fix was applied to the sentence table and not to the source table.** This
    field is the worse place for it: `description` is a convenience, `attribution` is what D-031
    makes non-optional, so the string somebody reads to know whose data this is was half in a
    language they may not have.
    """
    fuente = FUENTES[clave]
    fila = "\t".join((fuente["rol"], fuente["nombre"], fuente["url"],
                       fuente["licencia"], fuente["licencia_url"]))
    metadata["sources"] = (metadata.get("sources", "") + fila + "\n")
    en, es = fuente["prosa"]
    prosa = metadata.get("attribution", "")
    metadata["attribution"] = (prosa + " " + (en if es_ingles(metadata) else es)).strip()
    return fuente

# D-031: the content is CC BY-SA and the attribution screen is not optional. These two keys are
# what the app has to show; without them the pack does not comply with the data's licence.
#
# `name` is SHORT and `description` carries the long text (D-125). The name is shown in a watch row
# --in the home's selector, in the dictionaries screen, in the delete dialog and in the
# attribution-- and "Español - definiciones" is 22 characters: it was clipped in all four. What the
# long name said --that it carries definitions-- now comes from `kind`, which is a datum and not a
# string somebody has to read.
#
# `proper_nouns` declares the pack's content policy (D-116). The EFFECTIVE value is written into
# `meta`, not the declared one: meta has to say what happened, not what was intended.
#
# ⚠️ **And `description` carries the same obligation, which was being broken.** Both packs said
# "proper nouns pruned" / "sin nombres propios" while `proper_nouns` said `included` and English
# carried 163,470 proper nouns inside. It is not a comment: it is the text the user reads on the
# attribution screen, asserting the opposite of what the pack is. A `description` that describes a
# policy **goes stale on its own** when the policy changes -- so now it does not name it, and
# whoever wants to know reads `proper_nouns`.
#
# "lexical-only" and not "excluded" because the pruning has a measured exception: a proper noun
# with lexical life --the months, the countries, the languages-- is kept. See SENAL_LEXICA_MINIMA
# in sources/kaikki.py.
#
# `source_date` is the DUMP's date in YYYYMMDD, and it is informative: it says which dump the
# content comes from. **It is not the pack's version.** That one the builder derives from the
# build's clock (`build.data_version`), because rebuilding the same dump with another builder has
# to give a different number -- otherwise `devpack.py` and the installer read "it is the same
# pack" and a better pack never propagates.
def _destino(clave):
    """The SECOND declared language, or None if the pack has only one.

    Second is not secondary either: it is the other one. What it decides is whether the reader also
    emits the reverse entries, which is the only thing distinguishing a bidirectional pack from one
    that merely knows how to search in the other direction.
    """
    declarados = [x.strip() for x in PACKS[clave]["langs"].split(",")]
    return declarados[1] if len(declarados) > 1 else None


def _primario(clave):
    """The first language declared by a [PACKS] pack.

    ⚠️ **First is not main.** It is declaration order, and the only things it decides are the
    default language of a `Record` that declares none, and which sentence corpus gets read. In a
    bidirectional pack the two languages are peers: each one's entries carry their own `entry.lang`
    and their own fuzzy profile.
    """
    return PACKS[clave]["langs"].split(",")[0].strip()


PACKS = {
    "es": {
        "pack_id": "es-def-wikc",
        "kind": "monolingual",
        "name": "Español",
        "description": (
            "Definiciones en español del Wikcionario. Incluye sinónimos, antónimos "
            "y palabras relacionadas por acepción."
        ),
        "langs": "es",
        # ⚠️ **The language the payload's translations are in, and it is a DECLARATION the reader
        # needs**: without it the card shows a list of English words without saying they are
        # English. It does not make the pack bilingual --`kind` is still monolingual and the pack
        # declares a single language-- because they cannot be SEARCHED by: they are reading
        # content. The search channel is `trans`, which in this pack stays empty.
        # ⚠️ **The LANGUAGE the translations point at, and deliberately not a pack.**
        #
        # The first version also declared `translations_pack = "en-def-wikt"`. It was removed: if
        # the user has the English **core** installed and not the full one, the link died even
        # though there was an English dictionary perfectly able to resolve it. Naming the language
        # lets any installed pack of that language resolve it.
        #
        # The sense travels separately, as a suffix on the item, with a code that **also** names no
        # pack (see `payload.sense_code`): it is unique for `(language, word, sense)` and the core
        # and the full one share it by construction.
        "translations_to": "en",
        "fuzzy_profile": "es",
        "source_date": "20260915",
        "license": "CC-BY-SA-4.0",
        # The attribution is NOT written here: it is derived from FUENTES[fuente_base] (D-138), so
        # that adding content and adding credit are the same act.
        "fuente_base": "wikc",
        "source_url": "https://kaikki.org/eswiktionary/Espa%C3%B1ol/",
        "proper_nouns": "included",
    },
    # The SECOND Spanish base pack (D-139). It does not replace Wiktionary's: it is installed
    # alongside and queried together with it (D-136), and the gain is the union of lemmas -- 5,283
    # the other does not have, measured. It is also the catalog's only CC0 one.
    "es-wd": {
        "pack_id": "es-def-wd",
        "kind": "monolingual",
        "name": "Español (Wikidata)",
        "description": (
            "Definiciones en español de Wikidata Lexemes. Segundo diccionario de español: "
            "aporta gentilicios regionales y locuciones que el Wikcionario cubre peor."
        ),
        "langs": "es",
        "fuzzy_profile": "es",
        "source_date": "20260920",
        "license": "CC0-1.0",
        "fuente_base": "wd",
        "source_url": "https://dumps.wikimedia.org/wikidatawiki/entities/",
        "proper_nouns": "included",
    },
    # The third pack: BILINGUAL, and the only one that fills `trans`.
    #
    # ⚠️ **A single pack serves both directions, and the key is `trans`**: the entries are Spanish
    # words with English glosses, so searching "perro" finds it by prefix and searching "dog" finds
    # it through the reverse index (rung 3 of the cascade). What it does NOT give is the quality of
    # a pack written in the other direction: `trans` is derived from the glosses, it does not come
    # in the dump. See sources/bilingual.py.
    "es-en": {
        "pack_id": "es-tr-enwikt",
        "kind": "bilingual",
        # ⚠️ **A bilingual pack declares it TOO, and forgetting that was a real regression.** Since
        # D-183 the app asks for this key and not for `kind`, so without it the pack whose entire
        # purpose is translating stopped being offered. That it coincides with `meta.langs`'s
        # second language does not make it redundant: `langs` says which languages the pack HAS,
        # this says which language the payload's translations are in.
        "translations_to": "en",
        # ⚠️ **The arrow is TWO-headed since D-196**, and that is not cosmetic: the pack has
        # entries in both languages --`casa` and `house` in the same file-- and the name is what
        # the user reads on the home and in dictionary management. A one-way arrow asserted
        # something that had stopped being true.
        "name": "Español ↔ English",
        "description": (
            "Diccionario bilingüe en las dos direcciones: palabras españolas definidas en "
            "inglés y palabras inglesas con sus equivalentes en español."
        ),
        "langs": "es,en",
        "fuzzy_profiles": "es,en",
        "fuzzy_profile": "es",
        "source_date": "20260915",
        "license": "CC-BY-SA-4.0",
        "fuente_base": "enwikt",
        "source_url": "https://kaikki.org/dictionary/Spanish/",
        "proper_nouns": "included",
    },
    "en": {
        "pack_id": "en-def-wikt",
        "kind": "monolingual",
        "name": "English",
        "description": (
            "English definitions from Wiktionary. Includes synonyms, antonyms, "
            "related words and the source of each quoted example."
        ),
        # ⚠️ **English DOES translate, through the word channel.** It had been concluded that it
        # could not, by measuring that its 9,987 translations into Spanish carry **0
        # `sense_index`** -- but that only closes the `T` channel, which demands per-sense
        # attribution. The `W` channel exists precisely for the unattributable, so those 9,987 get
        # in, and they fill `trans` along the way: it is what makes searching `perro` find `dog` in
        # the English pack.
        "translations_to": "es",
        "langs": "en",
        "fuzzy_profile": "en",
        "source_date": "20260909",
        "license": "CC-BY-SA-4.0",
        "fuente_base": "wikt",
        "source_url": "https://kaikki.org/dictionary/English/",
        "proper_nouns": "included",
    },
    # SPIKE (D-120). It exists to measure, it is not a production pack: it is not in the catalog
    # and it does not go onto the watch. See sources/oewn.py.
    "en-core": {
        "pack_id": "en-core-oewn",
        "kind": "monolingual",
        "name": "English core",
        "description": "Spike: Open English WordNet 2025. Not a production pack (D-120).",
        # `langs` stays "en" and NOT "en-core": it goes into stable_uid(), and keeping it the same
        # as kaikki's pack is the only thing that leaves the two sources' logical identity
        # comparable if they are ever to be crossed.
        "langs": "en",
        "fuzzy_profile": "en",
        "source_date": "20251231",
        "license": "CC-BY-4.0",
        "fuente_base": "oewn",
        "source_url": "https://en-word.net/",
        # The standard OEWN 2025 edition carries no proper nouns: they are in Open English Namenet
        # / the 2025+ edition. The domain's reference source arrived at D-116 on its own.
        "proper_nouns": "excluded",
    },
}

# Which module each pack comes from. Two lines instead of a separate build_spike_oewn.py, which
# would duplicate the handling of --sample, of the metadata and of PackBuilder.
READERS = {"es": kaikki, "en": kaikki, "en-core": oewn, "es-wd": wikidata,
           "es-en": bilingual}


def _keep(headword, sample):
    if sample <= 1:
        return True
    digest = hashlib.sha256(headword.encode("utf-8")).digest()
    return int.from_bytes(digest[:4], "big") % sample == 0


def _con_sense_key_del_pack_final(nuevos):
    """Recomputes `sense_key` looking at the MERGED pack's homographs, not the source's.

    ⚠️ **`verify_pack.py` caught it and it is one of the expensive silent failures.** Each source
    decides whether an entry needs a `sense_key` by looking at ITS own homographs. On merging, a
    lexeme that had a twin in Wikidata can lose it --because the twin was already in the base
    source and got discarded-- and is left with a key that no longer corresponds to anything.

    That breaks `uid`, which is the logical identity and **the join key across packs** (D-055): the
    same lemma computed with a key in one pack and without one in another **stops joining**. It is
    the same error D-139 documents, coming in through another door.

    The rule that holds is the final pack's: a key goes to whoever has a homograph **there**. That
    is why the records are buffered --they are thousands, not millions-- instead of streamed.
    """
    cuenta = {}
    for record in nuevos:
        clave = (record.headword, record.part_of_speech)
        cuenta[clave] = cuenta.get(clave, 0) + 1
    for record in nuevos:
        if cuenta[(record.headword, record.part_of_speech)] == 1:
            record.sense_key = None
        yield record


def _pegar_ejemplo(record, ejemplos):
    """Glues the second source's example onto a THIN entry. See `sources/enwikt_examples`.

    ⚠️ **Only if the entry has one sense and none yet**, which is half the rule that prevents
    inventing the attribution -- the other half is applied by the source, requiring that there be
    only one there too. With several senses of ours it is unknown which to glue it to, and gluing
    it to the first is incorrect content that looks correct: the reader has no way of suspecting
    it.
    """
    if not ejemplos or len(record.senses) != 1 or record.senses[0]["examples"]:
        return
    traidos = ejemplos.get((record.headword, record.part_of_speech))
    if traidos:
        record.senses[0]["examples"] = list(traidos)


def main(argv):
    if len(argv) < 4 or argv[1] not in PACKS:
        sys.stderr.write(__doc__)
        sys.stderr.write("\nIdiomas: %s\n" % ", ".join(sorted(PACKS)))
        return 2
    lang, source, output = argv[1], argv[2], argv[3]
    sample = 1
    if "--sample" in argv:
        sample = int(argv[argv.index("--sample") + 1])
    politica = kaikki.POLITICA_POR_DEFECTO
    if "--con-nombres" in argv:
        politica = "included"
    if "--nombres" in argv:
        politica = argv[argv.index("--nombres") + 1]
    dump_ejemplos = None
    if "--ejemplos" in argv:
        dump_ejemplos = argv[argv.index("--ejemplos") + 1]
    dump_frases = None
    if "--frases" in argv:
        dump_frases = argv[argv.index("--frases") + 1]
    lista_frecuencias = None
    if "--frecuencias" in argv:
        lista_frecuencias = argv[argv.index("--frecuencias") + 1]
    flexiones = None
    if "--flexiones" in argv:
        flexiones = argv[argv.index("--flexiones") + 1]
    dump_tesauro = None
    if "--tesauro" in argv:
        dump_tesauro = argv[argv.index("--tesauro") + 1]
    sumar = None
    if "--sumar" in argv:
        i = argv.index("--sumar")
        sumar = (argv[i + 1], argv[i + 2])

    metadata = dict(PACKS[lang])
    # The manifest is assembled before anything else: the base source first, so it sits at the top
    # of the list, and each option adds its own where it mixes its content in.
    _declarar(metadata, metadata.pop("fuente_base"))
    if sample > 1:
        metadata["pack_id"] += "-sample%d" % sample
        metadata["name"] += " (piloto 1/%d)" % sample
    if politica != kaikki.POLITICA_POR_DEFECTO:
        # The default pack keeps the bare pack_id: if it changed, the watch's `pack_activo`, its
        # history and its favourites would point at a pack that no longer exists. The others suffix
        # it so two policies can be installed side by side and compared.
        metadata["pack_id"] += "-" + politica.replace("-", "")
        metadata["proper_nouns"] = politica
    ejemplos = {}
    if dump_ejemplos:
        # ⚠️ Two sources force naming BOTH, and that is what is expensive about this option
        # (D-135). It is not a formality: the attribution is the condition of the licence the pack
        # is distributed under, and both sources are CC BY-SA 4.0. It is written here, next to the
        # merge, so there is no way to mix the content without moving the credit.
        ejemplos = enwikt_examples.examples_by_entry(dump_ejemplos)
        metadata["pack_id"] += "-" + _declarar(metadata, "enwikt-ej")["codigo"]
        _describir(metadata, "enwikt-ej")
    frases = None
    if dump_frases:
        # Same criterion as above and the same reason: the credit moves WITH the content, here, so
        # there is no way to mix without attributing. Tatoeba is CC BY 2.0 FR --the CC0 export
        # brings 37 Spanish sentences out of 562,186-- so the attribution is compulsory, not a
        # courtesy.
        frases = tatoeba.shortest_by_norm(dump_frases)
        metadata["pack_id"] += "-" + _declarar(metadata, "tatoeba")["codigo"]
        _describir(metadata, "tatoeba")
    if lista_frecuencias:
        # ⚠️ **The pack DECLARES that its `rank` is frequency, and that is not decorative.** The
        # merge across packs is ordinal --`score` is the position within the pack's own results--
        # so the scale cancels out. What that does not fix: a badly calibrated pack puts the wrong
        # word at position 0 and on interleaving weighs the same as a well calibrated one. With
        # this key, at equal position the better calibrated one wins. Without it the app has no way
        # of knowing, and **it cannot be added later without rebuilding the pack**.
        metadata["rank_basis"] = "frequency-zipf-v1"
        # ⚠️ **Where the band with signal ends, DECLARED and not hardcoded on the watch.**
        #
        # `rank` is two disjoint bands (D-185): `[0, boundary)` comes from the Zipf and the rest
        # from page richness. The app needs to know where the cut is --word of the day uses it
        # (D-193)-- and the only alternative was copying the 500 into Kotlin, creating a **third
        # cross-language contract** that breaks in silence when it changes, like `norm()` and
        # `sense_code`. Declaring it turns it into a datum of the artifact.
        #
        # It is `rank_basis`'s same lesson: a key that cannot be added without rebuilding, and this
        # build is the opportunity.
        metadata["rank_signal_boundary"] = str(kaikki.FRONTERA_CON_SENAL)
        # The credit travels with the content, as above: the frequency list contributes NO text to
        # the pack, but it **decides the order of the results**, which is content just the same.
        # D-138 does not distinguish.
        metadata["pack_id"] += "-" + _declarar(metadata, "opensubs")["codigo"]
        _describir(metadata, "frecuencia")
    tesauro = None
    if dump_tesauro:
        # The format is decided by the pack's LANGUAGE, not by one more option: English has its own
        # WordNet in WN-LMF and Spanish arrives through OMW's .tab. They are the same idea served
        # differently, like the wiki's two shapes of synonyms (D-124).
        ingles = metadata["langs"].split(",")[0].strip() == "en"
        tesauro = wordnet.english(dump_tesauro) if ingles else wordnet.spanish(dump_tesauro)
        metadata["pack_id"] += "-" + _declarar(
            metadata, "oewn-tesauro" if ingles else "mcr")["codigo"]
        _describir(metadata, "wordnet")

    if sumar:
        # ⚠️ **A union of ROWS, not of fields.** The added source contributes LEMMAS the base does
        # not have; the ones they share keep the base's definition and there is nothing to
        # arbitrate. That is what makes it cheap -- and what distinguishes this from composition,
        # which splits an entry in two and is still blocked by `uid`'s granularity (D-146).
        metadata["pack_id"] += "-" + _declarar(
            metadata, PACKS[sumar[0]]["fuente_base"])["codigo"]
        _describir(metadata, "suma")

    # ⚠️ **The final identity: LANGUAGE + TIER, and it is stamped HERE, after every suffix**
    # (D-215). Until here `pack_id` had been accumulating which sources it comes from
    # --`es-def-wikc-tat-freq-wn-wd`-- and that has a defect: **adding a source changes the
    # identity**, so the pack looks like another one and the app does not recognize it as the one
    # already installed. The sources are not lost: they stay whole in `meta.sources`, which is
    # where they get consulted (D-138).
    #
    # ⚠️ **The bilingual one is left out, on purpose**: it has no tiers because its purpose is not
    # a size of the same dictionary, it is something else. It keeps its usual `pack_id`.
    if metadata.get("kind") != "bilingual":
        idioma = metadata["langs"].split(",")[0].strip()
        metadata["pack_id"] = "%s-full" % idioma
        metadata["tier"] = "full"
        # Through the same helper as `build_core`, so the tier is stamped ONCE even if the name
        # already carries one. See `build.name_with_tier`.
        metadata["name"] = name_with_tier(metadata["name"], "full")

    if os.path.dirname(output):
        os.makedirs(os.path.dirname(output), exist_ok=True)

    with PackBuilder(output, metadata, sentences=frases, thesaurus=tesauro) as builder:
        # ⚠️ **The ordering prior.** Without this `rank` is page richness --senses, examples and
        # above all FORMS-- and that rewards verbs: measured, it correlates **-0.250** with real
        # usage frequency where -1 would be expected. The symptom shows where D-142's coverage band
        # does not reach: `house` returned `solar, alojar, albergar` and never `casa`.
        #
        # The two sources are combined on the Zipf scale: OpenSubtitles decides and Tatoeba
        # --already on disk and already declared-- fills in what it does not cover. Each lemma
        # takes its value from ONE source; averaging occurrences against
        # sentences-that-contain-it would be a calibration nobody measured.
        mapa_frecuencias = None
        if lista_frecuencias:
            from sources import frequency
            principal = frequency.to_zipf(frequency.load(lista_frecuencias))
            relleno = {}
            # ⚠️ `dump_frases` and NOT `frases`: by this point `frases` is already the dictionary
            # of sentences `shortest_by_norm` assembles, not the corpus's path.
            if dump_frases:
                lang_corpus = {"es": "spa", "en": "eng"}.get(_primario(lang))
                relleno = frequency.to_zipf(
                    tatoeba.frequencies(dump_frases, lang=lang_corpus))
            mapa_frecuencias = frequency.combined(principal, relleno)
        reader = READERS[lang]
        # The bilingual reader receives the SOURCE language and not the CLI's key: "es-en" names
        # the pack, but the normalization profile and kaikki's `PERFILES` are Spanish's.
        argumentos = (
            (source, lang) if reader is oewn
            # ⚠️ The fourth argument is what makes the pack BIDIRECTIONAL: with it, the English
            # words stop being `trans` keys and become entries with their own `entry.lang`. It
            # comes from `meta.langs`, so what the pack declares and what the reader emits cannot
            # be separated.
            else (source, _primario(lang), politica, mapa_frecuencias, _destino(lang))
            if reader is bilingual
            # `translations_to` comes from the same table that declares it in `meta`, so the pack's
            # promise and what the reader emits cannot be separated.
            else (source, lang, politica, PACKS[lang].get("translations_to"), mapa_frecuencias)
            if reader is kaikki
            else (source, lang, politica)
        )
        vistos = set()
        # ⚠️ **The TARGET language's inflections, read from an already built pack.** They close the
        # reverse direction, which was weak for a structural reason: the Spanish side has `form`
        # and every inflection reaches its lemma, the English side only had the derived keys, so
        # `dogs` was found only if some gloss wrote it. Measured over the most used English words:
        # **78.1 % -> 98.9 %** in the top 8,000.
        #
        # They expand inside `record.translations`, that is, inside the `trans` table, and **that
        # touches neither the schema nor the query**: they are more rows of the same. The measured
        # alternative --an indirection table-- weighs 1.84 MB against 5.88, but it asks for a new
        # table, a new rung and two round trips per search. It is noted as an optimization with its
        # number.
        mapa_flexiones = {}
        if flexiones:
            from sources import inflections
            mapa_flexiones = inflections.por_lema(flexiones)
        # ⚠️ **In a bidirectional pack the inflections go to the reader, not to the loop.** There
        # they hang off the English ENTRY's `form` --`got` is an inflection of `get`, and `get` is
        # already a lemma-- instead of expanding inside `trans`, which in this pack is empty.
        # Skipping this step cost **8.6 points** of reverse coverage in the English top 1,000,
        # measured.
        if reader is bilingual and mapa_flexiones:
            argumentos = argumentos + (mapa_flexiones,)
            mapa_flexiones = {}
        for record in reader.records(*argumentos):
            if not _keep(record.headword, sample):
                continue
            _pegar_ejemplo(record, ejemplos)
            vistos.add((record.headword, record.part_of_speech))
            if mapa_flexiones and record.translations:
                claves = list(record.translations)
                ya = set(claves)
                for clave in record.translations:
                    for forma in mapa_flexiones.get(clave, ()):
                        if forma not in ya:
                            ya.add(forma)
                            claves.append(forma)
                record.translations = tuple(claves)
            builder.add(record)
        if sumar:
            # After the base and not mixed in: the order **is** the arbitration rule. Whoever
            # arrives first keeps the lemma, so the base source's definition wins without anybody
            # having to compare them.
            #
            # The key is the EXACT lemma and not `norm()`: "papa" and "papá" share a norm and are
            # two words, and "Dr." or "km²" would be lost against the entry that normalizes the
            # same.
            extra = READERS[sumar[0]]
            nuevos = []
            for record in extra.records(sumar[1], _primario(sumar[0]), politica):
                clave = (record.headword, record.part_of_speech)
                if clave in vistos or not _keep(record.headword, sample):
                    continue
                vistos.add(clave)
                nuevos.append(record)
            for record in _con_sense_key_del_pack_final(nuevos):
                builder.add(record)

    print("%s: %d entradas, %d bytes" % (output, builder.count, os.path.getsize(output)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
