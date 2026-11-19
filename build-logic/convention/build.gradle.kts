// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore

plugins {
    `kotlin-dsl`
    alias(libs.plugins.spotless)
}

group = "tv.stationbreak.buildlogic"

kotlin {
    jvmToolchain(17)
}

dependencies {
    // compileOnly: the root build puts these plugins on the classpath once (`apply false`), so every project
    // shares one copy of each and the convention plugins only compile against them.
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
    compileOnly(libs.detekt.gradlePlugin)
    compileOnly(libs.spotless.gradlePlugin)
    compileOnly(libs.licensee.gradlePlugin)

    // kotlin.test on JUnit 5 (the kotlin-dsl plugin's embedded Kotlin version), and TestKit for the
    // architecture check's fixture builds.
    testImplementation(kotlin("test"))
    // A JUnit 5 that reports a test it never runs (#211); the same version the JVM modules use.
    testImplementation(platform(libs.junit.bom))
    testImplementation(gradleTestKit())
    // Gradle 9 no longer supplies the JUnit Platform launcher; the junit-bom from kotlin-test-junit5 versions it.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // #211: a @Test JUnit cannot run (one that returns a value) fails the run instead of being skipped in silence.
    systemProperty("junit.platform.discovery.issue.severity.critical", "WARNING")
    // TestKit fixture projects for the architecture check.
    val fixtures = layout.projectDirectory.dir("src/test/fixtures")
    inputs.dir(fixtures).withPropertyName("fixtures")
    systemProperty("fixtures", fixtures.asFile.path)
    // RepositoryListsTest compares the root build's repository lists with build-logic's own (#182).
    val rootSettings = layout.projectDirectory.file("../../settings.gradle.kts")
    val buildLogicSettings = layout.projectDirectory.file("../settings.gradle.kts")
    inputs.files(rootSettings, buildLogicSettings).withPropertyName("settingsFiles")
    systemProperty("rootSettings", rootSettings.asFile.path)
    systemProperty("buildLogicSettings", buildLogicSettings.asFile.path)
}

spotless {
    val spdxHeader = "// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore"
    kotlin {
        target("src/**/*.kt")
        ktlint(libs.versions.ktlint.get())
        licenseHeader(spdxHeader)
    }
    kotlinGradle {
        // This project's build file, build-logic's settings file and the TestKit fixtures.
        target("*.gradle.kts", "../*.gradle.kts", "src/test/fixtures/**/*.gradle.kts")
        ktlint(libs.versions.ktlint.get())
        licenseHeader(spdxHeader, "^(?!//).*$")
    }
}

gradlePlugin {
    plugins {
        register("architecture") {
            id = "stationbreak.architecture"
            implementationClass = "tv.stationbreak.buildlogic.architecture.ArchitecturePlugin"
        }
        register("quality") {
            id = "stationbreak.quality"
            implementationClass = "tv.stationbreak.buildlogic.QualityPlugin"
        }
        register("androidApp") {
            id = "stationbreak.android-app"
            implementationClass = "tv.stationbreak.buildlogic.AndroidAppPlugin"
        }
        register("androidLib") {
            id = "stationbreak.android-lib"
            implementationClass = "tv.stationbreak.buildlogic.AndroidLibPlugin"
        }
        register("compose") {
            id = "stationbreak.compose"
            implementationClass = "tv.stationbreak.buildlogic.ComposePlugin"
        }
        register("kmpLib") {
            id = "stationbreak.kmp-lib"
            implementationClass = "tv.stationbreak.buildlogic.KmpLibPlugin"
        }
        register("licensee") {
            id = "stationbreak.licensee"
            implementationClass = "tv.stationbreak.buildlogic.LicenseePlugin"
        }
        register("contractTest") {
            id = "stationbreak.contract-test"
            implementationClass = "tv.stationbreak.buildlogic.ContractTestPlugin"
        }
        register("hilt") {
            id = "stationbreak.hilt"
            implementationClass = "tv.stationbreak.buildlogic.HiltPlugin"
        }
        register("securityTestClasses") {
            id = "stationbreak.security-test-classes"
            implementationClass = "tv.stationbreak.buildlogic.SecurityTestClassesPlugin"
        }
        register("jvmInject") {
            id = "stationbreak.jvm-inject"
            implementationClass = "tv.stationbreak.buildlogic.JvmInjectPlugin"
        }
        register("jvmLib") {
            id = "stationbreak.jvm-lib"
            implementationClass = "tv.stationbreak.buildlogic.JvmLibPlugin"
        }
    }
}
