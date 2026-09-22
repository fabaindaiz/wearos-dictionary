import java.io.File
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Si este build de `debug` lleva R8. Apagado salvo que se pase `-PdebugR8`.
 *
 * Se lee aca, en el script, y no dentro del `buildType`: ahi el receptor es el BuildType y
 * `providers` resuelve por la cadena de receptores implicitos, que es justo el tipo de cosa que
 * se rompe en silencio al subir de AGP.
 */
val debugConR8: Boolean = providers.gradleProperty("debugR8").isPresent

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
        /**
         * **R8 en `debug`, bajo demanda y apagado por defecto.**
         *
         * ```sh
         * ./gradlew :app:assembleDebug            # como siempre, segundos
         * ./gradlew :app:assembleDebug -PdebugR8  # con R8, ~3 minutos
         * ```
         *
         * Apagado por defecto por lo que cuesta: medido incremental el 2026-09-21, **11,7 s sin
         * el flag contra 59 s con el**, y `debug` es la build que se compila muchas veces al dia.
         * (El bloque `benchmark` dice «~3 minutos» para R8; eso es una build limpia, no esta.) Encendido siempre, dejaria de ser la build barata **y** dejaria de
         * ser la build sin minificar donde se verifica a diario -- seria ya otra cosa.
         *
         * ⚠️ **Esto NO da el arranque del build real, y es exactamente por lo que existe
         * `benchmark`.** `debug` es `debuggable = true`, y **ART nunca compila AOT un paquete
         * debuggable** porque el depurador necesita codigo deoptimizable. Medido en el reloj el
         * 2026-09-21: `cmd package compile -m speed -f` contesta `Success` y el estado aterriza
         * en **`verify`**, no en `speed`.
         *
         * ⚠️ **Y R8 en un build debuggable ENCOGE pero no OPTIMIZA.** Medido sobre los tres APK
         * el 2026-09-21:
         *
         * | build | dex | info de depuracion |
         * |---|---|---|
         * | `debug` | 43,8 MB en 12 archivos | si |
         * | `debug -PdebugR8` | **10,1 MB en 1** | **si** -- conserva los nombres de fuente |
         * | `benchmark` | **2,7 MB en 1** | no -- cero nombres `.kt` en el dex |
         *
         * Los 7,4 MB de diferencia son informacion que el depurador necesita y las pasadas de
         * optimizacion que R8 se salta para no romperlo. Asi que **«R8 no rompio nada en debug»
         * es una garantia MAS DEBIL que la de `benchmark`**: justo las transformaciones que
         * rompen cosas por reflexion --renombrado a fondo, inlining, fusion de clases-- son las
         * que aqui se atenuan.
         *
         * **Para que sirve entonces**: probar el *shrinking* **con un depurador enganchado** y
         * poner un breakpoint donde se sospeche. Lo que R8 rompe, lo rompe sin error de
         * compilacion --los dos `TileService` son el borde filoso, y sobreviven con su nombre
         * original en los dos builds, verificado leyendo el dex--. La verificacion que cuenta
         * sigue siendo `benchmark`, y el arranque comparable con los 500 ms de O-1, tambien.
         */
        debug {
            optimization {
                enable = debugConR8
                keepRules {
                    files.add(file("proguard-rules.pro"))
                }
            }
        }
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
        /**
         * El release con R8, **instalable por adb hoy**.
         *
         * Existe por dos trabajos distintos que piden lo mismo:
         *
         * 1. **Probar R8 antes de que exista la keystore.** El release de verdad sale sin firmar
         *    hasta que haya una (D-086), y un APK sin firmar no se instala. Este se firma con la
         *    clave de **debug**, que es la salida correcta para un build que no sale a nadie.
         * 2. **Los baseline profiles.** Macrobenchmark exige un build type minificado y **no
         *    debuggable** para medir; el `debug` no sirve y el `release` no se puede instalar.
         *
         * ⚠️ **NO lleva `applicationIdSuffix`, y es deliberado.** Con sufijo se instalaria al
         * lado del debug, con otro `filesDir` -- y habria que volver a copiar **300 MB de packs**
         * para probar. Sin sufijo y con la misma clave de debug, reemplaza al debug en el lugar y
         * **los packs se quedan donde estan**.
         *
         * ⚠️ **`isDebuggable = false` no es cosmetico**: un build debuggable desactiva
         * optimizaciones del runtime, asi que medir arranque sobre el mediria otra cosa.
         *
         * Por que no encender R8 en `debug` a secas, que era la pregunta: porque R8 tarda ~3
         * minutos y `debug` es la build que se compila veinte veces por dia, y porque dejaria de
         * ser la build donde se verifica a diario -- seria ya otra cosa.
         */
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            matchingFallbacks += listOf("release")
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
    androidResources {
        /**
         * Que la app aparezca en la lista de idiomas **del sistema** (API 33+).
         *
         * ⚠️ **El selector de D-158 ya funcionaba sin esto**, y esa es la parte que confunde:
         * `LocaleManager.setApplicationLocales` anda igual. Lo que faltaba es que el usuario
         * pudiera llegar por Ajustes del reloj -> Idiomas, que es donde la gente lo busca
         * primero. Sin `android:localeConfig` la app **no figura ahi**.
         *
         * Generado y no escrito a mano a proposito: AGP lo arma desde las carpetas `values-*`
         * reales, asi que **no puede quedarse atras** de una traduccion nueva. Una lista a mano
         * seria una segunda fuente de verdad del mismo dato, que es justo lo que
         * `check_ui_language_picker` vigila del otro lado.
         */
        generateLocaleConfig = true
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
 * Los packs que viajan DENTRO del APK: los **nucleos reales** de espanol e ingles.
 *
 * ⚠️ **El pack de demostracion se elimino el 2026-09-21 y con el el respaldo.** Antes, si los
 * nucleos no estaban, esta tarea generaba un juguete de 28 entradas con `build_toy.py` para que
 * la app recien instalada tuviera algo que mostrar. Eso dejo de valer la pena: los nucleos son el
 * contenido de verdad, y un diccionario de 28 palabras al lado de uno real confunde mas de lo que
 * ensena. Ademas causaba un bug visto en el reloj --la app avisaba que `demo-es-en.db` no era
 * compatible-- que con esto desaparece de raiz.
 *
 * **Lo que cambia para un clone limpio**: los nucleos se derivan de los packs completos, que pesan
 * 372 MB y **viven fuera del repo**, asi que un clone sin ellos produce un APK **sin diccionario**.
 * La app degrada bien --arranca y dice "No hay ningun diccionario instalado."-- y el build **sigue
 * compilando**, que es lo que D-086 exige. Lo que ya no hace es auto-abastecerse.
 *
 * De paso, el build de Android **deja de depender de Python**: `build_toy.py` ya solo genera el
 * fixture de los tests instrumentados, desde `:dict-data`.
 *
 * El directorio se configura con `dictionary.packsDir`; por defecto es `../wearos-dictionary-data`,
 * que es donde ya estan.
 */
val packsDir = providers.gradleProperty("dictionary.packsDir")
    .getOrElse("../wearos-dictionary-data")
val nucleos = listOf("es-core.db", "en-core.db")
    .map { rootProject.layout.projectDirectory.file("$packsDir/$it").asFile }
    .filter { it.isFile }

val bundlePacks = tasks.register("bundlePacks") {
    group = "build"
    description = "Pone en assets/ los packs nucleo. Si no estan, el APK viaja sin diccionario."
    val destino = layout.projectDirectory.dir("src/main/assets").asFile
    inputs.files(nucleos)
    outputs.dir(destino)
    val aCopiar = nucleos
    doLast {
        destino.mkdirs()
        // Se limpia lo anterior: dejar un pack viejo al lado de uno nuevo significa que la app
        // abre los dos, y el viejo contesta con datos de otra construccion.
        destino.listFiles()?.filter { it.name.endsWith(".db") }?.forEach { it.delete() }
        aCopiar.forEach { it.copyTo(File(destino, it.name), overwrite = true) }
    }
}

tasks.named("preBuild") { dependsOn(bundlePacks) }

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

// ⚠️ **El gate COMPILA los tests instrumentados aunque no pueda correrlos.**
//
// Correrlos necesita un reloj y por eso no entran (`CLAUDE.md` §Verificación). Pero compilarlos
// no necesita nada, y sin esta línea el `androidTest/` queda **fuera de todo**: un cambio de
// firma en `:app/main` lo rompe y `./gradlew check` sigue verde. Pasó el 2026-09-21 — `WordLink`
// dejó `GlossLinksOnDeviceTest` sin compilar y sólo se supo al enchufar el reloj, horas después.
//
// Medido: **12 s en frío y 3 s en caliente** para los dos módulos, contra 36 s de gate.
tasks.named("check") { dependsOn("assembleDebugAndroidTest") }
