// Puts the Python side inside the gate.
//
// Without this, the builder's 35 tests and the structural audit live outside `./gradlew check`,
// and a drift introduced in normalize.py would pass the gate without trouble -- which is exactly
// the failure mode the whole project tries to avoid (see docs/contratos-cruzados.md §1).
//
// The `base` plugin is the only one applied: it contributes the `check` task, which Gradle adds to
// the root's when `./gradlew check` is run.
plugins {
    base
}

/** The pack pipeline's 35 tests. Stdlib only: there is no environment to prepare. */
val pythonTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Corre los tests de tools/packbuilder."
    workingDir = layout.projectDirectory.dir("packbuilder").asFile
    commandLine("python3", "-m", "unittest", "discover", "-s", "tests")
}

/** The structural audit: it checks the repo against the rules it writes itself. */
val structuralAudit by tasks.registering(Exec::class) {
    group = "verification"
    description = "Corre tools/audit_dictionary.py."
    workingDir = rootProject.layout.projectDirectory.asFile
    commandLine("python3", "tools/audit_dictionary.py")
}

tasks.named("check") {
    dependsOn(pythonTest, structuralAudit)
}
