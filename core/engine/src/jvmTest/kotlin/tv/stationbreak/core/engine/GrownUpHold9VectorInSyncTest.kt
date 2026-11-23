// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The published `grown-up-hold-9.json` holds the code to every row, and the commonTest copy equals it. */
class GrownUpHold9VectorInSyncTest {
    private val file: File =
        File(
            File(requireNotNull(System.getProperty("rulesFormat.dir")) { "rulesFormat.dir is not set" }),
            "vectors/grown-up-hold-9.json",
        )

    @Test
    fun thePublishedFileHoldsEveryRow() {
        assertTrue(file.isFile, "missing $file")
        KidHold6VectorCheck.run(file.readText(), minRows = 1, explicitIsAdult = false)
    }

    @Test
    fun theCopyEqualsTheFile() {
        assertEquals(file.readText().trimEnd('\n'), GrownUpHold9VectorFile.json)
    }
}
