// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model.ordering

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Kotlin copies of the vector files (commonTest) must equal the published files, so they cannot drift. */
class VectorFilesInSyncTest {
    @Test
    fun theCopiesEqualTheFiles() {
        assertTrue(VectorFiles.files.isNotEmpty())
        for ((name, copy) in VectorFiles.files) {
            val file = File(PublishedVectors.dir, name)
            assertTrue(file.isFile, "missing $file")
            assertEquals(file.readText(), copy, name)
        }
    }
}
