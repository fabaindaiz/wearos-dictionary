package cl.fadiaz.dictionary.data

/**
 * The app's internal knobs: what can be changed from `adb` without rebuilding, and what that costs.
 *
 * ## Why a registry and not a field per knob
 *
 * The first one of these --the catalogue url-- was a `BuildConfig` constant, so pointing the app
 * at a development server meant a rebuild and a 112 MB reinstall. On a watch that is minutes per
 * attempt. The obvious fix is an override read at runtime; the trap is that the *second* knob
 * copies the first, the third copies the second, and each one repeats the same four decisions
 * --where it is stored, how it is parsed, what an invalid value does, when it expires-- slightly
 * differently. A registry makes those four decisions once.
 *
 * ⚠️ **Every knob declares whether it belongs in the user-facing Settings screen**, and that is
 * the field worth arguing about rather than the value. `sensitive` does **not** mean "hard to
 * change from `adb`": `adb` only reaches a build that ships [DebugIntents] at all, which release
 * does not. It means *"showing this to whoever is wearing the watch would be wrong"*, and the
 * catalogue url is the case that defines it: a hostile server serves the packs, so it is a
 * security control wearing the costume of a preference.
 *
 * ## The other half: they do not survive an update
 *
 * ⚠️ **An override is scaffolding tied to ONE build, and it expires with it.** A catalogue url
 * left pointing at a laptop that is no longer serving turns the next session's *"downloads are
 * broken"* into an hour of looking at the wrong thing. So the store carries the `versionCode`
 * that wrote it and [survives] says whether it still applies.
 *
 * ⚠️ **The USER's settings do NOT work this way and must not.** `Settings.textScale` is a choice
 * somebody made about their own watch; wiping it on update would be a bug, not hygiene. The rule
 * is about *overrides*, which exist to make one build behave unlike itself.
 *
 * It is the same rule the pack memo already follows: a new `versionCode` expires the verification
 * fingerprints (D-225), and a new `versionCode` re-extracts the APK's packs (D-176). Three places
 * now agree that *the app going up a version invalidates what was true of the last one*.
 */
object DebugKnobs {

    /** What a knob is: a name, whether the UI may show it, and how a written value is read. */
    data class Knob(
        val key: String,
        /** One line, printed by the dump and by `--help`-style output. */
        val what: String,
        /**
         * Whether showing this on the Settings screen would be wrong.
         *
         * ⚠️ It is about the **user-facing screen**, not about `adb`. See the class header.
         */
        val sensitive: Boolean,
        /** Returns null when the written value is not one this knob accepts. */
        val accepts: (String) -> Boolean,
        /** What the user should have typed, printed when [accepts] says no. */
        val expected: String,
    )

    /** The catalogue's base url. */
    const val CATALOG = "catalog"

    /** The reading size, which is a real user setting and is mirrored here for probes. */
    const val SCALE = "scale"

    val KNOBS: List<Knob> = listOf(
        Knob(
            key = CATALOG,
            what = "base url of the pack catalogue",
            // ⚠️ **The one knob that is a security control.** The index comes from this server and
            // every pack's sha256 comes from that index, so whoever answers this url decides what
            // the app installs. Verifying the hash proves the download matches what the server
            // said, never that the server is the right one. It stays out of the Settings screen
            // for that reason and not because a url is awkward to type on 234 dp -- though it is.
            sensitive = true,
            accepts = { it.startsWith("http://") || it.startsWith("https://") },
            expected = "an http:// or https:// url",
        ),
        Knob(
            key = SCALE,
            what = "reading size",
            // Already on the Settings screen, and it is here so a probe can set it without
            // three taps. A knob being settable from `adb` and visible in the UI is the normal
            // case; `sensitive` is the exception.
            sensitive = false,
            accepts = { name -> TextScale.entries.any { it.name == name.uppercase() } },
            expected = TextScale.entries.joinToString("|") { it.name },
        ),
    )

    fun knob(key: String): Knob? = KNOBS.firstOrNull { it.key == key }

    /** What the dump prints when asked what can be set. */
    fun help(): List<String> = KNOBS.map { k ->
        "  %-8s %s%s".format(k.key, k.what, if (k.sensitive) "  [not in Settings]" else "")
    }

    /**
     * The outcome of writing one knob, as something that can be logged **and** tested.
     *
     * ⚠️ **An unknown key is a failure and not a silent no-op.** Every one of these arrives from
     * somebody typing it into a shell at 1 a.m.; a typo that changes nothing and says nothing is
     * indistinguishable from a knob that does not work, and the second is what gets reported.
     */
    sealed interface Written {
        data class Ok(val key: String, val value: String) : Written
        data class Cleared(val key: String) : Written
        data class UnknownKey(val key: String) : Written
        data class BadValue(val key: String, val value: String, val expected: String) : Written
    }

    /**
     * Validates a write. **[Knob.sensitive] is deliberately NOT consulted here.**
     *
     * ⚠️ **Sensitive means "the Settings screen must not offer this", never "`adb` must not
     * set it".** Blocking it here would remove the only reason the flag's subject exists: aiming
     * a build at a development catalogue without rebuilding it. And it would buy nothing --
     * `adb` reaches only a build that ships the receiver at all, which release does not.
     *
     * Written out because it is the obvious-looking hardening for somebody skimming, and a test
     * pins it.
     */
    fun write(key: String, value: String): Written {
        val knob = knob(key) ?: return Written.UnknownKey(key)
        if (value.isBlank()) return Written.Cleared(key)
        if (!knob.accepts(value)) return Written.BadValue(key, value, knob.expected)
        return Written.Ok(key, value)
    }

    fun describe(w: Written): String = when (w) {
        is Written.Ok -> "debug: ${w.key} -> ${w.value}"
        is Written.Cleared -> "debug: ${w.key} -> back to this build's default"
        is Written.UnknownKey ->
            "debug: NO existe el ajuste '${w.key}'. Hay: " + KNOBS.joinToString(", ") { it.key }
        is Written.BadValue ->
            "debug: '${w.value}' no sirve para ${w.key}; se esperaba ${w.expected}"
    }

    /**
     * Whether overrides written by `writtenBy` still apply to the build now running.
     *
     * ⚠️ **Equality and not `>=`.** Going *down* a version --which sideloading does constantly--
     * has to expire them just as much: the override was written against a build that is no longer
     * the one running, and which direction it moved says nothing about whether it still makes
     * sense. The pack memo's fingerprint makes the same choice.
     */
    fun survives(writtenBy: Int, current: Int): Boolean = writtenBy == current
}
