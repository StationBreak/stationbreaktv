// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.engine.rules
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.Ordering
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The lineup edits for custom channels (F2.3): adding, replacing, reseeding and removing. */
class CustomEditsTest {
    private val catalog = TestCatalog(listOf(recipeOf("western", 310)), retiredNumbers = setOf(601))

    private fun rulesOf(
        name: String,
        seed: ULong = 1uL,
    ): ChannelRules = rules(listOf(Source.Genre("drama"))).copy(name = name, seed = Seed(seed))

    private fun custom(
        name: String,
        number: Int?,
        state: ChannelState = ChannelState.On,
        seed: ULong = 1uL,
    ) = LineupChannel(
        ChannelId("custom:$name"),
        null,
        number,
        null,
        state,
        ChannelSource.Custom(rulesOf(name, seed)),
        5L,
    )

    private fun starter(
        key: String,
        number: Int,
    ) = LineupChannel(
        LineupIds.starter(key),
        number,
        null,
        null,
        ChannelState.On,
        ChannelSource.Starter(key, false, rulesOf(key)),
        5L,
    )

    private fun lineupOf(vararg channels: LineupChannel) = Lineup(SERVER, USER, 1, 0, true, channels.toList())

    private fun apply(
        lineup: Lineup,
        edit: LineupEdit,
    ) = LineupEdits.apply(lineup, edit, 99L, catalog)

    private fun applied(
        lineup: Lineup,
        edit: LineupEdit,
    ) = assertIs<EditOutcome.Applied>(apply(lineup, edit)).lineup

    private fun refusal(
        lineup: Lineup,
        edit: LineupEdit,
    ) = assertIs<EditOutcome.Refused>(apply(lineup, edit)).refusal

    private fun Lineup.byId(id: ChannelId) = channels.first { it.id == id }

    private fun Lineup.seedOf(id: ChannelId) = (byId(id).source as ChannelSource.Custom).rules.seed

    private val newId = ChannelId("custom:new")
    private val mine = ChannelId("custom:mine")
    private val hiddenId = ChannelId("custom:hidden")
    private val western = LineupIds.starter("western")
    private val anyRules = rulesOf("x")

    @Test
    fun addCustomMakesAnOnChannelAtTheNumber() {
        val lineup = lineupOf(starter("western", 310))

        val added = applied(lineup, LineupEdit.AddCustom(newId, rulesOf("New"), 650, kids = false)).byId(newId)

        assertEquals(ChannelState.On, added.state)
        assertEquals(650, added.number)
        assertEquals(99L, added.onSinceMs)
        assertNull(added.nameOverride)
        assertNull(added.defaultNumber)
        assertEquals(ChannelSource.Custom(rulesOf("New")), added.source)
    }

    @Test
    fun addCustomRefusesATakenIdAndANumberOrOutOfRange() {
        val lineup = lineupOf(starter("western", 310), custom("mine", 650))

        assertEquals(EditRefusal.IdTaken, refusal(lineup, LineupEdit.AddCustom(mine, anyRules, 700, kids = false)))
        assertEquals(
            EditRefusal.NumberTaken(mine),
            refusal(lineup, LineupEdit.AddCustom(newId, anyRules, 650, kids = false)),
        )
        assertEquals(
            EditRefusal.NumberTaken(western),
            refusal(lineup, LineupEdit.AddCustom(newId, anyRules, 310, kids = false)),
        )
        assertEquals(EditRefusal.OutOfRange, refusal(lineup, LineupEdit.AddCustom(newId, anyRules, 0, kids = false)))
        assertEquals(EditRefusal.OutOfRange, refusal(lineup, LineupEdit.AddCustom(newId, anyRules, 1000, kids = false)))
        assertEquals(EditRefusal.KidsBandOnly, refusal(lineup, LineupEdit.AddCustom(newId, anyRules, 700, kids = true)))
    }

    @Test
    fun addCustomAtAKidNumberIsAKidChannel() {
        val added =
            applied(lineupOf(starter("western", 310)), LineupEdit.AddCustom(newId, rulesOf("Kids"), 5, kids = true))
                .byId(newId)

        assertTrue(KidStatus.isKidChannel(added))
        assertEquals(PoolPolicyKidsOnly, KidStatus.policyOf(added))
    }

    @Test
    fun replaceCustomKeepsStateAndOnSince() {
        val lineup = lineupOf(starter("western", 310), custom("mine", 650, ChannelState.UserHidden))

        val after =
            applied(lineup, LineupEdit.ReplaceCustom(mine, rulesOf("Renamed"), 651, kids = false))
                .byId(mine)

        assertEquals(ChannelState.UserHidden, after.state)
        assertEquals(5L, after.onSinceMs)
        assertEquals(651, after.number)
        assertEquals("Renamed", (after.source as ChannelSource.Custom).rules.name)
    }

    @Test
    fun replaceCustomKeepsTheStoredSeedNotTheCallers() {
        val lineup = lineupOf(starter("western", 310), custom("mine", 650, seed = 7uL))

        val after = applied(lineup, LineupEdit.ReplaceCustom(mine, rulesOf("mine", seed = 9uL), 650, kids = false))

        assertEquals(Seed(7uL), after.seedOf(mine))
    }

    @Test
    fun reseedChangesOnlyTheSeed() {
        val lineup = lineupOf(starter("western", 310), custom("mine", 650, seed = 7uL))
        val id = mine

        val after = applied(lineup, LineupEdit.Reseed(id, Seed(11uL)))

        assertEquals(Seed(11uL), after.seedOf(id))
        val expected = lineup.byId(id).copy(source = ChannelSource.Custom(rulesOf("mine", seed = 11uL)))
        assertEquals(lineup.copy(channels = listOf(lineup.channels[0], expected)), after)
    }

    @Test
    fun reseedOfAChannelThatDoesNotShuffleIsRefused() {
        val inOrder = rulesOf("mine").copy(formatVersion = 2, ordering = Ordering(OrderingMode.InOrder))
        val channel = custom("mine", 650).copy(source = ChannelSource.Custom(inOrder))
        val lineup = lineupOf(starter("western", 310), channel)

        assertEquals(EditRefusal.NotShuffle, refusal(lineup, LineupEdit.Reseed(mine, Seed(11uL))))
    }

    @Test
    fun reseedOfANonCustomOrNoChannelIsRefused() {
        val lineup = lineupOf(starter("western", 310), custom("mine", 650))

        assertEquals(EditRefusal.NotCustom, refusal(lineup, LineupEdit.Reseed(western, Seed(1uL))))
        assertEquals(EditRefusal.UnknownChannel, refusal(lineup, LineupEdit.Reseed(ChannelId("nope"), Seed(1uL))))
    }

    @Test
    fun saveBetweenReshufflesReadAndApplyIsKept() {
        val id = mine
        val lineup = lineupOf(starter("western", 310), custom("mine", 650, seed = 7uL))
        val read = (lineup.byId(id).source as ChannelSource.Custom).rules

        val reshuffled = applied(lineup, LineupEdit.Reseed(id, Seed(11uL)))
        val saved = applied(reshuffled, LineupEdit.ReplaceCustom(id, read.copy(name = "New name"), 650, kids = false))

        assertEquals(Seed(11uL), saved.seedOf(id))
        assertEquals("New name", (saved.byId(id).source as ChannelSource.Custom).rules.name)
    }

    @Test
    fun addCustomWithNoNumberTakesTheFirstFreeInside() {
        val add = LineupEdit.AddCustom(newId, rulesOf("a"), null, kids = false)
        val first = applied(lineupOf(starter("western", 310)), add)
        assertEquals(600, first.byId(newId).number)

        // 601 is retired in this catalog, so the next free one is 602.
        val second = applied(first, LineupEdit.AddCustom(ChannelId("custom:b"), rulesOf("b"), null, kids = false))
        assertEquals(602, second.byId(ChannelId("custom:b")).number)

        // A stale pair: both read the same lineup; the second apply, on the first's result, cannot collide.
        val stale = lineupOf(starter("western", 310))
        val a = applied(stale, LineupEdit.AddCustom(ChannelId("custom:a"), rulesOf("a"), null, kids = false))
        val b = applied(a, LineupEdit.AddCustom(ChannelId("custom:b"), rulesOf("b"), null, kids = false))
        assertEquals(setOf(600, 602), setOf(b.byId(ChannelId("custom:a")).number, b.byId(ChannelId("custom:b")).number))
    }

    @Test
    fun addCustomWithNoNumberAndAFullBandIsNoFreeNumber() {
        val full = lineupOf(starter("western", 310), *customsFilling(LineupDefaults.CUSTOM_NUMBERS))

        assertEquals(EditRefusal.NoFreeNumber, refusal(full, LineupEdit.AddCustom(newId, anyRules, null, kids = false)))
    }

    @Test
    fun addCustomForKidsWithNoNumberTakesTheKidsBand() {
        val lineup = lineupOf(starter("western", 310))
        val added = applied(lineup, LineupEdit.AddCustom(newId, rulesOf("k"), null, kids = true))

        assertEquals(1, added.byId(newId).number)
    }

    @Test
    fun replaceCustomMovesToAFreeNumber() {
        val lineup = lineupOf(starter("western", 310), custom("mine", 650))

        val after = applied(lineup, LineupEdit.ReplaceCustom(mine, rulesOf("mine"), 700, kids = false))

        assertEquals(700, after.byId(mine).number)
    }

    @Test
    fun replaceCustomRefusesATakenNumberWithoutASwap() {
        val lineup = lineupOf(starter("western", 310), custom("mine", 650))

        assertEquals(
            EditRefusal.NumberTaken(western),
            refusal(lineup, LineupEdit.ReplaceCustom(mine, rulesOf("mine"), 310, kids = false)),
        )
    }

    @Test
    fun replaceCustomSwapsWithTheNamedHolder() {
        val lineup = lineupOf(starter("western", 310), custom("mine", 650))

        val after = applied(lineup, LineupEdit.ReplaceCustom(mine, rulesOf("mine"), 310, western, kids = false))

        assertEquals(310, after.byId(mine).number)
        assertEquals(650, after.byId(western).number)
    }

    @Test
    fun replaceCustomRefusesASwapThatSendsAGrownUpHolderIntoTheKidsBand() {
        val lineup = lineupOf(starter("western", 310), custom("kid", 5), custom("mine", 650))
        val kid = ChannelId("custom:kid")

        // The kid channel moves to 650 and the grown-up holder would take 5.
        assertEquals(
            EditRefusal.KidsBandOnly,
            refusal(lineup, LineupEdit.ReplaceCustom(kid, rulesOf("kid"), 650, mine, kids = false)),
        )
    }

    @Test
    fun canSwapAnswersWhatAReplaceWithASwapWouldDoAndChangesNothing() {
        val kid = ChannelId("custom:kid")
        val lineup = lineupOf(starter("western", 310), custom("kid", 5), custom("mine", 650))

        assertTrue(LineupEdits.canSwap(lineup, mine, 310, kids = false))
        assertFalse(LineupEdits.canSwap(lineup, kid, 650, kids = false), "the grown-up holder would enter the band")
        assertFalse(LineupEdits.canSwap(lineup, mine, 5, kids = true), "the kid holder would leave the band")
        assertFalse(LineupEdits.canSwap(lineup, mine, 777, kids = false), "nothing holds it")
        assertFalse(LineupEdits.canSwap(lineup, western, 650, kids = false), "not a custom channel")
    }

    @Test
    fun replaceCustomIntoTheKidsBandMakesAKidChannel() {
        val lineup = lineupOf(starter("western", 310), custom("mine", 650))

        val after = applied(lineup, LineupEdit.ReplaceCustom(mine, rulesOf("mine"), 7, kids = true)).byId(mine)

        assertEquals(7, after.number)
        assertTrue(KidStatus.isKidChannel(after))
        assertEquals(PoolPolicyKidsOnly, KidStatus.policyOf(after))
    }

    @Test
    fun aSwapNeverTakesAKidCustomHolderOutOfTheKidsBand() {
        val kid = ChannelId("custom:kid")
        val lineup = lineupOf(starter("western", 310), custom("kid", 5), custom("mine", 650))

        val edit = LineupEdit.ReplaceCustom(mine, rulesOf("mine"), 5, kid, kids = true)

        assertEquals(EditRefusal.KidsBandOnly, refusal(lineup, edit))
    }

    @Test
    fun aSwapMayMoveAKidStarterHolderBecauseItStaysKid() {
        val kidStarter = starter(StarterCatalog.EVER_KID_SAFE_KEYS.first(), 5)
        val lineup = lineupOf(starter("western", 310), kidStarter, custom("mine", 650))

        val after = applied(lineup, LineupEdit.ReplaceCustom(mine, rulesOf("mine"), 5, kidStarter.id, kids = true))

        assertEquals(650, after.byId(kidStarter.id).number)
        assertTrue(KidStatus.isKidChannel(after.byId(kidStarter.id)))
        assertTrue(KidStatus.isKidChannel(after.byId(mine)))
    }

    @Test
    fun setNumberNeverTakesAKidCustomHolderOutOfTheKidsBand() {
        // The kid-safe starter is outside the band (kid by its key); the custom kid channel holds 5.
        val kidStarter = starter(StarterCatalog.EVER_KID_SAFE_KEYS.first(), 150)
        val lineup = lineupOf(starter("western", 310), kidStarter, custom("kid", 5))

        val edit = LineupEdit.SetNumber(kidStarter.id, 5, ChannelId("custom:kid"))

        assertEquals(EditRefusal.KidsBandOnly, refusal(lineup, edit))
    }

    @Test
    fun setNumberCannotTakeAKidCustomChannelOutOfTheKidsBand() {
        val kid = ChannelId("custom:kid")
        val lineup = lineupOf(starter("western", 310), custom("kid", 5))

        assertEquals(EditRefusal.KidsBandOnly, refusal(lineup, LineupEdit.SetNumber(kid, 700)))
        // Within the band is fine.
        assertEquals(6, applied(lineup, LineupEdit.SetNumber(kid, 6)).byId(kid).number)
    }

    @Test
    fun clearNumberCannotEndAKidCustomChannelsKidStatus() {
        val kid = ChannelId("custom:kid")
        val kidChannel = custom("kid", 5).copy(defaultNumber = 700)
        val lineup = lineupOf(starter("western", 310), kidChannel)

        assertEquals(EditRefusal.KidsBandOnly, refusal(lineup, LineupEdit.ClearNumber(kid)))
    }

    @Test
    fun aKidSafeStarterStaysKidAtAnyNumber() {
        val kidStarter = starter(StarterCatalog.EVER_KID_SAFE_KEYS.first(), 5)
        val lineup = lineupOf(starter("western", 310), kidStarter)

        val after = applied(lineup, LineupEdit.SetNumber(kidStarter.id, 700)).byId(kidStarter.id)

        assertTrue(KidStatus.isKidChannel(after))
    }

    @Test
    fun replaceCustomWithNoNumberKeepsANumberAlreadyInTheRequestedBand() {
        val lineup = lineupOf(starter("western", 310), custom("mine", 650), custom("kid", 5))

        val grownUp = applied(lineup, LineupEdit.ReplaceCustom(mine, rulesOf("mine"), null, kids = false)).byId(mine)
        val kid = applied(lineup, LineupEdit.ReplaceCustom(ChannelId("custom:kid"), rulesOf("kid"), null, kids = true))

        assertEquals(650, grownUp.number)
        assertEquals(5, kid.byId(ChannelId("custom:kid")).number)
    }

    @Test
    fun replaceCustomOutOfTheKidsBandMakesAGrownUpChannel() {
        val lineup = lineupOf(starter("western", 310), custom("kid", 5))
        val kid = ChannelId("custom:kid")

        val after = applied(lineup, LineupEdit.ReplaceCustom(kid, rulesOf("kid"), 700, kids = false)).byId(kid)

        assertEquals(700, after.number)
        assertTrue(!KidStatus.isKidChannel(after))
    }

    @Test
    fun replaceCustomWithNoNumberTakesTheFirstFreeInTheRightBand() {
        val lineup = lineupOf(starter("western", 310), custom("mine", 650), custom("kid", 1))

        val toKids = applied(lineup, LineupEdit.ReplaceCustom(mine, rulesOf("mine"), null, kids = true)).byId(mine)
        val grownUp = LineupEdit.ReplaceCustom(ChannelId("custom:kid"), rulesOf("kid"), null, kids = false)
        val toGrownUp = applied(lineup, grownUp)

        assertEquals(2, toKids.number)
        assertEquals(600, toGrownUp.byId(ChannelId("custom:kid")).number)
        val full = lineupOf(starter("western", 310), custom("mine", 650), *customsFilling(Band.Kids.numbers))
        val noRoom = LineupEdit.ReplaceCustom(mine, rulesOf("mine"), null, kids = true)
        assertEquals(EditRefusal.NoFreeNumber, refusal(full, noRoom))
    }

    @Test
    fun replaceAStarterIsNotCustom() {
        val lineup = lineupOf(starter("western", 310), custom("mine", 650))

        assertEquals(
            EditRefusal.NotCustom,
            refusal(lineup, LineupEdit.ReplaceCustom(western, anyRules, 311, kids = false)),
        )
        val unknown = LineupEdit.ReplaceCustom(ChannelId("nope"), anyRules, 311, kids = false)
        assertEquals(EditRefusal.UnknownChannel, refusal(lineup, unknown))
    }

    @Test
    fun removeCustomDropsTheChannel() {
        val hidden = custom("hidden", 651, ChannelState.UserHidden)
        val lineup = lineupOf(starter("western", 310), custom("mine", 650), hidden)

        val after =
            applied(applied(lineup, LineupEdit.RemoveCustom(mine)), LineupEdit.RemoveCustom(hiddenId))

        assertEquals(listOf(western), after.channels.map { it.id })
    }

    @Test
    fun removeTheLastOnIsRefused() {
        val lineup = lineupOf(custom("mine", 650), custom("hidden", 651, ChannelState.UserHidden))

        assertEquals(EditRefusal.LastOn, refusal(lineup, LineupEdit.RemoveCustom(mine)))
    }

    @Test
    fun removeAnUnusableCustomChannelIsRefusedBecauseItsDataCannotBeRead() {
        val unreadable = ChannelSource.Unusable("{}")
        val newer = LineupChannel(ChannelId("custom:newer"), null, 640, null, ChannelState.On, unreadable, 5L)
        val lineup = lineupOf(starter("western", 310), newer)

        assertEquals(EditRefusal.NotCustom, refusal(lineup, LineupEdit.RemoveCustom(newer.id)))
    }

    @Test
    fun removeAStarterIsNotCustom() {
        val lineup = lineupOf(starter("western", 310), custom("mine", 650))

        assertEquals(EditRefusal.NotCustom, refusal(lineup, LineupEdit.RemoveCustom(western)))
    }

    @Test
    fun firstFreeCustomStartsAt600AndSkipsUsedAndRetired() {
        assertEquals(600, LineupEdits.firstFreeCustom(lineupOf(starter("western", 310)), false, catalog))
        // 600 is used (default or override), 601 is retired.
        val lineup = lineupOf(starter("western", 310), custom("mine", 600), custom("other", 603))
        assertEquals(602, LineupEdits.firstFreeCustom(lineup, false, catalog))
    }

    @Test
    fun freeCustomNumbersListsEveryFreeNumberInOrderAndFirstFreeIsItsHead() {
        // 600 is used (an override), 601 is retired, 603 is used.
        val lineup = lineupOf(starter("western", 310), custom("mine", 600), custom("other", 603))
        val free = LineupEdits.freeCustomNumbers(lineup, false, catalog)

        assertEquals(listOf(602, 604, 605), free.take(3))
        assertEquals(LineupDefaults.CUSTOM_NUMBERS.count() - 3, free.size)
        assertEquals(free.firstOrNull(), LineupEdits.firstFreeCustom(lineup, false, catalog))
        val kidsFull = lineupOf(starter("w", 310), *customsFilling(Band.Kids.numbers))
        assertEquals(emptyList(), LineupEdits.freeCustomNumbers(kidsFull, true, catalog))
    }

    @Test
    fun firstFreeCustomForKidsIsInTheKidsBand() {
        val lineup = lineupOf(starter("western", 310), custom("a", 1), custom("b", 2))

        assertEquals(3, LineupEdits.firstFreeCustom(lineup, true, catalog))
    }

    @Test
    fun firstFreeCustomIsNullWhenTheBandIsFull() {
        val full = lineupOf(starter("western", 310), *customsFilling(LineupDefaults.CUSTOM_NUMBERS))
        assertNull(LineupEdits.firstFreeCustom(full, false, catalog))

        val kidsFull = lineupOf(starter("western", 310), *customsFilling(Band.Kids.numbers))
        assertNull(LineupEdits.firstFreeCustom(kidsFull, true, catalog))
    }

    private fun customsFilling(range: IntRange): Array<LineupChannel> = range.map { custom("c$it", it) }.toTypedArray()

    private companion object {
        val PoolPolicyKidsOnly = tv.stationbreak.core.engine.PoolPolicy.KidsOnly
    }
}
