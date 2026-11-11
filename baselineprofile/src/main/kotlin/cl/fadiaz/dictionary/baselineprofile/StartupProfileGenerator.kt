package cl.fadiaz.dictionary.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates the startup profile: the classes and methods a cold start runs, compiled ahead of
 * time by ART on install instead of interpreted on the first launch.
 *
 * Run on the emulator, with the packs already installed: a launch with no packs takes the empty
 * path and records a profile of a start nobody has.
 *
 * ```sh
 * ./gradlew :app:generateBaselineProfile
 * ```
 */
@RunWith(AndroidJUnit4::class)
class StartupProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startup() = rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
    }
}

internal const val PACKAGE = "cl.fadiaz.dictionary"
