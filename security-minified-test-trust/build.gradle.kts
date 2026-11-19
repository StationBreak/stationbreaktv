// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore

// The two security tests that need no Hilt (PinnedTrustOnApi25Test, NetworkSecurityConfigChainTest), run against the
// R8-minified app under the plain AndroidJUnitRunner (#436). Same shared sources as :security-minified-test, picked by file.
plugins {
    alias(libs.plugins.android.test)
    id("stationbreak.security-test-classes")
    id("stationbreak.architecture")
    id("stationbreak.quality")
}

android {
    namespace = "tv.stationbreak.securitytesttrust"
    compileSdk =
        libs.versions.compileSdk
            .get()
            .toInt()

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        minSdk =
            libs.versions.minSdk
                .get()
                .toInt()
        targetSdk =
            libs.versions.targetSdk
                .get()
                .toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"

    flavorDimensions += "distribution"
    productFlavors {
        create("amazon") { dimension = "distribution" }
        create("github") { dimension = "distribution" }
    }

    buildTypes {
        create("benchmark") {
            initWith(getByName("debug"))
            matchingFallbacks += "release"
        }
    }

    sourceSets {
        getByName("main").kotlin.directories.add("../app/src/androidTest/securityTest/trust/kotlin")
    }
}

dependencies {
    implementation(libs.androidx.test.core)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.junit)
    implementation(libs.okhttp)
    implementation(libs.okhttp.mockwebserver)
    implementation(libs.okhttp.tls)
    implementation(libs.kotlinx.coroutines.core)
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    implementation(project(":data:jellyfin"))
}

// The trust tests reach AndroidSystemTrust, which is
// `internal` to the app, so the app's classes are this module's friend, as in app's own androidTest.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    val appBuild =
        project(":app")
            .layout.buildDirectory
            .get()
            .asFile
            .toPath()
    friendPaths.from(libraries.filter { it.toPath().startsWith(appBuild) })
}
