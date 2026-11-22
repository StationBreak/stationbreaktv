// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore

plugins {
    id("stationbreak.kmp-lib")
}

kotlin {
    sourceSets.commonMain.dependencies {
        // The evaluator reads the channel rules (sources, exclusions, the rating ceiling) and the rating check.
        api(project(":core:rules"))
        // Its input snapshot and its output items are core:model types.
        api(project(":core:model"))
    }
    // Test only: the pool vectors are JSON. The library is already in the build for core:model and core:rules.
    sourceSets.commonTest.dependencies {
        implementation(libs.kotlinx.serialization.json)
    }
    // Test only: a 15,000-item library for the lineup planner's size test, which runs on the JVM only (MODULES.md:
    // tests may use it).
    sourceSets.jvmTest.dependencies {
        implementation(project(":core:testing"))
    }
    jvm {
        // The lineup audit (spec: docs/design/lineup-seats-and-gaps.md section 2; tools/audit-export/README.md): a
        // command-line run of the real planner and curation on a library snapshot. Its own compilations, associated
        // with `main` so they see its `internal` code. They are not a source set of `main` or of any published
        // variant: no other module can depend on them, and `commonMain` gains nothing from them.
        val main = this.compilations.getByName("main")
        val audit = this.compilations.create("audit") { associateWith(main) }
        // The gate's tests share the synthetic adult slice of the rating-safety tests (commonTest, `internal`), so they
        // are associated with `test` too (#1035).
        val test = this.compilations.getByName("test")
        val auditTest =
            this.compilations.create("auditTest") {
                associateWith(main)
                associateWith(audit)
                associateWith(test)
            }
        // Its own serialization dependency: the snapshot file is JSON. (core:rules already exposes the library to
        // `commonMain` through its api; the audit names it for itself, so it does not rely on that.)
        audit.defaultSourceSet.dependencies { implementation(libs.kotlinx.serialization.json) }
        auditTest.defaultSourceSet.dependencies { implementation(kotlin("test-junit")) }
        // The gate (#1035) runs from here: it cuts each channel's pool by the epoch with the scheduler's own function, as
        // the day build does. A test-only edge (docs/MODULES.md): the audit's main code stays free of core:scheduler.
        auditTest.defaultSourceSet.dependencies { implementation(project(":core:scheduler")) }
    }
}

// The rules-format vectors are the source of truth. The jvmTest check reads them from the repository and compares them
// with the Kotlin copy in commonTest, which is what linuxX64Test can use (it cannot read files).
val rulesFormatDir = rootProject.layout.projectDirectory.dir("docs/rules-format")

tasks.withType<Test>().configureEach {
    systemProperty("rulesFormat.dir", rulesFormatDir.asFile.absolutePath)
    inputs.files(rulesFormatDir.asFileTree).withPropertyName("rulesFormatFiles").withPathSensitivity(PathSensitivity.RELATIVE)
}

// The audit's own tests (jvmAuditTest): the golden outputs of the profile libraries, the snapshot codec and the
// per-term scores. `check` runs them. The goldens are inputs, so a changed golden reruns them.
val auditGoldenDir = layout.projectDirectory.dir("src/audit/golden")
// The household-view gate (#1035; docs/design/lineup-seats-and-gaps.md): frozen fixtures and their pinned reports.
val gateDir = layout.projectDirectory.dir("src/audit/gate")
val gateFixtureDir = gateDir.dir("fixtures")
val gateGoldenDir = gateDir.dir("golden")
val auditExportFixtures = rootProject.layout.projectDirectory.dir("tools/audit-export/fixtures")
val auditTestCompilation = kotlin.jvm().compilations.getByName("auditTest")
val jvmAuditTest =
    tasks.register<Test>("jvmAuditTest") {
        group = "verification"
        description = "Runs the lineup audit's tests."
        testClassesDirs = auditTestCompilation.output.classesDirs
        classpath = files(auditTestCompilation.output.allOutputs, auditTestCompilation.runtimeDependencyFiles)
        systemProperty("audit.golden.dir", auditGoldenDir.asFile.absolutePath)
        systemProperty("audit.export.fixtures", auditExportFixtures.asFile.absolutePath)
        systemProperty("gate.fixtures.dir", gateFixtureDir.asFile.absolutePath)
        systemProperty("gate.golden.dir", gateGoldenDir.asFile.absolutePath)
        inputs.files(fileTree(gateDir)).withPropertyName("gateFiles").withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.files(auditExportFixtures.asFileTree).withPropertyName("auditExportFixtures").withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.files(fileTree(auditGoldenDir)).withPropertyName("auditGoldens").withPathSensitivity(PathSensitivity.RELATIVE)
    }
// The kmp-lib convention names the detekt tasks of main and test; the audit's compilations are this module's own, so
// `check` runs their detekt tasks here. The names are the ones the detekt plugin makes for a compilation called `audit`
// (`detekt<Compilation>Jvm`) and for a source set called `jvmAudit` (`detekt<SourceSet>SourceSet`), pinned like the
// convention plugin's: a task that does not exist fails the build, so a rename is caught.
tasks.named("check") {
    dependsOn(jvmAuditTest, "detektAuditJvm", "detektAuditTestJvm", "detektJvmAuditSourceSet", "detektJvmAuditTestSourceSet")
}

// The lineup audit's command line (tools/audit-export/README.md). It reads a library snapshot (-PauditSnapshot=FILE) or a
// profile library (-PauditProfile=KEY or all) and prints the report, or writes it to -PauditOut=DIR.
//
//   ./gradlew :core:engine:lineupAudit -PauditProfile=sitcom-household
//   ./gradlew :core:engine:lineupAudit -PauditSnapshot=/path/to/snapshot.json -PauditOut=/path/to/reports
//   ./gradlew :core:engine:lineupAuditGoldens      (writes the pinned outputs again, after a change that moves them)
val auditCompilation = kotlin.jvm().compilations.getByName("audit")
val mainCompilation = kotlin.jvm().compilations.getByName("main")
val auditClasspath = files(auditCompilation.output.allOutputs, mainCompilation.output.allOutputs, auditCompilation.runtimeDependencyFiles)

tasks.register<JavaExec>("lineupAudit") {
    group = "lineup audit"
    description = "Runs the real lineup planner and curation on a library snapshot and explains the result."
    classpath = auditClasspath
    mainClass.set("tv.stationbreak.core.engine.audit.AuditMain")
    val snapshot = providers.gradleProperty("auditSnapshot")
    val profile = providers.gradleProperty("auditProfile")
    val out = providers.gradleProperty("auditOut")
    val format = providers.gradleProperty("auditFormat")
    argumentProviders.add(
        CommandLineArgumentProvider {
            buildList {
                snapshot.orNull?.let { addAll(listOf("--snapshot", it)) }
                profile.orNull?.let { addAll(listOf("--profile", it)) }
                out.orNull?.let { addAll(listOf("--out", it)) }
                format.orNull?.let { addAll(listOf("--format", it)) }
            }
        },
    )
}

tasks.register<JavaExec>("lineupAuditGoldens") {
    group = "lineup audit"
    description = "Writes the pinned audit outputs of the profile libraries again (src/audit/golden)."
    classpath = auditClasspath
    mainClass.set("tv.stationbreak.core.engine.audit.AuditMain")
    args("--update-goldens", auditGoldenDir.asFile.absolutePath)
}

// The household-view lineup gate (#1035, docs/design/lineup-seats-and-gaps.md): the real planner on the frozen fixtures with the ceilings
// and the leftovers on, hard-fail invariants and report-only warnings. `check` runs the same through jvmAuditTest.
//
//   ./gradlew :core:engine:lineupGate            (prints the failures and warnings; writes build/reports/lineup-gate)
//   ./gradlew :core:engine:lineupGateGoldens     (writes the generated fixtures and the pinned reports again)
tasks.register<JavaExec>("lineupGate") {
    group = "lineup audit"
    description = "Runs the household-view lineup gate on the frozen fixtures."
    classpath = files(auditTestCompilation.output.allOutputs, auditTestCompilation.runtimeDependencyFiles)
    mainClass.set("tv.stationbreak.core.engine.audit.GateMain")
    // A verification: it runs every time it is asked for, whatever the warnings file says.
    outputs.upToDateWhen { false }
    inputs.files(fileTree(gateDir)).withPropertyName("gateFiles").withPathSensitivity(PathSensitivity.RELATIVE)
    val warnings = layout.buildDirectory.file("reports/lineup-gate/warnings.txt")
    args("--fixtures", gateFixtureDir.asFile.absolutePath, "--golden", gateGoldenDir.asFile.absolutePath)
    args("--warnings", warnings.get().asFile.absolutePath)
}

tasks.register<JavaExec>("lineupGateGoldens") {
    group = "lineup audit"
    description = "Writes the gate's generated fixtures and pinned reports again (src/audit/gate)."
    classpath = files(auditTestCompilation.output.allOutputs, auditTestCompilation.runtimeDependencyFiles)
    mainClass.set("tv.stationbreak.core.engine.audit.GateGoldensMain")
    args(gateFixtureDir.asFile.absolutePath, gateGoldenDir.asFile.absolutePath)
}
