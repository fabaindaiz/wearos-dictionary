package cl.fadiaz.dictionary.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * A cold start with no ahead-of-time compilation against one with the startup profile: what the
 * profile buys, on the emulator (d-a2f271-2ccd54). `timeToFullDisplayMs` ends when a search can
 * run (`ReportDrawnWhen` in `MainActivity`), which is the number that matters here.
 *
 * ```sh
 * ./gradlew :baselineprofile:connectedBenchmarkAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=cl.fadiaz.dictionary.baselineprofile.StartupBenchmark
 * ```
 */
@RunWith(Parameterized::class)
class StartupBenchmark(private val mode: CompilationMode) {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun coldStart() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = 10,
    ) {
        pressHome()
        startActivityAndWait()
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun modes(): List<CompilationMode> =
            listOf(CompilationMode.None(), CompilationMode.Partial(BaselineProfileMode.Require))
    }
}
