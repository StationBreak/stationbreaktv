// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.engine.SeededGen
import tv.stationbreak.core.engine.forAllCases
import tv.stationbreak.core.engine.rules
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LineupEditsTest {
    // A real kid key: KidStatus reads StarterCatalog.EVER_KID_SAFE_KEYS, not the catalog passed to the edits.
    private val kidKey = StarterCatalog.EVER_KID_SAFE_KEYS.first()

    private val catalog =
        TestCatalog(
            listOf(
                recipeOf("western", 310),
                recipeOf("mystery", 205),
                recipeOf("spicy", 330, optIn = true),
            ),
            retiredNumbers = setOf(301),
        )

    private val someRules = rules(listOf(Source.Genre("drama")))

    private fun starter(
        key: String,
        number: Int?,
        state: ChannelState = ChannelState.On,
        override: Int? = null,
        id: ChannelId = LineupIds.starter(key),
    ) = LineupChannel(id, number, override, null, state, ChannelSource.Starter(key, false, someRules), 5L)

    private fun custom(
        name: String,
        number: Int?,
        state: ChannelState = ChannelState.Shelf,
    ) = LineupChannel(ChannelId("custom:$name"), null, number, null, state, ChannelSource.Custom(someRules), 5L)

    private fun lineupOf(vararg channels: LineupChannel) = Lineup(SERVER, USER, 1, 0, true, channels.toList())

    private fun apply(
        lineup: Lineup,
        edit: LineupEdit,
    ): EditOutcome = LineupEdits.apply(lineup, edit, 99L, catalog)

    private fun applied(
        lineup: Lineup,
        edit: LineupEdit,
    ): Lineup = assertIs<EditOutcome.Applied>(apply(lineup, edit)).lineup

    private fun refusal(
        lineup: Lineup,
        edit: LineupEdit,
    ): EditRefusal = assertIs<EditOutcome.Refused>(apply(lineup, edit)).refusal

    private fun Lineup.byId(id: ChannelId) = channels.first { it.id == id }

    private val western = LineupIds.starter("western")
    private val mystery = LineupIds.starter("mystery")

    @Test
    fun switchOnTurnsAShelfOrHiddenChannelOnAndStampsTheTime() {
        val lineup =
            lineupOf(starter("western", 310, ChannelState.Shelf), starter("mystery", 205, ChannelState.UserHidden))
        val after = applied(applied(lineup, LineupEdit.SwitchOn(western)), LineupEdit.SwitchOn(mystery))
        for (id in listOf(western, mystery)) {
            assertEquals(ChannelState.On, after.byId(id).state)
            assertEquals(99L, after.byId(id).onSinceMs)
        }
    }

    @Test
    fun switchOnAnOnChannelChangesNothing() {
        val lineup = lineupOf(starter("western", 310))
        assertEquals(lineup, applied(lineup, LineupEdit.SwitchOn(western)))
    }

    @Test
    fun switchOnGivesANumberlessKidChannelTheLowestFreeKidsNumber() {
        val kid = starter(kidKey, null, ChannelState.Shelf)
        val lineup = lineupOf(starter("western", 310), starter("mystery", 1), starter("other", null, override = 2), kid)
        val after = applied(lineup, LineupEdit.SwitchOn(kid.id))
        assertEquals(3, after.byId(kid.id).numberOverride)
        assertNull(after.byId(kid.id).defaultNumber)
    }

    @Test
    fun switchOnGivesAStarterTheLowestFreeNumberInItsRecipeBand() {
        val lineup = lineupOf(starter("western", null, ChannelState.Shelf), starter("other", 300), custom("x", 303))
        assertEquals(302, applied(lineup, LineupEdit.SwitchOn(western)).byId(western).number)
    }

    @Test
    fun switchOnSkipsDefaultAndRetiredNumbers() {
        // 300 is another channel's default (although overridden away), 301 is retired.
        val lineup = lineupOf(starter("western", null, ChannelState.Shelf), starter("other", 300, override = 700))
        val after = applied(lineup, LineupEdit.SwitchOn(western))
        assertEquals(302, after.byId(western).numberOverride)
    }

    @Test
    fun switchOnGivesGrownUpLeftoversTheGeneralBandAndACustomChannelItsOwnBand() {
        val odds =
            LineupChannel(
                LineupIds.leftovers("odds"),
                null,
                null,
                null,
                ChannelState.Shelf,
                ChannelSource.Leftovers("odds", null),
                0L,
            )
        val mine = custom("mine", null)
        val after = applied(applied(lineupOf(odds, mine), LineupEdit.SwitchOn(odds.id)), LineupEdit.SwitchOn(mine.id))
        assertEquals(100, after.byId(odds.id).number)
        assertEquals(LineupDefaults.CUSTOM_NUMBERS.first, after.byId(mine.id).number)
    }

    @Test
    fun switchOnFallsBackToTheLowestFreeNumberFrom100() {
        // A starter whose recipe has left the catalog has no band.
        val orphan = starter("gone", null, ChannelState.Shelf)
        val lineup = lineupOf(orphan, starter("other", 100), starter("more", 101))
        assertEquals(102, applied(lineup, LineupEdit.SwitchOn(orphan.id)).byId(orphan.id).number)
    }

    @Test
    fun switchOnWithNoFreeNumberIsRefused() {
        val full = (1..99).map { starter("k$it", it) }
        val kid = starter(kidKey, null, ChannelState.Shelf)
        val lineup = lineupOf(kid, *full.toTypedArray())
        assertEquals(EditRefusal.NoFreeNumber, refusal(lineup, LineupEdit.SwitchOn(kid.id)))
    }

    @Test
    fun switchOnIgnoresTheCap() {
        val on = (0 until LineupDefaults.CAP).map { starter("k$it", 300 + it) }
        val lineup = lineupOf(starter("western", null, ChannelState.Shelf, id = western), *on.toTypedArray())
        val after = applied(lineup, LineupEdit.SwitchOn(western))
        assertEquals(LineupDefaults.CAP + 1, after.channels.count { it.state == ChannelState.On })
    }

    @Test
    fun switchOnRefusesAnUnusableChannel() {
        val bad = LineupChannel(ChannelId("bad"), 5, null, null, ChannelState.Shelf, ChannelSource.Unusable("{}"), 0L)
        assertEquals(EditRefusal.NotSwitchable, refusal(lineupOf(bad), LineupEdit.SwitchOn(bad.id)))
    }

    @Test
    fun switchOnRefusesAnOptInStarterUnconfirmed() {
        val spicy = LineupIds.starter("spicy")
        val lineup = lineupOf(starter("spicy", 330, ChannelState.Shelf))
        assertEquals(EditRefusal.OptInNotConfirmed, refusal(lineup, LineupEdit.SwitchOn(spicy)))
        val confirmed = applied(lineup, LineupEdit.SwitchOn(spicy, confirmedOptIn = true))
        assertEquals(ChannelState.On, confirmed.byId(spicy).state)
    }

    @Test
    fun anEditOfAnUnknownChannelIsRefused() {
        val lineup = lineupOf(starter("western", 310))
        val ghost = ChannelId("ghost")
        val edits =
            listOf(
                LineupEdit.SwitchOn(ghost),
                LineupEdit.SwitchOff(ghost),
                LineupEdit.SetNumber(ghost, 5),
                LineupEdit.ClearNumber(ghost),
            )
        for (edit in edits) assertEquals(EditRefusal.UnknownChannel, refusal(lineup, edit), "$edit")
    }

    @Test
    fun switchOffHidesAnOnOrShelvedChannel() {
        val other = LineupIds.starter("other")
        val shelved = starter("other", 300, ChannelState.Shelf)
        val lineup = lineupOf(starter("western", 310), starter("mystery", 205), shelved)
        val after = applied(applied(lineup, LineupEdit.SwitchOff(western)), LineupEdit.SwitchOff(other))
        assertEquals(ChannelState.UserHidden, after.byId(western).state)
        assertEquals(ChannelState.UserHidden, after.byId(other).state)
        assertEquals(ChannelState.On, after.byId(mystery).state)
    }

    @Test
    fun switchOffRefusesTheLastOnChannel() {
        val lineup = lineupOf(starter("western", 310), starter("mystery", 205, ChannelState.Shelf))
        assertEquals(EditRefusal.LastOn, refusal(lineup, LineupEdit.SwitchOff(western)))
    }

    @Test
    fun setNumberRefusesZeroAnd1000() {
        val lineup = lineupOf(starter("western", 310))
        assertEquals(EditRefusal.OutOfRange, refusal(lineup, LineupEdit.SetNumber(western, 0)))
        assertEquals(EditRefusal.OutOfRange, refusal(lineup, LineupEdit.SetNumber(western, 1000)))
        assertEquals(999, applied(lineup, LineupEdit.SetNumber(western, 999)).byId(western).number)
    }

    @Test
    fun setNumberRefusesTheKidsBandForAGrownUpStarter() {
        // "western" is no kid key and sits at 310, outside the Kids band.
        val lineup = lineupOf(starter("western", 310))
        assertEquals(EditRefusal.KidsBandOnly, refusal(lineup, LineupEdit.SetNumber(western, 50)))
    }

    @Test
    fun setNumberAllowsAKidChannelAbove99() {
        val kid = starter(kidKey, 5)
        val after = applied(lineupOf(kid, starter("western", 310)), LineupEdit.SetNumber(kid.id, 450))
        assertEquals(450, after.byId(kid.id).number)
    }

    @Test
    fun setNumberAllowsAKidChannelInsideTheKidsBand() {
        val kid = starter(kidKey, 5)
        assertEquals(40, applied(lineupOf(kid), LineupEdit.SetNumber(kid.id, 40)).byId(kid.id).number)
    }

    @Test
    fun setNumberReportsTheHolderWithoutSwap() {
        val lineup = lineupOf(starter("western", 310), starter("mystery", 205))
        assertEquals(EditRefusal.NumberTaken(mystery), refusal(lineup, LineupEdit.SetNumber(western, 205)))
    }

    @Test
    fun setNumberChecksRangeAndKidsBandBeforeTaken() {
        val lineup = lineupOf(starter("western", 310), starter("mystery", 20))
        assertEquals(EditRefusal.KidsBandOnly, refusal(lineup, LineupEdit.SetNumber(western, 20)))
    }

    @Test
    fun setNumberWithTheMatchingSwapTradesNumbers() {
        val lineup = lineupOf(starter("western", 310), starter("mystery", 205))
        val after = applied(lineup, LineupEdit.SetNumber(western, 205, swapWith = mystery))
        assertEquals(205, after.byId(western).number)
        assertEquals(310, after.byId(mystery).number)
    }

    @Test
    fun swapWithAChannelThatNoLongerHoldsTheNumberIsRefused() {
        val lineup = lineupOf(starter("western", 310), starter("mystery", 205), starter("other", 300))
        val other = LineupIds.starter("other")
        val edit = LineupEdit.SetNumber(western, 205, swapWith = other)
        assertEquals(EditRefusal.NumberTaken(mystery), refusal(lineup, edit))
    }

    @Test
    fun swapRefusesSendingAGrownUpChannelIntoTheKidsBand() {
        val kid = starter(kidKey, 5)
        val lineup = lineupOf(kid, starter("western", 310))
        val edit = LineupEdit.SetNumber(kid.id, 310, swapWith = western)
        assertEquals(EditRefusal.KidsBandOnly, refusal(lineup, edit))
    }

    @Test
    fun swapGivesTheHolderANumberWhenTheEditedChannelHadNone() {
        val shelved = starter("western", null, ChannelState.Shelf)
        val lineup = lineupOf(shelved, starter("mystery", 205))
        val after = applied(lineup, LineupEdit.SetNumber(western, 205, swapWith = mystery))
        assertEquals(205, after.byId(western).number)
        // 205 is now held, so the holder takes the next free number in its band.
        assertEquals(200, after.byId(mystery).number)
    }

    @Test
    fun settingAChannelToItsOwnNumberChangesNothing() {
        val lineup = lineupOf(starter("western", 310, override = 700))
        assertEquals(lineup, applied(lineup, LineupEdit.SetNumber(western, 700)))
    }

    @Test
    fun anOverrideEqualToTheDefaultIsStoredAsNull() {
        val lineup = lineupOf(starter("western", 310, override = 700))
        assertNull(applied(lineup, LineupEdit.SetNumber(western, 310)).byId(western).numberOverride)
        val moved = applied(lineupOf(starter("western", 310)), LineupEdit.SetNumber(western, 320))
        assertEquals(320, moved.byId(western).numberOverride)
    }

    @Test
    fun clearNumberGoesBackToTheDefault() {
        val lineup = lineupOf(starter("western", 310, override = 700))
        val after = applied(lineup, LineupEdit.ClearNumber(western))
        assertNull(after.byId(western).numberOverride)
        assertEquals(310, after.byId(western).number)
    }

    @Test
    fun clearNumberWithoutADefaultChangesNothing() {
        val lineup = lineupOf(custom("mine", 700))
        assertEquals(lineup, applied(lineup, LineupEdit.ClearNumber(ChannelId("custom:mine"))))
    }

    @Test
    fun clearNumberReportsTheHolderOfTheDefault() {
        val lineup = lineupOf(starter("western", 310, override = 700), starter("mystery", 205, override = 310))
        assertEquals(EditRefusal.NumberTaken(mystery), refusal(lineup, LineupEdit.ClearNumber(western)))
    }

    @Test
    fun clearNumberWithSwapTradesNumbers() {
        val lineup = lineupOf(starter("western", 310, override = 700), starter("mystery", 205, override = 310))
        val after = applied(lineup, LineupEdit.ClearNumber(western, swapWith = mystery))
        assertEquals(310, after.byId(western).number)
        assertEquals(700, after.byId(mystery).number)
    }

    @Test
    fun everyAppliedLineupKeepsTheInvariants() {
        var applied = 0
        var refused = 0
        forAllCases("lineup-edits", 0x45444954uL, CASES) { gen ->
            var lineup = randomLineup(gen)
            repeat(EDITS) {
                val edit = randomEdit(gen, lineup)
                when (val outcome = LineupEdits.apply(lineup, edit, 7L, catalog)) {
                    is EditOutcome.Applied -> {
                        // Building a Lineup checks the invariants; this also checks nothing else was lost.
                        val ids = lineup.channels.map { c -> c.id }
                        assertEquals(ids, outcome.lineup.channels.map { c -> c.id }, "$edit")
                        lineup = outcome.lineup
                        applied++
                    }

                    is EditOutcome.Refused -> {
                        refused++
                    }
                }
            }
        }
        assertTrue(applied > 1_000, "applied: $applied")
        assertTrue(refused > 1_000, "refused: $refused")
    }

    private fun randomLineup(gen: SeededGen): Lineup {
        val count = gen.nextInt(2..8)
        val numbers = (listOf(1, 2, 3, 150, 205, 310, 311, 320, 700, 701) + (4..12)).distinct()
        val picked = mutableSetOf<Int>()
        val channels =
            List(count) { i ->
                val number = numbers[gen.nextInt(numbers.size)].takeIf { gen.chance(80) && picked.add(it) }
                val state = if (number == null) ChannelState.Shelf else gen.pick(ChannelState.entries)
                when (gen.nextInt(3)) {
                    0 -> starter(if (i == 0) kidKey else "k$i", number, state, id = ChannelId("c$i"))
                    1 -> custom("c$i", number, state).let { it.copy(id = ChannelId("c$i")) }
                    else -> starter("western", number, state, id = ChannelId("c$i"))
                }
            }
        return Lineup(SERVER, USER, 1, 0, true, channels)
    }

    private fun randomEdit(
        gen: SeededGen,
        lineup: Lineup,
    ): LineupEdit {
        val ids = lineup.channels.map { it.id } + ChannelId("ghost")
        val id = gen.pick(ids)
        val holders = lineup.channels.filter { it.number != null }
        val number = if (holders.isNotEmpty() && gen.chance(60)) gen.pick(holders).number!! else gen.nextInt(0..1001)
        val swap = if (gen.chance(50)) gen.pick(ids) else null
        return when (gen.nextInt(4)) {
            0 -> LineupEdit.SwitchOn(id, confirmedOptIn = gen.chance(50))
            1 -> LineupEdit.SwitchOff(id)
            2 -> LineupEdit.SetNumber(id, number, swap)
            else -> LineupEdit.ClearNumber(id, swap)
        }
    }

    private companion object {
        const val CASES = 300
        const val EDITS = 12
    }
}
