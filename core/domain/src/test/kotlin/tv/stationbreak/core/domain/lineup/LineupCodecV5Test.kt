// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import tv.stationbreak.core.domain.lineup.repair.TodayRepairs
import tv.stationbreak.core.domain.lineup.repair.repairOf
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.ViewerChoice
import tv.stationbreak.core.engine.lineup.ViewerChoice.Added
import tv.stationbreak.core.engine.lineup.ViewerChoice.Touched
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Lineup file version 5 (the viewer's added channels): the per-channel `addedByViewer: true` beside `chosenByViewer`.
 * The fixtures are named `lineup-file-v5*` so they are not taken for rules-format 5 files.
 */
class LineupCodecV5Test {
    private val comedy = rules("Laughs", Source.Genre("Comedy"))
    private val crime = rules("Whodunit", Source.Genre("Crime"))

    private val added = starter("laughs", 110, ChannelState.On, comedy).copy(viewerChoice = ViewerChoice.Added)
    private val plain = starter("whodunit", 210, ChannelState.On, crime)
    private val lineup = lineupOf(added, plain)

    private fun fixture(name: String) = checkNotNull(javaClass.getResourceAsStream("/lineup/$name")).readBytes()

    private fun decode(bytes: ByteArray) = LineupCodec.decode(bytes)

    private fun loaded(bytes: ByteArray) = assertIs<LineupCodec.Decoded.Loaded>(decode(bytes))

    private fun tree(bytes: ByteArray): JsonObject = Json.parseToJsonElement(bytes.decodeToString()).jsonObject

    private fun versionOf(bytes: ByteArray) = (tree(bytes)["version"] as JsonPrimitive).content.toInt()

    private fun JsonObject.with(
        key: String,
        value: JsonElement,
    ) = JsonObject(this + (key to value))

    private fun JsonObject.withChannel(
        index: Int,
        change: (JsonObject) -> JsonObject,
    ): JsonObject =
        with(
            "channels",
            JsonArray(channelsWith(index, change)),
        )

    private fun JsonObject.channelsWith(
        index: Int,
        change: (JsonObject) -> JsonObject,
    ) = getValue("channels").jsonArray.mapIndexed { i, c -> if (i == index) change(c.jsonObject) else c }

    private fun JsonObject.bytes() = toString().encodeToByteArray()

    private fun JsonObject.addedOn(index: Int) = withChannel(index) { it.with("addedByViewer", JsonPrimitive(true)) }

    private val customSource = ChannelSource.Custom(crime)
    private val customChannel = channel("custom:mine", 600, ChannelState.On, customSource).copy(viewerChoice = Touched)

    @Test
    fun theGoldenVersion5FileHoldsTheMarkBesideChosenByViewer() {
        val golden = fixture("lineup-file-v5-added.json")

        assertTrue(golden.contentEquals(LineupCodec.encode(lineup)), "the encoder writes the golden bytes")
        val read = loaded(golden)
        assertEquals(5, read.fileVersion)
        assertEquals(lineup, read.lineup)
        assertEquals(listOf(ViewerChoice.Added, ViewerChoice.None), read.lineup.channels.map { it.viewerChoice })
        assertTrue(golden.contentEquals(LineupCodec.encode(read.lineup, read.meta, read.repairs)), "writes back as is")
        val first = tree(golden).getValue("channels").jsonArray[0].jsonObject
        assertEquals(JsonPrimitive(true), first["chosenByViewer"])
        assertEquals(JsonPrimitive(true), first["addedByViewer"])
        assertEquals(null, tree(golden).getValue("channels").jsonArray[1].jsonObject["addedByViewer"])
    }

    @Test
    fun theVersionIsTheHighestAnyFeatureNeeds() {
        val repairs = TodayRepairs(selection = listOf(repairOf()))
        val none = lineupOf(plain)
        assertEquals(3, versionOf(LineupCodec.encode(none)))
        assertEquals(4, versionOf(LineupCodec.encode(none, repairs = repairs)))
        assertEquals(5, versionOf(LineupCodec.encode(lineup)))
        assertEquals(5, versionOf(LineupCodec.encode(lineup, repairs = repairs)))
        // A file with both holds both, and reads back as written.
        val both = loaded(LineupCodec.encode(lineup, repairs = repairs))
        assertEquals(lineup, both.lineup)
        assertEquals(repairs.stored(), both.repairs)
    }

    @Test
    fun aVersion5FileWithNoMarksLeftLoadsAndTheNextSaveWritesVersion3Or4() {
        val repairs = TodayRepairs(selection = listOf(repairOf()))
        val fiveWithRepairs = tree(LineupCodec.encode(lineup, repairs = repairs))
        val cleared =
            fiveWithRepairs.withChannel(0) { JsonObject(it - "addedByViewer") }.bytes()

        val read = loaded(cleared)

        assertEquals(5, read.fileVersion)
        assertEquals(ViewerChoice.Touched, read.lineup.channels[0].viewerChoice)
        assertEquals(4, versionOf(LineupCodec.encode(read.lineup, read.meta, read.repairs)))
        assertEquals(3, versionOf(LineupCodec.encode(read.lineup)))
    }

    @Test
    fun aVersion4FileWithRepairsStillLoadsAfterTheBump() {
        val file = checkNotNull(javaClass.getResourceAsStream("/lineup/lineup-v4-repairs.json")).readBytes()
        assertEquals(4, loaded(file).fileVersion)
    }

    @Test
    fun theKeyBelowVersion5IsUnreadable() {
        val five = tree(LineupCodec.encode(lineup))
        for (version in 1..4) {
            assertEquals(
                LineupCodec.Decoded.Unreadable,
                decode(five.with("version", JsonPrimitive(version)).bytes()),
                "version $version",
            )
        }
    }

    // The raw field is checked before the holder rule: a channel that could not hold the mark still makes a file below
    // version 5 unreadable.
    @Test
    fun theKeyBelowVersion5IsUnreadableOnAChannelThatCannotHoldIt() {
        val three = tree(LineupCodec.encode(lineupOf(plain, customChannel)))
        val withKey = three.addedOn(1)
        assertEquals(LineupCodec.Decoded.Unreadable, decode(withKey.bytes()))
        assertEquals(3, (three["version"] as JsonPrimitive).content.toInt())
    }

    @Test
    fun theKeyMustBeTrue() {
        val five = tree(LineupCodec.encode(lineup))
        val badValues =
            listOf(JsonPrimitive(false), JsonNull, JsonPrimitive("true"), JsonPrimitive(1), JsonObject(emptyMap()))
        for (value in badValues) {
            val file = five.withChannel(0) { it.with("addedByViewer", value) }
            assertEquals(LineupCodec.Decoded.Unreadable, decode(file.bytes()), "$value")
        }
        // On a channel that cannot hold it, too: checked before the holder rule.
        val onPlain = five.withChannel(1) { it.with("addedByViewer", JsonPrimitive(false)) }
        assertEquals(LineupCodec.Decoded.Unreadable, decode(onPlain.bytes()))
    }

    @Test
    fun theKeyOnAChannelThatCannotHoldItIsDroppedQuietly() {
        val edited =
            starter("edited", 120, ChannelState.On, comedy).let {
                val source = (it.source as ChannelSource.Starter).copy(edited = true)
                it.copy(source = source, viewerChoice = ViewerChoice.Touched)
            }
        val leftovers = leftovers("odds-and-ends", 199, null).copy(viewerChoice = ViewerChoice.Touched)
        val three = tree(LineupCodec.encode(lineupOf(added, edited, customChannel, leftovers)))
        for (index in 1..3) {
            val file = three.with("version", JsonPrimitive(5)).addedOn(index)
            val read = loaded(file.bytes())
            assertEquals(ViewerChoice.Touched, read.lineup.channels[index].viewerChoice, "channel $index")
            // The next save writes it as an ordinary choice and, with the one mark left, version 5.
            assertEquals(5, versionOf(LineupCodec.encode(read.lineup)))
        }
    }

    // With the mark dropped and no other, the next save is version 3 again.
    @Test
    fun aDroppedKeyLeavesNothingForVersion5() {
        val three = tree(LineupCodec.encode(lineupOf(plain, customChannel)))
        val file = three.with("version", JsonPrimitive(5)).addedOn(1)

        val read = loaded(file.bytes())

        assertEquals(5, read.fileVersion)
        assertEquals(3, versionOf(LineupCodec.encode(read.lineup)))
    }

    @Test
    fun theKeyWithoutChosenByViewerIsUnreadable() {
        val five = tree(LineupCodec.encode(lineup))
        val file = five.withChannel(0) { JsonObject(it - "chosenByViewer") }
        assertEquals(LineupCodec.Decoded.Unreadable, decode(file.bytes()))
    }

    // A starter this app cannot read (its rules need a newer app) keeps the mark, so the next save writes the file as
    // it was.
    @Test
    fun anUnusableStarterKeepsItsMarkAndWritesBackByteForByte() {
        val file = fixture("lineup-file-v5-unusable-added.json")

        val read = loaded(file)

        val channel = read.lineup.channels[0]
        assertIs<ChannelSource.Unusable>(channel.source)
        assertEquals(ViewerChoice.Added, channel.viewerChoice)
        assertTrue(file.contentEquals(LineupCodec.encode(read.lineup, read.meta, read.repairs)))
    }

    @Test
    fun anUnusableEditedStarterDropsTheMark() {
        val file = tree(fixture("lineup-file-v5-unusable-added.json"))
        val edited =
            file.withChannel(0) { c ->
                val source = c.getValue("source").jsonObject.with("edited", JsonPrimitive(true))
                c.with("source", source)
            }

        val read = loaded(edited.bytes())

        assertEquals(ViewerChoice.Touched, read.lineup.channels[0].viewerChoice)
    }

    @Test
    fun aSourceNestedTooDeepCannotHoldTheMark() {
        val deep = (1..40).fold<Int, JsonElement>(JsonPrimitive(1)) { inner, _ -> JsonObject(mapOf("a" to inner)) }
        val five = tree(LineupCodec.encode(lineup))
        val file = five.withChannel(0) { it.with("source", deep) }

        val read = loaded(file.bytes())

        assertIs<ChannelSource.Unusable>(read.lineup.channels[0].source)
        assertEquals(ViewerChoice.Touched, read.lineup.channels[0].viewerChoice)
    }

    // decode(encode(x)) keeps the mark for every source that can hold it, and is a Touched for every one that cannot.
    // A starter source whose rules this app cannot read.
    private fun unusable(edited: Boolean) = """{"kind":"starter","recipeKey":"x","edited":$edited,"rules":"newer"}"""

    @Test
    fun theMarkRoundTripsForEverySourceType() {
        val unusableStarter = ChannelSource.Unusable(unusable(edited = false))
        val unusableEdited = ChannelSource.Unusable(unusable(edited = true))
        val unusableCustom = ChannelSource.Unusable("""{"kind":"custom","rules":"newer"}""")
        val editedStarter = (plain.source as ChannelSource.Starter).copy(edited = true)
        val cases: List<Pair<ChannelSource, ViewerChoice>> =
            listOf(
                plain.source to ViewerChoice.Added,
                editedStarter to ViewerChoice.Touched,
                ChannelSource.Custom(comedy) to ViewerChoice.Touched,
                ChannelSource.Leftovers("odds-and-ends", null) to ViewerChoice.Touched,
                unusableStarter to ViewerChoice.Added,
                unusableEdited to ViewerChoice.Touched,
                unusableCustom to ViewerChoice.Touched,
            )
        for ((source, expected) in cases) {
            val channel: LineupChannel = channel("starter:t", 300, ChannelState.On, source).copy(viewerChoice = Added)
            val one: Lineup = lineupOf(channel)
            val read = loaded(LineupCodec.encode(one))
            assertEquals(expected, read.lineup.channels[0].viewerChoice, "$source")
            assertEquals(if (expected == ViewerChoice.Added) 5 else 3, read.fileVersion, "$source")
        }
    }
}
