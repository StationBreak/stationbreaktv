// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic.architecture

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Fails if this module's declared dependencies break MODULES.md's rules. The edges are collected at configuration
 * time (see [ArchitecturePlugin]); the action only reads its inputs.
 */
abstract class ArchitectureCheck : DefaultTask() {
    @get:Input
    abstract val modulePath: Property<String>

    @get:Input
    abstract val edges: ListProperty<DeclaredEdge>

    /** The result, so an unchanged module is up to date. */
    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val violations = ArchitectureRules.violations(modulePath.get(), edges.get())
        report.writeReportOrFail(violations.map { it.message }, "Module dependency rules broken (docs/MODULES.md):")
    }
}
