// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore

plugins {
    id("stationbreak.android-lib")
    id("stationbreak.compose")
    // @HiltViewModel (MODULES.md, "Hilt in pure modules"): Android feature modules may apply Hilt.
    id("stationbreak.hilt")
}

android {
    namespace = "tv.stationbreak.feature.settings"
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

    // They pin the androidx.test versions that Hilt's test artifact would otherwise resolve to an older one, as the
    // channel screen's do; the screen's instrumented test uses them.
    androidTestImplementation(testFixtures(project(":core:domain")))
    androidTestImplementation(libs.kotlinx.coroutines.core)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.compose.ui.test.manifest)
}
