// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore

import java.util.Locale

plugins {
    id("stationbreak.android-lib")
    id("stationbreak.compose")
}

android {
    namespace = "tv.stationbreak.core.ui"
}

dependencies {
    // MarkCache decodes channel logos off the main thread.
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)

    // The instrumented tests' ComponentActivity and test manifest would otherwise resolve androidx.activity,
    // lifecycle and core to old versions that have no entry in gradle/verification-metadata.xml; these pin them
    // to the app's versions (feature modules get the same pins from their lifecycle dependencies).
    androidTestImplementation(libs.androidx.activity.compose)
    androidTestImplementation(libs.androidx.lifecycle.viewmodel.compose)
    androidTestImplementation(libs.androidx.lifecycle.runtime.compose)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.compose.ui.test.manifest)
}

// FontManifestTest scans every module for font and licence files. Declare what it scans as inputs so a cached test
// result cannot hide a font that was added elsewhere. The test matches font extensions in any case (Foo.TTF counts),
// so the inputs do too; plain include patterns are case-sensitive.
val fontExtensions = setOf("ttf", "otf", "ttc", "woff", "woff2")
tasks.withType<Test>().configureEach {
    inputs
        .files(
            fileTree(rootDir) {
                include { element ->
                    val segments = element.relativePath.segments
                    // Keep walking directories; the excludes below still prune dot-folders and build output.
                    element.isDirectory ||
                        (
                            "src" in segments.dropLast(1) &&
                                element.name.substringAfterLast('.', "").lowercase(Locale.ROOT) in fontExtensions
                        )
                }
                include("**/src/*/assets/**/licenses/**", "core/ui/fonts.sha256")
                exclude("**/build/**", "**/.*/**")
            },
        ).withPathSensitivity(PathSensitivity.RELATIVE)
        .ignoreEmptyDirectories()
        .withPropertyName("fontManifestScan")
}

// ChannelMarkManifestTest scans every module's channel mark folders (Android merges assets across modules). Declare
// the manifest and the files it scans as inputs so a cached test result cannot hide a file that was added or changed.
tasks.withType<Test>().configureEach {
    inputs
        .files(
            fileTree(rootDir) {
                include("core/ui/channelmarks.sha256", "**/src/*/assets/channelmarks/**")
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
