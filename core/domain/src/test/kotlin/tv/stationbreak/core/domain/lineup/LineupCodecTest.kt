// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LineupCodecTest {
    private val full: Lineup =
        lineupOf(
            starter("toons", 10, ChannelState.On, rules("Toons", Source.Genre("Animation"), maxRating = "G")),
            leftovers("kids-odds-and-ends", 99, "PG"),
            starter("laughs", 110, ChannelState.Shelf, rules("Laughs", Source.Genre("Comedy")))
                .copy(numberOverride = 111, nameOverride = "Our laughs"),
            leftovers("odds-and-ends", 199, null),
            channel(
                "starter:whodunit",
                210,
                ChannelState.UserHidden,
                ChannelSource.Starter(
                    "whodunit",
                    edited = true,
                    rules = rules("Whodunit", Source.Genre("Crime"), exclusions = listOf(Source.Tag("gore"))),
                ),
                onSinceMs = 5L,
            ),
            channel("custom-1", 600, ChannelState.On, ChannelSource.Custom(rules("Mine", Source.Tag("mine")))),
            starter("rockets", null, ChannelState.Shelf, rules("Rockets", Source.Genre("Science Fiction"))),
            offset = -300,
        )

    private fun decode(text: String) = LineupCodec.decode(text.encodeToByteArray())

    private fun tree(lineup: Lineup = full): JsonObject {
        val text = LineupCodec.encode(lineup).decodeToString()
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun JsonObject.with(
        key: String,
        value: JsonElement,
    ) = JsonObject(this + (key to value))

    private fun JsonObject.channels() = getValue("channels").jsonArray.map { it.jsonObject }

    // The file with channel [index] replaced by [change] of it.
    private fun JsonObject.withChannel(
        index: Int,
        change: (JsonObject) -> JsonObject,
    ): JsonObject = with("channels", JsonArray(channels().mapIndexed { i, c -> if (i == index) change(c) else c }))

    private fun JsonObject.source() = getValue("source").jsonObject

    @Test
    fun roundTrip() {
        assertEquals(LineupCodec.Decoded.Loaded(full), LineupCodec.decode(LineupCodec.encode(full)))
    }

    @Test
    fun theRefreshersBookkeepingRoundTrips() {
        val kept = full.copy(collectionPassActedOn = 1_760_000_000_000L, syncsAwaitingCollections = 2)
        assertEquals(LineupCodec.Decoded.Loaded(kept), LineupCodec.decode(LineupCodec.encode(kept)))
        assertEquals(JsonPrimitive(1_760_000_000_000L), tree(kept)["collectionPassActedOn"])
        assertEquals(JsonPrimitive(2), tree(kept)["syncsAwaitingCollections"])
    }

    @Test
    fun aVersionOneFileWithoutTheBookkeepingStillLoads() {
        // What a debug install already holds: the same version, written before the two fields were added.
        val old = JsonObject(tree() - "collectionPassActedOn" - "syncsAwaitingCollections")
        val loaded = assertIs<LineupCodec.Decoded.Loaded>(decode(old.toString()))
        assertEquals(null, loaded.lineup.collectionPassActedOn)
        assertEquals(0, loaded.lineup.syncsAwaitingCollections)
        assertEquals(full, loaded.lineup)
    }

    @Test
    fun theBookkeepingFieldsAreKnownKeysAndAnythingElseStillMakesTheFileUnreadable() {
        val known = tree().with("collectionPassActedOn", JsonPrimitive(5L)).with("syncsAwaitingCollections", JsonPrimitive(1))
        assertIs<LineupCodec.Decoded.Loaded>(decode(known.toString()))
        val unknown = known.with("anotherNewField", JsonPrimitive(true))
        assertEquals(LineupCodec.Decoded.Unreadable, decode(unknown.toString()))
        val wrongType = tree().with("collectionPassActedOn", JsonPrimitive("yesterday"))
        assertEquals(LineupCodec.Decoded.Unreadable, decode(wrongType.toString()))
        val negative = tree().with("syncsAwaitingCollections", JsonPrimitive(-1))
        assertEquals(LineupCodec.Decoded.Unreadable, decode(negative.toString()))
    }

    @Test
    fun unfinalisedEmptyLineupRoundTrips() {
        val empty = Lineup(SERVER, USER, 1, 60, finalised = false, channels = emptyList())
        assertEquals(LineupCodec.Decoded.Loaded(empty), LineupCodec.decode(LineupCodec.encode(empty)))
    }

    @Test
    fun theFileSaysVersionOne() {
        assertEquals(JsonPrimitive(1), tree()["version"])
    }

    @Test
    fun newerVersionDecodesAsNewerVersion() {
        assertEquals(LineupCodec.Decoded.NewerVersion(2), decode("""{"version":2,"anything":["new"]}"""))
        // A newer file is newer even when the rest is not what this version expects.
        assertEquals(LineupCodec.Decoded.NewerVersion(7), decode("""{"version":7}"""))
        assertEquals(
            LineupCodec.Decoded.NewerVersion(Int.MAX_VALUE),
            decode("""{"version":99999999999999999999999}"""),
        )
    }

    @Test
    fun badVersionsAreUnreadable() {
        val texts = listOf("""{"version":0}""", """{"version":-1}""", """{"version":"1"}""", """{"version":1.5}""")
        assertEquals(LineupCodec.Decoded.Unreadable, decode("{}"))
        for (text in texts) {
            assertEquals(LineupCodec.Decoded.Unreadable, decode(text), text)
        }
    }

    @Test
    fun whatIsNotALineupIsUnreadable() {
        for (text in listOf("", "not json", "[1]", "null", """{"version":1}""")) {
            assertEquals(LineupCodec.Decoded.Unreadable, decode(text), text)
        }
        val notUtf8 = byteArrayOf(0x7b, 0xff.toByte(), 0x7d)
        assertEquals(LineupCodec.Decoded.Unreadable, LineupCodec.decode(notUtf8))
    }

    @Test
    fun unknownKeyAtSameVersionIsUnreadableNotDropped() {
        val files =
            listOf(
                tree().with("newField", JsonPrimitive(true)),
                tree().withChannel(0) { it.with("favourite", JsonPrimitive(true)) },
                tree().withChannel(0) { it.with("source", it.source().with("pinned", JsonPrimitive(true))) },
                tree().withChannel(0) { it.with("state", JsonPrimitive("archived")) },
                tree().withChannel(0) { it.with("source", it.source().with("kind", JsonPrimitive("seasonal"))) },
            )
        for (file in files) assertEquals(LineupCodec.Decoded.Unreadable, decode(file.toString()), file.toString())
    }

    @Test
    fun aMissingFieldIsUnreadable() {
        val noNumber = tree().withChannel(0) { JsonObject(it - "numberOverride") }
        assertEquals(LineupCodec.Decoded.Unreadable, decode(noNumber.toString()))
        val noOffset = JsonObject(tree() - "utcOffsetMinutes")
        assertEquals(LineupCodec.Decoded.Unreadable, decode(noOffset.toString()))
    }

    @Test
    fun brokenLineupInvariantsAreUnreadable() {
        // Two channels at 10.
        val duplicate = tree().withChannel(1) { it.with("defaultNumber", JsonPrimitive(10)) }
        assertEquals(LineupCodec.Decoded.Unreadable, decode(duplicate.toString()))
        val badServer = tree().with("serverId", JsonPrimitive("../x"))
        assertEquals(LineupCodec.Decoded.Unreadable, decode(badServer.toString()))
        val badOffset = tree().with("utcOffsetMinutes", JsonPrimitive(7))
        assertEquals(LineupCodec.Decoded.Unreadable, decode(badOffset.toString()))
    }

    @Test
    fun oneBadChannelKeepsTheRest() {
        val file =
            tree()
                // A rules file this app cannot read: its channel is kept, unusable, at its number and state.
                .withChannel(0) { it.with("source", it.source().with("rules", JsonPrimitive(NOT_RULES))) }
                // A leftovers ceiling that is not on the ladder.
                .withChannel(1) { it.with("source", it.source().with("maxRating", JsonPrimitive("PG-14"))) }
                // A source that is not an object at all.
                .withChannel(5) { it.with("source", JsonPrimitive(42)) }
        val loaded = assertIs<LineupCodec.Decoded.Loaded>(decode(file.toString())).lineup
        assertEquals(full.channels.size, loaded.channels.size)
        val byId = loaded.channels.associateBy { it.id.value }
        val bad = setOf("starter:toons", "leftovers:kids-odds-and-ends", "custom-1")
        for (unusable in bad) {
            val channel = byId.getValue(unusable)
            assertIs<ChannelSource.Unusable>(channel.source, unusable)
            val before = full.channels.first { it.id.value == unusable }
            assertEquals(before.copy(source = channel.source), channel, "everything but the source is kept")
        }
        for (kept in full.channels.filter { it.id.value !in bad }) {
            assertEquals(kept, byId.getValue(kept.id.value))
        }
    }

    // A source that is [levels] arrays deep.
    private fun nested(levels: Int): JsonElement =
        (1 until levels).fold<Int, JsonElement>(JsonArray(emptyList())) { inner, _ -> JsonArray(listOf(inner)) }

    @Test
    fun aSourceNestedPastThirtyTwoLevelsIsUnusableAndNotKept() {
        val file = tree().withChannel(0) { it.with("source", nested(33)) }
        val loaded = assertIs<LineupCodec.Decoded.Loaded>(decode(file.toString())).lineup
        val source = assertIs<ChannelSource.Unusable>(loaded.channels[0].source)
        assertEquals("null", source.rawJson, "too deep to keep or re-encode")
        assertEquals(full.channels.size, loaded.channels.size)
    }

    @Test
    fun aSourceNestedThirtyTwoLevelsIsStillKeptVerbatim() {
        val file = tree().withChannel(0) { it.with("source", nested(32)) }
        val loaded = assertIs<LineupCodec.Decoded.Loaded>(decode(file.toString())).lineup
        val source = assertIs<ChannelSource.Unusable>(loaded.channels[0].source)
        assertEquals(nested(32).toString(), source.rawJson)
    }

    @Test
    fun absurdNestingIsUnreadableNotAThrow() {
        val deep = "[".repeat(200_000)
        assertEquals(LineupCodec.Decoded.Unreadable, decode(deep))
        val inChannel = """{"version":1,"channels":""" + deep
        assertEquals(LineupCodec.Decoded.Unreadable, decode(inChannel))
    }

    @Test
    fun rulesThatNeedANewerAppAreUnusableNotUnreadable() {
        val newer = """{"formatVersion":2,"seed":"1","sources":[{"type":"genre","name":"Comedy"}]}"""
        val file = tree().withChannel(0) { it.with("source", it.source().with("rules", JsonPrimitive(newer))) }
        val loaded = assertIs<LineupCodec.Decoded.Loaded>(decode(file.toString())).lineup
        assertIs<ChannelSource.Unusable>(loaded.channels[0].source)
    }

    @Test
    fun unusableRoundTripsVerbatim() {
        // Odd spacing, a duplicate key and a newer format: the rules text must come back exactly as it was.
        val rulesText = "{ \"formatVersion\" : 3,\n  \"x\": [1,  2], \"x\": \"é\" }"
        val file =
            tree().withChannel(0) {
                val source = it.source().with("rules", JsonPrimitive(rulesText)).with("edited", JsonPrimitive(false))
                it.with("source", source)
            }
        val first = assertIs<LineupCodec.Decoded.Loaded>(decode(file.toString())).lineup
        val raw = assertIs<ChannelSource.Unusable>(first.channels[0].source).rawJson

        val again = LineupCodec.encode(first)
        val second = assertIs<LineupCodec.Decoded.Loaded>(LineupCodec.decode(again)).lineup
        assertEquals(raw, assertIs<ChannelSource.Unusable>(second.channels[0].source).rawJson)
        assertTrue(again.decodeToString().contains(raw), "the raw JSON is written as it is held")
        val written =
            Json
                .parseToJsonElement(again.decodeToString())
                .jsonObject
                .channels()[0]
                .source()
        assertEquals(rulesText, written.getValue("rules").jsonPrimitive.content, "the rules text is byte for byte")
        assertEquals(first, second)
    }

    private companion object {
        const val NOT_RULES = """{"formatVersion":1}"""
    }
}
