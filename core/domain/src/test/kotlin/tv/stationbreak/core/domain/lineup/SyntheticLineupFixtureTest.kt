// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `docs/rules-format/fixtures/synthetic-lineup.json` must be exactly what the app builds from the synthetic library
 * (see [SyntheticLineupFixture]). With `-PupdateFixtures=true` the test writes the file instead of checking it:
 *
 * ```
 * ./gradlew :core:domain:test --tests '*SyntheticLineupFixtureTest' -PupdateFixtures=true
 * ```
 */
class SyntheticLineupFixtureTest {
    private val file: File =
        File(
            File(requireNotNull(System.getProperty("rulesFormat.dir")) { "rulesFormat.dir is not set" }),
            "fixtures/synthetic-lineup.json",
        )

    @Test
    fun theCommittedFixtureIsWhatTheAppBuilds() {
        val built = SyntheticLineupFixture.build()
        if (System.getProperty("updateFixtures") == "true") {
            file.parentFile.mkdirs()
            file.writeBytes(built.encodeToByteArray())
            return
        }
        assertTrue(file.isFile, "missing $file: write it with -PupdateFixtures=true")
        val committed = file.readBytes()
        val expected = built.encodeToByteArray()
        if (!committed.contentEquals(expected)) {
            val have = committed.decodeToString().lines()
            val want = built.lines()
            val line = have.indices.firstOrNull { have[it] != want.getOrNull(it) } ?: have.size
            assertEquals(
                want.getOrNull(line),
                have.getOrNull(line),
                "${file.name} differs from what the app builds, first at line ${line + 1} " +
                    "(regenerate with -PupdateFixtures=true and review the diff)",
            )
        }
    }

    @Test
    fun buildingItTwiceGivesTheSameBytes() {
        assertEquals(SyntheticLineupFixture.build(), SyntheticLineupFixture.build())
    }
}
