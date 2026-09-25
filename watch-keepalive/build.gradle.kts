import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
}

/**
 * Un APK que existe para una sola cosa: sostener un `PARTIAL_WAKE_LOCK` mientras dura una sesion
 * de depuracion inalambrica.
 *
 * NO ES PARTE DEL PRODUCTO. Nadie lo declara como dependencia, `:app` no lo conoce, y el APK que
 * se mide no cambia por su existencia. Se instala a mano cuando hace falta y se desinstala cuando
 * no.
 *
 * POR QUE HACE FALTA UN APK Y NO UN AJUSTE
 *
 * **Medido el 2026-09-25** sobre un SM-L715F (Android 17 / SDK 37), fuera del cargador:
 *
 * - `wifi_always_requested=1` mantiene la radio --el mediator decide `toggleRadioState: true`
 *   incluso en el `SCREEN_OFF`-- y aun asi la sesion muere: lo que se apaga es el **SoC**.
 * - `screen_off_timeout=1800000` no sirve: la pantalla entro en `Dozing` a los **68 segundos**.
 *   En Wear OS el ambient es el estado normal y no consulta ese timeout, que es un knob de
 *   telefono. Un gesto de tapar la pantalla le gana igual.
 *
 * Lo unico que impide el suspend es un wake lock sostenido por un proceso, y para sostenerlo
 * hace falta un proceso. De ahi este modulo.
 *
 * SIN ICONO
 *
 * No declara ninguna activity, y menos una con `LAUNCHER`: no aparece en la lista de apps del
 * reloj. Se arranca por componente explicito desde adb, que funciona igual sobre un paquete sin
 * punto de entrada.
 */
android {
    namespace = "cl.fadiaz.watchkeepalive"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "cl.fadiaz.watchkeepalive"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
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
