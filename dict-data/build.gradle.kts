import java.io.File
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
    androidTestImplementation(libs.espresso.core)
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

val buildToyPack = tasks.register<Exec>("buildToyPack") {
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
val copySharedVectors = tasks.register<Copy>("copySharedVectors") {
    group = "build"
    description = "Copia los vectores de normalizacion a los assets de androidTest."

    from(rootProject.layout.projectDirectory.dir("tools/packbuilder/vectors")) {
        include("normalization-vectors.tsv")
    }
    into(androidTestAssets)
}

val prepareAndroidTestAssets = tasks.register("prepareAndroidTestAssets") {
    group = "build"
    dependsOn(buildToyPack, copySharedVectors)
}

tasks.named("preBuild") { dependsOn(prepareAndroidTestAssets) }

/**
 * Diagnostica si se pueden correr los tests instrumentados, ANTES de correrlos.
 *
 * `connectedDebugAndroidTest` sin dispositivo falla con un error de Gradle que no dice que
 * hacer. Esta tarea dice exactamente que falta, porque el paso que convierte el comportamiento
 * en Android de ASSUMPTION a verificado no deberia trabarse en un mensaje malo.
 */
tasks.register("devicePrecheck") {
    group = "verification"
    description = "Comprueba que haya un dispositivo o emulador listo para los tests instrumentados."

    val localProperties = rootProject.layout.projectDirectory.file("local.properties").asFile
    val sdkFromEnv = providers.environmentVariable("ANDROID_HOME")
        .orElse(providers.environmentVariable("ANDROID_SDK_ROOT"))
        .orNull
    val sdkFromFile = if (localProperties.isFile) {
        localProperties.readLines()
            .firstOrNull { it.startsWith("sdk.dir=") }
            ?.substringAfter("=")
    } else {
        null
    }
    val sdk = sdkFromEnv ?: sdkFromFile

    doLast {
        // Inline y no una funcion del script: referenciar una funcion declarada a nivel de
        // build script desde doLast captura el objeto del script, y eso rompe el
        // configuration cache. Paso lo mismo con un copy {} mas arriba.
        val ejecutar: (List<String>) -> String = { command ->
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output
        }

        if (sdk == null) {
            error(
                "No se encontro el SDK de Android. Defini sdk.dir en local.properties:\n" +
                    "  echo \"sdk.dir=\$HOME/Library/Android/sdk\" > local.properties",
            )
        }
        val adb = File(sdk, "platform-tools/adb")
        if (!adb.canExecute()) {
            error("No se encontro adb en ${adb.absolutePath}")
        }

        val conectados = ejecutar(listOf(adb.absolutePath, "devices", "-l"))
            .lines()
            .drop(1)
            .filter { it.isNotBlank() && !it.startsWith("*") }

        if (conectados.isNotEmpty()) {
            logger.lifecycle("Dispositivos listos:")
            conectados.forEach { logger.lifecycle("  $it") }
            logger.lifecycle("")
            logger.lifecycle("Corre los tests con:")
            logger.lifecycle("  ./gradlew :dict-data:connectedDebugAndroidTest")
            return@doLast
        }

        val emulator = File(sdk, "emulator/emulator")
        val disponibles = if (emulator.canExecute()) {
            ejecutar(listOf(emulator.absolutePath, "-list-avds")).lines().filter { it.isNotBlank() }
        } else {
            emptyList()
        }

        if (disponibles.isNotEmpty()) {
            error(
                buildString {
                    appendLine("No hay dispositivos conectados, pero hay AVDs creados:")
                    disponibles.forEach { appendLine("  $it") }
                    appendLine()
                    appendLine("Arranca uno y volve a intentar:")
                    appendLine("  ${emulator.absolutePath} -avd ${disponibles.first()} &")
                },
            )
        }

        error(
            buildString {
                appendLine("No hay ningun dispositivo conectado y no hay ningun AVD creado.")
                appendLine()
                appendLine("Los tests instrumentados de este modulo son la unica forma de")
                appendLine("verificar el comportamiento en Android. Sin dispositivo, todo lo que")
                appendLine("el repo afirma sobre Android es ASSUMPTION.")
                appendLine()
                appendLine("Crear un emulador de Wear OS desde Android Studio:")
                appendLine("  Tools > Device Manager > Add a new device > Wear OS")
                appendLine("  Imagen de API 33 o superior (el minSdk del proyecto es 33).")
                appendLine()
                appendLine("Conviene crear uno POR NIVEL DE API soportado: el punto de")
                appendLine("NormalizationOnDeviceTest es justamente que las versiones de ICU")
                appendLine("difieren entre versiones de Android.")
            },
        )
    }
}
