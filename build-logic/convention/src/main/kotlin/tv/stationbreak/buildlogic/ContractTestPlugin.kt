// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.kotlin
import org.gradle.kotlin.dsl.register
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * The `contractTest` source set and task for a JVM module (F0.3 D7): tests that need Docker, which `check` compiles
 * (so they can't rot) but never runs. They run on demand, or in CI's `jellyfin-contract` job, against the image named
 * by the `JELLYFIN_IMAGE` environment variable. The image travels as an environment variable, not a Gradle property or
 * system property, so it doesn't land in a report. Apply it after the module's Kotlin plugin.
 */
class ContractTestPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("java")
            val java = extensions.getByType(JavaPluginExtension::class.java)
            val main = java.sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME)
            val contract = java.sourceSets.create(SOURCE_SET)

            // The module's own code and everything it compiles against, as `test` has.
            configurations.named(contract.implementationConfigurationName) {
                extendsFrom(configurations.getByName(main.implementationConfigurationName))
            }
            configurations.named(contract.runtimeOnlyConfigurationName) {
                extendsFrom(configurations.getByName(main.runtimeOnlyConfigurationName))
            }
            dependencies { add(contract.implementationConfigurationName, main.output) }

            val contractTest =
                tasks.register<Test>(SOURCE_SET) {
                    description = "Runs the contract tests against a real server in Docker. Not part of check."
                    group = LifecycleBasePlugin.VERIFICATION_GROUP
                    testClassesDirs = contract.output.classesDirs
                    classpath = contract.runtimeClasspath
                    useJUnitPlatform()
                    // A real server is an input nothing here tracks, so a rerun is never "up to date".
                    outputs.upToDateWhen { false }
                    providers.environmentVariable(IMAGE_VARIABLE).orNull?.let { environment(IMAGE_VARIABLE, it) }
                }
            // Compiled by `check`, never run by it.
            checkDependsOn(contract.classesTaskName)

            pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
                // `internal` of the module stays reachable, as it is from `test`.
                extensions.getByType(KotlinJvmProjectExtension::class.java).target.compilations.apply {
                    getByName(SOURCE_SET).associateWith(getByName(SourceSet.MAIN_SOURCE_SET_NAME))
                }
                dependencies { add(contract.implementationConfigurationName, kotlin("test")) }
                contractTest.configure { useJUnitPlatform() }
            }
        }

    private companion object {
        const val SOURCE_SET = "contractTest"
        const val IMAGE_VARIABLE = "JELLYFIN_IMAGE"
    }
}
