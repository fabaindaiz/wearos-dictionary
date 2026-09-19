plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Los datos de firma del release, o null si no hay ninguno configurado.
 *
 * Cascada variable de entorno -> local.properties, la misma que usan `:dict-data:devicePrecheck`
 * y `tools/devpack.py` para encontrar el SDK. La variable primero para que una maquina de CI (o
 * un agente) pueda firmar sin tocar `local.properties`, que esta gitignoreado Y en la lista de
 * archivos que no se editan a mano.
 *
 * **La keystore nunca va al repo**, ni siquiera gitignoreada: vive fuera del proyecto y
 * `local.properties` solo guarda su ruta. Lo enforcea `audit_dictionary.py`.
 */
val firmaDelRelease: Map<String, String>? = run {
    val locales = rootProject.file("local.properties").takeIf { it.isFile }?.let { archivo ->
        archivo.readLines()
            .filterNot { it.startsWith("#") || "=" !in it }
            .associate { it.substringBefore("=").trim() to it.substringAfter("=").trim() }
    }.orEmpty()

    fun valor(clave: String): String? =
        System.getenv("DICT_" + clave.uppercase().replace(".", "_")) ?: locales[clave]

    val store = valor("release.keystore") ?: return@run null
    val datos = mapOf(
        "keystore" to store,
        "storePassword" to (valor("release.keystore.password") ?: return@run null),
        "keyAlias" to (valor("release.key.alias") ?: return@run null),
        "keyPassword" to (valor("release.key.password") ?: return@run null),
    )
    if (file(store).isFile) datos else null
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
        // De gradle.properties, con default para que un clone sin la property compile igual.
        // El audit (check_app_version) comprueba que la property exista y que nadie la pise
        // con un literal aca, que es como volveriamos al numero que no sube nadie.
        versionCode = providers.gradleProperty("dictionary.versionCode").getOrElse("2").toInt()
        versionName = providers.gradleProperty("dictionary.versionName").getOrElse("0.2.0")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    }

    signingConfigs {
        // Solo existe si hay keystore: sin ella el bloque no se crea y `assembleRelease` produce
        // un APK SIN FIRMAR en vez de romper el build. Un clone limpio tiene que seguir andando.
        firmaDelRelease?.let { datos ->
            create("release") {
                storeFile = file(datos.getValue("keystore"))
                storePassword = datos.getValue("storePassword")
                keyAlias = datos.getValue("keyAlias")
                keyPassword = datos.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // `findByName` y no `getByName`: null cuando no hay keystore configurada.
            //
            // Lo que NUNCA hay que hacer aca es caer a `signingConfigs.getByName("debug")`. Eso
            // instala y corre, asi que parece funcionar -- y deja la app firmada con una clave
            // que no es tuya y que no podes reemplazar despues sin desinstalar. Es la variante
            // silenciosa de este error, y el audit la busca.
            signingConfig = signingConfigs.findByName("release")

            // R8 sigue APAGADO, y eso ya no es herencia del template: es una decision con fecha.
            // La guia oficial de Wear OS lo nombra como una de las dos palancas mas efectivas,
            // pero activarlo reintroduce la clase de bug que solo aparece en release --codigo o
            // recursos que R8 quita y que en debug estaban-- y va atado a una comprobacion en
            // dispositivo que todavia no se hizo. Roadmap O-2.
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
    testOptions {
        unitTests {
            // Robolectric necesita los recursos reales --strings, temas, densidades-- para poder
            // medir un Composable. Sin esto los tests compilan y fallan al inflar.
            isIncludeAndroidResources = true
        }
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

/**
 * Diagnostica si se puede firmar el release, ANTES de armarlo.
 *
 * Sin esto, el sintoma de no tener keystore aparece recien al instalar, como
 * `INSTALL_PARSE_FAILED_NO_CERTIFICATES`, que no dice que hacer. Es la misma idea que
 * `:dict-data:devicePrecheck`: el paso que convierte el MVP en algo instalable no deberia
 * trabarse en un mensaje malo.
 */
tasks.register("releasePrecheck") {
    group = "verification"
    description = "Comprueba que haya keystore para firmar el release."

    val configurada = firmaDelRelease != null
    doLast {
        if (configurada) {
            logger.lifecycle("Firma configurada. Arma el release con:")
            logger.lifecycle("  ./gradlew :app:assembleRelease")
            return@doLast
        }
        error(
            buildString {
                appendLine("No hay keystore configurada: el release saldria SIN FIRMAR y no se")
                appendLine("puede instalar en un reloj.")
                appendLine()
                appendLine("1. Genera una, FUERA del repositorio:")
                appendLine("     keytool -genkeypair -v -keystore ~/.keystores/dictionary.jks \\")
                appendLine("       -alias dictionary -keyalg RSA -keysize 4096 -validity 10000 \\")
                appendLine("       -storetype PKCS12")
                appendLine()
                appendLine("2. Agrega a local.properties (gitignoreado):")
                appendLine("     release.keystore=/Users/<vos>/.keystores/dictionary.jks")
                appendLine("     release.keystore.password=...")
                appendLine("     release.key.alias=dictionary")
                appendLine("     release.key.password=...")
                appendLine()
                appendLine("   O por entorno: DICT_RELEASE_KEYSTORE, DICT_RELEASE_KEYSTORE_PASSWORD,")
                appendLine("   DICT_RELEASE_KEY_ALIAS, DICT_RELEASE_KEY_PASSWORD.")
                appendLine()
                appendLine("La keystore NO va al repositorio. Si la perdes no podes volver a")
                appendLine("actualizar una app ya instalada con ella: guardala aparte.")
            },
        )
    }
}

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
    // Las pantallas, en la JVM. Necesitan el mismo par que en dispositivo mas el runtime simulado.
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.ui.test.junit4)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.robolectric)
    implementation(libs.material.icons.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.coroutines.test)
    debugImplementation(libs.tiles.renderer)
    debugImplementation(libs.tiles.tooling)
    debugImplementation(libs.ui.test.manifest)
    debugImplementation(libs.ui.tooling)
}