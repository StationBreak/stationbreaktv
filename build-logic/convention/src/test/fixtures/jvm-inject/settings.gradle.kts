// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore

// TestKit fixture: a plain Java library with the jvm-inject plugin. The catalog is declared here, not read from the
// repository's, so the test needs no network and checks only what the plugin declares.
rootProject.name = "jvm-inject"

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            library("javax-inject", "javax.inject:javax.inject:1")
        }
    }
}
