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

/** Fails if a source file of this module names the TLS trust API where [TrustSurfaceRules] does not allow it. */
abstract class TrustSurfaceCheck : DefaultTask() {
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
                TrustSurfaceRules.violations(modulePath.get(), relative, file.readText())
            }
        report.writeReportOrFail(
            violations,
            "The TLS trust API is used where it is not allowed (docs/MODULES.md, spec 05 Unit 3). It lives only in " +
                "the trust package of :data:jellyfin; trust-all patterns and CertificatePinner are banned everywhere:",
        )
    }
}
