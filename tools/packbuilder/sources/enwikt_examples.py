"""Spanish usage examples, taken from the ENGLISH Wiktionary, Spanish section.

It is the Spanish pack's **second source**, and the only thing taken from it is the examples. Its
glosses are English translations: bringing them in would turn the pack bilingual, which is
something else (D-034). Nothing is defined here, things are illustrated.

**Why it exists.** 70.4 % of the Spanish pack is single-sense entries with no example, and the
Spanish Wiktionary dump has nothing more to give them: measured in D-132, of the unused fields
only 7 % was usable and it has been used.

⚠️ **And why it is NOT on by default** (D-135). The honest number is small --**326 entries, 0.28 %
of the pack**-- and the price is not the code but the **attribution**: using two sources forces
naming both in every pack, forever. Both are CC BY-SA 4.0, so there is no licence incompatibility;
there is a permanent obligation for 0.28 %. The decision to pay it is the user's, and that is why
this is a CLI option and not a default.

**How 326 was reached, which is the part not to rediscover.** The roadmap estimated 5,307 by
crossing on the lemma. That number counts cases where **the attribution would have to be
invented**: entries of ours with several senses, where it is unknown which one the example
illustrates. The four filters below are what is left when nothing is invented, and each comes from
looking at the dump:

    crossing on the lemma, unfiltered               ~5,300
    + our entry has ONE sense                        2,172
    + theirs also has ONE, and the same `pos`          510
    + `english` present (confirms that `text` is
      the Spanish and not notation or metatext)        326

What each filter removes, with the case that justifies it:

    one sense there   "y" has 5 senses there and 1 here: the example "jamon y queso" may be
                      illustrating a sense our pack does not have
    same `pos`        without it "A" (noun) comes in with the example of "A" as notation
    `type`            191 items with no `type` are notes, not examples: "Near-synonym: pedazo",
                      "Coordinate term: ovarios", a link to Wikipedia
    `english`         without it "19. Ac4xd5, Ab7xd5" comes in -- chess from a quoted game
"""

import json

# The two types that are uses of the word. Measured in the dump: 3,729 `example` (written) and
# 1,950 `quotation` (quoted from a published text, with a `ref`). Both are dictionary content. The
# 191 WITHOUT a `type` are not, which is why the filter lists rather than excludes.
TIPOS_QUE_SON_USO = ("example", "quotation")

# An example longer than this does not fit on a watch screen without eating the definition. The
# number is the width of two lines at 234 dp; the ones that fall out are 7 of 250 (2.8 %).
MAX_LARGO = 90

# One example is enough for what this solves --an entry that looks empty-- and two already push
# the definition off screen. Same criterion as MAX_EXAMPLES_PER_SENSE in kaikki.py.
MAX_POR_ENTRADA = 1


def examples_by_entry(path):
    """A `(headword, pos) -> [examples]` map, ready for the builder to consult.

    The key carries the `pos` because without it "A" as a noun inherits another thing's example.
    It fits in memory easily: it is a few thousand entries, not the whole dump.
    """
    out = {}
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            if not line.strip():
                continue
            raw = json.loads(line)
            if raw.get("lang_code") != "es":
                continue
            senses = raw.get("senses") or []
            # With more than one sense it is unknown which the example illustrates. See the
            # docstring.
            if len(senses) != 1:
                continue
            word, pos = raw.get("word"), raw.get("pos")
            if not word or not pos:
                continue
            buenos = []
            for item in senses[0].get("examples") or []:
                text = (item.get("text") or "").strip()
                if not text or len(text) > MAX_LARGO:
                    continue
                if item.get("type") not in TIPOS_QUE_SON_USO:
                    continue
                if not (item.get("english") or "").strip():
                    continue
                buenos.append(text)
                if len(buenos) == MAX_POR_ENTRADA:
                    break
            if buenos:
                out[(word, pos)] = buenos
    return out
