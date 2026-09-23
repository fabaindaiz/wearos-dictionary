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
 * De donde sale el catalogo de packs. **Es una url de DESARROLLO y se cambia sin tocar Kotlin.**
 *
 * El default es `localhost`, y funciona porque el dispositivo tunela ese puerto al de esta
 * maquina por adb:
 *
 *     adb reverse tcp:8765 tcp:8765
 *     python3 tools/packserver.py ../wearos-dictionary-data --port 8765
 *     ./gradlew :app:installDebug
 *
 * ⚠️ **El default era `10.0.2.2` --el alias del host para un emulador-- y FALLA, medido el
 * 2026-09-22 en el emulador del proyecto**: `SocketTimeoutException: failed to connect to
 * /10.0.2.2 (port 8765) from /10.0.2.15 after 8000ms`, con el servidor comprobadamente vivo y
 * respondiendo 200 al mismo curl desde el host, por loopback y por la IP de la LAN.
 *
 * ⚠️ **La causa no esta probada, y conviene que quede dicho asi**: el cortafuegos de macOS esta
 * encendido (`socketfilterfw --getglobalstate` = 1) y Python no esta en su lista de permitidos,
 * lo que es COHERENTE con el timeout, pero no se midio el bloqueo en si. Lo que si es un hecho es
 * que `10.0.2.2` no llego y `adb reverse` si.
 *
 * `adb reverse` evita el problema entero y tiene dos ventajas mas: **no hay que descubrir ninguna
 * IP** --que cambia de red en red-- y **vale igual en un reloj de verdad** por depuracion
 * inalambrica, donde `10.0.2.2` no significa nada. Para servir por la LAN de todos modos,
 * `packserver.py` imprime la IP al arrancar:
 *
 *     ./gradlew :app:installDebug -PcatalogUrl=http://192.168.1.42:8765
 *
 * ⚠️ Solo `debug` puede hablar por `http://` (ver `src/debug/AndroidManifest.xml`). El catalogo
 * de produccion sera HTTPS y entonces esto pasa a ser un default y no un apaño.
 */
val catalogUrl: String =
    providers.gradleProperty("catalogUrl").getOrElse("http://localhost:8765")

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
        // Ver [catalogUrl] arriba: se sobreescribe con -PcatalogUrl=...
        buildConfigField("String", "CATALOG_URL", "\"$catalogUrl\"")

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
            // ⚠️ **La puerta de los intents de depuracion, y su motivo de existir.** Sin ella no
            // hay forma de sembrar una consulta desde `adb`: el campo de busqueda **no toma foco
            // con un tap sintetico** --misma forma del problema que obligo a fijar espresso 3.7.0
            // (D-093)-- y eso dejo sin verificar D-168 y D-169 con el reloj en la mano, y volvio
            // a costar en el emulador. Ver `DebugIntents`.
            buildConfigField("boolean", "DEBUG_INTENTS", "true")
            optimization {
                enable = debugConR8
                keepRules {
                    files.add(file("proguard-rules.pro"))
                }
            }
        }
        release {
            // ⚠️ **Apagada, y es la mitad que importa.** Un receiver exportado en produccion es
            // superficie de ataque y bateria. Con la constante en `false` R8 pliega el `if` y se
            // lleva la clase entera, asi que en release el codigo **no existe**, no es que no se
            // use. Lo mismo que hace el source set `debug` con el HTTP en claro (D-213).
            buildConfigField("boolean", "DEBUG_INTENTS", "false")
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
            // ⚠️ **Encendida aca aunque herede de release, y es deliberado.** `benchmark` es la
            // build con la que se mide arranque y bateria (D-166) y la unica que se parece a
            // release siendo instalable: si fuera la unica sin poder sembrar una consulta, medir
            // una busqueda obligaria a un dedo. Es el mismo razonamiento por el que D-212 eligio
            // `Log.isLoggable` y no `BuildConfig.DEBUG`.
            buildConfigField("boolean", "DEBUG_INTENTS", "true")
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

            // `android.util.Log` devuelve el valor por defecto en vez de lanzar.
            //
            // Hizo falta al instrumentar la app con `DictLog`: el `android.jar` de los tests
            // unitarios es un stub que lanza `RuntimeException("Method isLoggable in
            // android.util.Log not mocked")`, asi que **un log dentro del ViewModel tiraba diez
            // tests de `SearchViewModelTest`**, que es un test JVM plano y no de Robolectric.
            //
            // ⚠️ **El precio es real y conviene saberlo**: con esto, CUALQUIER metodo de Android
            // sin mockear deja de avisar y devuelve `null`/`0`/`false` en silencio. Lo que antes
            // era una excepcion que decia "este test esta tocando el framework sin querer" ahora
            // pasa desapercibido. Los tests que de verdad necesitan Android siguen siendo los de
            // Robolectric, que traen la implementacion real y no se ven afectados por esto --por
            // eso `DictLogTest` puede afirmar sobre las lineas emitidas.
            isReturnDefaultValues = true
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
 * El directorio se configura con `dictionary.packsDir`; por defecto es
 * `../wearos-dictionary-data/dist`.
 *
 * ⚠️ **El default apuntaba a `../wearos-dictionary-data` a secas y por eso el APK viajaba SIN
 * diccionario.** El rebuild del 2026-09-22 movio lo publicable a `dist/` --que es la unica
 * carpeta que `packserver.py` sirve, y lo que `tools/CLAUDE.md` documenta-- y esta ruta se quedo
 * atras. El sintoma es exactamente el que este repo no puede ver: **el build sigue verde**,
 * porque `filter { it.isFile }` deja la lista vacia y eso es un caso soportado a proposito (un
 * clone limpio no tiene los packs, D-175). Lo unico que se nota es que la app arranca diciendo
 * *"No hay ningun diccionario instalado"* -- en el reloj, despues de instalar.
 *
 * Comprobado el 2026-09-23: `src/main/assets/` tenia solo el `.gitkeep`.
 */
val packsDir = providers.gradleProperty("dictionary.packsDir")
    .getOrElse("../wearos-dictionary-data/dist")
/**
 * Como se llama, en `assets/`, el indice de versiones de los packs incluidos.
 *
 * El mismo literal vive en `PackStore.CORE_INDEX`: son los dos extremos de un archivo, y no hay
 * forma de compartir una constante entre el script de build y el codigo de la app. Lo que si hay
 * es un enforcer -- `check_core_index_name` en el audit -- que falla si dejan de coincidir.
 */
val CORE_INDEX = "core-index.tsv"

val nucleos = listOf("es-core.db", "en-core.db")
    .map { rootProject.layout.projectDirectory.file("$packsDir/$it").asFile }
    .filter { it.isFile }

/**
 * El **indice**: el `data_version` de cada nucleo que viaja en el APK.
 *
 * ⚠️ **Existe para que la app NO tenga que extraer un pack de 50 MB para preguntarle su version.**
 * La primera version de D-226 copiaba el asset a un `.candidate`, lo abria y comparaba: correcto,
 * y caro. La observacion que lo corrige: *«puedo tener guardada la version del pack incluido en la
 * app, es un caso excepcional y hace la funcion de indice que si tiene el server web»*. Y es
 * literalmente eso — el archivo que se lee aca es **el mismo `index.json` que `packserver.py`
 * sirve** (`--index-only`), no un formato nuevo.
 *
 * ⚠️ **`db_bytes` es el guardian de frescura, y sin el esto seria peor que el metodo caro.** Un
 * indice viejo al lado de un `.db` nuevo declararia una version que no es, y la app decidiria un
 * reemplazo con un numero equivocado -- el downgrade silencioso que D-226 viene a cerrar, por otra
 * puerta. Si el tamano no coincide, el pack se queda **sin version declarada** y la app lo trata
 * como desconocido, que es el estado seguro.
 *
 * Se regenera con:
 *
 *     python3 tools/packserver.py <packsDir> --index-only > <packsDir>/index.json
 */
fun versionesDeclaradas(dirDePacks: File, packs: List<File>): Map<String, Long> {
    val indice = File(dirDePacks, "index.json")
    if (!indice.isFile) return emptyMap()
    val raiz = runCatching {
        @Suppress("UNCHECKED_CAST")
        (groovy.json.JsonSlurper().parse(indice) as Map<String, Any?>)["packs"] as? List<Any?>
    }.getOrNull() ?: return emptyMap()
    val porNombre = packs.associateBy { it.name }
    val salida = mutableMapOf<String, Long>()
    for (fila in raiz) {
        @Suppress("UNCHECKED_CAST")
        val entrada = fila as? Map<String, Any?> ?: continue
        val nombre = (entrada["db_url"] as? String)?.substringAfterLast('/') ?: continue
        val archivo = porNombre[nombre] ?: continue
        val version = (entrada["data_version"] as? Number)?.toLong() ?: continue
        val declarados = (entrada["db_bytes"] as? Number)?.toLong()
        // El guardian: un indice que no describe a ESTE archivo no se usa.
        if (declarados == null || declarados != archivo.length()) continue
        salida[nombre] = version
    }
    return salida
}

val bundlePacks = tasks.register("bundlePacks") {
    group = "build"
    description = "Pone en assets/ los packs nucleo. Si no estan, el APK viaja sin diccionario."
    val destino = layout.projectDirectory.dir("src/main/assets").asFile
    val dondeSeBusco = rootProject.layout.projectDirectory.file(packsDir).asFile
    val indice = File(dondeSeBusco, "index.json")
    inputs.files(nucleos)
    // El indice es entrada: cambiarlo tiene que re-correr la tarea, o el APK quedaria declarando
    // una version que ya no es la del pack que lleva adentro.
    //
    // ⚠️ **`inputs.files` en plural y no `inputs.file(...).optional()`**, y la diferencia rompe el
    // build: con la forma singular Gradle 9 valida la existencia ANTES de mirar el `optional()` y
    // falla con *«Input file does not exist»*. Un clone sin el directorio de datos --que es un
    // caso soportado, D-086 y D-175-- no compilaba. Lo agarro verificar el commit en un worktree,
    // que es exactamente donde ese directorio no esta.
    inputs.files(indice)
    outputs.dir(destino)
    val aCopiar = nucleos
    // ⚠️ **Se resuelve en CONFIGURACION y no dentro del `doLast`**, y no es estilo: el
    // configuration cache no puede serializar una referencia a una funcion del script, asi que
    // llamar a [versionesDeclaradas] desde la accion hace fallar el build entero. Lo que viaja al
    // `doLast` es el Map ya calculado, que si es serializable.
    val indiceDeVersiones = versionesDeclaradas(dondeSeBusco, nucleos)
    val versiones = indiceDeVersiones
    // ⚠️ Copia local del nombre, por el mismo motivo: leer una `val` de nivel de script desde el
    // `doLast` captura el objeto del script y el configuration cache lo rechaza.
    val nombreDelIndice = CORE_INDEX
    doLast {
        // ⚠️ **Avisa fuerte cuando no hay nucleos, y eso NO es cosmetica.** Que la lista venga
        // vacia es un caso soportado --un clone limpio no tiene los packs, que pesan 372 MB y
        // viven fuera del repo (D-175, D-086: un clone limpio tiene que seguir compilando)-- asi
        // que el build no puede fallar aqui. Pero el silencio ya costo: el 2026-09-22 el rebuild
        // movio los packs a `dist/`, esta ruta se quedo atras, y **el build siguio verde
        // publicando un APK sin diccionario**. El sintoma aparecia recien en el reloj.
        if (aCopiar.isEmpty()) {
            logger.warn(
                "bundlePacks: NO hay nucleos y el APK va a viajar SIN diccionario.\n" +
                    "  se buscaron es-core.db y en-core.db en: $dondeSeBusco\n" +
                    "  si estan en otro lado: ./gradlew ... -Pdictionary.packsDir=<ruta>",
            )
        }
        destino.mkdirs()
        // Se limpia lo anterior: dejar un pack viejo al lado de uno nuevo significa que la app
        // abre los dos, y el viejo contesta con datos de otra construccion.
        destino.listFiles()?.filter { it.name.endsWith(".db") }?.forEach { it.delete() }
        aCopiar.forEach { it.copyTo(File(destino, it.name), overwrite = true) }

        // El indice de versiones. Ver [versionesDeclaradas]: sin el, la app no puede saber si el
        // nucleo del APK es mas nuevo que el que el usuario bajo, y lo deja como esta.
        val manifiesto = File(destino, nombreDelIndice)
        if (versiones.isEmpty()) {
            manifiesto.delete()
            if (aCopiar.isNotEmpty()) {
                logger.warn(
                    "bundlePacks: hay nucleos pero NINGUNA version declarada.\n" +
                        "  la app no va a poder actualizarlos al instalar una version nueva:\n" +
                        "  se queda con el que el usuario tenga (ver D-226).\n" +
                        "  regeneralo con:\n" +
                        "    python3 tools/packserver.py $dondeSeBusco --index-only \\\n" +
                        "      > $indice",
                )
            }
        } else {
            // TSV y no JSON: son dos lineas y el parser vive en el reloj. Un formato que se
            // parsea con `split('\t')` no puede tirar una excepcion que nadie espera.
            manifiesto.writeText(
                versiones.entries.sortedBy { it.key }
                    .joinToString("\n") { (nombre, v) -> "$nombre\t$v" } + "\n",
            )
            logger.lifecycle("bundlePacks: ${versiones.size} nucleo(s) con version declarada")
            aCopiar.filterNot { it.name in versiones }.forEach {
                logger.warn("bundlePacks: ${it.name} viaja SIN version declarada (indice ausente o desactualizado)")
            }
        }
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

    // WorkManager. Estaba en el catalogo de versiones desde hace tiempo y sin usar, porque no
    // habia nada que diferir: los packs entraban por cable. Llega con la descarga (D-029), que
    // **exige** cargando + Wi-Fi sin medir, y eso es exactamente lo que WorkManager impone sin
    // que la app tenga que mirar el estado de la red --por eso el manifest no pide
    // ACCESS_NETWORK_STATE, y el audit lo vigila--.
    implementation(libs.work.runtime.ktx)
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
