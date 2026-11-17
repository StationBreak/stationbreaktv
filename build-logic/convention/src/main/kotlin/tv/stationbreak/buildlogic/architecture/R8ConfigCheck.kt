// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic.architecture

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Fails if the merged R8 configuration of a minified variant holds a risky rule ([R8ConfigRules]). */
abstract class R8ConfigCheck : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val configuration: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val violations = R8ConfigRules.violations(configuration.get().asFile.readText())
        report.writeReportOrFail(
            violations.map { "${it.rule}  (from ${it.source})" },
            "The merged R8 configuration holds a rule that weakens the release build (#437). Read it; if it is " +
                "acceptable, add its source to R8ConfigRules.allowed with the reason. Found:",
        )
    }
}
