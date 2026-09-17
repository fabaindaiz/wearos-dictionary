// Mete el lado Python dentro del gate.
//
// Sin esto, los 35 tests del builder y la auditoria estructural viven fuera de `./gradlew check`,
// y un desfase introducido en normalize.py pasaria el gate sin problema -- que es justo el modo
// de falla que el proyecto entero intenta evitar (ver docs/contratos-cruzados.md §1).
//
// El plugin `base` es lo unico que se aplica: aporta la tarea `check`, que Gradle agrega a la
// del root cuando se corre `./gradlew check`.
plugins {
    base
}

/** Los 35 tests del pipeline de packs. Solo stdlib: no hay entorno que preparar. */
val pythonTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Corre los tests de tools/packbuilder."
    workingDir = layout.projectDirectory.dir("packbuilder").asFile
    commandLine("python3", "-m", "unittest", "discover", "-s", "tests")
}

/** La auditoria estructural: comprueba el repo contra las reglas que el mismo escribe. */
val structuralAudit by tasks.registering(Exec::class) {
    group = "verification"
    description = "Corre tools/audit_dictionary.py."
    workingDir = rootProject.layout.projectDirectory.asFile
    commandLine("python3", "tools/audit_dictionary.py")
}

tasks.named("check") {
    dependsOn(pythonTest, structuralAudit)
}
