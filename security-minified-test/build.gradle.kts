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
        getByName("main").kotlin.directories.add("../app/src/androidTest/securityTest/kotlin")
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
    implementation(libs.okhttp.mockwebserver)
    implementation(libs.okhttp.tls)
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

// hilt-android(-testing) asks for old AndroidX releases; the app's own classpath resolves to these newer, already
// verified ones, and the test APK must run against the same.
dependencies {
    constraints {
        for (artifact in listOf("activity:activity:1.13.0", "core:core:1.18.0", "core:core-ktx:1.18.0")) {
            implementation("androidx.$artifact")
        }
        for (name in listOf("livedata", "livedata-core", "runtime", "viewmodel", "viewmodel-savedstate")) {
            implementation("androidx.lifecycle:lifecycle-$name:2.11.0")
        }
        implementation("androidx.savedstate:savedstate:1.5.0")
        implementation("androidx.collection:collection:1.5.0")
        implementation("androidx.profileinstaller:profileinstaller:1.4.1")
        implementation("androidx.compose.runtime:runtime-annotation:1.12.1")
        implementation("androidx.compose.runtime:runtime-annotation-android:1.12.1")
    }
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
