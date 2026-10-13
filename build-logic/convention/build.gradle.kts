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
}

spotless {
    val spdxHeader = "// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore"
    kotlin {
        target("src/**/*.kt")
        ktlint(libs.versions.ktlint.get())
        licenseHeader(spdxHeader)
    }
    kotlinGradle {
        // This project's build file and build-logic's settings file.
        target("*.gradle.kts", "../*.gradle.kts")
        ktlint(libs.versions.ktlint.get())
        licenseHeader(spdxHeader, "^(?!//).*$")
    }
}

gradlePlugin {
    plugins {
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
    }
}
