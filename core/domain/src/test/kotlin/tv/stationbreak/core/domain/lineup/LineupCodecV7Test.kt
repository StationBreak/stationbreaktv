// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import tv.stationbreak.core.domain.lineup.repair.TodayRepairs
import tv.stationbreak.core.domain.lineup.repair.repairOf
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.ShelfReason
import tv.stationbreak.core.engine.lineup.ViewerChoice
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Lineup file version 7: the meta field `plannedWithoutCollections` (a first choice made without the collections). */
class LineupCodecV7Test {
    private val unfinalised = Lineup(SERVER, USER, 1, 0, finalised = false, channels = emptyList())
    private val gaveUp = RefreshMeta(plannedGeneration = 3, plannedWithoutCollections = true)

    private fun tree(bytes: ByteArray): JsonObject = Json.parseToJsonElement(bytes.decodeToString()).jsonObject

    private fun versionOf(bytes: ByteArray) = (tree(bytes)["version"] as JsonPrimitive).content.toInt()

    // [bytes] with its version and its meta's `plannedWithoutCollections` replaced (null: the key removed).
    private fun rewritten(
        bytes: ByteArray,
        version: Int,
        flag: JsonElement?,
    ): ByteArray {
        val root = tree(bytes)
        val meta = root.getValue("meta").jsonObject - KEY
        val newMeta = JsonObject(if (flag == null) meta else meta + (KEY to flag))
        val newRoot = root + ("version" to JsonPrimitive(version)) + ("meta" to newMeta)
        return JsonObject(newRoot).toString().encodeToByteArray()
    }

    @Test
    fun aLineupThatGaveUpRoundTripsAtVersion7() {
        val bytes = LineupCodec.encode(unfinalised, gaveUp)

        assertEquals(7, versionOf(bytes))
        assertEquals(JsonPrimitive(true), tree(bytes).getValue("meta").jsonObject[KEY])
        val read = assertIs<LineupCodec.Decoded.Loaded>(LineupCodec.decode(bytes))
        assertEquals(7, read.fileVersion)
        assertEquals(gaveUp, read.meta)
        assertTrue(firstChoiceMade(read.lineup, read.meta))
    }

    @Test
    fun aLineupThatNeverGaveUpIsWrittenAsBefore() {
        val bytes = LineupCodec.encode(unfinalised, gaveUp.copy(plannedWithoutCollections = false))

        assertEquals(3, versionOf(bytes))
        assertNull(tree(bytes).getValue("meta").jsonObject[KEY], "no key")
    }

    @Test
    fun anOldFileWithoutTheFieldReadsAsNotGivenUp() {
        val old = rewritten(LineupCodec.encode(unfinalised, gaveUp), 6, flag = null)

        val read = assertIs<LineupCodec.Decoded.Loaded>(LineupCodec.decode(old))
        assertEquals(6, read.fileVersion)
        assertEquals(gaveUp.copy(plannedWithoutCollections = false), read.meta)
        assertFalse(firstChoiceMade(read.lineup, read.meta), "an old unfinalised lineup with no pass still waits")
    }

    @Test
    fun theFieldBelowVersion7OrNotTrueIsUnreadable() {
        val seven = LineupCodec.encode(unfinalised, gaveUp)
        for (version in 1..6) {
            for (flag in listOf(JsonPrimitive(true), JsonNull)) {
                val read = LineupCodec.decode(rewritten(seven, version, flag))
                assertEquals(LineupCodec.Decoded.Unreadable, read, "version $version, $flag")
            }
        }
        for (flag in listOf(JsonPrimitive(false), JsonNull, JsonPrimitive("true"), JsonPrimitive(1))) {
            assertEquals(LineupCodec.Decoded.Unreadable, LineupCodec.decode(rewritten(seven, 7, flag)), "$flag")
        }
    }

    @Test
    fun version7IsTheNewestAndOnlyAGiveUpNeedsIt() {
        assertEquals(7, LineupCodec.VERSION)
        val other = lineupOf(starter("laughs", 110, ChannelState.On, rules("Laughs", Source.Genre("Comedy"))))
        assertEquals(3, versionOf(LineupCodec.encode(other)))
    }

    @Test
    fun theFlagIsNotWrittenOnceAPassIsTakenInOrTheLineupIsFinalised() {
        val passTaken = LineupCodec.encode(unfinalised, gaveUp.copy(collectionPassActedOn = 1L))
        val finalised = LineupCodec.encode(unfinalised.copy(finalised = true), gaveUp)

        for (bytes in listOf(passTaken, finalised)) {
            assertEquals(3, versionOf(bytes))
            assertNull(tree(bytes).getValue("meta").jsonObject[KEY], "no key")
            val read = assertIs<LineupCodec.Decoded.Loaded>(LineupCodec.decode(bytes))
            assertFalse(read.meta.plannedWithoutCollections)
            assertTrue(firstChoiceMade(read.lineup, read.meta), "still has its first choice")
        }
    }

    @Test
    fun aVersion7FileWithEveryLaterFeatureRoundTrips() {
        val crime = rules("Whodunit", Source.Genre("Crime"))
        val noNumber = starter("laughs", 110, ChannelState.Shelf, rules("Laughs", Source.Genre("Comedy")))
        val added = starter("whodunit", 210, ChannelState.On, crime).copy(viewerChoice = ViewerChoice.Added)
        val lineup =
            lineupOf(noNumber.copy(shelfReason = ShelfReason.NoNumber), added).copy(finalised = false)
        val repairs = TodayRepairs(selection = listOf(repairOf()))

        val bytes = LineupCodec.encode(lineup, gaveUp, repairs)

        assertEquals(7, versionOf(bytes))
        val read = assertIs<LineupCodec.Decoded.Loaded>(LineupCodec.decode(bytes))
        assertEquals(7, read.fileVersion)
        assertEquals(lineup, read.lineup)
        assertEquals(gaveUp, read.meta)
        assertEquals(repairs, read.repairs)
    }
}

private const val KEY = "plannedWithoutCollections"
