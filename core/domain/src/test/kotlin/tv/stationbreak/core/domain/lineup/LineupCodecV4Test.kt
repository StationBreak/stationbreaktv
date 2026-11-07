// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import tv.stationbreak.core.domain.lineup.repair.HALF_HOUR_MS
import tv.stationbreak.core.domain.lineup.repair.NOW_MS
import tv.stationbreak.core.domain.lineup.repair.TodayRepairs
import tv.stationbreak.core.domain.lineup.repair.fixOf
import tv.stationbreak.core.domain.lineup.repair.repairOf
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LineupCodecV4Test {
    private val lineup =
        lineupOf(
            starter("laughs", 1, ChannelState.On, rules("Laughs", Source.Genre("Comedy"))),
            leftovers("odds-and-ends", 199, null),
        )
    private val meta = RefreshMeta(4L, 5L, listOf("lib-a"), 6L)

    private val repairs =
        TodayRepairs(
            selection =
                listOf(
                    repairOf(n = 1L, seq = 4L),
                    repairOf(channel = "leftovers:odds-and-ends", n = 2L, seq = 5L, hidden = HiddenReason.NoMatches),
                ),
            fixes = listOf(fixOf(), fixOf(n = 2L, blockStartMs = NOW_MS + 4 * HALF_HOUR_MS, gone = 91)),
            lostCheckedGeneration = 9L,
        )

    private fun tree(bytes: ByteArray): JsonObject = Json.parseToJsonElement(bytes.decodeToString()).jsonObject

    private fun loaded(bytes: ByteArray) = assertIs<LineupCodec.Decoded.Loaded>(LineupCodec.decode(bytes))

    private fun withRepairs(
        bytes: ByteArray,
        version: Int?,
        edit: (JsonObject) -> JsonElement,
    ): ByteArray {
        val root = tree(bytes)
        val changed = if (version == null) root else JsonObject(root + ("version" to JsonPrimitive(version)))
        val repairsNow = checkNotNull(root["repairs"]).jsonObject
        return Json
            .encodeToString(JsonElement.serializer(), JsonObject(changed + ("repairs" to edit(repairsNow))))
            .encodeToByteArray()
    }

    @Test
    fun v4RoundTrip_keepsRepairsExactly() {
        val bytes = LineupCodec.encode(lineup, meta, repairs)
        assertEquals(4, (tree(bytes)["version"] as JsonPrimitive).content.toInt())
        assertEquals(LineupCodec.Decoded.Loaded(lineup, meta, repairs, fileVersion = 4), LineupCodec.decode(bytes))
        // And again: what was read writes back to the same bytes.
        val again = loaded(bytes)
        assertTrue(bytes.contentEquals(LineupCodec.encode(again.lineup, again.meta, again.repairs)))
    }

    @Test
    fun v4Encoding_isCompact_blocksAreIntegerArrays() {
        val bytes = LineupCodec.encode(lineup, meta, TodayRepairs(selection = listOf(repairOf())))
        val first = checkNotNull(tree(bytes)["repairs"]).jsonObject
        val text = first.toString()
        assertTrue("[0," in text, "blocks are [startDelta, length, idIndex, mediaStart]: $text")
        assertFalse("startMs" in text, "no long field names per block")
    }

    @Test
    fun noRepairs_writesVersion3() {
        val plain = LineupCodec.encode(lineup, meta)
        assertEquals(3, (tree(plain)["version"] as JsonPrimitive).content.toInt())
        assertFalse("repairs" in tree(plain))
        assertTrue(plain.contentEquals(LineupCodec.encode(lineup, meta, TodayRepairs.NONE)))
        assertEquals(LineupCodec.Decoded.Loaded(lineup, meta, fileVersion = 3), LineupCodec.decode(plain))
    }

    @Test
    fun repairsKeyInV3File_isUnreadable() {
        val v4 = LineupCodec.encode(lineup, meta, repairs)
        for (version in listOf(1, 2, 3)) {
            val older = withRepairs(v4, version) { it }
            assertEquals(LineupCodec.Decoded.Unreadable, LineupCodec.decode(older), "v$version")
        }
    }

    @Test
    fun aVersion5File_isNewer() {
        val v4 = LineupCodec.encode(lineup, meta, repairs)
        val root = tree(v4)
        val bytes = Json.encodeToString(JsonElement.serializer(), JsonObject(root + ("version" to JsonPrimitive(5))))
        assertEquals(LineupCodec.Decoded.NewerVersion(5), LineupCodec.decode(bytes.encodeToByteArray()))
    }

    @Test
    fun anUnknownKeyOrBadShapeInRepairs_isUnreadable() {
        val v4 = LineupCodec.encode(lineup, meta, repairs)
        val bad =
            listOf<(JsonObject) -> JsonElement>(
                { JsonObject(it + ("surprise" to JsonPrimitive(1))) },
                { JsonPrimitive("nope") },
                { JsonObject(it + ("selection" to JsonPrimitive(1))) },
                { JsonObject(it + ("selection" to it.getValue("selection").let { s -> JsonObject(mapOf("x" to s)) })) },
            )
        for (edit in bad) {
            assertEquals(LineupCodec.Decoded.Unreadable, LineupCodec.decode(withRepairs(v4, null, edit)))
        }
    }

    @Test
    fun twoSelectionRepairsOfOneChannelDay_isUnreadable() {
        val twice = repairs.copy(selection = listOf(repairOf(n = 1L, seq = 4L), repairOf(n = 1L, seq = 5L)))
        val bytes = LineupCodec.encode(lineup, meta, twice)
        assertEquals(LineupCodec.Decoded.Unreadable, LineupCodec.decode(bytes))
    }

    @Test
    fun aBlockNamingAnIdOutsideTheTable_isUnreadable() {
        val text = LineupCodec.encode(lineup, meta, TodayRepairs(selection = listOf(repairOf()))).decodeToString()
        // The first block is [0, length, 0, mediaStart]: point it at index 99.
        val broken = text.replaceFirst(Regex("""\[0,(\d+),0,"""), "[0,\$1,99,")
        assertTrue(broken != text)
        assertEquals(LineupCodec.Decoded.Unreadable, LineupCodec.decode(broken.encodeToByteArray()))
    }

    @Test
    fun aV4FileWithEmptyRepairsObject_loadsAsNone() {
        val v4 = LineupCodec.encode(lineup, meta, repairs)
        val bytes = withRepairs(v4, null) { JsonObject(emptyMap()) }
        val read = loaded(bytes)
        assertEquals(TodayRepairs.NONE, read.repairs)
        assertEquals(4, read.fileVersion)
    }
}
