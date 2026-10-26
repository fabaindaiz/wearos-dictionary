"""The tier of *see more words*: common, rare or curious, decided over one raw Wiktionary record.

## What it is for

A reloadable list of random words to learn, per language, filtered by tier and by part of
speech (`docs/roadmap.md` i-a2f271-ec624b). The builder writes the tier into the pack's
`discover` table; the app only draws from it.

## Why `rank` alone cannot say "rare", measured 2026-10-21

`rank = 499 - round(zipf * 70)` only for a word the frequency list knows, and the list stops near
Zipf 2.3. From 500 up `rank` is page richness, which means **the frequency is absent**, not that
the word is rare: 82 % of `es-full` and 95 % of `en-full` sit there, and read as "very rare" the
tier fills with *hentriacontanoic*. And frequency misjudges rarity even where it exists: the
subtitle list rates *airbag* as rarely as *fragor*. What separates them is how much the
dictionary says about the word -- its translations, synonyms, a literary label -- so *rare* is a
low frequency **and** notability.

## Why the rules are on the record and on the gloss

Every signal is in the Wiktionary record the pack is already built from (CC BY-SA): nothing
third-party enters. The usage labels and topics are not in the pack, which is why the app cannot
recompute the tier and the builder writes it. The first gloss is what the card shows first, and it
gives away the noise no tag marks: a transparent derivative (*Que achica*, *One who incites*), a
variant, a demonym, science with no topic tag (*An isotope of hydrogen*).

## What is NOT decided here

Two rules need the whole pack and live in `PackBuilder` instead: a verb or adjective in *-ndo*,
*-do*, *-ing*, *-ed* whose key is a form of **another** verb (*girando*, kept as an entry by
D-066), and which part of speech a frequency belongs to (the noun *ridículo*, a handbag, inherited
the adjective's frequency).

## Version 2: who knows the word, where that is known (2026-10-26)

Version 1 cut *rare* by frequency and notability, and measured against word prevalence -- the share
of people who know a word -- its median *rare* word was known by **86 %** in Spanish and **92 %**
in English, while the published lists of recherché words sit at 16-63 %. Two corrections:

- **Spanish carries SPALEX** (`sources/prevalence.py`, CC BY 4.0): where it covers a word, *rare* is
  being known by 10-75 % of speakers, with a learned origin, a literary label or a synonym, and not a
  species or a trade. It covers about a third of the pack's nouns, verbs and adjectives.
- **Elsewhere a learned origin carries it**, below the translation count that marks a well-known
  word: version 1 read many translations as notability and against English prevalence they are the
  opposite. English has no prevalence norm with an open license, and its *rare* stays the weaker of
  the two -- measured, not guessed: `docs/roadmap.md` i-a2f271-ec624b.

Common was right in version 1 and is unchanged: its median word is known by 99 %.
"""

import re

#: The version of this policy, written to `meta.discover_version` by the builder. A tier is a
#: verdict the pack stores, so changing a rule here means rebuilding the packs; the version is how
#: a pack says which rules it was built under. Bump it with any change to what `tier` returns.
VERSION = 2

#: The tiers, as the `discover` table stores them and the app reads them. Part of the pack's
#: contract: renumbering one makes every installed pack draw from the wrong tier, with no error.
COMMON, RARE, CURIOUS = 0, 1, 2

#: The parts of speech a tier holds, with the code the table stores. Adverbs stay out: in both
#: languages most are the adjective plus *-mente* / *-ly*, the same word to learn twice.
POS_CODES = {"noun": 0, "verb": 1, "adj": 2}

#: Below this, function words (*a, de, el*). The word of the day's `RANK_FLOOR`, for the same
#: reason and on the same scale.
RANK_FLOOR = 150

#: Zipf 3.0: the top of the *low frequency* band in the SUBTLEX convention. Above it, *common*;
#: below it, a candidate for *rare*.
RARE_FROM = 290

#: A curious word is uncommon too. 250 is Zipf 3.56: *mu* and *rafe* still pass, *chulo* does not.
CURIOUS_FROM = 250

#: Translations past which a word is too well known to be rare. Version 1 read many translations
#: as *notable* and it is the opposite: measured against English prevalence on 2026-10-26, words
#: with 20 or more translations are known by over 90 % of people 64-71 % of the time, words with
#: none 41 %. A recherché word is one the dictionaries have not bothered to translate much.
WELL_KNOWN_TRANSLATIONS = 20

#: Senses with any of these and two senses or fewer: the page is about the label.
_DROP = frozenset({
    "vulgar", "derogatory", "offensive", "slur", "ethnic", "no-gloss", "misspelling",
    "abbreviation", "initialism", "acronym", "alternative", "nonstandard", "proscribed",
    "pronunciation-spelling", "eye-dialect", "Internet", "slang",
})

#: The usage labels that make a word curious. `outdated` is how the Spanish dump says *desusado*.
_CURIOUS = frozenset({
    "outdated", "obsolete", "archaic", "dated", "rare", "jocular", "humorous", "nonce-word",
    "poetic", "Lunfardo", "Germanía", "historical",
})

#: The regions that make a word curious. Always, even for somebody who lives there: asked for by
#: the owner on 2026-10-21, *sodero* shows to a reader in Chile too.
_REGION = frozenset({
    "Chile", "Argentina", "Mexico", "Venezuela", "Colombia", "Peru", "Uruguay", "Río-de-la-Plata",
    "Cuba", "Bolivia", "Honduras", "El-Salvador", "Ecuador", "Nicaragua", "Costa-Rica",
    "Puerto-Rico", "Guatemala", "Paraguay", "Dominican-Republic", "Andalusia", "Panama",
    "Central-America", "Aragon", "Chiloé", "Canary-Islands", "Scotland", "Australia", "Ireland",
    "Northern-England", "Southern-US", "dialectal", "regional", "Cockney", "Geordie",
    "New-Zealand", "South-Africa", "India",
})

#: A literary or learned label is notability on its own.
_LITERARY = frozenset({"literary", "formal", "poetic", "cultism", "learned"})

#: A page whose every topic is one of these is a term of art, not a word to learn.
_TECHNICAL = frozenset({
    "chemistry", "biology", "medicine", "anatomy", "zoology", "botany", "mineralogy",
    "organic-chemistry", "biochemistry", "pharmacology", "pathology", "genetics", "physics",
    "mathematics", "computing", "taxonomy", "mycology", "ichthyology", "entomology", "geology",
    "astronomy", "biological-sciences", "natural-sciences", "medical", "sciences", "mammals",
    "cities", "linguistics", "grammar", "topology", "algebra", "geometry", "finance", "law",
    "economics", "electronics", "engineering", "physiology", "cytology", "ornithology",
})

#: A first gloss that says the page is a form of another word, in the shapes with no `form_of`.
_FORM_GLOSS = re.compile(
    r"(?i)^(gerundio|participio|forma|primera|segunda|tercera|plural|femenino de|inflexión|"
    r"alternative|obsolete form|archaic form|misspelling|plural of|present participle|"
    r"past participle|simple past|third-person|comparative|superlative)")

#: The prefixes of a transparent English coinage: *nonharmful*, *reorderer*, *overskies*.
_COINAGE_PREFIXES = ("non", "un", "re", "over", "under", "anti", "semi", "pseudo", "sub")

#: A first gloss that gives away a derivative, a variant, a demonym or a language name.
_DERIVATIVE = re.compile(
    r"(?i)^\s*(\(.*?\)\s*)?(que \w+(\s\w+)?\.?$|acción (o|y) efecto|efecto de|"
    r"calidad (o cualidad )?de|condición de ser|persona originaria|originario|natural de|"
    r"relativo a|perteneciente a|propio de|lengua|idioma|variante|forma (alternativa|antigua)|"
    r"profesional de|unidad monetaria|synonym of|alternative|"
    r"the (state|quality|condition|act) (or \w+ )?of being|the (state|quality) of|one who|"
    r"someone who|somewhat|characteristic of|resembling|not \w+[;.]|a person from|a native of|"
    r"the language|of or pertaining to|pertaining to|relating to|lack of)")

#: Science named in the gloss, which no topic tag marked: *zinc*, *deuterium*, *acónito*.
_SCIENCE = re.compile(
    r"(?i)\b(chemical element|atomic number|isotope|compound of|enzyme|protein|genus|species of|"
    r"family [A-Z]|any of (several|various) .*(plants|fish|birds|insects)|elemento químico|"
    r"número atómico|isótopo|compuesto (químico|de)|enzima|género de|"
    r"especie de (planta|ave|pez|insecto|árbol)|planta de la familia|medicine\b|disease|"
    r"enfermedad|inflamación|síndrome|syndrome|"
    r"in (geometry|chemistry|physics|anatomy|biology|zoology|botany|mathematics))")


#: The share of people who know a word, in percent, that makes it *rare* when it is known. The
#: published lists of recherché Spanish words sit at a median of 35-63 % (SPALEX, 2026-10-26),
#: and above 75 % a word is one most readers already have; below 10 % it is one nobody does.
KNOWN_RARE = (10.0, 75.0)

#: Above this, a labelled word is known by nearly everyone, and *curious* would promise an oddity
#: the reader already uses. A quarter of version 1's *curious* sat here.
KNOWN_CURIOUS_MAX = 90.0

#: An etymology through Latin or Greek: the strongest Wiktionary signal of a recherché word,
#: x3 to x7 more frequent in the published lists than in a random sample of the pack.
_LEARNED = re.compile(r"(?i)\b(lat[ií]n|latin|griego|greek)")

#: A species or a trade, named by the first gloss. Infrequent and unknown to many, so prevalence
#: alone lets them in -- *aligustre*, *alcaraván*, *pizarrero* -- but they are vocabulary of a
#: field, not words to learn.
_TAXON = re.compile(
    r"(?i)^\s*(\(.*?\)\s*)?(planta|árbol|arbusto|hierba|ave|pájaro|pez|insecto|mamífero|molusco|"
    r"reptil|hongo|fruto|semilla|variedad de|especie de|nombre común de|cualquiera de|any of|"
    r"a (species|genus) of|an? (small |large )?(plant|tree|shrub|herb|bird|fish|insect|mammal|"
    r"moth|beetle|snake|fungus|mollusc|mollusk)\b)")
_TRADE = re.compile(
    r"(?i)^\s*(persona |el |la )?(que (fabrica|vende|hace|trabaja|repara|elabora|transporta|"
    r"se dedica)|oficio de|operario|one who (makes|sells|deals|repairs)|an? (maker|seller|"
    r"dealer|manufacturer) of)")


#: The jargon a learned etymology lets into *rare* where no prevalence judges it: measured on
#: 2026-10-26 over the English rare tier, these cut the technical share of a by-eye sample from
#: 21 of 60 to 15 of 60 with none of 14 literary control words lost. Prevalence cannot do this
#: itself: jargon and literature are equally unknown.
_JARGON_AFFIX = re.compile(
    r"^(lymph|cyto|histo|haemat|hemat|neuro|osteo|chondr|gastr|hepat|nephr|pneumo|bronch|phyto|"
    r"bacter|myco|chloro|sulf|sulph|phospho|nitro|sacchar|glyco|nucleo|homeo|electro|magneto|"
    r"spectro|thermo)|(itis|osis|oma|aemia|emia|algia|ectomy|otomy|ostomy|plasty|scopy|ase|phyte|"
    r"cyte|blast|morph|iform|genic|philia|phobia|lith|ptych|ometer|ometry|idae|aceae|oid)$")
_JARGON_GLOSS = re.compile(
    r"(?i)\b(cells?|tissues?|organism|species|compounds?|acids?|crystal\w*|minerals?|"
    r"chromosomes?|genes?|enzymes?|molecul\w*|atoms?|atomic|ions?|proteins?|bacteri\w*|"
    r"fung(i|us|al)|larva\w*|insects?|spiders?|worms?|earthworms?|plants?|stamens?|catkins?|"
    r"fossil\w*|glands?|nerves?|muscles?|arter\w*|lymph\w*|membranes?|secretion|diseases?|"
    r"symptoms?|inflammation|tumou?rs?|infection|anatom\w*|physiolog\w*|chemi\w*|botan\w*|"
    r"zoolog\w*|geolog\w*|mathemat\w*|equations?|matrix|vectors?|electr\w*|magnet\w*|"
    r"isomer\w*|resins?|alkaloids?|burrows?|embryo\w*|spores?|pollen|strata|igneous|"
    r"sediment\w*|silicate|sulfate|oxide|reptiles?|mammals?|crustaceans?|beetles?|moths?|"
    r"antennae?|organs?|parasit\w*|algae|antibiotics?|pigments?|toxins?|hormones?|"
    r"diacritics?|grammatical|syllables?|phonem\w*|vowels?|syndrome|feces|genital\w*)\b")
#: Topics of the first sense that do not make a word jargon: the humanities are where literary
#: words live. Only the first sense is read: a later technical sense does not unmake a word.
_HUMANITIES = frozenset({
    "sciences", "human-sciences", "literature", "poetry", "mythology", "philosophy", "religion",
    "Christianity", "theology", "history", "lifestyle", "entertainment", "media", "arts", "art",
    "writing", "communications", "journalism", "publishing", "mysticism", "ethics",
    "metaphysics", "rhetoric", "folklore", "occult", "fantasy", "fiction", "drama", "theater",
    "science-fiction", "emotions", "human-behaviour",
})


def _gloss_text(sense):
    return " ".join(sense.get("glosses") or [])


def _is_form(sense, gloss):
    return bool(sense.get("form_of") or sense.get("alt_of") or _FORM_GLOSS.match(gloss))


def _count(raw, key):
    """How many `key` the record carries, at the top and inside its senses: the dumps use both."""
    total = len(raw.get(key) or [])
    for sense in raw.get("senses") or []:
        total += len(sense.get(key) or [])
    return total


def tier(raw, first_gloss, rank, etymology=None, known=None):
    """The tier of this record, or None when it is not a word to learn.

    `first_gloss` is the first gloss the pack keeps, which is what the card shows first; `rank`
    is the one the pack writes; `etymology` is the text
    the pack keeps, or None; `known` is the share of people who know the word, in percent, when
    a prevalence norm covers it (SPALEX for Spanish), or None.
    """
    word = raw.get("word") or ""
    pos = raw.get("pos")
    if pos not in POS_CODES:
        return None
    # A capital is a name or an initialism, a digit or a space is not one word to learn.
    if not word.replace("-", "").isalpha() or word[:1].isupper():
        return None
    if rank < RANK_FLOOR:
        return None

    glossed = forms = labelled = literary = 0
    tags, topics = set(), set()
    first_topics = None
    for sense in raw.get("senses") or []:
        gloss = _gloss_text(sense)
        if not gloss:
            continue
        glossed += 1
        if first_topics is None:
            first_topics = set(sense.get("topics") or [])
        sense_tags = set(sense.get("tags") or [])
        tags |= sense_tags
        topics |= set(sense.get("topics") or [])
        if _is_form(sense, gloss):
            forms += 1
        # ⚠️ **A label counts only with a definition of three words or more.** Without this the
        # tier filled with old spellings -- *asconder*, *callentar* -- whose whole definition is
        # the word they became: obsolete, and nothing to learn.
        if sense_tags & (_CURIOUS | _REGION) and len(gloss.split()) >= 3:
            labelled += 1
        if sense_tags & _LITERARY:
            literary += 1

    if glossed == 0 or forms * 2 >= glossed:
        return None
    if tags & _DROP and glossed <= 2:
        return None
    if topics and topics <= _TECHNICAL:
        return None
    if _DERIVATIVE.search(first_gloss or ""):
        return None

    translations = _count(raw, "translations")
    synonyms = _count(raw, "synonyms")
    gloss = first_gloss or ""
    science = bool(_SCIENCE.search(gloss))
    field = bool(_TAXON.search(gloss) or _TRADE.search(gloss))
    learned = bool(_LEARNED.search(etymology or "")) or literary > 0
    # Version 2: a recherché word has a learned origin, a literary label or a synonym to be
    # explained by; without any of them it is vocabulary of a trade or a region.
    explained = learned or synonyms >= 1
    coined = word.startswith(_COINAGE_PREFIXES) or "-" in word

    if (labelled * 2 > glossed and rank >= CURIOUS_FROM and translations + synonyms >= 1
            and "-" not in word and len(word) <= 12):
        if science or (known is not None and known > KNOWN_CURIOUS_MAX):
            return None
        return CURIOUS
    if known is not None:
        # ⚠️ **Where prevalence is known it decides, over frequency.** *inefable* is frequent
        # enough to be common by Zipf and known by 63 %; *archipiélago* is infrequent and known by
        # 98 %. Neither verdict can come from frequency.
        if KNOWN_RARE[0] <= known <= KNOWN_RARE[1]:
            if (explained and labelled * 2 <= glossed and not science and not field
                    and not coined):
                return RARE
            return None
        if known < KNOWN_RARE[0]:
            return None
    if rank < RARE_FROM:
        return COMMON if labelled == 0 else None
    if known is not None:
        return None

    # ⚠️ **Without prevalence, a learned origin carries it alone**, and only below the translation
    # count that marks a well-known word. Measured against English prevalence: 38 % of these land in
    # the recherché band against 26 % for version 1's notability, which a synonym let anything pass.
    jargon = (bool((first_topics or set()) - _HUMANITIES) or bool(_JARGON_GLOSS.search(gloss))
              or bool(_JARGON_AFFIX.search(word)))
    if (learned and translations < WELL_KNOWN_TRANSLATIONS and not coined and not jargon
            and labelled * 2 <= glossed and not science and not field):
        return RARE
    return None
