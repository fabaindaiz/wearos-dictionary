"""Word prevalence: the share of speakers who know a word. SPALEX, for Spanish.

    python3 tools/packbuilder/build_pack.py es <es.jsonl> <out.db> --prevalencia spalex-word_info.csv

## Why it exists

*Rare* in *see more words* means recherché: a real word that few people know. Frequency cannot say
that -- *pelícano* is infrequent and known by everybody, *inefable* frequent enough to look common
and known by 63 % -- and prevalence says it directly. Measured on 2026-10-26: version 1's *rare*
had a median of 86 % of people knowing the word; the published lists of recherché words sit at
35-63 % (`docs/roadmap.md` i-a2f271-ec624b).

## The source, and what may be done with it

SPALEX (Aguasvivas et al. 2018, *Frontiers in Psychology* 9:2156), the `Word information` file on
Figshare (doi 10.6084/m9.figshare.5924794), **CC BY 4.0**: the pack may carry what is derived from
it with attribution, which `build_pack` declares in `meta.sources`. About 45,000 words, rated by
speakers in Spain and Latin America together (`percent_total`). The file itself is not in the
repository; it lives with the dumps.

⚠️ **English has no equivalent with an open license.** Brysbaert et al. 2019 is CC BY-NC-SA 4.0
at its source, so it serves to measure the English rules locally and never enters a pack.
"""

import csv


def load(path):
    """`{spelling: percent_known}` from SPALEX's `word_info.csv`, lowercase spelling.

    A row with no reading is skipped, not read as 0 %: absence is not "nobody knows it".
    """
    known = {}
    with open(path, encoding="utf-8", newline="") as handle:
        for row in csv.DictReader(handle):
            value = (row.get("percent_total") or "").strip()
            word = (row.get("spelling") or "").strip().lower()
            if not word or not value:
                continue
            try:
                known[word] = float(value)
            except ValueError:
                continue
    return known
