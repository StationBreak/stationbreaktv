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
import tv.stationbreak.core.engine.lineup.CurationBasis
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.RecipeResolver
import tv.stationbreak.core.engine.lineup.ShelfReason
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LineupCodecV3Test {
    private val old = rules("Old", Source.Genre("Drama"))
    private val current = rules("Current", Source.Genre("Comedy"))

    private fun decode(text: String) = LineupCodec.decode(text.encodeToByteArray())

    private fun loaded(text: String) = assertIs<LineupCodec.Decoded.Loaded>(decode(text))

    private fun tree(lineup: Lineup): JsonObject {
        val text = LineupCodec.encode(lineup).decodeToString()
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun JsonObject.with(
        key: String,
        value: JsonElement,
    ) = JsonObject(this + (key to value))

    private fun JsonObject.channel(index: Int) = getValue("channels").jsonArray[index].jsonObject

    private fun JsonObject.withChannel(
        index: Int,
        change: (JsonObject) -> JsonObject,
    ): JsonObject =
        with(
            "channels",
            JsonArray(
                getValue("channels").jsonArray.mapIndexed { i, c -> if (i == index) change(c.jsonObject) else c },
            ),
        )

    private fun JsonObject.source() = getValue("source").jsonObject

    private fun starterChannel(
        key: String,
        number: Int,
        previous: Boolean = false,
    ) = channel(
        "starter:$key",
        number,
        ChannelState.On,
        ChannelSource.Starter(
            key,
            edited = false,
            rules = current,
            previousRules = old.takeIf { previous },
            rulesFromMs = 5_000L.takeIf { previous },
        ),
    )

    @Test
    fun aVersionTwoFileReadsWithChoicesUnknown() {
        val plain = starterChannel("plain", 110)
        val hidden = starterChannel("hidden", 111).copy(state = ChannelState.UserHidden)
        val renumbered = starterChannel("renumbered", 112).copy(numberOverride = 150)
        val renamed = starterChannel("renamed", 113).copy(nameOverride = "Mine")
        val lineup = lineupOf(plain, hidden, renumbered, renamed)
        val two = LineupCodec.encode(lineup).decodeToString().replace("\"version\":3", "\"version\":2")

        val read = loaded(two)

        assertEquals(2, read.fileVersion)
        assertFalse(read.lineup.viewerChoicesKnown)
        assertEquals(
            listOf(false, true, true, true),
            read.lineup.channels.map { it.chosenByViewer },
        )
        assertTrue(read.lineup.useMyCollections)
        assertNull(read.lineup.curationBasis)
        assertTrue(read.lineup.channels.all { it.leavesAtMs == null && it.shelfReason == null })
    }

    @Test
    fun aVersionTwoFileWithAVersionThreeFieldIsUnreadable() {
        val lineup = lineupOf(starterChannel("a", 110), starterChannel("b", 111)).copy(useMyCollections = false)
        val text = LineupCodec.encode(lineup).decodeToString().replace("\"version\":3", "\"version\":2")

        assertEquals(LineupCodec.Decoded.Unreadable, decode(text))
        val withPrevious = lineupOf(starterChannel("a", 110, previous = true))
        val previousText = LineupCodec.encode(withPrevious).decodeToString().replace("\"version\":3", "\"version\":2")
        assertEquals(LineupCodec.Decoded.Unreadable, decode(previousText))
    }

    @Test
    fun everyNewFieldRoundTrips() {
        val a = starterChannel("a", 110, previous = true).copy(chosenByViewer = true, leavesAtMs = 9_000L)
        val b = starterChannel("b", 111).copy(state = ChannelState.Shelf, shelfReason = ShelfReason.BelowCap)
        val lineup =
            lineupOf(a, b).copy(
                useMyCollections = false,
                curationBasis = CurationBasis(7_000L, setOf(a.id), household = true),
                viewerChoicesKnown = false,
            )

        assertEquals(LineupCodec.Decoded.Loaded(lineup), decode(LineupCodec.encode(lineup).decodeToString()))
        for (reason in ShelfReason.entries) {
            val one = lineupOf(b.copy(shelfReason = reason))
            assertEquals(LineupCodec.Decoded.Loaded(one), LineupCodec.decode(LineupCodec.encode(one)))
        }
        val plain = lineupOf(starterChannel("c", 110))
        assertEquals(LineupCodec.Decoded.Loaded(plain), LineupCodec.decode(LineupCodec.encode(plain)))
    }

    @Test
    fun unknownBasisIdsAreDropped() {
        val a = starterChannel("a", 110)
        val lineup = lineupOf(a).copy(curationBasis = CurationBasis(1L, setOf(a.id), household = false))
        val file =
            tree(lineup).with(
                "curationBasis",
                JsonObject(
                    mapOf(
                        "fromMs" to JsonPrimitive(1L),
                        "on" to JsonArray(listOf("starter:a", "starter:gone", "").map(::JsonPrimitive)),
                        "household" to JsonPrimitive(false),
                    ),
                ),
            )

        val read = loaded(file.toString()).lineup

        assertEquals(setOf(ChannelId("starter:a")), read.curationBasis?.on)
    }

    @Test
    fun leavesAtAndShelfReasonOnANonStarterAreIgnored() {
        val lineup = lineupOf(starterChannel("a", 110), channel("custom-1", 600, ChannelState.On, custom()))
        val file =
            tree(lineup).withChannel(1) {
                it.with("leavesAtMs", JsonPrimitive(5L)).with("shelfReason", JsonPrimitive("empty"))
            }

        val read = loaded(file.toString()).lineup.channels[1]

        assertNull(read.leavesAtMs)
        assertNull(read.shelfReason)
        assertEquals(lineup.channels[1], read)
    }

    private fun custom() = ChannelSource.Custom(rules("Mine", Source.Tag("mine")))

    @Test
    fun previousRulesNeedTheirBoundary() {
        val lineup = lineupOf(starterChannel("a", 110, previous = true))
        val file = tree(lineup)

        val noBoundary = file.withChannel(0) { it.with("source", JsonObject(it.source() - "rulesFromMs")) }
        val noRules = file.withChannel(0) { it.with("source", JsonObject(it.source() - "previousRules")) }

        for (text in listOf(noBoundary, noRules)) {
            val starter = assertIs<ChannelSource.Starter>(loaded(text.toString()).lineup.channels[0].source)
            assertNull(starter.previousRules)
            assertNull(starter.rulesFromMs)
            assertEquals(current, starter.rules)
        }
    }

    @Test
    fun anUnreadablePreviousRulesKeepsTheCurrentRules() {
        val lineup = lineupOf(starterChannel("a", 110, previous = true))
        for (bad in listOf(JsonPrimitive("""{"formatVersion":1}"""), JsonPrimitive("not json"), JsonPrimitive(4))) {
            val file = tree(lineup).withChannel(0) { it.with("source", it.source().with("previousRules", bad)) }

            val channel = loaded(file.toString()).lineup.channels[0]

            val starter = assertIs<ChannelSource.Starter>(channel.source)
            assertEquals(current, starter.rules)
            assertNull(starter.previousRules)
            assertNull(starter.rulesFromMs)
        }
    }

    @Test
    fun basisOnIsWrittenSorted() {
        val channels = listOf("c", "a", "b").mapIndexed { i, k -> starterChannel(k, 110 + i) }
        val lineup =
            lineupOf(*channels.toTypedArray()).copy(
                curationBasis = CurationBasis(1L, channels.map { it.id }.toSet(), household = true),
            )

        val written =
            tree(lineup)
                .getValue("curationBasis")
                .jsonObject
                .getValue("on")
                .jsonArray
                .map { it.jsonPrimitive.content }

        assertEquals(listOf("starter:a", "starter:b", "starter:c"), written)
    }

    @Test
    fun aWorstCaseLineupStaysUnderTheCap() {
        val channels =
            (1..STARTERS).map { n ->
                val sources = (1..SOURCES).map { Source.Collection(id(n * 1000 + it)) }
                val now = rules("Starter $n", *sources.toTypedArray())
                val before = rules("Starter $n before", *sources.reversed().toTypedArray())
                channel(
                    "starter:s$n",
                    100 + n,
                    ChannelState.On,
                    ChannelSource.Starter("s$n", edited = false, rules = now, previousRules = before, rulesFromMs = 1L),
                )
            }

        val size = LineupCodec.encode(lineupOf(*channels.toTypedArray())).size

        println("worst-case lineup file: $size bytes with $SOURCES sources per rules")
        assertTrue(size <= FILE_CAP / 2, "the worst case takes $size bytes")
    }

    @Test
    fun aVersionFourFileIsNewer() {
        assertEquals(LineupCodec.Decoded.NewerVersion(4), decode("""{"version":4,"useMyCollections":false}"""))
    }

    private companion object {
        const val STARTERS = 50

        // The resolved-source cap for a starter (RecipeResolver.MAX_STARTER_SOURCES).
        val SOURCES = RecipeResolver.MAX_STARTER_SOURCES

        // LineupFiles.MAX_BYTES in data:settings: the largest lineup file the store reads or writes.
        const val FILE_CAP = 1024 * 1024
    }
}
