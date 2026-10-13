// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic.architecture

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Fails if any of the module's manifests uses `tools:overrideLibrary` ([ManifestRules]). */
@CacheableTask
abstract class ManifestCheck : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val manifests: ConfigurableFileCollection

    /** The result, so unchanged manifests are up to date. */
    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val offending = manifests.files.filter(ManifestRules::overridesLibraryMinSdk).sorted()
        report.get().asFile.writeText(offending.joinToString("\n", postfix = "\n").ifBlank { "OK\n" })
        if (offending.isNotEmpty()) {
            throw GradleException(
                "tools:overrideLibrary is not allowed (R-PLAT-1, docs/MODULES.md): it lets a library that needs " +
                    "a newer minSdk than 25 crash on API 25 devices. Use a library version that supports minSdk " +
                    "25 instead. Found in:\n" + offending.joinToString("\n") { "  - $it" },
            )
        }
    }
}
