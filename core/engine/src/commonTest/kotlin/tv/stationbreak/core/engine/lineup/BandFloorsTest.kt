// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.engine.rules
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.rules.AdultMark
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/** #1097: no Adult channel below 100, no Teen below 67, no Family below 34; every number path, and the stored move. */
class BandFloorsTest {
    private val kidKey = StarterCatalog.EVER_KID_SAFE_KEYS.first()
    private val catalog =
        TestCatalog(
            listOf(recipeOf("western", 310), recipeOf("family", 40), recipeOf("teen", 70)),
            retiredNumbers = setOf(100, 601),
        )

    private fun rulesOf(max: String? = null): ChannelRules = rules(listOf(Source.Genre("drama")), maxRating = max)

    private fun custom(
        name: String,
        number: Int?,
        ceiling: String? = null,
        rules: ChannelRules = rulesOf(ceiling),
        id: ChannelId = ChannelId("custom:$name"),
    ) = LineupChannel(id, null, number, null, ChannelState.On, ChannelSource.Custom(rules), 5L)

    private fun starter(
        key: String,
        default: Int?,
        override: Int? = null,
    ) = LineupChannel(
        LineupIds.starter(key),
        default,
        override,
        null,
        ChannelState.On,
        ChannelSource.Starter(key, false, rulesOf()),
        5L,
    )

    private fun leftovers(
        key: String,
        default: Int,
    ) = LineupChannel(
        LineupIds.leftovers(key),
        default,
        null,
        null,
        ChannelState.On,
        ChannelSource.Leftovers(key, null),
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

    private fun Lineup.numberOf(channel: LineupChannel) = channels.first { it.id == channel.id }.number

    private fun add(
        id: String,
        ceiling: String?,
        number: Int?,
        rules: ChannelRules = rulesOf(ceiling),
    ) = LineupEdit.AddCustom(ChannelId("custom:$id"), rules, number, kids = false, customLimit = null)

    private val general = EditRefusal.BelowBand(Band.General)

    private companion object {
        const val DAY = 20_000L
    }

    private val teen = EditRefusal.BelowBand(Band.Teen)
    private val family = EditRefusal.BelowBand(Band.Family)

    // The classification table.
    @Test
    fun theFloorOfEachKindOfChannel() {
        val adultCopy = custom("copy", 600, "PG", AdultMark.marked(rulesOf("PG")))
        val retired =
            LineupChannel(
                ChannelId("starter:gone"),
                40,
                null,
                null,
                ChannelState.On,
                ChannelSource.Custom(rulesOf()),
                5L,
            )
        val table =
            listOf(
                starter(kidKey, 5) to Band.Kids,
                custom("kid", 12) to Band.Kids,
                custom("none", 600) to Band.General,
                custom("pg", 600, "PG") to Band.Family,
                custom("g", 600, "G") to Band.Family,
                custom("pg13", 600, "PG-13") to Band.Teen,
                custom("tv14", 600, "TV-14") to Band.Teen,
                custom("r", 600, "R") to Band.General,
                adultCopy to Band.General,
                starter(AdultMark.AFTER_HOURS_KEY, 504) to Band.General,
                starter("family", 40) to Band.Family,
                starter("teen", 70) to Band.Teen,
                starter("western", 310) to Band.General,
                leftovers("a", 66) to Band.Family,
                leftovers("b", 99) to Band.Teen,
                leftovers("c", 199) to Band.General,
                retired to Band.Family,
            )
        for ((channel, band) in table) {
            assertEquals(band, ChannelFloors.needs(channel, catalog), channel.id.value)
            assertEquals(band.numbers.first, ChannelFloors.of(channel, catalog), channel.id.value)
        }
    }

    @Test
    fun addingACustomChannelBelowItsFloorIsRefused() {
        val lineup = lineupOf(starter("western", 310))
        assertEquals(general, refusal(lineup, add("a", null, 50)))
        assertEquals(general, refusal(lineup, add("a", "R", 99)))
        assertEquals(teen, refusal(lineup, add("a", "TV-14", 66)))
        assertEquals(general, refusal(lineup, add("a", "PG", 40, AdultMark.marked(rulesOf("PG")))))
        for ((ceiling, number) in listOf(null to 101, "R" to 101, "TV-14" to 67, "PG" to 34, "PG" to 650)) {
            assertEquals(number, applied(lineup, add("a", ceiling, number)).numberOf(custom("a", null)), "$ceiling")
        }
    }

    @Test
    fun aNumberThatIsTakenIsStillReportedAsTaken() {
        val holder = custom("held", 40, "PG")
        val lineup = lineupOf(starter("western", 310), holder)
        assertEquals(EditRefusal.NumberTaken(holder.id), refusal(lineup, add("a", "PG", 40)))
    }

    @Test
    fun settingANumberBelowTheFloorIsRefusedForCustomAndBuiltInChannels() {
        val mine = custom("mine", 650)
        val pg = custom("pg", 651, "PG")
        val teenOne = starter("teen", 70)
        val familyOne = starter("family", 40)
        val lineup = lineupOf(starter("western", 310), mine, pg, teenOne, familyOne)
        assertEquals(general, refusal(lineup, LineupEdit.SetNumber(mine.id, 50)))
        assertEquals(general, refusal(lineup, LineupEdit.SetNumber(LineupIds.starter("western"), 99)))
        assertEquals(teen, refusal(lineup, LineupEdit.SetNumber(teenOne.id, 66)))
        // The way up and the band's own first number stay open.
        assertEquals(34, applied(lineup, LineupEdit.SetNumber(pg.id, 34)).numberOf(pg))
        assertEquals(67, applied(lineup, LineupEdit.SetNumber(teenOne.id, 67)).numberOf(teenOne))
        assertEquals(34, applied(lineup, LineupEdit.SetNumber(familyOne.id, 34)).numberOf(familyOne))
        assertEquals(150, applied(lineup, LineupEdit.SetNumber(LineupIds.starter("western"), 150)).channels[0].number)
        // A Family channel moving up to a Teen or Adult number is fine; a grown-up into 1 to 33 is the Kids band's.
        assertEquals(EditRefusal.KidsBandOnly, refusal(lineup, LineupEdit.SetNumber(mine.id, 20)))
        assertEquals(EditRefusal.KidsBandOnly, refusal(lineup, LineupEdit.SetNumber(familyOne.id, 33)))
        assertNotEquals(family, refusal(lineup, LineupEdit.SetNumber(familyOne.id, 33)))
    }

    @Test
    fun clearingANumberBackToTheDefaultKeepsTheFloor() {
        val western = starter("western", 310, override = 400)
        val lineup = lineupOf(western)
        assertEquals(310, applied(lineup, LineupEdit.ClearNumber(western.id)).numberOf(western))
    }

    @Test
    fun aSwapIsRefusedWhenEitherChannelWouldLandBelowItsFloor() {
        val adult = custom("adult", 700)
        val pg = custom("pg", 40, "PG")
        val pg2 = custom("pg2", 50, "PG")
        val lineup = lineupOf(starter("western", 310), adult, pg, pg2)
        // The edited channel would land below: the adult one at the Family one's number.
        assertEquals(general, refusal(lineup, LineupEdit.SetNumber(adult.id, 40, swapWith = pg.id)))
        // The holder would land below: the Family one at the adult one's number sends the adult one to 40.
        assertEquals(general, refusal(lineup, LineupEdit.SetNumber(pg.id, 700, swapWith = adult.id)))
        // Two Family channels trade freely.
        val after = applied(lineup, LineupEdit.SetNumber(pg.id, 50, swapWith = pg2.id))
        assertEquals(50, after.numberOf(pg))
        assertEquals(40, after.numberOf(pg2))
    }

    @Test
    fun raisingACeilingOnAPlacedChannelIsRefusedUnlessItMovesUp() {
        val pg = custom("pg", 40, "PG")
        val lineup = lineupOf(starter("western", 310), pg)

        fun replace(
            ceiling: String?,
            number: Int?,
        ) = LineupEdit.ReplaceCustom(pg.id, rulesOf(ceiling), number, kids = false)
        // Family at 40 lifted to R (or no ceiling), number kept or not given: Adult below 100.
        assertEquals(general, refusal(lineup, replace("R", null)))
        assertEquals(general, refusal(lineup, replace(null, 40)))
        assertEquals(teen, refusal(lineup, replace("TV-14", 60)))
        // The same edit that moves it to an allowed number is applied.
        assertEquals(700, applied(lineup, replace("R", 700)).numberOf(pg))
        assertEquals(67, applied(lineup, replace("TV-14", 67)).numberOf(pg))
        // Lowering a ceiling is always fine.
        val adult = custom("adult", 700, "R")
        val low = LineupEdit.ReplaceCustom(adult.id, rulesOf("PG"), null, kids = false)
        assertEquals(700, applied(lineupOf(starter("western", 310), adult), low).numberOf(adult))
    }

    @Test
    fun aCopyOfAnAdultBuiltInStaysAboveOneHundredWhateverItsCeiling() {
        val copy = custom("copy", 650, "PG", AdultMark.marked(rulesOf("PG")))
        val lineup = lineupOf(starter("western", 310), copy)
        assertEquals(general, refusal(lineup, LineupEdit.SetNumber(copy.id, 45)))
        // Saved with a kids switch and a Kids-band number, it is still adult: refused.
        val replace = LineupEdit.ReplaceCustom(copy.id, AdultMark.marked(rulesOf("PG")), 5, kids = true)
        assertEquals(general, refusal(lineup, replace))
    }

    @Test
    fun kidChannelsAreUnchanged() {
        val kid = custom("kid", 12)
        val lineup = lineupOf(starter("western", 310), kid)
        assertEquals(3, applied(lineup, LineupEdit.SetNumber(kid.id, 3)).numberOf(kid))
        assertEquals(EditRefusal.KidsBandOnly, refusal(lineup, LineupEdit.SetNumber(kid.id, 50)))
        // The explicit switch (Kids off) is the one way out, as before.
        val off = LineupEdit.ReplaceCustom(kid.id, rulesOf(), 700, kids = false)
        assertEquals(700, applied(lineup, off).numberOf(kid))
        // And a new kid channel in the Kids band, whatever its ceiling.
        val addKid = LineupEdit.AddCustom(ChannelId("custom:k2"), rulesOf(), 4, kids = true, customLimit = null)
        assertEquals(4, applied(lineup, addKid).channels.last().number)
    }

    // The stored move.
    private fun migrate(vararg channels: LineupChannel) = FloorMigration.apply(channels.toList(), catalog, DAY).channels

    private fun List<LineupChannel>.numberOf(channel: LineupChannel) = first { it.id == channel.id }.number

    @Test
    fun aStoredCustomBelowItsFloorMovesUpOnceToTheFirstFreeNumberInItsBand() {
        val adult = custom("adult", 50)
        val teenCustom = custom("teen", 40, "TV-14")
        val heldBy = custom("held", 67, "TV-14")
        val old = migrate(starter("western", 310), adult, teenCustom, heldBy)
        // An Adult channel goes to 600-899 (601 is retired); 67 is held, so the Teen one takes 68.
        assertEquals(600, old.numberOf(adult))
        assertEquals(68, old.numberOf(teenCustom))
        assertEquals(67, old.numberOf(heldBy))
    }

    @Test
    fun theStoredMoveIsOnceNeverDownNeverDeletedAndNeverOnAHeldOrRetiredNumber() {
        val offenders = (1..4).map { custom("o$it", 60 + it) } + custom("pg", 40, "PG")
        val others = (101..110).map { custom("c$it", it) } + starter("western", 310, override = 111)
        val all = offenders + others
        val once = FloorMigration.apply(all, catalog, DAY).channels
        assertEquals(all.size, once.size)
        assertEquals(all.map { it.id }, once.map { it.id })
        val numbers = once.mapNotNull { it.number }
        assertEquals(numbers.size, numbers.toSet().size, "no number twice")
        assertEquals(false, 601 in numbers, "a retired number")
        for ((before, after) in all.zip(once)) assertEquals(true, after.number!! >= before.number!!, before.id.value)
        assertEquals(40, once.numberOf(offenders.last()), "a Family channel at 40 is not an offender")
        assertEquals(once, FloorMigration.apply(once, catalog, DAY).channels, "a second run changes nothing")
    }

    // A built-in a viewer's number put below its band (#1078 allowed it) goes back to its default, and says so.
    @Test
    fun aBuiltInBelowItsBandGoesBackToItsDefaultNumber() {
        val western = starter("western", 310, override = 98)
        val teenOne = starter("teen", 70, override = 50)
        val result = FloorMigration.apply(listOf(western, teenOne), catalog, DAY)
        assertEquals(null, result.channels.first { it.id == western.id }.numberOverride)
        assertEquals(310, result.channels.first { it.id == western.id }.number)
        assertEquals(70, result.channels.first { it.id == teenOne.id }.number)
        assertEquals(
            setOf(MovedNotice(western.id, 98, 310, DAY), MovedNotice(teenOne.id, 50, 70, DAY)),
            result.notices.toSet(),
        )
        val again = FloorMigration.apply(result.channels, catalog, DAY)
        assertEquals(result.channels, again.channels)
        assertEquals(emptyList(), again.notices, "no second notice")
    }

    @Test
    fun aBuiltInWhoseDefaultIsHeldTakesTheFirstFreeNumberInItsBandAtOrAboveTheFloor() {
        val western = starter("western", 310, override = 50)
        val holder = custom("holder", 310)
        val result = FloorMigration.apply(listOf(western, holder), catalog, DAY).channels
        // Its own recipe band is 300-399: 310 is held, 300 is free.
        assertEquals(300, result.numberOf(western))
        assertEquals(310, result.numberOf(holder))
    }

    @Test
    fun aRetiredBuiltInFallsBackToTheCustomRangeOnlyWhenItsBandIsFull() {
        val retired =
            LineupChannel(
                ChannelId("starter:gone"),
                150,
                60,
                null,
                ChannelState.On,
                ChannelSource.Custom(rulesOf()),
                5L,
            )
        assertEquals(
            150,
            FloorMigration
                .apply(listOf(retired), catalog, DAY)
                .channels
                .single()
                .number,
        )
        val full = (100..199).filter { it != 150 }.map { custom("g$it", it) } + custom("holder", 150)
        val moved = FloorMigration.apply(full + retired, catalog, DAY).channels.numberOf(retired)
        assertEquals(600, moved)
    }

    @Test
    fun aChannelWhoseBandAndTheCustomRangeAreFullStillMovesUpAboveItsFloor() {
        val full = (67..99).map { custom("t$it", it, "TV-14") } + (600..899).map { custom("c$it", it) }
        val offender = custom("low", 40, "TV-14")
        val moved = FloorMigration.apply(full + offender, catalog, DAY).channels.numberOf(offender)!!
        assertEquals(true, moved >= 67 && moved != 100 && moved != 601, "$moved")
    }

    // A starter with no default number (its band was full at the first choice) is placed by its recipe's band.
    @Test
    fun aNumberlessStarterSwitchedOnIsHeldToItsRecipesBandFloor() {
        val family = starter("family", null).copy(state = ChannelState.Shelf)
        val teenOne = starter("teen", null).copy(state = ChannelState.Shelf)
        val both = applied(lineupOf(family, teenOne), LineupEdit.SwitchOn(family.id))
        val on = applied(both, LineupEdit.SwitchOn(teenOne.id))
        assertEquals(34, on.numberOf(family))
        assertEquals(67, on.numberOf(teenOne))
        assertEquals(41, applied(on, LineupEdit.SetNumber(family.id, 41)).numberOf(family))
        assertEquals(68, applied(on, LineupEdit.SetNumber(teenOne.id, 68)).numberOf(teenOne))
        assertEquals(EditRefusal.KidsBandOnly, refusal(on, LineupEdit.SetNumber(family.id, 33)))
        assertEquals(teen, refusal(on, LineupEdit.SetNumber(teenOne.id, 66)))
        // The two swap numbers inside the safe bands without the Adult line: Family takes Teen's 67, Teen then 34.
        val swap = LineupEdit.SetNumber(family.id, 67, swapWith = teenOne.id)
        assertEquals(teen, refusal(on, swap), "the Teen channel would land at 34")
    }

    @Test
    fun aBandWithNoFreeNumberSendsTheChannelToTheCustomRange() {
        val full = (67..99).map { custom("t$it", it, "TV-14") }
        val offender = custom("low", 40, "TV-14")
        val moved = FloorMigration.apply(full + offender, catalog, DAY).channels
        assertEquals(600, moved.numberOf(offender))
    }

    @Test
    fun theStoredMoveLeavesKidsNumberlessAndInBandChannelsAlone() {
        val kid = custom("kid", 12)
        val shelf = custom("shelf", null)
        val inBand = listOf(starter("family", 40, override = 50), starter("teen", 70))
        val unchanged = listOf(kid, shelf, starter(kidKey, 5)) + inBand
        assertEquals(unchanged, FloorMigration.apply(unchanged, catalog, DAY).channels)
    }

    @Test
    fun theMovedNumberIsTheOverrideOrTheDefaultWhicheverTheChannelHeld() {
        val byDefault = custom("d", null).copy(defaultNumber = 50)
        val moved = migrate(byDefault).first()
        assertEquals(600, moved.defaultNumber)
        assertEquals(null, moved.numberOverride)
    }

    @Test
    fun thePlannerBringsAStoredLineupInOnItsNextRefresh() {
        val keys = listOf("alpha" to 110, "bravo" to 120, "charlie" to 210, "delta" to 310, "echo" to 410)
        val plain = TestCatalog(keys.map { (k, n) -> recipeOf(k, n) } + recipeOf("late", 520, optIn = true))
        val library = libraryOf(keys.associate { it.first to 30 } + ("late" to 30))
        val first = planOf(null, library, plain)
        val low = custom("low", 50)
        val stored = first.copy(channels = first.channels + low)
        val next = planOf(stored, library, plain, nowMs = 2_000L)
        assertEquals(600, next.channels.first { it.id == low.id }.number)
        assertEquals(stored.channels.size, next.channels.size)
        assertEquals(next.channels, planOf(next, library, plain, nowMs = 2_000L).channels, "once")
        assertEquals(next.channels, planOf(next, library, plain, nowMs = 3_000L).channels, "and again later")
    }

    @Test
    fun aMovedChannelIsNotedOnceAndNoChannelStaysBelowItsFloor() {
        val keys = listOf("alpha" to 110, "bravo" to 120, "charlie" to 210, "delta" to 310, "echo" to 410)
        val plain = TestCatalog(keys.map { (k, n) -> recipeOf(k, n) } + recipeOf("late", 520, optIn = true))
        val library = libraryOf(keys.associate { it.first to 30 } + ("late" to 30))
        val first = planOf(null, library, plain)
        val low = custom("low", 50)
        val alpha = first.channel("alpha").copy(numberOverride = 98)
        val stored = first.copy(channels = first.channels.filter { it.id != alpha.id } + alpha + low)
        val next = planOf(stored, library, plain, nowMs = 2_000L)
        assertEquals(setOf(low.id, alpha.id), next.movedNotices.map { it.id }.toSet())
        assertEquals(110, next.channel("alpha").number)
        assertEquals(true, next.channels.all { ChannelFloors.of(it, plain) <= (it.number ?: Int.MAX_VALUE) })
        val later = planOf(next, library, plain, nowMs = 3_000L)
        assertEquals(next.movedNotices, later.movedNotices, "no second notice")
    }

    @Test
    fun aResetKeepsTheViewersChannelsInLineWithTheFloors() {
        val keys = listOf("alpha" to 110, "bravo" to 120, "charlie" to 210, "delta" to 310, "echo" to 410)
        val plain = TestCatalog(keys.map { (k, n) -> recipeOf(k, n) } + recipeOf("late", 520, optIn = true))
        val library = libraryOf(keys.associate { it.first to 30 } + ("late" to 30))
        val low = custom("low", 50)
        val day = nextDayStart(1_000L)
        val reset = LineupPlanner.plan(null, SERVER, USER, 0, library, NO_NAMES, 1_000L, day, plain, kept = listOf(low))
        assertEquals(600, reset.channels.first { it.id == low.id }.number)
    }

    @Test
    fun aChannelThatStaysPutKeepsItsNumberHeldForTheOthers() {
        // Adult at 67 finds nothing free from 100 up (all full), so it stays; the Teen one at 40 must not take 67.
        val full = (100..999).map { custom("g$it", it) }
        val stuck = custom("stuck", 67)
        val teenCustom = custom("teen", 40, "TV-14")
        val numbers = FloorMigration.apply(full + stuck + teenCustom, catalog, DAY).channels.mapNotNull { it.number }
        assertEquals(numbers.size, numbers.toSet().size, "no number twice")
        assertEquals(68, FloorMigration.apply(full + stuck + teenCustom, catalog, DAY).channels.numberOf(teenCustom))
    }

    @Test
    fun aCustomOffenderDoesNotTakeABuiltInOffendersDefault() {
        // The Adult custom at 40 is processed first and, with 600-899 and 100-319 full, would reach 320: the default
        // the built-in (not in the catalog, at 50) is going back to.
        val oldTimer = starter("oldtimer", 320, override = 50)
        val adult = custom("adult", 40)
        val full = (100..319).map { custom("g$it", it) } + (600..899).map { custom("c$it", it) }
        val result = FloorMigration.apply(full + oldTimer + adult, catalog, DAY).channels
        assertEquals(320, result.numberOf(oldTimer))
        assertNotEquals(320, result.numberOf(adult))
    }

    // The built-in's default (70) is held only by an offender (the Adult custom at 70): once that has moved on, the
    // built-in returns to it rather than keeping its override. Never down, no number twice, and idempotent.
    @Test
    fun aBuiltInReturnsToADefaultOnlyAnotherOffenderWasHolding() {
        val teenOne = starter("teen", 70, override = 40)
        val adult = custom("adult", 70)
        val result = FloorMigration.apply(listOf(teenOne, adult), catalog, DAY)
        assertEquals(70, result.channels.numberOf(teenOne))
        assertEquals(null, result.channels.first { it.id == teenOne.id }.numberOverride)
        assertEquals(true, result.channels.numberOf(adult)!! >= 100)
        assertEquals(
            result.channels
                .mapNotNull { it.number }
                .toSet()
                .size,
            2,
            "no number twice",
        )
        val again = FloorMigration.apply(result.channels, catalog, DAY)
        assertEquals(result.channels, again.channels)
        assertEquals(emptyList(), again.notices)
    }

    private fun plannerSetup(): Triple<TestCatalog, tv.stationbreak.core.model.RuleSnapshot, Lineup> {
        val keys = listOf("alpha" to 110, "bravo" to 120, "charlie" to 210, "delta" to 310, "echo" to 410)
        val plain = TestCatalog(keys.map { (k, n) -> recipeOf(k, n) } + recipeOf("late", 520, optIn = true))
        val library = libraryOf(keys.associate { it.first to 30 } + ("late" to 30))
        return Triple(plain, library, planOf(null, library, plain))
    }

    // A notice lives KEEP_DAYS days: kept on the last of them, dropped the day after, and with it the file's v8.
    @Test
    fun aNoticeIsKeptForSixtyDaysAndThenDropped() {
        val (plain, library, first) = plannerSetup()
        val alpha = first.channel("alpha").id
        val made = 100L
        val stored = first.copy(movedNotices = listOf(MovedNotice(alpha, 50, 110, made)))

        fun at(day: Long) = planOf(stored, library, plain, nowMs = day * DAY_MS + DAY_START_MS).movedNotices
        assertEquals(60L, MovedNotice.KEEP_DAYS, "sixty days, so a viewer away for weeks is still told (#1107)")
        assertEquals(1, at(made + 60).size, "the boundary day")
        assertEquals(1, at(made + 14 + 1).size, "past the old fourteen")
        assertEquals(emptyList(), at(made + 61), "the day after the sixtieth")
        assertEquals(1, at(made).size, "a fresh one")
    }

    @Test
    fun aResetKeepsThePendingNoticesOfTheChannelsItKeeps() {
        val (plain, library, _) = plannerSetup()
        val mine = custom("mine", 650)
        val gone = ChannelId("custom:gone")
        val notices = listOf(MovedNotice(mine.id, 50, 650, 0L), MovedNotice(gone, 40, 600, 0L))
        val day = nextDayStart(1_000L)
        val reset =
            LineupPlanner.plan(
                null,
                SERVER,
                USER,
                0,
                library,
                NO_NAMES,
                1_000L,
                day,
                plain,
                kept = listOf(mine),
                movedNotices = notices,
            )
        assertEquals(listOf(MovedNotice(mine.id, 50, 650, 0L)), reset.movedNotices)
    }
}
