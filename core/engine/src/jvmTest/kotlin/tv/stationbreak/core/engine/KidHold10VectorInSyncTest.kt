// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The published `kid-hold-10.json` holds the code to every row, and the commonTest copy equals it. */
class KidHold10VectorInSyncTest {
    private val file: File =
        File(
            File(requireNotNull(System.getProperty("rulesFormat.dir")) { "rulesFormat.dir is not set" }),
            "vectors/kid-hold-10.json",
        )

    @Test
    fun thePublishedFileHoldsEveryRow() {
        assertTrue(file.isFile, "missing $file")
        KidHold6VectorCheck.run(file.readText(), minRows = 1_000, explicitIsAdult = false)
    }

    @Test
    fun theCopyEqualsTheFile() {
        assertEquals(file.readText().trimEnd('\n'), KidHold10VectorFile.json)
    }
}
