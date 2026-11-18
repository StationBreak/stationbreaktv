// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic.architecture

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Fails if a main source file of `:data:settings` uses `android.util.AtomicFile` ([AtomicFileRules]). */
abstract class AtomicFileCheck : DefaultTask() {
    @get:Input
    abstract val modulePath: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Internal
    abstract val moduleDir: DirectoryProperty

    /** The result, so unchanged sources are up to date. */
    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val root = moduleDir.get().asFile
        val violations =
            sources.files.sorted().flatMap { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                AtomicFileRules.violations(modulePath.get(), relative, file.readText())
            }
        report.writeReportOrFail(
            violations,
            "android.util.AtomicFile is used in :data:settings (#818, CLAUDE.md). On API 25 its .bak scheme can " +
                "leave the only copy truncated when a write fails; write <name>.new, sync, rename and sync the " +
                "directory as DiskRecordFile does:",
        )
    }
}
