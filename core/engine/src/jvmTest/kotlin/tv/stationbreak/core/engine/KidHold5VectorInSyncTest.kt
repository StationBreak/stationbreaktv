// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The published `kid-hold-5.json` holds the code to every row, and the commonTest copy equals it. */
class KidHold5VectorInSyncTest {
    private val file: File =
        File(
            File(requireNotNull(System.getProperty("rulesFormat.dir")) { "rulesFormat.dir is not set" }),
            "vectors/kid-hold-5.json",
        )

    @Test
    fun thePublishedFileHoldsEveryRow() {
        assertTrue(file.isFile, "missing $file")
        KidHold5VectorCheck.run(file.readText())
    }

    @Test
    fun theCopyEqualsTheFile() {
        assertEquals(file.readText().trimEnd('\n'), KidHold5VectorFile.json)
    }
}
