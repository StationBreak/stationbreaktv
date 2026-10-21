// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:Suppress("MagicNumber") // Sample sizes; detekt skips only src/test.

package tv.stationbreak.app.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResultsFileTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun aFileOverTheLimitIsEmptiedAtTheStartOfARun() {
        val file = folder.newFile("results.jsonl")
        file.writeText("x".repeat(11))
        ResultsFile.trimIfLarge(file, maxBytes = 10)
        assertFalse(file.exists() && file.length() > 0)
    }

    @Test
    fun aFileWithinTheLimitIsKept() {
        val file = folder.newFile("results.jsonl")
        file.writeText("x".repeat(10))
        ResultsFile.trimIfLarge(file, maxBytes = 10)
        assertTrue(file.exists())
        assertEquals(10L, file.length())
    }

    @Test
    fun aMissingFileIsFine() {
        ResultsFile.trimIfLarge(folder.root.resolve("none.jsonl"), maxBytes = 10)
    }
}
