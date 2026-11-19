// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore

plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    // Hands the test APK's classes to the app's R8 run, for the keep rules (#436).
    id("stationbreak.security-test-classes")
    id("stationbreak.architecture")
    id("stationbreak.quality")
}

android {
    namespace = "tv.stationbreak.securitytest"
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
        testInstrumentationRunner = "tv.stationbreak.app.HiltTestRunner"
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
        getByName("main").kotlin.directories.add("../app/src/androidTest/securityTest/hilt/kotlin")
    }
}

dependencies {
    implementation(libs.hilt.android)
    implementation(libs.hilt.android.testing)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.test.core)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.junit)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    implementation(testFixtures(project(":core:domain")))
    implementation(project(":data:jellyfin"))
    implementation(project(":data:settings"))
    // The tests build the app's whole Hilt graph under HiltTestApplication, so every module that contributes to it is here.
    implementation(project(":data:library"))
    implementation(project(":core:playback"))
    implementation(project(":core:ui"))
    implementation(project(":feature:live"))
    implementation(project(":feature:guide"))
    implementation(project(":feature:channels"))
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:settings"))
}

// hilt-android(-testing) asks for old AndroidX releases; the app's own classpath resolves to newer, already verified
// ones, and the test APK must run against the same. Every version is in gradle/libs.versions.toml (the Compose ones
// come from the BOM). shouldResolveConsistentlyWith(app) was tried first and fails: Gradle will not resolve another
// project's configuration without its lock when tasks run in parallel.
dependencies {
    constraints {
        implementation(libs.androidx.activity)
        implementation(libs.androidx.core)
        implementation(libs.androidx.core.ktx)
        implementation(libs.androidx.savedstate)
        implementation(libs.androidx.collection)
        implementation(libs.androidx.profileinstaller)
        implementation(libs.androidx.lifecycle.common)
        implementation(libs.androidx.lifecycle.livedata)
        implementation(libs.androidx.lifecycle.livedata.core)
        implementation(libs.androidx.lifecycle.runtime)
        implementation(libs.androidx.lifecycle.viewmodel)
        implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    }
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.runtime.annotation)
    implementation(libs.androidx.compose.runtime.annotation.android)
}

// The shared tests reach a few `internal` app types (AndroidSystemTrust, StationBreakApp.AppStartEntryPoint,
// resumeSelection), as the app's own androidTest does: the app's classes are this module's friend.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    val appBuild =
        project(":app")
            .layout.buildDirectory
            .get()
            .asFile
            .toPath()
    friendPaths.from(libraries.filter { it.toPath().startsWith(appBuild) })
}
