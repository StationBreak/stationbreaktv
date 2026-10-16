// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The Kotlin copies of the examples (commonTest) must equal the published files, so they cannot drift (ADR-0004).
class ExampleFilesInSyncTest {
    @Test
    fun validCopiesEqualTheFiles() {
        assertTrue(RulesFormatFiles.valid.isNotEmpty(), "no files in examples/")
        assertEquals(RulesFormatFiles.valid, ExampleFiles.valid)
    }

    @Test
    fun invalidCopiesEqualTheFiles() {
        assertTrue(RulesFormatFiles.invalid.isNotEmpty(), "no files in examples/invalid/")
        assertEquals(RulesFormatFiles.invalid, ExampleFiles.invalid)
    }
}
