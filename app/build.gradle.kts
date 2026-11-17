// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore

import com.android.build.api.variant.BuildConfigField
import com.android.build.api.variant.HostTestBuilder
import tv.stationbreak.buildlogic.ReleaseVersion

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

// CI gives its debug and benchmark builds a rising versionCode (#146), so each one installs over the last on a test
// device. CI passes the workflow's run number in CI_VERSION_CODE; a local build has none and keeps 1. Release builds
// keep defaultConfig's value, unless RELEASE_VERSION is set (below). Android's limit for versionCode is 2100000000.
val ciVersionCode: Int =
    providers.environmentVariable("CI_VERSION_CODE").orNull?.let { raw ->
        val code = raw.toIntOrNull()
        require(code != null && code in 1..2_100_000_000) {
            "CI_VERSION_CODE must be a whole number from 1 to 2100000000, not '$raw'"
        }
        code
    } ?: 1

// A tagged release (ADR-0011): the release workflow's build job passes the tag's version, without the `v`, in
// RELEASE_VERSION. versionName is that value and versionCode is MAJOR * 10000 + MINOR * 100 + PATCH (ReleaseVersion,
// which fails the build on a malformed value). Without it a release build keeps defaultConfig's 1 and 0.0.1.
val releaseVersion: ReleaseVersion? =
    providers.environmentVariable("RELEASE_VERSION").orNull?.let { ReleaseVersion.parse(it) }

// Whether StationBreak Pro is unlocked, by (build type, flavor) (#793). Debug and benchmark builds keep it open, so
// dogfood and device runs see no gate. Release ships closed in both flavors: any pair not listed is closed. What the
// GitHub release build does is #119's call, and one edit to this table. See di/ProModule and the testRelease guard.
val proUnlockedByVariant: Map<Pair<String, String?>, Boolean> =
    mapOf(
        ("debug" to "amazon") to true,
        ("debug" to "github") to true,
        ("benchmark" to "amazon") to true,
        ("benchmark" to "github") to true,
        ("release" to "amazon") to false,
        ("release" to "github") to false,
    )

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
    // kotlinx-coroutines' debug-agent file is for a JVM debugger attaching to the process; a non-debuggable build has no
    // use for it.
    onVariants { variant ->
        val distribution = variant.productFlavors.firstOrNull { it.first == "distribution" }?.second
        val proUnlocked = proUnlockedByVariant[(variant.buildType ?: "release") to distribution] ?: false
        // A release build must ship with the gates closed (#793): fail the build, not just a test.
        if (variant.buildType == "release" && proUnlocked) {
            throw GradleException("PRO_UNLOCKED must be false in a release build (${variant.name})")
        }
        variant.buildConfigFields?.put(
            "PRO_UNLOCKED",
            BuildConfigField("boolean", proUnlocked, "StationBreak Pro unlocked (#793); see app/build.gradle.kts"),
        )
        if (variant.buildType != "debug") {
            variant.packaging.resources.excludes
                .add("DebugProbesKt.bin")
        }
        if (variant.buildType != "release") {
            variant.outputs.forEach { it.versionCode.set(ciVersionCode) }
        } else if (releaseVersion != null) {
            variant.outputs.forEach {
                it.versionCode.set(releaseVersion.code)
                it.versionName.set(releaseVersion.name)
            }
        }
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

    // The build type the instrumented tests run against: debug, unless CI asks for the R8-minified benchmark build to
    // run the security tests against shipped code (#436): `-Pstationbreak.testBuildType=benchmark`.
    testBuildType = providers.gradleProperty("stationbreak.testBuildType").getOrElse("debug")

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
            // The warm standby's log.tag.SBWARM switches are read (di/WarmModule); never in release.
            buildConfigField("boolean", "WARM_SWITCHES", "true")
        }
        release {
            // No signing config, ever: Gradle never sees the release key. The release workflow signs the unsigned APK
            // in a separate job with no checkout (ADR-0011, S4.1.1).
            isDebuggable = false
            buildConfigField("boolean", "WARM_SWITCHES", "false")
            // R8 shrinks and optimises what ships; ADR-0008's SP-2 frame numbers were measured with it on. Class and
            // method names stay readable (-dontobfuscate in proguard-rules.pro), so logs that print class simple names
            // need nothing more (#331). A stack trace's line numbers and inlined frames need the build's mapping.txt.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
            // The instrumented-test APK's own rules, for the security tests run against this build (#436).
            testProguardFiles("proguard-androidtest.pro")
            isDebuggable = false
            // Release code, but measured on a device: the warm switches are read here (like debug).
            buildConfigField("boolean", "WARM_SWITCHES", "true")
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            applicationIdSuffix = ".benchmark"
        }
    }

    // The debug and benchmark builds share the ADB commands in src/nonRelease (#812); no release build compiles it.
    // Each of the two manifests declares the receiver, so the release manifest cannot. After buildTypes: a build
    // type's source set exists once it is declared.
    sourceSets {
        getByName("debug").kotlin.directories.add("src/nonRelease/kotlin")
        getByName("benchmark").kotlin.directories.add("src/nonRelease/kotlin")
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
    implementation(project(":feature:channels"))
    implementation(project(":feature:onboarding"))
    // Settings, Libraries (spec 26): the guide's Libraries slot, filled in nav/AppNavHost.
    implementation(project(":feature:settings"))
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
    // The benchmark's JSON lines (src/benchmark/.../JsonLine.kt) are encoded by kotlinx.serialization (#822). It is
    // already on every variant's runtime classpath through data:jellyfin; this only lets the benchmark compile against it.
    "benchmarkImplementation"(libs.kotlinx.serialization.json)
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
