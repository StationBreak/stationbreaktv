// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic

import com.diffplug.gradle.spotless.SpotlessExtension
import dev.detekt.gradle.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Spotless (ktlint plus the SPDX header) and detekt. Every other convention plugin applies this one.
 *
 * Which detekt tasks `check` runs is left to the module-kind plugins: detekt 2's plain `detekt` task skips the
 * rules that need type resolution (LongParameterList among them), and the tasks that run them are named per
 * platform.
 */
class QualityPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("com.diffplug.spotless")
            pluginManager.apply("dev.detekt")

            val ktlintVersion = libs.version("ktlint")
            extensions.configure<SpotlessExtension> {
                kotlin {
                    target("src/**/*.kt")
                    ktlint(ktlintVersion)
                    // The default delimiter puts the header above `package`, `import` or `@file`.
                    licenseHeader(SPDX_HEADER)
                }
                kotlinGradle {
                    target("*.gradle.kts")
                    ktlint(ktlintVersion)
                    // Above the first line that isn't a `//` comment.
                    licenseHeader(SPDX_HEADER, "^(?!//).*$")
                }
            }

            extensions.configure<DetektExtension> {
                buildUponDefaultConfig.set(true)
            }

            // Spotless hooks this into `check` itself; listing it keeps the gate if a plugin update stops doing so.
            checkDependsOn("spotlessCheck")
        }
}
