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

/** Fails if store SDK code leaves its flavor or Pro gets a second SKU ([FlavorIsolationRules]). */
abstract class FlavorIsolationCheck : DefaultTask() {
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
        val files = sources.files.sorted().associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }
        report.writeReportOrFail(
            FlavorIsolationRules.violations(modulePath.get(), files),
            "Store code left its flavor, or Pro has more than one SKU (A-REL-1, ADR-0023). Amazon's SDK belongs in " +
                ":app's src/amazon* source sets only, Google Play's nowhere yet, and the one SKU is `const val PRO_SKU` " +
                "in ProSku.kt:",
        )
    }
}
