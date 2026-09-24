package cl.fadiaz.dictionary.core

/** Which kind of dictionary a pack carries. Declared in `meta.kind`. */
enum class PackKind(val id: String) {
    /** A language pair with translations: "correr" -> "to run". */
    BILINGUAL("bilingual"),

    /** A single language with definitions: "correr" -> "moverse rapidamente...". */
    MONOLINGUAL("monolingual"),
    ;

    companion object {
        fun fromId(id: String): PackKind =
            entries.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("unknown meta.kind: $id")
    }
}

/**
 * How a pack computed its `rank`, that is, **what the number it sorts by means**.
 *
 * ⚠️ **It exists because merging across packs could not know it any other way, and that was
 * costing order.** The merge is already **ordinal** --`Suggestion.score` is the position within
 * the pack's own results, so the `rank` scale cancels out-- which makes it immune to one pack
 * using 0..1000 and another 900..1000. What that does **not** fix: a badly calibrated pack puts
 * the wrong word in position 0, and on interleaving it carries the same weight as a well
 * calibrated one.
 *
 * Measured over the real packs: `es-def-wikc` ranges 668..1997 and `es-def-wd` 911..997 --the same
 * language with different formulas, because `sources/wikidata.py` has its own-- and nothing told
 * the app.
 *
 * Read with `meta[...]`: an older pack does not carry it and [PAGE_RICHNESS] is assumed, which is
 * what all of them were.
 */
enum class RankBasis(val id: String) {
    /** `rank` comes from real usage frequency, on a Zipf scale. The good calibration. */
    FREQUENCY("frequency-zipf-v1"),

    /**
     * `rank` comes from the richness of the dump's page: senses, examples, **forms**.
     *
     * Measured: it correlates **-0.250** with real usage frequency, where -1 would be expected,
     * because a Spanish verb carries up to 222 forms and it counts every one.
     */
    PAGE_RICHNESS("page-richness-v1"),
    ;

    companion object {
        /** An unknown id **does not throw**: a newer pack may carry a basis we do not read. */
        fun fromId(id: String?): RankBasis = entries.firstOrNull { it.id == id } ?: PAGE_RICHNESS
    }
}

/**
 * The contents of a pack's `meta` table. Read whole when it is opened, once.
 *
 * [schemaVersion] and [normVersion] are validated against what the app supports: a pack with a
 * different normVersion is indexed under different normalization rules and has to be rejected,
 * because it would not fail -- it would simply return fewer results than it should.
 */
data class PackMetadata(
    val packId: String,
    val schemaVersion: Int,
    val normVersion: Int,
    val kind: PackKind,
    /**
     * The SHORT name, for a watch row. "Español", not "Español - definiciones".
     *
     * Measured by eye on the watch: in the dictionaries screen row, after the reserved check, the
     * paddings and the delete button, the name is left with ~140 dp. "Español - definiciones" is
     * 22 characters and was clipped in all four places it is shown. What the long name said --that
     * it carries definitions-- now comes from [kind], which is data rather than a string somebody
     * has to read.
     */
    val name: String,
    /**
     * The long text, for the attribution screen. Null in a pack older than D-125.
     *
     * Optional on purpose: it is read with `meta[...]` and not with `getValue`, so an old pack
     * still opens. The format has no migrations (D-001) but that applies to `schema_version`; a
     * new, additive key is exactly what this tolerance exists to support.
     */
    val description: String?,
    /**
     * The language of the translations the pack carries in its payload, or null if it carries none.
     *
     * ⚠️ **It is a CAPABILITY, which is why it lives apart from [kind].** `kind` answers *"which
     * language are the definitions in"*; this answers *"does it translate?"*. They are different
     * questions and the Spanish pack is the proof: it is `MONOLINGUAL` --it defines in Spanish--
     * and **it translates into English**. While the app asked `kind`, the translate action never
     * appeared over it.
     *
     * Optional and read with `meta[...]`: an older pack does not carry it and must still open.
     */
    val translationsTo: String? = null,
    /** What this pack's `rank` means. See [RankBasis]. */
    val rankBasis: RankBasis = RankBasis.PAGE_RICHNESS,
    /**
     * The pack's languages, **as peers and in declaration order**.
     *
     * ⚠️ **It replaces `langSource`/`langTarget`, and the change is conceptual before it is
     * mechanical.** That pair said one language was the source and the other the target, which is
     * true of a pack that translates IN ONE direction. A bidirectional pack has entries of both
     * --`casa` and `house` in the same file, each with its own `entry.lang`-- and neither is the
     * main one. Asked for: *"declare both languages as peers and not one as the main one"*.
     *
     * A monolingual pack declares a single one and nothing changes for it. **The order is
     * declaration, not hierarchy**: the only thing it decides is which one an entry that declares
     * none of its own uses.
     */
    val langs: List<String>,
    /**
     * Each language's folding profile, **positional against [langs]**.
     *
     * It exists because typo-tolerant folding DOES depend on the language --`ce`→`se` is a Spanish
     * rule-- while `norm()` does not. See [fuzzyProfileFor].
     */
    val fuzzyProfiles: List<FuzzyProfile>,
    /**
     * Which class of pack this is: `full` or `core`.
     *
     * ⚠️ **The artifact DECLARES it instead of it being inferred from the name.** A core steps
     * aside when the full one is installed, and until here that came from `subsetOf` --which names
     * the other pack-- or from the `pack_id` ending in `-core`, which is guessing from the name
     * what D-138 decided gets declared. `subsetOf` answers *"I am part of THAT one"* and this
     * answers *"I am a core"*, which is what is needed without knowing the other.
     */
    val tier: PackTier = PackTier.FULL,
    /**
     * Where the band of `rank` that carries a frequency signal ends, if the pack declares it.
     *
     * ⚠️ **Declared and not copied, and that avoids a third cross-language contract.** `rank` is
     * two disjoint bands when [rankBasis] is frequency (D-185); the app needs the cut --word of
     * the day uses it-- and the alternative was copying the builder's `500` into Kotlin, which is
     * exactly the class of constant that drifts in silence, like `norm()` and `sense_code`. Null =
     * the pack does not say, and then there is no band to respect.
     */
    val rankSignalBoundary: Int? = null,
    val entryCount: Int,
    /**
     * Which build of the pack this is: `YYYYMMDDHHMM`, off the build's clock.
     *
     * ⚠️ **`Long` and not `Int`, and that is not a style detail**: `202609211432` passes the top
     * of a 32-bit `Int`, so with `Int` the pack blows up on OPENING on the watch with a
     * NumberFormatException that does not name the key (D-070). A builder test pins it from the
     * other side.
     *
     * **It is not the dump's date**, which is `meta.source_date` and is informative. This one
     * orders: an installer compares two numbers to know which pack is newer, and two builds of the
     * same dump have to give different numbers or a better pack does not propagate.
     */
    val dataVersion: Long,
    /**
     * The `pack_id` of the dictionary that **contains this one**, or null.
     *
     * It is a claim about CONTENT, not about size or version: *"everything I have, that one
     * has"*. That is precisely what cannot be worked out on the watch --comparing 150,000 lemmas
     * would cost more than the search-- and so it is declared, just as `pack_id` declares identity
     * instead of guessing it from the file name (D-138).
     *
     * ⚠️ **`entry_count` is NO use for this.** It says which is bigger, which is another thing:
     * two packs from different sources can both be big without either containing the other, and
     * there querying both is exactly what is wanted (D-136).
     *
     * Null in any pack older than this, which is the case for every one today.
     */
    val subsetOf: String? = null,
    val license: String,
    val attribution: String,
    /**
     * The sources this pack declares, each with **its own** licence (D-138).
     *
     * ⚠️ **[license] and [attribution] stay, and they are not redundant.** They are the
     * collection's governing licence and its one-paragraph credit, which is what a pack built
     * before this field has and all a small screen can show at a glance. This list is the
     * itemised version: with definitions under CC BY-SA 4.0 and corpus sentences under CC BY
     * 2.0 FR, one name for the whole pack either over-claims or under-credits.
     *
     * Empty for a pack built before D-138, and that is handled rather than rejected: it reads
     * from `meta["sources"]`, not `getValue`, so an older pack still opens (same tolerance as
     * `description` in D-125).
     */
    val sources: List<PackSource> = emptyList(),
) {
    init {
        require(langs.isNotEmpty()) { "a pack declares at least one language in meta.langs" }
        require(fuzzyProfiles.size == langs.size) {
            "meta.fuzzy_profiles carries ${fuzzyProfiles.size} profiles for ${langs.size} languages"
        }
        // ⚠️ A bilingual declares TWO, as peers. It used to require `lang_dst`, which presupposed
        // a source and a target; what is required now is that there be two and neither be the
        // main one.
        require(kind != PackKind.BILINGUAL || langs.size >= 2) {
            "a bilingual pack declares both its languages in meta.langs (it declares $langs)"
        }
    }
}

/** How a result was arrived at. It orders the list ahead of any other criterion. */
enum class MatchKind {
    /** The lemma starts with what was typed. The normal path. */
    PREFIX,

    /** An inflected form matches: "corriendo" was typed and the lemma is "correr". */
    INFLECTED_FORM,

    /** The translation side matches: "run" was typed in an es->en pack. */
    TRANSLATION,

    /** The typo-tolerant key matches. Only attempted when the above returned nothing. */
    FUZZY,

    /** The definition text matches. Only on an explicit action by the user. */
    DEFINITION,
}

/**
 * One row of the results list. It is served whole from the covering index, without reading or
 * decompressing the payload: an entry's body is fetched only when the user opens it.
 */
data class Suggestion(
    val packId: String,
    val entryId: Long,
    val headword: String,
    val partOfSpeech: String?,
    val matchKind: MatchKind,
    /** Lower is better. Edit distance in FUZZY; relative position in the rest. */
    val score: Int,
    /**
     * Whether this lemma falls in the band of `rank` that carries **real usage frequency** (D-185).
     *
     * ⚠️ **It is a boolean and NOT the `rank`, and that shape is the decision.** Raw `rank` is not
     * comparable across packs --each computes it against its own dump with its own formula
     * (D-187)-- so exposing it would invite exactly the comparison that is worthless. Each pack
     * resolves the signal against **its** `meta.rank_signal_boundary` (D-198) and what crosses the
     * boundary is the answer, not the scale.
     *
     * ⚠️ **It exists because of a defect measured in English.** `coverageBand` rewards short
     * lemmas --typing `wat` covers `wat` 100 % and `water` 60 %-- and the English Wiktionary is
     * full of three-letter fragments: interjections, initialisms, bound forms. The mean position
     * of the obvious word was **5.1** on a first screen of three rows.
     *
     * `false` for a pack that does not declare the boundary, which is how all of them behaved.
     */
    val hasFrequencySignal: Boolean = false,
)

/**
 * Which class of pack this is, of the two the app treats differently.
 *
 * `fromId` does not throw on an unknown id: a newer pack may carry a class this version does not
 * know, and treating it as full is the safe degradation -- it gets queried unnecessarily, which is
 * work, not a wrong result.
 */
enum class PackTier(val id: String) {
    /** The whole dictionary, unfiltered. The pack that was built, not one derived from it. */
    FULL("full"),

    /**
     * A filter less strict than [CORE]: in doubt about a word, it stays.
     *
     * ⚠️ **A language whose `full` already fits the `main` budget has NO `main`**, and that is
     * deliberate: full Spanish is 73.6 MB, under the budget, so a Spanish `main` would be a second
     * pack with the same content. The catalog lists what exists.
     */
    MAIN("main"),

    /** The important, general-use words. The smallest one. */
    CORE("core"),
    ;

    companion object {
        fun fromId(id: String?): PackTier = entries.firstOrNull { it.id == id } ?: FULL
    }
}

/**
 * Why a pack **does not load**, as data and not as a log string.
 *
 * ⚠️ **It exists because the reason reached the screen.** Until here a rejection was an
 * exception's `message`, written for `logcat` and in a single language: useful for debugging and
 * **impossible to show somebody** who only sees that a dictionary is missing. A type gets
 * translated (D-127), is stored in the verification memo without dragging prose along, and **does
 * not compile** until somebody gives it a text -- the same rule D-125 put on `PackKind`.
 *
 * ⚠️ **They all reject, and that was a product decision taken against the recommendation.** It was
 * proposed to separate the ones that LIE --miscomputed keys, a misaligned `fts_def`, a truncated
 * pack-- from the ones that merely DEGRADE --an index is missing and the search falls back to
 * scanning-- and reject only the first group. The simple rule was chosen: *any broken invariant
 * rejects*, because a second category fills up with exceptions and stops being honest. **The
 * accepted cost**: a perfectly readable dictionary missing one index disappears from the list, and
 * all the user reads is one line saying why. See D-217.
 *
 * Declaration order is checking order, cheapest to most expensive, and that matters: a pack with
 * the wrong schema is rejected **before** its rows are counted.
 */
enum class PackRejection(val id: String) {
    /** `meta` is missing a key without which you cannot even tell what the file is. */
    METADATA("metadata"),

    /** A different `schema_version`: queries would point at columns that changed (D-001). */
    SCHEMA_VERSION("schema"),

    /** A different `norm_version`: indexed under other rules, it would return fewer words (D-006). */
    NORM_VERSION("norm"),

    /** A `payload_codec` this app cannot read. */
    PAYLOAD_CODEC("codec"),

    /**
     * It does not declare where its content comes from, or some source declares no licence.
     *
     * ⚠️ **It rejects, and that settles on its own the requirement that an incompatible pack not
     * appear in the credits**: nobody has to remember to exclude it from the attribution screen,
     * because it never comes to exist for the rest of the app. D-031 says that screen is not
     * optional, and a pack that cannot be credited makes it impossible to comply with.
     */
    LICENSE("license"),

    /** `idx_entry_norm` or `idx_entry_fuzzy` is missing: the search would scan the whole table. */
    MISSING_INDEX("index"),

    /** The builder's staging table was left behind: the pack was built half way. */
    HALF_BUILT("staging"),

    /** `meta.entry_count` does not match the real rows: the file is truncated. */
    ENTRY_COUNT("count"),

    /**
     * `fts_def` does not have one row per entry.
     *
     * Its `rowid` **is** `entry.id` (D-011). If they drift apart, searching by definition returns
     * **other** entries -- not fewer, but wrong ones, which is worse.
     */
    FTS_MISALIGNED("fts"),

    /** Some entry has an empty `norm`: no search path reaches it. */
    EMPTY_KEY("emptykey"),

    /** An inflected form or a translation points at an entry that does not exist. */
    ORPHAN_ROW("orphan"),

    /** The recomputed keys do not match the ones the pack carries (D-142). */
    KEYS("keys"),

    /** The compression dictionary is not the one the pack declares: the text would come out corrupt. */
    PAYLOAD_DICTIONARY("dict"),

    /** Anything else: the file is not a pack, or SQLite could not open it. */
    DAMAGED("damaged"),
    ;

    companion object {
        /**
         * The reason with this id, or [DAMAGED] if it is not recognized.
         *
         * ⚠️ **It does not throw, on purpose**: the id comes from the memo in `SharedPreferences`,
         * which another version of the app may have written. An unknown id means *"there was a
         * reason and this version does not know which"*, and treating it as damaged makes the pack
         * get tried again -- which is the right degradation, not hiding it forever.
         */
        fun fromId(id: String?): PackRejection = entries.firstOrNull { it.id == id } ?: DAMAGED
    }
}

/**
 * The folding profile for the given language, or the first one's if the pack does not know it.
 *
 * ⚠️ **It never throws, and that is the decision.** A language the pack does not declare is a bug
 * in the builder or in the caller, but failing here would leave the search dead; falling back to
 * the first profile returns slightly worse results on the tolerant rung, which is the last of the
 * cascade and the least trustworthy anyway.
 */
fun PackMetadata.fuzzyProfileFor(lang: String?): FuzzyProfile {
    val indice = langs.indexOf(lang)
    return fuzzyProfiles.getOrNull(indice) ?: fuzzyProfiles.firstOrNull() ?: FuzzyProfile.GENERIC
}

/** Whether the pack has entries in this language. A bidirectional pack answers `true` for both. */
fun PackMetadata.speaks(lang: String?): Boolean = lang != null && lang in langs

/**
 * An entry's cheap header: what can be known **without decompressing the payload**.
 *
 * It exists because some decisions need a specific entry's `rank` and `pos` and not its body
 * --picking the word of the day is the first-- and opening the payload for that would mean paying
 * an inflate per discarded candidate.
 *
 * It is not a [Suggestion]: that one is a result row and carries `matchKind` and `score`, which
 * mean nothing here. And it carries `rank`, which [Suggestion] deliberately does not expose.
 */
data class EntrySummary(
    val entryId: Long,
    val headword: String,
    val partOfSpeech: String?,
    val rank: Int,
)

/**
 * A usage example, and where it was quoted from when the source said so.
 *
 * ⚠️ **The citation is a field of the example and not a parallel list, and that is the whole
 * design.** Two lists that have to stay aligned by index drift silently -- one example dropped
 * anywhere upstream and every citation below it names the wrong sentence. This repo already has
 * that failure mode named: D-122 calls it *content that is wrong and looks right*, and rates it
 * worse than a missing word.
 *
 * [citation] is null far more often than not, and that is the source and not a gap: only 75,5 %
 * of the examples the English dump carries declare a `ref`, and the Tatoeba sentences carry none
 * by design -- that corpus is credited once per pack in `meta.sources`, never per sentence.
 */
data class Example(val text: String, val citation: String? = null)

/** One sense of an entry. */
data class Sense(
    val gloss: String,
    val examples: List<Example> = emptyList(),
    val translations: List<String> = emptyList(),
    /**
     * Synonyms of THIS sense, not of the entry (D-117).
     *
     * The distinction matters: "domingo" has `mesada, paga` in one sense and `pollerudo,
     * calzonazos` in another. Together they mean nothing.
     *
     * **Both languages carry them** (D-124). Spanish declares them with `sense_index` and English
     * serves them nested inside each sense; both shapes give the same attribution.
     *
     * It goes last on purpose: the ten existing call sites are positional with three arguments or
     * fewer, and so they compile untouched.
     */
    val synonyms: List<String> = emptyList(),
    /**
     * Antonyms of THIS sense (D-126).
     *
     * Same rule as [synonyms] and **a worse consequence when attributed wrong**: a synonym under
     * the wrong sense reads as odd, an antonym reads as the opposite of something else.
     *
     * Unlike synonyms, **they do not go into `fts_def`**: searching "frio" to find "caliente" is
     * not what anybody does, and putting them in the free-text index would only add noise to a
     * search whose ordering is already open debt (D-067).
     */
    val antonyms: List<String> = emptyList(),
    /**
     * **Related** words of this sense: a hypernym, a hyponym or a morphological relative (D-132).
     * They are not synonyms and the separate list is the whole difference: "frances" carries
     * `galo`, which is not an equivalent but a neighbour, and presenting it as a synonym would
     * assert something false.
     *
     * They exist because of the **thin entries**, which are 70.4 % of the Spanish pack: a single
     * sense with no example. Measured, 2,142 of 29,817 thin ones gain something here (7.2 %).
     *
     * **They only come filled when the entry has a single sense**, because the source declares
     * them at entry level and without `sense_index`: with several there is no datum saying which
     * one they belong to. See `sources/kaikki._relacionadas` in the builder.
     *
     * They do not go into `fts_def` either, for the same reason as the antonyms: nobody searches
     * "camelido" expecting "guanaco", and result ordering is already open debt (D-067).
     */
    val related: List<String> = emptyList(),
)

/** An entry's full body, as it comes out of the decompressed payload. */
data class Entry(
    val packId: String,
    val entryId: Long,
    /**
     * The entry's **logical** identity: stable across rebuilds of the pack, and the key by which
     * an auxiliary pack (synonyms, translations) adds information to this same entry.
     *
     * [entryId] is no use for that: it is the local rowid and it shifts wholesale when the pack is
     * rebuilt with new data.
     *
     * The builder computes it; the app **never** recomputes it, it only reads it. That is the
     * difference with `norm`/`fuzzy`, and it is what stops it being a second contract between two
     * languages.
     *
     * It is not in [Suggestion] on purpose: the list is served whole from the covering index
     * without touching the table, and adding the uid there would force every row to be read.
     * Composition happens when an entry is opened, not when it is listed.
     */
    val uid: Long,
    /**
     * THIS entry's language, which in a bidirectional pack is not the pack's.
     *
     * ⚠️ **It is what makes the card's label honest (D-190).** A result row can deduce the
     * language from the list --it is filtered to one-- but the card cannot: it is reached by
     * tapping a translation, and then the opened entry is **in the other language**. Without this
     * field the label would assert the wrong language, which is worse than leaving it off.
     *
     * Null = a pack older than `schema_version` 4. It cannot happen --the app rejects those
     * packs-- but the type says so instead of trusting.
     */
    val lang: String? = null,
    val headword: String,
    val partOfSpeech: String?,
    val senses: List<Sense>,
    /**
     * Translations of the whole word, which the source **could not** attribute to a sense.
     *
     * They go apart from [Sense.translations] and not mixed in: a list drawn under a sense
     * **asserts** that it belongs to that sense, and hanging unattributed data there is the D-117
     * mistake -- it reads plausible and nobody catches it. Measured, they are 37.7 % of the data.
     */
    val wordTranslations: List<String> = emptyList(),
    /**
     * The word's principal parts, with their own spelling. See [PayloadCodec.InflectedForm].
     *
     * ⚠️ **Not the `form` table.** That one holds `norm(form)` --`corrais`, not `corráis`--
     * because it is a search key; showing it would be misspelled. These travel in the payload
     * precisely so the card can show them.
     *
     * Empty for any pack built before the channel existed, and the card then has no forms
     * section at all.
     */
    val forms: List<PayloadCodec.InflectedForm> = emptyList(),
    /**
     * The word's pronunciation in IPA, without delimiters, or null. See
     * [PayloadCodec.Body.pronunciation].
     *
     * ⚠️ **Null covers two cases the card cannot separate**: a pack built before the channel, and
     * a word whose source carried none. Both draw nothing, which is right on screen; the question
     * *why* is answered by `verify_pack.py`, which reports the tag's coverage per pack.
     */
    val pronunciation: String? = null,
)
