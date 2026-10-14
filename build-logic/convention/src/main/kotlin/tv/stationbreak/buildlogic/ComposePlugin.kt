// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** Compose for TV, for an Android module that already applies android-app or android-lib. */
class ComposePlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

            dependencies {
                val bom = platform(libs.library("androidx-compose-bom"))
                add("implementation", bom)
                add("androidTestImplementation", bom)
                // Declared directly, not left to tv-material's transitive versions: modules use them directly.
                add("implementation", libs.library("androidx-compose-ui"))
                add("implementation", libs.library("androidx-compose-foundation"))
                add("implementation", libs.library("androidx-tv-material"))
            }
        }
}
