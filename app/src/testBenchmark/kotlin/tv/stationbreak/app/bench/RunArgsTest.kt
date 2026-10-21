// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:Suppress("MagicNumber", "LongParameterList") // Scripted times and sample values; detekt skips only src/test.

package tv.stationbreak.app.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import tv.stationbreak.core.playback.TrackOverride

class RunArgsTest {
    @Test
    fun argsParseIntoOrderedPlays() {
        val args =
            RunArgs.parse(
                items = arrayOf("a", "b", "c"),
                startsMs = longArrayOf(0, 30_000, 5),
                audio = intArrayOf(1, 2, 3),
                subtitles = intArrayOf(4, 5, 6),
                override = true,
                section = "audio",
                runId = "r1",
            )
        assertEquals(listOf("a", "b", "c"), args.plays.map { it.item })
        assertEquals(listOf(0L, 30_000L, 5L), args.plays.map { it.startMs })
        assertEquals(TrackOverride(2, 5), args.plays[1].override)
        assertEquals("audio", args.section)
        assertEquals("r1", args.runId)
    }

    @Test
    fun mismatchedArrayLengthsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            RunArgs.parse(arrayOf("a", "b"), longArrayOf(0), null, null, false, "s", "r")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RunArgs.parse(arrayOf("a"), null, intArrayOf(1, 2), null, true, "s", "r")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RunArgs.parse(null, null, null, null, false, "s", "r")
        }
    }

    @Test
    fun minusOneMeansNoOverride() {
        val withOverride = RunArgs.parse(arrayOf("a"), null, intArrayOf(-1), intArrayOf(-1), true, "s", "r")
        // Audio -1: the server's default. Subtitles -1 with the override on: subtitles off.
        assertEquals(TrackOverride(null, null), withOverride.plays.single().override)
        // Without the override flag the arrays are ignored: the server's own choice.
        val without = RunArgs.parse(arrayOf("a"), null, intArrayOf(2), intArrayOf(3), false, "s", "r")
        assertNull(without.plays.single().override)
    }
}
