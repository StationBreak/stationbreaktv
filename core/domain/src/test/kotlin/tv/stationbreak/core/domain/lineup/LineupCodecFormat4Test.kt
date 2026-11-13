// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.Filters
import tv.stationbreak.core.rules.Ordering
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Rules read from the lineup file (a stored, imported or restored lineup) under format 4
 * (docs/adr/0026-studio-fold-2.md, Versioning, the upgrade): with the gate off they come out as written; once it has
 * passed, rules that say 3 and hold a studio feature come out saying 4 with nothing else changed, the same file read
 * twice is written once, and every other file is left alone.
 */
class LineupCodecFormat4Test {
    // Rules that say 3, with a curated order and an unknown field, which the upgrade must keep.
    private fun v3(
        name: String,
        source: Source,
        exclusions: List<Source>? = null,
        filters: Filters? = null,
    ) = ChannelRules(
        formatVersion = 3,
        name = name,
        seed = Seed(name.hashCode().toULong()),
        sources = listOf(source),
        exclusions = exclusions,
        maxRating = null,
        ordering = Ordering(OrderingMode.Curated, repeatGapMinutes = 90),
        timing = timing(),
        filler = null,
        filters = filters,
        extras = JsonObject(mapOf("futureField" to JsonPrimitive("kept"))),
    )

    private val studioSource = v3("Studio", Source.Studio("Studio A"))
    private val studioExclusion = v3("Not B", Source.Genre("Comedy"), exclusions = listOf(Source.Studio("Studio B")))
    private val studiosFilter =
        v3("Filtered", Source.Genre("Comedy"), filters = studiosFilterOf("Studio C"))
    private val noStudios = v3("Plain", Source.Genre("Drama"))

    private fun studiosFilterOf(name: String) = Filters(null, null, null, null, null, null, studios = listOf(name))

    private val version2 =
        rules("Two", Source.Genre("Drama")).copy(formatVersion = 2, ordering = Ordering(OrderingMode.Rotation))
    private val version1 = rules("One", Source.Genre("Drama"))
    private val alreadyFour = studioSource.copy(formatVersion = 4, name = "Four")

    private val lineup: Lineup =
        lineupOf(
            channel("custom-1", 600, ChannelState.On, ChannelSource.Custom(studioSource)),
            channel("custom-2", 601, ChannelState.On, ChannelSource.Custom(studioExclusion)),
            channel("custom-3", 602, ChannelState.On, ChannelSource.Custom(studiosFilter)),
            channel("custom-4", 603, ChannelState.On, ChannelSource.Custom(noStudios)),
            channel("custom-5", 604, ChannelState.On, ChannelSource.Custom(version2)),
            channel("custom-6", 605, ChannelState.On, ChannelSource.Custom(version1)),
            channel("custom-7", 606, ChannelState.On, ChannelSource.Custom(alreadyFour)),
            channel(
                "starter:studio",
                110,
                ChannelState.On,
                ChannelSource.Starter("studio", false, studioExclusion, previousRules = studioSource, rulesFromMs = 5L),
            ),
        )

    private fun loaded(
        bytes: ByteArray,
        gatePassed: Boolean,
    ) = assertIs<LineupCodec.Decoded.Loaded>(LineupCodec.decode(bytes, gatePassed)).lineup

    private fun rulesOf(lineup: Lineup): List<ChannelRules> =
        lineup.channels.flatMap {
            when (val source = it.source) {
                is ChannelSource.Custom -> listOf(source.rules)
                is ChannelSource.Starter -> listOfNotNull(source.rules, source.previousRules)
                else -> emptyList()
            }
        }

    @Test
    fun withTheGateOffNothingIsUpgraded() {
        val bytes = LineupCodec.encode(lineup)
        assertEquals(lineup, loaded(bytes, gatePassed = false))
    }

    @Test
    fun theAppDefaultIsPastTheGate() {
        val bytes = LineupCodec.encode(lineup)
        val byDefault = assertIs<LineupCodec.Decoded.Loaded>(LineupCodec.decode(bytes)).lineup
        assertEquals(loaded(bytes, gatePassed = true), byDefault)
    }

    @Test
    fun withTheGateOnStudioFilesThatSay3Say4AndNothingElseChanges() {
        val read = loaded(LineupCodec.encode(lineup), gatePassed = true)
        val expected =
            listOf(
                studioSource.copy(formatVersion = 4),
                studioExclusion.copy(formatVersion = 4),
                studiosFilter.copy(formatVersion = 4),
                noStudios,
                version2,
                version1,
                alreadyFour,
                studioExclusion.copy(formatVersion = 4),
                studioSource.copy(formatVersion = 4),
            )
        assertEquals(expected, rulesOf(read))
        assertEquals(lineup.channels.map { it.id }, read.channels.map { it.id })
    }

    @Test
    fun theSameFileReadTwiceIsWrittenOnce() {
        val once = LineupCodec.encode(loaded(LineupCodec.encode(lineup), gatePassed = true))
        val twice = LineupCodec.encode(loaded(once, gatePassed = true))
        assertContentEquals(once, twice)
    }

    @Test
    fun aFileThatSays4IsReadAsItIsEitherWay() {
        for (gate in listOf(false, true)) {
            val read = rulesOf(loaded(LineupCodec.encode(lineup), gatePassed = gate))
            assertEquals(alreadyFour, read[6], "gate $gate")
        }
    }

    @Test
    fun theDecodeCountsTheRulesItUpgraded() {
        val bytes = LineupCodec.encode(lineup)
        val upgraded = assertIs<LineupCodec.Decoded.Loaded>(LineupCodec.decode(bytes, gatePassed = true))
        // Three custom channels, and the starter's rules and previous rules.
        assertEquals(5, upgraded.upgradedRules)
        val before = assertIs<LineupCodec.Decoded.Loaded>(LineupCodec.decode(bytes, gatePassed = false))
        assertEquals(0, before.upgradedRules)
        val again = LineupCodec.decode(LineupCodec.encode(upgraded.lineup), gatePassed = true)
        assertEquals(0, assertIs<LineupCodec.Decoded.Loaded>(again).upgradedRules, "nothing is left to upgrade")
    }

    @Test
    fun theRulesFormatsCountEveryRulesFileAndTheUpgrades() {
        val bytes = LineupCodec.encode(lineup)
        val upgraded = assertIs<LineupCodec.Decoded.Loaded>(LineupCodec.decode(bytes, gatePassed = true))
        val formats = RulesFormats.of(upgraded.lineup, upgraded.upgradedRules)
        assertEquals(RulesFormats(v1 = 1, v2 = 1, v3 = 1, v4 = 6, upgraded = 5), formats)
        assertEquals("rules formats v1=1 v2=1 v3=1 v4=6 upgraded=5", formats.line())
        assertEquals(RulesFormats(v1 = 1, v2 = 1, v3 = 6, v4 = 1, upgraded = 0), RulesFormats.of(lineup, 0))
    }
}
