// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic.architecture

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files
import java.nio.file.Path

/** Fails if the merged R8 configuration of a minified variant holds a risky rule ([R8ConfigRules]). */
abstract class R8ConfigCheck : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val configuration: RegularFileProperty

    // The app's own rules file, as AGP prints it in the configuration: the only section that gets the app's allowances.
    @get:Input
    abstract val appRules: Property<String>

    // The rules files AGP gave R8 (see AndroidAppPlugin): the only files a library header may name. Directories stand for
    // the files inside them.
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    abstract val rulesGivenToR8: ConfigurableFileCollection

    @get:OutputFile
    abstract val report: RegularFileProperty

    private fun ruleFiles(): Set<Path> =
        rulesGivenToR8.files
            .flatMap { file ->
                if (file.isDirectory) {
                    Files.walk(file.toPath()).use { walk ->
                        walk.filter { Files.isRegularFile(it) }.toList()
                    }
                } else {
                    listOf(file.toPath())
                }
            }.map { it.toAbsolutePath().normalize() }
            .toSet()

    @TaskAction
    fun check() {
        val violations = R8ConfigRules.violations(configuration.get().asFile.readText(), appRules.get(), ruleFiles())
        report.writeReportOrFail(
            violations.map { "${it.rule}  (from ${it.source})" },
            "The merged R8 configuration holds a rule that weakens the release build (#437). Read it; if it is " +
                "acceptable, add its source to R8ConfigRules.allowed with the reason. Found:",
        )
    }
}
