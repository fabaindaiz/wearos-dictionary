package cl.fadiaz.dictionary.core

/**
 * The knobs that shape behaviour and **travel with the build**.
 *
 * Asked for in those words -- *«que esto sea configurable desde algún archivo json interno en la
 * app»* -- and then widened to cover every knob of the same kind: settings that shape behaviour,
 * can be edited, and belong to the build rather than to the user.
 *
 * ## What belongs here, and what does not
 *
 * Three things live in different places and confusing them is how a setting ends up unreachable
 * or unrevertable:
 *
 * - **The user's settings** ([cl.fadiaz.dictionary.core.PackMetadata] aside, they are `Settings`
 *   in `:app`) are choices somebody made about their own watch. They persist, they have a screen,
 *   and they are never overwritten by an update.
 * - **The debug overrides** (`DebugKnobs`) are scaffolding for one build, set over `adb` and
 *   expired on a `versionCode` change on purpose.
 * - **This** is neither: it is what the build was compiled to do. Changing it means editing
 *   `assets/tuning.json` and building again, which is exactly the point — the value is versioned
 *   with the code that reads it and a watch cannot end up with a combination nobody shipped.
 *
 * ## Why every field has a default
 *
 * The asset can be missing, malformed, or carry a key this build does not know. None of those may
 * stop the app: a dictionary that does not open is worse than one tuned by last month's numbers.
 * Parsing therefore **falls back per field**, and says so through `DictLog` — a silent fallback
 * would make *"my change did nothing"* indistinguishable from *"the file did not parse"*.
 *
 * In `:dict-core` and not in `:app` because `:dict-data` reads the search half, and the JSON is
 * parsed in `:app`, where Android lives (D-017: nothing here imports `java.*`).
 */
data class Tuning(
    val search: SearchTuning = SearchTuning(),
    val display: DisplayTuning = DisplayTuning(),
)

/**
 * The cascade's numbers.
 *
 * ⚠️ **These were MEASURED, and putting them here has a cost that was named before the decision
 * was taken.** Each one comes out of `tools/measure_query_cost.py` over the real packs, and the
 * measurement is not reconstructable by reading the code. A value in a JSON invites being moved
 * without measuring again, and the failure that produces is not a crash — it is a search that got
 * slower, or a rung that stopped firing, months before anybody notices. The owner chose this
 * scope with that stated; what the comments below buy is that whoever moves a number reads what
 * it was measured to be.
 */
data class SearchTuning(
    /**
     * How many results a query returns. 30, and the screen shows the first 3–4.
     *
     * It is not a display number: it is the size of the list the cascade fills before it stops
     * climbing rungs, so lowering it makes the tolerant rung fire **more** often, not less.
     */
    val limit: Int = 30,
    /**
     * How many rows the prefix rung asks for, as a multiple of [limit].
     *
     * Over-fetching exists because the rung returns rows ordered by `rank` and the merge reorders
     * them; asking for exactly [limit] would let a better match from a later rung be cut by a
     * worse one that merely arrived first.
     */
    val prefixOverfetch: Int = 3,
    /**
     * Below how many accumulated results the typo-tolerant rung runs.
     *
     * 5. It is the most expensive rung and the least trustworthy, so it is a floor and not a
     * threshold: with a handful of good matches it never runs at all.
     */
    val fuzzyTrigger: Int = 5,
    /**
     * How many candidate rows the tolerant rung reads before scoring them. 200.
     *
     * ⚠️ **The one most worth leaving alone.** It bounds the only unbounded scan in the cascade;
     * a larger number is a linear cost paid on every typo, which is the common case on a wrist.
     */
    val fuzzyCandidates: Int = 200,
)

/** What the screens draw, and how much of it. */
data class DisplayTuning(
    /**
     * Which principal parts a card shows, **in this order**, by the key the pack stores.
     *
     * The known keys are `ind1s`, `ind2s`, `ind3s`, `ind1p`, `ind3p`, `pret1s`, `pret3s`, `sub1s`,
     * `part`, `ger`, `past`, `pl`, `fem`. A key that is not listed is not drawn; a key listed and
     * absent from the entry costs nothing.
     *
     * ⚠️ **Six, and read as the first column of a conjugation table** — chosen in those words over
     * the six that change the stem most. Two consequences are real and were stated before the
     * choice: **a noun or an adjective now shows no Forms section at all**, because `pl` and `fem`
     * are outside the list, and an **English verb loses its past tense**, because English tags it
     * `past` rather than as an indicative. Both come back by adding the key here.
     */
    val forms: List<String> = listOf("ind1s", "ind3s", "pret1s", "pret3s", "sub1s", "part"),
    /** Senses drawn before the *see more* row. */
    val visibleSenses: Int = 3,
    /**
     * How far the probe of an empty result counts before it stops, and what the pill then says
     * as `N+`.
     */
    val emptyProbeCap: Int = 9,
    /**
     * How tall a line becomes in a gloss that **has links**, as a multiple of its font size, or
     * `null` to leave the leading exactly as the typography sets it.
     *
     * ⚠️ **It defaults to OFF, and it defaulted to 2.8 for one day.** It was built to widen the
     * band `GlossTap` resolves a tap inside -- the touch area of a linked word is its glyph,
     * ~40 x 14 dp against a 48 dp minimum -- and the cost was named when it was built: a sense
     * goes from 3--4 visible lines to about 2. Seen on the watch, the cost is what shows: a
     * definition reads as separate lines with a gap between them, which is what a reader notices
     * before any mis-tap. Reverted by the owner on sight, which is the rule this project already
     * had written down -- an interface decision reasoned at a desk gets reverted on the wrist.
     *
     * It stays as a value rather than being deleted because the measurement behind it is real
     * and is not reconstructable from the code: at 2.8 a `bodyMedium` line goes from ~40 px to
     * **79 px**, 1.98x, which doubles the vertical band with no glyph touched. Whoever wants that
     * trade sets the number; the mis-tap it was for is still open as **P-11**.
     */
    val glossLineHeightInFonts: Float? = null,
)
