"""Usage frequency: the prior the results are ordered by.

## What problem it closes, measured

`kaikki._rank` computed the prior as **richness of the dump's page** --senses, examples, inflected
forms, translations, etymology-- and that rewards verbs: a Spanish verb carries up to 222 forms and
the `es` profile counts them up to a cap of 80, which is **the dominant term of the 1000 points**.
Measured over the real Spanish pack, the Spearman correlation between `rank` and real usage
frequency was **-0.250**, where -1 would be expected.

⚠️ **The symptom was not where it seemed.** `coverageBand` (D-142) already defends the prefix rung
without trusting the pack, so the damage there was bounded. The `INFLECTED_FORM` and `TRANSLATION`
rungs **have no such defence** --`orderFor` applies the band only to `PREFIX`-- and ordered by raw
`rank`:

    house  ->  solar, alojar, albergar, domiciliar    <- "casa" did not appear
    water  ->  gastar, regar, resbalar                <- "agua" did not appear
    book   ->  reservar, fichar, multar               <- "libro" did not appear

## Why subtitles

The SUBTLEX literature is consistent across more than six languages: **subtitle frequencies
predict word recognition better than book-corpus ones**, because they resemble speech. That is
exactly the register of somebody looking a word up on a watch.

## Why Zipf and not the raw count

The distribution is a power law: `de` appears 14,459,520 times and word number 50,000 appears 185.
Without a logarithm the first crushes them all and the rest of the vocabulary is
indistinguishable. Zipf is `log10(frequency per billion)`, the standard normalization --the same
scale `wordfreq` uses-- and it has the property that is needed: **three orders of magnitude are
three points**, not a factor of a thousand.

⚠️ **The idea is used, not the package**: `wordfreq` is a pip dependency and `tools/CLAUDE.md`
pins *stdlib only* as a deliberate property.

## Why the primary wins instead of averaging

The two sources measure different things: OpenSubtitles counts **occurrences** and
`tatoeba.frequencies` counts **sentences containing the word**. Averaging them would be
calibrating one scale against another, which is a decision nobody measured. Each lemma takes its
value from **one** source.
"""

import math
import unicodedata


def key(word):
    """The key a frequency is looked up by: lowercase in NFC, **with the accent**.

    ⚠️ **`norm()` is NOT used, and a defect seen in the built pack decided that.** `norm()` folds
    accents, and in Spanish the accent **distinguishes words**: with the normalized key, an
    obscure word inherits its common homograph's frequency.

        háber  (an obscure unit)  ->  rank  97   <- it took `haber`'s, the verb
        hábil                     ->  rank 237       (229,602 occurrences, position 210)
        líbero                    ->  rank 240   <- it summed `libero` + `liberó`
        liberal                   ->  rank 248

    What is lost is coverage --a word written with a different accent in the list does not match--
    and what is gained is that the signal does not lie. A word with no entry of its own falls into
    the band with no signal, which is the right answer: nobody measured its frequency.
    """
    return unicodedata.normalize("NFC", word).strip().lower()


def load(path):
    """`{key(word): count}` from a `word<space>count` list. See [key].

    A line that is not understood **is ignored rather than breaking**: it is 50,000 lines
    downloaded from the internet, and one bad one cannot bring down an hour-long build.
    """
    salida = {}
    with open(path, encoding="utf-8") as handle:
        for linea in handle:
            partes = linea.split()
            if len(partes) != 2:
                continue
            palabra, cuenta = partes
            if not cuenta.isdigit():
                continue
            clave = key(palabra)
            if clave:
                salida[clave] = salida.get(clave, 0) + int(cuenta)
    return salida


def to_zipf(counts):
    """`{norm: zipf}` where Zipf 3 == once per million, 6 == once per thousand.

    It is `wordfreq`'s scale: `log10(frequency per billion)`. What matters here is that it be
    **logarithmic**, because the distribution is not.
    """
    total = sum(counts.values())
    if not total:
        return {}
    return {k: math.log10(v / total * 1e9) for k, v in counts.items()}


def combined(principal, relleno):
    """The primary decides; `relleno` contributes only what it lacks. See the docstring."""
    salida = dict(relleno or {})
    salida.update(principal)
    return salida


def por_norm(counts, norm):
    """`{norm(word): count}`, **summing** when several words share a key.

    ⚠️ **Summing and not overwriting, and that cost one wrong measurement.** Written as a dict
    comprehension --`{norm(k): v for k, v in counts.items()}`-- the last word wins: in the English
    list `a` ended up with **3,942** occurrences instead of **14,484,562**, because some rare word
    normalizes the same and came later. The symptom was not an error but an absurd order, with
    `didn` heading the list of the most frequent English words.

    `norm` is passed as an argument and not imported so this module stays free of a dependency on
    `normalize`, which is what keeps it cheap to test.
    """
    salida = {}
    for palabra, cuenta in counts.items():
        clave = norm(palabra)
        if clave:
            salida[clave] = salida.get(clave, 0) + cuenta
    return salida


def cobertura(vocabulario, frecuencias):
    """What fraction of the corpus's TOKENS `vocabulario` covers, as a percentage.

    ⚠️ **It is a tier's metric, and the only one that answers the right question.** A `core` is not
    judged by how many words it carries --that is a number with no units-- but by what fraction of
    what somebody will look up it holds inside. It is measured over **tokens** and not over types:
    a missing word that appears a million times is not the same as a missing one that appears
    three.

    Measured over the real packs: full English covers **96.63 %** and Spanish **78.87 %**. The
    difference is not the pack's quality, it is the corpus's: OpenSubtitles Spanish carries a lot
    of inflected forms the pack resolves through `form` and this count does not see.

    ⚠️ **It saturates.** Beyond the ~50,000 words the list attests, adding lemmas does not raise
    the number: a 130 MB English `main` already reaches the 96.63 % of the full 307 MB pack. What a
    larger tier buys beyond that is finding the rare, which is another metric and is not here.
    """
    total = cubierto = 0
    for clave, cuenta in frecuencias.items():
        total += cuenta
        if clave in vocabulario:
            cubierto += cuenta
    return 100.0 * cubierto / total if total else 0.0
