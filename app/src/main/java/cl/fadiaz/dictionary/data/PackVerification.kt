package cl.fadiaz.dictionary.data

import cl.fadiaz.dictionary.core.PackRejection

/**
 * Whether a pack has already been tested, **with what result**, and whether that test still holds.
 *
 * ## The three moments, and why each uses a different method
 *
 * | Moment | What is asked | With what |
 * |---|---|---|
 * | **Installing or downloading** | Did the published bytes arrive? | **streaming sha256**, in `PackStore.installAtomically` |
 * | **Discovering a pack** the app did not install | Is it a valid pack and are its keys right? | the whole verification in `PackFile.open` |
 * | **Every launch** | Is it the same file I already tested, and what came out? | the fingerprint here |
 *
 * ⚠️ **A hash is NO use for the third, and that is where the nuance lies.** Checking it would
 * force re-reading the whole file --301 MB in the English pack-- which is far worse than the 64
 * rows it was meant to avoid. Whereas **it is exactly right for the first**, and there it comes
 * almost free: the bytes are already going past in order to be copied.
 *
 * ⚠️ **"Discovering" needs no mechanism**: a pack the app did not install --one put there by
 * `devpack.py`, or by the installer-- simply has no entry in the memo, so it is validated whole
 * the first time it is opened. The absence of an annotation *is* the discovery.
 *
 * ## The memo also stores the REJECTIONS, and that flips the sign of the risk
 *
 * Asked for: that a rejected pack not be scanned again and that the dictionaries screen be able to
 * say **why** without reopening it. That turns the memo into something more dangerous than it was:
 *
 * - One **yes** cached too many costs a bad pack being used -- and for that the file has to have
 *   changed without changing size or date.
 * - One **no** cached too many costs a **perfectly good pack disappearing forever**. The file does
 *   not change, so nothing ever looks at it again.
 *
 * The second is the failure mode this repo cannot observe, and the only mechanism that prevents it
 * is that **the whole rules go into the fingerprint**. If tomorrow the app understands
 * `schema_version` 5, every schema rejection has to expire on its own.
 *
 * ## What problem it solves, and what it does NOT stop doing
 *
 * Opening a pack recomputes `norm()` and `fuzzy()` over 64 spread-out entries (D-142) and checks
 * that its content is what `meta` promises. That turns `norm_version` --a number the pack assigns
 * itself-- into a proof, and covers the repo's central failure mode: a pack built under other
 * rules **returns fewer words, with no exception and no log**.
 *
 * Measured: it is **36 of the 42 ms** each process launch costs with the two real packs installed,
 * and the content checks added to it are **5.7 ms** more over the 306.8 MB English pack. And the
 * pack is **immutable** (D-001): testing the same file byte for byte again proves nothing that was
 * not already known.
 *
 * ## Why the fingerprint carries what it carries
 *
 * - `bytes` and `modifiedAt`: they identify the file. `devpack.py` and the installer write the
 *   `.db` **in place** (D-082), so the name is not enough to say it is the same one.
 * - [rules]: **everything that can change the verdict without the file changing.** See there.
 *
 * ## Why it does not carry a hash of the file
 *
 * Because it would cost more than it saves: hashing 301 MB on every launch is worse than re-reading
 * 64 rows. `(size, mtime)` does not distinguish two different files of the same size written in the
 * same millisecond, and that is acceptable **here**: it is not a defence against an attacker
 * --whoever writes in `filesDir` is already the app-- but against the user reinstalling a different
 * pack and nobody noticing.
 *
 * It is pure and free of Android on purpose: that way the gate covers it on the JVM and not on a
 * device (D-072). Who stores it is `PackStore`.
 */
internal object PackVerification {

    /**
     * Bumped when **what gets checked** changes, even if no format constant changes.
     *
     * ⚠️ **It is the path most easily forgotten.** `NORM_VERSION`, `schema_version` and the codec
     * change with ceremony and there are decisions that force them (D-005, D-006). Adding a new
     * invariant to `PackFile.open` touches none of the three -- and without bumping this, every
     * pack already annotated as verified would skip the new check forever, which is exactly the
     * hole the check came to plug.
     *
     * 1 → the original verification: schema, norm, codec, the dictionary's sha256, a 64 sample.
     * 2 → adds required meta keys, a declared licence, both indexes, staging, `entry_count`
     *     against the real rows, `fts_def` 1:1, empty `norm` and orphans.
     */
    const val CHECKS_VERSION: Int = 2

    /** What came out when this file was tested. `null` in [verdict] is *"never tested"*. */
    sealed interface Verdict {
        /** It passed everything. It can be opened skipping what was already checked. */
        data object Passed : Verdict

        /** It could not be used, and why. No need to reopen it to know that. */
        data class Rejected(val rejection: PackRejection) : Verdict
    }

    /**
     * Everything that can change the verdict **without the file changing**.
     *
     * The first three are the constants the pack is compared against; the fourth is which checks
     * get run. Any of them moving expires the whole memo, which is what stops a rejection
     * outliving the version of the app that would already know how to read that pack.
     *
     * ⚠️ **The fifth is the `versionCode`, and it exists because the other four depend on
     * somebody remembering.** `NORM_VERSION`, `schema_version` and the codec are forced upwards
     * by D-005 and D-006; nothing forces [CHECKS_VERSION] — adding an invariant to
     * `PackFile.open` touches none of the three, and forgetting leaves **every already-annotated
     * pack skipping the new check forever**, which is exactly the hole the check came to plug.
     *
     * The `versionCode` cannot be forgotten: the Android installer **rejects a downgrade**
     * (D-095), so an app reaching the watch always carries a higher number than the last. With it
     * in the fingerprint, installing a new version **expires the whole memo** and the first
     * launch re-tests every pack under today's rules.
     *
     * ⚠️ **And it expires the REJECTIONS, which is the half that matters most.** One cached yes
     * too many costs a bad pack being used; one cached **no** too many costs a perfectly good
     * pack disappearing with no log and nothing ever looking again. A new app is precisely the
     * moment a rejection may have stopped being true.
     *
     * **What it costs**: the first launch after each update pays D-142's 64-key sample over every
     * pack — measured in D-164, **41.33 ms against 6.30** with the two real packs. Once per app
     * update, not once per launch.
     */
    fun rules(
        normVersion: Int,
        schemaVersion: Int,
        codecId: String,
        appVersion: Int,
    ): String = "n$normVersion.s$schemaVersion.$codecId.c$CHECKS_VERSION.a$appVersion"

    /** What makes a tested file unique, under today's rules. */
    fun fingerprint(bytes: Long, modifiedAt: Long, rules: String): String =
        "$bytes:$modifiedAt:$rules"

    /** What came out last time with **this** file, or `null` if there is no record. */
    fun verdict(stored: String?, name: String, fingerprint: String): Verdict? {
        val anotado = entries(stored)[name] ?: return null
        if (anotado.fingerprint != fingerprint) return null
        return if (anotado.reason == PASSED) {
            Verdict.Passed
        } else {
            // `fromId` degrades to DAMAGED rather than throwing: a reason written by another
            // version of the app means "there was a reason and I do not know which", and the safe
            // outcome is that the pack gets looked at again, never that it stays hidden behind an
            // unreadable code.
            Verdict.Rejected(PackRejection.fromId(anotado.reason))
        }
    }

    /** The memo with this pack annotated. It replaces the previous entry of the same name. */
    fun remember(
        stored: String?,
        name: String,
        fingerprint: String,
        rejection: PackRejection?,
    ): String =
        serialize(entries(stored) + (name to Anotacion(fingerprint, rejection?.id ?: PASSED)))

    /** The memo without the packs no longer on disk, so it does not grow without a ceiling. */
    fun prune(stored: String?, present: Set<String>): String =
        serialize(entries(stored).filterKeys { it in present })

    /** `"ok"` is not a [PackRejection]: no reason id can collide with it. */
    private const val PASSED = "ok"

    private data class Anotacion(val fingerprint: String, val reason: String)

    private fun entries(stored: String?): Map<String, Anotacion> =
        stored.orEmpty().lineSequence()
            .mapNotNull { linea ->
                // A line without the three parts is garbage --another version of the app, a
                // half-finished write-- and is ignored rather than breaking. The cost of ignoring
                // it is testing the pack again, which is the previous behaviour.
                val campos = linea.split('\t')
                if (campos.size != 3 || campos.any { it.isEmpty() }) {
                    null
                } else {
                    campos[0] to Anotacion(campos[1], campos[2])
                }
            }
            .toMap()

    private fun serialize(entries: Map<String, Anotacion>): String =
        entries.entries
            // The name is chosen by whoever installs the pack: a tab inside it would split the
            // line somewhere else and pass off an unverified pack as verified.
            .filterNot { (name, _) -> '\t' in name || '\n' in name }
            .joinToString("\n") { (name, a) -> "$name\t${a.fingerprint}\t${a.reason}" }
}
