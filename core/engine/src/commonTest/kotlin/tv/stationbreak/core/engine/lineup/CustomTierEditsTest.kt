// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.engine.rules
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #1152: a custom channel's tier is its number band (Tots 1..33, Kids 34..66, Teens 67..99); the edits keep to it. */
class CustomTierEditsTest {
    private val catalog = TestCatalog(listOf(recipeOf("western", 310)), retiredNumbers = emptySet())
    private val newId = ChannelId("custom:new")
    private val mine = ChannelId("custom:mine")

    private fun rulesOf(max: String?): ChannelRules = rules(listOf(Source.Genre("drama")), maxRating = max)

    private fun custom(
        name: String,
        number: Int,
        ceiling: String? = "PG",
    ) = LineupChannel(
        ChannelId("custom:$name"),
        null,
        number,
        null,
        ChannelState.On,
        ChannelSource.Custom(rulesOf(ceiling)),
        5L,
    )

    private fun lineupOf(vararg channels: LineupChannel) = Lineup(SERVER, USER, 1, 0, true, channels.toList())

    private fun apply(
        lineup: Lineup,
        edit: LineupEdit,
    ) = LineupEdits.apply(lineup, edit, 99L, 99L + DAY_MS, catalog)

    private fun applied(
        lineup: Lineup,
        edit: LineupEdit,
    ) = assertIs<EditOutcome.Applied>(apply(lineup, edit)).lineup

    private fun refusal(
        lineup: Lineup,
        edit: LineupEdit,
    ) = assertIs<EditOutcome.Refused>(apply(lineup, edit)).refusal

    private fun add(
        number: Int?,
        band: Band?,
        ceiling: String?,
    ) = LineupEdit.AddCustom(newId, rulesOf(ceiling), number, band, customLimit = null)

    private fun Lineup.byId(id: ChannelId) = channels.first { it.id == id }

    private fun filling(range: IntRange) = range.map { custom("c$it", it) }.toTypedArray()

    @Test
    fun aNewChannelWithNoNumberTakesTheFirstFreeNumberOfItsBand() {
        val lineup = lineupOf(custom("a", 34), custom("b", 67))

        assertEquals(35, applied(lineup, add(null, Band.Family, "PG")).byId(newId).number)
        assertEquals(68, applied(lineup, add(null, Band.Teen, "TV-14")).byId(newId).number)
        assertEquals(1, applied(lineup, add(null, Band.Kids, "PG")).byId(newId).number)
    }

    @Test
    fun aNumberOutsideTheRequestedBandIsRefusedTotsAsBefore() {
        val lineup = lineupOf(custom("a", 650))

        assertEquals(EditRefusal.KidsBandOnly, refusal(lineup, add(40, Band.Kids, "PG")))
        assertEquals(EditRefusal.OutsideBand(Band.Family), refusal(lineup, add(5, Band.Family, "PG")))
        assertEquals(EditRefusal.OutsideBand(Band.Family), refusal(lineup, add(70, Band.Family, "PG")))
        assertEquals(EditRefusal.OutsideBand(Band.Teen), refusal(lineup, add(40, Band.Teen, "TV-14")))
        assertEquals(EditRefusal.OutsideBand(Band.Teen), refusal(lineup, add(700, Band.Teen, "TV-14")))
        assertEquals(EditRefusal.KidsBandOnly, refusal(lineup, add(700, Band.Kids, "PG")))
    }

    @Test
    fun aNumberInTheBandIsAddedAtItsCeilingFloor() {
        val lineup = lineupOf(custom("a", 700))

        assertEquals(40, applied(lineup, add(40, Band.Family, "PG")).byId(newId).number)
        assertEquals(80, applied(lineup, add(80, Band.Teen, "TV-14")).byId(newId).number)
        // PG is fine in the Teen band; PG-13 is not fine in the Family one (its floor is 67).
        assertEquals(80, applied(lineup, add(80, Band.Teen, "PG")).byId(newId).number)
        assertEquals(EditRefusal.BelowBand(Band.Teen), refusal(lineup, add(40, Band.Family, "PG-13")))
        assertEquals(EditRefusal.BelowBand(Band.General), refusal(lineup, add(80, Band.Teen, "R")))
    }

    @Test
    fun eachYoungBandRunsOutOnItsOwn() {
        val familyFull = lineupOf(*filling(Band.Family.numbers))
        val teenFull = lineupOf(*filling(Band.Teen.numbers))

        assertEquals(EditRefusal.NoFreeNumber, refusal(familyFull, add(null, Band.Family, "PG")))
        assertEquals(EditRefusal.NoFreeNumber, refusal(teenFull, add(null, Band.Teen, "TV-14")))
        // A full band leaves the others free.
        assertEquals(1, applied(familyFull, add(null, Band.Kids, "PG")).byId(newId).number)
        assertEquals(67, applied(familyFull, add(null, Band.Teen, "TV-14")).byId(newId).number)
        assertNull(LineupEdits.firstFreeCustom(teenFull, Band.Teen, catalog))
        assertEquals(34, LineupEdits.firstFreeCustom(teenFull, Band.Family, catalog))
    }

    @Test
    fun replaceMovesAChannelBetweenTiersByNumber() {
        val lineup = lineupOf(custom("mine", 40), custom("other", 67))

        val toTeens = LineupEdit.ReplaceCustom(mine, rulesOf("TV-14"), null, band = Band.Teen)
        val toKids = LineupEdit.ReplaceCustom(mine, rulesOf("PG"), 3, band = Band.Kids)
        val toGrownUp = LineupEdit.ReplaceCustom(mine, rulesOf("PG"), null, band = null)

        assertEquals(68, applied(lineup, toTeens).byId(mine).number)
        assertTrue(KidStatus.isKidChannel(applied(lineup, toKids).byId(mine)))
        // Off the family tiers it takes the first free grown-up number, never a young one it already holds.
        assertEquals(600, applied(lineup, toGrownUp).byId(mine).number)
    }

    @Test
    fun replaceWithNoNumberKeepsANumberAlreadyInTheTier() {
        val lineup = lineupOf(custom("mine", 70), custom("kid", 40))

        val same = LineupEdit.ReplaceCustom(mine, rulesOf("PG"), null, band = Band.Teen)

        assertEquals(70, applied(lineup, same).byId(mine).number)
    }

    @Test
    fun replaceToANumberOutsideTheTierIsRefused() {
        val lineup = lineupOf(custom("mine", 40))

        assertEquals(
            EditRefusal.OutsideBand(Band.Teen),
            refusal(lineup, LineupEdit.ReplaceCustom(mine, rulesOf("TV-14"), 50, band = Band.Teen)),
        )
        assertEquals(
            EditRefusal.NoFreeNumber,
            refusal(
                lineupOf(custom("mine", 700), *filling(Band.Teen.numbers)),
                LineupEdit.ReplaceCustom(mine, rulesOf("TV-14"), null, band = Band.Teen),
            ),
        )
    }

    @Test
    fun aGrownUpBandIsNotAYoungOne() {
        assertFailsWith<IllegalArgumentException> { add(700, Band.General, null) }
        assertFailsWith<IllegalArgumentException> {
            LineupEdit.ReplaceCustom(mine, rulesOf(null), null, band = Band.Drama)
        }
    }

    @Test
    fun aKidsOrTeensChannelIsNotAKidChannelButTheTotsOneIs() {
        val lineup = lineupOf(custom("a", 700))

        assertTrue(!KidStatus.isKidChannel(applied(lineup, add(40, Band.Family, "PG")).byId(newId)))
        assertTrue(!KidStatus.isKidChannel(applied(lineup, add(80, Band.Teen, "TV-14")).byId(newId)))
        assertTrue(KidStatus.isKidChannel(applied(lineup, add(5, Band.Kids, "PG")).byId(newId)))
    }
}
