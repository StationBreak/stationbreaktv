// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore

import com.android.build.api.variant.HostTestBuilder

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

// AGP builds unit tests for the debug variants only. The manifest tests (BackupRulesTest) must also see the release and
// benchmark manifests, because only the benchmark one may carry the exported PlaybackTestActivity (spec 13). The flavors
// share every source set and dependency, so the github benchmark manifest stands for the amazon one: that one alone is
// left out, which saves a manifest merge and a test run. Amazon's release is kept: it is the one that ships.
androidComponents {
    beforeVariants { variant ->
        val amazonBenchmark =
            variant.buildType == "benchmark" && variant.productFlavors.contains("distribution" to "amazon")
        if (!amazonBenchmark) variant.hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.enable = true
    }
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

    // BuildConfig.DEBUG guards the debug-only lineup timing probe (LineupTimingProbe).
    buildFeatures {
        buildConfig = true
    }

    // Debug builds use Android's per-machine debug key. CI re-signs its debug APK with the CI debug key in a separate
    // step, outside Gradle, so no build code ever sees that key (ADR-0011, amended 2026-10-18).
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
        release {
            // No signing config: release signing comes only from protected secrets (S4.1.1).
            isDebuggable = false
        }
        // The playback matrix build (spec 13): release code, with the benchmark-only PlaybackTestActivity (src/benchmark)
        // that plays items on a real device and reports JSON lines. Not debuggable, so it measures what ships. It is
        // signed with the per-machine debug key (never a protected one) so it installs with a plain `adb install`; its
        // own application ID keeps it beside the debug and release builds. Never published: the stores build release
        // only. CI builds debug and release, and on main also builds this one and signs it with the CI debug key, for
        // device measurement (#392).
        // initWith copies release's minify and shrink settings: keep them that way, or it measures code that is not shipped.
        create("benchmark") {
            initWith(getByName("release"))
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            applicationIdSuffix = ".benchmark"
        }
    }
}

dependencies {
    // ADR-0004: app consumes the KMP shared core through Gradle's jvm variant.
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    // The Media3 player, the capability probe and the tune controller (spec 13), wired in di/PlaybackModule.
    implementation(project(":core:playback"))
    // The adapter behind the MediaServer port, bound in di/MediaServerModule. Nothing else imports it.
    implementation(project(":data:jellyfin"))
    // The Keystore-backed SecretStore, bound in di/MediaServerModule. Nothing else imports it.
    implementation(project(":data:settings"))
    // The Room-backed LibraryRepository (F0.6), bound in di/LibraryModule. Nothing else imports it.
    implementation(project(":data:library"))
    // The base OkHttpClient is built here (ADR-0005); data:jellyfin keeps OkHttp to itself otherwise.
    implementation(libs.okhttp)
    // The player reads media through the credentialed OkHttp client; the app wraps it in this data source and hands it
    // over (ADR-0016, 9), so core:playback needs no HTTP client of its own.
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(project(":core:ui"))
    // The screens. Their ViewModels get the MediaServer port through Hilt, wired in di/.
    implementation(project(":feature:live"))
    implementation(project(":feature:guide"))
    implementation(project(":feature:onboarding"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    // ProcessLifecycleOwner: the app coming to the foreground starts the quiet library sync (spec 06 task 4.0).
    implementation(libs.androidx.lifecycle.process)
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
    // The pinned-trust tests (spec 05, Unit 3) run local TLS servers with generated test certificates on the emulator.
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.okhttp.tls)

    // The pin-sync tests (spec 05, Unit 3) use the in-memory settings fake and the pinned client's JVM seams.
    testImplementation(testFixtures(project(":core:domain")))
    // The benchmark playback run (src/benchmark) is tested with virtual time.
    testImplementation(libs.kotlinx.coroutines.test)
}

// The manifest and XML-rule tests (BackupRulesTest, NetworkSecurityConfigTest) read these files straight off disk, so
// Gradle must see them as inputs. Without that, a loosened rule file leaves the test task up to date and unrun.
tasks.withType<Test>().configureEach {
    inputs
        .files(fileTree("src") { include("**/AndroidManifest.xml", "**/res/xml/**", "**/res/raw/**") })
        .withPropertyName("manifestAndXmlRules")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// BackupRulesTest also reads the merged manifest of the variant under test: a library's manifest could merge in a
// backupAgent or loosen a backup attribute, and only the merge shows it. The unit test task for each variant depends on
// that variant's manifest merge and gets the merged file's path.
tasks.withType<Test>().configureEach {
    val variant = name.removePrefix("test").removeSuffix("UnitTest")
    if (variant.isNotEmpty() && variant != name) {
        val mergeTask = tasks.named("process${variant}MainManifest")
        val merged =
            layout.buildDirectory.file(
                "intermediates/merged_manifest/${variant.replaceFirstChar { it.lowercase() }}/" +
                    "process${variant}MainManifest/AndroidManifest.xml",
            )
        dependsOn(mergeTask)
        inputs.file(merged).withPropertyName("mergedManifest").withPathSensitivity(PathSensitivity.NONE)
        systemProperty("mergedManifest", merged.get().asFile.absolutePath)
    }
}
