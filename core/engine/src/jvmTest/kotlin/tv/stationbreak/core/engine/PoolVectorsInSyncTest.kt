// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Kotlin copies of `pool.json`, `pool-v2.json`, `pool-v3.json` and `kid-hold-4.json` (commonTest) must equal the
 * published files.
 */
class PoolVectorsInSyncTest {
    private val dir: File =
        File(File(requireNotNull(System.getProperty("rulesFormat.dir")) { "rulesFormat.dir is not set" }), "vectors")

    @Test
    fun theCopiesEqualTheFiles() {
        for ((name, copy) in mapOf(
            "pool.json" to PoolVectorFiles.pool,
            "pool-v2.json" to PoolVectorFiles.poolV2,
            "pool-v3.json" to PoolVectorFiles.poolV3,
            "kid-hold-4.json" to KidHold4VectorFile.json,
        )) {
            val file = File(dir, name)
            assertTrue(file.isFile, "missing $file")
            assertEquals(file.readText(), copy, name)
        }
    }
}
