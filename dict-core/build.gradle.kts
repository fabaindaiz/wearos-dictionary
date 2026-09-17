import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Kotlin/JVM puro: sin dependencias de Android ni de SQLite. Todo lo que vive aqui
// tiene que poder testearse en la JVM en milisegundos, porque es el contrato que
// comparte con tools/packbuilder (ver TextNormalizer).
java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

kotlin {
    compilerOptions {
        // JVM 11 para coincidir con compileOptions de :app
        jvmTarget = JvmTarget.JVM_11
        allWarningsAsErrors = true
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 11
}

dependencies {
    testImplementation(libs.kotlin.test)
}

tasks.test {
    // Los vectores de normalizacion son el contrato compartido con el builder Python.
    // Se pasan por propiedad para que el test no dependa del cwd.
    systemProperty("vectors.dir", rootProject.file("tools/packbuilder/vectors").absolutePath)
    testLogging { showStandardStreams = true }
}
