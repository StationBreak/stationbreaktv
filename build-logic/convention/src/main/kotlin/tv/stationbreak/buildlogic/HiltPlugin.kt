// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Hilt for an Android module (MODULES.md, "Hilt in pure modules"): the Gradle plugin, the KSP processor and the
 * runtime, plus the test support for instrumented tests. Only `app` applies it for now. `[KMP]` and `[JVM]` modules
 * never do; they carry the `javax.inject` annotations alone.
 */
class HiltPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            // KSP, not kapt: AGP 9's built-in Kotlin doesn't support kapt.
            pluginManager.apply("com.google.devtools.ksp")
            pluginManager.apply("com.google.dagger.hilt.android")

            dependencies {
                add("implementation", libs.library("hilt-android"))
                add("ksp", libs.library("hilt-compiler"))
                add("androidTestImplementation", libs.library("hilt-android-testing"))
                add("kspAndroidTest", libs.library("hilt-compiler"))
            }
        }
}
