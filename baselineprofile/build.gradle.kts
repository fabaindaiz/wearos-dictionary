import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

// The startup profile, generated and measured on the emulator only (d-a2f271-2ccd54). The watch
// never runs this module: what it measures is ART's compilation, and a profile pays only on a build
// ART compiles -- `benchmark`, never `debug`.
android {
    namespace = "cl.fadiaz.dictionary.baselineprofile"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        minSdk = 33
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Macrobenchmark refuses an emulator; the owner chose the emulator only, so its numbers
        // are relative -- with against without the profile -- and never a claim about the watch.
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }
    targetProjectPath = ":app"
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

baselineProfile {
    // The emulator that stands in for the watch (`wear_sm_l715f`), started by hand: a managed
    // device would be another screen and another memory budget.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.benchmark.macro.junit4)
    implementation(libs.uiautomator)
}
