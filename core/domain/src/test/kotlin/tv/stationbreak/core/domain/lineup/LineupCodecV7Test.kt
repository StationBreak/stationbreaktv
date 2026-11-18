// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.Lineup
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
        flag: JsonPrimitive?,
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
        for (version in 3..6) {
            val read = LineupCodec.decode(rewritten(seven, version, JsonPrimitive(true)))
            assertEquals(LineupCodec.Decoded.Unreadable, read, "version $version")
        }
        assertEquals(LineupCodec.Decoded.Unreadable, LineupCodec.decode(rewritten(seven, 7, JsonPrimitive(false))))
    }

    @Test
    fun version7IsTheNewestAndOnlyAGiveUpNeedsIt() {
        assertEquals(7, LineupCodec.VERSION)
        val other = lineupOf(starter("laughs", 110, ChannelState.On, rules("Laughs", Source.Genre("Comedy"))))
        assertEquals(3, versionOf(LineupCodec.encode(other)))
    }
}

private const val KEY = "plannedWithoutCollections"
