// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * `@Inject` for a `[JVM]` module (MODULES.md, "Hilt in pure modules"): the `javax.inject` annotations and nothing
 * else, so the module stays free of Dagger and Hilt. `app` does the wiring. `[KMP]` modules don't apply this.
 */
class JvmInjectPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.withPlugin("java") {
                dependencies { add("implementation", libs.library("javax-inject")) }
            }
        }
}
