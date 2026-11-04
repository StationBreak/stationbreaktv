// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.CurationBasis
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.LineupIds
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.RuleCollection
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.rules.Ordering
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The day of a starter takes its rules, its sources and the household flag from the day, not from now (spec 23). */
class BoundaryDaysTest {
    private val days = LineupDays(TestCatalog)
    private val noon = NOON.toEpochMilliseconds()
    private val dayMs = 86_400_000L

    // Today began at 06:00 UTC; the next day starts a day later.
    private val boundary = noon - 6 * 3_600_000L + dayMs
    private val tomorrow = noon + dayMs

    private val mine = id(900)
    private val mineFilms = (810..812).map { movie(it, "Drama", rating = "G") }
    private val items = fillingLibrary() + mineFilms

    private fun collectionOf(name: String?) =
        RuleCollection(mine, mineFilms.map { it.item.id }.toSet(), emptySet(), emptySet(), emptySet(), name)

    private fun snapshotOf(
        cutoff: Long,
        name: String? = "Box Set 1",
        synced: Boolean = true,
    ) = RuleSnapshot(
        cutoff,
        setOf(LIBRARY_ID),
        items,
        emptyMap(),
        mapOf(mine to collectionOf(name)),
        collectionsSynced = synced,
    )

    private fun built(
        lineup: Lineup,
        atMs: Long,
        name: String? = "Box Set 1",
        synced: Boolean = true,
    ): List<ChannelDay> {
        val scheduled = days.scheduled(lineup, atMs, null)
        val snapshots = scheduled.map { it.cutoffMs }.distinct().associateWith { snapshotOf(it, name, synced) }
        return days.days(lineup, scheduled, snapshots)
    }

    private fun pool(
        days: List<ChannelDay>,
        id: String,
    ) = days.single { it.channel.id.value == id }.pool.map { it.id }

    private fun swapped(
        lineup: Lineup,
        key: String,
        change: (ChannelSource.Starter) -> ChannelSource.Starter,
    ): Lineup =
        lineup.copy(
            channels =
                lineup.channels.map {
                    val source = it.source
                    if (it.id == LineupIds.starter(key) && source is ChannelSource.Starter) {
                        it.copy(source = change(source))
                    } else {
                        it
                    }
                },
        )

    private val comedy = rules("Laughs", Source.Genre("Comedy"))
    private val crime = rules("Laughs", Source.Genre("Crime"))

    private val before =
        lineupOf(
            starter("laughs", 110, ChannelState.On, comedy),
            leftovers("odds-and-ends", 199, null),
        )

    private val pending = swapped(before, "laughs") { it.copy(rules = crime).withPrevious(comedy, boundary) }

    @Test
    fun todaysBlocksSurviveARulesChange() {
        val id = LineupIds.starter("laughs").value
        assertEquals(pool(built(before, noon), id), pool(built(pending, noon), id))
        val after = lineupOf(starter("laughs", 110, ChannelState.On, crime), leftovers("odds-and-ends", 199, null))
        assertEquals(pool(built(after, tomorrow), id), pool(built(pending, tomorrow), id))
        assertNotEquals(pool(built(pending, noon), id), pool(built(pending, tomorrow), id))
    }

    @Test
    fun settingsComeFromTheDaysRules() {
        val rotation = comedy.copy(formatVersion = 2, ordering = Ordering(OrderingMode.Rotation))
        val lineup = swapped(before, "laughs") { it.copy(rules = comedy).withPrevious(rotation, boundary) }
        val scheduled = days.scheduled(lineup, noon, null).single { it.channel.id == LineupIds.starter("laughs") }
        assertEquals(OrderingMode.Rotation, scheduled.settings.orderingMode)
        val next = days.scheduled(lineup, tomorrow, null).single { it.channel.id == LineupIds.starter("laughs") }
        assertEquals(OrderingMode.Shuffle, next.settings.orderingMode)
    }

    @Test
    fun householdOffOnACustomChannelChangesNothing() {
        val custom = channel("mine", 600, ChannelState.On, ChannelSource.Custom(rules("Mine", Source.Collection(mine))))
        val on = lineupOf(custom, leftovers("odds-and-ends", 199, null))
        val off = on.copy(useMyCollections = false)
        assertEquals(pool(built(on, noon), "mine"), pool(built(off, noon), "mine"))
        assertTrue(pool(built(off, noon), "mine").isNotEmpty())
    }

    private val onlyMine =
        lineupOf(
            starter("mine", 110, ChannelState.On, rules("Mine", Source.Collection(mine))),
            leftovers("odds-and-ends", 199, null),
        )

    private fun mineDay(all: List<ChannelDay>) = all.single { it.channel.id == LineupIds.starter("mine") }

    @Test
    fun noSourceLeftIsHouseholdCollectionsOff() {
        val off = mineDay(built(onlyMine.copy(useMyCollections = false), noon))
        assertEquals(HiddenReason.HouseholdCollectionsOff, off.hidden)
        assertTrue(off.pool.isEmpty())
        val on = mineDay(built(onlyMine, noon))
        assertNull(on.hidden)
        // A collection a server made ends in " Collection": it stays.
        val server = built(onlyMine.copy(useMyCollections = false), noon, name = "Box Set 1 Collection")
        assertNull(mineDay(server).hidden)
        // The names are not known before a full pass: that is the reason given, not the setting.
        val unsynced = built(onlyMine.copy(useMyCollections = false), noon, synced = false)
        assertEquals(HiddenReason.CollectionsNotSynced, mineDay(unsynced).hidden)
    }

    @Test
    fun theLeftoversWithheldPathFollowsTheDaysHousehold() {
        // A hidden starter withholds what it would air from the leftovers: its household collection, flag on.
        val hidden =
            lineupOf(
                starter("laughs", 110, ChannelState.On, comedy),
                starter("mine", 120, ChannelState.UserHidden, rules("Mine", Source.Collection(mine))),
                leftovers("odds-and-ends", 199, null),
            )
        val leftoversId = LineupIds.leftovers("odds-and-ends").value
        val withheld = pool(built(hidden, noon), leftoversId)
        assertTrue(mineFilms.none { it.item.id in withheld }, "the hidden channel's films are withheld")
        val off = pool(built(hidden.copy(useMyCollections = false), noon), leftoversId)
        assertTrue(mineFilms.all { it.item.id in off }, "with the flag off the hidden channel withholds nothing")
    }

    @Test
    fun aHouseholdFlagChangeKeepsTodayUntilItsBoundary() {
        val flipped = onlyMine.copy(useMyCollections = false, curationBasis = CurationBasis(boundary, emptySet(), true))
        val id = LineupIds.starter("mine").value
        assertEquals(pool(built(onlyMine, noon), id), pool(built(flipped, noon), id))
        assertTrue(pool(built(flipped, tomorrow), id).isEmpty())
    }

    @Test
    fun aLeavingChannelShowsAsShelfOnceDue() {
        val laughs = before.channels.first { it.id == LineupIds.starter("laughs") }
        val leaving: LineupChannel = laughs.copy(leavesAtMs = boundary)
        val lineup = before.copy(channels = before.channels.map { if (it.id == leaving.id) leaving else it })
        assertTrue(days.scheduled(lineup, noon, null).any { it.channel.id == leaving.id }, "on until the boundary")
        assertTrue(days.scheduled(lineup, boundary - 1, null).any { it.channel.id == leaving.id })
        assertTrue(days.scheduled(lineup, boundary, null).none { it.channel.id == leaving.id }, "gone once due")
        assertEquals(ChannelStatus.On, leaving.summary(TestCatalog, PlayKey("k"), boundary - 1).state)
        assertEquals(ChannelStatus.Shelf, leaving.summary(TestCatalog, PlayKey("k"), boundary).state)
    }
}
