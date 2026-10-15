// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore

// TestKit fixture: the catalog is declared here, not read from the repository's, so the test needs no network.
rootProject.name = "hilt"

include(":app", ":feature", ":kmp")

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            library("hilt-android", "com.google.dagger:hilt-android:9.9")
            library("hilt-compiler", "com.google.dagger:hilt-compiler:9.9")
            library("hilt-android-testing", "com.google.dagger:hilt-android-testing:9.9")
        }
    }
}
