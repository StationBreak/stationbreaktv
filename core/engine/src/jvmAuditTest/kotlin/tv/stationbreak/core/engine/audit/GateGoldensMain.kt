// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.audit

import java.io.File

/**
 * `lineupGateGoldens`: writes the generated fixtures ([GateFixtureSources]) as `audit-snapshot` files, then the
 * gate's goldens over every fixture in the folder. Run it after reading the diff of a change that moves them.
 */
internal object GateGoldensMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val (fixtures, golden) = args
        for ((key, input) in GateFixtureSources.all()) {
            File(fixtures, "$key.json").also { it.parentFile?.mkdirs() }.writeText(SnapshotWriter.write(input))
        }
        GateMain.main(arrayOf("--fixtures", fixtures, "--golden", golden, "--update-goldens"))
    }
}
