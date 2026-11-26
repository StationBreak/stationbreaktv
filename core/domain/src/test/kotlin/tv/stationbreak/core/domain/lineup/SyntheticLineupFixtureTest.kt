// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
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

    // The version strings of the fixture move with each hold or catalog bump, its days only when a lineup moves: a
    // hold change (ADR-0029) that leaves the synthetic library's days alone must leave these bytes alone too.
    @Test
    fun theDaysOfTheCommittedFixtureAreByteIdenticalToWhatTheAppBuildsNow() {
        if (System.getProperty("updateFixtures") == "true") return

        fun days(text: String): String {
            val days = Json.parseToJsonElement(text).jsonObject.getValue("days")
            return FixtureJson.write(JsonObject(mapOf("days" to days)))
        }
        assertEquals(days(SyntheticLineupFixture.build()), days(file.readText()), "days differ under the current holds")
    }

    @Test
    fun buildingItTwiceGivesTheSameBytes() {
        assertEquals(SyntheticLineupFixture.build(), SyntheticLineupFixture.build())
    }
}
