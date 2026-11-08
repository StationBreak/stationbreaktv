// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore

plugins {
    id("stationbreak.android-lib")
    id("stationbreak.compose")
    // @HiltViewModel (MODULES.md, "Hilt in pure modules"): Android feature modules may apply Hilt. The guide's
    // ViewModel and screen (spec 17) are built on this module.
    id("stationbreak.hilt")
}

android {
    namespace = "tv.stationbreak.feature.guide"
}

dependencies {
    // Ports and value types. A feature module never sees the adapters (MODULES.md): `app` wires them.
    implementation(project(":core:domain"))
    implementation(project(":core:model"))
    implementation(project(":core:ui"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    testImplementation(testFixtures(project(":core:domain")))
    testImplementation(libs.kotlinx.coroutines.test)

    // The screen's instrumented tests (spec 17) use these; they also pin the androidx.test versions that Hilt's test
    // artifact would otherwise resolve to an older one.
    androidTestImplementation(testFixtures(project(":core:domain")))
    androidTestImplementation(libs.kotlinx.coroutines.core)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.compose.ui.test.manifest)
}

// ChannelMarkManifestTest scans every module's channel mark folders (Android merges assets across modules). Declare
// the manifest and the files it scans as inputs so a cached test result cannot hide a file that was added or changed.
tasks.withType<Test>().configureEach {
    inputs
        .files(
            fileTree(rootDir) {
                include("feature/guide/channelmarks.sha256", "**/src/*/assets/channelmarks/**")
                // Dot-folders and build output are pruned at the root walk only, never beneath a channelmarks folder.
                exclude { element ->
                    val segments = element.relativePath.segments
                    val marks = segments.indexOf("channelmarks")
                    val head = if (marks >= 0) segments.take(marks) else segments.dropLast(1)
                    head.any { it.startsWith(".") || it == "build" }
                }
            },
        ).withPathSensitivity(PathSensitivity.RELATIVE)
        .ignoreEmptyDirectories()
        .withPropertyName("channelMarkManifestScan")
}
