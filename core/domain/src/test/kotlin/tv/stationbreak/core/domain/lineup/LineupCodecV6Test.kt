// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import tv.stationbreak.core.domain.lineup.repair.TodayRepairs
import tv.stationbreak.core.domain.lineup.repair.repairOf
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.ShelfReason
import tv.stationbreak.core.engine.lineup.ViewerChoice
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Lineup file version 6 (#798): the `noNumber` shelf reason. */
class LineupCodecV6Test {
    private val comedy = rules("Laughs", Source.Genre("Comedy"))
    private val crime = rules("Whodunit", Source.Genre("Crime"))

    private val laughs = starter("laughs", 110, ChannelState.Shelf, comedy)

    private fun shelved(reason: ShelfReason) = laughs.copy(shelfReason = reason)

    private val plain = starter("whodunit", 210, ChannelState.On, crime)

    private fun tree(bytes: ByteArray): JsonObject = Json.parseToJsonElement(bytes.decodeToString()).jsonObject

    private fun versionOf(bytes: ByteArray) = (tree(bytes)["version"] as JsonPrimitive).content.toInt()

    private fun withVersion(
        bytes: ByteArray,
        version: Int,
    ) = JsonObject(tree(bytes) + ("version" to JsonPrimitive(version))).toString().encodeToByteArray()

    @Test
    fun noNumberRoundTripsAtVersion6() {
        val lineup = lineupOf(shelved(ShelfReason.NoNumber), plain)
        val bytes = LineupCodec.encode(lineup)

        assertEquals(6, versionOf(bytes))
        assertEquals(JsonPrimitive("noNumber"), tree(bytes).getValue("channels").jsonArray[0].jsonObject["shelfReason"])
        val read = assertIs<LineupCodec.Decoded.Loaded>(LineupCodec.decode(bytes))
        assertEquals(6, read.fileVersion)
        assertEquals(lineup, read.lineup)
    }

    @Test
    fun aLineupWithoutNoNumberIsStillWrittenAtTheLowestVersion() {
        val repairs = TodayRepairs(selection = listOf(repairOf()))
        val added = plain.copy(viewerChoice = ViewerChoice.Added)
        assertEquals(3, versionOf(LineupCodec.encode(lineupOf(shelved(ShelfReason.BelowCap), plain))))
        assertEquals(4, versionOf(LineupCodec.encode(lineupOf(plain), repairs = repairs)))
        assertEquals(5, versionOf(LineupCodec.encode(lineupOf(added))))
        // The highest any feature needs: NoNumber with the others still holds them all.
        val all = LineupCodec.encode(lineupOf(shelved(ShelfReason.NoNumber), added), repairs = repairs)
        assertEquals(6, versionOf(all))
    }

    @Test
    fun noNumberInAFileBelowVersion6IsUnreadable() {
        val six = LineupCodec.encode(lineupOf(shelved(ShelfReason.NoNumber), plain))
        for (version in 3..5) {
            val read = LineupCodec.decode(withVersion(six, version))
            assertEquals(LineupCodec.Decoded.Unreadable, read, "version $version")
        }
    }

    @Test
    fun theOldBelowCapStillReads() {
        val lineup = lineupOf(shelved(ShelfReason.BelowCap), plain)
        val read = assertIs<LineupCodec.Decoded.Loaded>(LineupCodec.decode(LineupCodec.encode(lineup)))
        assertEquals(lineup, read.lineup)
        assertEquals(3, read.fileVersion)
    }

    @Test
    fun version7IsNewer() {
        val six = LineupCodec.encode(lineupOf(shelved(ShelfReason.NoNumber)))
        assertEquals(LineupCodec.Decoded.NewerVersion(7), LineupCodec.decode(withVersion(six, 7)))
        assertEquals(6, LineupCodec.VERSION)
    }
}
