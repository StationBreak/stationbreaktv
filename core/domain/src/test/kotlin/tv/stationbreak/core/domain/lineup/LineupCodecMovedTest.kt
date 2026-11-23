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
        val noted = base.copy(movedNotices = listOf(MovedNotice(laughs, 50, 110, 20_000L)))
        val bytes = LineupCodec.encode(noted)
        val read = loaded(bytes)
        assertEquals(noted.movedNotices, read.lineup.movedNotices)
        assertEquals(LineupCodec.MOVED_VERSION, read.fileVersion)
        val moved = Json.parseToJsonElement(bytes.decodeToString()).jsonObject.getValue("moved")
        assertEquals("""[{"id":"starter:laughs","from":50,"to":110,"day":20000}]""", moved.toString())
    }

    @Test
    fun noNoticeLeavesTheFileAsItWas() {
        val bytes = LineupCodec.encode(base)
        assertEquals(LineupCodec.BASE_VERSION, loaded(bytes).fileVersion)
        assertFalse("moved" in Json.parseToJsonElement(bytes.decodeToString()).jsonObject)
    }

    @Test
    fun aNoticeForAChannelThatIsGoneIsDropped() {
        val noted = base.copy(movedNotices = listOf(MovedNotice(laughs, 50, 110, 20_000L)))
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
        val noted = base.copy(movedNotices = listOf(MovedNotice(laughs, 50, 110, 20_000L)))
        val tree = Json.parseToJsonElement(LineupCodec.encode(noted).decodeToString()).jsonObject
        val older = JsonObject(tree + ("version" to JsonPrimitive(LineupCodec.MOVED_VERSION - 1)))
        assertEquals(LineupCodec.Decoded.Unreadable, LineupCodec.decode(older.toString().encodeToByteArray()))
    }

    // What a refresh does to an old notice, then the file: no notice left, so the file is back at its old version.
    @Test
    fun anExpiredNoticeLeavesTheFileAtItsOldVersion() {
        val old = MovedNotice(laughs, 50, 110, 100L)
        val today = 100L + MovedNotice.KEEP_DAYS + 1
        val kept = MovedNotice.live(listOf(old), setOf(laughs), today)
        assertEquals(emptyList(), kept)
        val bytes = LineupCodec.encode(base.copy(movedNotices = kept))
        assertEquals(LineupCodec.BASE_VERSION, loaded(bytes).fileVersion)
        assertEquals(1, MovedNotice.live(listOf(old), setOf(laughs), today - 1).size, "the last day it is kept")
    }

    private fun withMoved(raw: String): ByteArray {
        val tree = Json.parseToJsonElement(LineupCodec.encode(base).decodeToString()).jsonObject
        val version = JsonPrimitive(LineupCodec.MOVED_VERSION)
        val moved = Json.parseToJsonElement(raw)
        return JsonObject(tree + ("moved" to moved) + ("version" to version)).toString().encodeToByteArray()
    }

    private val good = """{"id":"starter:laughs","from":50,"to":110,"day":7}"""

    @Test
    fun anAdviceEntryThatDoesNotReadIsDroppedAndNeverMakesTheFileUnreadable() {
        val bad =
            listOf(
                """{"id":"starter:laughs","from":"50","to":110,"day":7}""",
                """{"id":"starter:laughs","to":110,"day":7}""",
                """{"id":"starter:laughs","from":50,"to":110}""",
                """{"id":"starter:laughs","from":5000,"to":110,"day":7}""",
                """{"id":"starter:laughs","from":50.5,"to":110,"day":7}""",
                """{"id":7,"from":50,"to":110,"day":7}""",
                """17""",
                """"garbage"""",
                """null""",
            )
        for (entry in bad) {
            assertEquals(emptyList(), loaded(withMoved("[$entry]")).lineup.movedNotices, entry)
            assertEquals(1, loaded(withMoved("[$entry,$good]")).lineup.movedNotices.size, "the good one stays: $entry")
        }
        assertEquals(emptyList(), loaded(withMoved(""""not a list"""")).lineup.movedNotices)
        assertEquals(emptyList(), loaded(withMoved("""{"id":"x"}""")).lineup.movedNotices)
    }

    @Test
    fun anExtraKeyInAnEntryIsIgnored() {
        val extra = """{"id":"starter:laughs","from":50,"to":110,"day":7,"why":"floor"}"""
        assertEquals(listOf(MovedNotice(laughs, 50, 110, 7L)), loaded(withMoved("[$extra]")).lineup.movedNotices)
    }

    // With no notice the encoder writes what it wrote before notices existed: no `moved` key, version 3.
    @Test
    fun withNoNoticeTheBytesAreWhatTheEncoderAlwaysWrote() {
        val expected = GOLDEN
        assertEquals(expected, LineupCodec.encode(base).decodeToString())
    }

    private companion object {
        const val GOLDEN =
            """{"version":3,"serverId":"a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0","userId":"b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0","catalogVersion":1,"utcOffsetMinutes":0,"finalised":true,"channels":[{"id":"starter:laughs","defaultNumber":110,"numberOverride":null,"nameOverride":null,"state":"on","onSinceMs":1767225600000,"source":{"kind":"starter","recipeKey":"laughs","edited":false,"rules":"{\n  \"formatVersion\": 1,\n  \"name\": \"Laughs\",\n  \"seed\": \"18446744071683569202\",\n  \"sources\": [\n    {\n      \"type\": \"genre\",\n      \"name\": \"Comedy\"\n    }\n  ],\n  \"timing\": {\n    \"broadcastDay\": {\n      \"utcOffsetMinutes\": 0,\n      \"start\": \"06:00\"\n    }\n  }\n}\n"}}]}"""
    }
}
