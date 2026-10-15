// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic.architecture

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.kotlin.dsl.register
import org.gradle.language.base.plugins.LifecycleBasePlugin

/** Registers `checkArchitecture` and adds it to `check`. Applied by every module-kind convention plugin. */
class ArchitecturePlugin : Plugin<Project> {
    override fun apply(target: Project): Unit =
        with(target) {
            pluginManager.apply(LifecycleBasePlugin::class.java)

            val projectPath = path
            val configurations = configurations
            val checkArchitecture =
                tasks.register<ArchitectureCheck>("checkArchitecture") {
                    group = LifecycleBasePlugin.VERIFICATION_GROUP
                    description = "Checks this module's declared dependencies against docs/MODULES.md."
                    modulePath.set(projectPath)
                    // Read lazily, after the build file has declared its dependencies; with the configuration
                    // cache the value is computed once and stored, so the action never touches the project.
                    edges.set(provider { declaredEdges(configurations) })
                    report.set(layout.buildDirectory.file("reports/architecture/violations.txt"))
                }
            tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) { dependsOn(checkArchitecture) }

            val checkTrustSurface =
                tasks.register<TrustSurfaceCheck>("checkTrustSurface") {
                    group = LifecycleBasePlugin.VERIFICATION_GROUP
                    description = "Fails if the TLS trust API is named outside :data:jellyfin's trust package."
                    modulePath.set(projectPath)
                    moduleDir.set(layout.projectDirectory)
                    sources.from(fileTree("src") { include("**/*.kt", "**/*.java") })
                    report.set(layout.buildDirectory.file("reports/architecture/trust-surface.txt"))
                }
            tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) { dependsOn(checkTrustSurface) }
        }

    /**
     * Direct declarations only: each configuration's own dependencies, not the ones it inherits.
     *
     * Two known gaps, acceptable because this runs late (from the task's `provider {}`, after the build file):
     * - `configurations.flatMap` realizes every configuration, including lazily registered ones.
     * - Dependencies added later through `withDependencies` (at resolution time) aren't seen.
     */
    private fun declaredEdges(configurations: ConfigurationContainer): List<DeclaredEdge> =
        configurations
            .flatMap { configuration ->
                configuration.dependencies.mapNotNull { dependency ->
                    when (dependency) {
                        is ProjectDependency -> {
                            // testFixtures(project(…)) selects the "test-fixtures" feature; the selector types are
                            // internal in Gradle 9.8, so match the public description.
                            val testFixtures = dependency.capabilitySelectors.any { "test-fixtures" in it.displayName }
                            DeclaredEdge(configuration.name, Dependency.OnProject(dependency.path, testFixtures))
                        }

                        is ExternalModuleDependency -> {
                            dependency.group?.let { DeclaredEdge(configuration.name, Dependency.OnArtifact(it, dependency.name)) }
                        }

                        else -> {
                            null
                        }
                    }
                }
            }.distinct()
}
