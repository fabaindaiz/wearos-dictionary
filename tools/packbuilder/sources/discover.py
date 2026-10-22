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

## The bar, and what it is not

The owner's bar is 10 to 15 useful words of 20. Judged by the agent over 60 words per tier and
language: common ~18 and ~17, rare ~10 and ~9, curious ~13 and ~11 (es, en). A judgement, not a
labelled measurement; *rare* sits at the floor, and what would lift it is prevalence data, whose
license is unclear (`docs/references.md` §*Word rarity*).
"""

import re

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

#: Translations that make a word notable. English Wiktionary translates far more, so one number
#: would let *pistachio* into English *rare* or keep *fragor* out of Spanish.
NOTABLE_TRANSLATIONS = {"es": 8, "en": 20}

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


def tier(raw, first_gloss, rank, lang, has_etymology):
    """The tier of this record, or None when it is not a word to learn.

    `first_gloss` is the first gloss the pack keeps, which is what the card shows first; `rank`
    is the one the pack writes; `lang` picks the notability threshold.
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
    for sense in raw.get("senses") or []:
        gloss = _gloss_text(sense)
        if not gloss:
            continue
        glossed += 1
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
    science = bool(_SCIENCE.search(first_gloss or ""))

    if (labelled * 2 > glossed and rank >= CURIOUS_FROM and translations + synonyms >= 1
            and "-" not in word and len(word) <= 12):
        return None if science else CURIOUS
    if rank < RARE_FROM:
        return COMMON if labelled == 0 else None

    notable = ((translations >= NOTABLE_TRANSLATIONS.get(lang, NOTABLE_TRANSLATIONS["en"])
                or literary > 0 or (synonyms >= 3 and has_etymology))
               and not word.startswith(_COINAGE_PREFIXES) and "-" not in word)
    if notable and labelled * 2 <= glossed and not science:
        return RARE
    return None
