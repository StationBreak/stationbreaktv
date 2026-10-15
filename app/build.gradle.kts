// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore

plugins {
    // SDK levels, flavors, desugaring, toolchain, Lint, Spotless and detekt: build-logic's AndroidAppPlugin.
    id("stationbreak.android-app")
    id("stationbreak.compose")
    id("stationbreak.licensee")
    // The only module that applies Hilt: `[KMP]` and `[JVM]` modules keep to javax.inject (MODULES.md).
    id("stationbreak.hilt")
    // Typed navigation routes (`@Serializable` route objects).
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "tv.stationbreak.app"

    defaultConfig {
        applicationId = "tv.stationbreak.app"
        versionCode = 1
        versionName = "0.0.1"
        // Hilt's test application replaces ours in instrumented tests.
        testInstrumentationRunner = "tv.stationbreak.app.HiltTestRunner"
    }

    signingConfigs {
        // Public, throwaway and debug-only (ADR-0011): committed so every CI debug build installs over the last.
        // It must never sign a release.
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            // No signing config: release signing comes only from protected secrets (S4.1.1).
            isDebuggable = false
        }
    }
}

dependencies {
    // ADR-0004: app consumes the KMP shared core through Gradle's jvm variant.
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    // The adapter behind the MediaServer port, bound in di/MediaServerModule. Nothing else imports it.
    implementation(project(":data:jellyfin"))
    // The Keystore-backed SecretStore, bound in di/MediaServerModule. Nothing else imports it.
    implementation(project(":data:settings"))
    // The base OkHttpClient is built here (ADR-0005); data:jellyfin keeps OkHttp to itself otherwise.
    implementation(libs.okhttp)
    implementation(project(":core:ui"))
    // The screens. Their ViewModels get the MediaServer port through Hilt, wired in di/.
    implementation(project(":feature:onboarding"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    // The Jellyfin SDK logs through SLF4J and without a binding its first request crashes on Android (ADR-0006).
    // A no-op until S3.6.1 builds the redacting logger.
    runtimeOnly(libs.slf4j.nop)

    // The API 25 plaintext-marker test (F0.3 task 1.16) runs data:jellyfin's classifier on the emulator.
    androidTestImplementation(project(":data:jellyfin"))
    // The test makes its own https call; data:jellyfin keeps OkHttp to itself.
    androidTestImplementation(libs.okhttp)
    androidTestImplementation(project(":core:domain"))
    // The secret-storage tests (spec 04) restore a session against the port's scripted fake.
    androidTestImplementation(testFixtures(project(":core:domain")))
    // The SDK-binding test (F0.3 task 3.13, ADR-0006) makes one request through the SDK's own client.
    androidTestImplementation(libs.jellyfin.sdk.core)
    androidTestImplementation(libs.jellyfin.sdk.api.okhttp)
    // The adapter smoke test (F0.3 task 2.13): the adapter's own types, and runBlocking to call it.
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    // The pin-sync tests (spec 05, Unit 3) use the in-memory settings fake and the pinned client's JVM seams.
    testImplementation(testFixtures(project(":core:domain")))
}

// The manifest and XML-rule tests (BackupRulesTest, NetworkSecurityConfigTest) read these files straight off disk, so
// Gradle must see them as inputs. Without that, a loosened rule file leaves the test task up to date and unrun.
tasks.withType<Test>().configureEach {
    inputs
        .files(fileTree("src") { include("**/AndroidManifest.xml", "**/res/xml/**", "**/res/raw/**") })
        .withPropertyName("manifestAndXmlRules")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
