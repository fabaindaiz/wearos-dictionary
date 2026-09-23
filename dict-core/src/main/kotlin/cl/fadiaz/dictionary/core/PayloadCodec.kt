package cl.fadiaz.dictionary.core

/**
 * The format of an entry's body (the `entry.payload` column).
 *
 * THIS FILE HAS A MIRROR: tools/packbuilder/payload.py
 *
 * The payload is compressed with raw deflate and a preloaded dictionary shared by the whole pack,
 * stored in `meta.payload_dict`. Entries are a few hundred bytes, that is, far too short for
 * deflate to find redundancy on its own; the dictionary hands it a window already primed with the
 * corpus's frequent fragments.
 *
 * deflate was chosen over zstd even though it compresses less, because deflate is in
 * `java.util.zip` (the Android platform, no extra .so) and in Python's stdlib `zlib`. zstd would
 * force a native library on the watch ON TOP of SQLite's, and a pip dependency in the builder, to
 * gain a few points of compression.
 *
 * Once decompressed it is UTF-8 text, one line per field:
 *
 *     P<TAB>verb                     (part of speech, optional, before any S)
 *     S<TAB>moverse rapidamente      (opens a sense)
 *     E<TAB>corrio hasta la esquina  (example of the open sense)
 *     T<TAB>to run                   (translation of the open sense)
 *     Y<TAB>bobo                     (synonym of the open sense)
 *     S<TAB>dicho del tiempo...      (opens the next sense)
 *
 * Delimited text is used instead of JSON or CBOR on purpose: it parses with no dependency at all
 * in either language, it can be read by eye when debugging a pack, and after compression the size
 * difference against a binary format is noise.
 *
 * Unknown tags are ignored, so a newer builder can add fields without breaking an old app.
 *
 * Even so [CODEC_ID] is bumped when the text format changes, because `PackFile.open` compares it
 * with `!=` and rejects the pack. Today that costs a rebuild and a re-sideload, and nothing more.
 * **Once the installer exists, an ADDITIVE tag does not bump it** (D-119): forcing a 300 MB
 * re-download for a field the old reader ignores would throw away precisely this property.
 */
object PayloadCodec {

    /** Bumped when the format changes. Written to `meta.payload_codec` as "deflate-v<n>". */
    const val PAYLOAD_VERSION: Int = 2

    const val CODEC_ID: String = "deflate-v2"

    private const val TAG_PART_OF_SPEECH = 'P'
    private const val TAG_SENSE = 'S'
    private const val TAG_EXAMPLE = 'E'
    private const val TAG_TRANSLATION = 'T'
    private const val TAG_SYNONYM = 'Y'

    /**
     * An antonym of this sense (D-126).
     *
     * **It does not bump `CODEC_ID` and that is deliberate.** D-119 put it in writing that an
     * additive tag must not bump it: that is what ignoring unknown tags exists for. A pack with
     * antonyms opened by an old reader shows the entry without them.
     */
    private const val TAG_ANTONYM = 'A'

    /**
     * A related word of this sense (D-132): a hypernym, a hyponym or a morphological relative.
     * Additive like [TAG_ANTONYM], so **it does not bump [CODEC_ID]** either.
     *
     * Its own tag rather than reusing [TAG_SYNONYM]: `galo` is related to "frances", not
     * equivalent to it.
     */
    private const val TAG_RELATED = 'R'

    /**
     * Where the example above it was quoted from. Additive like [TAG_ANTONYM] and [TAG_RELATED],
     * so it **does not bump [CODEC_ID]** either.
     *
     * ⚠️ **A tag and not a `` suffix on `E`**, even though the suffix mechanism already
     * exists and translations use it: bolting it onto a tag that shipped without it would make
     * an older app paint the raw separator byte inside the example. A new tag degrades to
     * nothing; a new suffix degrades to garbage.
     *
     * ⚠️ **It only names the example written immediately above it** — see [parse]. The strict
     * rule is mirrored character for character in `payload.py`, because if the two sides
     * disagreed the same pack would show different attributions depending on who read it.
     */
    private const val TAG_CITATION = 'C'

    /**
     * Translations of the WORD, with no sense attributed.
     *
     * Additive like [TAG_ANTONYM] and [TAG_RELATED], so **it does not bump [CODEC_ID]** either: an
     * old reader drops it through the `else -> Unit` and shows the entry without the list, which
     * is the right degradation.
     *
     * ⚠️ **It exists so that the dishonest option stops being the cheap one.** With `T` alone
     * --which lives inside a sense-- a builder holding a translation the source did not attribute
     * could either drop it or hang it off the first sense, and the second reads plausible and
     * nobody catches it (D-117). Measured: it is **37.7 %** of the Spanish dump's translations.
     */
    private const val TAG_WORD_TRANSLATION = 'W'

    /**
     * A **principal part** of the word: gerund, participle, plural or feminine.
     *
     * ⚠️ **The value is `key:form`, and the key is deliberately neutral.** Storing the label
     * already translated --"gerundio"-- would put the interface's language *inside* the pack, and
     * the same pack is shared by a user running the app in Spanish and one running it in English.
     * The app maps the key to its localized string, which is where translation belongs.
     *
     * ⚠️ **Few and chosen, not the whole conjugation.** `correr` carries 137 forms in the source
     * and 202 rows in `form`; dumping those into the card would be unreadable on a watch and
     * expensive in bytes -- `form` is already 42 % of the Spanish pack. What travels here are the
     * parts the rest derive from, which is what a printed dictionary puts beside the headword.
     *
     * ⚠️ **And it is NOT the `form` table.** That one stores `norm(form)` --`corrais`, not
     * `corráis`-- because its job is to be a search key. A card that showed `corrais` would be
     * misspelled, and that is exactly why this channel had to exist at all.
     */
    private const val TAG_FORM = 'F'

    /** Separates the key from the form inside a [TAG_FORM]. Mirrors `payload.FORM_SEPARATOR`. */
    private const val FORM_SEPARATOR = ':'

    /** The decoded body, without the data that already comes in `entry`'s columns. */
    data class Body(
        val partOfSpeech: String?,
        val senses: List<Sense>,
        /** Translations of the whole word, with no sense. See [TAG_WORD_TRANSLATION]. */
        val wordTranslations: List<String> = emptyList(),
        /**
         * The word's principal parts, in the order the builder chose. See [TAG_FORM].
         *
         * Empty for every pack built before this channel existed, which is the degradation: the
         * card simply has no forms section. Nothing fails and nothing is left blank.
         */
        val forms: List<InflectedForm> = emptyList(),
    )

    /**
     * One principal part: a neutral [key] and the form with its own spelling.
     *
     * [key] is a token the pack chose (`ger`, `part`, `pl`, `fem`) and the app localizes. An
     * unknown key is shown without a label rather than dropped: a form the reader can see is
     * worth more than a label the app happens to know.
     */
    data class InflectedForm(val key: String, val form: String)

    /**
     * Splits a translation item into `(term, the sense it points at)`.
     *
     * ⚠️ **The three parts of a `(pack, word, sense)` reference live in different places, and that
     * split is the design**: the **pack is not named** --the target is declared by LANGUAGE in
     * `meta.translations_to`, so any installed pack of that language resolves it and the link does
     * not die because the user has the core instead of the full one (D-180)--, the **word** is the
     * term already on screen, and the **sense** is this optional suffix, because it only exists
     * when the source knew it.
     *
     * From that comes the property that matters: **a translation with no sense is already a link
     * to the word, and it costs not one extra byte**.
     */
    fun splitRef(value: String): Pair<String, String?> {
        val cut = value.indexOf(REF_SEPARATOR)
        return if (cut < 0) value to null
        else value.substring(0, cut) to value.substring(cut + 1).ifEmpty { null }
    }

    /** The same joiner `stable_uid()` uses. The builder strips it out of any source data. */
    private const val REF_SEPARATOR = '\u001f'

    /**
     * Hash of the preloaded dictionary, to be compared against `meta.payload_dict_sha256` ONCE on
     * opening the pack (not per entry).
     *
     * It is needed because deflate does NOT detect a wrong dictionary: given enough length it
     * decompresses without throwing anything and returns corrupt text. It is verified that
     * "moverse rapidamente" comes out as " nadrse rapidamente" with no exception at all. Without
     * this check, a pack with the wrong dictionary would fill the screen with garbage without a
     * single clue why, and the user would read it as "the app is broken".
     */
    fun dictionaryDigest(dictionary: ByteArray): String = sha256Hex(dictionary)

    /**
     * Folds a gloss to decide whether two sources wrote **the same** sense.
     *
     * ⚠️ **A MIRROR of `payload.fold_gloss`.**
     *
     * The case folding **follows the standard**: [CaseFolding.fold] implements `toCaseFold()`,
     * rule R4 of section 3.13 of the Unicode Standard, which is the operation UAX #31 defines for
     * *caseless matching*. `lowercase()` is the wrong one -- the standard separates the two: case
     * mapping to DISPLAY, case folding to COMPARE.
     *
     * ⚠️ **What IS a rule of OURS, and versioned, is stripping trailing punctuation**: no standard
     * does it, it is a content decision, and changing it invalidates every link already written.
     *
     * Light on purpose and it does **not** strip accents: `publico` and `público` are different
     * words.
     *
     * ⚠️ **Whitespace is enumerated by hand and `\s` is NOT used**: in Python `\s` over `str` is
     * Unicode and in Java it is ASCII, so a hard space (U+00A0) would collapse on one side and not
     * the other, and the two codes for the same sense would come out different **with no error and
     * no log**.
     */
    fun foldGloss(gloss: String): String =
        ESPACIO.replace(CaseFolding.fold(toNfc(gloss).trim()), " ").trim(*CIERRE)

    private val ESPACIO = Regex("[ \t\n\r\u000C\u000B]+")
    private val CIERRE = charArrayOf(' ', '.', ';', ':', ',')

    /** How many hex characters name a sense. Mirrors `SENSE_CODE_LENGTH`. */
    const val SENSE_CODE_LENGTH = 12

    /**
     * Names a sense **without naming a pack**: unique for `(language, word, sense)`.
     *
     * ⚠️ **THIS IS A MIRROR of `payload.sense_code`**, and if the two compute differently the
     * links between packs point at nothing **with no error and no log** -- this repo's central
     * failure mode. The same vector pins it on both sides: `sense_code(1, "casa")` is
     * `8ec316909e48`.
     *
     * The language and the word are already inside [Entry.uid] --`stable_uid(lang, headword, pos,
     * sense_key)`-- so combining that with the gloss is enough. From there come the three
     * properties that were asked for:
     *
     * 1. **It does not name a pack**, so any installed pack of that language can resolve it: the
     *    link does not die because the user has the core instead of the full one.
     * 2. **The core and the full one share it.** Verified over the real packs: the 21,534 codes of
     *    the Spanish core are identical in the full one, because `build_core.py` copies the uid
     *    instead of recomputing it (D-175).
     * 3. **It degrades to the word**: the code is a suffix on the term, it does not replace it, so
     *    if no pack has the sense but some pack has the word, the link still works.
     *
     * ⚠️ **Over the RAW gloss in NFC and NOT over `norm()`**, which is the precedent of D-055: if
     * it went through `norm()`, a `NORM_VERSION` bump --allowed by D-005 at any time-- would change
     * every code and break every link of every pack already built.
     */
    fun senseCode(uid: Long, gloss: String): String =
        sha256Hex("$uid\u001f${foldGloss(gloss)}".encodeToByteArray()).take(SENSE_CODE_LENGTH)

    /**
     * Decompresses and parses the payload.
     *
     * @param dictionary the pack's preloaded dictionary (`meta.payload_dict`). It has to be
     *   exactly the one the builder used: with another, deflate either fails or produces garbage.
     */
    fun decode(compressed: ByteArray, dictionary: ByteArray): Body =
        parse(inflateRaw(compressed, dictionary).decodeToString())

    /** Only the test side uses it: in production packs are built with Python. */
    fun encode(body: Body, dictionary: ByteArray): ByteArray =
        deflateRaw(render(body).encodeToByteArray(), dictionary)

    fun parse(text: String): Body {
        var partOfSpeech: String? = null
        val senses = mutableListOf<MutableSense>()
        val wordTranslations = mutableListOf<String>()
        val forms = mutableListOf<InflectedForm>()

        // The sense whose LAST example can still receive a citation, or null. An `E` sets it and
        // any other line clears it: a `C` that does not come right after its `E` is discarded
        // rather than having an example picked for it. See [TAG_CITATION].
        var citable: MutableSense? = null

        for (line in text.split('\n')) {
            if (line.isEmpty()) continue
            val separator = line.indexOf('\t')
            // A line with no tab is either corrupt or from a future format: it is ignored rather
            // than throwing the whole entry away.
            if (separator != 1) continue
            val value = line.substring(separator + 1)
            if (value.isEmpty()) continue

            if (line[0] == TAG_CITATION) {
                citable?.let { it.examples[it.examples.lastIndex] = it.examples.last().copy(citation = value) }
                citable = null
                continue
            }
            citable = null

            when (line[0]) {
                TAG_PART_OF_SPEECH -> if (partOfSpeech == null) partOfSpeech = value
                TAG_SENSE -> senses.add(MutableSense(value))
                // An example or translation before the first sense has nothing to hang off.
                TAG_EXAMPLE -> senses.lastOrNull()?.let {
                    it.examples.add(Example(value))
                    citable = it
                }
                TAG_TRANSLATION -> senses.lastOrNull()?.translations?.add(value)
                TAG_SYNONYM -> senses.lastOrNull()?.synonyms?.add(value)
                TAG_ANTONYM -> senses.lastOrNull()?.antonyms?.add(value)
                TAG_RELATED -> senses.lastOrNull()?.related?.add(value)
                // No `senses` guard: it belongs to the ENTRY, so its position in the text decides
                // nothing. If it did, a misplaced `W` would become a sense translation -- the
                // invented attribution this channel exists to prevent.
                TAG_WORD_TRANSLATION -> wordTranslations.add(value)
                // No `senses` guard, for the same reason as `W`: it describes the ENTRY.
                TAG_FORM -> {
                    val cut = value.indexOf(FORM_SEPARATOR)
                    // A line with no separator is ignored: losing one form is cheap, and throwing
                    // here would lose the whole entry over a single malformed line.
                    if (cut > 0 && cut < value.length - 1) {
                        forms.add(
                            InflectedForm(value.substring(0, cut), value.substring(cut + 1)),
                        )
                    }
                }
                else -> Unit
            }
        }

        return Body(
            partOfSpeech = partOfSpeech,
            wordTranslations = wordTranslations.toList(),
            forms = forms.toList(),
            senses = senses.map {
                Sense(
                    it.gloss,
                    it.examples.toList(),
                    it.translations.toList(),
                    it.synonyms.toList(),
                    it.antonyms.toList(),
                    it.related.toList(),
                )
            },
        )
    }

    /**
     * Turns a [Body] back into text, for the tests' round trip.
     *
     * ⚠️ **It does NOT deduplicate the lists, the Python side DOES, and the asymmetry is
     * deliberate.** `payload.render` is the step every pack goes through **while being built**, so
     * there deduplication decides what gets stored. Here a pack that already exists is being READ:
     * silently altering what the file carries would hide that a foreign pack ships the same word
     * twice, instead of leaving it in plain sight. Showing what the pack says is the right answer
     * for a reader.
     */
    fun render(body: Body): String {
        val out = StringBuilder()
        body.partOfSpeech?.let { out.append(TAG_PART_OF_SPEECH).append('\t').append(it).append('\n') }
        for (sense in body.senses) {
            out.append(TAG_SENSE).append('\t').append(sense.gloss).append('\n')
            for (example in sense.examples) {
                out.append(TAG_EXAMPLE).append('\t').append(example.text).append('\n')
                // Right after its example, which is what [parse] requires to accept it.
                example.citation?.let {
                    out.append(TAG_CITATION).append('\t').append(it).append('\n')
                }
            }
            for (translation in sense.translations) {
                out.append(TAG_TRANSLATION).append('\t').append(translation).append('\n')
            }
            for (synonym in sense.synonyms) {
                out.append(TAG_SYNONYM).append('\t').append(synonym).append('\n')
            }
            for (antonym in sense.antonyms) {
                out.append(TAG_ANTONYM).append('\t').append(antonym).append('\n')
            }
            for (related in sense.related) {
                out.append(TAG_RELATED).append('\t').append(related).append('\n')
            }
        }
        return out.toString()
    }

    private class MutableSense(val gloss: String) {
        val examples = mutableListOf<Example>()
        val translations = mutableListOf<String>()
        val synonyms = mutableListOf<String>()
        val antonyms = mutableListOf<String>()
        val related = mutableListOf<String>()
    }

}
