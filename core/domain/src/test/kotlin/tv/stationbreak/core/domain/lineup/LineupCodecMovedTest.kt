// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.MovedNotice
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/** The pending notices of channels the band-floor migration moved (#1097): file version 8, an ID and two numbers. */
class LineupCodecMovedTest {
    private val laughs = ChannelId("starter:laughs")
    private val base: Lineup =
        lineupOf(starter("laughs", 110, ChannelState.On, rules("Laughs", Source.Genre("Comedy"))))

    private fun loaded(bytes: ByteArray) = assertIs<LineupCodec.Decoded.Loaded>(LineupCodec.decode(bytes))

    @Test
    fun aNoticeRoundTripsAsVersionEightWithNoName() {
        val noted = base.copy(movedNotices = listOf(MovedNotice(laughs, 50, 110)))
        val bytes = LineupCodec.encode(noted)
        val read = loaded(bytes)
        assertEquals(noted.movedNotices, read.lineup.movedNotices)
        assertEquals(LineupCodec.MOVED_VERSION, read.fileVersion)
        val moved = Json.parseToJsonElement(bytes.decodeToString()).jsonObject.getValue("moved")
        assertEquals("""[{"id":"starter:laughs","from":50,"to":110}]""", moved.toString())
    }

    @Test
    fun noNoticeLeavesTheFileAsItWas() {
        val bytes = LineupCodec.encode(base)
        assertEquals(LineupCodec.BASE_VERSION, loaded(bytes).fileVersion)
        assertFalse("moved" in Json.parseToJsonElement(bytes.decodeToString()).jsonObject)
    }

    @Test
    fun aNoticeForAChannelThatIsGoneIsDropped() {
        val noted = base.copy(movedNotices = listOf(MovedNotice(laughs, 50, 110)))
        val tree = Json.parseToJsonElement(LineupCodec.encode(noted).decodeToString()).jsonObject
        val other =
            JsonArray(
                listOf(
                    JsonObject(
                        mapOf(
                            "id" to JsonPrimitive("custom:gone"),
                            "from" to JsonPrimitive(5),
                            "to" to JsonPrimitive(600),
                        ),
                    ),
                ),
            )
        val gone = JsonObject(tree + ("moved" to other))
        assertEquals(emptyList(), loaded(gone.toString().encodeToByteArray()).lineup.movedNotices)
    }

    @Test
    fun aMovedListInAnOlderFileIsUnreadable() {
        val noted = base.copy(movedNotices = listOf(MovedNotice(laughs, 50, 110)))
        val tree = Json.parseToJsonElement(LineupCodec.encode(noted).decodeToString()).jsonObject
        val older = JsonObject(tree + ("version" to JsonPrimitive(LineupCodec.MOVED_VERSION - 1)))
        assertEquals(LineupCodec.Decoded.Unreadable, LineupCodec.decode(older.toString().encodeToByteArray()))
    }
}
