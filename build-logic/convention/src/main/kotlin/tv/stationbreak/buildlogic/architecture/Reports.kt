// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic.architecture

import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty

/** Writes [lines] (or "OK") to the report, then fails with [header] and the lines as a bullet list if any. */
internal fun RegularFileProperty.writeReportOrFail(
    lines: List<String>,
    header: String,
) {
    get().asFile.writeText(lines.joinToString("\n", postfix = "\n").ifBlank { "OK\n" })
    if (lines.isNotEmpty()) {
        throw GradleException(header + "\n" + lines.joinToString("\n") { "  - $it" })
    }
}
