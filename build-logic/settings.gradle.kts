// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore

pluginManagement {
    repositories {
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
