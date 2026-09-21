import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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

            // Solo las dos ABIs que existen en un reloj. Medido sobre el APK de release: las
            // cuatro que trae SQLite nativo suman 4,9 MB y **x86 y x86_64 son solo de
            // emulador**, asi que son 2,3 MB que ningun dispositivo real usa.
            //
            // ⚠️ **El costo es que el APK de RELEASE ya no se instala en un emulador x86.** Es
            // deliberado y hay que saberlo antes de perder una tarde: el de debug sigue
            // trayendo las cuatro, que es donde se verifica a diario.
            //noinspection ChromeOsAbiSupport -- ChromeOS no es un destino de una app de Wear OS
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }

            // R8 ENCENDIDO. Medido antes de encenderlo: el APK pasa de 33,0 a 5,5 MB y el
            // dex de 29,5 a 2,7 -- un 91 % menos -- y compila sin una sola regla de keep.
            //
            // Importa para bateria y no solo para tamano: menos dex es menos carga de clases,
            // menos memoria y menos JIT en CADA arranque del proceso, y en esta app cada
            // arranque es alguien mirando la pantalla (docs/bateria.md).
            //
            // ⚠️ **Lo que R8 rompe, lo rompe SOLO en release y sin error de compilacion.** Los
            // dos TileService son el borde filoso: `app/CLAUDE.md` ya tiene escrito que romperlos
            // no da error ni test. Tienen regla propia en proguard-rules.pro.
            optimization {
                enable = true
                keepRules {
                    files.add(file("proguard-rules.pro"))
                }
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    /**
     * Lint entra al gate DE VERDAD: una advertencia rompe el build.
     *
     * Sin esto las advertencias se acumulan sin que nadie las vea, que es exactamente lo que
     * habia pasado del lado de Python: el linter existia, nadie lo corria, y tenia 26
     * violaciones -- entre ellas variables sin usar. Aca habia cinco reales, incluidas dos
     * cadenas muertas y un contador sin plural que en español decia "1 entradas".
     *
     * `checkDependencies = false` a proposito: lo que se vigila es el codigo de este repo.
     */
    lint {
        warningsAsErrors = true
        // Las dos unicas que se apagan, y por el mismo motivo: **avisan de que hay una version
        // mas nueva de una dependencia**. Eso es cierto casi siempre y no dice nada sobre el
        // codigo, asi que con `warningsAsErrors` el build se rompe solo con el paso del tiempo
        // -- y un gate que se rompe sin que nadie toque nada se termina apagando entero.
        // Actualizar dependencias es una tarea deliberada, no un error de compilacion.
        disable += setOf("NewerVersionAvailable", "AndroidGradlePluginVersion", "GradleDependency")
    }
    useLibrary("wear-sdk")
    buildFeatures {
        compose = true
        // Solo por VERSION_NAME, que la pantalla de ajustes muestra al fondo: el numero que se
        // le pide a alguien cuando reporta algo tiene que salir del APK, no de un literal que
        // se queda atras del que subio la property.
        buildConfig = true
    }
    testOptions {
        unitTests {
            // Robolectric necesita los recursos reales --strings, temas, densidades-- para poder
            // medir un Composable. Sin esto los tests compilan y fallan al inflar.
            isIncludeAndroidResources = true
        }
    }
}

/**
 * Los reportes del compilador de Compose, **bajo demanda**.
 *
 * `./gradlew :app:assembleDebug -Pdictionary.composeReports` deja en `build/compose_compiler/`
 * que Composable es skippable y cual no, y por que -- que parametro es inestable, cual fuerza
 * recomposicion. Es la unica forma de MEDIR el trabajo de UI, que en un reloj es CPU de verdad:
 * `docs/bateria.md` cotizo el SQL en ~1 ms por busqueda y no cotizo esto.
 *
 * Detras de una property y no siempre encendido porque los reportes cuestan tiempo de build y
 * nadie los mira a diario. El gate no los pide.
 */
composeCompiler {
    if (providers.gradleProperty("dictionary.composeReports").isPresent) {
        val destino = layout.buildDirectory.dir("compose_compiler")
        reportsDestination = destino
        metricsDestination = destino
    }
}

// Mismo rigor que :dict-core y :dict-data. `:app` era el unico modulo donde una advertencia del
// compilador --una API deprecada, un cast redundante-- pasaba el gate sin que nadie la viera.
kotlin {
    compilerOptions {
        allWarningsAsErrors = true
        // Explicito, como en :dict-core y :dict-data. Era el unico modulo que lo dejaba al
        // default de AGP: hoy AGP lo alinea con `compileOptions` y no se nota, pero dos
        // modulos declarandolo y uno no es una diferencia que nadie decidio.
        jvmTarget = JvmTarget.JVM_11
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
    implementation(libs.core.ktx)
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