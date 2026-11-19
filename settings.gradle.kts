// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore

pluginManagement {
    // Convention plugins (tv.stationbreak.*): shared module setup, one place to change it.
    includeBuild("build-logic")
    // build-logic/settings.gradle.kts lists the same repositories in the same order (RepositoryListsTest, #182).
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    // Kept in step with build-logic/settings.gradle.kts (RepositoryListsTest, #182).
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "stationbreaktv"

// The day-one set (MODULES.md). Every other module is created by the first story that needs it.
include(":app")
include(":core:model", ":core:rules", ":core:engine", ":core:scheduler", ":core:testing") // [KMP]
include(":core:domain", ":data:jellyfin") // [JVM]
include(":core:ui", ":core:playback", ":data:settings", ":data:library") // [Android]
include(":benchmark") // [Android test] Baseline Profile generation (#416); nothing ships from it
include(":security-minified-test-hilt", ":security-minified-test-trust") // [Android test] security tests on minified app (#436)
include(":feature:onboarding", ":feature:live", ":feature:guide", ":feature:channels", ":feature:settings") // [Android]
