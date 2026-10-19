// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Kotlin copy of `pool.json` (commonTest) must equal the published file, so it cannot drift. */
class PoolVectorsInSyncTest {
    private val dir: File =
        File(File(requireNotNull(System.getProperty("rulesFormat.dir")) { "rulesFormat.dir is not set" }), "vectors")

    @Test
    fun theCopyEqualsTheFile() {
        val file = File(dir, "pool.json")
        assertTrue(file.isFile, "missing $file")
        assertEquals(file.readText(), PoolVectorFiles.pool, "pool.json")
    }
}
