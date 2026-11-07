// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import tv.stationbreak.core.domain.lineup.repair.HALF_HOUR_MS
import tv.stationbreak.core.domain.lineup.repair.KeptBlock
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
            lastSeq = 9L,
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
    fun anUnknownTailHiddenName_readsAsNone_andTheFileStaysReadable() {
        val bytes = LineupCodec.encode(lineup, meta, repairs)
        val newer =
            withRepairs(bytes, version = null) { r ->
                val selection =
                    (checkNotNull(r["selection"]) as kotlinx.serialization.json.JsonArray).map { entry ->
                        val fields = entry.jsonObject
                        val newer = "hidden" to JsonPrimitive("AReasonFromANewerApp")
                        if ("hidden" in fields) JsonObject(fields + newer) else fields
                    }
                JsonObject(r + ("selection" to kotlinx.serialization.json.JsonArray(selection)))
            }

        val read = loaded(newer)

        assertEquals(listOf(null, null), read.repairs.selection.map { it.tailHidden })
        assertEquals(repairs.selection.map { it.copy(tailHidden = null) }, read.repairs.selection)
        assertEquals(repairs.fixes, read.repairs.fixes)
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

    // A record that does not read loads the lineup with none: never a reason to set the lineup aside.
    private fun assertRecordUnreadable(bytes: ByteArray) {
        val read = loaded(bytes)
        assertEquals(lineup, read.lineup)
        assertEquals(meta, read.meta)
        assertEquals(TodayRepairs.NONE, read.repairs)
        assertTrue(read.repairsUnreadable)
    }

    @Test
    fun anUnknownKeyOrBadShapeInRepairs_loadsTheLineupWithNoRecord() {
        val v4 = LineupCodec.encode(lineup, meta, repairs)
        val bad =
            listOf<(JsonObject) -> JsonElement>(
                { JsonObject(it + ("surprise" to JsonPrimitive(1))) },
                { JsonPrimitive("nope") },
                { JsonObject(it + ("selection" to JsonPrimitive(1))) },
                { JsonObject(it + ("selection" to it.getValue("selection").let { s -> JsonObject(mapOf("x" to s)) })) },
            )
        for (edit in bad) assertRecordUnreadable(withRepairs(v4, null, edit))
        assertFalse(loaded(v4).repairsUnreadable, "the control reads")
    }

    @Test
    fun twoSelectionRepairsOfOneChannelDayWithOneSeq_loadsTheLineupWithNoRecord() {
        val twice = repairs.copy(selection = listOf(repairOf(n = 1L, seq = 4L), repairOf(n = 1L, seq = 4L)))
        assertRecordUnreadable(LineupCodec.encode(lineup, meta, twice))
    }

    @Test
    fun twoSelectionRepairsOfOneChannelDay_ofTwoSeqs_roundTrip() {
        // The shown one and a later one whose selection is being saved.
        val both = repairs.copy(selection = listOf(repairOf(n = 1L, seq = 4L), repairOf(n = 1L, seq = 5L)))
        assertEquals(both, loaded(LineupCodec.encode(lineup, meta, both)).repairs)
    }

    @Test
    fun aMissingRequiredKey_loadsTheLineupWithNoRecord() {
        val v4 = LineupCodec.encode(lineup, meta, repairs)

        fun withoutInFirst(
            list: String,
            key: String,
        ): (JsonObject) -> JsonElement =
            { r ->
                val entries = (checkNotNull(r[list]) as kotlinx.serialization.json.JsonArray).toMutableList()
                entries[0] = JsonObject(entries[0].jsonObject - key)
                JsonObject(r + (list to kotlinx.serialization.json.JsonArray(entries)))
            }
        for (key in listOf("seq", "from", "b", "ch", "ids")) {
            assertRecordUnreadable(withRepairs(v4, null, withoutInFirst("selection", key)))
        }
        for (key in listOf("gone", "p", "blockStart")) {
            assertRecordUnreadable(withRepairs(v4, null, withoutInFirst("fixes", key)))
        }
    }

    @Test
    fun aBlockNamingAnIdOutsideTheTable_loadsTheLineupWithNoRecord() {
        val text = LineupCodec.encode(lineup, meta, TodayRepairs(selection = listOf(repairOf()))).decodeToString()
        // The first block is [0, length, 0, mediaStart]: point it at index 99.
        val broken = text.replaceFirst(Regex("""\[0,(\d+),0,"""), "[0,\$1,99,")
        assertTrue(broken != text)
        assertRecordUnreadable(broken.encodeToByteArray())
    }

    @Test
    fun aV4FileWithEmptyRepairsObject_loadsAsNone() {
        val v4 = LineupCodec.encode(lineup, meta, repairs)
        val bytes = withRepairs(v4, null) { JsonObject(emptyMap()) }
        val read = loaded(bytes)
        assertEquals(TodayRepairs.NONE, read.repairs)
        assertEquals(4, read.fileVersion)
    }

    // A new HiddenReason value is a schema change of the stored repairs: bump LineupCodec.VERSION and update this test.
    @Test
    fun theHiddenReasonsArePinnedToTheFileVersion() {
        assertEquals(
            listOf(
                "CollectionsNotSynced",
                "HouseholdCollectionsOff",
                "LibraryNotChosen",
                "NoMatches",
                "NothingMadeForKids",
                "ChildrensRatingsOnly",
                "AllExcluded",
                "AllFilteredOut",
                "AllBlockedByCeiling",
                "EmptyToday",
            ),
            HiddenReason.entries.map { it.name },
            GUARD,
        )
        assertEquals(4, LineupCodec.VERSION, GUARD)
    }

    @Test
    fun aNullRepairsKeyInAnOlderFile_isUnreadable() {
        val v3 = LineupCodec.encode(lineup, meta)
        val root = tree(v3)
        for (version in listOf(1, 2, 3)) {
            val text =
                Json.encodeToString(
                    JsonElement.serializer(),
                    JsonObject(root + ("version" to JsonPrimitive(version)) + ("repairs" to JsonNull)),
                )
            assertEquals(LineupCodec.Decoded.Unreadable, LineupCodec.decode(text.encodeToByteArray()), "v$version")
        }
    }

    @Test
    fun anIdRepeatedInATable_loadsTheLineupWithNoRecord_butAnUnusedOneIsFine() {
        val text = LineupCodec.encode(lineup, meta, TodayRepairs(selection = listOf(repairOf()))).decodeToString()
        val ids = Regex(""""ids":\["([0-9a-f]+)"""").find(text)!!.groupValues[1]
        val repeated = text.replaceFirst(""""ids":["$ids"""", """"ids":["$ids","$ids"""")
        assertRecordUnreadable(repeated.encodeToByteArray())
        val unused = text.replaceFirst(""""ids":["$ids"""", """"ids":["$ids","${"f".repeat(32)}"""")
        assertIs<LineupCodec.Decoded.Loaded>(LineupCodec.decode(unused.encodeToByteArray()))
    }

    private fun roundTrips(record: TodayRepairs) {
        val bytes = LineupCodec.encode(lineup, meta, record)
        assertEquals(LineupCodec.Decoded.Loaded(lineup, meta, record, fileVersion = 4), LineupCodec.decode(bytes))
    }

    @Test
    fun gapsBetweenBlocks_roundTrip() {
        val base = repairOf()
        val gappy = base.blocks.mapIndexed { i, b -> b.copy(startMs = b.startMs + i, endMs = b.endMs + i + 1) }
        roundTrips(TodayRepairs(selection = listOf(base.copy(blocks = gappy))))
        val fix = fixOf()
        val pieces = fix.pieces.mapIndexed { i, b -> b.copy(startMs = b.startMs + 5 * i, endMs = b.endMs + 5 * i) }
        roundTrips(TodayRepairs(fixes = listOf(fix.copy(pieces = pieces))))
    }

    @Test
    fun emptyLists_roundTrip() {
        val bare = repairOf().copy(libraries = emptySet(), blocks = emptyList(), pinLibrary = null)
        roundTrips(TodayRepairs(selection = listOf(bare), fixes = listOf(fixOf().copy(pieces = emptyList()))))
    }

    @Test
    fun veryLargeTimes_roundTrip() {
        val big = (1L shl 52) - 1_000_000L
        val block = KeptBlock(big, big + HALF_HOUR_MS, id(7), big / 2)
        val repair =
            repairOf().copy(
                dayEndMs = big + HALF_HOUR_MS,
                atMs = big,
                keptFromMs = big,
                pinEndMs = big + HALF_HOUR_MS,
                seq = Long.MAX_VALUE,
                blocks = listOf(block),
            )
        val fix = fixOf().copy(dayEndMs = big + HALF_HOUR_MS, blockStartMs = big, blockEndMs = big + HALF_HOUR_MS)
        roundTrips(TodayRepairs(listOf(repair), listOf(fix.copy(pieces = listOf(block))), lastSeq = Long.MAX_VALUE))
    }

    private companion object {
        const val GUARD = "A new HiddenReason value is a schema change: bump LineupCodec.VERSION and update this test"
    }
}
