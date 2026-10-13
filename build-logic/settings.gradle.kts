// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore

pluginManagement {
    // The same repositories, in the same order, as the root build's. A plugin marker can differ between Maven
    // Central and the Plugin Portal (Spotless's does), and the dependency verification file holds one checksum
    // per artifact (ADR-0013): both builds must resolve it from the same place.
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    // The same repositories as the root build's; the plugin artifacts it compiles against are all on these.
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs {
        // One catalog for the whole repository: build-logic reads the root's.
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"

include(":convention")
