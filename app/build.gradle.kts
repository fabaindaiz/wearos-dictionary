plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "cl.fadiaz.dictionary"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "cl.fadiaz.dictionary"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    useLibrary("wear-sdk")
    buildFeatures {
        compose = true
    }
}

// Mismo rigor que :dict-core y :dict-data. `:app` era el unico modulo donde una advertencia del
// compilador --una API deprecada, un cast redundante-- pasaba el gate sin que nadie la viera.
kotlin {
    compilerOptions {
        allWarningsAsErrors = true
    }
}

/**
 * El pack de demostracion que viaja dentro del APK.
 *
 * Existe para que la app recien instalada tenga algo que mostrar: sin ningun pack arranca y dice
 * "No hay ningun diccionario instalado.", que es honesto pero no se puede ensenar.
 *
 * **Es un placeholder y se nota a proposito**: hoy lo genera `build_toy.py`, el mismo generador
 * del fixture de los tests instrumentados, y su propia atribucion dice que no es un diccionario
 * real. Que contenido deberia tener esta sin decidir (ver docs/roadmap.md); cambiarlo es apuntar
 * esta tarea a otro `.db`.
 *
 * Se genera en el build y **no se commitea**: un binario que cambia en cada build ensuciaria el
 * diff, y el repo ya decidio eso una vez para el toy pack (D-020).
 */
val buildDemoPack = tasks.register<Exec>("buildDemoPack") {
    group = "build"
    description = "Genera el pack de demostracion que se empaqueta en el APK."

    inputs.dir(rootProject.layout.projectDirectory.dir("tools/packbuilder"))
    outputs.file(layout.projectDirectory.file("src/main/assets/demo-es-en.db"))

    workingDir = rootProject.layout.projectDirectory.asFile
    commandLine(
        "python3",
        "tools/packbuilder/build_toy.py",
        layout.projectDirectory.file("src/main/assets/demo-es-en.db").asFile.absolutePath,
    )
}

tasks.named("preBuild") { dependsOn(buildDemoPack) }

dependencies {
    implementation(project(":dict-data"))
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.coroutines.android)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.wear.compose.navigation)
    implementation(libs.wear.input)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling)
    implementation(libs.core.splashscreen)
    implementation(libs.guava)
    implementation(libs.protolayout)
    implementation(libs.protolayout.material3)
    implementation(libs.tiles)
    implementation(libs.tiles.tooling.preview)
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.watchface.complications.data.source.ktx)
    implementation(libs.wear.tooling.preview)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.coroutines.test)
    debugImplementation(libs.tiles.renderer)
    debugImplementation(libs.tiles.tooling)
    debugImplementation(libs.ui.test.manifest)
    debugImplementation(libs.ui.tooling)
}