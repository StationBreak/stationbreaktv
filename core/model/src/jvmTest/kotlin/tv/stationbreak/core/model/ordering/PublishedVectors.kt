// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model.ordering

import java.io.File

/** Reads `docs/rules-format/vectors/` (the `rulesFormat.dir` system property is set in build.gradle.kts). */
internal object PublishedVectors {
    val dir: File =
        File(File(requireNotNull(System.getProperty("rulesFormat.dir")) { "rulesFormat.dir is not set" }), "vectors")

    fun read(name: String): String = File(dir, name).also { check(it.isFile) { "missing $it" } }.readText()
}
