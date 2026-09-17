import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
}

// Capa que abre los packs y ejecuta las consultas. Aisla todo el SQL: :app no ve una query.
//
// Los tests de este modulo son INSTRUMENTADOS a proposito. Son los unicos que pueden cerrar las
// asunciones sobre Android que :dict-core no alcanza -- que FTS5 exista, que el plan de consulta
// use el covering index, y sobre todo que norm() de lo mismo en el reloj que en el builder.
// Ver docs/roadmap.md, "Comprobacion que falta y bloquea el ship".
android {
    namespace = "cl.fadiaz.dictionary.data"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 33
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
        allWarningsAsErrors = true
    }
}

dependencies {
    api(project(":dict-core"))
    implementation(libs.sqlite.bundled)
    implementation(libs.coroutines.android)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.coroutines.test)
}

/**
 * Assets de los tests instrumentados.
 *
 * El pack de juguete no esta en el repo (D-020): es determinista y commitearlo meteria un
 * binario que cambia en cada build por meta.built_at. Estas tareas lo producen, para que correr
 * los tests instrumentados no tenga un paso manual previo que alguien va a olvidar.
 *
 * Se escriben en el directorio estatico de assets y no en build/generated/ porque AGP 9 prohibe
 * pasarle un Provider a la SourceSet API. El directorio entero esta gitignorado.
 */
private val androidTestAssets = layout.projectDirectory.dir("src/androidTest/assets")

val buildToyPack by tasks.registering(Exec::class) {
    group = "build"
    description = "Construye el pack de juguete para los tests instrumentados."

    inputs.dir(rootProject.layout.projectDirectory.dir("tools/packbuilder"))
    outputs.file(androidTestAssets.file("toy-es-en.db"))

    workingDir = rootProject.layout.projectDirectory.asFile
    commandLine(
        "python3",
        "tools/packbuilder/build_toy.py",
        androidTestAssets.file("toy-es-en.db").asFile.absolutePath,
    )
}

/**
 * Los vectores compartidos viajan al dispositivo, pero la fuente sigue siendo una sola: este es
 * el MISMO archivo que corren los tests de :dict-core y del builder. Si alguien editara la copia
 * de assets, el contrato mas importante del repo tendria dos versiones.
 */
val copySharedVectors by tasks.registering(Copy::class) {
    group = "build"
    description = "Copia los vectores de normalizacion a los assets de androidTest."

    from(rootProject.layout.projectDirectory.dir("tools/packbuilder/vectors")) {
        include("normalization-vectors.tsv")
    }
    into(androidTestAssets)
}

val prepareAndroidTestAssets by tasks.registering {
    group = "build"
    dependsOn(buildToyPack, copySharedVectors)
}

tasks.named("preBuild") { dependsOn(prepareAndroidTestAssets) }
