package cl.fadiaz.dictionary.data

import cl.fadiaz.dictionary.core.DictionarySource
import cl.fadiaz.dictionary.core.PackMetadata
import cl.fadiaz.dictionary.core.PackRejection

/**
 * A `.db` in `filesDir/packs`: either open and queryable, or rejected and named as such.
 *
 * ⚠️ **The second variant exists because a rejected pack has to be VISIBLE.** It used to be a
 * loose string in `PackSet.problems` that only reached the attribution screen --the worst possible
 * place: that is the credits screen, and a pack that does not load credits nothing--. Now it is
 * one more row on the dictionaries screen, with its reason on one line and its delete button,
 * which is the only action anybody can take about it.
 */
sealed interface PackHandle {
    val packId: String

    data class Open(
        val source: DictionarySource,
        /**
         * It came **inside the APK**; nobody installed it.
         *
         * ⚠️ **It used to be called `isBundled` and the name lied about what it decides.** What
         * every use asks is *"did the app bring this file or did the user put it here?"* --from
         * which follow both that it cannot be deleted (it would come back on restart) and that it
         * does not beat a real dictionary--. That today the bundled pack happens to be a toy one
         * is a property of **this** build, not of the rule: when the core takes that place, the
         * rules are exactly the same and the name would still be false.
         *
         * Without this flag alphabetical order decided, and "demo-" beats "es-": with both
         * installed, the app opened the 28 toy entries instead of the 146,194 real ones.
         */
        val isBundled: Boolean = false,
        /**
         * The file in `filesDir/packs`, so it can be deleted.
         *
         * It is needed separately from `packId` because **they are not the same thing**: the id
         * comes from the metadata inside the pack and the name is chosen by whoever installed
         * it. Today `devpack.py` makes them match, but deriving one from the other would be an
         * assumption that deletes the wrong file the day they stop matching.
         */
        val fileName: String = "",
        /** What it takes on disk. The only figure that matters to someone deciding to delete. */
        val bytes: Long = 0,
    ) : PackHandle {
        override val packId: String get() = source.metadata.packId
        val metadata: PackMetadata get() = source.metadata
    }

    /**
     * A `.db` that is on disk and **does not load**, with the reason as data.
     *
     * ⚠️ **It has no `metadata`, and that is the guarantee and not a shortcoming.** The request was
     * that an incompatible pack *"not be loaded in any way, for instance that it not show up in
     * the credits"*. The way to comply is not remembering to filter it on every screen --that gets
     * forgotten on the next one-- but that **there be nothing to show**: with no `PackMetadata`
     * there is no name, no licence, no sources and no language, so no screen can include it even
     * if it wanted to. The compiler enforces it, not discipline.
     *
     * [packId] is the **file name** for the same reason: the `pack_id` lives inside the pack and
     * reading it would already be loading it.
     */
    data class Incompatible(
        val fileName: String,
        val bytes: Long,
        val rejection: PackRejection,
    ) : PackHandle {
        override val packId: String get() = fileName
    }
}

/**
 * The result of looking at which dictionaries are there.
 *
 * The three cases are not over-defensive: each one looks different on screen and the user can do
 * something different about each one.
 *
 * It lives without a single reference to Android, same as [PackLoad]: it is the boundary that
 * lets `SearchViewModel` be tested on the JVM inside the gate (D-072).
 */
sealed interface PackSet {

    /**
     * There is at least one usable pack. [active] is the one being searched.
     *
     * ⚠️ **The rejected ones travel in [all] as [PackHandle.Incompatible]**, and no longer in a
     * separate list of strings. That way a pack that does not load is still visible --it does not
     * disappear in silence, which is what that list protected-- but it now appears **where
     * something can be done about it**: the dictionaries screen, with its reason and its delete
     * button.
     */
    data class Ready(
        val active: PackHandle.Open,
        val all: List<PackHandle>,
    ) : PackSet

    /** No `.db` in `filesDir/packs/`. The APK ships none: one has to be installed. */
    data object NoPack : PackSet

    /** There were files and **none** of them is usable. */
    data class Unusable(val reason: String) : PackSet
}
