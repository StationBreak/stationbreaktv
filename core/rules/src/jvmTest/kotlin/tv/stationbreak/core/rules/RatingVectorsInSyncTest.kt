// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The Kotlin copies of the rating and auto-seed vectors (commonTest) must equal the published files: no drift.
class RatingVectorsInSyncTest {
    @Test
    fun theCopiesEqualTheFiles() {
        assertTrue(RatingVectorFiles.files.isNotEmpty())
        for ((name, copy) in RatingVectorFiles.files) {
            val file = File(File(RulesFormatFiles.dir, "vectors"), name)
            assertTrue(file.isFile, "missing $file")
            assertEquals(file.readText(), copy, name)
        }
    }
}
