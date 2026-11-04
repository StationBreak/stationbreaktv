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
import tv.stationbreak.core.engine.lineup.Anchor
import tv.stationbreak.core.engine.lineup.AnchorKind
import tv.stationbreak.core.engine.lineup.Band
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.CurationBasis
import tv.stationbreak.core.engine.lineup.LibraryNames
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupDefaults
import tv.stationbreak.core.engine.lineup.NameMatcher
import tv.stationbreak.core.engine.lineup.Recipe
import tv.stationbreak.core.engine.lineup.RecipeResolver
import tv.stationbreak.core.engine.lineup.ShelfReason
import tv.stationbreak.core.engine.lineup.Theme
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.CollectionNames
import tv.stationbreak.core.model.LineupLimits
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.RulesWriter
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

    // Every catalog starter resolved to the budget (collection IDs, 200-character non-ASCII studio names, a mix) and
    // topped up with name padding to within a few bytes of it, the cap of them on with previousRules, both leftovers,
    // five custom channels at 256 sources, a full curation basis and a refresher meta. What is bounded: the rules
    // copies of the starters total at most half the file, and the whole file stays under the file cap.
    @Test
    fun aWorstCaseLineupStaysUnderTheCap() {
        val collections = CollectionNames((1..300).associate { id(0x1000 + it) to "Box Set  Collection" })
        val library = LibraryNames(RuleSnapshot(0L, emptySet(), emptyList(), emptyMap(), emptyMap(), true), collections)
        val boxSets = Anchor(AnchorKind.Collection, NameMatcher.Prefix("box set"))
        val longStudios = (1..60).map { Source.Studio("é".repeat(200) + it) }
        val comedy = listOf<Source>(Source.Genre("Comedy"))
        val shapes =
            listOf(
                Theme(listOf(boxSets), emptyList(), null, false) to comedy,
                Theme(emptyList(), emptyList(), null, false) to comedy + longStudios,
                Theme(listOf(boxSets), emptyList(), null, false) to comedy + longStudios.take(3),
            )

        // The recipe is named first, so the resolver measures the name; padding then closes the gap to the budget.
        fun resolved(n: Int): ChannelRules {
            val (theme, sources) = shapes[n % shapes.size]
            val recipe =
                Recipe(
                    "s$n",
                    "Starter $n",
                    "Synthetic.",
                    Band.General,
                    110,
                    false,
                    sources,
                    emptyList(),
                    null,
                    OrderingMode.Curated,
                    emptyList(),
                    theme = theme,
                )
            val rules = RecipeResolver.resolve(recipe, SERVER, 0, collections, library)
            return paddedToBudget(rules)
        }
        val copies = ArrayList<ChannelRules>()
        val starters =
            (1..RecipeResolver.MAX_CATALOG_STARTERS).map { n ->
                val now = resolved(n)
                val on = n <= LineupDefaults.CAP
                copies += now
                val source =
                    if (on) {
                        val before = now
                        copies += before
                        ChannelSource.Starter("s$n", false, now, before, rulesFromMs = 1_792_000_000_000L)
                    } else {
                        ChannelSource.Starter("s$n", false, now)
                    }
                channel("starter:s$n", 1000 + n, if (on) ChannelState.On else ChannelState.Shelf, source)
            }
        val others =
            listOf(leftovers("kids-odds-and-ends", 99, "PG"), leftovers("odds-and-ends", 199, null)) +
                (1..5).map { n ->
                    val many = (1..256).map { Source.Collection(id(n * 1000 + it)) }
                    val mine = ChannelSource.Custom(rules("Mine $n", *many.toTypedArray()))
                    channel("custom-$n", 600 + n, ChannelState.On, mine)
                }
        val basis = CurationBasis(7_000L, starters.filter { it.state == ChannelState.On }.map { it.id }.toSet(), true)
        val lineup = lineupOf(*(starters + others).toTypedArray()).copy(curationBasis = basis)
        val meta = RefreshMeta(4L, 5L, (1..10).map { "library-$it" }, 6L)

        val copyBytes = copies.sumOf { fileBytes(it) }
        val total = LineupCodec.encode(lineup, meta).size

        println("worst case: ${copies.size} copies $copyBytes bytes, file $total of ${LineupLimits.MAX_FILE_BYTES}")
        assertEquals(RecipeResolver.MAX_CATALOG_STARTERS + LineupDefaults.CAP, copies.size)
        for (rules in copies) {
            val bytes = fileBytes(rules)
            assertTrue(bytes <= RecipeResolver.STARTER_RULES_BUDGET_BYTES, "a copy takes $bytes bytes")
            assertTrue(bytes >= RecipeResolver.STARTER_RULES_BUDGET_BYTES - 16, "a copy takes only $bytes bytes")
        }
        assertTrue(copyBytes <= LineupLimits.MAX_FILE_BYTES / 2, "the rules copies take $copyBytes bytes")
        assertTrue(total <= LineupLimits.MAX_FILE_BYTES, "the whole file takes $total bytes")
    }

    // [rules] with tag sources added (the longest that still fits, one after another), then a longer name for the last
    // few bytes: a copy that sits at the budget.
    private fun paddedToBudget(rules: ChannelRules): ChannelRules {
        val budget = RecipeResolver.STARTER_RULES_BUDGET_BYTES

        fun withTag(
            from: ChannelRules,
            index: Int,
            length: Int,
        ) = from.copy(sources = from.sources + Source.Tag("p$index" + "y".repeat(length)))
        var padded = rules
        var index = 0
        while (fileBytes(withTag(padded, index, 0)) <= budget) {
            var length = 0
            while (length < MAX_PAD && fileBytes(withTag(padded, index, length + 1)) <= budget) length++
            padded = withTag(padded, index, length)
            index++
        }
        return padded.copy(name = padded.name + "x".repeat(budget - fileBytes(padded)))
    }

    private fun fileBytes(rules: ChannelRules): Int {
        val text = JsonPrimitive(RulesWriter.write(rules)).toString()
        return text.encodeToByteArray().size
    }

    @Test
    fun anUnknownShelfReasonOnANonStarterIsIgnoredButNotOnAStarter() {
        val lineup = lineupOf(starterChannel("a", 110), channel("custom-1", 600, ChannelState.On, custom()))
        val odd = JsonPrimitive("someFutureReason")

        val onCustom = tree(lineup).withChannel(1) { it.with("shelfReason", odd) }
        assertEquals(lineup, loaded(onCustom.toString()).lineup)

        val onStarter = tree(lineup).withChannel(0) { it.with("shelfReason", odd) }
        assertEquals(LineupCodec.Decoded.Unreadable, decode(onStarter.toString()))
    }

    // A new ShelfReason value (or any schema change) is a new file version: older apps must see NewerVersion.
    @Test
    fun theShelfReasonsArePinnedToTheFileVersion() {
        assertEquals(listOf("Empty", "TooThin", "BelowCap", "OptIn"), ShelfReason.entries.map { it.name }, GUARD)
        assertEquals(3, LineupCodec.VERSION, GUARD)
    }

    @Test
    fun aVersionFourFileIsNewer() {
        assertEquals(LineupCodec.Decoded.NewerVersion(4), decode("""{"version":4,"useMyCollections":false}"""))
    }

    private companion object {
        const val MAX_PAD = 200

        const val GUARD = "A new ShelfReason value is a schema change: bump LineupCodec.VERSION and update this test"
    }
}
